package com.boost12.rd12.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boost12.rd12.data.adb.crypto.AdbCredentialStore
import com.boost12.rd12.data.adb.crypto.AppThemeMode
import com.boost12.rd12.domain.model.AdbConnectionState
import com.boost12.rd12.domain.model.AdbDebugLogEntry
import com.boost12.rd12.domain.model.CommandCardItem
import com.boost12.rd12.domain.model.CommandCategory
import com.boost12.rd12.domain.model.CommandHistoryRecord
import com.boost12.rd12.domain.model.DeviceProfile
import com.boost12.rd12.domain.model.DeviceTelemetry
import com.boost12.rd12.domain.model.MdnsDiscoveredEndpoint
import com.boost12.rd12.domain.model.RiskLevel
import com.boost12.rd12.domain.model.SavedConnectionConfig
import com.boost12.rd12.domain.repository.AdbRepository
import com.boost12.rd12.domain.repository.CommandRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CustomRunnerUiState(
    val adHocCommand: String = "",
    val isRunningAdHoc: Boolean = false,
    val lastAdHocOutput: String? = null,
    val lastAdHocError: String? = null,
    val lastDurationMs: Long? = null
)

data class MainUiState(
    val connectionState: AdbConnectionState = AdbConnectionState.Disconnected,
    val telemetry: DeviceTelemetry = DeviceTelemetry(),
    val deviceProfile: DeviceProfile = DeviceProfile(),
    val savedConfig: SavedConnectionConfig = SavedConnectionConfig(
        isPaired = false,
        lastHost = "127.0.0.1",
        lastConnectPort = 0,
        lastPairingPort = 0,
        autoReconnect = true,
        certificateFingerprint = null
    ),
    val discoveredPairing: MdnsDiscoveredEndpoint? = null,
    val discoveredConnect: MdnsDiscoveredEndpoint? = null,
    val searchQuery: String = "",
    val selectedCategory: CommandCategory = CommandCategory.ALL,
    val allCards: List<CommandCardItem> = emptyList(),
    val filteredCards: List<CommandCardItem> = emptyList(),
    val selectedDetailCard: CommandCardItem? = null,
    val showWirelessDebugSheet: Boolean = false,
    val isSyncingAll: Boolean = false,
    val isRevertingAll: Boolean = false,
    val batteryGuideDismissed: Boolean = false,
    val customRunner: CustomRunnerUiState = CustomRunnerUiState(),
    val transientMessage: String? = null
) {
    val activeBoostCount: Int
        get() = allCards.count { !it.command.requiresRoot && it.state.verifiedEnabled == true }
}

