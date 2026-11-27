package com.nononsenseapps.feeder.model.ebooks

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.core.content.edit
import androidx.core.content.getSystemService
import com.nononsenseapps.feeder.background.BackgroundJobId
import com.nononsenseapps.feeder.background.FeederJobService
import com.nononsenseapps.feeder.model.AppSetting
import com.nononsenseapps.feeder.model.ForcedAuto
import com.nononsenseapps.feeder.model.ForcedLocale
import com.nononsenseapps.feeder.model.LocaleOverride
import com.nononsenseapps.feeder.model.PreparedSpeech
import com.nononsenseapps.feeder.model.SpeechSegment
import com.nononsenseapps.feeder.model.SpeechWaveFile
import com.nononsenseapps.feeder.model.appendWaveFile
import com.nononsenseapps.feeder.model.encodeSpeechToAac
import com.nononsenseapps.feeder.model.openSpeechEngine
import com.nononsenseapps.feeder.model.speechParts
import com.nononsenseapps.feeder.model.synthesizeSpeech
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Chapter text from the reader page, saved so its audio can be made without the page. */
@Serializable
data class SectionText(
    val index: Int,
    val label: String,
    val text: String,
)

/** What the reader page sends: chapter texts, and the last chapter's index once the book's end is reached (else -1). */
@Serializable
data class SectionTexts(
    val texts: List<SectionText> = emptyList(),
    val last: Int = -1,
)

@Serializable
private data class Span(
    val s: Int,
    val e: Int,
    val a: Long,
    val b: Long,
)

/** What is saved next to a chapter's audio file. */
@Serializable
private data class AudioMeta(
    val textHash: Int,
    val textLength: Int,
    val lang: String,
    val durationMillis: Long,
    val segments: List<Span>,
    val words: List<Span>,
    /** Where in the text the audio starts: 0, or later for the chapter being read (spoken from where reading began) */
    val from: Int = 0,
)

/** Which text a chapter's saved chunks belong to. */
@Serializable
private data class ChunkInfo(
    val textHash: Int,
    val textLength: Int,
    val lang: String,
)

/** One chunk of a chapter being made: its sentences and words (times from the chunk's start), up to [end] in the text. */
@Serializable
private data class ChunkMeta(
    val end: Int,
    val segments: List<Span>,
    val words: List<Span>,
)

/** The sentences still to speak, when the chunks made so far reach [reached] in the text. */
internal fun remainingParts(
    parts: List<SpeechSegment>,
    reached: Int,
): List<SpeechSegment> = parts.filter { it.start >= reached }

/** The timeline moved [millis] later. */
internal fun List<SpeechSegment>.shiftedBy(millis: Long): List<SpeechSegment> = map { it.copy(startMillis = it.startMillis + millis, endMillis = it.endMillis + millis) }

private fun Span.segment() = SpeechSegment(s, e, a, b)

private fun SpeechSegment.span() = Span(start, end, startMillis, endMillis)

@Immutable
enum class SectionAudioState { PLAYED, PLAYING, READY, PREPARING, WAITING }

@Immutable
data class SectionAudio(
    val index: Int,
    val label: String,
    val state: SectionAudioState,
    val progress: Float = 0f,
    val durationMillis: Long = 0,
)

/** Offline audio of one book, for the player's status line and the Offline audio sheet. */
@Immutable
data class BookAudioState(
    val bookId: String = "",
    val current: Int = -1,
    val sections: List<SectionAudio> = emptyList(),
    val bytes: Long = 0,
    /** Chapters prepared ahead of the one being read */
    val readyAhead: Int = 0,
    /** The chapter being read is saved too, from where reading began */
    val currentReady: Boolean = false,
    val preparing: Boolean = false,
)

/**
 * Ebook chapters spoken ahead of time, so read aloud goes on from one chapter to the next without
 * waiting for speech to be made (and without the reader page, which may be asleep in the
 * background). Audio lives in files/book-audio/<book>/, made by [BookAudioJob] with the device's
 * offline voice, in chunks of [CHUNK_PARTS] sentences so a job the system stops picks up where it
 * left off instead of starting the chapter again. The chapter being read is kept too: read aloud
 * speaks it live, and that audio is saved once it is complete ([saveSpoken]). Played chapters are
 * deleted as reading moves on, and a book's audio goes when the book is finished or not opened for
 * [STALE_DAYS] days.
 */
