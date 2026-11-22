package com.nononsenseapps.feeder.db.room

import androidx.room.ColumnInfo
import com.nononsenseapps.feeder.db.COL_BOOKMARKED
import com.nononsenseapps.feeder.db.COL_FIRSTSYNCEDTIME
import com.nononsenseapps.feeder.db.COL_PRIMARYSORTTIME
import com.nononsenseapps.feeder.db.COL_READ_TIME
import com.nononsenseapps.feeder.db.COL_WORD_COUNT
import com.nononsenseapps.feeder.db.COL_WORD_COUNT_FULL
import java.time.Instant

/** The few columns the feed page's stats need, one row per stored article. */
data class FeedItemForStats(
    @ColumnInfo(name = COL_PRIMARYSORTTIME) val primarySortTime: Instant,
    @ColumnInfo(name = COL_FIRSTSYNCEDTIME) val firstSyncedTime: Instant,
    @ColumnInfo(name = COL_READ_TIME) val readTime: Instant?,
    @ColumnInfo(name = COL_BOOKMARKED) val bookmarked: Boolean,
    @ColumnInfo(name = COL_WORD_COUNT) val wordCount: Int,
    @ColumnInfo(name = COL_WORD_COUNT_FULL) val wordCountFull: Int,
)
