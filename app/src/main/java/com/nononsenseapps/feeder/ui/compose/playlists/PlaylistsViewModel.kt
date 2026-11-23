package com.nononsenseapps.feeder.ui.compose.playlists

import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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

    val queue: StateFlow<QueueState> = player.state
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

    fun open(id: String?) {
        openId.value = id
    }

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
