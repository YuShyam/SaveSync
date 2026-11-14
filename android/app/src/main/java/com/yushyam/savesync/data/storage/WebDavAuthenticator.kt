package com.yushyam.savesync.data.storage

import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * WebDAV 雙模身分驗證器 (WebDAV Dual-Mode Authenticator for Basic and Digest Auth).
 *
 * 支援 RFC 2617 / RFC 7616 Digest Auth 挑戰與傳統 HTTP Basic Auth，
 * 自動處理 Nonce、qop=auth、MD5 雜湊計算、快取預發與連線重試。
 *
 * Supports RFC 2617 / RFC 7616 Digest Auth challenge and standard HTTP Basic Auth,
 * handling Nonce, qop=auth, MD5 hashing, preemptive caching, and retry loops.
 */
class WebDavAuthenticator(
    private val usernameProvider: () -> String,
    private val passwordProvider: () -> String
) : Authenticator {

    @Volatile
    private var lastDigestParams: Map<String, String>? = null
    private val ncCounter = AtomicInteger(1)

    /**
     * 重設快取狀態與序號計數器 (Reset cached params and counter).
     */
    fun reset() {
        lastDigestParams = null
        ncCounter.set(1)
    }

    override fun authenticate(route: Route?, response: Response): Request? {
        val username = usernameProvider()
        val password = passwordProvider()
        if (username.isBlank() || password.isBlank()) return null

        // 避免因密碼錯誤導致無窮重試，最多嘗試 3 次 (Avoid infinite loop, max 3 retries)
        if (responseCount(response) >= 3) return null

        val authHeaders = response.headers("WWW-Authenticate")
        if (authHeaders.isEmpty()) return null

        // 1. 優先處理 Digest Auth 挑戰 (Prefer Digest Auth challenge)
        val digestHeader = authHeaders.firstOrNull { it.startsWith("Digest", ignoreCase = true) }
        if (digestHeader != null) {
            val params = parseAuthParams(digestHeader.substringAfter("Digest").trim())
            lastDigestParams = params
            val request = response.request
            val digestAuthHeader = buildDigestAuthorization(
                username = username,
                password = password,
                method = request.method,
                uri = getDigestUri(request),
                params = params,
                nc = "%08x".format(ncCounter.getAndIncrement()),
                cnonce = generateCnonce()
            )
            return request.newBuilder()
                .header("Authorization", digestAuthHeader)
                .build()
        }

        // 2. 次選處理 Basic Auth 挑戰 (Fallback to Basic Auth challenge)
        val basicHeader = authHeaders.firstOrNull { it.startsWith("Basic", ignoreCase = true) }
        if (basicHeader != null) {
            val credential = Credentials.basic(username, password)
            if (credential == response.request.header("Authorization")) {
                // 已嘗試過相同 Basic 帳密仍失敗，停止重試 (Stop retrying if identical credential failed)
                return null
            }
            return response.request.newBuilder()
                .header("Authorization", credential)
                .build()
        }

        return null
    }

    /**
     * 產生命中快取的預發 Digest 授權頭 (Create preemptive Digest Authorization header).
     *
     * @param method HTTP 請求方法 (HTTP request method)
     * @param urlString 完整目標網址 (Full target URL string)
     * @return 授權請求頭字串，若無快取則回傳 null (Authorization header string, or null if no cache)
     */
    fun createPreemptiveHeader(method: String, urlString: String): String? {
        val params = lastDigestParams ?: return null
        val username = usernameProvider()
        val password = passwordProvider()
        if (username.isBlank() || password.isBlank()) return null

        val parsedUrl = urlString.toHttpUrlOrNull()
        val uri = if (parsedUrl != null) {
            val path = parsedUrl.encodedPath
            val query = parsedUrl.encodedQuery
            if (query != null) "$path?$query" else path
        } else {
            "/"
        }

        return buildDigestAuthorization(
            username = username,
            password = password,
            method = method,
            uri = uri,
            params = params,
            nc = "%08x".format(ncCounter.getAndIncrement()),
            cnonce = generateCnonce()
        )
    }

    private fun getDigestUri(request: Request): String {
        val url = request.url
        val path = url.encodedPath
        val query = url.encodedQuery
        return if (query != null) "$path?$query" else path
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }

    private fun parseAuthParams(headerValue: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        val regex = Regex("""(\w+)\s*=\s*(?:"([^"]*)"|([^,\s]+))""")
        for (match in regex.findAll(headerValue)) {
            val key = match.groupValues[1].lowercase()
            val value = match.groupValues[2].ifEmpty { match.groupValues[3] }
            params[key] = value
        }
        return params
    }

    private fun buildDigestAuthorization(
        username: String,
        password: String,
        method: String,
        uri: String,
        params: Map<String, String>,
        nc: String,
        cnonce: String
    ): String {
        val realm = params["realm"].orEmpty()
        val nonce = params["nonce"].orEmpty()
        val qop = params["qop"].orEmpty()
        val opaque = params["opaque"]
        val algorithm = params["algorithm"] ?: "MD5"

        val ha1 = md5Hex("$username:$realm:$password")
        val ha2 = md5Hex("$method:$uri")

        val hasAuthQop = qop.split(",").map { it.trim().lowercase() }.contains("auth")

        val responseStr = if (hasAuthQop) {
            md5Hex("$ha1:$nonce:$nc:$cnonce:auth:$ha2")
        } else {
            md5Hex("$ha1:$nonce:$ha2")
        }

        return buildString {
            append("Digest ")
            append("username=\"$username\", ")
            append("realm=\"$realm\", ")
            append("nonce=\"$nonce\", ")
            append("uri=\"$uri\", ")
            append("response=\"$responseStr\"")
            if (hasAuthQop) {
                append(", qop=auth, nc=$nc, cnonce=\"$cnonce\"")
            }
            if (!opaque.isNullOrBlank()) {
                append(", opaque=\"$opaque\"")
            }
            if (algorithm.isNotBlank()) {
                append(", algorithm=\"$algorithm\"")
            }
        }
    }

    private fun generateCnonce(): String {
        return UUID.randomUUID().toString().replace("-", "").take(16)
    }

    private fun md5Hex(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
