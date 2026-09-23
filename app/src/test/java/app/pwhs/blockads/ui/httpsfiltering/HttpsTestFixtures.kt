package app.pwhs.blockads.ui.httpsfiltering

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.pwhs.blockads.data.datastore.AppPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.robolectric.Shadows.shadowOf
import tunnel.Engine
import tunnel.Tunnel

const val TEST_PEM = "-----BEGIN CERTIFICATE-----\nnot-a-real-cert\n-----END CERTIFICATE-----"

/** Installs a fake [Engine] behind `Tunnel.newEngine()` and a relaxed [AppPreferences] in Koin. */
class HttpsTestFixtures(
    httpsEnabled: Boolean = false,
    filterHttp3: Boolean = true,
    routingMode: String = AppPreferences.ROUTING_MODE_DIRECT,
    selectedBrowsers: Set<String> = emptySet(),
) {
    val app: Application = ApplicationProvider.getApplicationContext()
    val engine: Engine = mockk(relaxed = true)
    val prefs: AppPreferences = mockk(relaxed = true)
    var savedBrowsers: Set<String> = selectedBrowsers

    init {
        mockkStatic(Tunnel::class)
        every { Tunnel.newEngine() } returns engine
        every { engine.getMitmCACert(any()) } returns ""
        every { engine.startStackMitm(any()) } returns ""
        coEvery { prefs.getHttpsFilteringEnabledSnapshot() } returns httpsEnabled
        coEvery { prefs.getFilterHttp3Snapshot() } returns filterHttp3
        coEvery { prefs.getRoutingModeSnapshot() } returns routingMode
        every { prefs.getSelectedBrowsersSnapshot() } answers { savedBrowsers }
        coEvery { prefs.setSelectedBrowsers(any()) } answers { savedBrowsers = firstArg() }
        startKoin { modules(module { single { prefs } }) }
    }

    fun installBrowser(packageName: String, label: String, uid: Int) {
        val appInfo = ApplicationInfo().apply {
            this.packageName = packageName
            this.uid = uid
            nonLocalizedLabel = label
        }
        shadowOf(app.packageManager).installPackage(
            PackageInfo().apply {
                this.packageName = packageName
                applicationInfo = appInfo
            }
        )
        val resolve = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                this.packageName = packageName
                name = "$packageName.Main"
                applicationInfo = appInfo
            }
        }
        shadowOf(app.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com")),
            resolve
        )
    }
}
