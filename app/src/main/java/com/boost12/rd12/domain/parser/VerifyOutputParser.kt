package com.boost12.rd12.domain.parser

import com.boost12.rd12.domain.model.VerificationOutcome
import com.boost12.rd12.domain.model.VerifyRule
import kotlin.math.abs

/**
 * Pure, zero-crash verification output parser that determines whether a shell command's
 * real-time verification query proves ON, OFF, or blocked/indeterminate state.
 */
object VerifyOutputParser {

    private val PERMISSION_DENIAL_PATTERNS = listOf(
        Regex("""SecurityException""", RegexOption.IGNORE_CASE),
        Regex("""Permission denial""", RegexOption.IGNORE_CASE),
        Regex("""requires\s+android\.permission""", RegexOption.IGNORE_CASE),
        Regex("""Operation not permitted""", RegexOption.IGNORE_CASE),
        Regex("""Permission denied""", RegexOption.IGNORE_CASE),
        Regex("""cmd:\s*Can't find service""", RegexOption.IGNORE_CASE),
        Regex("""Unknown command:""", RegexOption.IGNORE_CASE)
    )

    fun evaluate(rule: VerifyRule, rawOutput: String?): VerificationOutcome {
        if (rule is VerifyRule.RootUnavailable) {
            return VerificationOutcome.UNSUPPORTED_OR_BLOCKED
        }

        val cleaned = rawOutput?.replace("\r\n", "\n")?.trim().orEmpty()
        if (cleaned.isEmpty()) {
            return when (rule) {
                is VerifyRule.RegexMatch -> {
                    val offRegex = compileSafe(rule.offPattern, rule.ignoreCase)
                    if (offRegex != null && offRegex.containsMatchIn("")) {
                        VerificationOutcome.VERIFIED_OFF
                    } else {
                        VerificationOutcome.INDETERMINATE
                    }
                }
                else -> VerificationOutcome.INDETERMINATE
            }
        }

        if (PERMISSION_DENIAL_PATTERNS.any { it.containsMatchIn(cleaned) }) {
            return VerificationOutcome.UNSUPPORTED_OR_BLOCKED
        }

        return when (rule) {
            is VerifyRule.RegexMatch -> evaluateRegex(rule, cleaned)
            is VerifyRule.AllScalesEqual -> evaluateScales(rule, cleaned)
            is VerifyRule.RefreshRateHzRule -> evaluateRefreshRate(rule, cleaned)
            is VerifyRule.ContainsSubstring -> evaluateContains(rule, cleaned)
            is VerifyRule.RootUnavailable -> VerificationOutcome.UNSUPPORTED_OR_BLOCKED
        }
    }

    private fun evaluateRegex(rule: VerifyRule.RegexMatch, output: String): VerificationOutcome {
        val onRegex = compileSafe(rule.onPattern, rule.ignoreCase)
            ?: return VerificationOutcome.INDETERMINATE
        val offRegex = compileSafe(rule.offPattern, rule.ignoreCase)

        val matchesOn = onRegex.containsMatchIn(output)
        val matchesOff = offRegex?.containsMatchIn(output) == true

        return when {
            matchesOn && !matchesOff -> VerificationOutcome.VERIFIED_ON
            matchesOff && !matchesOn -> VerificationOutcome.VERIFIED_OFF
            matchesOn -> VerificationOutcome.VERIFIED_ON
            offRegex == null && !matchesOn -> VerificationOutcome.VERIFIED_OFF
            else -> VerificationOutcome.INDETERMINATE
        }
    }

    private fun evaluateScales(rule: VerifyRule.AllScalesEqual, output: String): VerificationOutcome {
        val lines = output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

        if (lines.size < rule.expectedCount) {
            return VerificationOutcome.INDETERMINATE
        }

        val values = lines.take(rule.expectedCount).map { token ->
            if (token.equals("null", ignoreCase = true)) 1.0f else token.toFloatOrNull()
        }
        if (values.any { it == null }) {
            return VerificationOutcome.INDETERMINATE
        }

        val nonNullValues = values.filterNotNull()
        val allMatchTarget = nonNullValues.all { abs(it - rule.targetScaleOn) <= rule.tolerance }
        return if (allMatchTarget) {
            VerificationOutcome.VERIFIED_ON
        } else {
            VerificationOutcome.VERIFIED_OFF
        }
    }

    /**
     * Evaluates MIUI / HyperOS & AOSP refresh rate keys (`user_refresh_rate`, `miui_refresh_rate`,
     * `min_refresh_rate`, `peak_refresh_rate`) emitted as `key=value` lines.
     */
    private fun evaluateRefreshRate(
        rule: VerifyRule.RefreshRateHzRule,
        output: String
    ): VerificationOutcome {
        val pairs = mutableMapOf<String, Float>()
        for (line in output.lineSequence()) {
            val idx = line.indexOf('=')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim().lowercase()
            val rawVal = line.substring(idx + 1).trim()
            val num = rawVal.toFloatOrNull()
            if (num != null && num in 30f..240f) {
                pairs[key] = num
            }
        }

        if (pairs.isEmpty()) {
            // Fallback: plain numeric line
            val single = output.lineSequence().mapNotNull { it.trim().toFloatOrNull() }.firstOrNull()
                ?: return VerificationOutcome.INDETERMINATE
            return if (abs(single - rule.targetOnHz) < 1.5f) {
                VerificationOutcome.VERIFIED_ON
            } else {
                VerificationOutcome.VERIFIED_OFF
            }
        }

        val userHz = pairs["user_refresh_rate"] ?: pairs["miui_refresh_rate"]
        val minHz = pairs["min_refresh_rate"]
        val peakHz = pairs["peak_refresh_rate"]

        val target = rule.targetOnHz.toFloat()
        val miuiLockedOn = userHz != null && abs(userHz - target) < 1.5f
        val aospLockedOn = minHz != null && abs(minHz - target) < 1.5f &&
            (peakHz == null || peakHz + 1.5f >= target)

        return when {
            miuiLockedOn && (minHz == null || abs(minHz - target) < 1.5f) -> VerificationOutcome.VERIFIED_ON
            userHz == null && aospLockedOn -> VerificationOutcome.VERIFIED_ON
            miuiLockedOn && aospLockedOn -> VerificationOutcome.VERIFIED_ON
            else -> VerificationOutcome.VERIFIED_OFF
        }
    }

    private fun evaluateContains(
        rule: VerifyRule.ContainsSubstring,
        output: String
    ): VerificationOutcome {
        if (rule.expectedOn.isBlank()) return VerificationOutcome.INDETERMINATE
        val hasOn = output.contains(rule.expectedOn, ignoreCase = rule.ignoreCase)
        val hasOff = rule.expectedOff?.takeIf { it.isNotBlank() }
            ?.let { output.contains(it, ignoreCase = rule.ignoreCase) } == true

        return when {
            hasOn && !hasOff -> VerificationOutcome.VERIFIED_ON
            hasOff && !hasOn -> VerificationOutcome.VERIFIED_OFF
            hasOn -> VerificationOutcome.VERIFIED_ON
            rule.expectedOff.isNullOrBlank() && !hasOn -> VerificationOutcome.VERIFIED_OFF
            else -> VerificationOutcome.INDETERMINATE
        }
    }

    private fun compileSafe(pattern: String?, ignoreCase: Boolean): Regex? {
        if (pattern.isNullOrBlank()) return null
        return runCatching {
            if (ignoreCase) {
                Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
            } else {
                Regex(pattern, setOf(RegexOption.MULTILINE))
            }
        }.getOrNull()
    }
}
