package com.cryptohunter.app

import android.os.Bundle
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
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
import kotlinx.coroutines.delay
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import kotlin.math.abs
import kotlin.math.max

private val Bg = Color(0xFF070B16)
private val Panel = Color(0xFF111A2B)
private val PanelAlt = Color(0xFF172338)
private val Gold = Color(0xFF38BDF8)
private val Purple = Color(0xFF8B5CF6)
private val Green = Color(0xFF22C55E)
private val Red = Color(0xFFFB5364)
private val TextMain = Color(0xFFF1F5F9)
private val Muted = Color(0xFF9AAAC0)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CryptoHunterRoot() }
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

    suspend fun latestPrices(futures: Boolean): Map<String, Double> {
        val base = if (futures) "https://fapi.binance.com" else "https://api.binance.com"
        val path = if (futures) "/fapi/v1/ticker/24hr" else "/api/v3/ticker/24hr"
        val tickers = JSONArray(get(base + path))
        val result = mutableMapOf<String, Double>()
        for (i in 0 until tickers.length()) {
            val item = tickers.getJSONObject(i)
            val symbol = item.optString("symbol")
            val price = item.optString("lastPrice").toDoubleOrNull()
            if (symbol.endsWith("USDT") && price != null && price > 0) result[symbol] = price
        }
        return result
    }

    suspend fun scan(futures: Boolean, interval: String): List<CoinSignal> {
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
                val arr = JSONArray(get("$base$path?symbol=$symbol&interval=$interval&limit=120"))
                val closes = (0 until arr.length()).map { arr.getJSONArray(it).getString(4).toDouble() }
                val highs = (0 until arr.length()).map { arr.getJSONArray(it).getString(2).toDouble() }
                val lows = (0 until arr.length()).map { arr.getJSONArray(it).getString(3).toDouble() }
                val volumes = (0 until arr.length()).map { arr.getJSONArray(it).getString(5).toDouble() }
                val rsi = Indicators.rsi(closes)
                val ema20 = Indicators.ema(closes, 20)
                val ema50 = Indicators.ema(closes, 50)
                val macd = Indicators.macdBullish(closes)
                val atr = Indicators.atr(highs, lows, closes)
                val bullish = ema20 > ema50 && macd && rsi >= 48
                val bearish = ema20 < ema50 && !macd && rsi <= 52
                val avgVolume = volumes.dropLast(1).takeLast(20).average().coerceAtLeast(0.0000001)
                val volumeRatio = volumes.last() / avgVolume
                val volumeSpike = volumeRatio >= 1.8
                var score = 45
                if (bullish && rsi in 48.0..70.0) score += 20
                if (bearish && rsi in 30.0..52.0) score += 20
                if (volumeSpike) score += 18
                if (abs(change) >= 2.0) score += 8
                if (rsi in 35.0..70.0) score += 5
                score = score.coerceIn(0, 96)
                val shortSignal = bearish && score >= 65
                val entry = price
                val risk = (atr * 1.6).coerceAtLeast(entry * 0.003)
                val stop = if (shortSignal && futures) entry + risk else max(0.0, entry - risk)
                val tp1 = if (shortSignal && futures) entry - risk * 1.5 else entry + risk * 1.5
                val tp2 = if (shortSignal && futures) entry - risk * 2.2 else entry + risk * 2.2
                val tp3 = if (shortSignal && futures) entry - risk * 3.0 else entry + risk * 3.0
                val label = when {
                    bullish && score >= 70 -> "صعود محتمل"
                    shortSignal && futures -> "هبوط محتمل • فرصة بيع"
                    bearish && !futures -> "ضغط هابط • تجنب الشراء"
                    volumeSpike -> "ارتفاع حجم التداول"
                    else -> "مراقبة"
                }
                CoinSignal(
                    symbol, price, change, score, label,
                    entry, stop, tp1, tp2, tp3,
                    "RSI ${"%.1f".format(rsi)} • حجم التداول ${"%.1f".format(volumeRatio)}x • EMA20/50 ${if (ema20 > ema50) "إيجابي" else "سلبي"} • MACD ${if (macd) "إيجابي" else "سلبي"}"
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
    var interval by mutableStateOf("5m")
    var loading by mutableStateOf(false)
    var error by mutableStateOf("")
    var signals by mutableStateOf<List<CoinSignal>>(emptyList())
    var updated by mutableStateOf("")
    private val repo = MarketRepository()
    private var scanRunning = false

    fun refreshPrices() {
        viewModelScope.launch {
            try {
                val latest = withContext(Dispatchers.IO) { repo.latestPrices(futures) }
                signals = signals.map { coin -> latest[coin.symbol]?.let { coin.copy(price = it) } ?: coin }
                updated = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            } catch (_: Exception) { }
        }
    }

    fun refresh() {
        if (scanRunning) return
        viewModelScope.launch {
            scanRunning = true
            loading = true
            error = ""
            try {
                signals = withContext(Dispatchers.IO) { repo.scan(futures, interval) }
                updated = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                if (signals.isEmpty()) error = "لم تصل نتائج. تحقق من اتصال الإنترنت أو أعد المحاولة."
            } catch (e: Exception) {
                error = "تعذر جلب بيانات Binance: ${e.message ?: "خطأ اتصال"}"
            } finally { loading = false; scanRunning = false }
        }
    }
}


@Composable
fun CryptoHunterRoot() {
    val auth = remember { FirebaseAuth.getInstance() }
    var verifiedUser by remember { mutableStateOf<FirebaseUser?>(null) }
    var authReady by remember { mutableStateOf(false) }

    DisposableEffect(auth) {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            if (user == null) {
                verifiedUser = null
                authReady = true
            } else {
                user.reload().addOnCompleteListener {
                    val refreshed = auth.currentUser
                    if (refreshed != null && refreshed.isEmailVerified) {
                        verifiedUser = refreshed
                    } else {
                        verifiedUser = null
                    }
                    authReady = true
                }
            }
        }
        auth.addAuthStateListener(listener)
        onDispose { auth.removeAuthStateListener(listener) }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Gold, secondary = Purple, background = Bg, surface = Panel,
        onBackground = TextMain, onSurface = TextMain,
        surfaceVariant = PanelAlt, onSurfaceVariant = Muted,
        primaryContainer = Color(0xFF12334A), onPrimaryContainer = TextMain
    )) {
        if (!authReady) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Gold)
            }
        } else if (verifiedUser != null) {
            CryptoHunterApp()
        } else {
            AuthScreen(auth)
        }
    }
}

