package com.nononsenseapps.feeder.ui.compose.home

import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.lifecycle.viewModelScope
import com.nononsenseapps.feeder.archmodel.Repository
import com.nononsenseapps.feeder.background.runOnceRssSync
import com.nononsenseapps.feeder.base.DIAwareViewModel
import com.nononsenseapps.feeder.db.room.FeedDao
import com.nononsenseapps.feeder.sharedlinks.SHARED_FEED_URL_STRING
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val options =
        MutableStateFlow(
            Triple(
                enumPref(PREF_GROUP, HomeGroup.FOLDERS),
                enumPref(PREF_SORT, HomeSort.LATEST),
                enumPref(PREF_SIZE, HomeSize.M),
            ),
        )

    val viewState: StateFlow<HomeViewState> =
        combine(
            // Shared links is a built-in source, not a feed you subscribed to
            feedDao.loadHomeFeeds().map { rows -> rows.filter { it.url != SHARED_FEED_URL_STRING }.map { it.toHomeFeed() } },
            filter,
            openFolder,
            options,
        ) { feeds, f, folder, (group, sort, size) ->
            HomeViewState(
                loaded = true,
                feeds = feeds,
                filter = f,
                // A folder that was renamed or emptied elsewhere closes
                openFolder = folder?.takeIf { name -> feeds.any { it.tag == name } },
                group = group,
                sort = sort,
                size = size,
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

    fun setGroup(v: HomeGroup) = saveOptions(options.value.copy(first = v))

    fun setSort(v: HomeSort) = saveOptions(options.value.copy(second = v))

    fun setSize(v: HomeSize) = saveOptions(options.value.copy(third = v))

    fun refreshAll() {
        runOnceRssSync(di = di, forceNetwork = true, triggeredByUser = true)
    }

    private fun saveOptions(o: Triple<HomeGroup, HomeSort, HomeSize>) {
        options.value = o
        prefs.edit {
            putString(PREF_GROUP, o.first.name)
            putString(PREF_SORT, o.second.name)
            putString(PREF_SIZE, o.third.name)
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
    }
}
