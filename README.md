# Boost12 — On-Device Wireless ADB Performance Utility for Redmi 12 4G

**Boost12** (`v1.0`) is a modern, minimal, no-root Android utility built from the ground up in **100% Kotlin + Jetpack Compose (Material 3)** for the **Xiaomi Redmi 12 4G (`22041219NY` family · Qualcomm Snapdragon 685 `SM6225` · Adreno 610 · 8 GB RAM / 256 GB Storage · 90 Hz Display · MIUI / HyperOS)**.

Boost12 connects directly to the phone's own `adbd` daemon over Wi-Fi using Android 11+ **Wireless Debugging** (`TLSv1.3` + `SPAKE2+` 6-digit pairing + `mDNS` discovery) — **no root, no Magisk, no PC, and no external Shizuku dependency required**. Every tuning card executes safe user-space shell commands (`settings`, `cmd`, `dumpsys`, `wm`, `pm`, `sm`) and **verifies in real time** that the change actually took effect before allowing its Material 3 toggle switch to turn ON or OFF.

---

## Key Capabilities

- **Embedded Pure-Kotlin AOSP Wireless ADB Client**
  - Discovers the device's own `_adb-tls-pairing._tcp` and `_adb-tls-connect._tcp` endpoints via Android `NsdManager` mDNS (filtered strictly to local network interfaces).
  - Implements BoringSSL-compatible **SPAKE2 over Edwards25519** + RFC 5705 TLSv1.3 Exported Keying Material (`"adb-label\0"`, 64 bytes) + `HKDF-SHA256` + `AES-128-GCM` `PeerInfo` exchange.
  - Generates a 2048-bit RSA keypair and self-signed X.509v3 certificate via BouncyCastle and persists credentials in **`EncryptedSharedPreferences`** (`AES256_SIV` / `AES256_GCM`) for one-tap reconnection.
  - Manual fallback entry (`IP:port` + 6-digit pairing code, then `IP:connectPort`), plus **exponential backoff auto-reconnect** (`1s → 2s → 4s → 8s → 16s → 30s`) when sleep/Doze interrupts the socket.
- **Strict Real-Time Verification Command Engine**
  - Sequential `adb shell` execution with per-command timeouts (`8s`), automatic `1×` retry on transient failure, and deterministic exit-code capture.
  - **Honest toggles**: A switch transitions to `ON` or `OFF` *only* when `verifyShell` matches the command's `VerifyRule`. If MIUI PowerKeeper or SELinux blocks a hint, the switch stays in its real state and displays a subtle inline warning (`"Didn't apply — tap to inspect raw output"`).
  - **Full Sync on Open**: Automatically runs all `verifyShell` checks whenever the ADB session connects so UI switches always reflect real hardware/OS state.
  - **Hard-Blocked Destructive Commands**: `CommandSafetyGuard` inspects every shell chain (`;`, `&&`, `||`, `|`, `$()`, `sh -c`) and hard-blocks `rm`, `dd`, `mkfs`, `wipe`, `master_clear` / factory reset, bootloader/EDL reboots, `pm uninstall`, `su`/`magisk`, and writes to `/system`, `/vendor`, `/data/system`, or `/proc/sys`.
- **Persistence, Change History & Debugger Mode**
  - **Room Database** (`boost12.db`) persists verified command states, last raw outputs, custom user ON/OFF toggles, per-command `Revert on disconnect` flags, and a chronological **Change History** log with **one-tap Revert All**.
  - **ADB Verbose Debugger Mode** (enabled by default in the `v1.0` debug APK and toggleable in Settings) streams live mDNS, SPAKE2+, TLSv1.3 packet, and shell exit-code traces.

---

## Architecture

