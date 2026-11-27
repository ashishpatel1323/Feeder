package com.nononsenseapps.feeder.model.media

import android.app.Application
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.nononsenseapps.feeder.db.room.FeedItemDao
import com.nononsenseapps.feeder.model.audiobooks.AudioPositions
import com.nononsenseapps.feeder.model.offline.OfflineFiles
import com.nononsenseapps.feeder.model.offline.OfflineVideos
import com.nononsenseapps.feeder.model.youtube.VideoPositions
import com.nononsenseapps.feeder.model.youtube.youtubeVideoId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import java.io.File
import java.nio.ByteBuffer

/**
 * Moves a download (a YouTube video or a podcast episode saved in Downloads) into Books >
 * Player: the file goes to Player › YouTube › <channel> (or Podcasts › <podcast>) on this phone,
 * with its subtitles and where it was watched to, and leaves Downloads. With upload it then goes
 * to Dropbox › Apps › Feeder › Media in the same folder, so every device has it. Same as the web
 * app's lib/media/fromDownloads.ts.
 *
 * YouTube downloads are often a video file without sound plus a sound file: they are joined into
 * one MP4 first, copying the streams as they are (no re-encoding).
 */
class DownloadsToPlayer(
    override val di: DI,
) : DIAware {
    private val app: Application by instance()
    private val dao: FeedItemDao by instance()
    private val offlineFiles: OfflineFiles by instance()
    private val offlineVideos: OfflineVideos by instance()
    private val videoPositions: VideoPositions by instance()
    private val okHttp: OkHttpClient by instance()
    private val library by lazy { MediaLibrary.get(app) }
    private val positions by lazy { AudioPositions.get(app) }

    /** Moves the download of article [itemId]; returns the Player file's id. Uploads afterwards when [upload]. */
    suspend fun move(
        itemId: Long,
        upload: Boolean,
    ): String {
        val item = withContext(Dispatchers.IO) { dao.loadFeedItemWithFeed(itemId) } ?: throw IllegalStateException("The article is gone")
        val title = item.plainTitle.ifBlank { item.title }
        val video = offlineFiles.savedVideo(itemId)
        val episode = if (video == null) offlineFiles.savedAudio(itemId) else null
        if (video == null && episode == null) throw IllegalStateException("The download is gone")
        val channel = item.author?.ifBlank { null } ?: item.feedDisplayTitle
        val id =
            withContext(Dispatchers.IO) {
                val tmp = File(library.dir, "move-$itemId.tmp")
                try {
                    val ext: String
                    if (video != null) {
                        if (video.audio == null) {
                            video.video.copyTo(tmp, overwrite = true)
                            ext = video.video.extension.ifBlank { "mp4" }
                        } else {
                            ext = if (video.video.extension.equals("webm", true) && video.audio.extension.equals("webm", true)) "webm" else "mp4"
                            StreamJoiner.join(video.video, video.audio, tmp, ext == "webm")
                        }
                    } else {
                        episode!!.copyTo(tmp, overwrite = true)
                        ext = episode.extension.ifBlank { "mp3" }
                    }
                    val path = MoveRules.playerPath(title, channel, if (video != null) MoveRules.YOUTUBE else MoveRules.PODCASTS, ext)
                    val subs = video?.subtitles?.let { MoveRules.pickSubtitle(it.map { f -> f.name }) }?.let { n -> video.subtitles.first { it.name == n } }
                    val picture = MoveRules.pictureUrl(item.link, item.thumbnailImage?.url)?.let { fetchPicture(it) }
                    library.addFile(tmp, path.second, path.first, if (video != null) MediaRules.VIDEO else MediaRules.AUDIO, subs, picture)
                } finally {
                    tmp.delete()
                }
            }
        // Where it was watched to carries over (the Player keeps places by file)
        val key = VideoPositions.articleKey(item.feedUrl.toString(), item.guid)
        videoPositions.get(key)?.takeIf { it.t > 0 && positions.get(id) == null }?.let { p ->
            positions.save(id, 0, id, p.t * 1000L, if (p.d > 0) (p.t.toDouble() / p.d).coerceAtMost(1.0) else 0.0)
            if (p.d > 0) library.setDuration(id, p.d.toDouble())
        }
        offlineVideos.remove(itemId)
        if (upload) library.uploadToDropbox(id)
        return id
    }

    /** The video's (or episode's) own picture, for its row in the Player; null when it can't be had. */
    private fun fetchPicture(url: String): ByteArray? =
        runCatching {
            okHttp.newCall(Request.Builder().url(url).build()).execute().use { res ->
                if (!res.isSuccessful) return@use null
                // Never more than the limit in memory, even without a Content-Length
                val source = res.body.source()
                if (source.request(MAX_PICTURE_BYTES + 1L)) return@use null
                source.readByteArray().takeIf { it.isNotEmpty() }
            }
        }.getOrNull()

    private companion object {
        const val MAX_PICTURE_BYTES = 5 * 1024 * 1024
    }
}

