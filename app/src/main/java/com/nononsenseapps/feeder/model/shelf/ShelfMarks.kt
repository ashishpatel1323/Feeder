package com.nononsenseapps.feeder.model.shelf

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where a book is on its shelf: the three groups of Books (as in the Voice app). */
enum class BookStatus(
    val code: String,
) {
    CURRENT("current"),
    NEW("new"),
    DONE("done"),
    ;

    companion object {
        fun of(code: String?): BookStatus? = entries.firstOrNull { it.code == code }
    }
}

/** A status picked by hand, at [ts]. */
@Serializable
data class PickedStatus(
    val s: String,
    val ts: Long,
)

/** A cover picked from the Internet ([url]), at [ts]. */
@Serializable
data class PickedCover(
    val url: String,
    val ts: Long,
)

/** How a book's status follows from what was done with it. Same rules as web/src/lib/shelfMarks.ts. */
object ShelfStatusRules {
    /** This far through counts as finished: credits, an index or an epilogue often go unread */
    const val DONE_AT = 0.98

    /** From progress alone: never opened = not started, near the end = completed, else current. */
    fun auto(
        fraction: Double?,
        opened: Boolean,
        canFinish: Boolean = true,
    ): BookStatus =
        when {
            canFinish && fraction != null && fraction >= DONE_AT -> BookStatus.DONE
            opened || (fraction != null && fraction > 0) -> BookStatus.CURRENT
            else -> BookStatus.NEW
        }

    /** A status picked by hand holds until the book is read or played after it was picked. */
    fun effective(
        auto: BookStatus,
        picked: PickedStatus?,
        lastActivity: Long,
    ): BookStatus {
        val mine = picked?.let { BookStatus.of(it.s) } ?: return auto
        return if (lastActivity > picked.ts) auto else mine
    }

    /** Key of a book in [ShelfMarks]: "ebook:<id>", "audiobook:<id>" or "zim:<id>". */
    fun key(
        kind: String,
        id: String,
    ) = "$kind:$id"

    const val EBOOK = "ebook"
    const val AUDIOBOOK = "audiobook"
    const val ZIM = "zim"
}

/**
 * Statuses and covers picked by hand for books on the Books shelves, by [ShelfStatusRules.key].
 * Synced with the web app as kinds "shelfstatus" ({ s }) and "bookcover" ({ url }), newest wins.
 */
class ShelfMarks private constructor(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("shelf_marks", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val _statuses = MutableStateFlow(load("s:", PickedStatus.serializer()))
    val statuses: StateFlow<Map<String, PickedStatus>> = _statuses.asStateFlow()
    private val _covers = MutableStateFlow(load("c:", PickedCover.serializer()))
    val covers: StateFlow<Map<String, PickedCover>> = _covers.asStateFlow()

    /** Told after a change made here (not after one from sync). */
    @Volatile
    var onChanged: (() -> Unit)? = null

    /** Told when another device picked a cover, so this one fetches it too. */
    @Volatile
    var onRemoteCover: ((key: String, url: String) -> Unit)? = null

    private fun <T> load(
        prefix: String,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): Map<String, T> =
        prefs.all
            .filterKeys { it.startsWith(prefix) }
            .mapNotNull { (k, v) -> (v as? String)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }?.let { k.removePrefix(prefix) to it } }
            .toMap()

    @Synchronized
    fun setStatus(
        key: String,
        status: BookStatus,
        now: Long = System.currentTimeMillis(),
    ) {
        val ts = maxOf(now, (_statuses.value[key]?.ts ?: 0) + 1)
        putStatus(key, PickedStatus(status.code, ts))
        onChanged?.invoke()
    }

    @Synchronized
    fun applyRemoteStatus(
        key: String,
        s: String,
        ts: Long,
    ) {
        if (BookStatus.of(s) == null) return
        val local = _statuses.value[key]
        if (local != null && local.ts >= ts) return
        putStatus(key, PickedStatus(s, ts))
    }

    @Synchronized
    fun setCover(
        key: String,
        url: String,
        now: Long = System.currentTimeMillis(),
    ) {
        val ts = maxOf(now, (_covers.value[key]?.ts ?: 0) + 1)
        putCover(key, PickedCover(url, ts))
        onChanged?.invoke()
    }

    @Synchronized
    fun applyRemoteCover(
        key: String,
        url: String,
        ts: Long,
    ) {
        if (!url.startsWith("http")) return
        val local = _covers.value[key]
        if (local != null && local.ts >= ts) return
        putCover(key, PickedCover(url, ts))
        if (local?.url != url) onRemoteCover?.invoke(key, url)
    }

    private fun putStatus(
        key: String,
        v: PickedStatus,
    ) {
        _statuses.value = _statuses.value + (key to v)
        prefs.edit { putString("s:$key", json.encodeToString(PickedStatus.serializer(), v)) }
    }

    private fun putCover(
        key: String,
        v: PickedCover,
    ) {
        _covers.value = _covers.value + (key to v)
        prefs.edit { putString("c:$key", json.encodeToString(PickedCover.serializer(), v)) }
    }

    companion object {
        @Volatile
        private var instance: ShelfMarks? = null

        fun get(context: Context): ShelfMarks =
            instance ?: synchronized(this) {
                instance ?: ShelfMarks(context.applicationContext).also { instance = it }
            }
    }
}
