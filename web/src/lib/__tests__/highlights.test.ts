// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import type { Highlight } from '../db'
import { anchorOf, applyHighlights, articlePicture, locate, quoteText, textOf } from '../highlights'

const hl = (over: Partial<Highlight>): Highlight => ({
  id: 'h1',
  itemId: 'i',
  itemTitle: 'Why small teams ship faster',
  itemLink: 'https://example.com/a',
  feedTitle: 'The Verge',
  imageUrl: null,
  text: '',
  start: 0,
  prefix: '',
  color: 'yellow',
  note: '',
  createdAt: 1,
  ...over,
})

describe('locate', () => {
  it('uses the saved offset when the text still matches', () => {
    expect(locate('one two one two', { text: 'one', start: 8, prefix: 'two ' })).toBe(8)
  })
  it('falls back to the copy with the best matching prefix', () => {
    // Text shifted by 5 characters (full text vs feed text)
    expect(locate('Intro. one two one two', { text: 'one', start: 8, prefix: 'two ' })).toBe(15)
  })
  it('returns -1 when the text is gone', () => {
    expect(locate('nothing here', { text: 'missing', start: 0, prefix: '' })).toBe(-1)
  })
})

describe('applyHighlights', () => {
  it('wraps text across elements without changing the text', () => {
    const html = '<p>Hello <b>big</b> world</p><p>Second</p>'
    const out = applyHighlights(html, [hl({ text: 'big world', start: 6, color: 'green', note: 'x' })])
    const div = document.createElement('div')
    div.innerHTML = out
    expect(textOf(div)).toBe(textOf(Object.assign(document.createElement('div'), { innerHTML: html })))
    const marks = div.querySelectorAll('mark.hl-green.has-note')
    expect([...marks].map((m) => m.textContent)).toEqual(['big', ' world'])
    expect((marks[0] as HTMLElement).dataset.hl).toBe('h1')
  })
  it('leaves the html alone when a highlight is not found', () => {
    const html = '<p>Hello</p>'
    expect(applyHighlights(html, [hl({ text: 'bye', start: 0 })])).toBe(html)
  })
})

describe('anchorOf', () => {
  it('turns a selection into a trimmed offset, text and prefix', () => {
    const div = document.createElement('div')
    div.innerHTML = '<p>Hello <b>big</b> world</p>'
    document.body.append(div)
    const range = document.createRange()
    range.setStart(div.querySelector('p')!.firstChild!, 5) // the space before "big"
    range.setEnd(div.querySelector('b')!.firstChild!, 3)
    expect(anchorOf(div, range)).toEqual({ start: 6, text: 'big', prefix: 'Hello ' })
  })
})

describe('quoteText', () => {
  it('puts the quote, the source and the link on separate lines', () => {
    expect(quoteText(hl({ text: 'Ship it' }))).toBe('“Ship it”\n— Why small teams ship faster, The Verge\nhttps://example.com/a')
  })
})

describe('articlePicture', () => {
  it("prefers the feed's image", () => {
    expect(articlePicture('https://a/img.jpg', '<img src="https://b/x.jpg">')).toBe('https://a/img.jpg')
  })
  it('falls back to the first real picture in the text, skipping icons and pixels', () => {
    const html = '<img src="https://s/pixel.gif" width="1" height="1"><img src="https://s/icon.png" width="32"><p><img src="https://s/photo.jpg"></p>'
    expect(articlePicture(null, html)).toBe('https://s/photo.jpg')
  })
  it('gives null when there is no picture', () => {
    expect(articlePicture(null, '<p>text</p>')).toBeNull()
  })
})
