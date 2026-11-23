import { describe, expect, it } from 'vitest'
import { newFeed, type Item } from '../db'
import { itemFacts } from '../itemview'
import { makeContext } from '../playlists'

let n = 0
function item(p: Partial<Item> = {}): Item {
  n++
  return { id: `f\n${n}`, feedUrl: 'f', guid: String(n), title: `t${n}`, link: null, contentHtml: '', snippet: '', imageUrl: null, author: null, sortTime: 0, firstSeen: 0, wordCount: 880, read: 0, readTime: null, readSynced: 1, saved: 0, ...p }
}
const ctx = (positions: [string, { t: number; d: number }][] = []) => makeContext({ feeds: new Map([['f', newFeed('f')]]), positions: new Map(positions) })

describe('itemFacts', () => {
  it('gives articles their reading time and Read', () => {
    const f = itemFacts(item(), ctx())
    expect(f).toMatchObject({ kind: 'article', kindLabel: 'Article', badge: '4 min', lengthText: '4 min read', primaryLabel: 'Read', readLabel: 'Mark read', progress: null })
  })

  it('gives an episode its real length, not the show notes reading time', () => {
    const f = itemFacts(item({ enclosureUrl: 'https://x/ep.mp3', duration: 1475, wordCount: 50 }), ctx())
    expect(f).toMatchObject({ kind: 'podcast', kindLabel: 'Podcast', badge: '24:35', lengthText: '25 min', primaryLabel: 'Play', readLabel: 'Mark played' })
  })

  it('shows time left and Resume for a started episode', () => {
    const it = item({ enclosureUrl: 'https://x/ep.mp3', duration: 1500 })
    const f = itemFacts(it, ctx([[it.id, { t: 300, d: 1500 }]]))
    expect(f.lengthText).toBe('20 min left')
    expect(f.primaryLabel).toBe('Resume')
    expect(f.progress).toBeCloseTo(0.2)
  })

  it('calls a YouTube item a video, with Watch and Mark watched', () => {
    const it = item({ link: 'https://www.youtube.com/watch?v=dQw4w9WgXcQ', read: 1 })
    const f = itemFacts(it, ctx([[it.id, { t: 590, d: 600 }]]))
    expect(f).toMatchObject({ kind: 'youtube', kindLabel: 'Video', lengthText: 'Watched', primaryLabel: 'Watch', readLabel: 'Mark unwatched', badge: '10:00' })
  })
})
