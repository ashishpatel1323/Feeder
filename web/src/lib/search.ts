/**
 * Global search (⌘K palette and the all-results view): one query over everything in Feeder —
 * article, episode and video titles and text, saved podcast transcripts, highlights and their
 * notes (ebook highlights too), feeds, folders, playlists, ebook titles and authors, and the titles
 * of articles in ZIM files. Runs in memory on the data the app already holds; the plain text of
 * each article is worked out once and kept until the article changes. ZIM titles are looked up in
 * the files themselves, so they come in a moment later (addZimHits).
 */
import type { Book, Feed, Highlight, Item } from './db'
import { feedDisplayTitle } from './db'
import { kindOf, type ItemKind, type Playlist, type PlaylistContext } from './playlists'

/** What the palette can be narrowed to with Tab. 'library' = feeds, folders and playlists. */
export type SearchScope = 'all' | 'article' | 'podcast' | 'youtube' | 'highlight' | 'library' | 'book' | 'zim'
export const SCOPES: SearchScope[] = ['all', 'article', 'podcast', 'youtube', 'highlight', 'library', 'book', 'zim']
export const SCOPE_LABELS: Record<SearchScope, string> = {
  all: 'All',
  article: 'Articles',
  podcast: 'Episodes',
  youtube: 'Videos',
  highlight: 'Highlights',
  library: 'Feeds & playlists',
  book: 'Ebooks',
  zim: 'ZIM',
}

/** A piece of text to show, with the parts that matched as [start, end) ranges. */
export interface Snippet {
  text: string
  ranges: [number, number][]
}

export type MatchField = 'title' | 'feed' | 'transcript' | 'text'

export interface ItemHit {
  type: 'item'
  id: string
  item: Item
  kind: ItemKind
  score: number
  field: MatchField
  title: Snippet
  /** The line that matched when it wasn't the title (article text or a transcript line). */
  snippet: Snippet | null
  /** Transcript match: where in the episode the first matching line starts, and how many lines match. */
  atMs: number | null
  transcriptMatches: number
}
export interface HighlightHit {
  type: 'highlight'
  id: string
  highlight: Highlight
  score: number
  text: Snippet
  note: Snippet | null
}
export interface FeedHit {
  type: 'feed'
  id: string
  feed: Feed
  score: number
  title: Snippet
  unread: number
}
export interface FolderHit {
  type: 'folder'
  id: string
  tag: string
  score: number
  title: Snippet
  feeds: number
}
export interface PlaylistHit {
  type: 'playlist'
  id: string
  playlist: Playlist
  score: number
  title: Snippet
}
/** An ebook in the Ebooks tab, by its title or author. */
export interface BookHit {
  type: 'book'
  id: string
  book: Book
  score: number
  title: Snippet
  author: Snippet
}
/** An article in a ZIM file on this device, by its title. */
export interface ZimHit {
  type: 'zim'
  /** "<ZIM book id>\n<path>" */
  id: string
  bookId: string
  bookTitle: string
  path: string
  score: number
  title: Snippet
}
/** A ZIM title match as the file gives it (lib/zim). */
export interface ZimTitle {
  bookId: string
  bookTitle: string
  path: string
  title: string
}
export type SearchHit = ItemHit | HighlightHit | FeedHit | FolderHit | PlaylistHit | BookHit | ZimHit

export interface SearchResults {
  query: string
  terms: string[]
  items: ItemHit[]
  highlights: HighlightHit[]
  library: (FeedHit | FolderHit | PlaylistHit)[]
  books: BookHit[]
  zim: ZimHit[]
  /** How many results each scope has. */
  counts: Record<SearchScope, number>
}

export interface TranscriptLine {
  startMs: number
  text: string
}

export interface SearchData {
  items: Item[]
  feeds: Map<string, Feed>
  highlights: Highlight[]
  playlists: Playlist[]
  /** The Ebooks tab's books. */
  books: Book[]
  /** Saved transcripts by item id. */
  transcripts: Map<string, TranscriptLine[]>
  ctx: PlaylistContext
  now?: number
}