class BookAudio private constructor(
    private val context: Context,
) {
    private val prefs = context.getSharedPreferences("book_audio", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val root = File(context.filesDir, "book-audio")
    private val _state = MutableStateFlow(BookAudioState())
    val state: StateFlow<BookAudioState> = _state.asStateFlow()
    private val _ahead = MutableStateFlow(prefs.getInt(PREF_AHEAD, DEFAULT_AHEAD))
    val ahead: StateFlow<Int> = _ahead.asStateFlow()
    private val _deletePlayed = MutableStateFlow(prefs.getBoolean(PREF_DELETE_PLAYED, true))
    val deletePlayed: StateFlow<Boolean> = _deletePlayed.asStateFlow()

    /** Chapter being made right now, and how far along (0–1). */
    @Volatile
    private var making: Pair<String, Int>? = null

    @Volatile
    private var makingProgress = 0f

    /** Chapters deleted after being played, to show as such. */
    private val deletedPlayed = mutableMapOf<String, MutableSet<Int>>()

    @Volatile
    var jobRunning = false
        internal set

    init {
        Thread { removeStale() }.start()
    }

    fun setAhead(count: Int) {
        val value = count.coerceIn(1, 10)
        _ahead.value = value
        prefs.edit { putInt(PREF_AHEAD, value) }
        activeBook()?.let { trim(it) }
        refresh()
        schedule()
    }

    fun setDeletePlayed(delete: Boolean) {
        _deletePlayed.value = delete
        prefs.edit { putBoolean(PREF_DELETE_PLAYED, delete) }
        activeBook()?.let { trim(it) }
        refresh()
    }

    private fun bookDir(bookId: String) = File(root, bookId.replace(Regex("[^A-Za-z0-9._-]"), "_"))

    private fun textFile(
        bookId: String,
        index: Int,
    ) = File(bookDir(bookId), "s$index.txt")

    /** The chapter's audio: compressed (.m4a), or the WAV when the device couldn't compress it. */
    private fun audioFile(
        bookId: String,
        index: Int,
    ): File = File(bookDir(bookId), "s$index.m4a").takeIf { it.exists() } ?: File(bookDir(bookId), "s$index.wav")

    private fun metaFile(
        bookId: String,
        index: Int,
    ) = File(bookDir(bookId), "s$index.json")

    /** Chunks of a chapter being made, kept until the chapter's audio is complete. */
    private fun chunkDir(
        bookId: String,
        index: Int,
    ) = File(bookDir(bookId), "s$index.chunks")

    private fun readMeta(
        bookId: String,
        index: Int,
    ): AudioMeta? = runCatching { json.decodeFromString(AudioMeta.serializer(), metaFile(bookId, index).readText()) }.getOrNull()

    private fun activeBook(): String? = prefs.getString(PREF_BOOK, null)

    private fun currentOf(bookId: String): Int = if (activeBook() == bookId) prefs.getInt(PREF_CURRENT, -1) else -1

    /** Language setting the audio is made in, as saved text: "" app, "auto", or a language tag. */
    fun setLanguage(
        language: LocaleOverride,
        detect: Boolean,
    ) {
        val value =
            when (language) {
                is ForcedLocale -> language.locale.toLanguageTag()
                ForcedAuto -> "auto"
                AppSetting -> if (detect) "auto" else ""
            }
        if (prefs.getString(PREF_LANG, "") == value) return
        prefs.edit { putString(PREF_LANG, value) }
        // Audio in another language is no use now
        activeBook()?.let { book -> sectionIndices(book).forEach { deleteAudio(book, it) } }
        refresh()
        schedule()
    }

    private fun langSetting(): String = prefs.getString(PREF_LANG, "") ?: ""

    /** Indices of the chapters with saved text, in reading order. */
    private fun sectionIndices(bookId: String): List<Int> =
        bookDir(bookId)
            .listFiles()
            ?.mapNotNull { f ->
                f.name
                    .takeIf { it.endsWith(".txt") }
                    ?.removePrefix("s")
                    ?.removeSuffix(".txt")
                    ?.toIntOrNull()
            }?.sorted()
            .orEmpty()

    /** The [count] chapters (with text) after [current]. */
    private fun window(
        bookId: String,
        current: Int,
    ): List<Int> = sectionIndices(bookId).filter { it > current }.take(_ahead.value)

    fun saveTexts(
        bookId: String,
        texts: List<SectionText>,
    ) {
        val dir = bookDir(bookId).apply { mkdirs() }
        for (t in texts) {
            if (t.text.isBlank()) continue
            val file = File(dir, "s${t.index}.txt")
            if (!file.exists()) file.writeText(json.encodeToString(SectionText.serializer(), t))
        }
        refresh()
        schedule()
    }

    /**
     * Reading is now at chapter [current] of [bookId]: deletes what was played (and what is out of
     * reach), and prepares the chapters after it.
     */
    fun setPosition(
        bookId: String,
        current: Int,
        label: String = "",
    ) {
        prefs.edit {
            putString(PREF_BOOK, bookId)
            putInt(PREF_CURRENT, current)
            putString(PREF_CURRENT_LABEL, label)
            putLong("used:$bookId", System.currentTimeMillis())
        }
        trim(bookId)
        refresh()
        schedule()
    }

    private fun trim(bookId: String) {
        val current = currentOf(bookId)
        if (current < 0) return
        val keep = window(bookId, current).toSet() + current
        for (i in sectionIndices(bookId)) {
            if (i in keep || (!audioFile(bookId, i).exists() && !chunkDir(bookId, i).exists())) continue
            if (i < current) {
                if (!_deletePlayed.value) continue
                synchronized(deletedPlayed) { deletedPlayed.getOrPut(bookId) { mutableSetOf() } += i }
            }
            deleteAudio(bookId, i)
        }
    }

    fun setLastSection(
        bookId: String,
        index: Int,
    ) = prefs.edit { putInt("last:$bookId", index) }

    /** True when [index] is the book's last chapter with text (once that is known). */
    fun isLastSection(
        bookId: String,
        index: Int,
    ): Boolean = prefs.getInt("last:$bookId", Int.MAX_VALUE) <= index

    /** The book was read to the end: its audio and saved text go. */
    fun finished(bookId: String) = clear(bookId)

    fun clear(bookId: String) {
        bookDir(bookId).deleteRecursively()
        synchronized(deletedPlayed) { deletedPlayed.remove(bookId) }
        prefs.edit {
            if (activeBook() == bookId) remove(PREF_BOOK)
            remove("last:$bookId")
        }
        refresh()
    }

    private fun deleteAudio(
        bookId: String,
        index: Int,
    ) {
        metaFile(bookId, index).delete()
        File(bookDir(bookId), "s$index.m4a").delete()
        File(bookDir(bookId), "s$index.wav").delete()
        File(bookDir(bookId), "s$index.part").delete()
        File(bookDir(bookId), "s$index.spoken").delete()
        chunkDir(bookId, index).deleteRecursively()
    }

    /** The saved text of a chapter. */
    fun text(
        bookId: String,
        index: Int,
    ): SectionText? = runCatching { json.decodeFromString(SectionText.serializer(), textFile(bookId, index).readText()) }.getOrNull()

    /** Index of the chapter read after [current], when its text is saved. */
    fun nextSection(
        bookId: String,
        current: Int,
    ): Int? = sectionIndices(bookId).firstOrNull { it > current }

    /** Audio of chapter [index] made for exactly [text] that covers reading from [offset], or null. */
    fun prepared(
        bookId: String,
        index: Int,
        text: String,
        offset: Int = 0,
    ): PreparedSpeech? {
        val audio = audioFile(bookId, index)
        val meta = readMeta(bookId, index) ?: return null
        if (!audio.isFile || meta.textHash != text.hashCode() || meta.textLength != text.length || meta.lang != langSetting()) return null
        // Saved from where reading began: of no use for reading from before that
        if (offset < meta.from) return null
        return PreparedSpeech(
            audio = audio,
            segments = meta.segments.map { SpeechSegment(it.s, it.e, it.a, it.b) },
            words = meta.words.map { SpeechSegment(it.s, it.e, it.a, it.b) },
            durationMillis = meta.durationMillis,
        )
    }

    /** Recomputes [state] for the book being read. */
    fun refresh() {
        val bookId = activeBook()
        if (bookId == null) {
            _state.value = BookAudioState()
            return
        }
        val current = currentOf(bookId)
        val indices = sectionIndices(bookId)
        val ahead = window(bookId, current)
        val deleted = synchronized(deletedPlayed) { deletedPlayed[bookId]?.toSet().orEmpty() }
        val now = making

        fun label(i: Int) = text(bookId, i)?.label?.ifBlank { null } ?: if (i == current) prefs.getString(PREF_CURRENT_LABEL, "").orEmpty() else ""
        val rows = mutableListOf<SectionAudio>()
        // The last chapter played and deleted, then the one being read, then the ones ahead
        deleted.filter { it < current }.maxOrNull()?.let { rows += SectionAudio(it, label(it), SectionAudioState.PLAYED) }
        if (current >= 0) rows += SectionAudio(current, label(current), SectionAudioState.PLAYING)
        var ready = 0
        for (i in ahead) {
            val meta = metaFile(bookId, i)
            rows +=
                when {
                    meta.exists() -> {
                        ready++
                        val d = runCatching { json.decodeFromString(AudioMeta.serializer(), meta.readText()).durationMillis }.getOrDefault(0)
                        SectionAudio(i, label(i), SectionAudioState.READY, 1f, d)
                    }
                    now == bookId to i -> SectionAudio(i, label(i), SectionAudioState.PREPARING, makingProgress)
                    else -> SectionAudio(i, label(i), SectionAudioState.WAITING)
                }
        }
        val bytes = bookDir(bookId).listFiles()?.filter { it.name.endsWith(".wav") || it.name.endsWith(".m4a") }?.sumOf { it.length() } ?: 0L
        _state.value =
            BookAudioState(
                bookId = bookId,
                current = current,
                sections = if (indices.isEmpty() && current < 0) emptyList() else rows,
                bytes = bytes,
                readyAhead = ready,
                preparing = now?.first == bookId,
                currentReady = current >= 0 && metaFile(bookId, current).exists(),
            )
    }

    /**
     * Read aloud finished speaking chapter [index] live into [audio] (from where reading began to the
     * chapter's end): keeps it as the chapter's audio, unless audio covering more is already saved.
     */
    fun saveSpoken(
        bookId: String,
        index: Int,
        label: String,
        text: String,
        audio: File,
        segments: List<SpeechSegment>,
        words: List<SpeechSegment>,
    ) {
        if (activeBook() != bookId || currentOf(bookId) != index || segments.isEmpty()) return
        val from = segments.first().start
        readMeta(bookId, index)?.let { if (it.textHash == text.hashCode() && it.lang == langSetting() && it.from <= from && audioFile(bookId, index).isFile) return }
        val dir = bookDir(bookId).apply { mkdirs() }
        val spoken = File(dir, "s$index.spoken")
        try {
            // The live file goes when read aloud starts something else: copy it first
            audio.copyTo(spoken, overwrite = true)
            val lang = langSetting()
            textFile(bookId, index).takeUnless { it.exists() }?.writeText(json.encodeToString(SectionText.serializer(), SectionText(index, label, text)))
            metaFile(bookId, index).delete()
            File(dir, "s$index.m4a").delete()
            File(dir, "s$index.wav").delete()
            chunkDir(bookId, index).deleteRecursively()
            if (encodeSpeechToAac(spoken, File(dir, "s$index.m4a"))) spoken.delete() else spoken.renameTo(File(dir, "s$index.wav"))
            if (currentOf(bookId) != index && index !in window(bookId, currentOf(bookId))) {
                // Reading moved on while this was saved
                deleteAudio(bookId, index)
                return
            }
            val meta =
                AudioMeta(
                    textHash = text.hashCode(),
                    textLength = text.length,
                    lang = lang,
                    durationMillis = segments.last().endMillis,
                    segments = segments.map { it.span() },
                    words = words.map { it.span() },
                    from = from,
                )
            metaFile(bookId, index).writeText(json.encodeToString(AudioMeta.serializer(), meta))
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Could not keep the audio of chapter $index", e)
            spoken.delete()
        } finally {
            refresh()
        }
    }

    /** Starts the background job that makes missing chapter audio, unless it is running. */
    fun schedule() {
        if (jobRunning) return
        val book = activeBook() ?: return
        val current = currentOf(book)
        if (window(book, current).none { !metaFile(book, it).exists() }) return
        val scheduler: JobScheduler = context.getSystemService() ?: return
        val job =
            JobInfo
                .Builder(BackgroundJobId.BOOK_AUDIO.jobId, ComponentName(context, FeederJobService::class.java))
                .setRequiresBatteryNotLow(true)
                .build()
        runCatching { scheduler.schedule(job) }.onFailure { Log.w(LOG_TAG, "Could not schedule book audio", it) }
    }

    /** The next chapter to make: (book, index, text), or null when everything ahead is ready. */
    private fun nextToMake(): Pair<String, SectionText>? {
        val book = activeBook() ?: return null
        val current = currentOf(book)
        val index = window(book, current).firstOrNull { !metaFile(book, it).exists() } ?: return null
        return text(book, index)?.let { book to it }
    }

    /** Makes the missing audio ahead, one chapter at a time, until all is ready. */
    internal suspend fun makeMissing() {
        var failures = 0
        while (true) {
            val (book, section) = nextToMake() ?: break
            try {
                make(book, section)
                failures = 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Book audio failed for chapter ${section.index}", e)
                if (++failures >= 2) break
            } finally {
                making = null
                refresh()
            }
        }
    }

    /** The saved chunks of a chapter being made, in order (each with its audio and timeline). */
    private fun loadChunks(dir: File): List<Triple<Int, File, ChunkMeta>> =
        dir
            .listFiles()
            ?.mapNotNull { f ->
                val k = f.name.takeIf { it.startsWith("c") && it.endsWith(".json") }?.removePrefix("c")?.removeSuffix(".json")?.toIntOrNull() ?: return@mapNotNull null
                val wav = File(dir, "c$k.wav").takeIf { it.isFile } ?: return@mapNotNull null
                val meta = runCatching { json.decodeFromString(ChunkMeta.serializer(), f.readText()) }.getOrNull() ?: return@mapNotNull null
                Triple(k, wav, meta)
            }?.sortedBy { it.first }
            .orEmpty()

    private suspend fun make(
        bookId: String,
        section: SectionText,
    ) {
        val lang = langSetting()
        val language: LocaleOverride =
            when (lang) {
                "" -> AppSetting
                "auto" -> ForcedAuto
                else -> ForcedLocale(Locale.forLanguageTag(lang))
            }
        val index = section.index
        val dir = bookDir(bookId)
        val chunks = chunkDir(bookId, index)
        // Chunks made before (the job was stopped, or failed) carry on, when they are for this text
        val done =
            withContext(Dispatchers.IO) {
                val info = ChunkInfo(section.text.hashCode(), section.text.length, lang)
                val infoFile = File(chunks, "info.json")
                val saved = runCatching { json.decodeFromString(ChunkInfo.serializer(), infoFile.readText()) }.getOrNull()
                if (saved != info) {
                    chunks.deleteRecursively()
                    chunks.mkdirs()
                    infoFile.writeText(json.encodeToString(ChunkInfo.serializer(), info))
                }
                loadChunks(chunks)
            }
        val reached = done.lastOrNull()?.third?.end ?: 0
        making = bookId to index
        makingProgress = reached.toFloat() / section.text.length
        refresh()
        val speech = openSpeechEngine(context, section.text, language, detectLanguage = false)
        try {
            val todo = remainingParts(speechParts(section.text, speech.locale), reached)
            if (todo.isEmpty() && done.isEmpty()) error("No text to read")
            var k = (done.lastOrNull()?.first ?: -1) + 1
            var lastRefresh = 0L
            for (batch in todo.chunked(CHUNK_PARTS)) {
                val temporary = File(chunks, "c$k.tmp")
                try {
                    val (segments, words) =
                        synthesizeSpeech(speech.engine, section.text, batch, chunks, temporary) { made ->
                            makingProgress = made.part.end.toFloat() / section.text.length
                            val now = System.currentTimeMillis()
                            if (now - lastRefresh > 1000) {
                                lastRefresh = now
                                refresh()
                            }
                        }
                    withContext(Dispatchers.IO) {
                        check(temporary.renameTo(File(chunks, "c$k.wav"))) { "Could not keep chapter audio" }
                        // Written last: a chunk counts once its timeline is saved
                        File(chunks, "c$k.json").writeText(json.encodeToString(ChunkMeta.serializer(), ChunkMeta(batch.last().end, segments.map { it.span() }, words.map { it.span() })))
                    }
                } finally {
                    temporary.delete()
                }
                k++
            }
        } finally {
            speech.shutdown()
        }
        withContext(Dispatchers.IO) {
            // Reading may have moved past this chapter meanwhile
            val current = currentOf(bookId)
            if (index != current && index !in window(bookId, current)) {
                deleteAudio(bookId, index)
                return@withContext
            }
            val part = File(dir, "s$index.part")
            try {
                val segments = mutableListOf<SpeechSegment>()
                val words = mutableListOf<SpeechSegment>()
                SpeechWaveFile(part).use { wave ->
                    for ((_, wav, meta) in loadChunks(chunks)) {
                        val offset = wave.durationMillis()
                        appendWaveFile(wave, wav)
                        segments += meta.segments.map { it.segment() }.shiftedBy(offset)
                        words += meta.words.map { it.segment() }.shiftedBy(offset)
                    }
                    wave.finish()
                }
                // Kept compressed: about an eighth of the size
                if (encodeSpeechToAac(part, File(dir, "s$index.m4a"))) part.delete() else part.renameTo(File(dir, "s$index.wav"))
                val meta =
                    AudioMeta(
                        textHash = section.text.hashCode(),
                        textLength = section.text.length,
                        lang = lang,
                        durationMillis = segments.lastOrNull()?.endMillis ?: 0,
                        segments = segments.map { it.span() },
                        words = words.map { it.span() },
                    )
                metaFile(bookId, index).writeText(json.encodeToString(AudioMeta.serializer(), meta))
                chunks.deleteRecursively()
            } finally {
                if (!metaFile(bookId, index).exists()) part.delete()
            }
        }
    }

    /** Books not opened for [STALE_DAYS] days lose their audio. */
    private fun removeStale() {
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(STALE_DAYS)
        root.listFiles()?.forEach { dir ->
            val used =
                prefs.all.entries
                    .firstOrNull { (k, _) -> k.startsWith("used:") && bookDir(k.removePrefix("used:")) == dir }
                    ?.value as? Long ?: dir.lastModified()
            if (used < cutoff) dir.deleteRecursively()
        }
        refresh()
    }

    companion object {
        private const val LOG_TAG = "FEEDER_BOOK_AUDIO"
        private const val PREF_AHEAD = "ahead"
        private const val PREF_DELETE_PLAYED = "delete_played"
        private const val PREF_BOOK = "book"
        private const val PREF_CURRENT = "current"
        private const val PREF_CURRENT_LABEL = "current_label"
        private const val PREF_LANG = "lang"
        const val DEFAULT_AHEAD = 3

        /** Sentences per saved chunk: the most that is made again after the job is stopped. */
        const val CHUNK_PARTS = 12
        const val STALE_DAYS = 7L
        val AHEAD_CHOICES = listOf(1, 2, 3, 5)

        @Volatile
        private var instance: BookAudio? = null

        fun get(context: Context): BookAudio =
            instance ?: synchronized(this) {
                instance ?: BookAudio(context.applicationContext).also { instance = it }
            }
    }
}
