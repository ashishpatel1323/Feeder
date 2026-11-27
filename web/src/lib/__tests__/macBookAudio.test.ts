import 'fake-indexeddb/auto'
import { describe, expect, it, vi } from 'vitest'

vi.mock('../kokoro', () => ({ KOKORO_PREFIX: 'kokoro:', kokoro: { load: () => Promise.resolve(), generate: () => ({ id: 1, audio: Promise.resolve({ samples: new Float32Array(1), sampleRate: 24000 }) }) } }))

const DAY = 86400_000

describe('audio made on the Mac for the phone', async () => {
  const m = await import('../macBookAudio')

  it('hashes text like Java String.hashCode, so the phone can check it', () => {
    expect(m.javaHash('')).toBe(0)
    expect(m.javaHash('hello')).toBe(99162322)
    // Overflows wrap like a Java int
    expect(m.javaHash('The quick brown fox jumps over the lazy dog')).toBe(-609428141)
  })

  it('finds the chapter of a synced position', () => {
    expect(m.sectionOfCfi('epubcfi(/6/14!/4/2/1:0)')).toBe(6)
    expect(m.sectionOfCfi('epubcfi(/6/2!/4)')).toBe(0)
    expect(m.sectionOfCfi('nonsense')).toBe(-1)
  })

  it('keeps books in Dropbox opened in the last week ready, newest first, not finished ones', () => {
    const now = 100 * DAY
    const positions = [
      { id: 'old', ts: now - 8 * DAY, f: 0.2 },
      { id: 'a', ts: now - 2 * DAY, f: 0.5 },
      { id: 'b', ts: now - 1 * DAY, f: 0.1 },
      { id: 'done', ts: now - DAY, f: 1 },
      { id: 'phone-only', ts: now, f: 0.3 },
    ]
    expect(m.booksToPrepare(positions, new Set(['old', 'a', 'b', 'done']), now)).toEqual(['b', 'a'])
  })

  it('deletes a book folder when the book is finished, gone, or not opened for 14 days', () => {
    const now = 100 * DAY
    const pos = new Map([
      ['fresh', { ts: now - 3 * DAY, f: 0.4 }],
      ['stale', { ts: now - 15 * DAY, f: 0.4 }],
      ['done', { ts: now, f: 1 }],
    ])
    expect(m.staleBookFolders(['fresh', 'stale', 'done', 'unknown'], pos, now)).toEqual(['stale', 'done', 'unknown'])
  })

  it('keeps the chapter being read and the Prepare ahead count, or the whole book', () => {
    const readable = [0, 2, 3, 4, 6, 7, 9]
    expect(m.macWindow(readable, 3, 2)).toEqual([3, 4, 6])
    expect(m.macWindow(readable, 5, 2)).toEqual([6, 7])
    expect(m.macWindow(readable, 3, m.WHOLE_BOOK)).toEqual([3, 4, 6, 7, 9])
  })

  it('deletes chapters read past or out of the window, and counts only complete ones', () => {
    const names = ['s2.m4a', 's2.json', 's3.m4a', 's3.json', 's4.m4a', 's9.json', 'notes.txt']
    expect(m.chapterFilesToDelete(names, new Set([3, 4]))).toEqual(['s2.m4a', 's2.json', 's9.json'])
    expect(m.readyChapters(names)).toEqual([2, 3])
  })

  it('times sentences and spreads words over them like the phone does', () => {
    const text = 'One two. Three four five.'
    const pieces = m.pieceOffsets(text, 'en')
    expect(pieces.map((p) => text.slice(p.start, p.end))).toEqual(['One two.', 'Three four five.'])
    const words = m.estimateWords(text, { s: 9, e: 25, a: 1000, b: 2000 })
    expect(words.map((w) => text.slice(w.s, w.e))).toEqual(['Three', 'four', 'five.'])
    expect(words[0].a).toBe(1000)
    expect(words[2].b).toBe(2000)
  })

  it('writes 16-bit WAV for compressing', async () => {
    const blob = m.wavBlob(new Float32Array([0, 1, -1]), 24000)
    const v = new DataView(await blob.arrayBuffer())
    expect(blob.size).toBe(50)
    expect(v.getUint32(24, true)).toBe(24000)
    expect(v.getInt16(46, true)).toBe(32767)
    expect(v.getInt16(48, true)).toBe(-32768)
  })
})
