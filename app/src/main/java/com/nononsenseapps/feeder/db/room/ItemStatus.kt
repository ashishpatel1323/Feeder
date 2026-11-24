package com.nononsenseapps.feeder.db.room

import androidx.room.ColumnInfo

/** Read and saved state of one item, for rows outside the article list (downloads). */
data class ItemStatus(
    val id: Long,
    @ColumnInfo(name = "is_read") val read: Boolean,
    val bookmarked: Boolean,
    /** Publication (sort) time in epoch millis. */
    @ColumnInfo(name = "sort_time") val sortTime: Long,
    /** feedUrl \n guid: where YouTube keeps how far the video was watched. */
    @ColumnInfo(name = "video_key") val videoKey: String = "",
)
