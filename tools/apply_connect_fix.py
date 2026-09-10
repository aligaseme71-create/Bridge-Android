from pathlib import Path

main = Path('app/src/main/java/com/bridge/app/MainActivity.kt')
s = main.read_text()

s = s.replace('var selected by remember { mutableStateOf(prefs.getInt("selected_server", if (servers.isNotEmpty()) 0 else -1)) }', 'var selected by remember { mutableStateOf(-1) }')

old = '''    fun toggleConnection() {
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
    }'''
new = '''    fun toggleConnection() {
        if (connected) {
            onDisconnect()
            message = "Disconnecting..."
            return
        }
        val server = servers.getOrNull(selected)
        if (server != null) {
            message = "Connecting to ${server.name}..."
            onConnect(server.uri)
        } else {
            if (servers.isEmpty()) {
                tab = Tab.SUBSCRIPTION
                message = "Add a subscription first."
            } else {
                testAllFn?.invoke(true)
            }
        }
    }'''
if old not in s:
    raise SystemExit('toggleConnection block not found')
s = s.replace(old, new)

s = s.replace('''                onToggle = ::toggleConnection,
                        onFastest = { testAll(true) },
                        canFastest = servers.isNotEmpty() && !testing,''', '''                onToggle = ::toggleConnection,''')

s = s.replace('''    onToggle: () -> Unit,
    onFastest: () -> Unit,
    canFastest: Boolean,''', '''    onToggle: () -> Unit,''')

old_home = '''            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onFastest, enabled = canFastest, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text(if (testing) "TESTING SERVERS..." else "FASTEST SERVER • CONNECT", fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(10.dp))'''
s = s.replace(old_home, '''            Spacer(modifier = Modifier.height(10.dp))''')

s = s.replace('''                selected = 0
                prefs.edit().putString("subscription_url", subUrl).putInt("selected_server", 0).apply()''', '''                selected = -1
                prefs.edit().putString("subscription_url", subUrl).remove("selected_server").apply()''')
s = s.replace('''                    selected = index
                            prefs.edit().putInt("selected_server", index).apply()''', '''                    selected = index''')
s = s.replace('''                    selected = best
                    prefs.edit().putInt("selected_server", best).apply()''', '''                    selected = best''')

old_power = '''    val accent = if (connected) Green else if (testing) Blue else Red
    Box(modifier = Modifier.size(220.dp).clip(CircleShape).clickable(enabled = !testing, onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension * .38f
            drawCircle(accent.copy(alpha = .12f), radius * 1.28f)
            drawCircle(Color(0xFF071726), radius * .98f)
            drawCircle(accent, radius, style = Stroke(width = 7.dp.toPx()))'''
new_power = '''    val accent = if (connected) Green else if (testing) Blue else Red
    val ringAlpha = if (testing) .42f else .12f
    Box(modifier = Modifier.size(220.dp).clip(CircleShape).clickable(enabled = !testing, onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension * .38f
            drawCircle(accent.copy(alpha = ringAlpha), radius * 1.28f)
            if (testing) drawCircle(Blue.copy(alpha = .22f), radius * 1.17f, style = Stroke(width = 5.dp.toPx()))
            drawCircle(Color(0xFF071726), radius * .98f)
            drawCircle(accent, radius, style = Stroke(width = 7.dp.toPx()))'''
if old_power not in s:
    raise SystemExit('PowerButton block not found')
s = s.replace(old_power, new_power)
s = s.replace('Text(if (connected) "DISCONNECT" else "CONNECT", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)', 'Text(if (connected) "DISCONNECT" else if (testing) "CONNECTING..." else "CONNECT", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)')
s = s.replace('    val scope = rememberCoroutineScope()\n', '    val scope = rememberCoroutineScope()\n    var testAllFn: ((Boolean) -> Unit)? = null\n', 1)
s = s.replace('''    fun testOne(index: Int) {''', '''    testAllFn = ::testAll

    fun testOne(index: Int) {''', 1)
main.write_text(s)

