package com.boost12.rd12.data.adb

import android.content.Context
import com.boost12.rd12.data.adb.crypto.AdbCredentialStore
import com.boost12.rd12.data.adb.mdns.AdbMdnsDiscovery
import com.boost12.rd12.data.adb.protocol.AdbConnectionSession
import com.boost12.rd12.data.adb.protocol.AdbPairingClient
import com.boost12.rd12.data.adb.protocol.AdbPairingException
import com.boost12.rd12.data.adb.protocol.AdbSessionException
import com.boost12.rd12.data.adb.telemetry.DumpsysTelemetryParser
import com.boost12.rd12.domain.model.AdbConnectionState
import com.boost12.rd12.domain.model.AdbDebugLogEntry
import com.boost12.rd12.domain.model.AdbErrorKind
import com.boost12.rd12.domain.model.DebugLogLevel
import com.boost12.rd12.domain.model.DeviceProfile
import com.boost12.rd12.domain.model.DeviceTelemetry
import com.boost12.rd12.domain.model.EndpointType
import com.boost12.rd12.domain.model.MdnsDiscoveredEndpoint
import com.boost12.rd12.domain.model.SavedConnectionConfig
import com.boost12.rd12.domain.model.ShellExecutionResult
import com.boost12.rd12.domain.repository.AdbRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

