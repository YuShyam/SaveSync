package com.droidsave.sync.data.storage

import com.droidsave.sync.data.model.SyncManifest
import com.droidsave.sync.data.model.WebDavConfig
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
 * 支援 HTTP Basic 與 Digest 雙模認證，負責遠端連線檢測、目錄建立、存檔與中繼資料傳輸。
 * Supports HTTP Basic and Digest authentication for connection testing, directory creation,
 * and archive/manifest transfer.
 */
class WebDavClient(private val config: WebDavConfig) {

    private val authenticator = WebDavAuthenticator(
        usernameProvider = { config.username },
        passwordProvider = { config.password }
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
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
        builder.header("User-Agent", "DroidSaveSync-Android/1.0")
        return builder
    }

    fun testConnection(): Boolean {
        if (config.url.isBlank()) return false
        authenticator.reset()
        return try {
            val req = newRequestBuilder(config.url, "PROPFIND")
                .method("PROPFIND", "".toRequestBody("text/xml".toMediaType()))
                .build()
            val resp = client.newCall(req).execute()
            resp.isSuccessful || resp.code == 207 || resp.code == 405
        } catch (e: Exception) {
            false
        }
    }

    fun ensureRemoteDir(dirName: String): Boolean {
        val targetUrl = "${config.url.trimEnd('/')}/$dirName/"
        val req = newRequestBuilder(targetUrl, "MKCOL")
            .method("MKCOL", "".toRequestBody(null))
            .build()
        return try {
            val resp = client.newCall(req).execute()
            resp.isSuccessful || resp.code == 405
        } catch (e: Exception) {
            false
        }
    }

    fun uploadArchive(packageName: String, file: File): Boolean {
        ensureRemoteDir(packageName)
        val targetUrl = "${config.url.trimEnd('/')}/$packageName/latest.tar.gz"
        val body = file.asRequestBody("application/gzip".toMediaType())
        val req = newRequestBuilder(targetUrl, "PUT").put(body).build()
        return try {
            val resp = client.newCall(req).execute()
            resp.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    fun downloadArchive(packageName: String, destFile: File): Boolean {
        val targetUrl = "${config.url.trimEnd('/')}/$packageName/latest.tar.gz"
        val req = newRequestBuilder(targetUrl, "GET").get().build()
        return try {
            val resp = client.newCall(req).execute()
            if (resp.isSuccessful && resp.body != null) {
                destFile.parentFile?.mkdirs()
                destFile.writeBytes(resp.body!!.bytes())
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    fun getRemoteManifest(packageName: String): SyncManifest? {
        val targetUrl = "${config.url.trimEnd('/')}/$packageName/manifest.json"
        val req = newRequestBuilder(targetUrl, "GET").get().build()
        return try {
            val resp = client.newCall(req).execute()
            if (resp.isSuccessful && resp.body != null) {
                val json = resp.body!!.string()
                gson.fromJson(json, SyncManifest::class.java)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun putRemoteManifest(packageName: String, manifest: SyncManifest): Boolean {
        ensureRemoteDir(packageName)
        val targetUrl = "${config.url.trimEnd('/')}/$packageName/manifest.json"
        val json = gson.toJson(manifest)
        val body = json.toRequestBody("application/json".toMediaType())
        val req = newRequestBuilder(targetUrl, "PUT").put(body).build()
        return try {
            val resp = client.newCall(req).execute()
            resp.isSuccessful
        } catch (e: Exception) {
            false
        }
    }
}
