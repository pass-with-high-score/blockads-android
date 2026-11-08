package app.pwhs.blockads.ui.tailscale

import org.json.JSONObject

data class ParsedTailscaleStatus(
    val state: String,
    val selfIp: String?,
    val magicDnsName: String,
    val peers: List<TailscalePeer>,
    val exitNodes: List<TailscaleExitNodeItem>,
)

object TailscaleStatusParser {

    fun parse(
        json: String,
        currentPinned: Set<Long>,
        currentPeersMap: Map<Long, TailscalePeer>
    ): ParsedTailscaleStatus {
        val obj = JSONObject(json)
        val state = obj.optString("state", "DISCONNECTED")
        val ipsList = mutableListOf<String>()
        val ipsArr = obj.optJSONArray("tailscale_ips")
        if (ipsArr != null) {
            for (i in 0 until ipsArr.length()) {
                ipsList.add(ipsArr.getString(i))
            }
        }
        val dnsName = obj.optString("magic_dns_name", "")
        val selfNodeObj = obj.optJSONObject("self_node")
        val selfNodeId = selfNodeObj?.optLong("id") ?: 0L
        val selfIp = ipsList.firstOrNull()

        val peersList = mutableListOf<TailscalePeer>()
        val peersArr = obj.optJSONArray("peers")
        if (peersArr != null) {
            for (i in 0 until peersArr.length()) {
                val p = peersArr.getJSONObject(i)
                val id = p.optLong("id")
                val pIps = mutableListOf<String>()
                val pIpsArr = p.optJSONArray("ips")
                if (pIpsArr != null) {
                    for (j in 0 until pIpsArr.length()) {
                        pIps.add(pIpsArr.getString(j))
                    }
                }
                val isSelf = p.optBoolean("is_self") || (id != 0L && id == selfNodeId) || (selfIp != null && pIps.contains(selfIp))
                val existing = currentPeersMap[id]
                peersList.add(
                    TailscalePeer(
                        id = id,
                        name = p.optString("name"),
                        ips = pIps,
                        ipv4 = p.optString("ipv4").takeIf { it.isNotEmpty() },
                        ipv6 = p.optString("ipv6").takeIf { it.isNotEmpty() },
                        os = p.optString("os"),
                        online = p.optBoolean("online"),
                        isExitNode = p.optBoolean("is_exit_node"),
                        isPinned = currentPinned.contains(id),
                        isSelf = isSelf,
                        keyExpiry = p.optLong("key_expiry"),
                        keyExpired = p.optBoolean("key_expired"),
                        curAddr = p.optString("cur_addr"),
                        relay = p.optString("relay"),
                        lastPingMs = existing?.lastPingMs,
                        isPingLoading = existing?.isPingLoading ?: false,
                        pingError = existing?.pingError
                    )
                )
            }
        }

        val sortedPeers = peersList.sortedWith(
            compareByDescending<TailscalePeer> { it.isPinned }
                .thenByDescending { it.isSelf }
                .thenByDescending { it.online }
                .thenBy { it.name.lowercase() }
        )

        val exitList = mutableListOf<TailscaleExitNodeItem>()
        val exitsArr = obj.optJSONArray("exit_nodes")
        if (exitsArr != null) {
            for (i in 0 until exitsArr.length()) {
                val ex = exitsArr.getJSONObject(i)
                exitList.add(
                    TailscaleExitNodeItem(
                        id = ex.optLong("id"),
                        name = ex.optString("name"),
                        ip = ex.optString("ip")
                    )
                )
            }
        }

        return ParsedTailscaleStatus(
            state = state,
            selfIp = ipsList.firstOrNull(),
            magicDnsName = dnsName,
            peers = sortedPeers,
            exitNodes = exitList
        )
    }
}
