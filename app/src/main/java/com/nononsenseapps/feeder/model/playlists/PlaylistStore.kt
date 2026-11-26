package com.nononsenseapps.feeder.model.playlists

import android.content.SharedPreferences
import androidx.core.content.edit
import com.nononsenseapps.feeder.sync2.DeviceStamp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Playlists and where each episode was left, kept on this device (like the Downloads order).
 * The first use creates three starter smart playlists.
 */
class PlaylistStore(
    private val prefs: SharedPreferences,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(Playlist.serializer())
    private val positionsSerializer = MapSerializer(Long.serializer(), ListSerializer(Long.serializer()))

    private val _playlists = MutableStateFlow(load())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    // Item id to (seconds played, length, when saved in epoch millis; 0 before positions synced)
    private var stamped: Map<Long, Triple<Long, Long, Long>> = loadPositions()
    private val _positions = MutableStateFlow(stamped.mapValues { (_, v) -> v.first to v.second })

    /** Item id to (seconds played, length). */
    val positions: StateFlow<Map<Long, Pair<Long, Long>>> = _positions.asStateFlow()

    /**
     * Told after every change: true when only a playing position moved (saved every few seconds
     * while playing), false for playlist edits. Sync uses it to send changes to other devices.
     */
    @Volatile
    var onChanged: ((positionOnly: Boolean) -> Unit)? = null

    // Items whose position came from another device after this device last saved one
    private val fromOtherDevice = mutableSetOf<Long>()

    private val devicesSerializer = MapSerializer(Long.serializer(), ListSerializer(String.serializer()))
    private val devicesState = MutableStateFlow(loadDevices())

    /** Item id to the other device that saved its position last (gone once played here). */
    val positionDevices: StateFlow<Map<Long, DeviceStamp>> = devicesState.asStateFlow()

    /** When each position was saved (epoch millis), for "2h ago". */
    fun positionTime(itemId: Long): Long = stamped[itemId]?.third ?: 0L

    private fun loadDevices(): Map<Long, DeviceStamp> =
        runCatching {
            json
                .decodeFromString(devicesSerializer, prefs.getString(PREF_POSITION_DEVICES, null) ?: "{}")
                .mapNotNull { (k, v) -> if (v.size == 2) k to DeviceStamp(v[0], v[1]) else null }
                .toMap()
        }.getOrDefault(emptyMap())

    private fun putDevice(
        itemId: Long,
        stamp: DeviceStamp?,
    ) {
        val old = devicesState.value
        val next = if (stamp == null || !stamp.known) old - itemId else old + (itemId to stamp)
        if (next == old) return
        // Only for items that still have a position
        val kept = next.filterKeys { it in stamped }
        devicesState.value = kept
        prefs.edit { putString(PREF_POSITION_DEVICES, json.encodeToString(devicesSerializer, kept.mapValues { (_, v) -> listOf(v.id, v.name) })) }
    }

    private fun load(): List<Playlist> {
        val raw = prefs.getString(PREF_PLAYLISTS, null)
        if (raw == null && !prefs.getBoolean(PREF_SEEDED, false)) {
            val starters = starterPlaylists()
            prefs.edit {
                putString(PREF_PLAYLISTS, json.encodeToString(listSerializer, starters))
                putBoolean(PREF_SEEDED, true)
            }
            return starters
        }
        return runCatching { json.decodeFromString(listSerializer, raw.orEmpty()) }.getOrElse {
            // Kept, not overwritten by the next save; sync puts synced playlists back
            if (raw != null && prefs.getString(PREF_PLAYLISTS_UNREADABLE, null) == null) prefs.edit { putString(PREF_PLAYLISTS_UNREADABLE, raw) }
            emptyList()
        }
    }

    private val deletedSerializer = MapSerializer(String.serializer(), Long.serializer())

    // Playlist id to when it was deleted on this device, until sync has sent the delete
    private var deleted: Map<String, Long> =
        runCatching { json.decodeFromString(deletedSerializer, prefs.getString(PREF_DELETED, null) ?: "{}") }.getOrDefault(emptyMap())

    /**
     * Playlists deleted on this device (id to when) that sync hasn't sent yet. Only these go out
     * as deletes: a playlist that is only missing here is never deleted on the other devices.
     */
    @Synchronized
    fun deletedHere(): Map<String, Long> = deleted

    /** Sync sent (or no longer needs) the delete of these playlists. */
    @Synchronized
    fun forgetDeleted(ids: Collection<String>) {
        if (ids.none { it in deleted }) return
        putDeleted(deleted - ids.toSet())
    }

    private fun putDeleted(next: Map<String, Long>) {
        deleted = next
        prefs.edit { putString(PREF_DELETED, json.encodeToString(deletedSerializer, next)) }
    }

    private fun loadPositions(): Map<Long, Triple<Long, Long, Long>> =
        runCatching {
            json
                .decodeFromString(positionsSerializer, prefs.getString(PREF_POSITIONS, null) ?: "{}")
                .mapValues { (_, v) -> Triple(v.getOrNull(0) ?: 0L, v.getOrNull(1) ?: 0L, v.getOrNull(2) ?: 0L) }
        }.getOrDefault(emptyMap())

    /** Changes the playlists; the ones [fn] changed are stamped with the time, which sync goes by. */
    @Synchronized
    fun change(fn: (List<Playlist>) -> List<Playlist>) {
        val before = _playlists.value
        val now = System.currentTimeMillis()
        val next = stampChanged(before, fn(before), now)
        // Removed by the person here: remembered, so sync deletes them on the other devices too
        val nextIds = next.map { it.id }.toSet()
        val removed = before.map { it.id }.filterNot { it in nextIds }
        if (removed.isNotEmpty() || nextIds.any { it in deleted }) {
            putDeleted(deleted.filterKeys { it !in nextIds } + removed.associateWith { now })
        }
        write(next)
        onChanged?.invoke(false)
    }

    /** Playlists as another device left them (sync): stored as they are, not stamped or sent back. */
    @Synchronized
    fun replaceFromSync(fn: (List<Playlist>) -> List<Playlist>) {
        val next = fn(_playlists.value)
        // A newer version from another device brings back a playlist deleted here
        val ids = next.map { it.id }.toSet()
        if (ids.any { it in deleted }) putDeleted(deleted.filterKeys { it !in ids })
        write(next)
    }

    private fun write(next: List<Playlist>) {
        _playlists.value = next
        prefs.edit {
            putString(PREF_PLAYLISTS, json.encodeToString(listSerializer, next))
            putBoolean(PREF_SEEDED, true)
        }
    }

    /** Speed for episodes and videos (articles use the read-aloud speed). */
    var audioSpeed: Float
        get() = prefs.getFloat(PREF_AUDIO_SPEED, 1f).coerceIn(0.5f, 4f)
        set(value) = prefs.edit { putFloat(PREF_AUDIO_SPEED, value) }

    fun get(id: String): Playlist? = _playlists.value.firstOrNull { it.id == id }

    fun save(playlist: Playlist) = change { list -> if (list.any { it.id == playlist.id }) list.map { if (it.id == playlist.id) playlist else it } else list + playlist }

    fun delete(id: String) = change { list -> list.filterNot { it.id == id } }

    fun restore(list: List<Playlist>) = change { list }

    fun add(
        playlistId: String,
        ids: List<Long>,
    ) = change { list -> list.map { if (it.id == playlistId && !it.smart) it.withItems(ids) else it } }

    fun remove(
        playlistId: String,
        ids: List<Long>,
    ) = change { list -> list.map { if (it.id == playlistId) it.withoutItems(ids) else it } }

    fun move(
        playlistId: String,
        itemId: Long,
        to: Int,
    ) = change { list -> list.map { if (it.id == playlistId) it.moveItem(itemId, to) else it } }

    /** Played to the end: out of every manual playlist. Returns the playlists it was in. */
    fun removeFromAllManual(itemId: Long): List<String> {
        val from = _playlists.value.filter { !it.smart && itemId in it.itemIds }.map { it.id }
        if (from.isNotEmpty()) change { list -> list.map { if (it.id in from) it.withoutItems(listOf(itemId)) else it } }
        return from
    }

    @Synchronized
    fun savePosition(
        itemId: Long,
        seconds: Long,
        length: Long,
    ) {
        val old = stamped[itemId]
        if (old == null && seconds < 5) return
        val len = if (length > 0) length else old?.second ?: 0L
        if (old != null && old.first == seconds && old.second == len) return
        fromOtherDevice -= itemId
        putPosition(itemId, Triple(seconds, len, maxOf(System.currentTimeMillis(), (old?.third ?: 0L) + 1)))
        putDevice(itemId, null)
        onChanged?.invoke(true)
    }

    /** Where another device left [itemId], saved at [ts]: kept unless this device's is newer. */
    @Synchronized
    fun applyRemotePosition(
        itemId: Long,
        seconds: Long,
        length: Long,
        ts: Long,
        by: DeviceStamp = DeviceStamp.NONE,
        thisDeviceId: String = "",
    ) {
        val old = stamped[itemId]
        if (old != null && old.third >= ts) return
        if (old != null && old.first == seconds) return
        fromOtherDevice += itemId
        putPosition(itemId, Triple(seconds, if (length > 0) length else old?.second ?: 0L, ts))
        putDevice(itemId, by.takeIf { it.id != thisDeviceId })
    }

    /**
     * Where to carry on with [itemId] when it was paused here and then played further on another
     * device: the resume point (a few seconds back), once. Null when nothing changed elsewhere.
     */
    @Synchronized
    fun takeOtherDevicePosition(itemId: Long): Long? {
        if (!fromOtherDevice.remove(itemId)) return null
        return resumeAt(itemId)
    }

    /** Every position with when it was saved, for sync. */
    fun stampedPositions(): Map<Long, Triple<Long, Long, Long>> = stamped

    private fun putPosition(
        itemId: Long,
        value: Triple<Long, Long, Long>,
    ) {
        val next =
            ((stamped - itemId) + (itemId to value))
                .entries
                .toList()
                .takeLast(MAX_POSITIONS)
                .associate { it.key to it.value }
        stamped = next
        _positions.value = next.mapValues { (_, v) -> v.first to v.second }
        prefs.edit { putString(PREF_POSITIONS, json.encodeToString(positionsSerializer, next.mapValues { (_, v) -> listOf(v.first, v.second, v.third) })) }
    }

    /** Where to start [itemId]: a few seconds before where it was left, or 0. */
    fun resumeAt(itemId: Long): Long {
        val (t, d) = _positions.value[itemId] ?: return 0L
        if (t < 5 || (d > 0 && t >= d - PLAYED_TAIL_SECONDS)) return 0L
        return (t - 3).coerceAtLeast(0L)
    }

    companion object {
        private const val PREF_PLAYLISTS = "playlists_json"
        private const val PREF_SEEDED = "playlists_seeded"
        private const val PREF_DELETED = "playlists_deleted_here"
        private const val PREF_PLAYLISTS_UNREADABLE = "playlists_json_unreadable"
        private const val PREF_POSITIONS = "playlists_positions"
        private const val PREF_POSITION_DEVICES = "playlists_position_devices"
        private const val PREF_AUDIO_SPEED = "playlists_audio_speed"
        private const val MAX_POSITIONS = 500
    }
}

/** [next] with the playlists that differ from [before] stamped with [now]. */
internal fun stampChanged(
    before: List<Playlist>,
    next: List<Playlist>,
    now: Long,
): List<Playlist> {
    val old = before.associate { it.id to it.copy(modifiedAt = 0L) }
    return next.map { if (old[it.id] == it.copy(modifiedAt = 0L)) it else it.copy(modifiedAt = now) }
}
