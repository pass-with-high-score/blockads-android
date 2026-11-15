package app.pwhs.blockads.service

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import app.pwhs.blockads.service.vpn.VpnStatusStore
import app.pwhs.blockads.utils.VpnUtils
import timber.log.Timber

object VpnStateBridge {
    const val PREF_NAME = "blockads_vpn_state_bridge"
    const val KEY_VPN_STATE = "direct_boot_vpn_state"
    const val KEY_VPN_PHASE = "direct_boot_vpn_phase"
    const val KEY_START_TIMESTAMP = "direct_boot_start_timestamp"
    const val KEY_LAST_STOPPED_TIMESTAMP = "direct_boot_last_stopped_timestamp"
    const val KEY_PRIVATE_DNS_STRICT = "direct_boot_private_dns_strict"
    const val KEY_VPN_ENABLED = "direct_boot_vpn_enabled"

    @Volatile
    private var isBridgeInitialized = false

    @Volatile
    internal var isLivenessBound = false
        private set

    private var livenessConnection: ServiceConnection? = null

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
        privateDnsStrict: Boolean,
        phase: String = ""
    ) {
        try {
            getPrefs(context).edit()
                .putString(KEY_VPN_STATE, state.name)
                .putString(KEY_VPN_PHASE, phase)
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
            status.phase.value = prefs.getString(KEY_VPN_PHASE, "") ?: ""
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
                status.phase.value = ""
                status.startTimestamp = 0L
                persistState(
                    context = context,
                    state = VpnState.STOPPED,
                    startTimestamp = 0L,
                    lastStoppedTimestamp = status.lastStoppedTimestamp,
                    privateDnsStrict = status.privateDnsStrict.value,
                    phase = ""
                )
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to hydrate VPN state from DirectBoot preferences")
        }
    }

    internal fun bindLivenessMonitor(context: Context, status: VpnStatusStore) {
        val appContext = context.applicationContext ?: context
        synchronized(this) {
            if (isLivenessBound) return
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    // Liveness monitor only tracks remote process disconnection
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    handleRemoteProcessDied(appContext, status)
                }

                override fun onBindingDied(name: ComponentName?) {
                    handleRemoteProcessDied(appContext, status)
                }
            }
            val intent = Intent(appContext, AdBlockVpnService::class.java).apply {
                action = AdBlockVpnService.ACTION_TAILSCALE_BIND
            }
            try {
                // Flag 0 binds to an existing service without auto-creating :vpn if stopped
                isLivenessBound = appContext.bindService(intent, conn, 0)
                if (isLivenessBound) {
                    livenessConnection = conn
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to bind VPN liveness monitor")
            }
        }
    }

    internal fun unbindLivenessMonitor(context: Context) {
        val appContext = context.applicationContext ?: context
        synchronized(this) {
            if (!isLivenessBound) return
            try {
                livenessConnection?.let { appContext.unbindService(it) }
            } catch (e: Exception) {
                Timber.w(e, "Failed to unbind VPN liveness monitor")
            } finally {
                isLivenessBound = false
                livenessConnection = null
            }
        }
    }

    private fun handleRemoteProcessDied(context: Context, status: VpnStatusStore) {
        unbindLivenessMonitor(context)
        if (status.state.value != VpnState.STOPPED) {
            Timber.w("VPN process (:vpn) died unexpectedly; resetting state to STOPPED")
            val now = System.currentTimeMillis()
            status.state.value = VpnState.STOPPED
            status.phase.value = ""
            status.startTimestamp = 0L
            status.lastStoppedTimestamp = now
            persistState(
                context = context,
                state = VpnState.STOPPED,
                startTimestamp = 0L,
                lastStoppedTimestamp = now,
                privateDnsStrict = status.privateDnsStrict.value,
                phase = ""
            )
        }
    }

    private fun syncLivenessBinding(context: Context, status: VpnStatusStore) {
        when (status.state.value) {
            VpnState.STARTING, VpnState.RUNNING, VpnState.RESTARTING -> bindLivenessMonitor(context, status)
            VpnState.STOPPED -> unbindLivenessMonitor(context)
            VpnState.STOPPING -> {}
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
                        val phaseStr = intent.getStringExtra(AdBlockVpnService.EXTRA_VPN_PHASE)
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
                        if (phaseStr != null) {
                            status.phase.value = phaseStr
                        }
                        status.startTimestamp = startTime
                        status.lastStoppedTimestamp = stopTime
                        status.privateDnsStrict.value = privateDns
                        syncLivenessBinding(context, status)
                    }
                }
            }
            val filter = IntentFilter(AdBlockVpnService.ACTION_VPN_STATE_CHANGED)
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            hydrateState(context, status)
            syncLivenessBinding(context, status)
            isBridgeInitialized = true
        }
    }

    @VisibleForTesting
    internal fun resetBridgeForTesting() {
        isBridgeInitialized = false
        isLivenessBound = false
        livenessConnection = null
    }
}
