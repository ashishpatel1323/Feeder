package com.nononsenseapps.feeder.ui.compose.feedarticle

import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.automirrored.outlined.ViewSidebar
import androidx.media3.common.VideoSize
import com.nononsenseapps.feeder.model.transcript.TranscriptChapter
import com.nononsenseapps.feeder.ui.compose.theme.FeederDarkColors
import com.nononsenseapps.feeder.ui.compose.transcript.TranscriptPanel
import android.text.Layout
import android.view.View
import android.text.format.DateUtils
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.MoneyOff
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import coil3.compose.AsyncImage
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.model.offline.OfflineVideos
import com.nononsenseapps.feeder.model.offline.VideoPhase
import com.nononsenseapps.feeder.model.youtube.Chapter
import com.nononsenseapps.feeder.model.youtube.PlaybackState
import com.nononsenseapps.feeder.model.youtube.SkipSegment
import com.nononsenseapps.feeder.model.youtube.SubtitleSize
import com.nononsenseapps.feeder.model.youtube.VideoDetails
import com.nononsenseapps.feeder.model.youtube.VideoPositions
import com.nononsenseapps.feeder.model.youtube.YouTubePlayback
import kotlinx.coroutines.delay
import org.kodein.di.compose.LocalDI
import org.kodein.di.instance
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.max
import android.graphics.Color as AndroidColor

private val PlayedColor = Color(0xFFFF0033)
private val SponsorColor = Color(0xFF00D400)
private val Scrim = Color.Black.copy(alpha = 0.45f)
private val PillColor = Color.Black.copy(alpha = 0.55f)

private enum class PlayerSheet {
    SETTINGS,
    QUALITY,
    SPEED,
    AUDIO,
    SUBTITLES,
    STYLE,
    SLEEP,
    CHAPTERS,
}

/**
 * The video in the Flow-style player: [YouTubePlayback]'s player with Flow's controls (skip,
 * double-tap, chapters, quality, sleep timer, settings) and a fullscreen view.
 */
