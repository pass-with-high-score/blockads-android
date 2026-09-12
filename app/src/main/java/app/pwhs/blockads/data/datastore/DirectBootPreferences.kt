package app.pwhs.blockads.data.datastore

import android.content.Context
import android.content.SharedPreferences
import android.os.Build

/**
 * DirectBootPreferences stores essential VPN boot & connection state
 * in Device Encrypted (DE) storage via [Context.createDeviceProtectedStorageContext].
 *
 * This allows BlockAds to safely access preferences immediately upon receiving
 * [android.intent.action.LOCKED_BOOT_COMPLETED] BEFORE the user has unlocked the device
 * with their PIN/pattern/password, preventing telemetry leaks and preserving adblock protection.
 */
class DirectBootPreferences(context: Context) {

    private val deContext: Context = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        if (context.isDeviceProtectedStorage) context else context.createDeviceProtectedStorageContext()
    } else {
        context
    }

    private val prefs: SharedPreferences by lazy {
        deContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    var wasVpnEnabled: Boolean
        get() = prefs.getBoolean(KEY_VPN_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_VPN_ENABLED, value).apply()

    var autoReconnect: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RECONNECT, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_RECONNECT, value).apply()

    var routingMode: String
        get() = prefs.getString(KEY_ROUTING_MODE, AppPreferences.ROUTING_MODE_DIRECT) ?: AppPreferences.ROUTING_MODE_DIRECT
        set(value) = prefs.edit().putString(KEY_ROUTING_MODE, value).apply()

    companion object {
        private const val PREF_NAME = "blockads_direct_boot_state"
        private const val KEY_VPN_ENABLED = "direct_boot_vpn_enabled"
        private const val KEY_AUTO_RECONNECT = "direct_boot_auto_reconnect"
        private const val KEY_ROUTING_MODE = "direct_boot_routing_mode"
    }
}
