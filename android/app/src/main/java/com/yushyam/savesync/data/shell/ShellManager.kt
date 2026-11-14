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

    // 取得當前可用的提權模式 (具備實地主動探針檢驗)
    fun detectPrivilege(): PrivilegeMode {
        if (isRootAvailable()) return PrivilegeMode.ROOT
        if (isShizukuFunctional()) return PrivilegeMode.SHIZUKU
        return PrivilegeMode.NONE
    }

    private fun isRootAvailable(): Boolean {
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

    fun requestShizukuPermission(requestCode: Int = 1001) {
        try {
            if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    Shizuku.requestPermission(requestCode)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "requestShizukuPermission failed", e)
        }
    }

    // 取得提權狀態之深度診斷資訊，便於引導使用者修復
    fun getPrivilegeDiagnosis(): String {
        if (isRootAvailable()) return "Root 權限已啟用 (UID 0)"
        if (isShizukuFunctional()) return "Shizuku 服務運行中 (UID 2000)"

        return try {
            if (!Shizuku.pingBinder()) {
                "尚未取得提權（未偵測到 Root 或 Shizuku 服務）"
            } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                "Shizuku 已安裝，但尚未授權給 SaveSync"
            } else {
                "Shizuku 已授權，但服務尚未透過無線偵錯或 ADB 啟動"
            }
        } catch (e: Exception) {
            "未取得系統沙盒提權"
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
