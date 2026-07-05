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
    // Kept only so teardown removes the old fallback chain from previous
    // debug builds. Do not create this chain: rejecting IPv6 DNS can break
    // connectivity on IPv6-first networks instead of causing IPv4 fallback.
    private const val CHAIN_IPV6_DNS_FALLBACK = "BLOCKADS_DNS6_BLOCK"
    private const val LOCAL_DNS_PORT = 15353

    data class SetupResult(
        val ipv4Active: Boolean,
        val ipv6Active: Boolean,
    ) {
        val usable: Boolean get() = ipv4Active
    }

    data class RuleStatus(
        val ipv4Active: Boolean,
        val ipv6Active: Boolean,
    )

    private data class PrivateDnsSnapshot(
        val mode: String?,
        val specifier: String?,
    )

    @Volatile
    private var previousPrivateDns: PrivateDnsSnapshot? = null

    /**
     * Ensure the cached libsu main shell actually has root.
     *
     * libsu creates the main shell once and caches it for the process
     * lifetime. If the first shell was created while the su daemon wasn't
     * ready yet (typical right after boot), a non-root `sh` gets cached and
     * every subsequent command silently runs without root — retries can
     * never succeed. Closing the poisoned shell forces libsu to build a
     * fresh one that attempts `su` again.
     */
    fun ensureRootShell(): Boolean {
        val cached = Shell.getCachedShell()
        if (cached != null && cached.isRoot) return true

        if (cached != null) {
            try {
                cached.close()
            } catch (e: Exception) {
                Timber.w(e, "Failed to close non-root shell")
            }
        }

        val fresh = Shell.getShell()
        Timber.d("Recreated libsu main shell, isRoot=${fresh.isRoot}")
        return fresh.isRoot
    }

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
     * @param whitelistUids UIDs of whitelisted apps whose DNS must bypass the
     *        redirect entirely — the Root-mode equivalent of VPN mode's
     *        addDisallowedApplication (#150)
     * @return true if at least IPv4 rules succeeded
     */
    fun setupRules(
        context: Context,
        blockDoT: Boolean = true,
        whitelistUids: Collection<Int> = emptyList()
    ): Boolean {
        return setupRulesDetailed(context, blockDoT, whitelistUids).usable
    }

    @Synchronized
    fun setupRulesDetailed(
        context: Context,
        blockDoT: Boolean = true,
        whitelistUids: Collection<Int> = emptyList()
    ): SetupResult {
        val uid = context.applicationInfo.uid
        Timber.d("Setting up iptables rules for UID=$uid, blockDoT=$blockDoT, whitelistUids=$whitelistUids")

        // Always teardown first (idempotent), but do not restore Private DNS
        // during internal setup/reload. The original value is restored only
        // when Root mode really stops.
        teardownRules(restorePrivateDns = false)

        // ══════════════════════════════════════════════════════════════
        // Step 0: Disable Android Private DNS so system uses port 53
        // This is CRITICAL — without this, Android 9+ uses DoT (853)
        // and our port 53 redirect never sees traffic.
        // ══════════════════════════════════════════════════════════════
        savePrivateDnsIfNeeded()
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
            // Skip whitelisted apps — their DNS goes straight upstream
            for (wUid in whitelistUids) {
                add("iptables -t nat -A $CHAIN -m owner --uid-owner $wUid -j RETURN")
            }
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
                for (wUid in whitelistUids) {
                    add("iptables -t filter -A $CHAIN_FILTER -m owner --uid-owner $wUid -j RETURN")
                }
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
        // IPv6 — try NAT independently. Many Android kernels/ROMs lack
        // ip6tables nat. If NAT is unavailable, report partial protection
        // rather than blocking IPv6 DNS. Rejecting IPv6 DNS can break
        // connectivity on IPv6-first networks instead of causing fallback.
        // ══════════════════════════════════════════════════════════════
        val ipv6NatCommands = buildList {
            add("ip6tables -t nat -N $CHAIN 2>/dev/null || true")
            add("ip6tables -t nat -A $CHAIN -m owner --uid-owner $uid -j RETURN")
            for (wUid in whitelistUids) {
                add("ip6tables -t nat -A $CHAIN -m owner --uid-owner $wUid -j RETURN")
            }
            add("ip6tables -t nat -A $CHAIN -p udp --dport 53 -j REDIRECT --to-ports $LOCAL_DNS_PORT")
            add("ip6tables -t nat -A $CHAIN -p tcp --dport 53 -j REDIRECT --to-ports $LOCAL_DNS_PORT")
            add("ip6tables -t nat -A OUTPUT -j $CHAIN")
        }

        var ipv6NatSuccess = true
        for (cmd in ipv6NatCommands) {
            val result = Shell.cmd(cmd).exec()
            if (!result.isSuccess) {
                Timber.w("IPv6 ip6tables NAT cmd FAILED (will use fallback if needed): [$cmd] err=${result.err}")
                ipv6NatSuccess = false
            }
        }

        val ipv6FilterCommands = buildList {
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

        for (cmd in ipv6FilterCommands) {
            val result = Shell.cmd(cmd).exec()
            if (!result.isSuccess) {
                Timber.w("IPv6 ip6tables filter cmd FAILED (ignoring): [$cmd] err=${result.err}")
            }
        }

        val status = getStatus()

        // Verify rules are actually in place
        if (status.ipv4Active) {
            Timber.d("iptables rules verified active")
        } else {
            Timber.e("iptables rules NOT active after setup — root may have been denied")
        }
        if (status.ipv6Active) {
            Timber.d("IPv6 DNS path is protected")
        } else {
            Timber.w("IPv6 DNS path is not protected; Root protection is IPv4-only")
        }

        return SetupResult(
            ipv4Active = ipv4Success && status.ipv4Active,
            ipv6Active = ipv6NatSuccess && status.ipv6Active,
        )
    }

    /**
     * Remove all BlockAds iptables rules and restore Private DNS.
     * Safe to call multiple times. Uses 2>/dev/null to suppress errors.
     */
    @Synchronized
    fun teardownRules(restorePrivateDns: Boolean = true): Boolean {
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
            // IPv6 DNS fallback block chain
            "ip6tables -t filter -D OUTPUT -j $CHAIN_IPV6_DNS_FALLBACK 2>/dev/null",
            "ip6tables -t filter -F $CHAIN_IPV6_DNS_FALLBACK 2>/dev/null",
            "ip6tables -t filter -X $CHAIN_IPV6_DNS_FALLBACK 2>/dev/null",
        )

        Shell.cmd(*commands.toTypedArray()).exec()
        if (restorePrivateDns) {
            restorePrivateDns()
        }
        Timber.d("iptables teardown done, restorePrivateDns=$restorePrivateDns")
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

    fun isIpv6Active(): Boolean {
        val result = Shell.cmd(
            "ip6tables -t nat -L OUTPUT -n 2>/dev/null | grep $CHAIN"
        ).exec()
        return result.out.any { it.contains(CHAIN) }
    }

    fun getStatus(): RuleStatus = RuleStatus(
        ipv4Active = isActive(),
        ipv6Active = isIpv6Active(),
    )

    private fun savePrivateDnsIfNeeded() {
        if (previousPrivateDns != null) return
        val mode = readGlobalSetting("private_dns_mode")
        val specifier = readGlobalSetting("private_dns_specifier")
        previousPrivateDns = PrivateDnsSnapshot(mode = mode, specifier = specifier)
        Timber.d("Saved Private DNS state: mode=$mode, specifier=$specifier")
    }

    private fun restorePrivateDns() {
        val snapshot = previousPrivateDns ?: return
        if (snapshot.mode.isNullOrBlank()) {
            Shell.cmd("settings delete global private_dns_mode").exec()
        } else {
            Shell.cmd("settings put global private_dns_mode ${shellQuote(snapshot.mode)}").exec()
        }

        if (snapshot.specifier.isNullOrBlank()) {
            Shell.cmd("settings delete global private_dns_specifier").exec()
        } else {
            Shell.cmd("settings put global private_dns_specifier ${shellQuote(snapshot.specifier)}").exec()
        }

        previousPrivateDns = null
        Timber.d("Restored Private DNS state")
    }

    private fun readGlobalSetting(name: String): String? {
        val result = Shell.cmd("settings get global $name").exec()
        val value = result.out.firstOrNull()?.trim()
        return value?.takeUnless { it.isEmpty() || it == "null" }
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }
}
