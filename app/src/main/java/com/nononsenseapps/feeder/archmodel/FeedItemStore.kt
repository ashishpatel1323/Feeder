package com.nononsenseapps.feeder.archmodel

import android.app.Application
import android.content.Context
import android.text.format.DateFormat
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import androidx.sqlite.db.SimpleSQLiteQuery
import com.nononsenseapps.feeder.db.room.AppDatabase
import com.nononsenseapps.feeder.db.room.BlocklistDao
import com.nononsenseapps.feeder.db.room.FeedItem
import com.nononsenseapps.feeder.db.room.FeedItemCursor
import com.nononsenseapps.feeder.db.room.FeedItemDao
import com.nononsenseapps.feeder.db.room.FeedItemDao.Companion.FEED_ITEM_LIST_SORT_ORDER_ASC
import com.nononsenseapps.feeder.db.room.FeedItemDao.Companion.FEED_ITEM_LIST_SORT_ORDER_DESC
import com.nononsenseapps.feeder.db.room.FeedItemIdWithLink
import com.nononsenseapps.feeder.db.room.FeedItemWithFeed
import com.nononsenseapps.feeder.db.room.ID_SAVED_ARTICLES
import com.nononsenseapps.feeder.db.room.ID_UNSET
import com.nononsenseapps.feeder.db.room.upsertFeedItems
import com.nononsenseapps.feeder.model.PREVIEW_COLUMNS
import com.nononsenseapps.feeder.model.PreviewItem
import com.nononsenseapps.feeder.ui.compose.feed.FeedListItem
import com.nononsenseapps.feeder.ui.compose.feedarticle.FeedListFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Date
import java.util.TimeZone

