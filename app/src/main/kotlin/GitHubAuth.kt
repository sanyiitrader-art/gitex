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
    private const val DEFAULT_CLIENT_ID = "Ov23liFijOTyYvaxiFOa"

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
    suspend fun requestCode(): DeviceCode {
        val r = try {
            Http.request(
                "POST", "https://github.com/login/device/code", formHeaders,
                form("client_id" to clientId, "scope" to SCOPE)
            )
        } catch (e: Exception) {
            throw Exception(Http.describe(e))
        }
        val j = try {
            JSONObject(r.body)
        } catch (_: Exception) {
            throw Exception("GitHub sent an unexpected answer (HTTP ${r.code})")
        }
        if (j.has("error")) {
            throw Exception(
                when (j.optString("error")) {
                    "device_flow_disabled" -> "Device Flow is turned off for this OAuth App. Enable it in the app's settings on GitHub."
                    "incorrect_client_credentials" -> "GitHub doesn't recognise this OAuth client id."
                    else -> j.optString("error_description").ifBlank { j.optString("error") }
                }
            )
        }
        return DeviceCode(
            deviceCode = j.getString("device_code"),
            userCode = j.getString("user_code"),
            verificationUri = j.optString("verification_uri", "https://github.com/login/device"),
            expiresIn = j.optInt("expires_in", 900),
            interval = j.optInt("interval", 5).coerceAtLeast(5),
        )
    }

    /** Waits (cancellable) until the user authorizes, denies, or the code expires. */
    suspend fun pollForToken(dc: DeviceCode): Poll {
        val deadline = SystemClock.elapsedRealtime() + dc.expiresIn * 1000L
        var interval = dc.interval
        var failures = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            delay(interval * 1000L)
            val r = try {
                Http.request(
                    "POST", "https://github.com/login/oauth/access_token", formHeaders,
                    form(
                        "client_id" to clientId,
                        "device_code" to dc.deviceCode,
                        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"
                    )
                )
            } catch (e: Exception) {
                if (++failures >= 6) return Poll.Error(Http.describe(e))
                continue
            }
            failures = 0
            val j = try {
                JSONObject(r.body)
            } catch (_: Exception) {
                continue
            }
            val token = j.optString("access_token")
            if (token.isNotEmpty()) return Poll.Token(token)
            when (val err = j.optString("error")) {
                "authorization_pending" -> {}
                "slow_down" -> interval = j.optInt("interval", interval + 5).coerceAtLeast(interval + 5)
                "expired_token" -> return Poll.Expired
                "access_denied" -> return Poll.Denied
                "" -> {}
                else -> return Poll.Error(j.optString("error_description").ifBlank { err })
            }
        }
        return Poll.Expired
    }

    /** The account's login name, or null if the lookup fails. */
    suspend fun fetchLogin(token: String): String? = try {
        val r = Http.request(
            "GET", "https://api.github.com/user",
            mapOf("Authorization" to "Bearer $token", "Accept" to "application/vnd.github+json")
        )
        if (r.code == 200) JSONObject(r.body).optString("login").ifBlank { null } else null
    } catch (_: Exception) {
        null
    }

    /**
     * Disconnect removes the token from this phone. GitHub only lets an app revoke its own token
     * with the client secret, which the app deliberately doesn't have, so the settings screen
     * also points to github.com/settings/applications where it can be revoked for good.
     */
    fun disconnect() {
        Prefs.clearGithubToken()
    }
}