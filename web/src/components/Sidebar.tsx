import { useEffect, useMemo, useState } from 'react'
import { useLiveQuery } from 'dexie-react-hooks'
import { FEED_ORDER_KEY, feedDisplayTitle, FOLDER_ORDER_KEY, getKv, sortFolder, sortFolders, type Feed, type Item } from '../lib/db'
import type { Selection } from '../lib/listquery'
import { Icon, IconButton } from './Icon'
import { proxiedUrl } from '../lib/api'
import { useVideoJobs, useVideoSavingAvailable } from '../lib/offline'
import { usePlaylists } from './Playlists'
import { collapseAllFolders, loadClosedFolders, saveClosedFolders, toggleFolder } from '../lib/folders'

interface Props {
  feeds: Feed[]
  items: Item[]
  selection: Selection
  onSelect: (s: Selection) => void
  onEditFeed: (url: string) => void
  /**
   * Drag and drop: put the feed in folder `tag` ("" = no folder). With `beforeUrl` it also gets a
   * manual position in that folder (null = at the end); `folderUrls` is the folder as shown.
   */
  onMoveFeed: (url: string, tag: string, place?: { beforeUrl: string | null; folderUrls: string[] }) => void
  onAddFeed: () => void
  onManageFeeds: () => void
  /** The Manage feeds page is open. */
  managing: boolean
  highlightCount: number
  showingHighlights: boolean
  onHighlights: () => void
  showingHome: boolean
  showingDiscover: boolean
  onDiscover: () => void
  onHome: () => void
  showingDownloads: boolean
  onDownloads: () => void
  /** null = not on Playlists, '' = all playlists, else the open playlist's id. */
  showingPlaylist: string | null
  onPlaylists: (id: string) => void
  onRefresh: () => void
  onSettings: () => void
  refreshing: string | null
  syncLabel: string
  /** Desktop only: the sidebar is shrunk to a slim rail of icons. */
  collapsed: boolean
  onToggleCollapsed: () => void
}

