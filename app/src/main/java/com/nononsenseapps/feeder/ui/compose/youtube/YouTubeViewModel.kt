package com.nononsenseapps.feeder.ui.compose.youtube

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.core.content.edit
import androidx.lifecycle.viewModelScope
import com.nononsenseapps.feeder.ApplicationCoroutineScope
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.archmodel.Repository
import com.nononsenseapps.feeder.background.runOnceRssSync
import com.nononsenseapps.feeder.background.runOnceSyncChainSendRead
import com.nononsenseapps.feeder.base.DIAwareViewModel
import com.nononsenseapps.feeder.db.room.Feed
import com.nononsenseapps.feeder.db.room.FeedItemDao
import com.nononsenseapps.feeder.db.room.YouTubeChannel
import com.nononsenseapps.feeder.db.room.YouTubeFeedItem
import com.nononsenseapps.feeder.db.room.YouTubeVideoMeta
import com.nononsenseapps.feeder.db.room.YouTubeVideoMetaDao
import com.nononsenseapps.feeder.model.FeedParser
import com.nononsenseapps.feeder.model.RssLocalSync
import com.nononsenseapps.feeder.model.opml.importOpml
import com.nononsenseapps.feeder.model.youtube.VideoPos
import com.nononsenseapps.feeder.model.youtube.VideoPositions
import com.nononsenseapps.feeder.model.youtube.YouTubeMetaStore
import com.nononsenseapps.feeder.model.youtube.youtubeVideoId
import com.nononsenseapps.feeder.sync2.DeletedArticles
import com.nononsenseapps.feeder.ui.compose.editfeed.FeedStats
import com.nononsenseapps.feeder.ui.compose.editfeed.computeFeedStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.kodein.di.DI
import org.kodein.di.instance
import java.net.URL
import java.time.Instant
import java.time.temporal.ChronoUnit

enum class ChannelSort { NAME, RECENTLY_ADDED, MOST_ACTIVE }

/** A video row: a YouTube feed article with what was looked up about it. */
@Immutable
data class VideoRow(
    val item: YouTubeFeedItem,
    val videoId: String,
    val channel: String,
    val channelAvatar: String?,
    /** Seconds, 0 = not known. */
    val duration: Int,
    val views: Long?,
    val isShort: Boolean,
    val isLive: Boolean,
    /** 0..1 watched, 0 when not started. */
    val progress: Float,
) {
    val articleKey: String get() = VideoPositions.articleKey(item.feedUrl.toString(), item.guid)
}

@Immutable
data class YouTubeViewState(
    val loaded: Boolean = false,
    val channels: List<YouTubeChannel> = emptyList(),
    /** Folders that hold YouTube feeds, A to Z. */
    val groups: List<String> = emptyList(),
    /** null = All. */
    val group: String? = null,
    val grid: Boolean = false,
    val videos: List<VideoRow> = emptyList(),
    val shorts: List<VideoRow> = emptyList(),
    val refreshing: Boolean = false,
    val lastRefreshed: Instant? = null,
    val sort: ChannelSort = ChannelSort.NAME,
) {
    val hasChannels: Boolean get() = channels.isNotEmpty()

    fun channelsShown(
        query: String,
        music: Boolean,
    ): List<YouTubeChannel> =
        channels
            .filter { it.music == music }
            .filter { query.isBlank() || it.displayTitle.contains(query.trim(), ignoreCase = true) }
            .let { list ->
                when (sort) {
                    ChannelSort.NAME -> list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayTitle })
                    ChannelSort.RECENTLY_ADDED -> list.sortedByDescending { it.firstSeen ?: Instant.EPOCH }
                    ChannelSort.MOST_ACTIVE -> list.sortedByDescending { it.recentCount }
                }
            }
}

/**
 * The YouTube tab: Feeder's own YouTube feeds (channel and playlist feeds), shown like Flow's
 * Subscriptions. There is no separate subscription list: subscribing adds a feed, unsubscribing
 * deletes it, and groups are the folders those feeds are in.
 */
