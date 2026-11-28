package com.nononsenseapps.feeder.ui.compose.feedarticle

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.ui.semantics.selected
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.archmodel.Article
import com.nononsenseapps.feeder.archmodel.Repository
import com.nononsenseapps.feeder.model.PodcastPlayerState
import com.nononsenseapps.feeder.model.PodcastPlayerStateHolder
import com.nononsenseapps.feeder.ui.compose.feed.PlainTooltipBox
import com.nononsenseapps.feeder.ui.compose.theme.FeederTokens
import com.nononsenseapps.feeder.ui.compose.theme.LocalPaperMode
import com.nononsenseapps.feeder.ui.compose.transcript.TranscriptPanel
import kotlinx.coroutines.flow.emptyFlow
import org.kodein.di.compose.LocalDI
import org.kodein.di.instance

@Composable
fun HideablePodcastPlayer(
    visibleState: MutableTransitionState<Boolean>,
    viewState: PodcastPlayerState,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onSeekTo: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        modifier = modifier,
        visibleState = visibleState,
        enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(256)),
        exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(256)),
    ) {
        PodcastPlayerBar(
            viewState = viewState,
            onPlay = onPlay,
            onPause = onPause,
            onStop = onStop,
            onSeekBack = onSeekBack,
            onSeekForward = onSeekForward,
            onSeekTo = onSeekTo,
        )
    }
}

