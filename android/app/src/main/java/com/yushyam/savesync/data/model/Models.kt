package com.yushyam.savesync.data.model

import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class AppSyncProfile(
    val id: String,
    val packageName: String,
    val displayName: String,
    var isEnabled: Boolean = true,
    var relativePaths: List<String> = listOf("databases/", "shared_prefs/", "files/"),
    val excludes: List<String> = DefaultExcludes.BASE,
    var customStoragePath: String = "",
    var forceStopBeforeRestore: Boolean = false,
    var profileType: String = "AUTO",
    var extraStoragePaths: List<String> = emptyList(),
    var isMirrorSync: Boolean = false
)

data class StorageInspection(
    val path: String,
    val fileCount: Int,
    val totalSizeBytes: Long,
    val formattedSize: String,
    val lastModifiedMs: Long,
    val formattedLastModified: String,
    val isValid: Boolean,
    val statusSummary: String
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
        "*.tmp",
        "*sync_staging*",
        "*UnityCache*",
        "*unity*cache*",
        "*com.yushyam.savesync*"
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
    val relative_paths: List<String> = emptyList(),
    val extra_paths: Map<String, String> = emptyMap(),
    val is_slotted: Boolean = false
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

data class SubpathInspection(
    val path: String,
    val exists: Boolean = false,
    val fileCount: Int = 0,
    val sizeFormatted: String = "",
    val isAccessible: Boolean = true,
    val statusText: String = "",
    val totalSizeBytes: Long = 0L
)

data class FileDetailItem(
    val name: String,
    val relativePath: String,
    val sizeFormatted: String
)

data class SubpathDefinition(
    val key: String,
    val titleZh: String,
    val descZh: String = ""
)

/**
 * 同步設定檔安全反序列化工具 (Defensive Codec for AppSyncProfile)
 *
 * 解決 Gson 反序列化舊版 JSON 至 Kotlin data class 時繞過預設建構子、
 * 導致新增非空欄位變成 null 進而觸發 NullPointerException 的跨版本相容問題。
 *
 * Parses legacy and current AppSyncProfile JSON arrays safely with explicit
 * fallbacks for newly introduced non-null fields.
 */
object AppSyncProfileCodec {

    private val DEFAULT_RELATIVE_PATHS = listOf("databases/", "shared_prefs/", "files/")

    fun parseList(json: String?): List<AppSyncProfile> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val root = JsonParser.parseString(json)
            if (!root.isJsonArray) return emptyList()
            root.asJsonArray.mapNotNull { elem ->
                if (!elem.isJsonObject) return@mapNotNull null
                parseItem(elem.asJsonObject)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseItem(obj: JsonObject): AppSyncProfile? {
        val packageName = obj.optString("packageName", "").trim()
        if (packageName.isEmpty()) return null

        val id = obj.optString("id", packageName).ifBlank { packageName }
        val displayName = obj.optString("displayName", packageName).ifBlank { packageName }
        val isEnabled = obj.optBoolean("isEnabled", true)
        val relativePaths = obj.optStringList("relativePaths", DEFAULT_RELATIVE_PATHS)
        val rawExcludes = obj.optStringList("excludes", emptyList())
        val mergedExcludes = (rawExcludes + DefaultExcludes.getExcludesFor(packageName)).distinct()
        val customStoragePath = obj.optString("customStoragePath", "")
        val forceStopBeforeRestore = obj.optBoolean("forceStopBeforeRestore", false)
        val profileType = obj.optString("profileType", "AUTO").ifBlank { "AUTO" }
        val extraStoragePaths = obj.optStringList("extraStoragePaths", emptyList())
        val isMirrorSync = obj.optBoolean("isMirrorSync", false)

        return AppSyncProfile(
            id = id,
            packageName = packageName,
            displayName = displayName,
            isEnabled = isEnabled,
            relativePaths = relativePaths,
            excludes = mergedExcludes,
            customStoragePath = customStoragePath,
            forceStopBeforeRestore = forceStopBeforeRestore,
            profileType = profileType,
            extraStoragePaths = extraStoragePaths,
            isMirrorSync = isMirrorSync
        )
    }

    private fun JsonObject.optString(key: String, default: String): String {
        val elem = get(key) ?: return default
        return if (elem.isJsonNull || !elem.isJsonPrimitive) default else elem.asString
    }

    private fun JsonObject.optBoolean(key: String, default: Boolean): Boolean {
        val elem = get(key) ?: return default
        return try {
            if (elem.isJsonNull || !elem.isJsonPrimitive) default else elem.asBoolean
        } catch (_: Exception) {
            default
        }
    }

    private fun JsonObject.optStringList(key: String, default: List<String>): List<String> {
        val elem = get(key) ?: return default
        if (elem.isJsonNull || !elem.isJsonArray) return default
        return elem.asJsonArray.mapNotNull { item ->
            if (item.isJsonNull || !item.isJsonPrimitive) null else item.asString
        }
    }
}
