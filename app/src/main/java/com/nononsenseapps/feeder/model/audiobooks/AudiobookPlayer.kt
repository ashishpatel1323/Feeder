package com.nononsenseapps.feeder.model.audiobooks

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.nononsenseapps.feeder.ApplicationCoroutineScope
import com.nononsenseapps.feeder.dropbox.DropboxClient
import com.nononsenseapps.feeder.model.history.CentralHistory
import com.nononsenseapps.feeder.model.history.HistoryWhere
import com.nononsenseapps.feeder.model.history.ItemHistory
import com.nononsenseapps.feeder.model.history.RecentNote
import com.nononsenseapps.feeder.model.media.MediaFile
import com.nononsenseapps.feeder.model.media.MediaLibrary
import com.nononsenseapps.feeder.model.media.MediaRules
import com.nononsenseapps.feeder.model.playlists.PlaylistPlaybackService
import com.nononsenseapps.feeder.model.playlists.PlaylistPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance

/** What the Audiobooks screens, the mini player and the car show. */
data class AudiobookState(
    val bookId: String? = null,
    val title: String = "",
    val author: String = "",
    val isPlaying: Boolean = false,
    val loading: Boolean = false,
    /** File playing, and ms into it */
    val i: Int = 0,
    val ms: Long = 0,
    /** Ms into the whole book, and its length (0 while unknown) */
    val bookMs: Long = 0,
    val bookLength: Long = 0,
    val chapter: Int = 0,
    val chapters: List<Chapter> = emptyList(),
    val speed: Float = 1f,
    /** When the sleep timer pauses (ms since epoch), or 0 */
    val sleepEndsAt: Long = 0,
    val sleepEndOfChapter: Boolean = false,
    /** Reading the files' chapters and lengths: files done of files in all, or null */
    val scan: Pair<Int, Int>? = null,
    val error: String? = null,
    /** A file from Books > Player, not an audiobook */
    val media: Boolean = false,
    /** A Player file with pictures: its screen shows the video */
    val video: Boolean = false,
    /** Player files that come next (their ids), in order */
    val queue: List<String> = emptyList(),
) {
    val active: Boolean get() = bookId != null
    val sleepOn: Boolean get() = sleepEndsAt > 0 || sleepEndOfChapter
    val currentChapter: Chapter? get() = chapters.getOrNull(chapter)

    /** Ms into the chapter playing, and its length */
    val inChapterMs: Long get() = currentChapter?.let { if (it.i == i) (ms - it.startMs).coerceAtLeast(0) else 0 } ?: ms
    val chapterLengthMs: Long get() = currentChapter?.lengthMs ?: 0
}

/**
 * Plays one audiobook at a time: its files one after another in one ExoPlayer, streamed from
 * Dropbox (links are fetched as each file opens, so they never run out) or from the phone when the
 * book was downloaded. Keeps the position (every 15 s, on pause and on every jump; synced as
 * "audiopos"), a speed per book, and a sleep timer (minutes or end of chapter, fading out over the
 * last 10 s). The notification, lock screen, Bluetooth buttons and Android Auto come from
 * [PlaylistPlaybackService], whose player shows the audiobook while no playlist plays.
 *
 * It also plays Books > Player's files ([openMedia]), audio or video, as one-file books: the
 * same notification, speed, sleep timer and synced place. The Player's screen shows the video by
 * putting [exoPlayer] in a PlayerView; leaving the screen keeps the sound going. Files of a
 * folder play one after another ([AudiobookState.queue]).
 */
