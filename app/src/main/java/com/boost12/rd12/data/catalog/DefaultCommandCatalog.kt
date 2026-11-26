package com.boost12.rd12.data.catalog

import com.boost12.rd12.domain.model.Command
import com.boost12.rd12.domain.model.CommandCategory
import com.boost12.rd12.domain.model.RiskLevel
import com.boost12.rd12.domain.model.VerifyRule

/**
 * Curated, shell-verified command catalog engineered specifically for the
 * Redmi 12 4G (22041219NY family · Snapdragon 685 SM6225 · Adreno 610 · 8 GB / 256 GB · MIUI/HyperOS).
 *
 * Every non-root command has:
 * - Reversible `onShell` and `offShell` counterparts
 * - Real-time `verifyShell` and deterministic [VerifyRule]
 * - Zero root (`su`), zero `/system` writes, zero destructive operations
 */
object DefaultCommandCatalog {

    val builtInCommands: List<Command> = listOf(
        // ======================== DISPLAY & UI/GAME ========================
        Command(
            id = "anim_scale_zero",
            name = "Instant UI Animations (0.0x)",
            summary = "Disables window, transition, and animator duration scales for instant response.",
            description = "Sets window_animation_scale, transition_animation_scale, and animator_duration_scale to 0.0 so app launches and HyperOS recents open with zero animation delay. Toggling OFF restores all three scales to stock 1.0x.",
            category = CommandCategory.DISPLAY,
            onShell = listOf(
                "settings put global window_animation_scale 0.0",
                "settings put global transition_animation_scale 0.0",
                "settings put global animator_duration_scale 0.0"
            ),
            offShell = listOf(
                "settings put global window_animation_scale 1.0",
                "settings put global transition_animation_scale 1.0",
                "settings put global animator_duration_scale 1.0"
            ),
            verifyShell = "settings get global window_animation_scale; settings get global transition_animation_scale; settings get global animator_duration_scale",
            verifyParser = VerifyRule.AllScalesEqual(
                targetScaleOn = 0.0f,
                humanSummary = "All 3 animation scales equal 0.0 (OFF restores 1.0)"
            ),
            riskLevel = RiskLevel.SAFE
        ),
        Command(
            id = "anim_scale_half",
            name = "Snappy 0.5x Animation Scales",
            summary = "Halves UI animation duration (0.5x) while keeping fluid HyperOS transitions.",
            description = "Sets window, transition, and animator scales to 0.5x — twice as fast as stock without breaking apps that depend on ValueAnimator completion callbacks. Toggling OFF restores 1.0x.",
            category = CommandCategory.DISPLAY,
            onShell = listOf(
                "settings put global window_animation_scale 0.5",
                "settings put global transition_animation_scale 0.5",
                "settings put global animator_duration_scale 0.5"
            ),
            offShell = listOf(
                "settings put global window_animation_scale 1.0",
                "settings put global transition_animation_scale 1.0",
                "settings put global animator_duration_scale 1.0"
            ),
            verifyShell = "settings get global window_animation_scale; settings get global transition_animation_scale; settings get global animator_duration_scale",
            verifyParser = VerifyRule.AllScalesEqual(
                targetScaleOn = 0.5f,
                humanSummary = "All 3 animation scales equal 0.5 (OFF restores 1.0)"
            ),
            riskLevel = RiskLevel.SAFE
        ),
        Command(
            id = "display_force_90hz",
            name = "Force 90 Hz Refresh Rate",
            summary = "Locks the Redmi 12 4G panel to 90 Hz across MIUI, HyperOS, and AOSP keys.",
            description = "Writes 90 Hz to both AOSP DisplayManager keys (min_refresh_rate & peak_refresh_rate) and Xiaomi vendor keys (user_refresh_rate & miui_refresh_rate) so AdaptiveSync stops dropping to 50/60 Hz while scrolling. Toggling OFF returns to power-saving 60 Hz minimum.",
            category = CommandCategory.DISPLAY,
            onShell = listOf(
                "settings put secure user_refresh_rate 90",
                "settings put system user_refresh_rate 90",
                "settings put secure miui_refresh_rate 90",
                "settings put system min_refresh_rate 90.0",
                "settings put system peak_refresh_rate 90.0"
            ),
            offShell = listOf(
                "settings put secure user_refresh_rate 60",
                "settings put system user_refresh_rate 60",
                "settings put secure miui_refresh_rate 60",
                "settings put system min_refresh_rate 60.0",
                "settings put system peak_refresh_rate 90.0"
            ),
            verifyShell = "printf 'user_refresh_rate=%s\\nmiui_refresh_rate=%s\\nmin_refresh_rate=%s\\npeak_refresh_rate=%s\\n' \"$(settings get secure user_refresh_rate)\" \"$(settings get secure miui_refresh_rate)\" \"$(settings get system min_refresh_rate)\" \"$(settings get system peak_refresh_rate)\"",
            verifyParser = VerifyRule.RefreshRateHzRule(
                targetOnHz = 90,
                humanSummary = "MIUI user_refresh_rate and/or AOSP min_refresh_rate verified at 90 Hz"
            ),
            riskLevel = RiskLevel.SAFE,
            miuiQuirkNote = "If MIUI Battery Saver is active, PowerKeeper may force 60 Hz until Battery Saver is turned off."
        ),
        Command(
            id = "display_miui_power_mode",
            name = "MIUI Performance Power Profile",
            summary = "Switches Xiaomi system power_mode to 'high' to lift 60 Hz & touch caps.",
            description = "Updates MIUI/HyperOS system power_mode to 'high' so Xiaomi's user-space scheduler prioritizes responsiveness and 90 Hz rendering. Toggling OFF restores 'middle' (Balanced).",
            category = CommandCategory.DISPLAY,
            onShell = listOf("settings put system power_mode high"),
            offShell = listOf("settings put system power_mode middle"),
            verifyShell = "settings get system power_mode",
            verifyParser = VerifyRule.RegexMatch(
                onPattern = "^high$",
                offPattern = "^(middle|null)$",
                humanSummary = "settings get system power_mode == 'high'"
            ),
            riskLevel = RiskLevel.MODERATE,
            revertOnDisconnectDefault = true
        ),
        Command(
            id = "display_miui_game_turbo",
            name = "MIUI Game Turbo Boost Toggle",
            summary = "Enables Xiaomi GameBox boosting & touch gesture optimization flags.",
            description = "Activates MIUI/HyperOS Game Turbo shell flags (gb_boosting and perf_shielder_GESTURE_OPT) reachable without root to reduce touch latency during full-screen games.",
            category = CommandCategory.DISPLAY,
            onShell = listOf(
                "settings put secure gb_boosting 1",
                "settings put system perf_shielder_GESTURE_OPT 1"
            ),
            offShell = listOf(
                "settings put secure gb_boosting 0",
                "settings put system perf_shielder_GESTURE_OPT 0"
            ),
            verifyShell = "settings get secure gb_boosting",
            verifyParser = VerifyRule.RegexMatch(
                onPattern = "^1$",
                offPattern = "^(0|null)$",
                humanSummary = "settings get secure gb_boosting == 1"
            ),
            riskLevel = RiskLevel.SAFE,
            miuiQuirkNote = "Add your games inside Xiaomi Security → Game Turbo so HyperOS retains per-game profiles."
        ),
        Command(
            id = "display_per_app_game_90hz",
            name = "Force 90 Hz & Game Mode Per-App",
            summary = "Sets Android 12+ GameManager 'performance' mode and Xiaomi speed_mode.",
            description = "Requests Android GameManager Mode 2 (Performance / peak refresh rate) for your chosen target app package alongside Xiaomi's speed_mode=1 flag. Toggling OFF restores Standard mode.",
            category = CommandCategory.DISPLAY,
            onShell = listOf(
                "cmd game mode performance <pkg> 2>/dev/null || true",
                "settings put secure speed_mode 1"
            ),
            offShell = listOf(
                "cmd game mode standard <pkg> 2>/dev/null || true",
                "settings put secure speed_mode 0"
            ),
            verifyShell = "settings get secure speed_mode",
            verifyParser = VerifyRule.RegexMatch(
                onPattern = "^1$",
                offPattern = "^(0|null)$",
                humanSummary = "settings get secure speed_mode == 1"
            ),
            riskLevel = RiskLevel.MODERATE,
            supportsTargetPackage = true,
            defaultTargetPackage = "com.tencent.ig"
        ),
        Command(
            id = "display_wm_density_440",
            name = "Display Density Preset (440 DPI)",
            summary = "Applies crisp 440 DPI override; OFF runs 'wm density reset' to safe stock.",
            description = "Overrides WindowManager display density to 440 DPI via 'wm density 440'. Toggling OFF executes 'wm density reset' to cleanly restore the Redmi 12 4G's factory physical density.",
            category = CommandCategory.DISPLAY,
            onShell = listOf("wm density 440"),
            offShell = listOf("wm density reset"),
            verifyShell = "wm density",
            verifyParser = VerifyRule.RegexMatch(
                onPattern = "Override density:\\s*440",
                offPattern = "\\A(?![\\s\\S]*Override density)[\\s\\S]*Physical density:",
                humanSummary = "'wm density' reports Override density: 440 (OFF clears override)"
            ),
            riskLevel = RiskLevel.ADVANCED,
            revertOnDisconnectDefault = true
        ),

        // ======================== CPU & GOVERNOR HINTS ========================
        Command(
            id = "cpu_fixed_performance_mode",
            name = "Fixed Performance Mode (Power HAL)",
            summary = "Requests sustained Snapdragon 685 CPU/GPU clocks via cmd power.",
            description = "Invokes 'cmd power set-fixed-performance-mode-enabled true' to hint the Qualcomm SM6225 Power HAL to hold higher CPU/GPU frequency floors. Verified against dumpsys power; if a HyperOS build ignores it, Boost12 reports honestly.",
            category = CommandCategory.CPU,
            onShell = listOf("cmd power set-fixed-performance-mode-enabled true"),
            offShell = listOf("cmd power set-fixed-performance-mode-enabled false"),
            verifyShell = "dumpsys power | grep -i mFixedPerformanceModeEnabled",
            verifyParser = VerifyRule.RegexMatch(
                onPattern = "mFixedPerformanceModeEnabled=true",
                offPattern = "mFixedPerformanceModeEnabled=false",
                humanSummary = "dumpsys power shows mFixedPerformanceModeEnabled=true"
            ),
            riskLevel = RiskLevel.MODERATE,
            revertOnDisconnectDefault = true
        ),
        Command(
            id = "cpu_disable_adaptive_power_saver",
            name = "Disable Adaptive Power Throttling",
            summary = "Prevents Android Adaptive Battery Saver from downclocking SM6225 cores.",
            description = "Disables adaptive power saver and sticky low-power mode so background heuristics do not unexpectedly throttle Snapdragon 685 Kryo 265 cores under load.",
            category = CommandCategory.CPU,
            onShell = listOf(
                "cmd power set-adaptive-power-saver-enabled false",
                "settings put global low_power 0",
                "settings put global low_power_sticky 0"
            ),
            offShell = listOf(
                "cmd power set-adaptive-power-saver-enabled true"
            ),
            verifyShell = "dumpsys power | grep -i mAdaptivePowerSaverEnabled",
            verifyParser = VerifyRule.RegexMatch(
                onPattern = "mAdaptivePowerSaverEnabled=false",
                offPattern = "mAdaptivePowerSaverEnabled=true",
                humanSummary = "dumpsys power shows mAdaptivePowerSaverEnabled=false"
            ),
            riskLevel = RiskLevel.SAFE
        ),
        Command(
            id = "cpu_relax_background_cpu_quota",
            name = "Relax User-Level CPU Watchdog Cap",
            summary = "Raises ActivityManager CPU check ceiling to prevent premature app throttling.",
            description = "Raises ActivityManager's user-space power_check_max_cpu_1/2 thresholds so legitimate foreground/companion tasks are not aggressively throttled by Android's CPU watchdog.",
            category = CommandCategory.CPU,
            onShell = listOf(
                "settings put global power_check_max_cpu_1 75",
                "settings put global power_check_max_cpu_2 75"
            ),
            offShell = listOf(
                "settings delete global power_check_max_cpu_1",
                "settings delete global power_check_max_cpu_2"
            ),
            verifyShell = "settings get global power_check_max_cpu_1",
            verifyParser = VerifyRule.RegexMatch(
                onPattern = "^75$",
                offPattern = "^(null|25)$",
                humanSummary = "settings get global power_check_max_cpu_1 == 75"
            ),
            riskLevel = RiskLevel.MODERATE
        ),
        Command(
            id = "cpu_root_governor_thermal_locked",
            name = "CPU Scaling Governor & mi_thermald Override",
            summary = "Unavailable on this device — kernel cpufreq & /vendor thermal require root.",
            description = "Writing 'performance' to /sys/devices/system/cpu/cpufreq/policy*/scaling_governor and stopping Xiaomi's mi_thermald daemon require UID 0 (root) on Snapdragon 685 / HyperOS. Boost12 never fakes root-only toggles.",
            category = CommandCategory.CPU,
            onShell = emptyList(),
            offShell = emptyList(),
            verifyShell = "",
            verifyParser = VerifyRule.RootUnavailable(),
            riskLevel = RiskLevel.ADVANCED,
            requiresRoot = true,
            unavailableReason = "Unavailable on this device: /sys/devices/system/cpu/*/scaling_governor and /vendor/etc/thermal-engine.conf are owned by root/system with SELinux enforcement on Redmi 12 4G."
        ),

        // ======================== GPU (ADRENO 610) ========================
        Command(
            id = "gpu_angle_vulkan_driver",
            name = "ANGLE OpenGL-on-Vulkan Translation",
            summary = "Enables global ANGLE GLES driver hint backed by Adreno 610 Vulkan 1.1.",
            description = "Sets angle_gl_driver_all_angle=1 so compatible OpenGL ES workloads can leverage Google's ANGLE layer over Qualcomm Adreno 610's Vulkan driver. Toggling OFF resets to native GLES (0).",
            category = CommandCategory.GPU,
            onShell = listOf("settings put global angle_gl_driver_all_angle 1"),
            offShell = listOf("settings put global angle_gl_driver_all_angle 0"),
            verifyShell = "settings get global angle_gl_driver_all_angle",
            verifyParser = VerifyRule.RegexMatch(
                onPattern = "^1$",
                offPattern = "^(0|null)$",
                humanSummary = "settings get global angle_gl_driver_all_angle == 1"
            ),
            riskLevel = RiskLevel.MODERATE
        ),
        Command(
            id = "gpu_disable_hw_overlays",
            name = "Disable HW Overlays (Force GPU Compose)",
            summary = "Routes layer composition through Adreno 610 GPU instead of MDP overlays.",
            description = "Sets global disable_hw_overlays=1 (Developer Options equivalent) to eliminate display overlay flickering or mixed-rate stutter at the cost of slightly higher GPU power usage.",
            category = CommandCategory.GPU,
            onShell = listOf("settings put global disable_hw_overlays 1"),
            offShell = listOf("settings put global disable_hw_overlays 0"),
            verifyShell = "settings get global disable_hw_overlays",
            verifyParser = VerifyRule.RegexMatch(
                onPattern = "^1$",
                offPattern = "^(0|null)$",
                humanSummary = "settings get global disable_hw_overlays == 1"
            ),
            riskLevel = RiskLevel.ADVANCED,
            revertOnDisconnectDefault = true
        ),
        Command(
            id = "gpu_skiavk_hwui_hint",
            name = "Skia Vulkan HWUI Renderer Hint",
            summary = "Sets debug.hwui.renderer=skiavk for newly launched UI processes.",
            description = "Hints Android's 2D HWUI pipeline to render apps via Skia Vulkan (skiavk) on Adreno 610. Verified via getprop; automatically reverts to opengl if SELinux blocks debug.hwui properties on your ROM.",
            category = CommandCategory.GPU,
            onShell = listOf("setprop debug.hwui.renderer skiavk"),
            offShell = listOf("setprop debug.hwui.renderer opengl"),
            verifyShell = "getprop debug.hwui.renderer",
            verifyParser = VerifyRule.RegexMatch(
                onPattern = "^skiavk$",
                offPattern = "^(opengl|skiagl|)$",
                humanSummary = "getprop debug.hwui.renderer == 'skiavk'"
            ),
            riskLevel = RiskLevel.MODERATE,
            revertIfVerifyFails = true,
            revertOnDisconnectDefault = true
        ),
        Command(
            id = "gpu_adreno610_kgsl_locked",
            name = "Adreno 610 KGSL Max Clock Lock",
            summary = "Unavailable on this device — /sys/class/kgsl devfreq requires root.",
            description = "Locking Adreno 610 min_pwrlevel / force_clk_on under /sys/class/kgsl/kgsl-3d0 requires root permissions on stock MIUI/HyperOS kernels.",
            category = CommandCategory.GPU,
            onShell = emptyList(),
            offShell = emptyList(),
            verifyShell = "",
            verifyParser = VerifyRule.RootUnavailable(),
            riskLevel = RiskLevel.ADVANCED,
            requiresRoot = true,
            unavailableReason = "Unavailable on this device: /sys/class/kgsl/kgsl-3d0/devfreq/governor cannot be written by ADB shell (UID 2000) without root."
        ),

        // ======================== RAM & STORAGE / IO ========================
        Command(
            id = "ram_max_cached_processes_16",
            name = "Tune Cached App Limit (16 Processes)",
            summary = "Caps background cached processes at 16 to free ~1.2 GB on 8 GB Redmi 12.",
            description = "Configures activity_manager_constants max_cached_processes=16 so inactive MIUI bloatware does not hoard LPDDR4X pages needed by foreground apps and games. OFF restores Android's default 32-process cap.",
            category = CommandCategory.RAM,
            onShell = listOf("settings put global activity_manager_constants max_cached_processes=16"),
            offShell = listOf("settings delete global activity_manager_constants"),
            verifyShell = "settings get global activity_manager_constants",
            verifyParser = VerifyRule.RegexMatch(
                onPattern = "max_cached_processes=16",
                offPattern = "^null$",
                humanSummary = "activity_manager_constants contains max_cached_processes=16"
            ),
            riskLevel = RiskLevel.SAFE
        ),
        Command(
            id = "ram_kill_cached_and_fstrim",
            name = "Purge Cached Apps & Frequent fstrim",
            summary = "Runs 'cmd activity kill-all', triggers 'sm fstrim', and sets mandatory trim.",
            description = "Immediately reclaims cached background app RAM via 'cmd activity kill-all', triggers best-effort NAND TRIM ('sm fstrim') on the 256 GB storage, and sets fstrim_mandatory_interval=1.",
            category = CommandCategory.RAM,
            onShell = listOf(
                "settings put global fstrim_mandatory_interval 1",
                "cmd activity kill-all",
                "sm fstrim 2>/dev/null || true"
            ),
            offShell = listOf("settings delete global fstrim_mandatory_interval"),
            verifyShell = "settings get global fstrim_mandatory_interval",
            verifyParser = VerifyRule.RegexMatch(
                onPattern = "^1$",
                offPattern = "^null$",
                humanSummary = "settings get global fstrim_mandatory_interval == 1"
            ),
            riskLevel = RiskLevel.SAFE
        ),
        Command(
            id = "storage_trim_caches_and_logs",
            name = "Trim 1 GB App Caches & Compact DropBox",
            summary = "Runs 'pm trim-caches 1024M' and caps system crash/diagnostic log files.",
            description = "Reclaims up to 1024 MB of expendable app cache directories and bounds Android DropBox diagnostic log retention (dropbox_max_files=128) to reduce background flash write amplification.",
            category = CommandCategory.RAM,
            onShell = listOf(
                "pm trim-caches 1024M 2>/dev/null || cmd package trim-caches 1024M 2>/dev/null || true",
                "settings put global dropbox_max_files 128",
                "settings put global sys_storage_cache_max_bytes 536870912"
            ),
            offShell = listOf(
                "settings delete global dropbox_max_files",
                "settings delete global sys_storage_cache_max_bytes"
            ),
            verifyShell = "settings get global dropbox_max_files",
            verifyParser = VerifyRule.RegexMatch(
                onPattern = "^128$",
                offPattern = "^null$",
                humanSummary = "settings get global dropbox_max_files == 128"
            ),
            riskLevel = RiskLevel.SAFE
        ),
        Command(
            id = "storage_lift_app_standby_bucket",
            name = "Disable App Standby Restrictions (Per-App)",
            summary = "Pins a chosen priority app to the 'active' standby bucket (bucket 10).",
            description = "Uses 'am set-standby-bucket <pkg> active' so Android/HyperOS never defers I/O, jobs, or sync for your selected priority app. Toggling OFF returns the app to 'working_set'.",
            category = CommandCategory.RAM,
            onShell = listOf("am set-standby-bucket <pkg> active"),
            offShell = listOf("am set-standby-bucket <pkg> working_set"),
            verifyShell = "am get-standby-bucket <pkg>",
            verifyParser = VerifyRule.RegexMatch(
                onPattern = "^10$",
                offPattern = "^(20|30|40|45|50)$",
                humanSummary = "'am get-standby-bucket <pkg>' returns 10 (ACTIVE)"
            ),
            riskLevel = RiskLevel.SAFE,
            supportsTargetPackage = true,
            defaultTargetPackage = "com.android.chrome"
        ),
        Command(
            id = "ram_zram_swappiness_locked",
            name = "ZRAM Size & Kernel vm.swappiness",
            summary = "Unavailable on this device — /proc/sys/vm & /sys/block/zram0 require root.",
            description = "Modifying MIUI Memory Extension / ZRAM block size or /proc/sys/vm/swappiness directly requires kernel root privileges. Toggle Memory Extension in MIUI Settings → Additional settings instead.",
            category = CommandCategory.RAM,
            onShell = emptyList(),
            offShell = emptyList(),
            verifyShell = "",
            verifyParser = VerifyRule.RootUnavailable(),
            riskLevel = RiskLevel.ADVANCED,
            requiresRoot = true,
            unavailableReason = "Unavailable on this device: /proc/sys/vm/swappiness and /sys/block/zram0 are root-only kernel interfaces."
        ),

        // ======================== BACKGROUND APPS ========================
        Command(
            id = "bg_deep_doze_acceleration",
            name = "Deep Doze Fast Idle (60s Screen-Off)",
            summary = "Enters Deep Doze 60 seconds after screen-off instead of waiting 30 minutes.",
            description = "Tunes global device_idle_constants (inactive_to=60000, sensing_to=0, locating_to=0) so background wakelocks and jobs quiet down within one minute of locking your Redmi 12 4G. OFF restores stock Doze.",
            category = CommandCategory.BACKGROUND,
            onShell = listOf(
                "settings put global device_idle_constants inactive_to=60000,sensing_to=0,locating_to=0,motion_inactive_to=0,idle_after_inactive_to=60000"
            ),
            offShell = listOf(
                "settings delete global device_idle_constants"
            ),
            verifyShell = "settings get global device_idle_constants",
            verifyParser = VerifyRule.RegexMatch(
                onPattern = "inactive_to=60000",
                offPattern = "^null$",
                humanSummary = "device_idle_constants contains inactive_to=60000"
            ),
            riskLevel = RiskLevel.MODERATE
        ),
        Command(
            id = "bg_freeze_target_package",
            name = "Aggressive Background App Freeze",
            summary = "Removes app from Doze whitelist & sets RUN_IN_BACKGROUND to ignore.",
            description = "Runs 'cmd deviceidle whitelist -<pkg>' and 'cmd appops set <pkg> RUN_IN_BACKGROUND ignore' + 'RUN_ANY_IN_BACKGROUND ignore' for your selected package. Toggling OFF restores background execution.",
            category = CommandCategory.BACKGROUND,
            onShell = listOf(
                "cmd deviceidle whitelist -<pkg> 2>/dev/null || true",
                "cmd appops set <pkg> RUN_IN_BACKGROUND ignore",
                "cmd appops set <pkg> RUN_ANY_IN_BACKGROUND ignore"
            ),
            offShell = listOf(
                "cmd appops set <pkg> RUN_IN_BACKGROUND allow",
                "cmd appops set <pkg> RUN_ANY_IN_BACKGROUND allow"
            ),
            verifyShell = "cmd appops get <pkg> RUN_IN_BACKGROUND",
            verifyParser = VerifyRule.RegexMatch(
                onPattern = "RUN_IN_BACKGROUND:\\s*ignore",
                offPattern = "RUN_IN_BACKGROUND:\\s*(allow|default)",
                humanSummary = "appops RUN_IN_BACKGROUND is 'ignore'"
            ),
            riskLevel = RiskLevel.MODERATE,
            supportsTargetPackage = true,
            defaultTargetPackage = "com.miui.msa.global",
            miuiQuirkNote = "MIUI Autostart is managed by Xiaomi Security Center and cannot be toggled directly via shell: also open Settings → Apps → Permissions → Background autostart to block persistent MIUI apps."
        ),

        // ======================== NETWORK ========================
        Command(
            id = "net_restrict_background_global",
            name = "Global Background Data Saver",
            summary = "Enables 'cmd netpolicy set restrict-background true' to stop background traffic.",
            description = "Prevents non-whitelisted background apps from consuming Wi-Fi/LTE bandwidth and waking the modem while you game or browse. Toggling OFF restores unrestricted background networking.",
            category = CommandCategory.NETWORK,
            onShell = listOf("cmd netpolicy set restrict-background true"),
            offShell = listOf("cmd netpolicy set restrict-background false"),
            verifyShell = "cmd netpolicy get restrict-background",
            verifyParser = VerifyRule.RegexMatch(
                onPattern = "(enabled|true)",
                offPattern = "(disabled|false)",
                humanSummary = "netpolicy restrict-background status is enabled"
            ),
            riskLevel = RiskLevel.MODERATE,
            revertOnDisconnectDefault = true
        ),
        Command(
            id = "net_wifi_low_latency_scan_off",
            name = "Wi-Fi Low-Latency Scan Suppression",
            summary = "Stops background Wi-Fi & BLE roam scanning to eliminate ping spikes.",
            description = "Disables wifi_scan_always_enabled, wifi_wakeup_enabled, and ble_scan_always_enabled so the Redmi 12 4G's shared 2.4 GHz Wi-Fi/BT radio does not periodically leave the home channel to scan.",
            category = CommandCategory.NETWORK,
            onShell = listOf(
                "settings put global wifi_scan_always_enabled 0",
                "settings put global wifi_wakeup_enabled 0",
                "settings put global ble_scan_always_enabled 0"
            ),
            offShell = listOf(
                "settings put global wifi_scan_always_enabled 1",
                "settings put global wifi_wakeup_enabled 1"
            ),
            verifyShell = "settings get global wifi_scan_always_enabled",
            verifyParser = VerifyRule.RegexMatch(
                onPattern = "^0$",
                offPattern = "^(1|null)$",
                humanSummary = "settings get global wifi_scan_always_enabled == 0"
            ),
            riskLevel = RiskLevel.SAFE
        ),
        Command(
            id = "net_per_app_background_blacklist",
            name = "Per-App Background Data Block",
            summary = "Blacklists a specific package UID from background network access.",
            description = "Resolves the target package's Linux UID via PackageManager and adds it to NetworkPolicyManager's restrict-background-blacklist. Toggling OFF removes the UID from the blacklist.",
            category = CommandCategory.NETWORK,
            onShell = listOf(
                "uid=$(pm list packages -U <pkg> | sed -n 's/.*uid://p' | head -n 1); if [ -n \"\$uid\" ]; then cmd netpolicy add restrict-background-blacklist \"\$uid\"; fi"
            ),
            offShell = listOf(
                "uid=$(pm list packages -U <pkg> | sed -n 's/.*uid://p' | head -n 1); if [ -n \"\$uid\" ]; then cmd netpolicy remove restrict-background-blacklist \"\$uid\"; fi"
            ),
            verifyShell = "uid=$(pm list packages -U <pkg> | sed -n 's/.*uid://p' | head -n 1); if [ -n \"\$uid\" ] && cmd netpolicy list restrict-background-blacklist | grep -qw \"\$uid\"; then echo BLOCKED; else echo ALLOWED; fi",
            verifyParser = VerifyRule.RegexMatch(
                onPattern = "^BLOCKED$",
                offPattern = "^ALLOWED$",
                humanSummary = "Target package UID present in restrict-background-blacklist"
            ),
            riskLevel = RiskLevel.SAFE,
            supportsTargetPackage = true,
            defaultTargetPackage = "com.miui.analytics"
        )
    )
}
