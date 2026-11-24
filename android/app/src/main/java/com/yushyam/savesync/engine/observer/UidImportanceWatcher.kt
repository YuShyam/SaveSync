package com.yushyam.savesync.engine.observer

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.yushyam.savesync.data.model.AppSyncProfile
import com.yushyam.savesync.engine.network.NetworkPolicyGatekeeper
import kotlinx.coroutines.*
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/**
 * 單一 UID 精準重要性監聽器與 30 秒防抖控制器 (Target UID Importance Watcher with Debounce)
 *
 * 僅針對使用者加入同步清單之 App UID 進行前台生命週期監聽，
 * 徹底杜絕全域活動頻繁喚醒導致的 CPU 功耗問題。
 * 結合 30 秒協程防抖動與網路狀態檢驗，達成「離開即同步、離線自延遲」之極致省電體驗。
 *
 * Listens to foreground/background transitions specifically for configured target UIDs.
 * Employs a 30-second coroutine debounce to avoid spurious triggers and defers offline tasks.
 */
class UidImportanceWatcher(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onTriggerSync: (packageName: String) -> Unit
) {

    private val tag = "UidImportanceWatcher"

    // 目標套件與對應之 UID 映射 (Target package name to UID mapping)
    private val targetUidToPkg = ConcurrentHashMap<Int, String>()
    private val activeForegroundPkgs = ConcurrentHashMap.newKeySet<String>()
    private val debounceJobs = ConcurrentHashMap<String, Job>()

    // 系統監聽器 Proxy 實例引用 (Ref to registered listener proxy for unregistering)
    private var registeredProxy: Any? = null
    private var isWatching = false

    /**
     * 更新目標監聽清單 (Update target applications list to monitor).
     *
     * @param apps 已啟用的同步設定清單 (Enabled sync profile list)
     */
    fun updateTargetApps(apps: List<AppSyncProfile>) {
        val pm = context.packageManager
        targetUidToPkg.clear()

        apps.filter { it.isEnabled }.forEach { app ->
            try {
                val uid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageUid(app.packageName, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageUid(app.packageName, 0)
                }
                targetUidToPkg[uid] = app.packageName
                Log.d(tag, "登錄精準監聽目標：${app.displayName} (${app.packageName}) -> UID: $uid")
            } catch (e: Exception) {
                Log.w(tag, "無法獲取應用 [${app.packageName}] 之 UID", e)
            }
        }
    }

    /**
     * 啟動系統級 UID 重要性監聽 (Start listening to UID state transitions via ActivityManager).
     */
    @Synchronized
    fun startWatching() {
        if (isWatching) return
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return

        try {
            val listenerClass = Class.forName("android.app.ActivityManager\$OnUidImportanceListener")
            var proxyRef: Any? = null
            val invocationHandler = InvocationHandler { _, method, args ->
                when (method.name) {
                    "onUidImportance" -> {
                        if (args != null && args.size >= 2) {
                            val uid = args[0] as? Int ?: -1
                            val importance = args[1] as? Int ?: -1
                            handleImportanceChanged(uid, importance)
                        }
                        null
                    }
                    "hashCode" -> System.identityHashCode(proxyRef ?: this)
                    "equals" -> (args?.getOrNull(0) === proxyRef)
                    "toString" -> "UidImportanceListenerProxy"
                    else -> null
                }
            }

            val proxy = Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass),
                invocationHandler
            )
            proxyRef = proxy

            // 註冊監聽 cutpoint 為 200 (IMPORTANCE_FOREGROUND)
            // 唯有切入前台 (<=200) 或脫離前台 (>200) 時才會收到通知
            val addMethod = am.javaClass.getMethod(
                "addOnUidImportanceListener",
                listenerClass,
                Int::class.javaPrimitiveType
            )
            addMethod.invoke(am, proxy, 200)

            registeredProxy = proxy
            isWatching = true
            Log.i(tag, "成功啟用 Shizuku/System 精準 UID 重要性監聽器 (Zero-Polling)")
        } catch (e: Exception) {
            Log.w(tag, "註冊 OnUidImportanceListener 失敗（可能缺少權限，降級為手動/排程模式）", e)
        }
    }

    /**
     * 停止監聽器並釋放所有協程計時器 (Stop watcher and clean up pending jobs).
     */
    @Synchronized
    fun stopWatching() {
        if (!isWatching) return
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val proxy = registeredProxy

        if (proxy != null) {
            try {
                val listenerClass = Class.forName("android.app.ActivityManager\$OnUidImportanceListener")
                val removeMethod = am.javaClass.getMethod("removeOnUidImportanceListener", listenerClass)
                removeMethod.invoke(am, proxy)
            } catch (e: Exception) {
                Log.w(tag, "移除 OnUidImportanceListener 異常", e)
            }
            registeredProxy = null
        }

        debounceJobs.values.forEach { it.cancel() }
        debounceJobs.clear()
        activeForegroundPkgs.clear()
        isWatching = false
        Log.i(tag, "已停止 UID 重要性監聽器")
    }

    /**
     * 處理單一 UID 前後台狀態切換 (Handle UID foreground/background transition).
     */
    private fun handleImportanceChanged(uid: Int, importance: Int) {
        val packageName = targetUidToPkg[uid] ?: return // 非清單中目標 App，底層立刻丟棄，零開銷

        val isForeground = importance <= 200 // IMPORTANCE_FOREGROUND
        Log.d(tag, "目標應用 [$packageName] (UID $uid) 狀態變更：importance = $importance (前台=$isForeground)")

        if (isForeground) {
            // 進入前台：取消既有的防抖上傳倒數計時
            activeForegroundPkgs.add(packageName)
            debounceJobs[packageName]?.let {
                it.cancel()
                Log.d(tag, "目標應用 [$packageName] 重新回到前台，已取消未完成之防抖計時")
            }
        } else {
            // 脫離前台：使用者按 Home、切換或鎖屏
            if (activeForegroundPkgs.contains(packageName)) {
                activeForegroundPkgs.remove(packageName)
                scheduleDebounceSync(packageName)
            }
        }
    }

    /**
     * 啟動 30 秒防抖延遲計時 (Schedule 30-second debounce sync).
     */
    private fun scheduleDebounceSync(packageName: String, debounceMs: Long = 30_000L) {
        debounceJobs[packageName]?.cancel()

        val job = scope.launch(Dispatchers.IO) {
            Log.i(tag, "目標應用 [$packageName] 已離開前台，啟動 ${debounceMs / 1000} 秒防抖倒數...")
            delay(debounceMs)

            // 30 秒期間未再返回前台，確認使用者已真正結束使用
            if (!activeForegroundPkgs.contains(packageName)) {
                Log.i(tag, "目標應用 [$packageName] 防抖倒數確認完畢，開始評估網路並觸發自動同步")
                executeSafeSync(packageName)
            }
            debounceJobs.remove(packageName)
        }
        debounceJobs[packageName] = job
    }

    /**
     * 結合網路策略守門員之安全同步呼叫 (Execute safe sync with network gatekeeper pre-flight).
     */
    private fun executeSafeSync(packageName: String) {
        val prefs = context.getSharedPreferences("savesync_prefs", Context.MODE_PRIVATE)
        val allowCellular = prefs.getBoolean("allow_cellular_sync", true)

        val (canSync, reason) = NetworkPolicyGatekeeper.canSyncUnderPolicy(context, allowCellular)
        if (canSync) {
            Log.i(tag, "網路狀態符合政策 ($reason)，立即發起同步：$packageName")
            onTriggerSync(packageName)
        } else {
            Log.w(tag, "同步暫緩 ($reason)，向系統註冊離線恢復補傳...")
            NetworkPolicyGatekeeper.registerOneShotRecovery(context, allowCellular) {
                Log.i(tag, "網路已恢復，觸發延遲補傳：$packageName")
                onTriggerSync(packageName)
            }
        }
    }
}
