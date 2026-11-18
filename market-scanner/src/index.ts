export interface Env {
  ALERT_COOLDOWN: KVNamespace;
  FIREBASE_PROJECT_ID: string;
  FCM_CLIENT_EMAIL: string;
  FCM_PRIVATE_KEY: string;
}

type Kline = [number, string, string, string, string, string, ...unknown[]];

const WATCH_LIMIT = 18;
const SCORE_THRESHOLD = 80;
const COOLDOWN_MINUTES = 60;

export default {
  async scheduled(_event: ScheduledEvent, env: Env, ctx: ExecutionContext) {
    ctx.waitUntil(scanMarkets(env));
  },
  async fetch(_request: Request, _env: Env): Promise<Response> {
    return new Response("Crypto Hunter market scanner is running.", { status: 200 });
  }
};

async function scanMarkets(env: Env): Promise<void> {
  try {
    const tickersRes = await fetch("https://api.binance.com/api/v3/ticker/24hr");
    if (!tickersRes.ok) throw new Error(`Binance ticker API: ${tickersRes.status}`);
    const tickers = await tickersRes.json() as Array<{symbol:string; quoteVolume:string; lastPrice:string; priceChangePercent:string}>;
    const top = tickers
      .filter(t => t.symbol.endsWith("USDT") && !t.symbol.includes("UP") && !t.symbol.includes("DOWN"))
      .filter(t => Number(t.quoteVolume) >= 5_000_000 && Number(t.lastPrice) > 0)
      .sort((a,b) => Number(b.quoteVolume)-Number(a.quoteVolume))
      .slice(0, WATCH_LIMIT);

    const results = await Promise.allSettled(top.map(t => analyze(t.symbol, env)));
    const alerts = results.flatMap(r => r.status === "fulfilled" && r.value ? [r.value] : []);
    for (const a of alerts) console.log("QUALIFIED_SIGNAL", JSON.stringify(a));
    console.log(`Scan complete: ${top.length} symbols checked, ${alerts.length} qualified candidates.`);
  } catch (err) {
    console.error("Market scan failed:", err instanceof Error ? err.message : String(err));
  }
}

async function analyze(symbol: string, env: Env) {
  const res = await fetch(`https://api.binance.com/api/v3/klines?symbol=${symbol}&interval=5m&limit=60`);
  if (!res.ok) return null;
  const rows = await res.json() as Kline[];
  if (rows.length < 30) return null;
  // Ignore the still-forming candle for scoring.
  const closed = rows.slice(0, -1);
  const close = closed.map(k => Number(k[4]));
  const high = closed.map(k => Number(k[2]));
  const low = closed.map(k => Number(k[3]));
  const volume = closed.map(k => Number(k[5]));
  const last = close.length - 1;
  const price = close[last];
  const ema20 = ema(close, 20), ema50 = ema(close, 50);
  const rsiVal = rsi(close, 14);
  const macdOk = ema(close, 12) > ema(close, 26);
  const avgVol = volume.slice(-21, -1).reduce((a,b)=>a+b,0) / 20;
  const volRatio = avgVol > 0 ? volume[last] / avgVol : 0;
  const previousHigh = Math.max(...high.slice(-21, -1));
  const previousLow = Math.min(...low.slice(-21, -1));
  const upBreak = price > previousHigh;
  const downBreak = price < previousLow;
  const upMomentum = price > close[last-3] * 1.005;
  const downMomentum = price < close[last-3] * 0.995;
  const volPts = volRatio >= 1.8 ? 20 : volRatio >= 1.3 ? 10 : 0;
  const upScore = (ema20>ema50?20:0) + (macdOk?15:0) + (rsiVal>=50&&rsiVal<=70?10:0) + volPts + (upBreak?20:0) + (upMomentum?15:0);
  const downScore = (ema20<ema50?20:0) + (!macdOk?15:0) + (rsiVal>=30&&rsiVal<=50?10:0) + volPts + (downBreak?20:0) + (downMomentum?15:0);
  const direction = upScore >= downScore ? "UP" : "DOWN";
  const score = Math.max(upScore, downScore);
  if (score < SCORE_THRESHOLD) return null;
  const key = `signal:${symbol}:${direction}`;
  const prior = await env.ALERT_COOLDOWN.get(key);
  if (prior) return null;
  await env.ALERT_COOLDOWN.put(key, String(Date.now()), { expirationTtl: COOLDOWN_MINUTES * 60 });
  return { symbol, direction, score, price, rsi: Number(rsiVal.toFixed(1)), volumeRatio: Number(volRatio.toFixed(2)), timeframe: "5m", at: new Date().toISOString(), note: "Candidate signal only; not a trade recommendation." };
}

function ema(values: number[], period: number): number {
  if (values.length < period) return values.reduce((a,b)=>a+b,0) / Math.max(1, values.length);
  const k = 2/(period+1);
  let value = values.slice(0, period).reduce((a,b)=>a+b,0)/period;
  for (const x of values.slice(period)) value = x*k + value*(1-k);
  return value;
}

function rsi(values: number[], period: number): number {
  if (values.length <= period) return 50;
  const changes = values.slice(1).map((v,i)=>v-values[i]);
  let gain = changes.slice(0,period).filter(v=>v>0).reduce((a,b)=>a+b,0)/period;
  let loss = -changes.slice(0,period).filter(v=>v<0).reduce((a,b)=>a+b,0)/period;
  for (const d of changes.slice(period)) {
    gain=(gain*(period-1)+Math.max(d,0))/period;
    loss=(loss*(period-1)+Math.max(-d,0))/period;
  }
  if (loss===0) return 100;
  return 100-100/(1+gain/loss);
}
