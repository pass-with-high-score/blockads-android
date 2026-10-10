package app.pwhs.blockads.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a Quantumult X-compatible configuration or snippet ruleset.
 */
@Entity(tableName = "configs")
data class ConfigProfile(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val content: String = "",
    val remoteUrl: String? = null,
    val autoUpdate: Boolean = true,
    val lastUpdated: Long = 0L,
    val isActive: Boolean = false,
    val isBuiltIn: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val DEFAULT_NAME = "Default"
        const val SAMPLE_CONFIG = """# Configuration Profile
#
# Lines starting with ";" or "#" or "//" are comments.

[general]
dns_exclusion_list = *.local, localhost, *.lan

[dns]
server = 1.1.1.1
server = 8.8.8.8
server = 223.5.5.5

[policy]
static = DIRECT, direct
static = REJECT, reject

[server_local]

[server_remote]

[filter_local]
# Local ad blocking rules
host-suffix, doubleclick.net, reject
host-suffix, googleads.g.doubleclick.net, reject
host-suffix, ads.yahoo.com, reject
host-keyword, adservice, reject
ip-cidr, 10.0.0.0/8, direct
ip-cidr, 172.16.0.0/12, direct
ip-cidr, 192.168.0.0/16, direct
final, direct

[filter_remote]
# AdGuard Mobile & DNS Filters
https://filters.adtidy.org/android/filters/11_optimized.txt, tag=AdGuardMobile, update-interval=24
https://filters.adtidy.org/android/filters/15_optimized.txt, tag=AdGuardDNS, update-interval=24
# OISD Small DNS Filter
https://small.oisd.nl, tag=OISD_Small, update-interval=24
# ABPVN Filter for Vietnamese websites
https://raw.githubusercontent.com/abpvn/abpvn/master/filter/abpvn_rule.txt, tag=ABPVN, update-interval=24

[rewrite_local]

[rewrite_remote]

[task_local]

[http_backend]

[mitm]
"""
    }
}
