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

    companion object {
        val REQUIRED_RESTRICTIONS = listOf(
            UserManager.DISALLOW_CONFIG_VPN,
            UserManager.DISALLOW_DEBUGGING_FEATURES
        )
    }

    fun isDeviceOwner(): Boolean {
        return devicePolicyManager.isDeviceOwnerApp(context.packageName)
    }

    fun areRestrictionsEnforced(): Boolean {
        if (!isDeviceOwner()) return false

        val alwaysOnVpnPackage = try {
            devicePolicyManager.getAlwaysOnVpnPackage(componentName)
        } catch (e: Exception) {
            Timber.e(e, "Failed to get always-on VPN package")
            null
        }
        val isAlwaysOnVpnSet = alwaysOnVpnPackage == context.packageName

        val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
            ?: return false

        val allRestrictionsSet = REQUIRED_RESTRICTIONS.all { restriction ->
            userManager.hasUserRestriction(restriction)
        }

        return isAlwaysOnVpnSet && allRestrictionsSet
    }

    fun enforceRestrictions(): Boolean {
        if (!isDeviceOwner()) return false

        Timber.d("Enforcing Device Owner restrictions")
        var success = true

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
            success = false
        }

        for (restriction in REQUIRED_RESTRICTIONS) {
            try {
                devicePolicyManager.addUserRestriction(componentName, restriction)
            } catch (e: Exception) {
                Timber.e(e, "Failed to add restriction $restriction via DPM")
                success = false
            }
        }

        return success
    }

    fun clearRestrictions(): Boolean {
        if (!isDeviceOwner()) return false

        Timber.d("Clearing Device Owner restrictions")
        var success = true

        try {
            devicePolicyManager.setAlwaysOnVpnPackage(
                componentName,
                null,
                false
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to clear always-on VPN via DPM")
            success = false
        }

        for (restriction in REQUIRED_RESTRICTIONS) {
            try {
                devicePolicyManager.clearUserRestriction(componentName, restriction)
            } catch (e: Exception) {
                Timber.e(e, "Failed to clear restriction $restriction via DPM")
                success = false
            }
        }

        return success
    }

    fun clearDeviceOwner(): Boolean {
        if (!isDeviceOwner()) return false

        Timber.d("Clearing Device Owner status")
        return try {
            clearRestrictions()
            devicePolicyManager.clearDeviceOwnerApp(context.packageName)
            Timber.i("Device owner cleared successfully")
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to clear Device Owner")
            false
        }
    }

}
