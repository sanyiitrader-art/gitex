package app.gitex

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.webkit.MimeTypeMap
import android.widget.TextView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Hands a file Gitex can't edit (PDF, archive, audio...) to another app via Android's "Open with". */
fun openExternally(ctx: Context, f: File) {
    try {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(FileKinds.ext(f.name)) ?: "*/*"
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(view, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        Banners.show("No app on this phone can open that file", BannerKind.Error)
    }
}

private const val MAX_SIDE = 2048

private fun decodeScaled(path: String): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > MAX_SIDE || bounds.outHeight / sample > MAX_SIDE) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeFile(path, opts)
}

/**
 * Fit-to-screen image with smooth pinch-zoom, pan and double-tap. Decoded down to at most 2048px.
 * [reload] changes when the file was replaced, so the picture is read again. If the image can't be shown
 * (for example an empty placeholder file), a Replace button lets the user pick a real picture.
 */
@Composable
fun ImageViewer(
    path: String,
    reload: Int = 0,
    modifier: Modifier = Modifier,
    onReplace: (() -> Unit)? = null,
) {
    var failed by remember(path, reload) { mutableStateOf(false) }
    val bmp by produceState<ImageBitmap?>(null, path, reload) {
        val b = withContext(Dispatchers.IO) { runCatching { decodeScaled(path) }.getOrNull() }
        if (b == null) failed = true else value = b.asImageBitmap()
    }
    val empty = remember(path, reload, failed) { failed && File(path).length() == 0L }
    var scale by remember(path, reload) { mutableFloatStateOf(1f) }
    var offset by remember(path, reload) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    fun clamp(o: Offset, s: Float): Offset {
        val mx = size.width * (s - 1f) / 2f
        val my = size.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }

    val b = bmp
    var gestures: Modifier = Modifier
    if (b != null) {
        gestures = Modifier
            .pointerInput(path, reload) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val ns = (scale * zoom).coerceIn(1f, 8f)
                    scale = ns
                    offset = if (ns == 1f) Offset.Zero else clamp(offset + pan, ns)
                }
            }
            .pointerInput(path, reload) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1.05f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                })
            }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Gx.bg)
            .interactive()
            .onSizeChanged { size = it }
            .then(gestures),
        contentAlignment = Alignment.Center
    ) {
        when {
            b != null -> Image(
                b, null,
                Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale
                    translationX = offset.x; translationY = offset.y
                },
                contentScale = ContentScale.Fit
            )
            failed -> Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                GText("Couldn't open this image", color = Gx.textDim)
                if (empty) GText("The file has no data yet.", style = Ty.small, color = Gx.textDim)
                if (onReplace != null) GButton("Replace", onClick = onReplace)
            }
            else -> GSpinner()
        }
    }
}

// ---------------------------------------------------------------------------
// Replacing an empty / broken image file with a real picture
// ---------------------------------------------------------------------------

/** [target] is the image file to be replaced; [picked] is the picture the user chose in the system picker. */
object ReplaceState {
    var target by mutableStateOf<File?>(null)
    var picked by mutableStateOf<Uri?>(null)
}

class ReplaceOutcome(val file: File, val message: String)

object ImageReplace {
    fun displayName(ctx: Context, uri: Uri): String {
        var name = ""
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) name = it.getString(0).orEmpty()
            }
        } catch (_: Exception) {
        }
        if (name.isBlank()) name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':').orEmpty()
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
    }

    /** Writes to a temporary file in the same folder, then swaps it in, so a failure never damages anything. */
    private fun copyInto(ctx: Context, uri: Uri, dest: File) {
        val dir = dest.parentFile ?: throw IOException("No parent folder")
        val tmp = File(dir, ".${dest.name}.gitex-tmp")
        var total = 0L
        try {
            val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("Couldn't read the chosen picture")
            input.use { ins ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                    }
                    out.flush()
                    out.fd.sync()
                }
            }
            if (total == 0L) throw IOException("The chosen file has no data")
            try {
                Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun removeOriginal(ctx: Context, uri: Uri): Boolean {
        try {
            val f = Fs.pathFromDoc(ctx, uri)
            if (f != null && f.delete()) return true
        } catch (_: Exception) {
        }
        return try { DocumentsContract.deleteDocument(ctx.contentResolver, uri) } catch (_: Exception) { false }
    }

    /**
     * keepOldName = true: the picture is saved under the placeholder's name (the empty file is replaced).
     * keepOldName = false: it is saved under its own name and the empty placeholder is removed.
     */
    fun apply(ctx: Context, uri: Uri, target: File, keepOldName: Boolean, deleteOriginal: Boolean): Result<ReplaceOutcome> = runCatching {
        val parent = target.parentFile ?: throw IOException("No parent folder")
        var note = ""
        val dest = if (keepOldName) target else {
            val name = displayName(ctx, uri).ifBlank { target.name }
            var d = File(parent, name)
            if (d.exists() && d.absolutePath != target.absolutePath) {
                d = File(parent, FileOps.uniqueCopyName(parent, name, false))
                note = " A file named \"$name\" already existed, so it was saved as \"${d.name}\"."
            }
            d
        }
        copyInto(ctx, uri, dest)
        if (dest.absolutePath != target.absolutePath) target.delete()
        var msg = "Image replaced.$note"
        if (deleteOriginal) msg += if (removeOriginal(ctx, uri)) " The original was deleted." else " The original couldn't be deleted."
        ReplaceOutcome(dest, msg)
    }
}

