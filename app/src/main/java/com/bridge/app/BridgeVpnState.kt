package com.bridge.app

object BridgeVpnState {
    @Volatile
    var connected: Boolean = false

    @Volatile
    var message: String = "Disconnected"
}
