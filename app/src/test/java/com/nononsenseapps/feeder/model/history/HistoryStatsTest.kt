package com.nononsenseapps.feeder.model.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class HistoryStatsTest {
    private val now = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 27, 21, 0, 0) }.timeInMillis
    private var n = 0

    private fun at(
        back: Int,
        h: Int,
        m: Int = 0,
    ): Long = HistoryStats.dayStart(now, back) + h * 3_600_000L + m * 60_000L

    private fun ev(
        a: String,
        t: Long,
        f: Double = -1.0,
        p: String = "",
    ) = HistoryEvent(id = "e${n++}", c = "audiobook", i = "b", a = a, at = t, f = f, p = p)

    @Test
    fun pairsEachPlayWithTheNextEvent() {
        val s = HistoryStats.sessions(listOf(ev("pause", at(0, 20, 30)), ev("play", at(0, 20)), ev("play", at(0, 21)), ev("pause", at(0, 21, 10))), now)
        assertEquals(listOf(30L, 10L), s.map { (it.second - it.first) / 60_000 })
        // Still playing counts up to now; one with no pause is capped
        assertEquals(now, HistoryStats.sessions(listOf(ev("play", at(0, 20, 45))), now)[0].second)
        val long = HistoryStats.sessions(listOf(ev("play", at(1, 8)), ev("pause", at(0, 8))), now)[0]
        assertEquals(HistoryStats.MAX_SESSION_MS, long.second - long.first)
    }

    @Test
    fun addsMinutesPerDayAndTheStreak() {
        val g = HistoryStats.graphOf(listOf(ev("play", at(1, 23, 30)), ev("pause", at(0, 0, 20)), ev("open", at(3, 9)), ev("close", at(3, 9, 15))), now)
        assertEquals(listOf(15, 0, 30, 20), g.minutes.takeLast(4))
        assertEquals(65, g.week)
        assertEquals(2, g.streak)
        assertEquals(2, HistoryStats.streakOf(listOf(1, 0, 2, 3, 0)))
    }

    @Test
    fun followsProgressAndEstimatesDaysLeft() {
        val g = HistoryStats.graphOf(listOf(ev("pause", at(20, 9), 0.2), ev("pause", at(5, 9), 0.3), ev("pause", at(0, 9), 0.48, "p. 48 of 100")), now)
        assertEquals(listOf(0.2, 0.3, 0.48), g.progress.map { it.f })
        assertEquals(26, g.daysLeft)
        assertEquals(48 to 100, g.page)
        val none = HistoryStats.graphOf(listOf(ev("open", at(0, 9)), ev("close", at(0, 10))), now)
        assertNull(none.f)
        assertNull(none.daysLeft)
        assertEquals(4, none.minutesPerDay)
    }

    @Test
    fun countsPagesAndFindsTheChapter() {
        assertEquals(listOf(1, 0, 2), HistoryStats.countByDay(listOf(at(0, 9), at(0, 10), at(2, 9), at(30, 9)), now).takeLast(3))
        assertEquals(1 to 0.5, HistoryStats.chapterAt(listOf(0L to 100L, 100L to 100L), 200, 0.75))
    }

    @Test
    fun splitsTodayIntoHours() {
        val spans = HistoryStats.sessions(listOf(ev("play", at(1, 23, 40)), ev("pause", at(0, 0, 30)), ev("play", at(0, 15, 50)), ev("pause", at(0, 16, 20))), now)
        val m = HistoryStats.minutesByHour(spans, now)
        assertEquals(24, m.size)
        assertEquals(30, m[0])
        assertEquals(10, m[15])
        assertEquals(20, m[16])
        assertEquals(0, m[22])
        assertEquals(21, HistoryStats.hourIndex(now))
        assertEquals(2, HistoryStats.countByHour(listOf(at(0, 9, 5), at(0, 9, 50), at(1, 9)), now)[9])
        val p = HistoryStats.progressFrom(listOf(ev("pause", at(2, 9), f = 0.5), ev("pause", at(0, 10), f = 0.6)), HistoryStats.dayStart(now))
        assertEquals(listOf(0.5, 0.6), p.map { it.f })
        assertEquals(HistoryStats.dayStart(now), p.first().at)
    }
}
