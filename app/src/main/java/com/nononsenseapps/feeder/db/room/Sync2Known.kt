package com.nononsenseapps.feeder.db.room

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The last value exchanged for one record of Feeder's own sync (see sync2/Sync2Client.kt).
 * k is kind + "\n" + key, v is the value as JSON, ts the change time in epoch millis.
 */
@Entity(tableName = "sync2_known")
data class Sync2Known(
    @PrimaryKey @ColumnInfo(name = "k") val k: String,
    @ColumnInfo(name = "v") val v: String,
    @ColumnInfo(name = "ts") val ts: Long,
)

/** An article deleted for good: never shown again, even while its feed still lists it. */
@Entity(tableName = "hidden_items", primaryKeys = ["feed_url", "guid"])
data class HiddenItem(
    @ColumnInfo(name = "feed_url") val feedUrl: String,
    @ColumnInfo(name = "guid") val guid: String,
    @ColumnInfo(name = "ts") val ts: Long,
)

/** One article whose read or saved state differs from what was last exchanged. */
data class Sync2ItemDiff(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "feed_url") val feedUrl: String,
    @ColumnInfo(name = "guid") val guid: String,
    @ColumnInfo(name = "read_time") val readTime: Long?,
    @ColumnInfo(name = "bookmarked") val bookmarked: Boolean,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "link") val link: String?,
    @ColumnInfo(name = "author") val author: String?,
    @ColumnInfo(name = "snippet") val snippet: String,
    @ColumnInfo(name = "sort_time") val sortTime: Long,
    @ColumnInfo(name = "read_v") val readV: String?,
    @ColumnInfo(name = "read_ts") val readTs: Long?,
    @ColumnInfo(name = "saved_v") val savedV: String?,
    @ColumnInfo(name = "saved_ts") val savedTs: Long?,
)

data class Sync2ItemKey(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "feed_url") val feedUrl: String,
    @ColumnInfo(name = "guid") val guid: String,
)
