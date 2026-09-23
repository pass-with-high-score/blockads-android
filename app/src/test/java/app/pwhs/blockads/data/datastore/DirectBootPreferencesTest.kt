package app.pwhs.blockads.data.datastore

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DirectBootPreferencesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `defaults match the credential-encrypted preferences`() {
        val prefs = DirectBootPreferences(context)
        assertFalse(prefs.wasVpnEnabled)
        assertTrue(prefs.autoReconnect)
        assertEquals(AppPreferences.ROUTING_MODE_DIRECT, prefs.routingMode)
    }

    @Test
    fun `values persist across instances and device-protected contexts`() {
        DirectBootPreferences(context).apply {
            wasVpnEnabled = true
            autoReconnect = false
            routingMode = AppPreferences.ROUTING_MODE_ROOT
        }

        val fromDe = DirectBootPreferences(context.createDeviceProtectedStorageContext())
        assertTrue(fromDe.wasVpnEnabled)
        assertFalse(fromDe.autoReconnect)
        assertEquals(AppPreferences.ROUTING_MODE_ROOT, fromDe.routingMode)
    }
}
