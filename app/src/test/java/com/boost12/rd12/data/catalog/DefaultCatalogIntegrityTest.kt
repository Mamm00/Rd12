package com.boost12.rd12.data.catalog

import com.boost12.rd12.domain.model.VerificationOutcome
import com.boost12.rd12.domain.model.VerifyRule
import com.boost12.rd12.domain.parser.VerifyOutputParser
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
        assertThat(activeShellCommands.size).isAtLeast(34)

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

    @Test
    fun `all Memory, Background Process, and #Alice commands are present and verify ON vs OFF accurately`() {
        val allOnShells = DefaultCommandCatalog.builtInCommands.flatMap { it.onShell }

        val requiredShellCommands = listOf(
            "device_config put activity_manager max_cached_processes 108096",
            "device_config put activity_manager max_phantom_processes 2147483647",
            "settings put global ram_expand_size 0",
            "settings put global zram_enabled 1",
            "settings put global cached_apps_freezer enabled",
            "settings put global app_standby_enabled 1",
            "settings put global app_restriction_enabled true",
            "settings put global background_process_limit 4",
            "settings put system intelligent_sleep_mode 0",
            "device_config set_sync_disabled_for_tests persistent",
            "device_config put activity_manager max_cached_processes 512",
            "settings put global settings_enable_monitor_phantom_procs false",
            "device_config put activity_manager max_empty_time_millis 43200000",
            "device_config put activity_manager use_compaction true",
            "device_config put activity_manager_native_boot use_freezer true"
        )

        for (expectedCmd in requiredShellCommands) {
            assertThat(allOnShells).contains(expectedCmd)
        }

        // Verify #Alice bundle parser ON vs partial/OFF
        val aliceBundle = DefaultCommandCatalog.builtInCommands.first { it.id == "alice_full_profile_bundle" }
        val allSevenOn = """
            sync=persistent
            cached=512
            phantom=2147483647
            mon=false
            empty=43200000
            compact=true
            freezer=true
        """.trimIndent()
        val stockOrPartial = """
            sync=none
            cached=null
            phantom=null
            mon=true
            empty=null
            compact=null
            freezer=null
        """.trimIndent()
        val overriddenTo108096 = """
            sync=persistent
            cached=108096
            phantom=2147483647
            mon=false
            empty=43200000
            compact=true
            freezer=true
        """.trimIndent()

        assertThat(VerifyOutputParser.evaluate(aliceBundle.verifyParser, allSevenOn))
            .isEqualTo(VerificationOutcome.VERIFIED_ON)
        assertThat(VerifyOutputParser.evaluate(aliceBundle.verifyParser, stockOrPartial))
            .isEqualTo(VerificationOutcome.VERIFIED_OFF)
        assertThat(VerifyOutputParser.evaluate(aliceBundle.verifyParser, overriddenTo108096))
            .isEqualTo(VerificationOutcome.VERIFIED_OFF)

        // Verify individual presets (512 vs 108096, ram_expand_size 0, freezer, etc.)
        val byId = DefaultCommandCatalog.builtInCommands.associateBy { it.id }

        assertThat(VerifyOutputParser.evaluate(byId.getValue("ram_device_config_max_cached_108096").verifyParser, "108096"))
            .isEqualTo(VerificationOutcome.VERIFIED_ON)
        assertThat(VerifyOutputParser.evaluate(byId.getValue("ram_device_config_max_cached_108096").verifyParser, "512"))
            .isEqualTo(VerificationOutcome.VERIFIED_OFF)

        assertThat(VerifyOutputParser.evaluate(byId.getValue("alice_device_config_max_cached_512").verifyParser, "512"))
            .isEqualTo(VerificationOutcome.VERIFIED_ON)
        assertThat(VerifyOutputParser.evaluate(byId.getValue("alice_device_config_max_cached_512").verifyParser, "108096"))
            .isEqualTo(VerificationOutcome.VERIFIED_OFF)

        assertThat(VerifyOutputParser.evaluate(byId.getValue("ram_disable_miui_ram_expand").verifyParser, "0"))
            .isEqualTo(VerificationOutcome.VERIFIED_ON)
        assertThat(VerifyOutputParser.evaluate(byId.getValue("ram_disable_miui_ram_expand").verifyParser, "4096"))
            .isEqualTo(VerificationOutcome.VERIFIED_OFF)

        assertThat(VerifyOutputParser.evaluate(byId.getValue("bg_cached_apps_freezer_enabled").verifyParser, "enabled"))
            .isEqualTo(VerificationOutcome.VERIFIED_ON)
        assertThat(VerifyOutputParser.evaluate(byId.getValue("bg_cached_apps_freezer_enabled").verifyParser, "device_default"))
            .isEqualTo(VerificationOutcome.VERIFIED_OFF)
    }
}