@Composable
fun AuthScreen(auth: FirebaseAuth) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isRegister by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }

    fun showResult(text: String, error: Boolean = false) {
        message = text
        isError = error
        loading = false
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("CRYPTO HUNTER", color = TextMain, fontWeight = FontWeight.Black, style = MaterialTheme.typography.headlineMedium)
        Text("تسجيل الدخول بالبريد الإلكتروني", color = Muted, modifier = Modifier.padding(top = 8.dp, bottom = 28.dp))
        OutlinedTextField(
            value = email, onValueChange = { email = it.trim() },
            label = { Text("البريد الإلكتروني") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text("كلمة المرور") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = {
                if (email.isBlank() || password.length < 6) {
                    showResult("أدخل بريداً صحيحاً وكلمة مرور من 6 أحرف على الأقل.", true)
                } else {
                    loading = true
                    message = ""
                    if (isRegister) {
                        auth.createUserWithEmailAndPassword(email, password)
                            .addOnCompleteListener { task ->
                                if (task.isSuccessful) {
                                    val user = auth.currentUser
                                    if (user != null) {
                                        user.sendEmailVerification().addOnCompleteListener { verifyTask ->
                                            auth.signOut()
                                            if (verifyTask.isSuccessful) showResult("أنشأنا الحساب. افتح رسالة التحقق في بريدك ثم ارجع وسجّل الدخول.")
                                            else showResult("تم إنشاء الحساب، لكن تعذر إرسال رسالة التحقق. جرّب تسجيل الدخول لإعادة إرسالها.", true)
                                        }
                                    } else showResult("تعذر إكمال إنشاء الحساب. حاول مرة أخرى.", true)
                                } else showResult(task.exception?.localizedMessage ?: "تعذر إنشاء الحساب.", true)
                            }
                    } else {
                        auth.signInWithEmailAndPassword(email, password)
                            .addOnCompleteListener { task ->
                                if (task.isSuccessful) {
                                    val user = auth.currentUser
                                    if (user != null) {
                                        user.reload().addOnCompleteListener {
                                            val refreshed = auth.currentUser
                                            if (refreshed?.isEmailVerified == true) {
                                                showResult("تم تسجيل الدخول.")
                                            } else {
                                                refreshed?.sendEmailVerification()?.addOnCompleteListener {
                                                    auth.signOut()
                                                    showResult("بريدك غير مؤكد بعد. أرسلنا رابط تحقق جديد؛ افتحه ثم سجّل الدخول.", true)
                                                } ?: run {
                                                    auth.signOut()
                                                    showResult("أكد بريدك الإلكتروني أولاً ثم سجّل الدخول.", true)
                                                }
                                            }
                                        }
                                    } else showResult("تعذر قراءة الحساب.", true)
                                } else showResult(task.exception?.localizedMessage ?: "البريد أو كلمة المرور غير صحيحة.", true)
                            }
                    }
                }
            },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text(if (isRegister) "إنشاء حساب" else "تسجيل الدخول")
        }
        TextButton(onClick = {
            isRegister = !isRegister
            message = ""
        }) {
            Text(if (isRegister) "لديك حساب؟ تسجيل الدخول" else "إنشاء حساب جديد")
        }
        if (!isRegister) {
            TextButton(
                enabled = email.isNotBlank() && !loading,
                onClick = {
                    loading = true
                    auth.sendPasswordResetEmail(email).addOnCompleteListener { task ->
                        if (task.isSuccessful) showResult("أرسلنا رابط استعادة كلمة المرور إلى بريدك.")
                        else showResult(task.exception?.localizedMessage ?: "تعذر إرسال رابط الاستعادة.", true)
                    }
                }
            ) { Text("نسيت كلمة المرور؟") }
        }
        if (message.isNotBlank()) {
            Text(message, color = if (isError) Red else Green, modifier = Modifier.padding(top = 12.dp))
        }
        Text(
            "يلزم تأكيد البريد الإلكتروني قبل استخدام التطبيق. لا نطلب رقم هاتف.",
            color = Muted, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 24.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CryptoHunterApp(vm: MarketViewModel = viewModel()) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.refresh()
            var cycles = 0
            while (true) {
                delay(5_000)
                vm.refreshPrices()
                cycles++
                if (cycles % 12 == 0) vm.refresh()
            }
        }
    }
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Gold, secondary = Purple, background = Bg, surface = Panel,
        onBackground = TextMain, onSurface = TextMain,
        surfaceVariant = PanelAlt, onSurfaceVariant = Muted,
        primaryContainer = Color(0xFF12334A), onPrimaryContainer = TextMain
    )) {
        Scaffold(containerColor = Bg, topBar = {
            TopAppBar(
                title = { Column {
                    Text("CRYPTO HUNTER", color = TextMain, fontWeight = FontWeight.Black)
                    Text("ماسح سوق Binance الذكي", color = Muted, style = MaterialTheme.typography.labelSmall)
                }},
                actions = { IconButton(onClick = { vm.refresh() }) { Icon(Icons.Default.Refresh, "تحديث", tint = Gold) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg, titleContentColor = TextMain)
            )
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeButton("SPOT", !vm.futures, Modifier.weight(1f)) { vm.futures = false; vm.refresh() }
                    ModeButton("FUTURES", vm.futures, Modifier.weight(1f)) { vm.futures = true; vm.refresh() }
                }
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("1m", "3m", "5m", "15m").forEach { frame ->
                        FilterChip(
                            selected = vm.interval == frame,
                            onClick = { if (vm.interval != frame) { vm.interval = frame; vm.refresh() } },
                            label = { Text(frame) }
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (vm.futures) "أفضل فرص العقود الآجلة" else "أفضل فرص السوق الفوري", color = TextMain, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("تحديث: ${vm.updated.ifBlank { "--:--" }}", color = Muted, style = MaterialTheme.typography.labelSmall)
                }
                Text("رصد الزخم وحجم التداول • إطار ${vm.interval}", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
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
        containerColor = if (selected) Gold else PanelAlt,
        contentColor = if (selected) Color(0xFF06111F) else TextMain
    ), shape = RoundedCornerShape(12.dp)) { Text(label, fontWeight = FontWeight.Bold) }
}

@Composable
fun SignalCard(s: CoinSignal) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.symbol, color = TextMain, fontWeight = FontWeight.Bold)
                    Text("24h ${"%.2f".format(s.change)}%", color = if (s.change >= 0) Green else Red, style = MaterialTheme.typography.labelSmall)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("${s.score}/100", color = Gold, fontWeight = FontWeight.Black)
                    Text(s.signal, color = if (s.score >= 75) Green else Muted, style = MaterialTheme.typography.labelSmall)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Color(0xFF26364D))
            Text("السعر الحالي: ${fmt(s.price)} USDT", color = TextMain)
            Text("وقف الخسارة: ${fmt(s.stop)}", color = Red)
            Text("جني الأرباح: ${fmt(s.tp1)}  |  ${fmt(s.tp2)}  |  ${fmt(s.tp3)}", color = Green)
            Text(s.reason, color = Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
fun fmt(v: Double): String = if (v >= 100) "%.3f".format(v) else "%.6f".format(v)
