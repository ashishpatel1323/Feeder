package com.nononsenseapps.feeder.model.audiobooks

import android.app.Application
import android.net.Uri
import android.util.Log
import com.nononsenseapps.feeder.ApplicationCoroutineScope
import com.nononsenseapps.feeder.dropbox.DropboxClient
import com.nononsenseapps.feeder.model.transcript.AudioDecoder
import com.nononsenseapps.feeder.model.transcript.Resampler
import com.nononsenseapps.feeder.model.transcript.TranscriptCue
import com.nononsenseapps.feeder.model.transcript.Transcripts
import com.nononsenseapps.feeder.model.transcript.WhisperNative
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * A piece of an audiobook's transcript: the lines Whisper heard from [s] to [e] ms into the book.
 * Pieces never change once made, and other devices (the Mac transcribes fastest) send theirs
 * through sync as "abtx" records, so a book's transcript is the union of everyone's pieces.
 */
@Serializable
data class TranscriptPiece(
    val s: Long,
    val e: Long,
    /** [start ms, end ms, text] in book time */
    val c: List<PieceCue>,
) {
    val key: String get() = s.toString()
}

@Serializable
data class PieceCue(
    val s: Long,
    val e: Long,
    val t: String,
)

/** A stretch of the book to transcribe next: file [i] from [startMs] to [endMs] (file time). */
data class PieceTarget(
    val i: Int,
    val startMs: Long,
    val endMs: Long,
    val bookStartMs: Long,
)

/**
 * The rules, the same as the web app's (web/src/lib/audiobooks/transcriptRules.ts): how pieces
 * merge into one transcript, how far it is ready, and what to transcribe next.
 */
object AudiobookTranscriptRules {
    /** Whisper works this far ahead of where the book is playing, or to the end of the next chapter. */
    const val AHEAD_MIN_MS = 15 * 60_000L
    const val AHEAD_MAX_MS = 60 * 60_000L
    const val PIECE_MS = 5 * 60_000L

    /** Nothing ready where the book plays: a short first piece, so lines appear within seconds. */
    const val FIRST_PIECE_MS = 30_000L

    /** Gaps shorter than this between pieces are left alone. */
    const val GAP_MS = 1_500L

    /** All the lines, in order; where pieces overlap, the earlier piece's lines win. */
    fun merge(pieces: Collection<TranscriptPiece>): List<TranscriptCue> {
        val out = mutableListOf<TranscriptCue>()
        var covered = Long.MIN_VALUE
        for (p in pieces.sortedWith(compareBy({ it.s }, { -it.e }))) {
            if (p.e <= covered) continue
            for (c in p.c) if (c.s >= covered) out += TranscriptCue(c.s, c.e, c.t)
            covered = maxOf(covered, p.e)
        }
        return out
    }

    /** The stretches transcribed, merged, in order. */
    fun ranges(pieces: Collection<TranscriptPiece>): List<LongRange> {
        val out = mutableListOf<LongRange>()
        for (p in pieces.sortedBy { it.s }) {
            val last = out.lastOrNull()
            if (last != null && p.s <= last.last + GAP_MS) {
                if (p.e > last.last) out[out.size - 1] = last.first..p.e
            } else {
                out += p.s..p.e
            }
        }
        return out
    }

    /** Where the transcript ready from [atMs] on ends; [atMs] itself when nothing is ready there. */
    fun readyUntil(
        ranges: List<LongRange>,
        atMs: Long,
    ): Long = ranges.firstOrNull { atMs >= it.first - GAP_MS && atMs < it.last }?.last ?: atMs

    /** How far ahead of [bookMs] to have the transcript ready. */
    fun aheadUntil(
        chapters: List<Chapter>,
        bookMs: Long,
        bookLength: Long,
    ): Long {
        val k = chapters.indexOfLast { it.bookMs <= bookMs }
        val nextChapterEnd = chapters.getOrNull(k + 1)?.let { it.bookMs + it.lengthMs } ?: Long.MAX_VALUE
        val end = nextChapterEnd.coerceIn(bookMs + AHEAD_MIN_MS, bookMs + AHEAD_MAX_MS)
        return if (bookLength > 0) minOf(end, bookLength) else end
    }

