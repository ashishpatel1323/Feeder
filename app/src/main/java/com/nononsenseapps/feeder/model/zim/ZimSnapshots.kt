package com.nononsenseapps.feeder.model.zim

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

/**
 * A picture of a book's landing page, for the cover of a ZIM that has no icon of its own (most
 * sites made with zimit). Drawn once in an off-screen WebView fed from the file, the same way the
 * reader shows pages, and kept as a small JPEG in the app's files until the file changes.
 */
class ZimSnapshots private constructor(
    private val context: Context,
) {
    private val dir = File(context.filesDir, "zim-covers").apply { mkdirs() }
    private val lock = Mutex()

    private fun fileFor(book: ZimBook) = File(dir, "${book.id}-${book.size}.jpg")

    private fun noneFor(book: ZimBook) = File(dir, "${book.id}-${book.size}.none")

    /** The snapshot when it's already made, without drawing one. */
    fun cached(book: ZimBook): Bitmap? = fileFor(book).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    /** The landing page as a picture, drawn the first time; null when the page can't be shown. */
    suspend fun snapshot(
        library: ZimLibrary,
        book: ZimBook,
    ): Bitmap? {
        cached(book)?.let { return it }
        if (noneFor(book).exists() || !ZimNative.available) return null
        // One WebView at a time: each holds a renderer
        return lock.withLock {
            cached(book)?.let { return@withLock it }
            val path = withContext(Dispatchers.IO) { runCatching { ZimWebContent.mainPath(library.archive(book.id)) }.getOrNull() }
            val bitmap = path?.let { withTimeoutOrNull(TIMEOUT_MS) { draw(library, ZimWebContent.url(book.id, it)) } }
            withContext(Dispatchers.IO) {
                // Drop stale snapshots of an earlier copy of this file
                dir.listFiles { f -> f.name.startsWith("${book.id}-") }?.forEach { it.delete() }
                if (bitmap != null && !blank(bitmap)) {
                    fileFor(book).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                } else {
                    Log.i(LOG_TAG, "No snapshot for ${book.id}")
                    noneFor(book).createNewFile()
                }
            }
            bitmap?.takeUnless { blank(it) }
        }
    }

    /** Forgets a book's snapshot (the book was deleted). */
    fun forget(id: String) {
        dir.listFiles { f -> f.name.startsWith("$id-") }?.forEach { it.delete() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun draw(
        library: ZimLibrary,
        url: String,
    ): Bitmap? =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val handler = Handler(Looper.getMainLooper())
                val web = WebView(context)
                var done = false

                fun finish(result: Bitmap?) {
                    if (done) return
                    done = true
                    handler.removeCallbacksAndMessages(null)
                    web.stopLoading()
                    web.destroy()
                    if (cont.isActive) cont.resume(result)
                }
                cont.invokeOnCancellation { handler.post { finish(null) } }
                web.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                web.setBackgroundColor(Color.WHITE)
                web.settings.javaScriptEnabled = true
                web.settings.domStorageEnabled = true
                web.settings.allowFileAccess = false
                web.settings.allowContentAccess = false
                web.settings.blockNetworkLoads = true
                web.webViewClient =
                    object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse? =
                            if (request.url.host == ZimWebContent.HOST) {
                                ZimWebContent.respond(library, request)
                            } else {
                                WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), null)
                            }

                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ) = request.url.host != ZimWebContent.HOST

                        override fun onPageFinished(
                            view: WebView,
                            url: String,
                        ) {
                            // Give scripts, fonts and pictures a moment to settle
                            handler.removeCallbacksAndMessages(null)
                            handler.postDelayed({ finish(capture(web)) }, SETTLE_MS)
                        }
                    }
                // A phone-width page, drawn at half size
                val w = WIDTH_PX
                val h = w * 3 / 4
                web.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                web.layout(0, 0, w, h)
                web.loadUrl(url)
            }
        }

    private fun capture(web: WebView): Bitmap? =
        try {
            val full = Bitmap.createBitmap(web.width, web.height, Bitmap.Config.ARGB_8888)
            web.draw(Canvas(full))
            Bitmap.createScaledBitmap(full, web.width / 2, web.height / 2, true).also { if (it !== full) full.recycle() }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Snapshot failed", e)
            null
        }

    companion object {
        private const val LOG_TAG = "FEEDER_ZIM"
        private const val WIDTH_PX = 1080
        private const val SETTLE_MS = 1200L
        private const val TIMEOUT_MS = 20_000L

        @Volatile
        private var instance: ZimSnapshots? = null

        fun get(context: Context): ZimSnapshots =
            instance ?: synchronized(this) {
                instance ?: ZimSnapshots(context.applicationContext).also { instance = it }
            }
    }
}

/**
 * One colour all over: a page that drew nothing, or the plain placeholder icon zimit puts in sites
 * that have no favicon. Either makes a worse cover than the globe.
 */
internal fun blank(b: Bitmap): Boolean {
    val first = b.getPixel(0, 0)
    val stepX = (b.width / 12).coerceAtLeast(1)
    val stepY = (b.height / 12).coerceAtLeast(1)
    var y = 0
    while (y < b.height) {
        var x = 0
        while (x < b.width) {
            if (b.getPixel(x, y) != first) return false
            x += stepX
        }
        y += stepY
    }
    return true
}