/** Where a moved download goes, and which subtitles come along (same as the web app). */
object MoveRules {
    const val YOUTUBE = "YouTube"
    const val PODCASTS = "Podcasts"

    /** A file or folder name from a title: no slashes or characters Dropbox and file systems refuse. */
    fun safeName(
        s: String,
        fallback: String,
    ): String =
        s
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimEnd('.')
            .take(120)
            .ifEmpty { fallback }

    /** (folder, name): YouTube/<channel>/<title>.mp4 */
    fun playerPath(
        title: String,
        channel: String,
        top: String = YOUTUBE,
        ext: String = "mp4",
    ): Pair<String, String> = "$top/${safeName(channel, if (top == YOUTUBE) "Unknown channel" else "Unknown podcast")}" to "${safeName(title, if (top == YOUTUBE) "Video" else "Episode")}.$ext"

    /**
     * The picture to keep with a moved download: a YouTube video's own thumbnail (the 16:9 one,
     * without the black bars of the bigger sizes), else the article's picture (a podcast episode's).
     */
    fun pictureUrl(
        link: String?,
        image: String?,
    ): String? = youtubeVideoId(link)?.let { "https://i.ytimg.com/vi/$it/mqdefault.jpg" } ?: image?.takeIf { it.startsWith("http") }

    /** The subtitle file to keep, from names like "12.subs.en.vtt": English first, else the first. */
    fun pickSubtitle(names: List<String>): String? {
        fun lang(n: String) = n.removeSuffix(".vtt").substringAfterLast('.')
        return names.firstOrNull { lang(it) == "en" } ?: names.firstOrNull { lang(it).startsWith("en") } ?: names.firstOrNull()
    }
}

/** Joins a video-only file and a sound-only file into one, copying the streams (no re-encoding). */
object StreamJoiner {
    /** Copies the video track of [video] and the sound of [audio] into one file at [out] (MP4, or WebM). */
    fun join(
        video: File,
        audio: File,
        out: File,
        webm: Boolean,
    ) {
        val muxer = MediaMuxer(out.absolutePath, if (webm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val sources = listOf(video to "video/", audio to "audio/")
        val extractors = mutableListOf<Pair<MediaExtractor, Int>>()
        try {
            for ((file, prefix) in sources) {
                val ex = MediaExtractor()
                ex.setDataSource(file.absolutePath)
                val track =
                    (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true }
                        ?: throw IllegalStateException("No $prefix track in ${file.name}")
                ex.selectTrack(track)
                extractors += ex to muxer.addTrack(ex.getTrackFormat(track))
            }
            muxer.start()
            val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            for ((ex, outTrack) in extractors) {
                while (true) {
                    val size = ex.readSampleData(buffer, 0)
                    if (size < 0) break
                    info.set(0, size, ex.sampleTime, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(outTrack, buffer, info)
                    ex.advance()
                }
            }
            muxer.stop()
        } finally {
            extractors.forEach { it.first.release() }
            runCatching { muxer.release() }
        }
    }
}
