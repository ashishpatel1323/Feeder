package com.nononsenseapps.feeder.ui.compose.discover

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

/**
 * Discover's sources, all free and keyless (the web app's lib/discover.ts does the same):
 * a bundled topic list (assets/discover-feeds.json, shared with the web app), Feedly's public
 * feed search, Google News search feeds, Apple Podcasts search and Top charts, and YouTube
 * channel search (each channel has an RSS feed).
 */
enum class DiscoverKind { TEXT, PODCAST, YOUTUBE }

@Immutable
data class DiscoverResult(
    val kind: DiscoverKind,
    val title: String,
    val feedUrl: String,
    /** Website, Apple Podcasts page or YouTube channel page. */
    val site: String? = null,
    val image: String? = null,
    val description: String? = null,
    val meta: String? = null,
    /** A website or channel address to look for a feed on, instead of a feed address. */
    val needsFinder: Boolean = false,
)

@Immutable
data class CuratedTopic(
    val name: String,
    val group: String,
    val feeds: List<DiscoverResult>,
)

private val json = Json { ignoreUnknownKeys = true }

private fun JsonElement?.obj(): JsonObject? = this as? JsonObject

private fun JsonElement?.arr(): JsonArray? = this as? JsonArray

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

fun kindOfUrl(url: String): DiscoverKind = if (Regex("youtube\\.com/feeds/videos\\.xml", RegexOption.IGNORE_CASE).containsMatchIn(url)) DiscoverKind.YOUTUBE else DiscoverKind.TEXT

fun siteOf(url: String): String? =
    try {
        URI(url).host?.removePrefix("www.")
    } catch (_: Exception) {
        null
    }

fun parseCurated(text: String): List<CuratedTopic> =
    json
        .parseToJsonElement(text)
        .obj()
        ?.get("topics")
        .arr()
        ?.mapNotNull { t ->
            val o = t.obj() ?: return@mapNotNull null
            CuratedTopic(
                name = o.str("name") ?: return@mapNotNull null,
                group = o.str("group") ?: "",
                feeds =
                    o["feeds"].arr()?.mapNotNull { f ->
                        val fo = f.obj() ?: return@mapNotNull null
                        val u = fo.str("u") ?: return@mapNotNull null
                        DiscoverResult(kind = kindOfUrl(u), title = fo.str("t") ?: u, feedUrl = u, description = fo.str("d"), meta = siteOf(u))
                    } ?: emptyList(),
            )
        } ?: emptyList()

/** Every bundled feed whose name, description or topic has all the words. */
fun searchCurated(
    topics: List<CuratedTopic>,
    query: String,
): List<DiscoverResult> {
    val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (words.isEmpty()) return emptyList()
    val seen = HashSet<String>()
    return topics.flatMap { t ->
        t.feeds.mapNotNull { f ->
            val hay = "${f.title} ${f.description ?: ""} ${f.feedUrl} ${t.name}".lowercase()
            if (f.feedUrl in seen || !words.all { it in hay }) {
                null
            } else {
                seen += f.feedUrl
                f.copy(meta = "${siteOf(f.feedUrl) ?: ""} · ${t.name}")
            }
        }
    }
}

/** 9483 → "9.5K", 21500000 → "22M". */
fun compactNumber(n: Long): String {
    fun fmt(
        v: Double,
        big: Boolean,
    ) = (if (big) String.format(Locale.US, "%.0f", v) else String.format(Locale.US, "%.1f", v)).removeSuffix(".0")
    return when {
        n >= 1_000_000 -> fmt(n / 1e6, n >= 10_000_000) + "M"
        n >= 1_000 -> fmt(n / 1e3, n >= 10_000) + "K"
        else -> n.toString()
    }
}

/** Looks like an address someone pasted rather than words. */
fun looksLikeUrl(q: String): Boolean {
    val s = q.trim()
    return Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(s) || (Regex("^[\\w-]+(\\.[\\w-]+)+(/\\S*)?$").matches(s) && !s.contains(' '))
}

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

// --- Text feeds: Feedly ------------------------------------------------------------------------

fun feedlySearchUrl(query: String) = "https://cloud.feedly.com/v3/search/feeds?query=${enc(query)}&count=30&locale=en"

