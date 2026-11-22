// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { countWords, paragraphsOf, takeWords, WheelFlipper } from '../flip'

describe('paragraphsOf', () => {
  it('keeps paragraphs and drops captions and scripts', () => {
    const html = '<p>One  two</p><figure><img src="x"><figcaption>Caption</figcaption></figure><script>x()</script><ul><li><p>Item</p></li></ul>'
    expect(paragraphsOf(html)).toEqual(['One two', 'Item'])
  })
  it('falls back to plain text', () => {
    expect(paragraphsOf('First para.\n\nSecond para.')).toEqual(['First para.', 'Second para.'])
    expect(paragraphsOf('<div>Loose text <b>here</b></div>')).toEqual(['Loose text here'])
  })
})

describe('takeWords', () => {
  const ps = ['a b c', 'd e f, g']
  it('cuts at a word count with an ellipsis', () => {
    expect(takeWords(ps, 3)).toEqual(['a b c'])
    expect(takeWords(ps, 5)).toEqual(['a b c', 'd e…'])
    expect(takeWords(ps, 6)).toEqual(['a b c', 'd e f…'])
    expect(takeWords(ps, 99)).toEqual(ps)
    expect(countWords(ps)).toBe(7)
  })
})

describe('WheelFlipper', () => {
  it('turns one page per gesture, ignoring inertia', () => {
    const w = new WheelFlipper(60, 650, 220)
    expect(w.push(0, 30, 0)).toBe(0)
    expect(w.push(0, 40, 10)).toBe(1)
    // inertia keeps arriving: no more turns
    for (let t = 20; t < 1200; t += 16) expect(w.push(0, 20, t)).toBe(0)
    // quiet, then a new swipe back
    expect(w.push(0, -80, 2000)).toBe(-1)
  })
  it('uses horizontal swipes too', () => {
    expect(new WheelFlipper().push(-90, 5, 0)).toBe(-1)
  })
})
