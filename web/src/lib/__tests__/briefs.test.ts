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
  batchPrompt,
  finalPrompt,
  parseBatch,
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
    const done = briefFromValue({
      ...asked,
      s: 'done',
      via: 'mac',
      hello: 'Hi',
      segs: [
        { i: 0, say: 'x' },
        { i: 7, say: 'gone' },
      ],
      picks: [{ i: 1, why: 'y' }],
      bye: 'Bye',
    })!
    expect(done.segs).toEqual([{ i: 0, say: 'x' }])
    expect(done.picks).toEqual([{ i: 1, why: 'y' }])
    expect(done.via).toBe('mac')
    expect(
      briefFromValue({
        ...asked,
        items: [{ id: 'k', t: 5 }, { nope: 1 }],
      })!.items,
    ).toEqual([{ id: 'k', t: '', u: null }])
  })

  it('takes at most MAX_BRIEF_ITEMS articles in playlist order', () => {
    const many = Array.from({ length: 60 }, (_, i) => ({
      id: `f\n${i}`,
      title: i === 0 ? '' : `T${i}`,
      link: null,
    }))
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
    const a = (n: number) => ({
      title: 't',
      link: null,
      text: 'x'.repeat(n),
    })
    expect(batches([a(10), a(10)], 1000)).toEqual([[0, 1]])
    expect(batches([a(700), a(700), a(10)], 1200)).toEqual([[0], [1, 2]])
    // One very long article still makes its own batch
    expect(batches([a(1_000_000)], 1000)).toEqual([[0]])
  })

  const taste = {
    topFeeds: ['Stratechery'],
    saved: ['On batteries'],
    highlights: ['"Small models win" (Chips)'],
  }

  it("asks for the morning radio template with the reader's taste, and fences off the article text", () => {
    const p = briefPrompt('Morning', [{ title: 'One', link: 'https://x', source: 'The Verge', text: '' }], taste, new Date(2026, 10, 28))
    expect(p).toContain('morning radio')
    expect(p).toContain('Saturday 28 November')
    expect(p).toContain('"picks"')
    expect(p).toContain('Feeds they read most: Stratechery')
    expect(p).toContain('<article n="1">')
    expect(p).toContain('<source>The Verge</source>')
    expect(p).toContain('could not be fetched')
    expect(briefPrompt('M', [], { topFeeds: [], saved: [], highlights: [] })).toContain('Not known yet')
    expect(batchPrompt('Morning', [{ title: 'Two', link: null, text: 'Body' }], 1)).toContain('<article n="2">')
    expect(finalPrompt('Morning', ['One'], ['Sum'], taste)).toContain('<summary>Sum</summary>')
  })

  it("reads Claude's answer, with or without a code fence", () => {
    const parsed = parseClaude(
      'Here it is:\n```json\n{"hello": "Good morning!", "rundown": [{"n": 2, "say": " Two says "}, {"n": 2, "say": "dup"}, {"n": 9, "say": "out"}], "picks": [{"n": 1, "why": "Your kind of thing"}], "signoff": "Bye"}\n```',
      2,
    )!
    expect(parsed).toEqual({
      hello: 'Good morning!',
      segs: [{ i: 0, say: '' }, { i: 1, say: 'Two says' }],
      picks: [{ i: 0, why: 'Your kind of thing' }],
      bye: 'Bye',
    })
    expect(parseClaude('No JSON at all', 2)).toBeNull()
    expect(parseClaude('{"other": 1}', 2)).toBeNull()
    expect([...parseBatch('{"articles": [{"n": 3, "summary": " S "}, {"n": "x"}]}')]).toEqual([[3, 'S']])
  })

  it('turns a brief into one script for copying and reading aloud', () => {
    const text = briefAsText({
      ...asked,
      s: 'done',
      hello: 'Morning!',
      segs: [{ i: 0, say: 'One says.' }],
      picks: [{ i: 1, why: 'Worth it.' }],
      bye: 'Bye.',
    })
    expect(text).toBe('Morning!\n\nOne. One says.\n\nMy pick for you today:\n\nTwo. Worth it.\n\nBye.')
    expect(briefAsText({ ...asked, s: 'done', text: 'raw' })).toBe('raw')
    const sourced = { ...asked, s: 'done' as const, items: [{ ...asked.items[0], f: 'The Verge' }], segs: [{ i: 0, say: '' }] }
    expect(briefAsText(sourced)).toBe('One, from The Verge.')
    expect(briefFromValue(sourced)!.items[0].f).toBe('The Verge')
  })

  it('syncs: sends briefs made here and applies newer ones from other devices', async () => {
    await putBrief('pl1', asked, 5000, false)
    const out = (await collectChanges(6000)).filter((c) => c.k === 'brief')
    expect(out).toEqual([{ k: 'brief', key: 'pl1', v: asked, ts: 5000 }])
    // Once exchanged it isn't sent again
    await db.syncKnown.put({ k: 'brief\npl1', v: asked, ts: 5000 })
    expect((await collectChanges(6000)).filter((c) => c.k === 'brief')).toEqual([])

    const result = { applied: 0, addedFeeds: [] as string[] }
    const done = {
      ...asked,
      s: 'done',
      hello: 'Hi',
      segs: [{ i: 0, say: 'One says' }],
      at: 7000,
    }
    await applyRemote({ k: 'brief', key: 'pl1', v: done }, 7000, result)
    expect((await loadBriefs()).pl1).toEqual({ v: done, ts: 7000 })
    // An older record doesn't win
    await applyRemote({ k: 'brief', key: 'pl1', v: asked }, 6500, result)
    expect((await loadBriefs()).pl1.v.s).toBe('done')
    expect((await collectChanges(8000)).filter((c) => c.k === 'brief')).toEqual([])
  })
})
