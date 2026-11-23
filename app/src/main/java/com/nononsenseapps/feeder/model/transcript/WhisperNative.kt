package com.nononsenseapps.feeder.model.transcript

import android.util.Log

/** whisper.cpp, built from app/src/main/cpp. Missing on ABIs it isn't built for: see [available]. */
object WhisperNative {
    val available: Boolean by lazy {
        try {
            System.loadLibrary("feederwhisper")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w("FeederWhisper", "Speech to text is not built for this device", e)
            false
        }
    }

    /** Loads a ggml model file. 0 when it could not. */
    external fun init(modelPath: String): Long

    external fun free(ctx: Long)

    /** Stops the running [transcribe], which then returns null. */
    external fun cancel()

    /** 16 kHz mono samples in, UTF-8 lines of "start ms TAB end ms TAB text" out. */
    external fun transcribe(
        ctx: Long,
        samples: FloatArray,
        threads: Int,
        language: String,
    ): ByteArray?

    fun parseSegments(
        bytes: ByteArray,
        offsetMs: Long,
    ): List<TranscriptCue> =
        String(bytes, Charsets.UTF_8)
            .lineSequence()
            .mapNotNull { line ->
                val parts = line.split('\t', limit = 3)
                if (parts.size < 3) return@mapNotNull null
                val text = parts[2].trim()
                // Whisper marks silence and music like this; they are not speech
                if (text.isEmpty() || (text.startsWith("[") && text.endsWith("]")) || (text.startsWith("(") && text.endsWith(")"))) {
                    return@mapNotNull null
                }
                TranscriptCue(
                    startMs = offsetMs + (parts[0].toLongOrNull() ?: return@mapNotNull null),
                    endMs = offsetMs + (parts[1].toLongOrNull() ?: return@mapNotNull null),
                    text = text,
                )
            }.toList()
}
