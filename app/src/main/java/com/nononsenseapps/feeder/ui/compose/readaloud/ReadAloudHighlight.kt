@file:Suppress("ktlint:compose:compositionlocal-allowlist")

package com.nononsenseapps.feeder.ui.compose.readaloud

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.semantics
import com.nononsenseapps.feeder.model.SpeechSegment
import com.nononsenseapps.feeder.model.html.LinearArticle
import com.nononsenseapps.feeder.model.html.LinearBlockQuote
import com.nononsenseapps.feeder.model.html.LinearElement
import com.nononsenseapps.feeder.model.html.LinearListItem
import com.nononsenseapps.feeder.model.html.LinearText
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.IdentityHashMap

data class SpeechHighlight(
    val text: String,
    val segment: SpeechSegment,
    val focusRequestId: Long = 0,
    val followSpokenText: Boolean = false,
    // The word being spoken, highlighted more strongly on top of its sentence
    val word: SpeechSegment? = null,
)

@OptIn(ExperimentalFoundationApi::class)
internal object CenteredSpeechBringIntoViewSpec : BringIntoViewSpec {
    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float,
    ): Float = offset + size / 2f - containerSize / 2f
}

/**
 * The article's viewport. While following the spoken text it keeps that text centered, and it
 * stops following on user input: scrolling, dragging, or holding a finger on the text.
 *
 * Following never scrolls while a finger is down. A long press selects text (Copy, Highlight),
 * and text moving under the finger would cancel it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SpeechFollowViewport(
    centerSpeech: Boolean,
    onUserScroll: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val touch = remember { TouchState() }
    val spec =
        if (centerSpeech) {
            remember(touch) { HoldStillBringIntoViewSpec(CenteredSpeechBringIntoViewSpec, touch) }
        } else {
            LocalBringIntoViewSpec.current
        }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
        Box(modifier.stopSpeechFollowingOnUserInput(touch, onUserScroll), content = content)
    }
}

internal class TouchState {
    var down: Boolean = false
}

/** No scrolling to the spoken text while a finger is on the screen. */
@OptIn(ExperimentalFoundationApi::class)
internal class HoldStillBringIntoViewSpec(
    private val spec: BringIntoViewSpec,
    private val touch: TouchState,
) : BringIntoViewSpec {
    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float,
    ): Float = if (touch.down) 0f else spec.calculateScrollDistance(offset, size, containerSize)
}

/** Programmatic bring-into-view scrolling must not disable following; user input must. */
@Composable
private fun Modifier.stopSpeechFollowingOnUserInput(
    touch: TouchState,
    onUserScroll: () -> Unit,
): Modifier {
    val callback = rememberUpdatedState(onUserScroll)
    return pointerInput(touch) {
        coroutineScope {
            var holding: Job? = null
            awaitPointerEventScope {
                var origin: Offset? = null
                var dragging = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Scroll && event.changes.any { it.scrollDelta != Offset.Zero }) {
                        callback.value()
                    }
                    val pressed = event.changes.firstOrNull { it.pressed }
                    touch.down = pressed != null
                    if (pressed == null) {
                        origin = null
                        dragging = false
                        holding?.cancel()
                        holding = null
                    } else if (origin == null) {
                        origin = pressed.position
                        // Held: a long press to select text. A quick tap (read from here, links) leaves following on
                        holding =
                            launch {
                                delay(viewConfiguration.longPressTimeoutMillis)
                                callback.value()
                            }
                    } else if (!dragging && (pressed.position - checkNotNull(origin)).getDistance() > viewConfiguration.touchSlop) {
                        dragging = true
                        holding?.cancel()
                        holding = null
                        callback.value()
                    }
                }
            }
        }
    }
}

/** Accessibility scroll actions do not produce pointer events. */
@Composable
internal fun Modifier.stopSpeechFollowingOnAccessibilityScroll(
    state: ScrollState,
    onUserScroll: () -> Unit,
): Modifier {
    val scope = rememberCoroutineScope()
    return semantics {
        scrollBy { _, y ->
            onUserScroll()
            scope.launch { state.animateScrollBy(y) }
            true
        }
    }
}

