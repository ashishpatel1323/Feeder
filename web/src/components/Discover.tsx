import { useEffect, useMemo, useRef, useState, type ReactNode, type Ref } from 'react'
import { useLiveQuery } from 'dexie-react-hooks'
import { EMPTY_FOLDERS_KEY, getKv, type Feed } from '../lib/db'
import { addFeed } from '../lib/refresh'
import { syncNow } from '../lib/sync2'
import { initials, tileColors } from '../lib/home'
import {
  CURATED_TOPICS,
  curatedResults,
  defaultCountry,
  googleNewsResult,
  iconUrl,
  isFollowed,
  looksLikeUrl,
  PODCAST_COUNTRIES,
  PODCAST_GENRES,
  QUICK_TOPICS,
  searchCurated,
  searchFeedly,
  searchPodcasts,
  searchYouTube,
  supportingLine,
  topicLabel,
  topPodcasts,
  type DiscoverKind,
  type DiscoverResult,
} from '../lib/discover'
import { Icon, type IconName } from './Icon'

interface Props {
  feeds: Feed[]
  onOpenFeed: (url: string) => void
  onMenu: () => void
  toast: (m: string) => void
  /** Latest | By feed | Discover: Discover is part of the Feeds tab */
  feedsSwitch?: ReactNode
}

const TAB_KEY = 'feeder.discover.tab'
const COUNTRY_KEY = 'feeder.discover.country'
const LAST_FOLDER_KEY = 'feeder.discover.lastFolder'

const TABS: [DiscoverKind, string, IconName][] = [
  ['text', 'Websites', 'public'],
  ['podcast', 'Podcasts', 'headphones'],
  ['youtube', 'YouTube', 'play'],
]
const YOUTUBE_IDEAS = ['Science explained', 'Tech reviews', 'History', 'Personal finance India', 'Cooking', 'Startups', 'Chess', 'Documentaries']

const load = (k: string, fallback: string) => {
  try {
    return localStorage.getItem(k) ?? fallback
  } catch {
    return fallback
  }
}
const save = (k: string, v: string) => {
  try {
    localStorage.setItem(k, v)
  } catch {
    // private mode
  }
}

type Added = { feedUrl: string; tag: string }

/** A result's square icon: its artwork or site favicon, a letter tile when there is none. */
function Art({ r }: { r: DiscoverResult }) {
  const [broken, setBroken] = useState(false)
  const src = iconUrl(r)
  const big = !!r.image
  if (src && !broken)
    return (
      <span className={`dc-art${big ? ' dc-art-full' : ''}${r.kind === 'podcast' && big ? ' dc-art-pod' : ''}`}>
        <img src={src} alt="" loading="lazy" referrerPolicy="no-referrer" onError={() => setBroken(true)} />
      </span>
    )
  const [bg, fg] = tileColors(r.title)
  return (
    <span className="dc-art dc-gen" style={{ background: bg, color: fg }}>
      {initials(r.title)}
    </span>
  )
}

type MenuItem = { key: string; label: string; icon?: IconName; hint?: string; checked?: boolean; onClick: () => void }

/**
 * A button that opens a Material 3 menu below it, aligned to its right edge (like MoreMenu).
 * `footer` renders extra content at the end of the menu (the new folder box).
 */
