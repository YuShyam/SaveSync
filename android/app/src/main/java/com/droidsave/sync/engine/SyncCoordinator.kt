package com.droidsave.sync.engine

import android.content.Context
import com.droidsave.sync.data.model.AppSyncProfile
import com.droidsave.sync.data.model.AppSyncStatus
import com.droidsave.sync.data.model.SnapshotItem
import com.droidsave.sync.data.model.SyncManifest
import com.droidsave.sync.data.model.WebDavConfig
import com.droidsave.sync.data.shell.ShellManager
import com.droidsave.sync.data.storage.WebDavClient
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
                Pair(false, "存檔衝突，請先建立快照")
            }
            else -> {
                val ok = pushApp(profile, maxKeepSnapshots)
                Pair(ok, if (ok) "同步完成" else "同步失敗")
            }
        }
    }

    fun pushApp(profile: AppSyncProfile, maxKeepSnapshots: Int = 3): Boolean {
        shell.exec("am force-stop ${profile.packageName}")
        val tmpTarPath = "/data/local/tmp/${profile.packageName}_app_sync.tar.gz"
        val excludes = profile.excludes.joinToString(" ") { "--exclude='$it'" }
        shell.exec("cd /data/data/${profile.packageName} && toybox tar -czf $tmpTarPath $excludes . && chmod 666 $tmpTarPath")

        val tmpTarFile = File(tmpTarPath)
        if (!tmpTarFile.exists() || tmpTarFile.length() == 0L) {
            shell.exec("rm -f $tmpTarPath")
            return false
        }

        // 先於本地建立滾動快照
        createLocalRollbackSnapshot(profile, maxKeepSnapshots)

        // 由 App 內部複製至專屬 cache，確保跨 UID 權限完整
        val localFile = File(context.cacheDir, "${profile.packageName}_push.tar.gz")
        try {
            tmpTarFile.copyTo(localFile, overwrite = true)
        } catch (e: Exception) {
            shell.exec("rm -f $tmpTarPath")
            return false
        } finally {
            shell.exec("rm -f $tmpTarPath")
        }

        if (!localFile.exists() || localFile.length() == 0L) return false

        val sha256 = calculateSha256(localFile)
        val nowMs = System.currentTimeMillis()
        val currentLedger = getLocalLedger(profile.packageName)
        val nextVersion = (currentLedger?.save_version ?: 0) + 1

        val manifest = SyncManifest(
            package_name = profile.packageName,
            device_name = deviceName,
            updated_at_epoch_ms = nowMs,
            save_version = nextVersion,
            sha256 = sha256,
            archive_size_bytes = localFile.length(),
            relative_paths = profile.relativePaths
        )

        val uploadOk = webDav.uploadArchive(profile.packageName, localFile)
        val manifestOk = webDav.putRemoteManifest(profile.packageName, manifest)
        localFile.delete()

        if (uploadOk && manifestOk) {
            saveLocalLedger(manifest)
            return true
        }
        return false
    }

    fun pullApp(profile: AppSyncProfile, maxKeepSnapshots: Int = 3): Boolean {
        val remoteManifest = webDav.getRemoteManifest(profile.packageName) ?: return false
        val localFile = File(context.cacheDir, "${profile.packageName}_pull.tar.gz")
        if (!webDav.downloadArchive(profile.packageName, localFile)) return false

        // 核心安全防護：在覆蓋本地存檔前，強制產生 1 份本地快照備份
        createLocalRollbackSnapshot(profile, maxKeepSnapshots)

        // 取得本地目標應用的 UID 與 GID
        val uidGidStr = shell.exec("stat -c '%u %g' /data/data/${profile.packageName}")
        val parts = uidGidStr.split("\\s+".toRegex())
        val uid = parts.getOrNull(0) ?: return false
        val gid = parts.getOrNull(1) ?: uid

        shell.exec("am force-stop ${profile.packageName}")

        val tmpTarPath = "/data/local/tmp/${profile.packageName}_restore.tar.gz"
        localFile.setReadable(true, false)
        shell.exec("cat ${localFile.absolutePath} > $tmpTarPath && chmod 666 $tmpTarPath")
        localFile.delete()

        val restoreCmd = "cd /data/data/${profile.packageName} && toybox tar -xzf $tmpTarPath && chown -R $uid:$gid /data/data/${profile.packageName} && restorecon -R /data/data/${profile.packageName} && rm -f $tmpTarPath"
        shell.exec(restoreCmd)

        saveLocalLedger(remoteManifest)
        return true
    }

    // 建立本地安全還原快照 (滾動保留最新指定份數)
    fun createLocalRollbackSnapshot(profile: AppSyncProfile, maxKeep: Int = 3): Boolean {
        return try {
            val snapshotDir = File(context.filesDir, "snapshots/${profile.packageName}")
            if (!snapshotDir.exists()) snapshotDir.mkdirs()

            val now = System.currentTimeMillis()
            val tmpSnapPath = "/data/local/tmp/${profile.packageName}_snap_${now}.tar.gz"
            val excludes = profile.excludes.joinToString(" ") { "--exclude='$it'" }
            shell.exec("cd /data/data/${profile.packageName} && toybox tar -czf $tmpSnapPath $excludes . && chmod 666 $tmpSnapPath")

            val tmpFile = File(tmpSnapPath)
            if (tmpFile.exists() && tmpFile.length() > 0L) {
                val destFile = File(snapshotDir, "snapshot_${now}.tar.gz")
                tmpFile.copyTo(destFile, overwrite = true)
                shell.exec("rm -f $tmpSnapPath")

                cleanOldSnapshots(snapshotDir, maxKeep = maxKeep)
                true
            } else {
                shell.exec("rm -f $tmpSnapPath")
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
        val uidGidStr = shell.exec("stat -c '%u %g' /data/data/${profile.packageName}")
        val parts = uidGidStr.split("\\s+".toRegex())
        val uid = parts.getOrNull(0) ?: return false
        val gid = parts.getOrNull(1) ?: uid

        val tmpTarPath = "/data/local/tmp/${profile.packageName}_rollback.tar.gz"
        sourceFile.setReadable(true, false)
        shell.exec("cat ${sourceFile.absolutePath} > $tmpTarPath && chmod 666 $tmpTarPath")

        val restoreCmd = "cd /data/data/${profile.packageName} && toybox tar -xzf $tmpTarPath && chown -R $uid:$gid /data/data/${profile.packageName} && restorecon -R /data/data/${profile.packageName} && rm -f $tmpTarPath"
        shell.exec(restoreCmd)
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
        val out = shell.exec("stat -c '%Y' /data/data/$packageName")
        return (out.toLongOrNull() ?: 0L) * 1000L
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
