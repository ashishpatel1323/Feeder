import { describe, expect, it } from 'vitest'
import { aheadUntil, FIRST_PIECE_MS, mergePieces, nextTarget, PIECE_MS, pieceRanges, readyUntil, type TranscriptPiece } from '../audiobooks/transcriptRules'
import type { Chapter } from '../audiobooks/timeline'

const min = 60_000
const piece = (s: number, e: number, ...cues: [number, string][]): TranscriptPiece => ({ s, e, c: cues.map(([at, t]) => [at, at + 1000, t]) })
const ch = (bookMs: number, lengthMs: number, title: string): Chapter => ({ i: 0, startMs: bookMs, bookMs, lengthMs, title })

describe('audiobook transcript rules (same as Android)', () => {
  it('keeps the earlier piece where pieces overlap', () => {
    const a = piece(0, 10 * min, [0, 'a1'], [5 * min, 'a2'])
    const b = piece(8 * min, 15 * min, [8 * min, 'b1'], [12 * min, 'b2'])
    expect(mergePieces([b, a]).map((c) => c.text)).toEqual(['a1', 'a2', 'b2'])
  })

  it('joins touching pieces and says how far it is ready', () => {
    const r = pieceRanges([piece(5 * min, 10 * min), piece(0, 5 * min), piece(20 * min, 25 * min)])
    expect(r).toEqual([
      [0, 10 * min],
      [20 * min, 25 * min],
    ])
    expect(readyUntil(r, 3 * min)).toBe(10 * min)
    expect(readyUntil(r, 15 * min)).toBe(15 * min)
  })

  it('starts with a short piece at the play position', () => {
    expect(nextTarget([60 * min], [], [], 7 * min)).toEqual({ i: 0, startMs: 7 * min, endMs: 7 * min + FIRST_PIECE_MS, bookStartMs: 7 * min })
  })

  it('carries on from where the transcript ends, up to the next piece', () => {
    const pieces = [piece(0, 10 * min), piece(13 * min, 20 * min)]
    expect(nextTarget([60 * min], [], pieces, 2 * min)).toMatchObject({ startMs: 10 * min, endMs: 13 * min })
    expect(nextTarget([60 * min], [], [...pieces, piece(10 * min, 13 * min)], 2 * min)).toMatchObject({ startMs: 20 * min, endMs: 20 * min + PIECE_MS })
  })

  it('stays inside one file', () => {
    expect(nextTarget([12 * min, 30 * min], [], [piece(0, 10 * min)], min)).toMatchObject({ i: 0, endMs: 12 * min })
    expect(nextTarget([12 * min, 30 * min], [], [piece(0, 12 * min)], min)).toMatchObject({ i: 1, startMs: 0, bookStartMs: 12 * min })
  })

  it('stops when ready far enough ahead', () => {
    const chapters = [ch(0, 10 * min, '1'), ch(10 * min, 10 * min, '2'), ch(20 * min, 40 * min, '3')]
    expect(aheadUntil(chapters, 9 * min, 60 * min)).toBe(24 * min)
    expect(nextTarget([60 * min], chapters, [piece(0, 25 * min)], 9 * min)).toBeNull()
  })

  it('waits for unknown lengths', () => {
    expect(nextTarget([0], [], [], 0)).toBeNull()
  })

  it('skips a sliver left at the end of a file', () => {
    expect(nextTarget([35_060, 35_000], [], [piece(0, 34_900)], 0)).toMatchObject({ i: 1, startMs: 0 })
  })
})
