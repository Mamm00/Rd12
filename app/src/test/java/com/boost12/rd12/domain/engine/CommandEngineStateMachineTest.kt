package com.boost12.rd12.domain.engine

import com.boost12.rd12.domain.model.Command
import com.boost12.rd12.domain.model.CommandCategory
import com.boost12.rd12.domain.model.RiskLevel
import com.boost12.rd12.domain.model.ShellExecutionResult
import com.boost12.rd12.domain.model.VerificationOutcome
import com.boost12.rd12.domain.model.VerifyRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException

class CommandEngineStateMachineTest {

    private val sampleCommand = Command(
        id = "test_fixed_perf",
        name = "Fixed Performance Mode",
        summary = "Test CPU hint",
        description = "Test description",
        category = CommandCategory.CPU,
        onShell = listOf("cmd power set-fixed-performance-mode-enabled true"),
        offShell = listOf("cmd power set-fixed-performance-mode-enabled false"),
        verifyShell = "dumpsys power | grep mFixedPerformanceModeEnabled",
        verifyParser = VerifyRule.RegexMatch(
            onPattern = "mFixedPerformanceModeEnabled=true",
            offPattern = "mFixedPerformanceModeEnabled=false"
        ),
        riskLevel = RiskLevel.MODERATE
    )

    @Test
    fun `toggle transitions to ON only when verification passes`() = runTest {
        var powerState = false
        val engine = CommandEngine(
            shellRunner = { cmd, _ ->
                when {
                    cmd.contains("true") -> {
                        powerState = true
                        Result.success(ShellExecutionResult(cmd, "", "", 0, 12L))
                    }
                    cmd.contains("false") -> {
                        powerState = false
                        Result.success(ShellExecutionResult(cmd, "", "", 0, 12L))
                    }
                    else -> Result.success(
                        ShellExecutionResult(
                            cmd,
                            "  mFixedPerformanceModeEnabled=$powerState",
                            "",
                            0,
                            8L
                        )
                    )
                }
            },
            transientRetryDelayMs = 0L
        )

        val onReport = engine.applyToggle(sampleCommand, targetEnabled = true, previousVerifiedState = false)
        assertThat(onReport.verificationPassed).isTrue()
        assertThat(onReport.verifiedEnabled).isTrue()
        assertThat(onReport.errorMessage).isNull()

        val offReport = engine.applyToggle(sampleCommand, targetEnabled = false, previousVerifiedState = true)
        assertThat(offReport.verificationPassed).isTrue()
        assertThat(offReport.verifiedEnabled).isFalse()
        assertThat(offReport.errorMessage).isNull()
    }

    @Test
    fun `toggle stays OFF and surfaces error banner when HAL ignores command`() = runTest {
        val engine = CommandEngine(
            shellRunner = { cmd, _ ->
                val out = if (cmd.startsWith("dumpsys")) {
                    "mFixedPerformanceModeEnabled=false"
                } else {
                    ""
                }
                Result.success(ShellExecutionResult(cmd, out, "", 0, 10L))
            },
            transientRetryDelayMs = 0L
        )

        val report = engine.applyToggle(sampleCommand, targetEnabled = true, previousVerifiedState = false)
        assertThat(report.verificationPassed).isFalse()
        assertThat(report.verifiedEnabled).isFalse()
        assertThat(report.errorMessage).contains("Didn't apply")
    }

    @Test
    fun `engine retries once on transient failure and succeeds`() = runTest {
        var calls = 0
        val engine = CommandEngine(
            shellRunner = { cmd, _ ->
                calls++
                if (calls == 1) {
                    Result.failure(IOException("Transient socket stall"))
                } else {
                    val out = if (cmd.startsWith("dumpsys")) "mFixedPerformanceModeEnabled=true" else ""
                    Result.success(ShellExecutionResult(cmd, out, "", 0, 15L))
                }
            },
            transientRetryDelayMs = 0L
        )

        val report = engine.applyToggle(sampleCommand, targetEnabled = true, previousVerifiedState = false)
        assertThat(calls).isEqualTo(3) // 1 failed + 1 retry + 1 verify
        assertThat(report.verificationPassed).isTrue()
        assertThat(report.verifiedEnabled).isTrue()
    }

    @Test
    fun `revertIfVerifyFails automatically runs offShell rollback when unsupported`() = runTest {
        val executed = mutableListOf<String>()
        val skiavkCmd = sampleCommand.copy(
            id = "skiavk_test",
            onShell = listOf("setprop debug.hwui.renderer skiavk"),
            offShell = listOf("setprop debug.hwui.renderer opengl"),
            verifyShell = "getprop debug.hwui.renderer",
            verifyParser = VerifyRule.RegexMatch(onPattern = "^skiavk$", offPattern = "^opengl$"),
            revertIfVerifyFails = true
        )

        val engine = CommandEngine(
            shellRunner = { cmd, _ ->
                executed += cmd
                // Simulate SELinux ignoring skiavk and keeping opengl
                val out = if (cmd.startsWith("getprop")) "opengl" else ""
                Result.success(ShellExecutionResult(cmd, out, "", 0, 5L))
            },
            transientRetryDelayMs = 0L
        )

        val report = engine.applyToggle(skiavkCmd, targetEnabled = true, previousVerifiedState = false)
        assertThat(report.verificationPassed).isFalse()
        assertThat(report.outcome).isEqualTo(VerificationOutcome.VERIFIED_OFF)
        assertThat(executed).containsExactly(
            "setprop debug.hwui.renderer skiavk",
            "getprop debug.hwui.renderer",
            "setprop debug.hwui.renderer opengl"
        ).inOrder()
    }
}
