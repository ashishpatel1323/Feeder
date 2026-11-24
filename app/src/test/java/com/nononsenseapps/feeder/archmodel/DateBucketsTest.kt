package com.nononsenseapps.feeder.archmodel

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DateBucketsTest {
    private fun key(
        today: LocalDate,
        date: LocalDate,
        firstDay: DayOfWeek = DayOfWeek.MONDAY,
    ) = DateBucketBounds(today, firstDay).keyOf(date).toString()

    @Test
    fun bucketsForAThursday() {
        val today = LocalDate.of(2026, 11, 12)
        assertEquals("2026-11-12:today", key(today, today))
        assertEquals("2026-11-12:today", key(today, today.plusDays(3)))
        assertEquals("2026-11-11:yesterday", key(today, today.minusDays(1)))
        assertEquals("2026-11-09:week", key(today, LocalDate.of(2026, 11, 10)))
        assertEquals("2026-11-02:lastweek", key(today, LocalDate.of(2026, 11, 8)))
        assertEquals("2026-11-01:month", key(today, LocalDate.of(2026, 11, 1)))
        assertEquals("2026-10-01:lastmonth", key(today, LocalDate.of(2026, 10, 31)))
        assertEquals("2026-09-01:m", key(today, LocalDate.of(2026, 9, 15)))
        assertEquals("2025-12-01:y", key(today, LocalDate.of(2025, 12, 15)))
    }

    @Test
    fun weekStartFollowsLocale() {
        val today = LocalDate.of(2026, 11, 12)
        // Sunday-first week starts on 8 Nov
        assertEquals("2026-11-08:week", key(today, LocalDate.of(2026, 11, 8), DayOfWeek.SUNDAY))
        assertEquals("2026-11-02:lastweek", key(today, LocalDate.of(2026, 11, 8), DayOfWeek.MONDAY))
    }

    @Test
    fun lastWeekSpanningMonthsWinsOverMonth() {
        // Tuesday 3 Nov: last week is 26 Oct to 1 Nov
        val today = LocalDate.of(2026, 11, 3)
        assertEquals("2026-10-26:lastweek", key(today, LocalDate.of(2026, 11, 1)))
        assertEquals("2026-10-26:lastweek", key(today, LocalDate.of(2026, 10, 27)))
        assertEquals("2026-10-01:lastmonth", key(today, LocalDate.of(2026, 10, 25)))
    }

    @Test
    fun januaryHasNoMonthsOfThisYear() {
        val today = LocalDate.of(2027, 1, 20)
        assertEquals("2026-12-01:lastmonth", key(today, LocalDate.of(2026, 12, 5)))
        assertEquals("2026-11-01:y", key(today, LocalDate.of(2026, 11, 5)))
    }

    @Test
    fun keysSortNewestFirstAsText() {
        val today = LocalDate.of(2026, 11, 12)
        val bounds = DateBucketBounds(today, DayOfWeek.MONDAY)
        val dates = (0L..800L).map { today.minusDays(it) }
        val keys = dates.map { bounds.keyOf(it).toString() }
        // Newer dates never get a key that sorts below an older date's key
        for (i in 1 until keys.size) {
            assertTrue(keys[i - 1] >= keys[i], "${keys[i - 1]} before ${keys[i]}")
        }
    }

    @Test
    fun parseRoundTrips() {
        val k = DateBucketKey(LocalDate.of(2026, 10, 26), DateBucket.LAST_WEEK)
        assertEquals(k, DateBucketKey.parse(k.toString()))
        assertEquals(null, DateBucketKey.parse("2026-11-12"))
    }
}
