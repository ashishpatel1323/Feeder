/**
 * A video's transcript under chapter headings (same rules as the Android app's
 * TranscriptSections.kt). Without chapters the transcript is split into 5-minute blocks named by
 * their first words; a transcript shorter than one block gets no headings at all.
 */
import type { TranscriptCue } from './transcriptFormats'

export interface TranscriptSection {
  startMs: number
  title: string
  /** Cues firstCue up to (not including) endCue */
  firstCue: number
  endCue: number
  /** A small label after the title, e.g. "Mac" for an audiobook chapter transcribed on the Mac */
  badge?: string
}

export interface TranscriptChapter {
  startMs: number
  title: string
  badge?: string
}

export const TRANSCRIPT_BLOCK_MS = 5 * 60 * 1000

export function transcriptSections(cues: TranscriptCue[], chapters: TranscriptChapter[], blockMs = TRANSCRIPT_BLOCK_MS): TranscriptSection[] {
  if (!cues.length) return []
  const sorted = [...chapters].sort((a, b) => a.startMs - b.startMs)
  const firstFrom = (ms: number) => {
    const i = cues.findIndex((c) => c.startMs >= ms)
    return i < 0 ? cues.length : i
  }
  if (sorted.length) {
    return sorted.map((ch, i) => {
      // Lines before the first chapter's start belong to it
      const first = i === 0 ? 0 : firstFrom(ch.startMs)
      const end = i + 1 < sorted.length ? firstFrom(sorted[i + 1].startMs) : cues.length
      return { startMs: ch.startMs, title: ch.title, firstCue: first, endCue: Math.max(first, end), ...(ch.badge ? { badge: ch.badge } : {}) }
    })
  }
  const last = cues[cues.length - 1]
  if (Math.max(last.endMs, last.startMs) <= blockMs) return []
  const out: TranscriptSection[] = []
  let first = 0
  while (first < cues.length) {
    const blockStart = Math.floor(cues[first].startMs / blockMs) * blockMs
    const end = Math.max(first + 1, firstFrom(blockStart + blockMs))
    out.push({ startMs: blockStart, title: firstWords(cues[first].text), firstCue: first, endCue: end })
    first = end
  }
  return out
}

/** The section playing at `positionMs` (the last one started), or -1 before the first. */
export function sectionIndexAt(sections: TranscriptSection[], positionMs: number): number {
  let found = -1
  sections.forEach((s, i) => {
    if (s.startMs <= positionMs) found = i
  })
  return found
}

export function firstWords(text: string, maxChars = 40): string {
  const clean = text.replace(/\s+/g, ' ').trim()
  if (clean.length <= maxChars) return clean
  const cut = clean.slice(0, maxChars)
  const space = cut.lastIndexOf(' ')
  return (space > maxChars / 2 ? cut.slice(0, space) : cut).replace(/[,.;:]+$/, '') + '…'
}

/**
 * Chapters written in a YouTube description ("0:00 Intro", "1:02:03 - Wrap up"): YouTube makes
 * its chapters from these lines. Needs a list that starts at 0:00 and has at least three entries,
 * YouTube's own rule; otherwise there are none.
 */
export function descriptionChapters(description: string | null | undefined): TranscriptChapter[] {
  if (!description) return []
  const out: TranscriptChapter[] = []
  for (const line of description.split('\n')) {
    const m = line.match(/^\s*[([]?((?:\d{1,2}:)?\d{1,2}:\d{2})[)\]]?\s*[-–—:|.]?\s*(.+?)\s*$/)
    if (!m) continue
    const parts = m[1].split(':').map(Number)
    const sec = parts.reduce((a, n) => a * 60 + n, 0)
    out.push({ startMs: sec * 1000, title: m[2] })
  }
  if (out.length < 3 || out[0].startMs !== 0) return []
  return out
}
