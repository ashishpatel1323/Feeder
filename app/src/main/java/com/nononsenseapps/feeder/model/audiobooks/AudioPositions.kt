package com.nononsenseapps.feeder.model.audiobooks

import android.content.Context
import androidx.core.content.edit
import com.nononsenseapps.feeder.sync2.DeviceStamp
import com.nononsenseapps.feeder.sync2.ThisDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Where an audiobook was left: file [i] (and its content hash [h], in case the folder was
 * re-sorted), [ms] into that file, [f] = 0–1 through the book (0 while the length is unknown).
 */
@Serializable
data class AudioPos(
    val i: Int,
    val h: String,
    val ms: Long,
    val f: Double,
    val ts: Long,
    /** Device that saved it (ThisDevice), empty when not known */
    val dv: String = "",
    val dn: String = "",
) {
    val by: DeviceStamp get() = if (dv.isNotEmpty() && dn.isNotEmpty()) DeviceStamp(dv, dn) else DeviceStamp.NONE
}

/**
 * Where each audiobook was left, by book id. Synced with the web app as kind "audiopos"
 * ({ i, h, ms, f, dv, dn }, newest wins), like web/src/lib/audiobooks/library.ts saveAudioPos.
 */
class AudioPositions private constructor(
    context: Context,
) {
    private val device = ThisDevice.get(context)
    private val prefs = context.getSharedPreferences("audiobook_positions", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val _all = MutableStateFlow(load())
    val all: StateFlow<Map<String, AudioPos>> = _all.asStateFlow()

    /** Told after a position is saved here (not after one from sync). */
    @Volatile
    var onChanged: (() -> Unit)? = null

    private fun load(): Map<String, AudioPos> =
        prefs.all
            .mapNotNull { (k, v) -> (v as? String)?.let { runCatching { json.decodeFromString(AudioPos.serializer(), it) }.getOrNull() }?.let { k to it } }
            .toMap()

    fun get(id: String): AudioPos? = _all.value[id]

    @Synchronized
    fun save(
        id: String,
        i: Int,
        hash: String,
        ms: Long,
        fraction: Double,
        now: Long = System.currentTimeMillis(),
    ) {
        val old = _all.value[id]
        val f = Math.round(fraction.coerceIn(0.0, 1.0) * 10000) / 10000.0
        val at = ms.coerceAtLeast(0)
        // Unchanged within a second: nothing to save or sync
        if (old != null && old.i == i && old.h == hash && kotlin.math.abs(old.ms - at) < 1000) return
        val me = device.stamp()
        put(id, AudioPos(i, hash, at, f, maxOf(now, (old?.ts ?: 0) + 1), me.id, me.name))
        onChanged?.invoke()
    }

    /** A position from another device, kept when it is newer than the one here. */
    @Synchronized
    fun applyRemote(
        id: String,
        pos: AudioPos,
    ) {
        val local = _all.value[id]
        if (local != null && local.ts > pos.ts) return
        put(id, pos)
    }

    private fun put(
        id: String,
        pos: AudioPos,
    ) {
        _all.value = _all.value + (id to pos)
        prefs.edit { putString(id, json.encodeToString(AudioPos.serializer(), pos)) }
    }

    companion object {
        @Volatile
        private var instance: AudioPositions? = null

        fun get(context: Context): AudioPositions =
            instance ?: synchronized(this) {
                instance ?: AudioPositions(context.applicationContext).also { instance = it }
            }
    }
}
