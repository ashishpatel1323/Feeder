package com.nononsenseapps.feeder.ui.compose.feed

import com.nononsenseapps.feeder.archmodel.DateBucketBounds
import com.nononsenseapps.feeder.archmodel.FeedListGrouping
import java.time.DayOfWeek
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
            unknownSite = "Unknown site",
            noFeed = "No feed",
        )

    private val bounds = DateBucketBounds(now, DayOfWeek.MONDAY)

    private fun dateHeader(date: LocalDate): String =
        dateHeaderText(bounds.keyOf(date), labels, Locale.ENGLISH)

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
        )

    @Test
    fun todayAndYesterdayShowTheDate() {
        assertEquals("Today · Thu, 12 Nov", dateHeader(now))
        assertEquals("Yesterday · Wed, 11 Nov", dateHeader(now.minusDays(1)))
    }

    @Test
    fun weeksMonthsAndYears() {
        // Week starts Monday 9 Nov
        assertEquals("This week", dateHeader(LocalDate.of(2026, 11, 9)))
        assertEquals("Last week", dateHeader(LocalDate.of(2026, 11, 2)))
        assertEquals("Earlier this month", dateHeader(LocalDate.of(2026, 11, 1)))
        assertEquals("Last month", dateHeader(LocalDate.of(2026, 10, 1)))
        assertEquals("September", dateHeader(LocalDate.of(2026, 9, 30)))
        assertEquals("January", dateHeader(LocalDate.of(2026, 1, 1)))
        assertEquals("December 2025", dateHeader(LocalDate.of(2025, 12, 31)))
    }

    @Test
    fun dateGroupKeyIsParsed() {
        assertEquals("Today · Thu, 12 Nov", header(FeedListGrouping.DATE, "2026-11-12:today"))
        assertEquals("Last week", header(FeedListGrouping.DATE, "2026-11-02:lastweek"))
        assertEquals("March 2024", header(FeedListGrouping.DATE, "2024-03-01:y"))
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
    fun siteHeader() {
        assertEquals("techcrunch.com", header(FeedListGrouping.SITE, "techcrunch.com"))
        assertEquals("Unknown site", header(FeedListGrouping.SITE, ""))
    }

    @Test
    fun collapseKeyIncludesGrouping() {
        assertEquals("TAG:Tech", collapseKey(FeedListGrouping.TAG, "Tech"))
    }
}
