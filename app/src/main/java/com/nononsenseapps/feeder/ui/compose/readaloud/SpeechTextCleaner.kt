package com.nononsenseapps.feeder.ui.compose.readaloud

import com.nononsenseapps.feeder.model.html.LinearText
import com.nononsenseapps.feeder.model.html.LinearTextAnnotationLink
import com.nononsenseapps.feeder.model.html.LinearTextAnnotationSuperscript

/*
 * Keeps clutter out of read aloud (and speed reading, which uses the same text): footnote
 * markers, [1]-style reference numbers, URLs, (Author, 2020) citations, photo credits and
 * copyright lines. The text on screen stays as it is; only what is spoken skips them.
 * Keep the rules in step with web/src/lib/speechclean.ts.
 */

/** The text of one element as read aloud, with each kept character's index in the element's text. */
internal class SpokenText(
    val text: String,
    val sourceIndex: IntArray,
) {
    /** Position in [text] of the first kept character at or after [index] in the element's text. */
    fun spokenIndexOf(index: Int): Int {
        if (text.isEmpty()) return 0
        val i = sourceIndex.indexOfFirst { it >= index }
        return if (i < 0) text.length - 1 else i
    }

    companion object {
        val EMPTY = SpokenText("", IntArray(0))
    }
}

/** What read aloud says for [element]: its text without clutter, or nothing for a credit or copyright line. */
internal fun spokenTextOf(element: LinearText): SpokenText {
    val text = element.text
    if (isSkippedLine(text)) return SpokenText.EMPTY
    val removed = BooleanArray(text.length)

    fun remove(range: IntRange) {
        for (i in range.first.coerceAtLeast(0)..range.last.coerceAtMost(text.length - 1)) removed[i] = true
    }

    val links = element.annotations.filter { it.data is LinearTextAnnotationLink }
    element.annotations.forEach { annotation ->
        val range = annotation.start until annotation.endExclusive.coerceAtMost(text.length)
        if (range.isEmpty()) return@forEach
        val content = text.substring(range)
        when (val data = annotation.data) {
            is LinearTextAnnotationSuperscript -> {
                val linked = links.any { it.start <= range.first && it.end >= range.last }
                val previous = text.getOrNull(range.first - 1)
                if (isFootnoteMarker(content, linked, previous)) remove(range)
            }
            is LinearTextAnnotationLink ->
                if (isFootnoteLink(data.href, content)) remove(range)
            else -> Unit
        }
    }
    speechRemovals(text).forEach(::remove)

    val kept = StringBuilder(text.length)
    val sourceIndex = IntArray(text.length)
    text.forEachIndexed { index, char ->
        if (!removed[index]) {
            sourceIndex[kept.length] = index
            kept.append(char)
        }
    }
    return SpokenText(kept.toString(), sourceIndex.copyOf(kept.length))
}

private val ORDINAL_SUFFIX = Regex("""^\s*(?:st|nd|rd|th|er|e|re|ème|o|a)\s*$""", RegexOption.IGNORE_CASE)
private val FOOTNOTE_MARKER =
    Regex("""^[\s\[(]*(?:\d+|[*†‡§¶#]+|[ivx]+|[a-z])(?:\s*[,–-]\s*(?:\d+|[a-z]))*[\s\])]*$""")
private val DIGITS = Regex("""^\s*\d+\s*$""")

/** A superscript that points to a note, not an exponent like 10⁶ or an ordinal like 1st. */
internal fun isFootnoteMarker(
    content: String,
    linked: Boolean,
    previous: Char?,
): Boolean {
    if (content.isBlank() || ORDINAL_SUFFIX.matches(content)) return false
    if (linked) return true
    if (!FOOTNOTE_MARKER.matches(content)) return false
    return !(DIGITS.matches(content) && previous?.isDigit() == true)
}

private val FOOTNOTE_HREF = Regex("""#(?:fn|footnote|endnote|cite_note|note|ref)""", RegexOption.IGNORE_CASE)
private val SHORT_MARKER = Regex("""^[\s\[(]*(?:\d{1,3}|[*†‡§a-z])[\s\])]*$""")

