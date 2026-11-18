// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { cleanSpeechText, isFootnoteMarker, isSkippedLine } from '../speechclean'
import { ReadAloudEngine } from '../readaloud'

describe('speech text cleaning', () => {
  it('drops reference numbers', () => {
    expect(cleanSpeechText('The sky is blue.[1] Water is wet[12][13].')).toBe('The sky is blue. Water is wet.')
    expect(cleanSpeechText('Known since 1900[2, 3] and [citation needed] later[a].')).toBe('Known since 1900 and  later.')
    expect(cleanSpeechText('Keep [this] and arrays like a[i].')).toBe('Keep [this] and arrays like a.')
  })

  it('drops URLs but keeps sentence punctuation', () => {
    expect(cleanSpeechText('Read more at https://example.com/a?b=1.')).toBe('Read more at .')
    expect(cleanSpeechText('The report (https://example.com/report) says so.')).toBe('The report says so.')
    expect(cleanSpeechText('See www.example.org, then go.')).toBe('See , then go.')
    expect(cleanSpeechText('Wiki https://en.wikipedia.org/wiki/Foo_(bar) page')).toBe('Wiki  page')
  })

  it('drops parenthetical citations', () => {
    expect(cleanSpeechText('Sleep matters (Walker, 2017). Really.')).toBe('Sleep matters. Really.')
    expect(cleanSpeechText('As shown (Smith & Jones, 2019; Doe et al., 2020a).')).toBe('As shown.')
    expect(cleanSpeechText('Earlier work (see Müller, 2001, p. 12) agrees.')).toBe('Earlier work agrees.')
  })

  it('keeps parentheses that are not citations', () => {
    for (const s of ['It launched (March, 2021) quietly.', 'The film (2019) was fine.', 'Windows (Windows 2000) ran it.', 'A (big) deal.'])
      expect(cleanSpeechText(s)).toBe(s)
  })

  it('tells footnote superscripts from exponents and ordinals', () => {
    expect(isFootnoteMarker('1', false, 'd')).toBe(true)
    expect(isFootnoteMarker('[3]', false, '.')).toBe(true)
    expect(isFootnoteMarker('*', false, 'd')).toBe(true)
    expect(isFootnoteMarker('6', false, '0')).toBe(false)
    expect(isFootnoteMarker('st', true, '1')).toBe(false)
    expect(isFootnoteMarker('note', true, 'd')).toBe(true)
  })

  it('skips credit and copyright lines only when they are the whole paragraph', () => {
    expect(isSkippedLine('© 2026 Example Media. All rights reserved.')).toBe(true)
    expect(isSkippedLine('Photo: Jane Doe / Getty Images')).toBe(true)
    expect(isSkippedLine('Image credit: NASA')).toBe(true)
    expect(isSkippedLine('Photograph by John Smith for Reuters')).toBe(true)
    expect(isSkippedLine('The photo shows a cat sitting on a wall in the sun.')).toBe(false)
    expect(isSkippedLine('The company denied the allegations in a statement to Reuters.')).toBe(false)
    expect(isSkippedLine('Copyright law is changing for artists.')).toBe(false)
    expect(isSkippedLine('Copyright 2026 Example Media')).toBe(true)
    expect(isSkippedLine('Copyright law is changing, and here is what that means for the many artists who make a living online. ' .repeat(4))).toBe(false)
  })
})

describe('read aloud text', () => {
  const article = () => {
    const el = document.createElement('div')
    el.innerHTML = `
      <h2>Heading</h2>
      <p id="footnote-ref-1">Cats sleep a lot.<sup><a href="#fn1">1</a></sup> They are 10<sup>6</sup> times cuter[2] (Smith, 2020).</p>
      <figure><img alt="a cat"><figcaption>A cat. Photo: Getty Images</figcaption></figure>
      <table><caption>Table 1</caption><tr><td>Col A</td><td>Col B</td></tr></table>
      <p>Photo: Jane Doe / Getty Images</p>
      <p>More at <a href="https://example.com">https://example.com</a>.</p>
      <section class="footnotes" id="footnotes"><ol><li id="fn1">The footnote text.</li></ol></section>
      <p>© 2026 Example. All rights reserved.</p>`
    document.body.appendChild(el)
    return el
  }

  it('speaks only the paragraphs and headings, without clutter', () => {
    const engine = new ReadAloudEngine()
    engine.attach(article(), 'a')
    expect(engine.indexedText.replace(/\s+/g, ' ').trim()).toBe('Heading Cats sleep a lot. They are 106 times cuter. More at .')
  })

  it('keeps highlights and tap offsets on the right words', () => {
    const el = article()
    const engine = new ReadAloudEngine()
    engine.attach(el, 'b')
    const text = engine.indexedText
    const at = text.indexOf('cuter')
    expect(engine.rangeFor(at, at + 5)?.toString()).toBe('cuter')
    const p = el.querySelectorAll('p')[0]
    const last = p.lastChild as Text // " times cuter[2] (Smith, 2020)."
    expect(engine.offsetOf(last, last.data.indexOf('cuter'))).toBe(at)
    // A tap inside the skipped "[2]" lands on what is read next
    expect(text[engine.offsetOf(last, last.data.indexOf('[2]') + 1)]).toBe('.')
    const dot = text.lastIndexOf('.', text.indexOf('More') - 1)
    expect(engine.rangeFor(dot, dot + 1)?.toString()).toBe('.')
  })
})
