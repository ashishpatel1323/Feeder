package com.nononsenseapps.feeder.ui.compose.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.nononsenseapps.feeder.ui.compose.components.safeSemantics
import com.nononsenseapps.feeder.ui.compose.utils.onKeyEventLikeEscape
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val FeedListGrouping.icon: ImageVector
    get() =
        when (this) {
            FeedListGrouping.NONE -> Icons.Default.ViewAgenda
            FeedListGrouping.DATE -> Icons.Default.CalendarToday
            FeedListGrouping.FEED -> Icons.Default.RssFeed
        }

/**
 * Top bar button that picks how the article list is split into sections. The icon shows the
 * current choice, so it is visible at a glance without opening anything.
 */
@Composable
fun FeedListGroupingButton(
    current: FeedListGrouping,
    onSelect: (FeedListGrouping) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = stringResource(R.string.group_by)
    val selectedString = stringResource(androidx.compose.ui.R.string.selected)
    val notSelectedString = stringResource(androidx.compose.ui.R.string.not_selected)

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
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .safeSemantics { heading() },
                )
                FeedListGrouping.entries.forEach { option ->
                    val selected = option == current
                    DropdownMenuItem(
                        onClick = {
                            onSelect(option)
                            // Closing it is important for accessibility
                            expanded = false
                        },
                        leadingIcon = {
                            Icon(
                                if (selected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                                contentDescription = null,
                            )
                        },
                        trailingIcon = {
                            Icon(option.icon, contentDescription = null)
                        },
                        text = { Text(stringResource(option.stringId)) },
                        modifier =
                            Modifier.safeSemantics {
                                stateDescription = if (selected) selectedString else notSelectedString
                                role = Role.RadioButton
                            },
                    )
                }
            }
        }
    }
}

/**
 * The section an article belongs to. Equal keys mean the same section.
 */
fun FeedListItem.groupKey(
    grouping: FeedListGrouping,
    zoneId: ZoneId = ZoneId.systemDefault(),
): Any? =
    when (grouping) {
        FeedListGrouping.NONE -> null
        FeedListGrouping.DATE -> primarySortTime.atZone(zoneId).toLocalDate()
        FeedListGrouping.FEED -> feedId
    }

@Composable
fun rememberGroupHeaderText(
    item: FeedListItem,
    grouping: FeedListGrouping,
): String {
    val today = stringResource(R.string.today)
    val yesterday = stringResource(R.string.yesterday)
    val locale = LocalConfiguration.current.locales.get(0)
    return when (grouping) {
        FeedListGrouping.NONE -> ""
        FeedListGrouping.FEED -> item.feedTitle
        FeedListGrouping.DATE ->
            dateHeaderText(
                time = item.primarySortTime,
                today = today,
                yesterday = yesterday,
                locale = locale,
            )
    }
}

internal fun dateHeaderText(
    time: Instant,
    today: String,
    yesterday: String,
    locale: java.util.Locale,
    zoneId: ZoneId = ZoneId.systemDefault(),
    now: LocalDate = LocalDate.now(zoneId),
): String {
    val date = time.atZone(zoneId).toLocalDate()
    return when {
        date == now -> today
        date == now.minusDays(1) -> yesterday
        // Within the last week the weekday reads better than a date
        date.isAfter(now.minusDays(7)) && !date.isAfter(now) ->
            DateTimeFormatter.ofPattern("EEEE", locale).format(date)
        else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale).format(date)
    }
}

/**
 * Section header shown above the first article of each group.
 */
@Composable
fun FeedListGroupHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier =
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .safeSemantics { heading() },
    )
}
