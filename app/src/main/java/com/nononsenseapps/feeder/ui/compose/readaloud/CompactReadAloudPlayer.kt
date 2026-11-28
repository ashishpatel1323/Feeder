package com.nononsenseapps.feeder.ui.compose.readaloud

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.model.AppSetting
import com.nononsenseapps.feeder.model.ForcedAuto
import com.nononsenseapps.feeder.model.ForcedLocale
import com.nononsenseapps.feeder.model.LocaleOverride
import com.nononsenseapps.feeder.model.READ_ALOUD_SPEEDS
import com.nononsenseapps.feeder.model.ReadAloudNeighbors
import com.nononsenseapps.feeder.model.ReadAloudProgress
import com.nononsenseapps.feeder.ui.compose.components.safeSemantics
import com.nononsenseapps.feeder.ui.compose.feed.PlainTooltipBox
import com.nononsenseapps.feeder.ui.compose.rsvp.RSVP_DEFAULT_WPM
import com.nononsenseapps.feeder.ui.compose.rsvp.RSVP_MAX_WPM
import com.nononsenseapps.feeder.ui.compose.rsvp.RSVP_MIN_WPM
import com.nononsenseapps.feeder.ui.compose.rsvp.RSVP_WPM_STEP
import com.nononsenseapps.feeder.ui.compose.rsvp.RsvpWordDisplay
import com.nononsenseapps.feeder.ui.compose.theme.LocalPaperMode
import com.nononsenseapps.feeder.ui.compose.theme.PreviewTheme
import com.nononsenseapps.feeder.ui.compose.utils.ImmutableHolder
import com.nononsenseapps.feeder.ui.compose.utils.onKeyEventLikeEscape
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Read-aloud player panel for the article screen and the feed list, styled like the Android media
 * controls: a solid dark panel with a wavy progress line and a big play button. It takes the
 * navigation bar inset itself.
 */
@Composable
fun HideableCompactTTSPlayer(
    visibleState: MutableTransitionState<Boolean>,
    currentlyPlaying: Boolean,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onSkipNext: () -> Unit,
    languages: ImmutableHolder<List<Locale>>,
    onSelectLanguage: (LocaleOverride) -> Unit,
    progress: StateFlow<ReadAloudProgress>?,
    onSeekTo: (Long) -> Unit,
    onGoToSpokenText: () -> Unit,
    displayedArticleId: Long?,
    onSelectSpeed: (Float) -> Unit,
    modifier: Modifier = Modifier,
    diagnostics: () -> String = { "" },
    neighbors: ReadAloudNeighbors = ReadAloudNeighbors(),
    onSkipArticle: (next: Boolean) -> Unit = {},
    autoContinue: Boolean = false,
    onSetAutoContinue: (Boolean) -> Unit = {},
    showNowReading: Boolean = false,
    wordMode: WordModeState = WordModeState(),
    onSetShowWord: ((Boolean) -> Unit)? = null,
    onSetMuted: ((Boolean) -> Unit)? = null,
    onSetWpm: (Int) -> Unit = {},
    onSkipWords: (Int) -> Unit = {},
    onJumpToWord: (Int) -> Unit = {},
    statusLine: PlayerStatusLine? = null,
) {
    AnimatedVisibility(
        modifier = modifier,
        visibleState = visibleState,
        enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(256)),
        exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(256)),
    ) {
        val currentProgress = progress?.collectAsStateWithLifecycle()?.value ?: ReadAloudProgress()
        CompactTTSPlayer(
            currentlyPlaying = currentlyPlaying,
            onPlay = onPlay,
            onPause = onPause,
            onStop = onStop,
            onSkipNext = onSkipNext,
            languages = languages,
            onSelectLanguage = onSelectLanguage,
            progress = currentProgress,
            onSeekTo = onSeekTo,
            onGoToSpokenText = onGoToSpokenText,
            displayedArticleId = displayedArticleId,
            onSelectSpeed = onSelectSpeed,
            diagnostics = diagnostics,
            neighbors = neighbors,
            onSkipArticle = onSkipArticle,
            autoContinue = autoContinue,
            onSetAutoContinue = onSetAutoContinue,
            showNowReading = showNowReading,
            wordMode = wordMode,
            onSetShowWord = onSetShowWord,
            onSetMuted = onSetMuted,
            onSetWpm = onSetWpm,
            onSkipWords = onSkipWords,
            onJumpToWord = onJumpToWord,
            statusLine = statusLine,
        )
    }
}

/** One line of status above the seek bar (e.g. an ebook's offline audio); tapping it opens [onClick]. */
@Immutable
data class PlayerStatusLine(
    val text: String,
    val detail: String = "",
    val busy: Boolean = false,
    val onClick: () -> Unit = {},
)

