/** The MCP surface: tools, resources and prompts over the local Feeder index. */
import { McpServer, ResourceTemplate } from '@modelcontextprotocol/sdk/server/mcp.js'
import { z } from 'zod'
import { getItem, itemText, kindOfRow, type ItemRow } from './content.js'
import { getKv, type Db } from './db.js'
import { BOOK_ITEM_PREFIX, chapterChunks, extOf, listBooks, openBook, pickChapters, READABLE, type BookRow, type TocEntry } from './ebooks.js'
import { runOnce } from './indexer.js'
import { itemSeconds, kindOf, listPlaylists, playlistItems, playStatus, type PItem, type Playlist } from './playlists.js'
import { search, type SearchFilters } from './search.js'
import type { Source, StoredChunk } from './store.js'
import { clock, estTokens } from './text.js'
import { listZims, zimArticle, zimIndexed, archive, type ZimRow } from './zims.js'

// ---------------------------------------------------------------------------------------------
// Ids in tool I/O: Feeder ids hold newlines and URLs, so they travel as opaque base64url strings

const b64 = (s: string) => Buffer.from(s, 'utf8').toString('base64url')
const unb64 = (s: string) => Buffer.from(s, 'base64url').toString('utf8')
export const itemRef = (id: string) => `it_${b64(id)}`
const fromRef = (ref: string, prefix: string) => (ref.startsWith(prefix) ? unb64(ref.slice(prefix.length)) : ref)
export const itemIdOf = (ref: string) => fromRef(ref, 'it_')

const DAY = 86_400_000
const date = (ms: number | null | undefined) => (ms ? new Date(ms).toISOString().slice(0, 10) : null)
const minutes = (s: number | null) => (s == null ? null : Math.max(1, Math.round(s / 60)))

function parseWhen(v: string | undefined): number | undefined {
  if (!v) return undefined
  const rel = v.match(/^(\d+)\s*(d|day|days|w|week|weeks|h|hours?)$/i)
  if (rel) {
    const n = Number(rel[1])
    const unit = rel[2][0].toLowerCase()
    return Date.now() - n * (unit === 'w' ? 7 * DAY : unit === 'h' ? 3_600_000 : DAY)
  }
  const t = Date.parse(v)
  return Number.isFinite(t) ? t : undefined
}

// ---------------------------------------------------------------------------------------------
// Paging: a cursor is an offset into an ordered list of chunks

interface PageChunk {
  chunk_id: number
  heading?: string
  locator: Record<string, unknown>
  cite: string
  text: string
}

const encCursor = (o: unknown) => b64(JSON.stringify(o))
const decCursor = <T>(c: string | undefined, d: T): T => {
  if (!c) return d
  try {
    return JSON.parse(unb64(c)) as T
  } catch {
    return d
  }
}

function citeFor(title: string, c: StoredChunk, extra = ''): string {
  const loc = JSON.parse(c.locator) as Record<string, unknown>
  if (typeof loc.start_ms === 'number' && (loc.start_ms > 0 || (loc.end_ms as number) > 0)) return `${title}, ${clock(loc.start_ms)}`
  return extra ? `${title} ${extra}` : title
}

function toPage(chunks: StoredChunk[], from: number, budget: number, cite: (c: StoredChunk) => string): { out: PageChunk[]; next: number | null; used: number } {
  const out: PageChunk[] = []
  let used = 0
  let i = from
  for (; i < chunks.length; i++) {
    const c = chunks[i]
    if (out.length && used + c.tokens > budget) break
    out.push({ chunk_id: c.rowid, ...(c.heading ? { heading: c.heading } : {}), locator: JSON.parse(c.locator), cite: cite(c), text: c.text })
    used += c.tokens
  }
  return { out, next: i < chunks.length ? i : null, used }
}

// ---------------------------------------------------------------------------------------------
// Name resolution

function norm(s: string) {
  return s
    .toLowerCase()
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/[^\p{L}\p{N}]+/gu, ' ')
    .trim()
}

function trigrams(s: string): Set<string> {
  const t = `  ${norm(s)} `
  const out = new Set<string>()
  for (let i = 0; i < t.length - 2; i++) out.add(t.slice(i, i + 3))
  return out
}

function similarity(a: string, b: string): number {
  const x = trigrams(a)
  const y = trigrams(b)
  let n = 0
  for (const g of x) if (y.has(g)) n++
  return n / Math.max(1, Math.min(x.size, y.size))
}

type Resolved<T> = { hit: T; via: string } | { ambiguous: { id: string; name: string }[] } | { none: true }

function resolve<T>(list: T[], query: string, id: (t: T) => string, name: (t: T) => string): Resolved<T> {
  const q = query.trim()
  const byId = list.find((t) => id(t) === q)
  if (byId) return { hit: byId, via: 'id' }
  const exact = list.filter((t) => name(t) === q)
  if (exact.length === 1) return { hit: exact[0], via: 'exact name' }
  const ci = list.filter((t) => norm(name(t)) === norm(q))
  if (ci.length === 1) return { hit: ci[0], via: 'name' }
  const contains = list.filter((t) => norm(name(t)).includes(norm(q)))
  if (contains.length === 1) return { hit: contains[0], via: 'partial name' }
  const scored = list.map((t) => ({ t, s: similarity(name(t), q) })).sort((a, b) => b.s - a.s)
  if (scored[0] && scored[0].s >= 0.45 && (!scored[1] || scored[0].s - scored[1].s >= 0.15)) return { hit: scored[0].t, via: 'fuzzy name' }
  const close = (contains.length > 1 ? contains.map((t) => ({ t, s: 1 })) : scored.filter((x) => x.s >= 0.3)).slice(0, 8)
  if (close.length) return { ambiguous: close.map((x) => ({ id: id(x.t), name: name(x.t) })) }
  return { none: true }
}