service = Path('app/src/main/java/com/bridge/app/BridgeVpnService.kt')
s = service.read_text()
s = s.replace('import android.net.VpnService\n', 'import android.net.VpnService\nimport android.net.ConnectivityManager\nimport android.net.IpPrefix\nimport android.net.Network\nimport java.net.Inet4Address\nimport java.net.Inet6Address\nimport java.net.InetAddress\n')
s = s.replace('    private var stopping = false\n', '    private var stopping = false\n    private var boundNetwork: Network? = null\n', 1)
old = '''            val config = XrayConfigBuilder.build(uri)
            BridgeVpnState.message = "Starting Xray..."

            vpnInterface = Builder()'''
new = '''            val config = XrayConfigBuilder.build(uri)
            BridgeVpnState.message = "Starting Xray..."

            // Bind the process to the physical network BEFORE establishing the VPN.
            // Android applies this binding to sockets created later by the embedded Xray core,
            // keeping its upstream connection outside the VPN tunnel and preventing routing loops.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val cm = getSystemService(ConnectivityManager::class.java)
                val network = cm.activeNetwork
                if (network != null && cm.bindProcessToNetwork(network)) {
                    boundNetwork = network
                    Log.i("BridgeVPN", "Xray process bound to underlying network: $network")
                } else {
                    Log.w("BridgeVPN", "Could not bind Xray process to underlying network")
                }
            }

            vpnInterface = Builder()'''
if old not in s:
    raise SystemExit('config/builder block not found')
s = s.replace(old, new, 1)
old2 = '''            val pfd = vpnInterface ?: throw IllegalStateException("Android refused to create the VPN interface")

            worker.execute {'''
new2 = '''            val pfd = vpnInterface ?: throw IllegalStateException("Android refused to create the VPN interface")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                boundNetwork?.let { setUnderlyingNetworks(arrayOf(it)) }
            }

            worker.execute {'''
if old2 not in s:
    raise SystemExit('pfd block not found')
s = s.replace(old2, new2, 1)
old3 = '''    private fun fail(text: String) {
        BridgeVpnState.connected = false
        BridgeVpnState.message = text
        Log.e("BridgeVPN", text)'''
new3 = '''    private fun fail(text: String) {
        BridgeVpnState.connected = false
        BridgeVpnState.message = text
        Log.e("BridgeVPN", text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try { getSystemService(ConnectivityManager::class.java).bindProcessToNetwork(null) } catch (_: Exception) { }
        }
        boundNetwork = null'''
if old3 not in s:
    raise SystemExit('fail block not found')
s = s.replace(old3, new3, 1)
old4 = '''        try { controller?.stopLoop() } catch (_: Exception) { }
        try { vpnInterface?.close() } catch (_: Exception) { }'''
new4 = '''        try { controller?.stopLoop() } catch (_: Exception) { }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try { getSystemService(ConnectivityManager::class.java).bindProcessToNetwork(null) } catch (_: Exception) { }
        }
        boundNetwork = null
        try { vpnInterface?.close() } catch (_: Exception) { }'''
if old4 not in s:
    raise SystemExit('stop block not found')
s = s.replace(old4, new4, 1)
service.write_text(s)

builder = Path('app/src/main/java/com/bridge/app/XrayConfigBuilder.kt')
s = builder.read_text()
marker = 'object XrayConfigBuilder {'
if marker not in s:
    raise SystemExit('XrayConfigBuilder object not found')
helper = '''object XrayConfigBuilder {

    fun extractServerHost(uri: String): String? {
        return try {
            val u = android.net.Uri.parse(uri)
            when (u.scheme?.lowercase()) {
                "vless", "trojan" -> u.host
                "vmess" -> {
                    val raw = uri.substringAfter("vmess://", "").substringBefore("#")
                    val decoded = try { android.util.Base64.decode(raw, android.util.Base64.DEFAULT).toString(Charsets.UTF_8) } catch (_: Exception) { "" }
                    if (decoded.isBlank()) null else org.json.JSONObject(decoded).optString("add").takeIf { it.isNotBlank() }
                }
                "ss" -> {
                    val part = uri.substringAfter("@", "").substringBefore("#")
                    part.substringBeforeLast(":").takeIf { it.isNotBlank() }
                }
                else -> u.host
            }
        } catch (_: Exception) { null }
    }
'''
if 'fun extractServerHost' not in s:
    s = s.replace(marker, helper, 1)
builder.write_text(s)
