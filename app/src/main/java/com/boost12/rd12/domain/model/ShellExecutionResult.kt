package com.boost12.rd12.domain.model

data class ShellExecutionResult(
    val command: String,
    val stdout: String,
    val stderr: String = "",
    val exitCode: Int,
    val durationMs: Long,
    val timedOut: Boolean = false,
    val retried: Boolean = false
) {
    val isSuccess: Boolean
        get() = !timedOut && exitCode == 0

    val combinedOutput: String
        get() = buildString {
            if (stdout.isNotBlank()) append(stdout.trim())
            if (stderr.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append(stderr.trim())
            }
        }
}

data class DeviceTelemetry(
    val batteryPercent: Int? = null,
    val temperatureCelsius: Float? = null,
    val isCharging: Boolean = false,
    val voltageMv: Int? = null,
    val thermalStatusLabel: String? = null,
    val activeRefreshRateHz: Int? = null,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

enum class DebugLogLevel {
    INFO,
    PROTOCOL,
    SHELL,
    WARN,
    ERROR
}

data class AdbDebugLogEntry(
    val id: Long,
    val timestampEpochMs: Long,
    val level: DebugLogLevel,
    val tag: String,
    val message: String,
    val details: String? = null
)
