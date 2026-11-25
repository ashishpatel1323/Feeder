/**
 * Global search. Two views over one search (lib/search.ts):
 *
 * - The palette (⌘K, Ctrl+K or / anywhere): a box over the app for jumping straight to something.
 *   Empty, it offers recent searches, what you were in the middle of, and a few actions.
 *   Tab narrows it to one type; ⌘↵ (Ctrl+Enter) opens every result in the results view.
 * - The results view: the list pane shows every result with filter chips, and the reader opens
 *   the selected one at the match, so you can go through results without losing them.
 */
import { useDeferredValue, useEffect, useLayoutEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useLiveQuery } from 'dexie-react-hooks'
import { db, feedDisplayTitle, type Feed, type Highlight, type Item } from '../lib/db'
import { proxiedUrl } from '../lib/api'
import { itemFacts } from '../lib/itemview'
import type { PlaylistContext } from '../lib/playlists'
import {
  clock,
  forgetSearch,
  hitsInScope,
  isMacLike,
  loadRecentSearches,
  pruneTextCache,
  queryTerms,
  rememberSearch,
  SCOPE_LABELS,
  SCOPES,
  searchAll,
  addZimHits,
  type ItemHit,
  type SearchData,
  type SearchHit,
  type SearchResults,
  type SearchScope,
  type Snippet,
  type ZimTitle,
} from '../lib/search'
import { listBooks as listZimBooks, openBook as openZimBook, zimSupported } from '../lib/zim/library'
import { storedTranscriptLines } from '../lib/transcripts'
import { isWatched } from '../lib/videopos'
import { Icon, IconButton, type IconName } from './Icon'
import { ItemRow, KIND_ICONS, relativeTime, usePlaylistContext } from './ItemRow'
import { usePlaylists } from './Playlists'
import { FeedIcon } from './Sidebar'

/** Everything the search looks through, kept live; transcripts are read again each time search opens. */
export function useSearchData(items: Item[], feeds: Map<string, Feed>, highlights: Highlight[], rate: number, active: boolean): SearchData {
  const playlists = usePlaylists()
  const ctx = usePlaylistContext(feeds, rate)
  const [transcripts, setTranscripts] = useState<SearchData['transcripts']>(() => new Map())
  useEffect(() => {
    if (!active) return
    let live = true
    storedTranscriptLines()
      .then((m) => live && setTranscripts(m))
      .catch(() => undefined)
    return () => {
      live = false
    }
  }, [active])
  // Text of deleted articles needn't stay in memory
  useEffect(() => {
    if (!active) pruneTextCache(new Set(items.map((i) => i.id)))
  }, [active, items])
  const books = useLiveQuery(() => db.books.toArray(), [])
  return useMemo(() => ({ items, feeds, highlights, playlists, books: books ?? NO_BOOKS, transcripts, ctx }), [items, feeds, highlights, playlists, books, transcripts, ctx])
}
const NO_BOOKS: SearchData['books'] = []

/** ZIM titles per book, at most this many each. */
const ZIM_PER_BOOK = 8

/**
 * Titles starting with the query in each ZIM file on this device. They're read from the files, so
 * they come a moment after the rest; the list of files is read once per search session.
 */
function useZimTitles(query: string): ZimTitle[] {
  const [titles, setTitles] = useState<ZimTitle[]>([])
  const books = useRef<Promise<{ id: string; title: string }[]> | null>(null)
  useEffect(() => {
    const q = query.trim()
    if (!q || !zimSupported()) return setTitles([])
    let live = true
    const t = setTimeout(() => {
      books.current ??= listZimBooks().then(
        (r) => r.books.map((b) => ({ id: b.id, title: b.title })),
        () => [],
      )
      void books.current.then(async (list) => {
        const found = await Promise.all(
          list.map((b) =>
            openZimBook(b.id)
              .then((z) => z.suggest(q, ZIM_PER_BOOK))
              .then((hits) => hits.map((h) => ({ bookId: b.id, bookTitle: b.title, path: h.path, title: h.title })))
              .catch(() => [] as ZimTitle[]),
          ),
        )
        if (live) setTitles(found.flat())
      })
    }, 150)
    return () => {
      live = false
      clearTimeout(t)
    }
  }, [query])
  return titles
}

