package com.nononsenseapps.feeder.ui.compose.downloads

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import coil3.compose.rememberAsyncImagePainter
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.db.room.ItemStatus
import com.nononsenseapps.feeder.model.offline.VideoPhase
import com.nononsenseapps.feeder.model.playlists.ItemKind
import com.nononsenseapps.feeder.ui.compose.item.ItemActionButton
import com.nononsenseapps.feeder.ui.compose.item.ItemCommands
import com.nononsenseapps.feeder.ui.compose.item.ItemRow
import com.nononsenseapps.feeder.ui.compose.item.ItemRowActions
import com.nononsenseapps.feeder.ui.compose.item.ItemRowModel
import com.nononsenseapps.feeder.ui.compose.item.ageText
import com.nononsenseapps.feeder.ui.compose.item.rememberItemCommands
import com.nononsenseapps.feeder.ui.compose.item.rememberItemProgress
import com.nononsenseapps.feeder.ui.compose.playlists.rememberAddToPlaylist
import com.nononsenseapps.feeder.ui.compose.settings.formatBytes
import com.nononsenseapps.feeder.ui.compose.theme.SensibleTopAppBar
import com.nononsenseapps.feeder.ui.compose.transcript.TranscriptPanel
import kotlinx.coroutines.delay

/**
 * Downloads: every video saved on the phone. Unfinished downloads first (percent, Pause or
 * Resume, Delete), then the finished ones, which are also the playlist: Play all plays them
 * one after another, and the drag handle on each changes the order.
 */
