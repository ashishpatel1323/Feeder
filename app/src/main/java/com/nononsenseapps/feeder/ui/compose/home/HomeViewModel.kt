package com.nononsenseapps.feeder.ui.compose.home

import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.lifecycle.viewModelScope
import com.nononsenseapps.feeder.archmodel.Repository
import com.nononsenseapps.feeder.background.runOnceRssSync
import com.nononsenseapps.feeder.base.DIAwareViewModel
import com.nononsenseapps.feeder.db.room.FeedDao
import com.nononsenseapps.feeder.sharedlinks.SHARED_FEED_URL_STRING
import com.nononsenseapps.feeder.db.room.ID_UNSET
import com.nononsenseapps.feeder.model.playlists.ItemProgress
import com.nononsenseapps.feeder.model.playlists.PlaylistStore
import com.nononsenseapps.feeder.model.youtube.VideoPositions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.kodein.di.DI
import org.kodein.di.instance

data class HomeViewState(
    val loaded: Boolean = false,
    val feeds: List<HomeFeed> = emptyList(),
    val filter: HomeFilter = HomeFilter(),
    val openFolder: String? = null,
    val group: HomeGroup = HomeGroup.FOLDERS,
    val sort: HomeSort = HomeSort.LATEST,
    val size: HomeSize = HomeSize.M,
    val layout: HomeLayout = HomeLayout.TILES,
    /** Folders folded in the list layout. */
    val folded: Set<String> = emptySet(),
) {
    /** The feeds the counts and filters are about: one folder's while it is open. */
    val scope: List<HomeFeed> get() = if (openFolder == null) feeds else feeds.filter { it.tag == openFolder }

    private val activeFilter: HomeFilter get() = if (openFolder == null) filter else filter.copy(folder = null)

    val shown: List<HomeFeed> get() = scope.filterHome(activeFilter)

    fun kindCount(kind: FeedKind?): Int = scope.filterHome(activeFilter, skipKind = true).count { kind == null || it.kind == kind }

    val unreadCount: Int get() = scope.filterHome(activeFilter, skipUnread = true).count { it.unread > 0 }

    /** Folder name → feeds it would show; "" is feeds in no folder. Every folder keeps its chip. */
    val folderCounts: List<Pair<String, Int>>
        get() {
            val matching = feeds.filterHome(filter, skipFolder = true).groupingBy { it.tag }.eachCount()
            val names = feeds.map { it.tag }.distinct()
            return names
                .filter { it.isNotBlank() }
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
                .map { it to (matching[it] ?: 0) } +
                if ("" in names) listOf("" to (matching[""] ?: 0)) else emptyList()
        }

    /** Folder tiles only at the top level, grouped by folder, with no folder chip picked. */
    val asFolders: Boolean get() = group == HomeGroup.FOLDERS && openFolder == null && filter.folder == null
}

private data class HomeOptions(
    val group: HomeGroup,
    val sort: HomeSort,
    val size: HomeSize,
    val layout: HomeLayout,
    val folded: Set<String>,
)

