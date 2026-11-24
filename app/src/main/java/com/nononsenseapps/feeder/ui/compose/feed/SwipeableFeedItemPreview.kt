package com.nononsenseapps.feeder.ui.compose.feed

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.archmodel.SwipeAsRead
import com.nononsenseapps.feeder.model.playlists.ItemKind
import com.nononsenseapps.feeder.model.playlists.itemSeconds
import com.nononsenseapps.feeder.ui.compose.components.safeSemantics
import com.nononsenseapps.feeder.ui.compose.feedarticle.FeedListFilter
import com.nononsenseapps.feeder.ui.compose.feedarticle.onlyUnread
import com.nononsenseapps.feeder.ui.compose.item.ItemRow
import com.nononsenseapps.feeder.ui.compose.item.ItemRowActions
import com.nononsenseapps.feeder.ui.compose.item.ItemRowModel
import com.nononsenseapps.feeder.ui.compose.item.ageText
import com.nononsenseapps.feeder.ui.compose.item.markReadIcon
import com.nononsenseapps.feeder.ui.compose.item.markReadLabel
import com.nononsenseapps.feeder.ui.compose.item.saveIcon
import com.nononsenseapps.feeder.ui.compose.item.rememberItemPlayer
import com.nononsenseapps.feeder.ui.compose.item.rememberItemProgress
import com.nononsenseapps.feeder.ui.compose.theme.LocalDimens
import com.nononsenseapps.feeder.util.logDebug
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val LOG_TAG = "FEEDER_SWIPEITEM"

/**
 * OnSwipe takes a boolean parameter of the current read state of the item - so that it can be
 * called multiple times by several DisposableEffects.
 */
