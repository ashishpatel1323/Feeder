import 'fake-indexeddb/auto'
import { describe, expect, it, vi } from 'vitest'

const generated: { text: string; background: boolean }[] = []
vi.mock('../kokoro', () => ({
  KOKORO_PREFIX: 'kokoro:',
  kokoro: {
    load: () => Promise.resolve(),
    generate: (text: string, _voice: string, _urgent = false, background = false) => {
      generated.push({ text, background })
      return { id: generated.length, audio: Promise.resolve({ samples: new Float32Array(text.length * 10).fill(0.25), sampleRate: 24000 }) }
    },
  },
}))

const chapter = (n: number) =>
  `<html xmlns="http://www.w3.org/1999/xhtml"><body><h1>Chapter ${n}</h1><p>This is chapter number ${n}. It has a couple of sentences to read aloud.</p></body></html>`

/** Just enough of a foliate view: five chapters. */
function fakeView() {
  return {
    isFixedLayout: false,
    lastLocation: null,
    book: { sections: [0, 1, 2, 3, 4].map((n) => ({ size: 100, createDocument: () => Promise.resolve(new DOMParser().parseFromString(chapter(n), 'application/xhtml+xml')) })) },
    getProgressOf: (i: number) => ({ tocItem: { label: `Chapter ${i}` } }),
  }
}

describe('book audio prepared ahead', () => {
  it('prepares the chapters ahead, deletes played ones, and clears a finished book', async () => {
    const { bookAudio } = await import('../bookAudio')
    const view = fakeView()
    bookAudio.setBook('book1', view as never, 'kokoro:af_heart', true, 2, true)
    bookAudio.setPosition(0)
    await vi.waitFor(() => expect(bookAudio.state.readyAhead).toBe(2), { timeout: 3000 })
    expect(bookAudio.state.sections.map((s) => [s.index, s.state])).toEqual([
      [0, 'playing'],
      [1, 'ready'],
      [2, 'ready'],
    ])
    expect(generated.every((g) => g.background)).toBe(true)
    expect(generated.some((g) => g.text.includes('chapter number 1'))).toBe(true)
    expect(generated.some((g) => g.text.includes('chapter number 3'))).toBe(false)
    // The read-aloud engine finds stored pieces by voice + text
    const piece = generated.find((g) => g.text.includes('chapter number 1'))!.text
    expect(bookAudio.has('af_heart', piece)).toBe(true)
    expect((await bookAudio.load('af_heart', piece)).samples.length).toBe(piece.length * 10)

    // Reading moves on to chapter 2: chapter 1 is played and deleted, chapter 4 gets prepared
    bookAudio.setPosition(2)
    await vi.waitFor(
      () =>
        expect(bookAudio.state.sections.map((s) => [s.index, s.state])).toEqual([
          [1, 'played'],
          [2, 'playing'],
          [3, 'ready'],
          [4, 'ready'],
        ]),
      { timeout: 3000 },
    )
    expect(bookAudio.has('af_heart', piece)).toBe(false)

    await bookAudio.finished('book1')
    expect(bookAudio.state.bytes).toBe(0)
  })
})

describe('the chapter being read', () => {
  const keys = Array.from({ length: 20 }, (_, i) => `v\npiece ${i}`)

  it('makes the rest of the chapter past what read aloud makes itself, skipping kept pieces', async () => {
    const { currentChapterTodo } = await import('../bookAudio')
    const kept = new Set([keys[12], keys[13]])
    expect(currentChapterTodo(keys, (k) => kept.has(k), 2, 8)).toEqual([10, 11, 14, 15, 16, 17, 18, 19])
    // Near the end: read aloud covers the rest itself
    expect(currentChapterTodo(keys, () => false, 15, 8)).toEqual([])
  })

  it('counts what is kept from where reading is, so a reload carries on instead of starting over', async () => {
    const { keptFrom } = await import('../bookAudio')
    const kept = new Set(keys.slice(5, 15))
    expect(keptFrom(keys, (k) => kept.has(k), 5)).toBe(10 / 15)
    expect(keptFrom(keys, (k) => keys.indexOf(k) >= 5, 5)).toBe(1)
  })

  it('keeps pieces read aloud made for the chapter being read', async () => {
    const { bookAudio } = await import('../bookAudio')
    bookAudio.setBook('book2', fakeView() as never, 'kokoro:af_heart', true, 1, true)
    bookAudio.setPosition(1)
    await vi.waitFor(() => expect(bookAudio.state.readyAhead).toBe(1), { timeout: 3000 })
    // A piece of chapter 1 as read aloud asks for it (same pieces as the plan)
    const { indexSpeechText, kokoroPieces } = await import('../readaloud')
    const doc = new DOMParser().parseFromString(chapter(1), 'application/xhtml+xml')
    const pieces = kokoroPieces(indexSpeechText(doc.body).text, 'en')
    bookAudio.reading('af_heart', pieces[0])
    for (const p of pieces) bookAudio.spoken('af_heart', p, Promise.resolve({ samples: new Float32Array(10), sampleRate: 24000 }))
    await vi.waitFor(() => expect(bookAudio.state.currentReady).toBe(true), { timeout: 3000 })
    expect(bookAudio.has('af_heart', pieces[0])).toBe(true)
    await bookAudio.finished('book2')
  })
})
