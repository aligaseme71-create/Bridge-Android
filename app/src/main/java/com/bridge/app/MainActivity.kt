package com.bridge.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Base64

data class ServerProfile(val name: String, val uri: String, val protocol: String, val latency: Long = -1)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { BridgeApp() } }
}

private val Navy = Color(0xFF03111F)
private val Panel = Color(0xCC0A1C2D)
private val Blue = Color(0xFF159BFF)
private val Cyan = Color(0xFF00D9FF)
private val Muted = Color(0xFFAABBCD)

@Composable
fun BridgeApp() {
    var connected by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(0) }
    var subUrl by remember { mutableStateOf("") }
    var servers by remember { mutableStateOf(listOf<ServerProfile>()) }
    var selected by remember { mutableStateOf(-1) }
    var updating by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    MaterialTheme(colorScheme = darkColorScheme(background = Navy, surface = Panel, primary = Blue)) {
        Surface(Modifier.fillMaxSize(), color = Navy) {
            Box(Modifier.fillMaxSize()) {
                when (tab) {
                    0 -> HomeScreen(connected, servers.getOrNull(selected), { connected = !connected }, { tab = 1 }, { tab = 2 })
                    1 -> ServersScreen(servers, selected) { selected = it }
                    else -> SettingsScreen(subUrl, { subUrl = it }, updating, message) {
                        if (subUrl.isBlank()) message = "Enter a subscription URL first."
                        else scope.launch {
                            updating = true; message = ""
                            val result = SubscriptionLoader.load(subUrl)
                            servers = result; selected = if (result.isNotEmpty()) 0 else -1
                            message = if (result.isNotEmpty()) "${result.size} servers imported." else "No supported profiles found."
                            updating = false
                        }
                    }
                }
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) { BottomNav(tab) { tab = it } }
            }
        }
    }
}

@Composable
private fun BridgeBackdrop() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF062849), Navy, Color(0xFF010811))))
        val globe = Offset(size.width * .5f, size.height * .29f)
        drawCircle(Color(0x2200BFFF), size.width * .43f, globe)
        for (i in 0..22) {
            val x = size.width * i / 22f
            val y = size.height * (.18f + (i % 6) * .018f)
            drawCircle(Color(0xAA28BFFF), 2f, Offset(x, y))
        }
        val y = size.height * .41f
        drawLine(Color(0xFF78CFFF), Offset(0f, y), Offset(size.width, y), 4f)
        listOf(.18f, .72f).forEach { x ->
            drawLine(Color(0xFF5B96B7), Offset(size.width*x, y), Offset(size.width*x, y+size.height*.17f), 5f)
            drawLine(Color(0xFF9EDFFF), Offset(size.width*x, y), Offset(size.width*(x-.10f), y+size.height*.10f), 2f)
            drawLine(Color(0xFF9EDFFF), Offset(size.width*x, y), Offset(size.width*(x+.10f), y+size.height*.10f), 2f)
        }
        for (i in 0..18) drawLine(Color(0x3325AFFF), Offset(size.width*i/18f, y+size.height*.03f), Offset(size.width*i/18f, size.height*.60f), 1f)
    }
}

@Composable
private fun HomeScreen(connected: Boolean, server: ServerProfile?, onToggle: () -> Unit, onServers: () -> Unit, onSettings: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        BridgeBackdrop()
        Column(Modifier.fillMaxSize().padding(18.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("☰", Color.White, 30.sp)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Bridge", Color.White, 38.sp, FontWeight.Bold)
                    Text("SECURE  •  PRIVATE  •  GLOBAL", Muted, 11.sp, letterSpacing = 2.sp)
                }
                Text("⚙", Color.White, 28.sp, modifier = Modifier.clickable { onSettings() })
            }
            Spacer(Modifier.height(22.dp))
            Text("A SAFER\nCONNECTED\nWORLD", Muted, 16.sp, FontWeight.Medium, lineHeight = 24.sp)
            Spacer(Modifier.weight(1f))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.size(210.dp).border(7.dp, Blue, CircleShape).padding(8.dp).background(Color(0xDD071726), CircleShape).clickable { onToggle() }, contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("⏻", Color.White, 58.sp)
                        Text(if (connected) "CONNECTED" else "CONNECT", Color.White, 16.sp, letterSpacing = 3.sp)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Card(Modifier.fillMaxWidth().clickable { onServers() }, colors = CardDefaults.cardColors(Panel), shape = RoundedCornerShape(20.dp)) {
                Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("◉", Blue, 30.sp); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) { Text(server?.name ?: "Auto Select", Color.White, 19.sp); Text(server?.protocol ?: "Fastest Server", Muted, 14.sp) }
                    Text("›", Muted, 34.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                StatCard("⚡", "Ping", if (server?.latency ?: -1 >= 0) "${server!!.latency} ms" else "-- ms", Modifier.weight(1f))
                StatCard("↓", "Download", "-- Mbps", Modifier.weight(1f))
                StatCard("↑", "Upload", "-- Mbps", Modifier.weight(1f))
                StatCard("⌖", "Location", "--", Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Card(Modifier.fillMaxWidth().clickable { onSettings() }, colors = CardDefaults.cardColors(Panel), shape = RoundedCornerShape(20.dp)) {
                Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("🔗", fontSize = 26.sp); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text("Subscription Link", Color.White, 18.sp); Text("Add your subscription (v2ray / xray)", Muted, 13.sp) }; Text("›", Muted, 32.sp)
                }
            }
            Spacer(Modifier.height(74.dp))
        }
    }
}

