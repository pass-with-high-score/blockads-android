package app.pwhs.blockads.service

import android.content.Context
import app.pwhs.blockads.R
import app.pwhs.blockads.utils.AppNameResolver

/**
 * Maps raw package or app names from Go tunnel log callbacks into human-readable app identities.
 */
object DnsLogMapper {
    fun resolveAppIdentity(
        packageNameOrAppName: String,
        context: Context,
        appNameResolver: AppNameResolver
    ): Pair<String, String> {
        return when {
            packageNameOrAppName.isEmpty() ||
                packageNameOrAppName == "RootProxy" ||
                packageNameOrAppName == "uid:0" ||
                packageNameOrAppName == "uid:-1" ||
                packageNameOrAppName.equals("unknown", ignoreCase = true) -> {
                context.getString(R.string.log_unknown_app) to ""
            }
            packageNameOrAppName.startsWith("uid:") -> {
                val uid = packageNameOrAppName.removePrefix("uid:").toIntOrNull()
                if (uid != null) {
                    val pkg = appNameResolver.getPackageNameForUid(uid)
                    val name = appNameResolver.getAppNameForUid(uid)
                    val friendlyName = if (name.isNotEmpty() && name != "uid:$uid") {
                        name
                    } else {
                        context.getString(R.string.log_unknown_app)
                    }
                    friendlyName to pkg
                } else {
                    context.getString(R.string.log_unknown_app) to ""
                }
            }
            packageNameOrAppName.contains(".") -> {
                val label = runCatching {
                    val pm = context.packageManager
                    pm.getApplicationLabel(pm.getApplicationInfo(packageNameOrAppName, 0)).toString()
                }.getOrDefault(packageNameOrAppName)
                label to packageNameOrAppName
            }
            else -> {
                packageNameOrAppName to ""
            }
        }
    }

    fun dnsQueryTypeToString(type: Int): String = when (type) {
        1 -> "A"
        28 -> "AAAA"
        5 -> "CNAME"
        65 -> "HTTPS"
        else -> "OTHER"
    }
}
