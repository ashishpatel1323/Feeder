package com.nononsenseapps.feeder.db.room

import androidx.room.ColumnInfo

/** One feed on the Home screen: its picture, folder, unread count and what its articles carry. */
data class HomeFeedRow(
    val id: Long,
    @ColumnInfo(name = "display_title") val displayTitle: String,
    val url: String,
    val tag: String,
    @ColumnInfo(name = "image_url") val imageUrl: String?,
    val unread: Int,
    /** Newest article time (epoch millis), 0 when the feed has none. */
    val latest: Long,
    val total: Int,
    /** Articles with an audio attachment. */
    @ColumnInfo(name = "audio_items") val audioItems: Int,
    /** Articles that link to a YouTube video. */
    @ColumnInfo(name = "video_items") val videoItems: Int,
    /** Saved (bookmarked) articles. */
    val saved: Int = 0,
)

/** Which feed an article belongs to. */
data class ItemFeedId(
    val id: Long,
    @ColumnInfo(name = "feed_id") val feedId: Long,
)
