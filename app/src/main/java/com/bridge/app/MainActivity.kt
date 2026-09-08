package com.bridge.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Base64

data class ServerProfile(val name: String, val uri: String, val protocol: String, var latency: Long = -1)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BridgeApp() }
    }
}

@Composable
fun BridgeApp() {
    var connected by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(0) }
    var subUrl by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Not connected") }
    var lastUpdate by remember { mutableStateOf("Never") }
    var servers by remember { mutableStateOf(listOf<ServerProfile>()) }
    var selected by remember { mutableStateOf(-1) }
    var updating by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Text("BRIDGE", fontSize = 28.sp)
                Spacer(Modifier.height(18.dp))
                when (tab) {
                    0 -> HomeScreen(connected, if (selected in servers.indices) servers[selected] else null, status) {
                        connected = !connected
                        status = if (connected) "Connected" else "Not connected"
                    }
                    1 -> ServersScreen(servers, selected) { selected = it }
                    2 -> SettingsScreen(subUrl, { subUrl = it }, lastUpdate, updating, message) {
                        if (subUrl.isBlank()) message = "Enter a subscription URL first." else scope.launch {
                            updating = true
                            message = ""
                            val result = SubscriptionLoader.load(subUrl)
                            servers = result
                            if (result.isNotEmpty()) {
                                selected = result.indices.minByOrNull { i -> if (result[i].latency >= 0) result[i].latency else Long.MAX_VALUE } ?: 0
                                lastUpdate = "Just now"
                                message = "${result.size} servers imported."
                            } else message = "No supported server profiles were found."
                            updating = false
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                NavigationBar {
                    NavigationBarItem(tab == 0, { tab = 0 }, label = { Text("Home") }, icon = {})
                    NavigationBarItem(tab == 1, { tab = 1 }, label = { Text("Servers") }, icon = {})
                    NavigationBarItem(tab == 2, { tab = 2 }, label = { Text("Settings") }, icon = {})
                }
            }
        }
    }
}

@Composable
fun HomeScreen(connected: Boolean, server: ServerProfile?, status: String, onToggle: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(25.dp))
        Text(if (connected) "CONNECTED" else "DISCONNECTED", fontSize = 18.sp)
        Spacer(Modifier.height(25.dp))
        Button(onClick = onToggle, modifier = Modifier.size(170.dp), shape = CircleShape) { Text(if (connected) "OFF" else "ON", fontSize = 24.sp) }
        Spacer(Modifier.height(30.dp))
        if (server != null) {
            Text(server.name, fontSize = 21.sp)
            Text(if (server.latency >= 0) "${server.latency} ms" else "Testing...", fontSize = 18.sp)
        } else Text("No server selected", fontSize = 18.sp)
        Spacer(Modifier.height(25.dp)); Text(status); Spacer(Modifier.height(30.dp))
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
            Row(Modifier.padding(20.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Text("Download"); Text("0 KB/s") }
                Column { Text("Upload"); Text("0 KB/s") }
            }
        }
    }
}

@Composable
fun ServersScreen(servers: List<ServerProfile>, selected: Int, onSelect: (Int) -> Unit) {
    Column {
        Text("SERVERS", fontSize = 22.sp); Spacer(Modifier.height(12.dp))
        if (servers.isEmpty()) Text("No servers yet. Add a subscription in Settings.") else {
            Button(onClick = {
                val fastest = servers.indices.filter { servers[it].latency >= 0 }.minByOrNull { servers[it].latency }
                if (fastest != null) onSelect(fastest)
            }) { Text("⚡ FASTEST SERVER") }
            LazyColumn {
                items(servers) { s ->
                    val i = servers.indexOf(s)
                    ListItem(headlineContent = { Text(s.name) }, supportingContent = { Text("${s.protocol}  •  " + if (s.latency >= 0) "${s.latency} ms" else "not tested") }, trailingContent = { if (i == selected) Text("✓") }, modifier = Modifier.fillMaxWidth())
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(url: String, onUrl: (String) -> Unit, lastUpdate: String, updating: Boolean, message: String, onUpdate: () -> Unit) {
    Column {
        Text("SETTINGS", fontSize = 22.sp); Spacer(Modifier.height(20.dp)); Text("Subscription"); Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = url, onValueChange = onUrl, modifier = Modifier.fillMaxWidth(), label = { Text("Subscription URL") }, singleLine = true)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onUpdate, modifier = Modifier.fillMaxWidth(), enabled = !updating) { Text(if (updating) "UPDATING..." else "UPDATE SUBSCRIPTION") }
        Spacer(Modifier.height(12.dp)); Text("Last Update: $lastUpdate")
        if (message.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(message) }
        Spacer(Modifier.height(28.dp)); Text("Plan"); Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { AssistChip(onClick = {}, label = { Text("FREE") }); AssistChip(onClick = {}, label = { Text("PRO") }); AssistChip(onClick = {}, label = { Text("PREMIUM") }) }
    }
}

object SubscriptionLoader {
    private val client = OkHttpClient()
    suspend fun load(url: String): List<ServerProfile> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).header("User-Agent", "Bridge/0.2").build()
            client.newCall(request).execute().use { response -> if (!response.isSuccessful) return@withContext emptyList(); parse(response.body?.string()?.trim().orEmpty()) }
        } catch (_: Exception) { emptyList() }
    }
    private fun parse(body: String): List<ServerProfile> {
        val decoded = decodeMaybeBase64(body); val lines = decoded.lines().map { it.trim() }.filter { it.isNotBlank() }; val output = mutableListOf<ServerProfile>()
        for (line in lines) {
            val clean = line.removePrefix("proxies:").trim(); val lower = clean.lowercase()
            val protocol = when { lower.startsWith("vless://") -> "VLESS"; lower.startsWith("vmess://") -> "VMess"; lower.startsWith("trojan://") -> "Trojan"; lower.startsWith("ss://") -> "Shadowsocks"; else -> null } ?: continue
            val name = clean.substringAfter("#", "").ifBlank { "$protocol Server ${output.size + 1}" }.replace("%20", " ").replace("+", " ")
            output += ServerProfile(name, clean, protocol)
        }
        return output.distinctBy { it.uri }
    }
    private fun decodeMaybeBase64(input: String): String {
        val compact = input.replace("\\s".toRegex(), "")
        return try { Base64.getDecoder().decode(compact).toString(Charsets.UTF_8) } catch (_: Exception) { input }
    }
}
