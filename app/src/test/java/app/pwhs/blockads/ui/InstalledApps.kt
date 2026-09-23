package app.pwhs.blockads.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import org.robolectric.Shadows.shadowOf

/** Registers a package with Robolectric's PackageManager so getInstalledApplications lists it. */
fun Context.installApp(packageName: String, label: String, system: Boolean = false) {
    val info = PackageInfo().apply {
        this.packageName = packageName
        applicationInfo = ApplicationInfo().apply {
            this.packageName = packageName
            nonLocalizedLabel = label
            flags = if (system) ApplicationInfo.FLAG_SYSTEM else 0
        }
    }
    shadowOf(packageManager).installPackage(info)
}