/** Searching is quick, but not free on a big library: only while the query settles. */
function useResults(query: string, data: SearchData): SearchResults {
  const deferred = useDeferredValue(query)
  const zim = useZimTitles(deferred)
  const base = useMemo(() => searchAll(deferred, data), [deferred, data])
  return useMemo(() => addZimHits(base, zim), [base, zim])
}

/** The icon, title and line under it for an ebook or ZIM result (palette and results view). */
function bookOrZimRow(h: SearchHit): { icon: ReactNode; title: ReactNode; meta: ReactNode } | null {
  if (h.type === 'book') {
    return {
      icon: h.book.cover ? (
        <span className="cp-ic" aria-hidden="true">
          <img src={h.book.cover} alt="" />
        </span>
      ) : (
        <span className="cp-ic plain">
          <Icon name="book" size={18} />
        </span>
      ),
      title: <Marked s={h.title} />,
      meta: h.author.text ? (
        <>
          Ebook · <Marked s={h.author} />
        </>
      ) : (
        'Ebook'
      ),
    }
  }
  if (h.type === 'zim') {
    return {
      icon: (
        <span className="cp-ic plain">
          <Icon name="public" size={18} />
        </span>
      ),
      title: <Marked s={h.title} />,
      meta: `ZIM · ${h.bookTitle}`,
    }
  }
  return null
}

/** Text with the matched words marked. */
export function Marked({ s }: { s: Snippet }) {
  if (!s.ranges.length) return <>{s.text}</>
  const parts: ReactNode[] = []
  let at = 0
  s.ranges.forEach(([a, b], i) => {
    if (a > at) parts.push(s.text.slice(at, a))
    parts.push(
      <mark key={i} className="sr-mark">
        {s.text.slice(a, b)}
      </mark>,
    )
    at = b
  })
  if (at < s.text.length) parts.push(s.text.slice(at))
  return <>{parts}</>
}

export interface PaletteAction {
  id: string
  label: string
  icon: IconName
  /** More words that find it ("dark" finds the theme switch). */
  keywords?: string
  run: () => void
}

/** How a result opens: from the palette, a transcript match also plays from that line. */
export interface OpenOptions {
  query: string
  /** The item results in order, so the reader's next and previous go through them. */
  order: string[]
  /** The transcript line times of the item results, for marking the match in each. */
  at: Record<string, number | null>
  play: boolean
}

function thumbSrc(item: Item): string | null {
  if (!item.imageUrl) return null
  return item.imageUrl.startsWith('http://') ? proxiedUrl(item.imageUrl) : item.imageUrl
}

function Thumb({ item, kind }: { item: Item; kind: ItemHit['kind'] }) {
  const [broken, setBroken] = useState(false)
  const src = broken ? null : thumbSrc(item)
  return (
    <span className={`cp-ic k-${kind}`} aria-hidden="true">
      {src ? <img src={src} alt="" loading="lazy" referrerPolicy="no-referrer" onError={() => setBroken(true)} /> : <Icon name={KIND_ICONS[kind]} size={18} />}
      {src && kind !== 'article' ? (
        <span className="cp-badge">
          <Icon name={KIND_ICONS[kind]} size={11} />
        </span>
      ) : null}
    </span>
  )
}

const HL_LABEL: Record<Highlight['color'], string> = { yellow: 'Yellow', green: 'Green', blue: 'Blue', pink: 'Pink' }

