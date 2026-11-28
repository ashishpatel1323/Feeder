import 'fake-indexeddb/auto'
import { describe, expect, it } from 'vitest'
import {
  batches,
  briefAsText,
  briefFromValue,
  briefItems,
  briefPrompt,
  isPending,
  loadBriefs,
  MAX_BRIEF_ITEMS,
  overviewPrompt,
  parseClaude,
  putBrief,
  STALE_ASK_MS,
  type BriefValue,
} from '../briefs'
import { db } from '../db'
import { applyRemote, collectChanges } from '../sync2'

const asked: BriefValue = {
  s: 'asked',
  asked: 1000,
  name: 'Morning',
  items: [
    { id: 'https://a.example/feed\n1', t: 'One', u: 'https://a.example/1' },
    { id: 'https://a.example/feed\n2', t: 'Two', u: null },
  ],
  by: 'Pixel',
}

describe('Playlist briefs (briefs.ts)', () => {
  it('reads synced records and drops unknown shapes', () => {
    expect(briefFromValue(asked)).toEqual(asked)
    expect(briefFromValue({ s: 'nope' })).toBeNull()
    expect(briefFromValue(null)).toBeNull()
    const done = briefFromValue({ ...asked, s: 'done', ov: ['a', 3], sums: ['x', ''], items: [{ id: 'k', t: 5 }, { nope: 1 }] })!
    expect(done.items).toEqual([{ id: 'k', t: '', u: null }])
    expect(done.ov).toEqual(['a', ''])
  })

  it('takes at most MAX_BRIEF_ITEMS articles in playlist order', () => {
    const many = Array.from({ length: 60 }, (_, i) => ({ id: `f\n${i}`, title: i === 0 ? '' : `T${i}`, link: null }))
    const items = briefItems(many)
    expect(items).toHaveLength(MAX_BRIEF_ITEMS)
    expect(items[0]).toEqual({ id: 'f\n0', t: 'Untitled', u: null })
  })

  it('counts asked and working as pending until stale', () => {
    expect(isPending(asked, 2000)).toBe(true)
    expect(isPending({ ...asked, s: 'working' }, 2000)).toBe(true)
    expect(isPending({ ...asked, s: 'done' }, 2000)).toBe(false)
    expect(isPending(asked, 1000 + STALE_ASK_MS + 1)).toBe(false)
    expect(isPending(undefined)).toBe(false)
  })

  it('splits long playlists into batches and keeps order', () => {
    const a = (n: number) => ({ title: 't', link: null, text: 'x'.repeat(n) })
    expect(batches([a(10), a(10)], 1000)).toEqual([[0, 1]])
    expect(batches([a(700), a(700), a(10)], 1200)).toEqual([[0], [1, 2]])
    // One very long article still makes its own batch
    expect(batches([a(1_000_000)], 1000)).toEqual([[0]])
  })

  it('asks for JSON, numbers articles across batches and fences off the article text', () => {
    const p = briefPrompt('Morning', [{ title: 'Two', link: null, text: '' }], 1, false)
    expect(p).toContain('<article n="2">')
    expect(p).toContain('could not be fetched')
    expect(p).not.toContain('"overview"')
    expect(briefPrompt('Morning', [{ title: 'One', link: 'https://x', text: 'Body' }], 0, true)).toContain('"overview"')
    expect(overviewPrompt('Morning', ['One'], ['Sum'])).toContain('1. One: Sum')
  })

  it("reads Claude's answer, with or without a code fence", () => {
    const parsed = parseClaude('Here it is:\n```json\n{"overview": ["Big"], "articles": [{"n": 2, "summary": " Two says "}, {"n": "x", "summary": "bad"}]}\n```')!
    expect(parsed.overview).toEqual(['Big'])
    expect([...parsed.summaries]).toEqual([[2, 'Two says']])
    expect(parseClaude('No JSON at all')).toBeNull()
    expect(parseClaude('{"other": 1}')).toBeNull()
  })

  it('turns a brief into plain text for copying', () => {
    const text = briefAsText({ ...asked, s: 'done', ov: ['Big'], sums: ['One says', ''] })
    expect(text).toBe('Morning: brief\n\n• Big\n\nOne\nOne says')
    expect(briefAsText({ ...asked, s: 'done', text: 'raw' })).toBe('raw')
  })

  it('syncs: sends briefs made here and applies newer ones from other devices', async () => {
    await putBrief('pl1', asked, 5000, false)
    const out = (await collectChanges(6000)).filter((c) => c.k === 'brief')
    expect(out).toEqual([{ k: 'brief', key: 'pl1', v: asked, ts: 5000 }])
    // Once exchanged it isn't sent again
    await db.syncKnown.put({ k: 'brief\npl1', v: asked, ts: 5000 })
    expect((await collectChanges(6000)).filter((c) => c.k === 'brief')).toEqual([])

    const result = { applied: 0, addedFeeds: [] as string[] }
    const done = { ...asked, s: 'done', ov: ['Big'], sums: ['One says', 'Two says'], at: 7000 }
    await applyRemote({ k: 'brief', key: 'pl1', v: done }, 7000, result)
    expect((await loadBriefs()).pl1).toEqual({ v: done, ts: 7000 })
    // An older record doesn't win
    await applyRemote({ k: 'brief', key: 'pl1', v: asked }, 6500, result)
    expect((await loadBriefs()).pl1.v.s).toBe('done')
    expect((await collectChanges(8000)).filter((c) => c.k === 'brief')).toEqual([])
  })
})