export function FeedIcon({ feed }: { feed: Feed }) {
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
  const [closedTags, setClosedTags] = useState<string[]>(loadClosedFolders)
  useEffect(() => saveClosedFolders(closedTags), [closedTags])
  const order = useLiveQuery(() => getKv<string[]>(FEED_ORDER_KEY, []), []) ?? []
  const folderOrder = useLiveQuery(() => getKv<string[]>(FOLDER_ORDER_KEY, []), []) ?? []
  // Drag and drop of feeds between folders
  const [dragUrl, setDragUrl] = useState<string | null>(null)
  const [dropTag, setDropTag] = useState<string | null>(null)
  /** Where a reorder drop would land: before or after this feed row. */
  const [dropAt, setDropAt] = useState<{ url: string; after: boolean } | null>(null)
  const dragFeed = dragUrl ? p.feeds.find((f) => f.url === dragUrl) : undefined
  const endDrag = () => {
    setDragUrl(null)
    setDropTag(null)
    setDropAt(null)
  }
  /** Handlers that make an element a drop target for the folder `tag`. */
  const dropProps = (tag: string) => ({
    onDragOver: (e: React.DragEvent) => {
      if (!dragUrl) return
      e.preventDefault()
      e.stopPropagation()
      e.dataTransfer.dropEffect = 'move'
      if (dropTag !== tag) setDropTag(tag)
      if (dropAt) setDropAt(null)
    },
    onDrop: (e: React.DragEvent) => {
      if (!dragUrl) return
      e.preventDefault()
      e.stopPropagation()
      p.onMoveFeed(dragUrl, tag)
      endDrag()
    },
  })
  const isDropTarget = (tag: string) => dropTag === tag && !!dragFeed && dragFeed.tag !== tag
  const unreadByFeed = useMemo(() => {
    const m = new Map<string, number>()
    for (const i of p.items) if (!i.read) m.set(i.feedUrl, (m.get(i.feedUrl) ?? 0) + 1)
    return m
  }, [p.items])
  const totalUnread = useMemo(() => [...unreadByFeed.values()].reduce((a, b) => a + b, 0), [unreadByFeed])
  const savedCount = useMemo(() => p.items.filter((i) => i.saved).length, [p.items])
  const videoJobs = useVideoJobs()
  const canSaveVideos = useVideoSavingAvailable()
  // Saved videos plus unfinished downloads; the entry shows where videos can be saved or already are
  const downloadCount = useMemo(() => p.items.filter((i) => i.offlineVideo).length, [p.items]) + Object.keys(videoJobs).length
  const showDownloads = canSaveVideos || downloadCount > 0 || p.showingDownloads

  const tags = useMemo(() => {
    const byTag = new Map<string, Feed[]>()
    for (const f of p.feeds) byTag.set(f.tag, [...(byTag.get(f.tag) ?? []), f])
    for (const [tag, list] of byTag) byTag.set(tag, sortFolder(list, order))
    // Folders in the order set on the Manage feeds page, feeds without a folder last
    const named = sortFolders([...byTag.keys()].filter(Boolean), folderOrder)
    return [...named, ...(byTag.has('') ? [''] : [])].map((t) => [t, byTag.get(t)!] as const)
  }, [p.feeds, order, folderOrder])

  const folderNames = tags.map(([t]) => t).filter(Boolean)

  const playlists = usePlaylists()
  const isSel = (s: Selection) => !p.showingHome && !p.showingDiscover && !p.managing && !p.showingHighlights && !p.showingDownloads && p.showingPlaylist === null && JSON.stringify(s) === JSON.stringify(p.selection)

  const feedRow = (f: Feed, i: number, folder: Feed[]) => (
    <li
      key={f.url}
      draggable
      className={
        [
          dragUrl === f.url ? 'dragging' : '',
          dropAt?.url === f.url && dragUrl !== f.url ? (dropAt.after ? 'drop-after' : 'drop-before') : '',
        ]
          .filter(Boolean)
          .join(' ') || undefined
      }
      onDragStart={(e) => {
        e.dataTransfer.effectAllowed = 'move'
        e.dataTransfer.setData('text/plain', f.url)
        setDragUrl(f.url)
      }}
      onDragEnd={endDrag}
      onDragOver={(e) => {
        if (!dragUrl) return
        e.preventDefault()
        e.stopPropagation()
        e.dataTransfer.dropEffect = 'move'
        const r = e.currentTarget.getBoundingClientRect()
        const after = e.clientY > r.top + r.height / 2
        if (dropAt?.url !== f.url || dropAt.after !== after) setDropAt({ url: f.url, after })
        if (dropTag !== null) setDropTag(null)
      }}
      onDrop={(e) => {
        if (!dragUrl) return
        e.preventDefault()
        e.stopPropagation()
        const after = dropAt?.url === f.url ? dropAt.after : false
        const beforeUrl = after ? (folder[i + 1]?.url ?? null) : f.url
        if (dragUrl !== f.url) p.onMoveFeed(dragUrl, f.tag, { beforeUrl, folderUrls: folder.map((x) => x.url) })
        endDrag()
      }}
    >
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
      <div className="sidebar-rail" aria-label="Feeds (collapsed)">
        <IconButton icon="panelOpen" label="Show feeds" title="Show feeds ( [ )" onClick={p.onToggleCollapsed} />
        <button className={`rail-btn${p.showingHome ? ' selected' : ''}`} title="Home: all your feeds as tiles" aria-label="Home" onClick={p.onHome}>
          <Icon name="home" size={20} />
        </button>
        <button className={`rail-btn${p.showingDiscover ? ' selected' : ''}`} title="Discover new feeds, podcasts and channels" aria-label="Discover" onClick={p.onDiscover}>
          <Icon name="explore" size={20} />
        </button>
        <button
          className={`rail-btn${isSel({ kind: 'all' }) ? ' selected' : ''}`}
          title={`All articles${totalUnread ? ` (${totalUnread} unread)` : ''}`}
          aria-label="All articles"
          onClick={() => p.onSelect({ kind: 'all' })}
        >
          <Icon name="article" size={20} />
        </button>
        <button
          className={`rail-btn${isSel({ kind: 'saved' }) ? ' selected' : ''}`}
          title="Saved"
          aria-label="Saved"
          onClick={() => p.onSelect({ kind: 'saved' })}
        >
          <Icon name="bookmark" size={20} />
        </button>
        <button
          className={`rail-btn${p.showingHighlights ? ' selected' : ''}`}
          title="Highlights"
          aria-label="Highlights"
          onClick={p.onHighlights}
        >
          <Icon name="highlight" size={20} />
        </button>
        <button
          className={`rail-btn${p.showingPlaylist !== null ? ' selected' : ''}`}
          title="Playlists"
          aria-label="Playlists"
          onClick={() => p.onPlaylists('')}
        >
          <Icon name="queue" size={20} />
        </button>
        {showDownloads ? (
          <button
            className={`rail-btn${p.showingDownloads ? ' selected' : ''}`}
            title="Downloads"
            aria-label="Downloads"
            onClick={p.onDownloads}
          >
            <Icon name="download" size={20} />
          </button>
        ) : null}
        <span className="rail-gap" />
        <IconButton icon="refresh" label={p.refreshing ? `Refreshing ${p.refreshing}` : 'Refresh'} onClick={p.onRefresh} disabled={!!p.refreshing} />
        <IconButton icon="add" label="Add feed" onClick={p.onAddFeed} />
        <IconButton icon="tune" label="Manage feeds" onClick={p.onManageFeeds} active={p.managing} />
        <IconButton icon="settings" label="Settings" onClick={p.onSettings} />
      </div>
      <div className="sidebar-top">
        <span className="brand">Feeder</span>
        <IconButton icon="refresh" label={p.refreshing ? `Refreshing ${p.refreshing}` : 'Refresh'} text="Refresh" onClick={p.onRefresh} disabled={!!p.refreshing} />
        <IconButton icon="add" label="Add feed" text="Add feed" onClick={p.onAddFeed} />
        <IconButton icon="tune" label="Manage feeds" text="Manage" onClick={p.onManageFeeds} active={p.managing} />
        <IconButton icon="settings" label="Settings" text="Settings" onClick={p.onSettings} />
        <span className="only-wide">
          <IconButton icon="panelClose" label="Hide feeds" title="Hide feeds ( [ )" onClick={p.onToggleCollapsed} />
        </span>
      </div>
      {p.refreshing ? <div className="sidebar-status">Refreshing {p.refreshing}</div> : null}
      <ul className="nav-list">
        <li>
          <button className={`nav-row${p.showingHome ? ' selected' : ''}`} onClick={p.onHome}>
            <Icon name="home" size={20} />
            <span className="nav-title">Home</span>
            <span className="count muted">{p.feeds.length}</span>
          </button>
        </li>
        <li>
          <button className={`nav-row${p.showingDiscover ? ' selected' : ''}`} onClick={p.onDiscover}>
            <Icon name="explore" size={20} />
            <span className="nav-title">Discover</span>
          </button>
        </li>
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
        <li>
          <button className={`nav-row${p.showingHighlights ? ' selected' : ''}`} onClick={p.onHighlights}>
            <Icon name="highlight" size={20} />
            <span className="nav-title">Highlights</span>
            {p.highlightCount ? <span className="count muted">{p.highlightCount}</span> : null}
          </button>
        </li>
        {showDownloads ? (
          <li>
            <button className={`nav-row${p.showingDownloads ? ' selected' : ''}`} onClick={p.onDownloads}>
              <Icon name="download" size={20} />
              <span className="nav-title">Downloads</span>
              {downloadCount ? <span className="count muted">{downloadCount}</span> : null}
            </button>
          </li>
        ) : null}
        <li>
          <button className={`nav-row${p.showingPlaylist === '' ? ' selected' : ''}`} onClick={() => p.onPlaylists('')}>
            <Icon name="queue" size={20} />
            <span className="nav-title">Playlists</span>
            {playlists.length ? <span className="count muted">{playlists.length}</span> : null}
          </button>
          {playlists.length ? (
            <ul className="nav-list nested">
              {playlists.map((pl) => (
                <li key={pl.id}>
                  <button className={`nav-row pl-nav${p.showingPlaylist === pl.id ? ' selected' : ''}`} onClick={() => p.onPlaylists(pl.id)}>
                    <span className="pl-nav-dot" style={{ background: pl.color }} aria-hidden="true">
                      {pl.icon}
                    </span>
                    <span className="nav-title">{pl.name}</span>
                    {pl.smart ? <Icon name="bolt" size={14} title="Smart playlist" /> : <span className="count muted">{pl.itemIds.length}</span>}
                  </button>
                </li>
              ))}
            </ul>
          ) : null}
        </li>
      </ul>
      {folderNames.length ? (
        <div className="folder-tools">
          <span className="folder-tools-label">Folders</span>
          <IconButton icon="unfoldMore" label="Expand all folders" onClick={() => setClosedTags([])} />
          <IconButton
            icon="unfoldLess"
            label="Collapse all folders"
            onClick={() => setClosedTags((t) => collapseAllFolders(t, folderNames))}
          />
        </div>
      ) : null}
      <ul
        className={`nav-list feeds${isDropTarget('') ? ' drop-target' : ''}`}
        {...dropProps('')}
        onDragLeave={(e) => {
          if (!e.currentTarget.contains(e.relatedTarget as Node | null)) setDropTag(null)
        }}
      >
        {tags.map(([tag, feeds]) =>
          tag ? (
            <li key={`tag:${tag}`} className={`tag-block${isDropTarget(tag) ? ' drop-target' : ''}`} {...dropProps(tag)}>
              <div className="tag-row-wrap">
                <button
                  className="tag-toggle"
                  aria-label={closedTags.includes(tag) ? `Expand ${tag}` : `Collapse ${tag}`}
                  onClick={() => setClosedTags((t) => toggleFolder(t, tag))}
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
              {closedTags.includes(tag) ? null : <ul className="nav-list nested">{feeds.map((f, i) => feedRow(f, i, feeds))}</ul>}
            </li>
          ) : (
            feeds.map((f, i) => feedRow(f, i, feeds))
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
