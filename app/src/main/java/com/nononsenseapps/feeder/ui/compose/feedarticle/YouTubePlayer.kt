package com.nononsenseapps.feeder.ui.compose.feedarticle

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.background.runOnceSyncChainSendRead
import com.nononsenseapps.feeder.model.offline.OfflineVideos
import com.nononsenseapps.feeder.model.transcript.Transcripts
import com.nononsenseapps.feeder.model.youtube.VideoDetails
import com.nononsenseapps.feeder.model.youtube.VideoPositions
import com.nononsenseapps.feeder.model.youtube.YouTubePlayback
import com.nononsenseapps.feeder.model.youtube.YouTubeTiming
import com.nononsenseapps.feeder.model.youtube.youtubeWatchUrl
import com.nononsenseapps.feeder.ui.compose.theme.FeedListItemDateStyle
import com.nononsenseapps.feeder.ui.compose.transcript.TranscriptPanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.kodein.di.compose.LocalDI
import org.kodein.di.instance
import androidx.compose.ui.graphics.Color as ComposeColor

private const val LOG_TAG = "FEEDER_YOUTUBE"

// YouTube IFrame Player API states
private const val ENDED = 0
private const val PLAYING = 1
private const val PAUSED = 2

/** How often the position is written down while playing. */
private const val SAVE_EVERY_MS = 5000L

/**
 * A YouTube article's video, played like Flow: the video's own streams (no ads) with Flow's
 * controls, settings, background play and SponsorBlock, and the video's info below it. Resumes
 * where the video was left (per article, synced with the web app). YouTube's own player is the
 * fallback when the streams can't be found or won't play.
 *
 * [articleKey] empty = a video embedded in an article's text: plays, but nothing is remembered.
 */
