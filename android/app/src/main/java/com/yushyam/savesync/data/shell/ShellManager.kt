package com.yushyam.savesync.data.shell

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

enum class PrivilegeMode {
    ROOT,
    SHIZUKU,
    NONE
}

class ShellManager(private val context: Context) {

    private val tag = "ShellManager"
    private val prefs by lazy { context.getSharedPreferences("savesync_prefs", Context.MODE_PRIVATE) }

    fun getPreferredMode(): String {
        return prefs.getString("privilege_mode_pref", "AUTO") ?: "AUTO"
    }

    fun setPreferredMode(mode: String) {
        prefs.edit().putString("privilege_mode_pref", mode).apply()
    }

    // 取得當前可用的提權模式 (結合使用者手動偏好與實地探針檢驗)
    fun detectPrivilege(): PrivilegeMode {
        return when (getPreferredMode()) {
            "ROOT" -> if (isRootAvailable()) PrivilegeMode.ROOT else PrivilegeMode.NONE
            "SHIZUKU" -> if (isShizukuFunctional()) PrivilegeMode.SHIZUKU else PrivilegeMode.NONE
            "NONE" -> PrivilegeMode.NONE
            else -> {
                // AUTO 模式
                if (isRootAvailable()) PrivilegeMode.ROOT
                else if (isShizukuFunctional()) PrivilegeMode.SHIZUKU
                else PrivilegeMode.NONE
            }
        }
    }

    fun isRootAvailable(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val line = reader.readLine()
            process.waitFor()
            line != null && line.contains("uid=0")
        } catch (e: Exception) {
            false
        }
    }

    // 主動探針：除檢查 Binder 存活與權限外，必須實地執行命令驗證 Shizuku 行程是否真能工作
    fun isShizukuFunctional(): Boolean {
        return try {
            var attempts = 0
            while (!Shizuku.pingBinder() && attempts < 15) {
                Thread.sleep(100)
                attempts++
            }
            if (!Shizuku.pingBinder()) {
                Log.w(tag, "Shizuku.pingBinder() returned false after ${attempts * 100}ms")
                return false
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Log.w(tag, "Shizuku.checkSelfPermission() is not granted")
                return false
            }
            val probe = execShizukuDirect("id")
            val ok = probe.contains("uid=")
            if (!ok) {
                Log.w(tag, "Shizuku probe failed, returned: $probe")
            }
            ok
        } catch (e: Exception) {
            Log.e(tag, "isShizukuFunctional exception", e)
            false
        }
    }

    fun requestShizukuPermission(requestCode: Int = 1001): String {
        return try {
            if (!Shizuku.pingBinder()) {
                "Shizuku 服務尚未啟動，請先在 Shizuku App 中以無線偵錯或 ADB 啟動"
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                "Shizuku 已完成授權並正常運作"
            } else {
                Shizuku.requestPermission(requestCode)
                "已發送授權請求，請於系統彈窗中允許"
            }
        } catch (e: Exception) {
            Log.e(tag, "requestShizukuPermission failed", e)
            "請求授權失敗：${e.message}"
        }
    }

    // 取得提權狀態之深度診斷資訊，便於引導使用者修復
    fun getPrivilegeDiagnosis(): String {
        val rootOk = isRootAvailable()
        val shizukuOk = isShizukuFunctional()

        return when (getPreferredMode()) {
            "ROOT" -> if (rootOk) "已強制指定 Root 模式 (UID 0)" else "Root 模式啟用中，但尚未取得 su 權限"
            "SHIZUKU" -> if (shizukuOk) "已強制指定 Shizuku 模式 (UID 2000)" else "Shizuku 模式啟用中，但服務未連線或未授權"
            "NONE" -> "已手動指定一般模式 (Native 沙盒)"
            else -> {
                if (rootOk) "自動偵測：Root 權限已啟用 (UID 0)"
                else if (shizukuOk) "自動偵測：Shizuku 服務運行中 (UID 2000)"
                else if (!Shizuku.pingBinder()) "尚未取得提權（未偵測到 Root 或 Shizuku 服務）"
                else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) "Shizuku 服務中，但尚未授權給 SaveSync"
                else "未取得系統沙盒提權 (一般模式)"
            }
        }
    }

    fun exec(command: String): String {
        val mode = detectPrivilege()
        return when (mode) {
            PrivilegeMode.ROOT -> execRoot(command)
            PrivilegeMode.SHIZUKU -> execShizuku(command)
            PrivilegeMode.NONE -> execNormal(command)
        }
    }

    private fun execRoot(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            output.trim()
        } catch (e: Exception) {
            Log.e(tag, "execRoot failed for: $command", e)
            ""
        }
    }

    private fun execShizukuDirect(command: String): String {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        method.isAccessible = true
        val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val errOutput = process.errorStream.bufferedReader().use { it.readText() }
        process.waitFor()
        if (errOutput.isNotBlank()) {
            Log.w(tag, "execShizuku stderr for '$command': $errOutput")
        }
        return output.trim()
    }

    private fun execShizuku(command: String): String {
        return try {
            execShizukuDirect(command)
        } catch (e: Exception) {
            Log.e(tag, "execShizuku failed for: $command", e)
            execNormal(command)
        }
    }

    private fun execNormal(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            output.trim()
        } catch (e: Exception) {
            Log.e(tag, "execNormal failed for: $command", e)
            ""
        }
    }
}