/** Shows the Replace dialog whenever a picture was chosen for a placeholder image. */
@Composable
fun ReplaceImageDialog(ws: Workspace) {
    val target = ReplaceState.target
    val uri = ReplaceState.picked
    if (target == null || uri == null) return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var deleteOriginal by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val newName by produceState("", uri) { value = withContext(Dispatchers.IO) { ImageReplace.displayName(ctx, uri) } }

    fun close() { ReplaceState.target = null; ReplaceState.picked = null }

    fun run(keepOld: Boolean) {
        scope.launch {
            busy = true
            val r = withContext(Dispatchers.IO) { ImageReplace.apply(ctx, uri, target, keepOld, deleteOriginal) }
            busy = false
            close()
            r.onSuccess { out ->
                ws.bump()
                if (out.file.absolutePath != target.absolutePath) {
                    ws.deleted(target)
                    openPath(ctx, ws, out.file.absolutePath)
                }
                Banners.show(out.message, BannerKind.Success)
            }.onFailure { Banners.show(Fs.friendly(it), BannerKind.Error) }
        }
    }

    GDialog(
        onDismiss = { if (!busy) close() },
        title = "Replace image",
        dismissOnOutside = false,
        cancel = DialogBtn("Cancel", { if (!busy) close() }, enabled = !busy),
        actions = listOf(
            DialogBtn("Keep old name", { run(true) }, enabled = !busy),
            DialogBtn("Use new name", { run(false) }, primary = true, enabled = !busy),
        )
    ) {
        GText(
            "You chose \"${newName.ifBlank { "your picture" }}\" for \"${target.name}\".",
            style = Ty.small, color = Gx.textDim
        )
        Spacer(Modifier.height(10.dp))
        GText(
            "Keep old name: the picture is saved as \"${target.name}\", replacing the empty file.\n\n" +
                "Use new name: the picture is saved as \"${newName.ifBlank { "its own name" }}\" and the empty \"${target.name}\" is removed.",
            style = Ty.small
        )
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.pressable(scale = 0.98f, onTap = { deleteOriginal = !deleteOriginal }),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GIcon(if (deleteOriginal) Ico.CheckOn else Ico.CheckOff, size = 22.dp, tint = if (deleteOriginal) Gx.accent else Gx.textDim)
            Spacer(Modifier.width(10.dp))
            GText("Also delete the original after copying", style = Ty.small, maxLines = 2)
        }
        if (busy) {
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                GSpinner(size = 18.dp)
                Spacer(Modifier.width(10.dp))
                GText("Saving…", style = Ty.small, color = Gx.textDim)
            }
        }
    }
}

/**
 * Markdown preview. Markwon's core renderer never executes scripts and Gitex does not enable
 * its HTML plugin, so raw HTML inside the Markdown is not rendered. Parsing happens off the main thread.
 */
@Composable
fun MarkdownPreview(text: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val markwon = remember {
        Markwon.builder(ctx)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(ctx))
            .build()
    }
    val parsed by produceState<Spanned?>(null, text) {
        value = withContext(Dispatchers.Default) { markwon.toMarkdown(text) }
    }
    val fg = Gx.text.toArgb()
    val link = Gx.accent.toArgb()
    Box(modifier.fillMaxSize().background(Gx.bg).verticalScroll(rememberScrollState())) {
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(16.dp).interactive(),
            factory = { c ->
                TextView(c).apply {
                    setTextIsSelectable(true)
                    textSize = 15.5f
                    setLineSpacing(0f, 1.18f)
                    movementMethod = LinkMovementMethod.getInstance()
                }
            },
            update = { tv ->
                tv.setTextColor(fg)
                tv.setLinkTextColor(link)
                parsed?.let { markwon.setParsedMarkdown(tv, it) }
            }
        )
    }
}