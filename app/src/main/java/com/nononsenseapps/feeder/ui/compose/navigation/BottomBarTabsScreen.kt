package com.nononsenseapps.feeder.ui.compose.navigation

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.ui.MainActivityViewModel
import com.nononsenseapps.feeder.ui.compose.theme.FeederTokens

/** Settings > Bottom bar tabs. */
data object BottomBarTabsDestination : NavigationDestination(
    path = "settings/bottombar",
    navArguments = emptyList(),
    deepLinks = emptyList(),
) {
    fun navigate(navController: NavController) {
        navController.navigate(path) { launchSingleTop = true }
    }

    @Composable
    override fun RegisterScreen(
        navController: NavController,
        backStackEntry: NavBackStackEntry,
        navDrawerListState: LazyListState,
        mainActivityViewModel: MainActivityViewModel,
    ) {
        BottomBarTabsScreen(onNavigateUp = { navController.popBackStack() })
    }
}

/**
 * Picks up to [BottomBarTabs.MAX] tabs for the bottom bar and their order (drag the handle, or
 * TalkBack's move up/down). Feeds and Library can't be turned off, so everything stays reachable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomBarTabsScreen(
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val saved = remember { BottomBarTabs.tabs(context).value ?: BottomBarTabs.DEFAULT }
    // Every tab in display order: the bar's first, then the rest
    var order by remember { mutableStateOf(saved + MainTab.entries.filter { it !in saved }) }
    var on by remember { mutableStateOf(saved.toSet()) }

    fun save() = BottomBarTabs.set(context, order.filter { it in on })

    var dragging by remember { mutableStateOf<MainTab?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val listState = rememberLazyListState()
    val moveUp = stringResource(R.string.bottom_bar_move_up)
    val moveDown = stringResource(R.string.bottom_bar_move_down)

    fun move(
        tab: MainTab,
        by: Int,
    ) {
        val i = order.indexOf(tab)
        val j = (i + by).coerceIn(0, order.lastIndex)
        if (i == j) return
        order = order.toMutableList().apply { add(j, removeAt(i)) }
        save()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bottom_bar_tabs)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.go_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(
                stringResource(R.string.bottom_bar_tabs_help, BottomBarTabs.MAX),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = FeederTokens.Space.lg, vertical = FeederTokens.Space.sm),
            )
            LazyColumn(state = listState) {
                itemsIndexed(order, key = { _, t -> t.name }) { index, tab ->
                    val (_, outlined, label) = tab.look()
                    val always = tab in BottomBarTabs.ALWAYS
                    val checked = tab in on
                    val full = on.size >= BottomBarTabs.MAX
                    val isDragged = dragging == tab
                    ListItem(
                        leadingContent = { Icon(outlined, contentDescription = null) },
                        headlineContent = { Text(stringResource(label)) },
                        supportingContent =
                            if (always) {
                                { Text(stringResource(R.string.bottom_bar_always)) }
                            } else {
                                null
                            },
                        trailingContent = {
                            androidx.compose.foundation.layout.Row {
                                Checkbox(
                                    checked = checked,
                                    enabled = !always && (checked || !full),
                                    onCheckedChange = { c ->
                                        on = if (c) on + tab else on - tab
                                        save()
                                    },
                                )
                                Icon(
                                    Icons.Default.DragHandle,
                                    contentDescription = stringResource(R.string.bottom_bar_reorder),
                                    modifier =
                                        Modifier
                                            .padding(start = FeederTokens.Space.sm, top = 12.dp)
                                            .pointerInput(tab) {
                                                detectDragGesturesAfterLongPress(
                                                    onDragStart = {
                                                        dragging = tab
                                                        dragOffset = 0f
                                                    },
                                                    onDragEnd = {
                                                        dragging = null
                                                        dragOffset = 0f
                                                    },
                                                    onDragCancel = {
                                                        dragging = null
                                                        dragOffset = 0f
                                                    },
                                                    onDrag = { change, amount ->
                                                        change.consume()
                                                        dragOffset += amount.y
                                                        val height =
                                                            listState.layoutInfo.visibleItemsInfo
                                                                .firstOrNull { it.key == tab.name }
                                                                ?.size
                                                                ?.toFloat() ?: return@detectDragGesturesAfterLongPress
                                                        if (dragOffset > height / 2) {
                                                            move(tab, 1)
                                                            dragOffset -= height
                                                        } else if (dragOffset < -height / 2) {
                                                            move(tab, -1)
                                                            dragOffset += height
                                                        }
                                                    },
                                                )
                                            },
                                )
                            }
                        },
                        colors =
                            if (isDragged) {
                                ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                            } else {
                                ListItemDefaults.colors()
                            },
                        modifier =
                            Modifier
                                .graphicsLayer { translationY = if (isDragged) dragOffset else 0f }
                                .semantics {
                                    customActions =
                                        listOfNotNull(
                                            if (index > 0) {
                                                CustomAccessibilityAction(moveUp) {
                                                    move(tab, -1)
                                                    true
                                                }
                                            } else {
                                                null
                                            },
                                            if (index < order.lastIndex) {
                                                CustomAccessibilityAction(moveDown) {
                                                    move(tab, 1)
                                                    true
                                                }
                                            } else {
                                                null
                                            },
                                        )
                                },
                    )
                }
            }
        }
    }
}
