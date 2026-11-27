package com.nononsenseapps.feeder.model

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Locale
import java.util.UUID

/** A started speech engine with the language picked for one text. Call [shutdown] when done. */
internal class SpeechEngine(
    val engine: TextToSpeech,
    val locale: Locale,
    val available: List<Locale>,
) {
    /** Identifies the voice, so audio made earlier can tell whether it still matches. */
    val voiceKey: String
        get() = "${runCatching { engine.defaultEngine }.getOrNull()}|${runCatching { engine.voice?.name }.getOrNull()}|${locale.toLanguageTag()}"

    fun shutdown() {
        runCatching { engine.stop() }
        runCatching { engine.shutdown() }
    }
}

/**
 * Starts the device's speech engine and picks the language for [text]: the one the user picked,
 * else the detected or app language, falling back to whatever the engine can speak.
 */
internal suspend fun openSpeechEngine(
    context: Context,
    text: String,
    language: LocaleOverride,
    detectLanguage: Boolean,
): SpeechEngine {
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
        val preferredLocale =
            when (language) {
                is ForcedLocale -> language.locale
                else ->
                    if (language is ForcedAuto || detectLanguage) {
                        context.detectSpeechLocale(text) ?: context.getLocales().firstOrNull() ?: Locale.getDefault()
                    } else {
                        context.getLocales().firstOrNull() ?: Locale.getDefault()
                    }
            }
        val candidates =
            if (language is ForcedLocale) {
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
                    check(language !is ForcedLocale) { "Speech language is not available" }
                    // Some engines reject every language check yet speak fine with their own voice.
                    Log.w(SPEECH_LOG_TAG, "Speech engine rejected all languages; using its default voice")
                    runCatching { engine.defaultVoice?.let { engine.voice = it } }
                    defaultVoiceLocale ?: preferredLocale
                }
        return SpeechEngine(engine, locale, available)
    } catch (e: Throwable) {
        runCatching { engine.shutdown() }
        throw e
    }
}

/** The sentences (and long-sentence pieces) [text] is spoken in, from [startOffset]. */
internal fun speechParts(
    text: String,
    locale: Locale,
    startOffset: Int = 0,
): List<SpeechSegment> =
    speechSegments(text, locale, minOf(TextToSpeech.getMaxSpeechInputLength(), 700), firstChunkMaxLength = 180)
        .startingAt(startOffset)

/** What [synthesizeSpeech] made so far, after each sentence. */
internal class SynthesizedPart(
    val index: Int,
    val isLast: Boolean,
    val part: SpeechSegment,
    val timeline: List<SpeechSegment>,
    val words: List<SpeechSegment>,
    val wave: SpeechWaveFile,
)

/**
 * Speaks [parts] of [text] into the WAV file [audio], one sentence at a time, with when each
 * sentence and word starts. [onPart] runs after every sentence (for streaming or progress).
 * Returns the sentence and word timelines.
 */
internal suspend fun synthesizeSpeech(
    engine: TextToSpeech,
    text: String,
    parts: List<SpeechSegment>,
    directory: File,
    audio: File,
    onPart: suspend (SynthesizedPart) -> Unit = {},
): Pair<List<SpeechSegment>, List<SpeechSegment>> =
    withContext(Dispatchers.IO) {
        SpeechWaveFile(audio).use { wave ->
            val timeline = mutableListOf<SpeechSegment>()
            val wordTimeline = mutableListOf<SpeechSegment>()
            for ((index, part) in parts.withIndex()) {
                val done = CompletableDeferred<Unit>()
                val id = UUID.randomUUID().toString()
                var pcm: EnginePcm? = null
                var receivedAudio = false
                var sampleRate = 0
                val engineWords = mutableListOf<EngineWord>()
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
                                sampleRate = sampleRateInHz
                            } catch (e: Exception) {
                                done.completeExceptionally(e)
                            }
                        }

                        override fun onRangeStart(
                            utteranceId: String?,
                            start: Int,
                            end: Int,
                            frame: Int,
                        ) {
                            if (utteranceId != id || !done.isActive) return
                            synchronized(engineWords) { engineWords += EngineWord(part.start + start, part.start + end, frame.toLong()) }
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
                val temporary = File(directory, "segment-${audio.nameWithoutExtension}.wav")
                try {
                    check(engine.synthesizeToFile(text.substring(part.start, part.end), Bundle(), temporary, id) == TextToSpeech.SUCCESS)
                    withTimeout(120_000) { done.await() }
                    if (!receivedAudio) appendWrittenFile(wave, temporary)
                } finally {
                    done.cancel()
                    temporary.delete()
                }
                val end = wave.durationMillis()
                check(end > start) { "Speech engine produced no audio" }
                val timed = part.copy(startMillis = start, endMillis = end)
                timeline += timed
                // Audio from a written file has no streamed format, so its word reports cannot be timed
                val reported = if (receivedAudio) synchronized(engineWords) { engineWords.toList() } else emptyList()
                wordTimeline += speechWords(text, timed, reported, sampleRate)
                onPart(SynthesizedPart(index, index == parts.lastIndex, part, timeline.toList(), wordTimeline.toList(), wave))
            }
            wave.finish()
            timeline.toList() to wordTimeline.toList()
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

internal suspend fun Context.detectSpeechLocale(text: String): Locale? =
    try {
        withContext(Dispatchers.Default) { detectLocaleFromText(text.take(4000)).firstOrNull()?.locale }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(SPEECH_LOG_TAG, "Language detection unavailable; using device languages", e)
        null
    }

private const val SPEECH_LOG_TAG = "FEEDER_READ_ALOUD"