function MenuButton({
  button,
  label,
  title,
  items,
  footer,
  align = 'end',
}: {
  align?: 'start' | 'end'
  button: (open: boolean, toggle: () => void, ref: Ref<HTMLButtonElement>) => ReactNode
  label: string
  title?: string
  items: MenuItem[]
  footer?: (close: () => void) => ReactNode
}) {
  const [pos, setPos] = useState<{ top: number; left: number } | null>(null)
  const btn = useRef<HTMLButtonElement>(null)
  const menu = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!pos) return
    menu.current?.querySelector<HTMLButtonElement>('button')?.focus()
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.stopPropagation()
        setPos(null)
        btn.current?.focus()
      }
    }
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  }, [pos])
  const toggle = () => {
    if (pos) return setPos(null)
    const r = btn.current?.getBoundingClientRect()
    if (!r) return
    const width = 260
    setPos({ top: r.bottom + 4, left: Math.max(8, Math.min(align === 'end' ? r.right - width : r.left, window.innerWidth - width - 8)) })
  }
  const close = () => setPos(null)
  return (
    <>
      {button(!!pos, toggle, btn)}
      {pos ? (
        <div className="ctx-backdrop" onPointerDown={(e) => e.target === e.currentTarget && close()} onWheel={close}>
          <div ref={menu} className="ctx-menu dc-menu" role="menu" aria-label={label} style={{ top: pos.top, left: pos.left, maxHeight: `calc(100vh - ${pos.top + 8}px)` }}>
            {title ? <div className="dc-menu-title">{title}</div> : null}
            {items.map((it) =>
              it.key.startsWith('h:') ? (
                <div key={it.key} className="dc-menu-head" role="presentation">
                  {it.label}
                </div>
              ) : (
                <button
                  key={it.key}
                  type="button"
                  role={it.checked === undefined ? 'menuitem' : 'menuitemradio'}
                  aria-checked={it.checked}
                  className="ctx-item"
                  onClick={() => {
                    close()
                    it.onClick()
                  }}
                >
                  {it.icon ? <Icon name={it.icon} size={20} /> : null}
                  <span className="ctx-label">{it.label}</span>
                  {it.hint ? <span className="dc-menu-hint">{it.hint}</span> : null}
                  {it.checked ? <Icon name="check" size={18} /> : null}
                </button>
              ),
            )}
            {footer ? footer(close) : null}
          </div>
        </div>
      ) : null}
    </>
  )
}

/** The end of the folder menu: "New folder…", which turns into a name box. */
function NewFolder({ onPick }: { onPick: (tag: string) => void }) {
  const [editing, setEditing] = useState(false)
  const [name, setName] = useState('')
  if (!editing)
    return (
      <button type="button" role="menuitem" className="ctx-item dc-menu-accent" onClick={() => setEditing(true)}>
        <Icon name="newFolder" size={20} />
        <span className="ctx-label">New folder…</span>
      </button>
    )
  return (
    <form
      className="dc-newfolder"
      onSubmit={(e) => {
        e.preventDefault()
        if (name.trim()) onPick(name.trim())
      }}
    >
      <input autoFocus value={name} onChange={(e) => setName(e.target.value)} placeholder="Folder name" aria-label="New folder name" />
      <button type="submit" className="mf-btn primary" disabled={!name.trim()}>
        <span>Add</span>
      </button>
    </form>
  )
}

/** A compact list row: icon, title, one supporting line, and one trailing action. */
function ResultRow({
  r,
  followed,
  added,
  folders,
  onAdd,
  onOpen,
}: {
  r: DiscoverResult
  followed: boolean
  added: Added | undefined
  folders: string[]
  onAdd: (r: DiscoverResult, tag: string) => Promise<void>
  onOpen: (url: string) => void
}) {
  const [busy, setBusy] = useState(false)
  const pick = async (tag: string) => {
    setBusy(true)
    try {
      await onAdd(r, tag)
    } finally {
      setBusy(false)
    }
  }
  const last = load(LAST_FOLDER_KEY, '')
  const ordered = last && folders.includes(last) ? [last, ...folders.filter((f) => f !== last)] : folders
  const site = r.site && /^https?:/.test(r.site) ? r.site : undefined
  const line = supportingLine(r)
  const status = added ? (added.tag ? `Added to ${added.tag}` : 'Added') : followed ? 'Following' : ''
  return (
    <li className="dc-item">
      <Art r={r} />
      <div className="dc-body">
        {site ? (
          <a className="dc-title" href={site} target="_blank" rel="noopener noreferrer" title={`Visit ${r.title}`}>
            {r.title}
          </a>
        ) : (
          <span className="dc-title">{r.title}</span>
        )}
        {status || line ? <span className="dc-line">{[status, line].filter(Boolean).join(' · ')}</span> : null}
      </div>
      {added ? (
        <button type="button" className="mf-btn dc-done" onClick={() => onOpen(added.feedUrl)} title="Open feed">
          <Icon name="check" size={18} />
          <span>Open</span>
        </button>
      ) : followed ? (
        <span className="mf-btn dc-done" aria-disabled="true">
          <Icon name="check" size={18} />
          <span>Following</span>
        </span>
      ) : (
        <MenuButton
          label={`Add ${r.title} to a folder`}
          title={`Add ${r.title} to`}
          items={[
            ...ordered.map((f) => ({ key: `f:${f}`, label: f, icon: 'folder' as IconName, hint: f === last ? 'Last used' : undefined, onClick: () => void pick(f) })),
            { key: 'none', label: 'No folder', icon: 'remove' as IconName, onClick: () => void pick('') },
          ]}
          footer={(close) => (
            <NewFolder
              onPick={(t) => {
                close()
                void pick(t)
              }}
            />
          )}
          button={(open, toggle, ref) => (
            <button ref={ref} type="button" className="dc-add" aria-label={`Add ${r.title} to a folder`} title="Add to folder" aria-expanded={open} aria-haspopup="menu" disabled={busy} onClick={toggle}>
              <Icon name={busy ? 'refresh' : 'add'} size={20} />
            </button>
          )}
        />
      )}
    </li>
  )
}

