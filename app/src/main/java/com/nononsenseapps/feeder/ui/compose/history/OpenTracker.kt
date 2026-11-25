package com.nononsenseapps.feeder.ui.compose.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nononsenseapps.feeder.model.history.HistoryWhere
import com.nononsenseapps.feeder.model.history.ItemHistory
import org.kodein.di.compose.LocalDI
import org.kodein.di.direct
import org.kodein.di.instance

/**
 * Notes when a reader is opened and closed (same rules as the web app's trackOpen): [touch] once
 * the place is known (only the first counts), closed when the reader goes away. Leaving the app for
 * [ItemHistory.AWAY_MS] or more counts as closed when it was left, and opened again on coming back.
 */
class OpenTracker(
    private val history: ItemHistory,
    private val c: String,
    private val i: String,
    private val where: () -> HistoryWhere?,
) {
    private var open = false
    private var stoppedAt = 0L
    private var stoppedWhere: HistoryWhere? = null

    fun touch() {
        if (open) return
        val w = where() ?: return
        open = true
        history.record(c, i, ItemHistory.OPEN, w)
    }

    fun onStop() {
        stoppedAt = System.currentTimeMillis()
        stoppedWhere = where()
    }

    fun onStart() {
        val away = stoppedAt
        stoppedAt = 0
        if (!open || away == 0L || System.currentTimeMillis() - away < ItemHistory.AWAY_MS) return
        history.record(c, i, ItemHistory.CLOSE, stoppedWhere ?: HistoryWhere(), REASON_BACKGROUND, away)
        history.record(c, i, ItemHistory.OPEN, where() ?: stoppedWhere ?: HistoryWhere())
    }

    fun close() {
        if (!open) return
        open = false
        if (stoppedAt > 0) {
            // Closed while away: it was closed when the app was left
            history.record(c, i, ItemHistory.CLOSE, stoppedWhere ?: HistoryWhere(), REASON_BACKGROUND, stoppedAt)
        } else {
            history.record(c, i, ItemHistory.CLOSE, where() ?: HistoryWhere())
        }
    }

    companion object {
        const val REASON_BACKGROUND = "background"
    }
}

/** An [OpenTracker] for this screen, closed when the screen goes. */
@Composable
fun rememberOpenTracker(
    c: String,
    i: String,
    where: () -> HistoryWhere?,
): OpenTracker {
    val di = LocalDI.current
    val currentWhere = rememberUpdatedState(where)
    // From DI, so noting an event also nudges a sync
    val tracker = remember(c, i) { OpenTracker(di.direct.instance(), c, i) { currentWhere.value() } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(tracker, lifecycle) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> tracker.onStop()
                    Lifecycle.Event.ON_START -> tracker.onStart()
                    else -> Unit
                }
            }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            tracker.close()
        }
    }
    return tracker
}
