package com.nononsenseapps.feeder.ui.compose.readaloud

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.model.LocaleOverride
import com.nononsenseapps.feeder.model.ReadAloudProgress
import com.nononsenseapps.feeder.ui.compose.components.safeSemantics
import com.nononsenseapps.feeder.ui.compose.feed.PlainTooltipBox
import com.nononsenseapps.feeder.ui.compose.theme.PreviewTheme
import com.nononsenseapps.feeder.ui.compose.utils.ImmutableHolder
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * One-row read-aloud player for the article screen. It replaces the article action bar while
 * reading, so it also takes the navigation bar inset itself.
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
        )
    }
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
) {
    val seekable = progress.preparationProgress == null && progress.durationMillis > 0 && progress.error == null
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
        ) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            progress.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            // Nine controls share one row, so they use 40 dp touch targets instead of 48 dp.
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 40.dp) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlainTooltipBox(tooltip = { Text(stringResource(R.string.stop_reading)) }) {
                        IconButton(onClick = onStop) {
                            Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.stop_reading))
                        }
                    }
                    PlainTooltipBox(tooltip = { Text(stringResource(R.string.seek_back_10_seconds)) }) {
                        IconButton(
                            onClick = { onSeekTo((progress.positionMillis - SEEK_STEP_MILLIS).coerceAtLeast(0)) },
                            enabled = seekable,
                        ) {
                            Icon(Icons.Default.Replay10, contentDescription = stringResource(R.string.seek_back_10_seconds))
                        }
                    }
                    PlayButtonWithProgress(
                        currentlyPlaying = currentlyPlaying,
                        progress = progress,
                        onPlay = onPlay,
                        onPause = onPause,
                    )
                    PlainTooltipBox(tooltip = { Text(stringResource(R.string.seek_forward_10_seconds)) }) {
                        IconButton(
                            onClick = { onSeekTo(progress.positionMillis + SEEK_STEP_MILLIS) },
                            enabled = seekable,
                        ) {
                            Icon(Icons.Default.Forward10, contentDescription = stringResource(R.string.seek_forward_10_seconds))
                        }
                    }
                    SpeedButton(speed = progress.speed, onSelectSpeed = onSelectSpeed, size = 40.dp)
                    SecondaryControls(
                        progress = progress,
                        displayedArticleId = displayedArticleId,
                        onSkipNext = onSkipNext,
                        onGoToSpokenText = onGoToSpokenText,
                        languages = languages,
                        onSelectLanguage = onSelectLanguage,
                        // The article action Translate now sits in the top bar, so the voice language gets its own icon
                        languageIcon = Icons.Outlined.RecordVoiceOver,
                    )
                }
            }
        }
    }
}

/** Play/pause with the reading position drawn as a ring around it, in place of the slider. */
@Composable
private fun PlayButtonWithProgress(
    currentlyPlaying: Boolean,
    progress: ReadAloudProgress,
    onPlay: () -> Unit,
    onPause: () -> Unit,
) {
    val preparing = progress.preparationProgress != null
    val fraction =
        if (progress.durationMillis > 0) {
            (progress.positionMillis.toFloat() / progress.durationMillis).coerceIn(0f, 1f)
        } else {
            0f
        }
    val label = stringResource(if (currentlyPlaying) R.string.pause_reading else R.string.resume_reading)
    val position =
        if (preparing) {
            stringResource(R.string.read_aloud_preparing)
        } else {
            "${formatSpeechTime(progress.positionMillis)} / ${formatSpeechTime(progress.durationMillis)}"
        }
    PlainTooltipBox(tooltip = { Text("$label · $position") }) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(52.dp)
                    .safeSemantics {
                        stateDescription = position
                        if (!preparing) progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                    },
        ) {
            if (preparing) {
                CircularProgressIndicator(modifier = Modifier.size(52.dp), strokeWidth = 3.dp)
            } else {
                CircularProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.size(52.dp),
                    strokeWidth = 3.dp,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    gapSize = 0.dp,
                )
            }
            FilledTonalIconButton(
                onClick = if (currentlyPlaying) onPause else onPlay,
                modifier = Modifier.size(42.dp),
            ) {
                Icon(
                    if (currentlyPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = label,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

private const val SEEK_STEP_MILLIS = 10_000L

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
        )
    }
}
