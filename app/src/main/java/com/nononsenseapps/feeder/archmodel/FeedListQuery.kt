package com.nononsenseapps.feeder.archmodel

import androidx.room.ColumnInfo

/**
 * SQL building blocks for grouping and sorting the article list. Every expression here is meant
 * for a query over `feed_items LEFT JOIN feeds ON feed_items.feed_id = feeds.id` and never
 * evaluates to NULL, so the same expressions work in ORDER BY and in range comparisons.
 */
internal data class OrderTerm(
    val expr: String,
    val ascending: Boolean,
)

/** The article's word count, matching what the list shows as reading time. */
internal const val WORD_COUNT_EXPR =
    "(CASE WHEN feeds.fulltext_by_default = 1 THEN IFNULL(feed_items.word_count_full, 0) " +
        "ELSE IFNULL(feed_items.word_count, 0) END)"

// Reading time in the list is words * 60 / 220 seconds, so 3 minutes is 660 words and
// 10 minutes is 2200 words.
internal const val SHORT_READ_MAX_WORDS = 660
internal const val MEDIUM_READ_MAX_WORDS = 2200

private const val FEED_TITLE_KEY =
    "(COALESCE(NULLIF(TRIM(feeds.custom_title), ''), feeds.title, '') COLLATE NOCASE)"

private const val TAG_EXPR = "IFNULL(feeds.tag, '')"

/**
 * Lowercase host of the article link (or the feed url when the article has none), without a
 * leading "www.". Empty when unknown.
 */
private val SITE_EXPR: String =
    run {
        val url = "lower(COALESCE(NULLIF(feed_items.link, ''), feeds.url, ''))"
        val afterScheme = "(CASE WHEN instr($url, '://') > 0 THEN substr($url, instr($url, '://') + 3) ELSE $url END)"
        val host =
            "(CASE WHEN instr($afterScheme, '/') > 0 " +
                "THEN substr($afterScheme, 1, instr($afterScheme, '/') - 1) ELSE $afterScheme END)"
        "(CASE WHEN $host LIKE 'www.%' THEN substr($host, 5) ELSE $host END)"
    }

/**
 * The group key of an article as text. See [FeedListGroup] for the formats.
 */
internal fun FeedListGrouping.keyExpr(): String =
    when (this) {
        FeedListGrouping.NONE -> "''"
        FeedListGrouping.DATE -> "date(feed_items.primary_sort_time / 1000, 'unixepoch', 'localtime')"
        FeedListGrouping.FEED -> "CAST(IFNULL(feeds.id, 0) AS TEXT)"
        FeedListGrouping.TAG -> TAG_EXPR
        FeedListGrouping.LENGTH ->
            "(CASE WHEN $WORD_COUNT_EXPR < $SHORT_READ_MAX_WORDS THEN '0' " +
                "WHEN $WORD_COUNT_EXPR <= $MEDIUM_READ_MAX_WORDS THEN '1' ELSE '2' END)"
        FeedListGrouping.STATUS ->
            "(CASE WHEN feed_items.read_time IS NULL THEN '0' WHEN feed_items.bookmarked = 1 THEN '1' ELSE '2' END)"
        FeedListGrouping.SITE -> SITE_EXPR
    }

/**
 * How groups are ordered relative to each other. Within a group, [SortingOptions.orderTerms]
 * decide.
 */
internal fun FeedListGrouping.orderTerms(sorting: SortingOptions): List<OrderTerm> =
    when (this) {
        FeedListGrouping.NONE -> emptyList()
        FeedListGrouping.DATE ->
            listOf(OrderTerm(keyExpr(), ascending = sorting == SortingOptions.OLDEST_FIRST))
        FeedListGrouping.FEED ->
            listOf(
                OrderTerm(FEED_TITLE_KEY, ascending = true),
                OrderTerm("IFNULL(feeds.id, 0)", ascending = true),
            )
        FeedListGrouping.TAG ->
            listOf(
                // Untagged last
                OrderTerm("($TAG_EXPR = '')", ascending = true),
                OrderTerm("($TAG_EXPR COLLATE NOCASE)", ascending = true),
                OrderTerm(TAG_EXPR, ascending = true),
            )
        FeedListGrouping.LENGTH, FeedListGrouping.STATUS ->
            listOf(OrderTerm(keyExpr(), ascending = true))
        FeedListGrouping.SITE ->
            listOf(
                // Unknown site last
                OrderTerm("($SITE_EXPR = '')", ascending = true),
                OrderTerm(SITE_EXPR, ascending = true),
            )
    }

/**
 * Order of articles within the list (or within each group). Always ends in the article id so
 * the order is total.
 */
internal fun SortingOptions.orderTerms(): List<OrderTerm> {
    val newest =
        listOf(
            OrderTerm("feed_items.primary_sort_time", ascending = false),
            OrderTerm("IFNULL(feed_items.pub_date, '')", ascending = false),
            OrderTerm("feed_items.id", ascending = false),
        )
    return when (this) {
        SortingOptions.NEWEST_FIRST -> newest
        SortingOptions.OLDEST_FIRST -> newest.map { it.copy(ascending = true) }
        SortingOptions.SHORTEST_FIRST -> listOf(OrderTerm(WORD_COUNT_EXPR, ascending = true)) + newest
        SortingOptions.LONGEST_FIRST -> listOf(OrderTerm(WORD_COUNT_EXPR, ascending = false)) + newest
    }
}

internal fun List<OrderTerm>.toOrderBy(): String = joinToString(", ") { "${it.expr} ${if (it.ascending) "ASC" else "DESC"}" }

/**
 * Appends a condition that is true for the articles placed before ([before] = true) or after
 * the cursor article in the order given by [terms]. A lexicographic comparison, spelled out
 * since tuple comparisons aren't available here.
 */
internal fun StringBuilder.appendRangeCondition(
    terms: List<OrderTerm>,
    before: Boolean,
    cursorId: Long,
    args: MutableList<Any?>,
) {
    fun cursorValue(expr: String): String {
        args.add(cursorId)
        return "(SELECT $expr FROM feed_items LEFT JOIN feeds ON feed_items.feed_id = feeds.id " +
            "WHERE feed_items.id = ?)"
    }

    append("(\n")
    terms.forEachIndexed { i, term ->
        if (i > 0) append("OR ")
        append("(")
        for (j in 0 until i) {
            append("${terms[j].expr} = ${cursorValue(terms[j].expr)} AND ")
        }
        // Placed before the cursor means smaller on an ascending term, larger on a descending one
        val op = if (term.ascending == before) "<" else ">"
        append("${term.expr} $op ${cursorValue(term.expr)}")
        append(")\n")
    }
    append(")\n")
}

/**
 * One group of the current article list, in list order.
 *
 * [key] formats: DATE "yyyy-MM-dd" (local date); FEED the feed id ("0" = no feed); TAG the tag
 * ("" = untagged); LENGTH "0" quick (under 3 min), "1" medium (3 to 10 min), "2" long; STATUS
 * "0" unread, "1" saved, "2" read; SITE the host without "www." ("" = unknown).
 */
data class FeedListGroup(
    @ColumnInfo(name = "group_key") val key: String,
    @ColumnInfo(name = "total") val total: Int,
    @ColumnInfo(name = "unread") val unread: Int,
)
