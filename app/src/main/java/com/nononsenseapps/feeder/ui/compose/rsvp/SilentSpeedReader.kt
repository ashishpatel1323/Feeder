package com.nononsenseapps.feeder.ui.compose.rsvp

import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

const val SPEED_READING_PREFS = "speed_reading"
private const val PREF_WPM = "wpm"

// The first words after (re)starting are shown a little longer so the eye can settle
private val RAMP = listOf(1.8, 1.4, 1.15)

/** Where the silent (muted) speed reader is in the text. [focusRequest] changes when the page should scroll to it again. */
data class SilentSpeedReadState(
    val text: String = "",
    val words: List<RsvpWord> = emptyList(),
    val index: Int = 0,
    val playing: Boolean = false,
    val active: Boolean = false,
    val wpm: Int = RSVP_DEFAULT_WPM,
    val follow: Boolean = true,
    val focusRequest: Long = 1,
) {
    val word: RsvpWord? get() = if (active) words.getOrNull(index) else null

    val atEnd: Boolean get() = index >= words.lastIndex
}

/**
 * Speed reading with the voice muted: moves through the words on a timer at [SilentSpeedReadState.wpm],
 * which can go faster than any voice. Read aloud hands over its position when muted, and takes it
 * back when unmuted.
 */
class SilentSpeedReader(
    private val scope: CoroutineScope,
    private val prefs: SharedPreferences,
) {
    private val _state =
        MutableStateFlow(
            SilentSpeedReadState(wpm = prefs.getInt(PREF_WPM, RSVP_DEFAULT_WPM).coerceIn(RSVP_MIN_WPM, RSVP_MAX_WPM)),
        )
    val state: StateFlow<SilentSpeedReadState> = _state.asStateFlow()

    private var job: Job? = null

    // Words shown since play was last pressed or the position moved, for the ramp
    private var shownSincePlay = 0

    /** Starts at the word at [offset] in [text]. */
    fun start(
        text: String,
        offset: Int,
        play: Boolean,
    ) {
        job?.cancel()
        val words = rsvpWords(text)
        _state.update {
            it.copy(
                text = text,
                words = words,
                index = words.wordIndexAt(offset).coerceIn(0, words.lastIndex.coerceAtLeast(0)),
                active = words.isNotEmpty(),
                playing = false,
                follow = true,
                focusRequest = it.focusRequest + 1,
            )
        }
        if (play) play()
    }

    fun stop() {
        job?.cancel()
        _state.update { it.copy(active = false, playing = false) }
    }

    fun play() {
        val current = _state.value
        if (!current.active) return
        if (current.atEnd) _state.update { it.copy(index = 0) }
        _state.update { it.copy(playing = true) }
        runTimer()
    }

    fun pause() {
        job?.cancel()
        _state.update { it.copy(playing = false) }
    }

    fun jumpTo(index: Int) {
        _state.update { it.copy(index = index.coerceIn(0, it.words.lastIndex.coerceAtLeast(0))) }
        if (_state.value.playing) runTimer()
    }

    fun jumpToOffset(offset: Int) = jumpTo(_state.value.words.wordIndexAt(offset))

    fun skipWords(count: Int) = jumpTo(_state.value.index + count)

    fun setWpm(value: Int) {
        val stepped =
            ((value.toFloat() / RSVP_WPM_STEP).roundToInt() * RSVP_WPM_STEP)
                .coerceIn(RSVP_MIN_WPM, RSVP_MAX_WPM)
        _state.update { it.copy(wpm = stepped) }
        prefs.edit().putInt(PREF_WPM, stepped).apply()
    }

    fun setFollow(follow: Boolean) {
        _state.update { it.copy(follow = follow, focusRequest = if (follow) it.focusRequest + 1 else it.focusRequest) }
    }

    private fun runTimer() {
        job?.cancel()
        shownSincePlay = 0
        job =
            scope.launch {
                while (true) {
                    val current = _state.value
                    val word = current.words.getOrNull(current.index) ?: break
                    val ramp = RAMP.getOrElse(shownSincePlay) { 1.0 }
                    delay((word.delayMillis(current.wpm) * ramp).toLong())
                    shownSincePlay++
                    if (_state.value.atEnd) {
                        _state.update { it.copy(playing = false) }
                        break
                    }
                    _state.update { it.copy(index = it.index + 1) }
                }
            }
    }
}

/** End of the word starting at [start] in [text]: the next whitespace. */
fun wordEnd(
    text: String,
    start: Int,
): Int {
    var end = start.coerceIn(0, text.length)
    while (end < text.length && !text[end].isWhitespace()) end++
    return end
}

/** Index of the first word of the sentence holding [index]. */
fun List<RsvpWord>.sentenceStartOf(index: Int): Int {
    var start = index.coerceIn(0, lastIndex.coerceAtLeast(0))
    while (start > 0 && !this[start - 1].endsSentence()) start--
    return start
}
