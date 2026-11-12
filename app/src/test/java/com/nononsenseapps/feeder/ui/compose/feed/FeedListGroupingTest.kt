package com.nononsenseapps.feeder.ui.compose.feed

import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class FeedListGroupingTest {
    private val zone = ZoneOffset.UTC

    // A Thursday
    private val now = LocalDate.of(2026, 11, 12)

    private fun header(date: LocalDate): String =
        dateHeaderText(
            time = date.atTime(9, 30).toInstant(zone),
            today = "Today",
            yesterday = "Yesterday",
            locale = Locale.ENGLISH,
            zoneId = zone,
            now = now,
        )

    @Test
    fun todayAndYesterday() {
        assertEquals("Today", header(now))
        assertEquals("Yesterday", header(now.minusDays(1)))
    }

    @Test
    fun lastWeekShowsWeekday() {
        assertEquals("Monday", header(now.minusDays(3)))
    }

    @Test
    fun olderShowsFullDate() {
        assertEquals("Thursday, November 5, 2026", header(now.minusDays(7)))
    }
}
