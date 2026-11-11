package com.nononsenseapps.feeder.ui.compose.readaloud

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.model.AppSetting
import com.nononsenseapps.feeder.model.ForcedAuto
import com.nononsenseapps.feeder.model.ForcedLocale
import com.nononsenseapps.feeder.model.LocaleOverride
import com.nononsenseapps.feeder.model.READ_ALOUD_SPEEDS
import com.nononsenseapps.feeder.model.ReadAloudProgress
import com.nononsenseapps.feeder.ui.compose.components.PaddedBottomAppBar
import com.nononsenseapps.feeder.ui.compose.components.safeSemantics
import com.nononsenseapps.feeder.ui.compose.feed.PlainTooltipBox
import com.nononsenseapps.feeder.ui.compose.theme.PreviewTheme
import com.nononsenseapps.feeder.ui.compose.utils.ImmutableHolder
import com.nononsenseapps.feeder.ui.compose.utils.onKeyEventLikeEscape
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

@Composable
fun HideableTTSPlayer(
    visibleState: MutableTransitionState<Boolean>,
    currentlyPlaying: Boolean,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onSkipNext: () -> Unit,
    languages: ImmutableHolder<List<Locale>>,
    onSelectLanguage: (LocaleOverride) -> Unit,
    modifier: Modifier = Modifier,
    floatingActionButton: @Composable (() -> Unit)? = null,
    progress: StateFlow<ReadAloudProgress>? = null,
    onSeekTo: (Long) -> Unit = {},
    onGoToSpokenText: () -> Unit = {},
    displayedArticleId: Long? = null,
    onSelectSpeed: (Float) -> Unit = {},
) {
    AnimatedVisibility(
        modifier = modifier,
        visibleState = visibleState,
        enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(256)),
        exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(256)),
    ) {
        val currentProgress = progress?.collectAsStateWithLifecycle()?.value ?: ReadAloudProgress()
        TTSPlayer(
            currentlyPlaying = currentlyPlaying,
            onPlay = onPlay,
            onPause = onPause,
            onStop = onStop,
            onSkipNext = onSkipNext,
            languages = languages,
            onSelectLanguage = onSelectLanguage,
            floatingActionButton = floatingActionButton,
            progress = currentProgress,
            onSeekTo = onSeekTo,
            onGoToSpokenText = onGoToSpokenText,
            displayedArticleId = displayedArticleId,
            onSelectSpeed = onSelectSpeed,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TTSPlayer(
    currentlyPlaying: Boolean,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onSkipNext: () -> Unit,
    languages: ImmutableHolder<List<Locale>>,
    onSelectLanguage: (LocaleOverride) -> Unit,
    modifier: Modifier = Modifier,
    floatingActionButton: @Composable (() -> Unit)? = null,
    progress: ReadAloudProgress = ReadAloudProgress(),
    onSeekTo: (Long) -> Unit = {},
    onGoToSpokenText: () -> Unit = {},
    displayedArticleId: Long? = null,
    onSelectSpeed: (Float) -> Unit = {},
) {
    var showSpeedMenu by remember { mutableStateOf(false) }
    val seekable = progress.preparationProgress == null && progress.durationMillis > 0 && progress.error == null
    val positionLabel = stringResource(R.string.read_aloud_position)
    Column(modifier = modifier) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                val preparing = progress.preparationProgress
                var scrubPosition by remember(progress.articleId, preparing != null) { mutableStateOf<Float?>(null) }
                if (preparing != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.read_aloud_preparing), style = MaterialTheme.typography.labelMedium)
                        SecondaryControls(
                            progress = progress,
                            displayedArticleId = displayedArticleId,
                            onSkipNext = onSkipNext,
                            onGoToSpokenText = onGoToSpokenText,
                            languages = languages,
                            onSelectLanguage = onSelectLanguage,
                        )
                    }
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
                } else {
                    val seekableDuration = if (progress.isDurationEstimated) progress.generatedMillis else progress.durationMillis
                    Slider(
                        value = (scrubPosition ?: progress.positionMillis.toFloat()).coerceIn(0f, progress.durationMillis.coerceAtLeast(1).toFloat()),
                        onValueChange = { scrubPosition = it.coerceAtMost(seekableDuration.toFloat()) },
                        onValueChangeFinished = {
                            scrubPosition?.let { onSeekTo(it.toLong().coerceIn(0, seekableDuration)) }
                            scrubPosition = null
                        },
                        valueRange = 0f..progress.durationMillis.coerceAtLeast(1).toFloat(),
                        enabled = progress.durationMillis > 0 && progress.error == null,
                        modifier = Modifier.fillMaxWidth().safeSemantics { contentDescription = positionLabel },
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(formatSpeechTime(scrubPosition?.toLong() ?: progress.positionMillis), style = MaterialTheme.typography.labelMedium)
                        SecondaryControls(
                            progress = progress,
                            displayedArticleId = displayedArticleId,
                            onSkipNext = onSkipNext,
                            onGoToSpokenText = onGoToSpokenText,
                            languages = languages,
                            onSelectLanguage = onSelectLanguage,
                        )
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
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                progress.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium) }
            }
        }
        PaddedBottomAppBar(
            floatingActionButton = floatingActionButton,
            actions = {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlainTooltipBox(tooltip = { Text(stringResource(R.string.stop_reading)) }) {
                        IconButton(onClick = onStop) {
                            Icon(
                                Icons.Default.Stop,
                                contentDescription = stringResource(R.string.stop_reading),
                            )
                        }
                    }
                    PlainTooltipBox(tooltip = { Text(stringResource(R.string.seek_back_10_seconds)) }) {
                        IconButton(
                            onClick = { onSeekTo((progress.positionMillis - SEEK_STEP_MILLIS).coerceAtLeast(0)) },
                            enabled = seekable,
                        ) {
                            Icon(
                                Icons.Default.Replay10,
                                contentDescription = stringResource(R.string.seek_back_10_seconds),
                            )
                        }
                    }
                    Crossfade(targetState = currentlyPlaying) { playing ->
                        val label = stringResource(if (playing) R.string.pause_reading else R.string.resume_reading)
                        PlainTooltipBox(tooltip = { Text(label) }) {
                            FilledIconButton(
                                onClick = if (playing) onPause else onPlay,
                                modifier = Modifier.size(56.dp),
                            ) {
                                Icon(
                                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = label,
                                    modifier = Modifier.size(32.dp),
                                )
                            }
                        }
                    }
                    PlainTooltipBox(tooltip = { Text(stringResource(R.string.seek_forward_10_seconds)) }) {
                        IconButton(
                            onClick = { onSeekTo(progress.positionMillis + SEEK_STEP_MILLIS) },
                            enabled = seekable,
                        ) {
                            Icon(
                                Icons.Default.Forward10,
                                contentDescription = stringResource(R.string.seek_forward_10_seconds),
                            )
                        }
                    }
                    Box {
                        val speedLabel = stringResource(R.string.read_aloud_speed)
                        PlainTooltipBox(tooltip = { Text(speedLabel) }) {
                            FilledTonalButton(
                                onClick = { showSpeedMenu = true },
                                shape = CircleShape,
                                contentPadding = PaddingValues(0.dp),
                                modifier =
                                    Modifier
                                        .size(48.dp)
                                        .safeSemantics { contentDescription = "$speedLabel ${formatSpeed(progress.speed)}" },
                            ) {
                                Text(formatSpeed(progress.speed), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                            }
                        }
                        DropdownMenu(
                            expanded = showSpeedMenu,
                            onDismissRequest = { showSpeedMenu = false },
                            modifier = Modifier.onKeyEventLikeEscape { showSpeedMenu = false },
                        ) {
                            for (speed in READ_ALOUD_SPEEDS) {
                                DropdownMenuItem(
                                    onClick = {
                                        onSelectSpeed(speed)
                                        showSpeedMenu = false
                                    },
                                    text = { Text(formatSpeed(speed)) },
                                    trailingIcon = {
                                        if (speed == progress.speed) Icon(Icons.Default.Check, contentDescription = null)
                                    },
                                )
                            }
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun SecondaryControls(
    progress: ReadAloudProgress,
    displayedArticleId: Long?,
    onSkipNext: () -> Unit,
    onGoToSpokenText: () -> Unit,
    languages: ImmutableHolder<List<Locale>>,
    onSelectLanguage: (LocaleOverride) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showMenu by remember { mutableStateOf(false) }
    val closeMenuText = stringResource(id = R.string.close_menu)
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        PlainTooltipBox(tooltip = { Text(stringResource(R.string.go_to_spoken_text)) }) {
            val followingHere = progress.followSpokenText && progress.articleId != null && progress.articleId == displayedArticleId
            IconButton(
                onClick = onGoToSpokenText,
                enabled = progress.articleId != null,
                colors =
                    if (followingHere) {
                        IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    } else {
                        IconButtonDefaults.iconButtonColors()
                    },
                modifier = Modifier.safeSemantics { selected = followingHere },
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = stringResource(R.string.go_to_spoken_text))
            }
        }
        PlainTooltipBox(tooltip = { Text(stringResource(R.string.skip_to_next)) }) {
            IconButton(onClick = onSkipNext) {
                Icon(Icons.Default.SkipNext, contentDescription = stringResource(R.string.skip_to_next))
            }
        }
        Box {
            PlainTooltipBox(tooltip = { Text(stringResource(R.string.set_language)) }) {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.Translate, contentDescription = stringResource(R.string.set_language))
                }
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                modifier = Modifier.onKeyEventLikeEscape { showMenu = false },
            ) {
                // Hidden button for TalkBack
                DropdownMenuItem(
                    onClick = { showMenu = false },
                    text = {},
                    modifier =
                        Modifier
                            .height(0.dp)
                            .safeSemantics {
                                contentDescription = closeMenuText
                                role = Role.Button
                            },
                )
                DropdownMenuItem(
                    onClick = {
                        onSelectLanguage(AppSetting)
                        showMenu = false
                    },
                    text = { Text(stringResource(id = R.string.use_app_default)) },
                )
                DropdownMenuItem(
                    onClick = {
                        onSelectLanguage(ForcedAuto)
                        showMenu = false
                    },
                    text = { Text(stringResource(id = R.string.use_detect_language)) },
                )
                HorizontalDivider()
                for (lang in languages.item) {
                    DropdownMenuItem(
                        onClick = {
                            onSelectLanguage(ForcedLocale(lang))
                            showMenu = false
                        },
                        text = { Text(text = lang.getDisplayName(lang)) },
                    )
                }
            }
        }
    }
}

private const val SEEK_STEP_MILLIS = 10_000L

/** Formats 1f as "1×" and 1.25f as "1.25×". */
internal fun formatSpeed(speed: Float): String = "%.2f".format(Locale.ROOT, speed).trimEnd('0').trimEnd('.') + "\u00D7"

internal fun formatSpeechTime(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) {
        "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    } else {
        "%d:%02d".format(seconds / 60, seconds % 60)
    }
}

@Preview
@Composable
private fun PlayerPreview() {
    PreviewTheme {
        TTSPlayer(
            currentlyPlaying = true,
            onPlay = {},
            onPause = {},
            onStop = {},
            onSkipNext = {},
            languages = ImmutableHolder(emptyList()),
            {},
        )
    }
}

@Preview
@Composable
private fun PlayerPreviewWithFab() {
    PreviewTheme {
        TTSPlayer(
            currentlyPlaying = true,
            onPlay = {},
            onPause = {},
            onStop = {},
            onSkipNext = {},
            languages = ImmutableHolder(emptyList()),
            {},
            floatingActionButton = {
                FloatingActionButton(
                    onClick = {},
                ) {
                    Icon(
                        Icons.Default.DoneAll,
                        contentDescription = stringResource(R.string.mark_all_as_read),
                    )
                }
            },
        )
    }
}
