package com.nononsenseapps.feeder.model

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextLanguage
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.AnnotatedString
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.nononsenseapps.feeder.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.UUID

@Immutable
data class ReadAloudProgress(
    val articleId: Long? = null,
    val text: String = "",
    val positionMillis: Long = 0,
    val durationMillis: Long = 0,
    val preparationProgress: Float? = null,
    val segment: SpeechSegment? = null,
    val error: String? = null,
    val generatedMillis: Long = 0,
    val isDurationEstimated: Boolean = false,
    val synthesisProgress: Float = 0f,
    val focusRequestId: Long = 0,
    val followSpokenText: Boolean = false,
    val speed: Float = 1f,
)

/** Speeds offered by the read-aloud player. Playback speed changes keep the highlight in sync. */
val READ_ALOUD_SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

/** Application facade; the foreground service owns the player and its lifetime. */
class TTSStateHolder(
    val context: Context,
    val coroutineScope: CoroutineScope,
) {
    private val _ttsState = MutableStateFlow(PlaybackStatus.STOPPED)
    val ttsState = _ttsState.asStateFlow()
    private val _progress = MutableStateFlow(ReadAloudProgress())
    val progress = _progress.asStateFlow()
    private val _language = MutableStateFlow<LocaleOverride>(AppSetting)
    val language: StateFlow<LocaleOverride> = _language.asStateFlow()
    private val _availableLanguages = MutableStateFlow<List<Locale>>(emptyList())
    val availableLanguages = _availableLanguages.asStateFlow()
    private var request: SpeechRequest? = null
    private var player: ExoPlayer? = null
    private var preparation: Job? = null
    private var ticker: Job? = null
    private var segments = emptyList<SpeechSegment>()
    private var playWhenReady = true
    private var cacheDirectory: File? = null
    private var service: ReadAloudService? = null
    private var pendingServiceStart = false
    private var audioBuffer: SpeechAudioBuffer? = null
    private var focusSequence = 0L
    private val preferences by lazy { context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE) }
    private var playbackSpeed = 1f

    init {
        coroutineScope.launch(Dispatchers.IO) {
            val saved = preferences.getFloat(PREF_SPEED, 1f).coerceIn(MIN_SPEED, MAX_SPEED)
            withContext(Dispatchers.Main.immediate) {
                playbackSpeed = saved
                player?.playbackParameters = PlaybackParameters(saved)
                _progress.value = _progress.value.copy(speed = saved)
            }
        }
    }

    /** Changes speed in the player, not the engine, so the synthesized timeline stays valid. */
    fun setSpeed(speed: Float) {
        val value = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        coroutineScope.launch(Dispatchers.Main.immediate) {
            playbackSpeed = value
            player?.playbackParameters = PlaybackParameters(value)
            _progress.value = _progress.value.copy(speed = value)
        }
        coroutineScope.launch(Dispatchers.IO) {
            preferences.edit { putFloat(PREF_SPEED, value) }
        }
    }

    fun requestSpokenTextFocus(): Long? {
        val current = _progress.value
        val articleId = current.articleId ?: return null
        _progress.value =
            current.copy(
                focusRequestId = ++focusSequence,
                followSpokenText = true,
                segment = current.segment ?: segments.lastOrNull { it.startMillis <= current.positionMillis },
            )
        return articleId
    }

    fun stopFollowingSpokenText() {
        _progress.value = _progress.value.copy(followSpokenText = false)
    }

    private val artwork by lazy {
        runCatching {
            val size = 256
            val drawable = context.packageManager.getApplicationIcon(context.packageName)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(bitmap))
            ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                output.toByteArray()
            }
        }.getOrNull()
    }

    fun tts(
        textArray: List<AnnotatedString>,
        useDetectLanguage: Boolean,
        articleId: Long,
        title: String = "",
        author: String = "",
        feedTitle: String = "",
        startOffset: Int = 0,
    ) {
        val text = textArray.filterNot { it.isBlank() }.joinToString("\n\n") { it.text }
        if (text.isBlank()) return
        coroutineScope.launch(Dispatchers.Main.immediate) {
            request = SpeechRequest(articleId, text, useDetectLanguage, title, author, feedTitle, startOffset.coerceIn(0, text.length - 1))
            playWhenReady = true
            _ttsState.value = PlaybackStatus.PLAYING
            _progress.value = ReadAloudProgress(articleId = articleId, text = text, preparationProgress = 0f, followSpokenText = true, focusRequestId = ++focusSequence, speed = playbackSpeed)
            try {
                pendingServiceStart = true
                ContextCompat.startForegroundService(context, Intent(context, ReadAloudService::class.java).setAction(ReadAloudService.ACTION_PREPARE))
            } catch (e: Exception) {
                pendingServiceStart = false
                fail(e)
            }
        }
    }

    internal fun attach(
        service: ReadAloudService,
        player: ExoPlayer,
    ) {
        ticker?.cancel()
        this.service = service
        this.player = player
        player.playbackParameters = PlaybackParameters(playbackSpeed)
        player.addListener(
            object : Player.Listener {
                override fun onPlayWhenReadyChanged(
                    playWhenReady: Boolean,
                    reason: Int,
                ) {
                    if (_progress.value.preparationProgress != null && request != null) {
                        this@TTSStateHolder.playWhenReady = playWhenReady
                        _ttsState.value = if (playWhenReady) PlaybackStatus.PLAYING else PlaybackStatus.PAUSED
                    }
                }

                override fun onEvents(
                    player: Player,
                    events: Player.Events,
                ) {
                    if (_progress.value.preparationProgress == null && _progress.value.error == null && request != null) {
                        _ttsState.value =
                            if (player.playWhenReady && player.playbackState != Player.STATE_ENDED && player.playbackState != Player.STATE_IDLE) PlaybackStatus.PLAYING else PlaybackStatus.PAUSED
                        updatePosition()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    fail(error)
                }
            },
        )
        ticker =
            coroutineScope.launch(Dispatchers.Main.immediate) {
                while (isActive) {
                    updatePosition()
                    delay(100)
                }
            }
    }

    internal fun prepare() {
        // Only a user request through tts() may start or replace synthesis.
        if (!pendingServiceStart) {
            if (request == null) service?.stopSelf()
            return
        }
        pendingServiceStart = false
        val current =
            request ?: run {
                service?.stopSelf()
                return
            }
        val previous = preparation
        audioBuffer?.abort()
        audioBuffer = null
        previous?.cancel()
        service?.setArticle(current.articleId)
        player?.stop()
        player?.clearMediaItems()
        player?.playWhenReady = playWhenReady
        segments = emptyList()
        preparation =
            coroutineScope.launch(Dispatchers.Main.immediate) {
                try {
                    previous?.join()
                    // Each job owns a separate engine and directory so stale callbacks cannot
                    // corrupt a replacement article or language-change request.
                    val directory =
                        withContext(Dispatchers.IO) {
                            File(context.cacheDir, "read-aloud").apply { mkdirs() }.let { root ->
                                root.listFiles()?.forEach { it.deleteRecursively() }
                                File(root, UUID.randomUUID().toString()).apply { mkdirs() }
                            }
                        }
                    cacheDirectory = directory
                    val audio = File(directory, "article.wav")
                    val timeline = withTimeout(5 * 60_000L) { synthesize(current, directory, audio) }
                    if (request !== current) return@launch
                    segments = timeline
                    val duration = timeline.lastOrNull()?.endMillis ?: error("No speech audio")
                    _progress.value = _progress.value.copy(durationMillis = duration, generatedMillis = duration, isDurationEstimated = false)
                } catch (e: TimeoutCancellationException) {
                    if (request === current) fail(e)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (request === current) fail(e)
                }
            }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun startProgressivePlayback(
        current: SpeechRequest,
        buffer: SpeechAudioBuffer,
    ) {
        audioBuffer = buffer
        val item =
            MediaItem
                .Builder()
                .setMediaId(current.articleId.toString())
                .setUri(android.net.Uri.fromFile(buffer.file))
                .setMediaMetadata(
                    MediaMetadata
                        .Builder()
                        .setTitle(current.title.ifBlank { context.getString(R.string.read_article) })
                        .setArtist(current.author.ifBlank { current.feedTitle }.ifBlank { context.getString(R.string.app_name) })
                        .setArtworkData(artwork, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                        .build(),
                ).build()
        _progress.value = _progress.value.copy(preparationProgress = null)
        player?.apply {
            setMediaSource(speechMediaSource(buffer, item))
            playbackParameters = PlaybackParameters(playbackSpeed)
            prepare()
            playWhenReady = this@TTSStateHolder.playWhenReady
        }
    }

    private suspend fun synthesize(
        current: SpeechRequest,
        directory: File,
        audio: File,
    ): List<SpeechSegment> {
        val initialized = CompletableDeferred<Int>()
        val engine = TextToSpeech(context) { initialized.complete(it) }
        try {
            // Neural engines can take a while to load their model the first time.
            check(withTimeout(60_000) { initialized.await() } == TextToSpeech.SUCCESS) { "Speech engine initialization failed" }
            // Some engines list nothing in availableLanguages but do list voices.
            val voiceLocales = runCatching { engine.voices.orEmpty().map { it.locale } }.getOrDefault(emptyList())
            val defaultVoiceLocale = runCatching { engine.defaultVoice?.locale }.getOrNull()
            val available =
                engine.availableLanguages
                    .orEmpty()
                    .ifEmpty { voiceLocales.toSet() }
                    .sortedBy { it.getDisplayName(it) }
            _availableLanguages.value = available
            val preferredLocale =
                when (val selected = _language.value) {
                    is ForcedLocale -> selected.locale
                    else ->
                        if (selected is ForcedAuto || current.detectLanguage) {
                            detectSpeechLocale(current.text)
                                ?: context.getLocales().firstOrNull() ?: Locale.getDefault()
                        } else {
                            context.getLocales().firstOrNull() ?: Locale.getDefault()
                        }
                }
            val candidates =
                if (_language.value is ForcedLocale) {
                    sequenceOf(preferredLocale, Locale.forLanguageTag(preferredLocale.language))
                } else {
                    sequenceOf(preferredLocale, Locale.forLanguageTag(preferredLocale.language))
                        .plus(context.getLocales())
                        .plus(listOfNotNull(defaultVoiceLocale))
                        .plus(available)
                }
            val locale =
                candidates.firstOrNull { runCatching { engine.setLanguage(it) }.getOrDefault(TextToSpeech.ERROR) >= TextToSpeech.LANG_AVAILABLE }
                    ?: run {
                        // A language the user picked explicitly should fail visibly rather than be read in another one.
                        check(_language.value !is ForcedLocale) { "Speech language is not available" }
                        // Some engines reject every language check yet speak fine with their own voice.
                        Log.w(LOG_TAG, "Speech engine rejected all languages; using its default voice")
                        runCatching { engine.defaultVoice?.let { engine.voice = it } }
                        defaultVoiceLocale ?: preferredLocale
                    }
            val parts =
                speechSegments(current.text, locale, minOf(TextToSpeech.getMaxSpeechInputLength(), 700), firstChunkMaxLength = 180)
                    .startingAt(current.startOffset)
                    .ifEmpty { error("No text to read") }
            val readStart = parts.first().start
            return withContext(Dispatchers.IO) {
                SpeechWaveFile(audio).use { wave ->
                    var buffer: SpeechAudioBuffer? = null
                    var finished = false
                    try {
                        val timeline = mutableListOf<SpeechSegment>()
                        for ((index, part) in parts.withIndex()) {
                            val done = CompletableDeferred<Unit>()
                            val id = UUID.randomUUID().toString()
                            var pcm: EnginePcm? = null
                            var receivedAudio = false
                            engine.setOnUtteranceProgressListener(
                                object : UtteranceProgressListener() {
                                    override fun onStart(utteranceId: String?) = Unit

                                    override fun onDone(utteranceId: String?) {
                                        if (utteranceId == id) done.complete(Unit)
                                    }

                                    @Deprecated("Platform compatibility")
                                    override fun onError(utteranceId: String?) {
                                        if (utteranceId == id) done.completeExceptionally(IllegalStateException("Speech synthesis failed"))
                                    }

                                    override fun onBeginSynthesis(
                                        utteranceId: String?,
                                        sampleRateInHz: Int,
                                        audioFormat: Int,
                                        channelCount: Int,
                                    ) {
                                        if (utteranceId != id || !done.isActive) return
                                        try {
                                            val engineFormat = EnginePcm.forAudioFormat(audioFormat)
                                            wave.setFormat(sampleRateInHz, channelCount, engineFormat.bits, engineFormat.floatingPoint)
                                            pcm = engineFormat
                                        } catch (e: Exception) {
                                            done.completeExceptionally(e)
                                        }
                                    }

                                    override fun onAudioAvailable(
                                        utteranceId: String?,
                                        bytes: ByteArray?,
                                    ) {
                                        if (utteranceId != id || !done.isActive || bytes == null) return
                                        try {
                                            val engineFormat = pcm ?: error("Speech engine sent audio before its format")
                                            val converted = engineFormat.convert(bytes)
                                            if (converted.isNotEmpty()) {
                                                wave.append(converted)
                                                receivedAudio = true
                                            }
                                        } catch (e: Exception) {
                                            done.completeExceptionally(e)
                                        }
                                    }
                                },
                            )
                            val start = wave.durationMillis()
                            val temporary = File(directory, "segment.wav")
                            try {
                                check(engine.synthesizeToFile(current.text.substring(part.start, part.end), Bundle(), temporary, id) == TextToSpeech.SUCCESS)
                                withTimeout(120_000) { done.await() }
                                if (!receivedAudio) appendWrittenFile(wave, temporary)
                            } finally {
                                done.cancel()
                                temporary.delete()
                            }
                            val end = wave.durationMillis()
                            check(end > start) { "Speech engine produced no audio" }
                            timeline += part.copy(startMillis = start, endMillis = end)
                            val streaming = buffer ?: SpeechAudioBuffer(audio, wave.audioFormat()).also { buffer = it }
                            streaming.publish(wave.sizeBytes(), finished = index == parts.lastIndex)
                            withContext(Dispatchers.Main.immediate) {
                                if (request === current) {
                                    segments = timeline.toList()
                                    val fraction = (part.end - readStart).toDouble() / (current.text.length - readStart)
                                    _progress.value =
                                        _progress.value.copy(
                                            durationMillis = (end / fraction).toLong().coerceAtLeast(end),
                                            generatedMillis = end,
                                            isDurationEstimated = index != parts.lastIndex,
                                            synthesisProgress = fraction.toFloat(),
                                        )
                                    if (audioBuffer !== streaming) startProgressivePlayback(current, streaming)
                                }
                            }
                        }
                        wave.finish()
                        finished = true
                        timeline
                    } finally {
                        if (!finished) buffer?.abort()
                    }
                }
            }
        } finally {
            engine.stop()
            engine.shutdown()
        }
    }

    private fun appendWrittenFile(
        wave: SpeechWaveFile,
        file: File,
    ) {
        val data = readWavePcm(file) ?: return
        val engineFormat = EnginePcm.forWave(data.floatingPoint, data.bitsPerSample)
        wave.setFormat(data.sampleRate, data.channels, engineFormat.bits, engineFormat.floatingPoint)
        java.io.RandomAccessFile(file, "r").use { input ->
            input.seek(data.dataOffset)
            val chunk = ByteArray(64 * 1024)
            var remaining = data.dataLength
            while (remaining > 0) {
                val read = input.read(chunk, 0, minOf(chunk.size.toLong(), remaining).toInt())
                if (read <= 0) break
                wave.append(engineFormat.convert(chunk.copyOf(read)))
                remaining -= read
            }
        }
    }

    private suspend fun detectSpeechLocale(text: String): Locale? =
        try {
            withContext(Dispatchers.Default) { context.detectLocaleFromText(text.take(4000)).firstOrNull()?.locale }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Language detection unavailable; using device languages", e)
            null
        }

    fun resumeIfPausedOn(articleId: Long): Boolean {
        if (_ttsState.value != PlaybackStatus.PAUSED || _progress.value.articleId != articleId) return false
        play()
        return true
    }

    fun play() {
        coroutineScope.launch(Dispatchers.Main.immediate) {
            playWhenReady = true
            if (_progress.value.error != null) {
                request?.let { tts(listOf(AnnotatedString(it.text)), it.detectLanguage, it.articleId, it.title, it.author, it.feedTitle, it.startOffset) }
            } else {
                player?.let {
                    if (it.playbackState == Player.STATE_ENDED) it.seekTo(0)
                    if (it.playbackState == Player.STATE_IDLE && it.mediaItemCount > 0) it.prepare()
                    it.play()
                }
                if (request != null) _ttsState.value = PlaybackStatus.PLAYING
            }
        }
    }

    fun pause() {
        coroutineScope.launch(Dispatchers.Main.immediate) {
            playWhenReady = false
            player?.pause()
            if (request != null) _ttsState.value = PlaybackStatus.PAUSED
        }
    }

    fun seekTo(positionMillis: Long) {
        coroutineScope.launch(Dispatchers.Main.immediate) {
            if (_progress.value.preparationProgress == null) {
                player?.seekTo(positionMillis.coerceIn(0, _progress.value.generatedMillis))
                updatePosition()
            }
        }
    }

    /**
     * Jumps to [offset] in [text] when that part is already prepared for this article.
     * Returns false when reading has to be started again from there instead.
     */
    fun playFromPreparedText(
        articleId: Long,
        text: String,
        offset: Int,
    ): Boolean {
        val current = _progress.value
        val speaking = request ?: return false
        if (current.articleId != articleId || current.text != text || current.error != null || current.preparationProgress != null) return false
        val first = segments.firstOrNull() ?: return false
        // Text before the first prepared sentence was never synthesized
        if (offset < first.start && speaking.startOffset > 0) return false
        val target = segments.firstOrNull { it.end > offset } ?: return false
        _progress.value = current.copy(followSpokenText = true)
        seekTo(target.startMillis)
        play()
        return true
    }

    fun skipNext() {
        val position = _progress.value.positionMillis
        seekTo(segments.firstOrNull { it.startMillis > position + 50 }?.startMillis ?: _progress.value.durationMillis)
    }

    fun setLanguage(lang: LocaleOverride) {
        coroutineScope.launch(Dispatchers.Main.immediate) {
            _language.value = lang
            request?.let { tts(listOf(AnnotatedString(it.text)), it.detectLanguage, it.articleId, it.title, it.author, it.feedTitle) }
        }
    }

    fun stop() {
        coroutineScope.launch(Dispatchers.Main.immediate) {
            val pending = preparation
            audioBuffer?.abort()
            audioBuffer = null
            pending?.cancel()
            pendingServiceStart = false
            request = null
            player?.stop()
            player?.clearMediaItems()
            _ttsState.value = PlaybackStatus.STOPPED
            _progress.value = ReadAloudProgress(speed = playbackSpeed)
            segments = emptyList()
            val directory = cacheDirectory
            cacheDirectory = null
            coroutineScope.launch(Dispatchers.IO) {
                pending?.join()
                directory?.deleteRecursively()
                withContext(Dispatchers.Main.immediate) {
                    if (preparation === pending) preparation = null
                }
            }
            service?.stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
            service?.stopSelf()
        }
    }

    private fun updatePosition() {
        val player = player ?: return
        if (_progress.value.preparationProgress != null || _progress.value.error != null || request == null) return
        val position = player.currentPosition.coerceAtLeast(0)
        _progress.value = _progress.value.copy(positionMillis = position, segment = segments.segmentAt(position))
    }

    private fun fail(error: Exception) {
        if (request == null) return
        preparation?.cancel()
        audioBuffer?.abort()
        Log.e(LOG_TAG, "Read-aloud failed", error)
        player?.pause()
        _ttsState.value = PlaybackStatus.PAUSED
        _progress.value = _progress.value.copy(preparationProgress = null, error = context.getString(R.string.read_aloud_failed_reason, error.message ?: error.javaClass.simpleName), segment = null)
        service?.stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
    }

    internal fun detach(owner: ReadAloudService) {
        if (service !== owner) return
        audioBuffer?.abort()
        audioBuffer = null
        ticker?.cancel()
        val pending = preparation
        pending?.cancel()
        player = null
        service = null
        segments = emptyList()
        // A new user request may already be starting a replacement service instance.
        if (!pendingServiceStart) {
            request = null
            _ttsState.value = PlaybackStatus.STOPPED
            _progress.value = ReadAloudProgress(speed = playbackSpeed)
        }
        val directory = cacheDirectory
        cacheDirectory = null
        coroutineScope.launch(Dispatchers.IO) {
            pending?.join()
            directory?.deleteRecursively()
        }
    }

    fun shutdown() = stop()

    private data class SpeechRequest(
        val articleId: Long,
        val text: String,
        val detectLanguage: Boolean,
        val title: String,
        val author: String,
        val feedTitle: String,
        // Where in text reading started, for "read aloud from here"
        val startOffset: Int = 0,
    )

    companion object {
        private const val LOG_TAG = "FEEDER_READ_ALOUD"
        private const val PREFERENCES_NAME = "read_aloud"
        private const val PREF_SPEED = "playback_speed"
        private const val MIN_SPEED = 0.5f
        private const val MAX_SPEED = 3f
    }
}

fun Context.getLocales(): Sequence<Locale> =
    sequence {
        val locales = resources.configuration.locales

        for (i in 0 until locales.size()) {
            yield(locales[i])
        }
    }

@RequiresApi(Build.VERSION_CODES.Q)
fun Context.detectLocaleFromText(
    text: CharSequence,
    minConfidence: Float = 80.0f,
): Sequence<LocaleWithConfidence> {
    val textClassificationManager = getSystemService(TextClassificationManager::class.java)
    val textClassifier = textClassificationManager.textClassifier

    val textRequest = TextLanguage.Request.Builder(text).build()
    val detectedLanguage = textClassifier.detectLanguage(textRequest)

    return sequence {
        for (i in 0 until detectedLanguage.localeHypothesisCount) {
            val localeDetected = detectedLanguage.getLocale(i)
            val confidence = detectedLanguage.getConfidenceScore(localeDetected) * 100.0f
            if (confidence >= minConfidence) {
                yield(
                    LocaleWithConfidence(
                        locale = localeDetected.toLocale(),
                        confidence = confidence,
                    ),
                )
            }
        }
    }
}

data class LocaleWithConfidence(
    val locale: Locale,
    val confidence: Float,
)

enum class PlaybackStatus {
    STOPPED,
    PAUSED,
    PLAYING,
}

@Immutable
sealed class LocaleOverride

@Immutable
object AppSetting : LocaleOverride()

@Immutable
object ForcedAuto : LocaleOverride()

@Immutable
data class ForcedLocale(
    val locale: Locale,
) : LocaleOverride()
