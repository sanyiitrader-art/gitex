package app.gitex

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.reflect.KProperty

/**
 * A setting backed by SharedPreferences that is also Compose state, so screens
 * recompose when it changes. Reads the stored value lazily on first access.
 */
class PrefValue<T>(
    private val key: String,
    private val default: T,
    private val read: (SharedPreferences, String, T) -> T,
    private val write: (SharedPreferences.Editor, String, T) -> Unit,
) {
    private var state: MutableState<T>? = null

    private fun st(): MutableState<T> =
        state ?: mutableStateOf(read(Prefs.sp, key, default)).also { state = it }

    operator fun getValue(thisRef: Any?, property: KProperty<*>): T = st().value

    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        st().value = value
        val e = Prefs.sp.edit()
        write(e, key, value)
        e.apply()
    }
}

data class RepoLink(val owner: String, val repo: String, val branch: String)

object Prefs {
    internal lateinit var sp: SharedPreferences
    private var ready = false

    private const val MAX_RECENTS = 30
    private const val K_TOKEN = "github_token"
    private const val K_GEMINI = "gemini_key"

    private fun boolP(k: String, d: Boolean) = PrefValue<Boolean>(
        k, d, { s, key, def -> s.getBoolean(key, def) }, { e, key, v -> e.putBoolean(key, v) })

    private fun intP(k: String, d: Int) = PrefValue<Int>(
        k, d, { s, key, def -> s.getInt(key, def) }, { e, key, v -> e.putInt(key, v) })

    private fun longP(k: String, d: Long) = PrefValue<Long>(
        k, d, { s, key, def -> s.getLong(key, def) }, { e, key, v -> e.putLong(key, v) })

    private fun floatP(k: String, d: Float) = PrefValue<Float>(
        k, d, { s, key, def -> s.getFloat(key, def) }, { e, key, v -> e.putFloat(key, v) })

    private fun strP(k: String, d: String) = PrefValue<String>(
        k, d, { s, key, def -> s.getString(key, def) ?: def }, { e, key, v -> e.putString(key, v) })

    // ---- first launch ----
    var onboardingDone by boolP("onboarding_done", false)
    var welcomeDone by boolP("welcome_done", false)

    // ---- editor ----
    var fontSize by floatP("font_size", 14f)
    var fontKey by strP("font_key", "jetbrains_mono")
    var syntaxHighlight by boolP("syntax_highlight", true)
    var smartActions by boolP("smart_actions", true)
    var indentGuides by boolP("indent_guides", true)
    var indentWidth by intP("indent_width", 4)
    var bracketHighlight by boolP("bracket_highlight", true)
    var highlightCurrentLine by boolP("highlight_current_line", true)
    var autosave by boolP("autosave", false)

    // ---- appearance ----
    var themeId by strP("theme_id", "warm_ember")
    /** 0 = cool blue, 0.5 = the theme's own colours, 1 = warm brown/orange. */
    var uiTemperature by floatP("ui_temperature", 0.5f)
    var syntaxThemeId by strP("syntax_theme", "dark_plus")

    // ---- GitHub / Git ----
    var githubUrl by strP("github_url", "")
    var gitName by strP("git_name", "")
    var gitEmail by strP("git_email", "")
    var buildNotifications by boolP("build_notifications", true)
    var duplicateWarnings by boolP("duplicate_warnings", true)
    /** When GitHub says the saved sign-in ends (epoch ms), or 0 if it doesn't expire. */
    var tokenExpiresAt by longP("token_expires_at", 0L)

    // ---- workspace ----
    var projectRoot by strP("project_root", "")
    var lastOpenFile by strP("last_open_file", "")

    // ---- observable state loaded in init() ----
    val recents = mutableStateListOf<String>()
    private val links = mutableStateMapOf<String, RepoLink>()
    var githubConnected by mutableStateOf(false)
        private set

    fun init(ctx: Context) {
        if (ready) return
        sp = ctx.applicationContext.getSharedPreferences("gitex_prefs", Context.MODE_PRIVATE)
        loadRecents()
        loadLinks()
        githubConnected = SecureStore.get(K_TOKEN) != null
        ready = true
    }

    // ---- recent files (newest first) ----
    fun addRecent(path: String) {
        recents.remove(path)
        recents.add(0, path)
        while (recents.size > MAX_RECENTS) recents.removeAt(recents.lastIndex)
        saveRecents()
    }

    fun removeRecent(path: String) {
        if (recents.remove(path)) saveRecents()
    }

    fun replaceRecent(old: String, new: String) {
        val i = recents.indexOf(old)
        if (i >= 0) {
            recents.remove(new)
            val j = recents.indexOf(old)
            recents[j] = new
            saveRecents()
        }
    }

    private fun loadRecents() {
        recents.clear()
        try {
            val arr = JSONArray(sp.getString("recents", "[]"))
            for (i in 0 until arr.length()) recents.add(arr.getString(i))
        } catch (_: Exception) {
        }
    }

    private fun saveRecents() {
        sp.edit().putString("recents", JSONArray(recents.toList()).toString()).apply()
    }

    // ---- repository association per project folder ----
    fun repoLink(root: String): RepoLink? = links[root]

    /** All associations, used for the global artifact history. */
    fun allRepoLinks(): List<RepoLink> = links.values.toList()

    fun setRepoLink(root: String, link: RepoLink?) {
        if (link == null) links.remove(root) else links[root] = link
        val o = JSONObject()
        for ((k, v) in links) {
            o.put(k, JSONObject().put("owner", v.owner).put("repo", v.repo).put("branch", v.branch))
        }
        sp.edit().putString("repo_links", o.toString()).apply()
    }

