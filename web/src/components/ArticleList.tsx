import { useEffect, useRef, useState } from 'react'
import { db, feedDisplayTitle, type Feed, type Item } from '../lib/db'
import { readingMinutes, wordsOf, type Group } from '../lib/listquery'
import { updateSettings, type Grouping, type Settings, type ShowFilter, type Sorting } from '../lib/settings'
import { proxiedUrl } from '../lib/api'
import { Icon, IconButton } from './Icon'

interface Props {
  title: string
  groups: Group[]
  /** Unread and saved counts for the Show tabs. */
  counts: { unread: number; saved: number }
  feeds: Map<string, Feed>
  settings: Settings
  selectedId: string | null
  onOpen: (item: Item) => void
  onMenu: () => void
  onMarkAllRead: () => void
}

const SHOWS: [ShowFilter, string][] = [
  ['unread', 'Unread'],
  ['all', 'All'],
  ['saved', 'Saved'],
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

export function ArticleList(p: Props) {
  const longPress = useRef<number | null>(null)
  const longPressed = useRef(false)
  const s = p.settings
  const total = p.groups.reduce((n, g) => n + g.items.length, 0)
  const grouped = s.grouping !== 'none'

  const [sheet, setSheet] = useState(false)
  const countOf = (v: ShowFilter) => (v === 'unread' ? p.counts.unread : v === 'saved' ? p.counts.saved : 0)

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
        <IconButton icon="doneAll" label="Mark all as read" onClick={p.onMarkAllRead} />
      </header>
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
                    return (
                      <button
                        key={item.id}
                        className={`row${item.read ? ' read' : ''}${item.id === p.selectedId ? ' selected' : ''}`}
                        onClick={() => p.onOpen(item)}
                      >
                        <div className="row-main">
                          <div className="row-title">{item.title || '(no title)'}</div>
                          <div className="row-meta">
                            <span className="row-feed">{feedDisplayTitle(feed)}</span>
                            <span>{relativeTime(item.sortTime)}</span>
                            <span>{readingMinutes(wordsOf(item, feed))} min</span>
                            {item.saved ? <Icon name="bookmarkFilled" size={14} title="Saved" /> : null}
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
    </section>
  )
}
