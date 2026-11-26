/**
 * End-to-end check without touching the real sync server or Dropbox:
 *  - a local fake sync server serving records encrypted with a throwaway key
 *  - local RSS + podcast feeds (with a podcast:transcript) served by the same server
 *  - a generated EPUB registered as a local book
 * Then the real MCP server (dist/index.js) is started over stdio and every tool is called.
 *
 * Run: npm run build && node test/e2e.mjs
 */
import { createServer } from 'node:http'
import { mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { webcrypto as crypto } from 'node:crypto'
import JSZip from 'jszip'
import { DatabaseSync as Database } from 'node:sqlite'
import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js'
import { encryptString, decodeKey } from '../dist/vendor/crypto.js'

const dir = mkdtempSync(join(tmpdir(), 'feeder-mcp-e2e-'))
const rand = (n) => Buffer.from(crypto.getRandomValues(new Uint8Array(n))).toString('base64')
const secretKey = `${rand(16)}:${rand(32)}`
const syncCode = Buffer.from(crypto.getRandomValues(new Uint8Array(32))).toString('hex')
const keys = await decodeKey(secretKey)

let base = ''
const para = (n, topic) => Array.from({ length: n }, (_, i) => `<p>${topic} paragraph ${i + 1}. ${'The quick brown fox studies distributed systems and consensus. '.repeat(6)}</p>`).join('')

const rss = () => `<?xml version="1.0"?><rss version="2.0"><channel><title>Test Blog</title><link>${base}/</link>
<item><title>Raft consensus explained</title><link>${base}/raft</link><guid>${base}/g/raft-1</guid><pubDate>${new Date().toUTCString()}</pubDate><description><![CDATA[<h2>Leaders</h2>${para(8, 'Raft')}<h2>Logs</h2>${para(6, 'Log replication')}]]></description></item>
<item><title>Paxos for mortals</title><link>${base}/paxos</link><guid>${base}/g/paxos-1</guid><pubDate>${new Date(Date.now() - 86400000).toUTCString()}</pubDate><description><![CDATA[${para(5, 'Paxos')}]]></description></item>
<item><title>Short teaser post</title><link>${base}/teaser</link><guid>${base}/g/teaser-1</guid><pubDate>${new Date(Date.now() - 2 * 86400000).toUTCString()}</pubDate><description>Just a teaser.</description></item>
</channel></rss>`

const podcast = () => `<?xml version="1.0"?><rss version="2.0" xmlns:podcast="https://podcastindex.org/namespace/1.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"><channel><title>Test Pod</title>
<item><title>Episode 1: Gardening</title><guid>${base}/g/ep-1</guid><pubDate>${new Date().toUTCString()}</pubDate><enclosure url="${base}/ep1.mp3" type="audio/mpeg" length="1"/><itunes:duration>00:20:00</itunes:duration>
<podcast:transcript url="${base}/ep1.vtt" type="text/vtt"/><description>Show notes about tomatoes.</description></item>
</channel></rss>`

const vtt = () =>
  'WEBVTT\n\n' +
  Array.from({ length: 60 }, (_, i) => {
    const s = i * 20
    const t = (x) => `00:${String(Math.floor(x / 60)).padStart(2, '0')}:${String(x % 60).padStart(2, '0')}.000`
    return `${t(s)} --> ${t(s + 20)}\n<v Host>We talk about tomatoes and compost, minute ${Math.floor(s / 60)}. Heirloom varieties need sun.\n`
  }).join('\n')

let records = []
const server = createServer(async (req, res) => {
  const url = new URL(req.url, base)
  if (url.pathname === '/api/v2/sync') {
    let body = ''
    for await (const c of req) body += c
    const { since } = JSON.parse(body)
    const changes = records.filter((r) => r.seq > since)
    res.setHeader('content-type', 'application/json')
    res.end(JSON.stringify({ cursor: records.length, more: false, changes }))
    return
  }
  const pages = {
    '/feed.xml': [rss(), 'application/rss+xml'],
    '/pod.xml': [podcast(), 'application/rss+xml'],
    '/ep1.vtt': [vtt(), 'text/vtt'],
    '/teaser': [`<html><head><title>Short teaser post</title></head><body><article><h1>Short teaser post</h1>${para(10, 'Full article text about CRDTs')}</article></body></html>`, 'text/html'],
  }
  const p = pages[url.pathname]
  if (!p) {
    res.statusCode = 404
    res.end()
    return
  }
  res.setHeader('content-type', p[1])
  res.end(p[0])
})
await new Promise((r) => server.listen(0, '127.0.0.1', r))
base = `http://127.0.0.1:${server.address().port}`

const feedUrl = `${base}/feed.xml`
const podUrl = `${base}/pod.xml`
const now = Date.now()
const payloads = [
  { k: 'feed', key: feedUrl, v: { title: 'Test Blog', customTitle: '', tag: 'Tech', imageUrl: null, fullTextByDefault: false, openArticlesWith: '', alternateId: false, fetchOgImages: false, blockRules: '', allowRules: '' } },
  { k: 'feed', key: podUrl, v: { title: 'Test Pod', customTitle: '', tag: 'Podcasts', imageUrl: null, fullTextByDefault: false, openArticlesWith: '', alternateId: false, fetchOgImages: false, blockRules: '', allowRules: '' } },
  { k: 'read', key: `${feedUrl}\n${base}/g/paxos-1`, v: true },
  { k: 'saved', key: `${feedUrl}\n${base}/g/raft-1`, v: true, a: { title: 'Raft consensus explained', link: `${base}/raft`, sortTime: now } },
  { k: 'saved', key: `https://gone.example/feed\nold-1`, v: true, a: { title: 'An old saved article', link: `${base}/teaser`, sortTime: now - 9e9, snippet: 'Old one' } },
  {
    k: 'playlist',
    key: 'pl-manual',
    v: { name: 'Weekend reading', icon: '📚', color: '#1d4ed8', smart: false, rules: {}, items: [`${feedUrl}\n${base}/g/raft-1`, `${podUrl}\n${base}/g/ep-1`, `${feedUrl}\n${base}/g/teaser-1`, `${feedUrl}\nmissing`], sort: 'newest', createdAt: 1 },
  },
  { k: 'playlist', key: 'starter-short-reads', v: { deleted: true } },
  { k: 'highlight', key: 'h1', v: { item: `${feedUrl}\n${base}/g/raft-1`, title: 'Raft consensus explained', link: null, feed: 'Test Blog', image: null, text: 'Raft paragraph 2', start: 0, prefix: '', color: 'yellow', note: 'Leader election is the key idea', createdAt: now } },
  { k: 'highlight', key: 'h2', v: { item: 'ebook:\nbook-1', title: 'The Test Book', link: null, feed: '', image: null, text: 'second chapter about owls', start: 0, prefix: '', color: 'green', note: '', createdAt: now, cfi: 'epubcfi(/6/8)' } },
  { k: 'bookpos', key: 'book-1', v: { cfi: 'epubcfi(/6/8)', f: 0.4 } },
]
records = await Promise.all(payloads.map(async (p, i) => ({ id: `rec${String(i).padStart(14, '0')}`, ts: now, seq: i + 1, data: await encryptString(JSON.stringify(p), keys) })))

// An EPUB: cover, copyright, contents, then Part One with 2 chapters and Part Two with 2
const zip = new JSZip()
zip.file('mimetype', 'application/epub+zip')
zip.file('META-INF/container.xml', `<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>`)
const files = ['cover', 'copyright', 'toc', 'ch1', 'ch2', 'ch3', 'ch4', 'notes']
const body = (f) =>
  f.startsWith('ch')
    ? `<h1>Chapter ${f.slice(2)}</h1>${para(12, `Chapter ${f.slice(2)} ${f === 'ch2' ? 'second chapter about owls' : 'birds'}`)}`
    : `<h1>${f}</h1><p>${f} page</p>`
for (const f of files) zip.file(`OEBPS/${f}.xhtml`, `<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>${f}</title></head><body>${body(f)}</body></html>`)
zip.file(
  'OEBPS/nav.xhtml',
  `<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc"><ol>
<li><a href="cover.xhtml">Cover</a></li><li><a href="copyright.xhtml">Copyright</a></li><li><a href="toc.xhtml">Contents</a></li>
<li><span>Part One</span><ol><li><a href="ch1.xhtml">The Beginning</a></li><li><a href="ch2.xhtml">Owls</a></li></ol></li>
<li><span>Part Two</span><ol><li><a href="ch3.xhtml">Middle</a></li><li><a href="ch4.xhtml">End</a></li></ol></li>
<li><a href="notes.xhtml">Notes</a></li></ol></nav></body></html>`,
)
zip.file(
  'OEBPS/content.opf',
  `<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>The Test Book</dc:title><dc:creator>A. Writer</dc:creator></metadata>
<manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>${files.map((f) => `<item id="${f}" href="${f}.xhtml" media-type="application/xhtml+xml"/>`).join('')}</manifest>
<spine>${files.map((f) => `<itemref idref="${f}"/>`).join('')}</spine></package>`,
)
const epubPath = join(dir, 'test-book.epub')
writeFileSync(epubPath, await zip.generateAsync({ type: 'nodebuffer' }))

// Register the book as if Dropbox had listed it, with a local copy already present
const db = new Database(join(dir, 'index.db'))
db.close()

const env = { ...process.env, FEEDER_MCP_DIR: dir, FEEDER_SYNC_SERVER: base, FEEDER_SYNC_CODE: syncCode, FEEDER_SYNC_KEY: secretKey, FEEDER_ZIM_DIRS: join(dir, 'nozim'), FEEDER_MCP_NO_BACKGROUND: '1' }
const transport = new StdioClientTransport({ command: process.execPath, args: [new URL('../dist/index.js', import.meta.url).pathname], env, stderr: 'inherit' })
const client = new Client({ name: 'e2e', version: '1' })
await client.connect(transport)

const call = async (name, args = {}) => {
  const r = await client.callTool({ name, arguments: args })
  const data = JSON.parse(r.content[0].text)
  if (r.isError) console.log(`  ! ${name} error:`, data.error)
  return data
}
let failures = 0
const check = (cond, msg) => {
  console.log(`${cond ? 'ok  ' : 'FAIL'} ${msg}`)
  if (!cond) failures++
}

const tools = (await client.listTools()).tools.map((t) => t.name)
check(tools.length >= 15, `tools listed: ${tools.join(', ')}`)
const prompts = (await client.listPrompts()).prompts.map((p) => p.name)
check(prompts.includes('summarize_playlist') && prompts.includes('summarize_book_chapters'), `prompts: ${prompts.join(', ')}`)

const ref = await call('refresh', { what: ['sync', 'feeds'] })
check(ref.sync?.records === payloads.length, `sync pulled ${ref.sync?.records} records`)
check(ref.feeds?.feeds === 2 && ref.feeds.errors === 0, `fetched ${ref.feeds?.feeds} feeds, ${ref.feeds?.added} items`)

// Book: put it in the files table now that the db exists
const db2 = new Database(join(dir, 'index.db'))
db2.prepare(`INSERT INTO files (id, kind, path, name, size, title, local_path, seen_at) VALUES ('book-1', 'book', '/Ebooks/test-book.epub', 'test-book.epub', 1, 'test book', ?, ?)`).run(epubPath, Date.now())
db2.close()

const feeds = await call('list_feeds')
check(feeds.folders.length === 2, `list_feeds: ${feeds.folders.map((f) => f.name).join(', ')}`)

const items = await call('list_items', { read: false })
check(items.items.some((i) => i.title === 'Raft consensus explained' && i.saved) && !items.items.some((i) => i.title === 'Paxos for mortals'), `list_items unread: ${items.items.map((i) => i.title).join(' | ')}`)

const pls = await call('list_playlists')
check(pls.playlists.some((p) => p.name === 'Weekend reading') && !pls.playlists.some((p) => p.id === 'starter-short-reads') && pls.playlists.some((p) => p.id === 'starter-morning-drive'), `playlists: ${pls.playlists.map((p) => `${p.name}(${p.item_count})`).join(', ')}`)

const pl = await call('get_playlist', { playlist: 'weekend' })
check(pl.items?.length === 3 && pl.not_on_this_mac === 1, `get_playlist fuzzy "weekend": ${pl.items?.map((i) => `${i.kind}:${i.title}`).join(' | ')}; missing ${pl.not_on_this_mac}`)

let content = await call('get_playlist_content', { playlist: 'Weekend reading', per_item_tokens: 400, max_tokens: 1200 })
let pages = 1
const sections = [...content.sections]
while (content.next_cursor) {
  content = await call('get_playlist_content', { playlist: 'Weekend reading', per_item_tokens: 400, max_tokens: 1200, cursor: content.next_cursor })
  sections.push(...content.sections)
  pages++
}
check(sections.length === 3, `get_playlist_content: ${sections.length} sections over ${pages} pages`)
const ep = sections.find((s) => s.item.kind === 'podcast')
check(ep?.text_from === 'transcript:feed' && /tomatoes/.test(ep.chunks[0].text) && /, \d+:\d\d$/.test(ep.chunks[0].cite), `podcast section uses transcript (${ep?.text_from}), cite "${ep?.chunks[0]?.cite}"`)
const teaser = sections.find((s) => s.item.title === 'Short teaser post')
check(teaser?.text_from === 'full_text' && /CRDTs/.test(teaser.chunks[0].text), `teaser got full text (${teaser?.text_from})`)

const books = await call('list_books')
check(books.books[0]?.progress_pct === 40 && books.books[0].highlights === 1, `list_books: ${JSON.stringify(books.books[0])}`)
const toc = await call('get_book_toc', { book: 'test book' })
check(toc.toc?.filter((e) => e.kind === 'chapter').length === 4 && toc.toc[0].kind === 'front' && toc.toc.at(-1).kind === 'back', `toc: ${toc.toc?.map((e) => `${e.chapter ?? '-'}:${e.label}[${e.kind}]`).join(' ')}`)
let rb = await call('read_book', { book: 'The Test Book', chapters: '1-3', max_tokens: 1500 })
check(rb.mapped?.map((m) => m.label).join(',') === 'The Beginning,Owls,Middle', `read_book 1-3 maps to ${rb.mapped?.map((m) => m.label).join(', ')}`)
const bookSections = [...rb.sections]
while (rb.next_cursor) {
  rb = await call('read_book', { book: 'The Test Book', chapters: '1-3', max_tokens: 1500, cursor: rb.next_cursor })
  bookSections.push(...rb.sections)
}
const text = bookSections.flatMap((s) => s.chunks.map((c) => c.text)).join(' ')
check(/Chapter 1 birds/.test(text) && /owls/.test(text) && /Chapter 3 birds/.test(text) && !/Chapter 4/.test(text) && !/copyright page/.test(text), `read_book text covers ch 1–3 only (${bookSections.length} sections)`)
check(bookSections.some((s) => s.highlights_here?.length === 1), 'ebook highlight attached to its chapter')

const hl = await call('list_highlights', { has_note: true })
check(hl.highlights.length === 1 && hl.highlights[0].note.includes('Leader'), `list_highlights with note: ${hl.highlights.length}`)

const s1 = await call('search', { query: 'leader election' })
check(s1.hits.some((h) => h.kind === 'highlight'), `search "leader election": ${s1.hits.map((h) => h.kind + ':' + h.title).join(' | ')}`)
const s2 = await call('search', { query: 'heirloom tomatoes', kinds: ['podcast'] })
check(s2.hits.length > 0, `search podcast transcript: ${s2.hits.length} hits`)
const s3 = await call('search', { query: 'owls', book: 'test book' })
check(s3.hits.length > 0 && s3.hits[0].kind === 'book', `search inside book: ${s3.hits.length} hits`)

const item = await call('get_item', { id: items.items.find((i) => i.title.startsWith('Raft')).id, max_tokens: 600 })
check(item.chunks.length >= 1 && item.next_cursor, `get_item pages (first page ${item.chunks.length} chunks, more: ${!!item.next_cursor})`)

const res = await client.listResources()
check(res.resources.some((r) => r.uri.startsWith('feeder://playlist/')), `resources: ${res.resources.length}`)
const pr = await client.getPrompt({ name: 'summarize_book_chapters', arguments: { book: 'The Test Book', chapters: '1-3' } })
check(/read_book/.test(pr.messages[0].content.text), 'prompt summarize_book_chapters renders')

const st = await call('status')
console.log('status:', JSON.stringify(st))

await client.close()
server.close()
console.log(failures ? `\n${failures} check(s) failed` : '\nAll checks passed')
process.exit(failures ? 1 : 0)
