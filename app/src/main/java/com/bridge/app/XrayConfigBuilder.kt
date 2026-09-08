package com.bridge.app

import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

object XrayConfigBuilder {
    fun build(uri: String): String {
        val clean = uri.trim()
        val outbound = when {
            clean.startsWith("vless://", true) -> buildVless(clean)
            clean.startsWith("vmess://", true) -> buildVmess(clean)
            clean.startsWith("trojan://", true) -> buildTrojan(clean)
            clean.startsWith("ss://", true) -> buildShadowsocks(clean)
            else -> throw IllegalArgumentException("Unsupported profile")
        }

        val tun = JSONObject()
            .put("tag", "tun")
            .put("port", 0)
            .put("protocol", "tun")
            .put("settings", JSONObject().put("name", "bridge0").put("MTU", 1500))
            .put("sniffing", JSONObject()
                .put("enabled", true)
                .put("routeOnly", false)
                .put("destOverride", JSONArray().put("http").put("tls").put("quic")))

        val config = JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", JSONArray().put(tun))
            .put("outbounds", JSONArray()
                .put(outbound.put("tag", "proxy"))
                .put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
                .put(JSONObject().put("tag", "block").put("protocol", "blackhole")))
            .put("routing", JSONObject()
                .put("domainStrategy", "IPIfNonMatch")
                .put("rules", JSONArray().put(JSONObject()
                    .put("type", "field")
                    .put("inboundTag", JSONArray().put("tun"))
                    .put("outboundTag", "proxy"))))
        return config.toString()
    }

    private fun buildVless(link: String): JSONObject {
        val u = Uri.parse(link)
        val id = decode(u.userInfo ?: "")
        val host = u.host ?: throw IllegalArgumentException("VLESS host missing")
        val port = if (u.port > 0) u.port else 443
        val q = query(link)
        val user = JSONObject().put("id", id).put("encryption", q["encryption"] ?: "none")
        q["flow"]?.takeIf { it.isNotBlank() }?.let { user.put("flow", it) }
        val vnext = JSONObject().put("address", host).put("port", port).put("users", JSONArray().put(user))
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
        val src = JSONObject(String(Base64.decode(pad(encoded), Base64.DEFAULT), Charsets.UTF_8))
        val address = src.optString("add").ifBlank { src.optString("address") }
        if (address.isBlank()) throw IllegalArgumentException("VMess host missing")
        val port = src.optInt("port", 443)
        val user = JSONObject()
            .put("id", src.optString("id"))
            .put("alterId", src.optInt("aid", 0))
            .put("security", src.optString("scy", "auto"))
        val out = JSONObject().put("protocol", "vmess")
            .put("settings", JSONObject().put("vnext", JSONArray().put(
                JSONObject().put("address", address).put("port", port).put("users", JSONArray().put(user)))))
        val q = linkedMapOf<String, String>()
        listOf("net" to "type", "host" to "host", "path" to "path", "tls" to "security", "sni" to "sni", "fp" to "fp", "mode" to "mode", "extra" to "extra").forEach { (from, to) ->
            src.optString(from).takeIf { it.isNotBlank() }?.let { q[to] = if (from == "tls" && it == "tls") "tls" else it }
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
            val decoded = String(Base64.decode(pad(raw), Base64.DEFAULT), Charsets.UTF_8)
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
            "grpc" -> s.put("grpcSettings", JSONObject().put("serviceName", decode(q["serviceName"] ?: path.trim('/'))))
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

    private fun decode(value: String): String = try { URLDecoder.decode(value, "UTF-8") } catch (_: Exception) { value }

    private fun pad(value: String): String = value + "=".repeat((4 - value.length % 4) % 4)
}
