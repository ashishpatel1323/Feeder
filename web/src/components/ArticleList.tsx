import { useEffect, useRef, useState, type MouseEvent } from 'react'
import { db, type Feed, type Item } from '../lib/db'
import type { Group } from '../lib/listquery'
import { updateSettings, type Grouping, type Settings, type ShowFilter, type Sorting } from '../lib/settings'
import { useNetwork } from '../lib/offline'
import { Icon, IconButton } from './Icon'
import { MoreMenu } from './MoreMenu'
import { isShared } from '../lib/share'
import { articleVideoId } from '../lib/youtube'
import { useManualMembership } from './AddToPlaylist'
import { ItemRow, usePlaylistContext } from './ItemRow'

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
  /** Opens the feed's own page (stats and settings). */
  onOpenFeed: (url: string) => void
  /** On Saved: the always-visible "Paste a link" box. Resolves true when the link was added. */
  onAddLink?: (text: string) => Promise<boolean>
  /** Opens global search; a button only on narrow screens (wide ones have the sidebar's search bar). */
  onSearch?: () => void
}

type SavedKind = 'all' | 'links' | 'videos'

/** Saved only: paste any web page or YouTube link to keep it here. */
function AddLinkBar(p: { onAdd: (text: string) => Promise<boolean>; kind: SavedKind; onKind: (k: SavedKind) => void }) {
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const submit = async () => {
    if (!text.trim() || busy) return
    setBusy(true)
    try {
      if (await p.onAdd(text)) setText('')
    } finally {
      setBusy(false)
    }
  }
  return (
    <div className="add-link">
      <form
        className="add-link-row"
        onSubmit={(e) => {
          e.preventDefault()
          void submit()
        }}
      >
        <label className="add-link-input">
          <Icon name="link" size={18} />
          <input
            type="url"
            inputMode="url"
            placeholder="Paste a link to an article or YouTube video"
            aria-label="Link to save"
            value={text}
            disabled={busy}
            onChange={(e) => setText(e.target.value)}
            onPaste={(e) => {
              // Pasting into the box adds it straight away
              const t = e.clipboardData.getData('text')
              if (t && !text) {
                e.preventDefault()
                setText(t)
                setBusy(true)
                p.onAdd(t)
                  .then((ok) => ok && setText(''))
                  .finally(() => setBusy(false))
              }
            }}
          />
        </label>
        <button type="submit" className="add-link-btn" disabled={busy || !text.trim()}>
          <Icon name="add" size={18} />
          {busy ? 'Adding…' : 'Add'}
        </button>
      </form>
      <div className="add-link-hint">
        Or paste a link anywhere in Feeder (<kbd>⌘V</kbd>), or share it to Feeder on your phone.
      </div>
      <div className="add-link-kinds" role="radiogroup" aria-label="Kind">
        {(
          [
            ['all', 'Everything'],
            ['links', 'Shared links'],
            ['videos', 'Videos'],
          ] as [SavedKind, string][]
        ).map(([k, l]) => (
          <button key={k} role="radio" aria-checked={p.kind === k} className={`chip${p.kind === k ? ' on' : ''}`} onClick={() => p.onKind(k)}>
            {p.kind === k ? <Icon name="check" size={16} /> : null}
            {l}
          </button>
        ))}
      </div>
    </div>
  )
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
  const inPlaylists = useManualMembership()
  const grouped = s.grouping !== 'none'

  const [sheet, setSheet] = useState(false)
  const [savedKind, setSavedKind] = useState<SavedKind>('all')
  const kindFilter = p.onAddLink && savedKind !== 'all' ? (i: Item) => (savedKind === 'links' ? isShared(i) : !!articleVideoId(i)) : null
  const groups = kindFilter ? p.groups.map((g) => ({ ...g, items: g.items.filter(kindFilter) })).filter((g) => g.items.length) : p.groups
  const total = groups.reduce((n, g) => n + g.items.length, 0)
  // Feed kinds, play positions and lengths for each row's type and length
  const ctx = usePlaylistContext(p.feeds, 1)
  const countOf = (v: ShowFilter) => (v === 'unread' ? p.counts.unread : v === 'saved' ? p.counts.saved : v === 'downloaded' ? p.counts.downloaded : 0)

  const toggleGroup = (key: string) => {
    const id = `${s.grouping}:${key}`
    updateSettings({
      collapsedGroups: s.collapsedGroups.includes(id) ? s.collapsedGroups.filter((x) => x !== id) : [...s.collapsedGroups, id],
    })
  }
  const collapseAll = () => {
    const ids = groups.map((g) => `${s.grouping}:${g.key}`)
    const allClosed = ids.every((id) => s.collapsedGroups.includes(id))
    updateSettings({
      collapsedGroups: allClosed
        ? s.collapsedGroups.filter((x) => !ids.includes(x))
        : [...new Set([...s.collapsedGroups, ...ids])],
    })
  }
  // Articles in list order, for Shift-click ranges, Select all, and dropping ones that left the list
  const shown = groups.flatMap((g) => (grouped && s.collapsedGroups.includes(`${s.grouping}:${g.key}`) ? [] : g.items))
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
          // Offline mode is turned on from More; while on, this says so and turns it off
          s.offlineMode ? (
            <button
              type="button"
              className="offline-toggle on"
              aria-pressed
              title="Offline mode is on: only downloaded content, nothing fetched. Click to go online."
              onClick={() => updateSettings({ offlineMode: false })}
            >
              <Icon name="download" size={16} /> Offline mode
            </button>
          ) : null
        ) : (
          <span className="offline-badge" title="No connection: downloaded articles still open, and changes sync later">
            No connection
          </span>
        )}
        {p.onSearch ? (
          <span className="only-narrow">
            <IconButton icon="search" label="Search everything" onClick={p.onSearch} />
          </span>
        ) : null}
        <IconButton icon="flip" label="Flip mode (F)" text="Flip" kbd="F" onClick={p.onFlip} />
        <MoreMenu
          entries={[
            { icon: 'doneAll', label: 'Mark all as read', onClick: p.onMarkAllRead },
            { icon: 'download', label: s.offlineMode ? 'Go online' : 'Go offline', checked: s.offlineMode, onClick: () => updateSettings({ offlineMode: !s.offlineMode }), hidden: !network },
            'separator',
            { icon: 'delete', label: 'Delete read articles on all devices', onClick: p.onDeleteRead, danger: true },
          ]}
        />
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
      {p.onAddLink ? <AddLinkBar onAdd={p.onAddLink} kind={savedKind} onKind={setSavedKind} /> : null}
      <div className="list-filters">
        <div className="filter-row">
          {/* One Show chip instead of four segments, which got cut off on phones */}
          <label className={`select-chip show-chip${s.show !== 'unread' ? ' on' : ''}`}>
            <span>Show</span>
            <select value={s.show} onChange={(e) => updateSettings({ show: e.target.value as ShowFilter })}>
              {SHOWS.map(([v, l]) => {
                const n = countOf(v)
                return (
                  <option key={v} value={v}>
                    {n ? `${l} (${n})` : l}
                  </option>
                )
              })}
            </select>
          </label>
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
        {groups.map((g) => {
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
                : g.items.map((item) => (
                    <ItemRow
                      key={item.id}
                      item={item}
                      feed={p.feeds.get(item.feedUrl)}
                      ctx={ctx}
                      selected={item.id === p.selectedId && !picked.size}
                      picked={picked.has(item.id)}
                      rowProps={{ 'aria-selected': picked.size ? picked.has(item.id) : undefined } as React.HTMLAttributes<HTMLDivElement>}
                      onOpen={(e) => clickRow(e, item)}
                      onOpenFeed={(url) => !picked.size && p.onOpenFeed(url)}
                      onSetRead={(read) => setRead([item], read)}
                      onDelete={() => remove([item])}
                      inPlaylist={inPlaylists.has(item.id)}
                      snippetLines={s.snippetLines}
                      onContextMenu={(e) => {
                        e.preventDefault()
                        // Right-click inside the selection acts on all of it; elsewhere, on that article only
                        if (picked.has(item.id)) setMenu({ items: selected, x: e.clientX, y: e.clientY })
                        else {
                          if (picked.size) clearSelection()
                          setMenu({ items: [item], x: e.clientX, y: e.clientY })
                        }
                      }}
                    />
                  ))}
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