@Composable
private fun StatusLineRow(
    line: PlayerStatusLine,
    colors: PlayerColors,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(end = 4.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = line.onClick)
                .padding(vertical = 6.dp),
    ) {
        if (line.busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = colors.accent,
                trackColor = colors.track,
            )
        } else {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = colors.accent, modifier = Modifier.size(16.dp))
        }
        Text(line.text, style = MaterialTheme.typography.labelMedium, color = colors.accent, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (line.detail.isNotEmpty()) {
            Text("· ${line.detail}", style = MaterialTheme.typography.labelMedium, color = colors.muted, maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = colors.muted, modifier = Modifier.size(18.dp))
    }
}

/**
 * Speed reading inside the player: the big word, and the muted (timer driven) reader's position.
 * While [muted], the word follows the timer at [wpm] instead of the voice.
 */
@Immutable
data class WordModeState(
    val showWord: Boolean = false,
    val muted: Boolean = false,
    val word: String = "",
    val wpm: Int = RSVP_DEFAULT_WPM,
    val wordIndex: Int = 0,
    val wordCount: Int = 0,
    val millisLeft: Long = 0,
    val silentPlaying: Boolean = false,
)

/** Solid colors of the player panel: dark in both light and dark themes. */
@Immutable
private data class PlayerColors(
    val container: Color,
    val content: Color,
    val muted: Color,
    val button: Color,
    val track: Color,
    val accent: Color,
    val onAccent: Color,
)

@Composable
private fun playerColors(): PlayerColors {
    val scheme = MaterialTheme.colorScheme
    val darkTheme = scheme.surface.luminance() < 0.5f
    val container = if (darkTheme) scheme.surfaceContainerHighest else scheme.inverseSurface
    val content = if (darkTheme) scheme.onSurface else scheme.inverseOnSurface
    return PlayerColors(
        container = container,
        content = content,
        muted = content.copy(alpha = 0.72f).compositeOver(container),
        button = content.copy(alpha = 0.12f).compositeOver(container),
        track = content.copy(alpha = 0.22f).compositeOver(container),
        accent = if (darkTheme) scheme.primary else scheme.inversePrimary,
        onAccent = if (darkTheme) scheme.onPrimary else scheme.onPrimaryContainer,
    )
}