@Composable
fun DownloadsScreen(
    onNavigateUp: () -> Unit,
    viewModel: DownloadsViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val player by viewModel.player.collectAsStateWithLifecycle()
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var showTranscript by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    // Without Background play, leaving the app pauses (the player keeps its place)
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onAppHidden() }

    val current = player
    if (fullscreen && current != null && !state.playingAudio) {
        FullscreenPlayer(current, isPlaying = state.isPlaying, onExit = { fullscreen = false })
        return
    }

    Scaffold(
        modifier = modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)),
        contentWindowInsets = WindowInsets.statusBars,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            SensibleTopAppBar(
                title = stringResource(R.string.downloads),
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.go_back))
                    }
                },
                actions = {
                    if (state.videos.isNotEmpty() || state.episodes.isNotEmpty() || state.incomplete.isNotEmpty()) {
                        IconButton(onClick = { confirmDeleteAll = true }) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = stringResource(R.string.downloads_delete_all))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (current != null) {
                InlinePlayer(
                    player = current,
                    isPlaying = state.isPlaying,
                    playing = state.playing,
                    audio = state.playingAudio,
                    onFullscreen = { fullscreen = true },
                    onClose = viewModel::closePlayer,
                    showTranscript = showTranscript,
                    onToggleTranscript = { showTranscript = !showTranscript },
                )
            }
            val episodeId = state.playingId?.takeIf { current != null && state.playingAudio }
            if (episodeId != null && showTranscript) {
                // Subtitles for the playing episode take the place of the list
                TranscriptPanel(
                    itemId = episodeId,
                    positionMs = rememberPlayerPosition(current!!),
                    onSeek = { current.seekTo(it) },
                    modifier = Modifier.weight(1f),
                )
            } else {
                DownloadsTabs(
                    tab = state.tab,
                    videoCount = state.videos.size + state.incomplete.count { !it.job.audio },
                    audioCount = state.episodes.size + state.incomplete.count { it.job.audio },
                    onTab = viewModel::setTab,
                )
                DownloadsList(state, viewModel, snackbar = snackbar, modifier = Modifier.weight(1f))
            }
        }
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text(stringResource(R.string.downloads_delete_all)) },
            text = { Text(stringResource(R.string.downloads_delete_all_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteAll = false
                    viewModel.deleteAll()
                }) { Text(stringResource(R.string.downloads_delete_all_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAll = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun DownloadsList(
    state: DownloadsViewState,
    viewModel: DownloadsViewModel,
    snackbar: SnackbarHostState?,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val commands = rememberItemCommands()
    // The list being dragged; the view model's order otherwise
    val order = remember { mutableStateListOf<DownloadedVideo>() }
    var draggingId by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(state.completed, draggingId) {
        if (draggingId == null) {
            order.clear()
            order.addAll(state.completed)
        }
    }

    if (state.loaded && state.completed.isEmpty() && state.incompleteHere.isEmpty()) {
        EmptyDownloads(state.audioTab, modifier)
        return
    }

    LazyColumn(state = listState, modifier = modifier.fillMaxSize()) {
        if (state.completed.isNotEmpty()) {
            item(key = "controls", contentType = "controls") {
                PlaylistControls(state, viewModel)
            }
        }
        if (state.incompleteHere.isNotEmpty()) {
            item(key = "incomplete-header", contentType = "header") {
                SectionHeader(stringResource(R.string.downloads_incomplete), MaterialTheme.colorScheme.error)
            }
            items(state.incompleteHere, key = { "i${it.id}" }, contentType = { "incomplete" }) { download ->
                IncompleteRow(download, viewModel, modifier = Modifier.animateItem())
            }
        }
        if (order.isNotEmpty()) {
            item(key = "completed-header", contentType = "header") {
                SectionHeader(stringResource(R.string.downloads_completed), MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(order, key = { it.id }, contentType = { "completed" }) { video ->
            val dragging = draggingId == video.id
            CompletedRow(
                video = video,
                status = status[video.id],
                commands = commands,
                snackbar = snackbar,
                playing = state.playingId == video.id && state.playingAudio == state.audioTab,
                onPlay = { viewModel.play(video.id) },
                onDelete = { viewModel.delete(video.id) },
                onMoveUp = { viewModel.move(video.id, -1) },
                onMoveDown = { viewModel.move(video.id, 1) },
                dragHandleModifier =
                    Modifier.dragToReorder(
                        id = video.id,
                        listState = listState,
                        order = order,
                        onStart = {
                            draggingId = video.id
                            dragOffset = 0f
                        },
                        offset = { dragOffset },
                        setOffset = { dragOffset = it },
                        onEnd = {
                            viewModel.setOrder(order.map { it.id })
                            draggingId = null
                            dragOffset = 0f
                        },
                    ),
                modifier =
                    if (dragging) {
                        Modifier
                            .zIndex(1f)
                            .graphicsLayer {
                                translationY = dragOffset
                                shadowElevation = 8f
                            }.background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    } else {
                        Modifier.animateItem()
                    },
            )
            HorizontalDivider()
        }
    }
}

/**
 * Drag the handle to move a video: it follows the finger, and swaps with the row it passes
 * the middle of. [onEnd] saves the new order.
 */
private fun Modifier.dragToReorder(
    id: Long,
    listState: LazyListState,
    order: MutableList<DownloadedVideo>,
    onStart: () -> Unit,
    offset: () -> Float,
    setOffset: (Float) -> Unit,
    onEnd: () -> Unit,
): Modifier =
    pointerInput(id) {
        detectDragGestures(
            onDragStart = { onStart() },
            onDragEnd = { onEnd() },
            onDragCancel = { onEnd() },
            onDrag = { change, amount ->
                change.consume()
                setOffset(offset() + amount.y)
                val items = listState.layoutInfo.visibleItemsInfo
                val dragged = items.firstOrNull { it.key == id } ?: return@detectDragGestures
                val middle = dragged.offset + offset() + dragged.size / 2f
                val target =
                    items.firstOrNull { info ->
                        info.key is Long && info.key != id && middle > info.offset && middle < info.offset + info.size
                    } ?: return@detectDragGestures
                val from = order.indexOfFirst { it.id == id }
                val to = order.indexOfFirst { it.id == target.key }
                if (from < 0 || to < 0) return@detectDragGestures
                order.add(to, order.removeAt(from))
                // The row's place moved; keep it under the finger
                setOffset(offset() - (target.offset - dragged.offset))
            },
        )
    }

@Composable
private fun PlaylistControls(
    state: DownloadsViewState,
    viewModel: DownloadsViewModel,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { viewModel.play() }) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Text(stringResource(R.string.downloads_play_all), modifier = Modifier.padding(start = 8.dp))
            }
            val total = state.completed.sumOf { it.durationMs }
            Text(
                stringResource(
                    if (state.audioTab) R.string.downloads_summary_audio else R.string.downloads_summary,
                    state.completed.size,
                    formatDuration(total),
                    formatBytes(state.completed.sumOf { it.sizeBytes }),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.background,
                onClick = { viewModel.setBackground(!state.background) },
                label = { Text(stringResource(R.string.downloads_background)) },
            )
            FilterChip(
                selected = state.autoplay,
                onClick = { viewModel.setAutoplay(!state.autoplay) },
                label = { Text(stringResource(R.string.downloads_autoplay)) },
            )
            FilterChip(
                selected = state.loop,
                onClick = { viewModel.setLoop(!state.loop) },
                label = { Text(stringResource(R.string.downloads_loop)) },
            )
            FilterChip(
                selected = state.removeWatched,
                onClick = { viewModel.setRemoveWatched(!state.removeWatched) },
                label = { Text(stringResource(R.string.downloads_remove_watched)) },
            )
        }
    }
}

@Composable
private fun SectionHeader(
    text: String,
    color: Color,
) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = color,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun IncompleteRow(
    download: IncompleteDownload,
    viewModel: DownloadsViewModel,
    modifier: Modifier = Modifier,
) {
    val job = download.job
    val percent = job.overall.toInt()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .width(THUMB_WIDTH)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (download.info.thumbnailUrl != null) {
                AsyncImage(
                    model = download.info.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
            Text("$percent%", color = Color.White, style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(
                progress = { job.overall / 100f },
                color = MaterialTheme.colorScheme.error,
                trackColor = Color.Transparent,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
            )
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            if (download.info.title.isNotBlank()) {
                Text(download.info.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (download.info.channel.isNotBlank()) {
                Text(
                    download.info.channel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                when (job.phase) {
                    VideoPhase.ERROR -> stringResource(R.string.downloads_failed)
                    VideoPhase.PAUSED -> stringResource(R.string.downloads_paused_percent, percent)
                    VideoPhase.PREPARING -> stringResource(R.string.offline_video_preparing)
                    VideoPhase.SUBTITLES -> stringResource(R.string.offline_video_saving_subtitles)
                    else -> "$percent%"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        when (job.phase) {
            VideoPhase.ERROR ->
                IconButton(onClick = { viewModel.retry(download.id) }) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.offline_video_retry))
                }

            VideoPhase.PAUSED ->
                IconButton(onClick = { viewModel.resume(download.id) }) {
                    Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.downloads_resume))
                }

            else ->
                IconButton(onClick = { viewModel.pause(download.id) }) {
                    Icon(Icons.Default.Pause, contentDescription = stringResource(R.string.downloads_pause))
                }
        }
        IconButton(onClick = { viewModel.cancel(download.id) }) {
            Icon(Icons.Default.DeleteOutline, contentDescription = stringResource(R.string.downloads_delete))
        }
    }
}

/** A saved video or episode: the shared item row, plus Delete download and a drag handle. */
@Composable
private fun CompletedRow(
    video: DownloadedVideo,
    status: ItemStatus?,
    commands: ItemCommands,
    snackbar: SnackbarHostState?,
    playing: Boolean,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    modifier: Modifier = Modifier,
    dragHandleModifier: Modifier = Modifier,
) {
    val moveUp = stringResource(R.string.downloads_move_up)
    val moveDown = stringResource(R.string.downloads_move_down)
    val addToPlaylist = rememberAddToPlaylist()
    val progress = rememberItemProgress(video.id, status?.videoKey.orEmpty())
    val read = status?.read ?: false
    val saved = status?.bookmarked ?: false
    ItemRow(
        model =
            ItemRowModel(
                id = video.id,
                title = video.info.title,
                feedTitle = video.info.channel,
                age = ageText(status?.sortTime),
                kind = if (video.audio) ItemKind.PODCAST else ItemKind.YOUTUBE,
                lengthSeconds = (video.durationMs / 1000).takeIf { it > 0 },
                read = read,
                saved = saved,
                // A still from the saved file or its cover art works with no network
                image = video.thumbnail ?: video.info.thumbnailUrl,
                progress = progress,
            ),
        actions =
            ItemRowActions(
                onPrimary = onPlay,
                onToggleSave = { commands.setSaved(video.id, !saved) },
                onPlaylist = { addToPlaylist(video.id, video.info.title) },
                onToggleRead = { commands.setRead(video.id, !read) },
                onDelete = { commands.delete(video.id, snackbar) },
            ),
        onOpen = onPlay,
        highlighted = playing,
        primaryLabel = if (playing) stringResource(R.string.item_now_playing) else null,
        trailing = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = dragHandleModifier.size(48.dp),
            ) {
                Icon(Icons.Default.DragHandle, contentDescription = stringResource(R.string.downloads_drag_to_reorder))
            }
        },
        extras = {
            ItemActionButton(
                icon = Icons.Default.DeleteOutline,
                label = stringResource(R.string.item_action_delete_download),
                onClick = onDelete,
                danger = true,
            )
        },
        modifier =
            modifier.semantics {
                customActions =
                    listOf(
                        CustomAccessibilityAction(moveUp) {
                            onMoveUp()
                            true
                        },
                        CustomAccessibilityAction(moveDown) {
                            onMoveDown()
                            true
                        },
                    )
            },
    )
}

@OptIn(UnstableApi::class)
@Composable
private fun InlinePlayer(
    player: ExoPlayer,
    isPlaying: Boolean,
    playing: DownloadedVideo?,
    audio: Boolean,
    onFullscreen: () -> Unit,
    onClose: () -> Unit,
    showTranscript: Boolean,
    onToggleTranscript: () -> Unit,
) {
    val onFullscreenNow by rememberUpdatedState(onFullscreen)
    Column {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(if (audio) 2f else 16f / 9f).background(Color.Black)) {
            if (audio && playing != null) {
                // An episode has no picture: its cover art behind the controls
                val local = playing.thumbnail?.let { rememberAsyncImagePainter(it) }
                AsyncImage(
                    model = playing.thumbnail ?: playing.info.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    error = local,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            key(audio) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            setShowSubtitleButton(!audio)
                            setShowNextButton(true)
                            setShowPreviousButton(true)
                            if (audio) {
                                // Controls stay on screen; the cover art shows through
                                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                artworkDisplayMode = PlayerView.ARTWORK_DISPLAY_MODE_OFF
                                controllerShowTimeoutMs = 0
                                controllerHideOnTouch = false
                            } else {
                                setFullscreenButtonClickListener { onFullscreenNow() }
                            }
                        }
                    },
                    update = {
                        it.player = player
                        it.keepScreenOn = isPlaying && !audio
                        if (audio) it.showController()
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
        ) {
            Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(
                    playing?.info?.title ?: "",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!playing?.info?.channel.isNullOrBlank()) {
                    Text(
                        playing.info.channel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (audio && playing != null) {
                IconToggleButton(checked = showTranscript, onCheckedChange = { onToggleTranscript() }) {
                    Icon(
                        if (showTranscript) Icons.Default.ClosedCaption else Icons.Outlined.ClosedCaption,
                        contentDescription = stringResource(if (showTranscript) R.string.transcript_hide else R.string.transcript_show),
                    )
                }
            }
            TextButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = null)
                Text(stringResource(R.string.downloads_close_player), modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

/** The player on its own, landscape, without system bars. Back or the button leaves it. */
@OptIn(UnstableApi::class)
@Composable
private fun FullscreenPlayer(
    player: ExoPlayer,
    isPlaying: Boolean,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val onExitNow by rememberUpdatedState(onExit)
    BackHandler(onBack = onExit)
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val oldOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = oldOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                setShowSubtitleButton(true)
                setShowNextButton(true)
                setShowPreviousButton(true)
                setFullscreenButtonState(true)
                setFullscreenButtonClickListener { onExitNow() }
            }
        },
        update = {
            it.player = player
            it.keepScreenOn = isPlaying
        },
        modifier = Modifier.fillMaxSize().background(Color.Black),
    )
}

@Composable
private fun EmptyDownloads(
    audio: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier.fillMaxSize().padding(32.dp),
    ) {
        Icon(
            if (audio) Icons.Default.Podcasts else Icons.Outlined.DownloadForOffline,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(if (audio) R.string.downloads_empty_audio else R.string.downloads_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

/** Videos | Audio, like the two kinds of downloads in a YouTube client. */
@Composable
private fun DownloadsTabs(
    tab: DownloadsTab,
    videoCount: Int,
    audioCount: Int,
    onTab: (DownloadsTab) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        SegmentedButton(
            selected = tab == DownloadsTab.VIDEOS,
            onClick = { onTab(DownloadsTab.VIDEOS) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            icon = { Icon(Icons.Default.VideoLibrary, contentDescription = null, modifier = Modifier.size(18.dp)) },
        ) {
            Text(stringResource(R.string.downloads_tab_videos, videoCount))
        }
        SegmentedButton(
            selected = tab == DownloadsTab.AUDIO,
            onClick = { onTab(DownloadsTab.AUDIO) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            icon = { Icon(Icons.Default.Podcasts, contentDescription = null, modifier = Modifier.size(18.dp)) },
        ) {
            Text(stringResource(R.string.downloads_tab_audio, audioCount))
        }
    }
}

private val THUMB_WIDTH = 150.dp

private fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

/** 31:45, or 1:02:03 for an hour or more. */
internal fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/** Where [player] is, in ms, updated a few times a second while it plays. */
@Composable
internal fun rememberPlayerPosition(player: Player): Long {
    var position by remember(player) { mutableLongStateOf(player.currentPosition) }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            delay(200)
        }
    }
    return position
}
