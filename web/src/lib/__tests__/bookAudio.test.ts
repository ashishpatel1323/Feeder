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
