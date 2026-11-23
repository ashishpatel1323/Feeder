package com.nononsenseapps.feeder.model.playlists

import kotlin.math.max
import kotlin.math.roundToInt

// How long an item takes, the same way on every surface (article list, Saved, playlists,
// downloads, the article screen and Flip): an episode or video counts its real length (learned
// from a saved file, the feed's itunes:duration, or where it was left), an article its reading time.

/** Reading time of [words] at 220 words a minute, in seconds. Same rate as the reader. */
fun readingSeconds(words: Int): Long = (words * 60 / 220.0).roundToInt().toLong()

/**
 * Length in seconds of an episode or video: a learned length first, then the feed's duration,
 * then the length recorded with the last position. Null when none is known.
 */
fun mediaSeconds(
    knownLength: Long? = null,
    enclosureDuration: Long? = null,
    positionLength: Long? = null,
): Long? = knownLength?.takeIf { it > 0 } ?: enclosureDuration?.takeIf { it > 0 } ?: positionLength?.takeIf { it > 0 }

/** Length in seconds of any item: [mediaSeconds] for episodes and videos, reading time for articles. */
fun itemSeconds(
    kind: ItemKind,
    wordCount: Int,
    enclosureDuration: Long? = null,
    knownLength: Long? = null,
    positionLength: Long? = null,
): Long? =
    when (kind) {
        ItemKind.ARTICLE -> readingSeconds(wordCount).takeIf { it > 0 }
        else -> mediaSeconds(knownLength, enclosureDuration, positionLength)
    }

/** Where an episode or video was left. */
data class ItemProgress(
    val positionSeconds: Long,
    val lengthSeconds: Long,
) {
    /** 0..1, or 0 when the length is not known. */
    val fraction: Float
        get() = if (lengthSeconds > 0) (positionSeconds.toFloat() / lengthSeconds).coerceIn(0f, 1f) else 0f

    /** Started, and not within the last [PLAYED_TAIL_SECONDS]. */
    val partlyPlayed: Boolean
        get() = positionSeconds >= MIN_PROGRESS_SECONDS && (lengthSeconds <= 0 || positionSeconds < lengthSeconds - PLAYED_TAIL_SECONDS)
}

/** Positions this early do not count as started. */
const val MIN_PROGRESS_SECONDS = 5L

/** What the length text under an item says. Turned into words by the UI. */
sealed interface LengthText {
    /** "4 min read" */
    data class Read(
        val minutes: Int,
    ) : LengthText

    /** "24 min" */
    data class Total(
        val minutes: Int,
    ) : LengthText

    /** "19 min left" */
    data class Left(
        val minutes: Int,
    ) : LengthText
}

private fun minutesOf(seconds: Long): Int = max(1, ((seconds + 30) / 60).toInt())

/** The length text for an item, or null when its length is not known. */
fun lengthText(
    kind: ItemKind,
    seconds: Long?,
    progress: ItemProgress? = null,
): LengthText? {
    if (seconds == null || seconds <= 0) return null
    return when {
        kind == ItemKind.ARTICLE -> LengthText.Read(minutesOf(seconds))
        progress != null && progress.partlyPlayed -> LengthText.Left(minutesOf(max(0L, seconds - progress.positionSeconds)))
        else -> LengthText.Total(minutesOf(seconds))
    }
}

/** "mm:ss" or "h:mm:ss", for the badge on an episode's or video's thumbnail. */
fun clockText(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** Minutes for an article's badge ("N min"). */
fun badgeMinutes(seconds: Long): Int = minutesOf(seconds)
