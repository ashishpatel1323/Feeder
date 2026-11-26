package com.nononsenseapps.feeder.model.youtube

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.runtime.Immutable
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import com.nononsenseapps.feeder.ApplicationCoroutineScope
import com.nononsenseapps.feeder.background.runOnceSyncChainSendRead
import com.nononsenseapps.feeder.model.offline.OfflineVideos
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance

enum class SubtitleSize(
    val scale: Float,
) {
    SMALL(0.8f),
    NORMAL(1f),
    LARGE(1.3f),
    HUGE(1.6f),
}

/** The player choices that carry over to the next video. */
@Immutable
data class PlayerPrefs(
    val quality: Int = 720,
    val speed: Float = 1f,
    val subtitlesOn: Boolean = false,
    val subtitleLanguage: String = "",
    val subtitleSize: SubtitleSize = SubtitleSize.NORMAL,
    val subtitleBackground: Boolean = true,
    val sponsorBlock: Boolean = true,
)

/** What is playing now in the Flow-style player. */
@Immutable
data class PlaybackState(
    /** The article (or "embed:<id>" for a video inside an article's text). */
    val key: String,
    val videoId: String,
    val articleKey: String,
    val itemId: Long,
    val title: String,
    val details: VideoDetails,
    /** Height of the video stream playing; 0 = the stream with picture and sound. */
    val height: Int,
    val audio: AudioStream?,
    val subtitle: SubtitleTrack?,
    val speed: Float,
    /**
     * Keeps playing (sound) with the screen off or in other apps, with lock-screen and
     * notification controls. On for every video; its Background button turns it off for one.
     */
    val background: Boolean = true,
    /** When the sleep timer pauses: epoch ms, [SLEEP_AT_END] or 0 = off. */
    val sleepAt: Long = 0,
    val segments: List<SkipSegment> = emptyList(),
) {
    val isHd: Boolean get() = height >= 720

    companion object {
        const val SLEEP_AT_END = -1L
    }
}

/**
 * The YouTube player behind the reader, like Flow's: the video's own streams (no ads) in one
 * ExoPlayer that outlives the screen, so it can keep playing in the background. Remembers where
 * each article's video was left ([VideoPositions], synced) and skips SponsorBlock segments.
 * Main thread only.
 */
