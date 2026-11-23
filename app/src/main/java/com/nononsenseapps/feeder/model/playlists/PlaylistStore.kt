package com.nononsenseapps.feeder.model.playlists

import android.content.SharedPreferences
import androidx.core.content.edit
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

    private val _positions = MutableStateFlow(loadPositions())

    /** Item id to (seconds played, length). */
    val positions: StateFlow<Map<Long, Pair<Long, Long>>> = _positions.asStateFlow()

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
        return runCatching { json.decodeFromString(listSerializer, raw.orEmpty()) }.getOrDefault(emptyList())
    }

    private fun loadPositions(): Map<Long, Pair<Long, Long>> =
        runCatching {
            json
                .decodeFromString(positionsSerializer, prefs.getString(PREF_POSITIONS, null) ?: "{}")
                .mapValues { (_, v) -> (v.getOrNull(0) ?: 0L) to (v.getOrNull(1) ?: 0L) }
        }.getOrDefault(emptyMap())

    @Synchronized
    fun change(fn: (List<Playlist>) -> List<Playlist>) {
        val next = fn(_playlists.value)
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
        val old = _positions.value[itemId]
        if (old == null && seconds < 5) return
        val next =
            (_positions.value + (itemId to (seconds to (if (length > 0) length else old?.second ?: 0L))))
                .entries
                .toList()
                .takeLast(MAX_POSITIONS)
                .associate { it.key to it.value }
        _positions.value = next
        prefs.edit { putString(PREF_POSITIONS, json.encodeToString(positionsSerializer, next.mapValues { (_, v) -> listOf(v.first, v.second) })) }
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
        private const val PREF_POSITIONS = "playlists_positions"
        private const val PREF_AUDIO_SPEED = "playlists_audio_speed"
        private const val MAX_POSITIONS = 500
    }
}
