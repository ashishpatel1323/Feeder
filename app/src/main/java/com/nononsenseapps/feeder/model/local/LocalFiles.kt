package com.nononsenseapps.feeder.model.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.edit
import com.nononsenseapps.feeder.dropbox.DropboxClient

/** A file on the phone, picked through Android's file picker (a document URI), and where it is in what was picked. */
data class LocalEntry(
    val uri: Uri,
    /** Path inside the picked folder ("Dune/01.mp3"), or just the name for a picked file */
    val rel: String,
    val name: String,
    val size: Long,
    val modified: Long,
)

/**
 * Files and folders on the phone that the Ebooks, ZIM and Audiobooks tabs read in place, without
 * copying them or uploading them anywhere. Access is kept across restarts with persisted URI
 * permissions; content hashes (the same as Dropbox's, so positions match the same file on other
 * devices) are remembered by URI, size and date so a rescan doesn't read every file again.
 */
object LocalFiles {
    private const val LOG_TAG = "FEEDER_LOCAL"

    private fun prefs(context: Context) = context.getSharedPreferences("local_files", Context.MODE_PRIVATE)

    /** Keeps read access to [uri] (a picked file or folder) after the app restarts. */
    fun keep(
        context: Context,
        uri: Uri,
    ) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            .onFailure { Log.w(LOG_TAG, "No persistable permission for $uri", it) }
    }

    /** Lets go of [uri] when nothing uses it any more. */
    fun release(
        context: Context,
        uri: Uri,
    ) {
        runCatching { context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    /** Whether the app still may read [uri] (the user can take access away, or the file can go). */
    fun canRead(
        context: Context,
        uri: Uri,
    ): Boolean = runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)

    /** A picked file's name, size and date. */
    fun describe(
        context: Context,
        uri: Uri,
    ): LocalEntry? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val name = c.getString(0) ?: uri.lastPathSegment ?: "file"
                LocalEntry(uri, name, name, if (c.isNull(1)) 0 else c.getLong(1), runCatching { c.getLong(2) }.getOrDefault(0))
            }
        }.getOrNull()

    /** Every file inside a picked folder ([tree] from ACTION_OPEN_DOCUMENT_TREE), subfolders included. */
    fun walk(
        context: Context,
        tree: Uri,
    ): List<LocalEntry> {
        val out = mutableListOf<LocalEntry>()
        val resolver = context.contentResolver

        fun visit(
            docId: String,
            prefix: String,
            depth: Int,
        ) {
            if (depth > 8) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val dirs = mutableListOf<Pair<String, String>>()
            resolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    if (name.startsWith(".")) continue
                    if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        dirs += id to name
                    } else {
                        out += LocalEntry(DocumentsContract.buildDocumentUriUsingTree(tree, id), prefix + name, name, if (c.isNull(3)) 0 else c.getLong(3), if (c.isNull(4)) 0 else c.getLong(4))
                    }
                }
            }
            dirs.forEach { (id, name) -> visit(id, "$prefix$name/", depth + 1) }
        }
        visit(DocumentsContract.getTreeDocumentId(tree), "", 0)
        return out
    }

    /** The picked folder's own name. */
    fun treeName(
        context: Context,
        tree: Uri,
    ): String {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return describe(context, doc)?.name ?: DocumentsContract.getTreeDocumentId(tree).substringAfterLast(':').substringAfterLast('/')
    }

    /** Dropbox's content hash of a file on the phone, read once and remembered. */
    fun hash(
        context: Context,
        e: LocalEntry,
    ): String {
        val key = "h:${e.uri}|${e.size}|${e.modified}"
        prefs(context).getString(key, null)?.let { return it }
        val h = DropboxClient.contentHash(context.contentResolver.openInputStream(e.uri) ?: error("Could not read ${e.name}"))
        prefs(context).edit { putString(key, h) }
        return h
    }
}
