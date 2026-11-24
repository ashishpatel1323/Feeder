package com.nononsenseapps.feeder.archmodel

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Mail-style date groups: Today, Yesterday, This week, Last week, Earlier this month,
 * Last month, then one group per month (with the year for months of earlier years).
 *
 * A group key is "yyyy-MM-dd:kind", where the date is where the group starts. Groups never
 * overlap and an empty group never shows, so keys sort in the same order as the dates they
 * hold: newest first is a descending text sort.
 */
enum class DateBucket(
    val id: String,
) {
    TODAY("today"),
    YESTERDAY("yesterday"),
    THIS_WEEK("week"),
    LAST_WEEK("lastweek"),
    THIS_MONTH("month"),
    LAST_MONTH("lastmonth"),

    /** A month of this year, shown as its name. */
    MONTH("m"),

    /** A month of an earlier year, shown as name and year. */
    OLDER_MONTH("y"),
    ;

    companion object {
        fun fromId(id: String): DateBucket? = entries.firstOrNull { it.id == id }
    }
}

data class DateBucketKey(
    val start: LocalDate,
    val bucket: DateBucket,
) {
    override fun toString() = "$start:${bucket.id}"

    companion object {
        fun parse(key: String): DateBucketKey? {
            val i = key.indexOf(':')
            if (i < 0) return null
            val bucket = DateBucket.fromId(key.substring(i + 1)) ?: return null
            return try {
                DateBucketKey(LocalDate.parse(key.substring(0, i)), bucket)
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * Where each fixed group starts, for [today] in the user's time zone. The week starts on the
 * locale's first day of the week.
 */
data class DateBucketBounds(
    val today: LocalDate,
    val firstDayOfWeek: DayOfWeek,
) {
    val yesterday: LocalDate = today.minusDays(1)
    val weekStart: LocalDate = today.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
    val lastWeekStart: LocalDate = weekStart.minusWeeks(1)
    val monthStart: LocalDate = today.withDayOfMonth(1)
    val lastMonthStart: LocalDate = monthStart.minusMonths(1)
    val yearStart: LocalDate = today.withDayOfYear(1)

    /** The fixed groups, newest first. The first one whose start is on or before a date holds it. */
    private val fixed: List<Pair<LocalDate, DateBucket>> =
        listOf(
            today to DateBucket.TODAY,
            yesterday to DateBucket.YESTERDAY,
            weekStart to DateBucket.THIS_WEEK,
            lastWeekStart to DateBucket.LAST_WEEK,
            monthStart to DateBucket.THIS_MONTH,
            lastMonthStart to DateBucket.LAST_MONTH,
        )

    fun keyOf(date: LocalDate): DateBucketKey {
        // Anything dated in the future counts as today
        if (!date.isBefore(today)) return DateBucketKey(today, DateBucket.TODAY)
        for ((start, bucket) in fixed) {
            if (!date.isBefore(start)) return DateBucketKey(start, bucket)
        }
        val month = date.withDayOfMonth(1)
        return DateBucketKey(month, if (date.isBefore(yearStart)) DateBucket.OLDER_MONTH else DateBucket.MONTH)
    }

    /**
     * The same as [keyOf] in SQL, for a date expression [dateExpr] giving "yyyy-MM-dd". Dates
     * are inlined as literals: they are generated here, never user input.
     */
    fun keySql(dateExpr: String): String =
        buildString {
            append("(CASE ")
            append("WHEN $dateExpr >= '$today' THEN '${DateBucketKey(today, DateBucket.TODAY)}' ")
            for ((start, bucket) in fixed.drop(1)) {
                append("WHEN $dateExpr >= '$start' THEN '${DateBucketKey(start, bucket)}' ")
            }
            append("WHEN $dateExpr >= '$yearStart' THEN strftime('%Y-%m-01', $dateExpr) || ':${DateBucket.MONTH.id}' ")
            append("ELSE strftime('%Y-%m-01', $dateExpr) || ':${DateBucket.OLDER_MONTH.id}' END)")
        }

    companion object {
        fun current(locale: Locale = Locale.getDefault()): DateBucketBounds =
            DateBucketBounds(LocalDate.now(), WeekFields.of(locale).firstDayOfWeek)
    }
}
