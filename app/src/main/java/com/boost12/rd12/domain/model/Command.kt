package com.boost12.rd12.domain.model

import kotlinx.serialization.Serializable

enum class CommandCategory(val displayName: String) {
    ALL("All"),
    CPU("CPU"),
    GPU("GPU"),
    RAM("RAM"),
    BACKGROUND("Background"),
    DISPLAY("Display"),
    NETWORK("Network"),
    CUSTOM("Custom")
}

enum class RiskLevel(val displayName: String) {
    SAFE("Safe"),
    MODERATE("Moderate"),
    ADVANCED("Advanced")
}

@Serializable
sealed interface VerifyRule {
    val humanSummary: String

    @Serializable
    data class RegexMatch(
        val onPattern: String,
        val offPattern: String? = null,
        val ignoreCase: Boolean = true,
        override val humanSummary: String = "Matches regex '$onPattern' when ON"
    ) : VerifyRule

    @Serializable
    data class AllScalesEqual(
        val targetScaleOn: Float,
        val tolerance: Float = 0.05f,
        val expectedCount: Int = 3,
        override val humanSummary: String = "All 3 animation scales equal $targetScaleOn"
    ) : VerifyRule

    @Serializable
    data class RefreshRateHzRule(
        val targetOnHz: Int = 90,
        override val humanSummary: String = "Active MIUI/AOSP refresh rate equals ${targetOnHz}Hz"
    ) : VerifyRule

    @Serializable
    data class ContainsSubstring(
        val expectedOn: String,
        val expectedOff: String? = null,
        val ignoreCase: Boolean = true,
        override val humanSummary: String = "Output contains '$expectedOn' when ON"
    ) : VerifyRule

    @Serializable
    data class RootUnavailable(
        override val humanSummary: String = "Requires root access (UID 0); disabled without root"
    ) : VerifyRule
}

enum class VerificationOutcome {
    VERIFIED_ON,
    VERIFIED_OFF,
    UNSUPPORTED_OR_BLOCKED,
    INDETERMINATE
}

data class Command(
    val id: String,
    val name: String,
    val summary: String,
    val description: String,
    val category: CommandCategory,
    val onShell: List<String>,
    val offShell: List<String>,
    val verifyShell: String,
    val verifyParser: VerifyRule,
    val riskLevel: RiskLevel,
    val revertOnDisconnectDefault: Boolean = false,
    val revertIfVerifyFails: Boolean = false,
    val requiresRoot: Boolean = false,
    val unavailableReason: String? = null,
    val miuiQuirkNote: String? = null,
    val supportsTargetPackage: Boolean = false,
    val defaultTargetPackage: String? = null,
    val isCustom: Boolean = false
) {
    fun resolveOnShell(targetPackageOverride: String?): List<String> =
        substitutePackage(onShell, targetPackageOverride)

    fun resolveOffShell(targetPackageOverride: String?): List<String> =
        substitutePackage(offShell, targetPackageOverride)

    fun resolveVerifyShell(targetPackageOverride: String?): String =
        substitutePackage(listOf(verifyShell), targetPackageOverride).first()

    private fun substitutePackage(commands: List<String>, overridePkg: String?): List<String> {
        if (!supportsTargetPackage) return commands
        val pkg = sanitizePackageName(overridePkg)
            ?: sanitizePackageName(defaultTargetPackage)
            ?: "com.miui.analytics"
        return commands.map { it.replace("<pkg>", pkg).replace("<package>", pkg) }
    }

    companion object {
        private val PACKAGE_NAME_REGEX = Regex("""^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z0-9_]+)+$""")

        fun sanitizePackageName(candidate: String?): String? {
            val trimmed = candidate?.trim().orEmpty()
            return trimmed.takeIf { PACKAGE_NAME_REGEX.matches(it) }
        }
    }
}

data class CommandRuntimeState(
    val commandId: String,
    val verifiedEnabled: Boolean? = null,
    val isApplying: Boolean = false,
    val errorBanner: String? = null,
    val lastRawOutput: String? = null,
    val lastExitCode: Int? = null,
    val lastUpdatedEpochMs: Long? = null,
    val revertOnDisconnect: Boolean = false,
    val targetPackageOverride: String? = null
)

data class CommandCardItem(
    val command: Command,
    val state: CommandRuntimeState
) {
    val isUnavailable: Boolean
        get() = command.requiresRoot

    val effectiveTargetPackage: String?
        get() = if (command.supportsTargetPackage) {
            Command.sanitizePackageName(state.targetPackageOverride) ?: command.defaultTargetPackage
        } else {
            null
        }
}

enum class HistoryActionType {
    TURN_ON,
    TURN_OFF,
    TEST_RUN,
    REVERT_ALL,
    AUTO_REVERT,
    CUSTOM_RUN
}

data class CommandHistoryRecord(
    val id: Long,
    val commandId: String,
    val commandName: String,
    val category: CommandCategory,
    val action: HistoryActionType,
    val executedShell: String,
    val verifyOutput: String,
    val succeeded: Boolean,
    val timestampEpochMs: Long
)

data class ToggleExecutionReport(
    val commandId: String,
    val requestedEnabled: Boolean,
    val verifiedEnabled: Boolean?,
    val verificationPassed: Boolean,
    val outcome: VerificationOutcome,
    val combinedShellOutput: String,
    val verifyOutput: String,
    val errorMessage: String?
)
