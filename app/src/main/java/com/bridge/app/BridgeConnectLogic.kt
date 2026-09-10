package com.bridge.app

object BridgeConnectLogic {
    fun chooseUri(servers: List<ServerProfile>, selected: Int): String? = servers.getOrNull(selected)?.uri
    fun fastestIndex(servers: List<ServerProfile>): Int? = servers.indices.filter { servers[it].latency >= 0L }.minByOrNull { servers[it].latency }
}
