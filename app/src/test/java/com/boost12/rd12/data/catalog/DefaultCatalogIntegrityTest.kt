package com.boost12.rd12.data.catalog

import com.boost12.rd12.domain.model.VerifyRule
import com.boost12.rd12.domain.safety.CommandSafetyGuard
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DefaultCatalogIntegrityTest {

    @Test
    fun `every non-root catalog command has ON counterpart, OFF counterpart, verify rule, and passes safety guard`() {
        val commands = DefaultCommandCatalog.builtInCommands
        assertThat(commands).isNotEmpty()

        // Unique IDs
        val ids = commands.map { it.id }
        assertThat(ids.distinct()).hasSize(ids.size)

        val rootUnavailable = commands.filter { it.requiresRoot }
        val activeShellCommands = commands.filter { !it.requiresRoot }

        assertThat(rootUnavailable).hasSize(3)
        assertThat(activeShellCommands.size).isAtLeast(18)

        for (cmd in activeShellCommands) {
            assertThat(cmd.onShell).isNotEmpty()
            assertThat(cmd.offShell).isNotEmpty()
            assertThat(cmd.verifyShell).isNotEmpty()
            assertThat(cmd.verifyParser).isNotInstanceOf(VerifyRule.RootUnavailable::class.java)

            val resolvedOn = cmd.resolveOnShell(cmd.defaultTargetPackage)
            val resolvedOff = cmd.resolveOffShell(cmd.defaultTargetPackage)
            val resolvedVerify = cmd.resolveVerifyShell(cmd.defaultTargetPackage)

            for (shell in resolvedOn + resolvedOff + resolvedVerify) {
                val safety = CommandSafetyGuard.inspect(shell)
                assertThat(safety).isEqualTo(CommandSafetyGuard.CheckResult.Allowed)
                assertThat(shell).doesNotContain("su ")
                assertThat(shell).doesNotContain("magisk")
            }
        }

        for (rootCmd in rootUnavailable) {
            assertThat(rootCmd.unavailableReason).isNotNull()
            assertThat(rootCmd.unavailableReason).contains("Unavailable on this device")
            assertThat(rootCmd.verifyParser).isInstanceOf(VerifyRule.RootUnavailable::class.java)
        }
    }
}
