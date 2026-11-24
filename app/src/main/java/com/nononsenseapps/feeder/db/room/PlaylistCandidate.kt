package com.nononsenseapps.feeder.db.room

import androidx.room.ColumnInfo
import com.nononsenseapps.feeder.model.ThumbnailImage

/** One article, episode or video with what playlist rules and lists need to know about it. */
data class PlaylistCandidate(
    val id: Long,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "link") val link: String?,
    @ColumnInfo(name = "enclosure_link") val enclosureLink: String?,
    @ColumnInfo(name = "enclosure_type") val enclosureType: String?,
    /** Episode length in seconds from the feed, null when not given. */
    @ColumnInfo(name = "enclosure_duration") val enclosureDuration: Long?,
    @ColumnInfo(name = "is_read") val read: Boolean,
    val bookmarked: Boolean,
    /** Saved for offline reading. */
    @ColumnInfo(name = "is_offline") val offline: Boolean,
    /** Publication (sort) time in epoch millis. */
    @ColumnInfo(name = "sort_time") val sortTime: Long,
    @ColumnInfo(name = "word_count") val wordCount: Int,
    @ColumnInfo(name = "word_count_full") val wordCountFull: Int,
    @ColumnInfo(name = "image_url") val image: ThumbnailImage?,
    @ColumnInfo(name = "feed_id") val feedId: Long,
    @ColumnInfo(name = "feed_url") val feedUrl: String,
    @ColumnInfo(name = "feed_tag") val feedTag: String,
    @ColumnInfo(name = "feed_title") val feedTitle: String,
    @ColumnInfo(name = "feed_image") val feedImage: String?,
    @ColumnInfo(name = "full_text_by_default") val fullTextByDefault: Boolean,
    @ColumnInfo(name = "guid") val guid: String = "",
)
