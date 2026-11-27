package com.nononsenseapps.feeder.model.playlists

import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.core.content.edit
import androidx.core.content.getSystemService
import com.nononsenseapps.feeder.archmodel.Repository
import com.nononsenseapps.feeder.background.BackgroundJobId
import com.nononsenseapps.feeder.background.FeederJobService
import com.nononsenseapps.feeder.model.AppSetting
import com.nononsenseapps.feeder.model.ForcedAuto
import com.nononsenseapps.feeder.model.ForcedLocale
import com.nononsenseapps.feeder.model.LocaleOverride
import com.nononsenseapps.feeder.model.PreparedSpeech
import com.nononsenseapps.feeder.model.ReadAloudQueue
import com.nononsenseapps.feeder.model.SpeechSegment
import com.nononsenseapps.feeder.model.TTSStateHolder
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
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import java.io.File
import java.util.concurrent.TimeUnit

@Serializable
private data class Span(
    val s: Int,
    val e: Int,
    val a: Long,
    val b: Long,
)

@Serializable
private data class ArticleAudioMeta(
    val textHash: Int,
    val lang: String,
    val durationMillis: Long,
    val segments: List<Span>,
    val words: List<Span>,
)

/** The articles asked for with "Get next 20 articles ready offline", in order. */
@Serializable
private data class Batch(
    val playlistId: String,
    val ids: List<Long>,
)

@Immutable
data class PlaylistAudioState(
    /** The playlist the last request was for; null when none is going on or kept. */
    val playlistId: String? = null,
    val ids: List<Long> = emptyList(),
    /** Articles of any playlist whose audio is ready, and how long each plays (ms). */
    val ready: Map<Long, Long> = emptyMap(),
    val preparingId: Long? = null,
    val progress: Float = 0f,
    /** Articles in the request that could not be prepared (no text, or speech failed). */
    val failed: Set<Long> = emptySet(),
    val bytes: Long = 0,
    val running: Boolean = false,
) {
    val done: Int get() = ids.count { it in ready || it in failed }
    val readyInBatch: Int get() = ids.count { it in ready }
}

/**
 * Playlist articles spoken ahead on demand ("Get next 20 articles ready offline"): each article's
 * full text is fetched and spoken into a compressed audio file, so it plays at once and without a
 * connection. An article's audio is deleted once it has played, and anything not played within
 * [STALE_DAYS] days. Made by [com.nononsenseapps.feeder.background.PlaylistAudioJob].
 */