class FeedItemStore(
    override val di: DI,
) : DIAware {
    private val dao: FeedItemDao by instance()
    private val blocklistDao: BlocklistDao by instance()
    private val appDatabase: AppDatabase by instance()
    private val settingsStore: SettingsStore by instance()
    private val application: Application by instance()

    suspend fun setBlockStatusForNewInFeed(
        feedId: Long,
        blockTime: Instant,
    ) {
        blocklistDao.setItemBlockStatusForNewInFeed(feedId, blockTime, settingsStore.applyBlocklistToSummaries.value, settingsStore.applyBlocklistToLinks.value)
    }

    fun getFeedItemCountRaw(
        feedId: Long,
        tag: String,
        minReadTime: Instant,
        filter: FeedListFilter,
        search: String,
    ): Flow<Int> {
        val queryString = StringBuilder()
        val args = mutableListOf<Any?>()

        queryString.apply {
            append("SELECT count(*) FROM feed_items\n")
            append("LEFT JOIN feeds ON feed_items.feed_id = feeds.id\n")
            append("WHERE\n")

            rawQueryFilter(filter, search, args, minReadTime, feedId, tag)
        }

        return dao.getPreviewsCount(SimpleSQLiteQuery(queryString.toString(), args.toTypedArray()))
    }

    fun getWidgetFeedListItems(
        feedId: Long,
        tag: String,
    ): Flow<List<FeedListItem>> =
        when {
            feedId == ID_SAVED_ARTICLES -> dao.widgetPreviewsSaved()
            feedId > ID_UNSET -> dao.widgetPreviewsByFeed(feedId)
            tag.isNotEmpty() -> dao.widgetPreviewsByTag(tag)
            else -> dao.widgetPreviewsAllFeeds()
        }.map { list -> list.map { it.toFeedListItem(application) } }

    fun getPagedFeedItemsRaw(
        feedId: Long,
        tag: String,
        minReadTime: Instant,
        sorting: SortingOptions,
        filter: FeedListFilter,
        search: String,
        grouping: FeedListGrouping = FeedListGrouping.NONE,
    ): Flow<PagingData<FeedListItem>> =
        Pager(
            config =
                PagingConfig(
                    pageSize = 10,
                    initialLoadSize = 100,
                    prefetchDistance = 100,
                    jumpThreshold = 100,
                ),
        ) {
            val queryString = StringBuilder()
            val args = mutableListOf<Any?>()

            queryString.apply {
                append("SELECT $PREVIEW_COLUMNS, ${grouping.keyExpr()} AS group_key FROM feed_items\n")
                append("LEFT JOIN feeds ON feed_items.feed_id = feeds.id\n")
                append("WHERE\n")

                rawQueryFilter(filter, search, args, minReadTime, feedId, tag)

                append("ORDER BY ")
                when {
                    // The plain date orders are backed by a database index
                    grouping == FeedListGrouping.NONE && sorting == SortingOptions.NEWEST_FIRST ->
                        append("$FEED_ITEM_LIST_SORT_ORDER_DESC\n")
                    grouping == FeedListGrouping.NONE && sorting == SortingOptions.OLDEST_FIRST ->
                        append("$FEED_ITEM_LIST_SORT_ORDER_ASC\n")
                    else -> append("${listOrderTerms(grouping, sorting).toOrderBy()}\n")
                }
            }

            dao.pagingGroupedPreviews(SimpleSQLiteQuery(queryString.toString(), args.toTypedArray()))
        }.flow
            .map { pagingData ->
                pagingData
                    .map { it.preview.toFeedListItem(application, groupKey = it.groupKey) }
            }

    /**
     * The groups of the current list, in list order, with their article and unread counts.
     */
    fun getFeedListGroups(
        feedId: Long,
        tag: String,
        minReadTime: Instant,
        sorting: SortingOptions,
        filter: FeedListFilter,
        search: String,
        grouping: FeedListGrouping,
    ): Flow<List<FeedListGroup>> {
        val queryString = StringBuilder()
        val args = mutableListOf<Any?>()

        queryString.apply {
            append("SELECT ${grouping.keyExpr()} AS group_key, COUNT(*) AS total,\n")
            append("SUM(CASE WHEN feed_items.read_time IS NULL THEN 1 ELSE 0 END) AS unread\n")
            append("FROM feed_items\n")
            append("LEFT JOIN feeds ON feed_items.feed_id = feeds.id\n")
            append("WHERE\n")
            rawQueryFilter(filter, search, args, minReadTime, feedId, tag)
            append("GROUP BY group_key\n")
            // Every group term is constant within a group, MIN just makes that explicit
            val order =
                grouping
                    .orderTerms(sorting)
                    .map { it.copy(expr = "MIN(${it.expr})") }
            if (order.isNotEmpty()) {
                append("ORDER BY ${order.toOrderBy()}\n")
            }
        }

        return dao.getFeedListGroups(SimpleSQLiteQuery(queryString.toString(), args.toTypedArray()))
    }

    suspend fun markGroupAsRead(
        feedId: Long,
        tag: String,
        filter: FeedListFilter,
        search: String,
        minReadTime: Instant,
        grouping: FeedListGrouping,
        groupKey: String,
    ) {
        if (grouping == FeedListGrouping.NONE) return
        val queryString = StringBuilder()
        val args = mutableListOf<Any?>()

        queryString.apply {
            append("UPDATE feed_items SET read_time = coalesce(read_time, ?), notified = 1\n").also {
                args.add(Instant.now().toEpochMilli())
            }
            append("WHERE id IN (\n")
            append("SELECT feed_items.id FROM feed_items\n")
            append("LEFT JOIN feeds ON feed_items.feed_id = feeds.id\n")
            append("WHERE\n")
            rawQueryFilter(filter, search, args, minReadTime, feedId, tag)
            append("AND ${grouping.keyExpr()} = ?\n").also { args.add(groupKey) }
            append(")\n")
        }

        executeWrite(queryString.toString(), args)
    }

    private suspend fun executeWrite(
        sql: String,
        args: List<Any?>,
    ) {
        // Room does not support writable raw queries
        withContext(Dispatchers.IO) {
            appDatabase.openHelper.writableDatabase.execSQL(sql, args.toTypedArray())
            appDatabase.invalidationTracker.refreshVersionsAsync()
        }
    }

    private fun StringBuilder.rawQueryFilter(
        filter: FeedListFilter,
        search: String,
        args: MutableList<Any?>,
        minReadTime: Instant,
        feedId: Long,
        tag: String,
    ) {
        val onlySavedArticles = feedId == ID_SAVED_ARTICLES

        // Always blocklist
        append("block_time is null\n")
        // List filter
        if (!onlySavedArticles) {
            append("AND (\n")
            if (filter.unread && !filter.recentlyRead && !filter.read) {
                append("read_time is null\n")
            } else {
                append("(read_time is null or read_time >= ?)\n").also {
                    args.add(
                        when {
                            filter.read -> Instant.EPOCH
                            else -> minReadTime
                        }.toEpochMilli(),
                    )
                }
            }
            if (filter.saved) {
                append("OR (bookmarked = 1)\n")
            }
            append(")\n")
        }

        if (filter.today) {
            append("AND feed_items.primary_sort_time >= ?\n").also {
                args.add(
                    LocalDate
                        .now()
                        .atStartOfDay(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli(),
                )
            }
        }
        if (filter.shortReads) {
            append("AND $WORD_COUNT_EXPR < $SHORT_READ_MAX_WORDS\n")
        }

        when {
            search.isNotEmpty() -> {
                // User probably means the literal characters
                val sanitizedSearch =
                    search
                        .replace("\\", "\\\\")
                        .replace("%", "\\%")
                        .replace("_", "\\_")
                append("AND (\n")
                append("plain_title LIKE ?\n").also { args.add("%$sanitizedSearch%") }
                append("OR plain_snippet LIKE ?\n").also { args.add("%$sanitizedSearch%") }
                append(")\n")
            }
            onlySavedArticles -> append("AND bookmarked = 1\n")
            feedId > ID_UNSET -> append("AND feed_id IS ?\n").also { args.add(feedId) }
            tag.isNotEmpty() -> append("AND tag IS ?\n").also { args.add(tag) }
        }
    }

    suspend fun markAsReadRaw(
        feedId: Long,
        tag: String,
        filter: FeedListFilter,
        search: String,
        minReadTime: Instant,
        descending: Boolean,
        cursor: FeedItemCursor,
        // Set when the list is grouped or sorted by something other than date alone: the range
        // then follows that order, and [before] picks the articles above (true) or below the
        // cursor. [descending] is only used for the plain date orders.
        listOrder: ListOrder? = null,
    ) {
        val queryString = StringBuilder()
        val args = mutableListOf<Any?>()

        queryString.apply {
            append("UPDATE feed_items SET read_time = coalesce(read_time, ?), notified = 1\n").also {
                args.add(Instant.now().toEpochMilli())
            }
            append("WHERE id IN (\n")

            append("SELECT feed_items.id FROM feed_items\n")
            append("LEFT JOIN feeds ON feed_items.feed_id = feeds.id\n")
            append("WHERE\n")
            rawQueryFilter(filter, search, args, minReadTime, feedId, tag)
            // this version of sqlite doesn't seem to support tuple comparisons
            append("and (\n")

            if (listOrder != null) {
                appendRangeCondition(
                    terms = listOrderTerms(listOrder.grouping, listOrder.sorting),
                    before = listOrder.before,
                    cursorId = cursor.id,
                    args = args,
                )
                // and (
                append(")\n")
                // where id in (
                append(")\n")
                executeWrite(queryString.toString(), args)
                return
            }

            append(
                when (descending) {
                    true -> "primary_sort_time < ?\n"
                    else -> "primary_sort_time > ?\n"
                },
            ).also { args.add(cursor.primarySortTime.toEpochMilli()) }
            append(
                when (descending) {
                    true -> "or primary_sort_time = ? and pub_date < ?\n"
                    else -> "or primary_sort_time = ? and pub_date > ?\n"
                },
            ).also {
                args.add(cursor.primarySortTime.toEpochMilli())
                args.add(cursor.pubDate)
            }

            append(
                when (descending) {
                    true -> "or primary_sort_time = ? and pub_date = ? and feed_items.id < ?\n"
                    else -> "or primary_sort_time = ? and pub_date = ? and feed_items.id > ?\n"
                },
            ).also {
                args.add(cursor.primarySortTime.toEpochMilli())
                args.add(cursor.pubDate)
                args.add(cursor.id)
            }

            append(")\n")

            // where id in (
            append(")\n")
        }

        // Room does not support writable raw queries
        withContext(Dispatchers.IO) {
            appDatabase.openHelper.writableDatabase.execSQL(
                queryString.toString(),
                args.toTypedArray(),
            )
            appDatabase.invalidationTracker.refreshVersionsAsync()
        }
    }

    suspend fun markAsNotified(itemIds: List<Long>) {
        dao.markAsNotified(itemIds)
    }

    suspend fun markAsRead(itemIds: List<Long>) {
        dao.markAsRead(itemIds)
    }

    suspend fun markAsReadAndNotified(
        itemId: Long,
        readTime: Instant = Instant.now(),
    ) {
        dao.markAsReadAndNotified(
            id = itemId,
            readTime = readTime.coerceAtLeast(Instant.EPOCH),
        )
    }

    suspend fun markAsReadAndNotifiedAndOverwriteReadTime(
        itemId: Long,
        readTime: Instant,
    ) {
        dao.markAsReadAndNotifiedAndOverwriteReadTime(
            id = itemId,
            readTime = readTime.coerceAtLeast(Instant.EPOCH),
        )
    }

    suspend fun markAsUnread(itemId: Long) {
        dao.markAsUnread(itemId)
    }

    suspend fun setBookmarked(
        itemId: Long,
        bookmarked: Boolean,
    ) {
        dao.setBookmarked(itemId, bookmarked)
    }

    suspend fun getFullTextByDefault(itemId: Long): Boolean = dao.getFullTextByDefault(itemId) ?: false

    fun getFeedItem(itemId: Long): Flow<FeedItemWithFeed?> = dao.getFeedItemFlow(itemId)

    suspend fun getFeedItemId(
        feedUrl: URL,
        articleGuid: String,
    ): Long? = dao.getItemWith(feedUrl = feedUrl, articleGuid = articleGuid)

    suspend fun getLink(itemId: Long): String? = dao.getLink(itemId)

    suspend fun getArticleOpener(itemId: Long): String? = dao.getOpenArticleWith(itemId)

    suspend fun markAllAsReadInFeed(feedId: Long) {
        dao.markAllAsRead(feedId)
    }

    suspend fun markAllAsReadInTag(tag: String) {
        dao.markAllAsRead(tag)
    }

    suspend fun markAllAsRead() {
        dao.markAllAsRead()
    }

    fun getFeedsItemsWithDefaultFullTextNeedingDownload(): Flow<List<FeedItemIdWithLink>> = dao.getFeedsItemsWithDefaultFullTextNeedingDownload()

    suspend fun markAsFullTextDownloaded(feedItemId: Long) = dao.markAsFullTextDownloaded(feedItemId)

    fun getFeedItemsNeedingNotifying(): Flow<List<Long>> = dao.getFeedItemsNeedingNotifying()

    suspend fun loadFeedItem(
        guid: String,
        feedId: Long,
    ): FeedItem? = dao.loadFeedItem(guid = guid, feedId = feedId)

    suspend fun upsertFeedItems(
        itemsWithText: List<Pair<FeedItem, String>>,
        block: suspend (FeedItem, String) -> Unit,
    ) {
        dao.upsertFeedItems(itemsWithText = itemsWithText, block = block)
    }

    suspend fun getItemsToBeCleanedFromFeed(
        feedId: Long,
        keepCount: Int,
    ) = dao.getItemsToBeCleanedFromFeed(feedId = feedId, keepCount = keepCount)

    suspend fun deleteFeedItems(ids: List<Long>) {
        dao.deleteFeedItems(ids)
    }

    suspend fun updateWordCountFull(
        id: Long,
        wordCount: Int,
    ) {
        dao.updateWordCountFull(id, wordCount)
    }

    suspend fun duplicateStoryExists(
        id: Long,
        title: String,
        link: String?,
    ): Boolean =
        dao.duplicationExists(
            id = id,
            plainTitle = title,
            link = link,
        )

    suspend fun getArticle(id: Long): FeedItemWithFeed? = dao.getFeedItem(id)
}

/**
 * Full list order: groups first, then the chosen sort within each group.
 */
internal fun listOrderTerms(
    grouping: FeedListGrouping,
    sorting: SortingOptions,
): List<OrderTerm> = grouping.orderTerms(sorting) + sorting.orderTerms()

/**
 * The order of the list a mark-above/below is done in, and which side of the cursor to mark.
 */
data class ListOrder(
    val grouping: FeedListGrouping,
    val sorting: SortingOptions,
    val before: Boolean,
)

private fun PreviewItem.toFeedListItem(
    context: Context,
    groupKey: String = "",
) =
    FeedListItem(
        id = id,
        title = plainTitle,
        snippet = plainSnippet,
        feedTitle = feedDisplayTitle,
        feedId = feedId ?: ID_UNSET,
        groupKey = groupKey,
        unread = readTime == null,
        pubDate = pubDate.formatForFeed(context),
        image = image,
        link = link,
        bookmarked = bookmarked,
        feedImageUrl = feedImageUrl,
        rawPubDate = pubDate,
        primarySortTime = primarySortTime,
        wordCount = bestWordCount,
    )

internal fun ZonedDateTime?.formatForFeed(
    context: Context,
    zoneId: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zoneId),
): String =
    this?.let { publicationDate ->
        val formatter =
            if (publicationDate.withZoneSameInstant(zoneId).toLocalDate() >= today) {
                DateFormat.getTimeFormat(context)
            } else {
                DateFormat.getMediumDateFormat(context)
            }
        formatter.timeZone = TimeZone.getTimeZone(zoneId)
        formatter.format(Date.from(publicationDate.toInstant()))
    } ?: ""
