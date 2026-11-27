import 'fake-indexeddb/auto'
import { describe, expect, it, vi } from 'vitest'

const generated: string[] = []
vi.mock('../kokoro', () => ({
  KOKORO_PREFIX: 'kokoro:',
  kokoro: {
    load: () => Promise.resolve(),
    generate: (text: string) => {
      generated.push(text)
      return { id: generated.length, audio: Promise.resolve({ samples: new Float32Array(text.length * 10).fill(0.25), sampleRate: 24000 }) }
    },
  },
}))

const item = (n: number) => ({
  id: `feed\\n${n}`,
  feedUrl: 'feed',
  guid: String(n),
  title: `Article ${n}`,
  link: null,
  contentHtml: `<p>This is article number ${n}. It has two sentences to read.</p>`,
  snippet: '',
  imageUrl: null,
  author: null,
  sortTime: n,
  firstSeen: n,
  wordCount: 12,
  read: 0,
  readTime: null,
  readSynced: 0,
  saved: 0,
})

describe('playlist articles made ready offline', () => {
  it('prepares the requested articles, plays them from the same text, and deletes them once played', async () => {
    const { db } = await import('../db')
    await db.items.bulkPut([item(1), item(2)] as never[])
    const { playlistAudio } = await import('../playlistAudio')
    const { bookAudio } = await import('../bookAudio')
    await playlistAudio.start('pl1', ['feed\\n1', 'feed\\n2'], 'kokoro:af_heart', true)
    await vi.waitFor(() => expect(playlistAudio.state.ready.size).toBe(2), { timeout: 3000 })
    expect(playlistAudio.state.running).toBe(false)
    expect(generated.some((t) => t.includes('article number 2'))).toBe(true)

    const paras = await playlistAudio.parasFor('feed\\n1', 'kokoro:af_heart')
    expect(paras).toEqual(['This is article number 1. It has two sentences to read.'])
    // Another voice: not ready for it
    expect(await playlistAudio.parasFor('feed\\n1', 'kokoro:bm_george')).toBeNull()
    const piece = generated.find((t) => t.includes('article number 1'))!
    expect(bookAudio.has('af_heart', piece)).toBe(true)

    await playlistAudio.played('feed\\n1')
    expect(playlistAudio.state.ready.has('feed\\n1')).toBe(false)
    expect(bookAudio.has('af_heart', piece)).toBe(false)

    await playlistAudio.removeAll()
    expect(playlistAudio.state.ready.size).toBe(0)
  })
})
