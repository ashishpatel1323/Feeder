package com.nononsenseapps.feeder.ui.compose.feedarticle

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nononsenseapps.feeder.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive

private const val AUTO_SCROLL_PREFS = "auto_scroll"
private const val PREF_LEVEL = "level"
private const val DEFAULT_LEVEL = 4

// Scroll speed per level in dp per second; level 4 is roughly an unhurried reading pace
private val SPEEDS_DP = listOf(6f, 9f, 12f, 15f, 19f, 24f, 30f, 38f, 48f, 60f)

/** Hands-free reading: the article scrolls slowly on its own at a speed the reader picks. */
@Stable
class AutoScrollState(
    private val context: Context,
) {
    var active by mutableStateOf(false)
        private set
    var playing by mutableStateOf(false)
    var level by mutableIntStateOf(
        context
            .getSharedPreferences(AUTO_SCROLL_PREFS, Context.MODE_PRIVATE)
            .getInt(PREF_LEVEL, DEFAULT_LEVEL)
            .coerceIn(1, SPEEDS_DP.size),
    )
        private set

    val speedDp: Float get() = SPEEDS_DP[level - 1]

    fun start() {
        active = true
        playing = true
    }

    fun stop() {
        active = false
        playing = false
    }

    fun toggle() = if (active) stop() else start()

    fun changeLevel(delta: Int) {
        level = (level + delta).coerceIn(1, SPEEDS_DP.size)
        context
            .getSharedPreferences(AUTO_SCROLL_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(PREF_LEVEL, level)
            .apply()
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

@Composable
fun rememberAutoScrollState(): AutoScrollState {
    val context = LocalContext.current.applicationContext
    return remember(context) { AutoScrollState(context) }
}

/**
 * Moves [scrollState] while auto scroll plays. A finger or wheel on the article takes over and
 * auto scroll carries on from wherever it is left once the article is let go.
 */
@Composable
fun AutoScrollEffect(
    state: AutoScrollState,
    scrollState: ScrollState,
) {
    val density = LocalDensity.current
    val window = LocalContext.current.findActivity()?.window
    DisposableEffect(state.active, window) {
        // The screen must not turn off while reading hands-free. A window flag, so it never clears
        // the view's keepScreenOn that the read-aloud player sets for speed reading.
        if (state.active && window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        } else {
            onDispose { }
        }
    }
    LaunchedEffect(state.playing, state.level) {
        if (!state.playing) return@LaunchedEffect
        val pxPerSecond = with(density) { state.speedDp.dp.toPx() }
        while (isActive) {
            if (scrollState.value >= scrollState.maxValue && scrollState.maxValue > 0) {
                state.playing = false
                return@LaunchedEffect
            }
            try {
                scrollState.scroll(MutatePriority.Default) {
                    var last = withFrameNanos { it }
                    while (true) {
                        val now = withFrameNanos { it }
                        val seconds = ((now - last) / 1_000_000_000f).coerceAtMost(0.1f)
                        last = now
                        val wanted = pxPerSecond * seconds
                        if (scrollBy(wanted) < wanted / 2) break
                    }
                }
                // Stopped short: at the end, or the article is still being laid out
                delay(250)
            } catch (e: CancellationException) {
                if (!isActive) throw e
                // The reader grabbed the article: wait until they let go, then carry on from there
                snapshotFlow { scrollState.isScrollInProgress }.first { !it }
                delay(600)
            }
        }
    }
}

/** Pause, slower, speed, faster and close for auto scroll, floating over the article. */
@Composable
fun AutoScrollControls(
    state: AutoScrollState,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier
                .padding(16.dp)
                .widthIn(max = 360.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 6.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            val playLabel = stringResource(if (state.playing) R.string.auto_scroll_pause else R.string.auto_scroll_resume)
            IconButton(onClick = { state.playing = !state.playing }) {
                Icon(if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = playLabel)
            }
            IconButton(onClick = { state.changeLevel(-1) }, enabled = state.level > 1) {
                Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.auto_scroll_slower))
            }
            Text(
                text = stringResource(R.string.auto_scroll_speed, state.level),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            IconButton(onClick = { state.changeLevel(1) }, enabled = state.level < SPEEDS_DP.size) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.auto_scroll_faster))
            }
            IconButton(onClick = state::stop) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.auto_scroll_stop))
            }
        }
    }
}
