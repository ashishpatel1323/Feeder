import { useEffect, useMemo, useState } from 'react'
import { useLiveQuery } from 'dexie-react-hooks'
import { addEmptyFolder, FEED_ORDER_KEY, FOLDER_ORDER_KEY, getKv, renameTag, type Feed, type Item } from '../lib/db'
import {
  buildHomeFeeds,
  FEED_KINDS,
  FEED_KINDS_KEY,
  filterHome,
  groupByFolder,
  initials,
  KIND_LABELS,
  NO_FILTER,
  sortHome,
  tileColors,
  type FeedKind,
  type HomeFeed,
  type HomeFilter,
  type HomeFolder,
  type HomeGroup,
  type HomeSize,
  type HomeSort,
} from '../lib/home'
import { updateSettings, type Settings } from '../lib/settings'
import { syncNow } from '../lib/sync2'
import { Icon, type IconName } from './Icon'

interface Props {
  feeds: Feed[]
  items: Item[]
  settings: Settings
  onOpenFeed: (url: string) => void
  /** Shows a folder's articles in the article list. */
  onShowFolder: (tag: string) => void
  onAddFeed: () => void
  onRefresh: () => void
  refreshing: string | null
  /** Manage feeds, where feeds and folders can be dragged into order. */
  onArrange: () => void
  /** Discover: find new feeds, podcasts and YouTube channels. */
  onDiscover: () => void
  onMenu: () => void
  toast: (m: string) => void
}

const KIND_ICON: Record<FeedKind, IconName> = { podcast: 'headphones', youtube: 'play', text: 'article' }
const GROUPS: [HomeGroup, string][] = [
  ['folders', 'Folders'],
  ['none', 'None'],
  ['type', 'Type'],
]
const SORTS: [HomeSort, string][] = [
  ['latest', 'Latest'],
  ['az', 'A–Z'],
  ['unread', 'Most unread'],
  ['order', 'My order'],
]
const SIZES: [HomeSize, string][] = [
  ['s', 'S'],
  ['m', 'M'],
  ['l', 'L'],
]

function Segmented<T extends string>({ label, value, options, onChange }: { label: string; value: T; options: [T, string][]; onChange: (v: T) => void }) {
  return (
    <span className="home-seg-wrap">
      <span className="home-seg-label">{label}</span>
      <span className="home-seg" role="radiogroup" aria-label={label}>
        {options.map(([v, l]) => (
          <button key={v} type="button" role="radio" aria-checked={value === v} className={value === v ? 'on' : ''} onClick={() => onChange(v)}>
            {l}
          </button>
        ))}
      </span>
    </span>
  )
}

/** Feed artwork, or a generated tile with the feed's initials when it has none (or it fails to load). */
function Art({ h, mini = false }: { h: HomeFeed; mini?: boolean }) {
  const [broken, setBroken] = useState(false)
  // Site favicons are tiny and look smudged on a tile
  const src = h.feed.imageUrl && !/\.ico$/i.test(h.feed.imageUrl.split('?')[0]) ? h.feed.imageUrl : null
  if (src && !broken) return <img className="home-art" src={src} alt="" loading="lazy" referrerPolicy="no-referrer" onError={() => setBroken(true)} />
  const [bg, fg] = tileColors(h.title)
  return (
    <span className="home-art home-gen" style={{ background: bg, color: fg }}>
      <span className="home-gen-i">{initials(h.title)}</span>
      {mini ? null : <span className="home-gen-n">{h.title}</span>}
    </span>
  )
}

function FeedTile({ h, onOpen }: { h: HomeFeed; onOpen: (url: string) => void }) {
  const label = `${h.title}, ${KIND_LABELS[h.kind]}${h.unread ? `, ${h.unread} unread` : ''}${h.hasError ? ', last check failed' : ''}`
  return (
    <button type="button" className="home-tile" title={label} aria-label={label} onClick={() => onOpen(h.feed.url)}>
      <Art h={h} />
      {h.unread ? <span className="home-badge">{h.unread > 999 ? '999+' : h.unread}</span> : null}
      <span className={`home-kind k-${h.kind}`}>
        <Icon name={KIND_ICON[h.kind]} size={14} />
      </span>
      {h.hasError ? <span className="home-err" title={`Last check failed: ${h.feed.lastError}`} /> : null}
    </button>
  )
}

