package com.yushyam.savesync.data.storage

import com.yushyam.savesync.data.model.SyncManifest
import com.yushyam.savesync.data.model.WebDavConfig
import com.google.gson.Gson
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * WebDAV 雲端儲存客戶端 (WebDAV Storage Client).
 *
 * 支援 HTTP Basic 與 Digest 雙模認證，負責遠端連線檢測、目錄建立、存檔與中繼資料傳輸，
 * 並提供詳細錯誤診斷資訊以供除錯與回報。
 */
class WebDavClient(private val config: WebDavConfig) {

    var lastError: String? = null
        private set

    private val authenticator = WebDavAuthenticator(
        usernameProvider = { config.username },
        passwordProvider = { config.password }
    )

    private val client = OkHttpClient.Builder()
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        .retryOnConnectionFailure(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .authenticator(authenticator)
        .build()

    private val gson = Gson()

    private fun newRequestBuilder(url: String, method: String = "GET"): Request.Builder {
        val builder = Request.Builder().url(url)
        val preemptiveHeader = authenticator.createPreemptiveHeader(method, url)
        if (preemptiveHeader != null) {
            builder.header("Authorization", preemptiveHeader)
        } else if (config.username.isNotBlank() && config.password.isNotBlank()) {
            builder.header("Authorization", Credentials.basic(config.username, config.password))
        }
        builder.header("User-Agent", "SaveSync-Android/1.0")
        return builder
    }

    fun testConnection(): Boolean {
        if (config.url.isBlank()) {
            lastError = "未設定 WebDAV 伺服器網址"
            return false
        }
        authenticator.reset()
        return try {
            val req = newRequestBuilder(config.url, "OPTIONS")
                .method("OPTIONS", null)
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful || resp.code == 200 || resp.code == 207 || resp.code == 405) {
                    lastError = null
                    true
                } else {
                    lastError = "HTTP ${resp.code}: ${resp.message}"
                    false
                }
            }
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName}: ${e.localizedMessage ?: e.message}"
            false
        }
    }

    fun ensureRemoteDir(dirName: String): Boolean {
        if (config.url.isBlank()) {
            lastError = "未設定 WebDAV 伺服器網址"
            return false
        }
        val targetUrl = "${config.url.trimEnd('/')}/$dirName/"
        val req = newRequestBuilder(targetUrl, "MKCOL")
            .method("MKCOL", "".toRequestBody(null))
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful || resp.code == 405) {
                    true
                } else {
                    lastError = "建立遠端目錄失敗 (HTTP ${resp.code}: ${resp.message})"
                    false
                }
            }
        } catch (e: Exception) {
            lastError = "建立遠端目錄異常 (${e.javaClass.simpleName}: ${e.localizedMessage ?: e.message})"
            false
        }
    }

    fun uploadArchive(packageName: String, file: File): Boolean {
        if (config.url.isBlank()) {
            lastError = "未設定 WebDAV 伺服器網址"
            return false
        }
        ensureRemoteDir(packageName)
        val targetUrl = "${config.url.trimEnd('/')}/$packageName/latest.tar.gz"
        val body = file.asRequestBody("application/gzip".toMediaType())
        val req = newRequestBuilder(targetUrl, "PUT").put(body).build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    lastError = null
                    true
                } else {
                    lastError = "檔案上傳被拒 (HTTP ${resp.code}: ${resp.message})"
                    false
                }
            }
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName}: ${e.localizedMessage ?: e.message}"
            false
        }
    }

    fun downloadArchive(packageName: String, destFile: File): Boolean {
        if (config.url.isBlank()) {
            lastError = "未設定 WebDAV 伺服器網址"
            return false
        }
        val targetUrl = "${config.url.trimEnd('/')}/$packageName/latest.tar.gz"
        val req = newRequestBuilder(targetUrl, "GET").get().build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful && resp.body != null) {
                    destFile.parentFile?.mkdirs()
                    resp.body!!.byteStream().use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    lastError = null
                    true
                } else {
                    lastError = "檔案下載失敗 (HTTP ${resp.code}: ${resp.message})"
                    false
                }
            }
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName}: ${e.localizedMessage ?: e.message}"
            false
        }
    }

    fun getRemoteManifest(packageName: String): SyncManifest? {
        if (config.url.isBlank()) {
            lastError = "未設定 WebDAV 伺服器網址"
            return null
        }
        val targetUrl = "${config.url.trimEnd('/')}/$packageName/manifest.json"
        val req = newRequestBuilder(targetUrl, "GET").get().build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful && resp.body != null) {
                    val json = resp.body!!.string()
                    lastError = null
                    gson.fromJson(json, SyncManifest::class.java)
                } else {
                    if (resp.code != 404) {
                        lastError = "獲取雲端資訊失敗 (HTTP ${resp.code}: ${resp.message})"
                    }
                    null
                }
            }
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName}: ${e.localizedMessage ?: e.message}"
            null
        }
    }

    fun putRemoteManifest(packageName: String, manifest: SyncManifest): Boolean {
        if (config.url.isBlank()) {
            lastError = "未設定 WebDAV 伺服器網址"
            return false
        }
        ensureRemoteDir(packageName)
        val targetUrl = "${config.url.trimEnd('/')}/$packageName/manifest.json"
        val json = gson.toJson(manifest)
        val body = json.toRequestBody("application/json".toMediaType())
        val req = newRequestBuilder(targetUrl, "PUT").put(body).build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    lastError = null
                    true
                } else {
                    lastError = "清單寫入被拒 (HTTP ${resp.code}: ${resp.message})"
                    false
                }
            }
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName}: ${e.localizedMessage ?: e.message}"
            false
        }
    }
}