```text
app/src/main/java/com/boost12/rd12/
├── data/
│   ├── adb/
│   │   ├── crypto/        # Spake25519, PairingAuthCipher (HKDF+AES-GCM), AndroidPubkeyCodec, AdbCredentialStore
│   │   ├── mdns/          # AdbMdnsDiscovery (_adb-tls-pairing._tcp & _adb-tls-connect._tcp)
│   │   ├── protocol/      # AdbProtocol (24B framing), SslContextFactory (Conscrypt TLSv1.3), AdbPairingClient, AdbConnectionSession
│   │   ├── telemetry/     # Defensive DumpsysTelemetryParser (battery %, °C, thermal status, Hz)
│   │   └── AdbRepositoryImpl.kt
│   ├── catalog/           # DefaultCommandCatalog (22 Redmi 12 4G / SM6225 entries)
│   ├── local/             # Room Boost12Database, DAOs, and Entities
│   └── repository/        # CommandRepositoryImpl
├── di/                    # Hilt AppModule
├── domain/
│   ├── engine/            # CommandEngine (retry, timeout, verification gate, auto-rollback)
│   ├── model/             # DeviceProfile, Command, VerifyRule, AdbConnectionState, DeviceTelemetry
│   ├── parser/            # VerifyOutputParser
│   ├── repository/        # AdbRepository & CommandRepository interfaces
│   └── safety/            # CommandSafetyGuard (prefix & compound-chain destructive command blocker)
└── ui/
    ├── components/        # TopStatusHeader, DeviceProfileCard, CommandCard, CustomCommandCard, BottomSheets
    ├── screens/           # MainScreen, HistoryScreen, SettingsScreen
    ├── theme/             # Dark-first Obsidian + Electric Mint (#2EE59D) Material 3 theme (16dp shapes)
    └── Boost12ViewModel.kt
```

---

## Command Catalog & Self-Review Audit (ON / OFF / Verify Matrix)

Every non-root command in `DefaultCommandCatalog` has been audited to ensure an exact `ON` command list, a reversible `OFF` counterpart, and a real-time `verifyShell` rule:

| ID | Category | ON Counterpart | OFF Counterpart (Revert) | Real-Time Verify Rule |
|---|---|---|---|---|
| `anim_scale_zero` | Display | `window` / `transition` / `animator` scales → `0.0` | All 3 scales → `1.0` | `AllScalesEqual(0.0f)` across 3 `settings get global` lines |
| `anim_scale_half` | Display | All 3 animation scales → `0.5` | All 3 scales → `1.0` | `AllScalesEqual(0.5f)` across 3 `settings get global` lines |
| `display_force_90hz` | Display | `user_refresh_rate 90`, `miui_refresh_rate 90`, `min/peak_refresh_rate 90.0` | `user/miui_refresh_rate 60`, `min_refresh_rate 60.0`, `peak 90.0` | `RefreshRateHzRule(90)` across MIUI & AOSP keys |
| `display_miui_power_mode` | Display | `settings put system power_mode high` | `settings put system power_mode middle` | `settings get system power_mode` == `high` vs `middle`/`null` |
| `display_miui_game_turbo` | Display | `gb_boosting 1`, `perf_shielder_GESTURE_OPT 1` | `gb_boosting 0`, `perf_shielder_GESTURE_OPT 0` | `settings get secure gb_boosting` == `1` |
| `display_per_app_game_90hz` | Display | `cmd game mode performance <pkg>`, `speed_mode 1` | `cmd game mode standard <pkg>`, `speed_mode 0` | `settings get secure speed_mode` == `1` |
| `display_wm_density_440` | Display | `wm density 440` | `wm density reset` | `wm density` matches `Override density: 440` vs stock physical |
| `cpu_fixed_performance_mode` | CPU | `cmd power set-fixed-performance-mode-enabled true` | `cmd power set-fixed-performance-mode-enabled false` | `dumpsys power` `mFixedPerformanceModeEnabled=true` |
| `cpu_disable_adaptive_power_saver` | CPU | `cmd power set-adaptive-power-saver-enabled false`, `low_power 0` | `cmd power set-adaptive-power-saver-enabled true` | `dumpsys power` `mAdaptivePowerSaverEnabled=false` |
| `cpu_relax_background_cpu_quota` | CPU | `power_check_max_cpu_1/2` → `75` | `settings delete global power_check_max_cpu_1/2` | `settings get global power_check_max_cpu_1` == `75` |
| `gpu_angle_vulkan_driver` | GPU | `settings put global angle_gl_driver_all_angle 1` | `settings put global angle_gl_driver_all_angle 0` | `settings get global angle_gl_driver_all_angle` == `1` |
| `gpu_disable_hw_overlays` | GPU | `settings put global disable_hw_overlays 1` | `settings put global disable_hw_overlays 0` | `settings get global disable_hw_overlays` == `1` |
| `gpu_skiavk_hwui_hint` | GPU | `setprop debug.hwui.renderer skiavk` | `setprop debug.hwui.renderer opengl` | `getprop debug.hwui.renderer` == `skiavk` (auto-reverts if blocked) |
| `alice_full_profile_bundle` | RAM | All 7 `#Alice` `device_config` & phantom-process commands | Resets all 7 `#Alice` `device_config` & `settings` overrides | Compound `device_config` + `settings` check across all 7 keys |
| `alice_lock_device_config_sync` | RAM | `device_config set_sync_disabled_for_tests persistent` | `device_config set_sync_disabled_for_tests none` | `device_config get_sync_disabled_for_tests` == `persistent` |
| `alice_device_config_max_cached_512` | RAM | `device_config put activity_manager max_cached_processes 512` | `device_config delete activity_manager max_cached_processes` | `device_config get activity_manager max_cached_processes` == `512` |
| `ram_device_config_max_cached_108096` | RAM | `device_config put activity_manager max_cached_processes 108096` | `device_config delete activity_manager max_cached_processes` | `device_config get activity_manager max_cached_processes` == `108096` |
| `alice_max_phantom_processes_unlimited` | RAM | `device_config put activity_manager max_phantom_processes 2147483647` | `device_config delete activity_manager max_phantom_processes` | `device_config get activity_manager max_phantom_processes` == `2147483647` |
| `alice_max_empty_time_12h` | RAM | `device_config put activity_manager max_empty_time_millis 43200000` | `device_config delete activity_manager max_empty_time_millis` | `device_config get activity_manager max_empty_time_millis` == `43200000` |
| `alice_enable_memory_compaction` | RAM | `device_config put activity_manager use_compaction true` | `device_config delete activity_manager use_compaction` | `device_config get activity_manager use_compaction` == `true` |
| `ram_disable_miui_ram_expand` | RAM | `settings put global ram_expand_size 0` | `settings delete global ram_expand_size` | `settings get global ram_expand_size` == `0` |
| `ram_enable_zram_global` | RAM | `settings put global zram_enabled 1` | `settings delete global zram_enabled` | `settings get global zram_enabled` == `1` |
| `ram_max_cached_processes_16` | RAM | `activity_manager_constants max_cached_processes=16` | `settings delete global activity_manager_constants` | `settings get global activity_manager_constants` matches `16` |
| `ram_kill_cached_and_fstrim` | RAM | `fstrim_mandatory_interval 1`, `cmd activity kill-all`, `sm fstrim` | `settings delete global fstrim_mandatory_interval` | `settings get global fstrim_mandatory_interval` == `1` |
| `storage_trim_caches_and_logs` | RAM | `pm trim-caches 1024M`, `dropbox_max_files 128`, `sys_storage_cache_max_bytes` | `settings delete global dropbox_max_files` & cache cap | `settings get global dropbox_max_files` == `128` |
| `storage_lift_app_standby_bucket` | RAM | `am set-standby-bucket <pkg> active` | `am set-standby-bucket <pkg> working_set` | `am get-standby-bucket <pkg>` == `10` (`ACTIVE`) |
| `bg_cached_apps_freezer_enabled` | Background | `settings put global cached_apps_freezer enabled` | `settings delete global cached_apps_freezer` | `settings get global cached_apps_freezer` == `enabled` |
| `alice_native_boot_use_freezer` | Background | `device_config put activity_manager_native_boot use_freezer true` | `device_config delete activity_manager_native_boot use_freezer` | `device_config get activity_manager_native_boot use_freezer` == `true` |
| `alice_disable_phantom_proc_monitor` | Background | `settings put global settings_enable_monitor_phantom_procs false` | `settings put global settings_enable_monitor_phantom_procs true` | `settings get global settings_enable_monitor_phantom_procs` == `false` |
| `bg_app_standby_enabled` | Background | `settings put global app_standby_enabled 1` | `settings delete global app_standby_enabled` | `settings get global app_standby_enabled` == `1` |
| `bg_app_restriction_enabled` | Background | `settings put global app_restriction_enabled true` | `settings delete global app_restriction_enabled` | `settings get global app_restriction_enabled` == `true` |
| `bg_process_limit_4` | Background | `settings put global background_process_limit 4` | `settings delete global background_process_limit` | `settings get global background_process_limit` == `4` |
| `bg_disable_intelligent_sleep_mode` | Background | `settings put system intelligent_sleep_mode 0` | `settings delete system intelligent_sleep_mode` | `settings get system intelligent_sleep_mode` == `0` |
| `bg_deep_doze_acceleration` | Background | `device_idle_constants inactive_to=60000,...` | `settings delete global device_idle_constants` | `settings get global device_idle_constants` contains `inactive_to=60000` |
| `bg_freeze_target_package` | Background | `deviceidle whitelist -<pkg>`, `appops RUN_IN_BACKGROUND ignore` | `appops RUN_IN_BACKGROUND allow` & `RUN_ANY_IN_BACKGROUND allow` | `cmd appops get <pkg> RUN_IN_BACKGROUND` matches `ignore` |
| `net_restrict_background_global` | Network | `cmd netpolicy set restrict-background true` | `cmd netpolicy set restrict-background false` | `cmd netpolicy get restrict-background` matches `enabled`/`true` |
| `net_wifi_low_latency_scan_off` | Network | `wifi_scan_always_enabled 0`, `wifi_wakeup_enabled 0`, `ble_scan_always_enabled 0` | `wifi_scan_always_enabled 1`, `wifi_wakeup_enabled 1` | `settings get global wifi_scan_always_enabled` == `0` |
| `net_per_app_background_blacklist` | Network | `cmd netpolicy add restrict-background-blacklist <uid>` | `cmd netpolicy remove restrict-background-blacklist <uid>` | `cmd netpolicy list restrict-background-blacklist` contains `<uid>` |

