package com.nononsenseapps.feeder.model.search

import com.nononsenseapps.feeder.db.room.PlaylistCandidate
import com.nononsenseapps.feeder.model.highlights.Highlight
import com.nononsenseapps.feeder.model.playlists.ItemKind
import com.nononsenseapps.feeder.model.playlists.Playlist
import com.nononsenseapps.feeder.model.playlists.kind
import java.net.URI
import java.text.Normalizer

/**
 * Global search: one query over articles, episodes and videos (title, snippet, author, feed name),
 * saved podcast transcripts, highlights and their notes, feeds, folders and playlists. The same
 * matching and ranking as the web app's lib/search.ts, so both find the same things first.
 */
enum class SearchScope { ALL, ARTICLE, PODCAST, YOUTUBE, HIGHLIGHT, LIBRARY }

/** Text with the matched words as [start, end) ranges. */
data class Marked(
    val text: String,
    val ranges: List<IntRange> = emptyList(),
)

enum class MatchField { TITLE, FEED, TRANSCRIPT, TEXT }

sealed interface SearchHit {
    val key: String
    val score: Int
}

data class ItemHit(
    val item: PlaylistCandidate,
    val kind: ItemKind,
    override val score: Int,
    val field: MatchField,
    val title: Marked,
    /** The line that matched when it wasn't the title: the snippet or a transcript line. */
    val snippet: Marked?,
    /** Transcript match: where the first matching line starts, and how many lines match. */
    val atMs: Long? = null,
    val transcriptMatches: Int = 0,
) : SearchHit {
    override val key: String get() = "item:${item.id}"
}

data class HighlightHit(
    val highlight: Highlight,
    override val score: Int,
    val text: Marked,
    val note: Marked?,
) : SearchHit {
    override val key: String get() = "highlight:${highlight.id}"
}

data class FeedHit(
    val feedId: Long,
    val title: Marked,
    val tag: String,
    val image: String?,
    val unread: Int,
    override val score: Int,
) : SearchHit {
    override val key: String get() = "feed:$feedId"
}

data class FolderHit(
    val tag: String,
    val title: Marked,
    val feeds: Int,
    override val score: Int,
) : SearchHit {
    override val key: String get() = "folder:$tag"
}

data class PlaylistHit(
    val playlist: Playlist,
    val title: Marked,
    override val score: Int,
) : SearchHit {
    override val key: String get() = "playlist:${playlist.id}"
}

data class SearchResults(
    val query: String = "",
    val terms: List<String> = emptyList(),
    val items: List<ItemHit> = emptyList(),
    val highlights: List<HighlightHit> = emptyList(),
    val library: List<SearchHit> = emptyList(),
    val counts: Map<SearchScope, Int> = emptyMap(),
) {
    fun count(scope: SearchScope): Int = counts[scope] ?: 0

    /** The results one scope shows, best first. */
    fun inScope(scope: SearchScope): List<SearchHit> =
        when (scope) {
            SearchScope.ALL -> (items + highlights + library).sortedByDescending { it.score }
            SearchScope.HIGHLIGHT -> highlights
            SearchScope.LIBRARY -> library
            SearchScope.ARTICLE -> items.filter { it.kind == ItemKind.ARTICLE }
            SearchScope.PODCAST -> items.filter { it.kind == ItemKind.PODCAST }
            SearchScope.YOUTUBE -> items.filter { it.kind == ItemKind.YOUTUBE }
        }
}

/** Words of the article besides its title: the feed's snippet and the author. */
data class ItemText(
    val snippet: String,
    val author: String?,
)

data class TranscriptLine(
    val startMs: Long,
    val text: String,
)

data class SearchData(
    val items: List<PlaylistCandidate> = emptyList(),
    val texts: Map<Long, ItemText> = emptyMap(),
    val highlights: List<Highlight> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    /** Saved transcripts by item id. */
    val transcripts: Map<Long, List<TranscriptLine>> = emptyMap(),
    val now: Long = System.currentTimeMillis(),
)

private val MARKS = Regex("\\p{M}+")
private val SPACES = Regex("\\s+")

/** Lowercase with accents dropped, so "cafe" finds "Café"; keeps every position where it was. */
fun fold(s: String): String {
    val lower = s.lowercase()
    if (lower.length != s.length) return s.map { it.lowercaseChar() }.joinToString("")
    if (lower.all { it.code < 128 }) return lower
    val out = StringBuilder(lower.length)
    for (ch in lower) {
        val base = MARKS.replace(Normalizer.normalize(ch.toString(), Normalizer.Form.NFD), "")
        out.append(if (base.length == 1) base else ch.toString())
    }
    return out.toString()
}

