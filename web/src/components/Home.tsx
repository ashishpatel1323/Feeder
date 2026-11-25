import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { useLiveQuery } from 'dexie-react-hooks'
import { addEmptyFolder, db, FEED_ORDER_KEY, FOLDER_ORDER_KEY, getKv, renameTag, type Feed, type Item } from '../lib/db'
import {
  buildHomeFeeds,
  FEED_KINDS,
  FEED_KINDS_KEY,
  filterHome,
  groupByFolder,
  initials,
  KIND_LABELS,
  LANE_MAX,
  LIST_FOLDER_MAX,
  NO_FILTER,
  shortAge,
  sortHome,
  tileColors,
  type FeedKind,
  type HomeFeed,
  type HomeFilter,
  type HomeFolder,
  type HomeGroup,
  type HomeLayout,
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
  /** Global search (the ⌘K palette). */
  onSearch: () => void
  /** Latest | By feed */
  feedsSwitch: ReactNode
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
const LAYOUTS: [HomeLayout, string][] = [
  ['tiles', 'Tiles'],
  ['list', 'List'],
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

const plural = (n: number, w: string) => `${n} ${w}${n === 1 ? '' : 's'}`

function feedLabel(h: HomeFeed): string {
  return `${h.title}, ${KIND_LABELS[h.kind]}${h.unread ? `, ${h.unread} unread` : ''}${h.hasError ? ', last check failed' : ''}`
}

/** Artwork with an unread badge and type mark, then the feed's name and "N new" under it. */
function NamedTile({ h, onOpen }: { h: HomeFeed; onOpen: (url: string) => void }) {
  const label = feedLabel(h)
  return (
    <button type="button" className="home-ntile" title={label} aria-label={label} onClick={() => onOpen(h.feed.url)}>
      <span className="home-tile">
        <Art h={h} mini />
        {h.kind !== 'text' ? (
          <span className={`home-kind k-${h.kind}`}>
            <Icon name={KIND_ICON[h.kind]} size={14} />
          </span>
        ) : null}
        {h.hasError ? <span className="home-err" title={`Last check failed: ${h.feed.lastError}`} /> : null}
      </span>
      {h.unread ? <span className="home-badge">{h.unread > 999 ? '999+' : h.unread}</span> : null}
      <span className="home-ntile-t">{h.title}</span>
      <span className="home-ntile-m">{h.unread ? `${h.unread} new` : 'Up to date'}</span>
    </button>
  )
}

/** One feed as a list row: artwork, name, then unread · total · saved · in progress, and when it last posted. */
function FeedRow({ h, onOpen }: { h: HomeFeed; onOpen: (url: string) => void }) {
  const rest = [`${h.total} total`, h.saved ? `${h.saved} saved` : '', h.inProgress ? `${h.inProgress} in progress` : ''].filter(Boolean).join(' · ')
  const age = shortAge(h.latest)
  return (
    <button type="button" className="home-row" aria-label={`${feedLabel(h)}, ${rest}${age ? `, last post ${age} ago` : ''}`} onClick={() => onOpen(h.feed.url)}>
      <span className="home-row-art">
        <Art h={h} mini />
        {h.kind !== 'text' ? (
          <span className={`home-kind k-${h.kind}`}>
            <Icon name={KIND_ICON[h.kind]} size={12} />
          </span>
        ) : null}
      </span>
      <span className="home-row-tx">
        <span className="home-row-t">
          {h.title}
          {h.hasError ? <span className="home-row-err"> · last check failed</span> : null}
        </span>
        <span className="home-row-m">
          <span className={h.unread ? 'u' : ''}>{h.unread ? `${h.unread} unread` : 'All read'}</span> · {rest}
        </span>
      </span>
      {age ? <span className="home-row-age">{age}</span> : null}
    </button>
  )
}

/** A folder's title line: name, feed count and unread. Opens the folder (tiles) or folds it (list). */
function FolderHead({ f, foldable, folded, onClick }: { f: HomeFolder; foldable: boolean; folded: boolean; onClick: () => void }) {
  const name = f.name || 'No folder'
  return (
    <h2 className="home-lh">
      <button
        type="button"
        aria-expanded={foldable ? !folded : undefined}
        title={foldable ? (folded ? `Unfold ${name}` : `Fold ${name}`) : `Open ${name}`}
        onClick={onClick}
      >
        {foldable ? <Icon name={folded ? 'next' : 'expand'} size={20} /> : null}
        <span className="home-lh-n">
          {name}
          <span> · {plural(f.feeds.length, 'feed')}</span>
        </span>
        {f.unread ? <span className="home-lh-u">{f.unread} unread</span> : null}
        {foldable ? null : <Icon name="next" size={20} />}
      </button>
    </h2>
  )
}

/** A folder as a sideways row of named tiles, ending in "All N" when it has more than LANE_MAX. */
function Lane({ f, onOpen, onAll }: { f: HomeFolder; onOpen: (url: string) => void; onAll: () => void }) {
  return (
    <div className="home-lane">
      {f.feeds.slice(0, LANE_MAX).map((h) => (
        <NamedTile key={h.feed.url} h={h} onOpen={onOpen} />
      ))}
      {f.feeds.length > LANE_MAX ? (
        <button type="button" className="home-all" onClick={onAll}>
          <Icon name="next" size={20} />
          All {f.feeds.length}
        </button>
      ) : null}
    </div>
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
  const positionRows = useLiveQuery(() => db.videoPos.toArray(), [])
  const positions = useMemo(() => new Map((positionRows ?? []).map((r) => [r.id, r])), [positionRows])
  const { homeGroup: group, homeSort: sort, homeSize: size, homeLayout: layout = 'tiles', homeFolded: folded = [] } = p.settings
  const list = layout === 'list'

  const all = useMemo(() => buildHomeFeeds(p.feeds, p.items, overrides, positions), [p.feeds, p.items, overrides, positions])
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

  const markFolderRead = async (tag: string) => {
    const urls = new Set(p.feeds.filter((f) => f.tag === tag).map((f) => f.url))
    const ids = p.items.filter((i) => !i.read && urls.has(i.feedUrl)).map((i) => i.id)
    if (!ids.length) return p.toast(`Nothing unread in ${tag || 'No folder'}`)
    await db.items.bulkUpdate(ids.map((key) => ({ key, changes: { read: 1 as const, readTime: Date.now(), readSynced: 0 as const } })))
    syncNow().catch(() => undefined)
    p.toast(`Marked ${ids.length} as read`)
  }
  const toggleFold = (name: string) => setSetting({ homeFolded: folded.includes(name) ? folded.filter((n) => n !== name) : [...folded, name] })
  const folderTags = useMemo(() => [...new Set(all.map((h) => h.feed.tag))], [all])
  const allFolded = folderTags.length > 0 && folderTags.every((t) => folded.includes(t))

  const summary = (() => {
    const n = shown.length
    const feedsWord = `${n} feed${n === 1 ? '' : 's'}`
    if (folderView) return `${feedsWord} · ${folderView.folders.length} folder${folderView.folders.length === 1 ? '' : 's'} · ${folderView.loose.length} not in a folder`
    const unread = shown.filter((h) => h.unread).length
    return `${feedsWord} · ${unread} with unread`
  })()

  const feedsView = (hs: HomeFeed[]) =>
    list ? (
      <div className="home-rows">
        {hs.map((h) => (
          <FeedRow key={h.feed.url} h={h} onOpen={p.onOpenFeed} />
        ))}
      </div>
    ) : (
      <div className="home-grid home-ngrid">
        {hs.map((h) => (
          <NamedTile key={h.feed.url} h={h} onOpen={p.onOpenFeed} />
        ))}
      </div>
    )
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
    const sections: HomeFolder[] = [...folderView.folders]
    if (folderView.loose.length) {
      const l = folderView.loose
      sections.push({ name: '', feeds: l, unread: l.reduce((n, h) => n + h.unread, 0), latest: Math.max(0, ...l.map((h) => h.latest)) })
    }
    body = sections.map((f) => {
      if (!list) {
        return (
          <section key={f.name} className="home-sec">
            <FolderHead f={f} foldable={false} folded={false} onClick={() => setOpenFolder(f.name)} />
            <Lane f={f} onOpen={p.onOpenFeed} onAll={() => setOpenFolder(f.name)} />
          </section>
        )
      }
      const isFolded = folded.includes(f.name)
      return (
        <section key={f.name} className="home-sec">
          <FolderHead f={f} foldable folded={isFolded} onClick={() => toggleFold(f.name)} />
          {isFolded ? null : (
            <div className="home-rows">
              {f.feeds.slice(0, LIST_FOLDER_MAX).map((h) => (
                <FeedRow key={h.feed.url} h={h} onOpen={p.onOpenFeed} />
              ))}
              {f.feeds.length > LIST_FOLDER_MAX ? (
                <button type="button" className="home-showall" onClick={() => setOpenFolder(f.name)}>
                  Show all {f.feeds.length}
                </button>
              ) : null}
            </div>
          )}
        </section>
      )
    })
  } else if (group === 'type' && openFolder === null) {
    body = FEED_KINDS.map((k) => {
      const ofKind = sorted.filter((h) => h.kind === k)
      if (!ofKind.length) return null
      return (
        <section key={k} className="home-shelf">
          <h2>
            <Icon name={KIND_ICON[k]} size={18} />
            {KIND_LABELS[k]}
            <span>{ofKind.length}</span>
          </h2>
          {feedsView(ofKind)}
        </section>
      )
    })
  } else {
    body = feedsView(sorted)
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
              <h1>{openFolder || 'No folder'}</h1>
            </>
          ) : (
            <>
              <h1>Feeds</h1>
              {p.feedsSwitch}
            </>
          )}
          <input
            className="home-search"
            type="search"
            placeholder={openFolder !== null ? `Search ${openFolder || 'No folder'}…` : 'Search feeds…'}
            aria-label="Search feeds"
            value={filter.query}
            onChange={(e) => set({ query: e.target.value })}
          />
          {openFolder !== null ? (
            <>
              <button type="button" className="mf-btn" onClick={() => void markFolderRead(openFolder)}>
                <Icon name="doneAll" size={18} />
                <span>Mark all read</span>
              </button>
              <button type="button" className="mf-btn" onClick={() => p.onShowFolder(openFolder)}>
                <Icon name="article" size={18} />
                <span>Show articles</span>
              </button>
              {openFolder ? (
                <button type="button" className="mf-btn" onClick={() => void renameFolder(openFolder)}>
                  <Icon name="edit" size={18} />
                  <span>Rename</span>
                </button>
              ) : null}
            </>
          ) : null}
          <button type="button" className="mf-btn" onClick={p.onRefresh} disabled={!!p.refreshing}>
            <Icon name="refresh" size={18} />
            <span>{p.refreshing ? `Refreshing ${p.refreshing}` : 'Refresh all'}</span>
          </button>
          <button type="button" className="mf-btn" onClick={p.onSearch} title="Search everything (⌘K or /)">
            <Icon name="search" size={18} />
            <span>Search</span>
          </button>
          <button type="button" className="mf-btn" onClick={p.onArrange} title="Manage feeds: folders, order and settings">
            <Icon name="tune" size={18} />
            <span>Manage</span>
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
        <div className="home-tools">
          <span className="home-count">
            {openFolder !== null ? `In ${openFolder}` : filter.folder !== null ? filter.folder || 'No folder' : filter.kind ? KIND_LABELS[filter.kind] : 'All feeds'}
            <span>{summary}</span>
          </span>
          {openFolder === null ? <Segmented label="Group" value={group} options={GROUPS} onChange={(v) => setSetting({ homeGroup: v })} /> : null}
          <Segmented label="Sort" value={sort} options={SORTS} onChange={(v) => setSetting({ homeSort: v })} />
          <Segmented label="View" value={layout} options={LAYOUTS} onChange={(v) => setSetting({ homeLayout: v })} />
          {!list && !asFolders ? <Segmented label="Size" value={size} options={SIZES} onChange={(v) => setSetting({ homeSize: v })} /> : null}
          {list && asFolders ? (
            <button type="button" className="mf-btn" onClick={() => setSetting({ homeFolded: allFolded ? [] : folderTags })}>
              <Icon name={allFolded ? 'unfoldMore' : 'unfoldLess'} size={18} />
              <span>{allFolded ? 'Unfold all' : 'Fold all'}</span>
            </button>
          ) : null}
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
