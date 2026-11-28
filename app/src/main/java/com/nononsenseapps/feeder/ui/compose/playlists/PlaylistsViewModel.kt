package com.nononsenseapps.feeder.ui.compose.playlists

import androidx.lifecycle.viewModelScope
import com.nononsenseapps.feeder.archmodel.FeedItemStore
import com.nononsenseapps.feeder.archmodel.Repository
import com.nononsenseapps.feeder.base.DIAwareViewModel
import com.nononsenseapps.feeder.db.room.FeedItemDao
import com.nononsenseapps.feeder.db.room.PlaylistCandidate
import com.nononsenseapps.feeder.model.TTSStateHolder
import com.nononsenseapps.feeder.model.offline.OfflineFiles
import com.nononsenseapps.feeder.model.offline.OfflineVideos
import com.nononsenseapps.feeder.model.playlists.ItemKind
import com.nononsenseapps.feeder.model.playlists.Playlist
import com.nononsenseapps.feeder.model.playlists.PlaylistContext
import com.nononsenseapps.feeder.model.playlists.PlaylistPlayer
import com.nononsenseapps.feeder.model.playlists.PlaylistStore
import com.nononsenseapps.feeder.model.playlists.QueueMessage
import com.nononsenseapps.feeder.model.playlists.QueueState
import com.nononsenseapps.feeder.model.playlists.items
import com.nononsenseapps.feeder.model.playlists.kind
import com.nononsenseapps.feeder.model.playlists.newPlaylist
import com.nononsenseapps.feeder.sharedlinks.SHARED_FEED_URL_STRING
import com.nononsenseapps.feeder.ui.compose.feed.FeedListItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.instance

data class PlaylistsViewState(
    val playlists: List<Playlist> = emptyList(),
    val candidates: List<PlaylistCandidate> = emptyList(),
    val byId: Map<Long, PlaylistCandidate> = emptyMap(),
    val ctx: PlaylistContext = PlaylistContext(),
    val folders: List<String> = emptyList(),
    /** Feed id to title, for the feed picker and rule chips. */
    val feeds: List<Pair<Long, String>> = emptyList(),
    val loaded: Boolean = false,
)

