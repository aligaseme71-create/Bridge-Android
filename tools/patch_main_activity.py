from pathlib import Path

path = Path("app/src/main/java/com/bridge/app/MainActivity.kt")
s = path.read_text(encoding="utf-8")

# Android's VpnService lifecycle is designed around startService() after
# VpnService.prepare() grants consent. The service promotes itself to the
# foreground with startForeground() once started.
s = s.replace(
    "ContextCompat.startForegroundService(this, intent)",
    "startService(intent)",
    1,
)

if "import kotlinx.coroutines.async" not in s:
    s = s.replace(
        "import kotlinx.coroutines.Dispatchers\n",
        "import kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.async\nimport kotlinx.coroutines.awaitAll\n",
        1,
    )

if "fun testAllServers()" not in s:
    marker = "    fun selectServer(index: Int) {"
    insert = '''    fun testAllServers() {
        if (servers.isEmpty() || testing) return
        testing = true
        val snapshot = servers
        scope.launch(Dispatchers.IO) {
            val results = snapshot.map { profile ->
                async {
                    try {
                        val config = XrayConfigBuilder.build(profile.uri)
                        libv2ray.Libv2ray.measureOutboundDelay(config, "https://www.gstatic.com/generate_204")
                    } catch (_: Exception) { -1L }
                }
            }.awaitAll()
            withContext(Dispatchers.Main) {
                servers = snapshot.mapIndexed { index, profile -> profile.copy(latency = results.getOrElse(index) { -1L }) }
                testing = false
                saveServers(prefs, servers)
            }
        }
    }

'''
    if marker not in s:
        raise SystemExit("selectServer marker not found")
    s = s.replace(marker, insert + marker, 1)

old_call = '1 -> ServersScreen(darkMode, servers, selected, testing, ::selectServer) { tab = 0 }'
new_call = '1 -> ServersScreen(darkMode, servers, selected, testing, ::selectServer, ::testAllServers) { tab = 0 }'
if old_call in s:
    s = s.replace(old_call, new_call, 1)

old_sig = 'private fun ServersScreen(darkMode: Boolean, servers: List<ServerProfile>, selected: Int, testing: Boolean, onSelect: (Int) -> Unit, onBack: () -> Unit) {'
new_sig = 'private fun ServersScreen(darkMode: Boolean, servers: List<ServerProfile>, selected: Int, testing: Boolean, onSelect: (Int) -> Unit, onPingAll: () -> Unit, onBack: () -> Unit) {'
if old_sig in s:
    s = s.replace(old_sig, new_sig, 1)

old_block = '''        if (testing) Text("Testing selected server...", color = Blue, fontSize = 13.sp)
        Spacer(modifier = Modifier.height(8.dp))
'''
new_block = '''        if (testing) Text("Testing all servers...", color = Blue, fontSize = 13.sp)
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = onPingAll,
            enabled = servers.isNotEmpty() && !testing,
            modifier = Modifier.fillMaxWidth().height(46.dp),
            shape = RoundedCornerShape(13.dp)
        ) {
            Text(if (testing) "PINGING..." else "PING ALL SERVERS", fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(10.dp)
'''
if old_block in s:
    s = s.replace(old_block, new_block, 1)

path.write_text(s, encoding="utf-8")
print("MainActivity patch applied")
