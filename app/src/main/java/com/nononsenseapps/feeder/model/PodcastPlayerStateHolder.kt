package com.nononsenseapps.feeder.model

import android.app.Application
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.runtime.Immutable
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.util.logDebug
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PodcastPlayerStateHolder(
    private val application: Application,
    private val coroutineScope: CoroutineScope,
) {
    private val _playerState = MutableStateFlow(PodcastPlayerState())
    val playerState: StateFlow<PodcastPlayerState> = _playerState.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null
    private var startAtMillis: Int = 0
    private var speed: Float = 1f
    private var sleepJob: Job? = null

    /**
     * Gets (item id, seconds played, length in seconds) now and then while an episode plays, and
     * when it pauses, stops or ends: the same positions playlists keep, so every list shows where
     * an episode was left.
     */
    var positionSink: ((Long, Long, Long) -> Unit)? = null

    /**
     * Plays [link]; [itemId] is the article it belongs to (for its transcript and its saved
     * position). Starts [startAtSeconds] in (Resume).
     */
    fun playLink(
        link: String,
        itemId: Long? = null,
        startAtSeconds: Long = 0L,
    ) {
        if (link.isBlank()) {
            return
        }

        reportPosition()
        releaseCurrentPlayer()
        startAtMillis = (startAtSeconds * 1000).toInt().coerceAtLeast(0)
        _playerState.value =
            PodcastPlayerState(
                audioUrl = link,
                itemId = itemId,
                isVisible = true,
                isLoading = true,
                speed = speed,
                sleepEndsAtMillis = _playerState.value.sleepEndsAtMillis,
            )

        initializePlayer(link)
    }

    fun play() {
        val player = mediaPlayer ?: return
        player.start()
        applySpeed(player)
        startProgressUpdates()
        _playerState.update { it.copy(isPlaying = true) }
    }

    /** Playback speed, kept for the next episode too. Applied when playing (setting it while paused would start playback). */
    fun setSpeed(value: Float) {
        speed = value.coerceIn(0.5f, 3f)
        _playerState.update { it.copy(speed = speed) }
        val player = mediaPlayer ?: return
        if (_playerState.value.isPlaying) applySpeed(player)
    }

    /** Pauses after [minutes]; null or 0 turns the timer off. */
    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        sleepJob = null
        if (minutes == null || minutes <= 0) {
            _playerState.update { it.copy(sleepEndsAtMillis = null) }
            return
        }
        val endsAt = System.currentTimeMillis() + minutes * 60_000L
        _playerState.update { it.copy(sleepEndsAtMillis = endsAt) }
        sleepJob =
            coroutineScope.launch {
                delay(minutes * 60_000L)
                pause()
                _playerState.update { it.copy(sleepEndsAtMillis = null) }
            }
    }

    private fun applySpeed(player: MediaPlayer) {
        runCatching { player.playbackParams = player.playbackParams.setSpeed(speed) }
    }

    fun pause() {
        mediaPlayer?.pause()
        reportPosition()
        stopProgressUpdates()
        _playerState.update { it.copy(isPlaying = false) }
    }

    fun stop() {
        sleepJob?.cancel()
        sleepJob = null
        reportPosition()
        releaseCurrentPlayer()
        _playerState.value = PodcastPlayerState(speed = speed)
    }

    fun seekBy(deltaMillis: Int) {
        val player = mediaPlayer ?: return
        val currentState = _playerState.value
        if (!currentState.canSeek) {
            return
        }

        val targetPosition = (player.currentPosition + deltaMillis).coerceIn(0, player.duration.coerceAtLeast(0))
        player.seekTo(targetPosition)
        _playerState.update { it.copy(positionMillis = targetPosition) }
    }

    fun seekTo(positionMillis: Int) {
        val player = mediaPlayer ?: return
        val currentState = _playerState.value
        if (!currentState.canSeek) {
            return
        }

        val targetPosition = positionMillis.coerceIn(0, player.duration.coerceAtLeast(0))
        player.seekTo(targetPosition)
        _playerState.update { it.copy(positionMillis = targetPosition) }
    }

    fun shutdown() {
        releaseCurrentPlayer()
    }

    private fun initializePlayer(link: String) {
        coroutineScope.launch {
            runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes
                            .Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build(),
                    )
                    setOnPreparedListener { player ->
                        if (startAtMillis > 0 && startAtMillis < player.duration) player.seekTo(startAtMillis)
                        player.start()
                        applySpeed(player)
                        _playerState.update {
                            it.copy(
                                isLoading = false,
                                isBuffering = false,
                                isPlaying = true,
                                canSeek = player.duration > 0,
                                durationMillis = player.duration.coerceAtLeast(0),
                                errorMessage = null,
                            )
                        }
                        startProgressUpdates()
                    }
                    setOnCompletionListener { player ->
                        stopProgressUpdates()
                        _playerState.value.itemId?.let { id ->
                            val len = player.duration.coerceAtLeast(0) / 1000L
                            positionSink?.invoke(id, len, len)
                        }
                        _playerState.update {
                            it.copy(
                                isPlaying = false,
                                isBuffering = false,
                                positionMillis = player.duration.coerceAtLeast(0),
                            )
                        }
                    }
                    setOnInfoListener { _, what, _ ->
                        when (what) {
                            MediaPlayer.MEDIA_INFO_BUFFERING_START -> {
                                _playerState.update { it.copy(isBuffering = true) }
                                true
                            }

                            MediaPlayer.MEDIA_INFO_BUFFERING_END -> {
                                _playerState.update { it.copy(isBuffering = false) }
                                true
                            }

                            else -> false
                        }
                    }
                    setOnErrorListener { _, what, extra ->
                        logDebug(LOG_TAG, "Podcast playback failed: $what / $extra")
                        stopProgressUpdates()
                        _playerState.update {
                            it.copy(
                                isLoading = false,
                                isBuffering = false,
                                isPlaying = false,
                                errorMessage = application.getString(R.string.audio_playback_failed),
                            )
                        }
                        true
                    }

                    setDataSource(application, Uri.parse(link))
                    prepareAsync()
                }
            }.onSuccess { player ->
                mediaPlayer = player
            }.onFailure { error ->
                logDebug(LOG_TAG, "Failed to initialize podcast player: ${error.message}")
                _playerState.update {
                    it.copy(
                        isLoading = false,
                        isBuffering = false,
                        isPlaying = false,
                        errorMessage = application.getString(R.string.audio_playback_failed),
                    )
                }
            }
        }
    }

    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob =
            coroutineScope.launch {
                var ticks = 0
                while (isActive) {
                    val player = mediaPlayer ?: break
                    _playerState.update {
                        it.copy(
                            positionMillis = player.currentPosition.coerceAtLeast(0),
                            durationMillis = player.duration.coerceAtLeast(0),
                        )
                    }
                    // Often enough for transcript lines to change on time
                    delay(250)
                    if (++ticks % 20 == 0) reportPosition()
                }
            }
    }

    /** Hands where the episode is to [positionSink]. */
    private fun reportPosition() {
        val id = _playerState.value.itemId ?: return
        val player = mediaPlayer ?: return
        runCatching {
            val pos = player.currentPosition.coerceAtLeast(0) / 1000L
            val len = player.duration.coerceAtLeast(0) / 1000L
            if (pos > 0) positionSink?.invoke(id, pos, len)
        }
    }

    private fun stopProgressUpdates() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun releaseCurrentPlayer() {
        stopProgressUpdates()
        mediaPlayer?.runCatching {
            stop()
        }
        mediaPlayer?.release()
        mediaPlayer = null
    }

    companion object {
        private const val LOG_TAG = "FEEDER_PODCAST"
    }
}

@Immutable
data class PodcastPlayerState(
    val audioUrl: String = "",
    val itemId: Long? = null,
    val isVisible: Boolean = false,
    val isLoading: Boolean = false,
    val isBuffering: Boolean = false,
    val isPlaying: Boolean = false,
    val canSeek: Boolean = false,
    val durationMillis: Int = 0,
    val positionMillis: Int = 0,
    val errorMessage: String? = null,
    val speed: Float = 1f,
    // Wall-clock time the sleep timer pauses at; null = off
    val sleepEndsAtMillis: Long? = null,
)
