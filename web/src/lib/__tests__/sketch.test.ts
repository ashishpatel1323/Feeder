// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { encodeSketch, parseSketch } from '../sketch'
import { applyHighlights, textOf } from '../highlights'
import { highlightFromValue, highlightValue } from '../sync2'
import type { Highlight } from '../db'

const base: Highlight = {
  id: 's1',
  itemId: 'i',
  itemTitle: 't',
  itemLink: null,
  feedTitle: 'f',
  imageUrl: null,
  text: 'second',
  start: 0,
  prefix: '',
  color: 'pink',
  note: '',
  createdAt: 1,
}

describe('sketch', () => {
  it('crops, rounds and reads back', () => {
    const raw = encodeSketch({ h: 0, s: [{ c: 'blue', w: 3.24, p: [10.4, 300.6, 20, 320] }] })
    const sk = parseSketch(raw)!
    expect(sk.s[0].c).toBe('blue')
    expect(sk.s[0].w).toBe(3.2)
    // moved up to the top, with room above
    expect(sk.s[0].p[1]).toBeLessThan(40)
    expect(sk.h).toBeGreaterThanOrEqual(80)
    expect(parseSketch('nope')).toBeNull()
    expect(parseSketch('{"s":[]}')).toBeNull()
  })

  it('puts a strip at the end of the paragraph and keeps the text the same', () => {
    const html = '<p>first para</p><p>the second para</p><p>third</p>'
    const sketch = encodeSketch({ h: 0, s: [{ c: 'pink', w: 3, p: [0, 0, 100, 100] }] })
    const out = applyHighlights(html, [{ ...base, sketch }])
    const t = document.createElement('template')
    t.innerHTML = out
    const ps = t.content.querySelectorAll('p')
    expect(ps[1].lastElementChild?.classList.contains('feeder-sketch')).toBe(true)
    expect(ps[1].querySelector('mark')).toBeNull()
    expect(textOf(t.content)).toBe('first parathe second parathird')
  })

  it('keeps a sketch whose text is gone, at the end', () => {
    const sketch = encodeSketch({ h: 0, s: [{ c: 'pink', w: 3, p: [0, 0, 100, 100] }] })
    const out = applyHighlights('<p>nothing here</p>', [{ ...base, text: 'missing', sketch }])
    expect(out.endsWith('</span>')).toBe(true)
    expect(out).toContain('data-sketch="s1"')
  })

  it('syncs the drawing with the highlight', () => {
    const sketch = encodeSketch({ h: 0, s: [{ c: 'green', w: 3, p: [1, 2, 3, 4] }] })
    const v = highlightValue({ ...base, sketch })
    expect(v.sketch).toBe(sketch)
    expect(highlightFromValue('s1', v, 5).sketch).toBe(sketch)
    expect(highlightFromValue('s1', highlightValue(base), 5).sketch).toBeUndefined()
  })
})
