package com.nononsenseapps.feeder.ui.compose.downloads

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import com.nononsenseapps.feeder.base.DIAwareViewModel
import com.nononsenseapps.feeder.db.room.FeedItemDao
import com.nononsenseapps.feeder.model.offline.DownloadsPlaylist
import com.nononsenseapps.feeder.model.offline.OfflineFiles
import com.nononsenseapps.feeder.model.offline.OfflineVideos
import com.nononsenseapps.feeder.model.offline.SavedVideo
import com.nononsenseapps.feeder.model.offline.VideoJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kodein.di.DI
import org.kodein.di.instance
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/** What the Downloads screen shows about a video, from its article. */
data class VideoInfo(
    val title: String,
    val channel: String,
    val thumbnailUrl: String?,
    val link: String?,
)

data class DownloadedVideo(
    val id: Long,
    val info: VideoInfo,
    val durationMs: Long,
    val sizeBytes: Long,
    /** A still from the saved file: works with no network. */
    val thumbnail: File?,
)

data class IncompleteDownload(
    val id: Long,
    val info: VideoInfo,
    val job: VideoJob,
)

data class DownloadsViewState(
    val loaded: Boolean = false,
    val completed: List<DownloadedVideo> = emptyList(),
    val incomplete: List<IncompleteDownload> = emptyList(),
    val playingId: Long? = null,
    val isPlaying: Boolean = false,
    val autoplay: Boolean = true,
    val loop: Boolean = false,
    val removeWatched: Boolean = false,
)

/**
 * The Downloads screen: every saved video in the user's own order, unfinished downloads with
 * Pause/Resume, and a player that plays the downloads one after another as a playlist.
 */
