package com.nononsenseapps.feeder.ui.compose.feed

import com.nononsenseapps.feeder.archmodel.FeedListGrouping
import java.time.LocalDate
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class FeedListGroupingTest {
    // A Thursday
    private val now = LocalDate.of(2026, 11, 12)

    private val labels =
        GroupHeaderLabels(
            today = "Today",
            yesterday = "Yesterday",
            untagged = "Untagged",
            quickReads = "Quick",
            mediumReads = "Medium",
            longReads = "Long",
            unread = "Unread",
            saved = "Saved",
            read = "Read",
            unknownSite = "Unknown site",
            noFeed = "No feed",
        )

    private fun dateHeader(date: LocalDate): String =
        dateHeaderText(
            date = date,
            today = "Today",
            yesterday = "Yesterday",
            locale = Locale.ENGLISH,
            now = now,
        )

    private fun header(
        grouping: FeedListGrouping,
        key: String,
        feedTitle: String = "",
    ): String =
        groupHeaderText(
            grouping = grouping,
            groupKey = key,
            feedTitle = feedTitle,
            labels = labels,
            locale = Locale.ENGLISH,
            now = now,
        )

    @Test
    fun todayAndYesterday() {
        assertEquals("Today", dateHeader(now))
        assertEquals("Yesterday", dateHeader(now.minusDays(1)))
    }

    @Test
    fun lastWeekShowsWeekday() {
        assertEquals("Monday", dateHeader(now.minusDays(3)))
    }

    @Test
    fun olderShowsFullDate() {
        assertEquals("Thursday, November 5, 2026", dateHeader(now.minusDays(7)))
    }

    @Test
    fun dateGroupKeyIsParsed() {
        assertEquals("Today", header(FeedListGrouping.DATE, "2026-11-12"))
        assertEquals("Yesterday", header(FeedListGrouping.DATE, "2026-11-11"))
    }

    @Test
    fun unparseableDateKeyIsShownAsIs() {
        assertEquals("garbage", header(FeedListGrouping.DATE, "garbage"))
    }

    @Test
    fun feedHeaderUsesFeedTitle() {
        assertEquals("AWS ML", header(FeedListGrouping.FEED, "12", feedTitle = "AWS ML"))
        assertEquals("No feed", header(FeedListGrouping.FEED, "0", feedTitle = ""))
    }

    @Test
    fun tagHeader() {
        assertEquals("Tech", header(FeedListGrouping.TAG, "Tech"))
        assertEquals("Untagged", header(FeedListGrouping.TAG, ""))
    }

    @Test
    fun lengthHeader() {
        assertEquals("Quick", header(FeedListGrouping.LENGTH, "0"))
        assertEquals("Medium", header(FeedListGrouping.LENGTH, "1"))
        assertEquals("Long", header(FeedListGrouping.LENGTH, "2"))
    }

    @Test
    fun statusHeader() {
        assertEquals("Unread", header(FeedListGrouping.STATUS, "0"))
        assertEquals("Saved", header(FeedListGrouping.STATUS, "1"))
        assertEquals("Read", header(FeedListGrouping.STATUS, "2"))
    }

    @Test
    fun siteHeader() {
        assertEquals("techcrunch.com", header(FeedListGrouping.SITE, "techcrunch.com"))
        assertEquals("Unknown site", header(FeedListGrouping.SITE, ""))
    }

    @Test
    fun collapseKeyIncludesGrouping() {
        assertEquals("TAG:Tech", collapseKey(FeedListGrouping.TAG, "Tech"))
    }
}
