package com.nononsenseapps.feeder.model.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.nononsenseapps.feeder.dropbox.DropboxClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The picture on each row of Books > Player, in this order:
 *  1. the thumbnail kept with a moved YouTube download ([MediaLibrary.keptThumb]),
 *  2. a picture next to the file in Dropbox with the same name ([MediaFile.thumb]),
 *  3. the cover art inside the file (MP3, M4A, MP4…),
 *  4. for a video, a frame from about a tenth of the way in.
 * Found pictures are kept small in the app's cache, so each file is looked at once. When none is
 * found the row keeps its coloured tile, and the file is tried again after a week or once it is on
 * the phone. Same order as the web app (web/src/lib/media/thumbs.ts).
 */
class MediaThumbs private constructor(
    private val context: Context,
) {
    private val library by lazy { MediaLibrary.get(context) }
    private val dropbox by lazy { DropboxClient.get(context) }
    private val dir = File(context.cacheDir, "media-thumbs").apply { mkdirs() }

    /** Two at a time: a Dropbox file is read over the network */
    private val gate = Semaphore(2)
    private val running = ConcurrentHashMap<String, CompletableDeferred<File?>>()

    /** A picture put next to the file in Dropbox is kept apart, so it replaces a frame found before. */
    private fun cached(m: MediaFile) = File(dir, if (m.thumb != null) "${m.id}-s.jpg" else "${m.id}.jpg")

    private fun missMark(m: MediaFile) = File(dir, if (m.thumb != null) "${m.id}-s.none" else "${m.id}.none")

    /** The picture already found for [m], without looking (for drawing the list right away). */
    fun known(m: MediaFile): File? = library.keptThumb(m.id).takeIf { it.exists() } ?: cached(m).takeIf { it.exists() }

    /** The picture for [m]: found before, or looked for now. Null when it has none. */
    suspend fun get(m: MediaFile): File? {
        known(m)?.let { return it }
        val onPhone = library.onPhone(m)
        if (!MediaThumbRules.shouldTry(missMark(m).takeIf { it.exists() }?.let { it.lastModified() to it.readText() }, onPhone, System.currentTimeMillis())) {
            return null
        }
        val mine = CompletableDeferred<File?>()
        val other = running.putIfAbsent(m.id, mine)
        if (other != null) return other.await()
        return try {
            val found = gate.withPermit { withContext(Dispatchers.IO) { find(m, onPhone) } }
            mine.complete(found)
            found
        } catch (e: CancellationException) {
            mine.complete(null)
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "No picture for ${m.name}", e)
            mine.complete(null)
            null
        } finally {
            running.remove(m.id)
        }
    }

    private suspend fun find(
        m: MediaFile,
        onPhone: Boolean,
    ): File? {
        val picture =
            runCatching { m.thumb?.let { sidecar(it) } }.getOrNull()
                ?: runCatching { fromFile(m) }.onFailure { Log.w(LOG_TAG, "No picture from ${m.name}: ${it.message}") }.getOrNull()
        val out = cached(m)
        if (picture == null) {
            missMark(m).writeText(if (onPhone) PHONE else REMOTE)
            return null
        }
        try {
            out.outputStream().use { picture.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        } finally {
            picture.recycle()
        }
        missMark(m).delete()
        return out
    }

    /** A picture next to the file in Dropbox. */
    private suspend fun sidecar(path: String): Bitmap? {
        if (dropbox.link.value == null) return null
        val tmp = File(dir, "dl-${System.nanoTime()}.img")
        return try {
            dropbox.download(path, tmp)
            decodeSmall(tmp)
        } finally {
            tmp.delete()
        }
    }

    /** The cover art inside the file, else (a video) a frame. Reads a Dropbox file over the network. */
    private suspend fun fromFile(m: MediaFile): Bitmap? {
        val r = MediaMetadataRetriever()
        try {
            val local = library.localFile(m)
            when {
                local != null -> r.setDataSource(local.path)
                m.uri != null -> r.setDataSource(context, Uri.parse(m.uri))
                m.dropboxPath != null && dropbox.link.value != null -> r.setDataSource(dropbox.temporaryLink(m.dropboxPath), emptyMap())
                else -> return null
            }
            r.embeddedPicture?.let { bytes -> decodeSmall(bytes)?.let { return it } }
            if (!m.isVideo) return null
            val durationMs =
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    ?: m.duration?.let { (it * 1000).toLong() }
            val atUs = MediaThumbRules.frameAtMs(durationMs) * 1000
            val frame =
                r.getScaledFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, SIDE, SIDE)
                    ?: r.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, SIDE, SIDE)
            return frame
        } finally {
            runCatching { r.release() }
        }
    }

    private fun decodeSmall(file: File): Bitmap? = decodeSmall { opts -> BitmapFactory.decodeFile(file.path, opts) }

    private fun decodeSmall(bytes: ByteArray): Bitmap? = decodeSmall { opts -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }

    /** Decodes a picture at about [SIDE] pixels on its long side. */
    private fun decodeSmall(decode: (BitmapFactory.Options) -> Bitmap?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decode(bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIDE) sample *= 2
        return decode(BitmapFactory.Options().apply { inSampleSize = sample })
    }

    companion object {
        private const val LOG_TAG = "FEEDER_MEDIA"
        private const val SIDE = 480
        internal const val PHONE = "phone"
        internal const val REMOTE = "dropbox"

        @Volatile
        private var instance: MediaThumbs? = null

        fun get(context: Context): MediaThumbs =
            instance ?: synchronized(this) {
                instance ?: MediaThumbs(context.applicationContext).also { instance = it }
            }
    }
}

/** When to look for a picture, and where in a video to take the frame (same as the web app). */
object MediaThumbRules {
    const val RETRY_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * Whether to look for a file's picture again: [miss] is when nothing was found before and
     * whether the file was on the phone then ([MediaThumbs.PHONE]) or only in Dropbox.
     */
    fun shouldTry(
        miss: Pair<Long, String>?,
        onPhone: Boolean,
        now: Long,
    ): Boolean {
        if (miss == null) return true
        val (at, where) = miss
        if (onPhone && where != MediaThumbs.PHONE) return true
        return now - at > RETRY_MS
    }

    /** About a tenth of the way in (past a black opening), at least 1 s and at most a minute in. */
    fun frameAtMs(durationMs: Long?): Long {
        if (durationMs == null || durationMs <= 0) return 5_000
        if (durationMs < 2_000) return durationMs / 2
        return (durationMs / 10).coerceIn(1_000, 60_000)
    }
}
