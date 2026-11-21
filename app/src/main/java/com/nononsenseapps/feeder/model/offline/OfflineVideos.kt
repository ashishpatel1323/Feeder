package com.nononsenseapps.feeder.model.offline

import android.app.Application
import android.util.Log
import com.nononsenseapps.feeder.ApplicationCoroutineScope
import com.nononsenseapps.feeder.db.room.FeedItemDao
import com.nononsenseapps.feeder.model.youtube.VideoDetails
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** A YouTube video saved on the phone. [audio] is null when [video] already has sound. */
data class SavedVideo(
    val video: File,
    val audio: File?,
    val subtitles: List<File>,
) {
    val sizeBytes: Long
        get() = video.length() + (audio?.length() ?: 0L) + subtitles.sumOf { it.length() }
}

/** A Save video offline in progress (or failed). */
data class VideoJob(
    val phase: VideoPhase,
    val percent: Float = 0f,
    val error: String? = null,
)

enum class VideoPhase {
    PREPARING,
    VIDEO,
    AUDIO,
    SUBTITLES,
    ERROR,
}

/**
 * Save video offline for YouTube articles, one video at a time when asked to, with yt-dlp
 * running on the phone (youtubedl-android: yt-dlp with its own Python). Up to 720p.
 *
 * Without ffmpeg on the phone, video and sound are saved as two files and the player plays
 * them together. English subtitles (or automatic ones) are saved too when there are any.
 * Files are `offline/videos/<itemId>.*`, so deleting the article deletes them (see [OfflineFiles]).
 */
