package app.pwhs.blockads.service

import android.content.Context
import app.pwhs.blockads.utils.LibsuRootShell
import app.pwhs.blockads.utils.RootShell
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
 *                 REDIRECT browser TCP 80/443 → 15354 (root-mode HTTPS MITM)
 * - filter table: REJECT port 853 (block DoT) and browser QUIC (UDP 443)
 * - settings:     Disable Android Private DNS (forces port 53 fallback)
 */
object IptablesManager {

    private const val CHAIN = "BLOCKADS_DNS"
    private const val CHAIN_FILTER = "BLOCKADS_DOT"
    private const val LOCAL_DNS_PORT = 15353
    internal const val LOCAL_MITM_PORT = 15354

    @Volatile
    internal var shell: RootShell = LibsuRootShell

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
        Timber.d("Recreated libsu main shell, isRoot=${fresh.isRoot}")
        return fresh.isRoot
    }

    /**
     * Actively request root access. This will trigger the Magisk/KernelSU
     * permission prompt if it hasn't been granted yet.
     */
    fun isRootAvailable(): Boolean {
        // Explicitly trigger 'su' so Magisk/KernelSU shows the permission prompt
        val result = shell.exec("su -c id")
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
     * @param mitmUids Browser UIDs whose TCP 80/443 traffic must go to the
     *        root-mode MITM listener (empty = HTTPS filtering off)
     * @param blockQuic Reject browser QUIC (UDP 443) so HTTP/3 can't bypass
     *        the MITM path
     * @return true if at least IPv4 rules succeeded
     */
    fun setupRules(
        context: Context,
        blockDoT: Boolean = true,
        whitelistUids: Collection<Int> = emptyList(),
        mitmUids: Collection<Int> = emptyList(),
        blockQuic: Boolean = true,
    ): Boolean {
        val uid = context.applicationInfo.uid
        Timber.d("Setting up iptables rules for UID=$uid, blockDoT=$blockDoT, whitelistUids=$whitelistUids")

        // Always teardown first (idempotent)
        teardownRules()

        // ══════════════════════════════════════════════════════════════
        // Step 0: Disable Android Private DNS so system uses port 53
        // This is CRITICAL — without this, Android 9+ uses DoT (853)
        // and our port 53 redirect never sees traffic.
        // ══════════════════════════════════════════════════════════════
        shell.exec("settings put global private_dns_mode off")
        Timber.d("Disabled Android Private DNS (forced plain DNS mode)")

        // ══════════════════════════════════════════════════════════════
        // Step 1: nat table — REDIRECT port 53 → local engine
        //
        // NOTE: We run each iptables command individually so that a single
        // failure (e.g. chain already exists) doesn't abort the entire setup.
        // ══════════════════════════════════════════════════════════════
        val ipv4Commands = buildIpv4Commands(uid, blockDoT, whitelistUids, mitmUids, blockQuic)

        var ipv4Success = true
        for (cmd in ipv4Commands) {
            val result = shell.exec(cmd)
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
        val ipv6Commands = buildIpv6Commands(uid, blockDoT, whitelistUids, mitmUids, blockQuic)

        for (cmd in ipv6Commands) {
            val result = shell.exec(cmd)
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
        val commands = teardownCommands()
        shell.exec(*commands.toTypedArray())
        Timber.d("iptables teardown done, Private DNS restored")
        return true
    }

    internal fun buildIpv4Commands(
        uid: Int,
        blockDoT: Boolean,
        whitelistUids: Collection<Int>,
        mitmUids: Collection<Int> = emptyList(),
        blockQuic: Boolean = true,
    ): List<String> = buildList {
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
        // Root-mode HTTPS filtering: only the selected browser UIDs' TCP
        // 80/443 goes to the MITM listener; every other app stays direct.
        for (mUid in mitmUids) {
            add("iptables -t nat -A $CHAIN -m owner --uid-owner $mUid -p tcp --dport 80 -j REDIRECT --to-ports $LOCAL_MITM_PORT")
            add("iptables -t nat -A $CHAIN -m owner --uid-owner $mUid -p tcp --dport 443 -j REDIRECT --to-ports $LOCAL_MITM_PORT")
        }
        // Hook into OUTPUT chain
        add("iptables -t nat -A OUTPUT -j $CHAIN")

        if (blockDoT || mitmUids.isNotEmpty()) {
            // filter table — DoT block + QUIC suppression
            add("iptables -t filter -N $CHAIN_FILTER 2>/dev/null || true")
            add("iptables -t filter -A $CHAIN_FILTER -m owner --uid-owner $uid -j RETURN")
            for (wUid in whitelistUids) {
                add("iptables -t filter -A $CHAIN_FILTER -m owner --uid-owner $wUid -j RETURN")
            }
            if (blockDoT) {
                add("iptables -t filter -A $CHAIN_FILTER -p tcp --dport 853 -j REJECT")
            }
            if (blockQuic) {
                // HTTP/3 bypasses the TCP MITM, so force browser QUIC back
                // to TCP where cosmetic injection can run.
                for (mUid in mitmUids) {
                    add("iptables -t filter -A $CHAIN_FILTER -m owner --uid-owner $mUid -p udp --dport 443 -j REJECT")
                }
            }
            add("iptables -t filter -A OUTPUT -j $CHAIN_FILTER")
        }
    }

    internal fun buildIpv6Commands(
        uid: Int,
        blockDoT: Boolean,
        whitelistUids: Collection<Int>,
        mitmUids: Collection<Int> = emptyList(),
        blockQuic: Boolean = true,
    ): List<String> = buildList {
        add("ip6tables -t nat -N $CHAIN 2>/dev/null || true")
        add("ip6tables -t nat -A $CHAIN -m owner --uid-owner $uid -j RETURN")
        for (wUid in whitelistUids) {
            add("ip6tables -t nat -A $CHAIN -m owner --uid-owner $wUid -j RETURN")
        }
        add("ip6tables -t nat -A $CHAIN -p udp --dport 53 -j REDIRECT --to-ports $LOCAL_DNS_PORT")
        add("ip6tables -t nat -A $CHAIN -p tcp --dport 53 -j REDIRECT --to-ports $LOCAL_DNS_PORT")
        add("ip6tables -t nat -A OUTPUT -j $CHAIN")

        if (blockDoT || mitmUids.isNotEmpty()) {
            add("ip6tables -t filter -N $CHAIN_FILTER 2>/dev/null || true")
            add("ip6tables -t filter -A $CHAIN_FILTER -m owner --uid-owner $uid -j RETURN")
            for (wUid in whitelistUids) {
                add("ip6tables -t filter -A $CHAIN_FILTER -m owner --uid-owner $wUid -j RETURN")
            }
            if (blockDoT) {
                add("ip6tables -t filter -A $CHAIN_FILTER -p tcp --dport 853 -j REJECT")
            }
            // Kernels without IP6_NF_NAT can't REDIRECT IPv6 to the MITM
            // port. Rejecting v6 HTTP/HTTPS makes Happy Eyeballs fall back
            // to the IPv4 path, which the nat chain does intercept.
            for (mUid in mitmUids) {
                add("ip6tables -t filter -A $CHAIN_FILTER -m owner --uid-owner $mUid -p tcp --dport 80 -j REJECT")
                add("ip6tables -t filter -A $CHAIN_FILTER -m owner --uid-owner $mUid -p tcp --dport 443 -j REJECT")
            }
            if (blockQuic) {
                for (mUid in mitmUids) {
                    add("ip6tables -t filter -A $CHAIN_FILTER -m owner --uid-owner $mUid -p udp --dport 443 -j REJECT")
                }
            }
            add("ip6tables -t filter -A OUTPUT -j $CHAIN_FILTER")
        }
    }

    internal fun teardownCommands(): List<String> = listOf(
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

    /**
     * Check if our iptables rules are currently active.
     */
    fun isActive(): Boolean {
        val result = shell.exec("iptables -t nat -L OUTPUT -n 2>/dev/null | grep $CHAIN")
        return result.out.any { it.contains(CHAIN) }
    }
}