    /**
     * The next stretch to transcribe for a book playing at [bookMs]: the first gap from there,
     * up to [aheadUntil]. Stays inside one file and stops where the next piece starts. Null when
     * the transcript is ready far enough ahead, or the files' lengths aren't known yet.
     */
    fun nextTarget(
        trackLengths: List<Long>,
        chapters: List<Chapter>,
        pieces: Collection<TranscriptPiece>,
        bookMs: Long,
    ): PieceTarget? {
        if (trackLengths.isEmpty() || trackLengths.any { it <= 0 }) return null
        val bookLength = trackLengths.sum()
        val ranges = ranges(pieces)
        val until = aheadUntil(chapters, bookMs, bookLength)
        val from = readyUntil(ranges, bookMs.coerceIn(0, bookLength))
        if (from >= until || from >= bookLength - 500) return null
        val nothingHere = from == bookMs
        val nextStart = ranges.firstOrNull { it.first > from }?.first ?: Long.MAX_VALUE
        // The file holding [from]
        var base = 0L
        for ((i, len) in trackLengths.withIndex()) {
            // Less than a second left in this file (lengths are rounded): on to the next one
            if (from < base + len && base + len - from >= 1_000) {
                val start = maxOf(from, base)
                val length = if (nothingHere) FIRST_PIECE_MS else PIECE_MS
                val endBook = minOf(start + length, nextStart, base + len)
                // A sliver left at the end of a file joins this piece's file end instead
                val end = if (base + len - endBook < 20_000) base + len else endBook
                return PieceTarget(i, start - base, end - base, start)
            }
            base += len
        }
        return null
    }

    /** Whisper's lines for a piece (file time) to a piece in book time. */
    fun piece(
        target: PieceTarget,
        endMs: Long,
        cues: List<TranscriptCue>,
    ): TranscriptPiece {
        val base = target.bookStartMs - target.startMs
        return TranscriptPiece(
            s = target.bookStartMs,
            e = base + endMs,
            c = cues.map { PieceCue(base + it.startMs, base + it.endMs, it.text) },
        )
    }
}

/** What the transcript screen shows for one book. */
data class AudiobookTranscriptState(
    val bookId: String,
    val loaded: Boolean = false,
    val cues: List<TranscriptCue> = emptyList(),
    val ranges: List<LongRange> = emptyList(),
    /** Transcribing ahead is on for this book */
    val wanted: Boolean = false,
    /** Whisper is working on this book, at this book time */
    val workingAtMs: Long? = null,
    /** 0..1 while the speech model downloads, else null */
    val modelProgress: Float? = null,
    val error: String? = null,
)

@Serializable
private data class StoredBookTranscript(
    val pieces: List<TranscriptPiece> = emptyList(),
    /** Keys of pieces made here and not sent through sync yet */
    val unsynced: Set<String> = emptySet(),
    val wanted: Boolean = false,
)

/**
 * Live transcripts of audiobooks. Turned on per book from its transcript screen; then, while the
 * book plays (or its transcript is open), Whisper works a chapter or two ahead of the play
 * position in 5-minute pieces, each saved and synced as it finishes. Pieces from other devices
 * fill in too, and nothing is done twice. Kept in files/audiobook-transcripts/<book id>.json.
 */