@OptIn(UnstableApi::class)
class YouTubePlayback(
    override val di: DI,
) : DIAware {
    private val app: Application by instance()
    private val videoPositions: VideoPositions by instance()
    private val offlineVideos: OfflineVideos by instance()
    private val sponsorBlock: SponsorBlock by instance()
    private val youTubeStreams: YouTubeStreams by instance()
    private val applicationCoroutineScope: ApplicationCoroutineScope by instance()

    private val prefs by lazy { app.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val _prefs = MutableStateFlow(PlayerPrefs())
    private var prefsLoaded = false
    val playerPrefs: StateFlow<PlayerPrefs>
        get() {
            loadPrefs()
            return _prefs.asStateFlow()
        }

    private val _player = MutableStateFlow<ExoPlayer?>(null)
    val player: StateFlow<ExoPlayer?> = _player.asStateFlow()

    private val _state = MutableStateFlow<PlaybackState?>(null)
    val state: StateFlow<PlaybackState?> = _state.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Keys whose streams would not play: their reader shows YouTube's own player instead. */
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private val _skipped = MutableSharedFlow<SkipSegment>(extraBufferCapacity = 4)

    /** A SponsorBlock segment was just skipped. */
    val skipped: SharedFlow<SkipSegment> = _skipped.asSharedFlow()

    private var reporter: PlaybackReporter? = null
    private var tick: Job? = null
    private var attached: String? = null
    private var serviceStarted = false
    private val skippedSegments = mutableSetOf<String>()

    /**
     * Plays [details] for [key] from [start] seconds, or picks up the same key when it is still
     * playing (back to an article that kept playing in the background).
     */
    fun open(
        key: String,
        videoId: String,
        articleKey: String,
        itemId: Long,
        title: String,
        details: VideoDetails,
        start: Int,
    ) {
        attached = key
        if (_state.value?.key == key && _player.value != null) return
        stop()
        loadPrefs()
        val p = _prefs.value
        val video = details.videoFor(p.quality)
        val subtitle = if (p.subtitlesOn) pickSubtitle(details, p.subtitleLanguage) else null
        val initial =
            PlaybackState(
                key = key,
                videoId = videoId,
                articleKey = articleKey,
                itemId = itemId,
                title = title,
                details = details,
                height = if (details.audios.isNotEmpty()) video?.height ?: 0 else 0,
                audio = details.audios.firstOrNull(),
                subtitle = subtitle,
                speed = p.speed,
                background = true,
            )
        YouTubeTiming.log(videoId, "player prepare (${details.source})")
        val exo =
            ExoPlayer
                .Builder(app)
                .apply { if (!YouTubeStreams.slowMode()) setLoadControl(fastStartLoadControl()) }
                .setSeekBackIncrementMs(SEEK_MS)
                .setSeekForwardIncrementMs(SEEK_MS)
                .build()
                .apply {
                    setAudioAttributes(
                        AudioAttributes
                            .Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                            .build(),
                        true,
                    )
                    setHandleAudioBecomingNoisy(true)
                    setWakeMode(C.WAKE_MODE_NETWORK)
                    setPlaybackSpeed(p.speed)
                    setMediaSource(buildSource(initial), start * 1000L)
                    prepare()
                }
        exo.addListener(listener(exo))
        reporter =
            PlaybackReporter { t, d, stopped ->
                if (articleKey.isNotEmpty()) {
                    videoPositions.save(articleKey, t, d)
                    // Sent when it stops, even if a save while playing already wrote this position
                    if (stopped) runOnceSyncChainSendRead(di)
                }
            }
        skippedSegments.clear()
        _state.value = initial
        _player.value = exo
        tick = applicationCoroutineScope.launch(Dispatchers.Main) { tickLoop(exo) }
        if (p.sponsorBlock) {
            applicationCoroutineScope.launch(Dispatchers.Main) {
                val segments = sponsorBlock.segments(videoId)
                _state.update { if (it?.key == key) it.copy(segments = segments) else it }
            }
        }
        if (details.fromInnerTube) standBy(key, videoId)
    }

    /**
     * yt-dlp's details, asked for once the video plays: what YouTube's player endpoint leaves out
     * (chapters without timestamps in the description, followers, download qualities), and
     * streams to carry on with if YouTube stops serving the fast ones mid-video.
     */
    private var standby: Deferred<VideoDetails?>? = null

    private fun standBy(
        key: String,
        videoId: String,
    ) {
        standby?.cancel()
        standby =
            applicationCoroutineScope.async(Dispatchers.Main) {
                // Not while the video starts: Python takes a lot of the phone for seconds
                delay(STANDBY_DELAY_MS)
                val full =
                    try {
                        offlineVideos.videoDetails(youtubeWatchUrl(videoId))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(LOG_TAG, "yt-dlp standby failed for $videoId: ${e.message}")
                        null
                    }
                YouTubeTiming.log(videoId, "yt-dlp standby ${if (full != null) "ready" else "failed"}")
                if (full != null) {
                    _state.update { s ->
                        if (s?.key != key) return@update s
                        s.copy(
                            details =
                                s.details.copy(
                                    chapters = s.details.chapters.ifEmpty { full.chapters },
                                    channelFollowers = s.details.channelFollowers ?: full.channelFollowers,
                                    likeCount = s.details.likeCount ?: full.likeCount,
                                    uploadDate = s.details.uploadDate ?: full.uploadDate,
                                    downloadOptions = full.downloadOptions,
                                ),
                        )
                    }
                }
                full
            }
    }

    /**
     * The fast streams stopped (YouTube refuses them after a while for some visitors): carries on
     * where it was with yt-dlp's. False when there is nothing to carry on with.
     */
    private fun recover(
        exo: ExoPlayer,
        current: PlaybackState,
    ): Boolean {
        if (!current.details.fromInnerTube) return false
        val pending = standby ?: return false
        youTubeStreams.forget(current.videoId)
        val position = exo.currentPosition
        YouTubeTiming.log(current.videoId, "fast streams failed at ${position / 1000} s: switching to yt-dlp's")
        // Skip the standby's wait: it is needed now
        applicationCoroutineScope.launch(Dispatchers.Main) {
            val full =
                if (pending.isActive && !pending.isCompleted) {
                    pending.cancel()
                    try {
                        offlineVideos.videoDetails(youtubeWatchUrl(current.videoId))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                } else {
                    runCatching { pending.await() }.getOrNull()
                }
            val now = _state.value
            if (now?.key != current.key || _player.value !== exo) return@launch
            if (full == null || !full.hasStreams) {
                _errors.tryEmit(current.key)
                stop()
                return@launch
            }
            val p = _prefs.value
            val video = full.videoFor(p.quality)
            val next =
                now.copy(
                    details =
                        full.copy(
                            chapters = full.chapters.ifEmpty { now.details.chapters },
                            likeCount = full.likeCount ?: now.details.likeCount,
                            uploadDate = full.uploadDate ?: now.details.uploadDate,
                        ),
                    height = if (full.audios.isNotEmpty()) video?.height ?: 0 else 0,
                    audio = full.audios.firstOrNull { it.language == now.audio?.language } ?: full.audios.firstOrNull(),
                )
            _state.value = next
            // playWhenReady stays as it is now: a sleep timer may have paused it meanwhile
            exo.setMediaSource(buildSource(next), position)
            exo.prepare()
        }
        return true
    }

    private var keepKey: String? = null

    /**
     * The YouTube tab's video page collapses into the mini player: [key] keeps playing (or stays
     * paused, ready) when its page goes away next.
     */
    fun keepOnClose(key: String) {
        keepKey = key
    }

    /** The reader's player went away: stops, unless it plays in the background. */
    fun close(key: String) {
        if (attached == key) attached = null
        if (keepKey == key) {
            keepKey = null
            return
        }
        val current = _state.value ?: return
        if (current.key != key) return
        if (current.background && _player.value?.playWhenReady == true) return
        stop()
    }

    /** The app went to the background: pauses, unless background play is on. */
    fun onAppPaused(key: String) {
        val current = _state.value ?: return
        if (current.key == key && !current.background) _player.value?.pause()
    }

    fun stop() {
        val exo = _player.value
        if (exo != null) {
            if (exo.isPlaying) reporter?.playing(exo.currentPosition / 1000.0, exo.durationSeconds())
            reporter?.stop()
            exo.release()
        }
        tick?.cancel()
        tick = null
        standby?.let { pending ->
            // Its yt-dlp run would slow the next video's start
            if (pending.isActive) _state.value?.let { offlineVideos.stopDetails(youtubeWatchUrl(it.videoId)) }
            pending.cancel()
        }
        standby = null
        reporter = null
        _player.value = null
        stopService()
        _state.value = null
    }

    fun setQuality(height: Int) {
        savePrefs(playerPrefs.value.copy(quality = height))
        val current = _state.value ?: return
        val video = current.details.videoFor(height) ?: return
        rebuild(current.copy(height = video.height))
    }

    fun setAudio(audio: AudioStream) {
        val current = _state.value ?: return
        rebuild(current.copy(audio = audio))
    }

    fun setSubtitle(track: SubtitleTrack?) {
        savePrefs(playerPrefs.value.copy(subtitlesOn = track != null, subtitleLanguage = track?.language ?: playerPrefs.value.subtitleLanguage))
        val current = _state.value ?: return
        rebuild(current.copy(subtitle = track))
    }

    fun setSpeed(speed: Float) {
        savePrefs(playerPrefs.value.copy(speed = speed))
        _player.value?.setPlaybackSpeed(speed)
        _state.update { it?.copy(speed = speed) }
    }

    fun setSubtitleStyle(
        size: SubtitleSize,
        background: Boolean,
    ) = savePrefs(playerPrefs.value.copy(subtitleSize = size, subtitleBackground = background))

    fun setSponsorBlock(on: Boolean) {
        savePrefs(playerPrefs.value.copy(sponsorBlock = on))
        val current = _state.value ?: return
        if (on && current.segments.isEmpty()) {
            applicationCoroutineScope.launch(Dispatchers.Main) {
                val segments = sponsorBlock.segments(current.videoId)
                _state.update { if (it?.key == current.key) it.copy(segments = segments) else it }
            }
        }
    }

    fun setBackground(on: Boolean) {
        val current = _state.value ?: return
        if (current.background == on) return
        _state.value = current.copy(background = on)
        if (on) {
            if (_player.value?.isPlaying == true) startService()
        } else {
            stopService()
        }
    }

    /**
     * Shows the player to Android (notification, lock screen) once it plays with background play
     * on. Started from a tap in the app (play, or the Background button): allowed on every Android.
     */
    private fun startService() {
        if (serviceStarted) return
        serviceStarted = true
        app.startService(Intent(app, VideoPlaybackService::class.java))
    }

    /** [minutes] from now, [PlaybackState.SLEEP_AT_END], or 0 = off. */
    fun setSleepTimer(minutes: Int) {
        val at =
            when {
                minutes > 0 -> System.currentTimeMillis() + minutes * 60_000L
                minutes == SLEEP_END_OF_VIDEO -> PlaybackState.SLEEP_AT_END
                else -> 0L
            }
        _state.update { it?.copy(sleepAt = at) }
    }

    fun seekTo(seconds: Double) {
        val exo = _player.value ?: return
        exo.seekTo((seconds * 1000).toLong().coerceAtLeast(0))
    }

    private fun stopService() {
        serviceStarted = false
        app.stopService(Intent(app, VideoPlaybackService::class.java))
    }

    private fun rebuild(next: PlaybackState) {
        val exo = _player.value ?: return
        val position = exo.currentPosition
        val playWhenReady = exo.playWhenReady
        _state.value = next
        exo.setMediaSource(buildSource(next), position)
        exo.prepare()
        exo.playWhenReady = playWhenReady
    }

    /**
     * Starts playing once 0.75 s is buffered instead of ExoPlayer's 2.5 s, like Flow; after a
     * stall it waits for 2 s so it doesn't stutter.
     */
    private fun fastStartLoadControl() =
        DefaultLoadControl
            .Builder()
            .setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                START_BUFFER_MS,
                REBUFFER_MS,
            ).setPrioritizeTimeOverSizeThresholds(true)
            .build()

    private fun buildSource(state: PlaybackState): MediaSource {
        val details = state.details
        val http =
            DefaultHttpDataSource
                .Factory()
                .setAllowCrossProtocolRedirects(true)
                .apply { details.userAgent?.let { setUserAgent(it) } }
        val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(app, http))
        val metadata =
            MediaMetadata
                .Builder()
                .setTitle(state.title.ifBlank { details.title })
                .setArtist(details.channel)
                .apply { details.thumbnail?.let { setArtworkUri(it.toUri()) } }
                .build()
        val subtitles =
            listOfNotNull(
                state.subtitle?.let {
                    MediaItem.SubtitleConfiguration
                        .Builder(it.url.toUri())
                        .setMimeType(MimeTypes.TEXT_VTT)
                        .setLanguage(it.language)
                        .setLabel(it.label)
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                },
            )
        val video = if (state.height > 0) details.videoFor(state.height) else null
        val audio = state.audio ?: details.audios.firstOrNull()
        return if (video != null && audio != null) {
            MergingMediaSource(
                factory.createMediaSource(
                    MediaItem
                        .Builder()
                        .setMediaId(state.videoId)
                        .setUri(video.url)
                        .setMediaMetadata(metadata)
                        .setSubtitleConfigurations(subtitles)
                        .build(),
                ),
                factory.createMediaSource(MediaItem.fromUri(audio.url)),
            )
        } else {
            factory.createMediaSource(
                MediaItem
                    .Builder()
                    .setMediaId(state.videoId)
                    .setUri(checkNotNull(details.muxedUrl ?: details.videos.firstOrNull()?.url))
                    .setMediaMetadata(metadata)
                    .setSubtitleConfigurations(subtitles)
                    .build(),
            )
        }
    }

    private fun listener(exo: ExoPlayer) =
        object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && _state.value?.background == true) startService()
                if (!isPlaying && exo.playbackState != Player.STATE_ENDED) {
                    reporter?.paused(exo.currentPosition / 1000.0, exo.durationSeconds())
                }
            }

            private var firstFrame = false

            override fun onRenderedFirstFrame() {
                if (firstFrame) return
                firstFrame = true
                _state.value?.let { YouTubeTiming.log(it.videoId, "first frame") }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && !firstFrame) {
                    _state.value?.let { YouTubeTiming.log(it.videoId, "ready to play") }
                }
                if (playbackState == Player.STATE_ENDED) {
                    reporter?.ended(exo.durationSeconds())
                    _state.update { if (it?.sleepAt == PlaybackState.SLEEP_AT_END) it.copy(sleepAt = 0) else it }
                    // Nobody is looking at it: done
                    if (attached == null) stop()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val current = _state.value ?: return
                Log.w(LOG_TAG, "Stream failed for ${current.videoId}", error)
                if (recover(exo, current)) return
                offlineVideos.forgetDetails(youtubeWatchUrl(current.videoId))
                _errors.tryEmit(current.key)
                stop()
            }
        }

    private suspend fun tickLoop(exo: ExoPlayer) {
        while (true) {
            delay(TICK_MS)
            if (_player.value !== exo) return
            val current = _state.value ?: return
            if (exo.isPlaying) {
                val t = exo.currentPosition / 1000.0
                reporter?.playing(t, exo.durationSeconds())
                if (_prefs.value.sponsorBlock) {
                    current.segments
                        .firstOrNull { t >= it.start && t < it.end - 0.5 && it.uuid !in skippedSegments }
                        ?.let { segment ->
                            // Once: seeking back into it plays it
                            skippedSegments += segment.uuid
                            exo.seekTo((segment.end * 1000).toLong())
                            _skipped.tryEmit(segment)
                        }
                }
            }
            if (current.sleepAt > 0 && System.currentTimeMillis() >= current.sleepAt) {
                exo.pause()
                _state.update { it?.copy(sleepAt = 0) }
            }
        }
    }

    private fun pickSubtitle(
        details: VideoDetails,
        language: String,
    ): SubtitleTrack? =
        details.subtitles.firstOrNull { it.language == language && !it.automatic }
            ?: details.subtitles.firstOrNull { it.language.removeSuffix("-orig") == language }
            ?: details.subtitles.firstOrNull { !it.automatic }
            ?: details.subtitles.firstOrNull()

    @Synchronized
    private fun loadPrefs() {
        if (prefsLoaded) return
        prefsLoaded = true
        _prefs.value =
            PlayerPrefs(
                quality = prefs.getInt("quality", 720),
                speed = prefs.getFloat("speed", 1f),
                subtitlesOn = prefs.getBoolean("subtitles_on", false),
                subtitleLanguage = prefs.getString("subtitle_language", "") ?: "",
                subtitleSize = SubtitleSize.entries.firstOrNull { it.name == prefs.getString("subtitle_size", null) } ?: SubtitleSize.NORMAL,
                subtitleBackground = prefs.getBoolean("subtitle_background", true),
                sponsorBlock = prefs.getBoolean("sponsorblock", true),
            )
    }

    private fun savePrefs(p: PlayerPrefs) {
        loadPrefs()
        _prefs.value = p
        prefs
            .edit()
            .putInt("quality", p.quality)
            .putFloat("speed", p.speed)
            .putBoolean("subtitles_on", p.subtitlesOn)
            .putString("subtitle_language", p.subtitleLanguage)
            .putString("subtitle_size", p.subtitleSize.name)
            .putBoolean("subtitle_background", p.subtitleBackground)
            .putBoolean("sponsorblock", p.sponsorBlock)
            .apply()
    }

    companion object {
        private const val LOG_TAG = "FEEDER_YOUTUBE"
        private const val PREFS = "youtube_player"
        private const val TICK_MS = 250L
        private const val MIN_BUFFER_MS = 15_000
        private const val MAX_BUFFER_MS = 50_000
        private const val START_BUFFER_MS = 750
        private const val REBUFFER_MS = 2_000
        private const val STANDBY_DELAY_MS = 8_000L
        const val SEEK_MS = 10_000L
        const val SLEEP_END_OF_VIDEO = -1
    }
}

internal fun Player.durationSeconds(): Double = duration.takeIf { it > 0 }?.let { it / 1000.0 } ?: 0.0

/** Decides when a playing position is worth saving: every few seconds, and when it stops. */
internal class PlaybackReporter(
    private val report: (t: Double, d: Double, stopped: Boolean) -> Unit,
) {
    private var last: Pair<Double, Double>? = null
    private var lastSaved = 0L

    fun playing(
        t: Double,
        d: Double,
    ) {
        last = t to d
        val now = System.currentTimeMillis()
        if (now - lastSaved >= SAVE_EVERY_MS) {
            lastSaved = now
            report(t, d, false)
        }
    }

    fun paused(
        t: Double,
        d: Double,
    ) {
        if (last != null) last = t to d
        stop()
    }

    fun ended(d: Double) {
        last = d to d
        stop()
    }

    /** Writes down where it stopped, once: a later stop must not overwrite a newer position from another device. */
    fun stop() {
        val l = last ?: return
        last = null
        lastSaved = System.currentTimeMillis()
        report(l.first, l.second, true)
    }

    companion object {
        const val SAVE_EVERY_MS = 5000L
    }
}
