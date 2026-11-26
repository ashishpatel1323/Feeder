package com.nononsenseapps.feeder.db.room

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.nononsenseapps.feeder.model.ThumbnailImage
import kotlinx.coroutines.flow.Flow
import java.net.URL
import java.time.Instant
import java.time.ZonedDateTime

/**
 * What a YouTube channel feed leaves out about a video (its length, views, whether it is a Short
 * or live), looked up once from YouTube for the YouTube tab's rows and kept here.
 */
@Entity(tableName = "youtube_video_meta")
data class YouTubeVideoMeta(
    @PrimaryKey
    @ColumnInfo(name = "video_id") val videoId: String,
    /** Seconds, 0 when not known (live or upcoming). */
    @ColumnInfo(name = "duration") val duration: Int,
    @ColumnInfo(name = "views") val views: Long?,
    @ColumnInfo(name = "likes") val likes: Long?,
    @ColumnInfo(name = "is_short") val isShort: Boolean,
    @ColumnInfo(name = "is_live") val isLive: Boolean,
    /** Epoch ms of the lookup. */
    @ColumnInfo(name = "checked_at") val checkedAt: Long,
)

/** An article of a YouTube channel feed, as the YouTube tab lists it. */
data class YouTubeFeedItem(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "guid") val guid: String,
    @ColumnInfo(name = "plain_title") val title: String,
    @ColumnInfo(name = "link") val link: String?,
    @ColumnInfo(name = "image_url") val thumbnail: ThumbnailImage?,
    @ColumnInfo(name = "pub_date") val pubDate: ZonedDateTime?,
    @ColumnInfo(name = "primary_sort_time") val sortTime: Instant,
    @ColumnInfo(name = "bookmarked") val bookmarked: Boolean,
    @ColumnInfo(name = "read_time") val readTime: Instant?,
    @ColumnInfo(name = "feed_id") val feedId: Long,
    @ColumnInfo(name = "feed_url") val feedUrl: URL,
    @ColumnInfo(name = "feed_title") val feedTitle: String,
    @ColumnInfo(name = "feed_custom_title") val feedCustomTitle: String,
    @ColumnInfo(name = "feed_image_url") val feedImageUrl: URL?,
)

/** A YouTube channel or playlist feed, as the YouTube tab's channel list shows it. */
data class YouTubeChannel(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "url") val url: URL,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "custom_title") val customTitle: String,
    @ColumnInfo(name = "tag") val tag: String,
    @ColumnInfo(name = "image_url") val imageUrl: URL?,
    @ColumnInfo(name = "youtube_music") val music: Boolean,
    /** When it was added here (the first article fetched), for "Recently added". */
    @ColumnInfo(name = "first_seen") val firstSeen: Instant?,
    /** Articles in the last 30 days, for "Most active". */
    @ColumnInfo(name = "recent_count") val recentCount: Int,
    @ColumnInfo(name = "last_sync") val lastSync: Instant,
) {
    val displayTitle: String get() = customTitle.ifBlank { title }
}

private const val IS_YOUTUBE_FEED = "(f.url LIKE '%://youtube.com/%' OR f.url LIKE '%.youtube.com/%')"

@Dao
interface YouTubeVideoMetaDao {
    @Query(
        """
        SELECT fi.id, fi.guid, fi.plain_title, fi.link, fi.image_url, fi.pub_date, fi.primary_sort_time,
          fi.bookmarked, fi.read_time, f.id AS feed_id, f.url AS feed_url, f.title AS feed_title,
          f.custom_title AS feed_custom_title, f.image_url AS feed_image_url
        FROM feed_items fi JOIN feeds f ON fi.feed_id = f.id
        WHERE $IS_YOUTUBE_FEED
        ORDER BY fi.primary_sort_time DESC
        LIMIT :limit
        """,
    )
    fun youtubeItems(limit: Int): Flow<List<YouTubeFeedItem>>

    @Query(
        """
        SELECT fi.id, fi.guid, fi.plain_title, fi.link, fi.image_url, fi.pub_date, fi.primary_sort_time,
          fi.bookmarked, fi.read_time, f.id AS feed_id, f.url AS feed_url, f.title AS feed_title,
          f.custom_title AS feed_custom_title, f.image_url AS feed_image_url
        FROM feed_items fi JOIN feeds f ON fi.feed_id = f.id
        WHERE fi.id = :itemId
        """,
    )
    fun item(itemId: Long): Flow<YouTubeFeedItem?>

    @Query(
        """
        SELECT f.id, f.url, f.title, f.custom_title, f.tag, f.image_url, f.youtube_music, f.last_sync,
          (SELECT MIN(fi.first_synced_time) FROM feed_items fi WHERE fi.feed_id = f.id) AS first_seen,
          (SELECT COUNT(*) FROM feed_items fi WHERE fi.feed_id = f.id AND fi.primary_sort_time > :since) AS recent_count
        FROM feeds f
        WHERE $IS_YOUTUBE_FEED
        ORDER BY f.title COLLATE NOCASE
        """,
    )
    fun youtubeChannels(since: Long): Flow<List<YouTubeChannel>>

    @Query("SELECT * FROM youtube_video_meta")
    fun all(): Flow<List<YouTubeVideoMeta>>

    @Query("SELECT video_id FROM youtube_video_meta WHERE video_id IN (:ids)")
    suspend fun known(ids: List<String>): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rows: List<YouTubeVideoMeta>)

    @Query("DELETE FROM youtube_video_meta WHERE checked_at < :before")
    suspend fun deleteOlderThan(before: Long)
}
