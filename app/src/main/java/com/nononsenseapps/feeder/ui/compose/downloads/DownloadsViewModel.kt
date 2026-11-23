package com.nononsenseapps.feeder.ui.compose.downloads

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.ExoPlayer
import com.nononsenseapps.feeder.base.DIAwareViewModel
import com.nononsenseapps.feeder.db.room.FeedItemDao
import com.nononsenseapps.feeder.model.offline.DownloadsPlayback
import com.nononsenseapps.feeder.model.offline.DownloadsPlaylist
import com.nononsenseapps.feeder.model.offline.OfflineFiles
import com.nononsenseapps.feeder.model.offline.OfflineVideos
import com.nononsenseapps.feeder.model.offline.PlaylistEntry
import com.nononsenseapps.feeder.model.offline.VideoJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kodein.di.DI
import org.kodein.di.instance
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/** What the Downloads screen shows about a video or episode, from its article. */
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
    /** A still from the saved video, or an episode's cover art: works with no network. */
    val thumbnail: File?,
    val audio: Boolean = false,
)

data class IncompleteDownload(
    val id: Long,
    val info: VideoInfo,
    val job: VideoJob,
)

enum class DownloadsTab { VIDEOS, AUDIO }

data class DownloadsViewState(
    val loaded: Boolean = false,
    val tab: DownloadsTab = DownloadsTab.VIDEOS,
    val videos: List<DownloadedVideo> = emptyList(),
    val episodes: List<DownloadedVideo> = emptyList(),
    val incomplete: List<IncompleteDownload> = emptyList(),
    val playingId: Long? = null,
    val playingAudio: Boolean = false,
    val isPlaying: Boolean = false,
    val autoplay: Boolean = true,
    val loop: Boolean = false,
    val removeWatched: Boolean = false,
    val background: Boolean = true,
) {
    val audioTab: Boolean get() = tab == DownloadsTab.AUDIO

    /** The finished downloads of the open tab: its playlist. */
    val completed: List<DownloadedVideo> get() = if (audioTab) episodes else videos

    /** Unfinished downloads of the open tab. */
    val incompleteHere: List<IncompleteDownload> get() = incomplete.filter { it.job.audio == audioTab }

    /** The item playing, wherever it is. */
    val playing: DownloadedVideo? get() = (if (playingAudio) episodes else videos).firstOrNull { it.id == playingId }
}

/**
 * The Downloads screen: saved videos and podcast episodes (two tabs), each in the user's own
 * order, unfinished downloads with Pause/Resume, and the playlist player ([DownloadsPlayback],
 * which keeps playing in the background when that is on).
 */
