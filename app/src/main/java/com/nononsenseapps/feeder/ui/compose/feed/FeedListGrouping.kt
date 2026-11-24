package com.nononsenseapps.feeder.ui.compose.feed

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.archmodel.DateBucket
import com.nononsenseapps.feeder.archmodel.DateBucketKey
import com.nononsenseapps.feeder.archmodel.FeedListGrouping
import com.nononsenseapps.feeder.archmodel.SortingOptions
import com.nononsenseapps.feeder.ui.compose.components.safeSemantics
import com.nononsenseapps.feeder.ui.compose.feedarticle.FeedListFilter
import com.nononsenseapps.feeder.ui.compose.feedarticle.FeedListFilterCallback
import com.nononsenseapps.feeder.ui.compose.feedarticle.FeedListShow
import com.nononsenseapps.feeder.ui.compose.feedarticle.show
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Key used to remember which groups are collapsed. Includes the grouping so a key from one
 * grouping never collapses a group of another.
 */
fun collapseKey(
    grouping: FeedListGrouping,
    groupKey: String,
): String = "${grouping.name}:$groupKey"

/**
 * Texts the header helpers need, resolved from resources once per composition.
 */
data class GroupHeaderLabels(
    val today: String,
    val yesterday: String,
    val thisWeek: String = "This week",
    val lastWeek: String = "Last week",
    val earlierThisMonth: String = "Earlier this month",
    val lastMonth: String = "Last month",
    val untagged: String,
    val quickReads: String,
    val mediumReads: String,
    val longReads: String,
    val unknownSite: String,
    val noFeed: String,
)

@Composable
fun rememberGroupHeaderLabels(): GroupHeaderLabels =
    GroupHeaderLabels(
        today = stringResource(R.string.today),
        yesterday = stringResource(R.string.yesterday),
        thisWeek = stringResource(R.string.group_this_week),
        lastWeek = stringResource(R.string.group_last_week),
        earlierThisMonth = stringResource(R.string.group_earlier_this_month),
        lastMonth = stringResource(R.string.group_last_month),
        untagged = stringResource(R.string.group_untagged),
        quickReads = stringResource(R.string.group_quick_reads),
        mediumReads = stringResource(R.string.group_medium_reads),
        longReads = stringResource(R.string.group_long_reads),
        unknownSite = stringResource(R.string.group_unknown_site),
        noFeed = stringResource(R.string.group_no_feed),
    )

@Composable
fun rememberGroupHeaderText(
    item: FeedListItem,
    grouping: FeedListGrouping,
): String =
    groupHeaderText(
        grouping = grouping,
        groupKey = item.groupKey,
        feedTitle = item.feedTitle,
        labels = rememberGroupHeaderLabels(),
        locale = LocalConfiguration.current.locales.get(0),
    )

/**
 * The header shown for a group, from the group key the database computed for the article.
 */
internal fun groupHeaderText(
    grouping: FeedListGrouping,
    groupKey: String,
    feedTitle: String,
    labels: GroupHeaderLabels,
    locale: Locale,
): String =
    when (grouping) {
        FeedListGrouping.NONE -> ""
        FeedListGrouping.DATE ->
            DateBucketKey.parse(groupKey)?.let { dateHeaderText(it, labels, locale) } ?: groupKey
        FeedListGrouping.FEED -> feedTitle.ifBlank { labels.noFeed }
        FeedListGrouping.TAG -> groupKey.ifBlank { labels.untagged }
        FeedListGrouping.LENGTH ->
            when (groupKey) {
                "0" -> labels.quickReads
                "1" -> labels.mediumReads
                else -> labels.longReads
            }
        FeedListGrouping.SITE -> groupKey.ifBlank { labels.unknownSite }
    }

/**
 * Header of a Mail-style date group. Today and Yesterday also show the date; months of this
 * year show the month name and older months add the year.
 */
