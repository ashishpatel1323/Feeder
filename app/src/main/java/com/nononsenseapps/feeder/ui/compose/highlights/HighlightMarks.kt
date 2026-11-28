package com.nononsenseapps.feeder.ui.compose.highlights

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.nononsenseapps.feeder.model.highlights.Highlight
import com.nononsenseapps.feeder.model.highlights.HighlightColor
import com.nononsenseapps.feeder.model.highlights.HighlightLocator
import com.nononsenseapps.feeder.model.html.LinearArticle
import com.nononsenseapps.feeder.model.html.LinearText
import com.nononsenseapps.feeder.ui.compose.readaloud.speakableTexts
import java.util.IdentityHashMap

/**
 * The open article's highlights, given to the reader so it can mark them. The reader tells back
 * the [locator] for the text it shows, so a new selection can be saved with its place.
 */
class UserHighlights(
    val list: List<Highlight>,
    val onTap: (Highlight) -> Unit,
) {
    /** Margin sketches (shown as strips after their paragraph); the rest are marked in the text. */
    val sketches: List<Highlight> = list.filter { !it.sketch.isNullOrBlank() }

    /** Handwriting on the page (ArticleInk), drawn where the layout matches. */
    val inks: List<Highlight> = list.filter { !it.ink.isNullOrBlank() }
    val marked: List<Highlight> = list.filter { it.sketch.isNullOrBlank() && it.ink.isNullOrBlank() }

    @Volatile
    var locator: HighlightLocator? = null
}

@Suppress("ktlint:compose:compositionlocal-allowlist")
val LocalUserHighlights = staticCompositionLocalOf<UserHighlights?> { null }

/** One highlight's characters in one text element. */
data class HighlightMark(
    val range: IntRange,
    val color: HighlightColor,
    val id: String,
)

@Suppress("ktlint:compose:compositionlocal-allowlist")
internal val LocalHighlightMarks = compositionLocalOf<Map<LinearText, List<HighlightMark>>> { emptyMap() }

/** The paragraphs highlights are found in: the same text elements read aloud uses. */
internal fun LinearArticle.highlightTexts(): List<LinearText> = speakableTexts()

internal fun highlightLocator(article: LinearArticle): HighlightLocator = HighlightLocator(article.highlightTexts().map { it.text })

/** Each text element's highlight marks (by identity: equal paragraphs are different places). */
internal fun highlightMarks(
    article: LinearArticle,
    locator: HighlightLocator,
    highlights: List<Highlight>,
): Map<LinearText, List<HighlightMark>> {
    if (highlights.isEmpty()) return emptyMap()
    val texts = article.highlightTexts()
    val out = IdentityHashMap<LinearText, MutableList<HighlightMark>>()
    for (h in highlights) {
        for ((p, range) in locator.ranges(h.text, h.prefix, h.start)) {
            val element = texts.getOrNull(p) ?: continue
            out.getOrPut(element) { mutableListOf() } += HighlightMark(range, h.highlightColor, h.id)
        }
    }
    return out
}

/** Each paragraph's handwritten notes: the one their words start in. */
internal fun paragraphInks(
    article: LinearArticle,
    locator: HighlightLocator,
    inks: List<Highlight>,
): Map<LinearText, List<ParagraphInk>> {
    if (inks.isEmpty()) return emptyMap()
    val texts = article.highlightTexts()
    val out = IdentityHashMap<LinearText, MutableList<ParagraphInk>>()
    for (h in inks) {
        val data = com.nononsenseapps.feeder.model.highlights.InkCodec.parse(h.ink) ?: continue
        val first = locator.ranges(h.text, h.prefix, h.start).entries.minByOrNull { it.key } ?: continue
        val element = texts.getOrNull(first.key) ?: continue
        out.getOrPut(element) { mutableListOf() } += ParagraphInk(h.id, data, first.value)
    }
    return out
}

@Suppress("ktlint:compose:compositionlocal-allowlist")
internal val LocalSketchStrips = compositionLocalOf<Map<Int, List<Highlight>>> { emptyMap() }

/**
 * Which top-level element each margin sketch goes after: the one holding the last paragraph of
 * its text. Sketches whose text isn't in the article go after the last element, so they're never lost.
 */
internal fun sketchStrips(
    article: LinearArticle,
    locator: HighlightLocator,
    sketches: List<Highlight>,
): Map<Int, List<Highlight>> {
    if (sketches.isEmpty() || article.elements.isEmpty()) return emptyMap()
    val texts = article.highlightTexts()
    // Each paragraph's top-level element
    val topOf = IdentityHashMap<LinearText, Int>()
    article.elements.forEachIndexed { index, element -> element.visitTexts { topOf[it] = index } }
    val out = sortedMapOf<Int, MutableList<Highlight>>()
    for (h in sketches) {
        val last = locator.ranges(h.text, h.prefix, h.start).keys.maxOrNull()
        val top = last?.let { texts.getOrNull(it) }?.let { topOf[it] } ?: article.elements.lastIndex
        out.getOrPut(top) { mutableListOf() } += h
    }
    return out
}

private fun com.nononsenseapps.feeder.model.html.LinearElement.visitTexts(f: (LinearText) -> Unit) {
    when (this) {
        is LinearText -> f(this)
        is com.nononsenseapps.feeder.model.html.LinearBlockQuote -> content.forEach { it.visitTexts(f) }
        is com.nononsenseapps.feeder.model.html.LinearListItem -> content.forEach { it.visitTexts(f) }
        else -> Unit
    }
}

/** Behind highlighted text. The text on it is always dark, so it reads in both themes. */
fun HighlightColor.background(): Color = Color(swatch)

val HighlightTextColor = Color(0xFF1B1B1F)
