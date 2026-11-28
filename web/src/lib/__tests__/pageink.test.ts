// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { encodeInk, parseInk } from '../pageink'
import { highlightFromValue, highlightValue } from '../sync2'

describe('page ink', () => {
  it('reads back what it wrote, rounded', () => {
    const raw = encodeInk({ v: 1, sig: '604|18px|29.7px|Georgia|192', ox: 47.84, oy: -0.21, w: 238.44, h: 20.1, s: [{ c: 'pink', w: 2.24, p: [0, 1.06, 10.33, 2] }], prefs: { textSize: 18 } })
    const d = parseInk(raw)!
    expect(d.sig).toBe('604|18px|29.7px|Georgia|192')
    expect(d.ox).toBe(47.8)
    expect(d.s[0].p[2]).toBe(10.3)
    expect(d.prefs).toEqual({ textSize: 18 })
    expect(parseInk('{"sig":"x","s":[]}')).toBeNull()
    expect(parseInk('nope')).toBeNull()
  })

  it('syncs with the highlight', () => {
    const ink = encodeInk({ v: 1, sig: 's', ox: 0, oy: 0, w: 1, h: 1, s: [{ c: 'blue', w: 2, p: [0, 0] }] })
    const h = { id: 'i', itemId: 'x', itemTitle: '', itemLink: null, feedTitle: '', imageUrl: null, text: 'words', start: 0, prefix: '', color: 'pink' as const, note: '', createdAt: 1, ink }
    expect(highlightFromValue('i', highlightValue(h), 2).ink).toBe(ink)
  })
})
