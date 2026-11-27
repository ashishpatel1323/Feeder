package com.nononsenseapps.feeder.model.history

import java.util.Calendar
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** How far through an item was at a moment, 0–1. */
data class ProgressPoint(
    val at: Long,
    val f: Double,
)

/**
 * What the graphs at the top of an item's History show, worked out only from the plays, pauses,
 * opens and closes already recorded. The same as the web app's lib/historyStats.ts.
 */
data class HistoryGraph(
    /** Start of each of the last [HistoryStats.DAYS] days, oldest first; the last one is today */
    val days: List<Long>,
    /** Minutes on each of those days */
    val minutes: List<Int>,
    /** Minutes over the last 7 days, today included */
    val week: Int,
    /** Days in a row with some time, up to today (or yesterday, when nothing yet today) */
    val streak: Int,
    /** How far through it was over those days, oldest first (empty when not known) */
    val progress: List<ProgressPoint>,
    /** How far through it is now, when known */
    val f: Double?,
    val minutesPerDay: Int,
    /** Gained a day (0–1), when it went forward */
    val fPerDay: Double?,
    /** Days to the end at that pace */
    val daysLeft: Int?,
    /** Page and page count from the last "p. 41 of 310" chip (ebooks) */
    val page: Pair<Int, Int>?,
)

object HistoryStats {
    const val DAYS = 14

    /** A play or open with no pause or close after it (the app was killed) counts at most this long */
    const val MAX_SESSION_MS = 4 * 3_600_000L

    private val PAGE = Regex("""^p\. (\d+) of (\d+)""")

    /** The start of the day [t] is in, [back] days before that (local time). */
    fun dayStart(
        t: Long,
        back: Int = 0,
    ): Long =
        Calendar
            .getInstance()
            .apply {
                timeInMillis = t
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (back != 0) add(Calendar.DAY_OF_MONTH, -back)
            }.timeInMillis

    fun days(now: Long): List<Long> = (0 until DAYS).map { dayStart(now, DAYS - 1 - it) }

    /** Each play (or open) with the event after it, as start to end. */
    fun sessions(
        events: List<HistoryEvent>,
        now: Long,
    ): List<Pair<Long, Long>> {
        val sorted = events.sortedBy { it.at }
        val out = mutableListOf<Pair<Long, Long>>()
        sorted.forEachIndexed { k, e ->
            if (e.a != ItemHistory.PLAY && e.a != ItemHistory.OPEN) return@forEachIndexed
            // Still going: count up to now
            val end = sorted.getOrNull(k + 1)?.at ?: now
            val len = min(max(0L, end - e.at), MAX_SESSION_MS)
            if (len > 0) out += e.at to e.at + len
        }
        return out
    }

    /** Minutes on each of the last [DAYS] days, a session over midnight split between both. */
    fun minutesByDay(
        spans: List<Pair<Long, Long>>,
        now: Long,
    ): List<Int> {
        val days = days(now)
        val ends = days.indices.map { if (it + 1 < DAYS) days[it + 1] else dayStart(now, -1) }
        val ms = LongArray(DAYS)
        for ((a, b) in spans) {
            for (k in days.indices) {
                val overlap = min(b, ends[k]) - max(a, days[k])
                if (overlap > 0) ms[k] += overlap
            }
        }
        return ms.map { (it / 60_000.0).roundToInt() }
    }

    /** Days in a row with some minutes, ending today (or yesterday when nothing yet today). */
    fun streakOf(minutes: List<Int>): Int {
        var k = minutes.size - 1
        if (k >= 0 && minutes[k] == 0) k--
        var n = 0
        while (k >= 0 && minutes[k] > 0) {
            n++
            k--
        }
        return n
    }

    fun graphOf(
        events: List<HistoryEvent>,
        now: Long = System.currentTimeMillis(),
    ): HistoryGraph {
        val days = days(now)
        val minutes = minutesByDay(sessions(events, now), now)
        val from = days.first()
        val sorted = events.sortedBy { it.at }
        val known = sorted.filter { it.f >= 0 }
        val progress = mutableListOf<ProgressPoint>()
        known.lastOrNull { it.at < from }?.let { progress += ProgressPoint(from, it.f) }
        known.filter { it.at >= from }.forEach { progress += ProgressPoint(it.at, it.f) }
        val f = known.lastOrNull()?.f
        val gain = if (progress.size > 1) progress.last().f - progress.first().f else 0.0
        val fPerDay = if (gain > 0.001) gain / DAYS else null
        val daysLeft = if (fPerDay != null && f != null && f < 0.999) max(1, ceil((1 - f) / fPerDay - 1e-9).toInt()) else null
        val page =
            sorted.asReversed().firstNotNullOfOrNull { e ->
                PAGE.find(e.p)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            }
        return HistoryGraph(
            days = days,
            minutes = minutes,
            week = minutes.takeLast(7).sum(),
            streak = streakOf(minutes),
            progress = progress,
            f = f,
            minutesPerDay = (minutes.sum() / DAYS.toDouble()).roundToInt(),
            fPerDay = fPerDay,
            daysLeft = daysLeft,
            page = page,
        )
    }

    /** How many of [times] fall on each of the last [DAYS] days (ZIM pages visited). */
    fun countByDay(
        times: List<Long>,
        now: Long = System.currentTimeMillis(),
    ): List<Int> {
        val days = days(now)
        val end = dayStart(now, -1)
        val out = IntArray(DAYS)
        for (t in times) {
            if (t < days.first() || t >= end) continue
            var k = DAYS - 1
            while (t < days[k]) k--
            out[k]++
        }
        return out.toList()
    }

    /** The chapter at [f] through the book, from (start in book, length) pairs: its index and how far into it. */
    fun chapterAt(
        chapters: List<Pair<Long, Long>>,
        bookLengthMs: Long,
        f: Double,
    ): Pair<Int, Double>? {
        if (chapters.isEmpty() || bookLengthMs <= 0) return null
        val at = f * bookLengthMs
        var k = 0
        chapters.forEachIndexed { j, c -> if (c.first <= at) k = j }
        val (start, length) = chapters[k]
        val len = if (length > 0) length else (chapters.getOrNull(k + 1)?.first ?: bookLengthMs) - start
        return k to if (len > 0) ((at - start) / len).coerceIn(0.0, 1.0) else 0.0
    }
}
