package com.nononsenseapps.feeder.ui.compose.rsvp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// Where the focus letter sits, as a fraction of the width
private const val FOCUS_POSITION = 0.4f

/**
 * One speed-reading word between two guide lines, its focus letter always at the same spot so the
 * eye never has to move.
 */
@Composable
fun RsvpWordDisplay(
    word: String,
    textColor: Color,
    guideColor: Color,
    focusColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        FocusGuide(top = true, color = guideColor)
        FocusWord(word = word, textColor = textColor, focusColor = focusColor)
        FocusGuide(top = false, color = guideColor)
    }
}

/** A notch above or below where the focus letter sits. */
@Composable
private fun FocusGuide(
    top: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Layout(
        modifier = modifier.fillMaxWidth().height(10.dp),
        content = {
            Box(Modifier.width(2.dp).height(8.dp).background(color))
        },
    ) { measurables, constraints ->
        val mark = measurables[0].measure(constraints.copy(minWidth = 0, minHeight = 0))
        val height = constraints.maxHeight.takeIf { it != Int.MAX_VALUE } ?: mark.height
        layout(constraints.maxWidth, height) {
            val x = (constraints.maxWidth * FOCUS_POSITION).roundToInt() - mark.width / 2
            mark.place(x, if (top) 0 else height - mark.height)
        }
    }
}

/** The word, laid out so the centre of its focus letter is always at the same spot. */
@Composable
private fun FocusWord(
    word: String,
    textColor: Color,
    focusColor: Color,
    modifier: Modifier = Modifier,
) {
    // The word can be empty for a moment (between articles): then all three parts are empty
    val focus = focusIndex(word).coerceIn(0, word.length)
    val length = focusLength(word, focus).coerceIn(0, word.length - focus)
    val style = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Medium, color = textColor)
    Layout(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        content = {
            Text(word.substring(0, focus), style = style, maxLines = 1, softWrap = false)
            Text(
                word.substring(focus, focus + length),
                style = style.copy(fontWeight = FontWeight.Bold),
                color = focusColor,
                maxLines = 1,
                softWrap = false,
            )
            Text(word.substring(focus + length), style = style, maxLines = 1, softWrap = false)
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, maxWidth = Int.MAX_VALUE)
        val (left, pivot, right) = measurables.map { it.measure(loose) }
        val width = constraints.maxWidth
        val height = maxOf(left.height, pivot.height, right.height)
        layout(width, height) {
            val pivotX = (width * FOCUS_POSITION).roundToInt() - pivot.width / 2
            left.place(pivotX - left.width, 0)
            pivot.place(pivotX, 0)
            right.place(pivotX + pivot.width, 0)
        }
    }
}
