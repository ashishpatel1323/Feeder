package com.nononsenseapps.feeder.ui.compose.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.model.formatHnPoints

/**
 * Inoreader-style popularity badge: a trending arrow and the Hacker News points, like "1.2k".
 */
@Composable
fun HnScoreBadge(
    points: Int,
    style: TextStyle,
    modifier: Modifier = Modifier,
    iconSize: Dp = 16.dp,
) {
    val description = pluralStringResource(R.plurals.hn_points_description, points, points)
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Text(
            text = formatHnPoints(points),
            style = style,
            maxLines = 1,
        )
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.TrendingUp,
            contentDescription = null,
            modifier = Modifier.size(iconSize),
        )
    }
}
