package app.pwhs.blockads.service

import timber.log.Timber

/** The root proxy's bring-up loop, separated from [RootProxyService] so it runs against fakes. */
internal object RootProxyStartup {

    // On boot the su daemon (Magisk/KernelSU) can take well over the
    // normal retry window to come up — allow a much longer budget.
    fun retryManagerFor(startedFromBoot: Boolean) = VpnRetryManager(
        maxRetries = if (startedFromBoot) 30 else 10,
        maxDelayMs = 60000L
    )

    /**
     * Root shell, then the engine, then iptables; retried per [retryManager]. Returns whether all three came up.
     * [retryManager] is re-read each pass because a concurrent restart swaps the service's instance mid-loop.
     */
    suspend fun establish(
        retryManager: () -> VpnRetryManager,
        ensureRootShell: () -> Boolean,
        startEngine: suspend () -> Boolean,
        setupRules: () -> Boolean,
        stopEngine: () -> Unit,
    ): Boolean {
        var proxyStarted = false
        while (!proxyStarted && retryManager().shouldRetry()) {
            // Recreate the libsu shell if a non-root one got cached
            // (happens when the first shell command ran before the
            // su daemon was ready — see #179). Without this, every
            // retry reuses the poisoned non-root shell and iptables
            // can never succeed.
            if (!ensureRootShell()) {
                Timber.w("Root shell not available yet")
            } else {
                val engineStarted = startEngine()
                if (engineStarted) {
                    if (setupRules()) {
                        proxyStarted = true
                    } else {
                        stopEngine() // stop engine if iptables fails
                    }
                }
            }

            if (!proxyStarted && retryManager().shouldRetry()) {
                Timber.w("Root Proxy establishment failed, retrying... (${retryManager().getRetryCount()}/${retryManager().getMaxRetries()})")
                retryManager().waitForRetry()
            }
        }
        return proxyStarted
    }
}
