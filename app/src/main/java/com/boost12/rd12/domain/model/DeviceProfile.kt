package com.boost12.rd12.domain.model

data class DeviceProfile(
    val appTitle: String = "Boost12",
    val modelName: String = "Redmi 12 4G",
    val modelFamily: String = "22041219NY family",
    val socName: String = "Qualcomm Snapdragon 685 (SM6225)",
    val gpuName: String = "Adreno 610 GPU",
    val ramSpec: String = "8 GB LPDDR4X",
    val storageSpec: String = "256 GB",
    val displaySpec: String = "90 Hz AdaptiveSync (50 / 60 / 90 Hz)",
    val osTarget: String = "MIUI / HyperOS (Android 13 / 14 / 15)",
    val executionChannel: String = "Wireless Debugging (TLSv1.3 + SPAKE2+, No Root)",
    val vendorQuirks: List<String> = listOf(
        "MIUI PowerKeeper may reset refresh rate & appops unless Boost12 is battery-whitelisted.",
        "Kernel cpufreq governors, /vendor thermal configs, and power_profile require root and are honestly marked Unavailable.",
        "All tuning runs strictly via shell settings, cmd, dumpsys, wm, pm, and sm commands."
    ),
    val liveAndroidRelease: String? = null,
    val liveRomVersion: String? = null,
    val liveHardwareModel: String? = null
)
