package com.nononsenseapps.feeder.ui.compose.transcript

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.model.transcript.TranscriptCue
import com.nononsenseapps.feeder.model.transcript.TranscriptSource
import com.nononsenseapps.feeder.model.transcript.TranscriptState
import com.nononsenseapps.feeder.model.transcript.TranscriptStatus
import com.nononsenseapps.feeder.model.transcript.Transcripts
import com.nononsenseapps.feeder.model.transcript.indexAt
import kotlinx.coroutines.launch
import org.kodein.di.compose.LocalDI
import org.kodein.di.instance

/**
 * An episode's transcript as subtitles that follow playback: the line being said is highlighted
 * and kept in view, and tapping a line jumps there. Finds the feed's transcript, or offers to make
 * one on the phone.
 */
@Composable
fun TranscriptPanel(
    itemId: Long,
    positionMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val di = LocalDI.current
    val transcripts: Transcripts by di.instance()
    LaunchedEffect(itemId) { transcripts.load(itemId) }
    val state by remember(itemId) { transcripts.state(itemId) }.collectAsState(transcripts.current(itemId))

    Column(modifier = modifier) {
        TranscriptStatusRow(
            state = state,
            canGenerate = transcripts.canGenerate,
            modelSizeMb = transcripts.modelSizeMb,
            onGenerate = { transcripts.generate(itemId) },
            onStop = transcripts::stopGenerating,
            onRedo = { transcripts.delete(itemId) },
        )
        if (state.cues.isNotEmpty()) {
            TranscriptLines(
                cues = state.cues,
                positionMs = positionMs,
                onSeek = onSeek,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

@Composable
private fun TranscriptStatusRow(
    state: TranscriptState,
    canGenerate: Boolean,
    modelSizeMb: Int,
    onGenerate: () -> Unit,
    onStop: () -> Unit,
    onRedo: () -> Unit,
) {
    val padding = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    when (state.status) {
        TranscriptStatus.UNKNOWN, TranscriptStatus.CHECKING -> {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = padding) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.transcript_looking), style = MaterialTheme.typography.bodyMedium)
            }
        }

        TranscriptStatus.NONE -> {
            Column(modifier = padding, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.ClosedCaption, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    stringResource(R.string.transcript_none_in_feed),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                if (canGenerate) {
                    Button(onClick = onGenerate) { Text(stringResource(R.string.transcript_make_on_phone)) }
                    Text(
                        stringResource(R.string.transcript_make_on_phone_note, modelSizeMb),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                } else {
                    Text(
                        stringResource(R.string.transcript_not_supported),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        TranscriptStatus.DOWNLOADING_MODEL, TranscriptStatus.DOWNLOADING_AUDIO, TranscriptStatus.TRANSCRIBING -> {
            val label =
                when (state.status) {
                    TranscriptStatus.DOWNLOADING_MODEL -> stringResource(R.string.transcript_downloading_model, (state.progress * 100).toInt())
                    TranscriptStatus.DOWNLOADING_AUDIO -> stringResource(R.string.transcript_downloading_audio, (state.progress * 100).toInt())
                    else ->
                        stringResource(
                            R.string.transcript_transcribing,
                            (state.progress * 100).toInt(),
                            formatCueTime(state.doneMs),
                        )
                }
            Column(modifier = padding) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onStop) { Text(stringResource(R.string.transcript_stop)) }
                }
                if (state.progress > 0f) {
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }

        TranscriptStatus.PAUSED -> {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = padding) {
                Text(
                    stringResource(R.string.transcript_paused, formatCueTime(state.doneMs)),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (canGenerate) OutlinedButton(onClick = onGenerate) { Text(stringResource(R.string.transcript_continue)) }
            }
        }

        TranscriptStatus.FAILED -> {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = padding) {
                Text(
                    stringResource(R.string.transcript_failed, state.error ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                if (canGenerate) OutlinedButton(onClick = onGenerate) { Text(stringResource(R.string.transcript_try_again)) }
            }
        }

        TranscriptStatus.READY -> {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp)) {
                Text(
                    stringResource(
                        if (state.source == TranscriptSource.PHONE) R.string.transcript_source_phone else R.string.transcript_source_feed,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (state.source == TranscriptSource.PHONE) {
                    TextButton(onClick = onRedo) { Text(stringResource(R.string.transcript_redo)) }
                }
            }
        }
    }
}

@Composable
private fun TranscriptLines(
    cues: List<TranscriptCue>,
    positionMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val current = cues.indexAt(positionMs)
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    // After scrolling by hand, the list stops following playback for a few seconds
    var handScrolledAt by remember { mutableLongStateOf(0L) }
    var following by remember { mutableStateOf(true) }
    LaunchedEffect(dragged) {
        if (dragged) {
            following = false
            handScrolledAt = System.currentTimeMillis()
        }
    }
    LaunchedEffect(current, following, handScrolledAt) {
        if (!following) {
            kotlinx.coroutines.delay(FOLLOW_AGAIN_AFTER_MS)
            following = true
            return@LaunchedEffect
        }
        if (current >= 0) {
            // The spoken line sits a little below the top, with the previous one still visible
            listState.animateScrollToItem((current - 1).coerceAtLeast(0))
        }
    }

    Box(modifier = modifier) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
            itemsIndexed(cues, key = { index, cue -> "$index:${cue.startMs}" }) { index, cue ->
                val showSpeaker = cue.speaker != null && cue.speaker != cues.getOrNull(index - 1)?.speaker
                CueLine(
                    cue = cue,
                    speaker = if (showSpeaker) cue.speaker else null,
                    state =
                        when {
                            // In a long pause (music, an ad break) no line is lit
                            index == current && positionMs <= cue.endMs + PAUSE_GRACE_MS -> CueState.NOW
                            index == current -> CueState.PAST
                            index < current -> CueState.PAST
                            else -> CueState.LATER
                        },
                    onClick = { onSeek(cue.startMs) },
                )
            }
        }
        if (!following && current >= 0) {
            SmallFloatingActionButton(
                onClick = {
                    following = true
                    scope.launch { listState.animateScrollToItem((current - 1).coerceAtLeast(0)) }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = stringResource(R.string.transcript_follow))
            }
        }
    }
}

private enum class CueState { PAST, NOW, LATER }

@Composable
private fun CueLine(
    cue: TranscriptCue,
    speaker: String?,
    state: CueState,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 1.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (state == CueState.NOW) colors.primaryContainer else colors.surface.copy(alpha = 0f))
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        if (speaker != null) {
            Text(speaker, style = MaterialTheme.typography.labelMedium, color = colors.primary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                formatCueTime(cue.startMs),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp).width(44.dp),
            )
            Text(
                cue.text,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (state == CueState.NOW) FontWeight.Medium else FontWeight.Normal,
                color =
                    when (state) {
                        CueState.NOW -> colors.onPrimaryContainer
                        CueState.PAST -> colors.onSurfaceVariant
                        CueState.LATER -> colors.onSurface
                    },
            )
        }
    }
}

private const val FOLLOW_AGAIN_AFTER_MS = 5000L
private const val PAUSE_GRACE_MS = 2000L

internal fun formatCueTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
