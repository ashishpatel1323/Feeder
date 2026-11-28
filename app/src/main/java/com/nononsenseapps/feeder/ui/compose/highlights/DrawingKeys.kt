package com.nononsenseapps.feeder.ui.compose.highlights

import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/**
 * While Draw on page is on, a finger writes instead of scrolling, so the volume keys move the page
 * instead: volume down = next page (or scroll down), volume up = previous page (or scroll up).
 * The page on screen registers what a page turn means there ([VolumeKeysTurnPages]); MainActivity
 * hands the keys over.
 */
object DrawingVolumeKeys {
    @Volatile
    private var handler: ((forward: Boolean) -> Unit)? = null

    internal fun set(h: ((Boolean) -> Unit)?) {
        handler = h
    }

    internal fun clear(h: (Boolean) -> Unit) {
        if (handler === h) handler = null
    }

    /** True when the key was used to move the page. */
    fun onKeyDown(keyCode: Int): Boolean {
        val h = handler ?: return false
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                h(true)
                true
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                h(false)
                true
            }
            else -> false
        }
    }

    /** Swallows the key-up too, so the volume doesn't change and no volume sound plays. */
    fun onKeyUp(keyCode: Int): Boolean = handler != null && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)
}

/** Lets the volume keys move this page while [enabled] (Draw on page is on). */
@Composable
fun VolumeKeysTurnPages(
    enabled: Boolean,
    onPage: (forward: Boolean) -> Unit,
) {
    val current by rememberUpdatedState(onPage)
    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val h: (Boolean) -> Unit = { current(it) }
        DrawingVolumeKeys.set(h)
        onDispose { DrawingVolumeKeys.clear(h) }
    }
}

/**
 * The stylus side button (Samsung S Pen and others), from the raw touch events: held down while
 * writing, it erases. Android reports it as BUTTON_STYLUS_PRIMARY; Samsung devices have used
 * BUTTON_SECONDARY for the S Pen button, so both count (only for a stylus).
 */
object StylusButton {
    @Volatile
    var pressed: Boolean = false
        private set

    fun update(ev: MotionEvent) {
        if (ev.pointerCount == 0) return
        val tool = ev.getToolType(0)
        pressed =
            (tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER) &&
            ev.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY or MotionEvent.BUTTON_SECONDARY) != 0
    }
}