@Composable
fun CompactTTSPlayer(
    currentlyPlaying: Boolean,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onSkipNext: () -> Unit,
    languages: ImmutableHolder<List<Locale>>,
    onSelectLanguage: (LocaleOverride) -> Unit,
    progress: ReadAloudProgress,
    modifier: Modifier = Modifier,
    onSeekTo: (Long) -> Unit = {},
    onGoToSpokenText: () -> Unit = {},
    displayedArticleId: Long? = null,
    onSelectSpeed: (Float) -> Unit = {},
    diagnostics: () -> String = { "" },
    neighbors: ReadAloudNeighbors = ReadAloudNeighbors(),
    onSkipArticle: (next: Boolean) -> Unit = {},
    autoContinue: Boolean = false,
    onSetAutoContinue: (Boolean) -> Unit = {},
    showNowReading: Boolean = false,
    wordMode: WordModeState = WordModeState(),
    onSetShowWord: ((Boolean) -> Unit)? = null,
    onSetMuted: ((Boolean) -> Unit)? = null,
    onSetWpm: (Int) -> Unit = {},
    onSkipWords: (Int) -> Unit = {},
    onJumpToWord: (Int) -> Unit = {},
    statusLine: PlayerStatusLine? = null,
) {
    var diagnosticsText by remember { mutableStateOf<String?>(null) }
    diagnosticsText?.let { text ->
        AlertDialog(
            onDismissRequest = { diagnosticsText = null },
            confirmButton = {
                TextButton(onClick = { diagnosticsText = diagnostics() }) { Text(stringResource(R.string.read_aloud_diagnostics_refresh)) }
            },
            dismissButton = {
                TextButton(onClick = { diagnosticsText = null }) { Text(stringResource(android.R.string.ok)) }
            },
            title = { Text(stringResource(R.string.read_aloud_diagnostics)) },
            text = {
                SelectionContainer(Modifier.verticalScroll(rememberScrollState())) {
                    Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            },
        )
    }
    val colors = playerColors()
    val muted = wordMode.muted
    // Muted, the silent speed reader plays instead of the voice
    val playing = if (muted) wordMode.silentPlaying else currentlyPlaying
    val preparing = !muted && progress.preparationProgress != null
    val seekable = if (muted) wordMode.wordCount > 1 else !preparing && progress.durationMillis > 0 && progress.error == null
    if (muted && playing) {
        // Keep the screen on while words flow, and pause when the app goes to the background
        val view = LocalView.current
        DisposableEffect(view) {
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = false }
        }
        val lifecycleOwner = LocalLifecycleOwner.current
        val currentOnPause by rememberUpdatedState(onPause)
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) currentOnPause() }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }
    // Swipe down (or tap the handle) for a one-line mini player, so more of the article shows
    var minimized by rememberSaveable { mutableStateOf(false) }
    Surface(
        modifier =
            modifier.fillMaxWidth().pointerInput(Unit) {
                var dragged = 0f
                detectVerticalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = {
                        if (dragged > MINIMIZE_DRAG_PX) minimized = true
                        if (dragged < -MINIMIZE_DRAG_PX) minimized = false
                    },
                ) { change, amount ->
                    change.consume()
                    dragged += amount
                }
            },
        color = colors.container,
        contentColor = colors.content,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                    .padding(start = 16.dp, end = 12.dp, bottom = 4.dp),
        ) {
            DragHandle(colors = colors, minimized = minimized, onClick = { minimized = !minimized })
            if (minimized) {
                MiniPlayerRow(
                    colors = colors,
                    playing = playing,
                    preparing = preparing,
                    title = neighbors.currentTitle,
                    fraction =
                        if (muted) {
                            (wordMode.wordIndex.toFloat() / (wordMode.wordCount - 1).coerceAtLeast(1)).coerceIn(0f, 1f)
                        } else {
                            (progress.positionMillis.toFloat() / progress.durationMillis.coerceAtLeast(1)).coerceIn(0f, 1f)
                        },
                    hasNext = neighbors.nextId != null,
                    onPlay = onPlay,
                    onPause = onPause,
                    onNext = { onSkipArticle(true) },
                    onClose = onStop,
                )
            } else {
                // Landscape on a phone: one row, so the article keeps most of the screen
                val config = LocalConfiguration.current
                val oneRow =
                    config.screenHeightDp < ONE_ROW_MAX_HEIGHT_DP &&
                        config.screenWidthDp >= ONE_ROW_MIN_WIDTH_DP &&
                        !wordMode.showWord
                val seekArea: @Composable () -> Unit = {
                    if (muted) {
                        var scrubIndex by remember { mutableStateOf<Int?>(null) }
                        val last = (wordMode.wordCount - 1).coerceAtLeast(1)
                        val shownIndex = scrubIndex ?: wordMode.wordIndex
                        WavySeekBar(
                            fraction = (shownIndex.toFloat() / last).coerceIn(0f, 1f),
                            playing = playing,
                            enabled = seekable,
                            colors = colors,
                            onScrub = { scrubIndex = (it * last).roundToInt() },
                            onScrubFinished = {
                                scrubIndex?.let(onJumpToWord)
                                scrubIndex = null
                            },
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                stringResource(R.string.read_aloud_word_number, shownIndex + 1),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.muted,
                            )
                            Text(
                                stringResource(R.string.read_aloud_word_count, wordMode.wordCount),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.muted,
                            )
                        }
                    } else if (preparing) {
                        val fraction = progress.preparationProgress ?: 0f
                        Box(Modifier.height(24.dp), contentAlignment = Alignment.Center) {
                            if (fraction > 0f) {
                                LinearProgressIndicator(
                                    progress = { fraction },
                                    color = colors.accent,
                                    trackColor = colors.track,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                LinearProgressIndicator(color = colors.accent, trackColor = colors.track, modifier = Modifier.fillMaxWidth())
                            }
                        }
                        Text(
                            stringResource(R.string.read_aloud_preparing),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.muted,
                        )
                    } else {
                        var scrubMillis by remember(progress.articleId) { mutableStateOf<Long?>(null) }
                        val duration = progress.durationMillis.coerceAtLeast(1)
                        // While the length is still estimated, only the audio made so far can be reached
                        val seekableMillis = if (progress.isDurationEstimated) progress.generatedMillis else progress.durationMillis
                        val shownMillis = scrubMillis ?: progress.positionMillis
                        WavySeekBar(
                            fraction = (shownMillis.toFloat() / duration).coerceIn(0f, 1f),
                            playing = playing,
                            enabled = seekable,
                            colors = colors,
                            onScrub = { scrubMillis = (it * duration).toLong().coerceIn(0, seekableMillis) },
                            onScrubFinished = {
                                scrubMillis?.let(onSeekTo)
                                scrubMillis = null
                            },
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(formatSpeechTime(shownMillis), style = MaterialTheme.typography.labelSmall, color = colors.muted)
                            Text(
                                if (progress.isDurationEstimated) {
                                    stringResource(
                                        R.string.read_aloud_estimated_time,
                                        (progress.synthesisProgress * 100).toInt().coerceIn(0, 99),
                                        formatSpeechTime(progress.durationMillis),
                                    )
                                } else {
                                    formatSpeechTime(progress.durationMillis)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.muted,
                            )
                        }
                    }
                    progress.error?.let {
                        Text(
                            it,
                            // The panel is always dark, so a light error red
                            color = Color(0xFFFFB4AB),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                val controls: @Composable () -> Unit = {
                    ArticleSkipButton(next = false, enabled = neighbors.previousId != null, colors = colors, onClick = { onSkipArticle(false) })
                    PlainTooltipBox(tooltip = { Text(stringResource(R.string.seek_back_10_seconds)) }) {
                        IconButton(
                            onClick = {
                                if (muted) onSkipWords(-SKIP_WORDS) else onSeekTo((progress.positionMillis - SEEK_STEP_MILLIS).coerceAtLeast(0))
                            },
                            enabled = seekable,
                            colors = IconButtonDefaults.iconButtonColors(contentColor = colors.content, disabledContentColor = colors.track),
                        ) {
                            Icon(Icons.Default.Replay10, contentDescription = stringResource(R.string.seek_back_10_seconds))
                        }
                    }
                    PlayButton(
                        currentlyPlaying = playing,
                        preparing = preparing,
                        colors = colors,
                        onPlay = onPlay,
                        onPause = onPause,
                        onLongClick = { diagnosticsText = diagnostics() },
                    )
                    PlainTooltipBox(tooltip = { Text(stringResource(R.string.seek_forward_10_seconds)) }) {
                        IconButton(
                            onClick = { if (muted) onSkipWords(SKIP_WORDS) else onSeekTo(progress.positionMillis + SEEK_STEP_MILLIS) },
                            enabled = seekable,
                            colors = IconButtonDefaults.iconButtonColors(contentColor = colors.content, disabledContentColor = colors.track),
                        ) {
                            Icon(Icons.Default.Forward10, contentDescription = stringResource(R.string.seek_forward_10_seconds))
                        }
                    }
                    ArticleSkipButton(next = true, enabled = neighbors.nextId != null, colors = colors, onClick = { onSkipArticle(true) })
                }
                if (oneRow) {
                    if (statusLine != null && !muted) StatusLineRow(statusLine, colors)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth().height(68.dp),
                    ) {
                        controls()
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) { seekArea() }
                        Spacer(Modifier.width(8.dp))
                        TopActions(
                            colors = colors,
                            progress = progress,
                            displayedArticleId = displayedArticleId,
                            onSelectSpeed = onSelectSpeed,
                            onSkipNext = onSkipNext,
                            onGoToSpokenText = onGoToSpokenText,
                            languages = languages,
                            onSelectLanguage = onSelectLanguage,
                            autoContinue = autoContinue,
                            onSetAutoContinue = onSetAutoContinue,
                            onClose = onStop,
                            wordMode = wordMode,
                            onSetShowWord = onSetShowWord,
                            onSetMuted = onSetMuted,
                            onSetWpm = onSetWpm,
                        )
                    }
                } else {
                    TopLine(
                        colors = colors,
                        neighbors = neighbors,
                        showNowReading = showNowReading,
                        progress = progress,
                        displayedArticleId = displayedArticleId,
                        onSelectSpeed = onSelectSpeed,
                        onSkipNext = onSkipNext,
                        onGoToSpokenText = onGoToSpokenText,
                        languages = languages,
                        onSelectLanguage = onSelectLanguage,
                        autoContinue = autoContinue,
                        onSetAutoContinue = onSetAutoContinue,
                        onClose = onStop,
                        wordMode = wordMode,
                        onSetShowWord = onSetShowWord,
                        onSetMuted = onSetMuted,
                        onSetWpm = onSetWpm,
                    )
                    if (statusLine != null && !muted) StatusLineRow(statusLine, colors)
                    if (wordMode.showWord && onSetShowWord != null) {
                        RsvpWordDisplay(
                            word = wordMode.word,
                            textColor = colors.content,
                            guideColor = colors.muted,
                            focusColor = Color(0xFFFF8A80),
                            modifier = Modifier.padding(end = 4.dp, bottom = 4.dp),
                        )
                    }
                    Column(Modifier.padding(end = 4.dp)) {
                        seekArea()
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(76.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            controls()
                        }
                    }
                }
            }
        }
    }
}

