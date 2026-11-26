package com.nononsenseapps.feeder.ui.compose.youtube

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.archmodel.Repository
import com.nononsenseapps.feeder.background.runOnceSyncChainSendRead
import com.nononsenseapps.feeder.model.history.CentralHistoryRecorder
import com.nononsenseapps.feeder.model.offline.OfflineVideos
import com.nononsenseapps.feeder.model.youtube.VideoDetails
import com.nononsenseapps.feeder.model.youtube.VideoPositions
import com.nononsenseapps.feeder.model.youtube.YouTubePlayback
import com.nononsenseapps.feeder.model.youtube.YouTubeStreams
import com.nononsenseapps.feeder.ui.compose.feedarticle.YouTubePlayer
import com.nononsenseapps.feeder.ui.compose.navigation.HideMainBar
import com.nononsenseapps.feeder.ui.compose.theme.FeederTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.kodein.di.compose.LocalDI
import org.kodein.di.direct
import org.kodein.di.instance

/**
 * The Shorts shelf in full screen, like YouTube Shorts: one Short per screen, swipe up for the
 * next and down for the previous, each plays at once and loops. Tap pauses. The bin at the bottom
 * left deletes the Short (an article, on every device) with Undo, so it doesn't ask first.
 *
 * The list is the shelf as it was when this opened ([startItemId] first on screen), so Shorts
 * found meanwhile don't move the one playing.
 */
@kotlin.OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ShortsPlayerScreen(
    startItemId: Long,
    viewModel: YouTubeViewModel,
    onClose: () -> Unit,
) {
    HideMainBar()
    val state by viewModel.viewState.collectAsState()
    val shorts = remember { mutableStateListOf<VideoRow>() }
    var startPage by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(state.loaded) {
        if (state.loaded && startPage == null) {
            shorts.addAll(state.shorts)
            startPage = shorts.indexOfFirst { it.item.id == startItemId }.coerceAtLeast(0)
            if (shorts.isEmpty()) onClose()
        }
    }
    BackHandler { onClose() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val first = startPage
        if (first == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        } else {
            ShortsPager(shorts, first, viewModel, onClose)
        }
    }
}

