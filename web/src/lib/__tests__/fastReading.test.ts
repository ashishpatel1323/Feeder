// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { applyFastReading, fastReadingRanges, fixationLength } from '../fastReading'

const bracket = (text: string) => {
  let out = ''
  let last = 0
  for (const [s, e] of fastReadingRanges(text)) {
    out += text.slice(last, s) + '[' + text.slice(s, e) + ']'
    last = e
  }
  return out + text.slice(last)
}

describe('fast reading', () => {
  it('bolds word starts like text-vide', () => {
    expect(bracket('The quick brown fox jumps over the lazy dog.')).toBe('[T]he [qui]ck [bro]wn [f]ox [jum]ps [ov]er [t]he [la]zy [d]og.')
    expect(bracket('a I it')).toBe('[a] [I] [i]t')
    expect(bracket('Reading information, café 3D 2024!')).toBe('[Read]ing [inform]ation, [ca]fé [3]D 2024!')
  })

  it('goes from most to least bold', () => {
    expect(fixationLength(7, 1)).toBe(5)
    expect(fixationLength(7, 3)).toBe(4)
    expect(fixationLength(0)).toBe(0)
  })

  it('adds elements without changing the text, and leaves code alone', () => {
    const html = '<p>Hello <a href="https://x.y/">world</a> &amp; friends</p><pre><code>const value = 1</code></pre>'
    const out = applyFastReading(html)
    expect(out).toBe(
      '<p><b class="fr">Hel</b>lo <a href="https://x.y/"><b class="fr">wor</b>ld</a> &amp; <b class="fr">frie</b>nds</p><pre><code>const value = 1</code></pre>',
    )
    const a = document.createElement('div')
    a.innerHTML = html
    const b = document.createElement('div')
    b.innerHTML = out
    expect(b.textContent).toBe(a.textContent)
  })
})