function FolderTile({ f, onOpen }: { f: HomeFolder; onOpen: (name: string) => void }) {
  const [bg] = tileColors(f.name)
  const label = `Folder ${f.name}, ${f.feeds.length} feed${f.feeds.length === 1 ? '' : 's'}${f.unread ? `, ${f.unread} unread` : ''}`
  return (
    <button type="button" className="home-tile home-folder" style={{ background: bg }} title={label} aria-label={label} onClick={() => onOpen(f.name)}>
      <span className="home-fgrid">
        {f.feeds.slice(0, 4).map((h) => (
          <span key={h.feed.url} className="home-mini">
            <Art h={h} mini />
          </span>
        ))}
      </span>
      <span className="home-fname">
        <span className="home-fname-t">{f.name}</span>
        <span className="home-fname-c">{f.feeds.length}</span>
      </span>
      {f.unread ? <span className="home-badge">{f.unread > 999 ? '999+' : f.unread}</span> : null}
    </button>
  )
}

/**
 * The Home page: every feed as a tile (artwork or a generated tile), with type and folder filters
 * that show how many feeds each would leave, and folders as tiles of their own.
 */
export function HomePage(p: Props) {
  const overrides = useLiveQuery(() => getKv<Record<string, FeedKind>>(FEED_KINDS_KEY, {}), []) ?? {}
  const feedOrder = useLiveQuery(() => getKv<string[]>(FEED_ORDER_KEY, []), []) ?? []
  const folderOrder = useLiveQuery(() => getKv<string[]>(FOLDER_ORDER_KEY, []), []) ?? []
  const [filter, setFilter] = useState<HomeFilter>(NO_FILTER)
  const [openFolder, setOpenFolder] = useState<string | null>(null)
  const { homeGroup: group, homeSort: sort, homeSize: size } = p.settings

  const all = useMemo(() => buildHomeFeeds(p.feeds, p.items, overrides), [p.feeds, p.items, overrides])
  // Inside a folder, every count and filter is about that folder's feeds
  const scope = useMemo(() => (openFolder === null ? all : all.filter((h) => h.feed.tag === openFolder)), [all, openFolder])
  const active: HomeFilter = openFolder === null ? filter : { ...filter, folder: null }
  const shown = useMemo(() => filterHome(scope, active), [scope, active])

  const kindCounts = useMemo(() => {
    const base = filterHome(scope, active, ['kind'])
    return { all: base.length, ...Object.fromEntries(FEED_KINDS.map((k) => [k, base.filter((h) => h.kind === k).length])) } as Record<FeedKind | 'all', number>
  }, [scope, active])
  const unreadCount = useMemo(() => filterHome(scope, active, ['unreadOnly']).filter((h) => h.unread > 0).length, [scope, active])
  const errorCount = useMemo(() => filterHome(scope, active, ['errorsOnly']).filter((h) => h.hasError).length, [scope, active])
  const folderCounts = useMemo(() => {
    const base = filterHome(all, filter, ['folder'])
    const m = new Map<string, number>()
    for (const h of base) m.set(h.feed.tag, (m.get(h.feed.tag) ?? 0) + 1)
    // Folders with no matching feeds still get a chip, so the row doesn't jump around
    for (const h of all) if (!m.has(h.feed.tag)) m.set(h.feed.tag, 0)
    return m
  }, [all, filter])
  const folderNames = useMemo(() => {
    const names = [...folderCounts.keys()].filter(Boolean)
    return sort === 'order' ? groupByFolder(all, 'order', feedOrder, folderOrder).folders.map((f) => f.name).filter((n) => names.includes(n)) : names.sort((a, b) => a.localeCompare(b, undefined, { sensitivity: 'base' }))
  }, [folderCounts, sort, all, feedOrder, folderOrder])

  // Esc leaves an open folder
  useEffect(() => {
    if (openFolder === null) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape' || document.querySelector('[aria-modal="true"]')) return
      setOpenFolder(null)
      e.preventDefault()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [openFolder])

  const set = (patch: Partial<HomeFilter>) => setFilter((f) => ({ ...f, ...patch }))
  const setSetting = (patch: Partial<Settings>) => void updateSettings(patch)

  // Folder tiles only when grouping by folder, at the top level, with no folder chip picked
  const asFolders = group === 'folders' && openFolder === null && filter.folder === null
  const folderView = useMemo(() => (asFolders ? groupByFolder(shown, sort, feedOrder, folderOrder) : null), [asFolders, shown, sort, feedOrder, folderOrder])
  const sorted = useMemo(() => sortHome(shown, sort, feedOrder), [shown, sort, feedOrder])

  const newFolder = async () => {
    const name = prompt('New folder name')?.trim()
    if (!name) return
    if (await addEmptyFolder(name)) p.toast(`Folder ${name} added. Move feeds into it in Manage feeds or a feed's page.`)
    else p.toast(`There is already a folder called ${name}`)
  }
  const renameFolder = async (from: string) => {
    const to = prompt(`Rename folder ${from} to`, from)?.trim()
    if (!to || to === from) return
    await renameTag(from, to)
    syncNow().catch(() => undefined)
    setOpenFolder(to)
    p.toast(`Renamed to ${to}`)
  }

  const summary = (() => {
    const n = shown.length
    const feedsWord = `${n} feed${n === 1 ? '' : 's'}`
    if (folderView) return `${feedsWord} · ${folderView.folders.length} folder${folderView.folders.length === 1 ? '' : 's'} · ${folderView.loose.length} not in a folder`
    const unread = shown.filter((h) => h.unread).length
    return `${feedsWord} · ${unread} with unread`
  })()

  const grid = (children: React.ReactNode) => <div className="home-grid">{children}</div>
  let body: React.ReactNode
  if (!p.feeds.length) {
    body = (
      <div className="home-empty">
        <p>No feeds yet. Discover podcasts, YouTube channels and sites, add one by its address, or import OPML in Settings.</p>
        <button type="button" className="mf-btn primary" onClick={p.onDiscover}>
          <Icon name="explore" size={18} />
          <span>Discover</span>
        </button>
        <button type="button" className="mf-btn" onClick={p.onAddFeed}>
          <Icon name="add" size={18} />
          <span>Add feed</span>
        </button>
      </div>
    )
  } else if (!shown.length) {
    body = (
      <div className="home-empty">
        <p>No feeds match these filters.</p>
        <button type="button" className="mf-btn" onClick={() => setFilter({ ...NO_FILTER })}>
          <Icon name="close" size={18} />
          <span>Clear filters</span>
        </button>
      </div>
    )
  } else if (folderView) {
    body = grid(
      <>
        {folderView.folders.map((f) => (
          <FolderTile key={`f:${f.name}`} f={f} onOpen={setOpenFolder} />
        ))}
        {folderView.loose.map((h) => (
          <FeedTile key={h.feed.url} h={h} onOpen={p.onOpenFeed} />
        ))}
      </>,
    )
  } else if (group === 'type') {
    body = FEED_KINDS.map((k) => {
      const list = sorted.filter((h) => h.kind === k)
      if (!list.length) return null
      return (
        <section key={k} className="home-shelf">
          <h2>
            <Icon name={KIND_ICON[k]} size={18} />
            {KIND_LABELS[k]}
            <span>{list.length}</span>
          </h2>
          {grid(list.map((h) => <FeedTile key={h.feed.url} h={h} onOpen={p.onOpenFeed} />))}
        </section>
      )
    })
  } else {
    body = grid(sorted.map((h) => <FeedTile key={h.feed.url} h={h} onOpen={p.onOpenFeed} />))
  }

  const chip = (on: boolean, label: React.ReactNode, count: number, onClick: () => void, icon?: IconName, key?: string) => (
    <button key={key} type="button" className={`home-chip${on ? ' on' : ''}`} aria-pressed={on} onClick={onClick}>
      {icon ? <Icon name={icon} size={16} /> : null}
      {label}
      <b>{count}</b>
    </button>
  )

  return (
    <section className={`manage-pane home-page size-${size}`}>
      <div className="home-inner">
        <header className="home-head">
          <span className="only-narrow">
            <button type="button" className="mf-ib" aria-label="Feeds and folders" title="Feeds and folders" onClick={p.onMenu}>
              <Icon name="menu" size={22} />
            </button>
          </span>
          {openFolder !== null ? (
            <>
              <button type="button" className="mf-ib" title="Back to Home (Esc)" aria-label="Back to Home" onClick={() => setOpenFolder(null)}>
                <Icon name="back" size={22} />
              </button>
              <Icon name="folder" size={24} />
              <h1>{openFolder}</h1>
            </>
          ) : (
            <h1>Home</h1>
          )}
          <input
            className="home-search"
            type="search"
            placeholder={openFolder !== null ? `Search ${openFolder}…` : 'Search feeds…'}
            aria-label="Search feeds"
            value={filter.query}
            onChange={(e) => set({ query: e.target.value })}
          />
          {openFolder !== null ? (
            <>
              <button type="button" className="mf-btn" onClick={() => p.onShowFolder(openFolder)}>
                <Icon name="article" size={18} />
                <span>Show articles</span>
              </button>
              <button type="button" className="mf-btn" onClick={() => void renameFolder(openFolder)}>
                <Icon name="edit" size={18} />
                <span>Rename</span>
              </button>
            </>
          ) : null}
          <button type="button" className="mf-btn" onClick={p.onRefresh} disabled={!!p.refreshing}>
            <Icon name="refresh" size={18} />
            <span>{p.refreshing ? `Refreshing ${p.refreshing}` : 'Refresh all'}</span>
          </button>
          <button type="button" className="mf-btn" onClick={p.onDiscover}>
            <Icon name="explore" size={18} />
            <span>Discover</span>
          </button>
          <button type="button" className="mf-btn primary" onClick={p.onAddFeed}>
            <Icon name="add" size={18} />
            <span>Add feed</span>
          </button>
        </header>

        <div className="home-chips" aria-label="Filter by type">
          <span className="home-chips-label">Type</span>
          {chip(!filter.kind, 'All', kindCounts.all, () => set({ kind: null }))}
          {FEED_KINDS.map((k) => chip(filter.kind === k, KIND_LABELS[k], kindCounts[k], () => set({ kind: filter.kind === k ? null : k }), KIND_ICON[k], k))}
          {chip(filter.unreadOnly, 'Has unread', unreadCount, () => set({ unreadOnly: !filter.unreadOnly }), 'unread')}
          {errorCount || filter.errorsOnly ? chip(filter.errorsOnly, 'Errors', errorCount, () => set({ errorsOnly: !filter.errorsOnly }), 'close') : null}
        </div>
        {openFolder === null && folderNames.length ? (
          <div className="home-chips" aria-label="Filter by folder">
            <span className="home-chips-label">Folders</span>
            {folderNames.map((n) => chip(filter.folder === n, n, folderCounts.get(n) ?? 0, () => set({ folder: filter.folder === n ? null : n }), 'folder', n))}
            {folderCounts.has('') ? chip(filter.folder === '', 'No folder', folderCounts.get('') ?? 0, () => set({ folder: filter.folder === '' ? null : '' }), undefined, '') : null}
          </div>
        ) : null}

        <div className="home-tools">
          <span className="home-count">
            {openFolder !== null ? `In ${openFolder}` : filter.folder !== null ? filter.folder || 'No folder' : filter.kind ? KIND_LABELS[filter.kind] : 'All feeds'}
            <span>{summary}</span>
          </span>
          {openFolder === null ? <Segmented label="Group" value={group} options={GROUPS} onChange={(v) => setSetting({ homeGroup: v })} /> : null}
          <Segmented label="Sort" value={sort} options={SORTS} onChange={(v) => setSetting({ homeSort: v })} />
          <Segmented label="Size" value={size} options={SIZES} onChange={(v) => setSetting({ homeSize: v })} />
          {openFolder === null ? (
            <button type="button" className="mf-btn" onClick={() => void newFolder()}>
              <Icon name="newFolder" size={18} />
              <span>New folder</span>
            </button>
          ) : null}
          <button type="button" className="mf-btn" title="Drag feeds and folders into your own order" onClick={p.onArrange}>
            <Icon name="dragHandle" size={18} />
            <span>Arrange</span>
          </button>
        </div>

        {body}
      </div>
    </section>
  )
}
