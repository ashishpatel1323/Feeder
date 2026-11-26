package com.nononsenseapps.feeder.model.playlists

import android.content.SharedPreferences
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaylistStoreTest {
    private fun playlist(id: String) = Playlist(id = id, name = id, icon = "★", color = 0xFF1D4ED8, smart = false)

    @Test
    fun deletingHereIsRememberedForSyncAndUndoForgetsIt() {
        val store = PlaylistStore(MemoryPrefs())
        store.save(playlist("pl-a"))
        val before = store.playlists.value
        store.delete("pl-a")
        assertEquals(setOf("pl-a"), store.deletedHere().keys)
        store.restore(before)
        assertTrue(store.deletedHere().isEmpty())
    }

    @Test
    fun removedBySyncIsNotADeleteMadeHere() {
        val store = PlaylistStore(MemoryPrefs())
        store.save(playlist("pl-a"))
        store.replaceFromSync { list -> list.filterNot { it.id == "pl-a" } }
        assertTrue(store.deletedHere().isEmpty())
    }

    @Test
    fun deletesMadeHereSurviveARestartUntilSent() {
        val prefs = MemoryPrefs()
        PlaylistStore(prefs).apply {
            save(playlist("pl-a"))
            delete("pl-a")
        }
        val again = PlaylistStore(prefs)
        assertEquals(setOf("pl-a"), again.deletedHere().keys)
        again.forgetDeleted(listOf("pl-a"))
        assertTrue(PlaylistStore(prefs).deletedHere().isEmpty())
    }

    @Test
    fun unreadablePlaylistsAreKeptAside() {
        val prefs = MemoryPrefs()
        prefs.edit().putString("playlists_json", "not json").putBoolean("playlists_seeded", true).apply()
        val store = PlaylistStore(prefs)
        assertTrue(store.playlists.value.isEmpty())
        store.save(playlist("pl-new"))
        assertEquals("not json", prefs.getString("playlists_json_unreadable", null))
    }
}

/** SharedPreferences in memory, for tests. */
private class MemoryPrefs : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()

    override fun getAll(): Map<String, *> = values.toMap()

    override fun getString(
        key: String,
        defValue: String?,
    ): String? = values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(
        key: String,
        defValues: Set<String>?,
    ): Set<String>? = values[key] as? Set<String> ?: defValues

    override fun getInt(
        key: String,
        defValue: Int,
    ): Int = values[key] as? Int ?: defValue

    override fun getLong(
        key: String,
        defValue: Long,
    ): Long = values[key] as? Long ?: defValue

    override fun getFloat(
        key: String,
        defValue: Float,
    ): Float = values[key] as? Float ?: defValue

    override fun getBoolean(
        key: String,
        defValue: Boolean,
    ): Boolean = values[key] as? Boolean ?: defValue

    override fun contains(key: String): Boolean = key in values

    override fun edit(): SharedPreferences.Editor =
        object : SharedPreferences.Editor {
            private val changes = mutableMapOf<String, Any?>()
            private var clear = false

            override fun putString(
                key: String,
                value: String?,
            ) = apply { changes[key] = value }

            override fun putStringSet(
                key: String,
                values: Set<String>?,
            ) = apply { changes[key] = values }

            override fun putInt(
                key: String,
                value: Int,
            ) = apply { changes[key] = value }

            override fun putLong(
                key: String,
                value: Long,
            ) = apply { changes[key] = value }

            override fun putFloat(
                key: String,
                value: Float,
            ) = apply { changes[key] = value }

            override fun putBoolean(
                key: String,
                value: Boolean,
            ) = apply { changes[key] = value }

            override fun remove(key: String) = apply { changes[key] = null }

            override fun clear() = apply { clear = true }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clear) values.clear()
                for ((k, v) in changes) if (v == null) values.remove(k) else values[k] = v
            }
        }

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
}
