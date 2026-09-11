package com.bridge.app

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

/**
 * Builds an Xray JSON config from a single proxy URI.
 *
 * Two build modes:
 *  - [buildTunnel]  : full VPN config with a socks inbound that the tun2socks
 *                     layer feeds. Used by BridgeVpnService.
 *  - [buildForTest] : same outbound, but with only the outbound (no inbound).
 *                     Used to measure real proxy latency without a TUN.
 */
object XrayConfigBuilder {

    /** Local SOCKS port that the tun2socks bridge connects to. */
    const val SOCKS_PORT = 10808

    private fun outboundFor(uri: String): JSONObject {
        val clean = uri.trim()
        return when {
            clean.startsWith("vless://", true) -> buildVless(clean)
            clean.startsWith("vmess://", true) -> buildVmess(clean)
            clean.startsWith("trojan://", true) -> buildTrojan(clean)
            clean.startsWith("ss://", true) -> buildShadowsocks(clean)
            else -> throw IllegalArgumentException("Unsupported profile")
        }.put("tag", "proxy")
    }

    /**
     * Full config for the running VPN. A local SOCKS + DNS inbound is exposed;
     * the tun2socks bridge (fed by the Android TUN fd) forwards all device
     * traffic into this SOCKS port, and Xray sends it out through the proxy.
     */
    fun buildTunnel(uri: String): String {
        val outbound = outboundFor(uri)

        val socksInbound = JSONObject()
            .put("tag", "socks-in")
            .put("protocol", "socks")
            .put("listen", "127.0.0.1")
            .put("port", SOCKS_PORT)
            .put("settings", JSONObject().put("udp", true).put("auth", "noauth"))
            .put("sniffing", JSONObject()
                .put("enabled", true)
                .put("destOverride", JSONArray().put("http").put("tls").put("quic")))

        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", JSONArray().put(socksInbound))
            .put("outbounds", JSONArray()
                .put(outbound)
                .put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
                .put(JSONObject().put("tag", "block").put("protocol", "blackhole")))
            .put("routing", JSONObject()
                .put("domainStrategy", "IPIfNonMatch")
                .put("rules", JSONArray()
                    .put(JSONObject()
                        .put("type", "field")
                        .put("outboundTag", "proxy")
                        .put("network", "tcp,udp"))))
            .toString()
    }

    /**
     * Outbound-only config used to measure REAL proxy latency (Layer 2) with
     * libv2ray's measureOutboundDelay. No inbound / no TUN involved.
     */
    fun buildForTest(uri: String): String {
        return JSONObject()
            .put("log", JSONObject().put("loglevel", "none"))
            .put("outbounds", JSONArray()
                .put(outboundFor(uri))
                .put(JSONObject().put("tag", "direct").put("protocol", "freedom")))
            .toString()
    }

    // ---- protocol builders ------------------------------------------------

    private fun buildVless(link: String): JSONObject {
        val u = Uri.parse(link)
        val id = decode(u.userInfo ?: "")
        val host = u.host ?: throw IllegalArgumentException("VLESS host missing")
        val port = if (u.port > 0) u.port else 443
        val q = query(link)
        val user = JSONObject().put("id", id).put("encryption", q["encryption"] ?: "none")
        q["flow"]?.takeIf { it.isNotBlank() }?.let { user.put("flow", it) }
        val vnext = JSONObject().put("address", host).put("port", port)
            .put("users", JSONArray().put(user))
        return JSONObject().put("protocol", "vless")
            .put("settings", JSONObject().put("vnext", JSONArray().put(vnext)))
            .put("streamSettings", stream(q))
    }

    private fun buildTrojan(link: String): JSONObject {
        val u = Uri.parse(link)
        val password = decode(u.userInfo ?: "")
        val q = query(link)
        val server = JSONObject()
            .put("address", u.host ?: throw IllegalArgumentException("Trojan host missing"))
            .put("port", if (u.port > 0) u.port else 443)
            .put("password", password)
        return JSONObject().put("protocol", "trojan")
            .put("settings", JSONObject().put("servers", JSONArray().put(server)))
            .put("streamSettings", stream(q))
    }

    private fun buildVmess(link: String): JSONObject {
        val encoded = link.substringAfter("vmess://").substringBefore('#')
        val src = JSONObject(String(ProxyTools.robustBase64Decode(encoded), Charsets.UTF_8))
        val address = src.optString("add").ifBlank { src.optString("address") }
        if (address.isBlank()) throw IllegalArgumentException("VMess host missing")
        val port = src.optInt("port", 443)
        val user = JSONObject()
            .put("id", src.optString("id"))
            .put("alterId", src.optInt("aid", 0))
            .put("security", src.optString("scy", "auto"))
        val out = JSONObject().put("protocol", "vmess")
            .put("settings", JSONObject().put("vnext", JSONArray().put(
                JSONObject().put("address", address).put("port", port)
                    .put("users", JSONArray().put(user)))))
        val q = linkedMapOf<String, String>()
        listOf("net" to "type", "host" to "host", "path" to "path", "tls" to "security",
            "sni" to "sni", "fp" to "fp", "mode" to "mode", "extra" to "extra").forEach { (from, to) ->
            src.optString(from).takeIf { it.isNotBlank() }?.let {
                q[to] = if (from == "tls" && it == "tls") "tls" else it
            }
        }
        return out.put("streamSettings", stream(q))
    }

