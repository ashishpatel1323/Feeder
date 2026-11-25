package com.nononsenseapps.feeder.model.ebooks

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where an ebook was left: [cfi] of the page start, [f] = 0–1 through the book, saved at [ts]. */
@Serializable
data class BookPos(
    val cfi: String,
    val f: Double,
    val ts: Long,
)

/**
 * Where each ebook was left, by book id. Synced with the web app as kind "bookpos"
 * ({ cfi, f }, newest wins), like web/src/lib/ebooks/library.ts savePosition.
 */
class BookPositions private constructor(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("ebook_positions", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val _all = MutableStateFlow(load())
    val all: StateFlow<Map<String, BookPos>> = _all.asStateFlow()

    /** Told after a position is saved here (not after one from sync). */
    @Volatile
    var onChanged: (() -> Unit)? = null

    private fun load(): Map<String, BookPos> =
        prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { runCatching { json.decodeFromString(BookPos.serializer(), it) }.getOrNull() }?.let { k to it } }.toMap()

    fun get(id: String): BookPos? = _all.value[id]

    @Synchronized
    fun save(
        id: String,
        cfi: String,
        fraction: Double,
        now: Long = System.currentTimeMillis(),
    ) {
        if (cfi.isBlank()) return
        val f = Math.round(fraction.coerceIn(0.0, 1.0) * 10000) / 10000.0
        val old = _all.value[id]
        if (old != null && old.cfi == cfi) return
        put(id, BookPos(cfi, f, maxOf(now, (old?.ts ?: 0) + 1)))
        onChanged?.invoke()
    }

    /** A position from another device, kept when it is newer than the one here. */
    @Synchronized
    fun applyRemote(
        id: String,
        cfi: String,
        f: Double,
        ts: Long,
    ) {
        if (cfi.isBlank()) return
        val local = _all.value[id]
        if (local != null && local.ts > ts) return
        put(id, BookPos(cfi, if (f.isFinite()) f else 0.0, ts))
    }

    private fun put(
        id: String,
        pos: BookPos,
    ) {
        _all.value = _all.value + (id to pos)
        prefs.edit { putString(id, json.encodeToString(BookPos.serializer(), pos)) }
    }

    companion object {
        @Volatile
        private var instance: BookPositions? = null

        fun get(context: Context): BookPositions =
            instance ?: synchronized(this) {
                instance ?: BookPositions(context.applicationContext).also { instance = it }
            }
    }
}