/** A selected chip with ▾ that opens a menu of choices. */
function MenuChip<T extends string | number>({ label, icon, options, value, onPick, groups, hints }: { label: string; icon?: IconName; options: [T, string][]; value: T; onPick: (v: T) => void; groups?: Map<T, string>; hints?: Map<T, string> }) {
  const items: MenuItem[] = []
  let group = ''
  for (const [v, text] of options) {
    const g = groups?.get(v)
    if (g !== undefined && g !== group) {
      group = g
      items.push({ key: `h:${g}`, label: g, onClick: () => undefined })
    }
    items.push({ key: String(v), label: text, hint: hints?.get(v), checked: v === value, onClick: () => onPick(v) })
  }
  return (
    <MenuButton
      align="start"
      label={label}
      items={items}
      button={(open, toggle, ref) => (
        <button ref={ref} type="button" className="home-chip on dc-menuchip" aria-haspopup="menu" aria-expanded={open} onClick={toggle}>
          {icon ? <Icon name={icon} size={16} /> : null}
          {label}
          <Icon name="expand" size={18} />
        </button>
      )}
    />
  )
}

/**
 * Discover: find text feeds, podcasts and YouTube channels and add each to a folder.
 * Text feeds start from a bundled list by topic; typing searches Feedly, the bundled list and
 * offers a Google News feed; an address finds the site's own feed.
 */
