from pathlib import Path
import re

main = Path('app/src/main/java/com/bridge/app/MainActivity.kt')
s = main.read_text()
s = s.replace('var selected by remember { mutableStateOf(prefs.getInt("selected_server", if (servers.isNotEmpty()) 0 else -1)) }', 'var selected by remember { mutableStateOf(-1) }')
if 'import java.net.InetSocketAddress' not in s:
    s = s.replace('import java.net.URLDecoder\n', 'import java.net.URLDecoder\nimport java.net.InetSocketAddress\nimport java.net.Socket\n')

start = s.index('    fun testAll(autoConnect: Boolean) {')
end = s.index('    fun importSubscription() {', start)
new_tests = r'''    fun endpointFor(uri: String): Pair<String, Int>? {
        return try {
            val u = Uri.parse(uri)
            when (u.scheme?.lowercase()) {
                "vless", "trojan" -> (u.host ?: return null) to if (u.port > 0) u.port else 443
                "vmess" -> {
                    val raw = uri.substringAfter("vmess://", "").substringBefore("#")
                    val decoded = Base64.getDecoder().decode(raw.padEnd(((raw.length + 3) / 4) * 4, '='))
                        .toString(Charsets.UTF_8)
                    val j = JSONObject(decoded)
                    val host = j.optString("add").ifBlank { j.optString("address") }
                    if (host.isBlank()) null else host to j.optInt("port", 443)
                }
                "ss" -> {
                    val raw = uri.substringAfter("ss://", "").substringBefore("#")
                    val decoded = if (raw.contains("@")) raw else Base64.getDecoder().decode(raw.padEnd(((raw.length + 3) / 4) * 4, '='))
                        .toString(Charsets.UTF_8)
                    val hp = decoded.substringAfterLast('@')
                    val last = hp.lastIndexOf(':')
                    if (last <= 0) null else hp.substring(0, last).trim('[', ']') to (hp.substring(last + 1).toIntOrNull() ?: 443)
                }
                else -> null
            }
        } catch (_: Exception) { null }
    }

    fun tcpPing(uri: String): Long {
        val endpoint = endpointFor(uri) ?: return -1L
        val startNs = System.nanoTime()
        return try {
            Socket().use { socket -> socket.connect(InetSocketAddress(endpoint.first, endpoint.second), 5000) }
            (System.nanoTime() - startNs) / 1_000_000L
        } catch (_: Exception) { -1L }
    }

    fun testAll(autoConnect: Boolean) {
        if (servers.isEmpty() || testing) return
        testing = true
        message = "Testing servers..."
        val snapshot = servers
        scope.launch(Dispatchers.IO) {
            val results = snapshot.map { profile -> async { tcpPing(profile.uri) } }.awaitAll()
            val refreshed = snapshot.mapIndexed { index, profile -> profile.copy(latency = results.getOrElse(index) { -1L }) }
            val best = refreshed.indices.filter { refreshed[it].latency >= 0L }.minByOrNull { refreshed[it].latency }
            withContext(Dispatchers.Main) {
                servers = refreshed
                saveServers(prefs, refreshed)
                testing = false
                if (best != null) {
                    selected = best
                    message = "Fastest: ${refreshed[best].name} • ${refreshed[best].latency} ms"
                    if (autoConnect) onConnect(refreshed[best].uri)
                } else message = "No reachable server was found."
            }
        }
    }

    fun testOne(index: Int) {
        if (index !in servers.indices || testing) return
        testing = true
        val profile = servers[index]
        message = "Testing ${profile.name}..."
        scope.launch(Dispatchers.IO) {
            val result = tcpPing(profile.uri)
            withContext(Dispatchers.Main) {
                servers = servers.toMutableList().also { it[index] = profile.copy(latency = result) }
                saveServers(prefs, servers)
                testing = false
                message = if (result >= 0L) "${profile.name}: $result ms" else "${profile.name}: unreachable"
            }
        }
    }

'''
s = s[:start] + new_tests + s[end:]

if 'var testAllFn: ((Boolean) -> Unit)? = null' not in s:
    s = s.replace('    val scope = rememberCoroutineScope()\n', '    val scope = rememberCoroutineScope()\n    var testAllFn: ((Boolean) -> Unit)? = null\n', 1)
if 'testAllFn = ::testAll' not in s:
    s = s.replace('    fun importSubscription() {', '    testAllFn = ::testAll\n\n    fun importSubscription() {', 1)

s = re.sub(r'    fun toggleConnection\(\) \{.*?\n    \}\n\n    fun endpointFor', r'''    fun toggleConnection() {
        if (connected) {
            onDisconnect()
            message = "Disconnecting..."
            return
        }
        val server = servers.getOrNull(selected)
        if (server != null) {
            message = "Connecting to ${server.name}..."
            onConnect(server.uri)
        } else if (servers.isEmpty()) {
            tab = Tab.SUBSCRIPTION
            message = "Add a subscription first."
        } else {
            testAllFn?.invoke(true)
        }
    }

    fun endpointFor''', s, count=1, flags=re.S)

s = re.sub(r'\s*selected = 0\s*\n\s*prefs\.edit\(\)\.putString\("subscription_url", subUrl\)\.putInt\("selected_server", 0\)\.apply\(\)', '\n                selected = -1\n                prefs.edit().putString("subscription_url", subUrl).remove("selected_server").apply()', s)
s = s.replace('''                            selected = index
                            prefs.edit().putInt("selected_server", index).apply()''', '                            selected = index')
s = re.sub(r'\s*onFastest = \{ testAll\(true\) \},\n\s*canFastest = servers\.isNotEmpty\(\) && !testing,', '', s)
s = s.replace('''    onToggle: () -> Unit,
    onFastest: () -> Unit,
    canFastest: Boolean,''', '''    onToggle: () -> Unit,''')