@Composable
internal fun FlowVideo(
    key: String,
    videoId: String,
    articleKey: String,
    itemId: Long,
    title: String,
    details: VideoDetails,
    start: Int,
    modifier: Modifier = Modifier,
    // The YouTube tab's video page: a collapse chevron, previous/next videos and the quality as "720p"
    onCollapse: (() -> Unit)? = null,
    onPrevious: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
    autoPlay: Boolean = false,
) {
    val playback: YouTubePlayback by LocalDI.current.instance()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    val open = { playback.open(key, videoId, articleKey, itemId, title, details, start) }
    DisposableEffect(key) {
        // An article's own video is ready to play; videos inside an article's text wait for a
        // tap, so several on one page don't take the one player from each other
        if (articleKey.isNotEmpty() || autoPlay) open()
        if (autoPlay) playback.player.value?.play()
        onDispose { playback.close(key) }
    }
    DisposableEffect(lifecycleOwner, key) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE) playback.onAppPaused(key)
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(key) {
        playback.skipped.collect {
            Toast.makeText(context, R.string.player_sponsorblock_skipped, Toast.LENGTH_SHORT).show()
        }
    }

    val state by playback.state.collectAsState()
    val player by playback.player.collectAsState()
    val current = state?.takeIf { it.key == key }
    val exo = player
    if (current == null || exo == null) {
        // Not playing here (another video took the player): its picture and a play button
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                modifier
                    .background(Color.Black)
                    .clickable {
                        open()
                        playback.player.value?.play()
                    },
        ) {
            details.thumbnail?.let {
                AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Surface(shape = CircleShape, color = PillColor, contentColor = Color.White, modifier = Modifier.size(64.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.player_play), modifier = Modifier.size(40.dp))
                }
            }
        }
        return
    }

    // Saved so turning the phone (the activity is rebuilt) keeps full screen and the panel
    var fullscreen by rememberSaveable(key) { mutableStateOf(false) }
    var panelOpen by rememberSaveable(key) { mutableStateOf(true) }
    var sheet by remember(key) { mutableStateOf<PlayerSheet?>(null) }
    val canShowPanel = itemId > 0

    VideoSurface(
        player = exo,
        state = current,
        active = !fullscreen,
        fullscreen = false,
        onToggleFullscreen = { fullscreen = true },
        onSheet = { sheet = it },
        modifier = modifier,
        onCollapse = onCollapse,
        onPrevious = onPrevious,
        onNext = onNext,
    )
    if (!fullscreen) {
        sheet?.let { PlayerSheetContent(it, current, exo, onSheet = { next -> sheet = next }) }
    }

    if (fullscreen) {
        Dialog(
            onDismissRequest = { fullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            FullscreenWindow()
            BackHandler { fullscreen = false }
            val aspect = rememberVideoAspect(exo)
            val chapters =
                remember(current.details.chapters) {
                    current.details.chapters.map { TranscriptChapter((it.start * 1000).toLong(), it.title) }
                }
            val panelShown = canShowPanel && panelOpen
            val surface: @Composable (Modifier) -> Unit = { m ->
                VideoSurface(
                    player = exo,
                    state = current,
                    active = true,
                    fullscreen = true,
                    onToggleFullscreen = { fullscreen = false },
                    onSheet = { sheet = it },
                    modifier = m,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    qualityAsHeight = onCollapse != null,
                    panelShown = if (canShowPanel) panelShown else null,
                    onTogglePanel = { panelOpen = !panelOpen },
                )
            }
            val panel: @Composable (Modifier) -> Unit = { m ->
                MaterialTheme(colorScheme = FeederDarkColors, typography = MaterialTheme.typography) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = m) {
                        TranscriptPanel(
                            itemId = itemId,
                            positionMs = flowPositionMs(playback, key),
                            onSeek = { ms ->
                                playback.seekTo(ms / 1000.0)
                                exo.play()
                            },
                            video = true,
                            chapters = chapters,
                            modifier = Modifier.fillMaxSize().padding(top = 4.dp),
                        )
                    }
                }
            }
            BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                val w = maxWidth
                val h = maxHeight
                val landscape = w > h
                if (landscape) {
                    // Video on the left at its own shape (never cropped), the panel on the right
                    Row(modifier = Modifier.fillMaxSize()) {
                        surface(Modifier.weight(1f).fillMaxHeight())
                        if (panelShown) {
                            panel(
                                Modifier
                                    .width((w * 0.36f).coerceIn(280.dp, 420.dp))
                                    .fillMaxHeight()
                                    .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.End)),
                            )
                        }
                    }
                } else {
                    // Video pinned under the status bar with its controls on the picture; the
                    // space under it is the panel instead of black bars
                    Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Top))) {
                        val maxVideo = if (panelShown) h * 0.55f else h
                        val videoHeight = (w / aspect).coerceAtMost(maxVideo)
                        surface(Modifier.fillMaxWidth().height(videoHeight))
                        if (panelShown) {
                            panel(Modifier.fillMaxWidth().weight(1f).windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)))
                        } else if (aspect > 1.2f) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().weight(1f)) {
                                Text(
                                    stringResource(R.string.player_turn_for_bigger),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.6f),
                                )
                            }
                        }
                    }
                }
            }
            sheet?.let { PlayerSheetContent(it, current, exo, onSheet = { next -> sheet = next }) }
        }
    }
}

/**
 * Makes the full screen dialog cover the whole display: under the camera cut-out and the
 * navigation bar, black behind it, system bars hidden until swiped in. Without this the
 * dialog's window stopped short of the cut-out in landscape and the app showed through.
 */
@Composable
private fun FullscreenWindow() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            window.setBackgroundDrawable(ColorDrawable(AndroidColor.BLACK))
            window.setDimAmount(0f)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes =
                    window.attributes.apply {
                        layoutInDisplayCutoutMode =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                            } else {
                                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                            }
                    }
            }
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, view).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose { }
    }
}

/** A caption cue with its position dropped, so the player puts it centred near the bottom. */
@OptIn(UnstableApi::class)
internal fun centredCue(cue: Cue): Cue =
    if (cue.bitmap != null) {
        cue
    } else {
        cue
            .buildUpon()
            .setTextAlignment(Layout.Alignment.ALIGN_CENTER)
            .setPosition(Cue.DIMEN_UNSET)
            .setPositionAnchor(Cue.TYPE_UNSET)
            .setLine(Cue.DIMEN_UNSET, Cue.TYPE_UNSET)
            .setLineAnchor(Cue.TYPE_UNSET)
            .setSize(Cue.DIMEN_UNSET)
            .build()
    }

