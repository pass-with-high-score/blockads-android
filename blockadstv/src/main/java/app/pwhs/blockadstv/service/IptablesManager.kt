package app.pwhs.blockadstv.service

import android.content.Context
import app.pwhs.blockadstv.utils.LibsuRootShell
import app.pwhs.blockadstv.utils.RootShell
import timber.log.Timber

/**
 * Manages iptables rules for TV Root/Proxy mode.
 * Redirects outbound DNS traffic (port 53 UDP/TCP) to the local Go engine at 127.0.0.1:15353,
 * excluding this app's own UID to prevent infinite loops.
 */
object IptablesManager {

    private const val CHAIN = "BLOCKADS_DNS"
    private const val CHAIN_FILTER = "BLOCKADS_DOT"
    private const val LOCAL_DNS_PORT = 15353

    @Volatile
    internal var shell: RootShell = LibsuRootShell

    fun ensureRootShell(): Boolean {
        val cached = shell.cachedShell()
        if (cached != null && cached.isRoot) return true

        if (cached != null) {
            try {
                cached.close()
            } catch (e: Exception) {
                Timber.w(e, "Failed to close non-root shell")
            }
        }

        val fresh = shell.mainShell()
        Timber.d("Recreated libsu main shell for TV, isRoot=${fresh.isRoot}")
        return fresh.isRoot
    }

    fun isRootAvailable(): Boolean {
        val result = shell.exec("su -c id")
        return result.isSuccess && result.out.any { it.contains("uid=0") }
    }

    fun setupRules(
        context: Context,
        blockDoT: Boolean = true,
        whitelistUids: Collection<Int> = emptyList()
    ): Boolean {
        val uid = context.applicationInfo.uid
        Timber.d("Setting up TV iptables rules for UID=$uid, blockDoT=$blockDoT, whitelistUids=$whitelistUids")

        teardownRules()

        // Disable Android Private DNS to force port 53 fallback
        shell.exec("settings put global private_dns_mode off")
        Timber.d("Disabled Android Private DNS on TV")

        val ipv4Commands = buildIpv4Commands(uid, blockDoT, whitelistUids)
        var ipv4Success = true
        for (cmd in ipv4Commands) {
            val result = shell.exec(cmd)
            if (!result.isSuccess) {
                Timber.e("IPv4 iptables cmd failed on TV: [$cmd] err=${result.err}")
                ipv4Success = false
            }
        }

        val ipv6Commands = buildIpv6Commands(uid, blockDoT, whitelistUids)
        for (cmd in ipv6Commands) {
            val result = shell.exec(cmd)
            if (!result.isSuccess) {
                Timber.w("IPv6 ip6tables cmd failed on TV (ignoring): [$cmd] err=${result.err}")
            }
        }

        val verified = isActive()
        Timber.d("TV iptables setup complete, verified active=$verified")
        return ipv4Success && verified
    }

    fun teardownRules(): Boolean {
        val commands = teardownCommands()
        shell.exec(*commands.toTypedArray())
        Timber.d("TV iptables teardown done")
        return true
    }

    internal fun buildIpv4Commands(uid: Int, blockDoT: Boolean, whitelistUids: Collection<Int>): List<String> = buildList {
        add("iptables -t nat -N $CHAIN 2>/dev/null || true")
        add("iptables -t nat -A $CHAIN -m owner --uid-owner $uid -j RETURN")
        for (wUid in whitelistUids) {
            add("iptables -t nat -A $CHAIN -m owner --uid-owner $wUid -j RETURN")
        }
        add("iptables -t nat -A $CHAIN -p udp --dport 53 -j REDIRECT --to-ports $LOCAL_DNS_PORT")
        add("iptables -t nat -A $CHAIN -p tcp --dport 53 -j REDIRECT --to-ports $LOCAL_DNS_PORT")
        add("iptables -t nat -A OUTPUT -j $CHAIN")

        if (blockDoT) {
            add("iptables -t filter -N $CHAIN_FILTER 2>/dev/null || true")
            add("iptables -t filter -A $CHAIN_FILTER -m owner --uid-owner $uid -j RETURN")
            for (wUid in whitelistUids) {
                add("iptables -t filter -A $CHAIN_FILTER -m owner --uid-owner $wUid -j RETURN")
            }
            add("iptables -t filter -A $CHAIN_FILTER -p tcp --dport 853 -j REJECT")
            add("iptables -t filter -A OUTPUT -j $CHAIN_FILTER")
        }
    }

    internal fun buildIpv6Commands(uid: Int, blockDoT: Boolean, whitelistUids: Collection<Int>): List<String> = buildList {
        add("ip6tables -t nat -N $CHAIN 2>/dev/null || true")
        add("ip6tables -t nat -A $CHAIN -m owner --uid-owner $uid -j RETURN")
        for (wUid in whitelistUids) {
            add("ip6tables -t nat -A $CHAIN -m owner --uid-owner $wUid -j RETURN")
        }
        add("ip6tables -t nat -A $CHAIN -p udp --dport 53 -j REDIRECT --to-ports $LOCAL_DNS_PORT")
        add("ip6tables -t nat -A $CHAIN -p tcp --dport 53 -j REDIRECT --to-ports $LOCAL_DNS_PORT")
        add("ip6tables -t nat -A OUTPUT -j $CHAIN")

        if (blockDoT) {
            add("ip6tables -t filter -N $CHAIN_FILTER 2>/dev/null || true")
            add("ip6tables -t filter -A $CHAIN_FILTER -m owner --uid-owner $uid -j RETURN")
            for (wUid in whitelistUids) {
                add("ip6tables -t filter -A $CHAIN_FILTER -m owner --uid-owner $wUid -j RETURN")
            }
            add("ip6tables -t filter -A $CHAIN_FILTER -p tcp --dport 853 -j REJECT")
            add("ip6tables -t filter -A OUTPUT -j $CHAIN_FILTER")
        }
    }

    internal fun teardownCommands(): List<String> = listOf(
        "iptables -t nat -D OUTPUT -j $CHAIN 2>/dev/null",
        "iptables -t nat -F $CHAIN 2>/dev/null",
        "iptables -t nat -X $CHAIN 2>/dev/null",
        "iptables -t filter -D OUTPUT -j $CHAIN_FILTER 2>/dev/null",
        "iptables -t filter -F $CHAIN_FILTER 2>/dev/null",
        "iptables -t filter -X $CHAIN_FILTER 2>/dev/null",
        "ip6tables -t nat -D OUTPUT -j $CHAIN 2>/dev/null",
        "ip6tables -t nat -F $CHAIN 2>/dev/null",
        "ip6tables -t nat -X $CHAIN 2>/dev/null",
        "ip6tables -t filter -D OUTPUT -j $CHAIN_FILTER 2>/dev/null",
        "ip6tables -t filter -F $CHAIN_FILTER 2>/dev/null",
        "ip6tables -t filter -X $CHAIN_FILTER 2>/dev/null",
        "settings put global private_dns_mode opportunistic",
    )

    fun isActive(): Boolean {
        val result = shell.exec("iptables -t nat -L OUTPUT -n 2>/dev/null | grep $CHAIN")
        return result.out.any { it.contains(CHAIN) }
    }
}