---

## Honest Limitations: What Cannot Be Applied or Verified Without Root on Snapdragon 685 / HyperOS

Boost12 **never fakes toggle state**. The following items cannot be modified or directly verified from `adb shell` (`UID 2000`) without `root` (`UID 0`) on Redmi 12 4G (MIUI 14 / HyperOS Android 13–15):

1. **CPU `cpufreq` Scaling Governors & `mi_thermald` (`cpu_root_governor_thermal_locked`)** — Greyed out as *Unavailable on this device*. `/sys/devices/system/cpu/cpufreq/policy*/scaling_governor` and `/vendor/etc/thermal-engine.conf` are owned by `root:system` and blocked by SELinux `shell` domain policy.
2. **Adreno 610 KGSL GPU Clock / Bus Floors (`gpu_adreno610_kgsl_locked`)** — Greyed out as *Unavailable on this device*. `/sys/class/kgsl/kgsl-3d0/devfreq/*` nodes cannot be written or unlocked without root.
3. **Kernel ZRAM Size & `vm.swappiness` (`ram_zram_swappiness_locked`)** — Greyed out as *Unavailable on this device*. `/sys/block/zram0/disksize` and `/proc/sys/vm/swappiness` require `CAP_SYS_ADMIN` (`UID 0`).
4. **Block-Layer NAND `fstrim` Completion Byte Counts & `power_profile.xml`** — While `settings put global fstrim_mandatory_interval 1` is persisted and verified via `settings get`, reading kernel F2FS discard counters (`/sys/fs/f2fs/*/discard_stat` or `dmesg`) requires root (`dmesg_restrict=1`), and some HyperOS builds restrict direct `sm fstrim` invocation to `vold`.
5. **MIUI Security Center Autostart Database** — Xiaomi's proprietary Autostart toggle lives inside `/data/data/com.miui.securitycenter` (inaccessible without root). Boost12 applies and verifies standard Android `appops RUN_IN_BACKGROUND ignore`, and displays direct guidance to disable MIUI Background Autostart in `Settings → Apps → Permissions → Background autostart`.

*(Optional fallback note: Users who already run wireless `adbd` on port `5555` can also connect directly via the **Connect Session** tab without re-pairing.)*

---

## Building & Running Tests

### Prerequisites
- **JDK 17** (Temurin 17 recommended)
- **Android Studio Ladybug / Meerkat** or Android SDK (`platforms;android-35`, `build-tools;35.0.0`)

### Commands

```bash
# Run all unit tests + Robolectric Compose UI smoke tests
./gradlew testDebugUnitTest

# Build Debug APK (v1.0, isDebuggable = true, ADB Verbose Debugger Mode ON by default)
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk

# Build Release APK (uses keystore.properties if present, or debug signing fallback placeholder)
cp keystore.properties.example keystore.properties
./gradlew assembleRelease
```

### Pairing on Redmi 12 4G (No PC Needed)
1. Connect your Redmi 12 4G to Wi-Fi.
2. Enable **Developer Options** (tap *MIUI / HyperOS version* 7 times in *About phone*).
3. In **Developer Options**, enable **USB debugging**, **USB debugging (Security settings)** *(required on MIUI/HyperOS so `adb shell` can modify `settings` / `wm` / `appops`!)*, and **Wireless debugging**.
4. Open **Boost12**, tap **Pair Wireless Debugging**, and open **Developer Options → Wireless debugging** in **Split-Screen**.
5. Tap **Pair device with pairing code** — Boost12 auto-discovers the pairing port via mDNS (`_adb-tls-pairing._tcp`); enter the 6-digit code and tap **Pair with SPAKE2+**.
6. Set Boost12's MIUI Battery Saver policy to **No restrictions** (via the in-app **Whitelist App** button) so HyperOS keeps your ADB connection alive.
