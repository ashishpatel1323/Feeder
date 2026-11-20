package com.nononsenseapps.feeder.model.offline

import android.app.Application
import android.util.Log
import com.nononsenseapps.feeder.ApplicationCoroutineScope
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.archmodel.SettingsStore
import com.nononsenseapps.feeder.blob.blobFile
import com.nononsenseapps.feeder.blob.blobFullFile
import com.nononsenseapps.feeder.db.room.FeedItemDao
import com.nononsenseapps.feeder.model.FullTextParser
import com.nononsenseapps.feeder.model.html.HtmlLinearizer
import com.nononsenseapps.feeder.model.html.LinearBlockQuote
import com.nononsenseapps.feeder.model.html.LinearElement
import com.nononsenseapps.feeder.model.html.LinearImage
import com.nononsenseapps.feeder.model.html.LinearImageSource
import com.nononsenseapps.feeder.model.html.LinearListItem
import com.nononsenseapps.feeder.model.html.LinearTable
import com.nononsenseapps.feeder.model.html.LinearVideo
import com.nononsenseapps.feeder.util.FilePathProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import java.io.File
import java.io.InputStream
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/** Progress of the Download for offline job, shown in Settings. */
data class OfflineJobState(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val failed: Int = 0,
    val images: Int = 0,
    val message: String = "",
)

/**
 * Settings > Offline reading: a one-time job, started by hand, that downloads the newest unread
 * articles with their full text and images. Never runs on its own.
 *
 * Articles and their feed text are already on the device, so this only adds what reading
 * would otherwise fetch: the full text and the images. Read marks made offline already wait
 * for the next sync.
 */
