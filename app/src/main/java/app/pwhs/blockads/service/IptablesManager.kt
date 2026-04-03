package app.pwhs.blockads.service

import android.content.Context
import com.topjohnwu.superuser.Shell
import timber.log.Timber

/**
 * Manages iptables rules for Root/Proxy mode.
 * Redirects all outbound DNS traffic (port 53 UDP/TCP) to the local
 * Go engine at 127.0.0.1:15353, excluding this app's own UID to prevent
 * infinite redirect loops.
 *
 * Uses a custom chain (BLOCKADS_DNS) for clean setup/teardown.
 *
 * Architecture:
 * - nat table:    REDIRECT port 53 → 15353 (DNS interception)
 * - filter table: DROP port 853 (block DoT to force plain DNS)
 * - settings:     Disable Android Private DNS (forces port 53 fallback)
 */
object IptablesManager {

    private const val CHAIN = "BLOCKADS_DNS"
    private const val CHAIN_FILTER = "BLOCKADS_DOT"
    private const val LOCAL_DNS_PORT = 15353

    /**
     * Actively request root access. This will trigger the Magisk/KernelSU
     * permission prompt if it hasn't been granted yet.
     */
    fun isRootAvailable(): Boolean {
        // Explicitly trigger 'su' so Magisk/KernelSU shows the permission prompt
        val result = Shell.cmd("su -c id").exec()
        return result.isSuccess && result.out.any { it.contains("uid=0") }
    }

    /**
     * Apply iptables rules to redirect DNS traffic.
     *
     * @param context App context (used to get UID)
     * @param blockDoT If true, blocks DoT (port 853) to force DNS fallback to port 53
     * @return true if at least IPv4 rules succeeded
     */
    fun setupRules(context: Context, blockDoT: Boolean = true): Boolean {
        val uid = context.applicationInfo.uid
        Timber.d("Setting up iptables rules for UID=$uid, blockDoT=$blockDoT")

        // Always teardown first (idempotent)
        teardownRules()

        // ══════════════════════════════════════════════════════════════
        // Step 0: Disable Android Private DNS so system uses port 53
        // This is CRITICAL — without this, Android 9+ uses DoT (853)
        // and our port 53 redirect never sees traffic.
        // ══════════════════════════════════════════════════════════════
        Shell.cmd("settings put global private_dns_mode off").exec()
        Timber.d("Disabled Android Private DNS (forced plain DNS mode)")

        // ══════════════════════════════════════════════════════════════
        // Step 1: nat table — REDIRECT port 53 → local engine
        //
        // NOTE: We run each iptables command individually so that a single
        // failure (e.g. chain already exists) doesn't abort the entire setup.
        // ══════════════════════════════════════════════════════════════
        val ipv4Commands = buildList {
            // Create chain (may fail if leftover — that's OK)
            add("iptables -t nat -N $CHAIN 2>/dev/null || true")
            // Skip our own app's traffic (prevents infinite loop)
            add("iptables -t nat -A $CHAIN -m owner --uid-owner $uid -j RETURN")
            // Redirect UDP DNS → local engine
            add("iptables -t nat -A $CHAIN -p udp --dport 53 -j REDIRECT --to-ports $LOCAL_DNS_PORT")
            // Redirect TCP DNS → local engine
            add("iptables -t nat -A $CHAIN -p tcp --dport 53 -j REDIRECT --to-ports $LOCAL_DNS_PORT")
            // Hook into OUTPUT chain
            add("iptables -t nat -A OUTPUT -j $CHAIN")

            if (blockDoT) {
                // filter table — DROP port 853 (DoT)
                add("iptables -t filter -N $CHAIN_FILTER 2>/dev/null || true")
                add("iptables -t filter -A $CHAIN_FILTER -m owner --uid-owner $uid -j RETURN")
                add("iptables -t filter -A $CHAIN_FILTER -p tcp --dport 853 -j REJECT")
                add("iptables -t filter -A OUTPUT -j $CHAIN_FILTER")
            }
        }

        var ipv4Success = true
        for (cmd in ipv4Commands) {
            val result = Shell.cmd(cmd).exec()
            if (!result.isSuccess) {
                Timber.e("IPv4 iptables cmd FAILED: [$cmd] err=${result.err} out=${result.out}")
                ipv4Success = false
                // Don't break — continue applying remaining rules so partial state is maximised
            }
        }

        if (ipv4Success) {
            Timber.d("IPv4 iptables setup SUCCESS")
        } else {
            Timber.e("IPv4 iptables setup had failures (see individual logs above)")
        }

        // ══════════════════════════════════════════════════════════════
        // IPv6 — try independently, many Android kernels lack ip6tables nat
        // ══════════════════════════════════════════════════════════════
        val ipv6Commands = buildList {
            add("ip6tables -t nat -N $CHAIN 2>/dev/null || true")
            add("ip6tables -t nat -A $CHAIN -m owner --uid-owner $uid -j RETURN")
            add("ip6tables -t nat -A $CHAIN -p udp --dport 53 -j REDIRECT --to-ports $LOCAL_DNS_PORT")
            add("ip6tables -t nat -A $CHAIN -p tcp --dport 53 -j REDIRECT --to-ports $LOCAL_DNS_PORT")
            add("ip6tables -t nat -A OUTPUT -j $CHAIN")

            if (blockDoT) {
                add("ip6tables -t filter -N $CHAIN_FILTER 2>/dev/null || true")
                add("ip6tables -t filter -A $CHAIN_FILTER -m owner --uid-owner $uid -j RETURN")
                add("ip6tables -t filter -A $CHAIN_FILTER -p tcp --dport 853 -j REJECT")
                add("ip6tables -t filter -A OUTPUT -j $CHAIN_FILTER")
            }
        }

        for (cmd in ipv6Commands) {
            val result = Shell.cmd(cmd).exec()
            if (!result.isSuccess) {
                Timber.w("IPv6 ip6tables cmd FAILED (ignoring): [$cmd] err=${result.err}")
            }
        }

        // Verify rules are actually in place
        val verified = isActive()
        if (verified) {
            Timber.d("iptables rules verified active")
        } else {
            Timber.e("iptables rules NOT active after setup — root may have been denied")
        }

        return ipv4Success && verified
    }