class YouTubeViewModel(
    di: DI,
) : DIAwareViewModel(di) {
    private val app: Application by instance()
    private val repository: Repository by instance()
    private val history: com.nononsenseapps.feeder.model.history.CentralHistoryRecorder by instance()
    private val dao: YouTubeVideoMetaDao by instance()
    private val metaStore: YouTubeMetaStore by instance()
    private val videoPositions: VideoPositions by instance()
    private val rssLocalSync: RssLocalSync by instance()
    private val feedParser: FeedParser by instance()
    private val feedItemDao: FeedItemDao by instance()
    private val applicationCoroutineScope: ApplicationCoroutineScope by instance()

    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val saving = Mutex()

    private val local =
        MutableStateFlow(
            Local(
                group = prefs.getString(KEY_GROUP, null),
                grid = prefs.getBoolean(KEY_GRID, false),
                sort = prefs.getString(KEY_SORT, null)?.let { s -> ChannelSort.entries.firstOrNull { it.name == s } } ?: ChannelSort.NAME,
            ),
        )

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private data class Local(
        val group: String?,
        val grid: Boolean,
        val sort: ChannelSort,
        val refreshing: Boolean = false,
    )

    private val since = Instant.now().minus(30, ChronoUnit.DAYS).toEpochMilli()

    val viewState: StateFlow<YouTubeViewState> =
        combine(
            dao.youtubeChannels(since),
            dao.youtubeItems(MAX_ITEMS),
            metaStore.meta,
            videoPositions.all,
            local,
        ) { channels, items, meta, positions, l ->
            build(channels, items, meta, positions, l)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, YouTubeViewState())

    private fun build(
        channels: List<YouTubeChannel>,
        items: List<YouTubeFeedItem>,
        meta: Map<String, YouTubeVideoMeta>,
        positions: Map<String, VideoPos>,
        l: Local,
    ): YouTubeViewState {
        val groups =
            channels
                .map { it.tag }
                .filter { it.isNotBlank() }
                .distinct()
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
        val group = l.group?.takeIf { it in groups }
        val inGroup = if (group == null) null else channels.filter { it.tag == group }.map { it.id }.toSet()
        val byId = channels.associateBy { it.id }
        val rows =
            items
                .filter { inGroup == null || it.feedId in inGroup }
                .mapNotNull { item ->
                    val id = youtubeVideoId(item.link) ?: return@mapNotNull null
                    val m = meta[id]
                    val pos = positions[VideoPositions.articleKey(item.feedUrl.toString(), item.guid)]
                    val duration = m?.duration?.takeIf { it > 0 } ?: pos?.d ?: 0
                    VideoRow(
                        item = item,
                        videoId = id,
                        channel = byId[item.feedId]?.displayTitle ?: item.feedCustomTitle.ifBlank { item.feedTitle },
                        channelAvatar = item.feedImageUrl?.toString(),
                        duration = duration,
                        views = m?.views,
                        isShort = m?.isShort == true || item.link?.contains("/shorts/") == true,
                        isLive = m?.isLive == true,
                        progress =
                            if (pos != null && duration > 0) {
                                if (VideoPositions.isWatched(pos)) 1f else (pos.t.toFloat() / duration).coerceIn(0f, 1f)
                            } else {
                                0f
                            },
                    )
                }
        return YouTubeViewState(
            loaded = true,
            channels = channels,
            groups = groups,
            group = group,
            grid = l.grid,
            videos = rows.filterNot { it.isShort },
            shorts = rows.filter { it.isShort }.take(MAX_SHORTS),
            refreshing = l.refreshing,
            lastRefreshed = channels.maxOfOrNull { it.lastSync }?.takeIf { it.isAfter(Instant.EPOCH) },
            sort = l.sort,
        )
    }

    /** Rows on screen (and a few after): looks up their lengths and views if not known yet. */
    fun onRowsShown(rows: List<VideoRow>) {
        metaStore.want(rows.filter { it.duration == 0 || it.views == null }.map { it.videoId to it.item.link })
    }

    fun selectGroup(group: String?) {
        prefs.edit { putString(KEY_GROUP, group) }
        local.value = local.value.copy(group = group)
    }

    fun toggleGrid() {
        val grid = !local.value.grid
        prefs.edit { putBoolean(KEY_GRID, grid) }
        local.value = local.value.copy(grid = grid)
    }

    fun setSort(sort: ChannelSort) {
        prefs.edit { putString(KEY_SORT, sort.name) }
        local.value = local.value.copy(sort = sort)
    }

    /** Pull to refresh: fetches the YouTube feeds only. */
    fun refresh() {
        if (local.value.refreshing) return
        local.value = local.value.copy(refreshing = true)
        viewModelScope.launch {
            try {
                val ids =
                    viewState.value.channels
                        .map { it.id }
                        .toSet()
                if (ids.isNotEmpty()) {
                    withContext(Dispatchers.IO) { rssLocalSync.syncFeeds(forceNetwork = true, onlyFeedIds = ids) }
                }
            } finally {
                local.value = local.value.copy(refreshing = false)
            }
        }
    }

    fun setMusic(
        feedId: Long,
        music: Boolean,
    ) = updateFeeds(listOf(feedId)) { it.copy(youtubeMusic = music) }

    /** Moves feeds to a folder ("" = no folder), like Manage feeds. */
    fun moveToGroup(
        feedIds: Collection<Long>,
        group: String,
    ) = updateFeeds(feedIds) { it.copy(tag = group.trim()) }

    /** Makes [name] a folder of exactly [feedIds] (a new group, or one whose channels were picked again). */
    fun saveGroup(
        oldName: String?,
        name: String,
        feedIds: Set<Long>,
    ) = viewModelScope.launch {
        val newName = name.trim()
        if (newName.isEmpty()) return@launch
        val channels = viewState.value.channels
        // Channels left out of the group go to no folder
        val leaving = channels.filter { (it.tag == oldName || it.tag == newName) && it.id !in feedIds }.map { it.id }
        updateFeedsNow(leaving) { it.copy(tag = "") }
        updateFeedsNow(feedIds) { it.copy(tag = newName) }
        if (local.value.group == oldName) selectGroup(newName)
    }

    /** Deletes a group: its channels move to no folder (the feeds stay). */
    fun deleteGroup(name: String) =
        viewModelScope.launch {
            updateFeedsNow(
                viewState.value.channels
                    .filter { it.tag == name }
                    .map { it.id },
            ) { it.copy(tag = "") }
            if (local.value.group == name) selectGroup(null)
        }

    /** How many articles go with a feed when it is deleted, for the confirmation. */
    suspend fun statsFor(feedId: Long): FeedStats = withContext(Dispatchers.IO) { computeFeedStats(feedItemDao.loadItemsForStats(feedId)) }

    // The videos are Feeder articles: the same actions as in Feeds, through the same repository calls

    /** Save (Read later) or unsave; synced like a save in Feeds. */
    fun setSaved(
        itemIds: Collection<Long>,
        saved: Boolean,
    ) = applicationCoroutineScope.launch {
        for (id in itemIds) repository.setBookmarked(id, saved)
    }

    /** Marks videos watched (read) or not, synced like in Feeds. */
    fun setRead(
        itemIds: Collection<Long>,
        read: Boolean,
    ) = applicationCoroutineScope.launch {
        for (id in itemIds) {
            if (read) repository.markAsReadAndNotified(id) else repository.markAsUnread(id)
        }
    }

    /** Read above / below: every video listed before (or after) [itemId] in the current list. */
    fun markAboveAsRead(itemId: Long) = setRead(videosAbove(viewState.value.videos, itemId), true)

    fun markBelowAsRead(itemId: Long) = setRead(videosBelow(viewState.value.videos, itemId), true)

    /** Deleted for good on every device, as in Feeds. Returns what Undo needs. */
    suspend fun deleteForGood(itemIds: List<Long>): DeletedArticles = applicationCoroutineScope.async { repository.deleteArticlesForGood(itemIds) }.await()

    fun undoDelete(deleted: DeletedArticles) = applicationCoroutineScope.launch { repository.undoDeleteArticles(deleted) }

    /** Unsubscribe: deletes the feed, as everywhere else in Feeder. */
    fun unsubscribe(feedId: Long) =
        viewModelScope.launch {
            repository.deleteFeeds(listOf(feedId))
            runOnceSyncChainSendRead(di)
        }

    /** Subscribes to a channel given as a link, an @handle or a channel id: adds its feed. */
    fun addChannel(input: String) =
        viewModelScope.launch {
            val feedUrl = withContext(Dispatchers.IO) { resolveChannelFeed(input.trim()) }
            if (feedUrl == null) {
                _messages.tryEmit(app.getString(R.string.youtube_channel_not_found))
                return@launch
            }
            val added = addFeeds(listOf(feedUrl to null))
            _messages.tryEmit(
                if (added > 0) app.getString(R.string.youtube_channel_added) else app.getString(R.string.youtube_channel_already),
            )
        }

    /** OPML (any feeds) or YouTube Takeout's subscriptions.csv. */
    fun import(uri: Uri) =
        viewModelScope.launch {
            val text =
                withContext(Dispatchers.IO) {
                    runCatching { app.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() } }.getOrNull()
                } ?: run {
                    _messages.tryEmit(app.getString(R.string.youtube_import_failed))
                    return@launch
                }
            if (text.trimStart().startsWith("<")) {
                importOpml(di, uri)
                _messages.tryEmit(app.getString(R.string.youtube_import_opml_done))
                return@launch
            }
            val rows = parseTakeoutCsv(text)
            if (rows.isEmpty()) {
                _messages.tryEmit(app.getString(R.string.youtube_import_failed))
                return@launch
            }
            val added = addFeeds(rows.map { (id, title) -> channelFeedUrl(id) to title })
            _messages.tryEmit(app.resources.getQuantityString(R.plurals.youtube_import_done, added, added))
        }

    private suspend fun addFeeds(feeds: List<Pair<URL, String?>>): Int {
        var added = 0
        val tag = local.value.group ?: ""
        val newIds = mutableListOf<Long>()
        for ((url, title) in feeds) {
            if (repository.getFeed(url) != null) continue
            val name =
                title ?: withContext(Dispatchers.IO) {
                    feedParser.parseFeedUrl(url).getOrNull()?.title
                } ?: url.toString()
            newIds += repository.saveFeed(Feed(url = url, title = name, tag = tag, whenModified = Instant.now()))
            // One channel added by hand (not a Takeout import)
            if (feeds.size == 1) history.feedSubscribed(url.toString(), name)
            added++
        }
        if (added > 0) {
            runOnceSyncChainSendRead(di)
            if (newIds.size == 1) {
                runOnceRssSync(di = di, feedId = newIds.first(), forceNetwork = true, triggeredByUser = true)
            } else {
                runOnceRssSync(di = di, forceNetwork = true, triggeredByUser = true)
            }
        }
        return added
    }

    private suspend fun resolveChannelFeed(input: String): URL? {
        if (input.isBlank()) return null
        CHANNEL_ID.find(input)?.let { return channelFeedUrl(it.value) }
        val pageUrl =
            when {
                input.startsWith("@") -> "https://www.youtube.com/$input"
                input.startsWith("http://") || input.startsWith("https://") -> input
                input.contains("youtube.com") -> "https://$input"
                else -> "https://www.youtube.com/@$input"
            }
        val url = runCatching { URL(pageUrl) }.getOrNull() ?: return null
        if (url.path.startsWith("/feeds/videos.xml")) return url
        PLAYLIST_ID.find(url.query ?: "")?.let { return URL("https://www.youtube.com/feeds/videos.xml?playlist_id=${it.groupValues[1]}") }
        return feedParser
            .getSiteMetaData(url)
            .getOrNull()
            ?.alternateFeedLinks
            ?.map { it.link }
            ?.firstOrNull { it.host.endsWith("youtube.com") && it.path.startsWith("/feeds/") }
    }

    private fun updateFeeds(
        ids: Collection<Long>,
        change: (Feed) -> Feed,
    ) = viewModelScope.launch { updateFeedsNow(ids, change) }

    private suspend fun updateFeedsNow(
        ids: Collection<Long>,
        change: (Feed) -> Feed,
    ): Int =
        saving.withLock {
            var n = 0
            for (id in ids) {
                val feed = repository.getFeed(id) ?: continue
                val updated = change(feed)
                if (updated == feed) continue
                updated.whenModified = maxOf(Instant.now(), feed.whenModified.plusMillis(1))
                repository.saveFeed(updated)
                n++
            }
            if (n > 0) runOnceSyncChainSendRead(di)
            n
        }

    companion object {
        /** The ids of the videos listed before [itemId] (none when it isn't listed). */
        fun videosAbove(
            videos: List<VideoRow>,
            itemId: Long,
        ): List<Long> {
            val i = videos.indexOfFirst { it.item.id == itemId }
            return if (i < 0) emptyList() else videos.take(i).map { it.item.id }
        }

        /** The ids of the videos listed after [itemId]. */
        fun videosBelow(
            videos: List<VideoRow>,
            itemId: Long,
        ): List<Long> {
            val i = videos.indexOfFirst { it.item.id == itemId }
            return if (i < 0) emptyList() else videos.drop(i + 1).map { it.item.id }
        }

        private const val PREFS = "youtube_tab"
        private const val KEY_GROUP = "group"
        private const val KEY_GRID = "grid"
        private const val KEY_SORT = "channel_sort"
        private const val MAX_ITEMS = 1500
        private const val MAX_SHORTS = 30
        private val CHANNEL_ID = Regex("UC[-_a-zA-Z0-9]{22}")
        private val PLAYLIST_ID = Regex("(?:^|&)list=([-_a-zA-Z0-9]+)")

        fun channelFeedUrl(channelId: String): URL = URL("https://www.youtube.com/feeds/videos.xml?channel_id=$channelId")

        /**
         * YouTube Takeout's subscriptions.csv: "Channel Id,Channel Url,Channel Title" with a header
         * row. Returns channel id and title.
         */
        fun parseTakeoutCsv(text: String): List<Pair<String, String?>> =
            text
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { line ->
                    val cells = splitCsv(line)
                    val id = cells.firstOrNull { CHANNEL_ID.matches(it.trim()) }?.trim() ?: return@mapNotNull null
                    id to cells.getOrNull(2)?.trim()?.ifBlank { null }
                }.distinctBy { it.first }
                .toList()

        private fun splitCsv(line: String): List<String> {
            val out = mutableListOf<String>()
            val cur = StringBuilder()
            var quoted = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    c == '"' && quoted && line.getOrNull(i + 1) == '"' -> {
                        cur.append('"')
                        i++
                    }
                    c == '"' -> quoted = !quoted
                    c == ',' && !quoted -> {
                        out += cur.toString()
                        cur.clear()
                    }
                    else -> cur.append(c)
                }
                i++
            }
            out += cur.toString()
            return out
        }
    }
}
