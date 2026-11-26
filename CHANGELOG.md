# Changelog

All notable changes to **Boost12** are documented in this file.

## [1.0.0] - 2026-11-26

### Added
- **Embedded On-Device ADB-over-Wi-Fi Client (`TLSv1.3` + `SPAKE2+`)**:
  - Pure-Kotlin BoringSSL-compatible `Spake25519` key agreement over Edwards25519 + Conscrypt `TLSv1.3` Exported Keying Material (`adb-label\0`) + `HKDF-SHA256` + `AES-128-GCM` `PeerInfo` pairing.
  - Automatic mDNS service discovery (`_adb-tls-pairing._tcp` and `_adb-tls-connect._tcp`) scoped to the device's own local interfaces, with manual `IP:port` fallback.
  - Persistent RSA-2048 private key and self-signed X.509v3 certificate storage in `EncryptedSharedPreferences` for one-tap reconnection.
  - Exponential backoff auto-reconnect (`1s → 30s`), authorization revocation detection, and MIUI Battery Saver whitelist helper.
- **Verified Shell Command Engine & Safety Guard**:
  - Sequential `adb shell` execution with per-command timeouts, `1×` transient retry, deterministic exit-code extraction, and automatic rollback (`revertIfVerifyFails`) for unsupported properties.
  - Real-time `VerifyOutputParser` ensuring Material 3 switches only reflect verified hardware/OS state, with inline error banners and raw stdout/stderr inspection when a command fails to apply.
  - Hard-blocked destructive command filter (`CommandSafetyGuard`) preventing `rm`, `dd`, `mkfs`, `master_clear` / factory reset, `su`/`magisk`, `pm uninstall`, and `/system` or `/proc/sys` writes.
- **Redmi 12 4G (`22041219NY` / Snapdragon 685 `SM6225`) Default Catalog**:
  - 22 curated cards across **Display**, **CPU**, **GPU**, **RAM**, **Background**, and **Network**, including 0.0x / 0.5x animation scales, MIUI + AOSP 90 Hz refresh lock, MIUI performance power mode, Game Turbo flags, 440 DPI density preset (`wm density reset` revert), Power HAL fixed performance mode, ANGLE Vulkan driver, HW overlays toggle, Skia Vulkan HWUI hint, cached process tuning, `fstrim` & 1 GB cache trim, Deep Doze acceleration, per-app `appops` freeze, background data saver, Wi-Fi scan suppression, and 3 honest greyed-out root-only explanations.
  - Interactive **Custom ADB Shell Command** runner and persistent custom ON/OFF toggle builder.
- **Modern Minimal Material 3 UI & Debugger Mode**:
  - Dark-first Obsidian + Electric Mint (`#2EE59D`) Jetpack Compose interface with `16dp` cards, shimmer progress indicators, copyable shell detail bottom sheets, live `dumpsys` battery/temperature pill, Room-backed **Change History** with one-tap **Revert All**, **Settings** screen, and **ADB Verbose Debugger Mode** enabled by default in the `v1.0` debug APK.
- **Automated Test Suite & CI**:
  - JUnit4 unit tests covering `Spake25519`, `PairingAuthCipher`, `AdbProtocol`, `DumpsysTelemetryParser`, `CommandSafetyGuard`, `VerifyOutputParser`, `CommandEngine`, `DefaultCommandCatalog` integrity, plus Robolectric & AndroidX Compose UI toggle smoke tests.
