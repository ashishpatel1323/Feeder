package com.nononsenseapps.feeder.openai

import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Summarizes text with a model that runs on the phone itself, so the article never leaves the
 * device.
 */
interface OnDeviceSummarizer {
    suspend fun checkStatus(): OnDeviceStatus

    /** Returns the model's answer. Downloads the model first if the system still needs to. */
    suspend fun generate(
        systemPrompt: String,
        userPrompt: String,
        content: String,
    ): String
}

sealed interface OnDeviceStatus {
    data class Ready(
        val modelName: String,
    ) : OnDeviceStatus

    /** Supported, but the system still has to download the model (happens on first summary). */
    data object NeedsDownload : OnDeviceStatus

    data class Unavailable(
        val message: String,
    ) : OnDeviceStatus
}

/**
 * Gemini Nano through ML Kit GenAI Prompt API (runs in Android's AICore system service).
 *
 * Only works on phones that ship Gemini Nano, and only while the app is in the foreground.
 */
class GeminiNanoSummarizer : OnDeviceSummarizer {
    private val model: GenerativeModel by lazy { Generation.getClient() }
    private val downloadMutex = Mutex()

    override suspend fun checkStatus(): OnDeviceStatus =
        try {
            when (model.checkStatus()) {
                FeatureStatus.AVAILABLE -> OnDeviceStatus.Ready(modelName = baseModelName())
                FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> OnDeviceStatus.NeedsDownload
                else -> OnDeviceStatus.Unavailable(UNSUPPORTED_MESSAGE)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Gemini Nano status check failed", e)
            OnDeviceStatus.Unavailable(e.friendlyMessage())
        }

    override suspend fun generate(
        systemPrompt: String,
        userPrompt: String,
        content: String,
    ): String {
        try {
            ensureDownloaded()

            // Gemini Nano has a small context window (about 4000 tokens), so long articles
            // are cut to fit, leaving room for the instructions and the answer.
            val tokenLimit = runCatching { model.getTokenLimit() }.getOrDefault(DEFAULT_TOKEN_LIMIT)
            val inputBudget = (tokenLimit - OUTPUT_TOKENS - PROMPT_OVERHEAD_TOKENS).coerceAtLeast(500)
            var text = content.take(inputBudget * CHARS_PER_TOKEN_GUESS)
            var request = buildRequest(systemPrompt, userPrompt, text)
            for (attempt in 1..3) {
                val used =
                    runCatching { model.countTokens(request).totalTokens }.getOrNull()
                        ?: break
                if (used <= tokenLimit - OUTPUT_TOKENS) {
                    break
                }
                val keep = (text.length.toDouble() * (tokenLimit - OUTPUT_TOKENS) / used * 0.9).toInt()
                text = text.take(keep)
                request = buildRequest(systemPrompt, userPrompt, text)
            }

            val response = model.generateContent(request)
            return response.candidates.firstOrNull()?.text
                ?: throw IllegalStateException("Gemini Nano returned no text")
        } catch (e: CancellationException) {
            throw e
        } catch (e: GenAiException) {
            Log.w(LOG_TAG, "Gemini Nano summary failed", e)
            throw IllegalStateException(e.friendlyMessage(), e)
        }
    }

    private fun buildRequest(
        systemPrompt: String,
        userPrompt: String,
        content: String,
    ) = generateContentRequest(
        SystemInstruction(systemPrompt),
        TextPart("$userPrompt\n\n$content"),
    ) {
        temperature = 0.2f
        maxOutputTokens = OUTPUT_TOKENS
    }

    private suspend fun ensureDownloaded() {
        downloadMutex.withLock {
            when (model.checkStatus()) {
                FeatureStatus.AVAILABLE -> return

                FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> {
                    model.download().collect { status ->
                        if (status is DownloadStatus.DownloadFailed) {
                            throw status.e
                        }
                    }
                }

                else -> throw IllegalStateException(UNSUPPORTED_MESSAGE)
            }
        }
    }

    private suspend fun baseModelName(): String = runCatching { model.getBaseModelName() }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Gemini Nano"

    private fun Exception.friendlyMessage(): String =
        when ((this as? GenAiException)?.errorCode) {
            GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED ->
                "Gemini Nano only works while Feeder is open on screen."
            GenAiException.ErrorCode.BUSY ->
                "Gemini Nano is busy. Try again in a moment."
            GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED ->
                "Android's daily limit for on-device AI in Feeder was reached. Try again later."
            else -> message?.takeIf { it.isNotBlank() } ?: UNSUPPORTED_MESSAGE
        }

    companion object {
        private const val LOG_TAG = "FEEDER_NANO"
        private const val DEFAULT_TOKEN_LIMIT = 4000
        private const val OUTPUT_TOKENS = 400
        private const val PROMPT_OVERHEAD_TOKENS = 300
        private const val CHARS_PER_TOKEN_GUESS = 4
        const val UNSUPPORTED_MESSAGE =
            "Gemini Nano isn't available on this phone. It needs a supported device with an up to date AICore app."
    }
}
