package app.pwhs.blockads.service

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.UserManager
import timber.log.Timber

class DeviceOwnerManager(private val context: Context) {

    private val devicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val componentName = ComponentName(context, AdBlockDeviceAdminReceiver::class.java)

    fun isDeviceOwner(): Boolean {
        return devicePolicyManager.isDeviceOwnerApp(context.packageName)
    }

    fun areRestrictionsEnforced(): Boolean {
        if (!isDeviceOwner()) return false
        val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
        return userManager.hasUserRestriction(UserManager.DISALLOW_UNINSTALL_APPS)
    }

    fun enforceRestrictions() {
        if (!isDeviceOwner()) return

        Timber.d("Enforcing Device Owner restrictions")
        
        // Set as always-on VPN FIRST before applying DISALLOW_CONFIG_VPN
        // If we apply the restriction first, the OS immediately kills the active VPN
        try {
            devicePolicyManager.setAlwaysOnVpnPackage(
                componentName,
                context.packageName,
                false // lockdown disabled at OS level to allow bypassed apps and the VPN itself to access the internet
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to set always-on VPN via DPM")
        }

        val restrictions = listOf(
            UserManager.DISALLOW_CONFIG_VPN,
            UserManager.DISALLOW_DEBUGGING_FEATURES
        )

        for (restriction in restrictions) {
            devicePolicyManager.addUserRestriction(componentName, restriction)
        }
    }

    fun clearRestrictions() {
        if (!isDeviceOwner()) return

        Timber.d("Clearing Device Owner restrictions")
        
        try {
            devicePolicyManager.setAlwaysOnVpnPackage(
                componentName,
                null,
                false
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to clear always-on VPN via DPM")
        }

        val restrictions = listOf(
            UserManager.DISALLOW_CONFIG_VPN,
            UserManager.DISALLOW_DEBUGGING_FEATURES
        )

        for (restriction in restrictions) {
            devicePolicyManager.clearUserRestriction(componentName, restriction)
        }
    }

}
