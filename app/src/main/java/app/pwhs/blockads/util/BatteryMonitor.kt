package app.pwhs.blockads.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log

/**
 * Utility class for monitoring battery usage and status.
 * Provides information about battery level, charging status, and battery health.
 */
class BatteryMonitor(private val context: Context) {

    companion object {
        private const val TAG = "BatteryMonitor"
    }

    /**
     * Get current battery level as a percentage (0-100)
     */
    fun getBatteryLevel(): Int {
        val batteryStatus = getBatteryStatus()
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1

        return if (level != -1 && scale != -1) {
            (level.toFloat() / scale.toFloat() * 100).toInt()
        } else {
            -1
        }
    }

    /**
     * Check if device is currently charging
     */
    fun isCharging(): Boolean {
        val batteryStatus = getBatteryStatus()
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
    }

    /**
     * Get charging method (USB, AC, Wireless, or None)
     */
    fun getChargingMethod(): ChargingMethod {
        if (!isCharging()) return ChargingMethod.NONE

        val batteryStatus = getBatteryStatus()
        val chargePlug = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1

        return when (chargePlug) {
            BatteryManager.BATTERY_PLUGGED_USB -> ChargingMethod.USB
            BatteryManager.BATTERY_PLUGGED_AC -> ChargingMethod.AC
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> ChargingMethod.WIRELESS
            else -> ChargingMethod.NONE
        }
    }

    /**
     * Get battery health status
     */
    fun getBatteryHealth(): BatteryHealth {
        val batteryStatus = getBatteryStatus()
        val health = batteryStatus?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1

        return when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> BatteryHealth.GOOD
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> BatteryHealth.OVERHEAT
            BatteryManager.BATTERY_HEALTH_DEAD -> BatteryHealth.DEAD
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> BatteryHealth.OVER_VOLTAGE
            BatteryManager.BATTERY_HEALTH_COLD -> BatteryHealth.COLD
            else -> BatteryHealth.UNKNOWN
        }
    }

    /**
     * Get battery temperature in Celsius
     */
    fun getBatteryTemperature(): Float {
        val batteryStatus = getBatteryStatus()
        val temp = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        return if (temp != -1) temp / 10.0f else -1f
    }

    /**
     * Get battery voltage in millivolts
     */
    fun getBatteryVoltage(): Int {
        val batteryStatus = getBatteryStatus()
        return batteryStatus?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
    }

    /**
     * Get battery status information as a formatted string
     */
    fun getBatteryInfo(): BatteryInfo {
        return BatteryInfo(
            level = getBatteryLevel(),
            isCharging = isCharging(),
            chargingMethod = getChargingMethod(),
            health = getBatteryHealth(),
            temperature = getBatteryTemperature(),
            voltage = getBatteryVoltage()
        )
    }

    /**
     * Log current battery status
     */
    fun logBatteryStatus() {
        val info = getBatteryInfo()
        Log.d(TAG, "Battery Status: ${info.level}%, Charging: ${info.isCharging}, " +
                "Method: ${info.chargingMethod}, Health: ${info.health}, " +
                "Temp: ${info.temperature}°C, Voltage: ${info.voltage}mV")
    }

    private fun getBatteryStatus(): Intent? {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        return context.registerReceiver(null, intentFilter)
    }

    enum class ChargingMethod {
        NONE, USB, AC, WIRELESS
    }

    enum class BatteryHealth {
        UNKNOWN, GOOD, OVERHEAT, DEAD, OVER_VOLTAGE, COLD
    }

    data class BatteryInfo(
        val level: Int,
        val isCharging: Boolean,
        val chargingMethod: ChargingMethod,
        val health: BatteryHealth,
        val temperature: Float,
        val voltage: Int
    ) {
        override fun toString(): String {
            return "Battery: $level%, Charging: $isCharging ($chargingMethod), " +
                    "Health: $health, Temp: ${temperature}°C, Voltage: ${voltage}mV"
        }
    }
}
