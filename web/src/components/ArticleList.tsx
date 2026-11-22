import { useEffect, useRef, useState, type MouseEvent } from 'react'
import { useLiveQuery } from 'dexie-react-hooks'
import { db, feedDisplayTitle, type Feed, type Item } from '../lib/db'
import { readingMinutes, wordsOf, type Group } from '../lib/listquery'
import { updateSettings, type Grouping, type Settings, type ShowFilter, type Sorting } from '../lib/settings'
import { proxiedUrl } from '../lib/api'
import { useNetwork } from '../lib/offline'
import { isWatched, positionLabel } from '../lib/videopos'
import { Icon, IconButton } from './Icon'

interface Props {
  title: string
  groups: Group[]
  /** Unread and saved counts for the Show tabs. */
  counts: { unread: number; saved: number; downloaded: number }
  feeds: Map<string, Feed>
  settings: Settings
  selectedId: string | null
  onOpen: (item: Item) => void
  onMenu: () => void
  onMarkAllRead: () => void
  onDeleteRead: () => void
  /** From the right-click menu and the selection bar: one article or all selected ones. */
  onSetRead: (items: Item[], read: boolean) => void
  onDelete: (items: Item[]) => void
  /** Opens Flip mode on this list. */
  onFlip: () => void
}

const SHOWS: [ShowFilter, string][] = [
  ['unread', 'Unread'],
  ['all', 'All'],
  ['saved', 'Saved'],
  ['downloaded', 'Downloaded'],
]
const SORTINGS: [Sorting, string][] = [
  ['newest', 'Newest'],
  ['oldest', 'Oldest'],
  ['shortest', 'Shortest'],
  ['longest', 'Longest'],
]
const GROUPINGS: [Grouping, string][] = [
  ['none', 'None'],
  ['date', 'Date'],
  ['feed', 'Feed'],
  ['tag', 'Tag'],
  ['site', 'Site'],
  ['length', 'Length'],
]
const labelOf = <T extends string>(list: [T, string][], v: T) => list.find(([k]) => k === v)?.[1] ?? v

function ChoiceChips<T extends string>(p: { label: string; options: [T, string][]; value: T; onChange: (v: T) => void }) {
  return (
    <div className="sheet-section">
      <h3 className="sheet-label">{p.label}</h3>
      <div className="sheet-chips" role="radiogroup" aria-label={p.label}>
        {p.options.map(([v, l]) => (
          <button key={v} role="radio" aria-checked={p.value === v} className={`chip${p.value === v ? ' on' : ''}`} onClick={() => p.onChange(v)}>
            {p.value === v ? <Icon name="check" size={16} /> : null}
            {l}
          </button>
        ))}
      </div>
    </div>
  )
}

function SortGroupSheet({ s, onClose }: { s: Settings; onClose: () => void }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])
  return (
    <div className="sheet-backdrop" onClick={onClose}>
      <div className="sheet" role="dialog" aria-modal="true" aria-label="Sort and group" onClick={(e) => e.stopPropagation()}>
        <div className="sheet-handle" aria-hidden="true" />
        <ChoiceChips label="Sort" options={SORTINGS} value={s.sorting} onChange={(v) => updateSettings({ sorting: v })} />
        <ChoiceChips label="Group by" options={GROUPINGS} value={s.grouping} onChange={(v) => updateSettings({ grouping: v })} />
      </div>
    </div>
  )
}

function relativeTime(ms: number): string {
  const diff = Date.now() - ms
  const min = Math.round(diff / 60000)
  if (min < 1) return 'now'
  if (min < 60) return `${min}m`
  const h = Math.round(min / 60)
  if (h < 24) return `${h}h`
  const d = Math.round(h / 24)
  if (d < 7) return `${d}d`
  return new Date(ms).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
}

