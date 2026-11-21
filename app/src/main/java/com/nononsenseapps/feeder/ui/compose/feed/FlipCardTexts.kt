package com.nononsenseapps.feeder.ui.compose.feed

import android.app.Application
import android.util.Log
import android.util.LruCache
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.archmodel.Repository
import com.nononsenseapps.feeder.blob.blobFile
import com.nononsenseapps.feeder.blob.blobFullFile
import com.nononsenseapps.feeder.blob.blobFullInputStream
import com.nononsenseapps.feeder.blob.blobInputStream
import com.nononsenseapps.feeder.db.room.FeedItemForFetching
import com.nononsenseapps.feeder.model.FullTextParser
import com.nononsenseapps.feeder.model.html.HtmlLinearizer
import com.nononsenseapps.feeder.model.offline.OfflineFiles
import com.nononsenseapps.feeder.model.youtube.youtubeVideoId
import com.nononsenseapps.feeder.openai.OnDeviceStatus
import com.nononsenseapps.feeder.openai.OnDeviceSummarizer
import com.nononsenseapps.feeder.ui.compose.readaloud.speechText
import com.nononsenseapps.feeder.util.FilePathProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

// Below this the feed sent only a teaser, so the opening of the article page is used instead
private const val SHORT_FEED_TEXT = 500

// More than a phone screen holds; the card cuts it off with an ellipsis
private const val CARD_TEXT_CHARS = 1_800

// Gemini Nano only takes a few thousand tokens
private const val SUMMARY_INPUT_CHARS = 6_000

private const val SUMMARY_BULLETS = 3

private val BULLET_PREFIX = Regex("""^\s*(?:[-*•·]|\d+[.)])\s*""")

/**
 * The longer article text shown on flip cards, and the on-device summaries behind their
 * Summarize button.
 *
 * Card text comes from the feed's own article body. When the feed only sends a sentence or two,
 * the article page is fetched in the background (same full-text parser as the reader) and its
 * opening is used instead. Summaries are made by Gemini Nano on the phone and kept on disk, so each
 * article is only summarized once.
 */
