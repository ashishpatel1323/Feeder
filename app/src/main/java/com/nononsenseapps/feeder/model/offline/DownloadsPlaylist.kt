package com.nononsenseapps.feeder.model.offline

import android.content.SharedPreferences
import java.io.File

/**
 * The order the Downloads screen lists and plays saved videos in, and its playback toggles.
 * Kept on this device only: each device has its own downloads.
 *
 * Videos not in the saved order yet (new downloads) go at the end, oldest download first.
 */
class DownloadsPlaylist(
    private val prefs: SharedPreferences,
) {
    var autoplay: Boolean
        get() = prefs.getBoolean(PREF_AUTOPLAY, true)
        set(value) = prefs.edit().putBoolean(PREF_AUTOPLAY, value).apply()

    var loop: Boolean
        get() = prefs.getBoolean(PREF_LOOP, false)
        set(value) = prefs.edit().putBoolean(PREF_LOOP, value).apply()

    /** Keep playing with the screen off, in other apps or after leaving Downloads. */
    var background: Boolean
        get() = prefs.getBoolean(PREF_BACKGROUND, true)
        set(value) = prefs.edit().putBoolean(PREF_BACKGROUND, value).apply()

    var removeWatched: Boolean
        get() = prefs.getBoolean(PREF_REMOVE_WATCHED, false)
        set(value) = prefs.edit().putBoolean(PREF_REMOVE_WATCHED, value).apply()

    private fun savedOrder(audio: Boolean): List<Long> =
        prefs
            .getString(if (audio) PREF_ORDER_AUDIO else PREF_ORDER, null)
            .orEmpty()
            .split(',')
            .mapNotNull { it.trim().toLongOrNull() }

    /** [ids] in playlist order. [downloadedAt] says when each was saved, for new ones. */
    fun ordered(
        ids: Collection<Long>,
        audio: Boolean = false,
        downloadedAt: (Long) -> Long,
    ): List<Long> = order(savedOrder(audio), ids, downloadedAt)

    /** Videos and podcast episodes each have their own order. */
    fun setOrder(
        ids: List<Long>,
        audio: Boolean = false,
    ) {
        prefs.edit().putString(if (audio) PREF_ORDER_AUDIO else PREF_ORDER, ids.joinToString(",")).apply()
    }

    companion object {
        private const val PREF_ORDER = "downloads_order"
        private const val PREF_ORDER_AUDIO = "downloads_order_audio"
        private const val PREF_BACKGROUND = "downloads_background"
        private const val PREF_AUTOPLAY = "downloads_autoplay"
        private const val PREF_LOOP = "downloads_loop"
        private const val PREF_REMOVE_WATCHED = "downloads_remove_watched"

        /** The saved order without ids that are gone, then the new ids, oldest first. */
        fun order(
            saved: List<Long>,
            ids: Collection<Long>,
            downloadedAt: (Long) -> Long,
        ): List<Long> {
            val present = ids.toSet()
            val kept = saved.distinct().filter { it in present }
            val keptSet = kept.toSet()
            val added = ids.filterNot { it in keptSet }.distinct().sortedBy(downloadedAt)
            return kept + added
        }

        fun downloadedAt(video: SavedVideo?): Long = video?.video?.let(File::lastModified) ?: 0L
    }
}