@Composable
fun YouTubeVideoBlock(
    videoId: String,
    articleKey: String,
    title: String,
    modifier: Modifier = Modifier,
    itemId: Long = 0L,
    link: String? = null,
    isBookmarked: Boolean = false,
    onToggleBookmark: (() -> Unit)? = null,
    // Hoisted where the ⋮ menu also toggles it; otherwise kept here
    showTranscript: Boolean? = null,
    onToggleTranscript: (() -> Unit)? = null,
) {
    val di = LocalDI.current
    val videoPositions: VideoPositions by di.instance()
    val offlineVideos: OfflineVideos by di.instance()
    val playback: YouTubePlayback by di.instance()
    val positions by videoPositions.all.collectAsState()
    val pos = if (articleKey.isEmpty()) null else positions[articleKey]
    // Fixed when the article opens
    val start = remember(videoId, articleKey) { VideoPositions.resumeAt(videoPositions.get(articleKey)) }
    val context = LocalContext.current
    val playerKey = articleKey.ifEmpty { "embed:$videoId" }
    val openInYouTube = {
        val at = VideoPositions.resumeAt(videoPositions.get(articleKey))
        openInYouTubeApp(context, youtubeWatchUrl(videoId, at))
    }
    val positionLabel = pos?.let { VideoPositions.positionLabel(it, stringResource(R.string.video_watched)) }
    // Found while the video loads, so it is ready when Transcript is tapped
    val transcripts: Transcripts by di.instance()
    LaunchedEffect(itemId) { if (itemId > 0) transcripts.load(itemId) }
    var transcriptHere by rememberSaveable(playerKey) { mutableStateOf(false) }
    val transcriptShown = itemId > 0 && (showTranscript ?: transcriptHere)
    val toggleTranscript: (() -> Unit)? =
        if (itemId > 0) onToggleTranscript ?: { transcriptHere = !transcriptHere } else null

    DisableSelection {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = modifier.fillMaxWidth(),
        ) {
            // Each article gets its own player and position tracking, even for the same video
            key(playerKey) {
                val playerModifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                var playFailed by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    playback.errors.collect { if (it == playerKey) playFailed = true }
                }
                val iframe = remember { IframeControl() }
                val details by produceState<DetailsState>(
                    // Back to a video that kept playing in the background: no wait
                    playback.state.value
                        ?.takeIf { it.key == playerKey }
                        ?.let { DetailsState.Ready(it.details) } ?: DetailsState.Loading,
                ) {
                    if (value is DetailsState.Ready) return@produceState
                    YouTubeTiming.start(videoId)
                    value =
                        try {
                            DetailsState.Ready(offlineVideos.playbackDetails(videoId).also { YouTubeTiming.log(videoId, "details ready (${it.source})") })
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(LOG_TAG, "No direct streams for $videoId, using YouTube's player", e)
                            DetailsState.Failed(failureReason(e))
                        }
                }
                val current = details
                when {
                    playFailed || current is DetailsState.Failed ->
                        YouTubePlayer(
                            videoId = videoId,
                            start = start,
                            title = title,
                            onPosition = { t, d, stopped ->
                                if (articleKey.isNotEmpty()) {
                                    videoPositions.save(articleKey, t, d)
                                    // Sent when it stops, even if a save while playing already wrote this position
                                    if (stopped) runOnceSyncChainSendRead(di)
                                }
                            },
                            modifier = playerModifier,
                            control = iframe,
                        )

                    current is DetailsState.Ready ->
                        FlowVideo(
                            key = playerKey,
                            videoId = videoId,
                            articleKey = articleKey,
                            itemId = itemId,
                            title = title,
                            details = current.details,
                            start = start,
                            modifier = playerModifier,
                        )

                    else ->
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = playerModifier.background(ComposeColor.Black),
                        ) {
                            CircularProgressIndicator()
                        }
                }
                if (current is DetailsState.Ready && !playFailed) {
                    VideoInfo(
                        key = playerKey,
                        details = current.details,
                        itemId = itemId,
                        link = link,
                        isBookmarked = isBookmarked,
                        onToggleBookmark = onToggleBookmark,
                        positionLabel = positionLabel,
                        onOpenInYouTube = openInYouTube,
                        transcriptShown = transcriptShown,
                        onToggleTranscript = toggleTranscript,
                        playerLabel = stringResource(R.string.video_player_feeder),
                    )
                } else {
                    // Download stays here too: saving can work when direct playback doesn't
                    OpenInYouTubeRow(
                        positionLabel,
                        openInYouTube,
                        itemId.takeIf { it > 0 && link != null },
                        link,
                        transcriptShown = transcriptShown,
                        onToggleTranscript = toggleTranscript,
                    )
                    if (playFailed || current is DetailsState.Failed) {
                        // Says which player this is and why Feeder's own could not be used
                        val reason =
                            if (playFailed) stringResource(R.string.video_player_streams_failed) else (current as DetailsState.Failed).reason
                        Text(
                            text = stringResource(R.string.video_player_youtube, reason),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (transcriptShown) {
                    val flow = (current as? DetailsState.Ready)?.details?.takeIf { !playFailed }
                    VideoTranscript(
                        itemId = itemId,
                        positionMs =
                            if (flow != null) {
                                flowPositionMs(playback, playerKey)
                            } else {
                                iframe.positionMs
                            },
                        onSeek = { ms ->
                            if (flow != null) {
                                // Starts the video there if it is not playing here yet
                                playback.open(playerKey, videoId, articleKey, itemId, title, flow, (ms / 1000).toInt())
                                playback.seekTo(ms / 1000.0)
                                playback.player.value?.play()
                            } else {
                                iframe.seekTo(ms)
                            }
                        },
                    )
                }
            }
        }
    }
}

/** The video's transcript under it: the line being said lit, tap a line to jump there. */
@Composable
private fun VideoTranscript(
    itemId: Long,
    positionMs: Long,
    onSeek: (Long) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        TranscriptPanel(
            itemId = itemId,
            positionMs = positionMs,
            onSeek = onSeek,
            video = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).heightIn(max = 360.dp),
        )
    }
}

/** Where the Flow-style player is in [key]'s video, checked a few times a second; 0 when it plays another. */
@Composable
private fun flowPositionMs(
    playback: YouTubePlayback,
    key: String,
): Long {
    val state by playback.state.collectAsState()
    val exo by playback.player.collectAsState()
    val here = state?.key == key
    val position by produceState(0L, here, exo) {
        val player = exo
        if (!here || player == null) {
            value = 0L
            return@produceState
        }
        while (true) {
            value = player.currentPosition
            delay(250)
        }
    }
    return position
}

/** Lets the transcript follow YouTube's own player and move it. */
internal class IframeControl {
    var positionMs by mutableLongStateOf(0L)
    var webView: WebView? = null

