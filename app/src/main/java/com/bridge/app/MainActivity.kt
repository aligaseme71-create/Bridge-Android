package com.bridge.app

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLDecoder
import java.util.Base64

private val Navy = Color(0xFF03111F)
private val DeepNavy = Color(0xFF010811)
private val Panel = Color(0xCC0A1C2D)
private val LightBg = Color(0xFFF5F8FC)
private val Blue = Color(0xFF159BFF)
private val Cyan = Color(0xFF00D9FF)
private val Green = Color(0xFF22D66B)
private val Red = Color(0xFFFF3B3B)
private val Muted = Color(0xFFAABBCD)
private val LightText = Color(0xFF102033)
private val LightMuted = Color(0xFF60758A)

data class ServerProfile(val name: String, val uri: String, val protocol: String, val latency: Long = -1)

private enum class Tab { HOME, SERVERS, SUBSCRIPTION }

class MainActivity : ComponentActivity() {
    private var pendingUri: String? = null

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) pendingUri?.let(::startVpn)
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

@Composable
private fun BridgeApp(onConnect: (String) -> Unit, onDisconnect: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("bridge", Context.MODE_PRIVATE) }
    var connected by remember { mutableStateOf(BridgeVpnState.connected) }
    var tab by remember { mutableStateOf(Tab.HOME) }
    var darkMode by remember { mutableStateOf(prefs.getBoolean("dark_mode", true)) }
    var subUrl by remember { mutableStateOf(prefs.getString("subscription_url", "").orEmpty()) }
    var servers by remember { mutableStateOf(loadSavedServers(prefs)) }
    var selected by remember { mutableStateOf(prefs.getInt("selected_server", if (servers.isNotEmpty()) 0 else -1)) }
    var testing by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        while (true) {
            connected = BridgeVpnState.connected
            delay(250)
        }
    }

    fun toggleConnection() {
        if (connected) {
            onDisconnect()
            return
        }
        val server = servers.getOrNull(selected)
        if (server == null) {
            tab = Tab.SUBSCRIPTION
            message = "Add a subscription first."
            return
        }
        onConnect(server.uri)
    }

    fun testAll(autoConnect: Boolean) {
        if (servers.isEmpty() || testing) return
        testing = true
        message = "Testing all servers..."
        val snapshot = servers
        scope.launch(Dispatchers.IO) {
            val results = snapshot.map { profile ->
                async {
                    try {
                        val config = XrayConfigBuilder.build(profile.uri)
                        libv2ray.Libv2ray.measureOutboundDelay(config, "https://www.gstatic.com/generate_204")
                    } catch (_: Exception) {
                        -1L
                    }
                }
            }.awaitAll()
            val refreshed = snapshot.mapIndexed { index, profile ->
                profile.copy(latency = results.getOrElse(index) { -1L })
            }
            val best = refreshed.indices.filter { refreshed[it].latency >= 0L }.minByOrNull { refreshed[it].latency }
            withContext(Dispatchers.Main) {
                servers = refreshed
                saveServers(prefs, refreshed)
                testing = false
                if (best != null) {
                    selected = best
                    prefs.edit().putInt("selected_server", best).apply()
                    message = "Fastest: ${refreshed[best].name} • ${refreshed[best].latency} ms"
                    if (autoConnect) onConnect(refreshed[best].uri)
                } else {
                    message = "No reachable server was found."
                }
            }
        }
    }

    fun testOne(index: Int) {
        if (index !in servers.indices || testing) return
        testing = true
        val profile = servers[index]
        scope.launch(Dispatchers.IO) {
            val result = try {
                val config = XrayConfigBuilder.build(profile.uri)
                libv2ray.Libv2ray.measureOutboundDelay(config, "https://www.gstatic.com/generate_204")
            } catch (_: Exception) {
                -1L
            }
            withContext(Dispatchers.Main) {
                servers = servers.toMutableList().also { it[index] = profile.copy(latency = result) }
                saveServers(prefs, servers)
                testing = false
            }
        }
    }

    fun importSubscription() {
        if (subUrl.isBlank() || importing) {
            if (subUrl.isBlank()) message = "Enter a subscription URL first."
            return
        }
        scope.launch {
            importing = true
            message = "Loading subscription..."
            val result = withContext(Dispatchers.IO) { SubscriptionLoader.load(subUrl) }
            if (result.isNotEmpty()) {
                servers = result
                selected = 0
                prefs.edit().putString("subscription_url", subUrl).putInt("selected_server", 0).apply()
                saveServers(prefs, result)
                message = "${result.size} servers imported successfully."
                tab = Tab.SERVERS
            } else {
                message = "No supported profiles found."
            }
            importing = false
        }
    }

    val scheme = if (darkMode) {
        darkColorScheme(background = Navy, surface = Panel, primary = Blue)
    } else {
        lightColorScheme(background = LightBg, surface = Color.White, primary = Blue)
    }

    MaterialTheme(colorScheme = scheme) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(selected = tab == Tab.HOME, onClick = { tab = Tab.HOME }, icon = { Text("⌂") }, label = { Text("Home") })
                    NavigationBarItem(selected = tab == Tab.SERVERS, onClick = { tab = Tab.SERVERS }, icon = { Text("≋") }, label = { Text("Servers") })
                    NavigationBarItem(selected = tab == Tab.SUBSCRIPTION, onClick = { tab = Tab.SUBSCRIPTION }, icon = { Text("↗") }, label = { Text("Subscription") })
                }
            }
        ) { padding ->
            Surface(modifier = Modifier.fillMaxSize().padding(padding), color = MaterialTheme.colorScheme.background) {
                when (tab) {
                    Tab.HOME -> HomeScreen(
                        darkMode = darkMode,
                        connected = connected,
                        testing = testing,
                        server = servers.getOrNull(selected),
                        onToggle = ::toggleConnection,
                        onFastest = { testAll(true) },
                        canFastest = servers.isNotEmpty() && !testing,
                        onServers = { tab = Tab.SERVERS },
                        onSubscription = { tab = Tab.SUBSCRIPTION },
                        onTheme = {
                            darkMode = !darkMode
                            prefs.edit().putBoolean("dark_mode", darkMode).apply()
                        }
                    )
                    Tab.SERVERS -> ServersScreen(
                        darkMode = darkMode,
                        servers = servers,
                        selected = selected,
                        testing = testing,
                        onSelect = { index ->
                            selected = index
                            prefs.edit().putInt("selected_server", index).apply()
                        },
                        onTest = ::testOne,
                        onTestAll = { testAll(false) },
                        onFastestConnect = { testAll(true) }
                    )
                    Tab.SUBSCRIPTION -> SubscriptionScreen(
                        darkMode = darkMode,
                        url = subUrl,
                        onUrl = {
                            subUrl = it
                            prefs.edit().putString("subscription_url", it).apply()
                        },
                        importing = importing,
                        message = message,
                        serverCount = servers.size,
                        onImport = ::importSubscription,
                        onTheme = {
                            darkMode = !darkMode
                            prefs.edit().putBoolean("dark_mode", darkMode).apply()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    darkMode: Boolean,
    connected: Boolean,
    testing: Boolean,
    server: ServerProfile?,
    onToggle: () -> Unit,
    onFastest: () -> Unit,
    canFastest: Boolean,
    onServers: () -> Unit,
    onSubscription: () -> Unit,
    onTheme: () -> Unit
) {
    val text = if (darkMode) Color.White else LightText
    val muted = if (darkMode) Muted else LightMuted
    val bg = if (darkMode) Navy else LightBg

    Box(modifier = Modifier.fillMaxSize().background(bg)) {
        BridgeBackdrop(darkMode)
        Column(modifier = Modifier.fillMaxSize().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Bridge", color = text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text("⚙", color = text, fontSize = 24.sp, modifier = Modifier.clickable(onClick = onSubscription))
            }
            Text("SECURE • PRIVATE • GLOBAL", color = muted, fontSize = 9.sp, letterSpacing = 1.5.sp)
            Spacer(modifier = Modifier.weight(1f))
            PowerButton(connected = connected, testing = testing, onClick = onToggle)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                if (connected) "CONNECTED" else if (testing) "TESTING SERVERS" else "DISCONNECTED",
                color = if (connected) Green else if (testing) Blue else Red,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold
            )
            Text(if (connected) "Secure tunnel is active" else "Tap the power button to connect", color = muted, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onFastest, enabled = canFastest, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text(if (testing) "TESTING SERVERS..." else "FASTEST SERVER • CONNECT", fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(10.dp))
            Card(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onServers),
                colors = CardDefaults.cardColors(if (darkMode) Panel else Color.White),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(server?.name ?: "Auto Select", color = text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        server?.let { if (it.latency >= 0) "${it.protocol} • ${it.latency} ms" else it.protocol }
                            ?: "Fastest available server",
                        color = muted,
                        fontSize = 12.sp
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                StatCard("PING", server?.let { if (it.latency >= 0) "${it.latency} ms" else "--" } ?: "--", darkMode, Modifier.weight(1f))
                StatCard("DOWNLOAD", "--", darkMode, Modifier.weight(1f))
                StatCard("UPLOAD", "--", darkMode, Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(onClick = onTheme, modifier = Modifier.fillMaxWidth()) {
                Text(if (darkMode) "LIGHT MODE" else "DARK MODE")
            }
        }
    }
}

@Composable
private fun PowerButton(connected: Boolean, testing: Boolean, onClick: () -> Unit) {
    val accent = if (connected) Green else if (testing) Blue else Red
    Box(modifier = Modifier.size(220.dp).clip(CircleShape).clickable(enabled = !testing, onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension * .38f
            drawCircle(accent.copy(alpha = .12f), radius * 1.28f)
            drawCircle(Color(0xFF071726), radius * .98f)
            drawCircle(accent, radius, style = Stroke(width = 7.dp.toPx()))
            val p = radius * .35f
            drawArc(accent, -50f, 280f, false, androidx.compose.ui.geometry.Offset(center.x - p, center.y - p), androidx.compose.ui.geometry.Size(p * 2f, p * 2f), style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round))
            drawLine(accent, androidx.compose.ui.geometry.Offset(center.x, center.y - p * 1.12f), androidx.compose.ui.geometry.Offset(center.x, center.y + p * .2f), 8.dp.toPx(), cap = StrokeCap.Round)
        }
        Text(if (connected) "DISCONNECT" else "CONNECT", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
private fun BridgeBackdrop(darkMode: Boolean) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        if (darkMode) drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF062849), Navy, DeepNavy)))
        else drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.White, Color(0xFFF4F8FC))))
        if (darkMode) {
            val center = androidx.compose.ui.geometry.Offset(size.width * .5f, size.height * .24f)
            drawCircle(Color(0x2200BFFF), size.width * .46f, center)
            for (i in 0..22) {
                val x = size.width * i / 22f
                drawCircle(Color(0x6628BFFF), 2f, androidx.compose.ui.geometry.Offset(x, size.height * (.12f + (i % 6) * .02f)))
            }
        }
    }
}