s = re.sub(r'\s*Spacer\(modifier = Modifier\.height\(12\.dp\)\)\n\s*Button\(onClick = onFastest, enabled = canFastest, modifier = Modifier\.fillMaxWidth\(\)\.height\(48\.dp\)\) \{\n\s*Text\(if \(testing\) "TESTING SERVERS\.\.\." else "FASTEST SERVER • CONNECT", fontWeight = FontWeight\.Bold\)\n\s*\}\n\s*Spacer\(modifier = Modifier\.height\(10\.dp\)', '\n            Spacer(modifier = Modifier.height(10.dp))', s)

if 'message = message,' not in s:
    s = s.replace('''                        testing = testing,
                        server = servers.getOrNull(selected),''', '''                        testing = testing,
                        message = message,
                        server = servers.getOrNull(selected),''')
s = s.replace('''    testing: Boolean,
    server: ServerProfile?,''', '''    testing: Boolean,
    message: String,
    server: ServerProfile?,''')
if 'if (message.isNotBlank()) {' not in s:
    s = s.replace('''            Text(if (connected) "Secure tunnel is active" else "Tap the power button to connect", color = muted, fontSize = 12.sp)''', '''            Text(if (connected) "Secure tunnel is active" else "Tap the power button to connect", color = muted, fontSize = 12.sp)
            if (message.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(message, color = if (connected) Green else if (testing) Blue else muted, fontSize = 11.sp, textAlign = TextAlign.Center)
            }''')
s = s.replace('''            connected = BridgeVpnState.connected
            delay(250)''', '''            connected = BridgeVpnState.connected
            val coreMessage = BridgeVpnState.message
            if (coreMessage.isNotBlank() && coreMessage != "Disconnected") message = coreMessage
            delay(250)''')
s = s.replace('Text(if (connected) "DISCONNECT" else "CONNECT", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)', 'Text(if (connected) "DISCONNECT" else if (testing) "CONNECTING..." else "CONNECT", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)')
main.write_text(s)

service = Path('app/src/main/java/com/bridge/app/BridgeVpnService.kt')
s = service.read_text()
for imp in ['import android.net.ConnectivityManager\n','import android.net.Network\n']:
    if imp not in s: s = s.replace('import android.net.VpnService\n', 'import android.net.VpnService\n'+imp)
if 'private var boundNetwork: Network? = null' not in s:
    s = s.replace('    private var stopping = false\n', '    private var stopping = false\n    private var boundNetwork: Network? = null\n', 1)
s = s.replace('''                BridgeVpnState.connected = true
                BridgeVpnState.message = "Connected"
                handler.post { updateNotification("Bridge connected") }''', '''                BridgeVpnState.connected = false
                BridgeVpnState.message = "Xray started; checking tunnel..."
                handler.post { updateNotification("Bridge is checking connection") }''')
needle = '''            val config = XrayConfigBuilder.build(uri)
            BridgeVpnState.message = "Starting Xray..."

            vpnInterface = Builder()'''
if needle in s and 'bindProcessToNetwork(network)' not in s:
    s=s.replace(needle, '''            val config = XrayConfigBuilder.build(uri)
            BridgeVpnState.message = "Starting Xray..."

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val cm = getSystemService(ConnectivityManager::class.java)
                val network = cm.activeNetwork
                if (network != null && cm.bindProcessToNetwork(network)) {
                    boundNetwork = network
                    Log.i("BridgeVPN", "Bound Xray process to physical network")
                } else Log.w("BridgeVPN", "Could not bind Xray process to physical network")
            }

            vpnInterface = Builder()''',1)
needle2='''                    core.startLoop(config, pfd.fd)
                    Log.i("BridgeVPN", "Xray startLoop returned")
                    if (!stopping && !BridgeVpnState.connected) {
                        handler.post { fail("Xray core stopped") }
                    }'''
if needle2 in s:
    s=s.replace(needle2, '''                    core.startLoop(config, pfd.fd)
                    Log.i("BridgeVPN", "Xray startLoop returned")
                    if (!stopping) {
                        try {
                            val delayMs = core.measureDelay("https://www.gstatic.com/generate_204")
                            if (delayMs >= 0L && !stopping) {
                                BridgeVpnState.connected = true
                                BridgeVpnState.message = "Connected • ${delayMs} ms"
                                handler.post { updateNotification("Bridge connected • ${delayMs} ms") }
                            } else if (!stopping) handler.post { fail("VPN started but proxy traffic failed") }
                        } catch (e: Exception) {
                            Log.e("BridgeVPN", "Outbound connectivity check failed", e)
                            if (!stopping) handler.post { fail("Proxy connection failed: ${e.message ?: e.javaClass.simpleName}") }
                        }
                    }''',1)
if 'bindProcessToNetwork(null)' not in s:
    s=s.replace('''        Log.e("BridgeVPN", text)
        try { updateNotification(text) }''', '''        Log.e("BridgeVPN", text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try { getSystemService(ConnectivityManager::class.java).bindProcessToNetwork(null) } catch (_: Exception) { }
        }
        boundNetwork = null
        try { updateNotification(text) }''',1)
    s=s.replace('''        try { controller?.stopLoop() } catch (_: Exception) { }
        try { vpnInterface?.close() } catch (_: Exception) { }''', '''        try { controller?.stopLoop() } catch (_: Exception) { }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try { getSystemService(ConnectivityManager::class.java).bindProcessToNetwork(null) } catch (_: Exception) { }
        }
        boundNetwork = null
        try { vpnInterface?.close() } catch (_: Exception) { }''',1)
service.write_text(s)
