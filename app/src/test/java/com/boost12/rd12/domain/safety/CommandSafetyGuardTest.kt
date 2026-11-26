package com.boost12.rd12.domain.safety

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CommandSafetyGuardTest {

    @Test
    fun `blocks rm, dd, mkfs, su, factory reset, and chained destructive commands`() {
        val dangerousCommands = listOf(
            "rm -rf /sdcard/DCIM",
            "/system/bin/rm /data/local/tmp/test",
            "toybox rm -f /sdcard/file",
            "dd if=/dev/zero of=/dev/block/bootdevice/by-name/boot",
            "mkfs.ext4 /dev/block/sda1",
            "su -c 'echo performance > /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor'",
            "magisk --install-module mod.zip",
            "am broadcast -a android.intent.action.MASTER_CLEAR",
            "cmd testharness enable",
            "reboot bootloader",
            "reboot recovery",
            "pm uninstall --user 0 com.miui.powerkeeper",
            "settings put global window_animation_scale 0.0 && rm -rf /sdcard/Download",
            "sh -c 'rm -rf /data/local/tmp/*'",
            "echo 1 > /system/build.prop",
            "echo 60 | tee /proc/sys/vm/swappiness"
        )

        for (cmd in dangerousCommands) {
            val res = CommandSafetyGuard.inspect(cmd)
            assertThat(res).isInstanceOf(CommandSafetyGuard.CheckResult.Blocked::class.java)
        }
    }

    @Test
    fun `allows safe shell tuning commands used on Redmi 12 4G`() {
        val safeCommands = listOf(
            "settings put global window_animation_scale 0.0",
            "settings put secure user_refresh_rate 90",
            "cmd power set-fixed-performance-mode-enabled true",
            "dumpsys power | grep -i mFixedPerformanceModeEnabled",
            "wm density 440",
            "wm density reset",
            "pm trim-caches 1024M 2>/dev/null || true",
            "sm fstrim 2>/dev/null || true",
            "cmd netpolicy set restrict-background true"
        )

        for (cmd in safeCommands) {
            val res = CommandSafetyGuard.inspect(cmd)
            assertThat(res).isEqualTo(CommandSafetyGuard.CheckResult.Allowed)
        }
    }
}
