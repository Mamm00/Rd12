package com.boost12.rd12.domain.safety

/**
 * Hard-blocks destructive, root-escalating, or system-partition-mutating shell commands
 * BEFORE they ever reach the ADB-over-Wi-Fi transport.
 */
object CommandSafetyGuard {

    sealed interface CheckResult {
        data object Allowed : CheckResult
        data class Blocked(val reason: String, val offendingSegment: String) : CheckResult
    }

    private val BLOCKED_BINARIES = setOf(
        "rm",
        "rmdir",
        "unlink",
        "shred",
        "dd",
        "mkfs",
        "mke2fs",
        "mkfs.ext4",
        "mkfs.f2fs",
        "newfs_msdos",
        "wipe",
        "su",
        "magisk",
        "ksud",
        "apatch",
        "fastboot",
        "recovery",
        "flash_image"
    )

    private val PROTECTED_WRITE_PATHS = listOf(
        "/system",
        "/vendor",
        "/product",
        "/odm",
        "/data/system",
        "/dev/block",
        "/proc/sys"
    )

    private val DESTRUCTIVE_SUBSTRING_PATTERNS = listOf(
        Regex("""\bmaster_clear\b""", RegexOption.IGNORE_CASE) to
            "Factory reset broadcasts (MASTER_CLEAR) are hard-blocked.",
        Regex("""\bfactory[_\-\s]?reset\b""", RegexOption.IGNORE_CASE) to
            "Factory reset commands are hard-blocked.",
        Regex("""\bcmd\s+testharness\b""", RegexOption.IGNORE_CASE) to
            "Test-harness device wipe commands are hard-blocked.",
        Regex("""\breboot\s+(recovery|bootloader|fastboot|edl)\b""", RegexOption.IGNORE_CASE) to
            "Bootloader / recovery / EDL reboot commands are hard-blocked.",
        Regex("""\b(?:pm|cmd\s+package)\s+uninstall\b""", RegexOption.IGNORE_CASE) to
            "Package uninstallation via Boost12 is hard-blocked for device safety.",
        Regex("""\bpm\s+clear\s+(?:android|com\.android\.systemui|com\.miui\.home)\b""", RegexOption.IGNORE_CASE) to
            "Clearing core system packages is hard-blocked.",
        Regex("""\bmount\b.*\bremount\b""", RegexOption.IGNORE_CASE) to
            "Filesystem remounting is hard-blocked.",
        Regex("""`.*`|\$\(.*\b(?:rm|dd|mkfs|su|wipe)\b.*\)""") to
            "Command substitution containing blocked commands is forbidden."
    )

    private val WRITE_REDIRECT_REGEX = Regex("""(?:>>?|(?:\|\s*tee\b))\s*([^\s;|&]+)""")

    fun inspect(rawShellCommand: String): CheckResult {
        val trimmed = rawShellCommand.trim()
        if (trimmed.isEmpty()) {
            return CheckResult.Blocked("Command cannot be empty.", "")
        }

        for ((pattern, reason) in DESTRUCTIVE_SUBSTRING_PATTERNS) {
            if (pattern.containsMatchIn(trimmed)) {
                return CheckResult.Blocked(reason, trimmed)
            }
        }

        // Check output redirections to protected system partitions (allow /dev/null)
        for (match in WRITE_REDIRECT_REGEX.findAll(trimmed)) {
            val target = match.groupValues.getOrNull(1)?.trim('"', '\'').orEmpty()
            if (target != "/dev/null" && PROTECTED_WRITE_PATHS.any { target.startsWith(it) }) {
                return CheckResult.Blocked(
                    "Writing to protected partition path '$target' is hard-blocked.",
                    match.value
                )
            }
        }

        // Split compound shell chains (;, &&, ||, |, newlines) and inspect every command prefix
        val segments = splitShellSegments(trimmed)
        for (segment in segments) {
            val tokens = tokenizeSegment(segment)
            if (tokens.isEmpty()) continue

            // Strip leading env assignments (FOO=bar) or subshell parentheses
            val executableIdx = tokens.indexOfFirst { token ->
                !token.matches(Regex("""^[A-Za-z_][A-Za-z0-9_]*=.*$"""))
            }
            if (executableIdx < 0) continue

            val firstBinary = normalizeBinaryName(tokens[executableIdx])
            if (firstBinary in BLOCKED_BINARIES) {
                return CheckResult.Blocked(
                    "Command '$firstBinary' is hard-blocked by Boost12 safety policy.",
                    segment
                )
            }

            // Also inspect `toybox <applet>` / `busybox <applet>` / `sh -c ...`
            if ((firstBinary == "toybox" || firstBinary == "busybox") && executableIdx + 1 < tokens.size) {
                val applet = normalizeBinaryName(tokens[executableIdx + 1])
                if (applet in BLOCKED_BINARIES) {
                    return CheckResult.Blocked(
                        "Command '$firstBinary $applet' is hard-blocked by Boost12 safety policy.",
                        segment
                    )
                }
            }

            if ((firstBinary == "sh" || firstBinary == "bash") && tokens.size > executableIdx + 2) {
                val flag = tokens[executableIdx + 1]
                if (flag == "-c") {
                    val nested = tokens.subList(executableIdx + 2, tokens.size).joinToString(" ")
                        .trim('"', '\'')
                    val nestedCheck = inspect(nested)
                    if (nestedCheck is CheckResult.Blocked) return nestedCheck
                }
            }
        }

        return CheckResult.Allowed
    }

    fun inspectAll(commands: List<String>): CheckResult {
        for (cmd in commands) {
            val res = inspect(cmd)
            if (res is CheckResult.Blocked) return res
        }
        return CheckResult.Allowed
    }

    private fun splitShellSegments(command: String): List<String> {
        return command
            .replace("$(", " ; ")
            .split(Regex("""\s*(?:;|&&|\|\||\|+|\n)\s*"""))
            .map { it.trim().trim('(', ')') }
            .filter { it.isNotEmpty() }
    }

    private fun tokenizeSegment(segment: String): List<String> {
        return segment.trim()
            .split(Regex("""\s+"""))
            .map { it.trim('(', ')', '"', '\'') }
            .filter { it.isNotEmpty() }
    }

    private fun normalizeBinaryName(token: String): String {
        return token.substringAfterLast('/').lowercase()
    }
}
