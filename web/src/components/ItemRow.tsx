/**
 * The one row for an article, podcast episode or YouTube video, used by every list of items: the
 * article list and Saved, playlists, Downloads. Thumbnail on the left with the length (and how far
 * you got) on it, a type badge, and the same labeled buttons everywhere: Read/Play/Watch, Save,
 * Playlist, Mark read, Delete. A page adds its own buttons after those (extras).
 */
import { useMemo, useState, type MouseEvent, type ReactNode } from 'react'
import { useLiveQuery } from 'dexie-react-hooks'
import { db, feedDisplayTitle, getKv, hideItems, unhideItems, type Feed, type Item } from '../lib/db'
import { proxiedUrl } from '../lib/api'
import { FEED_KINDS_KEY, type FeedKind } from '../lib/home'
import { knownDurations } from '../lib/downloads'
import { itemFacts, type ItemFacts } from '../lib/itemview'
import { makeContext, type ItemKind, type PlaylistContext } from '../lib/playlists'
import { playQueue, useQueue } from '../lib/playqueue'
import { isShared, siteOf } from '../lib/share'
import { syncNow } from '../lib/sync2'
import type { Toast } from '../lib/toast'
import { openAddToPlaylist } from './AddToPlaylist'
import { Icon, type IconName } from './Icon'

/** Everything a row needs to know besides the item itself (feed kinds, play positions, lengths), kept live. */
export function usePlaylistContext(feeds: Map<string, Feed>, rate: number): PlaylistContext {
  const feedKinds = useLiveQuery(() => getKv<Record<string, FeedKind>>(FEED_KINDS_KEY, {}), []) ?? {}
  const positions = useLiveQuery(() => db.videoPos.toArray(), []) ?? []
  return useMemo(
    () => makeContext({ feeds, feedKinds, positions: new Map(positions.map((p) => [p.id, p])), videoDurations: knownDurations(), rate }),
    [feeds, feedKinds, positions, rate],
  )
}

/** Type, length and button words for one item, outside a list (reader, Flip). */
export function useItemFacts(item: Item, feeds: Map<string, Feed> | Feed | undefined): ItemFacts {
  const map = useMemo(() => (feeds instanceof Map ? feeds : new Map(feeds ? [[feeds.url, feeds]] : [])), [feeds])
  return itemFacts(item, usePlaylistContext(map, 1))
}

export const KIND_ICONS: Record<ItemKind, IconName> = { article: 'article', podcast: 'headphones', youtube: 'play' }

/** "Article", "Podcast" or "Video", with its icon. */
export function TypeBadge({ kind, label }: { kind: ItemKind; label: string }) {
  return (
    <span className={`ty k-${kind}`}>
      <Icon name={KIND_ICONS[kind]} size={12} />
      {label}
    </span>
  )
}

