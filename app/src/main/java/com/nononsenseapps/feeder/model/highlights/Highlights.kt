package com.nononsenseapps.feeder.model.highlights

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/** The four highlighter colors, the same as the web app's. */
enum class HighlightColor(
    val id: String,
    /** Soft color behind highlighted text and on swatches. */
    val swatch: Long,
    /** Stronger color for strokes on share images. */
    val ink: Long,
) {
    YELLOW("yellow", 0xFFFFE066, 0xFFFFD43B),
    GREEN("green", 0xFFB7EBC8, 0xFF5CC583),
    BLUE("blue", 0xFFBCDCFF, 0xFF5AA5F0),
    PINK("pink", 0xFFF6C3E0, 0xFFE07BB4),
    ;

    companion object {
        fun of(id: String?): HighlightColor = entries.firstOrNull { it.id == id } ?: YELLOW
    }
}

/**
 * A highlighted passage of an article, with an optional note. Synced with other devices as a
 * "highlight" record (sync2/Sync2Client.kt, same fields as the web app's HighlightValue).
 */
@Serializable
data class Highlight(
    val id: String,
    /** The article as "feedUrl \n guid", the way sync names articles. */
    val itemKey: String,
    // Copied from the article so the Highlights screen still works after it is cleaned up
    val itemTitle: String = "",
    val itemLink: String? = null,
    val feedTitle: String = "",
    val imageUrl: String? = null,
    val text: String,
    /** Where [text] starts in the article text, and a few characters before it, to find it again. */
    val start: Int = 0,
    val prefix: String = "",
    val color: String = HighlightColor.YELLOW.id,
    val note: String = "",
    val createdAt: Long,
    /** Last color or note change; sync goes by it (newest wins). */
    val modifiedAt: Long = createdAt,
    /** Ebook highlights (item "ebook:\n<book id>"): where the text is, as an EPUB CFI. */
    val cfi: String? = null,
) {
    val highlightColor: HighlightColor get() = HighlightColor.of(color)
}

/** The article a new highlight belongs to. */
data class HighlightArticle(
    val itemKey: String,
    val title: String,
    val link: String?,
    val feedTitle: String,
    val imageUrl: String?,
)

fun newHighlight(
    article: HighlightArticle,
    anchor: HighlightAnchor,
    color: HighlightColor,
    note: String,
    now: Long = System.currentTimeMillis(),
): Highlight =
    Highlight(
        id = UUID.randomUUID().toString(),
        itemKey = article.itemKey,
        itemTitle = article.title,
        itemLink = article.link,
        feedTitle = article.feedTitle,
        imageUrl = article.imageUrl,
        text = anchor.text,
        start = anchor.start,
        prefix = anchor.prefix,
        color = color.id,
        note = note.trim(),
        createdAt = now,
    )

/** Highlights kept on this device, like the playlists. Every change is told to sync. */
class HighlightStore(
    private val prefs: SharedPreferences,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Highlight.serializer())
    private val _highlights = MutableStateFlow(load())
    val highlights: StateFlow<List<Highlight>> = _highlights.asStateFlow()

    /** Told after every change made here (not after changes from sync). */
    @Volatile
    var onChanged: (() -> Unit)? = null

    /** Told how a highlight was and is after a change made here (null = none), for History. */
    @Volatile
    var onEdit: ((before: Highlight?, after: Highlight?) -> Unit)? = null

    private fun load(): List<Highlight> = runCatching { json.decodeFromString(serializer, prefs.getString(PREF_KEY, null) ?: "[]") }.getOrDefault(emptyList())

    private fun write(next: List<Highlight>) {
        _highlights.value = next
        prefs.edit { putString(PREF_KEY, json.encodeToString(serializer, next)) }
    }

    fun get(id: String): Highlight? = _highlights.value.firstOrNull { it.id == id }

    @Synchronized
    fun add(h: Highlight) {
        val before = get(h.id)
        write(_highlights.value.filterNot { it.id == h.id } + h)
        onChanged?.invoke()
        onEdit?.invoke(before, h)
    }

    /** Changes the color and note, stamped with the time so this edit wins on other devices. */
    @Synchronized
    fun update(
        id: String,
        color: HighlightColor,
        note: String,
    ) {
        val now = System.currentTimeMillis()
        val before = get(id)
        write(
            _highlights.value.map {
                if (it.id == id) it.copy(color = color.id, note = note.trim(), modifiedAt = maxOf(now, it.modifiedAt + 1)) else it
            },
        )
        onChanged?.invoke()
        if (before != null) onEdit?.invoke(before, get(id))
    }

    @Synchronized
    fun delete(id: String) {
        val before = get(id)
        write(_highlights.value.filterNot { it.id == id })
        onChanged?.invoke()
        if (before != null) onEdit?.invoke(before, null)
    }

    /** As another device left it (sync): stored as it is and not sent back. */
    @Synchronized
    fun putFromSync(h: Highlight) = write(_highlights.value.filterNot { it.id == h.id } + h)

    @Synchronized
    fun deleteFromSync(id: String) {
        if (_highlights.value.any { it.id == id }) write(_highlights.value.filterNot { it.id == id })
    }

    companion object {
        private const val PREF_KEY = "highlights_v1"
    }
}

/** What a new highlight remembers about where it is. */
data class HighlightAnchor(
    val text: String,
    val start: Int,
    val prefix: String,
)