@OptIn(UnstableApi::class)
class AudiobookPlayer(
    override val di: DI,
) : DIAware {
    private val app: Application by instance()
    private val scope: ApplicationCoroutineScope by instance()
    private val playlistPlayer: PlaylistPlayer by instance()
    private val positions: AudioPositions by instance()
    private val history: ItemHistory by instance()

    // Started with the player, so a book with its transcript on is transcribed ahead while it plays
    private val transcriptsAhead: AudiobookTranscripts by instance()
    private val library by lazy { AudiobookLibrary.get(app) }
    private val mediaLibrary by lazy { MediaLibrary.get(app) }
    private val centralHistory by lazy { CentralHistory.get(app) }

    /** The Player file playing, when it is one */
    private var mediaFile: MediaFile? = null

    /** The ExoPlayer, for a PlayerView that shows a Player file's video */
    val exoPlayer: Player? get() = player
    private val dropbox by lazy { DropboxClient.get(app) }
    private val prefs by lazy { app.getSharedPreferences("audiobook_player", android.content.Context.MODE_PRIVATE) }

    private val _state = MutableStateFlow(AudiobookState())
    val state: StateFlow<AudiobookState> = _state.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var player: ExoPlayer? = null
    private var book: Audiobook? = null
    private var tickJob: Job? = null
    private var lastSaved = 0L
    private var retried = false

    // History: whether a "play" was noted and no "pause" since, and why the next play happens
    private var noted = false
    private var playReason = REASON_APP

    init {
        AudioFocusHooks.pauseAudiobook = { if (_state.value.isPlaying || _state.value.loading) pause(REASON_FOCUS) }
    }

    /** Notes a play or pause in the book's history, with the chapter and time into it. */
    private fun note(
        action: String,
        reason: String,
    ) {
        val b = book ?: return
        val s = _state.value
        mediaFile?.takeIf { s.media }?.let { m ->
            val f = AudiobookRules.fractionAt(b, s.i, s.ms)
            val starts = action == ItemHistory.PLAY
            scope.launch(Dispatchers.IO) {
                centralHistory.note(
                    RecentNote(CentralHistory.MEDIA, m.id, n = m.title, s = m.folder, f = f.takeIf { it > 0 }, d = f >= 0.99, x = "0:${s.ms}"),
                    touch = starts,
                    create = starts,
                )
            }
            return
        }
        val ch = s.currentChapter
        history.record(
            ItemHistory.AUDIOBOOK,
            b.id,
            action,
            HistoryWhere(
                p = if (ch != null) "Ch. ${s.chapter + 1} · ${ItemHistory.clockText(s.inChapterMs)}" else ItemHistory.clockText(s.bookMs),
                s = ch?.title.orEmpty(),
                f = AudiobookRules.fractionAt(b, s.i, s.ms),
                x = "${s.i}:${s.ms}",
            ),
            reason,
        )
    }

    private fun notePause(reason: String) {
        if (!noted) return
        noted = false
        note(ItemHistory.PAUSE, reason)
    }

    private fun createPlayer(): ExoPlayer {
        // dropbox:/path URIs become a fresh streaming link when each file opens
        val data =
            ResolvingDataSource.Factory(DefaultDataSource.Factory(app)) { spec ->
                if (spec.uri.scheme == SCHEME) {
                    val path = spec.uri.path ?: ""
                    spec.withUri(Uri.parse(runBlocking { dropbox.temporaryLink(path, fresh = retried) }))
                } else {
                    spec
                }
            }
        return ExoPlayer
            .Builder(app)
            .setMediaSourceFactory(DefaultMediaSourceFactory(data))
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
                addListener(
                    object : Player.Listener {
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            _state.value = _state.value.copy(isPlaying = isPlaying, loading = if (isPlaying) false else _state.value.loading)
                            if (isPlaying) retried = false else save()
                            if (isPlaying && !noted) {
                                tick()
                                noted = true
                                note(ItemHistory.PLAY, playReason)
                                playReason = REASON_APP
                            } else if (!isPlaying && playWhenReady && playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS) {
                                // A call or a navigation prompt: it carries on afterwards
                                tick()
                                notePause(REASON_FOCUS)
                                playReason = REASON_FOCUS
                            }
                        }

                        override fun onPlayWhenReadyChanged(
                            playWhenReady: Boolean,
                            reason: Int,
                        ) {
                            if (playWhenReady) return
                            tick()
                            when (reason) {
                                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> notePause(REASON_BLUETOOTH)
                                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> notePause(REASON_FOCUS)
                                Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> notePause(REASON_END)
                                else -> Unit
                            }
                        }

                        override fun onPlaybackStateChanged(playbackState: Int) {
                            when (playbackState) {
                                Player.STATE_BUFFERING -> _state.value = _state.value.copy(loading = playWhenReady)
                                Player.STATE_READY -> _state.value = _state.value.copy(loading = false)
                                Player.STATE_ENDED -> finished()
                                else -> Unit
                            }
                        }

                        override fun onMediaItemTransition(
                            mediaItem: MediaItem?,
                            reason: Int,
                        ) {
                            tick()
                            save()
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            val p = player ?: return
                            // Most often the streaming link ran out: get a new one and carry on
                            if (!retried) {
                                retried = true
                                val i = p.currentMediaItemIndex
                                val at = p.currentPosition
                                p.prepare()
                                p.seekTo(i, at)
                                p.playWhenReady = true
                                return
                            }
                            val name = book?.tracks?.getOrNull(p.currentMediaItemIndex)?.name ?: "this file"
                            notePause(REASON_SYSTEM)
                            _state.value = _state.value.copy(isPlaying = false, loading = false, error = "Could not play $name")
                            _messages.tryEmit("Could not play $name: ${error.errorCodeName}")
                        }
                    },
                )
            }.also { player = it }
    }

    private fun dropboxUri(path: String) =
        Uri
            .Builder()
            .scheme(SCHEME)
            .path(path)
            .build()

    private fun mediaItems(b: Audiobook): List<MediaItem> =
        b.tracks.mapIndexed { k, t ->
            val uri =
                (if (mediaFile != null) mediaLibrary.localFile(t) else library.localFile(t))?.let(Uri::fromFile) ?: t.uri?.let(Uri::parse) ?: dropboxUri(t.path)
            // A Player file's subtitles (a .vtt or .srt next to it), read through the same data source
            val subs =
                mediaFile?.let { m ->
                    m.subs?.let { s ->
                        MediaItem.SubtitleConfiguration
                            .Builder(if (s.startsWith("content:")) Uri.parse(s) else dropboxUri(s))
                            .setMimeType(if (MediaRules.subtitlesAreSrt(app, m)) MimeTypes.APPLICATION_SUBRIP else MimeTypes.TEXT_VTT)
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                            .setLanguage("und")
                            .build()
                    }
                }
            MediaItem
                .Builder()
                .setMediaId("${b.id}:$k")
                .setUri(uri)
                .setSubtitleConfigurations(listOfNotNull(subs))
                .setMediaMetadata(
                    MediaMetadata
                        .Builder()
                        .setTitle(t.title ?: AudiobookRules.nameWithoutExt(t.name))
                        .setArtist(b.author)
                        .setAlbumTitle(b.title)
                        .build(),
                ).build()
        }

    private fun speedFor(id: String): Float = prefs.getFloat("speed:$id", prefs.getFloat(LAST_SPEED, 1f))

    /** Opens a book where it was left (or at file [at].first, ms [at].second), and plays it unless [play] is false. */
    fun open(
        id: String,
        play: Boolean = true,
        at: Pair<Int, Long>? = null,
        reason: String = REASON_APP,
    ) {
        transcriptsAhead.load(id)
        if (_state.value.bookId == id && at == null) {
            if (play) play(reason)
            return
        }
        val b = library.get(id) ?: return
        tick()
        notePause(REASON_APP)
        playReason = reason
        save()
        player?.stop()
        mediaFile = null
        book = b
        val chapters = AudiobookRules.chaptersOf(b)
        val start = at ?: AudiobookRules.resumePoint(b, positions.get(id)) ?: (0 to 0L)
        _state.value =
            AudiobookState(
                bookId = id,
                title = b.title,
                author = b.author,
                loading = play,
                i = start.first,
                ms = start.second,
                bookMs = AudiobookRules.trackStartMs(b, start.first) + start.second,
                bookLength = AudiobookRules.bookLengthMs(b),
                chapters = chapters,
                chapter = AudiobookRules.chapterAt(chapters, start.first, start.second),
                speed = speedFor(id),
                sleepEndsAt = _state.value.sleepEndsAt,
                sleepEndOfChapter = _state.value.sleepEndOfChapter,
            )
        library.markOpened(id)
        val p = player ?: createPlayer()
        p.setAudioAttributes(attributes(speech = true), true)
        p.setMediaItems(mediaItems(b), start.first, start.second)
        p.playbackParameters = PlaybackParameters(_state.value.speed)
        p.volume = 1f
        p.prepare()
        if (play) startPlaying()
        startTicker()
        // Chapters and lengths: read once, in the background; playing doesn't wait for them
        if (b.tracks.any { !it.scanned }) {
            _state.value = _state.value.copy(scan = b.tracks.count { it.scanned } to b.tracks.size)
            scope.launch {
                val fresh =
                    runCatching {
                        library.scan(id) { done, total -> if (_state.value.bookId == id) _state.value = _state.value.copy(scan = done to total) }
                    }.getOrNull()
                launch(Dispatchers.Main) {
                    if (_state.value.bookId != id) return@launch
                    _state.value = _state.value.copy(scan = null)
                    if (fresh != null) refreshBook(fresh)
                }
            }
        }
    }

    private fun attributes(speech: Boolean) =
        AudioAttributes
            .Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(if (speech) C.AUDIO_CONTENT_TYPE_SPEECH else C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

    /**
     * Opens a Books > Player file where it was left (or at [atMs]) and plays it unless [play] is
     * false. [queue] is what plays after it (ids, in order).
     */
    fun openMedia(
        id: String,
        queue: List<String> = emptyList(),
        play: Boolean = true,
        atMs: Long? = null,
        reason: String = REASON_APP,
    ) {
        if (_state.value.bookId == id && _state.value.media && atMs == null) {
            _state.value = _state.value.copy(queue = queue)
            if (play) play(reason)
            return
        }
        val m = mediaLibrary.get(id) ?: return
        tick()
        notePause(REASON_APP)
        playReason = reason
        save()
        player?.stop()
        mediaFile = m
        val b = MediaRules.asBook(m)
        book = b
        val start = atMs?.let { 0 to it } ?: AudiobookRules.resumePoint(b, positions.get(id)) ?: (0 to 0L)
        val chapters = AudiobookRules.chaptersOf(b)
        _state.value =
            AudiobookState(
                bookId = id,
                title = b.title,
                author = b.author,
                loading = play,
                ms = start.second,
                bookMs = start.second,
                bookLength = AudiobookRules.bookLengthMs(b),
                chapters = chapters,
                speed = speedFor(id),
                sleepEndsAt = _state.value.sleepEndsAt,
                sleepEndOfChapter = _state.value.sleepEndOfChapter,
                media = true,
                video = m.isVideo,
                queue = queue,
            )
        mediaLibrary.markOpened(id)
        val p = player ?: createPlayer()
        p.setAudioAttributes(attributes(speech = false), true)
        p.setMediaItems(mediaItems(b), 0, start.second)
        p.playbackParameters = PlaybackParameters(_state.value.speed)
        p.volume = 1f
        p.prepare()
        if (play) startPlaying()
        startTicker()
    }

    /** A Player file: the next one in its folder now. */
    fun skipNext() {
        val q = _state.value.queue
        if (_state.value.media && q.isNotEmpty()) openMedia(q.first(), q.drop(1))
    }

    private fun refreshBook(b: Audiobook) {
        book = b
        val s = _state.value
        val chapters = AudiobookRules.chaptersOf(b)
        _state.value =
            s.copy(
                title = b.title,
                author = b.author,
                chapters = chapters,
                bookLength = AudiobookRules.bookLengthMs(b),
                chapter = AudiobookRules.chapterAt(chapters, s.i, s.ms),
                bookMs = AudiobookRules.trackStartMs(b, s.i) + s.ms,
            )
    }

    private fun startPlaying() {
        // One player at a time
        if (playlistPlayer.state.value.active) playlistPlayer.stop()
        AudioFocusHooks.pausePodcast?.invoke()
        val p = player ?: return
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
        p.playWhenReady = true
        startService()
    }

    fun play(reason: String = REASON_APP) {
        val id = _state.value.bookId ?: return
        if (player == null) return if (_state.value.media) openMedia(id, _state.value.queue, reason = reason) else open(id, reason = reason)
        if (!noted) playReason = reason
        startPlaying()
    }

    fun pause(reason: String = REASON_APP) {
        tick()
        notePause(reason)
        player?.playWhenReady = false
        _state.value = _state.value.copy(loading = false)
        save()
    }

    fun toggle(
        play: Boolean? = null,
        reason: String = REASON_APP,
    ) {
        val playing = _state.value.isPlaying || _state.value.loading
        if (play == false || (play == null && playing)) pause(reason) else play(reason)
    }

    /** Jumps [ms] back or ahead, across files when needed. */
    fun seekBy(ms: Long) = seekToBookMs(_state.value.bookMs + ms)

    /** Jumps to a place in the whole book. */
    fun seekToBookMs(target: Long) {
        val b = book ?: return
        val p = player ?: return
        val len = _state.value.bookLength
        if (len <= 0) {
            // Lengths not known yet: seek within the file playing
            val t = (p.currentPosition + (target - _state.value.bookMs)).coerceAtLeast(0)
            p.seekTo(t)
        } else {
            val (i, ms) = AudiobookRules.locate(b, target.coerceIn(0, len - 1000))
            p.seekTo(i, ms)
        }
        tick()
        save(force = true)
    }

    /** Plays chapter [k] from its start. */
    fun jumpToChapter(k: Int) {
        val c = _state.value.chapters.getOrNull(k) ?: return
        val p = player ?: return
        p.seekTo(c.i, c.startMs)
        if (!p.playWhenReady) startPlaying()
        tick()
        save(force = true)
    }

    /** Previous: the start of this chapter, or the one before when already near its start. */
    fun previousChapter() {
        val s = _state.value
        val c = s.currentChapter
        if (c != null && c.i == s.i && s.ms - c.startMs > 3000) jumpToChapter(s.chapter) else jumpToChapter((s.chapter - 1).coerceAtLeast(0))
    }

    fun nextChapter() {
        val s = _state.value
        // A Player file has one chapter: Next is the next file in its folder
        if (s.media) return skipNext()
        if (s.chapter < s.chapters.size - 1) jumpToChapter(s.chapter + 1)
    }

    /** Speed for this book (remembered per book, and as the start speed for new books). */
    fun setSpeed(speed: Float) {
        player?.playbackParameters = PlaybackParameters(speed)
        _state.value = _state.value.copy(speed = speed)
        val id = _state.value.bookId ?: return
        prefs.edit {
            putFloat("speed:$id", speed)
            putFloat(LAST_SPEED, speed)
        }
    }

    /** The car's Speed button: 1, 1.25, 1.5, 1.75, 2, then back to 1. */
    fun cycleSpeed() = setSpeed(listOf(1f, 1.25f, 1.5f, 1.75f, 2f).firstOrNull { it > _state.value.speed + 0.01f } ?: 1f)

    /** Pauses after [minutes]; 0 pauses at the end of the chapter. */
    fun setSleep(minutes: Int) {
        player?.volume = 1f
        _state.value =
            if (minutes == 0) {
                _state.value.copy(sleepEndsAt = 0, sleepEndOfChapter = true)
            } else {
                _state.value.copy(sleepEndsAt = System.currentTimeMillis() + minutes * 60_000L, sleepEndOfChapter = false)
            }
    }

    /** Adds 5 minutes, or starts a 5-minute timer. */
    fun extendSleep() {
        val s = _state.value
        if (s.sleepEndsAt <= 0) return setSleep(5)
        player?.volume = 1f
        _state.value = s.copy(sleepEndsAt = maxOf(s.sleepEndsAt, System.currentTimeMillis()) + 5 * 60_000L)
    }

    fun cancelSleep() {
        player?.volume = 1f
        _state.value = _state.value.copy(sleepEndsAt = 0, sleepEndOfChapter = false)
    }

    private fun startTicker() {
        tickJob?.cancel()
        tickJob =
            scope.launch(Dispatchers.Main) {
                while (isActive) {
                    tick()
                    delay(500)
                }
            }
    }

    private fun tick() {
        val p = player ?: return
        val b = book ?: return
        val i = p.currentMediaItemIndex.coerceIn(0, (b.tracks.size - 1).coerceAtLeast(0))
        val ms = p.currentPosition.coerceAtLeast(0)
        val old = _state.value
        val chapter = AudiobookRules.chapterAt(old.chapters, i, ms)
        // A file's real length, when the tags didn't say
        val dur = p.duration
        if (dur > 0 && b.tracks.getOrNull(i)?.duration == null) {
            val tracks = b.tracks.mapIndexed { k, t -> if (k == i) t.copy(duration = dur / 1000.0) else t }
            refreshBook(b.copy(tracks = tracks))
            if (_state.value.media) mediaLibrary.setDuration(b.id, dur / 1000.0)
        }
        _state.value = _state.value.copy(i = i, ms = ms, bookMs = AudiobookRules.trackStartMs(book!!, i) + ms, chapter = chapter)
        if (chapter != old.chapter && old.sleepEndOfChapter && old.isPlaying) {
            cancelSleep()
            pause(REASON_SLEEP)
            _messages.tryEmit("Sleep timer: paused at the end of the chapter")
            return
        }
        val s = _state.value
        if (s.sleepEndsAt > 0 && s.isPlaying) {
            val left = s.sleepEndsAt - System.currentTimeMillis()
            if (left <= 0) {
                cancelSleep()
                pause(REASON_SLEEP)
                // Back a little, so the last words before falling asleep are heard again
                seekBy(-10_000)
                _messages.tryEmit("Sleep timer: paused")
            } else {
                p.volume = if (left < FADE_MS) (left.toFloat() / FADE_MS).coerceAtLeast(0.05f) else 1f
            }
        }
        if (s.isPlaying && System.currentTimeMillis() - lastSaved > 15_000) save()
    }

    /** Saves the position now (also sent to other devices with the next sync). */
    fun save(force: Boolean = false) {
        val b = book ?: return
        val p = player ?: return
        if (p.mediaItemCount == 0) return
        val i = p.currentMediaItemIndex.coerceIn(0, b.tracks.size - 1)
        val ms = p.currentPosition.coerceAtLeast(0)
        if (!force && ms == 0L && i == 0 && positions.get(b.id) != null) return
        lastSaved = System.currentTimeMillis()
        positions.save(b.id, i, b.tracks[i].hash, ms, AudiobookRules.fractionAt(b, i, ms))
    }

    private fun finished() {
        val b = book ?: return
        val last = b.tracks.lastOrNull() ?: return
        positions.save(b.id, b.tracks.size - 1, last.hash, last.lengthMs.takeIf { it > 0 } ?: (player?.duration ?: 0L).coerceAtLeast(0), 1.0)
        notePause(REASON_END)
        // A Player file: on to the next one in its folder
        val q = _state.value.queue
        if (_state.value.media && q.isNotEmpty()) {
            scope.launch(Dispatchers.Main) { openMedia(q.first(), q.drop(1)) }
            return
        }
        cancelSleep()
        _state.value = _state.value.copy(isPlaying = false, loading = false)
        _messages.tryEmit("Finished ${b.title}")
    }

    /** Stops and closes the player (the position is kept). */
    fun close() {
        tick()
        notePause(REASON_CLOSED)
        save()
        tickJob?.cancel()
        player?.release()
        player = null
        book = null
        mediaFile = null
        _state.value = AudiobookState()
        if (!playlistPlayer.state.value.active) stopService()
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

    companion object {
        private const val SCHEME = "dropbox"
        private const val LAST_SPEED = "speed"
        private const val FADE_MS = 10_000L

        // Why it played or paused, for its history (the same words as the web app's lib/history.ts)
        const val REASON_APP = "app"
        const val REASON_NOTIFICATION = "notification"
        const val REASON_HEADSET = "headset"
        const val REASON_BLUETOOTH = "bluetooth"
        const val REASON_CAR = "car"
        const val REASON_WATCH = "watch"
        const val REASON_SLEEP = "sleep"
        const val REASON_END = "end"
        const val REASON_FOCUS = "focus"
        const val REASON_CLOSED = "closed"
        const val REASON_SYSTEM = "system"
        const val REASON_WIDGET = "widget"
    }
}

/** Lets one player pause another without the two depending on each other. */
object AudioFocusHooks {
    @Volatile var pauseAudiobook: (() -> Unit)? = null

    @Volatile var pausePodcast: (() -> Unit)? = null
}
