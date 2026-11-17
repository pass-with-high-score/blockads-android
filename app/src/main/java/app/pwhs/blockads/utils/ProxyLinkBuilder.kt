package app.pwhs.blockads.utils

import android.util.Base64
import app.pwhs.blockads.data.entities.ProxyProfile
import app.pwhs.blockads.data.entities.ProxyProtocol
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object ProxyLinkBuilder {

    fun buildLink(profile: ProxyProfile): String {
        return when (profile.protocol) {
            ProxyProtocol.SHADOWSOCKS -> buildShadowsocks(profile)
            ProxyProtocol.TROJAN -> buildTrojan(profile)
            ProxyProtocol.VLESS -> buildVless(profile)
            ProxyProtocol.VMESS -> buildVmess(profile)
            ProxyProtocol.HYSTERIA2 -> buildHysteria2(profile)
            ProxyProtocol.TUIC -> buildTuic(profile)
            ProxyProtocol.MASQUE -> buildMasque(profile)
            ProxyProtocol.SOCKS5 -> buildUserPassUri("socks5", profile)
            ProxyProtocol.SOCKS4 -> buildUserPassUri("socks4", profile)
            ProxyProtocol.HTTP -> buildUserPassUri("http", profile)
            ProxyProtocol.HTTPS -> buildHttps(profile)
            ProxyProtocol.SSH -> buildUserPassUri("ssh", profile)
        }
    }

    private fun buildShadowsocks(p: ProxyProfile): String {
        val userPass = "${p.method}:${p.password}"
        val encodedUserInfo = Base64.encodeToString(
            userPass.toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP or Base64.URL_SAFE
        ).trimEnd('=')
        val fragment = encodeFragment(p.displayName)
        return "ss://$encodedUserInfo@${p.server}:${p.port}#$fragment"
    }

    private fun buildTrojan(p: ProxyProfile): String {
        val params = mutableMapOf<String, String>()
        if (p.sni.isNotEmpty()) params["sni"] = p.sni
        if (p.allowInsecure) params["allowinsecure"] = "1"
        if (p.network.isNotEmpty() && p.network != "tcp") params["type"] = p.network
        if (p.network.equals("ws", ignoreCase = true)) {
            if (p.wsPath.isNotEmpty()) params["path"] = p.wsPath
            if (p.wsHost.isNotEmpty()) params["host"] = p.wsHost
        }
        val query = encodeQuery(params)
        val fragment = encodeFragment(p.displayName)
        val queryPart = if (query.isNotEmpty()) "?$query" else ""
        return "trojan://${p.password}@${p.server}:${p.port}$queryPart#$fragment"
    }

    private fun buildVless(p: ProxyProfile): String {
        val params = mutableMapOf<String, String>()
        if (p.security.isNotEmpty()) params["security"] = p.security
        if (p.sni.isNotEmpty()) params["sni"] = p.sni
        if (p.allowInsecure) params["allowinsecure"] = "1"
        if (p.network.isNotEmpty() && p.network != "tcp") params["type"] = p.network
        if (p.network.equals("ws", ignoreCase = true)) {
            if (p.wsPath.isNotEmpty()) params["path"] = p.wsPath
            if (p.wsHost.isNotEmpty()) params["host"] = p.wsHost
        }
        if (p.security.equals("reality", ignoreCase = true)) {
            if (p.publicKey.isNotEmpty()) params["pbk"] = p.publicKey
            if (p.shortId.isNotEmpty()) params["sid"] = p.shortId
            if (p.spiderX.isNotEmpty()) params["spx"] = p.spiderX
            if (p.fingerprint.isNotEmpty()) params["fp"] = p.fingerprint
        } else if (p.fingerprint.isNotEmpty()) {
            params["fp"] = p.fingerprint
        }
        if (p.flow.isNotEmpty()) params["flow"] = p.flow
        val query = encodeQuery(params)
        val fragment = encodeFragment(p.displayName)
        val queryPart = if (query.isNotEmpty()) "?$query" else ""
        return "vless://${p.uuid}@${p.server}:${p.port}$queryPart#$fragment"
    }

    private fun buildVmess(p: ProxyProfile): String {
        val vmessObj = buildJsonObject {
            put("v", "2")
            put("ps", p.displayName)
            put("add", p.server)
            put("port", p.port)
            put("id", p.uuid)
            put("aid", p.alterId)
            put("scy", if (p.security.isNotEmpty() && p.security != "tls" && p.security != "reality") p.security else "auto")
            put("net", p.network.ifEmpty { "tcp" })
            put("type", "none")
            put("host", p.wsHost)
            put("path", p.wsPath)
            put("tls", if (p.security.equals("tls", ignoreCase = true)) "tls" else "")
            put("sni", p.sni.ifEmpty { p.server })
        }
        val encoded = Base64.encodeToString(
            vmessObj.toString().toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP
        )
        return "vmess://$encoded"
    }

    private fun buildHysteria2(p: ProxyProfile): String {
        val params = mutableMapOf<String, String>()
        if (p.sni.isNotEmpty()) params["sni"] = p.sni
        if (p.allowInsecure) params["insecure"] = "1"
        if (p.obfs.isNotEmpty()) {
            params["obfs"] = p.obfs
            if (p.obfsPassword.isNotEmpty()) params["obfs-password"] = p.obfsPassword
        }
        val query = encodeQuery(params)
        val fragment = encodeFragment(p.displayName)
        val queryPart = if (query.isNotEmpty()) "?$query" else ""
        return "hysteria2://${p.password}@${p.server}:${p.port}$queryPart#$fragment"
    }

    private fun buildTuic(p: ProxyProfile): String {
        val params = mutableMapOf<String, String>()
        if (p.sni.isNotEmpty()) params["sni"] = p.sni
        if (p.alpn.isNotEmpty()) params["alpn"] = p.alpn
        if (p.congestionControl.isNotEmpty()) params["congestion_control"] = p.congestionControl
        if (p.udpRelayMode.isNotEmpty()) params["udp_relay_mode"] = p.udpRelayMode
        if (p.allowInsecure) params["allow_insecure"] = "1"
        val query = encodeQuery(params)
        val fragment = encodeFragment(p.displayName)
        val queryPart = if (query.isNotEmpty()) "?$query" else ""
        return "tuic://${p.uuid}:${p.password}@${p.server}:${p.port}$queryPart#$fragment"
    }

    private fun buildMasque(p: ProxyProfile): String {
        val params = mutableMapOf<String, String>()
        if (p.sni.isNotEmpty()) params["sni"] = p.sni
        if (p.alpn.isNotEmpty()) params["alpn"] = p.alpn
        if (p.masquePath.isNotEmpty()) params["path"] = p.masquePath
        if (p.allowInsecure) params["allow_insecure"] = "1"
        val query = encodeQuery(params)
        val fragment = encodeFragment(p.displayName)
        val queryPart = if (query.isNotEmpty()) "?$query" else ""
        return "masque://${p.masqueUser}:${p.masquePass}@${p.server}:${p.port}$queryPart#$fragment"
    }

    private fun buildUserPassUri(scheme: String, p: ProxyProfile): String {
        val authPart = buildAuthPart(p.username, p.password)
        val fragment = encodeFragment(p.displayName)
        return "$scheme://$authPart${p.server}:${p.port}#$fragment"
    }

    private fun buildHttps(p: ProxyProfile): String {
        val authPart = buildAuthPart(p.username, p.password)
        val params = mutableMapOf<String, String>()
        if (p.sni.isNotEmpty() && p.sni != p.server) params["sni"] = p.sni
        if (p.allowInsecure) params["allowInsecure"] = "1"
        val query = encodeQuery(params)
        val queryPart = if (query.isNotEmpty()) "?$query" else ""
        val fragment = encodeFragment(p.displayName)
        return "https://$authPart${p.server}:${p.port}$queryPart#$fragment"
    }

    private fun buildAuthPart(username: String, password: String): String {
        if (username.isEmpty() && password.isEmpty()) return ""
        val u = URLEncoder.encode(username, StandardCharsets.UTF_8.name())
        return if (password.isNotEmpty()) {
            val pw = URLEncoder.encode(password, StandardCharsets.UTF_8.name())
            "$u:$pw@"
        } else {
            "$u@"
        }
    }

    private fun encodeQuery(params: Map<String, String>): String {
        return params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, StandardCharsets.UTF_8.name())}=${URLEncoder.encode(v, StandardCharsets.UTF_8.name())}"
        }
    }

    private fun encodeFragment(name: String): String {
        return URLEncoder.encode(name, StandardCharsets.UTF_8.name()).replace("+", "%20")
    }
}