fun parseFeedly(text: String): List<DiscoverResult> =
    json.parseToJsonElement(text).obj()?.get("results").arr()?.mapNotNull { r ->
        val o = r.obj() ?: return@mapNotNull null
        val id = o.str("feedId") ?: o.str("id") ?: return@mapNotNull null
        if (!id.startsWith("feed/")) return@mapNotNull null
        val feedUrl = id.removePrefix("feed/")
        if (!feedUrl.startsWith("http")) return@mapNotNull null
        val subs = o.num("subscribers")?.toLong() ?: 0
        DiscoverResult(
            kind = kindOfUrl(feedUrl),
            title = o.str("title")?.trim()?.ifBlank { null } ?: siteOf(feedUrl) ?: feedUrl,
            feedUrl = feedUrl,
            site = o.str("website"),
            image = o.str("iconUrl") ?: o.str("visualUrl"),
            description = o.str("description")?.trim()?.ifBlank { null },
            meta = if (subs > 0) "${compactNumber(subs)} Feedly followers" else null,
        )
    } ?: emptyList()

/** Feedly matches words strictly ("mint india" finds nothing, "livemint" does): try joined, then single words. */
fun feedlyQueries(q: String): List<String> {
    val s = q.trim().lowercase()
    val words = s.split(Regex("\\s+")).filter { it.isNotBlank() }
    val out = mutableListOf(s)
    if (words.size > 1) {
        out += words.joinToString("")
        out += words.sortedByDescending { it.length }.filter { it.length >= 4 }
    }
    return out.distinct()
}

/** A Google News search as a feed: follows any topic. */
fun googleNewsResult(
    q: String,
    locale: Locale = Locale.getDefault(),
): DiscoverResult {
    val region = locale.country.ifBlank { "US" }.uppercase()
    val lang = locale.language.ifBlank { "en" }
    val term = q.trim()
    return DiscoverResult(
        kind = DiscoverKind.TEXT,
        title = "Google News: $term",
        feedUrl = "https://news.google.com/rss/search?q=${enc(term)}&hl=$lang-$region&gl=$region&ceid=$region:$lang",
        description = "The latest news articles about “$term”, updated by Google News.",
        meta = "Search feed",
    )
}

// --- Podcasts: Apple ---------------------------------------------------------------------------

val PODCAST_GENRES: List<Pair<Int, String>> =
    listOf(
        0 to "All",
        1489 to "News",
        1321 to "Business",
        1318 to "Technology",
        1533 to "Science",
        1324 to "Society & Culture",
        1304 to "Education",
        1487 to "History",
        1303 to "Comedy",
        1512 to "Health & Fitness",
        1545 to "Sports",
        1301 to "Arts",
        1488 to "True Crime",
        1309 to "TV & Film",
        1310 to "Music",
        1305 to "Kids & Family",
        1314 to "Religion",
        1502 to "Leisure",
        1483 to "Fiction",
    )

val PODCAST_COUNTRIES: List<Pair<String, String>> = listOf("in" to "India", "us" to "US", "gb" to "UK", "au" to "Australia", "ca" to "Canada")

/** The chart country to start with: from the time zone (phones set to English (US) in India), else the locale. */
fun defaultCountry(
    locale: Locale = Locale.getDefault(),
    timeZone: String = java.util.TimeZone.getDefault().id,
): String {
    when (timeZone) {
        "Asia/Kolkata", "Asia/Calcutta" -> return "in"
        "Europe/London" -> return "gb"
    }
    val r = locale.country.lowercase()
    return if (PODCAST_COUNTRIES.any { it.first == r }) r else "us"
}

fun itunesSearchUrl(
    term: String,
    country: String,
) = "https://itunes.apple.com/search?media=podcast&entity=podcast&limit=40&country=$country&term=${enc(term.trim())}"

fun chartUrl(
    genre: Int,
    country: String,
) = "https://itunes.apple.com/$country/rss/toppodcasts/limit=50/${if (genre != 0) "genre=$genre/" else ""}json"

fun lookupUrl(
    ids: List<String>,
    country: String,
) = "https://itunes.apple.com/lookup?entity=podcast&country=$country&id=${ids.joinToString(",")}"

/** Podcasts from an iTunes search or lookup, paired with their Apple ids. */
fun parseItunesWithIds(text: String): List<Pair<String, DiscoverResult>> =
    json.parseToJsonElement(text).obj()?.get("results").arr()?.mapNotNull { r ->
        val o = r.obj() ?: return@mapNotNull null
        val feedUrl = o.str("feedUrl") ?: return@mapNotNull null
        val episodes = o.num("trackCount")?.toLong() ?: 0
        val id = o.num("collectionId")?.toLong()?.toString() ?: ""
        id to
            DiscoverResult(
                kind = DiscoverKind.PODCAST,
                title = o.str("collectionName") ?: feedUrl,
                feedUrl = feedUrl,
                site = o.str("collectionViewUrl"),
                image = o.str("artworkUrl600") ?: o.str("artworkUrl100"),
                meta = listOfNotNull(o.str("artistName"), o.str("primaryGenreName"), if (episodes > 0) "$episodes episodes" else null).joinToString(" · "),
            )
    } ?: emptyList()

