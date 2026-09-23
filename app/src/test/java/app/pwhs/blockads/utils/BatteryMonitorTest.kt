package app.pwhs.blockads.utils

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import app.pwhs.blockads.utils.BatteryMonitor.BatteryHealth
import app.pwhs.blockads.utils.BatteryMonitor.ChargingMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BatteryMonitorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val monitor = BatteryMonitor(context)

    @Suppress("DEPRECATION")
    private fun battery(
        level: Int = 50, scale: Int = 100, status: Int = BatteryManager.BATTERY_STATUS_DISCHARGING,
        plugged: Int = 0, health: Int = BatteryManager.BATTERY_HEALTH_GOOD, temp: Int = 315, voltage: Int = 4100,
    ) = context.sendStickyBroadcast(
        Intent(Intent.ACTION_BATTERY_CHANGED)
            .putExtra(BatteryManager.EXTRA_LEVEL, level)
            .putExtra(BatteryManager.EXTRA_SCALE, scale)
            .putExtra(BatteryManager.EXTRA_STATUS, status)
            .putExtra(BatteryManager.EXTRA_PLUGGED, plugged)
            .putExtra(BatteryManager.EXTRA_HEALTH, health)
            .putExtra(BatteryManager.EXTRA_TEMPERATURE, temp)
            .putExtra(BatteryManager.EXTRA_VOLTAGE, voltage)
    )

    @Test
    fun `unknown values without a battery broadcast`() {
        assertEquals(-1, monitor.getBatteryLevel())
        assertFalse(monitor.isCharging())
        assertEquals(ChargingMethod.NONE, monitor.getChargingMethod())
        assertEquals(BatteryHealth.UNKNOWN, monitor.getBatteryHealth())
        assertEquals(-1f, monitor.getBatteryTemperature())
        assertEquals(-1, monitor.getBatteryVoltage())
    }

    @Test
    fun `reads the sticky battery broadcast`() {
        battery(level = 3, scale = 4, temp = 315, voltage = 4100)

        val info = monitor.getBatteryInfo()
        assertEquals(BatteryMonitor.BatteryInfo(75, false, ChargingMethod.NONE, BatteryHealth.GOOD, 31.5f, 4100), info)
        assertEquals("Battery: 75%, Charging: false (NONE), Health: GOOD, Temp: 31.5°C, Voltage: 4100mV", info.toString())
        monitor.logBatteryStatus()
    }

    @Test
    fun `charging methods`() {
        val plugs = mapOf(
            BatteryManager.BATTERY_PLUGGED_USB to ChargingMethod.USB,
            BatteryManager.BATTERY_PLUGGED_AC to ChargingMethod.AC,
            BatteryManager.BATTERY_PLUGGED_WIRELESS to ChargingMethod.WIRELESS,
            0 to ChargingMethod.NONE,
        )
        for ((plug, method) in plugs) {
            battery(status = BatteryManager.BATTERY_STATUS_CHARGING, plugged = plug)
            assertTrue(monitor.isCharging())
            assertEquals(method, monitor.getChargingMethod())
        }
        battery(status = BatteryManager.BATTERY_STATUS_FULL, plugged = BatteryManager.BATTERY_PLUGGED_AC)
        assertTrue(monitor.isCharging())
        battery(status = BatteryManager.BATTERY_STATUS_NOT_CHARGING, plugged = BatteryManager.BATTERY_PLUGGED_AC)
        assertEquals(ChargingMethod.NONE, monitor.getChargingMethod())
    }

    @Test
    fun `health mapping`() {
        val table = mapOf(
            BatteryManager.BATTERY_HEALTH_GOOD to BatteryHealth.GOOD,
            BatteryManager.BATTERY_HEALTH_OVERHEAT to BatteryHealth.OVERHEAT,
            BatteryManager.BATTERY_HEALTH_DEAD to BatteryHealth.DEAD,
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE to BatteryHealth.OVER_VOLTAGE,
            BatteryManager.BATTERY_HEALTH_COLD to BatteryHealth.COLD,
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE to BatteryHealth.UNKNOWN,
        )
        for ((raw, health) in table) {
            battery(health = raw)
            assertEquals(health, monitor.getBatteryHealth())
        }
    }
}
