package com.nononsenseapps.feeder.model.transcript

import android.app.Application
import android.util.Log
import com.nononsenseapps.feeder.ApplicationCoroutineScope
import com.nononsenseapps.feeder.db.room.FeedItemDao
import com.nononsenseapps.feeder.model.offline.OfflineFiles
import com.nononsenseapps.feeder.model.offline.OfflineVideos
import com.nononsenseapps.feeder.model.youtube.SubtitleTrack
import com.nononsenseapps.feeder.model.youtube.youtubeVideoId
import com.nononsenseapps.feeder.model.youtube.youtubeWatchUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import java.io.File
import kotlin.math.abs

enum class TranscriptSource { FEED, PHONE, YOUTUBE }

enum class TranscriptStatus {
    /** Not looked for yet. */
    UNKNOWN,

    /** Looking on the phone and in the feed. */
    CHECKING,

    /** The feed has none; one can be made on the phone. */
    NONE,

    /** [TranscriptState.cues] has the whole transcript. */
    READY,
    DOWNLOADING_MODEL,
    DOWNLOADING_AUDIO,

    /** Speech to text is running; [TranscriptState.cues] grows as it goes. */
    TRANSCRIBING,

    /** Made on the phone up to [TranscriptState.doneMs], then stopped. Can carry on. */
    PAUSED,
    FAILED,
}

data class TranscriptState(
    val itemId: Long,
    val status: TranscriptStatus = TranscriptStatus.UNKNOWN,
    val cues: List<TranscriptCue> = emptyList(),
    val source: TranscriptSource? = null,
    /** 0..1 for downloads and transcribing. */
    val progress: Float = 0f,
    val doneMs: Long = 0L,
    val durationMs: Long = 0L,
    val error: String? = null,
)

@Serializable
private data class StoredTranscript(
    val source: TranscriptSource,
    val cues: List<TranscriptCue>,
    val complete: Boolean,
    val doneMs: Long = 0L,
    val durationMs: Long = 0L,
)

/**
 * Podcast transcripts, shown as subtitles that follow playback.
 *
 * 1. The feed's own transcript (Podcasting 2.0 `podcast:transcript`: VTT, SRT or JSON). For a
 *    YouTube video, its captions instead (the saved copy's, else from yt-dlp): the uploader's own
 *    in English first, then YouTube's automatic ones.
 * 2. Otherwise, on request, speech to text on the phone with whisper.cpp: no account, nothing
 *    leaves the phone. The speech model downloads once (about 60 MB). Long episodes are done in
 *    five-minute pieces, each saved as it finishes, so subtitles appear while it runs and a stop
 *    (app closed, Stop pressed) carries on from the last piece.
 *
 * Transcripts are kept in files/transcripts/<item id>.json.
 */