// ---------------------------------------------------------------------------------------------

const ok = (data: unknown) => ({ content: [{ type: 'text' as const, text: JSON.stringify(data, null, 1) }] })
const fail = (message: string, extra: Record<string, unknown> = {}) => ({ content: [{ type: 'text' as const, text: JSON.stringify({ error: message, ...extra }, null, 1) }], isError: true })

function feedTitle(db: Db, url: string): string {
  const f = db.prepare(`SELECT COALESCE(NULLIF(custom_title, ''), title, '') t FROM feeds WHERE url = ?`).get(url) as { t: string } | undefined
  return f?.t || url
}

function itemMeta(db: Db, i: ItemRow) {
  const s = db.prepare('SELECT read, saved, pos_t, pos_d FROM item_state WHERE id = ?').get(i.id) as { read: number; saved: number; pos_t: number; pos_d: number } | undefined
  return {
    id: itemRef(i.id),
    title: i.title,
    feed: feedTitle(db, i.feed_url),
    kind: kindOfRow(i),
    published: date(i.sort_time),
    link: i.link,
    author: i.author,
    read: !!s?.read,
    saved: !!s?.saved,
    ...(s?.pos_t ? { progress: `${clock(s.pos_t * 1000)}${s.pos_d ? ' of ' + clock(s.pos_d * 1000) : ''}` } : {}),
  }
}

function playlistSummary(p: Playlist, items: PItem[]) {
  const secs = items.reduce((s, i) => s + (itemSeconds(i) ?? 0), 0)
  return { id: p.id, name: p.name, icon: p.icon, smart: p.smart, item_count: items.length, minutes: Math.round(secs / 60) }
}

function resolvePlaylist(db: Db, q: string): Resolved<Playlist> {
  return resolve(listPlaylists(db), q, (p) => p.id, (p) => p.name)
}

function resolveBook(db: Db, q: string): Resolved<BookRow> {
  return resolve(listBooks(db), q, (b) => b.id, (b) => b.title || b.name)
}

let notReadyHint: () => string | null = () => null

function notFound<T>(r: Resolved<T>, what: string, q: string) {
  if ('ambiguous' in r) return fail(`More than one ${what} fits “${q}”. Ask which one, then call again with its id.`, { candidates: r.ambiguous })
  const hint = notReadyHint()
  return fail(`No ${what} matches “${q}”.${hint ? ' ' + hint : ''}`)
}

const kindsEnum = z.enum(['article', 'podcast', 'youtube', 'highlight', 'book', 'zim'])
const maxTokens = z.number().int().min(500).max(20_000).optional().describe('Most text to return in this call (default 6000). Use next_cursor for the rest.')