/** The video's width / height as the player reports it; 16:9 until it knows. */
@Composable
private fun rememberVideoAspect(player: ExoPlayer): Float {
    fun aspectOf(size: VideoSize): Float? =
        if (size.width > 0 && size.height > 0) size.width * size.pixelWidthHeightRatio / size.height else null
    var aspect by remember(player) { mutableFloatStateOf(aspectOf(player.videoSize) ?: 16f / 9f) }
    DisposableEffect(player) {
        val listener =
            object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    aspectOf(videoSize)?.let { aspect = it }
                }
            }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    return aspect
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(
    player: ExoPlayer,
    state: PlaybackState,
    active: Boolean,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onSheet: (PlayerSheet?) -> Unit,
    modifier: Modifier = Modifier,
    onCollapse: (() -> Unit)? = null,
    onPrevious: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
    qualityAsHeight: Boolean = onCollapse != null,
    // Full screen only: the transcript panel button (null = no panel for this video)
    panelShown: Boolean? = null,
    onTogglePanel: () -> Unit = {},
) {
    val playback: YouTubePlayback by LocalDI.current.instance()
    val prefs by playback.playerPrefs.collectAsState()

    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var playWhenReady by remember { mutableStateOf(player.playWhenReady) }
    var buffering by remember { mutableStateOf(false) }
    var ended by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var buffered by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    LaunchedEffect(player) {
        while (true) {
            isPlaying = player.isPlaying
            playWhenReady = player.playWhenReady
            buffering = player.playbackState == Player.STATE_BUFFERING
            ended = player.playbackState == Player.STATE_ENDED
            position = player.currentPosition
            buffered = player.bufferedPosition
            duration = player.duration.takeIf { it > 0 } ?: (state.details.duration * 1000).toLong()
            delay(200)
        }
    }

    var controls by remember { mutableStateOf(true) }
    var touchedAt by remember { mutableLongStateOf(0L) }
    var scrubbing by remember { mutableStateOf<Long?>(null) }
    // Controls hide a few seconds after the last touch while it plays
    LaunchedEffect(controls, isPlaying, touchedAt, scrubbing) {
        if (controls && isPlaying && scrubbing == null) {
            delay(3000)
            controls = false
        }
    }
    var seekFlash by remember { mutableStateOf<Pair<Boolean, Long>?>(null) }
    LaunchedEffect(seekFlash) {
        if (seekFlash != null) {
            delay(600)
            seekFlash = null
        }
    }

    // YouTube's captions come pinned to the left edge (position 0%), which cut them off. The
    // player's own caption view is hidden and they are drawn here centred along the bottom.
    var cues by remember { mutableStateOf<List<Cue>>(emptyList()) }
    DisposableEffect(player, active) {
        cues = if (active) player.currentCues.cues.map(::centredCue) else emptyList()
        val listener =
            object : Player.Listener {
                override fun onCues(cueGroup: CueGroup) {
                    if (active) cues = cueGroup.cues.map(::centredCue)
                }
            }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    Box(modifier = modifier.background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(AndroidColor.BLACK)
                    setKeepContentOnPlayerReset(true)
                }
            },
            update = { view ->
                // One view shows the player at a time (inline or fullscreen)
                view.player = if (active) player else null
                view.subtitleView?.visibility = View.GONE
            },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )
        AndroidView(
            factory = { ctx -> SubtitleView(ctx) },
            update = { view ->
                view.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * prefs.subtitleSize.scale)
                view.setStyle(
                    CaptionStyleCompat(
                        AndroidColor.WHITE,
                        if (prefs.subtitleBackground) 0xAA000000.toInt() else AndroidColor.TRANSPARENT,
                        AndroidColor.TRANSPARENT,
                        if (prefs.subtitleBackground) CaptionStyleCompat.EDGE_TYPE_NONE else CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
                        AndroidColor.BLACK,
                        null,
                    ),
                )
                view.setCues(cues)
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Taps show/hide the controls; double-taps on either side skip 10 seconds, like YouTube
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .pointerInput(player) {
                        detectTapGestures(
                            onTap = {
                                controls = !controls
                                touchedAt = System.currentTimeMillis()
                            },
                            onDoubleTap = { offset ->
                                val back = offset.x < size.width / 2
                                if (back) player.seekBack() else player.seekForward()
                                seekFlash = back to System.nanoTime()
                            },
                        )
                    },
        )

        seekFlash?.let { (back, _) ->
            Surface(
                shape = CircleShape,
                color = PillColor,
                contentColor = Color.White,
                modifier =
                    Modifier
                        .align(if (back) Alignment.CenterStart else Alignment.CenterEnd)
                        .padding(horizontal = 32.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
                    Icon(if (back) Icons.Filled.Replay10 else Icons.Filled.Forward10, contentDescription = null)
                }
            }
        }

        AnimatedVisibility(visible = controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize().background(Scrim)) {
                if (onCollapse != null) {
                    IconButton(onClick = onCollapse, modifier = Modifier.align(Alignment.TopStart).padding(4.dp)) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.player_collapse), tint = Color.White)
                    }
                }
                // Top: sleep timer and settings
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                ) {
                    val sleepLeft =
                        if (state.sleepAt > 0) {
                            max(1, ((state.sleepAt - System.currentTimeMillis()) / 60_000L).toInt() + 1)
                        } else {
                            null
                        }
                    if (sleepLeft != null) {
                        Text(
                            stringResource(R.string.player_sleep_left, sleepLeft),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    IconButton(onClick = { onSheet(PlayerSheet.SLEEP) }) {
                        Icon(
                            if (state.sleepAt != 0L) Icons.Filled.Bedtime else Icons.Outlined.Bedtime,
                            contentDescription = stringResource(R.string.player_sleep_timer),
                            tint = Color.White,
                        )
                    }
                    IconButton(onClick = { onSheet(PlayerSheet.SETTINGS) }) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.player_settings), tint = Color.White)
                    }
                }

                // Middle: back 10, play/pause, forward 10
                Row(
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    if (onPrevious != null) {
                        IconButton(onClick = onPrevious) {
                            Icon(Icons.Filled.SkipPrevious, contentDescription = stringResource(R.string.player_previous), tint = Color.White, modifier = Modifier.size(36.dp))
                        }
                    } else {
                        IconButton(onClick = {
                            player.seekBack()
                            touchedAt = System.currentTimeMillis()
                        }) {
                            Icon(Icons.Filled.Replay10, contentDescription = stringResource(R.string.skip_back_10), tint = Color.White, modifier = Modifier.size(32.dp))
                        }
                    }
                    Surface(
                        shape = CircleShape,
                        color = PillColor,
                        contentColor = Color.White,
                        onClick = {
                            when {
                                ended -> {
                                    player.seekTo(0)
                                    player.play()
                                }
                                playWhenReady -> player.pause()
                                else -> player.play()
                            }
                            touchedAt = System.currentTimeMillis()
                        },
                        modifier = Modifier.size(64.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (buffering && playWhenReady) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(36.dp))
                            } else {
                                Icon(
                                    if (playWhenReady && !ended) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = stringResource(if (playWhenReady && !ended) R.string.player_pause else R.string.player_play),
                                    modifier = Modifier.size(40.dp),
                                )
                            }
                        }
                    }
                    if (onNext != null) {
                        IconButton(onClick = onNext) {
                            Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.player_next), tint = Color.White, modifier = Modifier.size(36.dp))
                        }
                    } else {
                        IconButton(onClick = {
                            player.seekForward()
                            touchedAt = System.currentTimeMillis()
                        }) {
                            Icon(Icons.Filled.Forward10, contentDescription = stringResource(R.string.skip_forward_10), tint = Color.White, modifier = Modifier.size(32.dp))
                        }
                    }
                }

                // Bottom: time, chapter, quality, fullscreen, then the seek bar
                Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                    val shown = scrubbing ?: position
                    val chapter = state.details.chapterAt(shown / 1000.0)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    ) {
                        Pill(text = "${clock(shown)} / ${clock(duration)}")
                        if (chapter != null) {
                            Pill(
                                text = chapter.title,
                                trailing = Icons.Filled.ChevronRight,
                                onClick = { onSheet(PlayerSheet.CHAPTERS) },
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (state.height > 0) {
                            Pill(
                                text =
                                    if (qualityAsHeight) {
                                        "${state.height}p"
                                    } else if (state.isHd) {
                                        "HD"
                                    } else {
                                        "SD"
                                    },
                                onClick = { onSheet(PlayerSheet.QUALITY) },
                            )
                        }
                        if (panelShown != null) {
                            Surface(
                                shape = CircleShape,
                                color = if (panelShown) MaterialTheme.colorScheme.inversePrimary else PillColor,
                                contentColor = if (panelShown) MaterialTheme.colorScheme.onPrimaryContainer else Color.White,
                                onClick = {
                                    onTogglePanel()
                                    touchedAt = System.currentTimeMillis()
                                },
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Outlined.ViewSidebar,
                                    contentDescription =
                                        stringResource(if (panelShown) R.string.player_hide_transcript_panel else R.string.player_show_transcript_panel),
                                    modifier = Modifier.padding(6.dp),
                                )
                            }
                        }
                        Surface(shape = CircleShape, color = PillColor, contentColor = Color.White, onClick = onToggleFullscreen) {
                            Icon(
                                if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                                contentDescription = stringResource(if (fullscreen) R.string.player_exit_fullscreen else R.string.player_fullscreen),
                                modifier = Modifier.padding(6.dp),
                            )
                        }
                    }
                    SeekBar(
                        position = position,
                        buffered = buffered,
                        duration = duration,
                        chapters = state.details.chapters,
                        segments = state.segments,
                        interactive = true,
                        onScrub = {
                            scrubbing = it
                            touchedAt = System.currentTimeMillis()
                        },
                        onSeek = { player.seekTo(it) },
                    )
                }
            }
        }

        if (!controls) {
            SeekBar(
                position = position,
                buffered = buffered,
                duration = duration,
                chapters = state.details.chapters,
                segments = state.segments,
                interactive = false,
                onScrub = {},
                onSeek = {},
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    trailing: ImageVector? = null,
    onClick: (() -> Unit)? = null,
) {
    val content: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (trailing != null) Icon(trailing, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
    if (onClick != null) {
        Surface(shape = CircleShape, color = PillColor, contentColor = Color.White, onClick = onClick, modifier = modifier) { content() }
    } else {
        Surface(shape = CircleShape, color = PillColor, contentColor = Color.White, modifier = modifier) { content() }
    }
}

/** Flow's progress bar: split at chapters, sponsor segments marked, draggable when [interactive]. */
@Composable
private fun SeekBar(
    position: Long,
    buffered: Long,
    duration: Long,
    chapters: List<Chapter>,
    segments: List<SkipSegment>,
    interactive: Boolean,
    onScrub: (Long?) -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var drag by remember { mutableStateOf<Float?>(null) }
    var width by remember { mutableFloatStateOf(1f) }

    fun fractionAt(x: Float) = (x / width).coerceIn(0f, 1f)
    val gestures =
        if (interactive && duration > 0) {
            Modifier
                .pointerInput(duration) {
                    detectTapGestures(onTap = { onSeek((fractionAt(it.x) * duration).toLong()) })
                }.pointerInput(duration) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            drag = fractionAt(it.x)
                            onScrub((fractionAt(it.x) * duration).toLong())
                        },
                        onDragEnd = {
                            drag?.let { onSeek((it * duration).toLong()) }
                            drag = null
                            onScrub(null)
                        },
                        onDragCancel = {
                            drag = null
                            onScrub(null)
                        },
                    ) { change, _ ->
                        change.consume()
                        drag = fractionAt(change.position.x)
                        onScrub((fractionAt(change.position.x) * duration).toLong())
                    }
                }
        } else {
            Modifier
        }
    Canvas(
        modifier =
            modifier
                .fillMaxWidth()
                .height(if (interactive) 24.dp else 3.dp)
                .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .then(gestures),
    ) {
        if (duration <= 0) return@Canvas
        val barHeight = (if (drag != null) 5.dp else 3.dp).toPx()
        val top = size.height - barHeight - (if (interactive) 6.dp.toPx() else 0f)
        val played = drag ?: (position.toFloat() / duration).coerceIn(0f, 1f)
        val loaded = (buffered.toFloat() / duration).coerceIn(0f, 1f)

        fun bar(
            from: Float,
            to: Float,
            color: Color,
        ) {
            if (to > from) drawRect(color, Offset(from * size.width, top), Size((to - from) * size.width, barHeight))
        }
        bar(0f, 1f, Color.White.copy(alpha = 0.3f))
        bar(0f, loaded, Color.White.copy(alpha = 0.5f))
        for (s in segments) bar((s.start * 1000 / duration).toFloat(), (s.end * 1000 / duration).toFloat(), SponsorColor)
        bar(0f, played, PlayedColor)
        // Gaps between chapters
        val gap = 2.dp.toPx()
        for (c in chapters) {
            if (c.start <= 0) continue
            val x = (c.start * 1000 / duration).toFloat() * size.width
            drawRect(Color.Black, Offset(x - gap / 2, top), Size(gap, barHeight), blendMode = BlendMode.Clear)
        }
        if (interactive) {
            drawCircle(PlayedColor, radius = (if (drag != null) 9.dp else 6.dp).toPx(), center = Offset(played * size.width, top + barHeight / 2))
        }
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSheetContent(
    sheet: PlayerSheet,
    state: PlaybackState,
    player: ExoPlayer,
    onSheet: (PlayerSheet?) -> Unit,
) {
    val playback: YouTubePlayback by LocalDI.current.instance()
    val prefs by playback.playerPrefs.collectAsState()
    val details = state.details
    ModalBottomSheet(
        onDismissRequest = { onSheet(null) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            val subPage = sheet in setOf(PlayerSheet.QUALITY, PlayerSheet.SPEED, PlayerSheet.AUDIO, PlayerSheet.SUBTITLES, PlayerSheet.STYLE)
            SheetHeader(
                title =
                    stringResource(
                        when (sheet) {
                            PlayerSheet.SETTINGS -> R.string.player_settings
                            PlayerSheet.QUALITY -> R.string.player_quality
                            PlayerSheet.SPEED -> R.string.player_speed
                            PlayerSheet.AUDIO -> R.string.player_audio_track
                            PlayerSheet.SUBTITLES -> R.string.player_subtitles
                            PlayerSheet.STYLE -> R.string.player_subtitle_style
                            PlayerSheet.SLEEP -> R.string.player_sleep_timer
                            PlayerSheet.CHAPTERS -> R.string.player_chapters
                        },
                    ),
                onBack = if (subPage) ({ onSheet(PlayerSheet.SETTINGS) }) else null,
                onClose = { onSheet(null) },
            )
            HorizontalDivider()
            when (sheet) {
                PlayerSheet.SETTINGS -> {
                    SectionHeader(stringResource(R.string.player_section_video))
                    SettingsRow(Icons.Outlined.HighQuality, stringResource(R.string.player_quality), qualityLabel(state)) { onSheet(PlayerSheet.QUALITY) }
                    SectionHeader(stringResource(R.string.player_section_playback))
                    SettingsRow(Icons.Outlined.Speed, stringResource(R.string.player_speed), speedLabel(state.speed)) { onSheet(PlayerSheet.SPEED) }
                    SwitchRow(Icons.Outlined.MoneyOff, stringResource(R.string.player_sponsorblock), prefs.sponsorBlock) { playback.setSponsorBlock(it) }
                    SectionHeader(stringResource(R.string.player_section_audio))
                    SettingsRow(
                        Icons.Outlined.Audiotrack,
                        stringResource(R.string.player_audio_track),
                        state.audio?.label ?: "",
                        enabled = details.audios.size > 1,
                    ) { onSheet(PlayerSheet.AUDIO) }
                    SectionHeader(stringResource(R.string.player_section_captions))
                    SettingsRow(
                        Icons.Outlined.ClosedCaption,
                        stringResource(R.string.player_subtitles),
                        state.subtitle?.let { subtitleLabel(it.label, it.automatic) } ?: stringResource(R.string.player_subtitles_off),
                        enabled = details.subtitles.isNotEmpty(),
                    ) { onSheet(PlayerSheet.SUBTITLES) }
                    SettingsRow(Icons.Outlined.Tune, stringResource(R.string.player_subtitle_style), "") { onSheet(PlayerSheet.STYLE) }
                }

                PlayerSheet.QUALITY ->
                    details.videos.forEach { v ->
                        ChoiceRow("${v.height}p", selected = v.height == state.height) {
                            playback.setQuality(v.height)
                            onSheet(null)
                        }
                    }

                PlayerSheet.SPEED ->
                    SPEEDS.forEach { s ->
                        ChoiceRow(speedLabel(s), selected = s == state.speed) {
                            playback.setSpeed(s)
                            onSheet(null)
                        }
                    }

                PlayerSheet.AUDIO ->
                    details.audios.forEach { a ->
                        ChoiceRow(a.label, selected = a.formatId == state.audio?.formatId) {
                            playback.setAudio(a)
                            onSheet(null)
                        }
                    }

                PlayerSheet.SUBTITLES -> {
                    ChoiceRow(stringResource(R.string.player_subtitles_off), selected = state.subtitle == null) {
                        playback.setSubtitle(null)
                        onSheet(null)
                    }
                    details.subtitles.forEach { t ->
                        ChoiceRow(subtitleLabel(t.label, t.automatic), selected = t.url == state.subtitle?.url) {
                            playback.setSubtitle(t)
                            onSheet(null)
                        }
                    }
                }

                PlayerSheet.STYLE -> {
                    SectionHeader(stringResource(R.string.player_subtitle_size))
                    SubtitleSize.entries.forEach { size ->
                        ChoiceRow(subtitleSizeLabel(size), selected = size == prefs.subtitleSize) {
                            playback.setSubtitleStyle(size, prefs.subtitleBackground)
                        }
                    }
                    SwitchRow(null, stringResource(R.string.player_subtitle_background), prefs.subtitleBackground) {
                        playback.setSubtitleStyle(prefs.subtitleSize, it)
                    }
                }

                PlayerSheet.SLEEP -> {
                    ChoiceRow(stringResource(R.string.player_sleep_off), selected = state.sleepAt == 0L) {
                        playback.setSleepTimer(0)
                        onSheet(null)
                    }
                    SLEEP_MINUTES.forEach { m ->
                        ChoiceRow(stringResource(R.string.player_sleep_minutes, m), selected = false) {
                            playback.setSleepTimer(m)
                            onSheet(null)
                        }
                    }
                    ChoiceRow(stringResource(R.string.player_sleep_end_of_video), selected = state.sleepAt == PlaybackState.SLEEP_AT_END) {
                        playback.setSleepTimer(YouTubePlayback.SLEEP_END_OF_VIDEO)
                        onSheet(null)
                    }
                }

                PlayerSheet.CHAPTERS -> {
                    val now = details.chapterAt(player.currentPosition / 1000.0)
                    ChapterList(details.chapters, now) {
                        playback.seekTo(it.start)
                        onSheet(null)
                    }
                }
            }
        }
    }
}

@Composable
internal fun ChapterList(
    chapters: List<Chapter>,
    current: Chapter?,
    onClick: (Chapter) -> Unit,
) {
    chapters.forEach { c ->
        val isCurrent = c == current
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { onClick(c) }
                    .padding(horizontal = 24.dp, vertical = 12.dp),
        ) {
            Text(
                clock((c.start * 1000).toLong()),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.widthIn(min = 56.dp),
            )
            Text(
                c.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun SheetHeader(
    title: String,
    onBack: (() -> Unit)?,
    onClose: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = if (onBack == null) 24.dp else 8.dp, end = 8.dp, bottom = 8.dp),
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            }
        }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(android.R.string.cancel))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    label: String,
    value: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 24.dp, vertical = 14.dp),
    ) {
        Icon(icon, contentDescription = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 180.dp),
        )
        if (enabled) Icon(Icons.Filled.ChevronRight, contentDescription = null)
    }
}

