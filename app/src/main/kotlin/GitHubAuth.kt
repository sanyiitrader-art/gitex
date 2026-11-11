package app.gitex

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException

/** Tiny HTTPS helper shared by auth (now) and the GitHub API client (Phase 2). Never logs headers or bodies. */
object Http {
    class Resp(val code: Int, val body: String, val headers: Map<String, List<String>>)

    suspend fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        timeoutMs: Int = 20_000,
    ): Resp = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "Gitex-Android")
            for ((k, v) in headers) c.setRequestProperty(k, v)
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val h = HashMap<String, List<String>>()
            for ((k, v) in c.headerFields) if (k != null) h[k.lowercase()] = v
            Resp(code, text, h)
        } finally {
            c.disconnect()
        }
    }

    fun describe(e: Exception): String = when (e) {
        is UnknownHostException -> "No internet connection"
        is SocketTimeoutException -> "The connection timed out"
        is IOException -> "Network problem: ${e.message ?: "connection failed"}"
        else -> e.message ?: "Something went wrong"
    }
}

/**
 * GitHub OAuth Device Flow: no client secret is needed, so nothing confidential ships in the APK.
 * The token is stored encrypted with an Android Keystore key (see Prefs / SecureStore).
 */
object GitHubAuth {
    /** Public OAuth client id. The workflow can override it with the GITEX_CLIENT_ID repo variable. */
    private const val DEFAULT_CLIENT_ID = "Ov23liKP6ILxzLnJKgCV"

    val clientId: String get() = BuildConfig.GITHUB_CLIENT_ID.ifBlank { DEFAULT_CLIENT_ID }

    /** repo: push/commit/read Actions logs and artifacts. workflow: needed to push workflow files. */
    const val SCOPE = "repo workflow"

    class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val expiresIn: Int,
        val interval: Int,
    )

    sealed class Poll {
        class Token(val token: String) : Poll()
        object Denied : Poll()
        object Expired : Poll()
        class Error(val msg: String) : Poll()
    }

    private fun form(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) -> "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}" }

    private val formHeaders = mapOf(
        "Accept" to "application/json",
        "Content-Type" to "application/x-www-form-urlencoded",
    )

    /** Starts the flow. Throws an Exception with a readable message on failure. */
    suspend fun requestCode(): Devi