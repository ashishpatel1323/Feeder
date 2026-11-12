package com.nononsenseapps.feeder.ui.compose.feed

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.archmodel.FeedListGrouping
import com.nononsenseapps.feeder.archmodel.SortingOptions
import com.nononsenseapps.feeder.ui.compose.components.safeSemantics
import com.nononsenseapps.feeder.ui.compose.feedarticle.FeedListFilter
import com.nononsenseapps.feeder.ui.compose.feedarticle.FeedListFilterCallback
import com.nononsenseapps.feeder.ui.compose.feedarticle.onlyUnread
import com.nononsenseapps.feeder.ui.compose.utils.onKeyEventLikeEscape
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.util.Locale

private val FeedListGrouping.icon: ImageVector
    get() =
        when (this) {
            FeedListGrouping.NONE -> Icons.Default.ViewAgenda
            FeedListGrouping.DATE -> Icons.Default.CalendarToday
            FeedListGrouping.FEED -> Icons.Default.RssFeed
            FeedListGrouping.TAG -> Icons.AutoMirrored.Filled.Label
            FeedListGrouping.LENGTH -> Icons.Default.Timer
            FeedListGrouping.STATUS -> Icons.Default.MarkEmailUnread
            FeedListGrouping.SITE -> Icons.Default.Public
        }

/**
 * Top bar button holding the list's view options: how it is grouped and how it is sorted. The
 * icon shows the current grouping, so it is visible at a glance without opening anything.
 */
@Composable
fun FeedListGroupingButton(
    current: FeedListGrouping,
    onSelect: (FeedListGrouping) -> Unit,
    modifier: Modifier = Modifier,
    currentSorting: SortingOptions = SortingOptions.NEWEST_FIRST,
    onSelectSorting: (SortingOptions) -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    val label = stringResource(R.string.group_by)

    PlainTooltipBox(tooltip = { Text(label) }, modifier = modifier) {
        Box {
            IconButton(onClick = { expanded = true }) {
                Icon(
                    current.icon,
                    contentDescription = "$label: ${stringResource(current.stringId)}",
                    tint =
                        when (current) {
                            FeedListGrouping.NONE -> LocalContentColor.current
                            else -> MaterialTheme.colorScheme.primary
                        },
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.onKeyEventLikeEscape { expanded = false },
            ) {
                MenuSectionTitle(label)
                FeedListGrouping.entries.forEach { option ->
                    RadioMenuItem(
                        text = stringResource(option.stringId),
                        selected = option == current,
                        trailingIcon = option.icon,
                        onClick = {
                            onSelect(option)
                            // Closing it is important for accessibility
                            expanded = false
                        },
                    )
                }
                HorizontalDivider()
                MenuSectionTitle(stringResource(R.string.sort))
                SortingOptions.entries.forEach { option ->
                    RadioMenuItem(
                        text = stringResource(option.stringId),
                        selected = option == currentSorting,
                        trailingIcon = null,
                        onClick = {
                            onSelectSorting(option)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .safeSemantics { heading() },
    )
}

@Composable
private fun RadioMenuItem(
    text: String,
    selected: Boolean,
    trailingIcon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedString = stringResource(androidx.compose.ui.R.string.selected)
    val notSelectedString = stringResource(androidx.compose.ui.R.string.not_selected)
    DropdownMenuItem(
        onClick = onClick,
        leadingIcon = {
            Icon(
                if (selected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
            )
        },
        trailingIcon =
            if (trailingIcon != null) {
                { Icon(trailingIcon, contentDescription = null) }
            } else {
                null
            },
        text = { Text(text) },
        modifier =
            modifier.safeSemantics {
                stateDescription = if (selected) selectedString else notSelectedString
                role = Role.RadioButton
            },
    )
}

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
    val untagged: String,
    val quickReads: String,
    val mediumReads: String,
    val longReads: String,
    val unread: String,
    val saved: String,
    val read: String,
    val unknownSite: String,
    val noFeed: String,
)

@Composable
fun rememberGroupHeaderLabels(): GroupHeaderLabels =
    GroupHeaderLabels(
        today = stringResource(R.string.today),
        yesterday = stringResource(R.string.yesterday),
        untagged = stringResource(R.string.group_untagged),
        quickReads = stringResource(R.string.group_quick_reads),
        mediumReads = stringResource(R.string.group_medium_reads),
        longReads = stringResource(R.string.group_long_reads),
        unread = stringResource(R.string.group_status_unread),
        saved = stringResource(R.string.group_status_saved),
        read = stringResource(R.string.group_status_read),
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
    zoneId: ZoneId = ZoneId.systemDefault(),
    now: LocalDate = LocalDate.now(zoneId),
): String =
    when (grouping) {
        FeedListGrouping.NONE -> ""
        FeedListGrouping.DATE ->
            try {
                dateHeaderText(
                    date = LocalDate.parse(groupKey),
                    today = labels.today,
                    yesterday = labels.yesterday,
                    locale = locale,
                    now = now,
                )
            } catch (_: DateTimeParseException) {
                groupKey
            }
        FeedListGrouping.FEED -> feedTitle.ifBlank { labels.noFeed }
        FeedListGrouping.TAG -> groupKey.ifBlank { labels.untagged }
        FeedListGrouping.LENGTH ->
            when (groupKey) {
                "0" -> labels.quickReads
                "1" -> labels.mediumReads
                else -> labels.longReads
            }
        FeedListGrouping.STATUS ->
            when (groupKey) {
                "0" -> labels.unread
                "1" -> labels.saved
                else -> labels.read
            }
        FeedListGrouping.SITE -> groupKey.ifBlank { labels.unknownSite }
    }

internal fun dateHeaderText(
    date: LocalDate,
    today: String,
    yesterday: String,
    locale: Locale,
    now: LocalDate = LocalDate.now(),
): String =
    when {
        date == now -> today
        date == now.minusDays(1) -> yesterday
        // Within the last week the weekday reads better than a date
        date.isAfter(now.minusDays(7)) && !date.isAfter(now) ->
            DateTimeFormatter.ofPattern("EEEE", locale).format(date)
        else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale).format(date)
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

/**
 * Always visible row of quick filters under the top bar.
 */
@Composable
fun FeedListFilterChips(
    filter: FeedListFilter,
    filterCallback: FeedListFilterCallback,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        FilterChip(
            selected = filter.onlyUnread,
            onClick = { filterCallback.setOnlyUnread() },
            label = { Text(stringResource(R.string.filter_chip_unread_only)) },
        )
        FilterChip(
            selected = filter.saved,
            onClick = { filterCallback.setSaved(!filter.saved) },
            label = { Text(stringResource(R.string.saved_adjective)) },
        )
        FilterChip(
            selected = filter.today,
            onClick = { filterCallback.setToday(!filter.today) },
            label = { Text(stringResource(R.string.today)) },
        )
        FilterChip(
            selected = filter.shortReads,
            onClick = { filterCallback.setShortReads(!filter.shortReads) },
            label = { Text(stringResource(R.string.filter_chip_short_reads)) },
        )
        FilterChip(
            selected = filter.read,
            onClick = { filterCallback.setRead(!filter.read) },
            label = { Text(stringResource(R.string.read_adjective)) },
        )
    }
}
