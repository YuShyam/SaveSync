package com.yushyam.savesync.data.model

data class AppSyncProfile(
    val id: String,
    val packageName: String,
    val displayName: String,
    var isEnabled: Boolean = true,
    val relativePaths: List<String> = listOf("databases/", "shared_prefs/", "files/"),
    val excludes: List<String> = DefaultExcludes.BASE
)

object DefaultExcludes {
    val BASE = listOf("cache", "code_cache", "app_webview", "no_backup")

    fun getExcludesFor(packageName: String): List<String> {
        val list = BASE.toMutableList()
        val pkg = packageName.lowercase()
        when {
            // Nintendo Switch 模擬器 (Eden, Yuzu, Suyu, Uzuy 等)：排除 300MB+ 韌體 NAND、著色器快取與記錄檔
            pkg.contains("eden") || pkg.contains("yuzu") || pkg.contains("suyu") || pkg.contains("uzuy") -> {
                list.addAll(listOf("*nand/system*", "*shader*", "*dump*", "*gpu*", "*gpu_drivers*", "*log*"))
            }
            // 3DS 模擬器 (Citra)
            pkg.contains("citra") -> {
                list.addAll(listOf("*sysdata*", "*shaders*", "*dump*", "*log*"))
            }
            // PS2 模擬器 (AetherSX2, NetherSX2)
            pkg.contains("aethersx2") || pkg.contains("nethersx2") -> {
                list.addAll(listOf("*bios*", "*shaders*", "*covers*", "*snaps*"))
            }
            // GameCube / Wii (Dolphin)
            pkg.contains("dolphin") -> {
                list.addAll(listOf("*Dump*", "*Shaders*", "*Sys*", "*Wii/title/00000001*"))
            }
            // PSP (PPSSPP)
            pkg.contains("ppsspp") -> {
                list.addAll(listOf("*SYSTEM/CACHE*", "*SYSTEM/SHADERS*", "*TEXTURES*"))
            }
        }
        return list.distinct()
    }
}

data class SyncManifest(
    val schema_version: Int = 1,
    val package_name: String,
    val device_name: String,
    val updated_at_epoch_ms: Long,
    val save_version: Int,
    val sha256: String,
    val archive_size_bytes: Long,
    val relative_paths: List<String> = emptyList()
)

enum class AppSyncStatus(val labelZh: String) {
    UP_TO_DATE("最新"),
    NEED_PUSH("待上傳"),
    NEED_PULL("可下載"),
    CONFLICT("衝突"),
    NO_REMOTE("無備份"),
    UNCHECKED("未檢查"),
    SYNCING("同步中"),
    ERROR("失敗")
}

data class SnapshotItem(
    val fileName: String,
    val timestampMs: Long,
    val formattedDate: String,
    val sizeFormatted: String,
    val filePath: String
)

data class CandidateApp(
    val packageName: String,
    val displayName: String,
    val isExtension: Boolean = false
)

data class WebDavConfig(
    var url: String = "",
    var username: String = "",
    var password: String = ""
)
