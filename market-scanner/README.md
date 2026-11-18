# Crypto Hunter market scanner (Cloudflare Worker)

This worker is the scheduled backend scaffold for Crypto Hunter. It scans liquid Binance Spot USDT markets every five minutes, ranks possible 5-minute directional signals, and applies a 60-minute per-symbol/direction cooldown using Cloudflare KV.

## Current status
- Scanner logs qualified candidates only; it does **not** send push notifications yet.
- Public Binance market data only; no trading or API keys.
- Scheduled execution requires Cloudflare Worker Cron Triggers and KV binding.
- Signals are heuristic candidates, not guaranteed moves or financial advice.

## Deploy
1. Create a free Cloudflare account and Worker.
2. Create a KV namespace named for alert cooldown storage.
3. Replace the KV namespace ID in `wrangler.toml`.
4. Deploy using Wrangler CLI (`npx wrangler deploy`) from this folder.
5. Review Worker logs to confirm scheduled scans.

## Next
Connect Firebase Cloud Messaging (FCM) using a Firebase service-account credential stored as a Worker secret, never committed to Git. Then send only qualified alerts to registered device tokens.
