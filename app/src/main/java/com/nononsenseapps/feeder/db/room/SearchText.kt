package com.nononsenseapps.feeder.db.room

import androidx.room.ColumnInfo

/** The words of an article besides its title, for global search. */
data class SearchText(
    val id: Long,
    @ColumnInfo(name = "plain_snippet") val snippet: String,
    @ColumnInfo(name = "author") val author: String?,
)
