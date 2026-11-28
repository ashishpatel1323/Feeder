import { useMemo, useState } from 'react'
import { useLiveQuery } from 'dexie-react-hooks'
import { proxiedUrl } from '../lib/api'
import { feedDisplayTitle, type Feed, type Item, type RecentEntry } from '../lib/db'
import { kindOf, playlistItems, type Playlist } from '../lib/playlists'
import { recentEntries } from '../lib/recent'
import { IconButton } from './Icon'
import { usePlaylistContext } from './ItemRow'
import { usePlaylists } from './Playlists'

const OPENABLE = new Set<RecentEntry['t']>(['article', 'podcast', 'youtube', 'audiobook', 'ebook', 'zim', 'media'])
const JUMP_BACK = 6
const SHELF = 12

const img = (url: string | null | undefined) => (url ? (url.startsWith('http://') ? proxiedUrl(url) : url) : null)

/**
 * Home (redesign, YouTube Music style): "Jump back in" with what you had open on any device, then
 * one shelf per playlist with its next items. A shelf's title opens the whole playlist.
 */
export function HomeShelves(p: {
  items: Item[]
  feeds: Map<string, Feed>
  readRate: number
  onOpenRecent: (e: RecentEntry) => void
  onOpenItem: (id: string) => void
  onOpenPlaylist: (id: string) => void
  onSettings: () => void
  onMenu: () => void
}) {
  const entries = useLiveQuery(recentEntries, []) ?? []
  const playlists = usePlaylists()
  const ctx = usePlaylistContext(p.feeds, p.readRate)
  const byId = useMemo(() => new Map(p.items.map((i) => [i.id, i])), [p.items])
  const recent = useMemo(() => {
    const seen = new Set<string>()
    return entries.filter((e) => e.a === 'open' && OPENABLE.has(e.t) && !e.d && !seen.has(e.key) && seen.add(e.key)).slice(0, JUMP_BACK)
  }, [entries])
  const shelves = useMemo(
    () => playlists.map((pl) => [pl, playlistItems(pl, p.items, ctx, byId).slice(0, SHELF)] as [Playlist, Item[]]).filter(([, its]) => its.length > 0),
    [playlists, p.items, ctx, byId],
  )

  return (
    <section className="manage-pane shelves" aria-label="Home">
      <div className="mf-page">
      <div className="mf-head">
        <span className="only-narrow">
          <IconButton icon="menu" label="Feeds" onClick={p.onMenu} />
        </span>
        <h1>Home</h1>
        <span className="mf-sp" />
        <IconButton icon="settings" label="Settings" onClick={p.onSettings} />
      </div>
      {recent.length ? (
        <div className="shelf">
          <h2 className="shelf-title">Jump back in</h2>
          <div className="jump-grid">
            {recent.map((e) => (
              <button key={e.key} type="button" className="jump-tile" onClick={() => p.onOpenRecent(e)}>
                <Art src={img(e.img)} />
                <span className="jump-title">{e.n || e.s}</span>
                {e.f && e.f > 0 ? (
                  <span className="jump-prog" aria-hidden="true">
                    <b style={{ width: `${Math.min(100, e.f * 100)}%` }} />
                  </span>
                ) : null}
              </button>
            ))}
          </div>
        </div>
      ) : null}
      {shelves.map(([pl, its]) => (
        <div className="shelf" key={pl.id}>
          <div className="shelf-head">
            <h2 className="shelf-title">{pl.name}</h2>
            <button type="button" className="mf-btn ghost" onClick={() => p.onOpenPlaylist(pl.id)}>
              Show all
            </button>
          </div>
          <div className="shelf-row">
            {its.map((it) => {
              const video = kindOf(it, ctx) === 'youtube'
              return (
                <button key={it.id} type="button" className={`shelf-tile${video ? ' wide' : ''}`} onClick={() => p.onOpenItem(it.id)}>
                  <Art src={img(it.imageUrl) ?? img(p.feeds.get(it.feedUrl)?.imageUrl)} />
                  <span className="shelf-tile-title">{it.title}</span>
                  <span className="shelf-tile-sub">{feedDisplayTitle(p.feeds.get(it.feedUrl))}</span>
                </button>
              )
            })}
          </div>
        </div>
      ))}
      {!recent.length && !shelves.length ? <p className="muted shelves-empty">Things you open, and your playlists, show up here.</p> : null}
      </div>
    </section>
  )
}

function Art({ src }: { src: string | null }) {
  const [broken, setBroken] = useState(false)
  return <span className="shelf-art">{src && !broken ? <img src={src} alt="" loading="lazy" referrerPolicy="no-referrer" onError={() => setBroken(true)} /> : null}</span>
}