/** One line about an item result: where the words were found, or its type, feed and age. */
function itemMeta(hit: ItemHit, ctx: PlaylistContext): ReactNode {
  if (hit.field === 'transcript' && hit.snippet) {
    return (
      <>
        <span className="cp-time">{clock(hit.atMs ?? 0)}</span> “<Marked s={hit.snippet} />”{hit.transcriptMatches > 1 ? <span className="cp-dim"> +{hit.transcriptMatches - 1} more</span> : null}
      </>
    )
  }
  if (hit.field === 'text' && hit.snippet) return <Marked s={hit.snippet} />
  const facts = itemFacts(hit.item, ctx)
  return [facts.kindLabel, feedDisplayTitle(ctx.feeds.get(hit.item.feedUrl)), relativeTime(hit.item.sortTime), facts.percentText || facts.lengthText].filter(Boolean).join(' · ')
}

type Row =
  | { key: string; kind: 'hit'; hit: SearchHit }
  | { key: string; kind: 'action'; action: PaletteAction }
  | { key: string; kind: 'resume'; item: Item; percent: string }
  | { key: string; kind: 'all'; label: string; scope: SearchScope }

interface Group {
  label: string
  rows: Row[]
  more?: number
}

const hitRow = (hit: SearchHit): Row => ({ key: `${hit.type}:${hit.id}`, kind: 'hit', hit })

function matchesAction(a: PaletteAction, terms: string[]): boolean {
  const hay = `${a.label} ${a.keywords ?? ''}`.toLowerCase()
  return terms.every((t) => hay.includes(t))
}

interface PaletteProps {
  data: SearchData
  actions: PaletteAction[]
  onClose: () => void
  onOpen: (hit: SearchHit, opts: OpenOptions) => void
  onShowAll: (query: string, scope: SearchScope) => void
  onResume: (item: Item) => void
}

