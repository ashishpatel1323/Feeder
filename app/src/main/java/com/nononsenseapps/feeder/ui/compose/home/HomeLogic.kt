package com.nononsenseapps.feeder.ui.compose.home

import com.nononsenseapps.feeder.db.room.HomeFeedRow
import java.net.URI

/** What a feed carries, for Home's type filter. */
enum class FeedKind { PODCAST, YOUTUBE, TEXT }

enum class HomeGroup { FOLDERS, NONE, TYPE }

enum class HomeSort { LATEST, AZ, UNREAD }

enum class HomeSize { S, M, L }

data class HomeFeed(
    val id: Long,
    val title: String,
    val tag: String,
    val imageUrl: String?,
    val kind: FeedKind,
    val unread: Int,
    val latest: Long,
)

data class HomeFolder(
    val name: String,
    val feeds: List<HomeFeed>,
) {
    val unread: Int get() = feeds.sumOf { it.unread }
    val latest: Long get() = feeds.maxOfOrNull { it.latest } ?: 0L
}

data class HomeFilter(
    val kind: FeedKind? = null,
    /** null = any folder, "" = feeds in no folder. */
    val folder: String? = null,
    val unreadOnly: Boolean = false,
)

fun isYouTubeFeedUrl(url: String): Boolean =
    try {
        val host = URI(url).host?.removePrefix("www.") ?: ""
        host == "youtube.com" || host.endsWith(".youtube.com")
    } catch (_: Exception) {
        false
    }

/** YouTube channel feeds, podcasts (mostly audio attachments), and everything else as text. */
fun detectKind(row: HomeFeedRow): FeedKind =
    when {
        isYouTubeFeedUrl(row.url) -> FeedKind.YOUTUBE
        row.total == 0 -> FeedKind.TEXT
        row.audioItems > 0 && row.audioItems * 2 >= row.total -> FeedKind.PODCAST
        row.videoItems * 2 > row.total -> FeedKind.YOUTUBE
        else -> FeedKind.TEXT
    }

fun HomeFeedRow.toHomeFeed(): HomeFeed =
    HomeFeed(
        id = id,
        title = displayTitle.ifBlank { url },
        tag = tag,
        imageUrl = imageUrl?.takeIf { isTileImage(it) },
        kind = detectKind(this),
        unread = unread,
        latest = latest,
    )

/** Site favicons (.ico) are tiny and look smudged on a tile: those get the generated tile instead. */
fun isTileImage(url: String): Boolean = url.isNotBlank() && !url.substringBefore('?').endsWith(".ico", ignoreCase = true)

/** Applies the filter, leaving out the parts named in [skip] (so a chip can count what it would show). */
fun List<HomeFeed>.filterHome(
    f: HomeFilter,
    skipKind: Boolean = false,
    skipFolder: Boolean = false,
    skipUnread: Boolean = false,
): List<HomeFeed> =
    filter {
        (skipKind || f.kind == null || it.kind == f.kind) &&
            (skipFolder || f.folder == null || it.tag == f.folder) &&
            (skipUnread || !f.unreadOnly || it.unread > 0)
    }

private val byTitle = compareBy<HomeFeed, String>(String.CASE_INSENSITIVE_ORDER) { it.title }

fun List<HomeFeed>.sortHome(sort: HomeSort): List<HomeFeed> =
    when (sort) {
        HomeSort.AZ -> sortedWith(byTitle)
        HomeSort.UNREAD -> sortedWith(compareByDescending<HomeFeed> { it.unread }.then(byTitle))
        HomeSort.LATEST -> sortedWith(compareByDescending<HomeFeed> { it.latest }.then(byTitle))
    }

/** Folders (each sorted inside), then the feeds in no folder. */
fun List<HomeFeed>.groupByFolder(sort: HomeSort): Pair<List<HomeFolder>, List<HomeFeed>> {
    val (inFolder, loose) = partition { it.tag.isNotBlank() }
    val folders =
        inFolder
            .groupBy { it.tag }
            .map { (name, feeds) -> HomeFolder(name, feeds.sortHome(sort)) }
            .let { list ->
                val byName = compareBy<HomeFolder, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
                when (sort) {
                    HomeSort.AZ -> list.sortedWith(byName)
                    HomeSort.UNREAD -> list.sortedWith(compareByDescending<HomeFolder> { it.unread }.then(byName))
                    HomeSort.LATEST -> list.sortedWith(compareByDescending<HomeFolder> { it.latest }.then(byName))
                }
            }
    return folders to loose.sortHome(sort)
}

private val SMALL_WORDS = setOf("the", "a", "an", "of", "on", "and", "www")

/** Two letters for a feed with no artwork ("Hacker News" → HN). */
fun initials(title: String): String {
    val words =
        title
            .replace(Regex("https?://"), "")
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotBlank() && it.lowercase() !in SMALL_WORDS }
    return when {
        words.isEmpty() ->
            title
                .trim()
                .take(1)
                .uppercase()
                .ifEmpty { "?" }
        words.size == 1 -> words[0].take(2).uppercase()
        else -> "${words[0].first()}${words[1].first()}".uppercase()
    }
}

/** Background and text colors (ARGB) for a generated tile; same title, same colors. Matches the web app. */
private val TILE_COLORS =
    listOf(
        0xFF2F6F5E to 0xFFC9F0DE,
        0xFF7A3E2B to 0xFFFFD9C7,
        0xFF3B4A8C to 0xFFD6DEFC,
        0xFF6B3F7A to 0xFFF1D6FB,
        0xFF8A6D1F to 0xFFFFF0BF,
        0xFF22606E to 0xFFC6EEF6,
        0xFF7D2F4F to 0xFFFFD3E3,
        0xFF455A2A to 0xFFE1F3C4,
    )

fun tileColors(title: String): Pair<Long, Long> {
    var h = 0L
    // Same hash as the web app (32-bit unsigned, over UTF-16 code units)
    for (c in title) h = (h * 31 + c.code) and 0xFFFFFFFFL
    return TILE_COLORS[(h % TILE_COLORS.size).toInt()]
}
