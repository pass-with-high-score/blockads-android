package app.pwhs.blockads.utils

import android.os.Build
import app.pwhs.blockads.utils.DeviceManager.Manufacturer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeviceManagerTest {

    @Test
    fun `manufacturer detection table`() {
        val table = mapOf(
            ("samsung" to "samsung") to Manufacturer.SAMSUNG,
            ("Unknown" to "SAMSUNG") to Manufacturer.SAMSUNG,
            ("Google" to "google") to Manufacturer.GOOGLE,
            ("OEM" to "google") to Manufacturer.GOOGLE,
            ("Xiaomi" to "Redmi") to Manufacturer.XIAOMI,
            ("POCO" to "poco") to Manufacturer.XIAOMI,
            ("OPPO" to "oppo") to Manufacturer.OPPO_REALME,
            ("realme" to "realme") to Manufacturer.OPPO_REALME,
            ("OnePlus" to "OnePlus") to Manufacturer.OPPO_REALME,
            ("vivo" to "vivo") to Manufacturer.VIVO,
            ("iQOO" to "iqoo") to Manufacturer.VIVO,
            ("HUAWEI" to "HUAWEI") to Manufacturer.HUAWEI_HONOR,
            ("HONOR" to "HONOR") to Manufacturer.HUAWEI_HONOR,
            ("motorola" to "motorola") to Manufacturer.MOTOROLA,
            ("Nothing" to "Nothing") to Manufacturer.GENERIC,
            ("" to "") to Manufacturer.GENERIC,
        )
        for ((input, expected) in table) {
            assertEquals("$input", expected, DeviceManager.detectManufacturer(input.first, input.second))
        }
    }

    @Test
    fun `every manufacturer and sdk gets steps ending at the certificate file`() {
        for (m in Manufacturer.entries) {
            for (sdk in listOf(Build.VERSION_CODES.R, Build.VERSION_CODES.S, Build.VERSION_CODES.UPSIDE_DOWN_CAKE)) {
                val steps = DeviceManager.installStepsFor(m, sdk)
                assertTrue("$m/$sdk", steps.size in 4..6)
                assertTrue("$m/$sdk", steps.last().contains("BlockAds-RootCA.crt"))
            }
        }
    }

    @Test
    fun `samsung and pixel steps change with the android version`() {
        val samsung = listOf(Build.VERSION_CODES.R, Build.VERSION_CODES.S, Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            .map { DeviceManager.installStepsFor(Manufacturer.SAMSUNG, it) }
        assertEquals(3, samsung.distinct().size)
        assertNotEquals(
            DeviceManager.installStepsFor(Manufacturer.GOOGLE, Build.VERSION_CODES.TIRAMISU),
            DeviceManager.installStepsFor(Manufacturer.GOOGLE, Build.VERSION_CODES.UPSIDE_DOWN_CAKE),
        )
        assertEquals(
            DeviceManager.installStepsFor(Manufacturer.GOOGLE, 34),
            DeviceManager.installStepsFor(Manufacturer.MOTOROLA, 34),
        )
        assertEquals(
            DeviceManager.installStepsFor(Manufacturer.HUAWEI_HONOR, 34),
            DeviceManager.installStepsFor(Manufacturer.GENERIC, 34),
        )
    }

    @Test
    fun `current device helpers use Build`() {
        assertEquals(DeviceManager.detectManufacturer(Build.MANUFACTURER, Build.BRAND), DeviceManager.currentManufacturer)
        assertEquals(DeviceManager.installStepsFor(DeviceManager.currentManufacturer, Build.VERSION.SDK_INT), DeviceManager.getInstallSteps())
        assertTrue(DeviceManager.currentBrandName.first().let { !it.isLowerCase() })
    }
}
