package com.boost12.rd12.domain.repository

import com.boost12.rd12.domain.model.Command
import com.boost12.rd12.domain.model.CommandCardItem
import com.boost12.rd12.domain.model.CommandHistoryRecord
import com.boost12.rd12.domain.model.RiskLevel
import com.boost12.rd12.domain.model.ShellExecutionResult
import com.boost12.rd12.domain.model.ToggleExecutionReport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface CommandRepository {
    val commandCards: Flow<List<CommandCardItem>>
    val historyRecords: Flow<List<CommandHistoryRecord>>
    val isSyncingAll: StateFlow<Boolean>

    suspend fun syncAllCommands(): Int
    suspend fun verifySingleCommand(commandId: String): ToggleExecutionReport?
    suspend fun setCommandEnabled(commandId: String, targetEnabled: Boolean): ToggleExecutionReport
    suspend fun testRunVerify(commandId: String): Result<ShellExecutionResult>
    suspend fun revertCommand(commandId: String): ToggleExecutionReport
    suspend fun revertAllActiveCommands(): Int

    suspend fun setRevertOnDisconnect(commandId: String, enabled: Boolean)
    suspend fun setTargetPackageOverride(commandId: String, packageName: String)

    suspend fun runAdHocCustomShell(shellCommand: String): Result<ShellExecutionResult>

    suspend fun saveCustomCommand(
        name: String,
        summary: String,
        onShellCommands: List<String>,
        offShellCommands: List<String>,
        verifyShell: String,
        expectedOnContains: String,
        riskLevel: RiskLevel,
        revertOnDisconnect: Boolean
    ): Result<Command>

    suspend fun deleteCustomCommand(commandId: String)
    suspend fun clearHistory()
}