/**
 * The podcast player: a Material 3 mini player docked at the bottom (artwork, title, progress,
 * back 10 s and a large play/pause). Tapping it opens the Now playing sheet with the full controls.
 * [onSeekForward] is kept for callers; forward is 30 s here, as podcast apps do.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun PodcastPlayerBar(
    viewState: PodcastPlayerState,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onSeekTo: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val di = LocalDI.current
    val holder: PodcastPlayerStateHolder by di.instance()
    val repository: Repository by di.instance()
    val itemId = viewState.itemId
    val article by remember(itemId) {
        if (itemId != null) repository.getArticleFlow(itemId) else emptyFlow()
    }.collectAsState(initial = null)
    var expanded by rememberSaveable { mutableStateOf(false) }
    val onToggle = { if (viewState.isPlaying || viewState.isLoading) onPause() else onPlay() }

    MiniPlayer(
        viewState = viewState,
        article = article,
        onOpen = { expanded = true },
        onToggle = onToggle,
        onSeekBack = onSeekBack,
        modifier = modifier,
    )

    if (expanded) {
        NowPlayingSheet(
            viewState = viewState,
            article = article,
            onDismiss = { expanded = false },
            onToggle = onToggle,
            onSeekBack = onSeekBack,
            onSeekForward = { holder.seekBy(30_000) },
            onSeekTo = onSeekTo,
            onSpeed = { holder.setSpeed(nextSpeed(viewState.speed)) },
            onSleep = { holder.setSleepTimer(nextSleep(viewState)) },
            onStop = {
                expanded = false
                onStop()
            },
        )
    }
}

@Composable
private fun MiniPlayer(
    viewState: PodcastPlayerState,
    article: Article?,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onSeekBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val openLabel = stringResource(R.string.player_open_now_playing)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 3.dp,
        modifier =
            modifier
                .navigationBarsPadding()
                .padding(horizontal = FeederTokens.Space.sm, vertical = FeederTokens.Space.sm)
                .fillMaxWidth(),
    ) {
        Column {
            ProgressLine(viewState)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.md),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = openLabel, onClick = onOpen)
                        .padding(FeederTokens.Space.sm),
            ) {
                Artwork(article, size = 48.dp, corner = FeederTokens.Radius.md)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = article?.title?.ifBlank { null } ?: stringResource(R.string.item_now_playing),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text =
                            viewState.errorMessage
                                ?: listOfNotNull(
                                    article?.feedDisplayTitle?.ifBlank { null },
                                    "${clock(viewState.positionMillis)} / ${clock(viewState.durationMillis)}",
                                ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (viewState.errorMessage != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                PlainTooltipBox(tooltip = { Text(stringResource(R.string.seek_back_10_seconds)) }) {
                    IconButton(onClick = onSeekBack, enabled = viewState.canSeek) {
                        Icon(Icons.Default.Replay10, contentDescription = stringResource(R.string.seek_back_10_seconds))
                    }
                }
                PlayPauseButton(
                    playing = viewState.isPlaying || viewState.isLoading,
                    onClick = onToggle,
                    width = 56.dp,
                    height = 48.dp,
                    iconSize = 24.dp,
                )
            }
        }
    }
}

/** A thin bar along the mini player's top edge; indeterminate while loading. */
@Composable
private fun ProgressLine(viewState: PodcastPlayerState) {
    val modifier = Modifier.fillMaxWidth().height(4.dp)
    if (viewState.isLoading || viewState.isBuffering) {
        LinearProgressIndicator(modifier = modifier, gapSize = 0.dp)
    } else {
        LinearProgressIndicator(
            progress = { fraction(viewState) },
            modifier = modifier,
            gapSize = 0.dp,
            drawStopIndicator = {},
            trackColor = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NowPlayingSheet(
    viewState: PodcastPlayerState,
    article: Article?,
    onDismiss: () -> Unit,
    onToggle: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onSeekTo: (Int) -> Unit,
    onSpeed: () -> Unit,
    onSleep: () -> Unit,
    onStop: () -> Unit,
) {
    // P2: Transcript and Chapters are tabs at the foot of the sheet; the open one replaces the artwork
    var tab by rememberSaveable { mutableStateOf(NowPlayingTab.NONE) }
    val showTranscript = tab != NowPlayingTab.NONE
    var menuOpen by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = FeederTokens.Space.xl)
                    .padding(bottom = FeederTokens.Space.lg),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                PlainTooltipBox(tooltip = { Text(stringResource(R.string.player_collapse)) }) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.player_collapse))
                    }
                }
                Text(
                    stringResource(R.string.item_now_playing),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Box {
                    PlainTooltipBox(tooltip = { Text(stringResource(R.string.player_more_options)) }) {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.player_more_options))
                        }
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.player_stop_and_close)) },
                            leadingIcon = { Icon(Icons.Default.Close, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onStop()
                            },
                        )
                    }
                }
            }

            val itemId = viewState.itemId
            if (showTranscript && itemId != null) {
                TranscriptPanel(
                    itemId = itemId,
                    positionMs = viewState.positionMillis.toLong(),
                    onSeek = { onSeekTo(it.toInt()) },
                    // Chapters: the feed's chapters, or 5-minute blocks named by their first words
                    chapters = if (tab == NowPlayingTab.CHAPTERS) emptyList() else null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 320.dp).padding(vertical = FeederTokens.Space.sm),
                )
            } else {
                Artwork(
                    article,
                    size = null,
                    corner = 28.dp,
                    modifier = Modifier.padding(vertical = FeederTokens.Space.sm).widthIn(max = 300.dp).fillMaxWidth(),
                )
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(FeederTokens.Space.xs),
                modifier = Modifier.fillMaxWidth().padding(top = FeederTokens.Space.lg),
            ) {
                Text(
                    text = article?.title ?: "",
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = article?.feedDisplayTitle ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            SeekBar(viewState, onSeekTo, modifier = Modifier.padding(top = FeederTokens.Space.md))

            viewState.errorMessage?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = FeederTokens.Space.xs),
                )
            }

            // Expressive controls: a large play/pause between round back/forward buttons
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.md),
                modifier = Modifier.padding(vertical = FeederTokens.Space.lg),
            ) {
                RoundSeekButton(Icons.Default.Replay10, stringResource(R.string.seek_back_10_seconds), onSeekBack, viewState.canSeek)
                PlayPauseButton(
                    playing = viewState.isPlaying || viewState.isLoading,
                    onClick = onToggle,
                    width = 112.dp,
                    height = 80.dp,
                    iconSize = 36.dp,
                )
                RoundSeekButton(Icons.Default.Forward30, stringResource(R.string.seek_forward_30_seconds), onSeekForward, viewState.canSeek)
            }

            // Labeled secondary controls
            Row(horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.sm), modifier = Modifier.fillMaxWidth()) {
                PlayerChip(
                    icon = Icons.Default.Speed,
                    label = "%s×".format(speedText(viewState.speed)),
                    description = stringResource(R.string.player_speed),
                    onClick = onSpeed,
                    modifier = Modifier.weight(1f),
                )
                val sleepLeft = sleepMinutesLeft(viewState)
                PlayerChip(
                    icon = if (sleepLeft != null) Icons.Default.Bedtime else Icons.Outlined.Bedtime,
                    label = if (sleepLeft != null) stringResource(R.string.player_sleep_left, sleepLeft) else stringResource(R.string.player_sleep),
                    description = stringResource(R.string.player_sleep_timer),
                    onClick = onSleep,
                    selected = sleepLeft != null,
                    modifier = Modifier.weight(1f),
                )
            }

            if (itemId != null) {
                NowPlayingTabs(
                    current = tab,
                    onSelect = { tab = if (tab == it) NowPlayingTab.NONE else it },
                    modifier = Modifier.padding(top = FeederTokens.Space.md),
                )
            }
        }
    }
}