class OfflineVideos(
    override val di: DI,
) : DIAware {
    private val application: Application by instance()
    private val offlineFiles: OfflineFiles by instance()
    private val dao: FeedItemDao by instance()
    private val applicationCoroutineScope: ApplicationCoroutineScope by instance()

    private val _jobs = MutableStateFlow<Map<Long, VideoJob>>(emptyMap())
    val jobs: StateFlow<Map<Long, VideoJob>> = _jobs.asStateFlow()

    /** Bumped when a video is saved or removed, so screens look again. */
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()

    private val running = ConcurrentHashMap<Long, Job>()
    private val initMutex = Mutex()
    private var initialized = false
    private var updated = false

    /** The saved copy of this article's video, or null. */
    fun savedFiles(itemId: Long): SavedVideo? = offlineFiles.savedVideo(itemId)

    fun save(
        itemId: Long,
        link: String,
    ) {
        if (running[itemId]?.isActive == true) return
        setJob(itemId, VideoJob(VideoPhase.PREPARING))
        running[itemId] =
            applicationCoroutineScope.launch(Dispatchers.IO) {
                try {
                    download(itemId, link)
                    setJob(itemId, null)
                    // Saving a video counts as downloading the article (Downloaded tab)
                    dao.setOfflineTime(itemId, System.currentTimeMillis())
                    _changes.update { it + 1 }
                } catch (e: CancellationException) {
                    offlineFiles.deleteVideo(itemId)
                    setJob(itemId, null)
                    throw e
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "Save video offline failed for $link", e)
                    offlineFiles.deleteVideo(itemId)
                    setJob(itemId, VideoJob(VideoPhase.ERROR, error = e.message?.lineSequence()?.lastOrNull { it.isNotBlank() } ?: e.toString()))
                } finally {
                    running.remove(itemId)
                }
            }
    }

    fun cancel(itemId: Long) {
        YoutubeDL.getInstance().destroyProcessById(processId(itemId))
        running[itemId]?.cancel()
    }

    fun dismissError(itemId: Long) = setJob(itemId, null)

    suspend fun remove(itemId: Long) {
        withContext(Dispatchers.IO) {
            offlineFiles.deleteVideo(itemId)
        }
        _changes.update { it + 1 }
    }

    private suspend fun ensureReady() {
        initMutex.withLock {
            if (!initialized) {
                YoutubeDL.getInstance().init(application)
                initialized = true
            }
            if (!updated) {
                // YouTube changes often; the yt-dlp inside the app ages quickly
                try {
                    YoutubeDL.getInstance().updateYoutubeDL(application)
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Could not update yt-dlp: ${e.message}")
                }
                updated = true
            }
        }
    }

    private suspend fun download(
        itemId: Long,
        link: String,
    ) {
        ensureReady()
        val dir = offlineFiles.videosDir
        dir.mkdirs()
        offlineFiles.deleteVideo(itemId)

        // Video without sound up to 720p (H.264 preferred: every phone plays it), then the sound
        val videoOk =
            try {
                run(
                    itemId,
                    VideoPhase.VIDEO,
                    YoutubeDLRequest(link)
                        .addOption("-f", "bv[height<=720][ext=mp4][vcodec^=avc1]/bv[height<=720][ext=mp4]/bv[height<=720]")
                        .addOption("-o", File(dir, "$itemId.video.%(ext)s").absolutePath),
                )
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(LOG_TAG, "No separate video stream, trying one with sound: ${e.message}")
                false
            }
        if (videoOk) {
            run(
                itemId,
                VideoPhase.AUDIO,
                YoutubeDLRequest(link)
                    .addOption("-f", "ba[ext=m4a]/ba")
                    .addOption("-o", File(dir, "$itemId.audio.%(ext)s").absolutePath),
            )
        } else {
            run(
                itemId,
                VideoPhase.VIDEO,
                YoutubeDLRequest(link)
                    .addOption("-f", "b[height<=720][ext=mp4]/b[height<=720]/b")
                    .addOption("-o", File(dir, "$itemId.muxed.%(ext)s").absolutePath),
            )
        }

        // Subtitles are a bonus
        try {
            run(
                itemId,
                VideoPhase.SUBTITLES,
                YoutubeDLRequest(link)
                    .addOption("--skip-download")
                    .addOption("--write-subs")
                    .addOption("--write-auto-subs")
                    .addOption("--sub-langs", "en.*,en")
                    .addOption("--sub-format", "vtt")
                    .addOption("-o", File(dir, "$itemId.subs").absolutePath),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "No subtitles: ${e.message}")
        }

        if (offlineFiles.savedVideo(itemId) == null) {
            throw IllegalStateException("yt-dlp finished but no video file was saved")
        }
    }

    private fun run(
        itemId: Long,
        phase: VideoPhase,
        request: YoutubeDLRequest,
    ) {
        request
            .addOption("--no-playlist")
            .addOption("--no-mtime")
            .addOption("--no-part")
        setJob(itemId, VideoJob(phase))
        YoutubeDL.getInstance().execute(request, processId(itemId)) { progress, _, _ ->
            setJob(itemId, VideoJob(phase, percent = progress.coerceIn(0f, 100f)))
        }
    }

    private val detailsCache = ConcurrentHashMap<String, Pair<Long, VideoDetails>>()

    /**
     * Everything the app's player needs about a video, from yt-dlp: its own streams (every
     * quality and audio track), subtitles, chapters and info. Playing these streams instead of
     * YouTube's embed means no ads, like NewPipe-based apps (Flow, LibreTube). Stream addresses
     * expire after some hours, so details are kept for a while only.
     */
    suspend fun videoDetails(link: String): VideoDetails =
        withContext(Dispatchers.IO) {
            detailsCache[link]?.let { (at, details) ->
                if (System.currentTimeMillis() - at < STREAM_URLS_FOR_MS) return@withContext details
            }
            ensureReady()
            val response =
                YoutubeDL.getInstance().execute(
                    YoutubeDLRequest(link)
                        .addOption("--no-playlist")
                        .addOption("-J"),
                )
            val details = VideoDetails.parse(response.out)
            check(details.hasStreams) { "yt-dlp found no stream" }
            details.also { detailsCache[link] = System.currentTimeMillis() to it }
        }

    /** Drops cached details whose streams stopped working. */
    fun forgetDetails(link: String) {
        detailsCache.remove(link)
    }

    private fun setJob(
        itemId: Long,
        job: VideoJob?,
    ) {
        _jobs.update { if (job == null) it - itemId else it + (itemId to job) }
    }

    private fun processId(itemId: Long) = "feeder-video-$itemId"

    companion object {
        private const val LOG_TAG = "FEEDER_OFFLINE_VIDEO"

        /** Stream addresses last about 6 hours; use them for less than that. */
        private const val STREAM_URLS_FOR_MS = 4 * 60 * 60 * 1000L
    }
}