/** A plain link like `<a href="#fn1">1</a>` that jumps to a footnote. */
internal fun isFootnoteLink(
    href: String,
    content: String,
): Boolean = FOOTNOTE_HREF.containsMatchIn(href) && SHORT_MARKER.matches(content)

// "© 2026 …", "Copyright 2026 …", "… All rights reserved." but not "Copyright law is changing."
private val COPYRIGHT =
    Regex("""^\s*(?:©|\(c\)\s*\d{4}|copyright\s*(?:©|\(c\)|\d{4}))|\ball rights reserved\W*$""", RegexOption.IGNORE_CASE)
private val CREDIT =
    Regex(
        """^\s*(?:(?:(?:header|featured|lead|top|cover|hero)\s+)?(?:photo|image|picture|illustration|graphic|video)s?(?:\s+credits?)?\s*:""" +
            """|(?:photo(?:graph)?|image|illustration)s?\s+(?:by|courtesy of|via)\s|credits?\s*:)""",
        RegexOption.IGNORE_CASE,
    )
private val AGENCY_CREDIT =
    // "Jane Doe / Getty Images", "(Photo via Reuters)" but not "… in a statement to Reuters."
    Regex("""(?:[/|©]|\bvia\b|\bcourtesy\b)[^/|]*?(?:getty images|shutterstock|ap photo|reuters|afp|unsplash|istock)\W*$""", RegexOption.IGNORE_CASE)

/** A whole paragraph that is only a copyright notice or a photo credit. */
internal fun isSkippedLine(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return false
    if (trimmed.length < 300 && COPYRIGHT.containsMatchIn(trimmed)) return true
    if (trimmed.length < 200 && CREDIT.containsMatchIn(trimmed)) return true
    return trimmed.length < 120 && AGENCY_CREDIT.containsMatchIn(trimmed)
}

private val BRACKET_REF =
    Regex(
        """\[\s*(?:\d+[a-z]?(?:\s*[,–—-]\s*\d+[a-z]?)*|[a-z]|citation needed|clarification needed|edit|note\s+\d+|nb\s+\d+)\s*]""",
        RegexOption.IGNORE_CASE,
    )
private val URL = Regex("""(?:https?://|www\.)[^\s<>"]+""", RegexOption.IGNORE_CASE)
private const val NAME = """\p{Lu}[\p{L}'’\-]+"""
private const val MONTH =
    """(?:January|February|March|April|May|June|July|August|September|October|November|December|Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sept?|Oct|Nov|Dec|Spring|Summer|Fall|Autumn|Winter)\b"""
private const val AUTHORS = """(?!$MONTH)$NAME(?:(?:\s+|,\s*)(?:$NAME|et\s+al\.?|and|&))*"""
private const val YEAR = """(?:\d{4}[a-z]?|n\.\s?d\.)(?:,\s*pp?\.\s*\d+(?:\s*[–-]\s*\d+)?)?"""
private const val CITE = """$AUTHORS,\s*$YEAR(?:\s*,\s*$YEAR)*"""
private val CITATION = Regex("""[ \t\u00a0]*\(\s*(?:see\s+(?:also\s+)?|e\.g\.,?\s+|cf\.\s+)?$CITE(?:\s*;\s*$CITE)*\s*\)""")
private const val URL_TRAILING = ".,;:!?'\"”’"

/** Character ranges of [text] that read aloud skips: reference numbers, URLs and citations. */
internal fun speechRemovals(text: String): List<IntRange> {
    val result = mutableListOf<IntRange>()
    BRACKET_REF.findAll(text).forEach { result += it.range }
    CITATION.findAll(text).forEach { result += it.range }
    URL.findAll(text).forEach { match ->
        var end = match.range.last
        while (end > match.range.first) {
            val char = text[end]
            val url = text.substring(match.range.first, end + 1)
            val unbalanced = char == ')' && url.count { it == ')' } > url.count { it == '(' }
            if (char in URL_TRAILING || unbalanced) end-- else break
        }
        var start = match.range.first
        // "(https://example.com)" goes entirely, with the space before it
        if (text.getOrNull(start - 1) == '(' && text.getOrNull(end + 1) == ')') {
            start--
            end++
            while (start > 0 && text[start - 1] == ' ') start--
        }
        result += start..end
    }
    return result
}