fun parseItunes(text: String): List<DiscoverResult> = parseItunesWithIds(text).map { it.second }

/** The podcast ids of an Apple Top chart, best first. */
fun parseChartIds(text: String): List<String> {
    val entry = json.parseToJsonElement(text).obj()?.get("feed").obj()?.get("entry")
    val list = entry.arr()?.toList() ?: listOfNotNull(entry.obj())
    return list.mapNotNull { e -> e.obj()?.get("id").obj()?.get("attributes").obj()?.str("im:id") }
}

// --- YouTube -----------------------------------------------------------------------------------

fun youtubeFeedUrl(channelId: String) = "https://www.youtube.com/feeds/videos.xml?channel_id=$channelId"

fun youtubeChannelId(feedUrl: String): String? = Regex("[?&]channel_id=(UC[\\w-]{22})").find(feedUrl)?.groupValues?.get(1)

/** sp=EgIQAg%3D%3D is YouTube's own "Type: Channel" filter. */
fun youtubeSearchUrl(
    q: String,
    extra: String = "",
) = "https://www.youtube.com/results?search_query=${enc(q.trim())}&sp=EgIQAg%253D%253D$extra"

private fun ytText(e: JsonElement?): String {
    val o = e.obj() ?: return ""
    return o.str("simpleText") ?: o["runs"].arr()?.joinToString("") { it.obj()?.str("text") ?: "" } ?: ""
}

/** Reads the channels out of a youtube.com search page (its ytInitialData). */
fun parseYouTubeChannels(html: String): List<DiscoverResult> {
    val start = html.indexOf("ytInitialData")
    if (start < 0) return emptyList()
    val open = html.indexOf('{', start)
    val end = html.indexOf(";</script>", open)
    if (open < 0 || end < 0) return emptyList()
    val data =
        try {
            json.parseToJsonElement(html.substring(open, end))
        } catch (_: Exception) {
            return emptyList()
        }
    val out = mutableListOf<DiscoverResult>()
    val seen = HashSet<String>()

    fun walk(
        e: JsonElement?,
        depth: Int,
    ) {
        if (depth > 40) return
        when (e) {
            is JsonArray -> e.forEach { walk(it, depth + 1) }
            is JsonObject -> {
                val ch = e["channelRenderer"].obj()
                val id = ch?.str("channelId")
                if (ch != null && id != null) {
                    if (seen.add(id)) {
                        val thumb = ch["thumbnail"].obj()?.get("thumbnails").arr()?.lastOrNull().obj()?.str("url")
                        // YouTube puts the subscriber count in videoCountText and the @handle in subscriberCountText
                        val counts = listOf(ytText(ch["videoCountText"]), ytText(ch["subscriberCountText"])).filter { it.isNotBlank() }
                        val handle = counts.firstOrNull { it.startsWith("@") }
                        val subs = counts.firstOrNull { it.contains("subscriber", ignoreCase = true) }
                        out +=
                            DiscoverResult(
                                kind = DiscoverKind.YOUTUBE,
                                title = ytText(ch["title"]).ifBlank { id },
                                feedUrl = youtubeFeedUrl(id),
                                site = if (handle != null) "https://www.youtube.com/$handle" else "https://www.youtube.com/channel/$id",
                                image = thumb?.let { if (it.startsWith("//")) "https:$it" else it },
                                description = ytText(ch["descriptionSnippet"]).ifBlank { null },
                                meta = listOfNotNull(handle, subs).joinToString(" · ").ifBlank { null },
                            )
                    }
                } else {
                    e.values.forEach { walk(it, depth + 1) }
                }
            }
            else -> Unit
        }
    }
    walk(data, 0)
    return out
}

/** Whether a result is already followed: YouTube by channel id, others by address. */
fun isFollowed(
    r: DiscoverResult,
    followed: Collection<String>,
): Boolean {
    fun norm(u: String) =
        u
            .replace(Regex("^https?://(www\\.)?", RegexOption.IGNORE_CASE), "")
            .removeSuffix("/")
            .lowercase()
    val want = norm(r.feedUrl)
    val ch = youtubeChannelId(r.feedUrl)
    return followed.any { norm(it) == want || (ch != null && youtubeChannelId(it) == ch) }
}
