package com.yushyam.savesync.engine

import android.content.Context
import com.yushyam.savesync.data.model.AppSyncProfile
import com.yushyam.savesync.data.model.AppSyncStatus
import com.yushyam.savesync.data.model.DefaultExcludes
import com.yushyam.savesync.data.model.SnapshotItem
import com.yushyam.savesync.data.model.SyncManifest
import com.yushyam.savesync.data.model.WebDavConfig
import com.yushyam.savesync.data.shell.ShellManager
import com.yushyam.savesync.data.storage.WebDavClient
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SyncCoordinator(
    private val context: Context,
    private val shell: ShellManager,
    private val webDavConfig: WebDavConfig,
    private val deviceName: String = "Android_Device"
) {

    private val webDav = WebDavClient(webDavConfig)

    fun checkAppStatus(profile: AppSyncProfile): AppSyncStatus {
        val remoteManifest = webDav.getRemoteManifest(profile.packageName) ?: return AppSyncStatus.NO_REMOTE
        val localLedger = getLocalLedger(profile.packageName) ?: return AppSyncStatus.NEED_PULL

        val remoteTime = remoteManifest.updated_at_epoch_ms
        val localTime = localLedger.updated_at_epoch_ms

        val localMtime = getLocalDirMtimeMs(profile.packageName)
        val localHasNewer = localMtime > (localTime + 2000)

        return when {
            remoteManifest.sha256 == localLedger.sha256 && !localHasNewer -> AppSyncStatus.UP_TO_DATE
            remoteTime > localTime && !localHasNewer -> AppSyncStatus.NEED_PULL
            localHasNewer && remoteTime <= localTime -> AppSyncStatus.NEED_PUSH
            localHasNewer && remoteTime > localTime -> AppSyncStatus.CONFLICT
            else -> AppSyncStatus.UP_TO_DATE
        }
    }

    // 雙向同步：依據狀態決定上傳或下載
    fun autoSyncApp(profile: AppSyncProfile, maxKeepSnapshots: Int = 3): Pair<Boolean, String> {
        val st = checkAppStatus(profile)
        return when (st) {
            AppSyncStatus.NEED_PULL -> {
                val ok = pullApp(profile, maxKeepSnapshots)
                Pair(ok, if (ok) "已下載雲端存檔" else "下載失敗")
            }
            AppSyncStatus.NEED_PUSH, AppSyncStatus.NO_REMOTE -> {
                val ok = pushApp(profile, maxKeepSnapshots)
                Pair(ok, if (ok) "已上傳雲端" else "上傳失敗")
            }
            AppSyncStatus.UP_TO_DATE -> {
                Pair(true, "已是最新")
            }
            AppSyncStatus.CONFLICT -> {
                Pair(false, "檢測到存檔衝突，請點擊手動解決")
            }
            else -> {
                val ok = pushApp(profile, maxKeepSnapshots)
                Pair(ok, if (ok) "同步完成" else "同步失敗")
            }
        }
    }

    fun resolveExcludes(profile: AppSyncProfile): List<String> {
        val smart = DefaultExcludes.getExcludesFor(profile.packageName)
        return (profile.excludes + smart).distinct()
    }

    fun resolveAppDirectory(packageName: String): String {
        val sdcardData = "/sdcard/Android/data/$packageName"
        val checkSdcard = shell.exec("if [ -d '$sdcardData' ] && [ -r '$sdcardData' ]; then echo 'OK'; fi")
        if (checkSdcard.contains("OK")) {
            return sdcardData
        }

        val internalData = "/data/data/$packageName"
        val checkInternal = shell.exec("if [ -d '$internalData' ] && [ -r '$internalData' ]; then echo 'OK'; fi")
        if (checkInternal.contains("OK")) {
            return internalData
        }

        return sdcardData
    }

    private fun getSharedWorkDir(): File {
        val dir = context.getExternalFilesDir("sync_staging")
            ?: File("/sdcard/Android/data/${context.packageName}/files/sync_staging")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun buildRestoreCommand(targetDir: String, tarPath: String): String {
        return if (targetDir.startsWith("/sdcard/")) {
            "mkdir -p $targetDir && cd $targetDir && toybox tar -xzf $tarPath"
        } else {
            val uidGidStr = shell.exec("stat -c '%u %g' $targetDir")
            val parts = uidGidStr.split("\\s+".toRegex())
            val uid = parts.getOrNull(0) ?: ""
            val gid = parts.getOrNull(1) ?: uid
            val chownPart = if (uid.isNotBlank()) " && chown -R $uid:$gid $targetDir && restorecon -R $targetDir" else ""
            "mkdir -p $targetDir && cd $targetDir && toybox tar -xzf $tarPath$chownPart"
        }
    }

    /**
     * 強制以本地存檔覆蓋雲端 (解決衝突)
     * Force push local save to remote WebDAV to resolve conflict.
     */
    fun forcePushApp(profile: AppSyncProfile, maxKeepSnapshots: Int = 3): Pair<Boolean, String> {
        val ok = pushApp(profile, maxKeepSnapshots)
        return Pair(ok, if (ok) "已強制上傳並覆蓋雲端" else "強制上傳失敗")
    }

    /**
     * 強制以下載雲端存檔覆蓋本地 (解決衝突)
     * Force pull remote save to local storage to resolve conflict.
     */
    fun forcePullApp(profile: AppSyncProfile, maxKeepSnapshots: Int = 3): Pair<Boolean, String> {
        val ok = pullApp(profile, maxKeepSnapshots)
        return Pair(ok, if (ok) "已強制下載並覆蓋本地" else "強制下載失敗")
    }

    fun pushApp(profile: AppSyncProfile, maxKeepSnapshots: Int = 3): Boolean {
        shell.exec("am force-stop ${profile.packageName}")
        val targetDir = resolveAppDirectory(profile.packageName)

        val stagingDir = getSharedWorkDir()
        val stagingFile = File(stagingDir, "${profile.packageName}_push.tar.gz")
        if (stagingFile.exists()) stagingFile.delete()

        val effectiveExcludes = resolveExcludes(profile)
        val excludesStr = effectiveExcludes.joinToString(" ") { "--exclude='$it'" }

        // 細粒度選擇性打包 (Selective Archive according to relativePaths)
        val targets = if (profile.relativePaths.isEmpty()) {
            listOf(".")
        } else {
            profile.relativePaths.map { it.trim().trimEnd('/') }.filter { it.isNotBlank() }
        }
        val targetsArg = targets.joinToString(" ")

        // 檢查目標目錄是否存在以避免 tar 報錯 (Verify existing targets)
        val checkExisting = shell.exec("cd $targetDir && for d in $targetsArg; do if [ -e \"\$d\" ]; then echo \"\$d\"; fi; done")
        val validTargets = checkExisting.lines().map { it.trim() }.filter { it.isNotBlank() }
        val finalTargets = if (validTargets.isNotEmpty()) validTargets.joinToString(" ") else "."

        shell.exec("cd $targetDir && toybox tar -czf ${stagingFile.absolutePath} $excludesStr $finalTargets")

        if (!stagingFile.exists() || stagingFile.length() == 0L) {
            stagingFile.delete()
            return false
        }

        // 先於本地建立滾動快照 (直接複用已封裝檔案，避免重複消耗 CPU 與儲存壽命)
        createLocalRollbackSnapshot(profile, maxKeepSnapshots, existingArchive = stagingFile)

        val sha256 = calculateSha256(stagingFile)
        val nowMs = System.currentTimeMillis()
        val currentLedger = getLocalLedger(profile.packageName)
        val nextVersion = (currentLedger?.save_version ?: 0) + 1

        val manifest = SyncManifest(
            package_name = profile.packageName,
            device_name = deviceName,
            updated_at_epoch_ms = nowMs,
            save_version = nextVersion,
            sha256 = sha256,
            archive_size_bytes = stagingFile.length(),
            relative_paths = profile.relativePaths
        )

        val uploadOk = webDav.uploadArchive(profile.packageName, stagingFile)
        val manifestOk = webDav.putRemoteManifest(profile.packageName, manifest)
        stagingFile.delete()

        if (uploadOk && manifestOk) {
            saveLocalLedger(manifest)
            return true
        }
        return false
    }

    fun pullApp(profile: AppSyncProfile, maxKeepSnapshots: Int = 3): Boolean {
        val remoteManifest = webDav.getRemoteManifest(profile.packageName) ?: return false
        val stagingDir = getSharedWorkDir()
        val stagingFile = File(stagingDir, "${profile.packageName}_pull.tar.gz")
        if (stagingFile.exists()) stagingFile.delete()

        if (!webDav.downloadArchive(profile.packageName, stagingFile)) {
            stagingFile.delete()
            return false
        }

        if (!stagingFile.exists() || stagingFile.length() == 0L) {
            stagingFile.delete()
            return false
        }

        // 核心安全防護：在覆蓋本地存檔前，強制產生 1 份本地快照備份
        createLocalRollbackSnapshot(profile, maxKeepSnapshots)

        shell.exec("am force-stop ${profile.packageName}")

        val targetDir = resolveAppDirectory(profile.packageName)
        val restoreCmd = buildRestoreCommand(targetDir, stagingFile.absolutePath)
        shell.exec(restoreCmd)

        stagingFile.delete()

        saveLocalLedger(remoteManifest)
        return true
    }

    // 建立本地安全還原快照 (滾動保留最新指定份數)
    fun createLocalRollbackSnapshot(profile: AppSyncProfile, maxKeep: Int = 3, existingArchive: File? = null): Boolean {
        return try {
            val snapshotDir = File(context.filesDir, "snapshots/${profile.packageName}")
            if (!snapshotDir.exists()) snapshotDir.mkdirs()

            val now = System.currentTimeMillis()
            val destFile = File(snapshotDir, "snapshot_${now}.tar.gz")

            if (existingArchive != null && existingArchive.exists() && existingArchive.length() > 0L) {
                existingArchive.copyTo(destFile, overwrite = true)
                cleanOldSnapshots(snapshotDir, maxKeep = maxKeep)
                return true
            }

            val targetDir = resolveAppDirectory(profile.packageName)
            val stagingDir = getSharedWorkDir()
            val stagingSnap = File(stagingDir, "${profile.packageName}_snap_${now}.tar.gz")
            if (stagingSnap.exists()) stagingSnap.delete()

            val effectiveExcludes = resolveExcludes(profile)
            val excludesStr = effectiveExcludes.joinToString(" ") { "--exclude='$it'" }
            shell.exec("cd $targetDir && toybox tar -czf ${stagingSnap.absolutePath} $excludesStr .")

            if (stagingSnap.exists() && stagingSnap.length() > 0L) {
                stagingSnap.copyTo(destFile, overwrite = true)
                stagingSnap.delete()

                cleanOldSnapshots(snapshotDir, maxKeep = maxKeep)
                true
            } else {
                stagingSnap.delete()
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    // 取得指定應用的歷史快照清單
    fun listSnapshots(packageName: String): List<SnapshotItem> {
        val snapshotDir = File(context.filesDir, "snapshots/$packageName")
        if (!snapshotDir.exists()) return emptyList()

        val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault())
        val files = snapshotDir.listFiles { f -> f.isFile && f.name.startsWith("snapshot_") && f.name.endsWith(".tar.gz") }
            ?: return emptyList()

        return files.sortedByDescending { it.lastModified() }.map { f ->
            val sizeKb = f.length() / 1024.0
            val sizeStr = if (sizeKb >= 1024) "%.1f MB".format(sizeKb / 1024.0) else "%.1f KB".format(sizeKb)
            SnapshotItem(
                fileName = f.name,
                timestampMs = f.lastModified(),
                formattedDate = sdf.format(Date(f.lastModified())),
                sizeFormatted = sizeStr,
                filePath = f.absolutePath
            )
        }
    }

    // 將存檔直接還原至選定的歷史快照
    fun restoreSnapshot(profile: AppSyncProfile, snapshot: SnapshotItem): Boolean {
        val sourceFile = File(snapshot.filePath)
        if (!sourceFile.exists()) return false

        shell.exec("am force-stop ${profile.packageName}")
        val targetDir = resolveAppDirectory(profile.packageName)

        val stagingDir = getSharedWorkDir()
        val stagingFile = File(stagingDir, "${profile.packageName}_rollback.tar.gz")
        sourceFile.copyTo(stagingFile, overwrite = true)

        val restoreCmd = buildRestoreCommand(targetDir, stagingFile.absolutePath)
        shell.exec(restoreCmd)

        stagingFile.delete()
        return true
    }

    private fun cleanOldSnapshots(dir: File, maxKeep: Int) {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("snapshot_") && f.name.endsWith(".tar.gz") }
            ?: return
        if (files.size > maxKeep) {
            val sorted = files.sortedBy { it.lastModified() }
            val toDeleteCount = sorted.size - maxKeep
            for (i in 0 until toDeleteCount) {
                sorted[i].delete()
            }
        }
    }

    private fun getLocalDirMtimeMs(packageName: String): Long {
        val targetDir = resolveAppDirectory(packageName)
        val cmd = "find $targetDir -type f -exec stat -c '%Y' {} + 2>/dev/null | sort -nr | head -n 1"
        val out = shell.exec(cmd).trim()
        val newestSec = out.toLongOrNull()
        if (newestSec != null && newestSec > 0L) {
            return newestSec * 1000L
        }
        val dirOut = shell.exec("stat -c '%Y' $targetDir 2>/dev/null").trim()
        return (dirOut.toLongOrNull() ?: 0L) * 1000L
    }

    private fun getLocalLedger(packageName: String): SyncManifest? {
        val file = File(context.filesDir, "ledger_${packageName}.json")
        if (!file.exists()) return null
        return try {
            com.google.gson.Gson().fromJson(file.readText(), SyncManifest::class.java)
        } catch (e: Exception) {
            null
        }
    }

    private fun saveLocalLedger(manifest: SyncManifest) {
        val file = File(context.filesDir, "ledger_${manifest.package_name}.json")
        file.writeText(com.google.gson.Gson().toJson(manifest))
    }

    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
