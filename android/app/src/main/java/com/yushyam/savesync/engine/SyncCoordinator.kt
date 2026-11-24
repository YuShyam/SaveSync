package com.yushyam.savesync.engine

import android.content.Context
import com.yushyam.savesync.data.model.AppSyncProfile
import com.yushyam.savesync.data.model.AppSyncStatus
import com.yushyam.savesync.data.model.DefaultExcludes
import com.yushyam.savesync.data.model.FileDetailItem
import com.yushyam.savesync.data.model.SnapshotItem
import com.yushyam.savesync.data.model.StorageInspection
import com.yushyam.savesync.data.model.SubpathInspection
import com.yushyam.savesync.data.model.SyncManifest
import com.yushyam.savesync.data.model.WebDavConfig
import com.yushyam.savesync.data.shell.PrivilegeMode
import com.yushyam.savesync.data.shell.ShellManager
import com.yushyam.savesync.data.storage.WebDavClient
import com.yushyam.savesync.engine.network.NetworkPolicyGatekeeper
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

    fun getWebDavError(): String? = webDav.lastError

    fun checkAppStatus(profile: AppSyncProfile): AppSyncStatus {
        if (webDavConfig.url.isBlank()) return AppSyncStatus.UNCHECKED
        val remoteManifest = webDav.getRemoteManifest(profile.packageName) ?: run {
            if (webDav.lastError != null && !webDav.lastError!!.contains("404")) {
                return AppSyncStatus.ERROR
            }
            return AppSyncStatus.NO_REMOTE
        }

        // 零檔案防衛哨：檢查本地是否完全無檔案 (例如剛重新安裝、清除資料或手動刪除)
        val localInsp = inspectStorage(profile)
        if (localInsp.fileCount == 0) {
            // 本地為空且雲端已有存檔 ➜ 強制判定為待下載還原，徹底解決鬼影帳本誤判問題
            return AppSyncStatus.NEED_PULL
        }

        val localLedger = getLocalLedger(profile.packageName) ?: return AppSyncStatus.NEED_PULL

        val remoteTime = remoteManifest.updated_at_epoch_ms
        val localTime = localLedger.updated_at_epoch_ms

        val localMtime = getLocalDirMtimeMs(profile)
        val localHasNewer = localMtime > (localTime + 2000)

        return when {
            remoteManifest.sha256 == localLedger.sha256 && !localHasNewer -> AppSyncStatus.UP_TO_DATE
            remoteTime > localTime && !localHasNewer -> AppSyncStatus.NEED_PULL
            localHasNewer && remoteTime <= localTime -> AppSyncStatus.NEED_PUSH
            localHasNewer && remoteTime > localTime -> AppSyncStatus.CONFLICT
            else -> AppSyncStatus.UP_TO_DATE
        }
    }

    // 雙向同步：依據狀態決定上傳或下載 (支援雙軌保留快照策略)
    fun autoSyncApp(profile: AppSyncProfile, minKeepSnapshots: Int = 3, retentionDays: Int = 7): Pair<Boolean, String> {
        if (webDavConfig.url.isBlank()) {
            return Pair(false, "尚未設定 WebDAV 雲端伺服器（請至右上角 ⚙️ 設定）")
        }
        val prefs = context.getSharedPreferences("savesync_prefs", Context.MODE_PRIVATE)
        val allowCellular = prefs.getBoolean("allow_cellular_sync", true)
        val (canSync, reason) = NetworkPolicyGatekeeper.canSyncUnderPolicy(context, allowCellular)
        if (!canSync) {
            return Pair(false, reason)
        }
        val st = checkAppStatus(profile)
        return when (st) {
            AppSyncStatus.NEED_PULL -> {
                pullApp(profile, minKeepSnapshots, retentionDays)
            }
            AppSyncStatus.NEED_PUSH, AppSyncStatus.NO_REMOTE -> {
                pushApp(profile, minKeepSnapshots, retentionDays)
            }
            AppSyncStatus.UP_TO_DATE -> {
                Pair(true, "已是最新")
            }
            AppSyncStatus.CONFLICT -> {
                Pair(false, "檢測到存檔衝突，請點擊手動解決")
            }
            AppSyncStatus.ERROR -> {
                val err = webDav.lastError ?: "連線或校驗失敗"
                Pair(false, "同步連線失敗（$err）")
            }
            else -> {
                pushApp(profile, minKeepSnapshots, retentionDays)
            }
        }
    }

    fun resolveExcludes(profile: AppSyncProfile): List<String> {
        val smart = DefaultExcludes.getExcludesFor(profile.packageName)
        return (profile.excludes + smart).distinct()
    }

    fun resolveAppDirectory(profile: AppSyncProfile): String {
        if (profile.customStoragePath.isNotBlank()) {
            return profile.customStoragePath.trim()
        }
        return resolveAppDirectory(profile.packageName)
    }

    fun resolveAppDirectory(packageName: String): String {
        // 1. 優先檢查私有目錄 /data/data (具備 Root 提權時直接命中真實程式內部資料庫與組態所在)
        val internalData = "/data/data/$packageName"
        val checkInternal = shell.exec("if [ -d '$internalData' ] && [ -r '$internalData' ]; then echo 'OK'; fi")
        if (checkInternal.contains("OK")) {
            return internalData
        }

        // 2. 檢查外部應用資料目錄 /sdcard/Android/data
        val sdcardData = "/sdcard/Android/data/$packageName"
        val checkSdcard = shell.exec("if [ -d '$sdcardData' ] && [ -r '$sdcardData' ]; then echo 'OK'; fi")
        if (checkSdcard.contains("OK")) {
            return sdcardData
        }

        // 3. 次選：常見通用備份目錄 (如 Mihon / Tachiyomi 公開備份資料夾)
        if (packageName == "app.mihon" || packageName == "eu.kanade.tachiyomi") {
            val candidates = listOf(
                "/sdcard/Documents/Mihon/backups",
                "/sdcard/Documents/Mihon",
                "/sdcard/Download/Mihon",
                "/sdcard/Documents/Tachiyomi/backups"
            )
            for (p in candidates) {
                if (File(p).exists() && File(p).canRead()) return p
                val check = shell.exec("if [ -d '$p' ] && [ -r '$p' ]; then echo 'OK'; fi")
                if (check.contains("OK")) return p
            }
        }

        return sdcardData
    }

    private fun isExcluded(path: String, excludes: List<String>): Boolean {
        if (excludes.isEmpty()) return false
        val clean = path.replace("\\", "/")
        return excludes.any { pattern ->
            val p = pattern.trim().removeSurrounding("'").removeSurrounding("\"")
            when {
                p.startsWith("*") && p.endsWith("*") -> clean.contains(p.substring(1, p.length - 1), ignoreCase = true)
                p.startsWith("*") -> clean.endsWith(p.substring(1), ignoreCase = true)
                p.endsWith("*") -> clean.contains(p.substring(0, p.length - 1), ignoreCase = true)
                else -> clean.split("/").contains(p) || clean.contains("/$p/") || clean.endsWith("/$p")
            }
        }
    }

    private fun buildFindExcludes(excludes: List<String>): String {
        if (excludes.isEmpty()) return ""
        return excludes.joinToString(" ") { pat ->
            val p = pat.trim().removeSurrounding("'").removeSurrounding("\"")
            val wildcardPattern = if (!p.startsWith("*") && !p.endsWith("*")) "*$p*" else p
            "! -ipath '$wildcardPattern'"
        }
    }

    // 事前存檔即時預檢：支援直接傳入目錄路徑字串
    fun inspectStorage(targetPath: String): StorageInspection {
        val dummyProfile = AppSyncProfile(
            id = "custom_path",
            packageName = "custom.path",
            displayName = "Custom Path",
            customStoragePath = targetPath,
            relativePaths = emptyList()
        )
        return inspectStorage(dummyProfile)
    }

    fun inspectRawPath(targetDir: String, excludes: List<String> = DefaultExcludes.BASE): StorageInspection {
        val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
        val javaDir = File(targetDir)
        if (javaDir.exists() && javaDir.canRead()) {
            val allFiles = try {
                if (javaDir.isDirectory) {
                    javaDir.walkTopDown().filter { it.isFile && !isExcluded(it.absolutePath, excludes) }.toList()
                } else if (!isExcluded(javaDir.absolutePath, excludes)) {
                    listOf(javaDir)
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                emptyList()
            }
            if (allFiles.isNotEmpty()) {
                val count = allFiles.size
                val totalBytes = allFiles.sumOf { it.length() }
                val newestMs = allFiles.maxOfOrNull { it.lastModified() } ?: javaDir.lastModified()
                val sizeKb = totalBytes / 1024.0
                val sizeStr = if (sizeKb >= 1024) "%.1f MB".format(sizeKb / 1024.0) else "%.1f KB".format(sizeKb)
                val isValid = count > 0 && totalBytes > 0
                val summary = if (isValid) "$count 個檔案 · $sizeStr" else "此路徑為空 (0 個檔案)"
                return StorageInspection(
                    path = targetDir,
                    fileCount = count,
                    totalSizeBytes = totalBytes,
                    formattedSize = sizeStr,
                    lastModifiedMs = newestMs,
                    formattedLastModified = sdf.format(Date(newestMs)),
                    isValid = isValid,
                    statusSummary = summary
                )
            }
        }

        // 2. 特權 Shell 探針 (針對沙盒限制目錄或外部儲存受限路徑)
        val checkAccessible = shell.exec("if [ -e '$targetDir' ] && [ -r '$targetDir' ]; then echo 'OK'; fi").contains("OK")
        val findEx = buildFindExcludes(excludes)
        val sizesOut = shell.exec("find '$targetDir' -type f $findEx -printf '%s\n' 2>/dev/null")
        val sizes = sizesOut.lines().mapNotNull { it.trim().toLongOrNull() }
        val countOut = sizes.size
        val sizeOut = sizes.sum()
        val sizeKb = sizeOut / 1024.0
        val sizeStr = if (sizeKb >= 1024) "%.1f MB".format(sizeKb / 1024.0) else "%.1f KB".format(sizeKb)
        val isValid = checkAccessible && (countOut > 0 || sizeOut > 0)
        val summary = when {
            !checkAccessible -> "🔒 權限不足"
            countOut > 0 -> "$countOut 個檔案 · $sizeStr"
            else -> "此路徑為空 (0 個檔案)"
        }
        return StorageInspection(
            path = targetDir,
            fileCount = countOut,
            totalSizeBytes = sizeOut,
            formattedSize = sizeStr,
            lastModifiedMs = System.currentTimeMillis(),
            formattedLastModified = sdf.format(Date()),
            isValid = checkAccessible,
            statusSummary = summary
        )
    }

    // 事前存檔即時預檢：選取路徑當下即時計算檔案數、體積與狀態（含主目錄與附加目錄加總）
    fun inspectStorage(profile: AppSyncProfile): StorageInspection {
        val targetDir = resolveAppDirectory(profile)
        val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())

        var totalCount = 0
        var totalBytes = 0L
        var hasInaccessible = false
        val effectiveExcludes = resolveExcludes(profile)

        if (profile.relativePaths.isNotEmpty()) {
            val subInspections = profile.relativePaths.map { inspectSubpath(targetDir, it, effectiveExcludes) }
            totalCount += subInspections.sumOf { it.fileCount }
            totalBytes += subInspections.sumOf { it.totalSizeBytes }
            if (subInspections.any { !it.isAccessible }) hasInaccessible = true
        } else {
            val mainInsp = inspectRawPath(targetDir, effectiveExcludes)
            totalCount += mainInsp.fileCount
            totalBytes += mainInsp.totalSizeBytes
            if (!mainInsp.isValid) hasInaccessible = true
        }

        val safeExtras = profile.extraStoragePaths ?: emptyList()
        for (extra in safeExtras) {
            val extraInsp = inspectRawPath(extra, effectiveExcludes)
            totalCount += extraInsp.fileCount
            totalBytes += extraInsp.totalSizeBytes
            if (!extraInsp.isValid) hasInaccessible = true
        }

        val sizeKb = totalBytes / 1024.0
        val sizeStr = if (sizeKb >= 1024) "%.1f MB".format(sizeKb / 1024.0) else "%.1f KB".format(sizeKb)
        val isValid = totalCount > 0 && !hasInaccessible
        val summary = when {
            hasInaccessible -> "⚠️ 包含未授權目錄"
            isValid -> "$totalCount 個檔案 · $sizeStr"
            else -> "此路徑為空 (0 個檔案)"
        }
        return StorageInspection(
            path = targetDir,
            fileCount = totalCount,
            totalSizeBytes = totalBytes,
            formattedSize = sizeStr,
            lastModifiedMs = System.currentTimeMillis(),
            formattedLastModified = sdf.format(Date()),
            isValid = isValid,
            statusSummary = summary
        )
    }

    // 探測特定子目錄（如 databases/, shared_prefs/, files/）是否存在與檔案狀況
    fun inspectSubpath(baseDir: String, subpath: String, excludes: List<String> = DefaultExcludes.BASE): SubpathInspection {
        val cleanSub = subpath.trim().removeSuffix("/")
        val fullPath = if (cleanSub.isNotBlank()) "$baseDir/$cleanSub" else baseDir

        // 1. 優先嘗試標準 File
        val javaFile = File(fullPath)
        if (javaFile.exists()) {
            if (javaFile.canRead()) {
                val files = try {
                    if (javaFile.isDirectory) javaFile.walkTopDown().filter { it.isFile && !isExcluded(it.absolutePath, excludes) }.toList()
                    else if (!isExcluded(javaFile.absolutePath, excludes)) listOf(javaFile) else emptyList()
                } catch (e: Exception) {
                    emptyList()
                }
                if (files.isNotEmpty()) {
                    val count = files.size
                    val totalBytes = files.sumOf { it.length() }
                    val sizeKb = totalBytes / 1024.0
                    val sizeStr = if (sizeKb >= 1024) "%.1f MB".format(sizeKb / 1024.0) else "%.1f KB".format(sizeKb)
                    val status = "$count 個檔案 · $sizeStr"
                    return SubpathInspection(
                        path = subpath,
                        exists = true,
                        fileCount = count,
                        sizeFormatted = sizeStr,
                        isAccessible = true,
                        statusText = status,
                        totalSizeBytes = totalBytes
                    )
                }
            }
        }

        // 2. 特權 Shell 探針 (針對受沙盒保護之私有目錄)
        val probe = shell.exec("if [ -e '$fullPath' ]; then if [ -r '$fullPath' ]; then echo 'ACCESSIBLE'; else echo 'PERM_DENIED'; fi; else echo 'NOT_FOUND'; fi").trim()
        return when {
            probe == "NOT_FOUND" || probe.isEmpty() -> {
                SubpathInspection(
                    path = subpath,
                    exists = false,
                    fileCount = 0,
                    sizeFormatted = "0 KB",
                    isAccessible = true,
                    statusText = "未建立",
                    totalSizeBytes = 0L
                )
            }
            probe == "PERM_DENIED" -> {
                SubpathInspection(
                    path = subpath,
                    exists = true,
                    fileCount = 0,
                    sizeFormatted = "",
                    isAccessible = false,
                    statusText = "🔒 需提權存取",
                    totalSizeBytes = 0L
                )
            }
            else -> {
                val findEx = buildFindExcludes(excludes)
                val sizesOut = shell.exec("find '$fullPath' -type f $findEx -printf '%s\n' 2>/dev/null")
                val sizes = sizesOut.lines().mapNotNull { it.trim().toLongOrNull() }
                val count = sizes.size
                val totalBytes = sizes.sum()
                val sizeKb = totalBytes / 1024.0
                val sizeStr = if (sizeKb >= 1024) "%.1f MB".format(sizeKb / 1024.0) else "%.1f KB".format(sizeKb)
                val status = if (count > 0) "$count 個檔案 · $sizeStr" else "空資料夾"
                SubpathInspection(
                    path = subpath,
                    exists = true,
                    fileCount = count,
                    sizeFormatted = sizeStr,
                    isAccessible = true,
                    statusText = status,
                    totalSizeBytes = totalBytes
                )
            }
        }
    }

    // 取得特定子目錄下的檔案清單明細 (支援直接 File 讀取與特權 Shell 探針，自動排除快取並以單一指令快速獲取完整路徑與大小)
    fun listSubpathFiles(baseDir: String, subpath: String, excludes: List<String> = DefaultExcludes.BASE): List<FileDetailItem> {
        val cleanSub = subpath.trim().removeSuffix("/")
        val fullPath = if (cleanSub.isNotBlank()) "$baseDir/$cleanSub" else baseDir

        // 1. 標準 File 遍歷
        val javaFile = File(fullPath)
        if (javaFile.exists() && javaFile.canRead()) {
            return try {
                javaFile.walkTopDown().filter { it.isFile && !isExcluded(it.absolutePath, excludes) }.take(100).map { f ->
                    val rel = f.absolutePath.removePrefix(javaFile.absolutePath).removePrefix(File.separator)
                    val sizeKb = f.length() / 1024.0
                    val sizeStr = if (sizeKb >= 1024) "%.1f MB".format(sizeKb / 1024.0) else "%.1f KB".format(sizeKb)
                    FileDetailItem(name = f.name, relativePath = rel, sizeFormatted = sizeStr)
                }.toList()
            } catch (e: Exception) {
                emptyList()
            }
        }

        // 2. 特權 Shell 探針遍歷 (使用 find -printf 一次性取得完整相對路徑與大小，避免逐檔呼叫 stat 效能損耗)
        val findEx = buildFindExcludes(excludes)
        val out = shell.exec("if [ -d '$fullPath' ]; then cd '$fullPath' && find . -type f $findEx -printf '%p\\t%s\\n' 2>/dev/null | head -n 100; fi")
        if (out.isBlank()) return emptyList()

        return out.lines().filter { it.isNotBlank() && it.contains("\t") }.map { line ->
            val parts = line.split("\t")
            val rel = parts[0].trim().removePrefix("./")
            val sizeBytes = parts.getOrNull(1)?.trim()?.toLongOrNull() ?: 0L
            val fileName = rel.substringAfterLast("/")
            val sizeKb = sizeBytes / 1024.0
            val sizeStr = if (sizeKb >= 1024) "%.1f MB".format(sizeKb / 1024.0) else "%.1f KB".format(sizeKb)
            FileDetailItem(name = fileName, relativePath = rel, sizeFormatted = sizeStr)
        }
    }

    private fun getSharedWorkDir(): File {
        val dir = context.getExternalFilesDir("sync_staging")
            ?: File("/sdcard/Android/data/${context.packageName}/files/sync_staging")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun buildRestoreCommand(
        targetDir: String,
        tarPath: String,
        isMirror: Boolean = false,
        relativePaths: List<String> = emptyList()
    ): String {
        val cleanPart = if (isMirror) {
            if (targetDir.startsWith("/sdcard/")) {
                "rm -rf '$targetDir'/* 2>/dev/null; "
            } else if (relativePaths.isNotEmpty()) {
                val targets = relativePaths.map { "'$targetDir/${it.trimEnd('/')}'" }.joinToString(" ")
                "rm -rf $targets 2>/dev/null; "
            } else {
                ""
            }
        } else {
            ""
        }

        return if (targetDir.startsWith("/sdcard/")) {
            "${cleanPart}mkdir -p '$targetDir' && cd '$targetDir' && toybox tar -xzf '$tarPath'"
        } else {
            val uidGidStr = shell.exec("stat -c '%u %g' '$targetDir'")
            val parts = uidGidStr.split("\\s+".toRegex())
            val uid = parts.getOrNull(0) ?: ""
            val gid = parts.getOrNull(1) ?: uid
            val chownPart = if (uid.isNotBlank()) " && chown -R $uid:$gid '$targetDir' && restorecon -R '$targetDir'" else ""
            "${cleanPart}mkdir -p '$targetDir' && cd '$targetDir' && toybox tar -xzf '$tarPath'$chownPart"
        }
    }

    private fun buildRestoreDirectoryContentCommand(
        targetDir: String,
        sourceDir: String,
        isMirror: Boolean = false,
        relativePaths: List<String> = emptyList()
    ): String {
        val cleanPart = if (isMirror) {
            if (targetDir.startsWith("/sdcard/")) {
                "rm -rf '$targetDir'/* 2>/dev/null; "
            } else if (relativePaths.isNotEmpty()) {
                val targets = relativePaths.map { "'$targetDir/${it.trimEnd('/')}'" }.joinToString(" ")
                "rm -rf $targets 2>/dev/null; "
            } else {
                ""
            }
        } else {
            ""
        }

        val transferCmd = "(cd '$sourceDir' && toybox tar -cf - .) | (mkdir -p '$targetDir' && cd '$targetDir' && toybox tar -xf -)"
        return if (targetDir.startsWith("/sdcard/")) {
            "${cleanPart}$transferCmd"
        } else {
            val uidGidStr = shell.exec("stat -c '%u %g' '$targetDir'")
            val parts = uidGidStr.split("\\s+".toRegex())
            val uid = parts.getOrNull(0) ?: ""
            val gid = parts.getOrNull(1) ?: uid
            val chownPart = if (uid.isNotBlank()) " && chown -R $uid:$gid '$targetDir' && restorecon -R '$targetDir'" else ""
            "${cleanPart}$transferCmd$chownPart"
        }
    }

    fun forcePushApp(profile: AppSyncProfile, minKeepSnapshots: Int = 3, retentionDays: Int = 7): Pair<Boolean, String> {
        return pushApp(profile, minKeepSnapshots, retentionDays)
    }

    fun forcePullApp(profile: AppSyncProfile, minKeepSnapshots: Int = 3, retentionDays: Int = 7): Pair<Boolean, String> {
        return pullApp(profile, minKeepSnapshots, retentionDays)
    }

    fun pushApp(profile: AppSyncProfile, minKeepSnapshots: Int = 3, retentionDays: Int = 7): Pair<Boolean, String> {
        if (webDavConfig.url.isBlank()) {
            return Pair(false, "尚未設定 WebDAV 雲端伺服器（請至右上角 ⚙️ 設定）")
        }
        val localInsp = inspectStorage(profile)
        if (localInsp.fileCount == 0) {
            return Pair(false, "本地存檔為空 (0 個檔案)，已攔截上傳以防覆蓋清空雲端")
        }
        // 備份上傳為純讀取，絕不強制關閉目標應用，防止記憶體未存檔進度遺失
        val targetDir = resolveAppDirectory(profile)

        val stagingDir = getSharedWorkDir()
        val stagingFile = File(stagingDir, "${profile.packageName}_push.tar.gz")
        if (stagingFile.exists()) stagingFile.delete()

        val effectiveExcludes = resolveExcludes(profile)
        val excludesStr = effectiveExcludes.joinToString(" ") { "--exclude='$it'" }

        // 細粒度選擇性打包
        val safeRelatives = profile.relativePaths ?: emptyList()
        val targets = if (safeRelatives.isEmpty()) {
            listOf(".")
        } else {
            safeRelatives.map { it.trim().trimEnd('/') }.filter { it.isNotBlank() }
        }
        val targetsArg = targets.joinToString(" ")

        // 檢查目標目錄是否存在
        val checkExisting = shell.exec("cd '$targetDir' && for d in $targetsArg; do if [ -e \"\$d\" ]; then echo \"\$d\"; fi; done")
        val validTargets = checkExisting.lines().map { it.trim() }.filter { it.isNotBlank() }
        val finalTargets = if (validTargets.isNotEmpty()) validTargets.joinToString(" ") else "."

        val safeExtras = profile.extraStoragePaths ?: emptyList()
        val hasExtra = safeExtras.isNotEmpty()
        val extraMap = mutableMapOf<String, String>()

        if (!hasExtra) {
            shell.exec("cd '$targetDir' && toybox tar -czf '${stagingFile.absolutePath}' $excludesStr $finalTargets")
        } else {
            // 複合分槽封裝：primary/ 與 extra_0/
            val slotsDir = File(stagingDir, "slots_${profile.packageName}_${System.currentTimeMillis()}")
            slotsDir.mkdirs()
            val primarySlot = File(slotsDir, "primary")
            primarySlot.mkdirs()
            shell.exec("cd '$targetDir' && (toybox tar -cf - $excludesStr $finalTargets 2>/dev/null) | (cd '${primarySlot.absolutePath}' && toybox tar -xf -)")

            safeExtras.forEachIndexed { idx, extraPath ->
                val trimmed = extraPath.trim()
                if (trimmed.isNotBlank()) {
                    val slotName = "extra_$idx"
                    val extraSlot = File(slotsDir, slotName)
                    extraSlot.mkdirs()
                    extraMap[slotName] = trimmed
                    shell.exec("cd '$trimmed' && (toybox tar -cf - $excludesStr . 2>/dev/null) | (cd '${extraSlot.absolutePath}' && toybox tar -xf -)")
                }
            }
            shell.exec("cd '${slotsDir.absolutePath}' && toybox tar -czf '${stagingFile.absolutePath}' *")
            slotsDir.deleteRecursively()
        }

        // 存檔真實性與非空校驗 (若檔案小於 100 Bytes 視為無效或讀取失敗)
        if (!stagingFile.exists() || stagingFile.length() < 100L) {
            stagingFile.delete()
            return Pair(false, "存檔為空或打包失敗（路徑: $targetDir，請確認該路徑是否存在或具備讀取權限）")
        }

        // 建立本地雙軌安全快照
        createLocalRollbackSnapshot(profile, minKeep = minKeepSnapshots, retentionDays = retentionDays, existingArchive = stagingFile)

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
            relative_paths = profile.relativePaths,
            extra_paths = extraMap,
            is_slotted = hasExtra
        )

        val uploadOk = webDav.uploadArchive(profile.packageName, stagingFile)
        val manifestOk = if (uploadOk) webDav.putRemoteManifest(profile.packageName, manifest) else false
        val finalSizeKb = stagingFile.length() / 1024.0
        val sizeStr = if (finalSizeKb >= 1024) "%.1f MB".format(finalSizeKb / 1024.0) else "%.1f KB".format(finalSizeKb)
        stagingFile.delete()

        if (uploadOk && manifestOk) {
            saveLocalLedger(manifest)
            return Pair(true, "已上傳 ($sizeStr)")
        }
        val err = webDav.lastError ?: "伺服器無回應或拒絕連線"
        return Pair(false, "雲端上傳失敗（$err）")
    }

    fun pullApp(profile: AppSyncProfile, minKeepSnapshots: Int = 3, retentionDays: Int = 7): Pair<Boolean, String> {
        if (webDavConfig.url.isBlank()) {
            return Pair(false, "尚未設定 WebDAV 雲端伺服器（請至右上角 ⚙️ 設定）")
        }
        val remoteManifest = webDav.getRemoteManifest(profile.packageName) ?: run {
            val err = webDav.lastError ?: "找不到雲端存檔清單 manifest.json"
            return Pair(false, "無法獲取雲端存檔（$err）")
        }
        val stagingDir = getSharedWorkDir()
        val stagingFile = File(stagingDir, "${profile.packageName}_pull.tar.gz")
        if (stagingFile.exists()) stagingFile.delete()

        if (!webDav.downloadArchive(profile.packageName, stagingFile) || !stagingFile.exists() || stagingFile.length() < 100L) {
            stagingFile.delete()
            val err = webDav.lastError ?: "檔案損毀或下載中斷"
            return Pair(false, "雲端下載失敗（$err）")
        }

        // 核心安全防護：在覆蓋本地存檔前，強制產生 1 份本地快照備份
        createLocalRollbackSnapshot(profile, minKeep = minKeepSnapshots, retentionDays = retentionDays)

        // 僅在使用者明確開啟且具備系統提權時才執行進程關閉
        if (profile.forceStopBeforeRestore && shell.detectPrivilege() != PrivilegeMode.NONE) {
            shell.exec("am force-stop ${profile.packageName}")
        }

        val targetDir = resolveAppDirectory(profile)
        if (!remoteManifest.is_slotted) {
            val restoreCmd = buildRestoreCommand(targetDir, stagingFile.absolutePath, profile.isMirrorSync, profile.relativePaths)
            shell.exec(restoreCmd)
        } else {
            val unpackDir = File(stagingDir, "restore_slots_${profile.packageName}_${System.currentTimeMillis()}")
            unpackDir.mkdirs()
            shell.exec("cd '${unpackDir.absolutePath}' && toybox tar -xzf '${stagingFile.absolutePath}'")

            val primarySlot = File(unpackDir, "primary")
            if (primarySlot.exists()) {
                val restoreCmd = buildRestoreDirectoryContentCommand(targetDir, primarySlot.absolutePath, profile.isMirrorSync, profile.relativePaths)
                shell.exec(restoreCmd)
            }

            remoteManifest.extra_paths.forEach { (slotName, defaultPath) ->
                val slotDir = File(unpackDir, slotName)
                if (slotDir.exists()) {
                    val destPath = defaultPath
                    val restoreCmd = buildRestoreDirectoryContentCommand(destPath, slotDir.absolutePath, profile.isMirrorSync, emptyList())
                    shell.exec(restoreCmd)
                }
            }
            unpackDir.deleteRecursively()
        }

        val finalSizeKb = stagingFile.length() / 1024.0
        val sizeStr = if (finalSizeKb >= 1024) "%.1f MB".format(finalSizeKb / 1024.0) else "%.1f KB".format(finalSizeKb)
        stagingFile.delete()

        saveLocalLedger(remoteManifest)
        return Pair(true, "已還原 ($sizeStr)")
    }

    // 建立本地安全還原快照 (雙軌保留策略)
    fun createLocalRollbackSnapshot(
        profile: AppSyncProfile,
        minKeep: Int = 3,
        retentionDays: Int = 7,
        existingArchive: File? = null
    ): Boolean {
        return try {
            val snapshotDir = File(context.filesDir, "snapshots/${profile.packageName}")
            if (!snapshotDir.exists()) snapshotDir.mkdirs()

            val now = System.currentTimeMillis()
            val destFile = File(snapshotDir, "snapshot_${now}.tar.gz")

            if (existingArchive != null && existingArchive.exists() && existingArchive.length() > 0L) {
                existingArchive.copyTo(destFile, overwrite = true)
                cleanOldSnapshots(snapshotDir, minKeep = minKeep, retentionDays = retentionDays)
                return true
            }

            val targetDir = resolveAppDirectory(profile)
            val stagingDir = getSharedWorkDir()
            val stagingSnap = File(stagingDir, "${profile.packageName}_snap_${now}.tar.gz")
            if (stagingSnap.exists()) stagingSnap.delete()

            val effectiveExcludes = resolveExcludes(profile)
            val excludesStr = effectiveExcludes.joinToString(" ") { "--exclude='$it'" }
            shell.exec("cd '$targetDir' && toybox tar -czf '${stagingSnap.absolutePath}' $excludesStr .")

            if (stagingSnap.exists() && stagingSnap.length() > 0L) {
                stagingSnap.copyTo(destFile, overwrite = true)
                stagingSnap.delete()

                cleanOldSnapshots(snapshotDir, minKeep = minKeep, retentionDays = retentionDays)
                true
            } else {
                stagingSnap.delete()
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    // 取得指定應用的歷史快照清單 (若傳入策略則先執行修剪)
    fun listSnapshots(packageName: String, maxKeep: Int = -1, retentionDays: Int = -1): List<SnapshotItem> {
        val snapshotDir = File(context.filesDir, "snapshots/$packageName")
        if (!snapshotDir.exists()) return emptyList()

        if (maxKeep >= 0 || retentionDays >= 0) {
            cleanOldSnapshots(snapshotDir, if (maxKeep >= 0) maxKeep else 0, if (retentionDays >= 0) retentionDays else 0)
        }

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

    // 手動刪除單筆快照
    fun deleteSnapshot(snapshot: SnapshotItem): Boolean {
        return try {
            val f = File(snapshot.filePath)
            if (f.exists()) f.delete() else false
        } catch (e: Exception) {
            false
        }
    }

    // 將存檔直接還原至選定的歷史快照
    fun restoreSnapshot(profile: AppSyncProfile, snapshot: SnapshotItem): Boolean {
        val sourceFile = File(snapshot.filePath)
        if (!sourceFile.exists()) return false

        if (profile.forceStopBeforeRestore && shell.detectPrivilege() != PrivilegeMode.NONE) {
            shell.exec("am force-stop ${profile.packageName}")
        }
        val targetDir = resolveAppDirectory(profile)

        val stagingDir = getSharedWorkDir()
        val stagingFile = File(stagingDir, "${profile.packageName}_rollback.tar.gz")
        sourceFile.copyTo(stagingFile, overwrite = true)

        val restoreCmd = buildRestoreCommand(targetDir, stagingFile.absolutePath, profile.isMirrorSync, profile.relativePaths)
        shell.exec(restoreCmd)

        stagingFile.delete()
        return true
    }

    // 依指定策略對指定應用的歷史快照進行修剪
    fun pruneSnapshots(packageName: String, maxKeep: Int, retentionDays: Int) {
        val snapshotDir = File(context.filesDir, "snapshots/$packageName")
        if (snapshotDir.exists()) {
            cleanOldSnapshots(snapshotDir, maxKeep, retentionDays)
        }
    }

    // 嚴格 FIFO 滾動淘汰 + 選擇性天數過期淘汰 (0 代表不限)
    fun cleanOldSnapshots(dir: File, minKeep: Int = 3, retentionDays: Int = 7) {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("snapshot_") && f.name.endsWith(".tar.gz") }
            ?: return

        val sorted = files.sortedBy { it.lastModified() }
        val remaining = sorted.toMutableList()

        // 1. 若有設定天數上限 (retentionDays > 0)，先淘汰過期檔案
        if (retentionDays > 0) {
            val cutoff = System.currentTimeMillis() - (retentionDays.toLong() * 86400000L)
            val it = remaining.iterator()
            while (it.hasNext()) {
                val f = it.next()
                if (f.lastModified() < cutoff) {
                    f.delete()
                    it.remove()
                }
            }
        }

        // 2. 若有設定份數上限 (minKeep > 0)，嚴格執行 FIFO 佇列，淘汰最舊的多餘版本
        if (minKeep > 0 && remaining.size > minKeep) {
            val toRemove = remaining.size - minKeep
            for (i in 0 until toRemove) {
                remaining[i].delete()
            }
        }
    }

    private fun getLocalDirMtimeMs(profile: AppSyncProfile): Long {
        val targetDir = resolveAppDirectory(profile)
        val cmd = "find '$targetDir' -type f -exec stat -c '%Y' {} + 2>/dev/null | sort -nr | head -n 1"
        val out = shell.exec(cmd).trim()
        val newestSec = out.toLongOrNull()
        if (newestSec != null && newestSec > 0L) {
            return newestSec * 1000L
        }
        val dirOut = shell.exec("stat -c '%Y' '$targetDir' 2>/dev/null").trim()
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