export function relativeTime(ms: number): string {
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

/** Left thumbnail: the item's picture (or its type icon), the length, and a progress line. */
export function ItemThumb({ item, facts, overlay }: { item: Item; facts: ItemFacts; overlay?: ReactNode }) {
  const [broken, setBroken] = useState(false)
  const src = item.imageUrl && !broken ? (item.imageUrl.startsWith('http://') ? proxiedUrl(item.imageUrl) : item.imageUrl) : null
  return (
    <span className={`ir-thumb k-${facts.kind}`} aria-hidden="true">
      {src ? <img src={src} alt="" loading="lazy" referrerPolicy="no-referrer" onError={() => setBroken(true)} /> : <Icon name={KIND_ICONS[facts.kind]} size={26} />}
      {overlay}
      {facts.badge ? <span className="ir-len">{facts.badge}</span> : null}
      {facts.progress != null ? (
        <span className="ir-prog">
          <i style={{ width: `${Math.round(facts.progress * 100)}%` }} />
        </span>
      ) : null}
    </span>
  )
}

/** Toggles Saved, synced like the reader's Save button. */
export const toggleSaved = (item: Item) => db.items.update(item.id, { saved: item.saved ? 0 : 1, readSynced: 0 })

/** Mark read or unread on every device. */
export async function setItemRead(item: Item, read: boolean) {
  await db.items.update(item.id, { read: read ? 1 : 0, readTime: read ? Date.now() : null, readSynced: 0 })
  syncNow().catch(() => undefined)
}

/** Deletes for good on every device, with Undo. */
export async function deleteItemWithUndo(item: Item, toast: Toast) {
  await hideItems([item.id])
  syncNow().catch(() => undefined)
  toast('Deleted on all devices', { label: 'Undo', run: () => void unhideItems([item]).then(() => syncNow().catch(() => undefined)) })
}

/** Plays an episode in the bottom player; a second press pauses or resumes it. */
export function playOrToggle(item: Item, feedTitle: string) {
  if (playQueue.currentId === item.id) playQueue.toggle()
  else playQueue.start([item.id], { name: feedTitle || 'Up next' })
}

interface RowProps {
  item: Item
  feed: Feed | undefined
  ctx: PlaylistContext
  /** Opens the item (title, thumbnail, or a click on the row). Gets the click for Shift/Ctrl picking. */
  onOpen: (e: MouseEvent) => void
  /** The main button. Default: articles and videos open, episodes play in the bottom player. */
  onPrimary?: () => void
  primaryLabel?: string
  primaryIcon?: IconName
  onSetRead?: (read: boolean) => void
  onDelete: () => void
  onOpenFeed?: (url: string) => void
  /** Before the thumbnail: a drag handle or a number. */
  leading?: ReactNode
  /** On the thumbnail: "Playing", a download percent. */
  thumbOverlay?: ReactNode
  /** Page buttons after the shared five. */
  extras?: ReactNode
  /** More meta (size of a download, "needs download"). */
  metaExtra?: ReactNode
  snippetLines?: number
  inPlaylist?: boolean
  className?: string
  selected?: boolean
  picked?: boolean
  onContextMenu?: (e: MouseEvent) => void
  rowProps?: React.HTMLAttributes<HTMLDivElement>
}

export function ItemRow(p: RowProps) {
  const { item } = p
  const facts = itemFacts(item, p.ctx)
  const queue = useQueue()
  const feedTitle = feedDisplayTitle(p.feed)
  const playingHere = queue.ids[queue.index] === item.id && queue.status === 'playing'
  const stop = (e: MouseEvent) => e.stopPropagation()
  const primary = () => {
    if (p.onPrimary) return p.onPrimary()
    if (facts.kind === 'podcast') playOrToggle(item, feedTitle)
    else p.onOpen({ stopPropagation() {}, preventDefault() {} } as MouseEvent)
  }
  const primaryLabel = p.primaryLabel ?? (facts.kind === 'podcast' && playingHere ? 'Pause' : facts.primaryLabel)
  const primaryIcon: IconName = p.primaryIcon ?? (primaryLabel === 'Pause' ? 'pause' : facts.kind === 'article' ? 'article' : 'play')
  const setRead = (read: boolean) => (p.onSetRead ? p.onSetRead(read) : void setItemRead(item, read))
  return (
    <div
      {...p.rowProps}
      className={`row ir${item.read ? ' read' : ''}${p.selected ? ' selected' : ''}${p.picked ? ' picked' : ''}${playingHere ? ' playing' : ''}${p.className ? ` ${p.className}` : ''}`}
      onClick={p.onOpen}
      onContextMenu={p.onContextMenu}
    >
      <div className="ir-top">
      {p.leading}
      <button type="button" className="ir-thumb-btn" tabIndex={-1} onClick={(e) => (stop(e), p.onOpen(e))} aria-label={`Open ${item.title}`}>
        <ItemThumb item={item} facts={facts} overlay={p.thumbOverlay} />
      </button>
      <div className="ir-main row-main">
        <button type="button" className="row-title ir-title" onClick={(e) => (stop(e), p.onOpen(e))}>
          {item.title || '(no title)'}
        </button>
        <div className="row-meta">
          <TypeBadge kind={facts.kind} label={facts.kindLabel} />
          {isShared(item) ? (
            <span className="row-feed shared" title="Added by sharing a link">
              {siteOf(item.link) || 'Shared link'}
            </span>
          ) : p.onOpenFeed ? (
            <button type="button" className="row-feed" title="Open this feed's page: stats and settings" onClick={(e) => (stop(e), p.onOpenFeed!(item.feedUrl))}>
              {feedTitle}
            </button>
          ) : (
            <span className="row-feed">{feedTitle}</span>
          )}
          <span>{relativeTime(item.sortTime)}</span>
          {facts.lengthText ? <span>{facts.lengthText}</span> : null}
          {item.offlineAt || item.offlineVideo ? (
            <span className="row-offline" title={item.offlineVideo ? 'Saved for offline watching' : 'Saved for offline reading'}>
              <Icon name="download" size={14} /> Offline
            </span>
          ) : null}
          {p.metaExtra}
        </div>
        {p.snippetLines && item.snippet ? (
          <div className="row-snippet" style={{ WebkitLineClamp: p.snippetLines }}>
            {item.snippet}
          </div>
        ) : null}
      </div>
      </div>
        <div className="ir-acts" onClick={stop} onPointerDown={stop}>
          <button type="button" className="mf-btn ir-btn" onClick={primary}>
            <Icon name={primaryIcon} size={16} />
            {primaryLabel}
          </button>
          <button type="button" className={`mf-btn ir-btn${item.saved ? ' on' : ''}`} aria-pressed={!!item.saved} onClick={() => void toggleSaved(item)}>
            <Icon name={item.saved ? 'bookmarkFilled' : 'bookmark'} size={16} />
            {item.saved ? 'Saved' : 'Save'}
          </button>
          <button type="button" className={`mf-btn ir-btn${p.inPlaylist ? ' on' : ''}`} title="Add to a playlist, or play next" onClick={(e) => openAddToPlaylist([item.id], item.title, e.currentTarget)}>
            <Icon name="playlistAdd" size={16} />
            {p.inPlaylist ? 'In playlist' : 'Playlist'}
          </button>
          <button type="button" className="mf-btn ir-btn" onClick={() => setRead(!item.read)}>
            <Icon name={item.read ? 'unread' : 'read'} size={16} />
            {facts.readLabel}
          </button>
          <button type="button" className="mf-btn danger ir-btn" title="Delete on all your devices" onClick={p.onDelete}>
            <Icon name="delete" size={16} />
            Delete
          </button>
          {p.extras}
        </div>
    </div>
  )
}
