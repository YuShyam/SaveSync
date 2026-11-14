package com.droidsave.sync.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.droidsave.sync.data.model.AppSyncProfile
import com.droidsave.sync.data.model.WebDavConfig
import com.droidsave.sync.data.shell.PrivilegeMode
import com.droidsave.sync.data.shell.ShellManager
import com.droidsave.sync.engine.SyncCoordinator
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SyncWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val tag = "SyncWorker"

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.i(tag, "SaveSync 背景排程同步工作開始執行...")
        val prefs = appContext.getSharedPreferences("droidsave_prefs", Context.MODE_PRIVATE)
        val gson = Gson()

        val json = prefs.getString("saved_apps_list", null)
        val appsList: List<AppSyncProfile> = if (json != null) {
            try {
                val type = object : TypeToken<List<AppSyncProfile>>() {}.type
                gson.fromJson(json, type)
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        if (appsList.isEmpty()) {
            Log.i(tag, "目前無已配置的同步應用，略過排程。")
            return@withContext Result.success()
        }

        val webDavConfig = WebDavConfig(
            url = prefs.getString("webdav_url", "http://10.0.2.2:8080/") ?: "http://10.0.2.2:8080/",
            username = prefs.getString("webdav_user", "") ?: "",
            password = prefs.getString("webdav_pass", "") ?: ""
        )
        val maxSnapshots = prefs.getInt("max_snapshots", 3)

        val shellManager = ShellManager(appContext)
        val privilege = shellManager.detectPrivilege()
        if (privilege == PrivilegeMode.NONE) {
            Log.w(tag, "排程同步失敗：目前設備未取得提權！")
            return@withContext Result.retry()
        }

        val coordinator = SyncCoordinator(appContext, shellManager, webDavConfig)
        var successCount = 0

        appsList.forEach { app ->
            try {
                val (ok, msg) = coordinator.autoSyncApp(app, maxSnapshots)
                if (ok) successCount++
                Log.i(tag, "應用 [${app.displayName}] 同步結果: $ok ($msg)")
            } catch (e: Exception) {
                Log.e(tag, "應用 [${app.displayName}] 同步異常", e)
            }
        }

        Log.i(tag, "SaveSync 排程同步完成: $successCount / ${appsList.size} 款應用更新成功")
        Result.success()
    }
}

