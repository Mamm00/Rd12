package com.boost12.rd12.domain.repository

import com.boost12.rd12.domain.model.AdbConnectionState
import com.boost12.rd12.domain.model.AdbDebugLogEntry
import com.boost12.rd12.domain.model.DeviceProfile
import com.boost12.rd12.domain.model.DeviceTelemetry
import com.boost12.rd12.domain.model.MdnsDiscoveredEndpoint
import com.boost12.rd12.domain.model.SavedConnectionConfig
import com.boost12.rd12.domain.model.ShellExecutionResult
import kotlinx.coroutines.flow.StateFlow

interface AdbRepository {
    val connectionState: StateFlow<AdbConnectionState>
    val discoveredPairingEndpoint: StateFlow<MdnsDiscoveredEndpoint?>
    val discoveredConnectEndpoint: StateFlow<MdnsDiscoveredEndpoint?>
    val telemetry: StateFlow<DeviceTelemetry>
    val deviceProfile: StateFlow<DeviceProfile>
    val savedConfig: StateFlow<SavedConnectionConfig>
    val debugLogs: StateFlow<List<AdbDebugLogEntry>>
    val debuggerModeEnabled: StateFlow<Boolean>

    fun startMdnsDiscovery()
    fun stopMdnsDiscovery()

    suspend fun pairDevice(hostPortInput: String, pairingCode: String): Result<Unit>
    suspend fun connect(hostPortInput: String? = null): Result<Unit>
    suspend fun quickReconnect(): Result<Unit>
    suspend fun disconnect(userInitiated: Boolean = true)

    suspend fun executeShell(
        command: String,
        timeoutMs: Long = 8_000L
    ): Result<ShellExecutionResult>

    suspend fun refreshTelemetry()

    fun setAutoReconnect(enabled: Boolean)
    fun setDebuggerMode(enabled: Boolean)
    fun clearDebugLogs()
    suspend fun resetPairingCredentials()
}
