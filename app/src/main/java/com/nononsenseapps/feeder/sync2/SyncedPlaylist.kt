package com.nononsenseapps.feeder.sync2

import com.nononsenseapps.feeder.model.playlists.ItemKind
import com.nononsenseapps.feeder.model.playlists.PlayStatus
import com.nononsenseapps.feeder.model.playlists.Playlist
import com.nononsenseapps.feeder.model.playlists.PlaylistSort
import com.nononsenseapps.feeder.model.playlists.STARTER_IDS
import com.nononsenseapps.feeder.model.playlists.SmartRules

/**
 * A playlist as synced ("playlist" records), the same fields in the same order as the web app
 * (web/src/lib/sync2.ts, PlaylistValue): articles by "feedUrl \n guid", feeds by URL, colours as
 * "#rrggbb", and kinds, statuses and sort in the web app's lower case words.
 */
data class SyncedPlaylist(
    val name: String,
    val icon: String,
    val color: String,
    val smart: Boolean,
    val folders: List<String>,
    val feeds: List<String>,
    val kinds: List<String>,
    val statuses: List<String>,
    val savedOnly: Boolean,
    val downloadedOnly: Boolean,
    val withinDays: Int?,
    val minMinutes: Int?,
    val maxMinutes: Int?,
    val items: List<String>,
    val sort: String,
    val createdAt: Long,
) {
    fun toMap(): Map<String, Any?> =
        linkedMapOf(
            "name" to name,
            "icon" to icon,
            "color" to color,
            "smart" to smart,
            "rules" to
                linkedMapOf(
                    "folders" to folders,
                    "feeds" to feeds,
                    "kinds" to kinds,
                    "statuses" to statuses,
                    "savedOnly" to savedOnly,
                    "downloadedOnly" to downloadedOnly,
                    "withinDays" to withinDays,
                    "minMinutes" to minMinutes,
                    "maxMinutes" to maxMinutes,
                ),
            "items" to items,
            "sort" to sort,
            "createdAt" to createdAt,
        )

    /**
     * This playlist as stored here. [feedIdOf] and [itemIdOf] find this device's ids; feeds and
     * articles this device doesn't have are left out (and kept in the synced record).
     */
    fun toPlaylist(
        id: String,
        modifiedAt: Long,
        feedIdOf: (String) -> Long?,
        itemIdOf: (String) -> Long?,
    ): Playlist =
        Playlist(
            id = id,
            name = name,
            icon = icon,
            color = parseColor(color),
            smart = smart,
            rules =
                SmartRules(
                    folders = folders,
                    feedIds = feeds.mapNotNull(feedIdOf),
                    kinds = kinds.mapNotNull { k -> ItemKind.entries.firstOrNull { kindWord(it) == k } },
                    statuses = statuses.mapNotNull { s -> PlayStatus.entries.firstOrNull { statusWord(it) == s } },
                    savedOnly = savedOnly,
                    downloadedOnly = downloadedOnly,
                    withinDays = withinDays,
                    minMinutes = minMinutes,
                    maxMinutes = maxMinutes,
                ),
            itemIds = if (smart) emptyList() else items.mapNotNull(itemIdOf).distinct(),
            sort = PlaylistSort.entries.firstOrNull { it.name.lowercase() == sort } ?: PlaylistSort.NEWEST,
            createdAt = createdAt,
            modifiedAt = modifiedAt,
        )

    companion object {
        /** Items of a manual playlist that fit in one record (the server takes up to 64 KB). */
        const val MAX_ITEMS = 300

        /**
         * [p] as synced. [feedUrlOf] and [itemKeyOf] turn this device's ids into URLs and keys.
         * [unresolvedFeeds] and [unresolvedItems] are what the last synced version had that this
         * device doesn't (feeds not subscribed here, articles not fetched here): kept in place, so
         * they aren't removed on every other device.
         */
        fun of(
            p: Playlist,
            feedUrlOf: (Long) -> String?,
            itemKeyOf: (Long) -> String?,
            unresolvedFeeds: List<String> = emptyList(),
            unresolvedItems: List<IndexedValue<String>> = emptyList(),
        ): SyncedPlaylist {
            val items =
                if (p.smart) {
                    emptyList()
                } else {
                    val out = p.itemIds.mapNotNull(itemKeyOf).toMutableList()
                    for ((index, key) in unresolvedItems) if (key !in out) out.add(index.coerceAtMost(out.size), key)
                    out.take(MAX_ITEMS)
                }
            return SyncedPlaylist(
                name = p.name,
                icon = p.icon,
                color = colorString(p.color),
                smart = p.smart,
                folders = p.rules.folders,
                feeds = (p.rules.feedIds.mapNotNull(feedUrlOf) + unresolvedFeeds).distinct(),
                kinds = p.rules.kinds.map(::kindWord),
                statuses = p.rules.statuses.map(::statusWord),
                savedOnly = p.rules.savedOnly,
                downloadedOnly = p.rules.downloadedOnly,
                withinDays = p.rules.withinDays,
                minMinutes = p.rules.minMinutes,
                maxMinutes = p.rules.maxMinutes,
                items = items,
                sort = p.sort.name.lowercase(),
                createdAt = p.createdAt,
            )
        }

        /** A synced value from JSON (numbers arrive as doubles); missing fields get defaults. */
        fun fromMap(m: Map<*, *>): SyncedPlaylist {
            val r = m["rules"] as? Map<*, *> ?: emptyMap<Any, Any>()
            return SyncedPlaylist(
                name = m["name"] as? String ?: "Playlist",
                icon = m["icon"] as? String ?: "★",
                color = (m["color"] as? String)?.lowercase() ?: "#1d4ed8",
                smart = m["smart"] == true,
                folders = strings(r["folders"]),
                feeds = strings(r["feeds"]),
                kinds = strings(r["kinds"]).filter { it in KIND_WORDS },
                statuses = strings(r["statuses"]).filter { it in STATUS_WORDS },
                savedOnly = r["savedOnly"] == true,
                downloadedOnly = r["downloadedOnly"] == true,
                withinDays = (r["withinDays"] as? Number)?.toInt(),
                minMinutes = (r["minMinutes"] as? Number)?.toInt(),
                maxMinutes = (r["maxMinutes"] as? Number)?.toInt(),
                items = strings(m["items"]),
                sort = (m["sort"] as? String)?.takeIf { s -> PlaylistSort.entries.any { it.name.lowercase() == s } } ?: "newest",
                createdAt = (m["createdAt"] as? Number)?.toLong() ?: 0L,
            )
        }

        /**
         * Starter playlists made before they had fixed ids get them, unless already synced under
         * their old id, so two devices end up with one "Morning drive" between them.
         */
        fun adoptStarterIds(
            list: List<Playlist>,
            synced: Set<String>,
        ): List<Playlist> {
            val taken = list.map { it.id }.toMutableSet()
            return list.map { p ->
                val fixed = STARTER_IDS[p.name]
                if (fixed == null || !p.smart || p.id == fixed || p.id in synced || fixed in taken) {
                    p
                } else {
                    taken += fixed
                    p.copy(id = fixed)
                }
            }
        }

        fun colorString(argb: Long): String = "#%06x".format(argb and 0xFFFFFF)

        fun parseColor(s: String): Long = (s.removePrefix("#").take(6).toLongOrNull(16) ?: 0x1D4ED8) or 0xFF000000

        private val KIND_WORDS = setOf("podcast", "youtube", "article")
        private val STATUS_WORDS = setOf("unread", "inprogress", "read")

        private fun kindWord(k: ItemKind): String = k.name.lowercase()

        private fun statusWord(s: PlayStatus): String = s.name.lowercase().replace("_", "")

        private fun strings(v: Any?): List<String> = (v as? List<*>)?.filterIsInstance<String>() ?: emptyList()
    }
}