@Composable
fun SwipeableFeedItemPreview(
    onSwipe: (Boolean) -> Unit,
    filter: FeedListFilter,
    item: FeedListItem,
    showThumbnail: Boolean,
    swipeAsRead: SwipeAsRead,
    onMarkAboveAsRead: () -> Unit,
    onMarkBelowAsRead: () -> Unit,
    onToggleBookmark: () -> Unit,
    onShareItem: () -> Unit,
    onItemClick: () -> Unit,
    onOpenFeedItemInReader: () -> Unit,
    onOpenFeedItemInCustomTab: () -> Unit,
    onOpenFeedItemInBrowser: () -> Unit,
    modifier: Modifier = Modifier,
    swipeEnabled: Boolean = true,
    // Offered as a TalkBack action; deleting is otherwise in the selection bar and on the article screen
    onDelete: (() -> Unit)? = null,
    // Multi-select: "Select" on the row (or a long press) starts it, then taps add or remove articles
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: (() -> Unit)? = null,
) {
    val coroutineScope = rememberCoroutineScope()
    val addToPlaylist = com.nononsenseapps.feeder.ui.compose.playlists.LocalAddToPlaylist.current
    // Where the row's thumbnail and text are, so the swipe layer covers them and not the buttons
    var contentLeft by remember { mutableIntStateOf(0) }
    var contentTop by remember { mutableIntStateOf(0) }
    var contentWidth by remember { mutableIntStateOf(0) }
    var contentHeight by remember { mutableIntStateOf(0) }
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val anchoredDraggableState = remember { AnchoredDraggableState(FeedItemSwipeState.CENTER) }
    val onSwipeCallback by rememberUpdatedState(newValue = onSwipe)
    val onSaveCallback by rememberUpdatedState(newValue = onToggleBookmark)

    // Swiping toward the end (right in left-to-right languages) saves; toward the start marks read
    val swipingToSave by remember {
        derivedStateOf { anchoredDraggableState.offset > 0 }
    }

    val color by animateColorAsState(
        targetValue =
            when {
                swipingToSave -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.secondaryContainer
            },
        label = "swipeBackground",
    )

    LaunchedEffect(filter, item.unread) {
        // critical state changes - reset ui state
        if (anchoredDraggableState.currentValue != FeedItemSwipeState.CENTER) {
            anchoredDraggableState.animateTo(FeedItemSwipeState.CENTER)
        }
    }

    var skipHapticFeedback by remember { mutableStateOf(false) }
    LaunchedEffect(anchoredDraggableState.settledValue) {
        if (anchoredDraggableState.settledValue == FeedItemSwipeState.END) {
            logDebug(LOG_TAG, "onSave ${item.id}")
            onSaveCallback()
            skipHapticFeedback = true
            anchoredDraggableState.animateTo(FeedItemSwipeState.CENTER)
        } else if (anchoredDraggableState.settledValue != FeedItemSwipeState.CENTER) {
            logDebug(LOG_TAG, "onSwipe ${item.unread}")
            if (!filter.onlyUnread) {
                skipHapticFeedback = true
                anchoredDraggableState.animateTo(FeedItemSwipeState.CENTER)
            }

            onSwipeCallback(item.unread)
        }
    }

    val swipeIconAlignment by remember {
        derivedStateOf {
            when {
                anchoredDraggableState.offset > 0 -> Alignment.CenterStart
                else -> Alignment.CenterEnd
            }
        }
    }

    val toggleReadStatusLabel = stringResource(R.string.toggle_read_status)
    val openFeedItemInReaderLabel = stringResource(R.string.open_article_in_reader)
    val openFeedItemInCustomTabLabel = stringResource(R.string.open_article_in_custom_tab)
    val openFeedItemInBrowserLabel = stringResource(R.string.open_article_in_default_browser)
    val saveArticleLabel = stringResource(R.string.save_article)
    val unSaveArticleLabel = stringResource(R.string.unsave_article)
    val markAboveAsReadLabel = stringResource(R.string.mark_items_above_as_read)
    val markBelowAsReadLabel = stringResource(R.string.mark_items_below_as_read)
    val shareLabel = stringResource(R.string.share)
    val deleteLabel = stringResource(R.string.swipe_to_delete_article)

    val unreadLabel = stringResource(R.string.unread_adjective)
    val alreadyReadLabel = stringResource(R.string.read_adjective)
    val readStatusLabel by remember(item.unread) {
        derivedStateOf {
            if (item.unread) {
                unreadLabel
            } else {
                alreadyReadLabel
            }
        }
    }

    val dimens = LocalDimens.current

    BoxWithConstraints(
        modifier =
            modifier
                .width(dimens.maxContentWidth)
                .clipToBounds()
                .combinedClickable(
                    // Long press starts multi-select (bulk actions and Delete are in the selection bar)
                    onLongClick = onToggleSelect,
                    onClick = {
                        if (selectionMode && onToggleSelect != null) {
                            onToggleSelect()
                        } else {
                            onItemClick()
                        }
                    },
                ).safeSemantics {
                    stateDescription = readStatusLabel
                    customActions =
                        listOf(
                            CustomAccessibilityAction(toggleReadStatusLabel) {
                                coroutineScope.launch {
                                    onSwipe(item.unread)
                                }
                                true
                            },
                            CustomAccessibilityAction(openFeedItemInReaderLabel) {
                                onOpenFeedItemInReader()
                                true
                            },
                            CustomAccessibilityAction(openFeedItemInCustomTabLabel) {
                                onOpenFeedItemInCustomTab()
                                true
                            },
                            CustomAccessibilityAction(openFeedItemInBrowserLabel) {
                                onOpenFeedItemInBrowser()
                                true
                            },
                            CustomAccessibilityAction(
                                when (item.bookmarked) {
                                    true -> unSaveArticleLabel
                                    false -> saveArticleLabel
                                },
                            ) {
                                onToggleBookmark()
                                true
                            },
                            CustomAccessibilityAction(markAboveAsReadLabel) {
                                onMarkAboveAsRead()
                                true
                            },
                            CustomAccessibilityAction(markBelowAsReadLabel) {
                                onMarkBelowAsRead()
                                true
                            },
                            CustomAccessibilityAction(shareLabel) {
                                onShareItem()
                                true
                            },
                        ) +
                        listOfNotNull(
                            onDelete?.let { delete ->
                                CustomAccessibilityAction(deleteLabel) {
                                    delete()
                                    true
                                }
                            },
                        )
                },
    ) {
        val maxWidthPx =
            with(LocalDensity.current) {
                maxWidth.toPx()
            }

        val hapticFeedback = LocalHapticFeedback.current
        LaunchedEffect(anchoredDraggableState.currentValue) {
            try {
                if (anchoredDraggableState.requireOffset() == 0f) {
                    return@LaunchedEffect
                }
            } catch (_: IllegalStateException) {
                return@LaunchedEffect
            }

            if (skipHapticFeedback) {
                skipHapticFeedback = false
                return@LaunchedEffect
            }

            hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
        }

        val alpha by animateFloatAsState(
            targetValue = if (anchoredDraggableState.currentValue != FeedItemSwipeState.CENTER) 1.0f else 0.2f,
            animationSpec = tween(),
            label = "alphaAnimation",
        )

        Box(
            contentAlignment = swipeIconAlignment,
            modifier =
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        this.alpha = alpha
                    }.drawBehind {
                        drawRect(color = color)
                    }.padding(horizontal = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when {
                        swipingToSave -> saveIcon(item.bookmarked)
                        else -> markReadIcon(!item.unread)
                    },
                    contentDescription = null,
                    tint = if (swipingToSave) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        swipingToSave -> stringResource(if (item.bookmarked) R.string.swipe_to_unsave else R.string.swipe_to_save)
                        else -> markReadLabel(item.kind, !item.unread)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (swipingToSave) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }

        val rowOffset =
            Modifier.offset {
                try {
                    IntOffset(anchoredDraggableState.requireOffset().roundToInt(), 0)
                } catch (_: IllegalStateException) {
                    IntOffset(0, 0)
                }
            }
        val progress = rememberItemProgress(item.id, item.videoKey)
        val kind = item.kind
        val itemPlayer = rememberItemPlayer()
        val playingHere = itemPlayer.isPlaying(item.id)
        ItemRow(
            model =
                ItemRowModel(
                    id = item.id,
                    title = item.title.ifBlank { item.snippet },
                    feedTitle = item.feedTitle,
                    age = remember(item.rawPubDate) { ageText(item.rawPubDate?.toInstant()?.toEpochMilli()) }.ifBlank { item.pubDate },
                    kind = kind,
                    lengthSeconds =
                        itemSeconds(
                            kind = kind,
                            wordCount = item.wordCount,
                            enclosureDuration = item.enclosureDuration,
                            positionLength = progress?.lengthSeconds,
                        ),
                    read = !item.unread,
                    saved = item.bookmarked,
                    image = if (showThumbnail) item.image?.url else null,
                    progress = progress,
                ),
            actions =
                ItemRowActions(
                    onPrimary = {
                        when {
                            selectionMode && onToggleSelect != null -> onToggleSelect()
                            kind == ItemKind.PODCAST && item.enclosureLink != null ->
                                itemPlayer.togglePodcast(item.id, item.enclosureLink, progress)
                            else -> onOpenFeedItemInReader()
                        }
                    },
                    onToggleSave = onToggleBookmark,
                    onPlaylist = addToPlaylist?.let { add -> { add(item.id, item.title) } },
                    onToggleRead = { onSwipe(item.unread) },
                    onDelete = onDelete,
                ),
            primaryLabel = if (playingHere) stringResource(R.string.item_action_pause) else null,
            primaryIcon = if (playingHere) Icons.Default.Pause else null,
            onContentLayout = { left, top, width, height ->
                contentLeft = left
                contentTop = top
                contentWidth = width
                contentHeight = height
            },
            // The parent below offers them (with Share, Read above/below…)
            accessibilityActions = false,
            modifier =
                rowOffset
                    .background(MaterialTheme.colorScheme.background),
        )

        if (selected) {
            Box(
                contentAlignment = Alignment.TopEnd,
                modifier =
                    Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                        .border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium)
                        .padding(8.dp),
            ) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // This box handles swiping - it uses padding to allow the nav drawer to still be dragged
        // It's very important that clickable stuff is handled by its parent - or a direct child
        // Wrapped in an outer box to get the height set properly
        // Only over the thumbnail and text: the buttons below take their own taps
        if (swipeAsRead != SwipeAsRead.DISABLED && contentHeight > 0) {
            val density = LocalDensity.current
            Box(
                modifier =
                    Modifier
                        .padding(
                            start = with(density) { contentLeft.toDp() },
                            top = with(density) { contentTop.toDp() },
                        ).width(with(density) { contentWidth.toDp() })
                        .height(with(density) { contentHeight.toDp() }),
            ) {
                Box(
                    modifier =
                        Modifier
                            .run {
                                @Suppress("KotlinConstantConditions")
                                when (swipeAsRead) {
                                    // This never actually gets called due to outer if
                                    SwipeAsRead.DISABLED ->
                                        this
                                            .height(0.dp)
                                            .width(0.dp)

                                    // The end half of the title area, so a short swipe right (Save) also fits
                                    SwipeAsRead.ONLY_FROM_END -> {
                                        this
                                            .fillMaxHeight()
                                            .fillMaxWidth(0.5f)
                                            .align(Alignment.CenterEnd)
                                    }

                                    SwipeAsRead.FROM_ANYWHERE -> {
                                        this
                                            .padding(start = 48.dp)
                                            .matchParentSize()
                                    }
                                }
                            }.anchoredDraggable(
                                state = anchoredDraggableState,
                                orientation = Orientation.Horizontal,
                                reverseDirection = isRtl,
                                enabled = swipeEnabled && !selectionMode,
                            ),
                )

                // Dividing the maxWidth by 2 means you only have to swipe a quarter of the screen width to reach the swipe threshold, instead of a full half of the screen by default.
                LaunchedEffect(swipeAsRead) {
                    anchoredDraggableState.updateAnchors(
                        DraggableAnchors {
                            if (swipeAsRead == SwipeAsRead.ONLY_FROM_END) {
                                FeedItemSwipeState.START at -(maxWidthPx / 2)
                                FeedItemSwipeState.CENTER at 0f
                                FeedItemSwipeState.END at maxWidthPx / 3
                            } else if (swipeAsRead == SwipeAsRead.FROM_ANYWHERE) {
                                FeedItemSwipeState.START at -(maxWidthPx / 2)
                                FeedItemSwipeState.CENTER at 0f
                                FeedItemSwipeState.END at maxWidthPx / 2
                            }
                        },
                    )
                }
            }
        }
    }
}

enum class FeedItemSwipeState {
    CENTER,
    START,
    END,
}