class Transcripts(
    override val di: DI,
) : DIAware {
    private val app: Application by instance()
    private val dao: FeedItemDao by instance()
    private val okHttpClient: OkHttpClient by instance()
    private val offlineFiles: OfflineFiles by instance()
    private val offlineVideos: OfflineVideos by instance()
    private val scope: ApplicationCoroutineScope by instance()

    private val json = Json { ignoreUnknownKeys = true }
    private val dir by lazy { File(app.filesDir, "transcripts").apply { mkdirs() } }
    private val modelDir by lazy { File(app.filesDir, "whisper").apply { mkdirs() } }
    private val audioCacheDir by lazy { File(app.cacheDir, "transcribe").apply { mkdirs() } }
    private val downloadClient: OkHttpClient by lazy { okHttpClient.newBuilder().cache(null).build() }

    private val states = MutableStateFlow<Map<Long, TranscriptState>>(emptyMap())
    private val loadMutex = Mutex()

    private var generating: Job? = null
    private val _generatingId = MutableStateFlow<Long?>(null)

    /** The episode being transcribed on the phone, if any (one at a time). */
    val generatingId: StateFlow<Long?> = _generatingId.asStateFlow()

    /** True where speech to text is built for this phone's processor. */
    val canGenerate: Boolean get() = WhisperNative.available

    val modelSizeMb: Int get() = MODEL_SIZE_MB

    fun state(itemId: Long): Flow<TranscriptState> = states.map { it[itemId] ?: TranscriptState(itemId) }

    fun current(itemId: Long): TranscriptState = states.value[itemId] ?: TranscriptState(itemId)

    private fun set(
        itemId: Long,
        change: (TranscriptState) -> TranscriptState,
    ) = states.update { it + (itemId to change(it[itemId] ?: TranscriptState(itemId))) }

    /** Finds the episode's transcript: saved on the phone, else in its feed. Does nothing if already known. */
    fun load(itemId: Long) {
        val status = current(itemId).status
        if (status != TranscriptStatus.UNKNOWN && status != TranscriptStatus.FAILED) return
        set(itemId) { it.copy(status = TranscriptStatus.CHECKING, error = null) }
        scope.launch(Dispatchers.IO) {
            loadMutex.withLock {
                try {
                    readStored(itemId)?.let { stored ->
                        set(itemId) {
                            it.copy(
                                status = if (stored.complete) TranscriptStatus.READY else TranscriptStatus.PAUSED,
                                cues = stored.cues,
                                source = stored.source,
                                doneMs = stored.doneMs,
                                durationMs = stored.durationMs,
                                progress = if (stored.durationMs > 0) stored.doneMs / stored.durationMs.toFloat() else 0f,
                            )
                        }
                        return@withLock
                    }
                    val video = youtubeVideoId(dao.loadFeedItem(itemId)?.link)
                    val source = if (video != null) TranscriptSource.YOUTUBE else TranscriptSource.FEED
                    val cues = if (video != null) fromYouTube(itemId, video) else fromFeed(itemId)
                    if (cues.isNotEmpty()) {
                        write(itemId, StoredTranscript(source, cues, complete = true))
                        set(itemId) { it.copy(status = TranscriptStatus.READY, cues = cues, source = source) }
                    } else {
                        set(itemId) { it.copy(status = TranscriptStatus.NONE) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Looking for a transcript of $itemId failed", e)
                    // No feed transcript reachable (offline…): making one on the phone still works
                    set(itemId) { it.copy(status = TranscriptStatus.NONE, error = e.message) }
                }
            }
        }
    }

    /** Transcribes the episode on the phone, or carries on where it stopped. */
    fun generate(itemId: Long) {
        if (!canGenerate) return
        if (generating?.isActive == true) {
            if (_generatingId.value == itemId) return
            stopGenerating()
        }
        _generatingId.value = itemId
        TranscriptionService.start(app)
        generating =
            scope.launch(Dispatchers.Default) {
                try {
                    runWhisper(itemId)
                } catch (e: CancellationException) {
                    set(itemId) { it.copy(status = if (it.cues.isEmpty() && it.doneMs == 0L) TranscriptStatus.NONE else TranscriptStatus.PAUSED) }
                    throw e
                } catch (e: Throwable) {
                    Log.e(LOG_TAG, "Transcribing $itemId failed", e)
                    set(itemId) { it.copy(status = TranscriptStatus.FAILED, error = e.message ?: e.javaClass.simpleName) }
                } finally {
                    if (_generatingId.value == itemId) _generatingId.value = null
                    TranscriptionService.stop(app)
                }
            }
    }

    fun stopGenerating() {
        if (generating?.isActive == true) {
            WhisperNative.takeIf { it.available }?.cancel()
            generating?.cancel()
        }
    }

    /** The whole transcript as text, headed by the episode's title, for copying. */
    suspend fun plainText(itemId: Long): String {
        val title = withContext(Dispatchers.IO) { dao.loadFeedItem(itemId)?.plainTitle }
        return current(itemId).cues.toPlainText(title)
    }

    /** Forgets the transcript (a phone-made one can then be made again). */
    fun delete(itemId: Long) {
        if (_generatingId.value == itemId) stopGenerating()
        scope.launch(Dispatchers.IO) {
            File(dir, "$itemId.json").delete()
            set(itemId) { TranscriptState(itemId) }
            load(itemId)
        }
    }

    private suspend fun fromFeed(itemId: Long): List<TranscriptCue> {
        val item = dao.loadFeedItemWithFeed(itemId) ?: return emptyList()
        val feedUrl = item.feedUrl.toString().takeIf { it.isNotBlank() } ?: return emptyList()
        val xml = fetchText(feedUrl) ?: return emptyList()
        val links = FeedTranscriptLinks.find(xml, item.guid, item.enclosureLink)
        val link = FeedTranscriptLinks.best(links) ?: return emptyList()
        val content = fetchText(link.url) ?: return emptyList()
        return TranscriptFormats.parse(content, link.type, link.url)
    }

    /** The video's captions: from the saved copy if there is one, else the ones yt-dlp lists. */
    private suspend fun fromYouTube(
        itemId: Long,
        videoId: String,
    ): List<TranscriptCue> {
        offlineFiles
            .savedVideo(itemId)
            ?.subtitles
            ?.sortedByDescending { it.name.contains(".en") }
            ?.firstNotNullOfOrNull { file -> parseYouTube(file.readText()).takeIf { it.isNotEmpty() } }
            ?.let { return it }
        val details = offlineVideos.videoDetails(youtubeWatchUrl(videoId))
        val track = bestCaptions(details.subtitles) ?: return emptyList()
        val content = fetchText(track.url, details.userAgent) ?: return emptyList()
        return parseYouTube(content)
    }

    private fun parseYouTube(vtt: String): List<TranscriptCue> =
        // Automatic captions carry a time for each word and roll up line by line
        if (WORD_TIMING.containsMatchIn(vtt)) {
            TranscriptFormats.parseYouTubeAutoVtt(vtt)
        } else {
            TranscriptFormats.parse(vtt, "text/vtt")
        }

    private fun fetchText(
        url: String,
        userAgent: String? = null,
    ): String? =
        okHttpClient.newCall(Request.Builder().url(url).apply { userAgent?.let { header("User-Agent", it) } }.build()).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()
        }

    private suspend fun runWhisper(itemId: Long) {
        val stored = readStored(itemId)?.takeIf { it.source == TranscriptSource.PHONE }
        val cues = stored?.cues?.toMutableList() ?: mutableListOf()
        var doneMs = stored?.doneMs ?: 0L
        set(itemId) { it.copy(status = TranscriptStatus.DOWNLOADING_MODEL, source = TranscriptSource.PHONE, cues = cues.toList(), error = null) }

        val model = ensureModel { fraction -> set(itemId) { it.copy(status = TranscriptStatus.DOWNLOADING_MODEL, progress = fraction) } }
        val (audio, temporary) = audioFile(itemId)
        try {
            val decoder = AudioDecoder(audio)
            val durationMs = decoder.durationMs
            set(itemId) {
                it.copy(
                    status = TranscriptStatus.TRANSCRIBING,
                    durationMs = durationMs,
                    doneMs = doneMs,
                    progress = if (durationMs > 0) doneMs / durationMs.toFloat() else 0f,
                )
            }
            val ctx = WhisperNative.init(model.absolutePath)
            if (ctx == 0L) {
                model.delete()
                error("The speech model could not be loaded. It will download again next time.")
            }
            try {
                val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
                val chunk = FloatArray(CHUNK_SAMPLES + MAX_CARRY_SAMPLES)
                var filled = 0
                var chunkStartMs = doneMs

                fun transcribe(end: Int) {
                    val bytes =
                        WhisperNative.transcribe(ctx, chunk.copyOf(end), threads, LANGUAGE)
                            ?: throw CancellationException("Stopped")
                    cues += WhisperNative.parseSegments(bytes, chunkStartMs)
                    doneMs = chunkStartMs + end * 1000L / Resampler.TARGET_RATE
                    val finished = doneMs
                    write(itemId, StoredTranscript(TranscriptSource.PHONE, cues.toList(), complete = false, doneMs = finished, durationMs = durationMs))
                    set(itemId) {
                        it.copy(
                            cues = cues.toList(),
                            doneMs = finished,
                            progress = if (durationMs > 0) (finished / durationMs.toFloat()).coerceAtMost(1f) else 0f,
                        )
                    }
                }

                val job = currentCoroutineContext()[Job]
                decoder.decode(doneMs) { samples, count ->
                    if (job?.isActive == false) return@decode false
                    var offset = 0
                    while (offset < count) {
                        val take = minOf(count - offset, chunk.size - filled)
                        samples.copyInto(chunk, filled, offset, offset + take)
                        filled += take
                        offset += take
                        // The first piece is short, so the first lines show up within seconds
                        val target = if (chunkStartMs == 0L) FIRST_CHUNK_SAMPLES else CHUNK_SAMPLES
                        if (filled >= target + MAX_CARRY_SAMPLES) {
                            // Ends the piece in the quietest moment near its end, not mid-word
                            val cut = quietestCut(chunk, target, target + MAX_CARRY_SAMPLES)
                            transcribe(cut)
                            chunk.copyInto(chunk, 0, cut, filled)
                            filled -= cut
                            chunkStartMs = doneMs
                        }
                    }
                    true
                }
                currentCoroutineContext().ensureActive()
                if (filled > Resampler.TARGET_RATE / 4) transcribe(filled)
            } finally {
                WhisperNative.free(ctx)
            }
            val total = maxOf(durationMs, doneMs)
            write(itemId, StoredTranscript(TranscriptSource.PHONE, cues.toList(), complete = true, doneMs = total, durationMs = total))
            set(itemId) { it.copy(status = TranscriptStatus.READY, cues = cues.toList(), doneMs = total, durationMs = total, progress = 1f) }
        } finally {
            // A streamed episode's copy is kept only while it is unfinished, to carry on later
            if (temporary && current(itemId).status == TranscriptStatus.READY) audio.delete()
        }
    }

    /** The episode's audio: the downloaded episode, else a copy fetched for transcribing. */
    private suspend fun audioFile(itemId: Long): Pair<File, Boolean> {
        offlineFiles.savedAudio(itemId)?.let { return it to false }
        offlineFiles.savedVideo(itemId)?.let { saved -> return (saved.audio ?: saved.video) to false }
        val target = File(audioCacheDir, "$itemId.audio")
        if (target.isFile && target.length() > 0) return target to true
        val item = withContext(Dispatchers.IO) { dao.loadFeedItem(itemId) }
        val videoId = youtubeVideoId(item?.link)
        var userAgent: String? = null
        val url =
            if (videoId != null) {
                // A YouTube video: its sound stream in the video's own language
                val details = offlineVideos.videoDetails(youtubeWatchUrl(videoId))
                userAgent = details.userAgent
                details.audios.firstOrNull()?.url ?: error("This video has no sound stream")
            } else {
                item?.enclosureLink ?: error("This episode has no audio file")
            }
        set(itemId) { it.copy(status = TranscriptStatus.DOWNLOADING_AUDIO, progress = 0f) }
        download(url, target, userAgent) { fraction -> set(itemId) { it.copy(status = TranscriptStatus.DOWNLOADING_AUDIO, progress = fraction) } }
        return target to true
    }

    private suspend fun ensureModel(onProgress: (Float) -> Unit): File {
        val model = File(modelDir, MODEL_FILE)
        if (model.isFile && model.length() > MODEL_SIZE_MB * 900_000L) return model
        download(MODEL_URL, model, onProgress = onProgress)
        return model
    }

    private suspend fun download(
        url: String,
        target: File,
        userAgent: String? = null,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val part = File(target.parentFile, target.name + ".part")
        downloadClient.newCall(Request.Builder().url(url).apply { userAgent?.let { header("User-Agent", it) } }.build()).execute().use { response ->
            if (!response.isSuccessful) error("Download failed: HTTP ${response.code}")
            val body = response.body ?: error("Download failed: empty")
            val total = body.contentLength()
            var read = 0L
            var lastReport = 0L
            body.byteStream().use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        read += n
                        if (total > 0 && read - lastReport > 512 * 1024) {
                            lastReport = read
                            onProgress(read / total.toFloat())
                        }
                    }
                }
            }
            if (total > 0 && read != total) error("Download was cut short")
        }
        if (!part.renameTo(target)) error("Could not save ${target.name}")
    }

    private fun readStored(itemId: Long): StoredTranscript? =
        File(dir, "$itemId.json")
            .takeIf { it.isFile }
            ?.let { file -> runCatching { json.decodeFromString<StoredTranscript>(file.readText()) }.getOrNull() }

    private fun write(
        itemId: Long,
        transcript: StoredTranscript,
    ) {
        val file = File(dir, "$itemId.json")
        val temp = File(dir, "$itemId.json.tmp")
        temp.writeText(json.encodeToString(StoredTranscript.serializer(), transcript))
        temp.renameTo(file)
    }

    companion object {
        private const val LOG_TAG = "FeederTranscripts"

        // whisper.cpp's English base model, 5-bit quantized: good accuracy, several times faster
        // than real time on a recent phone
        private const val MODEL_FILE = "ggml-base.en-q5_1.bin"
        private const val MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$MODEL_FILE"
        private const val MODEL_SIZE_MB = 57
        private const val LANGUAGE = "en"

        private val WORD_TIMING = Regex("<\\d{2}:\\d{2}:\\d{2}\\.\\d{3}>")

        /** The uploader's English captions, else YouTube's automatic ones, else any the uploader made. */
        internal fun bestCaptions(tracks: List<SubtitleTrack>): SubtitleTrack? {
            fun english(t: SubtitleTrack) = t.language.removeSuffix("-orig").let { it == "en" || it.startsWith("en-") }
            return tracks.firstOrNull { !it.automatic && english(it) }
                ?: tracks.firstOrNull { it.automatic && english(it) }
                ?: tracks.firstOrNull { it.automatic }
                ?: tracks.firstOrNull()
        }

        private const val FIRST_CHUNK_SAMPLES = 30 * Resampler.TARGET_RATE
        private const val CHUNK_SAMPLES = 5 * 60 * Resampler.TARGET_RATE
        private const val MAX_CARRY_SAMPLES = 2 * Resampler.TARGET_RATE

        /** The start of the quietest 100 ms between [from] and [to]. */
        internal fun quietestCut(
            samples: FloatArray,
            from: Int,
            to: Int,
        ): Int {
            val window = Resampler.TARGET_RATE / 10
            var best = from
            var bestEnergy = Float.MAX_VALUE
            var start = from
            while (start + window <= to) {
                var energy = 0f
                for (i in start until start + window) energy += abs(samples[i])
                if (energy < bestEnergy) {
                    bestEnergy = energy
                    best = start
                }
                start += window / 2
            }
            return best + window / 2
        }
    }
}