@kotlin.OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ShortsPager(
    shorts: MutableList<VideoRow>,
    startPage: Int,
    viewModel: YouTubeViewModel,
    onClose: () -> Unit,
) {
    val di = LocalDI.current
    val playback: YouTubePlayback by di.instance()
    val streams: YouTubeStreams by di.instance()
    val repository: Repository by di.instance()
    val resources = LocalContext.current.resources
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val undoText = stringResource(R.string.undo_delete_article)
    val pager = rememberPagerState(initialPage = startPage) { shorts.size }

    val settled = shorts.getOrNull(pager.settledPage.coerceAtMost(shorts.lastIndex))
    // The next Shorts start at once too
    LaunchedEffect(settled?.item?.id) {
        val i = pager.settledPage
        streams.prefetch(shorts.drop(i + 1).take(PREFETCH_SHORTS).map { it.videoId })
    }
    // Watching one marks it watched, as opening a video does
    LaunchedEffect(settled?.item?.id) {
        val row = settled ?: return@LaunchedEffect
        delay(MARK_WATCHED_AFTER_MS)
        repository.markAsReadAndNotified(row.item.id)
        di.direct.instance<CentralHistoryRecorder>().itemOpened(row.item.id)
        runOnceSyncChainSendRead(di)
    }
    // The player stops when the Shorts go away (its videos never play in the background)
    val playingKey by rememberUpdatedState(settled?.articleKey)
    DisposableEffect(Unit) {
        onDispose {
            val key = playingKey
            if (key != null && playback.state.value?.key == key) playback.stop()
        }
    }

    fun delete(row: VideoRow) {
        val at = shorts.indexOfFirst { it.item.id == row.item.id }
        if (at < 0) return
        shorts.removeAt(at)
        if (playback.state.value?.key == row.articleKey) playback.stop()
        scope.launch {
            val deleted = viewModel.deleteForGood(listOf(row.item.id))
            snackbar.currentSnackbarData?.dismiss()
            val result =
                snackbar.showSnackbar(
                    message = resources.getQuantityString(R.plurals.n_articles_deleted_everywhere, 1, 1),
                    actionLabel = undoText,
                    duration = SnackbarDuration.Short,
                )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.undoDelete(deleted)
                shorts.add(at.coerceAtMost(shorts.size), row)
                pager.scrollToPage(at.coerceAtMost(shorts.lastIndex))
            } else if (shorts.isEmpty()) {
                // The last one: closes once its Undo has had its chance
                onClose()
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (shorts.isEmpty()) {
            Text(
                stringResource(R.string.youtube_shorts_none_left),
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        VerticalPager(
            state = pager,
            beyondViewportPageCount = 1,
            key = { shorts.getOrNull(it)?.item?.id ?: it },
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val row = shorts.getOrNull(page) ?: return@VerticalPager
            ShortPage(
                row = row,
                active = page == pager.settledPage,
                position = "${page + 1} / ${shorts.size}",
                onClose = onClose,
                onDelete = { delete(row) },
            )
        }
        SnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 72.dp),
        )
    }
}

private sealed interface ShortStreams {
    data object Loading : ShortStreams

    data class Ready(
        val details: VideoDetails,
    ) : ShortStreams

    data object Failed : ShortStreams
}

@OptIn(UnstableApi::class)
@Composable
private fun ShortPage(
    row: VideoRow,
    active: Boolean,
    position: String,
    onClose: () -> Unit,
    onDelete: () -> Unit,
) {
    val di = LocalDI.current
    val playback: YouTubePlayback by di.instance()
    val offlineVideos: OfflineVideos by di.instance()
    val videoPositions: VideoPositions by di.instance()
    val key = row.articleKey

    Box(Modifier.fillMaxSize()) {
        // The picture until the video plays (and on the Shorts above and below while swiping)
        coil3.compose.AsyncImage(
            model = youtubeThumbnail(row.videoId, large = true),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (active) {
            val streams by produceState<ShortStreams>(ShortStreams.Loading, row.videoId) {
                value =
                    try {
                        ShortStreams.Ready(offlineVideos.playbackDetails(row.videoId))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(LOG_TAG, "No streams for Short ${row.videoId}", e)
                        ShortStreams.Failed
                    }
            }
            when (val s = streams) {
                is ShortStreams.Ready -> ShortVideo(row, s.details)
                ShortStreams.Failed ->
                    YouTubePlayer(
                        videoId = row.videoId,
                        start = 0,
                        title = row.item.title,
                        onPosition = { t, d, _ -> videoPositions.save(key, t, d) },
                        modifier = Modifier.fillMaxSize(),
                    )
                ShortStreams.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            }
        }

        // Dark edges so the white text and buttons read over any picture
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(120.dp)
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent))),
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(200.dp)
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))),
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = FeederTokens.Space.xs),
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.go_back), tint = Color.White)
            }
            Text(
                stringResource(R.string.youtube_shorts),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            Text(
                position,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.padding(end = FeederTokens.Space.md),
            )
        }

        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(start = FeederTokens.Space.lg, end = FeederTokens.Space.lg, bottom = FeederTokens.Space.sm),
        ) {
            Text(
                row.channel,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                row.item.title,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            // Small, at the bottom left, like the request: one Short has Undo, so no question first
            IconButton(onClick = onDelete, modifier = Modifier.padding(top = FeederTokens.Space.xs).size(40.dp).offset(x = (-8).dp)) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.action_delete_article),
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

/** The Short playing: [YouTubePlayback]'s player, looping, filling the screen. Tap pauses. */
@OptIn(UnstableApi::class)
@Composable
private fun ShortVideo(
    row: VideoRow,
    details: VideoDetails,
) {
    val playback: YouTubePlayback by LocalDI.current.instance()
    val lifecycleOwner = LocalLifecycleOwner.current
    val key = row.articleKey

    DisposableEffect(key) {
        playback.open(key, row.videoId, key, row.item.id, row.item.title, details, 0)
        playback.setBackground(false)
        playback.player.value?.let {
            it.repeatMode = Player.REPEAT_MODE_ONE
            it.seekTo(0)
            it.play()
        }
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

    val state by playback.state.collectAsState()
    val player by playback.player.collectAsState()
    val exo = player
    if (state?.key != key || exo == null) return

    var playing by remember { mutableStateOf(exo.playWhenReady) }
    var started by remember { mutableStateOf(exo.currentPosition > 0) }
    var progress by remember { mutableFloatStateOf(0f) }
    var upright by remember { mutableStateOf(true) }
    LaunchedEffect(exo) {
        while (true) {
            val size = exo.videoSize
            if (size.height > 0) upright = size.width * size.pixelWidthHeightRatio / size.height < UPRIGHT_RATIO
            playing = exo.playWhenReady
            if (exo.isPlaying) started = true
            val d = exo.duration
            progress = if (d > 0) (exo.currentPosition.toFloat() / d).coerceIn(0f, 1f) else 0f
            delay(PROGRESS_TICK_MS)
        }
    }
    val pauseLabel = stringResource(if (playing) R.string.player_pause else R.string.player_play)

    Box(
        Modifier
            .fillMaxSize()
            .semantics { contentDescription = pauseLabel }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                if (exo.playWhenReady) exo.pause() else exo.play()
                playing = exo.playWhenReady
            },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    setKeepContentOnPlayerReset(true)
                }
            },
            update = {
                it.player = exo
                // Upright Shorts fill the screen like on YouTube (edges cropped); wider ones show whole
                it.resizeMode = if (upright) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
            },
            onRelease = { it.player = null },
            // Black behind the video once it plays, the thumbnail until then
            modifier = Modifier.fillMaxSize().then(if (started) Modifier.background(Color.Black) else Modifier),
        )
        if (!playing) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.align(Alignment.Center).size(72.dp),
            )
        }
        LinearProgressIndicator(
            progress = { progress },
            drawStopIndicator = {},
            gapSize = 0.dp,
            color = Color.White,
            trackColor = Color.White.copy(alpha = 0.25f),
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .height(2.dp),
        )
    }
}

private const val LOG_TAG = "FEEDER_SHORTS"
private const val PREFETCH_SHORTS = 2
private const val MARK_WATCHED_AFTER_MS = 2_000L
private const val PROGRESS_TICK_MS = 200L

/** Narrower than this (width / height) counts as upright: 9:16 is 0.56, 3:4 is 0.75. */
private const val UPRIGHT_RATIO = 0.8f
