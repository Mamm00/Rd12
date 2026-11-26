package com.boost12.rd12.domain.engine

import com.boost12.rd12.domain.model.Command
import com.boost12.rd12.domain.model.ShellExecutionResult
import com.boost12.rd12.domain.model.ToggleExecutionReport
import com.boost12.rd12.domain.model.VerificationOutcome
import com.boost12.rd12.domain.parser.VerifyOutputParser
import com.boost12.rd12.domain.safety.CommandSafetyGuard
import kotlinx.coroutines.delay

/**
 * Pure, deterministic Command Execution & Verification Engine.
 *
 * Guarantees:
 * 1. Every shell command is validated by [CommandSafetyGuard] before execution.
 * 2. Commands in `onShell` / `offShell` run sequentially with per-command timeouts.
 * 3. Transient transport/timeout failures are retried once automatically.
 * 4. Real-time verification (`verifyShell`) ALWAYS runs after mutation; the UI toggle state
 *    ONLY updates to ON or OFF when verification explicitly confirms the target state.
 * 5. Optional automatic rollback (`revertIfVerifyFails`) cleans up unsupported hints (e.g. `skiavk`).
 */
class CommandEngine(
    private val shellRunner: suspend (command: String, timeoutMs: Long) -> Result<ShellExecutionResult>,
    private val perCommandTimeoutMs: Long = 8_000L,
    private val transientRetryDelayMs: Long = 350L
) {

    suspend fun applyToggle(
        command: Command,
        targetEnabled: Boolean,
        previousVerifiedState: Boolean?,
        targetPackageOverride: String? = null
    ): ToggleExecutionReport {
        if (command.requiresRoot) {
            return ToggleExecutionReport(
                commandId = command.id,
                requestedEnabled = targetEnabled,
                verifiedEnabled = false,
                verificationPassed = false,
                outcome = VerificationOutcome.UNSUPPORTED_OR_BLOCKED,
                combinedShellOutput = "",
                verifyOutput = "",
                errorMessage = command.unavailableReason
                    ?: "Unavailable on this device without root."
            )
        }

        val commandsToRun = if (targetEnabled) {
            command.resolveOnShell(targetPackageOverride)
        } else {
            command.resolveOffShell(targetPackageOverride)
        }
        val resolvedVerify = command.resolveVerifyShell(targetPackageOverride)

        val safetyCheck = CommandSafetyGuard.inspectAll(commandsToRun + resolvedVerify)
        if (safetyCheck is CommandSafetyGuard.CheckResult.Blocked) {
            return ToggleExecutionReport(
                commandId = command.id,
                requestedEnabled = targetEnabled,
                verifiedEnabled = previousVerifiedState,
                verificationPassed = false,
                outcome = VerificationOutcome.UNSUPPORTED_OR_BLOCKED,
                combinedShellOutput = "",
                verifyOutput = "",
                errorMessage = "Blocked by safety engine: ${safetyCheck.reason}"
            )
        }

        val executionLogs = mutableListOf<String>()
        for (shellCmd in commandsToRun) {
            val execResult = executeWithSingleRetry(shellCmd)
            val shellOut = execResult.getOrNull()
            if (shellOut == null) {
                val err = execResult.exceptionOrNull()?.message ?: "ADB connection lost"
                executionLogs += "$ $shellCmd\n[Transport error: $err]"
                return ToggleExecutionReport(
                    commandId = command.id,
                    requestedEnabled = targetEnabled,
                    verifiedEnabled = previousVerifiedState,
                    verificationPassed = false,
                    outcome = VerificationOutcome.INDETERMINATE,
                    combinedShellOutput = executionLogs.joinToString("\n\n"),
                    verifyOutput = "",
                    errorMessage = "Didn't apply — ADB disconnected ($err)"
                )
            }

            val outSnippet = shellOut.combinedOutput.ifBlank { "(exit ${shellOut.exitCode})" }
            executionLogs += "$ $shellCmd\n$outSnippet"
        }

        // Run real-time verification query
        val verifyExec = executeWithSingleRetry(resolvedVerify)
        val verifyShellResult = verifyExec.getOrNull()
        if (verifyShellResult == null) {
            val err = verifyExec.exceptionOrNull()?.message ?: "verify failed"
            return ToggleExecutionReport(
                commandId = command.id,
                requestedEnabled = targetEnabled,
                verifiedEnabled = previousVerifiedState,
                verificationPassed = false,
                outcome = VerificationOutcome.INDETERMINATE,
                combinedShellOutput = executionLogs.joinToString("\n\n"),
                verifyOutput = "",
                errorMessage = "Didn't apply — verification query failed ($err)"
            )
        }

        val verifyRaw = verifyShellResult.combinedOutput
        val outcome = VerifyOutputParser.evaluate(command.verifyParser, verifyRaw)

        val expectedOutcome = if (targetEnabled) {
            VerificationOutcome.VERIFIED_ON
        } else {
            VerificationOutcome.VERIFIED_OFF
        }

        val passed = outcome == expectedOutcome
        if (!passed && targetEnabled && command.revertIfVerifyFails) {
            // Cleanly revert unsupported hint (e.g. skiavk on locked SELinux prop)
            for (revertCmd in command.resolveOffShell(targetPackageOverride)) {
                if (CommandSafetyGuard.inspect(revertCmd) is CommandSafetyGuard.CheckResult.Allowed) {
                    executeWithSingleRetry(revertCmd)
                }
            }
            executionLogs += "[Auto-reverted unsupported hint after verification mismatch]"
        }

        val newVerifiedState = when (outcome) {
            VerificationOutcome.VERIFIED_ON -> true
            VerificationOutcome.VERIFIED_OFF -> false
            VerificationOutcome.UNSUPPORTED_OR_BLOCKED,
            VerificationOutcome.INDETERMINATE -> previousVerifiedState
        }

        val errorBanner = if (passed) {
            null
        } else {
            when (outcome) {
                VerificationOutcome.UNSUPPORTED_OR_BLOCKED ->
                    "Didn't apply — restricted on this MIUI/HyperOS build (tap for output)"
                else ->
                    "Didn't apply — tap to inspect raw output"
            }
        }

        val fullLog = buildString {
            if (executionLogs.isNotEmpty()) {
                append(executionLogs.joinToString("\n\n"))
                append("\n\n")
            }
            append("# Verify: $resolvedVerify\n")
            append(verifyRaw.ifBlank { "(empty stdout, exit ${verifyShellResult.exitCode})" })
        }

        return ToggleExecutionReport(
            commandId = command.id,
            requestedEnabled = targetEnabled,
            verifiedEnabled = newVerifiedState,
            verificationPassed = passed,
            outcome = outcome,
            combinedShellOutput = fullLog,
            verifyOutput = verifyRaw,
            errorMessage = errorBanner
        )
    }

    suspend fun verifyCurrentState(
        command: Command,
        targetPackageOverride: String? = null
    ): ToggleExecutionReport {
        if (command.requiresRoot) {
            return ToggleExecutionReport(
                commandId = command.id,
                requestedEnabled = false,
                verifiedEnabled = false,
                verificationPassed = true,
                outcome = VerificationOutcome.UNSUPPORTED_OR_BLOCKED,
                combinedShellOutput = command.unavailableReason.orEmpty(),
                verifyOutput = "",
                errorMessage = null
            )
        }

        val resolvedVerify = command.resolveVerifyShell(targetPackageOverride)
        val safety = CommandSafetyGuard.inspect(resolvedVerify)
        if (safety is CommandSafetyGuard.CheckResult.Blocked) {
            return ToggleExecutionReport(
                commandId = command.id,
                requestedEnabled = false,
                verifiedEnabled = null,
                verificationPassed = false,
                outcome = VerificationOutcome.UNSUPPORTED_OR_BLOCKED,
                combinedShellOutput = "",
                verifyOutput = "",
                errorMessage = safety.reason
            )
        }

        val exec = executeWithSingleRetry(resolvedVerify)
        val res = exec.getOrNull() ?: return ToggleExecutionReport(
            commandId = command.id,
            requestedEnabled = false,
            verifiedEnabled = null,
            verificationPassed = false,
            outcome = VerificationOutcome.INDETERMINATE,
            combinedShellOutput = "",
            verifyOutput = "",
            errorMessage = exec.exceptionOrNull()?.message
        )

        val raw = res.combinedOutput
        val outcome = VerifyOutputParser.evaluate(command.verifyParser, raw)
        val enabled = when (outcome) {
            VerificationOutcome.VERIFIED_ON -> true
            VerificationOutcome.VERIFIED_OFF -> false
            else -> null
        }

        return ToggleExecutionReport(
            commandId = command.id,
            requestedEnabled = enabled == true,
            verifiedEnabled = enabled,
            verificationPassed = enabled != null,
            outcome = outcome,
            combinedShellOutput = "# Verify: $resolvedVerify\n${raw.ifBlank { "(empty)" }}",
            verifyOutput = raw,
            errorMessage = null
        )
    }

    suspend fun runSafeSingleShell(rawCommand: String): Result<ShellExecutionResult> {
        val check = CommandSafetyGuard.inspect(rawCommand)
        if (check is CommandSafetyGuard.CheckResult.Blocked) {
            return Result.failure(IllegalArgumentException(check.reason))
        }
        return executeWithSingleRetry(rawCommand)
    }

    private suspend fun executeWithSingleRetry(command: String): Result<ShellExecutionResult> {
        val first = shellRunner(command, perCommandTimeoutMs)
        val firstVal = first.getOrNull()
        if (first.isSuccess && firstVal != null && !firstVal.timedOut) {
            return first
        }

        // Retry once on transient transport error or command timeout
        if (transientRetryDelayMs > 0) {
            delay(transientRetryDelayMs)
        }
        val second = shellRunner(command, perCommandTimeoutMs)
        return second.map { it.copy(retried = true) }
    }
}
