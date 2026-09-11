package com.bridge.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.util.concurrent.Executors

/**
 * The Bridge VPN foreground service.
 *
 * Flow:
 *   1. Android grants VPN permission (handled in MainActivity).
 *   2. We build a full-tunnel TUN interface and hand its fd to Xray via
 *      startLoop(config, fd). The bundled AndroidLibXrayLite reads packets
 *      from that fd and routes them through the proxy outbound.
 *   3. Xray's own outbound sockets are protected with VpnService.protect()
 *      so they do NOT re-enter the tunnel (which would cause a routing loop).
 *   4. We only report CONNECTED after measureDelay() proves real proxy
 *      traffic works. Xray merely starting is NOT treated as connected.
 */
class BridgeVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.bridge.app.CONNECT"
        const val ACTION_DISCONNECT = "com.bridge.app.DISCONNECT"
        const val EXTRA_URI = "uri"
        private const val CHANNEL_ID = "bridge_vpn"
        private const val NOTIFICATION_ID = 1001
        private const val VERIFY_TIMEOUT_MS = 20000L
        private const val TAG = "BRIDGE_VPN"
        private const val PROBE_URL = "https://www.gstatic.com/generate_204"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var controller: CoreController? = null
    private var coreInitialized = false

    @Volatile private var stopping = false
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newCachedThreadPool()

    private val callback = object : CoreCallbackHandler {
        // Xray started internally. We do NOT mark connected here — we wait for
        // the proxy verification below.
        override fun startup(): Long {
            Log.i(TAG, "Xray startup callback")
            return 0L
        }

        override fun shutdown(): Long {
            Log.i(TAG, "Xray shutdown callback")
            return 0L
        }

        override fun onEmitStatus(code: Long, text: String?): Long {
            if (!text.isNullOrBlank()) Log.d("BRIDGE_XRAY", "status[$code]: $text")
            return 0L
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        initializeCore()
    }

    private fun initializeCore() {
        if (coreInitialized && controller != null) return
        try {
            Seq.setContext(applicationContext)
            Libv2ray.initCoreEnv(filesDir.absolutePath, "")
            controller = Libv2ray.newCoreController(callback)
            coreInitialized = true
            Log.i(TAG, "Core initialized")
        } catch (e: Exception) {
            coreInitialized = false
            controller = null
            setError("Core init failed: ${e.short()}")
            Log.e(TAG, "Core init failed", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> startTunnel(intent.getStringExtra(EXTRA_URI).orEmpty())
            ACTION_DISCONNECT -> stopTunnel()
        }
        return START_NOT_STICKY
    }

    private fun startTunnel(uri: String) {
        if (uri.isBlank()) { setError("No server selected"); return }

        stopTunnel(stopService = false, quiet = true)
        initializeCore()
        val core = controller ?: run { setError("VPN core is not available"); return }

        try {
            stopping = false
            setStage(BridgeVpnState.Stage.CONNECTING, "Preparing VPN...")
            startBridgeForeground("Bridge is connecting")

            val config = XrayConfigBuilder.buildTunnel(uri)
            Log.d("BRIDGE_CONFIG", "Config built (${config.length} bytes)")

            setStage(BridgeVpnState.Stage.CONNECTING, "Creating tunnel...")
            vpnInterface = Builder()
                .setSession("Bridge VPN")
                .setMtu(1500)
                .addAddress("10.0.0.2", 30)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .apply {
                    // Bridge itself must bypass the tunnel.
                    try { addDisallowedApplication(packageName) } catch (_: Exception) {}
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setMetered(false)
                }
                .establish()

            val pfd = vpnInterface
                ?: throw IllegalStateException("Android refused to create the VPN interface")

            setStage(BridgeVpnState.Stage.CONNECTING, "Starting Xray...")
            worker.execute {
                try {
                    // startLoop blocks while the tunnel runs.
                    core.startLoop(config, pfd.fd)
                    Log.i(TAG, "startLoop returned")
                    if (!stopping && BridgeVpnState.stage != BridgeVpnState.Stage.CONNECTED) {
                        handler.post { setError("Xray core stopped unexpectedly") }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "startLoop failed", e)
                    if (!stopping) handler.post { setError("Connection failed: ${e.short()}") }
                }
            }

            // Verify real proxy traffic on a separate thread.
            worker.execute { verifyConnection(core) }

        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            try { core.stopLoop() } catch (_: Exception) {}
            try { vpnInterface?.close() } catch (_: Exception) {}
            vpnInterface = null
            setError("Connection failed: ${e.short()}")
        }
    }

    /**
     * Poll measureDelay until it succeeds or we time out. Only then do we
     * report CONNECTED. This is what makes the green state MEAN something.
     */
    private fun verifyConnection(core: CoreController) {
        val deadline = System.currentTimeMillis() + VERIFY_TIMEOUT_MS
        var attempt = 0
        while (!stopping && System.currentTimeMillis() < deadline) {
            attempt++
            try {
                val delay = core.measureDelay(PROBE_URL)
                if (delay in 0..8000) {
                    if (!stopping) {
                        BridgeVpnState.latencyMs = delay
                        setStage(BridgeVpnState.Stage.CONNECTED, "Connected")
                        handler.post { updateNotification("Bridge connected • ${delay} ms") }
                    }
                    return
                }
            } catch (e: Exception) {
                Log.d(TAG, "verify attempt $attempt: ${e.short()}")
            }
            try { Thread.sleep(1500) } catch (_: InterruptedException) { return }
        }
        if (!stopping && BridgeVpnState.stage != BridgeVpnState.Stage.CONNECTED) {
            handler.post { setError("VPN started but proxy traffic failed") }
        }
    }

    /**
     * NOTE ON ROUTING LOOP:
     * Because we call addDisallowedApplication(packageName) on the tunnel
     * Builder, the entire Bridge process is excluded from the VPN. Xray runs
     * inside that same process, so its outbound sockets to the proxy server
     * automatically bypass the tunnel. That prevents the recursive
     * "Xray traffic re-enters its own tunnel" loop WITHOUT needing
     * bindProcessToNetwork (which caused instability in earlier versions).
     */

    private fun setStage(stage: BridgeVpnState.Stage, message: String) {
        BridgeVpnState.stage = stage
        BridgeVpnState.message = message
        Log.i(TAG, "stage=$stage msg=$message")
    }

    private fun setError(text: String) {
        BridgeVpnState.stage = BridgeVpnState.Stage.ERROR
        BridgeVpnState.message = text
        BridgeVpnState.latencyMs = -1L
        Log.e(TAG, "ERROR: $text")
        try { updateNotification(text) } catch (_: Exception) {}
        try { controller?.stopLoop() } catch (_: Exception) {}
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        handler.postDelayed({
            if (BridgeVpnState.stage == BridgeVpnState.Stage.ERROR) {
                BridgeVpnState.reset()
            }
            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        }, 1500L)
    }

    private fun stopTunnel(stopService: Boolean = true, quiet: Boolean = false) {
        stopping = true
        if (!quiet) setStage(BridgeVpnState.Stage.DISCONNECTING, "Disconnecting...")
        try { controller?.stopLoop() } catch (_: Exception) {}
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        if (!quiet) {
            BridgeVpnState.reset()
            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        }
        if (stopService) stopSelf()
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel(stopService = false)
        controller = null
        coreInitialized = false
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    private fun startBridgeForeground(text: String) {
        val notification = notification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Bridge VPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Bridge")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun Exception.short(): String = message ?: javaClass.simpleName
}