class DownloadsViewModel(
    di: DI,
) : DIAwareViewModel(di) {
    private val offlineVideos: OfflineVideos by instance()
    private val offlineFiles: OfflineFiles by instance()
    private val playlist: DownloadsPlaylist by instance()
    private val playback: DownloadsPlayback by instance()
    private val dao: FeedItemDao by instance()

    private val _state =
        MutableStateFlow(
            DownloadsViewState(
                autoplay = playlist.autoplay,
                loop = playlist.loop,
                removeWatched = playlist.removeWatched,
                background = playlist.background,
            ),
        )
    val state: StateFlow<DownloadsViewState> = _state.asStateFlow()

    val player: StateFlow<ExoPlayer?> = playback.player

    /** Read and saved state of every downloaded item, for the rows' Save and Mark read. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val status: StateFlow<Map<Long, com.nononsenseapps.feeder.db.room.ItemStatus>> =
        _state
            .map { s -> (s.videos + s.episodes).map { it.id } }
            .distinctUntilChanged()
            .flatMapLatest { ids -> if (ids.isEmpty()) flowOf(emptyList()) else dao.loadItemStatus(ids) }
            .map { list -> list.associateBy { it.id } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val infoCache = ConcurrentHashMap<Long, VideoInfo>()
    private val durationCache = ConcurrentHashMap<Long, Long>()

    init {
        viewModelScope.launch {
            offlineVideos.changes.collect { reload() }
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
        viewModelScope.launch {
            combine(playback.playingId, playback.playingAudio, playback.isPlaying, playback.background) { id, audio, playing, bg ->
                listOf(id, audio, playing, bg)
            }.collect { (id, audio, playing, bg) ->
                _state.update {
                    it.copy(playingId = id as Long?, playingAudio = audio as Boolean, isPlaying = playing as Boolean, background = bg as Boolean)
                }
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

    private suspend fun reload() {
        val (videos, episodes) =
            withContext(Dispatchers.IO) {
                val saved = offlineVideos.savedIds().mapNotNull { id -> offlineFiles.savedVideo(id)?.let { id to it } }.toMap()
                val videoOrder = playlist.ordered(saved.keys) { DownloadsPlaylist.downloadedAt(saved[it]) }
                val videos =
                    videoOrder.map { id ->
                        val files = saved.getValue(id)
                        DownloadedVideo(
                            id = id,
                            info = info(id),
                            durationMs = duration(id, files.video),
                            sizeBytes = files.sizeBytes,
                            thumbnail = videoThumbnail(id, files.video),
                        )
                    }
                val audio = offlineFiles.savedAudioIds().mapNotNull { id -> offlineFiles.savedAudio(id)?.let { id to it } }.toMap()
                val audioOrder = playlist.ordered(audio.keys, audio = true) { audio[it]?.lastModified() ?: 0L }
                val episodes =
                    audioOrder.map { id ->
                        val file = audio.getValue(id)
                        DownloadedVideo(
                            id = id,
                            info = info(id),
                            durationMs = duration(id, file),
                            sizeBytes = file.length(),
                            thumbnail = coverArt(id, file),
                            audio = true,
                        )
                    }
                videos to episodes
            }
        _state.update { it.copy(loaded = true, videos = videos, episodes = episodes) }
        playback.sync(videos.map(::entry), audio = false)
        playback.sync(episodes.map(::entry), audio = true)
    }

    private fun entry(item: DownloadedVideo) =
        PlaylistEntry(
            id = item.id,
            audio = item.audio,
            title = item.info.title,
            artist = item.info.channel,
            artwork = item.thumbnail?.let(Uri::fromFile) ?: item.info.thumbnailUrl?.let(Uri::parse),
        )

    private fun duration(
        id: Long,
        file: File,
    ): Long =
        durationCache[id] ?: try {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "No duration for $id: ${e.message}")
            0L
        }.also { durationCache[id] = it }

    /** A still from a tenth of the way in, made once and kept next to the video. */
    private fun videoThumbnail(
        id: Long,
        video: File,
    ): File? {
        val file = offlineFiles.videoThumbnail(id)
        if (file.isFile && file.length() > 0) return file
        return try {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(video.absolutePath)
                val atUs = (duration(id, video) * 100).coerceIn(1_000_000L, 60_000_000L)
                val frame =
                    retriever.getScaledFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 270)
                        ?: return null
                writeJpeg(frame, file)
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "No thumbnail for $id: ${e.message}")
            null
        }
    }

    /** The episode's own cover art (ID3/MP4 tags), made once; null when it has none. */
    private fun coverArt(
        id: Long,
        audio: File,
    ): File? {
        val file = offlineFiles.audioThumbnail(id)
        if (file.isFile && file.length() > 0) return file
        return try {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(audio.absolutePath)
                val bytes = retriever.embeddedPicture ?: return null
                val options = BitmapFactory.Options().apply { inSampleSize = 2 }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
                writeJpeg(bitmap, file)
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "No cover art for $id: ${e.message}")
            null
        }
    }

    private fun writeJpeg(
        bitmap: Bitmap,
        file: File,
    ): File {
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        bitmap.recycle()
        return file
    }

    fun setTab(tab: DownloadsTab) = _state.update { it.copy(tab = tab) }

    fun pause(id: Long) = offlineVideos.pause(id)

    fun resume(id: Long) = offlineVideos.resume(id)

    fun retry(id: Long) = offlineVideos.retry(id)

    fun cancel(id: Long) = offlineVideos.cancel(id)

    fun delete(id: Long) {
        viewModelScope.launch { offlineVideos.remove(id) }
    }

    fun deleteAll() {
        viewModelScope.launch {
            playback.stop()
            offlineVideos.removeAll()
        }
    }

    /** New order of the open tab's playlist, after a drag. */
    fun setOrder(ids: List<Long>) {
        val audio = _state.value.audioTab
        playlist.setOrder(ids, audio)
        val byId = _state.value.completed.associateBy { it.id }
        val ordered = ids.mapNotNull(byId::get)
        _state.update { if (audio) it.copy(episodes = ordered) else it.copy(videos = ordered) }
        playback.sync(ordered.map(::entry), audio)
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
        playback.setAutoplay(value)
        _state.update { it.copy(autoplay = value) }
    }

    fun setLoop(value: Boolean) {
        playback.setLoop(value)
        _state.update { it.copy(loop = value) }
    }

    fun setRemoveWatched(value: Boolean) {
        playlist.removeWatched = value
        _state.update { it.copy(removeWatched = value) }
    }

    fun setBackground(value: Boolean) = playback.setBackground(value)

    /** Plays the open tab's downloads in order, starting at [id] (or the first). */
    fun play(id: Long? = null) {
        playback.play(_state.value.completed.map(::entry), id)
    }

    fun closePlayer() = playback.stop()

    fun onAppHidden() = playback.onAppHidden()

    override fun onCleared() {
        playback.onScreenGone()
        super.onCleared()
    }

    companion object {
        private const val LOG_TAG = "FEEDER_DOWNLOADS"
    }
}
