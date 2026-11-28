package com.nononsenseapps.feeder.model.shelf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Book covers (ebooks, audiobooks) decoded small and kept in memory, so shelves don't decode
 * every full-size cover again each time they open.
 *
 * Covers on disk can be the book's own picture at full size (3000 px album art is common), and
 * decoding all of them at full size before showing any was what made the Books shelves slow.
 */
object CoverBitmaps {
    /** Long side in pixels: sharp on the largest cover (the book page), small everywhere else. */
    const val MAX_SIDE = 720

    private class Entry(
        val stamp: String,
        val image: ImageBitmap,
        val bytes: Int,
    )

    private val cache =
        object : LruCache<String, Entry>(32 * 1024 * 1024) {
            override fun sizeOf(
                key: String,
                value: Entry,
            ): Int = value.bytes
        }

    /** The cover already in memory, if any (no disk access: safe on the main thread). */
    fun peek(file: File): ImageBitmap? = cache.get(file.path)?.image

    /** The cover, decoded small, or null when there is none. Reads the disk: call off the main thread. */
    fun load(file: File): ImageBitmap? {
        if (!file.isFile) {
            cache.remove(file.path)
            return null
        }
        // A changed cover (picked from the internet, say) is a new file with a new time or size
        val stamp = "${file.lastModified()}:${file.length()}"
        cache.get(file.path)?.takeIf { it.stamp == stamp }?.let { return it.image }
        val bmp = decodeSmall(file) ?: return null
        val image = bmp.asImageBitmap()
        cache.put(file.path, Entry(stamp, image, bmp.allocationByteCount))
        return image
    }

    /** Decodes at the smallest power-of-two step that keeps at least [MAX_SIDE] on the long side. */
    private fun decodeSmall(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) })
    }

    internal fun sampleSize(
        width: Int,
        height: Int,
    ): Int {
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= MAX_SIDE) sample *= 2
        return sample
    }
}

/**
 * Covers for [ids], given to [emit] as they decode: first the ones already in memory, then a few
 * more at a time, so a shelf fills in from the top instead of waiting for every cover.
 */
suspend fun loadCovers(
    ids: List<String>,
    fileOf: (String) -> File,
    emit: (Map<String, ImageBitmap>) -> Unit,
) {
    val out = LinkedHashMap<String, ImageBitmap>()
    for (id in ids) CoverBitmaps.peek(fileOf(id))?.let { out[id] = it }
    if (ids.isEmpty()) emit(emptyMap())
    for (chunk in ids.chunked(6)) {
        withContext(Dispatchers.IO) {
            for (id in chunk) {
                val image = CoverBitmaps.load(fileOf(id))
                if (image != null) out[id] = image else out.remove(id)
            }
        }
        emit(out.toMap())
    }
}