/** Lowercase, accents dropped, so "cafe" finds "Café". Keeps the length of plain text the same. */
export function fold(s: string): string {
  const lower = s.toLowerCase()
  // Only strip accents when there are any: NFD on plain ASCII is wasted work
  // eslint-disable-next-line no-control-regex
  if (!/[^\u0000-\u007f]/.test(lower)) return lower
  let out = ''
  for (const ch of lower) {
    const base = ch.normalize('NFD').replace(/[̀-ͯ]/g, '')
    // A character that splits into something longer would shift the match positions: keep it
    out += base.length === ch.length ? base : ch
  }
  return out
}

export function queryTerms(query: string): string[] {
  return [...new Set(fold(query).split(/\s+/).filter(Boolean))]
}

const ENTITIES: Record<string, string> = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ', '#39': "'", rsquo: '’', lsquo: '‘', ldquo: '“', rdquo: '”', mdash: '—', ndash: '–', hellip: '…' }

/** The readable text of some HTML, on one line. */
export function htmlText(html: string): string {
  return html
    .replace(/<(script|style|noscript|svg)[\s\S]*?<\/\1>/gi, ' ')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&(#?\w+);/g, (m, e: string) => ENTITIES[e.toLowerCase()] ?? (e[0] === '#' ? codePoint(e.slice(1)) : m))
    .replace(/\s+/g, ' ')
    .trim()
}

function codePoint(n: string): string {
  const v = Number(n[0] === 'x' || n[0] === 'X' ? `0${n}` : n)
  return Number.isInteger(v) && v > 0 && v <= 0x10ffff ? String.fromCodePoint(v) : ' '
}

/** At most this much of an article's text is searched, so a book-length page doesn't stall typing. */
const TEXT_LIMIT = 40_000
const textCache = new Map<string, { key: string; text: string; folded: string }>()

/** An article's plain text (and its folded copy), worked out once until the article changes. */
export function itemText(item: Item): { text: string; folded: string } {
  const html = item.fullHtml || item.contentHtml || ''
  const key = `${html.length}|${html.slice(0, 48)}|${item.snippet?.length ?? 0}`
  const hit = textCache.get(item.id)
  if (hit && hit.key === key) return hit
  const text = (html ? htmlText(html) : item.snippet || '').slice(0, TEXT_LIMIT)
  const entry = { key, text, folded: fold(text) }
  textCache.set(item.id, entry)
  return entry
}

/** Forgets cached text for articles that are gone. */
export function pruneTextCache(ids: Set<string>) {
  for (const id of textCache.keys()) if (!ids.has(id)) textCache.delete(id)
}

/** Where each term occurs in the folded text, as ranges, or null when one of them doesn't. */
export function findTerms(folded: string, terms: string[]): [number, number][] | null {
  const ranges: [number, number][] = []
  for (const t of terms) {
    let at = folded.indexOf(t)
    if (at < 0) return null
    while (at >= 0) {
      ranges.push([at, at + t.length])
      at = folded.indexOf(t, at + t.length)
    }
  }
  return mergeRanges(ranges)
}

function mergeRanges(ranges: [number, number][]): [number, number][] {
  const sorted = [...ranges].sort((a, b) => a[0] - b[0])
  const out: [number, number][] = []
  for (const r of sorted) {
    const last = out[out.length - 1]
    if (last && r[0] <= last[1]) last[1] = Math.max(last[1], r[1])
    else out.push([r[0], r[1]])
  }
  return out
}

/** Every range of every term in the text (terms that don't occur are skipped), for marking. */
export function markTerms(text: string, terms: string[], folded = fold(text)): Snippet {
  const ranges: [number, number][] = []
  for (const t of terms) {
    let at = folded.indexOf(t)
    while (at >= 0) {
      ranges.push([at, at + t.length])
      at = folded.indexOf(t, at + t.length)
    }
  }
  return { text, ranges: mergeRanges(ranges) }
}

/** A window of the text around the first match, cut at word edges, with the matches marked. */
export function snippetAround(text: string, folded: string, terms: string[], before = 50, length = 180): Snippet {
  let first = -1
  for (const t of terms) {
    const at = folded.indexOf(t)
    if (at >= 0 && (first < 0 || at < first)) first = at
  }
  if (first < 0) return { text: text.slice(0, length), ranges: [] }
  let start = Math.max(0, first - before)
  if (start > 0) {
    const space = text.indexOf(' ', start)
    if (space >= 0 && space < first) start = space + 1
  }
  let end = Math.min(text.length, start + length)
  if (end < text.length) {
    const space = text.lastIndexOf(' ', end)
    if (space > first) end = space
  }
  const lead = start > 0 ? '…' : ''
  const tail = end < text.length ? '…' : ''
  const piece = markTerms(text.slice(start, end), terms, folded.slice(start, end))
  return { text: lead + piece.text + tail, ranges: piece.ranges.map(([a, b]) => [a + lead.length, b + lead.length]) }
}

/** How well a title matches: whole query at the start beats word starts beats anywhere. */
function titleScore(folded: string, query: string, terms: string[]): number {
  let s = 100
  if (folded.startsWith(query)) s += 40
  else if (folded.includes(query) && terms.length > 1) s += 25
  for (const t of terms) {
    const at = folded.indexOf(t)
    if (at === 0 || /[^\p{L}\p{N}]/u.test(folded[at - 1] ?? '')) s += 12
  }
  return s
}

const DAY = 86_400_000

/**
 * A small push for newer and saved things, so ties go to what you're likely after. Not read state:
 * opening a result marks it read, and the results must not reshuffle under you.
 */
function freshness(item: Item, now: number): number {
  const age = Math.max(0, now - item.sortTime)
  let s = age < 2 * DAY ? 8 : age < 7 * DAY ? 5 : age < 30 * DAY ? 2 : 0
  if (item.saved) s += 4
  return s
}

function matchItem(item: Item, data: SearchData, query: string, terms: string[], now: number): ItemHit | null {
  const feed = data.feeds.get(item.feedUrl)
  const titleFolded = fold(item.title || '')
  const base = { type: 'item' as const, id: item.id, item, kind: kindOf(item, data.ctx), atMs: null, transcriptMatches: 0 }
  const titleRanges = findTerms(titleFolded, terms)
  const title = markTerms(item.title || '', terms, titleFolded)
  const fresh = freshness(item, now)
  if (titleRanges) return { ...base, score: titleScore(titleFolded, query, terms) + fresh, field: 'title', title, snippet: null }

  const feedName = fold(`${feedDisplayTitle(feed)} ${item.author ?? ''}`)
  // Every term somewhere in the title, feed name or author, e.g. "planet money nvidia" needs the text too
  if (findTerms(feedName, terms)) return { ...base, score: 55 + fresh, field: 'feed', title, snippet: null }

  const lines = data.transcripts.get(item.id)
  if (lines?.length) {
    let first: TranscriptLine | null = null
    let firstFolded = ''
    let count = 0
    for (const line of lines) {
      const f = fold(line.text)
      if (findTerms(f, terms)) {
        count++
        if (!first) {
          first = line
          firstFolded = f
        }
      }
    }
    if (first) {
      return {
        ...base,
        score: 40 + Math.min(10, count) + fresh,
        field: 'transcript',
        title,
        snippet: snippetAround(first.text, firstFolded, terms, 40, 140),
        atMs: first.startMs,
        transcriptMatches: count,
      }
    }
  }

  const { text, folded } = itemText(item)
  if (findTerms(folded, terms)) {
    // Terms in the title count a little even when the rest are only in the text
    const inTitle = terms.filter((t) => titleFolded.includes(t)).length
    return { ...base, score: 30 + inTitle * 10 + fresh, field: 'text', title, snippet: snippetAround(text, folded, terms) }
  }
  // Split between the title and the text ("rubin nvidia" where only one is in the title)
  const together = `${titleFolded} ${folded}`
  if (terms.length > 1 && findTerms(together, terms)) {
    return { ...base, score: 28 + fresh, field: 'text', title, snippet: snippetAround(text, folded, terms) }
  }
  return null
}

/** At most this many items come back; the palette shows a few, the results view scrolls the rest. */
export const MAX_ITEM_RESULTS = 300

export function searchAll(query: string, data: SearchData): SearchResults {
  const terms = queryTerms(query)
  const q = terms.join(' ')
  const counts: Record<SearchScope, number> = { all: 0, article: 0, podcast: 0, youtube: 0, highlight: 0, library: 0, book: 0, zim: 0 }
  const empty: SearchResults = { query, terms, items: [], highlights: [], library: [], books: [], zim: [], counts }
  if (!terms.length) return empty
  const now = data.now ?? Date.now()

  const items: ItemHit[] = []
  for (const item of data.items) {
    const hit = matchItem(item, data, q, terms, now)
    if (hit) items.push(hit)
  }
  items.sort((a, b) => b.score - a.score || b.item.sortTime - a.item.sortTime)
  for (const h of items) counts[h.kind]++

  const highlights: HighlightHit[] = []
  for (const h of data.highlights) {
    const f = fold(`${h.text} ${h.note} ${h.itemTitle}`)
    if (!findTerms(f, terms)) continue
    const inText = findTerms(fold(h.text), terms)
    highlights.push({
      type: 'highlight',
      id: h.id,
      highlight: h,
      score: (inText ? 70 : 45) + (h.createdAt > now - 30 * DAY ? 3 : 0),
      text: snippetAround(h.text, fold(h.text), terms, 60, 200),
      note: h.note ? markTerms(h.note, terms) : null,
    })
  }
  highlights.sort((a, b) => b.score - a.score || b.highlight.createdAt - a.highlight.createdAt)
  counts.highlight = highlights.length

  const library: (FeedHit | FolderHit | PlaylistHit)[] = []
  const unread = new Map<string, number>()
  for (const i of data.items) if (!i.read) unread.set(i.feedUrl, (unread.get(i.feedUrl) ?? 0) + 1)
  const folders = new Map<string, number>()
  for (const feed of data.feeds.values()) {
    if (feed.tag) folders.set(feed.tag, (folders.get(feed.tag) ?? 0) + 1)
    const name = feedDisplayTitle(feed)
    const f = fold(`${name} ${hostOf(feed.siteUrl || feed.url)}`)
    if (!findTerms(f, terms)) continue
    library.push({ type: 'feed', id: feed.url, feed, score: titleScore(fold(name), q, terms) + 20, title: markTerms(name, terms), unread: unread.get(feed.url) ?? 0 })
  }
  for (const [tag, n] of folders) {
    const f = fold(tag)
    if (findTerms(f, terms)) library.push({ type: 'folder', id: tag, tag, score: titleScore(f, q, terms) + 15, title: markTerms(tag, terms), feeds: n })
  }
  for (const pl of data.playlists) {
    const f = fold(pl.name)
    if (findTerms(f, terms)) library.push({ type: 'playlist', id: pl.id, playlist: pl, score: titleScore(f, q, terms) + 18, title: markTerms(pl.name, terms) })
  }
  library.sort((a, b) => b.score - a.score)
  counts.library = library.length

  const books: BookHit[] = []
  for (const book of data.books ?? []) {
    const titleFolded = fold(book.title)
    const authorFolded = fold(book.author || '')
    if (!findTerms(`${titleFolded} ${authorFolded}`, terms)) continue
    const score = findTerms(titleFolded, terms) ? titleScore(titleFolded, q, terms) + 22 : findTerms(authorFolded, terms) ? 75 : 60
    books.push({ type: 'book', id: book.id, book, score, title: markTerms(book.title, terms, titleFolded), author: markTerms(book.author || '', terms, authorFolded) })
  }
  books.sort((a, b) => b.score - a.score || b.book.lastOpenedAt - a.book.lastOpenedAt)
  counts.book = books.length
  counts.all = items.length + highlights.length + library.length + books.length

  return { query, terms, items: items.slice(0, MAX_ITEM_RESULTS), highlights, library, books, zim: [], counts }
}

/**
 * Adds ZIM title matches to a search. They rank below your own articles and books (a Wikipedia ZIM
 * has a page for nearly every word), unless the title is exactly what was typed.
 */
const WIKI_NAMESPACE = /^(Category|Template|Portal|Help|Wikipedia|File|Module):/

export function addZimHits(r: SearchResults, titles: ZimTitle[]): SearchResults {
  if (!r.terms.length || !titles.length) return r
  const q = r.terms.join(' ')
  const zim: ZimHit[] = []
  const seen = new Set<string>()
  for (const t of titles) {
    const id = `${t.bookId}\n${t.path}`
    const folded = fold(t.title)
    if (seen.has(id) || !findTerms(folded, r.terms)) continue
    seen.add(id)
    // Wiki housekeeping pages ("Category:…", "Template:…") after real articles
    const housekeeping = WIKI_NAMESPACE.test(t.title) ? 30 : 0
    const score = titleScore(folded, q, r.terms) - 25 + (folded === q ? 30 : 0) - housekeeping
    zim.push({ type: 'zim', id, bookId: t.bookId, bookTitle: t.bookTitle, path: t.path, score, title: markTerms(t.title, r.terms, folded) })
  }
  zim.sort((a, b) => b.score - a.score || a.title.text.length - b.title.text.length)
  return { ...r, zim, counts: { ...r.counts, zim: zim.length, all: r.counts.all - r.counts.zim + zim.length } }
}

function hostOf(url: string): string {
  try {
    return new URL(url).hostname.replace(/^www\./, '')
  } catch {
    return ''
  }
}

/** The results one scope shows, best first (for the scoped palette and the results view). */
export function hitsInScope(r: SearchResults, scope: SearchScope): SearchHit[] {
  switch (scope) {
    case 'all':
      return [...r.items, ...r.highlights, ...r.library, ...r.books, ...r.zim].sort((a, b) => b.score - a.score)
    case 'highlight':
      return r.highlights
    case 'library':
      return r.library
    case 'book':
      return r.books
    case 'zim':
      return r.zim
    default:
      return r.items.filter((h) => h.kind === scope)
  }
}

// --- Recent searches (this browser only) ------------------------------------------------------

const RECENT_KEY = 'feeder.recentSearches'
const RECENT_MAX = 6

export function loadRecentSearches(): string[] {
  try {
    const v = JSON.parse(localStorage.getItem(RECENT_KEY) || '[]')
    return Array.isArray(v) ? v.filter((s) => typeof s === 'string').slice(0, RECENT_MAX) : []
  } catch {
    return []
  }
}

export function rememberSearch(query: string): string[] {
  const q = query.trim()
  if (!q) return loadRecentSearches()
  const next = [q, ...loadRecentSearches().filter((s) => fold(s) !== fold(q))].slice(0, RECENT_MAX)
  try {
    localStorage.setItem(RECENT_KEY, JSON.stringify(next))
  } catch {
    /* private mode */
  }
  return next
}

export function forgetSearch(query: string): string[] {
  const next = loadRecentSearches().filter((s) => s !== query)
  try {
    localStorage.setItem(RECENT_KEY, JSON.stringify(next))
  } catch {
    /* private mode */
  }
  return next
}

/** "16:17" or "1:02:03" for a transcript time. */
export function clock(ms: number): string {
  const s = Math.floor(ms / 1000)
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = String(s % 60).padStart(2, '0')
  return h ? `${h}:${String(m).padStart(2, '0')}:${sec}` : `${m}:${sec}`
}

/** Apple keyboards show ⌘, the rest Ctrl. */
export function isMacLike(): boolean {
  return typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent)
}
