package com.nononsenseapps.feeder.model.offline

import android.app.Application
import android.content.SharedPreferences
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

/** A Save video offline in progress (or paused, or failed). */
data class VideoJob(
    val phase: VideoPhase,
    val percent: Float = 0f,
    val error: String? = null,
    /** How far the whole download is, 0 to 100 (video is most of it, then sound). */
    val overall: Float = 0f,
)

enum class VideoPhase {
    PREPARING,
    VIDEO,
    AUDIO,
    SUBTITLES,
    ERROR,

    /** Stopped by Pause; the partial files stay so Resume carries on where it stopped. */
    PAUSED,
}

/** How far a whole download is, given the phase and that phase's percent. */
fun overallPercent(
    phase: VideoPhase,
    percent: Float,
): Float =
    when (phase) {
        VideoPhase.PREPARING -> 0f
        VideoPhase.VIDEO -> percent * 0.8f
        VideoPhase.AUDIO -> 80f + percent * 0.2f
        VideoPhase.SUBTITLES -> 100f
        VideoPhase.ERROR, VideoPhase.PAUSED -> percent
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
    private val prefs: SharedPreferences by instance()

    /** Links of downloads not finished yet, so Pause/Resume and Retry work after a restart. */
    private val links = ConcurrentHashMap<Long, String>()
    private val pausing = ConcurrentHashMap.newKeySet<Long>()

    private val _jobs = MutableStateFlow(loadPending())
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
    ) = start(itemId, link, resume = false)

    /** Carries on a paused (or interrupted) download from its partial files. */
    fun resume(itemId: Long) {
        val link = links[itemId] ?: return
        start(itemId, link, resume = true)
    }

    /** Starts a failed download again from the start. */
    fun retry(itemId: Long) {
        val link = links[itemId] ?: return
        start(itemId, link, resume = false)
    }

    private fun start(
        itemId: Long,
        link: String,
        resume: Boolean,
    ) {
        if (running[itemId]?.isActive == true) return
        links[itemId] = link
        val from = _jobs.value[itemId]?.takeIf { resume }?.overall ?: 0f
        setJob(itemId, VideoJob(VideoPhase.PREPARING, overall = from))
        running[itemId] =
            applicationCoroutineScope.launch(Dispatchers.IO) {
                try {
                    download(itemId, link, resume)
                    links.remove(itemId)
                    setJob(itemId, null)
                    // Saving a video counts as downloading the article (Downloaded tab)
                    dao.setOfflineTime(itemId, System.currentTimeMillis())
                    _changes.update { it + 1 }
                } catch (e: CancellationException) {
                    if (pausing.remove(itemId)) {
                        val overall = _jobs.value[itemId]?.overall ?: 0f
                        setJob(itemId, VideoJob(VideoPhase.PAUSED, percent = overall, overall = overall))
                    } else {
                        offlineFiles.deleteVideo(itemId)
                        links.remove(itemId)
                        setJob(itemId, null)
                    }
                    throw e
                } catch (e: Exception) {
                    if (pausing.remove(itemId)) {
                        // yt-dlp ends with an error when its process is stopped
                        val overall = _jobs.value[itemId]?.overall ?: 0f
                        setJob(itemId, VideoJob(VideoPhase.PAUSED, percent = overall, overall = overall))
                    } else {
                        Log.e(LOG_TAG, "Save video offline failed for $link", e)
                        offlineFiles.deleteVideo(itemId)
                        setJob(itemId, VideoJob(VideoPhase.ERROR, error = e.message?.lineSequence()?.lastOrNull { it.isNotBlank() } ?: e.toString()))
                    }
                } finally {
                    running.remove(itemId)
                }
            }
    }

    /** Stops and deletes a download in progress (or paused, or failed). */
    fun cancel(itemId: Long) {
        if (running[itemId]?.isActive == true) {
            YoutubeDL.getInstance().destroyProcessById(processId(itemId))
            running[itemId]?.cancel()
        } else {
            // Paused or failed: nothing runs, so delete what is left here
            links.remove(itemId)
            setJob(itemId, null)
            applicationCoroutineScope.launch(Dispatchers.IO) { offlineFiles.deleteVideo(itemId) }
        }
    }

    /** Stops a download but keeps what it saved so far, see [resume]. */
    fun pause(itemId: Long) {
        if (running[itemId]?.isActive != true) return
        pausing.add(itemId)
        YoutubeDL.getInstance().destroyProcessById(processId(itemId))
        running[itemId]?.cancel()
    }

    fun dismissError(itemId: Long) {
        links.remove(itemId)
        setJob(itemId, null)
    }

    /** Ids of every saved (complete) video on the phone. */
    fun savedIds(): List<Long> =
        offlineFiles.videosDir
            .listFiles()
            ?.mapNotNull { it.name.substringBefore('.').toLongOrNull() }
            .orEmpty()
            .distinct()
            .filter { offlineFiles.savedVideo(it) != null }

    /** Deletes every saved video and every unfinished download. */
    suspend fun removeAll() {
        for (id in _jobs.value.keys.toList()) cancel(id)
        withContext(Dispatchers.IO) {
            savedIds().forEach { offlineFiles.deleteVideo(it) }
        }
        _changes.update { it + 1 }
    }

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
        resume: Boolean,
    ) {
        ensureReady()
        val dir = offlineFiles.videosDir
        dir.mkdirs()
        // Resuming keeps the partial (.part) files and finished ones; yt-dlp carries on from them
        if (!resume) offlineFiles.deleteVideo(itemId)

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
        // .part files (no --no-part) so a paused download carries on instead of starting over
        request
            .addOption("--no-playlist")
            .addOption("--no-mtime")
            .addOption("--continue")
        val before = _jobs.value[itemId]?.overall ?: 0f
        setJob(itemId, VideoJob(phase, overall = maxOf(before, overallPercent(phase, 0f))))
        YoutubeDL.getInstance().execute(request, processId(itemId)) { progress, _, _ ->
            val percent = progress.coerceIn(0f, 100f)
            setJob(itemId, VideoJob(phase, percent = percent, overall = overallPercent(phase, percent)))
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
        val old = _jobs.value[itemId]
        _jobs.update { if (job == null) it - itemId else it + (itemId to job) }
        // Progress ticks often; only write when a download starts, stops or changes state
        if (old?.phase != job?.phase || (job == null) != (old == null)) savePending()
    }

    /**
     * Unfinished downloads, kept so the Downloads screen still lists them after a restart.
     * One line each: id, phase, percent, link. A download that was running when the app
     * stopped comes back paused, its partial files ready for Resume.
     */
    private fun loadPending(): Map<Long, VideoJob> =
        try {
            prefs
                .getString(PREF_PENDING, null)
                .orEmpty()
                .lineSequence()
                .mapNotNull { line ->
                    val parts = line.split('\t', limit = 4)
                    if (parts.size < 4) return@mapNotNull null
                    val id = parts[0].toLongOrNull() ?: return@mapNotNull null
                    val percent = parts[2].toFloatOrNull() ?: 0f
                    links[id] = parts[3]
                    val phase = if (parts[1] == VideoPhase.ERROR.name) VideoPhase.ERROR else VideoPhase.PAUSED
                    id to VideoJob(phase, percent = percent, overall = percent)
                }.toMap()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Could not read unfinished downloads: ${e.message}")
            emptyMap()
        }

    private fun savePending() {
        val text =
            _jobs.value.entries
                .mapNotNull { (id, job) ->
                    val link = links[id] ?: return@mapNotNull null
                    "$id\t${job.phase.name}\t${job.overall}\t$link"
                }.joinToString("\n")
        prefs.edit().putString(PREF_PENDING, text).apply()
    }

    private fun processId(itemId: Long) = "feeder-video-$itemId"

    companion object {
        private const val LOG_TAG = "FEEDER_OFFLINE_VIDEO"
        private const val PREF_PENDING = "offline_videos_pending"

        /** Stream addresses last about 6 hours; use them for less than that. */
        private const val STREAM_URLS_FOR_MS = 4 * 60 * 60 * 1000L
    }
}
