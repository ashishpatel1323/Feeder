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

/** Behind highlighted text. The text on it is always dark, so it reads in both themes. */
fun HighlightColor.background(): Color = Color(swatch)

val HighlightTextColor = Color(0xFF1B1B1F)
