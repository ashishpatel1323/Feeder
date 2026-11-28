package com.nononsenseapps.feeder.ui.compose.playlists

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.model.ReadAloudProgress
import com.nononsenseapps.feeder.model.SpeechSegment
import com.nononsenseapps.feeder.ui.compose.theme.LocalPaperMode

/**
 * The article being read aloud by a playlist, as text: the spoken sentence (and word) are
 * highlighted and kept in view. Scrolling by hand stops following until "Back to spoken text".
 */
@Composable
fun SpokenArticleText(
    progress: ReadAloudProgress,
    articleId: Long?,
    modifier: Modifier = Modifier,
) {
    val ready = articleId != null && progress.articleId == articleId && progress.text.isNotBlank()
    if (!ready) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.queue_text_loading),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        return
    }
    val text = progress.text
    val paragraphs = remember(text) { paragraphRanges(text) }
    val spokenAt = progress.segment?.start ?: progress.word?.start
    val current = spokenAt?.let { at -> paragraphs.indexOfLast { it.first <= at }.coerceAtLeast(0) } ?: -1
    var follow by remember(articleId) { mutableStateOf(true) }
    val listState = rememberLazyListState()
    val paper = LocalPaperMode.current
    val stopFollowing =
        remember {
            object : NestedScrollConnection {
                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (source == NestedScrollSource.UserInput) follow = false
                    return Offset.Zero
                }
            }
        }
    val sentenceColor = MaterialTheme.colorScheme.secondaryContainer
    val wordColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // Keep the spoken paragraph about a quarter of the way down
        val lead = with(LocalDensity.current) { (maxHeight * 0.25f).roundToPx() }
        LaunchedEffect(current, follow, lead) {
            if (follow && current >= 0) {
                if (paper) {
                    listState.scrollToItem(current, -lead)
                } else {
                    listState.animateScrollToItem(current, -lead)
                }
            }
        }
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = 8.dp, bottom = 72.dp),
            modifier = Modifier.fillMaxSize().nestedScroll(stopFollowing),
        ) {
            itemsIndexed(paragraphs, key = { _, range -> range.first }) { i, range ->
                val shown =
                    if (i == current) {
                        highlighted(text, range, progress.segment, progress.word, sentenceColor, wordColor, paper)
                    } else {
                        AnnotatedString(text.substring(range.first, range.last + 1))
                    }
                Text(
                    shown,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 19.sp, lineHeight = 29.sp),
                    color = if (i == current || current < 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                )
            }
        }
        if (!follow && current >= 0) {
            FilledTonalButton(
                onClick = { follow = true },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            ) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.queue_back_to_spoken_text))
            }
        }
    }
}

/** Non-blank lines of [text], as index ranges into it. */
internal fun paragraphRanges(text: String): List<IntRange> {
    val out = mutableListOf<IntRange>()
    var start = 0
    while (start <= text.length) {
        val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        var a = start
        var b = end
        while (a < b && text[a].isWhitespace()) a++
        while (b > a && text[b - 1].isWhitespace()) b--
        if (b > a) out += a until b
        start = end + 1
    }
    return out
}

private fun highlighted(
    text: String,
    range: IntRange,
    sentence: SpeechSegment?,
    word: SpeechSegment?,
    sentenceColor: androidx.compose.ui.graphics.Color,
    wordColor: androidx.compose.ui.graphics.Color,
    paper: Boolean,
): AnnotatedString =
    buildAnnotatedString {
        val base = range.first
        append(text.substring(range.first, range.last + 1))
        val len = range.last + 1 - base

        fun mark(
            seg: SpeechSegment?,
            style: SpanStyle,
        ) {
            if (seg == null) return
            val s = (seg.start - base).coerceIn(0, len)
            val e = (seg.end - base).coerceIn(0, len)
            if (e > s) addStyle(style, s, e)
        }
        // Paper (e-ink): bold and underline read better than tints
        mark(sentence, if (paper) SpanStyle(fontWeight = FontWeight.SemiBold) else SpanStyle(background = sentenceColor))
        mark(word, if (paper) SpanStyle(textDecoration = TextDecoration.Underline) else SpanStyle(background = wordColor))
    }
