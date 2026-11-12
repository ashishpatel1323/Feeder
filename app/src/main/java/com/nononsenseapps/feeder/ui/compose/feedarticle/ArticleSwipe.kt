package com.nononsenseapps.feeder.ui.compose.feedarticle

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.ui.compose.navigation.ArticleSwipeDirection
import kotlinx.coroutines.launch
import kotlin.math.abs

@Immutable
data class ArticleSwipeNeighbors(
    val previousId: Long? = null,
    val nextId: Long? = null,
)

/**
 * Swipe left for the next article in the list, right for the previous one. The article follows
 * the finger, and a hint behind it says where the swipe goes. Horizontal scrollers inside the
 * article (tables, code) keep their own swipes, and vertical scrolling wins when the finger
 * moves mostly up or down.
 */
@Composable
fun ArticleSwipeContainer(
    neighbors: ArticleSwipeNeighbors,
    onSwipeTo: (itemId: Long, direction: ArticleSwipeDirection) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val currentNeighbors by rememberUpdatedState(neighbors)
    val currentOnSwipeTo by rememberUpdatedState(onSwipeTo)
    var offsetX by remember { mutableFloatStateOf(0f) }
    val coroutineScope = rememberCoroutineScope()
    val flingVelocity = with(LocalDensity.current) { 800.dp.toPx() }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()

        if (offsetX != 0f) {
            val goingNext = offsetX < 0
            val target = if (goingNext) neighbors.nextId else neighbors.previousId
            val label =
                stringResource(
                    when {
                        goingNext && target != null -> R.string.next_article
                        goingNext -> R.string.last_article_in_list
                        target != null -> R.string.previous_article
                        else -> R.string.first_article_in_list
                    },
                )
            val ready = abs(offsetX) > width * SWIPE_THRESHOLD && target != null
            Column(
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier =
                    Modifier
                        .align(if (goingNext) Alignment.CenterEnd else Alignment.CenterStart)
                        .fillMaxHeight()
                        .widthIn(max = 120.dp)
                        .padding(horizontal = 16.dp),
            ) {
                val color =
                    if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                if (target != null) {
                    Icon(
                        if (goingNext) Icons.AutoMirrored.Filled.ArrowForward else Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        tint = color,
                    )
                }
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = color,
                    textAlign = TextAlign.Center,
                )
            }
        }

        Box(
            modifier =
                Modifier
                    .graphicsLayer { translationX = offsetX }
                    // Opaque, so the hint behind only shows where the article has moved away
                    .background(MaterialTheme.colorScheme.background)
                    .pointerInput(enabled, width) {
                        if (!enabled) return@pointerInput
                        val slop = viewConfiguration.touchSlop
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val velocityTracker = VelocityTracker()
                            velocityTracker.addPosition(down.uptimeMillis, down.position)
                            var totalX = 0f
                            var totalY = 0f
                            var dragging = false
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.changes.size > 1) break // pinch zoom and the like
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                val delta = change.positionChange()
                                if (!dragging) {
                                    // Something inside, like a horizontal scroller or text selection, took it
                                    if (change.isConsumed) break
                                    totalX += delta.x
                                    totalY += delta.y
                                    if (abs(totalX) > slop * 1.5f && abs(totalX) > abs(totalY) * 2) {
                                        dragging = true
                                    } else if (abs(totalY) > slop) {
                                        break
                                    } else {
                                        continue
                                    }
                                }
                                change.consume()
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                val hasTarget =
                                    if (offsetX + delta.x < 0) {
                                        currentNeighbors.nextId != null
                                    } else {
                                        currentNeighbors.previousId != null
                                    }
                                // Resist when there is nothing to go to
                                offsetX += if (hasTarget) delta.x else delta.x * 0.3f
                            }

                            if (!dragging) {
                                if (offsetX != 0f) {
                                    coroutineScope.launch { animate(offsetX, 0f, animationSpec = spring()) { v, _ -> offsetX = v } }
                                }
                                return@awaitEachGesture
                            }

                            val velocity = velocityTracker.calculateVelocity().x
                            val goingNext = offsetX < 0
                            val target = if (goingNext) currentNeighbors.nextId else currentNeighbors.previousId
                            val farEnough = abs(offsetX) > width * SWIPE_THRESHOLD
                            val flung = abs(velocity) > flingVelocity && (velocity < 0) == goingNext
                            val start = offsetX
                            coroutineScope.launch {
                                if (target != null && (farEnough || flung)) {
                                    animate(start, if (goingNext) -width else width, animationSpec = tween(150)) { v, _ ->
                                        offsetX = v
                                    }
                                    currentOnSwipeTo(
                                        target,
                                        if (goingNext) ArticleSwipeDirection.NEXT else ArticleSwipeDirection.PREVIOUS,
                                    )
                                } else {
                                    animate(start, 0f, animationSpec = spring()) { v, _ -> offsetX = v }
                                }
                            }
                        }
                    },
        ) {
            content()
        }
    }
}

private const val SWIPE_THRESHOLD = 0.25f
