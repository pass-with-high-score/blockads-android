package app.pwhs.blockads.data.entities

import androidx.annotation.DrawableRes

data class DnsProvider(
    val id: String,
    val name: String,
    val category: DnsCategory,
    val ipAddress: String,
    val dohUrl: String? = null,
    val description: String,
    @DrawableRes val iconRes: Int? = null
)

enum class DnsCategory {
    STANDARD,
    PRIVACY,
    FAMILY,
    CUSTOM
}

object DnsProviders {
    val GOOGLE = DnsProvider(
        id = "google",
        name = "Google DNS",
        category = DnsCategory.STANDARD,
        ipAddress = "8.8.8.8",
        dohUrl = "https://dns.google/dns-query",
        description = "Fast and reliable DNS service by Google"
    )

    val CLOUDFLARE = DnsProvider(
        id = "cloudflare",
        name = "Cloudflare DNS",
        category = DnsCategory.PRIVACY,
        ipAddress = "1.1.1.1",
        dohUrl = "https://cloudflare-dns.com/dns-query",
        description = "Privacy-focused, fastest DNS resolver"
    )

    val ADGUARD = DnsProvider(
        id = "adguard",
        name = "AdGuard DNS",
        category = DnsCategory.PRIVACY,
        ipAddress = "94.140.14.14",
        dohUrl = "https://dns.adguard-dns.com/dns-query",
        description = "Blocks ads and trackers at DNS level"
    )

    val QUAD9 = DnsProvider(
        id = "quad9",
        name = "Quad9",
        category = DnsCategory.PRIVACY,
        ipAddress = "9.9.9.9",
        dohUrl = "https://dns.quad9.net/dns-query",
        description = "Security and privacy-focused DNS"
    )

    val OPENDNS = DnsProvider(
        id = "opendns",
        name = "OpenDNS",
        category = DnsCategory.STANDARD,
        ipAddress = "208.67.222.222",
        dohUrl = null,
        description = "Reliable DNS with optional content filtering"
    )

    val OPENDNS_FAMILY = DnsProvider(
        id = "opendns_family",
        name = "OpenDNS Family Shield",
        category = DnsCategory.FAMILY,
        ipAddress = "208.67.222.123",
        dohUrl = null,
        description = "Family-safe DNS blocking adult content"
    )

    val CLOUDFLARE_FAMILY = DnsProvider(
        id = "cloudflare_family",
        name = "Cloudflare Family",
        category = DnsCategory.FAMILY,
        ipAddress = "1.1.1.3",
        dohUrl = "https://family.cloudflare-dns.com/dns-query",
        description = "Cloudflare DNS with malware and adult content blocking"
    )

    val ALL_PROVIDERS = listOf(
        GOOGLE,
        CLOUDFLARE,
        ADGUARD,
        QUAD9,
        OPENDNS,
        OPENDNS_FAMILY,
        CLOUDFLARE_FAMILY
    )

    fun getById(id: String): DnsProvider? = ALL_PROVIDERS.find { it.id == id }

    fun getByIp(ip: String): DnsProvider? = ALL_PROVIDERS.find { it.ipAddress == ip }
}
