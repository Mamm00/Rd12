package com.boost12.rd12.data.adb.telemetry

import com.boost12.rd12.domain.model.DeviceTelemetry

/**
 * Defensive, zero-crash parser for `dumpsys battery`, `dumpsys thermalservice`, and `dumpsys display`.
 * All regex & numeric conversions tolerate malformed, truncated, localized, or empty HyperOS/MIUI output.
 */
object DumpsysTelemetryParser {
    private val LEVEL_REGEX = Regex("""(?m)^\s*level:\s*(\d+)""")
    private val SCALE_REGEX = Regex("""(?m)^\s*scale:\s*(\d+)""")
    private val TEMP_REGEX = Regex("""(?m)^\s*temperature:\s*(-?\d+)""")
    private val VOLTAGE_REGEX = Regex("""(?m)^\s*voltage:\s*(\d+)""")
    private val AC_POWER_REGEX = Regex("""(?m)^\s*AC powered:\s*(true|false)""", RegexOption.IGNORE_CASE)
    private val USB_POWER_REGEX = Regex("""(?m)^\s*USB powered:\s*(true|false)""", RegexOption.IGNORE_CASE)
    private val WIRELESS_POWER_REGEX = Regex("""(?m)^\s*Wireless powered:\s*(true|false)""", RegexOption.IGNORE_CASE)
    private val STATUS_REGEX = Regex("""(?m)^\s*status:\s*(\d+)""")

    private val THERMAL_STATUS_REGEX = Regex("""Thermal Status:\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val REFRESH_RATE_REGEX = Regex(
        """(?:renderFrameRate|fps|mDefaultPeakRefreshRate|user_refresh_rate)[=:\s]+(\d+(?:\.\d+)?)""",
        RegexOption.IGNORE_CASE
    )

    fun parse(
        batteryOutput: String?,
        thermalOutput: String? = null,
        displayOutput: String? = null,
        nowEpochMs: Long = System.currentTimeMillis()
    ): DeviceTelemetry {
        val battery = batteryOutput.orEmpty()
        val rawLevel = LEVEL_REGEX.find(battery)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val scale = SCALE_REGEX.find(battery)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 100
        val percent = if (rawLevel != null && scale > 0) {
            ((rawLevel * 100) / scale).coerceIn(0, 100)
        } else {
            null
        }

        val tenthsC = TEMP_REGEX.find(battery)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val tempC = tenthsC?.let { (it / 10.0f).takeIf { c -> c in -20f..110f } }

        val voltage = VOLTAGE_REGEX.find(battery)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val ac = AC_POWER_REGEX.find(battery)?.groupValues?.getOrNull(1)?.equals("true", true) == true
        val usb = USB_POWER_REGEX.find(battery)?.groupValues?.getOrNull(1)?.equals("true", true) == true
        val wireless = WIRELESS_POWER_REGEX.find(battery)?.groupValues?.getOrNull(1)?.equals("true", true) == true
        val statusCode = STATUS_REGEX.find(battery)?.groupValues?.getOrNull(1)?.toIntOrNull()
        // BatteryManager.BATTERY_STATUS_CHARGING == 2, FULL == 5
        val charging = ac || usb || wireless || statusCode == 2

        val thermalCode = THERMAL_STATUS_REGEX.find(thermalOutput.orEmpty())
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
        val thermalLabel = when (thermalCode) {
            0 -> "Nominal"
            1 -> "Light"
            2 -> "Moderate"
            3 -> "Severe"
            4 -> "Critical"
            5, 6 -> "Emergency"
            else -> tempC?.let {
                when {
                    it >= 44f -> "Hot"
                    it >= 39f -> "Warm"
                    else -> "Cool"
                }
            }
        }

        val refreshHz = REFRESH_RATE_REGEX.find(displayOutput.orEmpty())
            ?.groupValues?.getOrNull(1)
            ?.toFloatOrNull()
            ?.toInt()
            ?.takeIf { it in 30..144 }

        return DeviceTelemetry(
            batteryPercent = percent,
            temperatureCelsius = tempC,
            isCharging = charging,
            voltageMv = voltage,
            thermalStatusLabel = thermalLabel,
            activeRefreshRateHz = refreshHz,
            updatedAtEpochMs = nowEpochMs
        )
    }

    fun parseEndpointInput(
        rawHostPort: String,
        fallbackHost: String = "127.0.0.1"
    ): Pair<String, Int>? {
        val trimmed = rawHostPort.trim()
        if (trimmed.isEmpty()) return null

        // Pure port number e.g. "39421" or ":39421"
        val barePort = trimmed.removePrefix(":").toIntOrNull()
        if (barePort != null && barePort in 1..65535 && !trimmed.contains('.')) {
            return fallbackHost to barePort
        }

        val colonIdx = trimmed.lastIndexOf(':')
        if (colonIdx <= 0 || colonIdx == trimmed.lastIndex) return null

        val host = trimmed.substring(0, colonIdx).trim().removeSurrounding("[", "]")
        val port = trimmed.substring(colonIdx + 1).trim().toIntOrNull() ?: return null
        if (host.isBlank() || port !in 1..65535) return null
        return host to port
    }
}
