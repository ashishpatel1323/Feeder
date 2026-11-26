package com.nononsenseapps.feeder.ui.compose.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.ui.compose.navigation.GlobalSearchTopBar

/** The Library tab: what you kept (Saved, Highlights, Downloads), books and offline sites, then Settings and Sync. */
@Composable
fun LibraryScreen(
    onOpenSaved: () -> Unit,
    onHighlights: () -> Unit,
    onDownloads: () -> Unit,
    onEbooks: () -> Unit,
    onAudiobooks: () -> Unit,
    onZim: () -> Unit,
    onSettings: () -> Unit,
    onSync: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)),
        contentWindowInsets = WindowInsets(0),
        topBar = { GlobalSearchTopBar() },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item { Row(Icons.Outlined.BookmarkBorder, stringResource(R.string.saved_articles), stringResource(R.string.library_saved_desc), onOpenSaved) }
            item { Row(Icons.Outlined.BorderColor, stringResource(R.string.highlights), stringResource(R.string.library_highlights_desc), onHighlights) }
            item { Row(Icons.Outlined.Download, stringResource(R.string.downloads), stringResource(R.string.library_downloads_desc), onDownloads) }
            item { Row(Icons.AutoMirrored.Outlined.MenuBook, stringResource(R.string.tab_ebooks), stringResource(R.string.library_ebooks_desc), onEbooks) }
            item { Row(Icons.Outlined.Headphones, stringResource(R.string.tab_audiobooks), stringResource(R.string.library_audiobooks_desc), onAudiobooks) }
            item { Row(Icons.Outlined.Public, stringResource(R.string.tab_zim), stringResource(R.string.library_zim_desc), onZim) }
            item {
                Text(
                    stringResource(R.string.library_app),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
                )
            }
            item { Row(Icons.Outlined.Settings, stringResource(R.string.action_settings), null, onSettings) }
            item { Row(Icons.Outlined.Sync, stringResource(R.string.device_sync), null, onSync) }
        }
    }
}

@Composable
private fun Row(
    icon: ImageVector,
    title: String,
    description: String?,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = description?.let { { Text(it) } },
        leadingContent = { Icon(icon, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
