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

class BridgeVpnService : VpnService() {
    companion object {
        const val ACTION_CONNECT = "com.bridge.app.CONNECT"
        const val ACTION_DISCONNECT = "com.bridge.app.DISCONNECT"
        const val EXTRA_URI = "uri"
        private const val CHANNEL_ID = "bridge_vpn"
        private const val NOTIFICATION_ID = 1001
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var controller: CoreController? = null
    private var coreInitialized = false
    private var stopping = false
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private val callback = object : CoreCallbackHandler {
        override fun startup(): Long {
            BridgeVpnState.connected = true
            BridgeVpnState.message = "Connected"
            handler.post { updateNotification("Bridge connected") }
            return 0L
        }

        override fun shutdown(): Long {
            BridgeVpnState.connected = false
            BridgeVpnState.message = "Disconnected"
            return 0L
        }

        override fun onEmitStatus(code: Long, text: String?): Long {
            if (!text.isNullOrBlank()) BridgeVpnState.message = text
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
            Libv2ray.initCoreEnv(filesDir.absolutePath, "bridge")
            controller = Libv2ray.newCoreController(callback)
            coreInitialized = true
            BridgeVpnState.message = "Core ready"
        } catch (e: Exception) {
            coreInitialized = false
            controller = null
            BridgeVpnState.message = "Core init failed: ${e.message ?: e.javaClass.simpleName}"
            Log.e("BridgeVPN", "Core init failed", e)
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
        if (uri.isBlank()) {
            fail("No server selected")
            return
        }

        stopTunnel(false)
        initializeCore()
        val core = controller
        if (core == null) {
            fail("VPN core is not available")
            return
        }

        try {
            stopping = false
            startBridgeForeground("Bridge is connecting")
            BridgeVpnState.connected = false
            BridgeVpnState.message = "Preparing VPN..."

            val config = XrayConfigBuilder.build(uri)
            BridgeVpnState.message = "Starting Xray..."

            vpnInterface = Builder()
                .setSession("Bridge VPN")
                .setMtu(1500)
                .addAddress("10.0.0.2", 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .apply {
                    addDisallowedApplication(packageName)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setMetered(false)
                }
                .establish()

            val pfd = vpnInterface ?: throw IllegalStateException("Android refused to create the VPN interface")

            worker.execute {
                try {
                    core.startLoop(config, pfd.fd)
                    handler.post {
                        if (!stopping && !core.isRunning && !BridgeVpnState.connected) {
                            fail("Xray core stopped during startup")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("BridgeVPN", "Xray startLoop failed", e)
                    handler.post { if (!stopping) fail("Connection failed: ${e.message ?: e.javaClass.simpleName}") }
                }
            }
        } catch (e: Exception) {
            Log.e("BridgeVPN", "Start failed", e)
            try { core.stopLoop() } catch (_: Exception) { }
            try { vpnInterface?.close() } catch (_: Exception) { }
            vpnInterface = null
            fail("Connection failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun fail(text: String) {
        BridgeVpnState.connected = false
        BridgeVpnState.message = text
        Log.e("BridgeVPN", text)
        try { updateNotification(text) } catch (_: Exception) { }
        handler.postDelayed({ try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) { } }, 5000L)
    }

    private fun stopTunnel(stopService: Boolean = true) {
        stopping = true
        try { controller?.stopLoop() } catch (_: Exception) { }
        try { vpnInterface?.close() } catch (_: Exception) { }
        vpnInterface = null
        BridgeVpnState.connected = false
        BridgeVpnState.message = "Disconnected"
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
        if (stopService) stopSelf()
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel(false)
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
}
