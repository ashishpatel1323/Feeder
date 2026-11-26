package com.nononsenseapps.feeder.model.youtube

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Logs how long each step from opening a video to its first frame takes (tag FEEDER_YT_TIMING):
 * `adb logcat -s FEEDER_YT_TIMING`.
 */
object YouTubeTiming {
    private const val LOG_TAG = "FEEDER_YT_TIMING"
    private val started = ConcurrentHashMap<String, Long>()

    /** A video was opened (its page or the reader): later steps count from here. */
    fun start(videoId: String) {
        started[videoId] = System.currentTimeMillis()
        Log.i(LOG_TAG, "$videoId open")
    }

    fun log(
        videoId: String,
        step: String,
    ) {
        val t0 = started[videoId]
        val since = if (t0 != null) "+${System.currentTimeMillis() - t0} ms" else "(not opened)"
        Log.i(LOG_TAG, "$videoId $since $step")
    }
}