class OfflineDownloader(
    override val di: DI,
) : DIAware {
    private val dao: FeedItemDao by instance()
    private val fullTextParser: FullTextParser by instance()
    private val okHttpClient: OkHttpClient by instance()
    private val filePathProvider: FilePathProvider by instance()
    private val offlineFiles: OfflineFiles by instance()
    private val settingsStore: SettingsStore by instance()
    private val applicationCoroutineScope: ApplicationCoroutineScope by instance()
    private val application: Application by instance()

    private val _state = MutableStateFlow(OfflineJobState())
    val state: StateFlow<OfflineJobState> = _state.asStateFlow()

    val downloadedCount: Flow<Int>
        get() = dao.getOfflineCount()

    // Downloads go around the feed cache, which they would only push out
    private val imageClient: OkHttpClient by lazy { okHttpClient.newBuilder().cache(null).build() }

    private var job: Job? = null

    /** Downloads the newest [limit] unread articles (0 = all of them). */
    fun start(
        limit: Int,
        images: Boolean,
    ) {
        if (job?.isActive == true) return
        if (settingsStore.offlineMode.value) {
            _state.value = OfflineJobState(message = application.getString(R.string.offline_turn_off_to_download))
            return
        }
        _state.value = OfflineJobState(running = true, message = application.getString(R.string.offline_finding_articles))
        job =
            applicationCoroutineScope.launch(Dispatchers.IO) {
                run(limit, images)
            }
    }

    fun stop() {
        job?.cancel()
    }

    /** Removes every download and the Downloaded marks. */
    suspend fun removeAll() {
        job?.let {
            it.cancel()
            it.join()
        }
        withContext(Dispatchers.IO) {
            offlineFiles.deleteAll()
            dao.clearAllOfflineTimes()
        }
        _state.value = OfflineJobState()
    }

    /** Removes downloads of these articles (deleted, or read a while ago). */
    suspend fun removeDownloads(itemIds: List<Long>) {
        if (itemIds.isEmpty()) return
        withContext(Dispatchers.IO) {
            offlineFiles.deleteDownloads(itemIds)
            itemIds.chunked(500).forEach { dao.clearOfflineTimes(it) }
        }
    }

    suspend fun storageBytes(): Long = withContext(Dispatchers.IO) { offlineFiles.sizeBytes() }

    private suspend fun run(
        limit: Int,
        images: Boolean,
    ) {
        val done = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val savedImages = AtomicInteger(0)
        var total = 0

        fun update(
            running: Boolean,
            message: String,
        ) {
            _state.value =
                OfflineJobState(
                    running = running,
                    done = done.get(),
                    total = total,
                    failed = failed.get(),
                    images = savedImages.get(),
                    message = message,
                )
        }

        try {
            // Copies of articles read over a week ago are dropped, saved ones are kept
            removeDownloads(dao.getOfflineIdsReadBefore(System.currentTimeMillis() - KEEP_READ_MS))

            val ids = dao.getUnreadIdsNewestFirst(if (limit > 0) limit else -1)
            total = ids.size
            val saving = application.getString(R.string.offline_saving_articles)
            update(true, saving)

            val next = AtomicInteger(0)
            coroutineScope {
                repeat(CONCURRENCY) {
                    launch {
                        while (true) {
                            val index = next.getAndIncrement()
                            if (index >= ids.size) break
                            ensureActive()
                            val result =
                                try {
                                    downloadOne(ids[index], images)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Log.e(LOG_TAG, "Failed to download ${ids[index]}", e)
                                    null
                                }
                            if (result == null || !result.fullTextOk) failed.incrementAndGet()
                            if (result != null) savedImages.addAndGet(result.images)
                            done.incrementAndGet()
                            update(true, saving)
                        }
                    }
                }
            }
            update(
                false,
                application.getString(R.string.offline_saved_summary, done.get(), savedImages.get()),
            )
        } catch (e: CancellationException) {
            update(false, application.getString(R.string.offline_stopped_after, done.get(), total))
            throw e
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Download for offline failed", e)
            update(false, application.getString(R.string.offline_download_failed, e.message ?: e.toString()))
        }
    }

    private class Result(
        val fullTextOk: Boolean,
        val images: Int,
    )

    private suspend fun downloadOne(
        itemId: Long,
        images: Boolean,
    ): Result? {
        val item = dao.loadFeedItem(itemId) ?: return null
        if (item.offlineTime != null) return Result(fullTextOk = true, images = 0)
        val link = item.link
        val feedUrl = dao.loadFeedUrlOfFeedItem(itemId)?.toString() ?: ""

        // Full text for everything with a page (YouTube pages have none); feed text stays as the fallback
        var fullTextOk = true
        if (link != null && !isYouTubeLink(link)) {
            fullTextOk = fullTextParser.parseFullArticleIfMissing(item, ignoreOfflineMode = true).isRight()
            offlineFiles.keepFullText(itemId)
        }

        val keys = mutableListOf<String>()
        var count = 0
        if (images) {
            for (image in imagesOf(itemId, link ?: "", feedUrl, item.thumbnailImage?.url)) {
                currentCoroutineContext().ensureActive()
                val key = saveImage(image.best) ?: continue
                keys += key
                count++
                for (other in image.others) {
                    val otherKey = OfflineFiles.imageKey(other)
                    if (otherKey == key || offlineFiles.imageFile(otherKey).isFile) continue
                    offlineFiles.aliasFile(otherKey).writeText(key)
                    keys += otherKey
                }
            }
        }
        offlineFiles.writeManifest(itemId, keys)
        dao.setOfflineTime(itemId, System.currentTimeMillis())
        return Result(fullTextOk = fullTextOk, images = count)
    }

    private class ImageToSave(
        val best: String,
        val others: List<String>,
    )

    /**
     * Images the reader would show for the article: the thumbnail, and for every image in the
     * full text and the feed text, the size the reader picks on this screen. The other sizes
     * point to the same saved file.
     */
    private fun imagesOf(
        itemId: Long,
        link: String,
        feedUrl: String,
        thumbnail: String?,
    ): List<ImageToSave> {
        val result = linkedMapOf<String, ImageToSave>()
        thumbnail?.takeIf { it.startsWith("http") }?.let { result[it] = ImageToSave(it, emptyList()) }

        val metrics = application.resources.displayMetrics
        val maxWidth = metrics.widthPixels.coerceAtMost(2000)
        val density = metrics.density

        val sources =
            listOf(
                blobFullFile(itemId, filePathProvider.fullArticleDir),
                blobFile(itemId, filePathProvider.articleDir),
            )
        for (file in sources) {
            if (!file.isFile) continue
            val elements =
                try {
                    gzipStream(file).use { stream ->
                        HtmlLinearizer(tooLargeText = "", openInBrowserText = "", articleLink = link)
                            .linearize(inputStream = stream, baseUrl = feedUrl)
                            .elements
                    }
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "Could not read $file", e)
                    continue
                }
            collectImages(elements) { image ->
                val best = image.bestSource(density, maxWidth)?.imgUri ?: return@collectImages
                if (!best.startsWith("http") || best in result) return@collectImages
                result[best] =
                    ImageToSave(
                        best = best,
                        others = image.sources.map { it.imgUri }.filter { it != best && it.startsWith("http") },
                    )
            }
            collectVideoThumbnails(elements) { url ->
                if (url.startsWith("http") && url !in result) result[url] = ImageToSave(url, emptyList())
            }
        }
        return result.values.take(MAX_IMAGES_PER_ARTICLE)
    }

    /** Saves one image, returns its key, or null if it couldn't be saved. */
    private fun saveImage(url: String): String? {
        val key = OfflineFiles.imageKey(url)
        val file = offlineFiles.imageFile(key)
        if (file.isFile) return key
        return try {
            val request = Request.Builder().url(url).build()
            imageClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val type = response.body.contentType()
                if (type != null && type.type != "image" && type.subtype != "octet-stream") return null
                val length = response.body.contentLength()
                if (length > MAX_IMAGE_BYTES) return null
                offlineFiles.imagesDir.mkdirs()
                val tmp = File(offlineFiles.imagesDir, "$key.tmp")
                var tooLarge = false
                response.body.byteStream().use { input ->
                    tmp.outputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var written = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            written += read
                            if (written > MAX_IMAGE_BYTES) {
                                tooLarge = true
                                break
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                if (tooLarge || !tmp.renameTo(file)) {
                    tmp.delete()
                    null
                } else {
                    key
                }
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Could not save image $url: ${e.message}")
            null
        }
    }

    companion object {
        private const val LOG_TAG = "FEEDER_OFFLINE"
        private const val CONCURRENCY = 4
        private const val MAX_IMAGES_PER_ARTICLE = 30
        private const val MAX_IMAGE_BYTES = 8L * 1024 * 1024

        /** Downloads of read, unsaved articles are dropped this long after reading. */
        private const val KEEP_READ_MS = 7L * 24 * 60 * 60 * 1000

        val LIMIT_OPTIONS = listOf(100, 250, 500, 0)
    }
}

private fun gzipStream(file: File): InputStream = java.util.zip.GZIPInputStream(file.inputStream())

fun isYouTubeLink(link: String): Boolean {
    val host =
        try {
            URI(link).host?.lowercase()
        } catch (e: Exception) {
            null
        } ?: return false
    return host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")
}

private fun collectImages(
    elements: List<LinearElement>,
    onImage: (LinearImage) -> Unit,
) {
    for (element in elements) {
        when (element) {
            is LinearImage -> onImage(element)
            is LinearListItem -> collectImages(element.content, onImage)
            is LinearBlockQuote -> collectImages(element.content, onImage)
            is LinearTable -> element.cells.values.forEach { collectImages(it.content, onImage) }
            else -> Unit
        }
    }
}

private fun collectVideoThumbnails(
    elements: List<LinearElement>,
    onThumbnail: (String) -> Unit,
) {
    for (element in elements) {
        when (element) {
            is LinearVideo -> element.imageThumbnail?.let(onThumbnail)
            is LinearListItem -> collectVideoThumbnails(element.content, onThumbnail)
            is LinearBlockQuote -> collectVideoThumbnails(element.content, onThumbnail)
            is LinearTable -> element.cells.values.forEach { collectVideoThumbnails(it.content, onThumbnail) }
            else -> Unit
        }
    }
}

/** Same choice as the reader makes (LinearArticleContent). */
private fun LinearImage.bestSource(
    pixelDensity: Float,
    maxWidth: Int,
): LinearImageSource? =
    sources.minByOrNull { candidate ->
        val candidateSize =
            when {
                candidate.pixelDensity != null -> candidate.pixelDensity / pixelDensity
                candidate.screenWidth != null -> candidate.screenWidth / maxWidth.toFloat()
                candidate.widthPx != null -> candidate.widthPx / maxWidth.toFloat()
                else -> 1.0f / pixelDensity
            }
        abs(candidateSize - 1.0f)
    }