class PlaylistAudio(
    override val di: DI,
) : DIAware {
    private val context: Application by instance()
    private val repository: Repository by instance()
    private val readAloudQueue: ReadAloudQueue by instance()
    private val tts: TTSStateHolder by instance()
    private val prefs = context.getSharedPreferences("playlist_audio", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val root = File(context.filesDir, "playlist-audio").apply { mkdirs() }
    private val _state = MutableStateFlow(PlaylistAudioState())
    val state: StateFlow<PlaylistAudioState> = _state.asStateFlow()

    @Volatile
    private var preparing: Pair<Long, Float>? = null
    private val failed = mutableSetOf<Long>()

    @Volatile
    var jobRunning = false
        internal set

    init {
        Thread {
            removeStale()
            refresh()
            // A request cut short (app closed or updated) carries on
            if (batch()?.ids?.any { !metaFile(it).exists() } == true) schedule()
        }.start()
    }

    private fun textFile(id: Long) = File(root, "$id.txt")

    private fun metaFile(id: Long) = File(root, "$id.json")

    private fun audioFile(id: Long): File = File(root, "$id.m4a").takeIf { it.exists() } ?: File(root, "$id.wav")

    private fun batch(): Batch? = prefs.getString(PREF_BATCH, null)?.let { runCatching { json.decodeFromString(Batch.serializer(), it) }.getOrNull() }

    /** The language setting audio is made in; audio made in another one is not used. */
    private fun langKey(): String =
        when (val l = tts.language.value) {
            is ForcedLocale -> l.locale.toLanguageTag()
            ForcedAuto -> "auto"
            AppSetting -> if (repository.useDetectLanguage.value) "auto" else ""
        }

    private fun languageOf(key: String): LocaleOverride =
        when (key) {
            "" -> AppSetting
            "auto" -> ForcedAuto
            else -> ForcedLocale(java.util.Locale.forLanguageTag(key))
        }

    /** Prepares [ids] (articles, in play order) of [playlistId]. Replaces an earlier request. */
    fun start(
        playlistId: String,
        ids: List<Long>,
    ) {
        synchronized(failed) { failed.clear() }
        prefs.edit { putString(PREF_BATCH, json.encodeToString(Batch.serializer(), Batch(playlistId, ids))) }
        refresh()
        schedule()
    }

    /** Stops preparing; what is ready stays. */
    fun cancel() {
        val b = batch() ?: return
        val kept = b.ids.filter { metaFile(it).exists() }
        prefs.edit { putString(PREF_BATCH, json.encodeToString(Batch.serializer(), b.copy(ids = kept))) }
        context.getSystemService<JobScheduler>()?.cancel(BackgroundJobId.PLAYLIST_AUDIO.jobId)
        refresh()
    }

    /** Deletes the audio of the last request. */
    fun removeAll() {
        context.getSystemService<JobScheduler>()?.cancel(BackgroundJobId.PLAYLIST_AUDIO.jobId)
        batch()?.ids?.forEach { delete(it) }
        prefs.edit { remove(PREF_BATCH) }
        refresh()
    }

    /** Audio and text of article [id] when it is ready (in the current language), else null. */
    fun prepared(id: Long): Pair<String, PreparedSpeech>? {
        val meta = runCatching { json.decodeFromString(ArticleAudioMeta.serializer(), metaFile(id).readText()) }.getOrNull() ?: return null
        val text = runCatching { textFile(id).readText() }.getOrNull() ?: return null
        val audio = audioFile(id)
        if (!audio.isFile || meta.textHash != text.hashCode() || meta.lang != langKey()) return null
        return text to
            PreparedSpeech(
                audio = audio,
                segments = meta.segments.map { SpeechSegment(it.s, it.e, it.a, it.b) },
                words = meta.words.map { SpeechSegment(it.s, it.e, it.a, it.b) },
                durationMillis = meta.durationMillis,
            )
    }

    /** Article [id] played to the end: its audio is no longer needed. */
    fun played(id: Long) {
        if (!metaFile(id).exists() && !textFile(id).exists()) return
        delete(id)
        refresh()
    }

    private fun delete(id: Long) {
        metaFile(id).delete()
        textFile(id).delete()
        File(root, "$id.m4a").delete()
        File(root, "$id.wav").delete()
        File(root, "$id.part").delete()
    }

    fun refresh() {
        val ready =
            root
                .listFiles()
                ?.filter { it.name.endsWith(".json") }
                ?.mapNotNull { f ->
                    val id = f.name.removeSuffix(".json").toLongOrNull() ?: return@mapNotNull null
                    val d = runCatching { json.decodeFromString(ArticleAudioMeta.serializer(), f.readText()).durationMillis }.getOrDefault(0)
                    id to d
                }?.toMap()
                .orEmpty()
        val bytes = root.listFiles()?.filter { it.name.endsWith(".m4a") || it.name.endsWith(".wav") }?.sumOf { it.length() } ?: 0L
        val b = batch()
        val p = preparing
        _state.value =
            PlaylistAudioState(
                playlistId = b?.playlistId,
                ids = b?.ids.orEmpty(),
                ready = ready,
                preparingId = p?.first,
                progress = p?.second ?: 0f,
                failed = synchronized(failed) { failed.toSet() },
                bytes = bytes,
                running = jobRunning || (b != null && b.ids.any { it !in ready && it !in failed }),
            )
    }

    private fun schedule() {
        if (jobRunning) return
        val scheduler: JobScheduler = context.getSystemService() ?: return
        val job =
            JobInfo
                .Builder(BackgroundJobId.PLAYLIST_AUDIO.jobId, ComponentName(context, FeederJobService::class.java))
                .setRequiresBatteryNotLow(true)
                .build()
        runCatching { scheduler.schedule(job) }.onFailure { Log.w(LOG_TAG, "Could not schedule playlist audio", it) }
    }

    /** Prepares the requested articles one by one until all are ready or failed. */
    internal suspend fun makeMissing() {
        refresh()
        while (true) {
            val b = batch() ?: break
            val id = b.ids.firstOrNull { !metaFile(it).exists() && it !in synchronized(failed) { failed } } ?: break
            try {
                if (!make(id)) synchronized(failed) { failed += id }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Playlist audio failed for $id", e)
                synchronized(failed) { failed += id }
            } finally {
                preparing = null
                refresh()
            }
        }
    }

    /** False when the article has nothing to read. */
    private suspend fun make(id: Long): Boolean {
        preparing = id to 0f
        refresh()
        val article = withContext(Dispatchers.IO) { repository.getArticle(id) } ?: return false
        if (itemKind(article.link, article.enclosure.link.takeIf { article.enclosure.present }, article.enclosure.type) != ItemKind.ARTICLE) return false
        // The same text playing the article would read (full text when the feed uses it)
        val text = readAloudQueue.speechTextFor(article)?.takeIf { it.isNotBlank() } ?: return false
        val lang = langKey()
        val part = File(root, "$id.part")
        val speech = openSpeechEngine(context, text, languageOf(lang), detectLanguage = false)
        try {
            val parts = speechParts(text, speech.locale)
            if (parts.isEmpty()) return false
            var last = 0L
            val (segments, words) =
                synthesizeSpeech(speech.engine, text, parts, root, part) { made ->
                    preparing = id to made.part.end.toFloat() / text.length
                    val now = System.currentTimeMillis()
                    if (now - last > 1000) {
                        last = now
                        refresh()
                    }
                }
            withContext(Dispatchers.IO) {
                textFile(id).writeText(text)
                if (encodeSpeechToAac(part, File(root, "$id.m4a"))) part.delete() else part.renameTo(File(root, "$id.wav"))
                val meta =
                    ArticleAudioMeta(
                        textHash = text.hashCode(),
                        lang = lang,
                        durationMillis = segments.lastOrNull()?.endMillis ?: 0,
                        segments = segments.map { Span(it.start, it.end, it.startMillis, it.endMillis) },
                        words = words.map { Span(it.start, it.end, it.startMillis, it.endMillis) },
                    )
                metaFile(id).writeText(json.encodeToString(ArticleAudioMeta.serializer(), meta))
            }
            return true
        } finally {
            speech.shutdown()
            part.delete()
        }
    }

    /** Audio not played within [STALE_DAYS] days goes. */
    private fun removeStale() {
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(STALE_DAYS)
        root.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    companion object {
        private const val LOG_TAG = "FEEDER_PLAYLIST_AUDIO"
        private const val PREF_BATCH = "batch"
        const val STALE_DAYS = 7L
        const val BATCH_SIZE = 20
    }
}
