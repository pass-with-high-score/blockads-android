package app.pwhs.blockadstv.data.entities

import kotlinx.serialization.Serializable

@Serializable
enum class ProxyProtocol(val value: String, val displayName: String) {
    SHADOWSOCKS("shadowsocks", "SS"),
    TROJAN("trojan", "Trojan"),
    VLESS("vless", "VLESS"),
    VMESS("vmess", "VMess"),
    HYSTERIA2("hysteria2", "Hysteria 2"),
    TUIC("tuic", "TUIC"),
    MASQUE("masque", "MASQUE");

    companion object {
        fun fromValue(value: String): ProxyProtocol = when (value.lowercase()) {
            "trojan" -> TROJAN
            "vless" -> VLESS
            "vmess" -> VMESS
            "hysteria2", "hy2" -> HYSTERIA2
            "tuic" -> TUIC
            "masque" -> MASQUE
            else -> SHADOWSOCKS
        }
    }
}