internal fun dateHeaderText(
    key: DateBucketKey,
    labels: GroupHeaderLabels,
    locale: Locale,
): String {
    val day = { DateTimeFormatter.ofPattern("EEE, d MMM", locale).format(key.start) }
    return when (key.bucket) {
        DateBucket.TODAY -> "${labels.today} · ${day()}"
        DateBucket.YESTERDAY -> "${labels.yesterday} · ${day()}"
        DateBucket.THIS_WEEK -> labels.thisWeek
        DateBucket.LAST_WEEK -> labels.lastWeek
        DateBucket.THIS_MONTH -> labels.earlierThisMonth
        DateBucket.LAST_MONTH -> labels.lastMonth
        DateBucket.MONTH -> DateTimeFormatter.ofPattern("LLLL", locale).format(key.start)
        DateBucket.OLDER_MONTH -> DateTimeFormatter.ofPattern("LLLL yyyy", locale).format(key.start)
    }
}

/**
 * Section header shown above the first article of each group: a full-width shaded band with
 * the group's name, its unread count, a mark-as-read button and a chevron. Tapping it collapses
 * or expands the group; a long press collapses or expands every group.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FeedListGroupHeader(
    text: String,
    collapsed: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    unreadCount: Int = 0,
    onMarkGroupAsRead: () -> Unit = {},
    onToggleAll: () -> Unit = {},
) {
    val expandedString = stringResource(R.string.group_expanded)
    val collapsedString = stringResource(R.string.group_collapsed)
    val toggleAllLabel = stringResource(R.string.group_toggle_all)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .combinedClickable(
                    onClick = onToggle,
                    onLongClick = onToggleAll,
                    onLongClickLabel = toggleAllLabel,
                ).padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp)
                    .safeSemantics(mergeDescendants = true) {
                        heading()
                        stateDescription = if (collapsed) collapsedString else expandedString
                        role = Role.Button
                    },
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (unreadCount > 0) {
                Text(
                    text = stringResource(R.string.group_unread_count, unreadCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
        }
        if (unreadCount > 0) {
            IconButton(onClick = onMarkGroupAsRead) {
                Icon(
                    Icons.Default.DoneAll,
                    contentDescription = stringResource(R.string.group_mark_as_read, text),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            if (collapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

private val SortingOptions.shortStringId: Int
    get() =
        when (this) {
            SortingOptions.NEWEST_FIRST -> R.string.sort_newest_short
            SortingOptions.OLDEST_FIRST -> R.string.sort_oldest_short
            SortingOptions.SHORTEST_FIRST -> R.string.sort_shortest_short
            SortingOptions.LONGEST_FIRST -> R.string.sort_longest_short
        }

private val FeedListGrouping.shortStringId: Int
    get() =
        when (this) {
            FeedListGrouping.LENGTH -> R.string.group_by_length_short
            else -> stringId
        }

private val FeedListShow.stringId: Int
    get() =
        when (this) {
            FeedListShow.UNREAD -> R.string.show_unread
            FeedListShow.ALL -> R.string.show_all
            FeedListShow.SAVED -> R.string.show_saved
            FeedListShow.DOWNLOADED -> R.string.show_downloaded
        }

/** Order of the group options in the sort and group sheet. */
private val GROUPING_ORDER =
    listOf(
        FeedListGrouping.NONE,
        FeedListGrouping.DATE,
        FeedListGrouping.FEED,
        FeedListGrouping.TAG,
        FeedListGrouping.SITE,
        FeedListGrouping.LENGTH,
    )