class HomeViewModel(
    di: DI,
) : DIAwareViewModel(di) {
    private val feedDao: FeedDao by instance()
    private val prefs: SharedPreferences by instance()
    private val repository: Repository by instance()

    /** True while feeds sync: the spinner of pull-to-refresh. */
    val syncing: StateFlow<Boolean> = repository.currentlySyncing.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val filter = MutableStateFlow(HomeFilter())
    private val openFolder = MutableStateFlow<String?>(null)
    private val playlistStore: PlaylistStore by instance()
    private val videoPositions: VideoPositions by instance()

    private val options =
        MutableStateFlow(
            HomeOptions(
                group = enumPref(PREF_GROUP, HomeGroup.FOLDERS),
                sort = enumPref(PREF_SORT, HomeSort.LATEST),
                size = enumPref(PREF_SIZE, HomeSize.M),
                layout = enumPref(PREF_LAYOUT, HomeLayout.TILES),
                folded = prefs.getStringSet(PREF_FOLDED, null)?.toSet() ?: emptySet(),
            ),
        )

    private val homeFeeds =
        // Shared links is a built-in source, not a feed you subscribed to
        feedDao.loadHomeFeeds().map { rows -> rows.filter { it.url != SHARED_FEED_URL_STRING }.map { it.toHomeFeed() } }

    /** Feed id → episodes and videos started but not finished. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val inProgress: Flow<Map<Long, Int>> =
        combine(homeFeeds, playlistStore.positions, videoPositions.all) { feeds, positions, videos -> Triple(feeds, positions, videos) }
            .mapLatest { (feeds, positions, videos) ->
                val counts = mutableMapOf<Long, Int>()
                val started =
                    positions
                        .filter { (_, p) -> ItemProgress(p.first, p.second).partlyPlayed }
                        .keys
                        .toList()
                for (chunk in started.chunked(500)) {
                    for (row in feedDao.loadItemFeedIds(chunk)) counts.merge(row.feedId, 1, Int::plus)
                }
                val byUrl = feeds.associate { it.url to it.id }
                for ((key, v) in videos) {
                    if (!ItemProgress(v.t.toLong(), v.d.toLong()).partlyPlayed) continue
                    val feedId = byUrl[key.substringBefore('\n')] ?: continue
                    counts.merge(feedId, 1, Int::plus)
                }
                counts as Map<Long, Int>
            }.onStart { emit(emptyMap()) }
            .flowOn(Dispatchers.IO)

    val viewState: StateFlow<HomeViewState> =
        combine(
            // Shared links is a built-in source, not a feed you subscribed to
            homeFeeds,
            filter,
            openFolder,
            options,
            inProgress,
        ) { rawFeeds, f, folder, o, progress ->
            val feeds = if (progress.isEmpty()) rawFeeds else rawFeeds.map { it.copy(inProgress = progress[it.id] ?: 0) }
            HomeViewState(
                loaded = true,
                feeds = feeds,
                filter = f,
                // A folder that was renamed or emptied elsewhere closes
                openFolder = folder?.takeIf { name -> feeds.any { it.tag == name } },
                group = o.group,
                sort = o.sort,
                size = o.size,
                layout = o.layout,
                folded = o.folded,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeViewState())

    fun setKind(kind: FeedKind?) = filter.value.let { filter.value = it.copy(kind = kind) }

    fun toggleFolderFilter(name: String) = filter.value.let { filter.value = it.copy(folder = if (it.folder == name) null else name) }

    fun toggleUnreadOnly() = filter.value.let { filter.value = it.copy(unreadOnly = !it.unreadOnly) }

    fun clearFilters() {
        filter.value = HomeFilter()
    }

    fun openFolder(name: String?) {
        openFolder.value = name
    }

    fun setGroup(v: HomeGroup) = saveOptions(options.value.copy(group = v))

    fun setSort(v: HomeSort) = saveOptions(options.value.copy(sort = v))

    fun setSize(v: HomeSize) = saveOptions(options.value.copy(size = v))

    fun setLayout(v: HomeLayout) = saveOptions(options.value.copy(layout = v))

    fun toggleFolded(name: String) =
        options.value.let { saveOptions(it.copy(folded = if (name in it.folded) it.folded - name else it.folded + name)) }

    fun setAllFolded(
        names: Collection<String>,
        fold: Boolean,
    ) = saveOptions(options.value.copy(folded = if (fold) names.toSet() else emptySet()))

    fun markFolderRead(name: String) {
        viewModelScope.launch { repository.markAllAsReadInFeedOrTag(ID_UNSET, name) }
    }

    fun refreshAll() {
        runOnceRssSync(di = di, forceNetwork = true, triggeredByUser = true)
    }

    private fun saveOptions(o: HomeOptions) {
        options.value = o
        prefs.edit {
            putString(PREF_GROUP, o.group.name)
            putString(PREF_SORT, o.sort.name)
            putString(PREF_SIZE, o.size.name)
            putString(PREF_LAYOUT, o.layout.name)
            putStringSet(PREF_FOLDED, o.folded)
        }
    }

    private inline fun <reified T : Enum<T>> enumPref(
        key: String,
        default: T,
    ): T = prefs.getString(key, null)?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default

    companion object {
        private const val PREF_GROUP = "home_group"
        private const val PREF_SORT = "home_sort"
        private const val PREF_SIZE = "home_size"
        private const val PREF_LAYOUT = "home_layout"
        private const val PREF_FOLDED = "home_folded"
    }
}