/** Neutral emphasis, independent of dynamic accent colors, without sacrificing text contrast. */
internal fun speechHighlightColor(
    background: Color,
    foreground: Color,
    link: Color,
): Color {
    fun contrast(
        a: Color,
        b: Color,
    ): Float {
        val x = a.luminance()
        val y = b.luminance()
        return (maxOf(x, y) + 0.05f) / (minOf(x, y) + 0.05f)
    }
    val neutral = if (background.luminance() > 0.5f) Color.Black else Color.White
    val targetAlpha = if (neutral == Color.Black) 0.10f else 0.14f
    val colors = listOf(foreground, link)
    val minimums = colors.map { minOf(4.5f, contrast(it, background)) }
    for (step in 10 downTo 0) {
        val candidate = neutral.copy(alpha = targetAlpha * step / 10)
        val highlighted = candidate.compositeOver(background)
        if (colors.indices.all { contrast(colors[it], highlighted) + 0.001f >= minimums[it] }) return candidate
    }
    return Color.Transparent
}

// A document-scoped rendering attribute, including nested quotes, lists, and table cells.
@Suppress("ktlint:compose:compositionlocal-allowlist")
internal val LocalSpeechHighlights = compositionLocalOf<Map<LinearText, IntRange>> { emptyMap() }

@Suppress("ktlint:compose:compositionlocal-allowlist")
internal val LocalSpeechWordHighlights = compositionLocalOf<Map<LinearText, IntRange>> { emptyMap() }

internal class SpeechFocusTarget(
    val text: LinearText,
    val range: IntRange,
    val requestId: Long,
)

@Suppress("ktlint:compose:compositionlocal-allowlist")
internal val LocalSpeechFocusTarget = compositionLocalOf<SpeechFocusTarget?> { null }

/**
 * The text elements of an article in reading order that read aloud speaks, the same ones that can be highlighted.
 * Tables and footnote list items are left out: read aloud keeps to the article's paragraphs and headings.
 */
internal fun LinearArticle.speakableTexts(): List<LinearText> {
    val result = mutableListOf<LinearText>()

    fun visit(element: LinearElement) {
        when (element) {
            is LinearText -> result += element
            is LinearBlockQuote -> element.content.forEach(::visit)
            is LinearListItem -> if (!isFootnoteItem(element.ids)) element.content.forEach(::visit)
            else -> Unit
        }
    }
    elements.forEach(::visit)
    return result
}

// Footnote list items ("fn1", "fn:1", "footnote-1", "cite_note-3"), not the in-text anchors ("fnref1", "footnote-ref-1"),
// whose ids the linearizer also collects on the list item or paragraph around them
private val FOOTNOTE_ID = Regex("""^(?:fn[:_-]?\d|footnote|endnote|cite_note)(?!.*ref)""", RegexOption.IGNORE_CASE)

private fun isFootnoteItem(ids: Set<String>): Boolean = ids.any { FOOTNOTE_ID.containsMatchIn(it) }

/**
 * The text to read aloud for an article, built from what is shown so highlights always match.
 */
internal fun LinearArticle.speechText(): String =
    speakableTexts()
        .map { spokenTextOf(it).text.trim() }
        .filter { it.isNotBlank() }
        .joinToString("\n\n")

/**
 * Position in [speechText] of character [index] in [text], or null when that element is not read aloud.
 */
internal fun LinearArticle.speechOffsetOf(
    text: LinearText,
    index: Int,
): Int? {
    var position = 0
    var first = true
    for (element in speakableTexts()) {
        val spoken = spokenTextOf(element)
        val trimmed = spoken.text.trim()
        if (element === text && trimmed.isEmpty()) return null
        if (trimmed.isEmpty()) continue
        if (!first) position += 2
        first = false
        if (element === text) {
            val leading = spoken.text.indexOfFirst { !it.isWhitespace() }
            return position + (spoken.spokenIndexOf(index) - leading).coerceIn(0, trimmed.length - 1)
        }
        position += trimmed.length
    }
    return null
}