class PlaylistsViewModel(
    di: DI,
) : DIAwareViewModel(di) {
    private val dao: FeedItemDao by instance()
    private val store: PlaylistStore by instance()
    private val player: PlaylistPlayer by instance()
    private val tts: TTSStateHolder by instance()
    private val offlineFiles: OfflineFiles by instance()
    private val offlineVideos: OfflineVideos by instance()
    private val feedItemStore: FeedItemStore by instance()
    private val repository: Repository by instance()
    private val playlistAudio: com.nononsenseapps.feeder.model.playlists.PlaylistAudio by instance()
    private val briefStore: com.nononsenseapps.feeder.model.briefs.PlaylistBriefs by instance()
    private val sync2: com.nononsenseapps.feeder.sync2.Sync2Client by instance()
    private val app: android.app.Application by instance()

    /** Playlist briefs by playlist id, written by Claude Code in Feeder for Mac. */
    val briefs: StateFlow<Map<String, com.nononsenseapps.feeder.model.briefs.StoredBrief>> = briefStore.all

    /** The articles a brief covers: text articles only, in the playlist's order. */
    fun briefArticles(playlist: Playlist): List<PlaylistCandidate> {
        val s = viewState.value
        return playlist
            .items(s.candidates, s.ctx, s.byId)
            .filter { it.kind == ItemKind.ARTICLE }
            .take(com.nononsenseapps.feeder.model.briefs.Brief.MAX_ITEMS)
    }

    /** Asks the Mac for a brief of the playlist's articles; nothing is marked read. Returns how many. */
    fun askBrief(playlist: Playlist): Int {
        val articles = briefArticles(playlist)
        val items = articles.map { com.nononsenseapps.feeder.model.briefs.BriefItem("${it.feedUrl}\n${it.guid}", it.title.ifBlank { "Untitled" }, it.link) }
        briefStore.request(
            playlist.id,
            playlist.name,
            items,
            com.nononsenseapps.feeder.sync2.ThisDevice
                .get(app)
                .name.value,
        )
        return items.size
    }

    /** Looks for the Mac's answer now (while a brief is being made). */
    fun syncForBrief() {
        viewModelScope.launch(Dispatchers.IO) { runCatching { sync2.sync() } }
    }

    val queue: StateFlow<QueueState> = player.state

    /** Articles made ready offline on demand. */
    val offlineAudio: StateFlow<com.nononsenseapps.feeder.model.playlists.PlaylistAudioState> = playlistAudio.state

    /**
     * The next [com.nononsenseapps.feeder.model.playlists.PlaylistAudio.BATCH_SIZE] text articles
     * of [playlist] to make ready offline: after the one playing when this playlist plays, else
     * from the top. Podcasts and videos are left to Downloads.
     */
    fun nextArticlesToPrepare(playlist: Playlist): List<PlaylistCandidate> {
        val s = viewState.value
        val items = playlist.items(s.candidates, s.ctx, s.byId)
        val q = queue.value
        val ordered =
            if (q.playlistId == playlist.id && q.ids.isNotEmpty()) {
                val byId = items.associateBy { it.id }
                q.ids.drop(q.index + 1).mapNotNull { byId[it] }
            } else {
                items
            }
        return ordered.filter { it.kind == ItemKind.ARTICLE }.take(com.nononsenseapps.feeder.model.playlists.PlaylistAudio.BATCH_SIZE)
    }

    fun prepareArticles(playlist: Playlist): Int {
        val ids = nextArticlesToPrepare(playlist).map { it.id }
        if (ids.isNotEmpty()) playlistAudio.start(playlist.id, ids)
        return ids.size
    }

    fun cancelPreparing() {
        viewModelScope.launch(Dispatchers.IO) { playlistAudio.cancel() }
    }

    fun removePrepared() {
        viewModelScope.launch(Dispatchers.IO) { playlistAudio.removeAll() }
    }

    val messages: SharedFlow<QueueMessage> = player.messages

    /** The playlist open on screen, null for the list of playlists. */
    val openId = MutableStateFlow<String?>(null)

    private val downloaded =
        offlineVideos.changes
            .map { (offlineFiles.savedAudioIds() + offlineVideos.savedIds()).toSet() }
            .flowOn(Dispatchers.IO)

    val viewState: StateFlow<PlaylistsViewState> =
        combine(
            dao.loadPlaylistCandidates(),
            store.playlists,
            store.positions,
            downloaded,
            tts.progress.map { it.speed },
        ) { candidates, playlists, positions, down, speed ->
            val feeds =
                candidates
                    .filterNot { it.feedUrl == SHARED_FEED_URL_STRING }
                    .associate { it.feedId to it.feedTitle }
                    .toList()
                    .sortedBy { it.second.lowercase() }
            PlaylistsViewState(
                playlists = playlists,
                candidates = candidates,
                byId = candidates.associateBy { it.id },
                ctx = PlaylistContext(positions = positions, downloaded = down, speed = speed.takeIf { it > 0f } ?: 1f),
                folders =
                    candidates
                        .map { it.feedTag }
                        .filter { it.isNotBlank() }
                        .distinct()
                        .sortedBy { it.lowercase() },
                feeds = feeds,
                loaded = true,
            )
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlaylistsViewState())

    /** The playlist shown as flip pages, null when none is. */
    val flipId = MutableStateFlow<String?>(null)

    // The items of the flipped playlist when flip view opened. Kept as they were, as the feed list
    // keeps what it showed: an item read while flipping stays, instead of the pages shifting under
    // the reader in an Unread playlist.
    private val flipIds = MutableStateFlow<List<Long>?>(null)

    /** The flip pages: [flipId]'s items in playlist order, as the feed list shows them. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val flipItems: StateFlow<List<FeedListItem>?> =
        flipIds
            .flatMapLatest { ids -> if (ids == null) flowOf(null) else feedItemStore.getFeedListItemsByIds(ids) }
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun flip(id: String?) {
        val s = viewState.value
        flipIds.value =
            id?.let { s.playlists.firstOrNull { it.id == id } }?.let { pl ->
                pl.items(s.candidates, s.ctx, s.byId).map { it.id }.take(MAX_FLIP_ITEMS)
            }
        flipId.value = id
        // Read aloud's Up next and the reader's swipes follow the pages
        if (id != null) flipIds.value?.let { openingArticle(id, it) }
    }

    fun open(id: String?) {
        openId.value = id
    }

    /**
     * An article is opened from playlist [playlistId], showing [order]: swiping left and right in
     * the reader goes through the playlist in this order, as it goes through a feed list.
     */
    fun openingArticle(
        playlistId: String,
        order: List<Long>,
    ) = repository.setArticleSwipeList("playlist:$playlistId", order)

    /** The ids of the flip pages, in order. */
    fun flipOrder(): List<Long> = flipIds.value.orEmpty()

    fun play(
        playlist: Playlist,
        start: Int = 0,
        shuffle: Boolean = false,
    ) {
        val s = viewState.value
        var ids = playlist.items(s.candidates, s.ctx, s.byId).map { it.id }
        if (ids.isEmpty()) return
        if (shuffle) ids = ids.shuffled()
        player.start(ids, playlist.name, playlist.id, start)
    }

    fun save(playlist: Playlist) = store.save(playlist)

    fun delete(id: String) {
        store.delete(id)
        if (openId.value == id) openId.value = null
    }

    fun restore(list: List<Playlist>) = store.restore(list)

    fun remove(
        playlistId: String,
        itemId: Long,
    ) = store.remove(playlistId, listOf(itemId))

    fun add(
        playlistId: String,
        itemId: Long,
    ) = store.add(playlistId, listOf(itemId))

    fun move(
        playlistId: String,
        itemId: Long,
        to: Int,
    ) = store.move(playlistId, itemId, to)

    fun newPlaylistDraft(smart: Boolean): Playlist = newPlaylist(if (smart) "New smart playlist" else "New playlist", smart, store.playlists.value)

    /** Saves the YouTube videos and podcast episodes in the list that aren't on the phone yet. */
    fun downloadAll(items: List<PlaylistCandidate>): Int {
        val s = viewState.value
        var n = 0
        viewModelScope.launch(Dispatchers.IO) {
            for (i in items) {
                if (i.id in s.ctx.downloaded) continue
                when (i.kind) {
                    ItemKind.YOUTUBE -> i.link?.let { offlineVideos.save(i.id, it) }
                    ItemKind.PODCAST -> i.enclosureLink?.let { offlineVideos.saveAudio(i.id, it) }
                    ItemKind.ARTICLE -> Unit
                }
            }
        }
        for (i in items) if (i.id !in s.ctx.downloaded && i.kind != ItemKind.ARTICLE) n++
        return n
    }

    // Player controls
    fun toggle() = player.toggle()

    fun next() = player.next()

    fun previous() = player.previous()

    fun seekBy(ms: Long) = player.seekBy(ms)

    fun seekToFraction(f: Float) = player.seekToFraction(f)

    fun jump(index: Int) = player.jump(index)

    fun removeFromQueue(index: Int) = player.remove(index)

    fun stop() = player.stop()

    fun setSpeed(
        speed: Float,
        article: Boolean,
    ) {
        if (article) tts.setSpeed(speed) else player.setAudioSpeed(speed)
    }
}

// SQLite limits the ids in one query; nobody flips through more in one go
private const val MAX_FLIP_ITEMS = 900
