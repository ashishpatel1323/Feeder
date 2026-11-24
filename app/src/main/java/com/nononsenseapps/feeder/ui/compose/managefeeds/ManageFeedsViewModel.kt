package com.nononsenseapps.feeder.ui.compose.managefeeds

import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.lifecycle.viewModelScope
import com.nononsenseapps.feeder.archmodel.Repository
import com.nononsenseapps.feeder.background.runOnceSyncChainSendRead
import com.nononsenseapps.feeder.base.DIAwareViewModel
import com.nononsenseapps.feeder.db.room.Feed
import com.nononsenseapps.feeder.db.room.FeedDao
import com.nononsenseapps.feeder.db.room.FeedItemDao
import com.nononsenseapps.feeder.sharedlinks.SHARED_FEED_URL_STRING
import com.nononsenseapps.feeder.ui.compose.editfeed.FeedStats
import com.nononsenseapps.feeder.ui.compose.editfeed.computeFeedStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.instance
import java.time.Instant

data class ManageFeedsViewState(
    val loaded: Boolean = false,
    val feeds: List<ManagedFeed> = emptyList(),
    val query: String = "",
    val sort: ManageSort = ManageSort.NAME,
    /** Collapsed folder cards; "" is No folder. */
    val closed: Set<String> = emptySet(),
    val selected: Set<Long> = emptySet(),
) {
    val folders: List<ManagedFolder> = groupManagedFeeds(feeds, query, sort)

    val filtering: Boolean get() = isFiltering(query, sort)

    fun isClosed(tag: String): Boolean = !filtering && tag in closed

    /** Folder names A to Z, without No folder. */
    val allTags: List<String> get() =
        feeds
            .map { it.tag }
            .filter { it.isNotBlank() }
            .distinct()
            .sortedWith(String.CASE_INSENSITIVE_ORDER)

    /** Feeds on screen right now: Select all never picks ones inside a collapsed folder. */
    val visibleIds: List<Long> get() = folders.flatMap { f -> if (isClosed(f.tag)) emptyList() else f.shown.map { it.id } }

    fun feed(id: Long): ManagedFeed? = feeds.firstOrNull { it.id == id }
}

/**
 * The Manage feeds page: every feed by folder, with sorting, quick settings, stats, moving,
 * renaming and deleting. Every change syncs right away.
 */