    private fun loadLinks() {
        links.clear()
        try {
            val o = JSONObject(sp.getString("repo_links", "{}"))
            for (k in o.keys()) {
                val v = o.getJSONObject(k)
                links[k] = RepoLink(v.optString("owner"), v.getString("repo"), v.optString("branch", "main"))
            }
        } catch (_: Exception) {
        }
    }

    // ---- secrets (Android Keystore, never plain text) ----
    fun githubToken(): String? = SecureStore.get(K_TOKEN)

    fun saveGithubToken(token: String) {
        SecureStore.put(K_TOKEN, token)
        tokenExpiresAt = GitHubAuth.takePendingExpiry()
        githubConnected = SecureStore.get(K_TOKEN) != null
    }

    fun clearGithubToken() {
        SecureStore.put(K_TOKEN, null)
        tokenExpiresAt = 0L
        githubConnected = false
    }

    fun geminiKey(): String? = SecureStore.get(K_GEMINI)

    fun saveGeminiKey(key: String?) {
        SecureStore.put(K_GEMINI, key?.trim()?.ifEmpty { null })
    }
}

/**
 * AES-256-GCM with a key that lives in the Android Keystore. Values are stored as Base64(iv + ciphertext).
 * Decrypted values are kept in memory. A value is only deleted when it is truly unreadable
 * (wrong key / damaged data), never because of a temporary Keystore error.
 */
object SecureStore {
    private const val ALIAS = "gitex_secret_key_v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LEN = 12

    private val cache = HashMap<String, String>()

    private val sp: SharedPreferences
        get() = App.instance.getSharedPreferences("gitex_secure", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    @Synchronized
    fun put(name: String, value: String?) {
        val e = sp.edit()
        if (value == null) {
            e.remove(name)
            cache.remove(name)
        } else {
            try {
                val c = Cipher.getInstance(TRANSFORM)
                c.init(Cipher.ENCRYPT_MODE, key())
                val ct = c.doFinal(value.toByteArray(Charsets.UTF_8))
                e.putString(name, Base64.encodeToString(c.iv + ct, Base64.NO_WRAP))
                cache[name] = value
            } catch (_: Exception) {
                return // never fall back to storing a secret unencrypted
            }
        }
        e.apply()
    }

    @Synchronized
    fun get(name: String): String? {
        cache[name]?.let { return it }
        val stored = sp.getString(name, null) ?: return null
        return try {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, IV_LEN))
            val v = String(c.doFinal(raw, IV_LEN, raw.size - IV_LEN), Charsets.UTF_8)
            cache[name] = v
            v
        } catch (_: GeneralSecurityException) {
            // The key no longer matches the data: it can never be read again, so drop it.
            sp.edit().remove(name).apply()
            null
        } catch (_: Exception) {
            // Temporary problem (Keystore busy ...): keep the stored value and try again next time.
            null
        }
    }
}

/** Input validation shared by dialogs and settings. Each *Error function returns null when valid. */
object Validate {
    private val reservedAccounts = setOf(
        "settings", "orgs", "login", "logout", "marketplace", "explore", "features", "pricing",
        "about", "topics", "trending", "notifications", "new", "codespaces", "sponsors", "apps",
        "organizations", "search", "pulls", "issues", "join", "sessions"
    )
    private val accountRe = Regex("^[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*$")
    private val repoRe = Regex("^[A-Za-z0-9._-]+$")

    /**
     * Accepts "https://github.com/user", "github.com/user", "www.github.com/user/".
     * Returns the normalised "https://github.com/user", or null for anything else
     * (including repository URLs such as .../user/repo or .../user/repo.git).
     */
    fun accountUrl(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        s = s.replaceFirst(Regex("^https?://", RegexOption.IGNORE_CASE), "")
        s = s.replaceFirst(Regex("^www\\.", RegexOption.IGNORE_CASE), "")
        if (!s.startsWith("github.com/", ignoreCase = true)) return null
        val name = s.substring("github.com/".length).trimEnd('/')
        if (name.isEmpty() || name.contains('/') || name.contains('?') || name.contains('#')) return null
        if (name.length > 39 || !accountRe.matches(name)) return null
        if (name.lowercase() in reservedAccounts) return null
        return "https://github.com/$name"
    }

    fun accountUrlError(raw: String): String? =
        if (accountUrl(raw) != null) null
        else "Enter your account URL, like https://github.com/username (not a repository URL)"

    fun accountName(url: String): String = url.trimEnd('/').substringAfterLast('/')

    fun repoNameError(raw: String): String? {
        val n = raw.trim()
        if (n.isEmpty()) return "Repository name can't be empty"
        if (n.length > 100) return "Repository name is too long"
        if (n == "." || n == "..") return "Invalid repository name"
        if (n.endsWith(".git")) return "Leave out the .git ending"
        if (!repoRe.matches(n)) return "Use only letters, numbers, dots, dashes and underscores"
        return null
    }

    fun branchError(raw: String): String? {
        val b = raw.trim()
        if (b.isEmpty()) return "Branch name can't be empty"
        if (b.length > 200) return "Branch name is too long"
        if (b.any { it.isWhitespace() || it.code < 32 || it.code == 127 || it in "~^:?*[\\" })
            return "Branch names can't contain spaces or ~ ^ : ? * [ \\"
        if (b.startsWith("/") || b.endsWith("/") || b.contains("//") || b.startsWith("-"))
            return "Invalid branch name"
        if (b.contains("..") || b.contains("@{") || b == "@")
            return "Invalid branch name"
        if (b.endsWith(".") || b.split("/").any { it.startsWith(".") || it.endsWith(".lock") })
            return "Invalid branch name"
        return null
    }
}