/** Seek bar with a wavy played part while playing (Material 3 Expressive) and a bar thumb. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SeekBar(
    viewState: PodcastPlayerState,
    onSeekTo: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val max = viewState.durationMillis.toFloat().coerceAtLeast(1f)
    val sliderState = remember(viewState.audioUrl, max) { SliderState(0f, 0, 0f..max) }
    var isDragging by remember(viewState.audioUrl) { mutableStateOf(false) }
    LaunchedEffect(viewState.positionMillis, isDragging, sliderState) {
        if (!isDragging) sliderState.value = viewState.positionMillis.toFloat().coerceIn(0f, max)
    }
    val shown = if (isDragging) sliderState.value else viewState.positionMillis.toFloat()
    Column(modifier = modifier.fillMaxWidth()) {
        Slider(
            state = sliderState,
            onValueChange = {
                isDragging = true
                sliderState.value = it
            },
            onValueChangeFinished = {
                isDragging = false
                onSeekTo(sliderState.value.toInt())
            },
            enabled = viewState.canSeek,
            thumb = {
                Box(
                    Modifier
                        .size(width = 4.dp, height = 24.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
                )
            },
            track = { state ->
                val progress = state.coercedValueAsFraction
                if (viewState.isPlaying && !isDragging && !LocalPaperMode.current) {
                    LinearWavyProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(clock(shown.toInt()), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(clock(viewState.durationMillis), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Play/pause whose corners tighten while playing, as Material 3 Expressive buttons do. */
@Composable
private fun PlayPauseButton(
    playing: Boolean,
    onClick: () -> Unit,
    width: Dp,
    height: Dp,
    iconSize: Dp,
) {
    val corner by animateDpAsState(if (playing) height / 3 else height / 2, label = "playCorner")
    val label = stringResource(if (playing) R.string.pause_audio else R.string.play_audio)
    PlainTooltipBox(tooltip = { Text(label) }) {
        FilledIconButton(
            onClick = onClick,
            shape = RoundedCornerShape(corner),
            modifier = Modifier.size(width = width, height = height),
        ) {
            Icon(
                if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = label,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Composable
private fun RoundSeekButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
) {
    PlainTooltipBox(tooltip = { Text(label) }) {
        FilledTonalIconButton(
            onClick = onClick,
            enabled = enabled,
            shape = CircleShape,
            modifier = Modifier.size(64.dp),
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun PlayerChip(
    icon: ImageVector,
    label: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    FilledTonalButton(
        onClick = onClick,
        shape = RoundedCornerShape(FeederTokens.Radius.md),
        colors =
            if (selected) {
                androidx.compose.material3.ButtonDefaults.filledTonalButtonColors()
            } else {
                androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                )
            },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = FeederTokens.Space.sm),
        modifier = modifier.height(40.dp).semantics { contentDescription = "$description: $label" },
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The episode's image, or the podcast icon on a tinted tile. [size] null = square filling the width. */
@Composable
private fun Artwork(
    article: Article?,
    size: Dp?,
    corner: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .then(if (size != null) Modifier.size(size) else Modifier.aspectRatio(1f))
                .clip(RoundedCornerShape(corner))
                .background(MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Icon(
            Icons.Default.Podcasts,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(if (size != null) size / 2 else 96.dp),
        )
        article?.image?.url?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun fraction(s: PodcastPlayerState): Float = if (s.durationMillis > 0) (s.positionMillis.toFloat() / s.durationMillis).coerceIn(0f, 1f) else 0f

private val SPEEDS = listOf(1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 0.8f)

private fun nextSpeed(current: Float): Float {
    val i = SPEEDS.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
    return SPEEDS[(i + 1) % SPEEDS.size]
}

private fun speedText(speed: Float): String = if (speed % 1f == 0f) "%.0f".format(speed) else "%s".format(speed).trimEnd('0')

private val SLEEP_STEPS = listOf(15, 30, 45, 60)

/** Off → 15 → 30 → 45 → 60 → off. */
private fun nextSleep(s: PodcastPlayerState): Int? {
    val left = sleepMinutesLeft(s) ?: return SLEEP_STEPS.first()
    return SLEEP_STEPS.firstOrNull { it > left }
}

private fun sleepMinutesLeft(s: PodcastPlayerState): Int? {
    val end = s.sleepEndsAtMillis ?: return null
    val ms = end - System.currentTimeMillis()
    return if (ms > 0) ((ms + 59_999) / 60_000).toInt() else null
}

private fun clock(totalMillis: Int): String {
    val totalSeconds = (totalMillis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

private enum class NowPlayingTab { NONE, TRANSCRIPT, CHAPTERS }

/** The tab strip at the foot of Now playing (YouTube Music style): tap to open, tap again to close. */
@Composable
private fun NowPlayingTabs(
    current: NowPlayingTab,
    onSelect: (NowPlayingTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth().padding(top = FeederTokens.Space.xs)) {
            listOf(NowPlayingTab.TRANSCRIPT to R.string.transcript, NowPlayingTab.CHAPTERS to R.string.player_chapters).forEach { (t, label) ->
                val selected = current == t
                TextButton(
                    onClick = { onSelect(t) },
                    modifier = Modifier.semantics { this.selected = selected },
                ) {
                    Text(
                        stringResource(label),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        textDecoration = if (selected) androidx.compose.ui.text.style.TextDecoration.Underline else null,
                    )
                }
            }
        }
    }
}
