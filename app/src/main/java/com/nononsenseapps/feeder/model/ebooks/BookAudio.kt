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
)

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
    val preparing: Boolean = false,
)

/**
 * Ebook chapters spoken ahead of time, so read aloud goes on from one chapter to the next without
 * waiting for speech to be made (and without the reader page, which may be asleep in the
 * background). Audio lives in files/book-audio/<book>/, made by [BookAudioJob] with the device's
 * offline voice. Played chapters are deleted as reading moves on, and a book's audio goes when the
 * book is finished or not opened for [STALE_DAYS] days.
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
            if (i in keep || !audioFile(bookId, i).exists()) continue
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

    /** Audio of chapter [index] made for exactly [text], or null. */
    fun prepared(
        bookId: String,
        index: Int,
        text: String,
    ): PreparedSpeech? {
        val audio = audioFile(bookId, index)
        val meta = runCatching { json.decodeFromString(AudioMeta.serializer(), metaFile(bookId, index).readText()) }.getOrNull() ?: return null
        if (!audio.isFile || meta.textHash != text.hashCode() || meta.textLength != text.length || meta.lang != langSetting()) return null
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
            )
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
        making = bookId to section.index
        makingProgress = 0f
        refresh()
        val dir = bookDir(bookId)
        val part = File(dir, "s${section.index}.part")
        val speech = openSpeechEngine(context, section.text, language, detectLanguage = false)
        try {
            val parts = speechParts(section.text, speech.locale)
            if (parts.isEmpty()) error("No text to read")
            var lastRefresh = 0L
            val (segments, words) =
                synthesizeSpeech(speech.engine, section.text, parts, dir, part) { made ->
                    makingProgress = made.part.end.toFloat() / section.text.length
                    val now = System.currentTimeMillis()
                    if (now - lastRefresh > 1000) {
                        lastRefresh = now
                        refresh()
                    }
                }
            withContext(Dispatchers.IO) {
                // Reading may have moved past this chapter meanwhile
                if (section.index !in window(bookId, currentOf(bookId))) {
                    part.delete()
                    return@withContext
                }
                // Kept compressed: about an eighth of the size
                if (encodeSpeechToAac(part, File(dir, "s${section.index}.m4a"))) part.delete() else part.renameTo(File(dir, "s${section.index}.wav"))
                val meta =
                    AudioMeta(
                        textHash = section.text.hashCode(),
                        textLength = section.text.length,
                        lang = lang,
                        durationMillis = segments.lastOrNull()?.endMillis ?: 0,
                        segments = segments.map { Span(it.start, it.end, it.startMillis, it.endMillis) },
                        words = words.map { Span(it.start, it.end, it.startMillis, it.endMillis) },
                    )
                metaFile(bookId, section.index).writeText(json.encodeToString(AudioMeta.serializer(), meta))
            }
        } finally {
            speech.shutdown()
            if (!metaFile(bookId, section.index).exists()) part.delete()
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
