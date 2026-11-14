package app.pwhs.blockads.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import app.pwhs.blockads.service.vpn.VpnStatusStore
import app.pwhs.blockads.utils.VpnUtils
import timber.log.Timber

object VpnStateBridge {
    const val PREF_NAME = "blockads_vpn_state_bridge"
    const val KEY_VPN_STATE = "direct_boot_vpn_state"
    const val KEY_START_TIMESTAMP = "direct_boot_start_timestamp"
    const val KEY_LAST_STOPPED_TIMESTAMP = "direct_boot_last_stopped_timestamp"
    const val KEY_PRIVATE_DNS_STRICT = "direct_boot_private_dns_strict"
    const val KEY_VPN_ENABLED = "direct_boot_vpn_enabled"

    @Volatile
    private var isBridgeInitialized = false

    private fun getPrefs(context: Context): SharedPreferences {
        val deContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (context.isDeviceProtectedStorage) context else context.createDeviceProtectedStorageContext()
        } else {
            context
        }
        return deContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun persistState(
        context: Context,
        state: VpnState,
        startTimestamp: Long,
        lastStoppedTimestamp: Long,
        privateDnsStrict: Boolean
    ) {
        try {
            getPrefs(context).edit()
                .putString(KEY_VPN_STATE, state.name)
                .putLong(KEY_START_TIMESTAMP, startTimestamp)
                .putLong(KEY_LAST_STOPPED_TIMESTAMP, lastStoppedTimestamp)
                .putBoolean(KEY_PRIVATE_DNS_STRICT, privateDnsStrict)
                .putBoolean(KEY_VPN_ENABLED, state == VpnState.RUNNING || state == VpnState.STARTING)
                .commit()
        } catch (e: Exception) {
            Timber.w(e, "Failed to persist VPN state to DirectBoot preferences")
        }
    }

    fun hydrateState(
        context: Context,
        status: VpnStatusStore
    ) {
        try {
            val prefs = getPrefs(context)
            val stateName = prefs.getString(KEY_VPN_STATE, null)
            if (stateName != null) {
                try {
                    status.state.value = VpnState.valueOf(stateName)
                } catch (e: Exception) {
                    Timber.w(e, "Unknown hydrated VPN state: $stateName")
                }
            } else if (prefs.getBoolean(KEY_VPN_ENABLED, false)) {
                status.state.value = VpnState.RUNNING
            }
            status.startTimestamp = prefs.getLong(KEY_START_TIMESTAMP, 0L)
            status.lastStoppedTimestamp = prefs.getLong(KEY_LAST_STOPPED_TIMESTAMP, 0L)
            status.privateDnsStrict.value = prefs.getBoolean(KEY_PRIVATE_DNS_STRICT, false)

            // Reconcile: If hydrated state is RUNNING or STARTING on cold boot, verify against OS active VPN transport.
            // If the OS reports no active VPN transport, fall back to STOPPED and reset startTimestamp.
            if ((status.state.value == VpnState.RUNNING || status.state.value == VpnState.STARTING) &&
                !VpnUtils.isVpnTransportActive(context)
            ) {
                Timber.i("Cold-boot reconciliation: No active VPN transport detected, falling back to STOPPED")
                status.state.value = VpnState.STOPPED
                status.startTimestamp = 0L
                persistState(
                    context = context,
                    state = VpnState.STOPPED,
                    startTimestamp = 0L,
                    lastStoppedTimestamp = status.lastStoppedTimestamp,
                    privateDnsStrict = status.privateDnsStrict.value
                )
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to hydrate VPN state from DirectBoot preferences")
        }
    }

    fun initMainProcessBridge(context: Context, status: VpnStatusStore) {
        if (isBridgeInitialized) return
        synchronized(this) {
            if (isBridgeInitialized) return
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    if (intent?.action == AdBlockVpnService.ACTION_VPN_STATE_CHANGED) {
                        val stateStr = intent.getStringExtra(AdBlockVpnService.EXTRA_VPN_STATE)
                        val startTime = intent.getLongExtra(AdBlockVpnService.EXTRA_START_TIMESTAMP, 0L)
                        val stopTime = intent.getLongExtra(AdBlockVpnService.EXTRA_LAST_STOPPED_TIMESTAMP, 0L)
                        val privateDns = intent.getBooleanExtra(AdBlockVpnService.EXTRA_PRIVATE_DNS_STRICT, false)

                        if (stateStr != null) {
                            try {
                                status.state.value = VpnState.valueOf(stateStr)
                            } catch (e: Exception) {
                                Timber.w(e, "Failed to parse VPN state: $stateStr")
                            }
                        }
                        status.startTimestamp = startTime
                        status.lastStoppedTimestamp = stopTime
                        status.privateDnsStrict.value = privateDns
                    }
                }
            }
            val filter = IntentFilter(AdBlockVpnService.ACTION_VPN_STATE_CHANGED)
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            hydrateState(context, status)
            isBridgeInitialized = true
        }
    }

    @VisibleForTesting
    internal fun resetBridgeForTesting() {
        isBridgeInitialized = false
    }
}
