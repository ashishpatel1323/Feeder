package com.nononsenseapps.feeder.archmodel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeedListQueryTest {
    @Test
    fun rangeConditionComparesTermsLexicographically() {
        val terms =
            listOf(
                OrderTerm("a", ascending = true),
                OrderTerm("b", ascending = false),
            )
        val args = mutableListOf<Any?>()
        val builder = StringBuilder()
        builder.appendRangeCondition(terms, before = true, cursorId = 7L, args = args)
        val sql = builder.toString()

        // a before cursor: smaller a, or same a and larger b (b is descending)
        assertTrue(sql.contains("(a < (SELECT a "), sql)
        assertTrue(sql.contains("OR (a = (SELECT a "), sql)
        assertTrue(sql.contains("AND b > (SELECT b "), sql)
        // One cursor lookup per comparison: a, then a and b
        assertEquals(listOf<Any?>(7L, 7L, 7L), args)
    }

    @Test
    fun afterFlipsComparisons() {
        val args = mutableListOf<Any?>()
        val builder = StringBuilder()
        builder.appendRangeCondition(listOf(OrderTerm("a", ascending = true)), before = false, cursorId = 1L, args = args)
        val sql = builder.toString()
        assertTrue(sql.contains("(a > (SELECT a "), sql)
    }

    @Test
    fun plainGroupingAddsNoGroupTerms() {
        assertEquals(emptyList(), FeedListGrouping.NONE.orderTerms(SortingOptions.NEWEST_FIRST))
        assertEquals(
            SortingOptions.NEWEST_FIRST.orderTerms(),
            listOrderTerms(FeedListGrouping.NONE, SortingOptions.NEWEST_FIRST),
        )
    }

    @Test
    fun everySortingEndsWithId() {
        SortingOptions.entries.forEach {
            assertEquals("feed_items.id", it.orderTerms().last().expr)
        }
    }
}
