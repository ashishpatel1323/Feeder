package com.nononsenseapps.feeder.ui.compose.feed

import androidx.compose.runtime.Immutable
import com.nononsenseapps.feeder.model.SpeechSegment
import com.nononsenseapps.feeder.ui.compose.readaloud.normalizeSpeechText

/** The sentence and word being spoken within one piece of text on a flip card. */
@Immutable
internal data class SpokenRanges(
    val sentence: IntRange?,
    val word: IntRange?,
)

/** Shortest start of a sentence that is still looked for in the article when the whole one is not found. */
private const val MIN_SENTENCE_PREFIX = 24

/**
 * Where each of [parts] starts in the text read aloud for them, the same way read aloud joins
 * them: blank parts are left out (null) and the others are separated by a blank line.
 */
internal fun spokenPartStarts(parts: List<String>): List<Int?> {
    var position = 0
    return parts.map { part ->
        if (part.isBlank()) {
            null
        } else {
            position.also { position += part.length + 2 }
        }
    }
}

/** The text read aloud for [parts]: what the flip card passes to read aloud. */
internal fun spokenTextOf(parts: List<String>): String = parts.filterNot { it.isBlank() }.joinToString("\n\n")

/**
 * The spoken sentence and word within each of [parts], or null for a part not being spoken.
 * Empty when [spokenText] is not the text of these parts (another card or article is read).
 */
internal fun spokenRangesOf(
    parts: List<String>,
    spokenText: String,
    segment: SpeechSegment?,
    word: SpeechSegment?,
): List<SpokenRanges?> {
    if (segment == null && word == null) return emptyList()
    if (spokenText != spokenTextOf(parts)) return emptyList()
    val starts = spokenPartStarts(parts)
    return parts.mapIndexed { index, part ->
        val start = starts[index] ?: return@mapIndexed null
        val sentence = segment?.let { rangeWithin(it, start, part.length) }
        val spokenWord = word?.let { rangeWithin(it, start, part.length) }
        if (sentence == null && spokenWord == null) null else SpokenRanges(sentence, spokenWord)
    }
}

private fun rangeWithin(
    segment: SpeechSegment,
    start: Int,
    length: Int,
): IntRange? {
    val first = maxOf(segment.start, start) - start
    val last = minOf(segment.end, start + length) - start - 1
    return if (first <= last) first..last else null
}

/**
 * The sentence of the card's body being spoken, to go on reading from in the full article, or
 * null when the title is being read (the article then starts from the top).
 */
internal fun spokenBodySentence(
    parts: List<String>,
    spokenText: String,
    segment: SpeechSegment?,
): String? {
    if (segment == null || spokenText != spokenTextOf(parts)) return null
    val bodyStart = spokenPartStarts(parts).drop(1).firstOrNull { it != null } ?: return null
    if (segment.end <= bodyStart) return null
    val start = segment.start.coerceIn(bodyStart, spokenText.length)
    val end = segment.end.coerceIn(start, spokenText.length)
    // The card text is cut off with an ellipsis that the article does not have
    return spokenText
        .substring(start, end)
        .trim()
        .removeSuffix("…")
        .trim()
        .takeIf { it.isNotEmpty() }
}

/**
 * Where [sentence] starts in [text], ignoring differences in white space, or null when it is not
 * there. A sentence cut off at the end of a card is found by its start.
 */
internal fun speechOffsetOfSentence(
    text: String,
    sentence: String,
): Int? {
    val source = normalizeSpeechText(text)
    val wanted = normalizeSpeechText(sentence).text
    if (wanted.isEmpty()) return null
    var candidate = wanted
    while (true) {
        val index = source.text.indexOf(candidate)
        if (index >= 0) return source.offsets[index]
        if (candidate.length <= MIN_SENTENCE_PREFIX) return null
        // Drop the last word and try again
        val cut = candidate.lastIndexOf(' ', candidate.length - 2)
        if (cut < MIN_SENTENCE_PREFIX) return null
        candidate = candidate.substring(0, cut)
    }
}
