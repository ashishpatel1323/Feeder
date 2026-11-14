import { useMemo, useState } from 'react'
import { feedDisplayTitle, type Feed, type Item } from '../lib/db'
import type { Selection } from '../lib/listquery'
import { Icon, IconButton } from './Icon'
import { proxiedUrl } from '../lib/api'

interface Props {
  feeds: Feed[]
  items: Item[]
  selection: Selection
  onSelect: (s: Selection) => void
  onEditFeed: (url: string) => void
  onAddFeed: () => void
  onRefresh: () => void
  onSettings: () => void
  refreshing: string | null
  syncLabel: string
}

function FeedIcon({ feed }: { feed: Feed }) {
  const [broken, setBroken] = useState(false)
  const title = feedDisplayTitle(feed)
  let src = feed.imageUrl
  if (!src) {
    try {
      src = proxiedUrl(`${new URL(feed.siteUrl || feed.url).origin}/favicon.ico`)
    } catch {
      src = null
    }
  }
  if (!src || broken) return <span className="feed-icon letter">{(title[0] ?? '?').toUpperCase()}</span>
  return <img className="feed-icon" src={src} alt="" loading="lazy" referrerPolicy="no-referrer" onError={() => setBroken(true)} />
}

export function Sidebar(p: Props) {
  const [closedTags, setClosedTags] = useState<string[]>([])
  const unreadByFeed = useMemo(() => {
    const m = new Map<string, number>()
    for (const i of p.items) if (!i.read) m.set(i.feedUrl, (m.get(i.feedUrl) ?? 0) + 1)
    return m
  }, [p.items])
  const totalUnread = useMemo(() => [...unreadByFeed.values()].reduce((a, b) => a + b, 0), [unreadByFeed])
  const savedCount = useMemo(() => p.items.filter((i) => i.saved).length, [p.items])

  const tags = useMemo(() => {
    const byTag = new Map<string, Feed[]>()
    for (const f of p.feeds) byTag.set(f.tag, [...(byTag.get(f.tag) ?? []), f])
    for (const list of byTag.values())
      list.sort((a, b) => feedDisplayTitle(a).toLowerCase().localeCompare(feedDisplayTitle(b).toLowerCase()))
    return [...byTag.entries()].sort(([a], [b]) => (!a !== !b ? (a ? -1 : 1) : a.toLowerCase().localeCompare(b.toLowerCase())))
  }, [p.feeds])

  const isSel = (s: Selection) => JSON.stringify(s) === JSON.stringify(p.selection)

  const feedRow = (f: Feed) => (
    <li key={f.url}>
      <button className={`nav-row feed${isSel({ kind: 'feed', url: f.url }) ? ' selected' : ''}`} onClick={() => p.onSelect({ kind: 'feed', url: f.url })}>
        <FeedIcon feed={f} />
        <span className="nav-title">{feedDisplayTitle(f)}</span>
        {f.lastError ? <span className="err-dot" title={`Last refresh failed: ${f.lastError}`} /> : null}
        {unreadByFeed.get(f.url) ? <span className="count">{unreadByFeed.get(f.url)}</span> : null}
      </button>
      <button className="nav-edit" title="Edit feed" aria-label={`Edit ${feedDisplayTitle(f)}`} onClick={() => p.onEditFeed(f.url)}>
        <Icon name="edit" size={16} />
      </button>
    </li>
  )

  return (
    <nav className="sidebar">
      <div className="sidebar-top">
        <span className="brand">Feeder</span>
        <IconButton icon="refresh" label={p.refreshing ? `Refreshing ${p.refreshing}` : 'Refresh'} onClick={p.onRefresh} disabled={!!p.refreshing} />
        <IconButton icon="add" label="Add feed" onClick={p.onAddFeed} />
        <IconButton icon="settings" label="Settings" onClick={p.onSettings} />
      </div>
      {p.refreshing ? <div className="sidebar-status">Refreshing {p.refreshing}</div> : null}
      <ul className="nav-list">
        <li>
          <button className={`nav-row${isSel({ kind: 'all' }) ? ' selected' : ''}`} onClick={() => p.onSelect({ kind: 'all' })}>
            <Icon name="article" size={20} />
            <span className="nav-title">All articles</span>
            {totalUnread ? <span className="count">{totalUnread}</span> : null}
          </button>
        </li>
        <li>
          <button className={`nav-row${isSel({ kind: 'saved' }) ? ' selected' : ''}`} onClick={() => p.onSelect({ kind: 'saved' })}>
            <Icon name="bookmark" size={20} />
            <span className="nav-title">Saved</span>
            {savedCount ? <span className="count muted">{savedCount}</span> : null}
          </button>
        </li>
      </ul>
      <ul className="nav-list feeds">
        {tags.map(([tag, feeds]) =>
          tag ? (
            <li key={`tag:${tag}`} className="tag-block">
              <div className="tag-row-wrap">
                <button
                  className="tag-toggle"
                  aria-label={closedTags.includes(tag) ? `Expand ${tag}` : `Collapse ${tag}`}
                  onClick={() => setClosedTags((t) => (t.includes(tag) ? t.filter((x) => x !== tag) : [...t, tag]))}
                >
                  <Icon name={closedTags.includes(tag) ? 'next' : 'expand'} size={18} />
                </button>
                <button className={`nav-row tag${isSel({ kind: 'tag', tag }) ? ' selected' : ''}`} onClick={() => p.onSelect({ kind: 'tag', tag })}>
                  <span className="nav-title">{tag}</span>
                  {feeds.reduce((n, f) => n + (unreadByFeed.get(f.url) ?? 0), 0) ? (
                    <span className="count">{feeds.reduce((n, f) => n + (unreadByFeed.get(f.url) ?? 0), 0)}</span>
                  ) : null}
                </button>
              </div>
              {closedTags.includes(tag) ? null : <ul className="nav-list nested">{feeds.map(feedRow)}</ul>}
            </li>
          ) : (
            feeds.map(feedRow)
          ),
        )}
      </ul>
      {!p.feeds.length ? (
        <p className="sidebar-empty">No feeds yet. Add one, import OPML, or join your phone's sync chain in Settings.</p>
      ) : null}
      <div className="sidebar-foot">{p.syncLabel}</div>
    </nav>
  )
}
