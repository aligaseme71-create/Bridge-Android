package com.bridge.app

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLDecoder
import java.util.Base64


data class ServerProfile(val name: String, val uri: String, val protocol: String, val latency: Long = -1)

class MainActivity : ComponentActivity() {
    private var pendingUri: String? = null

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) pendingUri?.let { uri -> startVpn(uri) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BridgeApp(::requestVpn, ::stopVpn) }
    }

    private fun requestVpn(uri: String) {
        pendingUri = uri
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermission.launch(intent) else startVpn(uri)
    }

    private fun startVpn(uri: String) {
        val intent = Intent(this, BridgeVpnService::class.java).apply {
            action = BridgeVpnService.ACTION_CONNECT
            putExtra(BridgeVpnService.EXTRA_URI, uri)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopVpn() {
        startService(Intent(this, BridgeVpnService::class.java).setAction(BridgeVpnService.ACTION_DISCONNECT))
    }
}

private val Navy = Color(0xFF03111F)
private val DeepNavy = Color(0xFF010811)
private val Panel = Color(0xCC0A1C2D)
private val LightBg = Color(0xFFF5F8FC)
private val LightPanel = Color(0xFFFFFFFF)
private val Blue = Color(0xFF159BFF)
private val Cyan = Color(0xFF00D9FF)
private val Green = Color(0xFF22D66B)
private val Red = Color(0xFFFF3B3B)
private val Muted = Color(0xFFAABBCD)
private val LightText = Color(0xFF102033)
private val LightMuted = Color(0xFF60758A)

@Composable
fun BridgeApp(onConnect: (String) -> Unit, onDisconnect: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("bridge", 0) }
    var connected by remember { mutableStateOf(BridgeVpnState.connected) }
    var tab by remember { mutableStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var subUrl by remember { mutableStateOf(prefs.getString("subscription_url", "").orEmpty()) }
    var servers by remember { mutableStateOf(loadSavedServers(prefs)) }
    var selected by remember { mutableStateOf(prefs.getInt("selected_server", if (servers.isNotEmpty()) 0 else -1)) }
    var updating by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var darkMode by remember { mutableStateOf(prefs.getBoolean("dark_mode", true)) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        while (true) {
            connected = BridgeVpnState.connected
            delay(250)
        }
    }

    fun testServer(index: Int) {
        if (index !in servers.indices || testing) return
        testing = true
        val profile = servers[index]
        scope.launch(Dispatchers.IO) {
            val delayMs = try {
                val config = XrayConfigBuilder.build(profile.uri)
                libv2ray.Libv2ray.measureOutboundDelay(config, "https://www.gstatic.com/generate_204")
            } catch (_: Exception) { -1L }
            withContext(Dispatchers.Main) {
                servers = servers.toMutableList().also {
                    if (index in it.indices) it[index] = profile.copy(latency = delayMs)
                }
                testing = false
                saveServers(prefs, servers)
            }
        }
    }

    fun selectServer(index: Int) {
        if (index !in servers.indices) return
        selected = index
        prefs.edit().putInt("selected_server", index).apply()
        testServer(index)
    }

    fun importSubscription() {
        if (subUrl.isBlank()) {
            message = "Enter a subscription URL first."
            return
        }
        scope.launch {
            updating = true
            message = "Loading subscription..."
            val result = SubscriptionLoader.load(subUrl)
            if (result.isNotEmpty()) {
                servers = result
                selected = 0
                prefs.edit().putString("subscription_url", subUrl).putInt("selected_server", 0).apply()
                saveServers(prefs, result)
                message = "${result.size} servers imported successfully."
                tab = 1
                testServer(0)
            } else {
                message = "No supported profiles found."
            }
            updating = false
        }
    }

    val colors = if (darkMode) {
        darkColorScheme(background = Navy, surface = Panel, primary = Blue, onBackground = Color.White, onSurface = Color.White)
    } else {
        lightColorScheme(background = LightBg, surface = LightPanel, primary = Blue, onBackground = LightText, onSurface = LightText)
    }

    MaterialTheme(colorScheme = colors) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.fillMaxSize()) {
                when (tab) {
                    0 -> HomeScreen(
                        darkMode = darkMode,
                        connected = connected,
                        server = servers.getOrNull(selected),
                        menuOpen = menuOpen,
                        onMenu = { menuOpen = !menuOpen },
                        onToggle = {
                            if (connected) onDisconnect()
                            else servers.getOrNull(selected)?.let { onConnect(it.uri) } ?: run {
                                tab = 2
                                message = "Add a subscription first."
                            }
                        },
                        onServers = { tab = 1; menuOpen = false },
                        onSubscription = { tab = 2; menuOpen = false },
                        onTheme = {
                            darkMode = !darkMode
                            prefs.edit().putBoolean("dark_mode", darkMode).apply()
                            menuOpen = false
                        }
                    )
                    1 -> ServersScreen(darkMode, servers, selected, testing, ::selectServer) { tab = 0 }
                    else -> SubscriptionScreen(
                        darkMode = darkMode,
                        url = subUrl,
                        onUrl = {
                            subUrl = it
                            prefs.edit().putString("subscription_url", it).apply()
                        },
                        updating = updating,
                        message = message,
                        serverCount = servers.size,
                        onImport = ::importSubscription,
                        onTheme = {
                            darkMode = !darkMode
                            prefs.edit().putBoolean("dark_mode", darkMode).apply()
                        },
                        onBack = { tab = 0 }
                    )
                }
                BottomNav(tab, darkMode) { tab = it; menuOpen = false }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    darkMode: Boolean,
    connected: Boolean,
    server: ServerProfile?,
    menuOpen: Boolean,
    onMenu: () -> Unit,
    onToggle: () -> Unit,
    onServers: () -> Unit,
    onSubscription: () -> Unit,
    onTheme: () -> Unit
) {
    val bg = if (darkMode) Navy else LightBg
    val text = if (darkMode) Color.White else LightText
    val muted = if (darkMode) Muted else LightMuted
    val panel = if (darkMode) Panel else LightPanel

    Box(modifier = Modifier.fillMaxSize().background(bg)) {
        if (darkMode) BridgeBackdrop() else LightBridgeBackdrop()
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("☰", color = text, fontSize = 30.sp, modifier = Modifier.clickable { onMenu() })
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Bridge", color = text, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    Text("SECURE  •  PRIVATE  •  GLOBAL", color = muted, fontSize = 9.sp, letterSpacing = 1.6.sp)
                }
                Text("⚙", color = text, fontSize = 27.sp, modifier = Modifier.clickable { onSubscription() })
            }
            if (menuOpen) {
                Card(modifier = Modifier.padding(top = 5.dp).width(205.dp), colors = CardDefaults.cardColors(if (darkMode) Color(0xF20B2032) else Color.White), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(8.dp)) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        MenuItem("⌂  Home", text) { onMenu() }
                        MenuItem("▤  Servers", text, onServers)
                        MenuItem("🔗  Subscription", text, onSubscription)
                        MenuItem(if (darkMode) "☀  Light Mode" else "☾  Dark Mode", text, onTheme)
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(if (connected) "PROTECTED\nCONNECTION\nACTIVE" else "YOUR PRIVACY\nOUR PRIORITY", color = if (connected) Green else muted, fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 23.sp)
            Spacer(modifier = Modifier.weight(1f))
            PowerButton(connected, onToggle)
            Spacer(modifier = Modifier.height(12.dp))
            Text(if (connected) "Connected" else "Disconnected", color = if (connected) Green else Red, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Text(if (connected) "Secure tunnel is active" else "Tap the power button to connect", color = muted, fontSize = 12.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth().clickable { onServers() }, colors = CardDefaults.cardColors(panel), shape = RoundedCornerShape(19.dp), elevation = CardDefaults.cardElevation(if (darkMode) 0.dp else 3.dp)) {
                Row(modifier = Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("⚡", color = Blue, fontSize = 25.sp)
                    Spacer(modifier = Modifier.width(11.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(server?.name ?: "Auto Select", color = text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text(server?.let { if (it.latency >= 0) "${it.protocol} • ${it.latency} ms" else it.protocol } ?: "Fastest available server", color = muted, fontSize = 12.sp)
                    }
                    Text("›", color = muted, fontSize = 31.sp)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatCard("⚡", "Ping", if (server?.latency ?: -1 >= 0) "${server!!.latency} ms" else "-- ms", darkMode, Modifier.weight(1f))
                StatCard("↓", "Download", "-- Mbps", darkMode, Modifier.weight(1f))
                StatCard("↑", "Upload", "-- Mbps", darkMode, Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(76.dp))
        }
    }
}

@Composable
private fun PowerButton(connected: Boolean, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "powerPulse")
    val pulse by transition.animateFloat(0.55f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
    val active = if (connected) Green else Red
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(215.dp).clickable { onClick() }) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension * .40f
            drawCircle(active.copy(alpha = .10f * pulse), radius * 1.25f)
            drawCircle(active.copy(alpha = .18f * pulse), radius * 1.08f)
            drawCircle(Color(0xFF071726), radius * .96f)
            drawCircle(active.copy(alpha = pulse), radius, style = Stroke(width = 7.dp.toPx()))
            drawCircle(active.copy(alpha = .22f), radius * .88f, style = Stroke(width = 2.dp.toPx()))
            val p = radius * .36f
            drawArc(active, -50f, 280f, false, topLeft = Offset(c.x - p, c.y - p), size = androidx.compose.ui.geometry.Size(p * 2f, p * 2f), style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round))
            drawLine(active, Offset(c.x, c.y - p * 1.12f), Offset(c.x, c.y + p * .20f), strokeWidth = 7.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun BridgeBackdrop() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF062849), Navy, DeepNavy)))
        val globe = Offset(size.width * .5f, size.height * .28f)
        drawCircle(Color(0x2200BFFF), size.width * .43f, globe)
        for (i in 0..24) drawCircle(Color(0x6628BFFF), 2f, Offset(size.width * i / 24f, size.height * (.15f + (i % 7) * .018f)))
        val y = size.height * .39f
        drawLine(Color(0xFF78CFFF), Offset(0f, y), Offset(size.width, y), 3f)
        listOf(.18f, .72f).forEach { x ->
            drawLine(Color(0xFF5B96B7), Offset(size.width * x, y), Offset(size.width * x, y + size.height * .17f), 5f)
            drawLine(Color(0xFF9EDFFF), Offset(size.width * x, y), Offset(size.width * (x - .10f), y + size.height * .10f), 2f)
            drawLine(Color(0xFF9EDFFF), Offset(size.width * x, y), Offset(size.width * (x + .10f), y + size.height * .10f), 2f)
        }
        for (i in 0..18) drawLine(Color(0x3325AFFF), Offset(size.width * i / 18f, y + size.height * .03f), Offset(size.width * i / 18f, size.height * .60f), 1f)
    }
}

