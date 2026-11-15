package com.yushyam.savesync.data.model

data class AppSyncProfile(
    val id: String,
    val packageName: String,
    val displayName: String,
    var isEnabled: Boolean = true,
    var relativePaths: List<String> = listOf("databases/", "shared_prefs/", "files/"),
    val excludes: List<String> = DefaultExcludes.BASE
)

object DefaultExcludes {
    val BASE = listOf(
        "cache",
        "code_cache",
        "app_webview",
        "no_backup",
        "*shader*",
        "*dump*",
        "*log*",
        "*gpu*",
        "*nand/system*",
        "*.tmp"
    )

    fun getExcludesFor(packageName: String): List<String> = BASE
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
