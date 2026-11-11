package app.gitex

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.webkit.MimeTypeMap
import android.widget.TextView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import io.noties.markwon.ext.tables.TablesPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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

/** Fit-to-screen image with smooth pinch-zoom, pan and double-tap. Decoded down to at most 2048px, so large photos stay light. */
@Composable
fun ImageViewer(path: String, modifier: Modifier = Modifier) {
    var failed by remember(path) { mutableStateOf(false) }
    val bmp by produceState<ImageBitmap?>(null, path) {
        val b = withContext(Dispatchers.IO) { runCatching { decodeScaled(path) }.getOrNull() }
        if (b == null) failed = true else value = b.asImageBitmap()
    }
    var scale by remember(path) { mutableFloatStateOf(1f) }
    var offset by remember(path) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    fun clamp(o: Offset, s: Float): Offset {
        val mx = size.width * (s - 1f) / 2f
        val my = size.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Gx.bg)
            .interactive()
            .onSizeChanged { size = it }
            .pointerInput(path) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val ns = (scale * zoom).coerceIn(1f, 8f)
                    scale = ns
                    offset = if (ns == 1f) Offset.Zero else clamp(offset + pan, ns)
                }
            }
            .pointerInput(path) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1.05f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                })
            },
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        when {
            b != null -> Image(
                b, null,
                Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale
                    translationX = offset.x; translationY = offset.y
                },
                contentScale = ContentScale.Fit
            )
            failed -> GText("Couldn't open this image", color = Gx.textDim)
            else -> GSpinner()
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
            .usePlugin(TablesPlugin.create(ctx))
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