export function CommandPalette(p: PaletteProps) {
  const [query, setQuery] = useState('')
  const [scope, setScope] = useState<SearchScope>('all')
  const [active, setActive] = useState(0)
  const [recent, setRecent] = useState(loadRecentSearches)
  const inputRef = useRef<HTMLInputElement>(null)
  const listRef = useRef<HTMLDivElement>(null)
  const results = useResults(query, p.data)
  const terms = results.terms
  const hasQuery = queryTerms(query).length > 0

  // Episodes and videos you're part way through, newest first
  const positions = useLiveQuery(() => db.videoPos.toArray(), []) ?? []
  const inProgress = useMemo(() => {
    const byId = new Map(p.data.items.map((i) => [i.id, i]))
    return positions
      .filter((v) => v.t > 5 && !isWatched(v) && byId.has(v.id))
      .sort((a, b) => b.ts - a.ts)
      .slice(0, 3)
      .map((v) => {
        const item = byId.get(v.id)!
        return { item, percent: itemFacts(item, p.data.ctx).percentText }
      })
  }, [positions, p.data.items, p.data.ctx])

  const groups = useMemo<Group[]>(() => {
    if (!hasQuery) {
      const out: Group[] = []
      if (inProgress.length) out.push({ label: 'Jump back in', rows: inProgress.map(({ item, percent }) => ({ key: `resume:${item.id}`, kind: 'resume', item, percent })) })
      out.push({ label: 'Actions', rows: p.actions.slice(0, 4).map((a) => ({ key: `action:${a.id}`, kind: 'action', action: a })) })
      return out
    }
    if (scope !== 'all') {
      const hits = hitsInScope(results, scope)
      const shown = hits.slice(0, 40)
      const out: Group[] = [{ label: `${SCOPE_LABELS[scope]} · ${results.counts[scope]}`, rows: shown.map(hitRow) }]
      if (hits.length > shown.length) out.push({ label: '', rows: [{ key: 'all', kind: 'all', label: `Show all ${results.counts[scope]} in the list`, scope }] })
      return out.filter((g) => g.rows.length)
    }
    const all = hitsInScope(results, 'all')
    const top = all[0]
    const notTop = (h: SearchHit) => h !== top
    const articles = results.items.filter((h) => h.kind === 'article' && notTop(h))
    const media = results.items.filter((h) => h.kind !== 'article' && notTop(h))
    const highlights = results.highlights.filter(notTop)
    const library = results.library.filter(notTop)
    const books = results.books.filter(notTop)
    const zim = results.zim.filter(notTop)
    const actions = p.actions.filter((a) => matchesAction(a, terms))
    const out: Group[] = []
    if (top) out.push({ label: 'Top hit', rows: [hitRow(top)] })
    const add = (label: string, list: SearchHit[], max: number) => {
      if (list.length) out.push({ label, rows: list.slice(0, max).map(hitRow), more: list.length - Math.min(max, list.length) })
    }
    add('Articles', articles, 3)
    add('Videos and episodes', media, 3)
    add('Highlights', highlights, 2)
    add('Feeds, folders and playlists', library, 3)
    add('Ebooks', books, 3)
    add('In your ZIM files', zim, 3)
    if (actions.length) out.push({ label: 'Actions', rows: actions.slice(0, 3).map((a) => ({ key: `action:${a.id}`, kind: 'action', action: a })) })
    const shown = out.reduce((n, g) => n + g.rows.filter((r) => r.kind === 'hit').length, 0)
    if (results.counts.all > shown) out.push({ label: '', rows: [{ key: 'all', kind: 'all', label: `Show all ${results.counts.all} results in the list`, scope: 'all' }] })
    return out
  }, [hasQuery, inProgress, p.actions, scope, results, terms])

  const rows = useMemo(() => groups.flatMap((g) => g.rows), [groups])
  useEffect(() => setActive(0), [query, scope])
  const current = rows[Math.min(active, rows.length - 1)]

  // Keep the chosen row in view while moving with the arrow keys
  useLayoutEffect(() => {
    listRef.current?.querySelector('.cp-row.on')?.scrollIntoView({ block: 'nearest' })
  }, [active, rows])

  // Focus goes back where it was when the palette closes
  useEffect(() => {
    const before = document.activeElement as HTMLElement | null
    inputRef.current?.focus()
    return () => before?.focus?.()
  }, [])

  const openOpts = (play: boolean): OpenOptions => {
    const order = results.items.filter((h) => scope === 'all' || h.kind === scope).map((h) => h.id)
    const at: Record<string, number | null> = {}
    for (const h of results.items) at[h.id] = h.atMs
    return { query, order, at, play }
  }

  const run = (row: Row | undefined) => {
    if (!row) return
    if (row.kind === 'action') {
      p.onClose()
      row.action.run()
      return
    }
    if (row.kind === 'resume') {
      p.onClose()
      p.onResume(row.item)
      return
    }
    if (row.kind === 'all') {
      setRecent(rememberSearch(query))
      p.onClose()
      p.onShowAll(query, row.scope)
      return
    }
    setRecent(rememberSearch(query))
    p.onClose()
    p.onOpen(row.hit, openOpts(row.hit.type === 'item' && row.hit.field === 'transcript'))
  }

  const cycleScope = (by: number) => setScope((s) => SCOPES[(SCOPES.indexOf(s) + by + SCOPES.length) % SCOPES.length])

  const onKey = (e: React.KeyboardEvent) => {
    // The app's own shortcuts stay quiet while typing here
    e.stopPropagation()
    if (e.key === 'ArrowDown' || (e.ctrlKey && e.key === 'n')) {
      e.preventDefault()
      setActive((a) => (rows.length ? (a + 1) % rows.length : 0))
    } else if (e.key === 'ArrowUp' || (e.ctrlKey && e.key === 'p')) {
      e.preventDefault()
      setActive((a) => (rows.length ? (a - 1 + rows.length) % rows.length : 0))
    } else if (e.key === 'Enter') {
      e.preventDefault()
      if ((e.metaKey || e.ctrlKey) && hasQuery) {
        setRecent(rememberSearch(query))
        p.onClose()
        p.onShowAll(query, scope)
      } else run(current)
    } else if (e.key === 'Tab') {
      e.preventDefault()
      cycleScope(e.shiftKey ? -1 : 1)
    } else if (e.key === 'Backspace' && !query && scope !== 'all') {
      e.preventDefault()
      setScope('all')
    } else if (e.key === 'Escape') {
      e.preventDefault()
      p.onClose()
    } else if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
      e.preventDefault()
      p.onClose()
    }
  }

  const hint = (row: Row): string => {
    if (row.kind === 'resume') return 'Resume'
    if (row.kind === 'action' || row.kind === 'all') return ''
    const h = row.hit
    if (h.type === 'item') return h.field === 'transcript' && h.atMs != null ? `Play from ${clock(h.atMs)}` : h.kind === 'article' ? 'Read' : 'Open'
    if (h.type === 'feed' || h.type === 'folder') return 'Show articles'
    if (h.type === 'book') return 'Read'
    return 'Open'
  }

  const renderRow = (row: Row) => {
    let icon: ReactNode
    let title: ReactNode
    let meta: ReactNode = null
    if (row.kind === 'action') {
      icon = (
        <span className="cp-ic plain">
          <Icon name={row.action.icon} size={18} />
        </span>
      )
      title = row.action.label
    } else if (row.kind === 'all') {
      icon = (
        <span className="cp-ic plain">
          <Icon name="search" size={18} />
        </span>
      )
      title = row.label
    } else if (row.kind === 'resume') {
      const kind = itemFacts(row.item, p.data.ctx).kind
      icon = <Thumb item={row.item} kind={kind} />
      title = row.item.title
      meta = [kind === 'youtube' ? 'Video' : 'Episode', feedDisplayTitle(p.data.feeds.get(row.item.feedUrl)), row.percent].filter(Boolean).join(' · ')
    } else {
      const h = row.hit
      const bz = bookOrZimRow(h)
      if (bz) ({ icon, title, meta } = bz)
      else if (h.type === 'item') {
        icon = <Thumb item={h.item} kind={h.kind} />
        title = <Marked s={h.title} />
        meta = itemMeta(h, p.data.ctx)
      } else if (h.type === 'highlight') {
        icon = (
          <span className={`cp-ic plain hl-${h.highlight.color}`} title={`${HL_LABEL[h.highlight.color]} highlight`}>
            <Icon name="highlight" size={18} />
          </span>
        )
        title = (
          <>
            “<Marked s={h.text} />”
          </>
        )
        meta = (
          <>
            {h.highlight.itemTitle}
            {h.note ? (
              <>
                {' · '}
                <Marked s={h.note} />
              </>
            ) : null}
          </>
        )
      } else if (h.type === 'feed') {
        icon = (
          <span className="cp-ic feed">
            <FeedIcon feed={h.feed} />
          </span>
        )
        title = <Marked s={h.title} />
        meta = ['Feed', h.feed.tag ? `in ${h.feed.tag}` : '', h.unread ? `${h.unread} unread` : ''].filter(Boolean).join(' · ')
      } else if (h.type === 'folder') {
        icon = (
          <span className="cp-ic plain">
            <Icon name="folder" size={18} />
          </span>
        )
        title = <Marked s={h.title} />
        meta = `Folder · ${h.feeds} feed${h.feeds === 1 ? '' : 's'}`
      } else if (h.type === 'playlist') {
        icon = (
          <span className="cp-ic tile" style={{ background: h.playlist.color }}>
            {h.playlist.icon}
          </span>
        )
        title = <Marked s={h.title} />
        meta = h.playlist.smart ? 'Smart playlist' : `Playlist · ${h.playlist.itemIds.length} item${h.playlist.itemIds.length === 1 ? '' : 's'}`
      }
    }
    const i = rows.indexOf(row)
    const on = row === current
    const words = hint(row)
    return (
      <div
        key={row.key}
        id={`cp-row-${i}`}
        role="option"
        aria-selected={on}
        className={`cp-row${on ? ' on' : ''}`}
        onMouseMove={() => i !== active && setActive(i)}
        onClick={() => run(row)}
      >
        {icon}
        <span className="cp-txt">
          <span className="cp-title">{title}</span>
          {meta ? <span className="cp-meta">{meta}</span> : null}
        </span>
        {on && words ? (
          <span className="cp-hint">
            {words} <kbd>↵</kbd>
          </span>
        ) : null}
      </div>
    )
  }

  return (
    <div className="cp-backdrop" onMouseDown={(e) => e.target === e.currentTarget && p.onClose()}>
      <div className="cp" role="dialog" aria-modal="true" aria-label="Search Feeder">
        <div className="cp-input">
          <Icon name="search" size={22} />
          <input
            ref={inputRef}
            type="text"
            role="combobox"
            aria-expanded
            aria-controls="cp-list"
            aria-activedescendant={current ? `cp-row-${rows.indexOf(current)}` : undefined}
            aria-label="Search articles, episodes, videos, feeds, playlists, highlights, ebooks and ZIM files"
            placeholder="Search articles, episodes, videos, feeds…"
            value={query}
            spellCheck={false}
            autoComplete="off"
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={onKey}
          />
          {scope !== 'all' ? (
            <button type="button" className="cp-scope" title="Show every type (Backspace)" onClick={() => (setScope('all'), inputRef.current?.focus())}>
              {SCOPE_LABELS[scope]}
              <Icon name="close" size={14} />
            </button>
          ) : null}
          <kbd className="cp-esc">esc</kbd>
        </div>
        <div className="cp-body" id="cp-list" role="listbox" ref={listRef} aria-label="Results">
          {!hasQuery && recent.length ? (
            <>
              <div className="cp-group">Recent searches</div>
              <div className="cp-recent">
                {recent.map((r) => (
                  <span key={r} className="chip cp-recent-chip">
                    <button type="button" onClick={() => (setQuery(r), inputRef.current?.focus())}>
                      <Icon name="history" size={16} />
                      {r}
                    </button>
                    <button type="button" className="cp-forget" title="Remove from recent searches" aria-label={`Remove ${r} from recent searches`} onClick={() => setRecent(forgetSearch(r))}>
                      <Icon name="close" size={14} />
                    </button>
                  </span>
                ))}
              </div>
            </>
          ) : null}
          {hasQuery && !rows.length ? (
            <p className="cp-empty">
              No matches for “{query.trim()}”{scope !== 'all' ? ` in ${SCOPE_LABELS[scope].toLowerCase()}` : ''}.
            </p>
          ) : null}
          {groups.map((g, gi) => (
            <div key={gi} role="group" aria-label={g.label || undefined}>
              {g.label ? (
                <div className="cp-group">
                  {g.label}
                  {g.more ? <span className="cp-more">{g.more} more</span> : null}
                </div>
              ) : null}
              {g.rows.map(renderRow)}
            </div>
          ))}
        </div>
        <div className="cp-foot" aria-hidden="true">
          <span>
            <kbd>↑</kbd>
            <kbd>↓</kbd> move
          </span>
          <span>
            <kbd>↵</kbd> open
          </span>
          {hasQuery ? (
            <span>
              <kbd>{isMacLike() ? '⌘' : 'Ctrl'}</kbd>
              <kbd>↵</kbd> all results
            </span>
          ) : null}
          <span>
            <kbd>tab</kbd> {scope === 'all' ? 'filter by type' : `next: ${SCOPE_LABELS[SCOPES[(SCOPES.indexOf(scope) + 1) % SCOPES.length]]}`}
          </span>
          <span className="cp-foot-sp" />
          {hasQuery ? (
            <span>
              {results.counts[scope]} result{results.counts[scope] === 1 ? '' : 's'}
            </span>
          ) : (
            <span>
              <kbd>{isMacLike() ? '⌘' : 'Ctrl'}</kbd>
              <kbd>K</kbd> or <kbd>/</kbd> anywhere
            </span>
          )}
        </div>
      </div>
    </div>
  )
}


