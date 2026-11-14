import { describe, expect, it } from 'vitest'
import { parseFeed, relativeLinkIntoAbsolute } from '../feedparse'
import { makeFilter } from '../filterrules'

const RSS = `<?xml version="1.0"?>
<rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/" xmlns:media="http://search.yahoo.com/mrss/">
<channel><title>Example</title><link>https://ex.com/</link>
<item><title>One &amp; only</title><link>https://ex.com/1</link><guid isPermaLink="false">abc-1</guid>
<description>&lt;p&gt;Hello &lt;b&gt;world&lt;/b&gt;&lt;/p&gt;</description><pubDate>Tue, 10 Jun 2025 04:00:00 GMT</pubDate>
<media:thumbnail url="https://ex.com/1.jpg"/></item>
<item><title>Two</title><link>/2</link><content:encoded><![CDATA[<p>Body <img src="/img.png"></p>]]></content:encoded></item>
<item><title>Three</title><guid>tag:ex.com,2025:3</guid></item>
</channel></rss>`

const ATOM = `<?xml version="1.0" encoding="utf-8"?>
<feed xmlns="http://www.w3.org/2005/Atom"><title>Atom ex</title><link href="https://a.com/"/>
<entry><id>urn:uuid:1225c695</id><title type="html">A &lt;i&gt;b&lt;/i&gt;</title>
<link rel="alternate" href="https://a.com/x"/><updated>2025-01-01T00:00:00Z</updated><summary>S</summary></entry>
</feed>`

describe('parseFeed', () => {
  it('parses RSS with Feeder-compatible ids', () => {
    const f = parseFeed(RSS, 'https://ex.com/blog/feed.xml')
    expect(f.title).toBe('Example')
    const [a, b, c] = f.articles
    // guid resolved against the feed URL like Java's URL(base, spec)
    expect(a.guid).toBe('https://ex.com/blog/abc-1')
    expect(a.title).toBe('One & only')
    expect(a.snippet).toBe('Hello world')
    expect(a.imageUrl).toBe('https://ex.com/1.jpg')
    expect(a.published).toBe(Date.UTC(2025, 5, 10, 4))
    // no guid: falls back to the (resolved) link
    expect(b.guid).toBe('https://ex.com/2')
    expect(b.imageUrl).toBe('https://ex.com/img.png')
    // unknown scheme kept as written
    expect(c.guid).toBe('tag:ex.com,2025:3')
  })

  it('parses Atom', () => {
    const f = parseFeed(ATOM, 'https://a.com/feed')
    expect(f.articles[0].guid).toBe('urn:uuid:1225c695')
    expect(f.articles[0].title).toBe('A b')
    expect(f.articles[0].link).toBe('https://a.com/x')
  })

  it('parses JSON Feed', () => {
    const f = parseFeed(
      JSON.stringify({ version: 'https://jsonfeed.org/version/1.1', title: 'J', items: [{ id: '42', url: 'https://j.com/42', content_text: 'hi' }] }),
      'https://j.com/feed.json',
    )
    expect(f.articles[0].guid).toBe('https://j.com/42')
    expect(f.articles[0].snippet).toBe('hi')
  })

  it('uses alternate ids when ids repeat', () => {
    const dup = RSS.replace('tag:ex.com,2025:3', 'abc-1')
    const f = parseFeed(dup, 'https://ex.com/blog/feed.xml')
    expect(f.articles[0].guid.startsWith('https://ex.com/blog/abc-1|')).toBe(true)
  })

  it('rejects XHTML pages and other XML', () => {
    const xhtml = '<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>Site</title></head><body/></html>'
    expect(() => parseFeed(xhtml, 'https://s.com/')).toThrow()
    expect(() => parseFeed('<root><item/></root>', 'https://s.com/')).toThrow()
    // a genuinely empty feed is fine
    expect(parseFeed('<rss version="2.0"><channel><title>E</title></channel></rss>', 'https://e.com/f').articles).toEqual([])
  })

  it('keeps markup in Atom xhtml content', () => {
    const atom = `<feed xmlns="http://www.w3.org/2005/Atom"><title>X</title><entry><id>tag:x,1</id><title>T</title>
      <content type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml"><p>One <a href="https://l.com/">link</a></p><p><img src="https://i.com/a.png"/></p></div></content></entry></feed>`
    const html = parseFeed(atom, 'https://x.com/feed').articles[0].contentHtml
    expect(html).toContain('<a href="https://l.com/">link</a>')
    expect(html).toContain('<img src="https://i.com/a.png"')
    expect(html).not.toContain('xmlns')
  })

  it('keeps absolute links byte-for-byte', () => {
    expect(relativeLinkIntoAbsolute('https://x.com/', 'https://EX.com')).toBe('https://EX.com')
  })
})

describe('filter rules', () => {
  it('blocks and allows like the Android app', () => {
    const f = parseFeed(RSS, 'https://ex.com/feed.xml')
    const keep = makeFilter('Title=^Two$', '')
    expect(f.articles.filter(keep).map((a) => a.title)).toEqual(['One & only', 'Three'])
    const allow = makeFilter('', 'Content=world')
    expect(f.articles.filter(allow).map((a) => a.title)).toEqual(['One & only'])
  })
})
