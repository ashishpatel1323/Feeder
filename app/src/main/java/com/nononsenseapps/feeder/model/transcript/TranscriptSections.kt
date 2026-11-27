package com.nononsenseapps.feeder.model.transcript

/**
 * A heading in a transcript shown by chapter: the cues from [firstCue] up to (not including)
 * [endCue]. [title] is the chapter's name, or the block's first words when the video has no chapters.
 */
data class TranscriptSection(
    val startMs: Long,
    val title: String,
    val firstCue: Int,
    val endCue: Int,
    /** A small label after the title, e.g. "Mac" for an audiobook chapter transcribed on the Mac */
    val badge: String? = null,
) {
    val isEmpty: Boolean get() = endCue <= firstCue

    fun contains(cue: Int): Boolean = cue in firstCue until endCue
}

/** A chapter's start and name, as the transcript needs it. */
data class TranscriptChapter(
    val startMs: Long,
    val title: String,
    val badge: String? = null,
)

const val TRANSCRIPT_BLOCK_MS = 5 * 60 * 1000L

/**
 * Splits [cues] under [chapters]. Without chapters, the transcript is split into
 * [blockMs] blocks named by their first words; a transcript shorter than one block gets no
 * headings at all (empty list = show it as one plain list).
 */
fun transcriptSections(
    cues: List<TranscriptCue>,
    chapters: List<TranscriptChapter>,
    blockMs: Long = TRANSCRIPT_BLOCK_MS,
): List<TranscriptSection> {
    if (cues.isEmpty()) return emptyList()
    val sorted = chapters.sortedBy { it.startMs }
    if (sorted.isNotEmpty()) {
        return sorted.mapIndexed { i, chapter ->
            // Lines before the first chapter's start belong to it
            val first = if (i == 0) 0 else firstCueFrom(cues, chapter.startMs)
            val end = sorted.getOrNull(i + 1)?.let { firstCueFrom(cues, it.startMs) } ?: cues.size
            TranscriptSection(chapter.startMs, chapter.title, first, end.coerceAtLeast(first), chapter.badge)
        }
    }
    if (cues.last().endMs.coerceAtLeast(cues.last().startMs) <= blockMs) return emptyList()
    val sections = mutableListOf<TranscriptSection>()
    var first = 0
    while (first < cues.size) {
        val blockStart = (cues[first].startMs / blockMs) * blockMs
        val end = firstCueFrom(cues, blockStart + blockMs).coerceAtLeast(first + 1)
        sections += TranscriptSection(blockStart, firstWords(cues[first].text), first, end)
        first = end
    }
    return sections
}

/** The section holding [cue], or -1. */
fun List<TranscriptSection>.indexOfCue(cue: Int): Int = indexOfFirst { it.contains(cue) }

/** The section playing at [positionMs] (the last one started), or -1 before the first. */
fun List<TranscriptSection>.indexAtTime(positionMs: Long): Int = indexOfLast { it.startMs <= positionMs }

private fun firstCueFrom(
    cues: List<TranscriptCue>,
    ms: Long,
): Int {
    val i = cues.indexOfFirst { it.startMs >= ms }
    return if (i < 0) cues.size else i
}

internal fun firstWords(
    text: String,
    maxChars: Int = 40,
): String {
    val clean = text.replace(Regex("\\s+"), " ").trim()
    if (clean.length <= maxChars) return clean
    val cut = clean.take(maxChars)
    val space = cut.lastIndexOf(' ')
    return (if (space > maxChars / 2) cut.take(space) else cut).trimEnd(',', '.', ';', ':') + "…"
}
