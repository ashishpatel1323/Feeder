package com.nononsenseapps.feeder.ui.compose.feedarticle

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.ui.compose.readaloud.SpeechHighlight
import org.json.JSONObject

/**
 * The article's own web page, shown in place of the reader view. A small page script
 * reports the page's visible text, so read aloud speaks it and highlights the spoken
 * sentence and word on the page itself, and taps on words start reading from there.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun OriginalPageView(
    url: String,
    speechHighlight: SpeechHighlight?,
    isReadingAloud: Boolean,
    onPageText: (String) -> Unit,
    onReadFrom: (Int) -> Unit,
    onUserScroll: () -> Unit,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val script =
        remember {
            context.assets
                .open(SCRIPT_ASSET)
                .bufferedReader()
                .use { it.readText() }
        }
    val readFromHereLabel = stringResource(R.string.read_aloud_from_here)
    var pageText by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    val currentOnPageText by rememberUpdatedState(onPageText)
    val currentOnReadFrom by rememberUpdatedState(onReadFrom)
    val currentOnUserScroll by rememberUpdatedState(onUserScroll)
    val currentOnOpenLink by rememberUpdatedState(onOpenLink)

    val webView =
        remember {
            val mainThread = Handler(Looper.getMainLooper())
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun onText(text: String) {
                            mainThread.post {
                                pageText = text
                                currentOnPageText(text)
                            }
                        }

                        @JavascriptInterface
                        fun onReadFrom(offset: Int) {
                            mainThread.post { currentOnReadFrom(offset) }
                        }

                        @JavascriptInterface
                        fun onUserScroll() {
                            mainThread.post { currentOnUserScroll() }
                        }
                    },
                    BRIDGE_NAME,
                )
                webChromeClient =
                    object : android.webkit.WebChromeClient() {
                        override fun onProgressChanged(
                            view: WebView?,
                            newProgress: Int,
                        ) {
                            progress = newProgress
                        }
                    }
                webViewClient =
                    object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean {
                            request ?: return false
                            // Links the reader taps open outside, so the page being read stays put
                            if (request.isForMainFrame && request.hasGesture()) {
                                currentOnOpenLink(request.url.toString())
                                return true
                            }
                            return false
                        }

                        override fun onPageStarted(
                            view: WebView?,
                            url: String?,
                            favicon: Bitmap?,
                        ) {
                            pageText = null
                        }

                        override fun onPageFinished(
                            view: WebView?,
                            url: String?,
                        ) {
                            view?.evaluateJavascript(
                                "window.__feederReadFromHereLabel=${JSONObject.quote(readFromHereLabel)};$script",
                                null,
                            )
                        }
                    }
                loadUrl(url)
            }
        }

    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }

    val text = pageText
    // Only highlight when what is being read is this page's text, not the reader view's
    val matching = speechHighlight?.takeIf { text != null && it.text == text }
    val readingHere = isReadingAloud && (speechHighlight == null || matching != null)
    LaunchedEffect(webView, readingHere, text) {
        webView.evaluateJavascript("window.__feederReadAloud&&window.__feederReadAloud.setReading($readingHere)", null)
    }
    LaunchedEffect(webView, matching, text) {
        val js =
            if (matching == null) {
                "window.__feederReadAloud&&window.__feederReadAloud.clear()"
            } else {
                val word = matching.word
                "window.__feederReadAloud&&window.__feederReadAloud.highlight(" +
                    "${matching.segment.start},${matching.segment.end}," +
                    "${word?.start ?: -1},${word?.end ?: -1},${matching.followSpokenText})"
            }
        webView.evaluateJavascript(js, null)
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { webView },
            modifier = Modifier.fillMaxSize(),
        )
        if (progress in 0..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
            )
        }
    }
}

private const val SCRIPT_ASSET = "original_page_read_aloud.js"
private const val BRIDGE_NAME = "FeederReadAloud"