function Thumb({ src }: { src: string }) {
  return (
    <img
      className="thumb"
      src={src.startsWith('http://') ? proxiedUrl(src) : src}
      alt=""
      loading="lazy"
      referrerPolicy="no-referrer"
      onError={(e) => ((e.target as HTMLImageElement).style.display = 'none')}
    />
  )
}

/** Right-click menu on an article, or on all selected articles: Mark read or unread, and Delete. */
function RowMenu(p: { items: Item[]; x: number; y: number; onClose: () => void; onSetRead: (read: boolean) => void; onDelete: () => void }) {
  const ref = useRef<HTMLDivElement>(null)
  const [pos, setPos] = useState({ left: p.x, top: p.y })
  useEffect(() => {
    // Keep the menu on screen near the window's edges
    const el = ref.current
    if (el) {
      const r = el.getBoundingClientRect()
      setPos({ left: Math.max(8, Math.min(p.x, window.innerWidth - r.width - 8)), top: Math.max(8, Math.min(p.y, window.innerHeight - r.height - 8)) })
      el.querySelector<HTMLButtonElement>('button')?.focus()
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.stopPropagation()
        p.onClose()
      }
    }
    const close = () => p.onClose()
    window.addEventListener('keydown', onKey, true)
    window.addEventListener('resize', close)
    window.addEventListener('blur', close)
    return () => {
      window.removeEventListener('keydown', onKey, true)
      window.removeEventListener('resize', close)
      window.removeEventListener('blur', close)
    }
  }, [p.x, p.y]) // eslint-disable-line react-hooks/exhaustive-deps
  const act = (f: () => void) => () => {
    p.onClose()
    f()
  }
  const n = p.items.length
  const some = n > 1 ? ` ${n}` : ''
  const anyUnread = p.items.some((i) => !i.read)
  const anyRead = p.items.some((i) => i.read)
  return (
    <div
      className="ctx-backdrop"
      onPointerDown={(e) => e.target === e.currentTarget && p.onClose()}
      onContextMenu={(e) => {
        e.preventDefault()
        p.onClose()
      }}
      onWheel={p.onClose}
    >
      <div ref={ref} className="ctx-menu" role="menu" aria-label={n > 1 ? `${n} articles` : p.items[0]?.title || 'Article'} style={pos}>
        {n > 1 ? <div className="ctx-heading">{n} articles selected</div> : null}
        {anyUnread ? (
          <button role="menuitem" className="ctx-item" onClick={act(() => p.onSetRead(true))}>
            <Icon name="read" size={20} />
            {`Mark${some} as read`}
          </button>
        ) : null}
        {anyRead ? (
          <button role="menuitem" className="ctx-item" onClick={act(() => p.onSetRead(false))}>
            <Icon name="unread" size={20} />
            {`Mark${some} unread`}
          </button>
        ) : null}
        <button role="menuitem" className="ctx-item danger" onClick={act(p.onDelete)}>
          <Icon name="delete" size={20} />
          {`Delete${some}`}
        </button>
      </div>
    </div>
  )
}

