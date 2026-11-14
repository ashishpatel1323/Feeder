import { useRef } from 'react'
import { db, feedDisplayTitle, type Feed, type Item } from '../lib/db'
import { formatPoints, readingMinutes, wordsOf, type Group } from '../lib/listquery'
import { updateSettings, type FilterChip, type Grouping, type Settings, type Sorting } from '../lib/settings'
import { proxiedUrl } from '../lib/api'
import { Icon, IconButton } from './Icon'

interface Props {
  title: string
  groups: Group[]
  feeds: Map<string, Feed>
  settings: Settings
  selectedId: string | null
  onOpen: (item: Item) => void
  onMenu: () => void
  onMarkAllRead: () => void
}

const GROUPINGS: [Grouping, string][] = [
  ['none', 'None'],
  ['date', 'Date'],
  ['feed', 'Feed'],
  ['tag', 'Tag'],
  ['length', 'Length'],
  ['status', 'Status'],
  ['site', 'Site'],
]
const SORTINGS: [Sorting, string][] = [
  ['newest', 'Newest first'],
  ['oldest', 'Oldest first'],
  ['shortest', 'Shortest first'],
  ['longest', 'Longest first'],
]
const CHIPS: [FilterChip, string][] = [
  ['unread', 'Unread only'],
  ['saved', 'Saved'],
  ['today', 'Today'],
  ['short', 'Short reads'],
  ['read', 'Read'],
]

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

  const toggleChip = (c: FilterChip) =>
    updateSettings({ filters: s.filters.includes(c) ? s.filters.filter((x) => x !== c) : [...s.filters, c] })

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
      <div className="list-controls">
        <label>
          Group
          <select value={s.grouping} onChange={(e) => updateSettings({ grouping: e.target.value as Grouping })}>
            {GROUPINGS.map(([v, l]) => (
              <option key={v} value={v}>
                {l}
              </option>
            ))}
          </select>
        </label>
        <label>
          Sort
          <select value={s.sorting} onChange={(e) => updateSettings({ sorting: e.target.value as Sorting })}>
            {SORTINGS.map(([v, l]) => (
              <option key={v} value={v}>
                {l}
              </option>
            ))}
          </select>
        </label>
      </div>
      <div className="chips" role="group" aria-label="Filters">
        {CHIPS.map(([c, l]) => (
          <button key={c} className={`chip${s.filters.includes(c) ? ' on' : ''}`} aria-pressed={s.filters.includes(c)} onClick={() => toggleChip(c)}>
            {l}
          </button>
        ))}
      </div>
      <div className="list-scroll">
        {total === 0 ? <p className="empty">Nothing here. Try other filters, or refresh.</p> : null}
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
                            {s.showHnScore && item.hnPoints ? (
                              <span className="hn-badge" title={`${item.hnPoints} points, ${item.hnComments ?? 0} comments on Hacker News`}>
                                ▲ {formatPoints(item.hnPoints)}
                              </span>
                            ) : null}
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