class FlipCardTexts(
    override val di: DI,
) : DIAware {
    private val application: Application by instance()
    private val repository: Repository by instance()
    private val filePathProvider: FilePathProvider by instance()
    private val fullTextParser: FullTextParser by instance()
    private val onDeviceSummarizer: OnDeviceSummarizer by instance()
    private val offlineFiles: OfflineFiles by instance()

    private val texts = LruCache<Long, String>(64)
    private val summaries = LruCache<Long, List<String>>(64)

    // Flipping fast should not start a page download for every card at once
    private val fetches = Semaphore(2)

    // The card and its Summarize button can ask for the same article at once; one of them fetches
    // and the other waits, so the page is never written twice or read half written
    private val articleLocks = ConcurrentHashMap<Long, Mutex>()

    private val summaryDir: File
        get() = File(application.cacheDir, "flip_summaries")

    fun cachedText(itemId: Long): String? = texts.get(itemId)

    /** The article text for a card, or [fallback] when there is nothing longer. */
    suspend fun cardText(
        itemId: Long,
        link: String?,
        fallback: String,
    ): String {
        texts.get(itemId)?.let { return it }
        val text = articleText(itemId, link, CARD_TEXT_CHARS).takeIf { it.length > fallback.length } ?: fallback
        texts.put(itemId, text)
        return text
    }

    fun cachedSummary(itemId: Long): List<String>? {
        summaries.get(itemId)?.let { return it }
        val file = File(summaryDir, "$itemId.txt")
        if (!file.isFile) return null
        return try {
            file
                .readLines()
                .filter { it.isNotBlank() }
                .takeIf { it.isNotEmpty() }
                ?.also { summaries.put(itemId, it) }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Summarizes the article in three bullets with Gemini Nano. Throws with a message for the user
     * when the phone can't do it.
     */
    suspend fun summarize(
        itemId: Long,
        title: String,
        link: String?,
        fallback: String,
    ): List<String> {
        cachedSummary(itemId)?.let { return it }
        val status = onDeviceSummarizer.checkStatus()
        if (status is OnDeviceStatus.Unavailable) {
            throw IllegalStateException(application.getString(R.string.flip_summary_unavailable))
        }
        val text = articleText(itemId, link, SUMMARY_INPUT_CHARS).ifBlank { fallback }
        if (text.isBlank()) {
            throw IllegalStateException(application.getString(R.string.flip_summary_no_text))
        }
        val answer =
            onDeviceSummarizer.generate(
                systemPrompt =
                    "You summarize news articles for a reader app. " +
                        "Reply with exactly $SUMMARY_BULLETS bullet points, each starting with \"- \" and under 25 words. " +
                        "Use the article's language. No title, no introduction, nothing else.",
                userPrompt = "Summarize this article titled \"$title\" in $SUMMARY_BULLETS bullet points.",
                content = text,
            )
        val bullets = parseSummaryBullets(answer)
        if (bullets.isEmpty()) {
            throw IllegalStateException(application.getString(R.string.flip_summary_no_text))
        }
        summaries.put(itemId, bullets)
        withContext(Dispatchers.IO) {
            try {
                summaryDir.mkdirs()
                File(summaryDir, "$itemId.txt").writeText(bullets.joinToString("\n"))
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Could not keep summary of $itemId", e)
            }
        }
        return bullets
    }

    private suspend fun articleText(
        itemId: Long,
        link: String?,
        maxChars: Int,
    ): String =
        withContext(Dispatchers.IO) {
            try {
                val feedText = readText(itemId, link, full = false)
                if (feedText.length >= SHORT_FEED_TEXT) {
                    return@withContext feedText.cutAt(maxChars)
                }
                val fullText =
                    articleLocks.getOrPut(itemId) { Mutex() }.withLock {
                        val fullFile = blobFullFile(itemId, filePathProvider.fullArticleDir)
                        // A copy saved by Download for offline counts even when the cache was cleared
                        offlineFiles.restoreFullText(itemId)
                        if (!fullFile.isFile && link != null && youtubeVideoId(link) == null && !repository.offlineMode.value) {
                            fetches.withPermit {
                                fullTextParser.parseFullArticleIfMissing(
                                    object : FeedItemForFetching {
                                        override val id = itemId
                                        override val link = link
                                    },
                                )
                            }
                        }
                        if (fullFile.isFile) readText(itemId, link, full = true) else ""
                    }
                (if (fullText.length > feedText.length) fullText else feedText).cutAt(maxChars)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Could not load text for card $itemId", e)
                ""
            }
        }

    private fun readText(
        itemId: Long,
        link: String?,
        full: Boolean,
    ): String {
        val file =
            if (full) blobFullFile(itemId, filePathProvider.fullArticleDir) else blobFile(itemId, filePathProvider.articleDir)
        if (!file.isFile) return ""
        val linearizer =
            HtmlLinearizer(
                tooLargeText = application.getString(R.string.failed_to_fetch_full_article_too_large),
                openInBrowserText = application.getString(R.string.open_in_web_view),
                articleLink = link ?: "",
            )
        val stream: InputStream =
            if (full) {
                blobFullInputStream(itemId, filePathProvider.fullArticleDir)
            } else {
                blobInputStream(itemId, filePathProvider.articleDir)
            }
        return stream.use { linearizer.linearize(inputStream = it, baseUrl = link ?: "").speechText() }.trim()
    }

    companion object {
        private const val LOG_TAG = "FEEDER_FLIPTEXT"
    }
}

/** Cuts [this] to at most [maxChars], at the end of a word. */
internal fun String.cutAt(maxChars: Int): String {
    if (length <= maxChars) return this
    val end = lastIndexOf(' ', maxChars).takeIf { it > maxChars / 2 } ?: maxChars
    return substring(0, end).trimEnd() + "…"
}

/** The bullet lines of a model's answer, without their markers, at most three. */
internal fun parseSummaryBullets(answer: String): List<String> =
    answer
        .lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("Lang:", ignoreCase = true) }
        .map { it.replace(BULLET_PREFIX, "").replace("**", "").trim() }
        .filter { it.isNotEmpty() }
        .take(SUMMARY_BULLETS)
