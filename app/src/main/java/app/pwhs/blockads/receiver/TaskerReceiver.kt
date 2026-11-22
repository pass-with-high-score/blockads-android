package app.pwhs.blockads.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.service.AdBlockVpnService
import app.pwhs.blockads.service.RootProxyService
import app.pwhs.blockads.service.ServiceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Receiver designed for automation apps like Tasker and MacroDroid.
 * Allows starting/stopping Adblocker as well as enabling/disabling/toggling
 * outbound proxy (WireGuard / Shadowsocks / SOCKS5) via broadcast intents.
 *
 * Actions:
 * - app.pwhs.blockads.TASKER_START
 * - app.pwhs.blockads.TASKER_STOP
 * - app.pwhs.blockads.TASKER_START_PROXY (alias: app.pwhs.blockads.TASKER_PROXY_START, app.pwhs.blockads.START_PROXY)
 * - app.pwhs.blockads.TASKER_STOP_PROXY (alias: app.pwhs.blockads.TASKER_PROXY_STOP, app.pwhs.blockads.STOP_PROXY)
 * - app.pwhs.blockads.TASKER_TOGGLE_PROXY (alias: app.pwhs.blockads.TOGGLE_PROXY)
 *
 * Optional Extras for proxy actions:
 * - "profile" / "profile_name" / "profile_id": String (matches WireGuard or Proxy node profile by name, server, or id)
 */
class TaskerReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_START = "app.pwhs.blockads.TASKER_START"
        const val ACTION_STOP = "app.pwhs.blockads.TASKER_STOP"

        const val ACTION_START_PROXY = "app.pwhs.blockads.TASKER_START_PROXY"
        const val ACTION_PROXY_START = "app.pwhs.blockads.TASKER_PROXY_START"
        const val ACTION_LEGACY_START_PROXY = "app.pwhs.blockads.START_PROXY"

        const val ACTION_STOP_PROXY = "app.pwhs.blockads.TASKER_STOP_PROXY"
        const val ACTION_PROXY_STOP = "app.pwhs.blockads.TASKER_PROXY_STOP"
        const val ACTION_LEGACY_STOP_PROXY = "app.pwhs.blockads.STOP_PROXY"

        const val ACTION_TOGGLE_PROXY = "app.pwhs.blockads.TASKER_TOGGLE_PROXY"
        const val ACTION_LEGACY_TOGGLE_PROXY = "app.pwhs.blockads.TOGGLE_PROXY"

        const val EXTRA_PROFILE = "profile"
        const val EXTRA_PROFILE_NAME = "profile_name"
        const val EXTRA_PROFILE_ID = "profile_id"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Timber.d("TaskerReceiver received action: $action")

        when (action) {
            ACTION_START -> {
                ServiceController.requestStart(context)
            }
            ACTION_STOP -> {
                ServiceController.requestStop(context)
            }
            ACTION_START_PROXY, ACTION_PROXY_START, ACTION_LEGACY_START_PROXY -> {
                val pending = goAsync()
                scope.launch {
                    try {
                        startProxy(context, intent)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to start proxy from TaskerReceiver")
                    } finally {
                        pending?.finish()
                    }
                }
            }
            ACTION_STOP_PROXY, ACTION_PROXY_STOP, ACTION_LEGACY_STOP_PROXY -> {
                val pending = goAsync()
                scope.launch {
                    try {
                        stopProxy(context)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to stop proxy from TaskerReceiver")
                    } finally {
                        pending?.finish()
                    }
                }
            }
            ACTION_TOGGLE_PROXY, ACTION_LEGACY_TOGGLE_PROXY -> {
                val pending = goAsync()
                scope.launch {
                    try {
                        toggleProxy(context, intent)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to toggle proxy from TaskerReceiver")
                    } finally {
                        pending?.finish()
                    }
                }
            }
        }
    }

    private suspend fun startProxy(context: Context, intent: Intent) {
        val appPrefs = AppPreferences(context)
        selectProfileIfSpecified(intent, appPrefs)

        if (appPrefs.getHttpsFilteringEnabledSnapshot()) {
            appPrefs.setHttpsFilteringEnabled(false)
        }
        appPrefs.setRoutingMode(AppPreferences.ROUTING_MODE_PROXY_VPN)

        if (AdBlockVpnService.isRunning || RootProxyService.isRunning) {
            ServiceController.requestRestart(context)
        } else {
            ServiceController.requestStart(context)
        }
    }

    private suspend fun stopProxy(context: Context) {
        val appPrefs = AppPreferences(context)
        appPrefs.setRoutingMode(AppPreferences.ROUTING_MODE_DIRECT)

        if (AdBlockVpnService.isRunning || RootProxyService.isRunning) {
            ServiceController.requestRestart(context)
        }
    }

    private suspend fun toggleProxy(context: Context, intent: Intent) {
        val appPrefs = AppPreferences(context)
        val currentMode = appPrefs.getRoutingModeSnapshot()
        val isProxyActive = currentMode == AppPreferences.ROUTING_MODE_PROXY_VPN ||
            currentMode == AppPreferences.ROUTING_MODE_WIREGUARD ||
            currentMode == AppPreferences.ROUTING_MODE_SHADOWSOCKS

        if (isProxyActive) {
            stopProxy(context)
        } else {
            startProxy(context, intent)
        }
    }

    private suspend fun selectProfileIfSpecified(intent: Intent, appPrefs: AppPreferences) {
        val target = intent.getStringExtra(EXTRA_PROFILE)
            ?: intent.getStringExtra(EXTRA_PROFILE_NAME)
            ?: intent.getStringExtra(EXTRA_PROFILE_ID)
            ?: return

        val trimmed = target.trim()
        if (trimmed.isEmpty()) return

        val wgProfiles = appPrefs.getWgProfilesSnapshot()
        val matchedWg = wgProfiles.firstOrNull {
            it.id.equals(trimmed, ignoreCase = true) || it.name.equals(trimmed, ignoreCase = true)
        }
        if (matchedWg != null) {
            appPrefs.setActiveWgProfile(matchedWg.id)
            appPrefs.setActiveProxyType(AppPreferences.PROXY_TYPE_WIREGUARD)
            return
        }

        val proxyProfiles = appPrefs.getProxyProfilesSnapshot()
        val matchedProxy = proxyProfiles.firstOrNull {
            it.id.equals(trimmed, ignoreCase = true) ||
                it.displayName.equals(trimmed, ignoreCase = true) ||
                it.server.equals(trimmed, ignoreCase = true)
        }
        if (matchedProxy != null) {
            appPrefs.setActiveProxyProfileId(matchedProxy.id)
            appPrefs.setActiveProxyType(AppPreferences.PROXY_TYPE_SHADOWSOCKS)
        }
    }
}