/**
 * Finds highlights in an article's text, made of paragraphs (the reader's text elements, in
 * order). Spaces, line breaks and quote styles are ignored when comparing, because a selection
 * across paragraphs and the web app's text join paragraphs differently, and the web app's feed
 * text often has straight quotes where this app shows curly ones.
 */
class HighlightLocator(
    private val paragraphs: List<String>,
) {
    /** All paragraphs joined by line breaks: what [Highlight.start] counts on this device. */
    val full: String = paragraphs.joinToString("\n")

    // The text without spaces, and for each of its characters: paragraph, and index in it and in [full]
    private val stripped: String
    private val para: IntArray
    private val index: IntArray
    private val fullIndex: IntArray

    init {
        val sb = StringBuilder()
        val p = ArrayList<Int>()
        val i = ArrayList<Int>()
        val f = ArrayList<Int>()
        var offset = 0
        paragraphs.forEachIndexed { pi, text ->
            text.forEachIndexed { ci, ch ->
                if (!isGap(ch)) {
                    sb.append(fold(ch))
                    p += pi
                    i += ci
                    f += offset + ci
                }
            }
            offset += text.length + 1
        }
        stripped = sb.toString()
        para = p.toIntArray()
        index = i.toIntArray()
        fullIndex = f.toIntArray()
    }

    /**
     * Where [text] is, as a range of stripped characters, or null. When it appears more than once,
     * the copy whose preceding text matches [prefix] best wins, then the one nearest [start].
     */
    private fun find(
        text: String,
        prefix: String,
        start: Int,
    ): IntRange? {
        val needle = strip(text)
        if (needle.isEmpty()) return null
        val before = strip(prefix)
        var best: Int = -1
        var bestScore = -1
        var bestDistance = Int.MAX_VALUE
        var at = stripped.indexOf(needle)
        while (at >= 0) {
            var score = 0
            while (score < before.length && at - 1 - score >= 0 && stripped[at - 1 - score] == before[before.length - 1 - score]) score++
            val distance = kotlin.math.abs(fullIndex[at] - start)
            if (score > bestScore || (score == bestScore && distance < bestDistance)) {
                best = at
                bestScore = score
                bestDistance = distance
            }
            at = stripped.indexOf(needle, at + 1)
        }
        return if (best < 0) null else best until best + needle.length
    }

    /** For a highlight: the characters it covers in each paragraph, or empty when it isn't in this text. */
    fun ranges(
        text: String,
        prefix: String,
        start: Int,
    ): Map<Int, IntRange> {
        val r = find(text, prefix, start) ?: return emptyMap()
        val out = LinkedHashMap<Int, IntRange>()
        for (s in r) {
            val p = para[s]
            val c = index[s]
            val prev = out[p]
            out[p] = if (prev == null) c..c else prev.first..c
        }
        return out
    }

    /** Where a highlight starts in this text, for reading order; null when it isn't in it. */
    fun positionOf(h: Highlight): Int? = find(h.text, h.prefix, h.start)?.let { fullIndex[it.first] }

    /** Where a selected passage is, to save it as a highlight. Null when it isn't in the article text. */
    fun anchorOf(selected: String): HighlightAnchor? {
        val text = selected.trim()
        val r = find(text, "", 0) ?: return null
        val start = fullIndex[r.first]
        return HighlightAnchor(text = text, start = start, prefix = full.substring(maxOf(0, start - PREFIX_LEN), start))
    }

    companion object {
        const val PREFIX_LEN = 32

        private fun isGap(c: Char): Boolean = c.isWhitespace() || c == '\u00a0' || c == '\u200b'

        /** Curly quotes and dashes as plain ones, the same as the web app's foldChar. */
        private fun fold(c: Char): Char =
            when (c) {
                '\u2018', '\u2019', '\u201a', '\u201b', '\u02bc', '\u2032' -> '\''
                '\u201c', '\u201d', '\u201e', '\u2033' -> '"'
                '\u2013', '\u2014', '\u2212' -> '-'
                else -> c
            }

        private fun strip(s: String): String = buildString { s.forEach { if (!isGap(it)) append(fold(it)) } }
    }
}

/** What goes on the clipboard and in a share's text: the quote, where it's from, and the link. */
fun quoteText(
    text: String,
    itemTitle: String,
    feedTitle: String,
    itemLink: String?,
    note: String = "",
): String {
    val from = listOf(itemTitle, feedTitle).filter { it.isNotBlank() }.joinToString(", ")
    return buildString {
        append("“").append(text).append("”")
        if (note.isNotBlank()) append("\n").append(note)
        if (from.isNotBlank()) append("\n— ").append(from)
        if (!itemLink.isNullOrBlank()) append("\n").append(itemLink)
    }
}

/** Several highlights of one article as text, in reading order. Same format as the web app. */
fun allQuotesText(
    hs: List<Highlight>,
    withNotes: Boolean = true,
): String {
    val first = hs.firstOrNull() ?: return ""
    val quotes = hs.joinToString("\n\n") { h -> "“${h.text}”" + if (withNotes && h.note.isNotBlank()) "\n  ${h.note}" else "" }
    val from = listOf(first.itemTitle, first.feedTitle).filter { it.isNotBlank() }.joinToString(", ")
    return "$quotes\n\n— $from" + (first.itemLink?.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: "")
}