@OptIn(UnstableApi::class)
class DownloadsViewModel(
    di: DI,
) : DIAwareViewModel(di) {
    private val offlineVideos: OfflineVideos by instance()
    private val offlineFiles: OfflineFiles by instance()
    private val playlist: DownloadsPlaylist by instance()
    private val dao: FeedItemDao by instance()

    private val _state =
        MutableStateFlow(
            DownloadsViewState(
                autoplay = playlist.autoplay,
                loop = playlist.loop,
                removeWatched = playlist.removeWatched,
            ),
        )
    val state: StateFlow<DownloadsViewState> = _state.asStateFlow()

    private val _player = MutableStateFlow<ExoPlayer?>(null)
    val player: StateFlow<ExoPlayer?> = _player.asStateFlow()

    private val infoCache = ConcurrentHashMap<Long, VideoInfo>()
    private val durationCache = ConcurrentHashMap<Long, Long>()
    private val savedFiles = ConcurrentHashMap<Long, SavedVideo>()

    /** The id of the video playing before the latest change of video, for Remove after watching. */
    private var lastPlayingId: Long? = null

    init {
        viewModelScope.launch {
            offlineVideos.changes.collect { reloadCompleted() }
        }
        viewModelScope.launch {
            offlineVideos.jobs.collect { jobs ->
                val incomplete =
                    jobs.entries
                        .sortedBy { it.key }
                        .map { (id, job) -> IncompleteDownload(id, info(id), job) }
                _state.update { it.copy(incomplete = incomplete) }
            }
        }
    }

    private suspend fun info(id: Long): VideoInfo =
        infoCache[id] ?: withContext(Dispatchers.IO) {
            val item = dao.loadFeedItemWithFeed(id)
            VideoInfo(
                title = item?.plainTitle ?: "",
                channel = item?.author?.ifBlank { null } ?: item?.feedDisplayTitle ?: "",
                thumbnailUrl = item?.thumbnailImage?.url,
                link = item?.link,
            ).also { if (item != null) infoCache[id] = it }
        }

    private suspend fun reloadCompleted() {
        val videos =
            withContext(Dispatchers.IO) {
                val saved =
                    offlineVideos
                        .savedIds()
                        .mapNotNull { id ->
                            offlineFiles.savedVideo(id)?.let { id to it }
                        }.toMap()
                savedFiles.clear()
                savedFiles.putAll(saved)
                val order = playlist.ordered(saved.keys) { DownloadsPlaylist.downloadedAt(saved[it]) }
                order.map { id ->
                    val files = saved.getValue(id)
                    DownloadedVideo(
                        id = id,
                        info = info(id),
                        durationMs = duration(id, files),
                        sizeBytes = files.sizeBytes,
                        thumbnail = thumbnail(id, files),
                    )
                }
            }
        _state.update { it.copy(loaded = true, completed = videos) }
        syncPlayer()
    }

    private fun duration(
        id: Long,
        files: SavedVideo,
    ): Long =
        durationCache[id] ?: try {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(files.video.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "No duration for $id: ${e.message}")
            0L
        }.also { durationCache[id] = it }

    /** A still from a tenth of the way in, made once and kept next to the video. */
    private fun thumbnail(
        id: Long,
        files: SavedVideo,
    ): File? {
        val file = offlineFiles.videoThumbnail(id)
        if (file.isFile && file.length() > 0) return file
        return try {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(files.video.absolutePath)
                val atUs = (duration(id, files) * 100).coerceIn(1_000_000L, 60_000_000L)
                val frame =
                    retriever.getScaledFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 270)
                        ?: return null
                FileOutputStream(file).use { frame.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                frame.recycle()
                file
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "No thumbnail for $id: ${e.message}")
            null
        }
    }

    fun pause(id: Long) = offlineVideos.pause(id)

    fun resume(id: Long) = offlineVideos.resume(id)

    fun retry(id: Long) = offlineVideos.retry(id)

    fun cancel(id: Long) = offlineVideos.cancel(id)

    fun delete(id: Long) {
        viewModelScope.launch { offlineVideos.remove(id) }
    }

    fun deleteAll() {
        viewModelScope.launch {
            _player.value?.stop()
            offlineVideos.removeAll()
        }
    }

    /** New playlist order, after a drag. */
    fun setOrder(ids: List<Long>) {
        playlist.setOrder(ids)
        val byId = _state.value.completed.associateBy { it.id }
        _state.update { it.copy(completed = ids.mapNotNull(byId::get)) }
        syncPlayer()
    }

    fun move(
        id: Long,
        by: Int,
    ) {
        val ids =
            _state.value.completed
                .map { it.id }
                .toMutableList()
        val from = ids.indexOf(id)
        val to = (from + by).coerceIn(0, ids.lastIndex)
        if (from < 0 || from == to) return
        ids.add(to, ids.removeAt(from))
        setOrder(ids)
    }

    fun setAutoplay(value: Boolean) {
        playlist.autoplay = value
        _player.value?.pauseAtEndOfMediaItems = !value
        _state.update { it.copy(autoplay = value) }
    }

    fun setLoop(value: Boolean) {
        playlist.loop = value
        _player.value?.repeatMode = if (value) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        _state.update { it.copy(loop = value) }
    }

    fun setRemoveWatched(value: Boolean) {
        playlist.removeWatched = value
        _state.update { it.copy(removeWatched = value) }
    }

    /** Plays the downloads in order, starting at [id] (or the first). */
    fun play(id: Long? = null) {
        val ids = _state.value.completed.map { it.id }
        if (ids.isEmpty()) return
        val player = _player.value ?: createPlayer()
        if (playerIds(player) != ids) {
            player.setMediaSources(ids.map(::mediaSource))
        }
        val index = id?.let(ids::indexOf)?.takeIf { it >= 0 } ?: 0
        player.seekTo(index, 0L)
        player.prepare()
        player.play()
    }

    fun closePlayer() {
        _player.value?.release()
        _player.value = null
        _state.update { it.copy(playingId = null, isPlaying = false) }
    }

    private fun createPlayer(): ExoPlayer {
        val player =
            ExoPlayer.Builder(getApplication()).build().apply {
                pauseAtEndOfMediaItems = !_state.value.autoplay
                repeatMode = if (_state.value.loop) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
                addListener(
                    object : Player.Listener {
                        override fun onMediaItemTransition(
                            mediaItem: MediaItem?,
                            reason: Int,
                        ) {
                            val finished = lastPlayingId
                            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && finished != null) {
                                watched(finished)
                            }
                            lastPlayingId = mediaItem?.mediaId?.toLongOrNull()
                            _state.update { it.copy(playingId = lastPlayingId) }
                        }

                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_ENDED) {
                                lastPlayingId?.let(::watched)
                            }
                        }

                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            _state.update { it.copy(isPlaying = isPlaying) }
                        }
                    },
                )
            }
        _player.value = player
        return player
    }

    private fun watched(id: Long) {
        if (_state.value.removeWatched) delete(id)
    }

    private fun mediaSource(id: Long): MediaSource {
        val context = getApplication<android.app.Application>()
        val saved = savedFiles.getValue(id)
        val factory = DefaultMediaSourceFactory(context)
        val videoItem =
            MediaItem
                .Builder()
                .setMediaId(id.toString())
                .setUri(Uri.fromFile(saved.video))
                .setSubtitleConfigurations(
                    saved.subtitles.map { file ->
                        MediaItem.SubtitleConfiguration
                            .Builder(Uri.fromFile(file))
                            .setMimeType(MimeTypes.TEXT_VTT)
                            .setLanguage(file.name.split('.').getOrNull(2) ?: "en")
                            .build()
                    },
                ).build()
        val videoSource = factory.createMediaSource(videoItem)
        return saved.audio?.let { audio ->
            MergingMediaSource(videoSource, factory.createMediaSource(MediaItem.fromUri(Uri.fromFile(audio))))
        } ?: videoSource
    }

    private fun playerIds(player: Player): List<Long> = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).mediaId.toLongOrNull() }

    /** Makes the player's playlist match the list: removed, added and moved videos. */
    private fun syncPlayer() {
        val player = _player.value ?: return
        val wanted = _state.value.completed.map { it.id }
        val have = playerIds(player).toMutableList()
        for (index in have.indices.reversed()) {
            if (have[index] !in wanted) {
                player.removeMediaItem(index)
                have.removeAt(index)
            }
        }
        for (id in wanted) {
            if (id !in have && savedFiles.containsKey(id)) {
                player.addMediaSource(mediaSource(id))
                have.add(id)
            }
        }
        wanted.forEachIndexed { target, id ->
            val from = have.indexOf(id)
            if (from >= 0 && from != target) {
                player.moveMediaItem(from, target)
                have.add(target, have.removeAt(from))
            }
        }
        if (player.mediaItemCount == 0) closePlayer()
    }

    override fun onCleared() {
        _player.value?.release()
        _player.value = null
        super.onCleared()
    }

    companion object {
        private const val LOG_TAG = "FEEDER_DOWNLOADS"
    }
}