@Composable
private fun SwitchRow(
    icon: ImageVector?,
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onChange(!checked) }
                .padding(horizontal = 24.dp, vertical = 10.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 2.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Below the video, like Flow: views and date (tap for the description and chapters), the
 * channel, and always-visible buttons: likes, Save, Download, Background, Open in YouTube.
 */
@Composable
internal fun VideoInfo(
    key: String,
    details: VideoDetails,
    itemId: Long,
    link: String?,
    isBookmarked: Boolean,
    onToggleBookmark: (() -> Unit)?,
    positionLabel: String?,
    onOpenInYouTube: () -> Unit,
    modifier: Modifier = Modifier,
    transcriptShown: Boolean = false,
    onToggleTranscript: (() -> Unit)? = null,
    playerLabel: String? = null,
) {
    val di = LocalDI.current
    val playback: YouTubePlayback by di.instance()
    val state by playback.state.collectAsState()
    val current = state?.takeIf { it.key == key }
    var expanded by rememberSaveable(key) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = modifier.fillMaxWidth()) {
        val meta =
            listOfNotNull(
                // First, so it is never cut off
                playerLabel,
                details.viewCount?.let { stringResource(R.string.video_views, VideoDetails.compactCount(it)) },
                relativeDate(details.uploadDate),
                positionLabel,
            ).joinToString(" · ")
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        ) {
            Text(
                meta,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                stringResource(if (expanded) R.string.video_less else R.string.video_more),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 6.dp),
            )
        }

        if (details.channel.isNotBlank()) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(details.channel.take(1).uppercase(), style = MaterialTheme.typography.titleMedium)
                    }
                }
                Column {
                    Text(details.channel, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    details.channelFollowers?.let {
                        Text(
                            stringResource(R.string.video_subscribers, VideoDetails.compactCount(it)),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        ) {
            val padding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            details.likeCount?.let {
                InfoButton(Icons.Outlined.ThumbUp, VideoDetails.compactCount(it), padding, enabled = false) {}
            }
            // Up front so it is in view without scrolling the row
            if (onToggleTranscript != null) {
                TranscriptButton(transcriptShown, onToggleTranscript, padding)
            }
            if (onToggleBookmark != null) {
                InfoButton(
                    if (isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                    stringResource(if (isBookmarked) R.string.action_saved else R.string.action_save),
                    padding,
                    onClick = onToggleBookmark,
                )
            }
            if (itemId > 0 && link != null) {
                DownloadVideoButton(itemId, link, padding)
            }
            if (current != null) {
                InfoButton(
                    Icons.Outlined.Headphones,
                    stringResource(R.string.video_background),
                    padding,
                    selected = current.background,
                ) { playback.setBackground(!current.background) }
            }
            InfoButton(Icons.AutoMirrored.Outlined.OpenInNew, stringResource(R.string.open_in_youtube_app), padding, onClick = onOpenInYouTube)
        }

        if (expanded) {
            if (details.description.isNotBlank()) {
                Text(details.description, style = MaterialTheme.typography.bodyMedium)
            }
            if (details.chapters.isNotEmpty()) {
                Text(stringResource(R.string.player_chapters), style = MaterialTheme.typography.titleMedium)
                Column {
                    ChapterList(details.chapters, null) { chapter ->
                        if (current != null) {
                            playback.seekTo(chapter.start)
                            playback.player.value?.play()
                        }
                    }
                }
            }
        }
    }
}

/** Shows or hides the video's transcript under it; filled while it is shown. */
@Composable
internal fun TranscriptButton(
    shown: Boolean,
    onToggle: () -> Unit,
    padding: PaddingValues = ButtonDefaults.ContentPadding,
) {
    InfoButton(Icons.Outlined.ClosedCaption, stringResource(R.string.transcript), padding, selected = shown, onClick = onToggle)
}

/** Save video offline, with its progress: the same job as the panel under the video. */
@Composable
internal fun DownloadVideoButton(
    itemId: Long,
    link: String,
    padding: PaddingValues = ButtonDefaults.ContentPadding,
) {
    val offlineVideos: OfflineVideos by LocalDI.current.instance()
    val jobs by offlineVideos.jobs.collectAsState()
    val job = jobs[itemId]
    val label =
        when {
            job == null || job.phase == VideoPhase.ERROR -> stringResource(R.string.video_download)
            job.phase == VideoPhase.PAUSED -> stringResource(R.string.downloads_paused_percent, job.overall.toInt())
            else -> stringResource(R.string.video_downloading, job.overall.toInt())
        }
    var pickQuality by remember { mutableStateOf(false) }
    InfoButton(Icons.Outlined.Download, label, padding) {
        when {
            job == null || job.phase == VideoPhase.ERROR -> pickQuality = true
            job.phase == VideoPhase.PAUSED -> offlineVideos.resume(itemId)
        }
    }
    if (pickQuality) {
        DownloadQualityDialog(
            link = link,
            onPick = { format ->
                pickQuality = false
                offlineVideos.save(itemId, link, format)
            },
            onDismiss = { pickQuality = false },
        )
    }
}

@Composable
private fun InfoButton(
    icon: ImageVector,
    label: String,
    padding: PaddingValues,
    enabled: Boolean = true,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val content: @Composable () -> Unit = {
        Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(label, maxLines = 1)
    }
    if (selected) {
        Button(onClick = onClick, contentPadding = padding) { content() }
    } else {
        FilledTonalButton(
            onClick = onClick,
            enabled = enabled,
            contentPadding = padding,
            colors =
                ButtonDefaults.filledTonalButtonColors(
                    disabledContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    disabledContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
        ) { content() }
    }
}

private val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
private val SLEEP_MINUTES = listOf(10, 15, 30, 45, 60, 90)

private fun clock(ms: Long): String = VideoPositions.formatClock((ms / 1000).toInt())

@Composable
private fun speedLabel(speed: Float): String = if (speed == 1f) stringResource(R.string.player_speed_normal) else "${speed.toString().removeSuffix(".0")}x"

@Composable
private fun qualityLabel(state: PlaybackState): String = if (state.height > 0) "${state.height}p" else ""

@Composable
private fun subtitleLabel(
    label: String,
    automatic: Boolean,
): String = if (automatic) stringResource(R.string.player_subtitles_auto, label) else label

@Composable
private fun subtitleSizeLabel(size: SubtitleSize): String =
    stringResource(
        when (size) {
            SubtitleSize.SMALL -> R.string.player_subtitle_size_small
            SubtitleSize.NORMAL -> R.string.player_subtitle_size_normal
            SubtitleSize.LARGE -> R.string.player_subtitle_size_large
            SubtitleSize.HUGE -> R.string.player_subtitle_size_huge
        },
    )

internal fun relativeDate(yyyymmdd: String?): String? {
    if (yyyymmdd == null || yyyymmdd.length != 8) return null
    return try {
        val time = SimpleDateFormat("yyyyMMdd", Locale.ROOT).parse(yyyymmdd)?.time ?: return null
        DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS).toString()
    } catch (e: Exception) {
        null
    }
}