@Composable
private fun StatCard(title: String, value: String, darkMode: Boolean, modifier: Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(if (darkMode) Panel else Color.White), shape = RoundedCornerShape(14.dp)) {
        Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = if (darkMode) Muted else LightMuted, fontSize = 9.sp)
            Text(value, color = if (darkMode) Color.White else LightText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ServersScreen(
    darkMode: Boolean,
    servers: List<ServerProfile>,
    selected: Int,
    testing: Boolean,
    onSelect: (Int) -> Unit,
    onTest: (Int) -> Unit,
    onTestAll: () -> Unit,
    onFastestConnect: () -> Unit
) {
    val text = if (darkMode) Color.White else LightText
    val muted = if (darkMode) Muted else LightMuted
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Servers", color = text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("${servers.size} imported servers", color = muted, fontSize = 12.sp)
        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onTestAll, enabled = servers.isNotEmpty() && !testing, modifier = Modifier.weight(1f)) { Text("PING ALL") }
            Button(onClick = onFastestConnect, enabled = servers.isNotEmpty() && !testing, modifier = Modifier.weight(1f)) { Text("FASTEST + CONNECT") }
        }
        Spacer(modifier = Modifier.height(10.dp))
        if (testing) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp))
            }
            Spacer(modifier = Modifier.height(10.dp))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(servers) { index, server ->
                val selectedThis = index == selected
                Card(
                    modifier = Modifier.fillMaxWidth().border(1.dp, if (selectedThis) Blue else Color.Transparent, RoundedCornerShape(15.dp)),
                    colors = CardDefaults.cardColors(if (darkMode) Panel else Color.White),
                    shape = RoundedCornerShape(15.dp)
                ) {
                    Row(modifier = Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f).clickable { onSelect(index) }) {
                            Text(server.name, color = text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text(server.protocol, color = muted, fontSize = 11.sp)
                            Text(if (server.latency >= 0) "Ping ${server.latency} ms" else "Ping --", color = if (server.latency >= 0) Green else muted, fontSize = 11.sp)
                        }
                        OutlinedButton(onClick = { onTest(index) }, enabled = !testing) { Text("TEST") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubscriptionScreen(
    darkMode: Boolean,
    url: String,
    onUrl: (String) -> Unit,
    importing: Boolean,
    message: String,
    serverCount: Int,
    onImport: () -> Unit,
    onTheme: () -> Unit
) {
    val text = if (darkMode) Color.White else LightText
    Column(modifier = Modifier.fillMaxSize().padding(18.dp)) {
        Text("Subscription", color = text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(value = url, onValueChange = onUrl, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Subscription URL") })
        Spacer(modifier = Modifier.height(10.dp))
        Button(onClick = onImport, enabled = !importing, modifier = Modifier.fillMaxWidth().height(50.dp)) {
            if (importing) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) else Text("IMPORT SERVERS", fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text("Saved servers: $serverCount", color = if (darkMode) Muted else LightMuted, fontSize = 12.sp)
        if (message.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(message, color = if (message.contains("success", true) || message.contains("Fastest", true)) Green else if (message.contains("No", true)) Red else Blue, textAlign = TextAlign.Start)
        }
        Spacer(modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onTheme, modifier = Modifier.fillMaxWidth()) { Text(if (darkMode) "LIGHT MODE" else "DARK MODE") }
    }
}

object SubscriptionLoader {
    private val client = OkHttpClient()

    fun load(url: String): List<ServerProfile> {
        val request = Request.Builder().url(url.trim()).header("User-Agent", "Bridge/1.0").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string().orEmpty()
            val decoded = decodeSubscription(body)
            return decoded.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("vless://", true) || it.startsWith("vmess://", true) || it.startsWith("trojan://", true) || it.startsWith("ss://", true) }
                .mapIndexed { index, uri ->
                    val u = try { Uri.parse(uri) } catch (_: Exception) { null }
                    val protocol = uri.substringBefore("://").uppercase()
                    val name = u?.fragment?.let { decodeText(it) }?.takeIf { it.isNotBlank() } ?: "Server ${index + 1}"
                    ServerProfile(name, uri, protocol)
                }
                .toList()
        }
    }

    private fun decodeSubscription(body: String): String {
        val trimmed = body.trim()
        if (trimmed.contains("://")) return trimmed.replace("\r", "")
        val compact = trimmed.replace("\r", "").replace("\n", "")
        return try {
            String(Base64.getDecoder().decode(pad(compact)), Charsets.UTF_8)
        } catch (_: Exception) {
            try {
                String(android.util.Base64.decode(pad(compact), android.util.Base64.DEFAULT), Charsets.UTF_8)
            } catch (_: Exception) {
                trimmed
            }
        }
    }

    private fun pad(value: String): String = value + "=".repeat((4 - value.length % 4) % 4)
    private fun decodeText(value: String): String = try { URLDecoder.decode(value, "UTF-8") } catch (_: Exception) { value }
}

private fun loadSavedServers(prefs: SharedPreferences): List<ServerProfile> {
    val raw = prefs.getString("servers", "").orEmpty()
    if (raw.isBlank()) return emptyList()
    return raw.split("\n").mapNotNull { line ->
        val parts = line.split("|", limit = 4)
        if (parts.size < 4) null else ServerProfile(parts[0], parts[2], parts[1], parts[3].toLongOrNull() ?: -1L)
    }
}

private fun saveServers(prefs: SharedPreferences, servers: List<ServerProfile>) {
    val raw = servers.joinToString("\n") { "${it.name}|${it.protocol}|${it.uri}|${it.latency}" }
    prefs.edit().putString("servers", raw).apply()
}
