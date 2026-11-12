package com.nononsenseapps.feeder.openai

import android.content.Context
import android.util.Log
import com.google.common.util.concurrent.ListenableFuture
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig
import com.google.mlkit.genai.summarization.Summarization
import com.google.mlkit.genai.summarization.SummarizationRequest
import com.google.mlkit.genai.summarization.Summarizer
import com.google.mlkit.genai.summarization.SummarizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
 * Gemini Nano through ML Kit GenAI (runs in Android's AICore system service).
 *
 * Phones differ in which Gemini Nano build they offer through the Prompt API (stable or
 * preview, full or fast), so every combination is tried before giving up. If the Prompt API
 * has nothing, ML Kit's dedicated Summarization API is used instead, since AICore can offer it
 * on phones where the Prompt API isn't enabled yet.
 *
 * Only works while the app is in the foreground.
 */
class GeminiNanoSummarizer(
    private val context: Context,
) : OnDeviceSummarizer {
    private sealed interface Backend {
        val status: Int

        class Prompt(
            val model: GenerativeModel,
            val label: String,
            override val status: Int,
        ) : Backend

        class Summary(
            val summarizer: Summarizer,
            override val status: Int,
        ) : Backend
    }

    private class PromptCandidate(
        val label: String,
        val releaseStage: Int,
        val preference: Int,
    ) {
        val model: GenerativeModel by lazy {
            Generation.getClient(
                generationConfig {
                    modelConfig =
                        modelConfig {
                            releaseStage = this@PromptCandidate.releaseStage
                            preference = this@PromptCandidate.preference
                        }
                },
            )
        }
    }

    private val promptCandidates =
        listOf(
            PromptCandidate("stable, full", ModelReleaseStage.STABLE, ModelPreference.FULL),
            PromptCandidate("stable, fast", ModelReleaseStage.STABLE, ModelPreference.FAST),
            PromptCandidate("preview, full", ModelReleaseStage.PREVIEW, ModelPreference.FULL),
            PromptCandidate("preview, fast", ModelReleaseStage.PREVIEW, ModelPreference.FAST),
        )

    private val summarizer: Summarizer by lazy {
        Summarization.getClient(
            SummarizerOptions
                .builder(context.applicationContext)
                .setInputType(SummarizerOptions.InputType.ARTICLE)
                .setOutputType(SummarizerOptions.OutputType.THREE_BULLETS)
                .setLanguage(SummarizerOptions.Language.ENGLISH)
                .setLongInputAutoTruncationEnabled(true)
                .build(),
        )
    }

    private val lock = Mutex()

    // Only a working backend is remembered. "Unavailable" is checked again next time, because
    // AICore often reports it until it has fetched its configuration after an update.
    private var chosen: Backend? = null

    override suspend fun checkStatus(): OnDeviceStatus =
        try {
            val backend = lock.withLock { findBackend(refresh = true) }
            when (backend?.status) {
                FeatureStatus.AVAILABLE -> OnDeviceStatus.Ready(modelName = backend.displayName())
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
            return when (val backend = lock.withLock { ensureDownloaded() }) {
                is Backend.Prompt -> generateWithPrompt(backend.model, systemPrompt, userPrompt, content)
                is Backend.Summary -> summarizeWithSummaryApi(backend.summarizer, content)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GenAiException) {
            Log.w(LOG_TAG, "Gemini Nano summary failed", e)
            throw IllegalStateException(e.friendlyMessage(), e)
        }
    }

    /** Returns the first backend that isn't UNAVAILABLE, or null if none are. */
    private suspend fun findBackend(refresh: Boolean): Backend? {
        val previous = chosen
        if (previous != null && !refresh) {
            return previous
        }
        if (previous != null) {
            // Re-check the one that worked before first, so its status (downloaded or not) is fresh
            val status = previous.checkLiveStatus()
            if (status != FeatureStatus.UNAVAILABLE) {
                return previous.withStatus(status).also { chosen = it }
            }
        }
        for (candidate in promptCandidates) {
            val status =
                try {
                    candidate.model.checkStatus()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Prompt API (${candidate.label}) status check failed", e)
                    FeatureStatus.UNAVAILABLE
                }
            Log.i(LOG_TAG, "Prompt API (${candidate.label}): ${statusName(status)}")
            if (status != FeatureStatus.UNAVAILABLE) {
                return Backend.Prompt(candidate.model, candidate.label, status).also { chosen = it }
            }
        }
        val summaryStatus =
            try {
                summarizer.checkFeatureStatus().await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Summarization API status check failed", e)
                FeatureStatus.UNAVAILABLE
            }
        Log.i(LOG_TAG, "Summarization API: ${statusName(summaryStatus)}")
        if (summaryStatus != FeatureStatus.UNAVAILABLE) {
            return Backend.Summary(summarizer, summaryStatus).also { chosen = it }
        }
        chosen = null
        return null
    }

    private suspend fun ensureDownloaded(): Backend {
        val backend = findBackend(refresh = true) ?: throw IllegalStateException(UNSUPPORTED_MESSAGE)
        if (backend is Backend.Prompt &&
            (backend.status == FeatureStatus.DOWNLOADABLE || backend.status == FeatureStatus.DOWNLOADING)
        ) {
            backend.model.download().collect { status ->
                if (status is DownloadStatus.DownloadFailed) {
                    throw status.e
                }
            }
        }
        // The Summarization API downloads its model by itself on the first request.
        return backend
    }

    private suspend fun generateWithPrompt(
        model: GenerativeModel,
        systemPrompt: String,
        userPrompt: String,
        content: String,
    ): String {
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
    }

    private suspend fun summarizeWithSummaryApi(
        summarizer: Summarizer,
        content: String,
    ): String {
        // About 3000 English words fit; the API needs at least 400 characters.
        val text = content.take(SUMMARY_API_MAX_CHARS)
        if (text.length < SUMMARY_API_MIN_CHARS) {
            throw IllegalStateException("This article is too short for an on-device summary.")
        }
        val result = summarizer.runInference(SummarizationRequest.builder(text).build()).await()
        return result.summary
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n") { line ->
                if (line.startsWith("•") || line.startsWith("*")) "- " + line.drop(1).trim() else line
            }.ifBlank { throw IllegalStateException("Gemini Nano returned no text") }
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

    private suspend fun Backend.checkLiveStatus(): Int =
        try {
            when (this) {
                is Backend.Prompt -> model.checkStatus()
                is Backend.Summary -> summarizer.checkFeatureStatus().await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FeatureStatus.UNAVAILABLE
        }

    private fun Backend.withStatus(status: Int): Backend =
        when (this) {
            is Backend.Prompt -> Backend.Prompt(model, label, status)
            is Backend.Summary -> Backend.Summary(summarizer, status)
        }

    private suspend fun Backend.displayName(): String =
        when (this) {
            is Backend.Prompt ->
                runCatching { model.getBaseModelName() }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?: "Gemini Nano"
            is Backend.Summary -> "Gemini Nano (summaries in English only)"
        }

    private fun statusName(status: Int): String =
        when (status) {
            FeatureStatus.AVAILABLE -> "AVAILABLE"
            FeatureStatus.DOWNLOADABLE -> "DOWNLOADABLE"
            FeatureStatus.DOWNLOADING -> "DOWNLOADING"
            FeatureStatus.UNAVAILABLE -> "UNAVAILABLE"
            else -> "UNKNOWN($status)"
        }

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
        private const val SUMMARY_API_MAX_CHARS = 12_000
        private const val SUMMARY_API_MIN_CHARS = 400
        const val UNSUPPORTED_MESSAGE =
            "Android's AICore says Gemini Nano isn't ready on this phone yet. Update \"AICore\" and " +
                "\"Private Compute Services\" in the Play Store, leave the phone on Wi-Fi and charging for " +
                "a while, then open this dialog again. Phones with an unlocked bootloader can't use it."
    }
}

private suspend fun <T> ListenableFuture<T>.await(): T =
    suspendCancellableCoroutine { cont ->
        addListener(
            {
                try {
                    cont.resume(get())
                } catch (e: ExecutionException) {
                    cont.resumeWithException(e.cause ?: e)
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            },
            Executor { it.run() },
        )
        cont.invokeOnCancellation { cancel(false) }
    }