@Composable private fun StatCard(icon: String, title: String, value: String, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(Color(0xCC091B2C)), shape = RoundedCornerShape(15.dp)) { Column(Modifier.padding(vertical = 9.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) { Text(icon, Blue, 20.sp); Text(title, Muted, 11.sp); Text(value, Color.White, 11.sp) } }
}

@Composable private fun BottomNav(tab: Int, onTab: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(68.dp).background(Color(0xF20A1A2A), RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        listOf("⌂" to "Home", "▤" to "Servers", "▧" to "Settings").forEachIndexed { i, item -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onTab(i) }) { Text(item.first, if (tab == i) Blue else Muted, 25.sp); Text(item.second, if (tab == i) Blue else Muted, 11.sp) } }
    }
}

@Composable private fun ServersScreen(servers: List<ServerProfile>, selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxSize().padding(18.dp)) { Text("SERVERS", Color.White, 26.sp, FontWeight.Bold); Spacer(Modifier.height(16.dp)); if (servers.isEmpty()) Text("No servers yet. Add a subscription in Settings.", color = Muted) else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) { itemsIndexed(servers) { i, s -> Card(Modifier.fillMaxWidth().clickable { onSelect(i) }, colors = CardDefaults.cardColors(if (i == selected) Color(0xCC123557) else Panel), shape = RoundedCornerShape(16.dp)) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Text("◉", Blue, 24.sp); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(s.name, Color.White); Text(s.protocol, Muted, 13.sp) }; if (i == selected) Text("✓", Cyan, 22.sp) } } } } }
}

@Composable private fun SettingsScreen(url: String, onUrl: (String) -> Unit, updating: Boolean, message: String, onUpdate: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(18.dp)) { Text("SETTINGS", Color.White, 26.sp, FontWeight.Bold); Spacer(Modifier.height(22.dp)); Text("Subscription", Muted); Spacer(Modifier.height(8.dp)); OutlinedTextField(value = url, onValueChange = onUrl, modifier = Modifier.fillMaxWidth(), label = { Text("Subscription URL") }, singleLine = true); Spacer(Modifier.height(12.dp)); Button(onClick = onUpdate, Modifier.fillMaxWidth(), enabled = !updating) { Text(if (updating) "UPDATING..." else "UPDATE SUBSCRIPTION") }; if (message.isNotBlank()) { Spacer(Modifier.height(12.dp)); Text(message, Cyan) }; Spacer(Modifier.height(28.dp)); Text("Supported: VLESS • VMess • Trojan • Shadowsocks", Muted) }
}

object SubscriptionLoader {
    private val client = OkHttpClient()
    suspend fun load(url: String): List<ServerProfile> = withContext(Dispatchers.IO) { try { val r = Request.Builder().url(url).header("User-Agent", "Bridge/0.3").build(); client.newCall(r).execute().use { x -> if (!x.isSuccessful) return@withContext emptyList(); parse(x.body?.string()?.trim().orEmpty()) } } catch (_: Exception) { emptyList() } }
    private fun parse(body: String): List<ServerProfile> { val decoded = decode(body); val out = mutableListOf<ServerProfile>(); for (line in decoded.lines().map { it.trim() }.filter { it.isNotBlank() }) { val c = line.removePrefix("proxies:").trim(); val l = c.lowercase(); val p = when { l.startsWith("vless://") -> "VLESS"; l.startsWith("vmess://") -> "VMess"; l.startsWith("trojan://") -> "Trojan"; l.startsWith("ss://") -> "Shadowsocks"; else -> null } ?: continue; val n = c.substringAfter("#", "").ifBlank { "$p Server ${out.size + 1}" }.replace("%20", " ").replace("+", " "); out += ServerProfile(n, c, p) }; return out.distinctBy { it.uri } }
    private fun decode(input: String): String { val compact = input.replace("\\s".toRegex(), ""); return try { Base64.getDecoder().decode(compact).toString(Charsets.UTF_8) } catch (_: Exception) { input } }
}