/** The small bar on top of the panel; tap it to shrink or grow the player (or swipe the panel). */
@Composable
private fun DragHandle(
    colors: PlayerColors,
    minimized: Boolean,
    onClick: () -> Unit,
) {
    val label = stringResource(if (minimized) R.string.read_aloud_expand_player else R.string.read_aloud_minimize_player)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .fillMaxWidth()
                .height(16.dp)
                .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
                .safeSemantics { contentDescription = label },
    ) {
        Box(
            Modifier
                .size(width = 32.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colors.muted),
        )
    }
}

/** Minimized player: play, what is being read with a thin progress line, next, close. */
@Composable
private fun MiniPlayerRow(
    colors: PlayerColors,
    playing: Boolean,
    preparing: Boolean,
    title: String,
    fraction: Float,
    hasNext: Boolean,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    val label = stringResource(if (playing) R.string.pause_reading else R.string.resume_reading)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(colors.accent)
                    .clickable(onClickLabel = label, role = Role.Button, onClick = if (playing) onPause else onPlay),
        ) {
            if (preparing) CircularProgressIndicator(color = colors.onAccent, strokeWidth = 2.dp, modifier = Modifier.size(38.dp))
            Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = label, tint = colors.onAccent)
        }
        Column(Modifier.weight(1f)) {
            Text(
                title.ifBlank { stringResource(R.string.read_aloud_now_reading) },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { fraction },
                color = colors.accent,
                trackColor = colors.track,
                drawStopIndicator = {},
                modifier = Modifier.fillMaxWidth().height(3.dp),
            )
        }
        ArticleSkipButton(next = true, enabled = hasNext, colors = colors, onClick = onNext)
        SmallRoundButton(colors = colors, label = stringResource(R.string.read_aloud_close), onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * Title line: what comes next (or what is being read), the speed, a menu with the less used
 * actions, and close.
 */
@Composable
private fun TopLine(
    colors: PlayerColors,
    neighbors: ReadAloudNeighbors,
    showNowReading: Boolean,
    progress: ReadAloudProgress,
    displayedArticleId: Long?,
    onSelectSpeed: (Float) -> Unit,
    onSkipNext: () -> Unit,
    onGoToSpokenText: () -> Unit,
    languages: ImmutableHolder<List<Locale>>,
    onSelectLanguage: (LocaleOverride) -> Unit,
    autoContinue: Boolean,
    onSetAutoContinue: (Boolean) -> Unit,
    onClose: () -> Unit,
    wordMode: WordModeState,
    onSetShowWord: ((Boolean) -> Unit)?,
    onSetMuted: ((Boolean) -> Unit)?,
    onSetWpm: (Int) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .height(48.dp),
    ) {
        val nowReading = (showNowReading && neighbors.currentTitle.isNotBlank()) || neighbors.nextId == null
        val left = wordMode.millisLeft
        val (label, title) =
            when {
                // Room is short with the speed stepper, so muted shows only the time left
                wordMode.muted ->
                    "" to
                        if (left < 60_000) {
                            stringResource(R.string.rsvp_seconds_left, (left / 1000).toInt().coerceAtLeast(1))
                        } else {
                            stringResource(R.string.rsvp_minutes_left, (left / 60_000.0).roundToInt())
                        }
                nowReading && neighbors.currentTitle.isNotBlank() -> stringResource(R.string.read_aloud_now_reading) to neighbors.currentTitle
                nowReading -> stringResource(R.string.read_aloud_up_next) to stringResource(R.string.read_aloud_end_of_list)
                else -> stringResource(R.string.read_aloud_up_next) to neighbors.nextTitle
            }
        if (label.isNotEmpty()) {
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = colors.accent,
                maxLines = 1,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        TopActions(
            colors = colors,
            progress = progress,
            displayedArticleId = displayedArticleId,
            onSelectSpeed = onSelectSpeed,
            onSkipNext = onSkipNext,
            onGoToSpokenText = onGoToSpokenText,
            languages = languages,
            onSelectLanguage = onSelectLanguage,
            autoContinue = autoContinue,
            onSetAutoContinue = onSetAutoContinue,
            onClose = onClose,
            wordMode = wordMode,
            onSetShowWord = onSetShowWord,
            onSetMuted = onSetMuted,
            onSetWpm = onSetWpm,
        )
    }
}

/** Speed (or words per minute when muted), mute, the menu with the less used actions, and close. */
@Composable
private fun TopActions(
    colors: PlayerColors,
    progress: ReadAloudProgress,
    displayedArticleId: Long?,
    onSelectSpeed: (Float) -> Unit,
    onSkipNext: () -> Unit,
    onGoToSpokenText: () -> Unit,
    languages: ImmutableHolder<List<Locale>>,
    onSelectLanguage: (LocaleOverride) -> Unit,
    autoContinue: Boolean,
    onSetAutoContinue: (Boolean) -> Unit,
    onClose: () -> Unit,
    wordMode: WordModeState,
    onSetShowWord: ((Boolean) -> Unit)?,
    onSetMuted: ((Boolean) -> Unit)?,
    onSetWpm: (Int) -> Unit,
) {
    if (wordMode.muted) {
        WpmStepper(wpm = wordMode.wpm, onSetWpm = onSetWpm, colors = colors)
    } else {
        SpeedPill(speed = progress.speed, onSelectSpeed = onSelectSpeed, colors = colors)
    }
    if (onSetMuted != null) {
        Spacer(Modifier.width(6.dp))
        val label = stringResource(if (wordMode.muted) R.string.read_aloud_unmute else R.string.read_aloud_mute)
        SmallRoundButton(colors = colors, label = label, onClick = { onSetMuted(!wordMode.muted) }) {
            Icon(
                if (wordMode.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        }
    }
    Spacer(Modifier.width(6.dp))
    MoreMenu(
        colors = colors,
        progress = progress,
        displayedArticleId = displayedArticleId,
        onSkipNext = onSkipNext,
        onGoToSpokenText = onGoToSpokenText,
        languages = languages,
        onSelectLanguage = onSelectLanguage,
        autoContinue = autoContinue,
        onSetAutoContinue = onSetAutoContinue,
        showWord = wordMode.showWord,
        onSetShowWord = onSetShowWord,
    )
    Spacer(Modifier.width(6.dp))
    SmallRoundButton(
        colors = colors,
        label = stringResource(R.string.read_aloud_close),
        onClick = onClose,
    ) {
        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun SmallRoundButton(
    colors: PlayerColors,
    label: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    PlainTooltipBox(tooltip = { Text(label) }) {
        IconButton(
            onClick = onClick,
            colors = IconButtonDefaults.iconButtonColors(containerColor = colors.button, contentColor = colors.content),
            modifier =
                Modifier
                    .size(36.dp)
                    .safeSemantics { contentDescription = label },
        ) {
            content()
        }
    }
}

/** Words per minute while muted: − and + around the value, in one pill. */
@Composable
private fun WpmStepper(
    wpm: Int,
    onSetWpm: (Int) -> Unit,
    colors: PlayerColors,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .height(36.dp)
                .background(colors.button, CircleShape),
    ) {
        IconButton(
            onClick = { onSetWpm(wpm - RSVP_WPM_STEP) },
            enabled = wpm > RSVP_MIN_WPM,
            colors = IconButtonDefaults.iconButtonColors(contentColor = colors.content, disabledContentColor = colors.track),
            modifier = Modifier.size(36.dp),
        ) {
            Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.rsvp_slower), modifier = Modifier.size(18.dp))
        }
        val wpmLabel = stringResource(R.string.rsvp_words_per_minute)
        Text(
            stringResource(R.string.rsvp_wpm, wpm),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            modifier = Modifier.safeSemantics { contentDescription = "$wpmLabel $wpm" },
        )
        IconButton(
            onClick = { onSetWpm(wpm + RSVP_WPM_STEP) },
            enabled = wpm < RSVP_MAX_WPM,
            colors = IconButtonDefaults.iconButtonColors(contentColor = colors.content, disabledContentColor = colors.track),
            modifier = Modifier.size(36.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.rsvp_faster), modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SpeedPill(
    speed: Float,
    onSelectSpeed: (Float) -> Unit,
    colors: PlayerColors,
) {
    var showMenu by remember { mutableStateOf(false) }
    Box {
        val speedLabel = stringResource(R.string.read_aloud_speed)
        PlainTooltipBox(tooltip = { Text(speedLabel) }) {
            Button(
                onClick = { showMenu = true },
                colors = ButtonDefaults.buttonColors(containerColor = colors.button, contentColor = colors.content),
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier =
                    Modifier
                        .height(36.dp)
                        .safeSemantics { contentDescription = "$speedLabel ${formatSpeed(speed)}" },
            ) {
                Text(formatSpeed(speed), style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            modifier = Modifier.onKeyEventLikeEscape { showMenu = false },
        ) {
            for (option in READ_ALOUD_SPEEDS) {
                DropdownMenuItem(
                    onClick = {
                        onSelectSpeed(option)
                        showMenu = false
                    },
                    text = { Text(formatSpeed(option)) },
                    trailingIcon = {
                        if (option == speed) Icon(Icons.Default.Check, contentDescription = null)
                    },
                )
            }
        }
    }
}

/** Auto-play next, go to spoken text, skip ahead and the voice language. */
@Composable
private fun MoreMenu(
    colors: PlayerColors,
    progress: ReadAloudProgress,
    displayedArticleId: Long?,
    onSkipNext: () -> Unit,
    onGoToSpokenText: () -> Unit,
    languages: ImmutableHolder<List<Locale>>,
    onSelectLanguage: (LocaleOverride) -> Unit,
    autoContinue: Boolean,
    onSetAutoContinue: (Boolean) -> Unit,
    showWord: Boolean,
    onSetShowWord: ((Boolean) -> Unit)?,
) {
    var showMenu by remember { mutableStateOf(false) }
    var showVoices by remember { mutableStateOf(false) }
    val close = {
        showMenu = false
        showVoices = false
    }
    Box {
        SmallRoundButton(
            colors = colors,
            label = stringResource(R.string.read_aloud_more),
            onClick = { showMenu = true },
        ) {
            Icon(Icons.Default.MoreVert, contentDescription = null, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = close,
            modifier = Modifier.onKeyEventLikeEscape { close() },
        ) {
            if (!showVoices) {
                if (onSetShowWord != null) {
                    DropdownMenuItem(
                        onClick = { onSetShowWord(!showWord) },
                        leadingIcon = { Icon(Icons.Default.Speed, contentDescription = null) },
                        text = { Text(stringResource(R.string.read_aloud_show_word)) },
                        trailingIcon = {
                            Switch(checked = showWord, onCheckedChange = null, modifier = Modifier.padding(start = 12.dp))
                        },
                    )
                }
                DropdownMenuItem(
                    onClick = { onSetAutoContinue(!autoContinue) },
                    leadingIcon = { Icon(Icons.Default.Repeat, contentDescription = null) },
                    text = { Text(stringResource(R.string.read_aloud_auto_play_next)) },
                    trailingIcon = {
                        Switch(checked = autoContinue, onCheckedChange = null, modifier = Modifier.padding(start = 12.dp))
                    },
                )
                val followingHere = progress.followSpokenText && progress.articleId != null && progress.articleId == displayedArticleId
                DropdownMenuItem(
                    onClick = {
                        onGoToSpokenText()
                        close()
                    },
                    enabled = progress.articleId != null,
                    leadingIcon = {
                        Icon(
                            Icons.Default.MyLocation,
                            contentDescription = null,
                            tint = if (followingHere) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    text = { Text(stringResource(R.string.go_to_spoken_text)) },
                    trailingIcon = { if (followingHere) Icon(Icons.Default.Check, contentDescription = null) },
                )
                DropdownMenuItem(
                    onClick = {
                        onSkipNext()
                        close()
                    },
                    leadingIcon = { Icon(Icons.Default.FastForward, contentDescription = null) },
                    text = { Text(stringResource(R.string.skip_to_next)) },
                )
                DropdownMenuItem(
                    onClick = { showVoices = true },
                    leadingIcon = { Icon(Icons.Outlined.RecordVoiceOver, contentDescription = null) },
                    text = { Text(stringResource(R.string.read_aloud_change_voice)) },
                )
            } else {
                DropdownMenuItem(
                    onClick = { showVoices = false },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) },
                    text = { Text(stringResource(R.string.read_aloud_change_voice)) },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    onClick = {
                        onSelectLanguage(AppSetting)
                        close()
                    },
                    text = { Text(stringResource(id = R.string.use_app_default)) },
                )
                DropdownMenuItem(
                    onClick = {
                        onSelectLanguage(ForcedAuto)
                        close()
                    },
                    text = { Text(stringResource(id = R.string.use_detect_language)) },
                )
                HorizontalDivider()
                for (lang in languages.item) {
                    DropdownMenuItem(
                        onClick = {
                            onSelectLanguage(ForcedLocale(lang))
                            close()
                        },
                        text = { Text(text = lang.getDisplayName(lang)) },
                    )
                }
            }
        }
    }
}

/** Big round button that reads the previous or next article in the list. */
@Composable
private fun ArticleSkipButton(
    next: Boolean,
    enabled: Boolean,
    colors: PlayerColors,
    onClick: () -> Unit,
) {
    val label = stringResource(if (next) R.string.next_article else R.string.previous_article)
    PlainTooltipBox(tooltip = { Text(label) }) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            colors =
                IconButtonDefaults.iconButtonColors(
                    containerColor = colors.button,
                    contentColor = colors.content,
                    disabledContainerColor = colors.button,
                    disabledContentColor = colors.track,
                ),
            modifier = Modifier.size(48.dp),
        ) {
            Icon(if (next) Icons.Default.SkipNext else Icons.Default.SkipPrevious, contentDescription = label)
        }
    }
}

/** Play/pause: a rounded square while playing that turns round when paused. */
@Composable
private fun PlayButton(
    currentlyPlaying: Boolean,
    preparing: Boolean,
    colors: PlayerColors,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onLongClick: () -> Unit,
) {
    val label = stringResource(if (currentlyPlaying) R.string.pause_reading else R.string.resume_reading)
    val corner by animateDpAsState(if (currentlyPlaying) 22.dp else 32.dp, label = "play corner")
    PlainTooltipBox(tooltip = { Text(label) }) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(corner))
                    .background(colors.accent)
                    // Long-press opens read-aloud diagnostics
                    .combinedClickable(
                        role = Role.Button,
                        onClickLabel = label,
                        onClick = if (currentlyPlaying) onPause else onPlay,
                        onLongClick = onLongClick,
                    ),
        ) {
            if (preparing) {
                CircularProgressIndicator(color = colors.onAccent, strokeWidth = 3.dp, modifier = Modifier.size(56.dp))
            }
            Icon(
                if (currentlyPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = label,
                tint = colors.onAccent,
                modifier = Modifier.size(34.dp),
            )
        }
    }
}

/**
 * Reading position as a line that waves while playing and lies flat when paused. Tap or drag to
 * move; [onScrub] gets the fraction while dragging and [onScrubFinished] seeks.
 */
@Composable
private fun WavySeekBar(
    fraction: Float,
    playing: Boolean,
    enabled: Boolean,
    colors: PlayerColors,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
) {
    // Paper (e-ink): a flat line that never animates
    val paper = LocalPaperMode.current
    val amplitude by animateFloatAsState(if (playing && !paper) 1f else 0f, animationSpec = if (paper) snap() else tween(400), label = "wave amplitude")
    val phase by rememberInfiniteTransition(label = "wave").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "wave phase",
    )
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val currentOnScrub by rememberUpdatedState(onScrub)
    val currentOnScrubFinished by rememberUpdatedState(onScrubFinished)
    val positionLabel = stringResource(R.string.read_aloud_position)
    val activeColor = if (enabled) colors.accent else colors.track
    Canvas(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(24.dp)
                .safeSemantics {
                    contentDescription = positionLabel
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                    if (enabled) {
                        setProgress { target ->
                            currentOnScrub(target.coerceIn(0f, 1f))
                            currentOnScrubFinished()
                            true
                        }
                    }
                }.pointerInput(enabled, rtl) {
                    if (!enabled) return@pointerInput

                    fun toFraction(x: Float) = (x / size.width).coerceIn(0f, 1f).let { if (rtl) 1f - it else it }
                    detectTapGestures { offset ->
                        currentOnScrub(toFraction(offset.x))
                        currentOnScrubFinished()
                    }
                }.pointerInput(enabled, rtl) {
                    if (!enabled) return@pointerInput

                    fun toFraction(x: Float) = (x / size.width).coerceIn(0f, 1f).let { if (rtl) 1f - it else it }
                    detectHorizontalDragGestures(
                        onDragStart = { currentOnScrub(toFraction(it.x)) },
                        onDragEnd = { currentOnScrubFinished() },
                        onDragCancel = { currentOnScrubFinished() },
                        onHorizontalDrag = { change, _ -> currentOnScrub(toFraction(change.position.x)) },
                    )
                },
    ) {
        val stroke = 4.dp.toPx()
        val thumb = 6.dp.toPx()
        val gap = 4.dp.toPx()
        val waveHeight = 3.dp.toPx() * amplitude
        val wavelength = 24.dp.toPx()
        val centerY = size.height / 2
        val start = stroke / 2
        val end = size.width - stroke / 2
        val thumbX = start + (end - start) * fraction

        fun x(value: Float) = if (rtl) size.width - value else value

        val playedEnd = thumbX - thumb - gap
        if (playedEnd > start) {
            val path = Path()
            var px = start
            path.moveTo(x(px), centerY)
            while (px < playedEnd) {
                px = (px + 2f).coerceAtMost(playedEnd)
                val y = centerY + waveHeight * sin(2 * PI * (px / wavelength - phase)).toFloat()
                path.lineTo(x(px), y)
            }
            drawPath(path, activeColor, style = Stroke(width = stroke, cap = StrokeCap.Round))
        }
        val trackStart = thumbX + thumb + gap
        if (trackStart < end) {
            drawLine(colors.track, Offset(x(trackStart), centerY), Offset(x(end), centerY), strokeWidth = stroke, cap = StrokeCap.Round)
        }
        drawCircle(activeColor, radius = thumb, center = Offset(x(thumbX), centerY))
    }
}

private const val SEEK_STEP_MILLIS = 10_000L

// Below this screen height (a phone on its side) the player is one row
private const val ONE_ROW_MAX_HEIGHT_DP = 500

// One row needs room for 5 controls (~270dp), speed/mute/menu/close (~200dp) and a usable seek bar
private const val ONE_ROW_MIN_WIDTH_DP = 600

private const val MINIMIZE_DRAG_PX = 40f

// Muted, the ±10 buttons move ten words
private const val SKIP_WORDS = 10

@Preview
@Composable
private fun CompactPlayerPreview() {
    PreviewTheme {
        CompactTTSPlayer(
            currentlyPlaying = true,
            onPlay = {},
            onPause = {},
            onStop = {},
            onSkipNext = {},
            languages = ImmutableHolder(emptyList()),
            onSelectLanguage = {},
            progress = ReadAloudProgress(articleId = 1, positionMillis = 42_000, durationMillis = 70_000, speed = 1.5f),
            neighbors = ReadAloudNeighbors(previousId = 1, previousTitle = "Before", nextId = 3, nextTitle = "Flaming Hydra’s new Kickstarter campaign"),
        )
    }
}
