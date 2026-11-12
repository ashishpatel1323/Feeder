package com.nononsenseapps.feeder.model

import androidx.room.ColumnInfo
import androidx.room.Embedded

/**
 * A list row together with the key of the group it belongs to in the current grouping.
 */
data class GroupedPreviewItem(
    @Embedded val preview: PreviewItem,
    @ColumnInfo(name = "group_key") val groupKey: String,
)
