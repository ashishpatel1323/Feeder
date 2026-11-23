import { useEffect, useMemo, useRef, useState } from 'react'
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
  isFollowed,
  looksLikeUrl,
  PODCAST_COUNTRIES,
  PODCAST_GENRES,
  searchCurated,
  searchFeedly,
  searchPodcasts,
  searchYouTube,
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
}

const TAB_KEY = 'feeder.discover.tab'
const COUNTRY_KEY = 'feeder.discover.country'
const LAST_FOLDER_KEY = 'feeder.discover.lastFolder'

const TABS: [DiscoverKind, string, IconName][] = [
  ['text', 'Text feeds', 'article'],
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

/** Artwork for a result, or a generated tile with its initials. */
function Art({ r }: { r: DiscoverResult }) {
  const [broken, setBroken] = useState(false)
  if (r.image && !broken) return <img className="dc-art" src={r.image} alt="" loading="lazy" referrerPolicy="no-referrer" onError={() => setBroken(true)} />
  const [bg, fg] = tileColors(r.title)
  return (
    <span className="dc-art dc-gen" style={{ background: bg, color: fg }}>
      {initials(r.title)}
    </span>
  )
}

/** The folder picker under a result: every folder as a button, plus a new folder box. */
function FolderPicker({ folders, onPick, onCancel }: { folders: string[]; onPick: (tag: string) => void; onCancel: () => void }) {
  const [name, setName] = useState('')
  const last = load(LAST_FOLDER_KEY, '')
  const ordered = useMemo(() => (last && folders.includes(last) ? [last, ...folders.filter((f) => f !== last)] : folders), [folders, last])
  return (
    <div className="dc-picker" role="group" aria-label="Choose a folder">
      <span className="dc-picker-label">Add to</span>
      {ordered.map((f) => (
        <button key={f} type="button" className={`home-chip${f === last ? ' dc-last' : ''}`} onClick={() => onPick(f)}>
          <Icon name="folder" size={16} />
          {f}
        </button>
      ))}
      <button type="button" className="home-chip" onClick={() => onPick('')}>
        No folder
      </button>
      <form
        className="dc-newfolder"
        onSubmit={(e) => {
          e.preventDefault()
          if (name.trim()) onPick(name.trim())
        }}
      >
        <input value={name} onChange={(e) => setName(e.target.value)} placeholder="New folder" aria-label="New folder name" />
        <button type="submit" className="mf-btn" disabled={!name.trim()}>
          <Icon name="newFolder" size={18} />
          <span>Create and add</span>
        </button>
      </form>
      <button type="button" className="mf-btn" onClick={onCancel}>
        <span>Cancel</span>
      </button>
    </div>
  )
}

function ResultCard({
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
  const [picking, setPicking] = useState(false)
  const [busy, setBusy] = useState(false)
  const pick = async (tag: string) => {
    setPicking(false)
    setBusy(true)
    try {
      await onAdd(r, tag)
    } finally {
      setBusy(false)
    }
  }
  return (
    <li className="dc-card">
      <div className="dc-row">
        <Art r={r} />
        <div className="dc-body">
          <div className="dc-title">{r.title}</div>
          {r.meta ? <div className="dc-meta">{r.meta}</div> : null}
          {r.description ? <div className="dc-desc">{r.description}</div> : null}
          <div className="dc-actions">
            {added ? (
              <>
                <span className="dc-done">
                  <Icon name="check" size={16} />
                  {added.tag ? `Added to ${added.tag}` : 'Added'}
                </span>
                <button type="button" className="mf-btn" onClick={() => onOpen(added.feedUrl)}>
                  <Icon name="open" size={18} />
                  <span>Open feed</span>
                </button>
              </>
            ) : followed ? (
              <span className="dc-done">
                <Icon name="check" size={16} />
                Already following
              </span>
            ) : (
              <button type="button" className="mf-btn primary" disabled={busy} aria-expanded={picking} onClick={() => setPicking((v) => !v)}>
                <Icon name={busy ? 'refresh' : 'newFolder'} size={18} />
                <span>{busy ? 'Adding…' : 'Add to folder'}</span>
              </button>
            )}
            {r.site && /^https?:/.test(r.site) ? (
              <a className="mf-btn" href={r.site} target="_blank" rel="noopener noreferrer">
                <Icon name="link" size={18} />
                <span>Visit</span>
              </a>
            ) : null}
          </div>
        </div>
      </div>
      {picking ? <FolderPicker folders={folders} onPick={(t) => void pick(t)} onCancel={() => setPicking(false)} /> : null}
    </li>
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

  const placeholder = tab === 'text' ? 'Search sites and blogs, or paste an address' : tab === 'podcast' ? 'Search podcasts' : 'Search YouTube channels, or paste a channel link'
  const topicGroups = useMemo(() => {
    const m = new Map<string, typeof CURATED_TOPICS>()
    for (const t of CURATED_TOPICS) m.set(t.group, [...(m.get(t.group) ?? []), t])
    return [...m.entries()]
  }, [])

  let heading = ''
  if (submitted.trim()) heading = `Results for “${submitted.trim()}”`
  else if (tab === 'text') heading = topic
  else if (tab === 'podcast') heading = `Top ${PODCAST_GENRES.find(([g]) => g === genre)?.[1] ?? ''} podcasts in ${PODCAST_COUNTRIES.find(([c]) => c === country)?.[1] ?? country.toUpperCase()}`.replace('Top All', 'Top')

  return (
    <section className="manage-pane home-page discover-page">
      <div className="home-inner">
        <header className="home-head">
          <span className="only-narrow">
            <button type="button" className="mf-ib" aria-label="Feeds and folders" title="Feeds and folders" onClick={p.onMenu}>
              <Icon name="menu" size={22} />
            </button>
          </span>
          <h1>Discover</h1>
          <span className="home-seg dc-tabs" role="tablist" aria-label="What to find">
            {TABS.map(([t, label, icon]) => (
              <button key={t} type="button" role="tab" aria-selected={tab === t} className={tab === t ? 'on' : ''} onClick={() => switchTab(t)}>
                <Icon name={icon} size={16} />
                {label}
              </button>
            ))}
          </span>
        </header>

        <form
          className="dc-search"
          onSubmit={(e) => {
            e.preventDefault()
            setSubmitted(query)
          }}
        >
          <input className="home-search" type="search" value={query} placeholder={placeholder} aria-label={placeholder} onChange={(e) => {
            setQuery(e.target.value)
            if (!e.target.value) setSubmitted('')
          }} />
          <button type="submit" className="mf-btn primary" disabled={!query.trim()}>
            <Icon name="search" size={18} />
            <span>Search</span>
          </button>
        </form>

        {tab === 'text' && !submitted.trim()
          ? topicGroups.map(([group, list]) => (
              <div key={group} className="home-chips" aria-label={`${group} topics`}>
                <span className="home-chips-label">{group}</span>
                {list.map((t) => (
                  <button key={t.name} type="button" className={`home-chip${topic === t.name ? ' on' : ''}`} aria-pressed={topic === t.name} onClick={() => setTopic(t.name)}>
                    {t.name}
                    <b>{t.feeds.length}</b>
                  </button>
                ))}
              </div>
            ))
          : null}
        {tab === 'podcast' ? (
          <>
            <div className="home-chips" aria-label="Country">
              <span className="home-chips-label">Country</span>
              {PODCAST_COUNTRIES.map(([c, label]) => (
                <button key={c} type="button" className={`home-chip${country === c ? ' on' : ''}`} aria-pressed={country === c} onClick={() => setCountry(c)}>
                  {label}
                </button>
              ))}
            </div>
            {!submitted.trim() ? (
              <div className="home-chips" aria-label="Category">
                <span className="home-chips-label">Top charts</span>
                {PODCAST_GENRES.map(([g, label]) => (
                  <button key={g} type="button" className={`home-chip${genre === g ? ' on' : ''}`} aria-pressed={genre === g} onClick={() => setGenre(g)}>
                    {label}
                  </button>
                ))}
              </div>
            ) : null}
          </>
        ) : null}
        {tab === 'youtube' && !submitted.trim() ? (
          <div className="home-chips" aria-label="Ideas">
            <span className="home-chips-label">Try</span>
            {YOUTUBE_IDEAS.map((idea) => (
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
            ))}
          </div>
        ) : null}

        <div className="home-tools">
          <span className="home-count">
            {heading || 'Find YouTube channels'}
            <span>{loading ? 'Searching…' : heading ? `${results.length} result${results.length === 1 ? '' : 's'}` : 'Search by name, or paste a channel link or @handle. Each channel becomes a feed of its new videos.'}</span>
          </span>
        </div>
        {note ? <p className="dc-note">{note}</p> : null}
        <ul className="dc-list">
          {results.map((r) => (
            <ResultCard key={r.feedUrl} r={r} followed={isFollowed(r, followed)} added={added[r.feedUrl]} folders={folders} onAdd={onAdd} onOpen={p.onOpenFeed} />
          ))}
        </ul>
        {tab === 'text' && !submitted.trim() ? <p className="dc-source">Topic lists come from awesome-rss-feeds (CC0) plus Indian business sites. Search uses Feedly.</p> : null}
        {tab === 'podcast' ? <p className="dc-source">Podcast search and charts come from Apple Podcasts.</p> : null}
      </div>
    </section>
  )
}
