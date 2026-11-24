package com.nononsenseapps.feeder.ui.compose.managefeeds

import com.nononsenseapps.feeder.ui.compose.editfeed.FeedStats

/**
 * Sorts of the Manage feeds page, the same as the web app's (web/src/components/ManageFeeds.tsx).
 * The web's "Folder order" is a hand-made order kept in the browser; Android has no hand-made
 * order, so it is A to Z here. The web's "Errors" needs the last fetch error, which Android
 * does not store.
 */
enum class ManageSort {
    NAME,
    MOST_ACTIVE,
    LEAST_ACTIVE,
    MOST_UNREAD,
    READ_LEAST,
    STALE,
}

/** One feed row of Manage feeds. */
data class ManagedFeed(
    val id: Long,
    val title: String,
    val url: String,
    val tag: String,
    val imageUrl: String?,
    val openArticlesWith: String,
    val fullTextByDefault: Boolean,
    val lastSync: java.time.Instant?,
    val stats: FeedStats,
)

/** A folder card: every feed in it ([all]) and the ones the search and sort show ([shown]). */
data class ManagedFolder(
    /** "" is No folder. */
    val tag: String,
    val all: List<ManagedFeed>,
    val shown: List<ManagedFeed>,
) {
    val unread: Int get() = all.sumOf { it.stats.unread }
}

/** A search or a filtering sort opens every folder so no match is hidden (as on the web). */
fun isFiltering(
    query: String,
    sort: ManageSort,
): Boolean = query.isNotBlank() || sort == ManageSort.STALE

private val byTitle = compareBy<ManagedFeed, String>(String.CASE_INSENSITIVE_ORDER) { it.title }

/** Read share of the last 30 days; feeds with nothing new go last when sorting by Read the least. */
private fun ManagedFeed.readRate(): Double = if (stats.recent > 0) stats.recentRead.toDouble() / stats.recent else 2.0

/**
 * Folder cards A to Z with No folder last. With a sort other than Name the cards stay and feeds
 * are sorted inside each one; Not updated in 30 days only keeps feeds with nothing new.
 * Folders with nothing to show while searching or filtering are left out.
 */
fun groupManagedFeeds(
    feeds: List<ManagedFeed>,
    query: String,
    sort: ManageSort,
): List<ManagedFolder> {
    val q = query.trim().lowercase()
    val cmp: Comparator<ManagedFeed> =
        when (sort) {
            ManageSort.NAME -> byTitle
            ManageSort.MOST_ACTIVE -> compareByDescending<ManagedFeed> { it.stats.rate }.then(byTitle)
            ManageSort.LEAST_ACTIVE -> compareBy<ManagedFeed> { it.stats.rate }.then(byTitle)
            ManageSort.MOST_UNREAD -> compareByDescending<ManagedFeed> { it.stats.unread }.then(byTitle)
            ManageSort.READ_LEAST -> compareBy<ManagedFeed> { it.readRate() }.then(byTitle)
            ManageSort.STALE -> compareBy<ManagedFeed> { it.stats.lastArticle?.toEpochMilli() ?: 0L }.then(byTitle)
        }
    val keep = { f: ManagedFeed ->
        (q.isEmpty() || f.title.lowercase().contains(q) || f.url.lowercase().contains(q)) &&
            (sort != ManageSort.STALE || f.stats.recent == 0)
    }
    val filtering = isFiltering(query, sort)
    val byTag = feeds.groupBy { it.tag }
    val tags = byTag.keys.filter { it.isNotBlank() }.sortedWith(String.CASE_INSENSITIVE_ORDER) + listOfNotNull("".takeIf { it in byTag })
    return tags.mapNotNull { tag ->
        val all = byTag[tag].orEmpty().sortedWith(byTitle)
        val shown = all.filter(keep).sortedWith(cmp)
        if (filtering && shown.isEmpty()) null else ManagedFolder(tag, all, shown)
    }
}