export function ArticleList(p: Props) {
  const [menu, setMenu] = useState<{ items: Item[]; x: number; y: number } | null>(null)
  // Multi-select: Ctrl/Cmd-click adds or removes one article, Shift-click selects a range
  const [picked, setPicked] = useState<Set<string>>(() => new Set())
  const anchor = useRef<string | null>(null)
  const longPress = useRef<number | null>(null)
  const longPressed = useRef(false)
  const s = p.settings
  const network = useNetwork()
  const online = network && !s.offlineMode
  const total = p.groups.reduce((n, g) => n + g.items.length, 0)
  const grouped = s.grouping !== 'none'

  const [sheet, setSheet] = useState(false)
  // Where YouTube videos were left, shown in place of the reading time
  const videoPos = useLiveQuery(async () => new Map((await db.videoPos.toArray()).map((v) => [v.id, v])), [])
  const countOf = (v: ShowFilter) => (v === 'unread' ? p.counts.unread : v === 'saved' ? p.counts.saved : v === 'downloaded' ? p.counts.downloaded : 0)

  const toggleGroup = (key: string) => {
    const id = `${s.grouping}:${key}`
    updateSettings({
      collapsedGroups: s.collapsedGroups.includes(id) ? s.collapsedGroups.filter((x) => x !== id) : [...s.collapsedGroups, id],
    })
  }
  const collapseAll = () => {
    const ids = p.groups.map((g) => `${s.grouping}:${g.key}`)
    const allClosed = ids.every((id) => s.collapsedGroups.includes(id))
    updateSettings({
      collapsedGroups: allClosed
        ? s.collapsedGroups.filter((x) => !ids.includes(x))
        : [...new Set([...s.collapsedGroups, ...ids])],
    })
  }
  // Articles in list order, for Shift-click ranges, Select all, and dropping ones that left the list
  const shown = p.groups.flatMap((g) => (grouped && s.collapsedGroups.includes(`${s.grouping}:${g.key}`) ? [] : g.items))
  const selected = shown.filter((i) => picked.has(i.id))
  useEffect(() => {
    if (picked.size && selected.length !== picked.size) setPicked(new Set(selected.map((i) => i.id)))
  }, [selected.length, picked.size]) // eslint-disable-line react-hooks/exhaustive-deps
  const clearSelection = () => {
    setPicked(new Set())
    anchor.current = null
  }
  useEffect(() => {
    if (!picked.size) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !menu) {
        e.stopPropagation()
        clearSelection()
      }
    }
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  }, [picked.size, menu])

  const clickRow = (e: MouseEvent, item: Item) => {
    if (e.shiftKey && anchor.current) {
      e.preventDefault()
      const a = shown.findIndex((i) => i.id === anchor.current)
      const b = shown.findIndex((i) => i.id === item.id)
      if (a >= 0 && b >= 0) {
        const [from, to] = a < b ? [a, b] : [b, a]
        setPicked(new Set([...picked, ...shown.slice(from, to + 1).map((i) => i.id)]))
        return
      }
    }
    if (e.metaKey || e.ctrlKey || e.shiftKey) {
      e.preventDefault()
      const openId = p.selectedId && p.selectedId !== item.id && shown.some((i) => i.id === p.selectedId) ? p.selectedId : null
      anchor.current = item.id
      setPicked((prev) => {
        const next = new Set(prev)
        if (next.has(item.id)) next.delete(item.id)
        else next.add(item.id)
        // Start with the open article, so Ctrl-clicking a second one selects both
        if (!prev.size && openId) next.add(openId)
        return next
      })
      return
    }
    if (picked.size) clearSelection()
    p.onOpen(item)
  }
  const setRead = (items: Item[], read: boolean) => {
    p.onSetRead(items, read)
  }
  const remove = (items: Item[]) => {
    clearSelection()
    p.onDelete(items)
  }

  const markGroupRead = async (g: Group) => {
    const ids = g.items.filter((i) => !i.read).map((i) => i.id)
    await db.items.bulkUpdate(ids.map((key) => ({ key, changes: { read: 1, readTime: Date.now(), readSynced: 0 } })))
  }

  return (
    <section className="list-pane" aria-label="Articles">
      <header className="pane-bar">
        <span className="only-narrow">
          <IconButton icon="menu" label="Feeds" onClick={p.onMenu} />
        </span>
        <h1 className="pane-title" title={p.title}>
          {p.title}
        </h1>
        {network ? (
          <button
            type="button"
            className={`offline-toggle${s.offlineMode ? ' on' : ''}`}
            aria-pressed={s.offlineMode}
            title={s.offlineMode ? 'Offline mode is on: only downloaded content, nothing fetched. Click to go online.' : 'Offline mode: use only downloaded content, even with internet'}
            onClick={() => updateSettings({ offlineMode: !s.offlineMode })}
          >
            <Icon name="download" size={16} /> {s.offlineMode ? 'Offline mode' : 'Go offline'}
          </button>
        ) : (
          <span className="offline-badge" title="No connection: downloaded articles still open, and changes sync later">
            No connection
          </span>
        )}
        <IconButton icon="flip" label="Flip mode (F)" onClick={p.onFlip} />
        <IconButton icon="delete" label="Delete read articles on all devices" onClick={p.onDeleteRead} />
        <IconButton icon="doneAll" label="Mark all as read" onClick={p.onMarkAllRead} />
      </header>
      {selected.length ? (
        <div className="select-bar" role="toolbar" aria-label="Selected articles">
          <IconButton icon="close" label="Clear selection (Esc)" onClick={clearSelection} />
          <span className="select-count">{selected.length} selected</span>
          {selected.length < shown.length ? (
            <button className="select-all" onClick={() => setPicked(new Set(shown.map((i) => i.id)))}>
              Select all
            </button>
          ) : null}
          <span className="select-spacer" />
          {selected.some((i) => !i.read) ? <IconButton icon="read" label="Mark as read" onClick={() => setRead(selected, true)} /> : null}
          {selected.some((i) => i.read) ? <IconButton icon="unread" label="Mark unread" onClick={() => setRead(selected, false)} /> : null}
          <IconButton icon="delete" label="Delete on all devices" onClick={() => remove(selected)} />
        </div>
      ) : null}
      <div className="list-filters">
        <div className="filter-row">
          <div className="segmented" role="radiogroup" aria-label="Show">
            {SHOWS.map(([v, l]) => {
              const n = countOf(v)
              return (
                <button key={v} role="radio" aria-checked={s.show === v} className={`seg${s.show === v ? ' on' : ''}`} onClick={() => updateSettings({ show: v })}>
                  {l}
                  {n ? <span className="seg-count">{n}</span> : null}
                </button>
              )
            })}
          </div>
          <button className={`chip${s.today ? ' on' : ''}`} aria-pressed={s.today} onClick={() => updateSettings({ today: !s.today })}>
            {s.today ? <Icon name="check" size={16} /> : null}
            Today
          </button>
          <button className="chip sort-button only-phone" aria-haspopup="dialog" onClick={() => setSheet(true)}>
            <Icon name="sort" size={16} />
            {labelOf(SORTINGS, s.sorting)} · {labelOf(GROUPINGS, s.grouping)}
          </button>
        </div>
        <div className="filter-row inline-sort only-wide">
          <label className="select-chip">
            <span>Sort</span>
            <select value={s.sorting} onChange={(e) => updateSettings({ sorting: e.target.value as Sorting })}>
              {SORTINGS.map(([v, l]) => (
                <option key={v} value={v}>
                  {l}
                </option>
              ))}
            </select>
          </label>
          <label className="select-chip">
            <span>Group</span>
            <select value={s.grouping} onChange={(e) => updateSettings({ grouping: e.target.value as Grouping })}>
              {GROUPINGS.map(([v, l]) => (
                <option key={v} value={v}>
                  {l}
                </option>
              ))}
            </select>
          </label>
        </div>
      </div>
      {sheet ? <SortGroupSheet s={s} onClose={() => setSheet(false)} /> : null}
      <div className="list-scroll">
        {total === 0 ? <p className="empty">Nothing here. Try another tab, turn off Today, or refresh.</p> : null}
        {p.groups.map((g) => {
          const collapsed = grouped && s.collapsedGroups.includes(`${s.grouping}:${g.key}`)
          return (
            <div key={g.key} className="group">
              {grouped ? (
                <div
                  className="group-header"
                  role="button"
                  tabIndex={0}
                  aria-expanded={!collapsed}
                  title="Click to collapse, long-press to collapse all"
                  onPointerDown={() => {
                    longPressed.current = false
                    longPress.current = window.setTimeout(() => {
                      longPressed.current = true
                      collapseAll()
                    }, 550)
                  }}
                  onPointerUp={() => longPress.current && clearTimeout(longPress.current)}
                  onPointerLeave={() => longPress.current && clearTimeout(longPress.current)}
                  onClick={() => !longPressed.current && toggleGroup(g.key)}
                  onKeyDown={(e) => (e.key === 'Enter' || e.key === ' ') && toggleGroup(g.key)}
                >
                  <Icon name={collapsed ? 'next' : 'expand'} size={18} />
                  <span className="group-label">{g.label}</span>
                  <span className="group-count">{g.unread ? `${g.unread} unread` : `${g.items.length}`}</span>
                  {g.unread ? (
                    <button
                      className="group-read"
                      title="Mark group as read"
                      aria-label={`Mark ${g.label} as read`}
                      onClick={(e) => {
                        e.stopPropagation()
                        markGroupRead(g)
                      }}
                      onPointerDown={(e) => e.stopPropagation()}
                    >
                      <Icon name="doneAll" size={18} />
                    </button>
                  ) : null}
                </div>
              ) : null}
              {collapsed
                ? null
                : g.items.map((item) => {
                    const feed = p.feeds.get(item.feedUrl)
                    const pos = videoPos?.get(item.id)
                    return (
                      <button
                        key={item.id}
                        className={`row${item.read ? ' read' : ''}${item.id === p.selectedId && !picked.size ? ' selected' : ''}${picked.has(item.id) ? ' picked' : ''}`}
                        aria-pressed={picked.size ? picked.has(item.id) : undefined}
                        onClick={(e) => clickRow(e, item)}
                        onContextMenu={(e) => {
                          e.preventDefault()
                          // Right-click inside the selection acts on all of it; elsewhere, on that article only
                          if (picked.has(item.id)) setMenu({ items: selected, x: e.clientX, y: e.clientY })
                          else {
                            if (picked.size) clearSelection()
                            setMenu({ items: [item], x: e.clientX, y: e.clientY })
                          }
                        }}
                      >
                        <div className="row-main">
                          <div className="row-title">{item.title || '(no title)'}</div>
                          <div className="row-meta">
                            <span className="row-feed">{feedDisplayTitle(feed)}</span>
                            <span>{relativeTime(item.sortTime)}</span>
                            {pos ? (
                              <span className="row-video" title="Where you left this video">
                                {pos.d > 0 && !isWatched(pos) ? (
                                  <span className="row-video-bar" aria-hidden="true">
                                    <span style={{ width: `${Math.min(100, (pos.t / pos.d) * 100)}%` }} />
                                  </span>
                                ) : null}
                                {positionLabel(pos)}
                              </span>
                            ) : (
                              <span>{readingMinutes(wordsOf(item, feed))} min</span>
                            )}
                            {item.saved ? <Icon name="bookmarkFilled" size={14} title="Saved" /> : null}
                            {item.offlineAt || item.offlineVideo ? (
                              <span className={`row-offline${online ? '' : ' strong'}`} title={item.offlineVideo ? 'Video saved for offline watching' : 'Saved for offline reading'}>
                                <Icon name="download" size={14} />
                              </span>
                            ) : null}
                          </div>
                          {s.snippetLines > 0 && item.snippet ? (
                            <div className="row-snippet" style={{ WebkitLineClamp: s.snippetLines }}>
                              {item.snippet}
                            </div>
                          ) : null}
                        </div>
                        {s.showThumbnails && item.imageUrl ? <Thumb src={item.imageUrl} /> : null}
                      </button>
                    )
                  })}
            </div>
          )
        })}
      </div>
      {menu ? (
        <RowMenu
          items={menu.items}
          x={menu.x}
          y={menu.y}
          onClose={() => setMenu(null)}
          onSetRead={(read) => setRead(menu.items, read)}
          onDelete={() => remove(menu.items)}
        />
      ) : null}
    </section>
  )
}
