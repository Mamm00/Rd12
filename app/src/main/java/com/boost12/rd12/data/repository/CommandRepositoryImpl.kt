package com.boost12.rd12.data.repository

import com.boost12.rd12.data.catalog.DefaultCommandCatalog
import com.boost12.rd12.data.local.dao.CommandHistoryDao
import com.boost12.rd12.data.local.dao.CommandStateDao
import com.boost12.rd12.data.local.dao.CustomCommandDao
import com.boost12.rd12.data.local.entity.CommandHistoryEntity
import com.boost12.rd12.data.local.entity.CommandStateEntity
import com.boost12.rd12.data.local.entity.CustomCommandEntity
import com.boost12.rd12.domain.engine.CommandEngine
import com.boost12.rd12.domain.model.AdbConnectionState
import com.boost12.rd12.domain.model.Command
import com.boost12.rd12.domain.model.CommandCardItem
import com.boost12.rd12.domain.model.CommandCategory
import com.boost12.rd12.domain.model.CommandHistoryRecord
import com.boost12.rd12.domain.model.CommandRuntimeState
import com.boost12.rd12.domain.model.HistoryActionType
import com.boost12.rd12.domain.model.RiskLevel
import com.boost12.rd12.domain.model.ShellExecutionResult
import com.boost12.rd12.domain.model.ToggleExecutionReport
import com.boost12.rd12.domain.model.VerificationOutcome
import com.boost12.rd12.domain.model.VerifyRule
import com.boost12.rd12.domain.repository.AdbRepository
import com.boost12.rd12.domain.repository.CommandRepository
import com.boost12.rd12.domain.safety.CommandSafetyGuard
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class CommandRepositoryImpl(
    private val adbRepository: AdbRepository,
    private val stateDao: CommandStateDao,
    private val historyDao: CommandHistoryDao,
    private val customCommandDao: CustomCommandDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : CommandRepository {

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val engine = CommandEngine(
        shellRunner = { cmd, timeout -> adbRepository.executeShell(cmd, timeout) }
    )

    private val applyingIds = MutableStateFlow<Set<String>>(emptySet())
    private val _isSyncingAll = MutableStateFlow(false)
    override val isSyncingAll: StateFlow<Boolean> = _isSyncingAll.asStateFlow()

    override val commandCards: Flow<List<CommandCardItem>> = combine(
        customCommandDao.observeAll(),
        stateDao.observeAll(),
        applyingIds
    ) { customEntities, stateEntities, activeIds ->
        val stateById = stateEntities.associateBy { it.commandId }
        val customCommands = customEntities.map { it.toDomainCommand() }
        val allCommands = DefaultCommandCatalog.builtInCommands + customCommands

        allCommands.map { command ->
            val persisted = stateById[command.id]
            val runtimeState = CommandRuntimeState(
                commandId = command.id,
                verifiedEnabled = if (command.requiresRoot) false else persisted?.verifiedEnabled,
                isApplying = command.id in activeIds,
                errorBanner = persisted?.lastErrorBanner,
                lastRawOutput = persisted?.lastRawOutput ?: command.unavailableReason,
                lastExitCode = persisted?.lastExitCode,
                lastUpdatedEpochMs = persisted?.updatedAtEpochMs,
                revertOnDisconnect = persisted?.revertOnDisconnect
                    ?: command.revertOnDisconnectDefault,
                targetPackageOverride = persisted?.targetPackageOverride
            )
            CommandCardItem(command = command, state = runtimeState)
        }
    }

    override val historyRecords: Flow<List<CommandHistoryRecord>> =
        historyDao.observeRecent().map { list ->
            list.map { entity ->
                CommandHistoryRecord(
                    id = entity.id,
                    commandId = entity.commandId,
                    commandName = entity.commandName,
                    category = runCatching { CommandCategory.valueOf(entity.categoryName) }
                        .getOrDefault(CommandCategory.ALL),
                    action = runCatching { HistoryActionType.valueOf(entity.actionName) }
                        .getOrDefault(HistoryActionType.TURN_ON),
                    executedShell = entity.executedShell,
                    verifyOutput = entity.verifyOutput,
                    succeeded = entity.succeeded,
                    timestampEpochMs = entity.timestampEpochMs
                )
            }
        }

    init {
        // Automatically sync all verify commands whenever ADB session connects
        scope.launch {
            adbRepository.connectionState
                .map { it is AdbConnectionState.Connected }
                .distinctUntilChanged()
                .collect { connected ->
                    if (connected) {
                        syncAllCommands()
                    }
                }
        }
    }

    override suspend fun syncAllCommands(): Int = withContext(ioDispatcher) {
        if (adbRepository.connectionState.value !is AdbConnectionState.Connected) {
            return@withContext 0
        }
        _isSyncingAll.value = true
        var syncedCount = 0
        try {
            val allCommands = getAllCommandsSnapshot()
            for (command in allCommands) {
                if (command.requiresRoot) continue
                val existing = stateDao.getById(command.id)
                val report = engine.verifyCurrentState(
                    command = command,
                    targetPackageOverride = existing?.targetPackageOverride
                )
                val now = System.currentTimeMillis()
                stateDao.upsert(
                    CommandStateEntity(
                        commandId = command.id,
                        verifiedEnabled = report.verifiedEnabled ?: existing?.verifiedEnabled,
                        revertOnDisconnect = existing?.revertOnDisconnect
                            ?: command.revertOnDisconnectDefault,
                        lastRawOutput = report.combinedShellOutput.ifBlank { existing?.lastRawOutput },
                        lastExitCode = 0,
                        lastErrorBanner = null,
                        targetPackageOverride = existing?.targetPackageOverride,
                        updatedAtEpochMs = now
                    )
                )
                if (report.verificationPassed) syncedCount++
            }
        } finally {
            _isSyncingAll.value = false
        }
        syncedCount
    }

    override suspend fun verifySingleCommand(commandId: String): ToggleExecutionReport? =
        withContext(ioDispatcher) {
            val command = findCommandById(commandId) ?: return@withContext null
            val existing = stateDao.getById(commandId)
            val report = engine.verifyCurrentState(command, existing?.targetPackageOverride)
            stateDao.upsert(
                CommandStateEntity(
                    commandId = command.id,
                    verifiedEnabled = report.verifiedEnabled ?: existing?.verifiedEnabled,
                    revertOnDisconnect = existing?.revertOnDisconnect
                        ?: command.revertOnDisconnectDefault,
                    lastRawOutput = report.combinedShellOutput,
                    lastExitCode = 0,
                    lastErrorBanner = report.errorMessage,
                    targetPackageOverride = existing?.targetPackageOverride,
                    updatedAtEpochMs = System.currentTimeMillis()
                )
            )
            report
        }

    override suspend fun setCommandEnabled(
        commandId: String,
        targetEnabled: Boolean
    ): ToggleExecutionReport = withContext(ioDispatcher) {
        val command = findCommandById(commandId) ?: return@withContext ToggleExecutionReport(
            commandId = commandId,
            requestedEnabled = targetEnabled,
            verifiedEnabled = null,
            verificationPassed = false,
            outcome = VerificationOutcome.INDETERMINATE,
            combinedShellOutput = "",
            verifyOutput = "",
            errorMessage = "Command not found"
        )

        val existing = stateDao.getById(commandId)
        applyingIds.update { it + commandId }

        try {
            val report = engine.applyToggle(
                command = command,
                targetEnabled = targetEnabled,
                previousVerifiedState = existing?.verifiedEnabled,
                targetPackageOverride = existing?.targetPackageOverride
            )
            val now = System.currentTimeMillis()
            stateDao.upsert(
                CommandStateEntity(
                    commandId = command.id,
                    verifiedEnabled = report.verifiedEnabled,
                    revertOnDisconnect = existing?.revertOnDisconnect
                        ?: command.revertOnDisconnectDefault,
                    lastRawOutput = report.combinedShellOutput,
                    lastExitCode = if (report.verificationPassed) 0 else 1,
                    lastErrorBanner = report.errorMessage,
                    targetPackageOverride = existing?.targetPackageOverride,
                    updatedAtEpochMs = now
                )
            )

            val shellExecuted = (if (targetEnabled) {
                command.resolveOnShell(existing?.targetPackageOverride)
            } else {
                command.resolveOffShell(existing?.targetPackageOverride)
            }).joinToString(" && ")

            historyDao.insert(
                CommandHistoryEntity(
                    commandId = command.id,
                    commandName = command.name,
                    categoryName = command.category.name,
                    actionName = if (targetEnabled) {
                        HistoryActionType.TURN_ON.name
                    } else {
                        HistoryActionType.TURN_OFF.name
                    },
                    executedShell = shellExecuted,
                    verifyOutput = report.verifyOutput.ifBlank {
                        report.errorMessage ?: "(no output)"
                    },
                    succeeded = report.verificationPassed,
                    timestampEpochMs = now
                )
            )

            report
        } finally {
            applyingIds.update { it - commandId }
        }
    }

    override suspend fun testRunVerify(commandId: String): Result<ShellExecutionResult> =
        withContext(ioDispatcher) {
            val command = findCommandById(commandId)
                ?: return@withContext Result.failure(IllegalArgumentException("Unknown command"))
            if (command.requiresRoot) {
                return@withContext Result.failure(
                    IllegalStateException(command.unavailableReason ?: "Requires root")
                )
            }
            val existing = stateDao.getById(commandId)
            val verifyCmd = command.resolveVerifyShell(existing?.targetPackageOverride)
            val result = engine.runSafeSingleShell(verifyCmd)
            result.onSuccess { out ->
                val report = engine.verifyCurrentState(command, existing?.targetPackageOverride)
                val now = System.currentTimeMillis()
                stateDao.upsert(
                    CommandStateEntity(
                        commandId = command.id,
                        verifiedEnabled = report.verifiedEnabled ?: existing?.verifiedEnabled,
                        revertOnDisconnect = existing?.revertOnDisconnect
                            ?: command.revertOnDisconnectDefault,
                        lastRawOutput = "# Test verify: $verifyCmd\n${out.combinedOutput}",
                        lastExitCode = out.exitCode,
                        lastErrorBanner = null,
                        targetPackageOverride = existing?.targetPackageOverride,
                        updatedAtEpochMs = now
                    )
                )
                historyDao.insert(
                    CommandHistoryEntity(
                        commandId = command.id,
                        commandName = command.name,
                        categoryName = command.category.name,
                        actionName = HistoryActionType.TEST_RUN.name,
                        executedShell = verifyCmd,
                        verifyOutput = out.combinedOutput,
                        succeeded = out.isSuccess,
                        timestampEpochMs = now
                    )
                )
            }
            result
        }

    override suspend fun revertCommand(commandId: String): ToggleExecutionReport {
        return setCommandEnabled(commandId, targetEnabled = false)
    }

    override suspend fun revertAllActiveCommands(): Int = withContext(ioDispatcher) {
        val states = stateDao.getAllSnapshot().filter { it.verifiedEnabled == true }
        var revertedCount = 0
        for (state in states) {
            val command = findCommandById(state.commandId) ?: continue
            if (command.requiresRoot) continue
            val report = setCommandEnabled(command.id, targetEnabled = false)
            if (report.verificationPassed) {
                revertedCount++
            }
        }
        revertedCount
    }

    suspend fun revertFlaggedBeforeDisconnect(): Int = withContext(ioDispatcher) {
        if (adbRepository.connectionState.value !is AdbConnectionState.Connected) return@withContext 0
        val allCommands = getAllCommandsSnapshot().associateBy { it.id }
        val statesById = stateDao.getAllSnapshot().associateBy { it.commandId }

        var count = 0
        for ((id, cmd) in allCommands) {
            if (cmd.requiresRoot) continue
            val st = statesById[id]
            val shouldRevert = st?.revertOnDisconnect ?: cmd.revertOnDisconnectDefault
            if (shouldRevert && st?.verifiedEnabled == true) {
                val report = setCommandEnabled(id, targetEnabled = false)
                if (report.verificationPassed) count++
            }
        }
        count
    }

    override suspend fun setRevertOnDisconnect(commandId: String, enabled: Boolean) =
        withContext(ioDispatcher) {
            val existing = stateDao.getById(commandId)
            val now = System.currentTimeMillis()
            stateDao.upsert(
                CommandStateEntity(
                    commandId = commandId,
                    verifiedEnabled = existing?.verifiedEnabled,
                    revertOnDisconnect = enabled,
                    lastRawOutput = existing?.lastRawOutput,
                    lastExitCode = existing?.lastExitCode,
                    lastErrorBanner = existing?.lastErrorBanner,
                    targetPackageOverride = existing?.targetPackageOverride,
                    updatedAtEpochMs = existing?.updatedAtEpochMs ?: now
                )
            )
        }

    override suspend fun setTargetPackageOverride(commandId: String, packageName: String) =
        withContext(ioDispatcher) {
            val sanitized = Command.sanitizePackageName(packageName) ?: return@withContext
            val existing = stateDao.getById(commandId)
            val now = System.currentTimeMillis()
            stateDao.upsert(
                CommandStateEntity(
                    commandId = commandId,
                    verifiedEnabled = existing?.verifiedEnabled,
                    revertOnDisconnect = existing?.revertOnDisconnect ?: false,
                    lastRawOutput = existing?.lastRawOutput,
                    lastExitCode = existing?.lastExitCode,
                    lastErrorBanner = null,
                    targetPackageOverride = sanitized,
                    updatedAtEpochMs = now
                )
            )
            if (adbRepository.connectionState.value is AdbConnectionState.Connected) {
                verifySingleCommand(commandId)
            }
        }

    override suspend fun runAdHocCustomShell(shellCommand: String): Result<ShellExecutionResult> =
        withContext(ioDispatcher) {
            val res = engine.runSafeSingleShell(shellCommand)
            val now = System.currentTimeMillis()
            res.onSuccess { out ->
                historyDao.insert(
                    CommandHistoryEntity(
                        commandId = "custom_adhoc",
                        commandName = "Custom Shell Run",
                        categoryName = CommandCategory.CUSTOM.name,
                        actionName = HistoryActionType.CUSTOM_RUN.name,
                        executedShell = shellCommand,
                        verifyOutput = out.combinedOutput.ifBlank { "(exit ${out.exitCode})" },
                        succeeded = out.isSuccess,
                        timestampEpochMs = now
                    )
                )
            }.onFailure { err ->
                historyDao.insert(
                    CommandHistoryEntity(
                        commandId = "custom_adhoc",
                        commandName = "Custom Shell Run (Blocked/Failed)",
                        categoryName = CommandCategory.CUSTOM.name,
                        actionName = HistoryActionType.CUSTOM_RUN.name,
                        executedShell = shellCommand,
                        verifyOutput = err.message ?: "Failed",
                        succeeded = false,
                        timestampEpochMs = now
                    )
                )
            }
            res
        }

    override suspend fun saveCustomCommand(
        name: String,
        summary: String,
        onShellCommands: List<String>,
        offShellCommands: List<String>,
        verifyShell: String,
        expectedOnContains: String,
        riskLevel: RiskLevel,
        revertOnDisconnect: Boolean
    ): Result<Command> = withContext(ioDispatcher) {
        val cleanName = name.trim()
        val cleanOn = onShellCommands.map { it.trim() }.filter { it.isNotEmpty() }
        val cleanOff = offShellCommands.map { it.trim() }.filter { it.isNotEmpty() }
        val cleanVerify = verifyShell.trim()
        val cleanExpected = expectedOnContains.trim()

        if (cleanName.isEmpty() || cleanOn.isEmpty() || cleanOff.isEmpty() ||
            cleanVerify.isEmpty() || cleanExpected.isEmpty()
        ) {
            return@withContext Result.failure(
                IllegalArgumentException("Provide name, ON command, OFF command, Verify command, and expected ON string.")
            )
        }

        val safety = CommandSafetyGuard.inspectAll(cleanOn + cleanOff + cleanVerify)
        if (safety is CommandSafetyGuard.CheckResult.Blocked) {
            return@withContext Result.failure(IllegalArgumentException(safety.reason))
        }

        val id = "custom_${UUID.randomUUID().toString().take(8)}"
        val entity = CustomCommandEntity(
            id = id,
            name = cleanName,
            summary = summary.trim().ifEmpty { "Custom shell toggle (${cleanOn.first()})" },
            onShellJoined = cleanOn.joinToString("\n"),
            offShellJoined = cleanOff.joinToString("\n"),
            verifyShell = cleanVerify,
            expectedOnContains = cleanExpected,
            riskLevelName = riskLevel.name,
            revertOnDisconnect = revertOnDisconnect,
            createdAtEpochMs = System.currentTimeMillis()
        )
        customCommandDao.upsert(entity)
        Result.success(entity.toDomainCommand())
    }

    override suspend fun deleteCustomCommand(commandId: String) = withContext(ioDispatcher) {
        customCommandDao.deleteById(commandId)
        stateDao.deleteById(commandId)
    }

    override suspend fun clearHistory() = withContext(ioDispatcher) {
        historyDao.clearAll()
    }

    private suspend fun getAllCommandsSnapshot(): List<Command> {
        val custom = customCommandDao.getAllSnapshot().map { it.toDomainCommand() }
        return DefaultCommandCatalog.builtInCommands + custom
    }

    private suspend fun findCommandById(commandId: String): Command? {
        return DefaultCommandCatalog.builtInCommands.firstOrNull { it.id == commandId }
            ?: customCommandDao.getAllSnapshot()
                .firstOrNull { it.id == commandId }
                ?.toDomainCommand()
    }

    private fun CustomCommandEntity.toDomainCommand(): Command {
        val onList = onShellJoined.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val offList = offShellJoined.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val risk = runCatching { RiskLevel.valueOf(riskLevelName) }.getOrDefault(RiskLevel.MODERATE)
        return Command(
            id = id,
            name = name,
            summary = summary,
            description = "$summary\n\nCustom user-defined ADB shell pair verified by checking output for '$expectedOnContains'.",
            category = CommandCategory.CUSTOM,
            onShell = onList,
            offShell = offList,
            verifyShell = verifyShell,
            verifyParser = VerifyRule.ContainsSubstring(
                expectedOn = expectedOnContains,
                humanSummary = "Verify output contains '$expectedOnContains'"
            ),
            riskLevel = risk,
            revertOnDisconnectDefault = revertOnDisconnect,
            isCustom = true
        )
    }
}
