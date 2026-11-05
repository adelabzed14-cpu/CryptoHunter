package com.cryptohunter.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import kotlin.math.abs
import kotlin.math.max

private val Bg = Color(0xFF090D14)
private val Panel = Color(0xFF151D29)
private val Gold = Color(0xFFE5B94F)
private val Green = Color(0xFF26C281)
private val Red = Color(0xFFEF6262)
private val Muted = Color(0xFF9AA8BA)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CryptoHunterApp() }
    }
}

data class CoinSignal(
    val symbol: String, val price: Double, val change: Double, val score: Int,
    val signal: String, val entry: Double, val stop: Double,
    val tp1: Double, val tp2: Double, val tp3: Double, val reason: String
)

class MarketRepository {
    private val client = OkHttpClient()
    private fun get(url: String): String {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Binance HTTP ${response.code}")
            return response.body?.string() ?: error("Empty response")
        }
    }

    suspend fun scan(futures: Boolean): List<CoinSignal> {
        val base = if (futures) "https://fapi.binance.com" else "https://api.binance.com"
        val tickerPath = if (futures) "/fapi/v1/ticker/24hr" else "/api/v3/ticker/24hr"
        val tickers = JSONArray(get(base + tickerPath))
        val candidates = mutableListOf<Triple<String, Double, Double>>()
        for (i in 0 until tickers.length()) {
            val t = tickers.getJSONObject(i)
            val symbol = t.optString("symbol")
            if (!symbol.endsWith("USDT")) continue
            if (symbol.contains("UPUSDT") || symbol.contains("DOWNUSDT")) continue
            val quoteVol = t.optString("quoteVolume").toDoubleOrNull() ?: 0.0
            val price = t.optString("lastPrice").toDoubleOrNull() ?: continue
            val change = t.optString("priceChangePercent").toDoubleOrNull() ?: 0.0
            if (quoteVol > 1_000_000 && price > 0) candidates.add(Triple(symbol, price, change))
        }
        return candidates.sortedByDescending { abs(it.third) }.take(35).mapNotNull { (symbol, price, change) ->
            try {
                val path = if (futures) "/fapi/v1/klines" else "/api/v3/klines"
                val arr = JSONArray(get("$base$path?symbol=$symbol&interval=1h&limit=120"))
                val closes = (0 until arr.length()).map { arr.getJSONArray(it).getString(4).toDouble() }
                val highs = (0 until arr.length()).map { arr.getJSONArray(it).getString(2).toDouble() }
                val lows = (0 until arr.length()).map { arr.getJSONArray(it).getString(3).toDouble() }
                val rsi = Indicators.rsi(closes)
                val ema20 = Indicators.ema(closes, 20)
                val ema50 = Indicators.ema(closes, 50)
                val macd = Indicators.macdBullish(closes)
                val atr = Indicators.atr(highs, lows, closes)
                var score = 50
                if (rsi in 42.0..65.0) score += 15
                if (rsi < 30) score += 12
                if (ema20 > ema50) score += 15
                if (macd) score += 10
                if (change > 0) score += 5
                score = score.coerceIn(0, 100)
                val entry = price
                val stop = max(0.0, entry - atr * 1.6)
                val risk = entry - stop
                CoinSignal(
                    symbol, price, change, score,
                    if (score >= 75) "شراء محتمل" else if (score <= 40) "ضعيفة" else "مراقبة",
                    entry, stop, entry + risk * 1.5, entry + risk * 2.2,
                    entry + risk * 3.0,
                    "RSI ${"%.1f".format(rsi)} • EMA20/50 ${if (ema20 > ema50) "إيجابي" else "سلبي"} • MACD ${if (macd) "إيجابي" else "محايد"}"
                )
            } catch (_: Exception) { null }
        }.sortedByDescending { it.score }.take(10)
    }
}

