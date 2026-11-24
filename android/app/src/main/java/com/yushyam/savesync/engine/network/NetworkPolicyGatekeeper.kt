package com.yushyam.savesync.engine.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 網路狀態與傳輸政策守門員 (Network Policy & Connection Gatekeeper)
 *
 * 負責在存檔同步前執行 0 毫秒的網路健康預檢、判定行動數據 (4G/5G) 存取策略，
 * 並於離線或飛航模式時提供系統級事件回調 (NetworkCallback)，以零輪詢方式於連網時自動補傳。
 *
 * Provides pre-flight connectivity verification, enforces cellular network policy,
 * and manages zero-polling network recovery listeners via Android ConnectivityManager.
 */
object NetworkPolicyGatekeeper {

    private const val TAG = "NetworkGatekeeper"

    /**
     * 檢驗當前是否具備已驗證的網際網路連線 (Check if Internet is Active and Validated).
     */
    fun isNetworkConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false

        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return hasInternet && isValidated
    }

    /**
     * 檢驗當前是否為行動網路 4G/5G (Check if Current Connection is Cellular).
     */
    fun isCellular(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    /**
     * 檢驗當前是否為 Wi-Fi 或乙太網路 (Check if Current Connection is Wi-Fi or Ethernet).
     */
    fun isWifiOrEthernet(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /**
     * 依據流量政策判定是否允許發起同步 (Check if Sync is Permitted Under Policy).
     *
     * @param context 應用程式上下文 (Application context)
     * @param allowCellular 是否允許行動數據 (Whether cellular sync is enabled by user)
     * @return Pair(是否允許, 狀態說明文字) (Pair of allowed flag and status message)
     */
    fun canSyncUnderPolicy(context: Context, allowCellular: Boolean): Pair<Boolean, String> {
        if (!isNetworkConnected(context)) {
            Log.d(TAG, "網路預檢攔截：無可用網際網路或處於飛航模式")
            return Pair(false, "無網路連線（處於飛航模式或無訊號）")
        }

        if (isCellular(context) && !allowCellular) {
            Log.d(TAG, "網路預檢攔截：當前為行動數據，依設定暫停自動同步")
            return Pair(false, "目前為行動網路 (4G/5G)，已依偏好設定暫緩同步（等待 Wi-Fi）")
        }

        return Pair(true, "網路連線良好")
    }

    /**
     * 註冊一次性網路恢復監聽器 (Register One-shot Network Recovery Callback).
     *
     * 當處於離線或飛航模式時登記此監聽，完全由 Android 核心主動推播連線恢復事件，
     * 觸發後立刻自動解除註冊，達成 0% 額外輪詢開銷。
     *
     * @param context 應用程式上下文
     * @param allowCellular 是否允許行動數據
     * @param onAvailable 網路恢復且符合政策時的回調
     */
    fun registerOneShotRecovery(
        context: Context,
        allowCellular: Boolean,
        onAvailable: () -> Unit
    ) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val fired = AtomicBoolean(false)

        val requestBuilder = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

        if (!allowCellular) {
            requestBuilder.addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        }

        val request = requestBuilder.build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (fired.compareAndSet(false, true)) {
                    Log.i(TAG, "偵測到網路恢復連線，觸發延遲補傳...")
                    try {
                        cm.unregisterNetworkCallback(this)
                    } catch (e: Exception) {
                        Log.w(TAG, "解除 NetworkCallback 異常", e)
                    }
                    onAvailable()
                }
            }
        }

        try {
            cm.registerNetworkCallback(request, callback)
            Log.i(TAG, "已向系統註冊網路恢復推播監聽 (One-shot NetworkCallback)")
        } catch (e: Exception) {
            Log.e(TAG, "註冊 NetworkCallback 失敗", e)
        }
    }
}