fun queryTerms(query: String): List<String> = fold(query).split(SPACES).filter { it.isNotEmpty() }.distinct()

/** Where each term occurs, or null when one of them doesn't. */
fun findTerms(
    folded: String,
    terms: List<String>,
): List<IntRange>? {
    val ranges = mutableListOf<IntRange>()
    for (t in terms) {
        var at = folded.indexOf(t)
        if (at < 0) return null
        while (at >= 0) {
            ranges += at until at + t.length
            at = folded.indexOf(t, at + t.length)
        }
    }
    return merge(ranges)
}

private fun merge(ranges: List<IntRange>): List<IntRange> {
    val out = mutableListOf<IntRange>()
    for (r in ranges.sortedBy { it.first }) {
        val last = out.lastOrNull()
        if (last != null && r.first <= last.last + 1) {
            out[out.size - 1] = last.first..maxOf(last.last, r.last)
        } else {
            out += r
        }
    }
    return out
}

/** Every occurrence of every term (terms that don't occur are skipped), for marking. */
fun markTerms(
    text: String,
    terms: List<String>,
    folded: String = fold(text),
): Marked {
    val ranges = mutableListOf<IntRange>()
    for (t in terms) {
        var at = folded.indexOf(t)
        while (at >= 0) {
            ranges += at until at + t.length
            at = folded.indexOf(t, at + t.length)
        }
    }
    return Marked(text, merge(ranges))
}