export function buildServer(db: Db): McpServer {
  const server = new McpServer(
    { name: 'feeder', version: '0.1.0' },
    {
      instructions:
        'Ashish’s Feeder library: feeds and articles, podcast and YouTube transcripts, playlists, Saved, highlights with notes, ebooks and offline ZIM sites. ' +
        'Long text comes in pages: summarize each page before asking for the next (next_cursor). Quote with the `cite` given. ' +
        'Names (playlists, books, feeds) are matched loosely; when a tool answers with candidates, ask which one is meant.',
    },
  )
  const ro = { readOnlyHint: true, openWorldHint: false }
  notReadyHint = () => {
    if (!getKv<number>(db, 'lastSync', 0)) return 'Feeder sync isn’t set up on this Mac yet: turn on Settings › AI agents (MCP) in Feeder for Mac, or run `node dist/cli.js setup` in web/mcp.'
    if (!getKv<unknown>(db, 'dropbox', null)) return 'Dropbox isn’t signed in on Feeder, so no ebooks are listed.'
    return null
  }

  // ---- search ----
  server.registerTool(
    'search',
    {
      title: 'Search Feeder',
      description: 'Keyword search across articles, transcripts, highlights + notes, opened ebooks and ZIM articles. Returns the best matching passages.',
      inputSchema: {
        query: z.string().min(1).describe('Words to find; "quoted phrases" match exactly'),
        kinds: z.array(kindsEnum).optional(),
        feeds: z.array(z.string()).optional().describe('Feed URLs or titles'),
        folders: z.array(z.string()).optional(),
        since: z.string().optional().describe('ISO date, or relative like "7d", "2w"'),
        until: z.string().optional(),
        read: z.boolean().optional(),
        saved: z.boolean().optional(),
        book: z.string().optional().describe('Search inside one ebook (id or title); opens it first if needed'),
        limit: z.number().int().min(1).max(50).optional(),
      },
      annotations: ro,
    },
    async (a) => {
      const filters: SearchFilters = {
        kinds: a.kinds as Source[] | undefined,
        folders: a.folders,
        since: parseWhen(a.since),
        until: parseWhen(a.until),
        read: a.read,
        saved: a.saved,
      }
      if (a.feeds?.length) {
        const all = db.prepare(`SELECT url, COALESCE(NULLIF(custom_title, ''), title) t FROM feeds WHERE deleted = 0`).all() as { url: string; t: string }[]
        filters.feeds = a.feeds.flatMap((f) => all.filter((x) => x.url === f || norm(x.t) === norm(f) || norm(x.t).includes(norm(f))).map((x) => x.url))
      }
      if (a.book) {
        const r = resolveBook(db, a.book)
        if (!('hit' in r)) return notFound(r, 'book', a.book)
        await openBook(db, r.hit)
        filters.book = r.hit.id
      }
      const hits = search(db, a.query, filters, a.limit ?? 10)
      return ok({
        query: a.query,
        hits: hits.map((h) => ({
          chunk_id: h.rowid,
          kind: h.source,
          ...(h.source === 'article' || h.source === 'podcast' || h.source === 'youtube' ? { item: itemRef(h.ref_id) } : {}),
          ...(h.source === 'book' ? { book: h.ref_id } : {}),
          ...(h.source === 'zim' ? { zim: h.ref_id, path: (JSON.parse(h.locator) as { path?: string }).path } : {}),
          ...(h.source === 'highlight' ? { highlight: h.ref_id } : {}),
          title: h.title,
          feed: h.feed_title ?? undefined,
          published: date(h.sort_time),
          heading: h.heading || undefined,
          snippet: h.snippet,
        })),
        note: hits.length ? undefined : 'Nothing found. Try fewer or different words, or drop filters.',
      })
    },
  )

  // ---- feeds and items ----
  server.registerTool(
    'list_feeds',
    {
      title: 'List feeds',
      description: 'Feeds grouped by folder, with unread and total counts.',
      inputSchema: { folder: z.string().optional() },
      annotations: ro,
    },
    async ({ folder }) => {
      const rows = db
        .prepare(
          `SELECT f.url, COALESCE(NULLIF(f.custom_title, ''), f.title) title, f.folder, f.last_error,
                  count(i.id) total, sum(CASE WHEN COALESCE(s.read, 0) = 0 THEN 1 ELSE 0 END) unread
           FROM feeds f LEFT JOIN items i ON i.feed_url = f.url LEFT JOIN item_state s ON s.id = i.id
           WHERE f.deleted = 0 AND (COALESCE(s.hidden, 0) = 0) GROUP BY f.url ORDER BY f.folder, title`,
        )
        .all() as { url: string; title: string; folder: string; last_error: string | null; total: number; unread: number }[]
      const groups = new Map<string, unknown[]>()
      for (const r of rows) {
        if (folder && norm(r.folder) !== norm(folder)) continue
        const g = groups.get(r.folder || '(no folder)') ?? []
        g.push({ url: r.url, title: r.title || r.url, unread: r.unread ?? 0, total: r.total, ...(r.last_error ? { error: r.last_error } : {}) })
        groups.set(r.folder || '(no folder)', g)
      }
      return ok({ folders: [...groups].map(([name, feeds]) => ({ name, feeds })) })
    },
  )

  server.registerTool(
    'list_items',
    {
      title: 'List items',
      description: 'Browse articles, episodes and videos with filters. Use get_item to read one.',
      inputSchema: {
        feed: z.string().optional().describe('Feed URL or title'),
        folder: z.string().optional(),
        kinds: z.array(z.enum(['article', 'podcast', 'youtube'])).optional(),
        read: z.boolean().optional(),
        saved: z.boolean().optional(),
        since: z.string().optional(),
        until: z.string().optional(),
        sort: z.enum(['newest', 'oldest']).optional(),
        limit: z.number().int().min(1).max(200).optional(),
        cursor: z.string().optional(),
      },
      annotations: ro,
    },
    async (a) => {
      const where = ['COALESCE(s.hidden, 0) = 0']
      const params: unknown[] = []
      if (a.feed) {
        where.push(`(i.feed_url = ? OR lower(COALESCE(NULLIF(f.custom_title, ''), f.title)) LIKE ?)`)
        params.push(a.feed, `%${a.feed.toLowerCase()}%`)
      }
      if (a.folder) {
        where.push('lower(f.folder) = ?')
        params.push(a.folder.toLowerCase())
      }
      if (a.read != null) where.push(a.read ? 's.read = 1' : 'COALESCE(s.read, 0) = 0')
      if (a.saved != null) where.push(a.saved ? 's.saved = 1' : 'COALESCE(s.saved, 0) = 0')
      const since = parseWhen(a.since)
      const until = parseWhen(a.until)
      if (since) {
        where.push('i.sort_time >= ?')
        params.push(since)
      }
      if (until) {
        where.push('i.sort_time <= ?')
        params.push(until)
      }
      const offset = decCursor(a.cursor, 0)
      const limit = a.limit ?? 50
      const rows = db
        .prepare(
          `SELECT i.* FROM items i LEFT JOIN feeds f ON f.url = i.feed_url LEFT JOIN item_state s ON s.id = i.id
           WHERE ${where.join(' AND ')} ORDER BY i.sort_time ${a.sort === 'oldest' ? 'ASC' : 'DESC'}`,
        )
        .all(...params) as ItemRow[]
      const filtered = a.kinds?.length ? rows.filter((r) => a.kinds!.includes(kindOfRow(r))) : rows
      const page = filtered.slice(offset, offset + limit)
      return ok({
        total: filtered.length,
        items: page.map((i) => itemMeta(db, i)),
        next_cursor: offset + limit < filtered.length ? encCursor(offset + limit) : undefined,
      })
    },
  )

  server.registerTool(
    'get_item',
    {
      title: 'Read an item',
      description:
        'The full text of an article (fetching the full article when the feed only has a teaser), or the transcript of a podcast episode or YouTube video. Paged.',
      inputSchema: {
        id: z.string().describe('Item id from search, list_items or get_playlist'),
        prefer: z.enum(['auto', 'full_text', 'feed', 'transcript']).optional(),
        max_tokens: maxTokens,
        cursor: z.string().optional(),
      },
      annotations: { readOnlyHint: true, openWorldHint: true },
    },
    async (a) => {
      const item = getItem(db, itemIdOf(a.id))
      if (!item) return fail('No such item. Ids come from search, list_items or get_playlist.')
      const t = await itemText(db, item, a.prefer ?? 'auto')
      const meta = itemMeta(db, item)
      const page = toPage(t.chunks, decCursor(a.cursor, 0), a.max_tokens ?? 6000, (c) => citeFor(`${item.title} — ${meta.feed}`, c))
      return ok({
        item: meta,
        text_from: t.origin,
        ...(t.note ? { note: t.note } : {}),
        total_tokens: t.chunks.reduce((s, c) => s + c.tokens, 0),
        chunks: page.out,
        next_cursor: page.next != null ? encCursor(page.next) : undefined,
      })
    },
  )

  // ---- playlists ----
  server.registerTool(
    'list_playlists',
    { title: 'List playlists', description: 'Playlists (manual and smart) with item counts and total minutes.', inputSchema: {}, annotations: ro },
    async () => ok({ playlists: listPlaylists(db).map((p) => playlistSummary(p, playlistItems(db, p).items)) }),
  )

  server.registerTool(
    'get_playlist',
    {
      title: 'Get a playlist',
      description: 'The items of a playlist in play order (smart playlists evaluated now, as the app does).',
      inputSchema: {
        playlist: z.string().describe('Playlist id or name (loose match)'),
        statuses: z.array(z.enum(['unread', 'inprogress', 'read'])).optional(),
        limit: z.number().int().min(1).max(200).optional(),
      },
      annotations: ro,
    },
    async (a) => {
      const r = resolvePlaylist(db, a.playlist)
      if (!('hit' in r)) return notFound(r, 'playlist', a.playlist)
      const { items, missing } = playlistItems(db, r.hit)
      const list = (a.statuses?.length ? items.filter((i) => a.statuses!.includes(playStatus(i))) : items).slice(0, a.limit ?? 50)
      return ok({
        playlist: playlistSummary(r.hit, items),
        matched_by: r.via,
        items: list.map((i, n) => ({
          position: n + 1,
          id: itemRef(i.id),
          title: i.title,
          feed: i.feed_title,
          kind: kindOf(i),
          minutes: minutes(itemSeconds(i)),
          status: playStatus(i),
          published: date(i.sort_time),
        })),
        ...(missing.length ? { not_on_this_mac: missing.length, note: `${missing.length} item(s) are no longer in their feeds and were never saved, so their text isn't available.` } : {}),
      })
    },
  )

  server.registerTool(
    'get_playlist_content',
    {
      title: 'Read a playlist',
      description:
        'The text of a playlist’s items in order (article text, or transcript for episodes and videos), each item capped at per_item_tokens. Paged: summarize each page, then pass next_cursor.',
      inputSchema: {
        playlist: z.string(),
        per_item_tokens: z.number().int().min(200).max(10_000).optional().describe('Cap per item (default 1500); call get_item for an item’s full text'),
        statuses: z.array(z.enum(['unread', 'inprogress', 'read'])).optional(),
        item_ids: z.array(z.string()).optional().describe('Only these items'),
        max_tokens: maxTokens,
        cursor: z.string().optional(),
      },
      annotations: { readOnlyHint: true, openWorldHint: true },
    },
    async (a) => {
      const r = resolvePlaylist(db, a.playlist)
      if (!('hit' in r)) return notFound(r, 'playlist', a.playlist)
      let { items } = playlistItems(db, r.hit)
      if (a.statuses?.length) items = items.filter((i) => a.statuses!.includes(playStatus(i)))
      if (a.item_ids?.length) {
        const want = new Set(a.item_ids.map(itemIdOf))
        items = items.filter((i) => want.has(i.id))
      }
      const per = a.per_item_tokens ?? 1500
      const budget = a.max_tokens ?? 6000
      let at = decCursor(a.cursor, 0)
      const sections: unknown[] = []
      let used = 0
      // Fetch text for the next few items in parallel (full text and transcripts can take seconds)
      const prefetch = new Map<number, Promise<Awaited<ReturnType<typeof itemText>> | null>>()
      const textAt = (n: number) => {
        if (!prefetch.has(n)) {
          const row = getItem(db, items[n].id)
          prefetch.set(n, row ? itemText(db, row).catch(() => null) : Promise.resolve(null))
        }
        return prefetch.get(n)!
      }
      for (; at < items.length; at++) {
        for (let k = at; k < Math.min(items.length, at + 4); k++) void textAt(k)
        const i = items[at]
        const t = await textAt(at)
        const chunks = t?.chunks ?? []
        const page = toPage(chunks, 0, per, (c) => citeFor(`${i.title} — ${i.feed_title}`, c))
        if (sections.length && used + page.used > budget) break
        const total = chunks.reduce((s, c) => s + c.tokens, 0)
        sections.push({
          position: at + 1,
          item: { id: itemRef(i.id), title: i.title, feed: i.feed_title, kind: kindOf(i), minutes: minutes(itemSeconds(i)), status: playStatus(i) },
          text_from: t?.origin ?? 'none',
          ...(t?.note ? { note: t.note } : {}),
          chunks: page.out,
          ...(page.next != null ? { truncated: `Showing ~${page.used} of ~${total} tokens. Call get_item for the rest.` } : {}),
        })
        used += page.used
      }
      return ok({
        playlist: playlistSummary(r.hit, items),
        matched_by: r.via,
        sections,
        next_cursor: at < items.length ? encCursor(at) : undefined,
        remaining_items: items.length - at,
      })
    },
  )

  // ---- ebooks ----
  server.registerTool(
    'list_books',
    { title: 'List ebooks', description: 'Books in Dropbox Apps/Feeder/Ebooks, with reading progress.', inputSchema: { query: z.string().optional() }, annotations: ro },
    async ({ query }) => {
      let books = listBooks(db)
      if (query) books = books.filter((b) => norm(`${b.title} ${b.author ?? ''} ${b.name}`).includes(norm(query)) || similarity(b.title ?? b.name, query) > 0.45)
      const pos = new Map((db.prepare('SELECT id, f FROM book_pos').all() as { id: string; f: number }[]).map((p) => [p.id, p.f]))
      return ok({
        books: books.map((b) => ({
          id: b.id,
          title: b.title || b.name,
          author: b.author || undefined,
          format: extOf(b.name),
          readable: READABLE.has(extOf(b.name)),
          progress_pct: pos.has(b.id) ? Math.round(pos.get(b.id)! * 100) : undefined,
          highlights: (db.prepare('SELECT count(*) n FROM highlights WHERE item_id = ? AND deleted = 0').get(BOOK_ITEM_PREFIX + b.id) as { n: number }).n || undefined,
        })),
        ...(Dropbox_missing(db) ? { note: 'Not signed in to Dropbox in Feeder, so no ebooks can be listed.' } : {}),
      })
    },
  )

  const tocOut = (toc: TocEntry[]) =>
    toc.map((e) => ({ index: e.index, ...(e.chapterNo ? { chapter: e.chapterNo } : {}), label: e.label, kind: e.kind, ...(e.part ? { part: e.part } : {}), tokens: e.tokens }))

  server.registerTool(
    'get_book_toc',
    {
      title: 'Book contents',
      description: 'Table of contents of an ebook. "chapter" numbers skip front matter (cover, copyright, contents); "index" is raw order.',
      inputSchema: { book: z.string().describe('Book id or title (loose match)') },
      annotations: ro,
    },
    async ({ book }) => {
      const r = resolveBook(db, book)
      if (!('hit' in r)) return notFound(r, 'book', book)
      try {
        const b = await openBook(db, r.hit)
        return ok({ book: { id: r.hit.id, title: b.title, author: b.author }, matched_by: r.via, toc: tocOut(b.toc) })
      } catch (e) {
        return fail(String((e as Error).message ?? e))
      }
    },
  )

  server.registerTool(
    'read_book',
    {
      title: 'Read ebook chapters',
      description:
        'Text of chosen chapters, in order, paged. chapters: "1-3", "2,5", "4", or a chapter title. Numbers count real chapters (front matter skipped) unless include_front_matter.',
      inputSchema: {
        book: z.string(),
        chapters: z.string().describe('e.g. "1-3"'),
        include_front_matter: z.boolean().optional().describe('Count every TOC entry (index) instead of chapters'),
        max_tokens: maxTokens,
        cursor: z.string().optional(),
      },
      annotations: ro,
    },
    async (a) => {
      const r = resolveBook(db, a.book)
      if (!('hit' in r)) return notFound(r, 'book', a.book)
      let b
      try {
        b = await openBook(db, r.hit)
      } catch (e) {
        return fail(String((e as Error).message ?? e))
      }
      const picked = pickChapters(b.toc, a.chapters, !!a.include_front_matter)
      if (!picked.length) return fail(`No chapters match “${a.chapters}”.`, { toc: tocOut(b.toc) })
      const highlights = db.prepare('SELECT text, note, cfi FROM highlights WHERE item_id = ? AND deleted = 0').all(BOOK_ITEM_PREFIX + r.hit.id) as { text: string; note: string }[]
      // Flatten picked chapters into one chunk list; cursor = [chapter position, chunk offset]
      const [startCh, startChunk] = decCursor<[number, number]>(a.cursor, [0, 0])
      const budget = a.max_tokens ?? 6000
      const out: unknown[] = []
      let used = 0
      let ch = startCh
      let off = startChunk
      while (ch < picked.length) {
        const e = picked[ch]
        const chunks = chapterChunks(db, r.hit.id, e.index)
        const label = e.chapterNo ? `Ch. ${e.chapterNo}: ${e.label}` : e.label
        const page = toPage(chunks, off, Math.max(0, budget - used) || 1, () => `${b.title} › ${label}`)
        if (!page.out.length && out.length) break
        const chapterText = chunks.map((c) => c.text).join('\n')
        out.push({
          chapter: e.chapterNo ?? null,
          index: e.index,
          label: e.label,
          ...(off === 0 ? { highlights_here: highlights.filter((h) => chapterText.includes(h.text.slice(0, 60))).map((h) => ({ text: h.text, note: h.note || undefined })) } : {}),
          chunks: page.out,
        })
        used += page.used
        if (page.next != null) {
          off = page.next
          break
        }
        ch++
        off = 0
        if (used >= budget) break
      }
      return ok({
        book: { id: r.hit.id, title: b.title, author: b.author },
        matched_by: r.via,
        mapped: picked.map((e) => ({ chapter: e.chapterNo ?? null, index: e.index, label: e.label, tokens: e.tokens })),
        sections: out,
        next_cursor: ch < picked.length ? encCursor([ch, off]) : undefined,
      })
    },
  )

  // ---- highlights ----
  server.registerTool(
    'list_highlights',
    {
      title: 'List highlights',
      description: 'Highlights and their notes, from articles and ebooks, newest first.',
      inputSchema: {
        item: z.string().optional().describe('Item id'),
        book: z.string().optional().describe('Book id or title'),
        feed: z.string().optional().describe('Feed title'),
        color: z.enum(['yellow', 'green', 'blue', 'pink']).optional(),
        has_note: z.boolean().optional(),
        since: z.string().optional(),
        query: z.string().optional().describe('Words in the text or note'),
        limit: z.number().int().min(1).max(500).optional(),
        cursor: z.string().optional(),
      },
      annotations: ro,
    },
    async (a) => {
      const where = ['deleted = 0']
      const params: unknown[] = []
      if (a.item) {
        where.push('item_id = ?')
        params.push(itemIdOf(a.item))
      }
      if (a.book) {
        const r = resolveBook(db, a.book)
        if (!('hit' in r)) return notFound(r, 'book', a.book)
        where.push('item_id = ?')
        params.push(BOOK_ITEM_PREFIX + r.hit.id)
      }
      if (a.feed) {
        where.push('lower(feed) LIKE ?')
        params.push(`%${a.feed.toLowerCase()}%`)
      }
      if (a.color) {
        where.push('color = ?')
        params.push(a.color)
      }
      if (a.has_note != null) where.push(a.has_note ? `note <> ''` : `note = ''`)
      const since = parseWhen(a.since)
      if (since) {
        where.push('created_at >= ?')
        params.push(since)
      }
      if (a.query) {
        where.push('(lower(text) LIKE ? OR lower(note) LIKE ?)')
        params.push(`%${a.query.toLowerCase()}%`, `%${a.query.toLowerCase()}%`)
      }
      const rows = db.prepare(`SELECT * FROM highlights WHERE ${where.join(' AND ')} ORDER BY created_at DESC`).all(...params) as {
        id: string
        item_id: string
        title: string
        feed: string
        link: string | null
        text: string
        note: string
        color: string
        created_at: number
      }[]
      const offset = decCursor(a.cursor, 0)
      const limit = a.limit ?? 50
      return ok({
        total: rows.length,
        highlights: rows.slice(offset, offset + limit).map((h) => ({
          id: h.id,
          text: h.text,
          note: h.note || undefined,
          color: h.color,
          source: h.item_id.startsWith(BOOK_ITEM_PREFIX) ? { book: h.item_id.slice(BOOK_ITEM_PREFIX.length), title: h.title } : { item: itemRef(h.item_id), title: h.title, feed: h.feed, link: h.link },
          cite: `${h.title}${h.feed ? ' — ' + h.feed : ''}`,
          created: date(h.created_at),
        })),
        next_cursor: offset + limit < rows.length ? encCursor(offset + limit) : undefined,
      })
    },
  )

  // ---- ZIM ----
  server.registerTool(
    'list_zims',
    { title: 'List ZIM files', description: 'Offline sites (ZIM files) in Dropbox Apps/Feeder/ZIM and ~/Zimit.', inputSchema: {}, annotations: ro },
    async () =>
      ok({
        zims: listZims(db).map((z) => ({
          id: z.id,
          title: z.title,
          file: z.name,
          size_mb: Math.round(z.size / 1048576),
          where: z.local_path ? 'local' : 'dropbox',
          full_text_searchable: zimIndexed(db, z.id),
        })),
      }),
  )

  const resolveZim = (q: string | undefined): ZimRow[] | Resolved<ZimRow> => {
    const all = listZims(db)
    if (!q) return all
    return resolve(all, q, (z) => z.id, (z) => z.title ?? z.name)
  }

  server.registerTool(
    'zim_lookup',
    {
      title: 'Find in ZIM',
      description: 'Find articles in ZIM files by title (prefix match). For words inside articles use search with kinds ["zim"].',
      inputSchema: { query: z.string().min(1), zim: z.string().optional().describe('ZIM id or title; all when left out'), limit: z.number().int().min(1).max(50).optional() },
      annotations: ro,
    },
    async (a) => {
      const r = resolveZim(a.zim)
      const zims = Array.isArray(r) ? r : 'hit' in r ? [r.hit] : null
      if (!zims) return notFound(r as Resolved<ZimRow>, 'ZIM', a.zim!)
      const hits: unknown[] = []
      for (const z of zims) {
        try {
          const arc = await archive(db, z)
          for (const h of await arc.suggest(a.query, a.limit ?? 10)) hits.push({ zim: z.id, zim_title: z.title, path: h.path, title: h.title })
        } catch {
          // unreadable ZIM
        }
      }
      return ok({ hits: hits.slice(0, a.limit ?? 20) })
    },
  )

  server.registerTool(
    'zim_read',
    {
      title: 'Read ZIM article',
      description: 'An article from a ZIM file as text, paged.',
      inputSchema: { zim: z.string(), path: z.string(), max_tokens: maxTokens, cursor: z.string().optional() },
      annotations: ro,
    },
    async (a) => {
      const r = resolveZim(a.zim)
      if (Array.isArray(r) || !('hit' in r)) return notFound(r as Resolved<ZimRow>, 'ZIM', a.zim)
      const art = await zimArticle(db, r.hit, a.path)
      if (!art) return fail('No article at that path.')
      const page = toPage(art.chunks, decCursor(a.cursor, 0), a.max_tokens ?? 6000, () => `${art.title} — ${r.hit.title}`)
      return ok({ zim: r.hit.title, title: art.title, path: art.path, chunks: page.out, next_cursor: page.next != null ? encCursor(page.next) : undefined })
    },
  )

  // ---- status / refresh ----
  server.registerTool(
    'status',
    { title: 'Index status', description: 'What the index holds and when it was last refreshed.', inputSchema: {}, annotations: ro },
    async () => ok(statusOf(db)),
  )

  server.registerTool(
    'refresh',
    {
      title: 'Refresh now',
      description: 'Pull sync, refetch feeds and/or relist Dropbox now instead of waiting for the background refresh.',
      inputSchema: { what: z.array(z.enum(['sync', 'feeds', 'dropbox'])).optional() },
      annotations: { readOnlyHint: true, openWorldHint: true },
    },
    async ({ what }) => {
      const w = new Set(what ?? ['sync', 'feeds', 'dropbox'])
      return ok(await runOnce(db, { sync: w.has('sync'), feeds: w.has('feeds'), dropbox: w.has('dropbox'), zimFullText: false }))
    },
  )

  registerResources(server, db)
  registerPrompts(server)
  return server
}

