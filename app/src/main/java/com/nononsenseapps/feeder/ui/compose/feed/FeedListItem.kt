package com.nononsenseapps.feeder.ui.compose.feed

import androidx.compose.runtime.Immutable
import com.nononsenseapps.feeder.db.room.FeedItemCursor
import com.nononsenseapps.feeder.db.room.ID_UNSET
import com.nononsenseapps.feeder.model.ThumbnailImage
import com.nononsenseapps.feeder.model.playlists.ItemKind
import com.nononsenseapps.feeder.model.playlists.itemKind
import java.net.URL
import java.time.Instant
import java.time.ZonedDateTime

@Immutable
data class FeedListItem(
    val id: Long,
    val title: String,
    val snippet: String,
    val feedTitle: String,
    val unread: Boolean,
    val pubDate: String,
    val image: ThumbnailImage?,
    val link: String?,
    val bookmarked: Boolean,
    val feedImageUrl: URL?,
    val primarySortTime: Instant,
    val rawPubDate: ZonedDateTime?,
    val wordCount: Int,
    val feedId: Long = ID_UNSET,
    // Key of the group this item is in for the current grouping, empty when not grouped
    val groupKey: String = "",
    // Sync key (feedUrl + "\n" + guid) of a YouTube article, for where its video was left; empty otherwise
    val videoKey: String = "",
    val enclosureLink: String? = null,
    val enclosureType: String? = null,
    // Episode length in seconds from the feed, null when not given
    val enclosureDuration: Long? = null,
) {
    /** Article, podcast episode or YouTube video: decided the same way as in playlists. */
    val kind: ItemKind
        get() = itemKind(link, enclosureLink, enclosureType)

    val cursor: FeedItemCursor
        get() =
            object : FeedItemCursor {
                override val primarySortTime: Instant = this@FeedListItem.primarySortTime
                override val pubDate: ZonedDateTime? = this@FeedListItem.rawPubDate
                override val id: Long = this@FeedListItem.id
            }

    /**
     * Used so lazylist/grid can re-use items.
     *
     * Type will depend on having images as that will influence visible items
     */
    fun contentType(): String =
        when {
            image != null -> "row/image"
            else -> "row/other"
        }
}
