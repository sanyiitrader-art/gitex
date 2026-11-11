package app.gitex

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** File classification and icon keys. Icon PNGs live in assets/file-icons/<key>.png (see scripts/fetch-icons.sh). */
object FileKinds {
    private val images = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif", "ico")
    private val archives = setOf("zip", "tar", "gz", "tgz", "bz2", "7z", "rar", "xz", "jar", "aar", "apk", "aab")
    private val audio = setOf("mp3", "wav", "ogg", "flac", "m4a", "aac", "opus")
    private val video = setOf("mp4", "mkv", "avi", "mov", "webm", "3gp")
    private val binaries = archives + audio + video + setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp",
        "so", "dex", "class", "bin", "exe", "dll", "ttf", "otf", "woff", "woff2", "db", "sqlite", "iso"
    )

    fun ext(name: String): String {
        val i = name.lastIndexOf('.')
        return if (i >= 0 && i < name.length - 1) name.substring(i + 1).lowercase() else ""
    }

    fun kindOf(name: String): FileKind {
        val e = ext(name)
        return when {
            e in images -> FileKind.IMAGE
            e in binaries -> FileKind.BINARY
            else -> FileKind.TEXT
        }
    }

    fun iconKey(name: String): String {
        Languages.forName(name)?.let { return it.iconKey }
        val e = ext(name)
        return when {
            e == "log" -> "log"
            e == "pdf" -> "pdf"
            e == "doc" || e == "docx" -> "docx"
            e in images -> "image"
            e in archives -> "archive"
            e in audio -> "audio"
            e in video -> "video"
            e == "txt" -> "text"
            else -> "file"
        }
    }
}

private object IconCache {
    private val cache = LruCache<String, ImageBitmap>(96)
    private val missing = HashSet<String>()

    @Synchronized
    fun peek(key: String): ImageBitmap? = cache.get(key)

    suspend fun load(ctx: Context, key: String): ImageBitmap? = withContext(Dispatchers.IO) {
        peek(key)?.let { return@withContext it }
        synchronized(this@IconCache) { if (key in missing) return@withContext null }
        val bmp = try {
            ctx.assets.open("file-icons/$key.png").use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
        } catch (_: Exception) {
            null
        }
        synchronized(this@IconCache) {
            if (bmp != null) cache.put(key, bmp) else missing.add(key)
        }
        bmp
    }
}

/** Real logo for the file's language/format; generic file icon only when no logo exists. */
@Composable
fun FileIcon(name: String, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    val key = FileKinds.iconKey(name)
    val ctx = LocalContext.current
    val bmp by produceState<ImageBitmap?>(IconCache.peek(key), key) {
        if (value == null) value = IconCache.load(ctx, key) ?: IconCache.load(ctx, "file")
    }
    val b = bmp
    if (b != null) {
        Image(b, null, modifier.size(size), filterQuality = FilterQuality.Medium)
    } else {
        GIcon(Ico.File, modifier, size, Gx.textDim)
    }
}

private object FolderGlyphs {
    private val map: Map<String, ImageVector> by lazy {
        mapOf(
            "github" to Ico.Github, "test" to Ico.Flask, "main" to Ico.Home, "src" to Ico.Code,
            "game" to Ico.Gamepad, "ui" to Ico.Dashboard, "assets" to Ico.Image, "build" to Ico.Wrench,
            "dist" to Ico.Archive, "docs" to Ico.File, "lib" to Ico.Book, "config" to Ico.Gear,
            "components" to Ico.Widgets, "scripts" to Ico.Terminal, "data" to Ico.Database,
            "plugins" to Ico.Extension, "resources" to Ico.Archive, "fonts" to Ico.FontA,
            "images" to Ico.Image, "audio" to Ico.Music, "video" to Ico.Play, "public" to Ico.Globe,
            "android" to Ico.Robot, "ios" to Ico.Phone, "web" to Ico.Globe, "vendor" to Ico.Archive,
        )
    }

    fun of(name: String): ImageVector? = map[name.lowercase().removePrefix(".")]
}

/** Folder body in the current accent colour with a contrasting symbol for well-known names. */
@Composable
fun FolderIcon(name: String, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val accent = Gx.accent
    val glyph = remember(name) { FolderGlyphs.of(name) }
    val fg = if (accent.luminance() > 0.55f) Color.Black else Color.White
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = this.size.width
            val h = this.size.height
            drawRoundRect(
                accent, Offset(w * 0.05f, h * 0.14f), Size(w * 0.42f, h * 0.30f),
                CornerRadius(w * 0.09f)
            )
            drawRoundRect(
                accent, Offset(w * 0.05f, h * 0.26f), Size(w * 0.90f, h * 0.62f),
                CornerRadius(w * 0.12f)
            )
        }
        if (glyph != null) {
            GIcon(glyph, Modifier.offset(y = size * 0.06f), size * 0.46f, fg)
        }
    }
}