function Dropbox_missing(db: Db) {
  return !getKv<unknown>(db, 'dropbox', null)
}

export function statusOf(db: Db) {
  const n = (sql: string) => (db.prepare(sql).get() as { n: number }).n
  return {
    sync: { last: getKv<number>(db, 'lastSync', 0) ? new Date(getKv<number>(db, 'lastSync', 0)).toISOString() : 'never', cursor: getKv(db, 'syncCursor', 0) },
    feeds: n('SELECT count(*) n FROM feeds WHERE deleted = 0'),
    feeds_with_errors: n('SELECT count(*) n FROM feeds WHERE deleted = 0 AND last_error IS NOT NULL'),
    items: n('SELECT count(*) n FROM items'),
    saved: n('SELECT count(*) n FROM item_state WHERE saved = 1'),
    playlists: n('SELECT count(*) n FROM playlists WHERE deleted = 0'),
    highlights: n('SELECT count(*) n FROM highlights WHERE deleted = 0'),
    books: n(`SELECT count(*) n FROM files WHERE kind = 'book'`),
    zims: n(`SELECT count(*) n FROM files WHERE kind = 'zim'`),
    documents: n('SELECT count(*) n FROM documents'),
    chunks: n('SELECT count(*) n FROM chunks'),
    dropbox: getKv<unknown>(db, 'dropbox', null) ? 'signed in (from Feeder sync)' : 'not signed in',
    last_feed_refresh: getKv<number>(db, 'lastFeedRefresh', 0) ? new Date(getKv<number>(db, 'lastFeedRefresh', 0)).toISOString() : 'never',
  }
}

