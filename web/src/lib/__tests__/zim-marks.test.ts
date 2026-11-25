// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { textOf } from '../textanchor'
import { paintHighlights, parseZimItemId, rangeAt, selectionAnchor, zimItemId } from '../zim/marks'

describe('ZIM page highlights', () => {
  it('names a page as book and path, paths with slashes too', () => {
    expect(zimItemId('wikipedia_en_ray-charles', 'A/Ray_Charles')).toBe('zim:\nwikipedia_en_ray-charles\nA/Ray_Charles')
    expect(parseZimItemId(zimItemId('wikipedia_en_ray-charles', 'A/Ray_Charles'))).toEqual({ book: 'wikipedia_en_ray-charles', path: 'A/Ray_Charles' })
    expect(parseZimItemId(zimItemId('b', ''))).toEqual({ book: 'b', path: '' })
    expect(parseZimItemId('ebook:\nabc')).toBeNull()
    expect(parseZimItemId('https://feed\nguid')).toBeNull()
  })

  it('finds the range of an offset span across elements', () => {
    document.body.innerHTML = '<h1>Ray Charles</h1><p>An <b>American</b> singer and pianist.</p>'
    const full = textOf(document.body)
    const start = full.indexOf('American singer')
    const r = rangeAt(document.body, start, start + 'American singer'.length)
    expect(r?.toString()).toBe('American singer')
  })

  it('anchors a selection and finds the highlight again after the page shifted', () => {
    document.body.innerHTML = '<p>An <b>American</b> singer and pianist.</p>'
    const r = rangeAt(document.body, 3, 18)!
    document.getSelection()!.removeAllRanges()
    document.getSelection()!.addRange(r)
    const a = selectionAnchor(document)
    expect(a).toEqual({ start: 3, text: 'American singer', prefix: 'An ' })
    // Another device's page has more text before it: found by text and prefix
    document.body.innerHTML = '<div>Menu</div><p>An <b>American</b> singer and pianist.</p>'
    const painted = paintHighlights(document, [{ id: 'h1', color: 'green', ...a! }])
    expect(painted.get('h1')?.toString()).toBe('American singer')
  })

  it('skips highlights whose text is gone', () => {
    document.body.innerHTML = '<p>Something else</p>'
    expect(paintHighlights(document, [{ id: 'h1', color: 'yellow', start: 0, text: 'American singer', prefix: '' }]).size).toBe(0)
  })
})
