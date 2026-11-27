package com.nononsenseapps.feeder.ui.compose.audiobooks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.model.audiobooks.Audiobook
import com.nononsenseapps.feeder.model.audiobooks.AudiobookPlayer
import com.nononsenseapps.feeder.model.audiobooks.AudiobookRules
import com.nononsenseapps.feeder.model.audiobooks.AudiobookState
import com.nononsenseapps.feeder.model.audiobooks.AudiobookTranscriptRules
import com.nononsenseapps.feeder.model.audiobooks.AudiobookTranscriptState
import com.nononsenseapps.feeder.model.audiobooks.AudiobookTranscripts
import com.nononsenseapps.feeder.model.transcript.TranscriptChapter
import com.nononsenseapps.feeder.model.transcript.toPlainText
import com.nononsenseapps.feeder.ui.compose.feed.PlainTooltipBox
import com.nononsenseapps.feeder.ui.compose.theme.FeederTokens
import com.nononsenseapps.feeder.ui.compose.transcript.TranscriptView
import org.kodein.di.compose.LocalDI
import org.kodein.di.instance

/**
 * The audiobook's transcript full screen (design B): the lines under chapter headings, the
 * playing chapter open and following playback, Search and time stamps as in YouTube transcripts,
 * and the controls in a dock at the bottom. Opening it turns nothing on by itself; "Transcribe
 * this book" starts Whisper working ahead of the play position.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AudiobookReadAlong(
    book: Audiobook,
    cover: ImageBitmap?,
    s: AudiobookState,
    mine: Boolean,
    player: AudiobookPlayer,
    onClose: () -> Unit,
) {
    val di = LocalDI.current
    val transcripts: AudiobookTranscripts by di.instance()
    LaunchedEffect(book.id) { transcripts.load(book.id) }
    DisposableEffect(book.id) {
        transcripts.viewing(book.id)
        onDispose { transcripts.viewing(null) }
    }
    // Reading along: the screen stays on
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    BackHandler(onBack = onClose)
    val t by remember(book.id) { transcripts.state(book.id) }.collectAsState(transcripts.current(book.id))
    val chapters = if (mine) s.chapters else remember(book) { AudiobookRules.chaptersOf(book) }
    val positionMs = if (mine) s.bookMs else 0L

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            (if (mine) s.currentChapter?.title else null) ?: book.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            book.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.audiobook_transcript_close))
                    }
                },
            )
        },
        bottomBar = { ReadAlongDock(book, cover, s, mine, player) },
    ) { padding ->
        TranscriptView(
            cues = t.cues,
            positionMs = positionMs,
            onSeek = { ms -> if (mine) player.seekToBookMs(ms) else player.open(book.id, at = AudiobookRules.locate(book, ms)) },
            resetKey = book.id,
            copyText = { t.cues.toPlainText(book.title) },
            chapters = remember(chapters) { chapters.map { TranscriptChapter(it.bookMs, it.title) } },
            pendingText = stringResource(if (t.wanted && transcripts.canTranscribe) R.string.audiobook_transcript_pending else R.string.audiobook_transcript_pending_off),
            modifier = Modifier.padding(padding).fillMaxSize(),
            status = {
                TranscriptAheadStatus(
                    t = t,
                    positionMs = positionMs,
                    canTranscribe = transcripts.canTranscribe,
                    onStart = { transcripts.setWanted(book.id, true) },
                    onStop = { transcripts.setWanted(book.id, false) },
                )
            },
        )
    }
}

@Composable
private fun TranscriptAheadStatus(
    t: AudiobookTranscriptState,
    positionMs: Long,
    canTranscribe: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val ready = AudiobookRules.clock(AudiobookTranscriptRules.readyUntil(t.ranges, positionMs))
    val line = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp)
    when {
        !t.loaded -> Unit

        // Nothing yet and not on: what it is, and the one button
        t.cues.isEmpty() && !t.wanted -> {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(horizontal = FeederTokens.Space.xl, vertical = FeederTokens.Space.xl),
            ) {
                Icon(Icons.Default.Subtitles, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
                Text(
                    stringResource(R.string.audiobook_transcript_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = FeederTokens.Space.md),
                )
                if (canTranscribe) {
                    Button(onClick = onStart) { Text(stringResource(R.string.audiobook_transcript_start)) }
                    Text(
                        stringResource(R.string.audiobook_transcript_model_note, 57),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = FeederTokens.Space.sm),
                    )
                } else {
                    Text(
                        stringResource(R.string.audiobook_transcript_unsupported),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        t.modelProgress != null -> {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.audiobook_transcript_model, (t.modelProgress * 100).toInt()), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { t.modelProgress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }

        else -> {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = line) {
                if (t.workingAtMs != null) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    when {
                        t.error != null -> stringResource(R.string.audiobook_transcript_failed, t.error)
                        !t.wanted -> stringResource(R.string.audiobook_transcript_from_devices, ready)
                        t.workingAtMs != null -> stringResource(R.string.audiobook_transcript_working, ready)
                        else -> stringResource(R.string.audiobook_transcript_ready_to, ready)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (t.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
                if (canTranscribe) {
                    if (t.wanted) {
                        TextButton(onClick = onStop) { Text(stringResource(R.string.audiobook_transcript_stop)) }
                    } else {
                        TextButton(onClick = onStart) { Text(stringResource(R.string.audiobook_transcript_ahead)) }
                    }
                }
            }
        }
    }
}

/** The controls, small: cover, book and Speed, the chapter's seek bar, and play controls. */
@Composable
private fun ReadAlongDock(
    book: Audiobook,
    cover: ImageBitmap?,
    s: AudiobookState,
    mine: Boolean,
    player: AudiobookPlayer,
) {
    val playing = mine && (s.isPlaying || s.loading)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cover(cover, book.title, Modifier.size(44.dp), corner = 10.dp)
                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (book.author.isNotBlank()) {
                        Text(book.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                SpeedButton(if (mine) s.speed else 1f, enabled = mine) { player.setSpeed(it) }
            }
            val len = if (mine) s.chapterLengthMs else 0L
            val max = len.toFloat().coerceAtLeast(1f)
            var dragging by remember { mutableStateOf(false) }
            var dragValue by remember { mutableFloatStateOf(0f) }
            val shown = if (dragging) dragValue.toLong() else s.inChapterMs
            Slider(
                value = shown.toFloat().coerceIn(0f, max),
                onValueChange = {
                    dragging = true
                    dragValue = it
                },
                onValueChangeFinished = {
                    dragging = false
                    s.currentChapter?.let { c -> player.seekToBookMs(c.bookMs + dragValue.toLong()) }
                },
                valueRange = 0f..max,
                enabled = mine && len > 0,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(AudiobookRules.clock(shown), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text(if (len > 0) "−" + AudiobookRules.clock(len - shown) else "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.sm, Alignment.CenterHorizontally),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Control(Icons.Default.SkipPrevious, stringResource(R.string.audiobook_prev_chapter), mine) { player.previousChapter() }
                Round(Icons.Default.Replay10, stringResource(R.string.audiobook_back_10), mine) { player.seekBy(-10_000) }
                val label = stringResource(if (playing) R.string.audiobook_pause else R.string.audiobook_play)
                PlainTooltipBox(tooltip = { Text(label) }) {
                    FilledIconButton(
                        onClick = { if (mine) player.toggle() else player.open(book.id) },
                        shape = RoundedCornerShape(if (playing) 20.dp else 32.dp),
                        modifier = Modifier.size(width = 76.dp, height = 60.dp),
                    ) {
                        Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = label, modifier = Modifier.size(32.dp))
                    }
                }
                Round(Icons.Default.Forward30, stringResource(R.string.audiobook_ahead_30), mine) { player.seekBy(30_000) }
                Control(Icons.Default.SkipNext, stringResource(R.string.audiobook_next_chapter), mine && s.chapter < s.chapters.size - 1) { player.nextChapter() }
            }
        }
    }
}
