package com.nononsenseapps.feeder.model.playlists

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.ui.text.AnnotatedString
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.nononsenseapps.feeder.ApplicationCoroutineScope
import com.nononsenseapps.feeder.archmodel.Repository
import com.nononsenseapps.feeder.model.PlaybackStatus
import com.nononsenseapps.feeder.model.ReadAloudQueue
import com.nononsenseapps.feeder.model.TTSStateHolder
import com.nononsenseapps.feeder.model.offline.DownloadsPlayback
import com.nononsenseapps.feeder.model.offline.OfflineFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance

/** What the Now playing bar, car mode and the media controls show. */
data class QueueState(
    val playlistId: String? = null,
    val name: String = "",
    val ids: List<Long> = emptyList(),
    val index: Int = 0,
    /** Null when nothing is queued. */
    val kind: ItemKind? = null,
    val isPlaying: Boolean = false,
    val loading: Boolean = false,
    val title: String = "",
    val feedTitle: String = "",
    val artwork: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** Audio speed (articles use the read-aloud speed). */
    val audioSpeed: Float = 1f,
) {
    val active: Boolean get() = kind != null && ids.isNotEmpty()
    val currentId: Long? get() = ids.getOrNull(index)
    val hasNext: Boolean get() = index < ids.size - 1
    val hasPrevious: Boolean get() = index > 0
}

/** A message for a snackbar, with an optional action. */
data class QueueMessage(
    val text: String,
    val actionLabel: String? = null,
    val action: (() -> Unit)? = null,
)

/**
 * Plays a playlist from start to end, mixing engines: podcast episodes (streamed, or the saved
 * file) and saved YouTube videos play as audio in this player, articles are read aloud by
 * [TTSStateHolder]. When an item ends it is marked read, leaves your manual playlists, and the
 * next one starts. [PlaylistPlaybackService] shows the audio player to Android (notification,
 * lock screen, Bluetooth and steering wheel buttons); read aloud has its own service, and both
 * send Next and Previous here while a playlist plays.
 */
