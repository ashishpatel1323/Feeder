package com.nononsenseapps.feeder.model.youtube

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Immutable
import com.nononsenseapps.feeder.sync2.DeviceStamp
import com.nononsenseapps.feeder.sync2.SyncNudge
import com.nononsenseapps.feeder.sync2.ThisDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import org.kodein.di.instanceOrNull
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Where a YouTube video was left: [t] seconds watched of [d] (0 = length not known), saved at [ts]
 * by [by] (empty when not known).
 */
@Immutable
data class VideoPos(
    val t: Int,
    val d: Int,
    val ts: Long,
    val by: DeviceStamp = DeviceStamp.NONE,
)

/**
 * Where each YouTube video was left, per article, so the reader's player resumes there.
 * Keyed like the sync records ("feedUrl\nguid") and synced with the web app as kind "video"
 * (newest wins). Same rules as web/src/lib/videopos.ts.
 */
class VideoPositions(
    override val di: DI,
) : DIAware {
    private val app: Application by instance()
    private val prefs by lazy { app.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    // Sends where a video is to other devices while it plays, not only when it stops
    private val syncNudge: SyncNudge? by instanceOrNull()

    // History follows where videos were left
    private val history: com.nononsenseapps.feeder.model.history.CentralHistoryRecorder? by instanceOrNull()

    private val _all = MutableStateFlow<Map<String, VideoPos>>(emptyMap())
    private var loaded = false

    /** Every saved position, by article key. */
    val all: StateFlow<Map<String, VideoPos>>
        get() {
            ensureLoaded()
            return _all.asStateFlow()
        }

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        _all.value =
            prefs.all.mapNotNull { (k, v) -> (v as? String)?.let(::decode)?.let { k to it } }.toMap()
    }

    fun get(key: String): VideoPos? = all.value[key]

    /** Saves where the video is now. Skips tiny moves and the first few seconds of a fresh start. */
    @Synchronized
    fun save(
        key: String,
        t: Double,
        d: Double,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (key.isBlank() || !t.isFinite() || t < 0) return false
        ensureLoaded()
        val old = _all.value[key]
        val next = nextPos(old, t, d, now)?.copy(by = ThisDevice.get(app).stamp()) ?: return false
        put(key, next)
        syncNudge?.changed(positionOnly = true)
        history?.keyProgress(key, next.t.toLong(), next.d.toLong())
        return true
    }

    /** Applies a position from another device when it is newer than the one here. */
    @Synchronized
    fun applyRemote(
        key: String,
        t: Double,
        d: Double,
        ts: Long,
        by: DeviceStamp = DeviceStamp.NONE,
    ) {
        if (key.isBlank() || !t.isFinite() || t < 0) return
        ensureLoaded()
        val local = _all.value[key]
        if (local != null && local.ts > ts) return
        put(key, VideoPos(t.roundToInt(), if (d.isFinite() && d > 0) d.roundToInt() else 0, ts, by))
    }

    private fun put(
        key: String,
        pos: VideoPos,
    ) {
        _all.value = _all.value + (key to pos)
        prefs.edit().putString(key, encode(pos)).apply()
    }

    companion object {
        private const val PREFS = "youtube_video_positions"

        /** Resume this many seconds before where the video was left. */
        const val RESUME_BACK = 3

        /** The last this many seconds count as watched: the next play starts over. */
        const val WATCHED_TAIL = 30

        /** Positions this early are not worth keeping. */
        const val MIN_SAVE = 5

        fun isWatched(p: VideoPos): Boolean = p.d > 0 && p.t >= p.d - WATCHED_TAIL

        /** Where the player should start, in whole seconds (0 = from the beginning). */
        fun resumeAt(p: VideoPos?): Int {
            if (p == null || isWatched(p) || p.t < MIN_SAVE) return 0
            return max(0, p.t - RESUME_BACK)
        }

        /** The position to store after seeing [t] of [d], or null when nothing changed. */
        fun nextPos(
            old: VideoPos?,
            t: Double,
            d: Double,
            now: Long,
        ): VideoPos? {
            val time = t.roundToInt()
            val dur = if (d.isFinite() && d > 0) d.roundToInt() else 0
            if (old == null && time < MIN_SAVE) return null
            if (old != null && old.t == time && (old.d == dur || dur == 0)) return null
            return VideoPos(time, if (dur > 0) dur else old?.d ?: 0, max(now, (old?.ts ?: 0) + 1))
        }

        /** "4:12" or "1:02:05". */
        fun formatClock(sec: Int): String {
            val s = max(0, sec)
            val h = s / 3600
            val m = (s % 3600) / 60
            val ss = "%02d".format(s % 60)
            return if (h > 0) "$h:${"%02d".format(m)}:$ss" else "$m:$ss"
        }

        /** "4:12 / 14:19", "4:12" without a length, or [watched]. */
        fun positionLabel(
            p: VideoPos,
            watched: String,
        ): String =
            when {
                isWatched(p) -> watched
                p.d > 0 -> "${formatClock(p.t)} / ${formatClock(p.d)}"
                else -> formatClock(p.t)
            }

        // "t|d|ts", plus "|id|name" (URL-encoded) when the device is known
        internal fun encode(p: VideoPos): String =
            if (p.by.known) {
                "${p.t}|${p.d}|${p.ts}|${URLEncoder.encode(p.by.id, "UTF-8")}|${URLEncoder.encode(p.by.name, "UTF-8")}"
            } else {
                "${p.t}|${p.d}|${p.ts}"
            }

        internal fun decode(s: String): VideoPos? {
            val parts = s.split('|')
            if (parts.size != 3 && parts.size != 5) return null
            return VideoPos(
                t = parts[0].toIntOrNull() ?: return null,
                d = parts[1].toIntOrNull() ?: 0,
                ts = parts[2].toLongOrNull() ?: 0,
                by =
                    if (parts.size == 5) {
                        runCatching { DeviceStamp(URLDecoder.decode(parts[3], "UTF-8"), URLDecoder.decode(parts[4], "UTF-8")) }.getOrDefault(DeviceStamp.NONE)
                    } else {
                        DeviceStamp.NONE
                    },
            )
        }

        /** The article key used by sync and by these positions. */
        fun articleKey(
            feedUrl: String?,
            guid: String?,
        ): String = if (feedUrl.isNullOrBlank() || guid.isNullOrBlank()) "" else "$feedUrl\n$guid"
    }
}
