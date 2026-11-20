package com.nononsenseapps.feeder.ui.compose.feedarticle

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
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
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.ui.PlayerView
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.background.runOnceSyncChainSendRead
import com.nononsenseapps.feeder.model.offline.OfflineVideos
import com.nononsenseapps.feeder.model.offline.StreamUrls
import com.nononsenseapps.feeder.model.youtube.VideoPositions
import com.nononsenseapps.feeder.model.youtube.youtubeWatchUrl
import com.nononsenseapps.feeder.ui.compose.theme.FeedListItemDateStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.kodein.di.compose.LocalDI
import org.kodein.di.instance

private const val LOG_TAG = "FEEDER_YOUTUBE"

// YouTube IFrame Player API states
private const val ENDED = 0
private const val PLAYING = 1
private const val PAUSED = 2

/** How often the position is written down while playing. */
private const val SAVE_EVERY_MS = 5000L

/**
 * A YouTube article's video, like the web app's reader: YouTube's own player, which resumes
 * where the video was left (per article, synced with the web app), and an always visible
 * button to open it in the YouTube app at that point.
 *
 * [articleKey] empty = a video embedded in an article's text: plays, but nothing is remembered.
 */
@Composable
fun YouTubeVideoBlock(
    videoId: String,
    articleKey: String,
    title: String,
    modifier: Modifier = Modifier,
) {
    val di = LocalDI.current
    val videoPositions: VideoPositions by di.instance()
    val offlineVideos: OfflineVideos by di.instance()
    val positions by videoPositions.all.collectAsState()
    val pos = if (articleKey.isEmpty()) null else positions[articleKey]
    // The start goes in the player's page, so it is fixed when the article opens
    val start = remember(videoId, articleKey) { VideoPositions.resumeAt(videoPositions.get(articleKey)) }
    val context = LocalContext.current

    DisableSelection {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = modifier.fillMaxWidth(),
        ) {
            // Each article gets its own player and position tracking, even for the same video
            key(articleKey) {
                val onPosition: (Double, Double, Boolean) -> Unit = { t, d, stopped ->
                    if (articleKey.isNotEmpty()) {
                        videoPositions.save(articleKey, t, d)
                        // Sent when it stops, even if a save while playing already wrote this position
                        if (stopped) runOnceSyncChainSendRead(di)
                    }
                }
                val playerModifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                // No ads: the video's own streams (from yt-dlp) play in the app's player, like
                // Flow and NewPipe. YouTube's embedded player is the fallback when that fails.
                val streams by produceState<StreamState>(StreamState.Loading, videoId) {
                    value =
                        try {
                            StreamState.Ready(offlineVideos.streamUrls(youtubeWatchUrl(videoId)))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(LOG_TAG, "No direct streams for $videoId, using YouTube's player", e)
                            StreamState.Failed
                        }
                }
                when (val current = streams) {
                    StreamState.Loading ->
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = playerModifier.background(ComposeColor.Black),
                        ) {
                            CircularProgressIndicator()
                        }

                    is StreamState.Ready ->
                        AdFreePlayer(
                            urls = current.urls,
                            start = start,
                            onPosition = onPosition,
                            modifier = playerModifier,
                        )

                    StreamState.Failed ->
                        YouTubePlayer(
                            videoId = videoId,
                            start = start,
                            title = title,
                            onPosition = onPosition,
                            modifier = playerModifier,
                        )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                FilledTonalButton(
                    onClick = {
                        val at = VideoPositions.resumeAt(videoPositions.get(articleKey))
                        openInYouTubeApp(context, youtubeWatchUrl(videoId, at))
                    },
                ) {
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
                if (pos != null) {
                    Text(
                        text = VideoPositions.positionLabel(pos, stringResource(R.string.video_watched)),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
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
fun YouTubePlayer(
    videoId: String,
    start: Int,
    title: String,
    onPosition: (t: Double, d: Double, stopped: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnPosition by rememberUpdatedState(onPosition)
    val tracker = remember(videoId) { PositionTracker { t, d, stopped -> currentOnPosition(t, d, stopped) } }
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
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.destroy()
            },
            modifier = modifier,
        )
    }
}

private sealed interface StreamState {
    data object Loading : StreamState

    data class Ready(
        val urls: StreamUrls,
    ) : StreamState

    data object Failed : StreamState
}

/**
 * The video's own streams in the app's player (Media3): no ads. Same position reports as
 * [YouTubePlayer]. Its fullscreen button opens a landscape fullscreen view.
 */
@OptIn(UnstableApi::class)
@Composable
private fun AdFreePlayer(
    urls: StreamUrls,
    start: Int,
    onPosition: (t: Double, d: Double, stopped: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnPosition by rememberUpdatedState(onPosition)
    val reporter = remember(urls) { PlaybackReporter { t, d, stopped -> currentOnPosition(t, d, stopped) } }
    var fullscreen by remember(urls) { mutableStateOf(false) }
    val player =
        remember(urls) {
            ExoPlayer.Builder(context).build().apply {
                val factory = DefaultMediaSourceFactory(context)
                val video = factory.createMediaSource(MediaItem.fromUri(urls.video))
                val source =
                    urls.audio?.let { audio ->
                        MergingMediaSource(video, factory.createMediaSource(MediaItem.fromUri(audio)))
                    } ?: video
                setMediaSource(source, start * 1000L)
                prepare()
            }
        }

    DisposableEffect(player, lifecycleOwner) {
        val listener =
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (!isPlaying && player.playbackState != Player.STATE_ENDED) {
                        reporter.paused(player.currentPosition / 1000.0, player.durationSeconds())
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) reporter.ended(player.durationSeconds())
                }
            }
        player.addListener(listener)
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE) player.pause()
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.removeListener(listener)
            if (player.isPlaying) reporter.playing(player.currentPosition / 1000.0, player.durationSeconds())
            reporter.stop()
            player.release()
        }
    }

    LaunchedEffect(player) {
        while (true) {
            delay(1000)
            if (player.isPlaying) reporter.playing(player.currentPosition / 1000.0, player.durationSeconds())
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                setFullscreenButtonClickListener { fullscreen = it }
            }
        },
        update = { view ->
            // One view shows the player at a time
            view.player = if (fullscreen) null else player
            view.setFullscreenButtonState(false)
        },
        onRelease = { it.player = null },
        modifier = modifier.background(ComposeColor.Black),
    )

    if (fullscreen) {
        DisposableEffect(Unit) {
            val activity = context.findActivity()
            val before = activity?.requestedOrientation
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            onDispose {
                if (activity != null && before != null) activity.requestedOrientation = before
            }
        }
        Dialog(
            onDismissRequest = { fullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        setFullscreenButtonState(true)
                        setFullscreenButtonClickListener { fullscreen = it }
                    }
                },
                update = { it.player = player },
                onRelease = { it.player = null },
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(ComposeColor.Black),
            )
        }
    }
}

private fun Player.durationSeconds(): Double = duration.takeIf { it > 0 }?.let { it / 1000.0 } ?: 0.0

/** Decides when a position of [AdFreePlayer] is worth saving, like [PositionTracker]. */
private class PlaybackReporter(
    private val report: (t: Double, d: Double, stopped: Boolean) -> Unit,
) {
    private var last: Pair<Double, Double>? = null
    private var lastSaved = 0L

    fun playing(
        t: Double,
        d: Double,
    ) {
        last = t to d
        val now = System.currentTimeMillis()
        if (now - lastSaved >= SAVE_EVERY_MS) {
            lastSaved = now
            report(t, d, false)
        }
    }

    fun paused(
        t: Double,
        d: Double,
    ) {
        if (last != null) last = t to d
        stop()
    }

    fun ended(d: Double) {
        last = d to d
        stop()
    }

    fun stop() {
        val l = last ?: return
        last = null
        lastSaved = System.currentTimeMillis()
        report(l.first, l.second, true)
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
      setInterval(function(){if(player&&player.getPlayerState&&player.getPlayerState()==1)report()},1000);
    }
    </script>
    <script src="https://www.youtube.com/iframe_api"></script>
    </body></html>
    """.trimIndent()
