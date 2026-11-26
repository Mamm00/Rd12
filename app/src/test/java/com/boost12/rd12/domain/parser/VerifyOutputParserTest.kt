package com.boost12.rd12.domain.parser

import com.boost12.rd12.domain.model.VerificationOutcome
import com.boost12.rd12.domain.model.VerifyRule
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VerifyOutputParserTest {

    @Test
    fun `AllScalesEqual verifies 0_0x ON and 1_0x OFF`() {
        val rule = VerifyRule.AllScalesEqual(targetScaleOn = 0.0f)

        assertThat(VerifyOutputParser.evaluate(rule, "0.0\n0.0\n0.0"))
            .isEqualTo(VerificationOutcome.VERIFIED_ON)
        assertThat(VerifyOutputParser.evaluate(rule, "0\n0\n0"))
            .isEqualTo(VerificationOutcome.VERIFIED_ON)
        assertThat(VerifyOutputParser.evaluate(rule, "1.0\n1.0\n1.0"))
            .isEqualTo(VerificationOutcome.VERIFIED_OFF)
        assertThat(VerifyOutputParser.evaluate(rule, "null\nnull\nnull"))
            .isEqualTo(VerificationOutcome.VERIFIED_OFF)
    }

    @Test
    fun `AllScalesEqual verifies 0_5x ON and rejects mixed scales`() {
        val rule = VerifyRule.AllScalesEqual(targetScaleOn = 0.5f)

        assertThat(VerifyOutputParser.evaluate(rule, "0.5\n0.5\n0.5"))
            .isEqualTo(VerificationOutcome.VERIFIED_ON)
        assertThat(VerifyOutputParser.evaluate(rule, "0.5\n1.0\n0.5"))
            .isEqualTo(VerificationOutcome.VERIFIED_OFF)
    }

    @Test
    fun `RefreshRateHzRule detects MIUI and AOSP 90Hz vendor paths`() {
        val rule = VerifyRule.RefreshRateHzRule(targetOnHz = 90)

        val miui90 = """
            user_refresh_rate=90
            miui_refresh_rate=90
            min_refresh_rate=90.0
            peak_refresh_rate=90.0
        """.trimIndent()
        assertThat(VerifyOutputParser.evaluate(rule, miui90))
            .isEqualTo(VerificationOutcome.VERIFIED_ON)

        val miui60 = """
            user_refresh_rate=60
            miui_refresh_rate=60
            min_refresh_rate=60.0
            peak_refresh_rate=90.0
        """.trimIndent()
        assertThat(VerifyOutputParser.evaluate(rule, miui60))
            .isEqualTo(VerificationOutcome.VERIFIED_OFF)

        val aospOnly90 = """
            user_refresh_rate=null
            miui_refresh_rate=null
            min_refresh_rate=90.0
            peak_refresh_rate=90.0
        """.trimIndent()
        assertThat(VerifyOutputParser.evaluate(rule, aospOnly90))
            .isEqualTo(VerificationOutcome.VERIFIED_ON)
    }

    @Test
    fun `RegexMatch distinguishes wm density override vs stock reset`() {
        val rule = VerifyRule.RegexMatch(
            onPattern = "Override density:\\s*440",
            offPattern = "\\A(?![\\s\\S]*Override density)[\\s\\S]*Physical density:"
        )

        val overridden = "Physical density: 450\nOverride density: 440"
        val stockReset = "Physical density: 450"

        assertThat(VerifyOutputParser.evaluate(rule, overridden))
            .isEqualTo(VerificationOutcome.VERIFIED_ON)
        assertThat(VerifyOutputParser.evaluate(rule, stockReset))
            .isEqualTo(VerificationOutcome.VERIFIED_OFF)
    }

    @Test
    fun `Permission denial or SecurityException never fakes ON and never crashes`() {
        val rule = VerifyRule.ContainsSubstring(expectedOn = "1")

        val secEx = "java.lang.SecurityException: Permission denial: writing to settings requires android.permission.WRITE_SECURE_SETTINGS"
        assertThat(VerifyOutputParser.evaluate(rule, secEx))
            .isEqualTo(VerificationOutcome.UNSUPPORTED_OR_BLOCKED)
    }

    @Test
    fun `Malformed empty or corrupt regex inputs never throw`() {
        val badRegexRule = VerifyRule.RegexMatch(onPattern = "[unclosed_group(")

        assertThat(VerifyOutputParser.evaluate(badRegexRule, "anything"))
            .isEqualTo(VerificationOutcome.INDETERMINATE)
        assertThat(VerifyOutputParser.evaluate(badRegexRule, null))
            .isEqualTo(VerificationOutcome.INDETERMINATE)
        assertThat(VerifyOutputParser.evaluate(VerifyRule.RootUnavailable(), "root"))
            .isEqualTo(VerificationOutcome.UNSUPPORTED_OR_BLOCKED)
    }
}