class ManageFeedsViewModel(
    di: DI,
) : DIAwareViewModel(di) {
    private val repository: Repository by instance()
    private val feedDao: FeedDao by instance()
    private val feedItemDao: FeedItemDao by instance()
    private val prefs: SharedPreferences by instance()

    private val ui =
        MutableStateFlow(
            ManageFeedsViewState(
                closed = prefs.getStringSet(PREF_CLOSED, emptySet())?.toSet() ?: emptySet(),
                sort = prefs.getString(PREF_SORT, null)?.let { s -> ManageSort.entries.firstOrNull { it.name == s } } ?: ManageSort.NAME,
            ),
        )

    private val feedsWithStats =
        combine(
            feedDao.getFlowOfFeedsOrderedByUrl(),
            feedItemDao.loadAllItemsForStats(),
        ) { feeds, rows ->
            val byFeed = rows.groupBy({ it.feedId }, { it.item })
            val now = Instant.now()
            feeds
                // Shared links is a built-in source, not a feed you subscribed to
                .filter { it.url.toString() != SHARED_FEED_URL_STRING }
                .map { it.toManaged(byFeed[it.id]?.let { list -> computeFeedStats(list, now) } ?: FeedStats()) }
        }.flowOn(Dispatchers.Default)

    val viewState: StateFlow<ManageFeedsViewState> =
        combine(feedsWithStats, ui) { feeds, s ->
            val ids = feeds.map { it.id }.toSet()
            s.copy(loaded = true, feeds = feeds, selected = s.selected.filterTo(mutableSetOf()) { it in ids })
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ManageFeedsViewState())

    fun setQuery(q: String) = ui.value.let { ui.value = it.copy(query = q) }

    fun setSort(sort: ManageSort) {
        ui.value = ui.value.copy(sort = sort)
        prefs.edit { putString(PREF_SORT, sort.name) }
    }

    fun toggleFolder(tag: String) = saveClosed(ui.value.closed.let { if (tag in it) it - tag else it + tag })

    fun expandAll() = saveClosed(emptySet())

    fun collapseAll() =
        saveClosed(
            viewState.value.folders
                .map { it.tag }
                .toSet(),
        )

    private fun saveClosed(closed: Set<String>) {
        ui.value = ui.value.copy(closed = closed)
        prefs.edit { putStringSet(PREF_CLOSED, closed) }
    }

    fun toggleSelected(id: Long) = ui.value.let { ui.value = it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id) }

    fun selectAllVisible() = ui.value.let { ui.value = it.copy(selected = it.selected + viewState.value.visibleIds) }

    fun clearSelection() = ui.value.let { ui.value = it.copy(selected = emptySet()) }

    fun setOpensWith(
        id: Long,
        value: String,
    ) = updateFeeds(listOf(id)) { it.copy(openArticlesWith = value) }

    fun setFullText(
        id: Long,
        value: Boolean,
    ) = updateFeeds(listOf(id)) { it.copy(fullTextByDefault = value) }

    fun rename(
        id: Long,
        name: String,
    ) = updateFeeds(listOf(id)) { it.copy(customTitle = name.trim()) }

    /** Moves the feeds to the folder ("" is No folder); returns through [onDone] how many moved. */
    fun moveTo(
        ids: Collection<Long>,
        tag: String,
        onDone: (Int) -> Unit = {},
    ) = viewModelScope.launch {
        val n = updateFeedsNow(ids) { it.copy(tag = tag.trim()) }
        clearSelection()
        onDone(n)
    }

    fun delete(
        ids: Collection<Long>,
        onDone: (Int) -> Unit = {},
    ) = viewModelScope.launch {
        repository.deleteFeeds(ids.toList())
        ui.value = ui.value.copy(selected = ui.value.selected - ids.toSet())
        runOnceSyncChainSendRead(di)
        onDone(ids.size)
    }

    /** Renames a folder; a name that already exists merges the two (as on the web). */
    fun renameFolder(
        from: String,
        to: String,
    ) = viewModelScope.launch {
        val name = to.trim()
        if (name.isEmpty() || name == from) return@launch
        updateFeedsNow(
            viewState.value.feeds
                .filter { it.tag == from }
                .map { it.id },
        ) { it.copy(tag = name) }
        if (from in ui.value.closed) saveClosed(ui.value.closed - from + name)
    }

    /** Deletes a folder: its feeds either move to No folder or are deleted with it. */
    fun deleteFolder(
        tag: String,
        deleteFeeds: Boolean,
    ) = viewModelScope.launch {
        val ids =
            viewState.value.feeds
                .filter { it.tag == tag }
                .map { it.id }
        if (deleteFeeds) {
            repository.deleteFeeds(ids)
            runOnceSyncChainSendRead(di)
        } else {
            updateFeedsNow(ids) { it.copy(tag = "") }
        }
        saveClosed(ui.value.closed - tag)
    }

    private fun updateFeeds(
        ids: Collection<Long>,
        change: (Feed) -> Feed,
    ) = viewModelScope.launch { updateFeedsNow(ids, change) }

    /** Saves the changed feeds with a newer modified time, then syncs. Returns how many changed. */
    private suspend fun updateFeedsNow(
        ids: Collection<Long>,
        change: (Feed) -> Feed,
    ): Int {
        var n = 0
        for (id in ids) {
            val feed = repository.getFeed(id) ?: continue
            val updated = change(feed)
            if (updated == feed) continue
            // In case clocks between devices differ don't let this date go backwards
            updated.whenModified = maxOf(Instant.now(), feed.whenModified.plusMillis(1))
            repository.saveFeed(updated)
            n++
        }
        if (n > 0) runOnceSyncChainSendRead(di)
        return n
    }

    companion object {
        private const val PREF_CLOSED = "manage_feeds_closed_folders"
        private const val PREF_SORT = "manage_feeds_sort"
    }
}

private fun Feed.toManaged(stats: FeedStats) =
    ManagedFeed(
        id = id,
        title = displayTitle.ifBlank { url.toString() },
        url = url.toString(),
        tag = tag,
        imageUrl = imageUrl?.toString(),
        openArticlesWith = openArticlesWith,
        fullTextByDefault = fullTextByDefault,
        lastSync = lastSync.takeIf { it.toEpochMilli() > 0 },
        stats = stats,
    )
