package com.yushyam.savesync.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.yushyam.savesync.data.model.AppSyncProfile
import com.yushyam.savesync.data.model.WebDavConfig
import com.yushyam.savesync.data.shell.PrivilegeMode
import com.yushyam.savesync.data.shell.ShellManager
import com.yushyam.savesync.engine.SyncCoordinator
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SyncBroadcastReceiver : BroadcastReceiver() {

    private val tag = "SyncBroadcastReceiver"

    companion object {
        const val ACTION_SYNC_ALL = "com.yushyam.savesync.ACTION_SYNC_ALL"
        const val ACTION_SYNC_SHORT = "sync.all"
        const val ACTION_SYNC_MINI = "sync"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == ACTION_SYNC_ALL || action == ACTION_SYNC_SHORT || action == ACTION_SYNC_MINI) {
            Log.i(tag, "收到外部廣播觸發全量同步: $action")
            val pendingResult = goAsync()

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val prefs = context.getSharedPreferences("savesync_prefs", Context.MODE_PRIVATE)
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
                        Log.w(tag, "外部觸發失敗：清單內無任何應用")
                        showToast(context, "SaveSync: 同步清單為空")
                        return@launch
                    }

                    val webDavConfig = WebDavConfig(
                        url = prefs.getString("webdav_url", "http://10.0.2.2:8080/") ?: "http://10.0.2.2:8080/",
                        username = prefs.getString("webdav_user", "") ?: "",
                        password = prefs.getString("webdav_pass", "") ?: ""
                    )
                    val maxSnapshots = prefs.getInt("max_snapshots", 3)

                    val shellManager = ShellManager(context)
                    val privilege = shellManager.detectPrivilege()
                    if (privilege == PrivilegeMode.NONE) {
                        Log.e(tag, "外部觸發失敗：設備未取得提權！")
                        showToast(context, "SaveSync 廣播觸發失敗：無有效提權")
                        return@launch
                    }

                    val coordinator = SyncCoordinator(context, shellManager, webDavConfig)
                    var successCount = 0

                    appsList.forEach { app ->
                        val (ok, _) = coordinator.autoSyncApp(app, maxSnapshots)
                        if (ok) successCount++
                    }

                    Log.i(tag, "外部廣播全量同步完成: $successCount / ${appsList.size}")
                    showToast(context, "SaveSync (Tasker 觸發): $successCount / ${appsList.size} 款應用同步成功！")
                } catch (e: Exception) {
                    Log.e(tag, "外部廣播處理異常", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    private fun showToast(context: Context, text: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }
}