    /**
     * Remove all BlockAds iptables rules and restore Private DNS.
     * Safe to call multiple times. Uses 2>/dev/null to suppress errors.
     */
    fun teardownRules(): Boolean {
        val commands = listOf(
            // IPv4 nat chain
            "iptables -t nat -D OUTPUT -j $CHAIN 2>/dev/null",
            "iptables -t nat -F $CHAIN 2>/dev/null",
            "iptables -t nat -X $CHAIN 2>/dev/null",
            // IPv4 filter chain (DoT blocking)
            "iptables -t filter -D OUTPUT -j $CHAIN_FILTER 2>/dev/null",
            "iptables -t filter -F $CHAIN_FILTER 2>/dev/null",
            "iptables -t filter -X $CHAIN_FILTER 2>/dev/null",
            // IPv6 nat chain
            "ip6tables -t nat -D OUTPUT -j $CHAIN 2>/dev/null",
            "ip6tables -t nat -F $CHAIN 2>/dev/null",
            "ip6tables -t nat -X $CHAIN 2>/dev/null",
            // IPv6 filter chain (DoT blocking)
            "ip6tables -t filter -D OUTPUT -j $CHAIN_FILTER 2>/dev/null",
            "ip6tables -t filter -F $CHAIN_FILTER 2>/dev/null",
            "ip6tables -t filter -X $CHAIN_FILTER 2>/dev/null",
            // Restore Android Private DNS to automatic mode
            "settings put global private_dns_mode opportunistic",
        )

        Shell.cmd(*commands.toTypedArray()).exec()
        Timber.d("iptables teardown done, Private DNS restored")
        return true
    }

    /**
     * Check if our iptables rules are currently active.
     */
    fun isActive(): Boolean {
        val result = Shell.cmd(
            "iptables -t nat -L OUTPUT -n 2>/dev/null | grep $CHAIN"
        ).exec()
        return result.out.any { it.contains(CHAIN) }
    }
}