@OptIn(UnstableApi::class)
class PlaylistPlayer(
    override val di: DI,
) : DIAware {
    private val app: Application by instance()
    private val store: PlaylistStore by instance()
    private val repository: Repository by instance()
    private val tts: TTSStateHolder by instance()
    private val readAloudQueue: ReadAloudQueue by instance()
    private val playlistAudio: PlaylistAudio by instance()
    private val offlineFiles: OfflineFiles by instance()
    private val downloadsPlayback: DownloadsPlayback by instance()
    private val scope: ApplicationCoroutineScope by instance()
    private val history: com.nononsenseapps.feeder.model.history.CentralHistoryRecorder by instance()

    private val _state = MutableStateFlow(QueueState(audioSpeed = store.audioSpeed))
    val state: StateFlow<QueueState> = _state.asStateFlow()

    private val _player = MutableStateFlow<ExoPlayer?>(null)

    /** The audio player, for [PlaylistPlaybackService]; null while nothing plays as audio. */
    val player: StateFlow<ExoPlayer?> = _player.asStateFlow()

    private val _messages = MutableSharedFlow<QueueMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<QueueMessage> = _messages.asSharedFlow()

    private var savedNavigator: TTSStateHolder.ArticleNavigator? = null
    private var navigatorInstalled = false
    private var playJob: Job? = null
    private var tickJob: Job? = null

    init {
        // Read aloud's state is the article's state while an article is the current item
        scope.launch {
            combine(tts.ttsState, tts.progress) { status, progress -> status to progress }.collect { (status, progress) ->
                val s = _state.value
                if (s.kind != ItemKind.ARTICLE || progress.articleId != s.currentId) return@collect
                _state.value =
                    s.copy(
                        isPlaying = status == PlaybackStatus.PLAYING,
                        loading = progress.preparationProgress != null && progress.durationMillis == 0L,
                        positionMs = progress.positionMillis,
                        durationMs = progress.durationMillis,
                    )
            }
        }
    }

    private fun emit(message: QueueMessage) {
        _messages.tryEmit(message)
    }

    /** Plays [ids] in order from [start]. */
    fun start(
        ids: List<Long>,
        name: String,
        playlistId: String? = null,
        start: Int = 0,
    ) {
        if (ids.isEmpty()) return
        _state.value = _state.value.copy(ids = ids, index = start.coerceIn(0, ids.size - 1), name = name, playlistId = playlistId)
        playCurrent()
    }

    /** Puts [id] right after the item playing now, or starts with it. */
    fun playNext(id: Long) {
        val s = _state.value
        if (!s.active) return start(listOf(id), "Up next")
        if (s.currentId == id) return
        val ids = s.ids.filterIndexed { i, x -> x != id || i == s.index }.toMutableList()
        val at = ids.indexOf(s.currentId!!)
        ids.add(at + 1, id)
        _state.value = s.copy(ids = ids, index = at)
    }

    /** Puts [id] at the end of the queue, or starts with it. */
    fun playLast(id: Long) {
        val s = _state.value
        if (!s.active) return start(listOf(id), "Up next")
        if (id in s.ids) return
        _state.value = s.copy(ids = s.ids + id)
    }

    private fun installNavigator() {
        if (navigatorInstalled) return
        // Make sure read aloud's own queue has set its navigator first, then step in front of it
        readAloudQueue.autoContinue
        savedNavigator = tts.articleNavigator
        tts.articleNavigator =
            object : TTSStateHolder.ArticleNavigator {
                override fun hasPrevious(): Boolean = _state.value.hasPrevious

                override fun hasNext(): Boolean = _state.value.hasNext

                override fun previous() = this@PlaylistPlayer.previous()

                override fun next() = this@PlaylistPlayer.next()

                override fun onArticleFinished(articleId: Long) {
                    if (_state.value.kind == ItemKind.ARTICLE && _state.value.currentId == articleId) finishCurrent()
                }
            }
        navigatorInstalled = true
    }

    private fun removeNavigator() {
        if (!navigatorInstalled) return
        tts.articleNavigator = savedNavigator
        savedNavigator = null
        navigatorInstalled = false
    }

    private fun playCurrent() {
        playJob?.cancel()
        playJob =
            scope.launch(Dispatchers.Main.immediate) {
                val id = _state.value.currentId ?: return@launch stop()
                val article = withContext(Dispatchers.IO) { repository.getArticle(id) }
                if (article == null) {
                    // Deleted meanwhile: skip it
                    val s = _state.value
                    val ids = s.ids.filterNot { it == id }
                    if (ids.isEmpty() || s.index >= ids.size) return@launch stop()
                    _state.value = s.copy(ids = ids)
                    return@launch playCurrent()
                }
                val kind = itemKind(article.link, article.enclosure.link.takeIf { article.enclosure.present }, article.enclosure.type)
                _state.value.let { s -> history.itemOpened(id, s.playlistId, s.name) }
                installNavigator()
                downloadsPlayback.stop()
                com.nononsenseapps.feeder.model.audiobooks.AudioFocusHooks.pauseAudiobook
                    ?.invoke()
                _state.value =
                    _state.value.copy(
                        kind = kind,
                        loading = true,
                        isPlaying = false,
                        title = article.title,
                        feedTitle = article.feedDisplayTitle,
                        artwork = article.image?.url,
                        positionMs = 0,
                        durationMs = 0,
                    )
                if (kind == ItemKind.ARTICLE) {
                    releaseAudio()
                    if (tts.ttsState.value != PlaybackStatus.STOPPED) tts.pause()
                    // Made ahead ("Get next 20 articles ready offline"): plays at once, no connection needed
                    val ready = withContext(Dispatchers.IO) { playlistAudio.prepared(id) }
                    if (ready != null) {
                        tts.playPrepared(
                            articleId = id,
                            text = ready.first,
                            prepared = ready.second,
                            useDetectLanguage = repository.useDetectLanguage.value,
                            title = article.title,
                            author = article.author.orEmpty(),
                            feedTitle = article.feedDisplayTitle,
                        )
                        if (article.isUnread) repository.markAsReadAndNotified(id)
                        return@launch
                    }
                    val text = readAloudQueue.speechTextFor(article)
                    if (_state.value.currentId != id) return@launch
                    if (text.isNullOrBlank()) {
                        emit(QueueMessage("Nothing to read in “${article.title}”, skipped"))
                        return@launch next(auto = true)
                    }
                    tts.tts(
                        textArray = listOf(AnnotatedString(text)),
                        useDetectLanguage = repository.useDetectLanguage.value,
                        articleId = id,
                        title = article.title,
                        author = article.author.orEmpty(),
                        feedTitle = article.feedDisplayTitle,
                    )
                    // Starting to read an article counts as reading it. The queue keeps its order,
                    // so an Unread playlist that drops it doesn't skip the next one.
                    if (article.isUnread) repository.markAsReadAndNotified(id)
                    return@launch
                }
                if (tts.ttsState.value != PlaybackStatus.STOPPED) tts.stop()
                val source = audioSource(id, kind, article.enclosure.link, article.title, article.feedDisplayTitle, article.image?.url)
                if (source == null) {
                    emit(
                        QueueMessage(
                            if (kind == ItemKind.YOUTUBE) {
                                "“${article.title}” is a YouTube video that isn't downloaded yet, skipped. Download it to hear it here."
                            } else {
                                "Could not play “${article.title}”, skipped"
                            },
                        ),
                    )
                    return@launch next(auto = true)
                }
                val player = _player.value ?: createPlayer()
                player.setMediaSource(source)
                player.prepare()
                // Starts from the newest spot, from here or another device (which then needs no second seek)
                val at = store.takeOtherDevicePosition(id) ?: store.resumeAt(id)
                if (at > 0) player.seekTo(at * 1000)
                player.playWhenReady = true
                startService()
                startTicker()
            }
    }

    private fun audioSource(
        id: Long,
        kind: ItemKind,
        enclosure: String,
        title: String,
        artist: String,
        artwork: String?,
    ): MediaSource? {
        val factory = DefaultMediaSourceFactory(app)
        val metadata =
            MediaMetadata
                .Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(_state.value.name.ifBlank { "Feeder" })
                .setArtworkUri(artwork?.toUri())
                .build()

        fun item(uri: Uri) =
            MediaItem
                .Builder()
                .setMediaId(id.toString())
                .setUri(uri)
                .setMediaMetadata(metadata)
                .build()
        if (kind == ItemKind.PODCAST) {
            val file = offlineFiles.savedAudio(id)
            val uri = file?.let(Uri::fromFile) ?: enclosure.takeIf { it.isNotBlank() }?.toUri() ?: return null
            if (file == null && repository.offlineMode.value) return null
            return factory.createMediaSource(item(uri))
        }
        val saved = offlineFiles.savedVideo(id) ?: return null
        // Audio only: the sound track when it is a separate file, else the muxed video
        return saved.audio?.let { factory.createMediaSource(item(Uri.fromFile(it))) }
            ?: factory.createMediaSource(item(Uri.fromFile(saved.video)))
    }

    private fun createPlayer(): ExoPlayer {
        val player =
            ExoPlayer
                .Builder(app)
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                        .build(),
                    true,
                ).setHandleAudioBecomingNoisy(true)
                .build()
                .apply {
                    playbackParameters = PlaybackParameters(_state.value.audioSpeed)
                    addListener(
                        object : Player.Listener {
                            override fun onIsPlayingChanged(isPlaying: Boolean) {
                                if (_state.value.kind != ItemKind.ARTICLE) _state.value = _state.value.copy(isPlaying = isPlaying)
                                // Played further on another device since it was paused here: carry on from there
                                if (isPlaying && _state.value.kind != ItemKind.ARTICLE) {
                                    val id = _state.value.currentId
                                    val at = id?.let { store.takeOtherDevicePosition(it) }
                                    if (at != null) seekTo(at * 1000)
                                }
                            }

                            override fun onPlaybackStateChanged(playbackState: Int) {
                                if (_state.value.kind == ItemKind.ARTICLE) return
                                when (playbackState) {
                                    Player.STATE_READY -> _state.value = _state.value.copy(loading = false, durationMs = duration.coerceAtLeast(0))
                                    Player.STATE_BUFFERING -> _state.value = _state.value.copy(loading = true)
                                    Player.STATE_ENDED -> finishCurrent()
                                    else -> Unit
                                }
                            }

                            override fun onPlayerError(error: PlaybackException) {
                                emit(QueueMessage("Could not play “${_state.value.title}”, skipped"))
                                next(auto = true)
                            }
                        },
                    )
                }
        _player.value = player
        return player
    }

    private fun startTicker() {
        tickJob?.cancel()
        tickJob =
            scope.launch(Dispatchers.Main) {
                var n = 0
                while (isActive) {
                    val p = _player.value ?: break
                    val s = _state.value
                    if (s.kind != ItemKind.ARTICLE) {
                        _state.value = s.copy(positionMs = p.currentPosition, durationMs = p.duration.coerceAtLeast(0))
                        if (++n % 5 == 0 && p.isPlaying) s.currentId?.let { store.savePosition(it, p.currentPosition / 1000, p.duration.coerceAtLeast(0) / 1000) }
                    }
                    delay(1000)
                }
            }
    }

    private fun saveAudioPosition() {
        val p = _player.value ?: return
        val id = _state.value.currentId ?: return
        if (_state.value.kind != ItemKind.ARTICLE && p.currentPosition > 0) store.savePosition(id, p.currentPosition / 1000, p.duration.coerceAtLeast(0) / 1000)
    }

    /** Played to the end: mark read, leave manual playlists, play the next one. */
    private fun finishCurrent() {
        val s = _state.value
        val id = s.currentId ?: return
        val title = s.title
        if (s.kind != ItemKind.ARTICLE) {
            _player.value?.let { store.savePosition(id, it.duration.coerceAtLeast(0) / 1000, it.duration.coerceAtLeast(0) / 1000) }
        }
        scope.launch {
            // Its audio made ahead is no longer needed
            if (s.kind == ItemKind.ARTICLE) withContext(Dispatchers.IO) { playlistAudio.played(id) }
            repository.markAsReadAndNotified(id)
            val from = store.removeFromAllManual(id)
            if (from.isNotEmpty()) {
                emit(
                    QueueMessage("Finished “$title”, removed from your playlist", "Undo") {
                        scope.launch { repository.markAsUnread(id) }
                        from.forEach { store.add(it, listOf(id)) }
                    },
                )
            }
        }
        next(auto = true)
    }

    fun next(auto: Boolean = false) {
        val s = _state.value
        if (s.hasNext) {
            saveAudioPosition()
            _state.value = s.copy(index = s.index + 1)
            playCurrent()
        } else if (auto) {
            emit(QueueMessage(if (s.name.isNotBlank()) "End of ${s.name}" else "End of the queue"))
            stop()
        }
    }

    fun previous() {
        val s = _state.value
        val p = _player.value
        // Past the first few seconds, Previous starts this one over
        if (s.kind != ItemKind.ARTICLE && p != null && p.currentPosition > 5_000) {
            p.seekTo(0)
            return
        }
        if (s.hasPrevious) {
            saveAudioPosition()
            _state.value = s.copy(index = s.index - 1)
            playCurrent()
        } else if (s.kind == ItemKind.ARTICLE) {
            tts.seekTo(0)
        }
    }

    fun jump(index: Int) {
        val s = _state.value
        if (index !in s.ids.indices) return
        saveAudioPosition()
        _state.value = s.copy(index = index)
        playCurrent()
    }

    /** Play/pause; [play] forces one. */
    fun toggle(play: Boolean? = null) {
        val s = _state.value
        if (!s.active) return
        if (s.kind == ItemKind.ARTICLE) {
            val playing = tts.ttsState.value == PlaybackStatus.PLAYING
            when {
                play == false || (play == null && playing) -> tts.pause()
                tts.progress.value.articleId == s.currentId && tts.ttsState.value == PlaybackStatus.PAUSED -> tts.play()
                else -> playCurrent()
            }
            return
        }
        val p = _player.value ?: return playCurrent()
        if (play == false || (play == null && p.isPlaying)) {
            p.pause()
            saveAudioPosition()
        } else {
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            p.play()
            startService()
        }
    }

    fun seekBy(ms: Long) {
        val s = _state.value
        if (s.kind == ItemKind.ARTICLE) {
            tts.seekTo((tts.progress.value.positionMillis + ms).coerceAtLeast(0))
            return
        }
        val p = _player.value ?: return
        val target = (p.currentPosition + ms).coerceAtLeast(0)
        p.seekTo(if (p.duration > 0) target.coerceAtMost(p.duration - 1000) else target)
    }

    fun seekToFraction(f: Float) {
        val s = _state.value
        if (s.kind == ItemKind.ARTICLE) {
            if (s.durationMs > 0) tts.seekTo((s.durationMs * f).toLong())
            return
        }
        val p = _player.value ?: return
        if (p.duration > 0) p.seekTo((p.duration * f).toLong())
    }

    fun setAudioSpeed(speed: Float) {
        store.audioSpeed = speed
        _player.value?.playbackParameters = PlaybackParameters(speed)
        _state.value = _state.value.copy(audioSpeed = speed)
    }

    fun articleSpeed(): Float =
        tts.progress.value.speed
            .takeIf { it > 0f } ?: 1f

    /** Speed of what plays now: read-aloud speed for articles, audio speed for the rest. */
    fun setSpeed(speed: Float) {
        if (_state.value.kind == ItemKind.ARTICLE) tts.setSpeed(speed) else setAudioSpeed(speed)
    }

    /** The car's Speed button: 1, 1.25, 1.5, 1.75, 2, then back to 1. */
    fun cycleSpeed() {
        val now = if (_state.value.kind == ItemKind.ARTICLE) articleSpeed() else _state.value.audioSpeed
        setSpeed(CAR_SPEEDS.firstOrNull { it > now + 0.01f } ?: CAR_SPEEDS.first())
    }

    /** Takes an item out of the queue (not out of any playlist). */
    fun remove(index: Int) {
        val s = _state.value
        if (index !in s.ids.indices) return
        val ids = s.ids.filterIndexed { i, _ -> i != index }
        if (ids.isEmpty()) return stop()
        if (index == s.index) {
            _state.value = s.copy(ids = ids, index = index.coerceAtMost(ids.size - 1))
            playCurrent()
        } else {
            _state.value = s.copy(ids = ids, index = if (index < s.index) s.index - 1 else s.index)
        }
    }

    fun stop() {
        playJob?.cancel()
        tickJob?.cancel()
        saveAudioPosition()
        val s = _state.value
        if (s.kind == ItemKind.ARTICLE && tts.progress.value.articleId == s.currentId) tts.stop()
        releaseAudio()
        removeNavigator()
        _state.value = QueueState(audioSpeed = s.audioSpeed)
    }

    private fun releaseAudio() {
        tickJob?.cancel()
        stopService()
        _player.value?.release()
        _player.value = null
    }

    private fun startService() {
        try {
            app.startService(Intent(app, PlaylistPlaybackService::class.java))
        } catch (_: Exception) {
            // Not allowed from the background right now; the media session comes with the next play
        }
    }

    private fun stopService() {
        app.stopService(Intent(app, PlaylistPlaybackService::class.java))
    }
}

private val CAR_SPEEDS = listOf(1f, 1.25f, 1.5f, 1.75f, 2f)