// ---------------------------------------------------------------------------------------------

function registerResources(server: McpServer, db: Db) {
  const md = (uri: string, text: string) => ({ contents: [{ uri, mimeType: 'text/markdown', text }] })

  server.registerResource(
    'item',
    new ResourceTemplate('feeder://item/{id}', {
      list: async () => ({
        resources: (
          db
            .prepare(`SELECT i.id, i.title FROM items i JOIN item_state s ON s.id = i.id WHERE s.saved = 1 ORDER BY s.saved_ts DESC LIMIT 50`)
            .all() as { id: string; title: string }[]
        ).map((r) => ({ uri: `feeder://item/${itemRef(r.id)}`, name: `Saved: ${r.title}`, mimeType: 'text/markdown' })),
      }),
    }),
    { title: 'Feeder item', description: 'An article, episode or video as Markdown (transcript for episodes/videos)' },
    async (uri, { id }) => {
      const item = getItem(db, itemIdOf(String(id)))
      if (!item) return md(uri.href, 'Not found')
      const t = await itemText(db, item)
      const meta = itemMeta(db, item)
      return md(uri.href, `# ${item.title}\n\n${meta.feed} · ${meta.published ?? ''}${item.link ? ` · ${item.link}` : ''}\n\n${t.chunks.map((c) => (c.heading ? `### ${c.heading}\n\n` : '') + c.text).join('\n\n')}`)
    },
  )

  server.registerResource(
    'playlist',
    new ResourceTemplate('feeder://playlist/{id}', {
      list: async () => ({ resources: listPlaylists(db).map((p) => ({ uri: `feeder://playlist/${p.id}`, name: `Playlist: ${p.icon} ${p.name}`, mimeType: 'text/markdown' })) }),
    }),
    { title: 'Feeder playlist', description: 'A playlist as a Markdown list of its items' },
    async (uri, { id }) => {
      const p = listPlaylists(db).find((x) => x.id === String(id))
      if (!p) return md(uri.href, 'Not found')
      const { items } = playlistItems(db, p)
      return md(uri.href, `# ${p.icon} ${p.name}\n\n${items.map((i, n) => `${n + 1}. ${i.title} — ${i.feed_title} (${kindOf(i)}, ${minutes(itemSeconds(i)) ?? '?'} min) [${itemRef(i.id)}]`).join('\n')}`)
    },
  )

  server.registerResource(
    'book-toc',
    new ResourceTemplate('feeder://book/{id}/toc', {
      list: async () => ({ resources: listBooks(db).map((b) => ({ uri: `feeder://book/${b.id}/toc`, name: `Book: ${b.title ?? b.name}`, mimeType: 'text/markdown' })) }),
    }),
    { title: 'Ebook contents', description: 'Table of contents of an ebook' },
    async (uri, { id }) => {
      const b = listBooks(db).find((x) => x.id === String(id))
      if (!b) return md(uri.href, 'Not found')
      const o = await openBook(db, b)
      return md(uri.href, `# ${o.title}\n\n${o.author}\n\n${o.toc.map((e) => `- ${e.chapterNo ? `Chapter ${e.chapterNo}: ` : ''}${e.label} (${e.kind}, ~${e.tokens} tokens)`).join('\n')}`)
    },
  )

  server.registerResource(
    'book-chapter',
    new ResourceTemplate('feeder://book/{id}/chapter/{n}', { list: undefined }),
    { title: 'Ebook chapter', description: 'One chapter of an ebook (n = chapter number, front matter skipped)' },
    async (uri, { id, n }) => {
      const b = listBooks(db).find((x) => x.id === String(id))
      if (!b) return md(uri.href, 'Not found')
      const o = await openBook(db, b)
      const e = pickChapters(o.toc, String(n), false)[0]
      if (!e) return md(uri.href, 'No such chapter')
      return md(uri.href, `# ${o.title} › Chapter ${e.chapterNo}: ${e.label}\n\n${chapterChunks(db, b.id, e.index).map((c) => c.text).join('\n\n')}`)
    },
  )

  server.registerResource(
    'highlights',
    'feeder://highlights',
    { title: 'Recent highlights', description: 'The 200 newest highlights with notes', mimeType: 'text/markdown' },
    async (uri) => {
      const rows = db.prepare('SELECT title, feed, text, note FROM highlights WHERE deleted = 0 ORDER BY created_at DESC LIMIT 200').all() as {
        title: string
        feed: string
        text: string
        note: string
      }[]
      return md(uri.href, `# Highlights\n\n${rows.map((h) => `> ${h.text}\n\n— ${h.title}${h.feed ? ', ' + h.feed : ''}${h.note ? `\n\nNote: ${h.note}` : ''}`).join('\n\n---\n\n')}`)
    },
  )
}

function registerPrompts(server: McpServer) {
  const user = (text: string) => ({ messages: [{ role: 'user' as const, content: { type: 'text' as const, text } }] })

  server.registerPrompt(
    'summarize_playlist',
    {
      title: 'Summarize a playlist',
      description: 'Summary of every item in a Feeder playlist, then the themes across them',
      argsSchema: { playlist: z.string(), style: z.string().optional().describe('brief (default) or detailed'), only_unread: z.string().optional().describe('yes/no') },
    },
    ({ playlist, style, only_unread }) =>
      user(
        `Summarize my Feeder playlist "${playlist}".\n\n` +
          `1. Call get_playlist_content with playlist="${playlist}"${/^y/i.test(only_unread ?? '') ? ' and statuses=["unread","inprogress"]' : ''}. ` +
          `If it answers with candidates, ask me which playlist I mean.\n` +
          `2. For each section, write a ${style === 'detailed' ? '5–8 line' : '2–3 line'} summary under the item's title and feed, with its kind and length. Use the cite strings for any quote.\n` +
          `3. If next_cursor is present, summarize what you have, then call again with that cursor until it's gone. Don't re-fetch items already summarized.\n` +
          `4. Finish with "Across the playlist": 3–5 bullets on shared themes, and which items are most worth my time.\n` +
          `If an item had no text (note says no transcript), say so in one line instead of guessing.`,
      ),
  )

  server.registerPrompt(
    'summarize_book_chapters',
    {
      title: 'Summarize ebook chapters',
      description: 'Summary of chosen chapters of an ebook, with my highlights from them',
      argsSchema: { book: z.string(), chapters: z.string().describe('e.g. "1-3"'), depth: z.string().optional().describe('brief (default) or detailed') },
    },
    ({ book, chapters, depth }) =>
      user(
        `Summarize chapters ${chapters} of the ebook "${book}" from my Feeder library.\n\n` +
          `1. Call get_book_toc with book="${book}" (ask me if it answers with candidates). Tell me in one line which TOC entries chapters ${chapters} map to.\n` +
          `2. Call read_book with chapters="${chapters}". Summarize each page as it comes; follow next_cursor until it's gone.\n` +
          `3. For each chapter: a ${depth === 'detailed' ? 'detailed (8–12 line)' : 'short (4–6 line)'} summary, then key ideas as bullets, then my highlights from it (highlights_here) if any.\n` +
          `4. End with 3 bullets on how the chapters connect.`,
      ),
  )

  server.registerPrompt(
    'unread_digest',
    {
      title: 'Unread digest',
      description: 'Unread items grouped by topic, with the top picks',
      argsSchema: { folder: z.string().optional(), since: z.string().optional().describe('e.g. 1d, 3d, 1w (default 1d)') },
    },
    ({ folder, since }) =>
      user(
        `Make me a digest of unread items in Feeder${folder ? ` in the folder "${folder}"` : ''} from the last ${since ?? '1d'}.\n` +
          `Call list_items with read=false, since="${since ?? '1d'}"${folder ? `, folder="${folder}"` : ''}, limit=100. Group them by topic, one line each (title — feed). ` +
          `Then pick the 5 most worth reading, call get_item on each (max_tokens 2500) and give a 2-line summary of each.`,
      ),
  )

  server.registerPrompt(
    'highlights_review',
    { title: 'Review highlights', description: 'My highlights and notes grouped by theme', argsSchema: { since: z.string().optional(), topic: z.string().optional() } },
    ({ since, topic }) =>
      user(
        `Review my Feeder highlights${topic ? ` about "${topic}"` : ''}${since ? ` from the last ${since}` : ''}.\n` +
          `Call ${topic ? `search with kinds=["highlight"] and query="${topic}", then ` : ''}list_highlights${since ? ` with since="${since}"` : ''}${topic ? ` and query="${topic}"` : ''}. ` +
          `Group them into themes, quote each highlight with its cite, include my notes, and end with what the highlights say I care about.`,
      ),
  )

  server.registerPrompt(
    'ask_my_library',
    { title: 'Ask my library', description: 'Answer a question only from my Feeder content, with citations', argsSchema: { question: z.string() } },
    ({ question }) =>
      user(
        `Answer from my Feeder library only: ${question}\n\n` +
          `Search first (search, then refine with filters). Read the most relevant hits in full with get_item, read_book or zim_read. ` +
          `Answer with citations (the cite strings). If the library doesn't cover it, say so.`,
      ),
  )
}