/**
 * The word around [offset] in [text], without surrounding punctuation, or null for whitespace.
 * A tap on the right half of a word's last letter reports the offset just after it.
 */
internal fun wordRangeAt(
    text: String,
    offset: Int,
): IntRange? {
    if (text.isEmpty()) return null
    var index = offset.coerceIn(0, text.length - 1)
    if (text[index].isWhitespace() && index > 0 && !text[index - 1].isWhitespace() && offset >= 1) index--
    if (text[index].isWhitespace()) return null
    var start = index
    var end = index
    while (start > 0 && !text[start - 1].isWhitespace()) start--
    while (end < text.length - 1 && !text[end + 1].isWhitespace()) end++
    var first = start
    var last = end
    while (first < last && !text[first].isLetterOrDigit()) first++
    while (last > first && !text[last].isLetterOrDigit()) last--
    return if (text[first].isLetterOrDigit()) first..last else start..end
}

/** Lets a tap on article text start read aloud from that point. */
internal class ReadAloudTapHandler(
    // While reading, a tap jumps straight there; otherwise it offers to start reading
    val isReading: Boolean,
    val onReadFrom: (LinearText, Int) -> Unit,
)

@Suppress("ktlint:compose:compositionlocal-allowlist")
internal val LocalReadAloudTapHandler = compositionLocalOf<ReadAloudTapHandler?> { null }

/** Maps matching text in document order, never highlighting another occurrence of a repeated paragraph. */
internal class SpeechHighlightIndex(
    article: LinearArticle,
    speechText: String,
) {
    private val source = normalizeSpeechText(speechText)
    private val matches = IdentityHashMap<LinearText, Pair<Int, NormalizedSpeechText>>()
    private val documentOrder = mutableListOf<LinearText>()

    init {
        var cursor = 0
        article.speakableTexts().forEach { element ->
            // Match the spoken version, but highlight characters of the text on screen
            val spoken = spokenTextOf(element)
            val normalized =
                normalizeSpeechText(spoken.text).let { it.copy(offsets = it.offsets.map { i -> spoken.sourceIndex[i] }) }
            if (normalized.text.isNotBlank()) {
                val start = source.text.indexOf(normalized.text, cursor)
                if (start >= 0) {
                    matches[element] = start to normalized
                    documentOrder += element
                    cursor = start + normalized.text.length
                }
            }
        }
    }

    fun highlights(segment: SpeechSegment): Map<LinearText, IntRange> {
        val result = IdentityHashMap<LinearText, IntRange>()
        matches.forEach { (element, match) ->
            val (start, normalized) = match
            val indices = normalized.offsets.indices.filter { source.offsets[start + it] in segment.start until segment.end }
            if (indices.isNotEmpty()) {
                result[element] = normalized.offsets[indices.first()]..normalized.offsets[indices.last()]
            }
        }
        return result
    }

    fun focusTarget(
        segment: SpeechSegment,
        requestId: Long,
    ): SpeechFocusTarget? {
        if (requestId == 0L) return null
        val ranges = highlights(segment)
        val first = documentOrder.firstOrNull { ranges.containsKey(it) } ?: return null
        return SpeechFocusTarget(first, ranges.getValue(first), requestId)
    }
}

internal data class NormalizedSpeechText(
    val text: String,
    val offsets: List<Int>,
)

internal fun normalizeSpeechText(text: String): NormalizedSpeechText {
    val offsets = mutableListOf<Int>()
    val normalized =
        buildString {
            text.forEachIndexed { index, char ->
                if (char.isWhitespace() || char == '\u00a0') {
                    if (isNotEmpty() && last() != ' ') {
                        append(' ')
                        offsets += index
                    }
                } else {
                    append(char)
                    offsets += index
                }
            }
            if (endsWith(' ')) {
                deleteCharAt(length - 1)
                offsets.removeAt(offsets.lastIndex)
            }
        }
    return NormalizedSpeechText(normalized, offsets)
}