/**
 * The one line of filters under the top bar: a Show chip (Unread, All, Saved, Downloaded in a
 * menu), the Today chip and a Sort and group chip that opens a bottom sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedListFilterBar(
    filter: FeedListFilter,
    filterCallback: FeedListFilterCallback,
    grouping: FeedListGrouping,
    sorting: SortingOptions,
    onSelectGrouping: (FeedListGrouping) -> Unit,
    onSelectSorting: (SortingOptions) -> Unit,
    modifier: Modifier = Modifier,
    showTabs: Boolean = true,
    unreadCount: Int = 0,
    downloadedCount: Int = 0,
    offlineMode: Boolean = false,
) {
    var showSheet by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        // Material 3 filter chips on one line: Show (a menu instead of four cut-off tabs), Today,
        // Sort and group. Offline mode is turned on from the ⋮ menu and shows here only while on.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
        ) {
            if (showTabs) {
                ShowMenuChip(
                    current = filter.show,
                    onSelect = filterCallback::setShow,
                    countOf = { option ->
                        when (option) {
                            FeedListShow.UNREAD -> unreadCount
                            FeedListShow.DOWNLOADED -> downloadedCount
                            else -> 0
                        }
                    },
                )
            }
            if (offlineMode) {
                FilterChip(
                    selected = true,
                    onClick = { filterCallback.setOfflineMode(false) },
                    label = { Text(stringResource(R.string.offline_mode), maxLines = 1) },
                    leadingIcon = {
                        Icon(Icons.Default.CloudOff, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
                    },
                    trailingIcon = {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
                    },
                )
            }
            FilterChip(
                selected = filter.today,
                onClick = { filterCallback.setToday(!filter.today) },
                label = { Text(stringResource(R.string.today)) },
                leadingIcon =
                    if (filter.today) {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                    } else {
                        null
                    },
            )
            val sortLabel = stringResource(sorting.shortStringId)
            val groupLabel = stringResource(grouping.shortStringId)
            val buttonDescription = stringResource(R.string.sort_and_group)
            AssistChip(
                onClick = { showSheet = true },
                label = { Text("$sortLabel · $groupLabel", maxLines = 1) },
                leadingIcon = {
                    Icon(
                        Icons.Default.SwapVert,
                        contentDescription = null,
                        modifier = Modifier.size(AssistChipDefaults.IconSize),
                    )
                },
                modifier =
                    Modifier.safeSemantics {
                        contentDescription = "$buttonDescription: $sortLabel, $groupLabel"
                    },
            )
        }
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            ) {
                SheetChoiceChips(
                    title = stringResource(R.string.sort),
                    options = SortingOptions.entries,
                    selected = sorting,
                    label = { stringResource(it.shortStringId) },
                    onSelect = onSelectSorting,
                )
                SheetChoiceChips(
                    title = stringResource(R.string.group_by),
                    options = GROUPING_ORDER,
                    selected = grouping,
                    label = { stringResource(it.shortStringId) },
                    onSelect = onSelectGrouping,
                )
            }
        }
    }
}

/** The Show filter as one chip with a menu, so long labels and counts never get cut off. */
@Composable
private fun ShowMenuChip(
    current: FeedListShow,
    onSelect: (FeedListShow) -> Unit,
    countOf: (FeedListShow) -> Int,
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = stringResource(current.stringId)
    val currentCount = countOf(current)
    val showLabel = stringResource(R.string.filter_show)
    Box {
        FilterChip(
            // Unread is the usual view; any other choice is highlighted as an active filter
            selected = current != FeedListShow.UNREAD,
            onClick = { expanded = true },
            label = {
                Text(
                    if (currentCount > 0) stringResource(R.string.filter_show_with_count, currentLabel, currentCount) else currentLabel,
                    maxLines = 1,
                )
            },
            trailingIcon = {
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
            },
            modifier =
                Modifier.safeSemantics {
                    contentDescription = "$showLabel: $currentLabel"
                    role = Role.DropdownList
                },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            FeedListShow.entries.forEach { option ->
                val count = countOf(option)
                DropdownMenuItem(
                    text = { Text(stringResource(option.stringId)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                    leadingIcon =
                        if (option == current) {
                            { Icon(Icons.Default.Check, contentDescription = null) }
                        } else {
                            { Spacer(Modifier.size(24.dp)) }
                        },
                    trailingIcon = if (count > 0) ({ Text(count.toString(), style = MaterialTheme.typography.labelLarge) }) else null,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> SheetChoiceChips(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedString = stringResource(androidx.compose.ui.R.string.selected)
    val notSelectedString = stringResource(androidx.compose.ui.R.string.not_selected)
    Column(modifier = modifier.padding(vertical = 8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier
                    .padding(bottom = 8.dp)
                    .safeSemantics { heading() },
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                val isSelected = option == selected
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelect(option) },
                    label = { Text(label(option)) },
                    leadingIcon =
                        if (isSelected) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                        } else {
                            null
                        },
                    modifier =
                        Modifier.safeSemantics {
                            stateDescription = if (isSelected) selectedString else notSelectedString
                            role = Role.RadioButton
                        },
                )
            }
        }
    }
}