export function DiscoverPage(p: Props) {
  const [tab, setTab] = useState<DiscoverKind>(() => {
    const t = load(TAB_KEY, 'text')
    return t === 'podcast' || t === 'youtube' ? t : 'text'
  })
  const [query, setQuery] = useState('')
  const [submitted, setSubmitted] = useState('')
  const [topic, setTopic] = useState(CURATED_TOPICS[0]?.name ?? '')
  const [genre, setGenre] = useState(0)
  const [country, setCountry] = useState(() => load(COUNTRY_KEY, defaultCountry()))
  const [results, setResults] = useState<DiscoverResult[]>([])
  const [loading, setLoading] = useState(false)
  const [note, setNote] = useState('')
  const [added, setAdded] = useState<Record<string, Added>>({})
  const run = useRef(0)

  const emptyFolders = useLiveQuery(() => getKv<string[]>(EMPTY_FOLDERS_KEY, []), []) ?? []
  const folders = useMemo(
    () => [...new Set([...p.feeds.map((f) => f.tag).filter(Boolean), ...emptyFolders])].sort((a, b) => a.localeCompare(b, undefined, { sensitivity: 'base' })),
    [p.feeds, emptyFolders],
  )
  const followed = useMemo(() => new Set(p.feeds.map((f) => f.url)), [p.feeds])

  useEffect(() => save(TAB_KEY, tab), [tab])
  useEffect(() => save(COUNTRY_KEY, country), [country])

  // Load whatever the current tab, search and chips ask for
  useEffect(() => {
    const id = ++run.current
    const q = submitted.trim()
    const done = (list: DiscoverResult[], msg = '') => {
      if (id !== run.current) return
      setResults(list)
      setNote(msg)
      setLoading(false)
    }
    const fail = (e: unknown, fallback: DiscoverResult[] = [], what = 'That search') => done(fallback, `${what} did not work (${e instanceof Error ? e.message : String(e)}).`)
    setNote('')
    if (q && looksLikeUrl(q)) {
      const url = /^https?:\/\//i.test(q) ? q : `https://${q}`
      return done([{ kind: tab, title: url.replace(/^https?:\/\/(www\.)?/, ''), feedUrl: url, meta: 'Feeder looks for the feed on this page when you add it', site: url }])
    }
    if (tab === 'youtube' && q.startsWith('@') && !/\s/.test(q)) {
      const url = `https://www.youtube.com/${q}`
      return done([{ kind: 'youtube', title: q, feedUrl: url, meta: 'YouTube channel', site: url }])
    }
    if (tab === 'text' && !q) {
      const t = CURATED_TOPICS.find((x) => x.name === topic)
      return done(t ? curatedResults(t) : [])
    }
    if (tab === 'youtube' && !q) return done([])
    setLoading(true)
    setResults([])
    if (tab === 'text') {
      const local = searchCurated(q)
      searchFeedly(q)
        .then((remote) => {
          const seen = new Set(local.map((r) => r.feedUrl))
          done([...local, ...remote.filter((r) => !seen.has(r.feedUrl)), googleNewsResult(q)], !local.length && !remote.length ? 'No feeds found for that. Try the site’s name (like “livemint”) or paste its address. Google News can follow any topic:' : '')
        })
        .catch((e) => fail(e, [...local, googleNewsResult(q)], 'Feedly search'))
    } else if (tab === 'podcast') {
      ;(q ? searchPodcasts(q, country) : topPodcasts(genre, country)).then((list) => done(list, list.length ? '' : 'No podcasts found.')).catch((e) => fail(e))
    } else {
      searchYouTube(q)
        .then((list) => done(list, list.length ? '' : 'No channels found. Try another name, or paste the channel’s address or @handle.'))
        .catch((e) => fail(e))
    }
  }, [tab, submitted, topic, genre, country])

  const onAdd = async (r: DiscoverResult, tag: string) => {
    try {
      const feedUrl = await addFeed(r.feedUrl, tag)
      save(LAST_FOLDER_KEY, tag)
      setAdded((a) => ({ ...a, [r.feedUrl]: { feedUrl, tag } }))
      syncNow().catch(() => undefined)
      p.toast(tag ? `Added ${r.title} to ${tag}` : `Added ${r.title}`)
    } catch (e) {
      p.toast(`Could not add ${r.title}: ${e instanceof Error ? e.message : String(e)}`)
    }
  }

  const switchTab = (t: DiscoverKind) => {
    setTab(t)
    setQuery('')
    setSubmitted('')
  }

  const placeholder = tab === 'text' ? 'Search sites and blogs, or paste a link' : tab === 'podcast' ? 'Search podcasts' : 'Search channels, a link or @handle'
  const searchRef = useRef<HTMLInputElement>(null)
  // "/" focuses the search box, as on other Feeder pages
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement | null
      if (e.key !== '/' || e.metaKey || e.ctrlKey || e.altKey || (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable))) return
      e.preventDefault()
      searchRef.current?.focus()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  const q = submitted.trim()
  const topicNames = useMemo(() => new Set(CURATED_TOPICS.map((t) => t.name)), [])
  const topicGroups = useMemo(() => new Map(CURATED_TOPICS.map((t) => [t.name, t.group] as [string, string])), [])
  const topicCounts = useMemo(() => new Map(CURATED_TOPICS.map((t) => [t.name, String(t.feeds.length)] as [string, string])), [])
  const currentTopic = CURATED_TOPICS.find((t) => t.name === topic)
  const countryName = PODCAST_COUNTRIES.find(([c]) => c === country)?.[1] ?? country.toUpperCase()

  let heading = ''
  if (q) heading = `Results for “${q}”`
  else if (tab === 'text') heading = `${topicLabel(topic)} · ${currentTopic?.feeds.length ?? 0} feeds`
  else if (tab === 'podcast') heading = `Top ${genre ? `${PODCAST_GENRES.find(([g]) => g === genre)?.[1] ?? ''} ` : ''}podcasts in ${countryName}`
  else heading = 'Find YouTube channels'

  return (
    <section className="manage-pane home-page discover-page">
      <div className="home-inner">
        <header className="home-head dc-head">
          <span className="only-narrow">
            <button type="button" className="mf-ib" aria-label="Feeds and folders" title="Feeds and folders" onClick={p.onMenu}>
              <Icon name="menu" size={22} />
            </button>
          </span>
          <h1>Feeds</h1>
          {p.feedsSwitch}
          <form
            className="dc-search"
            role="search"
            onSubmit={(e) => {
              e.preventDefault()
              setSubmitted(query)
              searchRef.current?.blur()
            }}
          >
            <Icon name="search" size={20} />
            <input
              ref={searchRef}
              type="search"
              value={query}
              placeholder={placeholder}
              aria-label={placeholder}
              onChange={(e) => {
                setQuery(e.target.value)
                if (!e.target.value) setSubmitted('')
              }}
            />
            {query ? (
              <button
                type="button"
                className="dc-clear"
                aria-label="Clear search"
                title="Clear search"
                onClick={() => {
                  setQuery('')
                  setSubmitted('')
                  searchRef.current?.focus()
                }}
              >
                <Icon name="close" size={18} />
              </button>
            ) : (
              <kbd className="btn-kbd">/</kbd>
            )}
          </form>
          <span className="home-seg dc-tabs" role="tablist" aria-label="What to find">
            {TABS.map(([t, label, icon]) => (
              <button key={t} type="button" role="tab" aria-selected={tab === t} className={tab === t ? 'on' : ''} onClick={() => switchTab(t)}>
                <Icon name={icon} size={16} />
                {label}
              </button>
            ))}
          </span>
        </header>

        <div className="dc-chips" aria-label="Filters">
          {tab === 'text' && !q ? (
            <>
              <MenuChip label={topicLabel(topic)} options={CURATED_TOPICS.map((t) => [t.name, topicLabel(t.name)] as [string, string])} groups={topicGroups} hints={topicCounts} value={topic} onPick={setTopic} />
              {QUICK_TOPICS.filter((t) => topicNames.has(t) && t !== topic).map((t) => (
                <button key={t} type="button" className="home-chip" onClick={() => setTopic(t)}>
                  {topicLabel(t)}
                </button>
              ))}
            </>
          ) : null}
          {tab === 'podcast' ? (
            <>
              <MenuChip label={countryName} icon="public" options={PODCAST_COUNTRIES} value={country} onPick={setCountry} />
              {!q ? (
                <MenuChip label={genre ? (PODCAST_GENRES.find(([g]) => g === genre)?.[1] ?? 'Category') : 'All categories'} options={PODCAST_GENRES.map(([g, l]) => [g, g ? l : 'All categories'] as [number, string])} value={genre} onPick={setGenre} />
              ) : null}
            </>
          ) : null}
          {tab === 'youtube' && !q
            ? YOUTUBE_IDEAS.map((idea) => (
                <button
                  key={idea}
                  type="button"
                  className="home-chip"
                  onClick={() => {
                    setQuery(idea)
                    setSubmitted(idea)
                  }}
                >
                  <Icon name="search" size={16} />
                  {idea}
                </button>
              ))
            : null}
        </div>

        <div className="dc-heading">
          <span>{heading}</span>
          {loading ? <span className="dc-loading">Searching…</span> : null}
        </div>
        {tab === 'youtube' && !q ? <p className="dc-note">Search by name, or paste a channel link or @handle. Each channel becomes a feed of its new videos.</p> : null}
        {note ? <p className="dc-note">{note}</p> : null}
        <ul className="dc-list">
          {results.map((r) => (
            <ResultRow key={r.feedUrl} r={r} followed={isFollowed(r, followed)} added={added[r.feedUrl]} folders={folders} onAdd={onAdd} onOpen={p.onOpenFeed} />
          ))}
        </ul>
        {tab === 'text' && !q ? <p className="dc-source">Topic lists come from awesome-rss-feeds (CC0) plus Indian business sites. Search uses Feedly.</p> : null}
        {tab === 'podcast' ? <p className="dc-source">Podcast search and charts come from Apple Podcasts.</p> : null}
      </div>
    </section>
  )
}