    fun seekTo(ms: Long) {
        positionMs = ms
        webView?.evaluateJavascript("if(player&&player.seekTo){player.seekTo(${ms / 1000.0},true);player.playVideo()}", null)
    }
}

@Composable
private fun OpenInYouTubeRow(
    positionLabel: String?,
    onOpenInYouTube: () -> Unit,
    downloadItemId: Long?,
    link: String?,
    transcriptShown: Boolean,
    onToggleTranscript: (() -> Unit)?,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    ) {
        FilledTonalButton(onClick = onOpenInYouTube) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            Text(
                text = stringResource(R.string.open_in_youtube_app),
                modifier = Modifier.padding(start = ButtonDefaults.IconSpacing),
            )
        }
        if (onToggleTranscript != null) {
            TranscriptButton(transcriptShown, onToggleTranscript)
        }
        if (downloadItemId != null && link != null) {
            DownloadVideoButton(downloadItemId, link)
        }
        if (positionLabel != null) {
            Text(
                text = positionLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private sealed interface DetailsState {
    data object Loading : DetailsState

    data class Ready(
        val details: VideoDetails,
    ) : DetailsState

    data class Failed(
        val reason: String,
    ) : DetailsState
}

/** yt-dlp's own error line ("Sign in to confirm you're not a bot"), shortened, for the player label. */
internal fun failureReason(e: Throwable): String {
    val text = e.message.orEmpty()
    val line =
        text.lines().lastOrNull { it.contains("ERROR:") }
            ?: text.lines().firstOrNull { it.isNotBlank() }
            ?: e.javaClass.simpleName
    return line
        .substringAfter("ERROR:")
        .replace(Regex("^\\s*\\[[^]]*]\\s*[A-Za-z0-9_-]{11}:\\s*"), "")
        .trim()
        .take(160)
}

/** "4:12 / 14:19" or "Watched" on a YouTube article's list row, once some of it was watched. */
@Composable
fun VideoPositionText(
    videoKey: String,
    modifier: Modifier = Modifier,
) {
    val videoPositions: VideoPositions by LocalDI.current.instance()
    val positions by videoPositions.all.collectAsState()
    val pos = positions[videoKey] ?: return
    Text(
        text = VideoPositions.positionLabel(pos, stringResource(R.string.video_watched)),
        style = FeedListItemDateStyle(),
        maxLines = 1,
        overflow = TextOverflow.Clip,
        modifier = modifier,
    )
}

private fun openInYouTubeApp(
    context: Context,
    url: String,
) {
    try {
        // The YouTube app handles watch links; without it this opens the browser
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Log.e(LOG_TAG, "Could not open $url", e)
    }
}

/**
 * YouTube's IFrame player in a WebView. [onPosition] gets (seconds, length, stopped) while it
 * plays (stopped = false, every few seconds) and when it pauses, ends or goes away (stopped = true).
 * Nothing is reported until it has played here.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun YouTubePlayer(
    videoId: String,
    start: Int,
    title: String,
    onPosition: (t: Double, d: Double, stopped: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    control: IframeControl? = null,
) {
    val currentOnPosition by rememberUpdatedState(onPosition)
    val tracker = remember(videoId) { PositionTracker { t, d, stopped -> currentOnPosition(t, d, stopped) } }
    tracker.onTime = control?.let { c -> { t -> c.positionMs = (t * 1000).toLong() } }
    val lifecycleOwner = LocalLifecycleOwner.current
    val holder = remember(videoId) { WebViewHolder() }
    val context = LocalContext.current
    val chromeClient = remember(videoId) { FullscreenChromeClient(context) }

    // Back leaves fullscreen first, then the article
    BackHandler(enabled = chromeClient.isFullscreen) {
        chromeClient.onHideCustomView()
    }

    DisposableEffect(lifecycleOwner, holder) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_PAUSE -> {
                        tracker.stop()
                        holder.webView?.onPause()
                    }

                    Lifecycle.Event.ON_RESUME -> holder.webView?.onResume()

                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            tracker.stop()
        }
    }

    // A new page (and player) for another video
    key(videoId, start) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    holder.webView = this
                    control?.webView = this
                    contentDescription = title
                    setBackgroundColor(Color.BLACK)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = true
                    addJavascriptInterface(tracker, "FeederYT")
                    webViewClient =
                        object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean {
                                // Taps on the title or YouTube logo: open YouTube instead of navigating the player away
                                if (request.isForMainFrame) {
                                    openInYouTubeApp(view.context, request.url.toString())
                                    return true
                                }
                                return false
                            }
                        }
                    webChromeClient = chromeClient
                    // YouTube's embed wants to know which app is asking: the referrer is https://<package>
                    loadDataWithBaseURL(
                        "https://${ctx.packageName}",
                        playerHtml(videoId, start),
                        "text/html",
                        "utf-8",
                        null,
                    )
                }
            },
            onRelease = { webView ->
                chromeClient.onHideCustomView()
                tracker.stop()
                holder.webView = null
                if (control?.webView === webView) control.webView = null
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.destroy()
            },
            modifier = modifier,
        )
    }
}

private class WebViewHolder {
    var webView: WebView? = null
}

/** Gets the player's state from its page and decides when a position is worth saving. */
private class PositionTracker(
    private val report: (t: Double, d: Double, stopped: Boolean) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var last: Pair<Double, Double>? = null
    private var lastSaved = 0L

    /** Every position the page reports (for the transcript), on the main thread. */
    var onTime: ((Double) -> Unit)? = null

    @JavascriptInterface
    fun onProgress(
        state: Int,
        t: Double,
        d: Double,
    ) {
        main.post { handle(state, t, d) }
    }

    private fun handle(
        state: Int,
        t: Double,
        d: Double,
    ) {
        if (state == PLAYING || state == PAUSED) onTime?.invoke(t)
        when (state) {
            PLAYING -> {
                last = t to d
                val now = System.currentTimeMillis()
                if (now - lastSaved >= SAVE_EVERY_MS) {
                    lastSaved = now
                    report(t, d, false)
                }
            }

            PAUSED -> {
                if (last != null) last = t to d
                stop()
            }

            ENDED -> {
                last = d to d
                stop()
            }
        }
    }

    /** Writes down where it stopped and sends it to other devices. */
    fun stop() {
        val l = last ?: return
        // Reported once: a later stop must not overwrite a newer position from another device
        last = null
        lastSaved = System.currentTimeMillis()
        report(l.first, l.second, true)
    }
}

/** Lets the player's fullscreen button cover the whole screen. */
private class FullscreenChromeClient(
    private val context: Context,
) : WebChromeClient() {
    private var custom: View? = null
    private var callback: CustomViewCallback? = null

    var isFullscreen by mutableStateOf(false)
        private set

    override fun onShowCustomView(
        view: View,
        callback: CustomViewCallback,
    ) {
        val activity = context.findActivity()
        val decor = activity?.window?.decorView as? FrameLayout
        if (activity == null || decor == null) {
            callback.onCustomViewHidden()
            return
        }
        onHideCustomView()
        custom = view
        this.callback = callback
        view.setBackgroundColor(Color.BLACK)
        decor.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        isFullscreen = true
        WindowCompat.getInsetsController(activity.window, decor).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onHideCustomView() {
        val view = custom ?: return
        val activity = context.findActivity()
        (view.parent as? ViewGroup)?.removeView(view)
        activity?.let {
            WindowCompat.getInsetsController(it.window, it.window.decorView).show(WindowInsetsCompat.Type.systemBars())
        }
        custom = null
        isFullscreen = false
        callback?.onCustomViewHidden()
        callback = null
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

internal fun playerHtml(
    videoId: String,
    start: Int,
): String =
    """
    <!doctype html>
    <html><head>
    <meta name="viewport" content="width=device-width,initial-scale=1">
    <style>html,body{margin:0;padding:0;height:100%;background:#000;overflow:hidden}#p{position:absolute;top:0;left:0;width:100%;height:100%}</style>
    </head><body>
    <div id="p"></div>
    <script>
    var player;
    function report(){try{FeederYT.onProgress(player.getPlayerState(),player.getCurrentTime(),player.getDuration())}catch(e){}}
    function onYouTubeIframeAPIReady(){
      player=new YT.Player('p',{width:'100%',height:'100%',videoId:'$videoId',
        playerVars:{rel:0,playsinline:1,start:${start.coerceAtLeast(0)},origin:location.origin},
        events:{onStateChange:function(){report()}}});
      setInterval(function(){if(player&&player.getPlayerState&&player.getPlayerState()==1)report()},500);
    }
    </script>
    <script src="https://www.youtube.com/iframe_api"></script>
    </body></html>
    """.trimIndent()