object Indicators {
    fun ema(values: List<Double>, period: Int): Double {
        if (values.isEmpty()) return 0.0
        val k = 2.0 / (period + 1)
        var result = values.take(period).average()
        for (v in values.drop(period)) result = v * k + result * (1 - k)
        return result
    }
    fun rsi(values: List<Double>, period: Int = 14): Double {
        if (values.size <= period) return 50.0
        val changes = values.zipWithNext { a, b -> b - a }
        var gains = changes.take(period).filter { it > 0 }.sum() / period
        var losses = -changes.take(period).filter { it < 0 }.sum() / period
        for (d in changes.drop(period)) {
            gains = (gains * (period - 1) + max(d, 0.0)) / period
            losses = (losses * (period - 1) + max(-d, 0.0)) / period
        }
        if (losses == 0.0) return 100.0
        val rs = gains / losses
        return 100 - 100 / (1 + rs)
    }
    fun macdBullish(v: List<Double>): Boolean =
        v.size >= 35 && ema(v, 12) > ema(v, 26)
    fun atr(high: List<Double>, low: List<Double>, close: List<Double>, period: Int = 14): Double {
        if (close.size < period + 1) return close.last() * 0.02
        val tr = (1 until close.size).map { i ->
            max(high[i] - low[i], max(abs(high[i] - close[i-1]), abs(low[i] - close[i-1])))
        }
        return tr.takeLast(period).average()
    }
}

class MarketViewModel : ViewModel() {
    var futures by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var error by mutableStateOf("")
    var signals by mutableStateOf<List<CoinSignal>>(emptyList())
    var updated by mutableStateOf("")
    private val repo = MarketRepository()

    fun refresh() {
        viewModelScope.launch {
            loading = true
            error = ""
            try {
                signals = withContext(Dispatchers.IO) { repo.scan(futures) }
                updated = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                if (signals.isEmpty()) error = "لم تصل نتائج. تحقق من اتصال الإنترنت أو أعد المحاولة."
            } catch (e: Exception) {
                error = "تعذر جلب بيانات Binance: ${e.message ?: "خطأ اتصال"}"
            } finally { loading = false }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CryptoHunterApp(vm: MarketViewModel = viewModel()) {
    LaunchedEffect(Unit) { vm.refresh() }
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Gold, background = Bg, surface = Panel,
        onBackground = Color.White, onSurface = Color.White
    )) {
        Scaffold(containerColor = Bg, topBar = {
            TopAppBar(
                title = { Column {
                    Text("CRYPTO HUNTER", color = Gold, fontWeight = FontWeight.Black)
                    Text("ماسح سوق Binance الذكي", color = Muted, style = MaterialTheme.typography.labelSmall)
                }},
                actions = { IconButton(onClick = { vm.refresh() }) { Icon(Icons.Default.Refresh, "تحديث", tint = Gold) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg)
            )
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeButton("SPOT", !vm.futures, Modifier.weight(1f)) { vm.futures = false; vm.refresh() }
                    ModeButton("FUTURES", vm.futures, Modifier.weight(1f)) { vm.futures = true; vm.refresh() }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (vm.futures) "أفضل فرص العقود الآجلة" else "أفضل فرص السوق الفوري", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("تحديث: ${vm.updated.ifBlank { "--:--" }}", color = Muted, style = MaterialTheme.typography.labelSmall)
                }
                Text("أفضل 10 فرص حسب المؤشرات الفنية • إطار 1 ساعة", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
                if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Gold)
                if (vm.error.isNotBlank()) Text(vm.error, color = Red, modifier = Modifier.padding(8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(vm.signals) { signal -> SignalCard(signal) }
                    item {
                        Text("تنبيه: هذه إشارات تحليلية آلية وليست توصيات مضمونة. مستويات الدخول والأهداف تقديرية وتتغير مع السوق. لا تستخدمها دون إدارة مخاطر.", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 14.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun ModeButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier, colors = ButtonDefaults.buttonColors(
        containerColor = if (selected) Gold else Panel,
        contentColor = if (selected) Bg else Color.White
    ), shape = RoundedCornerShape(12.dp)) { Text(label, fontWeight = FontWeight.Bold) }
}

@Composable
fun SignalCard(s: CoinSignal) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.symbol, color = Color.White, fontWeight = FontWeight.Bold)
                    Text("24h ${"%.2f".format(s.change)}%", color = if (s.change >= 0) Green else Red, style = MaterialTheme.typography.labelSmall)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("${s.score}/100", color = Gold, fontWeight = FontWeight.Black)
                    Text(s.signal, color = if (s.score >= 75) Green else Muted, style = MaterialTheme.typography.labelSmall)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Color(0xFF293647))
            Text("السعر الحالي / الدخول: ${fmt(s.entry)} USDT", color = Color.White)
            Text("وقف الخسارة: ${fmt(s.stop)}", color = Red)
            Text("جني الأرباح: ${fmt(s.tp1)}  |  ${fmt(s.tp2)}  |  ${fmt(s.tp3)}", color = Green)
            Text(s.reason, color = Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
fun fmt(v: Double): String = if (v >= 100) "%.3f".format(v) else "%.6f".format(v)