@Composable
private fun LightBridgeBackdrop() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(Color.White, Color(0xFFF4F8FC), Color(0xFFEAF3FB))))
        val center = Offset(size.width * .5f, size.height * .29f)
        drawCircle(Color(0x14009BFF), size.width * .43f, center)
        for (i in 0..20) drawCircle(Color(0x334099D6), 2f, Offset(size.width * i / 20f, size.height * (.17f + (i % 6) * .018f)))
        val y = size.height * .39f
        drawLine(Color(0x6681B9D8), Offset(0f, y), Offset(size.width, y), 3f)
    }
}

@Composable
private fun MenuItem(text: String, textColor: Color, onClick: () -> Unit) {
    Text(text, color = textColor, fontSize = 15.sp, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onClick() }.padding(12.dp))
}

@Composable
private fun StatCard(icon: String, title: String, value: String, darkMode: Boolean, modifier: Modifier) {
    val panel = if (darkMode) Color(0xCC091B2C) else Color.White
    val text = if (darkMode) Color.White else LightText
    val muted = if (darkMode) Muted else LightMuted
    Card(modifier = modifier, colors = CardDefaults.cardColors(panel), shape = RoundedCornerShape(14.dp), elevation = CardDefaults.cardElevation(if (darkMode) 0.dp else 2.dp)) {
        Column(modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(icon, color = Blue, fontSize = 19.sp)
            Text(title, color = muted, fontSize = 10.sp)
            Text(value, color = text, fontSize = 10.sp)
        }
    }
}