@HiltViewModel
class Boost12ViewModel @Inject constructor(
    private val adbRepository: AdbRepository,
    private val commandRepository: CommandRepository,
    private val credentialStore: AdbCredentialStore
) : ViewModel() {

    private val searchQuery = MutableStateFlow("")
    private val selectedCategory = MutableStateFlow(CommandCategory.ALL)
    private val selectedDetailCommandId = MutableStateFlow<String?>(null)
    private val showWirelessSheet = MutableStateFlow(false)
    private val isRevertingAll = MutableStateFlow(false)
    private val batteryGuideDismissed = MutableStateFlow(credentialStore.isBatteryGuideDismissed())
    private val customRunnerState = MutableStateFlow(CustomRunnerUiState())
    private val transientMessage = MutableStateFlow<String?>(null)

    private val _themeMode = MutableStateFlow(credentialStore.getThemeMode())
    val themeMode: StateFlow<AppThemeMode> = _themeMode.asStateFlow()

    private val _dynamicColor = MutableStateFlow(credentialStore.isDynamicColorEnabled())
    val dynamicColor: StateFlow<Boolean> = _dynamicColor.asStateFlow()

    val debuggerModeEnabled: StateFlow<Boolean> = adbRepository.debuggerModeEnabled
    val debugLogs: StateFlow<List<AdbDebugLogEntry>> = adbRepository.debugLogs

    val historyRecords: StateFlow<List<CommandHistoryRecord>> =
        commandRepository.historyRecords.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList()
        )

    private data class AdbSnapshot(
        val connection: AdbConnectionState,
        val telemetry: DeviceTelemetry,
        val profile: DeviceProfile,
        val config: SavedConnectionConfig,
        val pairingMdns: MdnsDiscoveredEndpoint?,
        val connectMdns: MdnsDiscoveredEndpoint?
    )

    private val adbSnapshotFlow = combine(
        combine(
            adbRepository.connectionState,
            adbRepository.telemetry,
            adbRepository.deviceProfile
        ) { conn, tel, prof -> Triple(conn, tel, prof) },
        combine(
            adbRepository.savedConfig,
            adbRepository.discoveredPairingEndpoint,
            adbRepository.discoveredConnectEndpoint
        ) { cfg, pairEp, connEp -> Triple(cfg, pairEp, connEp) }
    ) { (conn, tel, prof), (cfg, pairEp, connEp) ->
        AdbSnapshot(conn, tel, prof, cfg, pairEp, connEp)
    }

    private data class LocalControlsSnapshot(
        val query: String,
        val category: CommandCategory,
        val detailId: String?,
        val showSheet: Boolean,
        val revertingAll: Boolean,
        val guideDismissed: Boolean,
        val runner: CustomRunnerUiState,
        val toast: String?
    )

    private val localControlsFlow = combine(
        combine(searchQuery, selectedCategory, selectedDetailCommandId, showWirelessSheet) { q, cat, det, sheet ->
            listOf(q, cat, det, sheet)
        },
        combine(isRevertingAll, batteryGuideDismissed, customRunnerState, transientMessage) { rev, guide, run, msg ->
            listOf(rev, guide, run, msg)
        }
    ) { a, b ->
        LocalControlsSnapshot(
            query = a[0] as String,
            category = a[1] as CommandCategory,
            detailId = a[2] as String?,
            showSheet = a[3] as Boolean,
            revertingAll = b[0] as Boolean,
            guideDismissed = b[1] as Boolean,
            runner = b[2] as CustomRunnerUiState,
            toast = b[3] as String?
        )
    }

    val uiState: StateFlow<MainUiState> = combine(
        adbSnapshotFlow,
        commandRepository.commandCards,
        commandRepository.isSyncingAll,
        localControlsFlow
    ) { adb, cards, syncing, local ->
        val filtered = cards.filter { item ->
            val categoryMatch = when (local.category) {
                CommandCategory.ALL -> true
                else -> item.command.category == local.category
            }
            val q = local.query.trim()
            val queryMatch = q.isEmpty() ||
                item.command.name.contains(q, ignoreCase = true) ||
                item.command.summary.contains(q, ignoreCase = true) ||
                item.command.description.contains(q, ignoreCase = true) ||
                item.command.onShell.any { it.contains(q, ignoreCase = true) }
            categoryMatch && queryMatch
        }

        MainUiState(
            connectionState = adb.connection,
            telemetry = adb.telemetry,
            deviceProfile = adb.profile,
            savedConfig = adb.config,
            discoveredPairing = adb.pairingMdns,
            discoveredConnect = adb.connectMdns,
            searchQuery = local.query,
            selectedCategory = local.category,
            allCards = cards,
            filteredCards = filtered,
            selectedDetailCard = local.detailId?.let { id -> cards.firstOrNull { it.command.id == id } },
            showWirelessDebugSheet = local.showSheet,
            isSyncingAll = syncing,
            isRevertingAll = local.revertingAll,
            batteryGuideDismissed = local.guideDismissed,
            customRunner = local.runner,
            transientMessage = local.toast
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = MainUiState()
    )

    init {
        adbRepository.startMdnsDiscovery()
        val cfg = adbRepository.savedConfig.value
        if (cfg.isPaired && cfg.autoReconnect && cfg.lastConnectPort > 0) {
            viewModelScope.launch {
                adbRepository.quickReconnect()
            }
        }
    }

    fun onSearchQueryChanged(query: String) {
        searchQuery.value = query
    }

    fun onCategorySelected(category: CommandCategory) {
        selectedCategory.value = category
    }

    fun openCommandDetail(commandId: String) {
        selectedDetailCommandId.value = commandId
    }

    fun closeCommandDetail() {
        selectedDetailCommandId.value = null
    }

    fun openWirelessDebuggingSheet() {
        adbRepository.startMdnsDiscovery()
        showWirelessSheet.value = true
    }

    fun closeWirelessDebuggingSheet() {
        showWirelessSheet.value = false
    }

    fun dismissBatteryGuide() {
        credentialStore.setBatteryGuideDismissed(true)
        batteryGuideDismissed.value = true
    }

    fun consumeTransientMessage() {
        transientMessage.value = null
    }

    fun onToggleCommand(item: CommandCardItem, requestedState: Boolean) {
        if (item.command.requiresRoot) {
            transientMessage.value = item.command.unavailableReason
                ?: "Unavailable on this device without root."
            return
        }
        if (adbRepository.connectionState.value !is AdbConnectionState.Connected) {
            transientMessage.value = "Connect Wireless Debugging first to apply & verify shell commands."
            showWirelessSheet.value = true
            return
        }
        viewModelScope.launch {
            val report = commandRepository.setCommandEnabled(item.command.id, requestedState)
            if (!report.verificationPassed) {
                transientMessage.value = report.errorMessage
                    ?: "Command didn't apply — tap card to view raw shell output."
            }
        }
    }

    fun onTestRunCommand(commandId: String) {
        if (adbRepository.connectionState.value !is AdbConnectionState.Connected) {
            transientMessage.value = "Connect Wireless Debugging first to run verification."
            return
        }
        viewModelScope.launch {
            val res = commandRepository.testRunVerify(commandId)
            res.onSuccess {
                transientMessage.value = "Verify ran in ${it.durationMs}ms (exit ${it.exitCode})."
            }.onFailure {
                transientMessage.value = "Verify error: ${it.message}"
            }
        }
    }

    fun onRevertSingleCommand(commandId: String) {
        if (adbRepository.connectionState.value !is AdbConnectionState.Connected) {
            transientMessage.value = "Connect Wireless Debugging first to revert."
            return
        }
        viewModelScope.launch {
            val report = commandRepository.revertCommand(commandId)
            transientMessage.value = if (report.verificationPassed) {
                "Reverted and verified OFF."
            } else {
                report.errorMessage ?: "Revert verification did not pass."
            }
        }
    }

    fun onRevertAllCommands() {
        if (adbRepository.connectionState.value !is AdbConnectionState.Connected) {
            transientMessage.value = "Connect Wireless Debugging first to revert active boosts."
            return
        }
        viewModelScope.launch {
            isRevertingAll.value = true
            try {
                val count = commandRepository.revertAllActiveCommands()
                transientMessage.value = if (count > 0) {
                    "Reverted $count active boost(s) to stock defaults."
                } else {
                    "All commands are already at stock defaults."
                }
            } finally {
                isRevertingAll.value = false
            }
        }
    }

    fun onSyncAllCommands() {
        if (adbRepository.connectionState.value !is AdbConnectionState.Connected) {
            transientMessage.value = "Connect Wireless Debugging first to sync device state."
            showWirelessSheet.value = true
            return
        }
        viewModelScope.launch {
            val synced = commandRepository.syncAllCommands()
            adbRepository.refreshTelemetry()
            transientMessage.value = "Synced $synced command states from Redmi 12 4G."
        }
    }

    fun onSetRevertOnDisconnect(commandId: String, enabled: Boolean) {
        viewModelScope.launch {
            commandRepository.setRevertOnDisconnect(commandId, enabled)
        }
    }

    fun onUpdateTargetPackage(commandId: String, packageName: String) {
        val clean = com.boost12.rd12.domain.model.Command.sanitizePackageName(packageName)
        if (clean == null) {
            transientMessage.value = "Enter a valid Android package name (e.g. com.miui.analytics)."
            return
        }
        viewModelScope.launch {
            commandRepository.setTargetPackageOverride(commandId, clean)
            transientMessage.value = "Updated target package to $clean."
        }
    }

    fun onPairDevice(hostPort: String, sixDigitCode: String) {
        viewModelScope.launch {
            val res = adbRepository.pairDevice(hostPort, sixDigitCode)
            res.onSuccess {
                transientMessage.value = if (adbRepository.connectionState.value is AdbConnectionState.Connected) {
                    "Paired & connected over TLSv1.3!"
                } else {
                    "Paired! Now tap Connect with your Wireless Debugging connect port."
                }
                if (adbRepository.connectionState.value is AdbConnectionState.Connected) {
                    showWirelessSheet.value = false
                }
            }
        }
    }

    fun onConnectDevice(hostPort: String?) {
        viewModelScope.launch {
            val res = if (hostPort.isNullOrBlank()) {
                adbRepository.quickReconnect()
            } else {
                adbRepository.connect(hostPort)
            }
            res.onSuccess {
                showWirelessSheet.value = false
                transientMessage.value = "Connected to Wireless Debugging!"
            }
        }
    }

    fun onDisconnectGracefully() {
        viewModelScope.launch {
            val reverted = commandRepository.revertFlaggedBeforeDisconnect()
            adbRepository.disconnect(userInitiated = true)
            transientMessage.value = if (reverted > 0) {
                "Auto-reverted $reverted boost(s) and disconnected."
            } else {
                "Disconnected from Wireless Debugging."
            }
        }
    }

    fun onResetPairingCredentials() {
        viewModelScope.launch {
            adbRepository.resetPairingCredentials()
            transientMessage.value = "Cleared saved pairing keys. Pair again in Wireless Debugging."
        }
    }

    fun onSetAutoReconnect(enabled: Boolean) {
        adbRepository.setAutoReconnect(enabled)
    }

    fun onSetThemeMode(mode: AppThemeMode) {
        credentialStore.setThemeMode(mode)
        _themeMode.value = mode
    }

    fun onSetDynamicColor(enabled: Boolean) {
        credentialStore.setDynamicColorEnabled(enabled)
        _dynamicColor.value = enabled
    }

    fun onSetDebuggerMode(enabled: Boolean) {
        adbRepository.setDebuggerMode(enabled)
    }

    fun onClearDebugLogs() {
        adbRepository.clearDebugLogs()
    }

    fun onClearHistory() {
        viewModelScope.launch {
            commandRepository.clearHistory()
            transientMessage.value = "Cleared command change history."
        }
    }

    fun onAdHocCommandChanged(input: String) {
        customRunnerState.update { it.copy(adHocCommand = input, lastAdHocError = null) }
    }

    fun onRunAdHocCommand() {
        val cmd = customRunnerState.value.adHocCommand.trim()
        if (cmd.isEmpty()) return
        if (adbRepository.connectionState.value !is AdbConnectionState.Connected) {
            customRunnerState.update {
                it.copy(lastAdHocError = "Connect Wireless Debugging first to execute shell commands.")
            }
            return
        }
        viewModelScope.launch {
            customRunnerState.update {
                it.copy(isRunningAdHoc = true, lastAdHocError = null)
            }
            val result = commandRepository.runAdHocCustomShell(cmd)
            result.onSuccess { out ->
                customRunnerState.update {
                    it.copy(
                        isRunningAdHoc = false,
                        lastAdHocOutput = out.combinedOutput.ifBlank { "(exit ${out.exitCode}, empty stdout)" },
                        lastAdHocError = if (out.isSuccess) null else "Exited with code ${out.exitCode}",
                        lastDurationMs = out.durationMs
                    )
                }
            }.onFailure { err ->
                customRunnerState.update {
                    it.copy(
                        isRunningAdHoc = false,
                        lastAdHocError = err.message ?: "Execution blocked or failed"
                    )
                }
            }
        }
    }

    fun onSaveCustomToggle(
        name: String,
        summary: String,
        onCmd: String,
        offCmd: String,
        verifyCmd: String,
        expectedOnSubstring: String,
        riskLevel: RiskLevel,
        revertOnDisconnect: Boolean,
        onSaved: () -> Unit
    ) {
        viewModelScope.launch {
            val res = commandRepository.saveCustomCommand(
                name = name,
                summary = summary,
                onShellCommands = onCmd.lines(),
                offShellCommands = offCmd.lines(),
                verifyShell = verifyCmd,
                expectedOnContains = expectedOnSubstring,
                riskLevel = riskLevel,
                revertOnDisconnect = revertOnDisconnect
            )
            res.onSuccess { created ->
                transientMessage.value = "Saved custom toggle '${created.name}'."
                onSaved()
                if (adbRepository.connectionState.value is AdbConnectionState.Connected) {
                    commandRepository.verifySingleCommand(created.id)
                }
            }.onFailure { err ->
                transientMessage.value = err.message ?: "Could not save custom command."
            }
        }
    }

    fun onDeleteCustomCommand(commandId: String) {
        viewModelScope.launch {
            commandRepository.deleteCustomCommand(commandId)
            selectedDetailCommandId.value = null
            transientMessage.value = "Deleted custom command."
        }
    }

    override fun onCleared() {
        adbRepository.stopMdnsDiscovery()
        super.onCleared()
    }
}