/** A window of the text around the first match, cut at word edges, with the matches marked. */
fun snippetAround(
    text: String,
    folded: String,
    terms: List<String>,
    before: Int = 40,
    length: Int = 140,
): Marked {
    val first = terms.map { folded.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: return Marked(text.take(length))
    var start = maxOf(0, first - before)
    if (start > 0) {
        val space = text.indexOf(' ', start)
        if (space in 0 until first) start = space + 1
    }
    var end = minOf(text.length, start + length)
    if (end < text.length) {
        val space = text.lastIndexOf(' ', end)
        if (space > first) end = space
    }
    val lead = if (start > 0) "…" else ""
    val tail = if (end < text.length) "…" else ""
    val piece = markTerms(text.substring(start, end), terms, folded.substring(start, end))
    return Marked(lead + piece.text + tail, piece.ranges.map { (it.first + lead.length)..(it.last + lead.length) })
}

private val WORD = Regex("[\\p{L}\\p{N}]")

/** How well a name matches: the whole query at its start beats word starts beats anywhere. */
private fun titleScore(
    folded: String,
    query: String,
    terms: List<String>,
): Int {
    var s = 100
    if (folded.startsWith(query)) {
        s += 40
    } else if (terms.size > 1 && folded.contains(query)) {
        s += 25
    }
    for (t in terms) {
        val at = folded.indexOf(t)
        if (at == 0 || (at > 0 && !WORD.matches(folded[at - 1].toString()))) s += 12
    }
    return s
}

private const val DAY = 86_400_000L

/**
 * A small push for newer and saved things. Not read state: opening a result marks it read, and
 * the results must not reshuffle under you.
 */
private fun freshness(
    item: PlaylistCandidate,
    now: Long,
): Int {
    val age = maxOf(0L, now - item.sortTime)
    var s =
        when {
            age < 2 * DAY -> 8
            age < 7 * DAY -> 5
            age < 30 * DAY -> 2
            else -> 0
        }
    if (item.bookmarked) s += 4
    return s
}

private fun matchItem(
    item: PlaylistCandidate,
    data: SearchData,
    query: String,
    terms: List<String>,
): ItemHit? {
    val kind = item.kind
    val titleFolded = fold(item.title)
    val title = markTerms(item.title, terms, titleFolded)
    val fresh = freshness(item, data.now)
    if (findTerms(titleFolded, terms) != null) {
        return ItemHit(item, kind, titleScore(titleFolded, query, terms) + fresh, MatchField.TITLE, title, null)
    }
    val text = data.texts[item.id]
    if (findTerms(fold("${item.feedTitle} ${text?.author.orEmpty()}"), terms) != null) {
        return ItemHit(item, kind, 55 + fresh, MatchField.FEED, title, null)
    }
    data.transcripts[item.id]?.let { lines ->
        var first: TranscriptLine? = null
        var firstFolded = ""
        var count = 0
        for (line in lines) {
            val f = fold(line.text)
            if (findTerms(f, terms) != null) {
                count++
                if (first == null) {
                    first = line
                    firstFolded = f
                }
            }
        }
        if (first != null) {
            return ItemHit(
                item = item,
                kind = kind,
                score = 40 + minOf(10, count) + fresh,
                field = MatchField.TRANSCRIPT,
                title = title,
                snippet = snippetAround(first.text, firstFolded, terms),
                atMs = first.startMs,
                transcriptMatches = count,
            )
        }
    }
    val snippet = text?.snippet.orEmpty()
    if (snippet.isNotEmpty()) {
        val folded = fold(snippet)
        if (findTerms(folded, terms) != null) {
            val inTitle = terms.count { titleFolded.contains(it) }
            return ItemHit(item, kind, 30 + inTitle * 10 + fresh, MatchField.TEXT, title, snippetAround(snippet, folded, terms))
        }
        // Words split between the title and the snippet
        if (terms.size > 1 && findTerms("$titleFolded $folded", terms) != null) {
            return ItemHit(item, kind, 28 + fresh, MatchField.TEXT, title, snippetAround(snippet, folded, terms))
        }
    }
    return null
}

/** At most this many items come back; the list scrolls through them. */
const val MAX_ITEM_RESULTS = 300

fun searchAll(
    query: String,
    data: SearchData,
): SearchResults {
    val terms = queryTerms(query)
    if (terms.isEmpty()) return SearchResults(query = query)
    val q = terms.joinToString(" ")

    val items =
        data.items
            .mapNotNull { matchItem(it, data, q, terms) }
            .sortedWith(compareByDescending<ItemHit> { it.score }.thenByDescending { it.item.sortTime })

    val highlights =
        data.highlights
            .mapNotNull { h ->
                if (findTerms(fold("${h.text} ${h.note} ${h.itemTitle}"), terms) == null) return@mapNotNull null
                val textFolded = fold(h.text)
                val inText = findTerms(textFolded, terms) != null
                HighlightHit(
                    highlight = h,
                    score = (if (inText) 70 else 45) + (if (h.createdAt > data.now - 30 * DAY) 3 else 0),
                    text = snippetAround(h.text, textFolded, terms, 60, 200),
                    note = h.note.takeIf { it.isNotBlank() }?.let { markTerms(it, terms) },
                )
            }.sortedWith(compareByDescending<HighlightHit> { it.score }.thenByDescending { it.highlight.createdAt })

    val library = mutableListOf<SearchHit>()
    val unread =
        data.items
            .filterNot { it.read }
            .groupingBy { it.feedId }
            .eachCount()
    val feeds = data.items.distinctBy { it.feedId }.filter { it.feedTitle.isNotBlank() && it.feedId > 0 }
    for (f in feeds) {
        if (findTerms(fold("${f.feedTitle} ${hostOf(f.feedUrl)}"), terms) == null) continue
        library += FeedHit(f.feedId, markTerms(f.feedTitle, terms), f.feedTag, f.feedImage, unread[f.feedId] ?: 0, titleScore(fold(f.feedTitle), q, terms) + 20)
    }
    feeds
        .filter { it.feedTag.isNotBlank() }
        .groupingBy { it.feedTag }
        .eachCount()
        .forEach { (tag, n) ->
            val f = fold(tag)
            if (findTerms(f, terms) != null) library += FolderHit(tag, markTerms(tag, terms), n, titleScore(f, q, terms) + 15)
        }
    for (pl in data.playlists) {
        val f = fold(pl.name)
        if (findTerms(f, terms) != null) library += PlaylistHit(pl, markTerms(pl.name, terms), titleScore(f, q, terms) + 18)
    }
    library.sortByDescending { it.score }

    val counts =
        mapOf(
            SearchScope.ALL to items.size + highlights.size + library.size,
            SearchScope.ARTICLE to items.count { it.kind == ItemKind.ARTICLE },
            SearchScope.PODCAST to items.count { it.kind == ItemKind.PODCAST },
            SearchScope.YOUTUBE to items.count { it.kind == ItemKind.YOUTUBE },
            SearchScope.HIGHLIGHT to highlights.size,
            SearchScope.LIBRARY to library.size,
        )
    return SearchResults(query, terms, items.take(MAX_ITEM_RESULTS), highlights, library, counts)
}

private fun hostOf(url: String): String = runCatching { URI(url).host?.removePrefix("www.") }.getOrNull().orEmpty()

/** "16:17" or "1:02:03" for a transcript time. */
fun clock(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = (s % 60).toString().padStart(2, '0')
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$sec" else "$m:$sec"
}