@Composable
private fun BottomNav(tab: Int, darkMode: Boolean, onTab: (Int) -> Unit) {
    val bg = if (darkMode) Color(0xF20A1A2A) else Color(0xF8FFFFFF)
    val text = if (darkMode) Muted else LightMuted
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 9.dp).height(68.dp).background(bg, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)).border(1.dp, if (darkMode) Color(0x223E83B5) else Color(0x22000000), RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            listOf("⌂" to "Home", "▤" to "Servers", "🔗" to "Subscription").forEachIndexed { i, item ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(105.dp).clickable { onTab(i) }) {
                    Text(item.first, color = if (tab == i) Blue else text, fontSize = 23.sp)
                    Text(item.second, color = if (tab == i) Blue else text, fontSize = 10.sp, fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
private fun ServersScreen(darkMode: Boolean, servers: List<ServerProfile>, selected: Int, testing: Boolean, onSelect: (Int) -> Unit, onBack: () -> Unit) {
    val text = if (darkMode) Color.White else LightText
    val muted = if (darkMode) Muted else LightMuted
    val panel = if (darkMode) Panel else Color.White
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = text, fontSize = 34.sp, modifier = Modifier.clickable { onBack() })
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text("SERVERS", color = text, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Text("${servers.size} servers loaded", color = muted, fontSize = 12.sp)
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        if (testing) Text("Testing selected server...", color = Blue, fontSize = 13.sp)
        Spacer(modifier = Modifier.height(8.dp))
        if (servers.isEmpty()) {
            Card(colors = CardDefaults.cardColors(panel), shape = RoundedCornerShape(18.dp)) {
                Text("No servers yet. Open Subscription and import your link.", color = muted, modifier = Modifier.padding(18.dp))
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 90.dp)) {
                itemsIndexed(servers, key = { _, s -> s.uri }) { i, s ->
                    Card(modifier = Modifier.fillMaxWidth().clickable { onSelect(i) }, colors = CardDefaults.cardColors(if (i == selected) Color(0xCC123557) else panel), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(if (darkMode) 0.dp else 2.dp)) {
                        Row(modifier = Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(39.dp).background(if (i == selected) Blue.copy(alpha = .18f) else Blue.copy(alpha = .08f), CircleShape), contentAlignment = Alignment.Center) {
                                Text(protocolIcon(s.protocol), color = Blue, fontSize = 18.sp)
                            }
                            Spacer(modifier = Modifier.width(11.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(s.name, color = text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                Text(s.protocol, color = muted, fontSize = 11.sp)
                            }
                            Text(if (s.latency >= 0) "${s.latency} ms" else "-- ms", color = if (s.latency >= 0) Green else muted, fontSize = 11.sp)
                            if (i == selected) { Spacer(modifier = Modifier.width(7.dp)); Text("✓", color = Green, fontSize = 20.sp) }
                        }
                    }
                }
            }
        }
    }
}

private fun protocolIcon(protocol: String): String = when (protocol.uppercase()) {
    "VLESS" -> "V"
    "VMESS" -> "M"
    "TROJAN" -> "T"
    "SHADOWSOCKS" -> "S"
    else -> "•"
}

@Composable
private fun SubscriptionScreen(darkMode: Boolean, url: String, onUrl: (String) -> Unit, updating: Boolean, message: String, serverCount: Int, onImport: () -> Unit, onTheme: () -> Unit, onBack: () -> Unit) {
    val text = if (darkMode) Color.White else LightText
    val muted = if (darkMode) Muted else LightMuted
    val panel = if (darkMode) Panel else Color.White
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = text, fontSize = 34.sp, modifier = Modifier.clickable { onBack() })
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text("SUBSCRIPTION", color = text, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Text("Link & appearance settings", color = muted, fontSize = 12.sp)
            }
        }
        Spacer(modifier = Modifier.height(17.dp))
        Card(colors = CardDefaults.cardColors(panel), shape = RoundedCornerShape(19.dp), elevation = CardDefaults.cardElevation(if (darkMode) 0.dp else 2.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🔗", fontSize = 24.sp)
                    Spacer(modifier = Modifier.width(9.dp))
                    Text("Subscription Link", color = text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(modifier = Modifier.height(11.dp))
                OutlinedTextField(value = url, onValueChange = onUrl, modifier = Modifier.fillMaxWidth(), label = { Text("Paste subscription URL") }, placeholder = { Text("https://…") }, minLines = 2, maxLines = 3, shape = RoundedCornerShape(13.dp))
                Spacer(modifier = Modifier.height(11.dp))
                Button(onClick = onImport, modifier = Modifier.fillMaxWidth().height(50.dp), enabled = !updating, shape = RoundedCornerShape(13.dp)) {
                    Text(if (updating) "IMPORTING..." else "IMPORT SERVERS", fontWeight = FontWeight.Bold)
                }
                if (message.isNotBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(message, color = if (message.contains("success", true)) Green else Blue, fontSize = 12.sp)
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(panel), shape = RoundedCornerShape(17.dp)) {
            Column(modifier = Modifier.padding(15.dp)) {
                Text("Current Subscription", color = text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(5.dp))
                Text(if (serverCount > 0) "●  Loaded • $serverCount servers" else "○  No subscription loaded", color = if (serverCount > 0) Green else muted, fontSize = 13.sp)
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Card(modifier = Modifier.fillMaxWidth().clickable { onTheme() }, colors = CardDefaults.cardColors(panel), shape = RoundedCornerShape(17.dp)) {
            Row(modifier = Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (darkMode) "☀" else "☾", color = Blue, fontSize = 25.sp)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Theme", color = text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (darkMode) "Dark mode" else "Light mode", color = muted, fontSize = 12.sp)
                }
                Text("›", color = muted, fontSize = 28.sp)
            }
        }
    }
}

object SubscriptionLoader {
    private val client = OkHttpClient()

    suspend fun load(url: String): List<ServerProfile> = withContext(Dispatchers.IO) {
        try {
            val cleanUrl = url.trim()
            val request = Request.Builder().url(cleanUrl).header("User-Agent", "Bridge/1.0").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                parse(response.body?.string()?.trim().orEmpty())
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun parse(body: String): List<ServerProfile> {
        val decoded = decode(body)
        val out = mutableListOf<ServerProfile>()
        for (line in decoded.lines().map { it.trim() }.filter { it.isNotBlank() }) {
            val candidate = line.removePrefix("proxies:").trim().removeSurrounding("\"")
            val lower = candidate.lowercase()
            val protocol = when {
                lower.startsWith("vless://") -> "VLESS"
                lower.startsWith("vmess://") -> "VMess"
                lower.startsWith("trojan://") -> "Trojan"
                lower.startsWith("ss://") -> "Shadowsocks"
                else -> null
            } ?: continue
            val name = displayName(candidate, protocol, out.size + 1)
            out += ServerProfile(name, candidate, protocol)
        }
        return out.distinctBy { it.uri }
    }

    private fun displayName(uri: String, protocol: String, index: Int): String {
        val fragment = uri.substringAfter("#", "").let { try { URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it } }.replace("+", " ").trim()
        if (fragment.isUsefulName()) return cleanName(fragment)
        if (protocol == "VMess") {
            try {
                val raw = uri.substringAfter("vmess://").substringBefore("#").padBase64()
                val json = String(Base64.getDecoder().decode(raw), Charsets.UTF_8)
                val ps = JSONObject(json).optString("ps")
                if (ps.isUsefulName()) return cleanName(ps)
            } catch (_: Exception) { }
        }
        val host = try { Uri.parse(uri).host.orEmpty() } catch (_: Exception) { "" }
        return host.takeIf { it.isNotBlank() } ?: "$protocol Server $index"
    }

    private fun cleanName(value: String): String = value.replace(Regex("\\s+"), " ").replace(Regex("^[|/\\\\_\\-]+|[|/\\\\_\\-]+$"), "").trim().take(64)

    private fun String.isUsefulName(): Boolean {
        if (isBlank()) return false
        val n = lowercase().trim()
        return n !in setOf("vless", "vmess", "trojan", "shadowsocks", "none", "server", "default") && length <= 96
    }

    private fun decode(input: String): String {
        val compact = input.replace(Regex("\\s"), "")
        return try { Base64.getDecoder().decode(compact.padBase64()).toString(Charsets.UTF_8) } catch (_: Exception) { input }
    }

    private fun String.padBase64(): String = this + "=".repeat((4 - length % 4) % 4)
}

private fun saveServers(prefs: SharedPreferences, servers: List<ServerProfile>) {
    val value = servers.joinToString("\n") { listOf(it.name, it.uri, it.protocol, it.latency.toString()).joinToString("\t") }
    prefs.edit().putString("servers", value).apply()
}

private fun loadSavedServers(prefs: SharedPreferences): List<ServerProfile> {
    val raw = prefs.getString("servers", "").orEmpty()
    if (raw.isBlank()) return emptyList()
    return raw.lines().mapNotNull { line ->
        val p = line.split("\t")
        if (p.size >= 4) p[3].toLongOrNull()?.let { ServerProfile(p[0], p[1], p[2], it) } else null
    }
}