class AdbRepositoryImpl(
    context: Context,
    private val credentialStore: AdbCredentialStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AdbRepository {

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val sessionMutex = Mutex()
    private val nextLogId = AtomicLong(1L)

    private val _connectionState = MutableStateFlow<AdbConnectionState>(AdbConnectionState.Disconnected)
    override val connectionState: StateFlow<AdbConnectionState> = _connectionState.asStateFlow()

    private val _discoveredPairingEndpoint = MutableStateFlow<MdnsDiscoveredEndpoint?>(null)
    override val discoveredPairingEndpoint: StateFlow<MdnsDiscoveredEndpoint?> =
        _discoveredPairingEndpoint.asStateFlow()

    private val _discoveredConnectEndpoint = MutableStateFlow<MdnsDiscoveredEndpoint?>(null)
    override val discoveredConnectEndpoint: StateFlow<MdnsDiscoveredEndpoint?> =
        _discoveredConnectEndpoint.asStateFlow()

    private val _telemetry = MutableStateFlow(DeviceTelemetry())
    override val telemetry: StateFlow<DeviceTelemetry> = _telemetry.asStateFlow()

    private val _deviceProfile = MutableStateFlow(DeviceProfile())
    override val deviceProfile: StateFlow<DeviceProfile> = _deviceProfile.asStateFlow()

    private val _savedConfig = MutableStateFlow(credentialStore.getSavedConfig())
    override val savedConfig: StateFlow<SavedConnectionConfig> = _savedConfig.asStateFlow()

    private val _debugLogs = MutableStateFlow<List<AdbDebugLogEntry>>(emptyList())
    override val debugLogs: StateFlow<List<AdbDebugLogEntry>> = _debugLogs.asStateFlow()

    private val _debuggerModeEnabled = MutableStateFlow(credentialStore.isDebuggerModeEnabled())
    override val debuggerModeEnabled: StateFlow<Boolean> = _debuggerModeEnabled.asStateFlow()

    @Volatile
    private var activeSession: AdbConnectionSession? = null

    private var reconnectJob: Job? = null
    private var telemetryLoopJob: Job? = null

    private val mdnsDiscovery = AdbMdnsDiscovery(
        context = context,
        onEndpointDiscovered = { endpoint ->
            when (endpoint.type) {
                EndpointType.TLS_PAIRING -> _discoveredPairingEndpoint.value = endpoint
                EndpointType.TLS_CONNECT -> {
                    _discoveredConnectEndpoint.value = endpoint
                    credentialStore.saveConnectEndpoint(endpoint.host, endpoint.port)
                    refreshSavedConfig()
                    // Auto-connect if already paired and currently disconnected or waiting to reconnect
                    val cfg = _savedConfig.value
                    val state = _connectionState.value
                    if (cfg.isPaired && cfg.autoReconnect &&
                        (state is AdbConnectionState.Disconnected || state is AdbConnectionState.Error)
                    ) {
                        scope.launch {
                            connect("${endpoint.host}:${endpoint.port}")
                        }
                    }
                }
            }
        },
        onEndpointLost = { type ->
            when (type) {
                EndpointType.TLS_PAIRING -> _discoveredPairingEndpoint.value = null
                EndpointType.TLS_CONNECT -> _discoveredConnectEndpoint.value = null
            }
        },
        onTrace = { msg -> appendLog(DebugLogLevel.PROTOCOL, "mDNS", msg) }
    )

    override fun startMdnsDiscovery() {
        mdnsDiscovery.start()
    }

    override fun stopMdnsDiscovery() {
        mdnsDiscovery.stop()
    }

    override suspend fun pairDevice(hostPortInput: String, pairingCode: String): Result<Unit> =
        withContext(ioDispatcher) {
            val defaultHost = _discoveredPairingEndpoint.value?.host
                ?: AdbMdnsDiscovery.findDeviceIpv4Address()
                ?: AdbCredentialStore.DEFAULT_LOOPBACK_HOST

            val resolvedInput = hostPortInput.ifBlank {
                _discoveredPairingEndpoint.value?.addressLabel.orEmpty()
            }
            val (host, port) = DumpsysTelemetryParser.parseEndpointInput(resolvedInput, defaultHost)
                ?: return@withContext Result.failure<Unit>(
                    AdbPairingException(
                        AdbErrorKind.INVALID_ENDPOINT,
                        "Enter valid pairing IP:port (e.g. 192.168.1.20:39124 or :39124)."
                    )
                ).also {
                    _connectionState.value = AdbConnectionState.Error(
                        kind = AdbErrorKind.INVALID_ENDPOINT,
                        message = "Enter valid pairing IP:port (e.g. 192.168.1.20:39124)."
                    )
                }

            reconnectJob?.cancel()
            _connectionState.value = AdbConnectionState.Pairing(host, port)
            appendLog(DebugLogLevel.INFO, "Pairing", "Initiating SPAKE2+ pairing with $host:$port")

            return@withContext try {
                val keyMaterial = credentialStore.getOrCreateKeyMaterial()
                val pairingClient = AdbPairingClient(
                    onProtocolTrace = { trace -> appendLog(DebugLogLevel.PROTOCOL, "SPAKE2+", trace) }
                )
                pairingClient.pair(
                    host = host,
                    port = port,
                    sixDigitCode = pairingCode,
                    keyMaterial = keyMaterial
                )
                credentialStore.markPaired(host, port)
                refreshSavedConfig()
                appendLog(DebugLogLevel.INFO, "Pairing", "Paired successfully with $host:$port")

                // Immediately connect if mDNS already resolved the connect port or saved port exists
                val connectTarget = _discoveredConnectEndpoint.value?.addressLabel
                    ?: _savedConfig.value.takeIf { it.lastConnectPort > 0 }
                        ?.let { "${it.lastHost}:${it.lastConnectPort}" }

                if (connectTarget != null) {
                    connect(connectTarget)
                } else {
                    _connectionState.value = AdbConnectionState.Disconnected
                    Result.success(Unit)
                }
            } catch (e: AdbPairingException) {
                appendLog(DebugLogLevel.ERROR, "Pairing", e.message, e.cause?.message)
                _connectionState.value = AdbConnectionState.Error(
                    kind = e.kind,
                    message = e.message
                )
                Result.failure(e)
            } catch (t: Throwable) {
                val msg = "Pairing failed: ${t.message ?: "unexpected error"}"
                appendLog(DebugLogLevel.ERROR, "Pairing", msg)
                _connectionState.value = AdbConnectionState.Error(
                    kind = AdbErrorKind.UNKNOWN,
                    message = msg
                )
                Result.failure(t)
            }
        }

    override suspend fun connect(hostPortInput: String?): Result<Unit> =
        connectInternal(hostPortInput = hostPortInput, attemptNumber = 1)

    override suspend fun quickReconnect(): Result<Unit> {
        val discovered = _discoveredConnectEndpoint.value?.addressLabel
        if (discovered != null) {
            return connect(discovered)
        }
        val cfg = credentialStore.getSavedConfig()
        if (cfg.lastConnectPort > 0) {
            return connect("${cfg.lastHost}:${cfg.lastConnectPort}")
        }
        startMdnsDiscovery()
        val msg = "Waiting for Wireless Debugging port via mDNS, or enter the IP & Port shown on the Wireless Debugging screen."
        _connectionState.value = AdbConnectionState.Error(
            kind = AdbErrorKind.CONNECTION_REFUSED,
            message = msg
        )
        return Result.failure(IllegalStateException(msg))
    }

    private suspend fun connectInternal(
        hostPortInput: String?,
        attemptNumber: Int
    ): Result<Unit> = withContext(ioDispatcher) {
        val defaultHost = _discoveredConnectEndpoint.value?.host
            ?: AdbMdnsDiscovery.findDeviceIpv4Address()
            ?: credentialStore.getSavedConfig().lastHost.ifBlank { AdbCredentialStore.DEFAULT_LOOPBACK_HOST }

        val candidate = hostPortInput?.takeIf { it.isNotBlank() }
            ?: _discoveredConnectEndpoint.value?.addressLabel
            ?: credentialStore.getSavedConfig().takeIf { it.lastConnectPort > 0 }
                ?.let { "${it.lastHost}:${it.lastConnectPort}" }
            ?: return@withContext Result.failure<Unit>(
                AdbSessionException(
                    AdbErrorKind.INVALID_ENDPOINT,
                    "No connect port known yet. Open Wireless Debugging or enter IP:connectPort."
                )
            ).also {
                _connectionState.value = AdbConnectionState.Error(
                    kind = AdbErrorKind.INVALID_ENDPOINT,
                    message = "Enter the IP address & Port from Wireless Debugging (or wait for auto-discovery)."
                )
            }

        val (host, port) = DumpsysTelemetryParser.parseEndpointInput(candidate, defaultHost)
            ?: return@withContext Result.failure<Unit>(
                AdbSessionException(
                    AdbErrorKind.INVALID_ENDPOINT,
                    "Invalid connect endpoint '$candidate'. Expected IP:port."
                )
            ).also {
                _connectionState.value = AdbConnectionState.Error(
                    kind = AdbErrorKind.INVALID_ENDPOINT,
                    message = "Invalid IP:port '$candidate'. Example: 192.168.1.20:41555"
                )
            }

        sessionMutex.withLock {
            activeSession?.close()
            activeSession = null
            _connectionState.value = AdbConnectionState.Connecting(host, port, attemptNumber)

            return@withLock try {
                val keyMaterial = credentialStore.getOrCreateKeyMaterial()
                val session = AdbConnectionSession(
                    host = host,
                    port = port,
                    keyMaterial = keyMaterial,
                    onProtocolTrace = { trace -> appendLog(DebugLogLevel.PROTOCOL, "ADB", trace) },
                    onUnexpectedDisconnect = { cause -> handleUnexpectedDisconnect(cause) }
                )
                session.connect(timeoutMs = 10_000L)
                activeSession = session
                reconnectJob?.cancel()

                credentialStore.markPaired(host, credentialStore.getSavedConfig().lastPairingPort)
                credentialStore.saveConnectEndpoint(host, port)
                refreshSavedConfig()

                _connectionState.value = AdbConnectionState.Connected(
                    host = host,
                    port = port,
                    isTls = session.isTlsUpgraded
                )
                appendLog(
                    DebugLogLevel.INFO,
                    "ADB",
                    "Connected to $host:$port (TLSv1.3=${session.isTlsUpgraded})"
                )
                startTelemetryPolling()
                scope.launch { probeLiveDeviceProperties() }
                Result.success(Unit)
            } catch (e: AdbSessionException) {
                if (e.kind == AdbErrorKind.UNAUTHORIZED_REVOKED) {
                    credentialStore.markUnpaired()
                    refreshSavedConfig()
                }
                appendLog(DebugLogLevel.ERROR, "ADB", e.message)
                _connectionState.value = AdbConnectionState.Error(
                    kind = e.kind,
                    message = e.message,
                    attempt = attemptNumber
                )
                Result.failure(e)
            } catch (t: Throwable) {
                val msg = "Connection error: ${t.message ?: "unable to reach adbd"}"
                appendLog(DebugLogLevel.ERROR, "ADB", msg)
                _connectionState.value = AdbConnectionState.Error(
                    kind = AdbErrorKind.CONNECTION_REFUSED,
                    message = msg,
                    attempt = attemptNumber
                )
                Result.failure(t)
            }
        }
    }

    override suspend fun disconnect(userInitiated: Boolean) {
        reconnectJob?.cancel()
        telemetryLoopJob?.cancel()
        sessionMutex.withLock {
            activeSession?.close()
            activeSession = null
            if (userInitiated) {
                _connectionState.value = AdbConnectionState.Disconnected
                appendLog(DebugLogLevel.INFO, "ADB", "Disconnected by user")
            }
        }
    }

    private fun handleUnexpectedDisconnect(cause: Throwable) {
        telemetryLoopJob?.cancel()
        activeSession = null
        val kind = (cause as? AdbSessionException)?.kind ?: AdbErrorKind.SOCKET_DROPPED_DOZE
        appendLog(
            DebugLogLevel.WARN,
            "ADB",
            "Socket dropped (${cause.message ?: "sleep/Doze"}); autoReconnect=${_savedConfig.value.autoReconnect}"
        )

        if (kind == AdbErrorKind.UNAUTHORIZED_REVOKED) {
            credentialStore.markUnpaired()
            refreshSavedConfig()
            _connectionState.value = AdbConnectionState.Error(
                kind = AdbErrorKind.UNAUTHORIZED_REVOKED,
                message = "ADB authorization was revoked in Developer Options. Re-pair Wireless Debugging."
            )
            return
        }

        if (!_savedConfig.value.autoReconnect) {
            _connectionState.value = AdbConnectionState.Error(
                kind = kind,
                message = "Wireless Debugging disconnected (sleep/Doze). Tap Reconnect to resume."
            )
            return
        }

        scheduleExponentialBackoffReconnect()
    }

    private fun scheduleExponentialBackoffReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            startMdnsDiscovery()
            var attempt = 1
            while (isActive && attempt <= MAX_AUTO_RECONNECT_ATTEMPTS) {
                val backoffMs = computeBackoffDelayMs(attempt)
                _connectionState.value = AdbConnectionState.Error(
                    kind = AdbErrorKind.SOCKET_DROPPED_DOZE,
                    message = "Socket dropped (sleep/Doze). Auto-reconnecting (attempt $attempt/$MAX_AUTO_RECONNECT_ATTEMPTS)…",
                    attempt = attempt,
                    nextRetryInMs = backoffMs
                )
                delay(backoffMs)
                val target = _discoveredConnectEndpoint.value?.addressLabel
                    ?: credentialStore.getSavedConfig().takeIf { it.lastConnectPort > 0 }
                        ?.let { "${it.lastHost}:${it.lastConnectPort}" }
                if (target != null) {
                    val res = connectInternal(target, attemptNumber = attempt)
                    if (res.isSuccess) return@launch
                    val state = _connectionState.value
                    if (state is AdbConnectionState.Error &&
                        state.kind == AdbErrorKind.UNAUTHORIZED_REVOKED
                    ) {
                        return@launch
                    }
                }
                attempt++
            }
            _connectionState.value = AdbConnectionState.Error(
                kind = AdbErrorKind.SOCKET_DROPPED_DOZE,
                message = "Auto-reconnect paused after $MAX_AUTO_RECONNECT_ATTEMPTS attempts. Whitelist Boost12 from MIUI Battery Saver and tap Reconnect."
            )
        }
    }

    override suspend fun executeShell(
        command: String,
        timeoutMs: Long
    ): Result<ShellExecutionResult> = withContext(ioDispatcher) {
        val session = activeSession
        if (session == null || !session.isActive) {
            return@withContext Result.failure(
                AdbSessionException(
                    AdbErrorKind.SOCKET_DROPPED_DOZE,
                    "Not connected to Wireless Debugging."
                )
            )
        }

        return@withContext try {
            appendLog(DebugLogLevel.SHELL, "shell$", command)
            val result = session.executeShell(command, timeoutMs)
            appendLog(
                level = if (result.isSuccess) DebugLogLevel.SHELL else DebugLogLevel.WARN,
                tag = "shell→${result.exitCode}",
                message = "${result.durationMs}ms · ${command.take(72)}",
                details = result.combinedOutput.take(1_000)
            )
            Result.success(result)
        } catch (t: Throwable) {
            appendLog(DebugLogLevel.ERROR, "shell!", "Failed executing '$command': ${t.message}")
            Result.failure(t)
        }
    }

    override suspend fun refreshTelemetry() {
        val session = activeSession ?: return
        if (!session.isActive) return
        runCatching {
            val bat = session.executeShell("dumpsys battery", timeoutMs = 4_000L).stdout
            val therm = session.executeShell(
                "dumpsys thermalservice | grep -i 'Thermal Status'",
                timeoutMs = 3_000L
            ).stdout
            val disp = session.executeShell(
                "settings get secure user_refresh_rate; settings get system peak_refresh_rate",
                timeoutMs = 3_000L
            ).stdout
            val firstHzLine = disp.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.toFloatOrNull() != null }
                ?.let { "user_refresh_rate=$it" }
            _telemetry.value = DumpsysTelemetryParser.parse(
                batteryOutput = bat,
                thermalOutput = therm,
                displayOutput = firstHzLine
            )
        }
    }

    private fun startTelemetryPolling() {
        telemetryLoopJob?.cancel()
        telemetryLoopJob = scope.launch {
            while (isActive && activeSession?.isActive == true) {
                refreshTelemetry()
                delay(TELEMETRY_POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun probeLiveDeviceProperties() {
        val session = activeSession ?: return
        runCatching {
            val out = session.executeShell(
                "getprop ro.build.version.release; getprop ro.miui.ui.version.name; getprop ro.product.marketname; getprop ro.product.model",
                timeoutMs = 4_000L
            ).stdout
            val lines = out.lines().map { it.trim() }
            val androidRel = lines.getOrNull(0)?.takeIf { it.isNotBlank() }
            val miuiVer = lines.getOrNull(1)?.takeIf { it.isNotBlank() }
            val market = lines.getOrNull(2)?.takeIf { it.isNotBlank() }
                ?: lines.getOrNull(3)?.takeIf { it.isNotBlank() }
            _deviceProfile.update { prev ->
                prev.copy(
                    liveAndroidRelease = androidRel,
                    liveRomVersion = miuiVer,
                    liveHardwareModel = market
                )
            }
        }
    }

    override fun setAutoReconnect(enabled: Boolean) {
        credentialStore.setAutoReconnect(enabled)
        refreshSavedConfig()
        if (!enabled) {
            reconnectJob?.cancel()
        }
    }

    override fun setDebuggerMode(enabled: Boolean) {
        credentialStore.setDebuggerModeEnabled(enabled)
        _debuggerModeEnabled.value = enabled
        appendLog(
            DebugLogLevel.INFO,
            "Debugger",
            "ADB Verbose Debugger Mode ${if (enabled) "ENABLED" else "DISABLED"}"
        )
    }

    override fun clearDebugLogs() {
        _debugLogs.value = emptyList()
    }

    override suspend fun resetPairingCredentials() {
        disconnect(userInitiated = true)
        credentialStore.resetCredentials()
        refreshSavedConfig()
        appendLog(DebugLogLevel.INFO, "Security", "Cleared saved RSA-2048 pairing key & certificate")
    }

    private fun refreshSavedConfig() {
        _savedConfig.value = credentialStore.getSavedConfig()
    }

    private fun appendLog(
        level: DebugLogLevel,
        tag: String,
        message: String,
        details: String? = null
    ) {
        if (!_debuggerModeEnabled.value && level != DebugLogLevel.ERROR) return
        val entry = AdbDebugLogEntry(
            id = nextLogId.getAndIncrement(),
            timestampEpochMs = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
            details = details?.takeIf { it.isNotBlank() }
        )
        _debugLogs.update { existing ->
            (listOf(entry) + existing).take(MAX_DEBUG_LOG_ENTRIES)
        }
    }

    companion object {
        private const val TELEMETRY_POLL_INTERVAL_MS = 12_000L
        private const val MAX_AUTO_RECONNECT_ATTEMPTS = 6
        private const val MAX_DEBUG_LOG_ENTRIES = 250

        internal fun computeBackoffDelayMs(attempt: Int): Long {
            val shift = (attempt - 1).coerceIn(0, 5)
            return minOf(1_000L shl shift, 30_000L)
        }
    }
}
