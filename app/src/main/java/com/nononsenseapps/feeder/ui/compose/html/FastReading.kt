package com.nononsenseapps.feeder.ui.compose.html

import androidx.compose.runtime.compositionLocalOf

/*
 * Fast reading: the first part of every word is bold, so the eye can jump between these
 * fixation points and the brain fills in the rest of the word.
 *
 * Ported from text-vide by Gumball12 (https://github.com/Gumball12/text-vide), MIT License,
 * Copyright (c) 2022 Gumball12. The web reader in web/src/lib/fastReading.ts uses the same port,
 * so both apps bold the same letters.
 */

/** How much of each word is bold, from 1 (most) to 5 (least), as in text-vide. */
const val FAST_READING_FIXATION_POINT = 3

// For fixation point N, a word of length L gets L - i bold letters, where i is the first index with L <= boundary[i]
private val FIXATION_BOUNDARIES: List<IntArray> =
    listOf(
        intArrayOf(0, 4, 12, 17, 24, 29, 35, 42, 48),
        intArrayOf(1, 2, 7, 10, 13, 14, 19, 22, 25, 28, 31, 34, 37, 40, 43, 46, 49),
        intArrayOf(1, 2, 5, 7, 9, 11, 13, 15, 17, 19, 21, 23, 25, 27, 29, 31, 33, 35, 37, 39, 41, 43, 45, 47, 49),
        intArrayOf(0, 2, 4, 5, 6, 8, 9, 11, 14, 15, 17, 18, 20, 0, 21, 23, 24, 26, 27, 29, 30, 32, 33, 35, 36, 38, 39, 41, 42, 44, 45, 47, 48),
        intArrayOf(0, 2, 3, 5, 6, 7, 8, 10, 11, 12, 14, 15, 17, 19, 20, 21, 23, 24, 25, 26, 28, 29, 30, 32, 33, 34, 35, 37, 38, 39, 41, 42, 43, 44, 46, 47, 48),
    )

// A word holds at least one letter; digits only count inside words
private val WORD = Regex("[\\p{L}\\p{Nd}]*\\p{L}[\\p{L}\\p{Nd}]*")

internal fun fixationLength(
    wordLength: Int,
    fixationPoint: Int = FAST_READING_FIXATION_POINT,
): Int {
    val boundaries = FIXATION_BOUNDARIES.getOrElse(fixationPoint - 1) { FIXATION_BOUNDARIES[0] }
    val index = boundaries.indexOfFirst { wordLength <= it }
    val bold = if (index == -1) wordLength - boundaries.size else wordLength - index
    return bold.coerceAtLeast(0)
}

/**
 * The parts of [text] to make bold, as [start, end) pairs. Only styles change, never the text,
 * so read-aloud highlights and tap-to-read offsets still line up.
 */
internal fun fastReadingRanges(
    text: String,
    fixationPoint: Int = FAST_READING_FIXATION_POINT,
): List<IntRange> =
    WORD
        .findAll(text)
        .mapNotNull { match ->
            val length = fixationLength(match.value.length, fixationPoint)
            if (length > 0) match.range.first until match.range.first + length else null
        }.toList()

/** Whether the reader shows bold word starts. */
@Suppress("ktlint:compose:compositionlocal-allowlist")
internal val LocalFastReading = compositionLocalOf { false }