class AudiobookTranscripts(
    override val di: DI,
) : DIAware {
    private val app: Application by instance()
    private val scope: ApplicationCoroutineScope by instance()
    private val player: AudiobookPlayer by instance()
    private val transcripts: Transcripts by instance()
    private val library by lazy { AudiobookLibrary.get(app) }
    private val dropbox by lazy { DropboxClient.get(app) }

    private val json = Json { ignoreUnknownKeys = true }
    private val dir by lazy { File(app.filesDir, "audiobook-transcripts").apply { mkdirs() } }
    private val fileMutex = Mutex()
    private val stored = mutableMapOf<String, StoredBookTranscript>()
    private val states = MutableStateFlow<Map<String, AudiobookTranscriptState>>(emptyMap())

    /** The book whose transcript screen is open, if any: it keeps transcribing while paused. */
    private val viewing = MutableStateFlow<String?>(null)
    private var job: Job? = null

    val canTranscribe: Boolean get() = WhisperNative.available

    init {
        scope.launch {
            player.state.collect { s -> if (s.isPlaying && s.bookId != null) kick() }
        }
        scope.launch { viewing.collect { if (it != null) kick() } }
    }

    fun state(bookId: String): Flow<AudiobookTranscriptState> = states.map { it[bookId] ?: AudiobookTranscriptState(bookId) }

    fun current(bookId: String): AudiobookTranscriptState = states.value[bookId] ?: AudiobookTranscriptState(bookId)

    private fun set(
        bookId: String,
        change: (AudiobookTranscriptState) -> AudiobookTranscriptState,
    ) = states.update { it + (bookId to change(it[bookId] ?: AudiobookTranscriptState(bookId))) }

    private fun publish(
        bookId: String,
        t: StoredBookTranscript,
    ) = set(bookId) {
        it.copy(
            loaded = true,
            cues = AudiobookTranscriptRules.merge(t.pieces),
            ranges = AudiobookTranscriptRules.ranges(t.pieces),
            wanted = t.wanted,
        )
    }

    private suspend fun read(bookId: String): StoredBookTranscript =
        stored[bookId] ?: withContext(Dispatchers.IO) {
            File(dir, "${safe(bookId)}.json")
                .takeIf { it.isFile }
                ?.let { f -> runCatching { json.decodeFromString<StoredBookTranscript>(f.readText()) }.getOrNull() }
                ?: StoredBookTranscript()
        }.also { stored[bookId] = it }

    private suspend fun change(
        bookId: String,
        f: (StoredBookTranscript) -> StoredBookTranscript,
    ): StoredBookTranscript =
        fileMutex.withLock {
            val next = f(read(bookId))
            stored[bookId] = next
            withContext(Dispatchers.IO) {
                val tmp = File(dir, "${safe(bookId)}.json.tmp")
                tmp.writeText(json.encodeToString(StoredBookTranscript.serializer(), next))
                tmp.renameTo(File(dir, "${safe(bookId)}.json"))
            }
            next
        }.also { publish(bookId, it) }

    /** Loads the book's transcript for the screen. */
    fun load(bookId: String) {
        if (current(bookId).loaded) return
        scope.launch { publish(bookId, fileMutex.withLock { read(bookId) }) }
    }

    /** The transcript screen opened (or closed, with null). */
    fun viewing(bookId: String?) {
        viewing.value = bookId
    }

    /** Turns transcribing ahead on or off for the book. */
    fun setWanted(
        bookId: String,
        on: Boolean,
    ) {
        scope.launch {
            change(bookId) { it.copy(wanted = on) }
            if (on) kick() else if (current(bookId).workingAtMs != null) WhisperNative.takeIf { it.available }?.cancel()
        }
    }

    /** Forgets every line of the book (on this phone; pieces may come back through sync). */
    fun clear(bookId: String) {
        scope.launch { change(bookId) { StoredBookTranscript(wanted = it.wanted) } }
    }

    // ---- Sync ----

    /** Pieces made here not sent yet, as sync records: key "<book id>:<start ms>". */
    suspend fun unsynced(): List<Pair<String, Map<String, Any?>>> =
        fileMutex.withLock {
            dir
                .listFiles { f -> f.name.endsWith(".json") }
                .orEmpty()
                .flatMap { f ->
                    val t = runCatching { json.decodeFromString<StoredBookTranscript>(f.readText()) }.getOrNull() ?: return@flatMap emptyList()
                    val bookId = unsafe(f.name.removeSuffix(".json"))
                    val t2 = stored[bookId] ?: t
                    t2.pieces.filter { it.key in t2.unsynced }.map { p -> "$bookId:${p.s}" to pieceValue(p) }
                }
        }

    suspend fun markSynced(keys: List<String>) {
        if (keys.isEmpty()) return
        keys.groupBy({ it.substringBeforeLast(':') }, { it.substringAfterLast(':') }).forEach { (bookId, done) ->
            change(bookId) { it.copy(unsynced = it.unsynced - done.toSet()) }
        }
    }

    /** A piece another device made. */
    suspend fun applyRemote(
        key: String,
        v: Any?,
    ) {
        val bookId = key.substringBeforeLast(':', "")
        val m = v as? Map<*, *> ?: return
        if (bookId.isEmpty()) return
        val s = key.substringAfterLast(':').toLongOrNull() ?: return
        val e = (m["e"] as? Number)?.toLong() ?: return
        val cues =
            (m["c"] as? List<*>).orEmpty().mapNotNull { row ->
                val r = row as? List<*> ?: return@mapNotNull null
                val cs = (r.getOrNull(0) as? Number)?.toLong() ?: return@mapNotNull null
                val ce = (r.getOrNull(1) as? Number)?.toLong() ?: return@mapNotNull null
                val text = r.getOrNull(2) as? String ?: return@mapNotNull null
                PieceCue(cs, ce, text)
            }
        change(bookId) { t ->
            if (t.pieces.any { it.s == s }) t else t.copy(pieces = t.pieces + TranscriptPiece(s, e, cues))
        }
    }

    private fun pieceValue(p: TranscriptPiece): Map<String, Any?> = mapOf("e" to p.e, "c" to p.c.map { listOf(it.s, it.e, it.t) })

    // ---- Transcribing ahead ----

    private fun kick() {
        if (!canTranscribe || job?.isActive == true) return
        job = scope.launch(Dispatchers.Default) { runAhead() }
    }

    /** Works while a wanted book plays or its transcript is open; stops after a minute idle. */
    private suspend fun runAhead() {
        var idleSince = System.currentTimeMillis()
        var ctx = 0L
        try {
            while (coroutineContext.isActive) {
                val s = player.state.value
                val bookId = s.bookId
                val book = bookId?.let { library.get(it) }
                val active = book != null && (s.isPlaying || viewing.value == bookId)
                val t = if (book != null) fileMutex.withLock { read(book.id) } else null
                val target =
                    if (active && t?.wanted == true) {
                        AudiobookTranscriptRules.nextTarget(book!!.tracks.map { it.lengthMs }, s.chapters, t.pieces, s.bookMs)
                    } else {
                        null
                    }
                if (book == null || target == null) {
                    if (System.currentTimeMillis() - idleSince > IDLE_STOP_MS) return
                    if (ctx != 0L) {
                        WhisperNative.free(ctx)
                        ctx = 0L
                    }
                    delay(2_000)
                    continue
                }
                idleSince = System.currentTimeMillis()
                Transcripts.whisperLock.withLock {
                    if (ctx == 0L) ctx = openModel(book.id)
                    if (ctx != 0L) transcribe(book, target, ctx)
                }
            }
        } finally {
            if (ctx != 0L) WhisperNative.free(ctx)
            states.update { m -> m.mapValues { (_, v) -> v.copy(workingAtMs = null, modelProgress = null) } }
        }
    }

    private suspend fun openModel(bookId: String): Long =
        try {
            val model = transcripts.whisperModel { f -> set(bookId) { it.copy(modelProgress = f) } }
            set(bookId) { it.copy(modelProgress = null) }
            WhisperNative.init(model.absolutePath).also { if (it == 0L) model.delete() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Speech model download failed", e)
            set(bookId) { it.copy(modelProgress = null, error = e.message ?: "Download failed") }
            delay(30_000)
            0L
        }

    private suspend fun transcribe(
        book: Audiobook,
        target: PieceTarget,
        ctx: Long,
    ) {
        val track = book.tracks[target.i]
        set(book.id) { it.copy(workingAtMs = target.bookStartMs, error = null) }
        try {
            val link = if (library.localFile(track) == null && track.uri == null) dropbox.temporaryLink(track.path) else null
            val decoder =
                AudioDecoder(
                    {
                        val local = library.localFile(track)
                        when {
                            local != null -> it.setDataSource(local.absolutePath)
                            track.uri != null -> it.setDataSource(app, Uri.parse(track.uri), null)
                            else -> it.setDataSource(link!!, emptyMap())
                        }
                    },
                    track.name,
                )
            val want = ((target.endMs - target.startMs) * Resampler.TARGET_RATE / 1000).toInt()
            val samples = FloatArray(want)
            var filled = 0
            val job = coroutineContext[Job]
            withContext(Dispatchers.IO) {
                decoder.decode(target.startMs) { buf, count ->
                    if (job?.isActive == false) return@decode false
                    val take = minOf(count, want - filled)
                    buf.copyInto(samples, filled, 0, take)
                    filled += take
                    filled < want
                }
            }
            if (filled < Resampler.TARGET_RATE / 4) error("No sound in ${track.name} at ${AudiobookRules.clock(target.startMs)}")
            val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
            val bytes = WhisperNative.transcribe(ctx, samples.copyOf(filled), threads, "en") ?: return // stopped
            val cues = WhisperNative.parseSegments(bytes, target.startMs)
            // The file ended first (its length is rounded): the piece still reaches its end
            val endMs = if (filled < want) target.endMs else target.startMs + filled * 1000L / Resampler.TARGET_RATE
            val piece = AudiobookTranscriptRules.piece(target, endMs.coerceAtLeast(target.startMs + 1), cues)
            change(book.id) { t ->
                if (t.pieces.any { it.s == piece.s }) t else t.copy(pieces = t.pieces + piece, unsynced = t.unsynced + piece.key)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Transcribing ${book.title} failed", e)
            set(book.id) { it.copy(error = e.message ?: e.javaClass.simpleName) }
            delay(15_000)
        } finally {
            set(book.id) { it.copy(workingAtMs = null) }
        }
    }

    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_")

    // Book ids are hex hashes, so the file name is the id
    private fun unsafe(name: String) = name

    companion object {
        private const val LOG_TAG = "FeederAbTranscripts"
        private const val IDLE_STOP_MS = 60_000L
        const val SYNC_KIND = "abtx"
    }
}
