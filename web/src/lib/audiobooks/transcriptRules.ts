/**
 * Audiobook transcripts in pieces, the same rules as Android's AudiobookTranscriptRules: how the
 * pieces (from any device; they sync as "abtx") merge into one transcript, how far it is ready, and
 * what to transcribe next, a chapter or two ahead of where the book plays.
 */
import type { TranscriptCue } from '../transcriptFormats'
import type { Chapter } from './timeline'

/** The lines Whisper heard from s to e ms into the book: [start ms, end ms, text] in book time. */
export interface TranscriptPiece {
  s: number
  e: number
  c: [number, number, string][]
  /** "mac" when the Feeder Mac app made it */
  d?: string
}

/** A stretch to transcribe: file i from startMs to endMs (file time), starting bookStartMs into the book. */
export interface PieceTarget {
  i: number
  startMs: number
  endMs: number
  bookStartMs: number
}

const MIN = 60_000
export const AHEAD_MIN_MS = 15 * MIN
export const AHEAD_MAX_MS = 60 * MIN
export const PIECE_MS = 5 * MIN
/** Nothing ready where the book plays: a short first piece, so lines appear within seconds. */
export const FIRST_PIECE_MS = 30_000
/** Gaps shorter than this between pieces are left alone. */
export const GAP_MS = 1_500

export type Range = [number, number]

/** All the lines, in order; where pieces overlap, the earlier piece's lines win. */
export function mergePieces(pieces: TranscriptPiece[]): TranscriptCue[] {
  const out: TranscriptCue[] = []
  let covered = -Infinity
  for (const p of [...pieces].sort((a, b) => a.s - b.s || b.e - a.e)) {
    if (p.e <= covered) continue
    for (const [s, e, t] of p.c) if (s >= covered) out.push({ startMs: s, endMs: e, text: t })
    covered = Math.max(covered, p.e)
  }
  return out
}

/** The stretches transcribed, merged, in order. */
export function pieceRanges(pieces: TranscriptPiece[]): Range[] {
  const out: Range[] = []
  for (const p of [...pieces].sort((a, b) => a.s - b.s)) {
    const last = out[out.length - 1]
    if (last && p.s <= last[1] + GAP_MS) last[1] = Math.max(last[1], p.e)
    else out.push([p.s, p.e])
  }
  return out
}

/** Where the transcript ready from atMs on ends; atMs itself when nothing is ready there. */
export function readyUntil(ranges: Range[], atMs: number): number {
  return ranges.find(([s, e]) => atMs >= s - GAP_MS && atMs < e)?.[1] ?? atMs
}

/** Transcribe ahead: every chapter to the end of the book */
export const WHOLE_BOOK = 1000
export const TRANSCRIBE_AHEAD_CHOICES = [1, 2, 3, 5, WHOLE_BOOK]

/**
 * How far ahead of bookMs to have the transcript ready: the end of the chapter [ahead] chapters
 * after the one playing, at least 15 minutes and at most an hour per chapter ahead; the end of the
 * book for WHOLE_BOOK.
 */
export function aheadUntil(chapters: Chapter[], bookMs: number, bookLength: number, ahead = 1): number {
  if (ahead >= WHOLE_BOOK) return bookLength > 0 ? bookLength : Infinity
  let k = -1
  chapters.forEach((c, i) => {
    if (c.bookMs <= bookMs) k = i
  })
  const next = chapters[k + ahead]
  const nextEnd = next ? next.bookMs + next.lengthMs : Infinity
  const end = Math.min(Math.max(nextEnd, bookMs + AHEAD_MIN_MS), bookMs + AHEAD_MAX_MS * ahead)
  return bookLength > 0 ? Math.min(end, bookLength) : end
}

/** Chapters (or the whole book, as one, when it has none) transcribed from start to end. */
export function chaptersReady(chapters: Chapter[], ranges: Range[], bookLength: number): { ready: number; total: number } {
  const spans = chapters.length ? chapters.map((c) => [c.bookMs, c.bookMs + c.lengthMs] as const) : bookLength > 0 ? [[0, bookLength] as const] : []
  const ready = spans.filter(([s, e]) => ranges.some(([a, b]) => a <= s + GAP_MS && b >= e - GAP_MS)).length
  return { ready, total: spans.length }
}

/**
 * The next stretch to transcribe for a book playing at bookMs: the first gap from there, up to
 * aheadUntil, inside one file, stopping where the next piece starts. Null when ready far enough
 * ahead, or while the files' lengths aren't known.
 */
export function nextTarget(trackLengths: number[], chapters: Chapter[], pieces: TranscriptPiece[], bookMs: number, ahead = 1): PieceTarget | null {
  if (!trackLengths.length || trackLengths.some((l) => !(l > 0))) return null
  const bookLength = trackLengths.reduce((a, b) => a + b, 0)
  const ranges = pieceRanges(pieces)
  const at = Math.min(Math.max(0, bookMs), bookLength)
  const until = aheadUntil(chapters, bookMs, bookLength, ahead)
  const from = readyUntil(ranges, at)
  if (from >= until || from >= bookLength - 500) return null
  const nothingHere = from === bookMs
  const nextStart = ranges.find(([s]) => s > from)?.[0] ?? Infinity
  let base = 0
  for (let i = 0; i < trackLengths.length; i++) {
    const len = trackLengths[i]
    // Less than a second left in this file (lengths are rounded): on to the next one
    if (from < base + len && base + len - from >= 1000) {
      const start = Math.max(from, base)
      const endBook = Math.min(start + (nothingHere ? FIRST_PIECE_MS : PIECE_MS), nextStart, base + len)
      // A sliver left at the end of a file joins this piece
      const end = base + len - endBook < 20_000 ? base + len : endBook
      return { i, startMs: start - base, endMs: end - base, bookStartMs: start }
    }
    base += len
  }
  return null
}

/** Whisper's lines for a piece (file time) to a piece in book time. */
export function toPiece(target: PieceTarget, endMs: number, cues: TranscriptCue[]): TranscriptPiece {
  const base = target.bookStartMs - target.startMs
  return { s: target.bookStartMs, e: base + endMs, c: cues.map((c) => [base + c.startMs, base + c.endMs, c.text]) }
}
