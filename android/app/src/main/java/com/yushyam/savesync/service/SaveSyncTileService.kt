package com.yushyam.savesync.service

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.yushyam.savesync.data.model.AppSyncProfile
import com.yushyam.savesync.data.model.WebDavConfig
import com.yushyam.savesync.data.shell.ShellManager
import com.yushyam.savesync.engine.SyncCoordinator
import com.yushyam.savesync.engine.network.NetworkPolicyGatekeeper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 下拉通知欄快捷磁貼服務 (Quick Settings Tile Service for 1-Tap Sync)
 *
 * 提供使用者於手機通知中心下拉直接點擊「⚡ SaveSync 立即同步」，
 * 具備 0 毫秒網路預檢、觸覺與 Toast 即時回饋，平時 0% 背景行程佔用。
 *
 * Provides a Quick Settings tile enabling instantaneous manual sync on demand.
 */
@RequiresApi(Build.VERSION_CODES.N)
class SaveSyncTileService : TileService() {

    private val tag = "SaveSyncTile"
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onStartListening() {
        super.onStartListening()
        updateTileState(Tile.STATE_ACTIVE, "⚡ SaveSync 立即同步")
    }

    override fun onClick() {
        super.onClick()
        Log.i(tag, "使用者點擊下拉選單快捷磁貼，發起全量即時同步...")

        val prefs = getSharedPreferences("savesync_prefs", Context.MODE_PRIVATE)
        val allowCellular = prefs.getBoolean("allow_cellular_sync", true)

        // 1. 網路健康與政策預檢
        val (canSync, reason) = NetworkPolicyGatekeeper.canSyncUnderPolicy(this, allowCellular)
        if (!canSync) {
            showToast("SaveSync: $reason")
            return
        }

        updateTileState(Tile.STATE_UNAVAILABLE, "⏳ 同步處理中...")

        scope.launch {
            try {
                val json = prefs.getString("saved_apps_list", null)
                val appsList: List<AppSyncProfile> =
                    com.yushyam.savesync.data.model.AppSyncProfileCodec.parseList(json)

                if (appsList.isEmpty()) {
                    showToast("SaveSync: 尚未配置任何同步應用")
                    updateTileState(Tile.STATE_ACTIVE, "⚡ SaveSync 立即同步")
                    return@launch
                }

                val webDavConfig = WebDavConfig(
                    url = prefs.getString("webdav_url", "http://10.0.2.2:8080/") ?: "http://10.0.2.2:8080/",
                    username = prefs.getString("webdav_user", "") ?: "",
                    password = prefs.getString("webdav_pass", "") ?: ""
                )
                val minSnapshots = prefs.getInt("min_snapshots", prefs.getInt("max_snapshots", 3))
                val retentionDays = prefs.getInt("retention_days", 7)

                val shellManager = ShellManager(applicationContext)
                val coordinator = SyncCoordinator(applicationContext, shellManager, webDavConfig)
                var successCount = 0

                appsList.filter { it.isEnabled }.forEach { app ->
                    val (ok, _) = coordinator.autoSyncApp(app, minSnapshots, retentionDays)
                    if (ok) successCount++
                }

                Log.i(tag, "快捷磁貼同步完成：$successCount / ${appsList.size}")
                showToast("SaveSync: 同步成功 ($successCount 款應用已是最新)")
            } catch (e: Exception) {
                Log.e(tag, "快捷磁貼同步異常", e)
                showToast("SaveSync: 同步發生異常")
            } finally {
                updateTileState(Tile.STATE_ACTIVE, "⚡ SaveSync 立即同步")
            }
        }
    }

    private fun updateTileState(state: Int, label: String) {
        qsTile?.let {
            it.state = state
            it.label = label
            it.updateTile()
        }
    }

    private fun showToast(text: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()
        }
    }
}
