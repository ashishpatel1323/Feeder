package com.nononsenseapps.feeder.ui.compose.rsvp

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nononsenseapps.feeder.R
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val PREFS = "speed_reading"
private const val PREF_WPM = "wpm"

// The first words after (re)starting are shown a little longer so the eye can settle
private val RAMP = listOf(1.8, 1.4, 1.15)

// Where the focus letter sits, as a fraction of the width
private const val FOCUS_POSITION = 0.4f

private fun Context.loadWpm(): Int =
    getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getInt(PREF_WPM, RSVP_DEFAULT_WPM)
        .coerceIn(RSVP_MIN_WPM, RSVP_MAX_WPM)

private fun Context.saveWpm(wpm: Int) {
    getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(PREF_WPM, wpm).apply()
}

/**
 * Speed reading: shows [text] one word at a time at a fixed spot, with a highlighted focus letter.
 * [onClose] gets the offset in [text] of the word reached (0 when finished), so reopening can continue from there.
 */
@Composable
fun RsvpReader(
    text: String,
    title: String,
    startOffset: Int,
    onClose: (offset: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val words = remember(text) { rsvpWords(text) }
    var index by rememberSaveable(text) {
        mutableIntStateOf(words.wordIndexAt(startOffset).coerceIn(0, (words.size - 1).coerceAtLeast(0)))
    }
    var playing by rememberSaveable(text) { mutableStateOf(words.isNotEmpty()) }
    var wpm by rememberSaveable { mutableIntStateOf(context.loadWpm()) }
    // Words shown since play was last pressed or the position moved, for the ramp
    var shownSincePlay by remember { mutableIntStateOf(0) }

    val word = words.getOrNull(index)
    val atEnd = index >= words.size - 1
    // Finished: hand back 0 so reopening starts from the top
    val close = { onClose(if (atEnd && !playing) 0 else word?.start ?: startOffset) }

    fun jump(to: Int) {
        shownSincePlay = 0
        index = to.coerceIn(0, (words.size - 1).coerceAtLeast(0))
    }

    fun togglePlay() {
        if (playing) {
            playing = false
        } else {
            if (atEnd) index = 0
            shownSincePlay = 0
            playing = words.isNotEmpty()
        }
    }

    fun changeWpm(value: Int) {
        val stepped =
            ((value.toFloat() / RSVP_WPM_STEP).roundToInt() * RSVP_WPM_STEP)
                .coerceIn(RSVP_MIN_WPM, RSVP_MAX_WPM)
        wpm = stepped
        context.saveWpm(stepped)
    }

    LaunchedEffect(playing, index, wpm) {
        if (!playing || word == null) return@LaunchedEffect
        val ramp = RAMP.getOrElse(shownSincePlay) { 1.0 }
        delay((word.delayMillis(wpm) * ramp).toLong())
        shownSincePlay++
        if (atEnd) playing = false else index++
    }

    // Keep the screen on while words are flowing
    val view = LocalView.current
    DisposableEffect(view, playing) {
        view.keepScreenOn = playing
        onDispose { view.keepScreenOn = false }
    }

    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                ) {
                    IconButton(onClick = close) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.rsvp_close))
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (words.isNotEmpty()) {
                        val left = words.millisLeft(index, wpm)
                        Text(
                            text =
                                if (left < 60_000) {
                                    stringResource(R.string.rsvp_seconds_left, (left / 1000).toInt().coerceAtLeast(1))
                                } else {
                                    stringResource(R.string.rsvp_minutes_left, (left / 60_000.0).roundToInt())
                                },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
                HorizontalDivider()

                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClickLabel = stringResource(if (playing) R.string.rsvp_pause else R.string.rsvp_play),
                                onClick = ::togglePlay,
                            ).padding(16.dp),
                ) {
                    if (word == null) {
                        Text(
                            text = stringResource(R.string.rsvp_no_text),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Column(modifier = Modifier.widthIn(max = 640.dp)) {
                                FocusGuide(top = true)
                                FocusWord(word = word.text)
                                FocusGuide(top = false)
                            }
                            if (!playing) {
                                SentenceContext(words = words, index = index)
                                Text(
                                    text = stringResource(if (atEnd) R.string.rsvp_finished else R.string.rsvp_paused),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 12.dp),
                                )
                            }
                        }
                    }
                }

                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        val positionLabel = stringResource(R.string.rsvp_position)
                        Slider(
                            value = index.toFloat(),
                            onValueChange = { jump(it.roundToInt()) },
                            valueRange = 0f..(words.size - 1).coerceAtLeast(1).toFloat(),
                            enabled = words.size > 1,
                            modifier = Modifier.semantics { contentDescription = positionLabel },
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            IconButton(onClick = { jump(words.sentenceStartBefore(index)) }) {
                                Icon(Icons.Default.Replay, contentDescription = stringResource(R.string.rsvp_back_sentence))
                            }
                            IconButton(onClick = { jump(index - 1) }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                    contentDescription = stringResource(R.string.rsvp_back_word),
                                )
                            }
                            FilledIconButton(onClick = ::togglePlay, modifier = Modifier.size(56.dp)) {
                                Icon(
                                    when {
                                        playing -> Icons.Default.Pause
                                        atEnd -> Icons.Default.Replay
                                        else -> Icons.Default.PlayArrow
                                    },
                                    contentDescription = stringResource(if (playing) R.string.rsvp_pause else R.string.rsvp_play),
                                    modifier = Modifier.size(32.dp),
                                )
                            }
                            IconButton(onClick = { jump(index + 1) }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = stringResource(R.string.rsvp_forward_word),
                                )
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.widthIn(max = 480.dp),
                        ) {
                            OutlinedIconButton(onClick = { changeWpm(wpm - RSVP_WPM_STEP) }) {
                                Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.rsvp_slower))
                            }
                            val wpmLabel = stringResource(R.string.rsvp_words_per_minute)
                            Slider(
                                value = wpm.toFloat(),
                                onValueChange = { changeWpm(it.roundToInt()) },
                                valueRange = RSVP_MIN_WPM.toFloat()..RSVP_MAX_WPM.toFloat(),
                                modifier = Modifier.weight(1f).semantics { contentDescription = wpmLabel },
                            )
                            OutlinedIconButton(onClick = { changeWpm(wpm + RSVP_WPM_STEP) }) {
                                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.rsvp_faster))
                            }
                            Text(
                                text = stringResource(R.string.rsvp_wpm, wpm),
                                style = MaterialTheme.typography.labelLarge,
                                textAlign = TextAlign.End,
                                modifier = Modifier.width(72.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A thin line with a notch above or below where the focus letter sits. */
@Composable
private fun FocusGuide(
    top: Boolean,
    modifier: Modifier = Modifier,
) {
    val color = MaterialTheme.colorScheme.outlineVariant
    val notch = MaterialTheme.colorScheme.onSurfaceVariant
    Layout(
        modifier = modifier.fillMaxWidth().height(14.dp),
        content = {
            Box(Modifier.fillMaxWidth().height(2.dp).background(color))
            Box(Modifier.width(2.dp).height(10.dp).background(notch))
        },
    ) { measurables, constraints ->
        val line = measurables[0].measure(constraints.copy(minHeight = 0))
        val mark = measurables[1].measure(constraints.copy(minWidth = 0, minHeight = 0))
        val height = constraints.maxHeight.takeIf { it != Int.MAX_VALUE } ?: (line.height + mark.height)
        layout(constraints.maxWidth, height) {
            val x = (constraints.maxWidth * FOCUS_POSITION).roundToInt() - mark.width / 2
            if (top) {
                line.place(0, 0)
                mark.place(x, 0)
            } else {
                line.place(0, height - line.height)
                mark.place(x, height - mark.height)
            }
        }
    }
}

/** The word, laid out so the centre of its focus letter is always at the same spot. */
@Composable
private fun FocusWord(
    word: String,
    modifier: Modifier = Modifier,
) {
    val focus = focusIndex(word)
    val length = focusLength(word, focus)
    val style = MaterialTheme.typography.displaySmall.copy(fontSize = 40.sp, fontWeight = FontWeight.Medium)
    val pivotColor = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFFFF8A80) else Color(0xFFD1342F)
    Layout(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        content = {
            Text(word.substring(0, focus), style = style, maxLines = 1, softWrap = false)
            Text(
                word.substring(focus, focus + length),
                style = style.copy(fontWeight = FontWeight.Bold),
                color = pivotColor,
                maxLines = 1,
                softWrap = false,
            )
            Text(word.substring(focus + length), style = style, maxLines = 1, softWrap = false)
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, maxWidth = Int.MAX_VALUE)
        val (left, pivot, right) = measurables.map { it.measure(loose) }
        val width = constraints.maxWidth
        val height = maxOf(left.height, pivot.height, right.height)
        layout(width, height) {
            val pivotX = (width * FOCUS_POSITION).roundToInt() - pivot.width / 2
            left.place(pivotX - left.width, 0)
            pivot.place(pivotX, 0)
            right.place(pivotX + pivot.width, 0)
        }
    }
}

/** The sentence around the current word, shown while paused, with the word marked. */
@Composable
private fun SentenceContext(
    words: List<RsvpWord>,
    index: Int,
    modifier: Modifier = Modifier,
) {
    val from = maxOf(words.sentenceStartBefore(index + 1), index - 30)
    val to = minOf(words.sentenceEndFrom(index), index + 30)
    val highlight = MaterialTheme.colorScheme.tertiaryContainer
    val onHighlight = MaterialTheme.colorScheme.onTertiaryContainer
    val text =
        buildAnnotatedString {
            for (i in from..to) {
                if (i == index) {
                    withStyle(SpanStyle(background = highlight, color = onHighlight)) { append(words[i].text) }
                } else {
                    append(words[i].text)
                }
                if (i < to) append(' ')
            }
        }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier.padding(top = 20.dp).widthIn(max = 640.dp),
    )
}