// --- Results view (the list pane) ---------------------------------------------------------------

interface ViewProps {
  query: string
  scope: SearchScope
  onQuery: (q: string) => void
  onScope: (s: SearchScope) => void
  data: SearchData
  selectedId: string | null
  onOpen: (hit: SearchHit, opts: OpenOptions) => void
  onClose: () => void
  onMenu: () => void
  onDelete: (items: Item[]) => void
  onOpenFeed: (url: string) => void
  /** The item results in order, for J / K and next / previous. */
  onOrder: (ids: string[]) => void
}

export function SearchView(p: ViewProps) {
  const results = useResults(p.query, p.data)
  const hits = useMemo(() => hitsInScope(results, p.scope), [results, p.scope])
  const itemHits = useMemo(() => hits.filter((h): h is ItemHit => h.type === 'item'), [hits])
  const order = useMemo(() => itemHits.map((h) => h.id), [itemHits])
  const at = useMemo(() => Object.fromEntries(itemHits.map((h) => [h.id, h.atMs])), [itemHits])
  const inputRef = useRef<HTMLInputElement>(null)
  const { onOrder } = p
  useEffect(() => onOrder(order), [order, onOrder])
  const hasQuery = results.terms.length > 0
  const open = (hit: SearchHit) => {
    rememberSearch(p.query)
    p.onOpen(hit, { query: p.query, order, at, play: false })
  }
  // Shown at most this many at once; the list grows as you scroll to its end
  const [limit, setLimit] = useState(60)
  useEffect(() => setLimit(60), [p.query, p.scope])

  return (
    <section className="list-pane search-view" aria-label="Search results">
      <header className="sv-top">
        <div className="sv-field" role="search">
          <span className="only-narrow">
            <IconButton icon="menu" label="Feeds" onClick={p.onMenu} />
          </span>
          <IconButton icon="back" label="Close search (Esc)" onClick={p.onClose} />
          <input
            ref={inputRef}
            type="text"
            value={p.query}
            placeholder="Search everything"
            aria-label="Search everything"
            spellCheck={false}
            autoComplete="off"
            onChange={(e) => p.onQuery(e.target.value)}
            onKeyDown={(e) => {
              e.stopPropagation()
              if (e.key === 'Escape') {
                e.preventDefault()
                if (p.query) p.onQuery('')
                else p.onClose()
              } else if (e.key === 'Enter' && hits[0]) {
                e.preventDefault()
                open(hits[0])
              } else if (e.key === 'ArrowDown') {
                e.preventDefault()
                ;(document.querySelector('.search-view .sv-list button, .search-view .sv-list [tabindex="0"]') as HTMLElement | null)?.focus()
              }
            }}
          />
          {p.query ? <IconButton icon="close" label="Clear" onClick={() => (p.onQuery(''), inputRef.current?.focus())} /> : null}
        </div>
        <div className="sv-chips" role="radiogroup" aria-label="Show">
          {SCOPES.map((s) => (
            <button key={s} type="button" role="radio" aria-checked={p.scope === s} className={`chip${p.scope === s ? ' on' : ''}`} disabled={hasQuery && s !== 'all' && !results.counts[s] && p.scope !== s} onClick={() => p.onScope(s)}>
              {SCOPE_LABELS[s]}
              {hasQuery ? <span className="sv-count">{results.counts[s]}</span> : null}
            </button>
          ))}
        </div>
      </header>
      <div
        className="list-scroll sv-list"
        onScroll={(e) => {
          const el = e.currentTarget
          if (el.scrollTop + el.clientHeight > el.scrollHeight - 400 && limit < hits.length) setLimit((l) => l + 60)
        }}
      >
        {!hasQuery ? <p className="empty">Search titles, article text, podcast transcripts, highlights, feeds, playlists, ebooks and ZIM files.</p> : null}
        {hasQuery && !hits.length ? <p className="empty">No matches for “{p.query.trim()}”.</p> : null}
        {hits.slice(0, limit).map((h) => (
          <ResultRow key={`${h.type}:${h.id}`} hit={h} data={p.data} selected={h.type === 'item' && h.id === p.selectedId} onOpen={() => open(h)} onDelete={p.onDelete} onOpenFeed={p.onOpenFeed} />
        ))}
      </div>
    </section>
  )
}