    private fun buildShadowsocks(link: String): JSONObject {
        val raw = link.substringAfter("ss://").substringBefore('#')
        val at = raw.indexOf('@')
        val methodPassword: String
        val hostPort: String
        if (at >= 0) {
            methodPassword = decode(raw.substring(0, at))
            hostPort = raw.substring(at + 1)
        } else {
            val decoded = String(ProxyTools.robustBase64Decode(raw), Charsets.UTF_8)
            val splitAt = decoded.lastIndexOf('@')
            if (splitAt <= 0) throw IllegalArgumentException("Invalid Shadowsocks profile")
            methodPassword = decoded.substring(0, splitAt)
            hostPort = decoded.substring(splitAt + 1)
        }
        val colon = hostPort.lastIndexOf(':')
        if (colon <= 0) throw IllegalArgumentException("Shadowsocks host missing")
        val host = hostPort.substring(0, colon).trim('[', ']')
        val port = hostPort.substring(colon + 1).toIntOrNull() ?: 443
        val pair = methodPassword.split(':', limit = 2)
        val server = JSONObject().put("address", host).put("port", port)
            .put("method", pair.first()).put("password", pair.getOrElse(1) { "" })
        return JSONObject().put("protocol", "shadowsocks")
            .put("settings", JSONObject().put("servers", JSONArray().put(server)))
    }

    // ---- stream / transport ----------------------------------------------

    private fun stream(q: Map<String, String>): JSONObject {
        val network = (q["type"] ?: q["network"] ?: "tcp").lowercase()
        val security = (q["security"] ?: q["tls"] ?: "none").lowercase()
        val securityValue = if (security == "tls" || security == "reality") security else "none"
        val s = JSONObject().put("network", network).put("security", securityValue)
        val host = q["host"] ?: q["hostHeader"]
        val path = q["path"] ?: "/"
        when (network) {
            "ws" -> {
                val ws = JSONObject().put("path", decode(path))
                host?.let { ws.put("headers", JSONObject().put("Host", it)) }
                s.put("wsSettings", ws)
            }
            "grpc" -> s.put("grpcSettings",
                JSONObject().put("serviceName", decode(q["serviceName"] ?: path.trim('/'))))
            "http", "h2" -> {
                val http = JSONObject().put("path", decode(path))
                host?.let { http.put("host", JSONArray().put(it)) }
                s.put("httpSettings", http)
            }
            "httpupgrade" -> {
                val hu = JSONObject().put("path", decode(path))
                host?.let { hu.put("host", it) }
                s.put("httpupgradeSettings", hu)
            }
            "xhttp" -> {
                val x = JSONObject().put("path", decode(path)).put("mode", q["mode"] ?: "auto")
                host?.let { x.put("host", it) }
                q["extra"]?.takeIf { it.isNotBlank() }?.let {
                    try { x.put("extra", JSONObject(decode(it))) } catch (_: Exception) { }
                }
                s.put("xhttpSettings", x)
            }
            "tcp" -> if (!host.isNullOrBlank()) {
                val header = JSONObject().put("type", "http").put("request", JSONObject()
                    .put("headers", JSONObject().put("Host", JSONArray().put(host)))
                    .put("path", JSONArray().put(path)))
                s.put("tcpSettings", JSONObject().put("header", header))
            }
        }
        when (security) {
            "tls" -> {
                val tls = JSONObject().put("serverName", q["sni"] ?: host ?: "")
                q["fp"]?.let { tls.put("fingerprint", it) }
                q["alpn"]?.let { tls.put("alpn", JSONArray(it.split(',').filter(String::isNotBlank))) }
                tls.put("allowInsecure", q["allowInsecure"] == "1" || q["allowInsecure"] == "true")
                s.put("tlsSettings", tls)
            }
            "reality" -> {
                val reality = JSONObject()
                    .put("serverName", q["sni"] ?: "")
                    .put("fingerprint", q["fp"] ?: "chrome")
                    .put("publicKey", q["pbk"] ?: q["publicKey"] ?: "")
                    .put("shortId", q["sid"] ?: "")
                    .put("spiderX", decode(q["spx"] ?: "/"))
                s.put("realitySettings", reality)
            }
        }
        return s
    }

    private fun query(link: String): Map<String, String> {
        val u = Uri.parse(link)
        return u.queryParameterNames.associateWith { u.getQueryParameter(it).orEmpty() }
    }

    private fun decode(value: String): String =
        try { URLDecoder.decode(value, "UTF-8") } catch (_: Exception) { value }
}
