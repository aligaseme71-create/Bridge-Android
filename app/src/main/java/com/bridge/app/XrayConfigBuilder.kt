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
            clean.startsWith("vless://", true) -> vless(clean)
            clean.startsWith("vmess://", true) -> vmess(clean)
            clean.startsWith("trojan://", true) -> trojan(clean)
            clean.startsWith("ss://", true) -> shadowsocks(clean)
            else -> throw IllegalArgumentException("Unsupported profile")
        }
        val config = JSONObject()
        config.put("log", JSONObject().put("loglevel", "warning"))
        config.put("inbounds", JSONArray().put(JSONObject()
            .put("port", 0)
            .put("protocol", "tun")
            .put("settings", JSONObject().put("name", "bridge0").put("mtu", 1500))))
        config.put("outbounds", JSONArray()
            .put(outbound.put("tag", "proxy"))
            .put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
            .put(JSONObject().put("tag", "block").put("protocol", "blackhole")))
        config.put("routing", JSONObject()
            .put("domainStrategy", "IPIfNonMatch")
            .put("rules", JSONArray().put(JSONObject()
                .put("type", "field")
                .put("inboundTag", JSONArray().put("bridge0"))
                .put("outboundTag", "proxy"))))
        return config.toString()
    }

    private fun vless(link: String): JSONObject {
        val u = Uri.parse(link)
        val user = URLDecoder.decode(u.userInfo ?: "", "UTF-8")
        val address = u.host ?: throw IllegalArgumentException("VLESS host missing")
        val port = if (u.port > 0) u.port else 443
        val q = u.queryParameterMap()
        val userObj = JSONObject().put("id", user).put("encryption", q["encryption"] ?: "none")
        q["flow"]?.takeIf { it.isNotBlank() }?.let { userObj.put("flow", it) }
        val vnext = JSONObject().put("address", address).put("port", port).put("users", JSONArray().put(userObj))
        val out = JSONObject().put("protocol", "vless").put("settings", JSONObject().put("vnext", JSONArray().put(vnext)))
        out.put("streamSettings", streamSettings(q))
        return out
    }

    private fun trojan(link: String): JSONObject {
        val u = Uri.parse(link)
        val password = URLDecoder.decode(u.userInfo ?: "", "UTF-8")
        val q = u.queryParameterMap()
        val server = JSONObject().put("address", u.host ?: throw IllegalArgumentException("Trojan host missing"))
            .put("port", if (u.port > 0) u.port else 443).put("password", password)
        val out = JSONObject().put("protocol", "trojan").put("settings", JSONObject().put("servers", JSONArray().put(server)))
        out.put("streamSettings", streamSettings(q))
        return out
    }

    private fun vmess(link: String): JSONObject {
        val encoded = link.substringAfter("vmess://").substringBefore("#")
        val json = String(Base64.decode(padBase64(encoded), Base64.DEFAULT), Charsets.UTF_8)
        val src = JSONObject(json)
        val address = src.optString("add").ifBlank { src.optString("address") }
        val port = src.optInt("port", 443)
        val user = src.optString("id")
        val userObj = JSONObject().put("id", user).put("alterId", src.optInt("aid", 0)).put("security", src.optString("scy", "auto"))
        val out = JSONObject().put("protocol", "vmess").put("settings", JSONObject().put("vnext", JSONArray().put(
            JSONObject().put("address", address).put("port", port).put("users", JSONArray().put(userObj)))))
        val q = linkedMapOf<String, String>()
        src.optString("net").takeIf { it.isNotBlank() }?.let { q["type"] = it }
        src.optString("host").takeIf { it.isNotBlank() }?.let { q["host"] = it }
        src.optString("path").takeIf { it.isNotBlank() }?.let { q["path"] = it }
        src.optString("tls").takeIf { it.isNotBlank() }?.let { q["security"] = if (it == "tls") "tls" else it }
        src.optString("sni").takeIf { it.isNotBlank() }?.let { q["sni"] = it }
        src.optString("fp").takeIf { it.isNotBlank() }?.let { q["fp"] = it }
        out.put("streamSettings", streamSettings(q))
        return out
    }

    private fun shadowsocks(link: String): JSONObject {
        val raw = link.substringAfter("ss://").substringBefore("#")
        val decoded = try { String(Base64.decode(padBase64(raw.substringBefore('@')), Base64.DEFAULT), Charsets.UTF_8) } catch (_: Exception) { "" }
        val at = raw.indexOf('@')
        val methodPassword: String
        val hostPort: String
        if (at >= 0) {
            methodPassword = URLDecoder.decode(raw.substring(0, at), "UTF-8")
            hostPort = raw.substring(at + 1)
        } else {
            val decodedFull = String(Base64.decode(padBase64(raw), Base64.DEFAULT), Charsets.UTF_8)
            val a = decodedFull.lastIndexOf('@')
            methodPassword = decodedFull.substring(0, a)
            hostPort = decodedFull.substring(a + 1)
        }
        val colon = hostPort.lastIndexOf(':')
        val host = hostPort.substring(0, colon).trim('[', ']')
        val port = hostPort.substring(colon + 1).toIntOrNull() ?: 443
        val split = methodPassword.split(':', limit = 2)
        val server = JSONObject().put("address", host).put("port", port).put("method", split.first()).put("password", split.getOrElse(1) { "" })
        return JSONObject().put("protocol", "shadowsocks").put("settings", JSONObject().put("servers", JSONArray().put(server)))
    }

    private fun streamSettings(q: Map<String, String>): JSONObject {
        val network = q["type"] ?: q["network"] ?: "tcp"
        val security = q["security"] ?: q["tls"] ?: "none"
        val s = JSONObject().put("network", network).put("security", if (security == "reality") "reality" else if (security == "tls") "tls" else "none")
        val host = q["host"] ?: q["hostHeader"]
        val path = q["path"] ?: "/"
        when (network.lowercase()) {
            "ws" -> s.put("wsSettings", JSONObject().put("path", decode(path)).apply { host?.let { put("headers", JSONObject().put("Host", it)) } })
            "grpc" -> s.put("grpcSettings", JSONObject().put("serviceName", decode(q["serviceName"] ?: path.trim('/'))))
            "http", "h2" -> s.put("httpSettings", JSONObject().put("path", decode(path)).apply { host?.let { put("host", JSONArray().put(it)) } })
            "tcp" -> if (!host.isNullOrBlank()) s.put("tcpSettings", JSONObject().put("header", JSONObject().put("type", "http").put("request", JSONObject().put("headers", JSONObject().put("Host", JSONArray().put(host))).put("path", JSONArray().put(path)))))
        }
        when (security.lowercase()) {
            "tls" -> s.put("tlsSettings", JSONObject().put("serverName", q["sni"] ?: host ?: "").apply {
                q["fp"]?.let { put("fingerprint", it) }
                q["alpn"]?.let { put("alpn", JSONArray(it.split(',').filter { v -> v.isNotBlank() })) }
                put("allowInsecure", q["allowInsecure"] == "1" || q["allowInsecure"] == "true")
            })
            "reality" -> s.put("realitySettings", JSONObject().put("serverName", q["sni"] ?: "").put("fingerprint", q["fp"] ?: "chrome").put("publicKey", q["pbk"] ?: q["publicKey"] ?: "").put("shortId", q["sid"] ?: "").put("spiderX", decode(q["spx"] ?: "/")))
        }
        return s
    }

    private fun decode(v: String): String = try { URLDecoder.decode(v, "UTF-8") } catch (_: Exception) { v }

    private fun padBase64(v: String): String = v + "=".repeat((4 - v.length % 4) % 4)

    private fun String.queryParameterMap(): Map<String, String> {
        val result = linkedMapOf<String, String>()
        Uri.parse(this).queryParameterNames.forEach { key -> result[key] = Uri.parse(this).getQueryParameter(key).orEmpty() }
        return result
    }
}
