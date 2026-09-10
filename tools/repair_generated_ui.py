from pathlib import Path

p = Path('app/src/main/java/com/bridge/app/MainActivity.kt')
s = p.read_text()

s = s.replace(
    '''                        testing = testing,\n                        server = servers.getOrNull(selected),''',
    '''                        testing = testing,\n                        message = message,\n                        server = servers.getOrNull(selected),'''
)

start = s.index('@Composable\nprivate fun HomeScreen(')
end = s.index('@Composable\nprivate fun PowerButton', start)

home = '''@Composable
private fun HomeScreen(
    darkMode: Boolean,
    connected: Boolean,
    testing: Boolean,
    message: String,
    server: ServerProfile?,
    onToggle: () -> Unit,
    onServers: () -> Unit,
    onSubscription: () -> Unit,
    onTheme: () -> Unit
) {
    val text = if (darkMode) Color.White else LightText
    val muted = if (darkMode) Muted else LightMuted
    val bg = if (darkMode) Navy else LightBg

    Box(modifier = Modifier.fillMaxSize().background(bg)) {
        BridgeBackdrop(darkMode)
        Column(
            modifier = Modifier.fillMaxSize().padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
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
            Text(
                if (connected) "Secure tunnel is active" else if (testing) "Checking servers..." else "Tap the power button to connect",
                color = muted,
                fontSize = 12.sp
            )
            if (message.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    message,
                    color = if (connected) Green else if (testing) Blue else muted,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
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

'''

s = s[:start] + home + s[end:]
p.write_text(s)
print('Generated MainActivity UI repaired.')