function ResultRow({ hit, data, selected, onOpen, onDelete, onOpenFeed }: { hit: SearchHit; data: SearchData; selected: boolean; onOpen: () => void; onDelete: (items: Item[]) => void; onOpenFeed: (url: string) => void }) {
  if (hit.type === 'item') {
    const where =
      hit.field === 'transcript' ? (
        <span className="sv-where">
          <Icon name="closedCaption" size={13} /> {hit.transcriptMatches} in transcript
        </span>
      ) : hit.field === 'text' ? (
        <span className="sv-where">In the text</span>
      ) : null
    const snippet =
      hit.field === 'transcript' && hit.snippet ? (
        <>
          <span className="cp-time">{clock(hit.atMs ?? 0)}</span> <Marked s={hit.snippet} />
        </>
      ) : hit.snippet ? (
        <Marked s={hit.snippet} />
      ) : undefined
    return (
      <ItemRow
        item={hit.item}
        feed={data.feeds.get(hit.item.feedUrl)}
        ctx={data.ctx}
        onOpen={onOpen}
        onDelete={() => onDelete([hit.item])}
        onOpenFeed={onOpenFeed}
        selected={selected}
        titleNode={<Marked s={hit.title} />}
        snippet={snippet}
        snippetLines={2}
        metaExtra={where}
      />
    )
  }
  let icon: ReactNode = null
  let title: ReactNode = null
  let meta: ReactNode = null
  const bz = bookOrZimRow(hit)
  if (bz) ({ icon, title, meta } = bz)
  else if (hit.type === 'highlight') {
    icon = (
      <span className={`cp-ic plain hl-${hit.highlight.color}`}>
        <Icon name="highlight" size={18} />
      </span>
    )
    title = (
      <>
        “<Marked s={hit.text} />”
      </>
    )
    meta = (
      <>
        Highlight · {hit.highlight.itemTitle}
        {hit.note ? (
          <>
            {' · '}
            <Marked s={hit.note} />
          </>
        ) : null}
      </>
    )
  } else if (hit.type === 'feed') {
    icon = (
      <span className="cp-ic feed">
        <FeedIcon feed={hit.feed} />
      </span>
    )
    title = <Marked s={hit.title} />
    meta = ['Feed', hit.feed.tag ? `in ${hit.feed.tag}` : '', hit.unread ? `${hit.unread} unread` : ''].filter(Boolean).join(' · ')
  } else if (hit.type === 'folder') {
    icon = (
      <span className="cp-ic plain">
        <Icon name="folder" size={18} />
      </span>
    )
    title = <Marked s={hit.title} />
    meta = `Folder · ${hit.feeds} feed${hit.feeds === 1 ? '' : 's'}`
  } else if (hit.type === 'playlist') {
    icon = (
      <span className="cp-ic tile" style={{ background: hit.playlist.color }}>
        {hit.playlist.icon}
      </span>
    )
    title = <Marked s={hit.title} />
    meta = hit.playlist.smart ? 'Smart playlist' : 'Playlist'
  }
  return (
    <button type="button" className={`sv-row${hit.type === 'highlight' ? ' tall' : ''}`} onClick={onOpen}>
      {icon}
      <span className="cp-txt">
        <span className="cp-title">{title}</span>
        <span className="cp-meta">{meta}</span>
      </span>
    </button>
  )
}
