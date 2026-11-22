package com.nononsenseapps.feeder.model.offline

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import com.nononsenseapps.feeder.ApplicationCoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance

/** One saved video or podcast episode in the Downloads playlist, with what the notification shows. */
data class PlaylistEntry(
    val id: Long,
    val audio: Boolean,
    val title: String,
    val artist: String,
    val artwork: Uri?,
)

/**
 * The Downloads playlist player. It belongs to the app, not to the Downloads screen, so it keeps
 * playing after leaving the screen, with the screen off or in other apps when Background play is
 * on. [DownloadsPlaybackService] shows it to Android (notification and lock-screen controls).
 *
 * Videos and podcast episodes are separate playlists; [playingAudio] says which one is playing.
 */
@OptIn(UnstableApi::class)
class DownloadsPlayback(
    override val di: DI,
) : DIAware {
    private val app: Application by instance()
    private val offlineFiles: OfflineFiles by instance()
    private val offlineVideos: OfflineVideos by instance()
    private val playlist: DownloadsPlaylist by instance()
    private val applicationCoroutineScope: ApplicationCoroutineScope by instance()

    private val _player = MutableStateFlow<ExoPlayer?>(null)
    val player: StateFlow<ExoPlayer?> = _player.asStateFlow()

    private val _playingId = MutableStateFlow<Long?>(null)
    val playingId: StateFlow<Long?> = _playingId.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playingAudio = MutableStateFlow(false)
    val playingAudio: StateFlow<Boolean> = _playingAudio.asStateFlow()

    private val _background = MutableStateFlow(playlist.background)
    val background: StateFlow<Boolean> = _background.asStateFlow()

    private var lastPlayingId: Long? = null

    /** Plays [entries] in order from [startId] (or the first). */
    fun play(
        entries: List<PlaylistEntry>,
        startId: Long?,
    ) {
        if (entries.isEmpty()) return
        val audio = entries.first().audio
        val player = _player.value ?: createPlayer()
        if (_playingAudio.value != audio || playerIds(player) != entries.map { it.id }) {
            _playingAudio.value = audio
            player.setMediaSources(entries.mapNotNull(::mediaSource))
        }
        val index = startId?.let { id -> entries.indexOfFirst { it.id == id } }?.takeIf { it >= 0 } ?: 0
        player.seekTo(index, 0L)
        player.prepare()
        player.play()
        if (_background.value) startService()
    }

    /** Makes the playing playlist match the list on screen: removed, added and moved items. */
    fun sync(
        entries: List<PlaylistEntry>,
        audio: Boolean,
    ) {
        val player = _player.value ?: return
        if (_playingAudio.value != audio) return
        val wanted = entries.map { it.id }
        val have = playerIds(player).toMutableList()
        for (index in have.indices.reversed()) {
            if (have[index] !in wanted) {
                player.removeMediaItem(index)
                have.removeAt(index)
            }
        }
        for (entry in entries) {
            if (entry.id !in have) {
                mediaSource(entry)?.let {
                    player.addMediaSource(it)
                    have.add(entry.id)
                }
            }
        }
        wanted.forEachIndexed { target, id ->
            val from = have.indexOf(id)
            if (from >= 0 && from != target) {
                player.moveMediaItem(from, target)
                have.add(target, have.removeAt(from))
            }
        }
        if (player.mediaItemCount == 0) stop()
    }

    fun setAutoplay(on: Boolean) {
        playlist.autoplay = on
        _player.value?.pauseAtEndOfMediaItems = !on
    }

    fun setLoop(on: Boolean) {
        playlist.loop = on
        _player.value?.repeatMode = if (on) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
    }

    fun setBackground(on: Boolean) {
        playlist.background = on
        _background.value = on
        if (on && _player.value != null) {
            startService()
        } else if (!on) {
            stopService()
        }
    }

    /** The Downloads screen went away: playback ends unless Background play is on. */
    fun onScreenGone() {
        if (!_background.value) stop()
    }

    /** The app went to the background: pauses unless Background play is on. */
    fun onAppHidden() {
        if (!_background.value) _player.value?.pause()
    }

    fun stop() {
        stopService()
        _player.value?.release()
        _player.value = null
        _playingId.value = null
        _isPlaying.value = false
        lastPlayingId = null
    }

    private fun startService() {
        app.startService(Intent(app, DownloadsPlaybackService::class.java))
    }

    private fun stopService() {
        app.stopService(Intent(app, DownloadsPlaybackService::class.java))
    }

    private fun createPlayer(): ExoPlayer {
        val player =
            ExoPlayer
                .Builder(app)
                // Other players (YouTube, read aloud, podcast apps) pause while this plays
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    true,
                ).setHandleAudioBecomingNoisy(true)
                .build()
                .apply {
                    pauseAtEndOfMediaItems = !playlist.autoplay
                    repeatMode = if (playlist.loop) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
                    addListener(
                        object : Player.Listener {
                            override fun onMediaItemTransition(
                                mediaItem: MediaItem?,
                                reason: Int,
                            ) {
                                val finished = lastPlayingId
                                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && finished != null) watched(finished)
                                lastPlayingId = mediaItem?.mediaId?.toLongOrNull()
                                _playingId.value = lastPlayingId
                            }

                            override fun onPlaybackStateChanged(playbackState: Int) {
                                if (playbackState == Player.STATE_ENDED) lastPlayingId?.let(::watched)
                            }

                            override fun onIsPlayingChanged(isPlaying: Boolean) {
                                _isPlaying.value = isPlaying
                            }
                        },
                    )
                }
        _player.value = player
        return player
    }

    private fun watched(id: Long) {
        if (playlist.removeWatched) applicationCoroutineScope.launch { offlineVideos.remove(id) }
    }

    private fun mediaSource(entry: PlaylistEntry): MediaSource? {
        val factory = DefaultMediaSourceFactory(app)
        val metadata =
            MediaMetadata
                .Builder()
                .setTitle(entry.title)
                .setArtist(entry.artist)
                .setArtworkUri(entry.artwork)
                .build()
        if (entry.audio) {
            val file = offlineFiles.savedAudio(entry.id) ?: return null
            return factory.createMediaSource(
                MediaItem
                    .Builder()
                    .setMediaId(entry.id.toString())
                    .setUri(Uri.fromFile(file))
                    .setMediaMetadata(metadata)
                    .build(),
            )
        }
        val saved = offlineFiles.savedVideo(entry.id) ?: return null
        val videoItem =
            MediaItem
                .Builder()
                .setMediaId(entry.id.toString())
                .setUri(Uri.fromFile(saved.video))
                .setMediaMetadata(metadata)
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
}
