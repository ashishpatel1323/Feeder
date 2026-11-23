package com.nononsenseapps.feeder.ui.compose.transcript

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.widget.Toast
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.IconButton
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import com.nononsenseapps.feeder.model.transcript.TranscriptMatch
import com.nononsenseapps.feeder.model.transcript.findMatches
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

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val copyLabel = stringResource(R.string.transcript)
    val copiedMessage = stringResource(R.string.transcript_copied)
    var searchOpen by rememberSaveable(itemId) { mutableStateOf(false) }
    var query by rememberSaveable(itemId) { mutableStateOf("") }
    var active by remember(itemId) { mutableIntStateOf(0) }
    val matches = remember(searchOpen, state.cues, query) { if (searchOpen) state.cues.findMatches(query) else emptyList() }
    // A new search starts at the first match from where the episode is now
    LaunchedEffect(query) {
        val now = state.cues.indexAt(positionMs)
        active = matches.indexOfFirst { it.cue >= now }.coerceAtLeast(0)
    }
    fun step(by: Int) {
        if (matches.isNotEmpty()) active = (active + by + matches.size) % matches.size
    }

    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.Top) {
            Box(modifier = Modifier.weight(1f)) {
                TranscriptStatusRow(
                    state = state,
                    canGenerate = transcripts.canGenerate,
                    modelSizeMb = transcripts.modelSizeMb,
                    onGenerate = { transcripts.generate(itemId) },
                    onStop = transcripts::stopGenerating,
                    onRedo = { transcripts.delete(itemId) },
                )
            }
            if (state.cues.isNotEmpty() && !searchOpen) {
                IconButton(onClick = { searchOpen = true }) {
                    Icon(Icons.Default.Search, contentDescription = stringResource(R.string.transcript_search))
                }
                IconButton(
                    onClick = {
                        scope.launch {
                            val text = transcripts.plainText(itemId)
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            clipboard?.setPrimaryClip(ClipData.newPlainText(copyLabel, text))
                            // Android 13 and newer confirm copies on their own
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.transcript_copy))
                }
            }
        }
        if (searchOpen) {
            TranscriptSearchBar(
                query = query,
                onQuery = { query = it },
                count = matches.size,
                active = active,
                onPrevious = { step(-1) },
                onNext = { step(1) },
                onClose = {
                    searchOpen = false
                    query = ""
                },
            )
        }
        if (state.cues.isNotEmpty()) {
            TranscriptLines(
                cues = state.cues,
                positionMs = positionMs,
                onSeek = onSeek,
                matches = matches,
                activeMatch = if (matches.isEmpty()) -1 else active,
                searching = searchOpen && query.isNotBlank(),
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

@Composable
private fun TranscriptSearchBar(
    query: String,
    onQuery: (String) -> Unit,
    count: Int,
    active: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(start = 14.dp),
    ) {
        Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onNext() }),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) {
                        Text(
                            stringResource(R.string.transcript_search),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    inner()
                }
            },
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp).focusRequester(focus),
        )
        if (query.isNotBlank()) {
            Text(
                if (count > 0) stringResource(R.string.transcript_search_count, active + 1, count) else stringResource(R.string.transcript_search_none),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onPrevious, enabled = count > 0) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.transcript_search_previous))
        }
        IconButton(onClick = onNext, enabled = count > 0) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.transcript_search_next))
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.transcript_search_close))
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
    matches: List<TranscriptMatch>,
    activeMatch: Int,
    searching: Boolean,
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
    val byCue = remember(matches) { matches.groupBy { it.cue } }
    val selected = matches.getOrNull(activeMatch)
    // While searching, the list stays on the results instead of following playback
    LaunchedEffect(selected) {
        // The panel can be short (above the player bar), so the match itself goes to the top
        if (selected != null) listState.animateScrollToItem(selected.cue)
    }
    LaunchedEffect(current, following, handScrolledAt, searching) {
        if (searching) return@LaunchedEffect
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
                    matches = byCue[index].orEmpty(),
                    selected = selected,
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
        if (!following && !searching && current >= 0) {
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
    matches: List<TranscriptMatch>,
    selected: TranscriptMatch?,
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
                markedText(cue.text, matches, selected, colors.tertiaryContainer, colors.tertiary),
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

/** The cue's text with the search matches marked; the selected one stands out. */
private fun markedText(
    text: String,
    matches: List<TranscriptMatch>,
    selected: TranscriptMatch?,
    markColor: Color,
    selectedColor: Color,
): AnnotatedString =
    buildAnnotatedString {
        append(text)
        for (m in matches) {
            addStyle(
                if (m == selected) {
                    SpanStyle(background = selectedColor, color = Color.White, fontWeight = FontWeight.SemiBold)
                } else {
                    SpanStyle(background = markColor)
                },
                m.start,
                m.end.coerceAtMost(text.length),
            )
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
