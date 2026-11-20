package com.nononsenseapps.feeder.model.offline

import android.util.Log
import com.nononsenseapps.feeder.blob.blobFullFile
import com.nononsenseapps.feeder.util.FilePathProvider
import java.io.File
import java.security.MessageDigest

/**
 * Where downloads for offline reading live. Everything is under the files dir (not the cache
 * dir) so Android doesn't clear it when storage runs low:
 *
 * - `offline/images/<key>`: an image, [key] being the sha-256 of its url
 * - `offline/images/<key>.alias`: another size of a saved image (srcset); holds the saved key
 * - `offline/full/<id>.full.html.gz`: a copy of the full text (the reader's copy is in the cache dir)
 * - `offline/items/<id>.txt`: the image keys an article saved, so deleting it removes them
 * - `offline/videos/<id>.*`: a saved YouTube video, see [OfflineVideos]
 */
class OfflineFiles(
    private val filePathProvider: FilePathProvider,
) {
    val rootDir: File
        get() = filePathProvider.filesDir.resolve("offline")
    val imagesDir: File
        get() = rootDir.resolve("images")
    val fullDir: File
        get() = rootDir.resolve("full")
    val itemsDir: File
        get() = rootDir.resolve("items")
    val videosDir: File
        get() = rootDir.resolve("videos")

    private fun videoFiles(itemId: Long): List<File> =
        videosDir
            .listFiles { file -> file.name.startsWith("$itemId.") }
            ?.toList()
            .orEmpty()

    /** The saved copy of an article's YouTube video, if complete. */
    fun savedVideo(itemId: Long): SavedVideo? {
        val files = videoFiles(itemId).filter { it.isFile && it.length() > 0 }
        val subtitles = files.filter { it.name.startsWith("$itemId.subs.") && it.name.endsWith(".vtt") }
        files.firstOrNull { it.name.startsWith("$itemId.muxed.") }?.let { return SavedVideo(it, null, subtitles) }
        val video = files.firstOrNull { it.name.startsWith("$itemId.video.") } ?: return null
        val audio = files.firstOrNull { it.name.startsWith("$itemId.audio.") } ?: return null
        return SavedVideo(video, audio, subtitles)
    }

    fun deleteVideo(itemId: Long) {
        videoFiles(itemId).forEach { it.delete() }
    }

    fun imageFile(key: String): File = imagesDir.resolve(key)

    fun aliasFile(key: String): File = imagesDir.resolve("$key.alias")

    fun manifestFile(itemId: Long): File = itemsDir.resolve("$itemId.txt")

    fun fullTextCopy(itemId: Long): File = blobFullFile(itemId, fullDir)

    /** The saved copy of the image at [url], if there is one. */
    fun savedImage(url: String): File? {
        if (!url.startsWith("http")) return null
        val key = imageKey(url)
        val file = imageFile(key)
        if (file.isFile) return file
        val alias = aliasFile(key)
        if (!alias.isFile) return null
        val target = imageFile(alias.readText().trim())
        return target.takeIf { it.isFile }
    }

    /** Copies the downloaded full text to where the reader looks, if the cache lost it. */
    fun restoreFullText(itemId: Long) {
        try {
            val cached = blobFullFile(itemId, filePathProvider.fullArticleDir)
            val copy = fullTextCopy(itemId)
            if (!cached.isFile && copy.isFile) {
                filePathProvider.fullArticleDir.mkdirs()
                copy.copyTo(cached, overwrite = true)
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Could not restore full text of $itemId", e)
        }
    }

    /** Keeps a copy of the full text the reader cached for [itemId]. */
    fun keepFullText(itemId: Long) {
        try {
            val cached = blobFullFile(itemId, filePathProvider.fullArticleDir)
            if (cached.isFile) {
                fullDir.mkdirs()
                cached.copyTo(fullTextCopy(itemId), overwrite = true)
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Could not keep full text of $itemId", e)
        }
    }

    fun writeManifest(
        itemId: Long,
        keys: Collection<String>,
    ) {
        itemsDir.mkdirs()
        manifestFile(itemId).writeText(keys.joinToString("\n"))
    }

    /** Removes everything downloaded for these articles. Safe to call for any article. */
    fun deleteDownloads(itemIds: Collection<Long>) {
        if (!rootDir.isDirectory) return
        for (id in itemIds) {
            try {
                val manifest = manifestFile(id)
                if (manifest.isFile) {
                    manifest.readLines().filter { it.isNotBlank() }.forEach { key ->
                        imageFile(key).delete()
                        aliasFile(key).delete()
                    }
                    manifest.delete()
                }
                fullTextCopy(id).delete()
                deleteVideo(id)
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Could not delete downloads of $id", e)
            }
        }
    }

    /** Removes downloads of articles that no longer exist (deleted feeds, synced deletes). */
    fun deleteOrphans(existingIds: Set<Long>) {
        val ids =
            itemsDir
                .listFiles()
                ?.mapNotNull { it.name.removeSuffix(".txt").toLongOrNull() }
                .orEmpty() +
                fullDir
                    .listFiles()
                    ?.mapNotNull { it.name.substringBefore('.').toLongOrNull() }
                    .orEmpty() +
                videosDir
                    .listFiles()
                    ?.mapNotNull { it.name.substringBefore('.').toLongOrNull() }
                    .orEmpty()
        deleteDownloads(ids.filterNot { it in existingIds }.toSet())
    }

    fun deleteAll() {
        rootDir.deleteRecursively()
    }

    fun sizeBytes(): Long =
        if (rootDir.isDirectory) {
            rootDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } else {
            0L
        }

    companion object {
        private const val LOG_TAG = "FEEDER_OFFLINE"

        fun imageKey(url: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(url.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
