package com.boost12.rd12.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "command_states")
data class CommandStateEntity(
    @PrimaryKey val commandId: String,
    val verifiedEnabled: Boolean?,
    val revertOnDisconnect: Boolean,
    val lastRawOutput: String?,
    val lastExitCode: Int?,
    val lastErrorBanner: String?,
    val targetPackageOverride: String?,
    val updatedAtEpochMs: Long
)

@Entity(tableName = "command_history")
data class CommandHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val commandId: String,
    val commandName: String,
    val categoryName: String,
    val actionName: String,
    val executedShell: String,
    val verifyOutput: String,
    val succeeded: Boolean,
    val timestampEpochMs: Long
)

@Entity(tableName = "custom_commands")
data class CustomCommandEntity(
    @PrimaryKey val id: String,
    val name: String,
    val summary: String,
    val onShellJoined: String,
    val offShellJoined: String,
    val verifyShell: String,
    val expectedOnContains: String,
    val riskLevelName: String,
    val revertOnDisconnect: Boolean,
    val createdAtEpochMs: Long
)
