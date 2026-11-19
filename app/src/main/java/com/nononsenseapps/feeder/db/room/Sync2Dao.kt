package com.nononsenseapps.feeder.db.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface Sync2Dao {
    @Query("SELECT * FROM sync2_known WHERE k = :k")
    suspend fun getKnown(k: String): Sync2Known?

    /** Every record whose key starts with [prefix] (no LIKE: URLs contain % and _). */
    @Query("SELECT * FROM sync2_known WHERE substr(k, 1, length(:prefix)) = :prefix")
    suspend fun getKnownWithPrefix(prefix: String): List<Sync2Known>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertKnown(rows: List<Sync2Known>)

    @Query("DELETE FROM sync2_known")
    suspend fun deleteAllKnown()

    @Query("SELECT * FROM hidden_items")
    suspend fun getHidden(): List<HiddenItem>

    @Query("SELECT guid FROM hidden_items WHERE feed_url = :feedUrl")
    suspend fun getHiddenGuids(feedUrl: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHidden(rows: List<HiddenItem>)

    @Query("DELETE FROM hidden_items WHERE feed_url = :feedUrl AND guid = :guid")
    suspend fun deleteHidden(
        feedUrl: String,
        guid: String,
    )

    /** Guids in a feed that another device saved before this one fetched them. */
    @Query(
        """
        SELECT substr(k, length(:prefix) + 1) FROM sync2_known
        WHERE substr(k, 1, length(:prefix)) = :prefix AND v = 'true'
        """,
    )
    suspend fun getKnownTrueGuids(prefix: String): List<String>

    /** Articles whose read or saved state differs from the last exchanged value. */
    @Query(
        """
        SELECT i.id AS id, f.url AS feed_url, i.guid AS guid, i.read_time AS read_time,
          i.bookmarked AS bookmarked, i.plain_title AS title, i.link AS link, i.author AS author,
          substr(i.plain_snippet, 1, 300) AS snippet, i.primary_sort_time AS sort_time,
          kr.v AS read_v, kr.ts AS read_ts, ks.v AS saved_v, ks.ts AS saved_ts
        FROM feed_items i
        JOIN feeds f ON i.feed_id = f.id
        LEFT JOIN sync2_known kr ON kr.k = 'read' || char(10) || f.url || char(10) || i.guid
        LEFT JOIN sync2_known ks ON ks.k = 'saved' || char(10) || f.url || char(10) || i.guid
        WHERE (i.read_time IS NOT NULL) != (COALESCE(kr.v, 'false') = 'true')
           OR i.bookmarked != (COALESCE(ks.v, 'false') = 'true')
        """,
    )
    suspend fun getItemDiffs(): List<Sync2ItemDiff>

    @Query(
        """
        SELECT i.id FROM feed_items i JOIN feeds f ON i.feed_id = f.id
        WHERE f.url = :feedUrl AND i.guid = :guid
        """,
    )
    suspend fun getItemId(
        feedUrl: String,
        guid: String,
    ): Long?

    @Query(
        """
        SELECT i.id AS id, f.url AS feed_url, i.guid AS guid FROM feed_items i JOIN feeds f ON i.feed_id = f.id
        WHERE i.id IN (:ids)
        """,
    )
    suspend fun getItemKeys(ids: List<Long>): List<Sync2ItemKey>

    @Query("UPDATE feed_items SET bookmarked = :bookmarked WHERE id = :id")
    suspend fun setBookmarked(
        id: Long,
        bookmarked: Boolean,
    )

    @Query("UPDATE feed_items SET read_time = :readTime, notified = 1 WHERE id = :id")
    suspend fun setReadTime(
        id: Long,
        readTime: Long?,
    )

    @Query("DELETE FROM feed_items WHERE id = :id")
    suspend fun deleteItem(id: Long)

    @Query("SELECT * FROM feed_items WHERE id IN (:ids)")
    suspend fun getFeedItems(ids: List<Long>): List<FeedItem>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFeedItems(items: List<FeedItem>)

    /** Undo for [hide]: the articles come back here, and the next sync brings them back elsewhere. */
    @Transaction
    suspend fun unhide(
        keys: List<Sync2ItemKey>,
        items: List<FeedItem>,
    ) {
        for (k in keys) deleteHidden(k.feedUrl, k.guid)
        insertFeedItems(items)
    }

    @Transaction
    suspend fun hide(
        rows: List<HiddenItem>,
        itemIds: List<Long>,
    ) {
        insertHidden(rows)
        for (id in itemIds) deleteItem(id)
    }
}
