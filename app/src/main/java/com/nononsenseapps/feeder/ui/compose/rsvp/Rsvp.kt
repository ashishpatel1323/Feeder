package com.nononsenseapps.feeder.ui.compose.rsvp

import kotlin.math.ceil
import kotlin.math.roundToInt

// Speed reading (RSVP: rapid serial visual presentation). One word at a time, at a fixed spot,
// with a focus letter so the eye never has to move. The same rules live in the web app
// (web/src/lib/rsvp.ts); keep them in step.

/**
 * A word as shown, punctuation included. [start] is where it starts in the source text.
 */
data class RsvpWord(
    val text: String,
    val start: Int,
    val paragraphEnd: Boolean = false,
)

const val RSVP_MIN_WPM = 100
const val RSVP_MAX_WPM = 1000
const val RSVP_DEFAULT_WPM = 300
const val RSVP_WPM_STEP = 25

// Words longer than this are shown in hyphenated pieces so they fit and stay readable
private const val MAX_PIECE = 13

private val WORD = Regex("\\S+")
private val SENTENCE_END = Regex("[.!?…][\"'”’)\\]]*$")
private val CLAUSE_END = Regex("[,;:–—][\"'”’)\\]]*$")

private fun Char.isWordChar() = isLetterOrDigit() || isSurrogate()

/** Splits text into words. A line break ends a paragraph. */
fun rsvpWords(text: String): List<RsvpWord> {
    val words = mutableListOf<RsvpWord>()
    var lastEnd = 0
    for (match in WORD.findAll(text)) {
        val start = match.range.first
        if (words.isNotEmpty() && text.substring(lastEnd, start).contains('\n')) {
            words[words.lastIndex] = words.last().copy(paragraphEnd = true)
        }
        lastEnd = match.range.last + 1
        // Lone punctuation (a dash, a bullet) is not worth a frame of its own
        if (match.value.none { it.isWordChar() }) continue
        var offset = start
        for ((piece, length) in splitLong(match.value)) {
            words += RsvpWord(text = piece, start = offset)
            offset += length
        }
    }
    if (words.isNotEmpty()) words[words.lastIndex] = words.last().copy(paragraphEnd = true)
    return words
}

/** Pieces of a long word as (shown text, length in the source). */
private fun splitLong(token: String): List<Pair<String, Int>> {
    if (token.length <= MAX_PIECE + 2) return listOf(token to token.length)
    // Prefer existing hyphens, dashes and slashes as break points, joining neighbours while they fit
    val parts = token.split(Regex("(?<=[-–—/])")).filter { it.isNotEmpty() }
    if (parts.size > 1 && parts.all { it.length <= MAX_PIECE + 2 }) {
        val joined = mutableListOf<String>()
        for (part in parts) {
            if (joined.isNotEmpty() && joined.last().length + part.length <= MAX_PIECE + 2) {
                joined[joined.lastIndex] = joined.last() + part
            } else {
                joined += part
            }
        }
        return joined.map { it to it.length }
    }
    val count = ceil(token.length / MAX_PIECE.toDouble()).toInt()
    val size = ceil(token.length / count.toDouble()).toInt()
    return (token.indices step size).map { i ->
        val piece = token.substring(i, minOf(token.length, i + size))
        (if (i + size < token.length) "$piece-" else piece) to piece.length
    }
}

/** Index of the word at or just before [offset] in the source text. */
fun List<RsvpWord>.wordIndexAt(offset: Int): Int {
    var lo = 0
    var hi = lastIndex
    while (lo < hi) {
        val mid = (lo + hi + 1) / 2
        if (this[mid].start <= offset) lo = mid else hi = mid - 1
    }
    return lo.coerceAtLeast(0)
}

/**
 * Index of the focus letter: a little left of the middle of the word's letters, where the eye
 * recognises a word fastest. Leading quotes or brackets are skipped.
 */
fun focusIndex(word: String): Int {
    if (word.isEmpty()) return 0
    var start = 0
    while (start < word.length - 1 && !word[start].isWordChar()) start++
    var end = word.length
    while (end > start + 1 && !word[end - 1].isWordChar()) end--
    val len = end - start
    val pos =
        when {
            len <= 1 -> 0
            len <= 5 -> 1
            len <= 9 -> 2
            len <= 13 -> 3
            else -> 4
        }
    var index = (start + pos).coerceAtMost(word.length - 1)
    // Never point into the middle of a surrogate pair
    if (index > 0 && word[index].isLowSurrogate()) index--
    return index
}

/** Length in chars of the focus letter at [index] (2 for a surrogate pair). */
fun focusLength(
    word: String,
    index: Int,
): Int = if (index + 1 < word.length && word[index].isHighSurrogate()) 2 else 1

fun RsvpWord.endsSentence(): Boolean = paragraphEnd || SENTENCE_END.containsMatchIn(text)

/** How long to show a word, in milliseconds. Longer words and pauses in the sentence get more time. */
fun RsvpWord.delayMillis(wpm: Int): Long {
    val base = 60_000.0 / wpm
    var factor = 1.0
    val letters = text.count { it.isWordChar() }
    if (letters > 8) {
        factor += 0.4
    } else if (letters <= 3) {
        factor -= 0.1
    }
    when {
        paragraphEnd -> factor += 1.5
        SENTENCE_END.containsMatchIn(text) -> factor += 1.2
        CLAUSE_END.containsMatchIn(text) -> factor += 0.5
    }
    return (base * factor).roundToInt().toLong()
}

/** Index of the first word of the sentence before [index] (or of its own sentence if well into it). */
fun List<RsvpWord>.sentenceStartBefore(index: Int): Int {
    var start = minOf(index, size) - 1
    while (start >= 0 && !this[start].endsSentence()) start--
    val current = start + 1
    if (index - current > 2) return current
    var i = start - 1
    while (i >= 0 && !this[i].endsSentence()) i--
    return (i + 1).coerceAtLeast(0)
}

/** Index of the last word of the sentence holding [index]. */
fun List<RsvpWord>.sentenceEndFrom(index: Int): Int {
    var end = index.coerceIn(0, lastIndex.coerceAtLeast(0))
    while (end < lastIndex && !this[end].endsSentence()) end++
    return end
}

/** Milliseconds left at [wpm] from [index]. */
fun List<RsvpWord>.millisLeft(
    index: Int,
    wpm: Int,
): Long {
    var ms = 0L
    for (i in index.coerceAtLeast(0) until size) ms += this[i].delayMillis(wpm)
    return ms
}
