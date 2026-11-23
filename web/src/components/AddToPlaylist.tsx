import { useEffect, useRef, useState } from 'react'
import { useLiveQuery } from 'dexie-react-hooks'
import { db } from '../lib/db'
import { addToPlaylist, loadPlaylists, newPlaylist, removeFromPlaylist, savePlaylist, PLAYLISTS_KEY } from '../lib/playlists'
import { playQueue } from '../lib/playqueue'
import { Icon } from './Icon'

interface Target {
  ids: string[]
  title: string
  x: number
  y: number
}

let current: Target | null = null
const subs = new Set<(t: Target | null) => void>()
function setTarget(t: Target | null) {
  current = t
  subs.forEach((f) => f(t))
}

/** Opens the "Add to playlist" menu for one or more items, next to the button that was clicked. */
export function openAddToPlaylist(ids: string[], title: string, anchor: Element | { x: number; y: number }) {
  const r = anchor instanceof Element ? anchor.getBoundingClientRect() : null
  setTarget({ ids, title, x: r ? r.left : (anchor as { x: number }).x, y: r ? r.bottom + 4 : (anchor as { y: number }).y })
}

/** The visible "+ Playlist" button for list rows (a span, since rows are buttons themselves). */
export function AddToPlaylistChip({ id, title, inPlaylist }: { id: string; title: string; inPlaylist?: boolean }) {
  return (
    <span
      className={`pl-chip${inPlaylist ? ' in' : ''}`}
      role="button"
      tabIndex={0}
      title="Add to a playlist, or play next"
      onClick={(e) => {
        e.stopPropagation()
        e.preventDefault()
        openAddToPlaylist([id], title, e.currentTarget)
      }}
      onKeyDown={(e) => {
        if (e.key !== 'Enter' && e.key !== ' ') return
        e.stopPropagation()
        e.preventDefault()
        openAddToPlaylist([id], title, e.currentTarget)
      }}
      onPointerDown={(e) => e.stopPropagation()}
    >
      <Icon name="playlistAdd" size={15} />
      Playlist
    </span>
  )
}

/** All playlist ids that hold each item, for the "in a playlist" look of the chip. */
export function useManualMembership(): Set<string> {
  const lists = useLiveQuery(() => db.kv.get(PLAYLISTS_KEY), []) as { value?: { smart: boolean; itemIds: string[] }[] } | undefined
  const out = new Set<string>()
  for (const p of lists?.value ?? []) if (!p.smart) for (const id of p.itemIds ?? []) out.add(id)
  return out
}

/** The menu itself, rendered once by the app. */
export function AddToPlaylistMenu({ toast }: { toast: (msg: string, action?: { label: string; run: () => void }) => void }) {
  const [t, setT] = useState<Target | null>(current)
  useEffect(() => {
    subs.add(setT)
    return () => void subs.delete(setT)
  }, [])
  const ref = useRef<HTMLDivElement>(null)
  const lists = useLiveQuery(() => loadPlaylists(), []) ?? []
  const [naming, setNaming] = useState<string | null>(null)

  useEffect(() => {
    if (!t) return
    setNaming(null)
    const onDown = (e: PointerEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setTarget(null)
    }
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setTarget(null)
    window.addEventListener('pointerdown', onDown)
    window.addEventListener('keydown', onKey)
    return () => {
      window.removeEventListener('pointerdown', onDown)
      window.removeEventListener('keydown', onKey)
    }
  }, [t])

  if (!t) return null
  const manual = lists.filter((p) => !p.smart)
  const width = 320
  const left = Math.max(8, Math.min(t.x, window.innerWidth - width - 8))
  const top = Math.min(t.y, window.innerHeight - 120 - (manual.length + 4) * 44)
  const short = t.title.length > 50 ? `${t.title.slice(0, 48)}…` : t.title
  const one = t.ids.length === 1

  const toggle = async (id: string, has: boolean) => {
    if (has) {
      await removeFromPlaylist(id, t.ids)
      toast('Taken out of the playlist')
    } else {
      await addToPlaylist(id, t.ids)
      const name = lists.find((p) => p.id === id)?.name ?? 'playlist'
      toast(`Added to ${name}`, { label: 'Undo', run: () => void removeFromPlaylist(id, t.ids) })
    }
  }

  return (
    <div className="pl-menu" ref={ref} role="dialog" aria-label="Add to playlist" style={{ left, top: Math.max(8, top), width }}>
      <div className="pl-menu-head">Add “{short}”</div>
      <button
        className="pl-menu-row"
        onClick={() => {
          t.ids.slice().reverse().forEach((id) => playQueue.playNext(id))
          toast(playQueue.active ? 'Plays next' : 'Playing')
          setTarget(null)
        }}
      >
        <Icon name="skipNext" size={20} />
        <span>
          Play next<small>After the item playing now</small>
        </span>
      </button>
      <button
        className="pl-menu-row"
        onClick={() => {
          t.ids.forEach((id) => playQueue.playLast(id))
          toast('Added to the end of the queue')
          setTarget(null)
        }}
      >
        <Icon name="queue" size={20} />
        <span>
          Play last<small>At the end of the queue</small>
        </span>
      </button>
      <div className="pl-menu-sep">My playlists</div>
      {manual.map((pl) => {
        const has = t.ids.every((id) => pl.itemIds.includes(id))
        return (
          <button key={pl.id} className="pl-menu-row" onClick={() => void toggle(pl.id, has)} aria-pressed={has}>
            <span className="pl-dot" style={{ background: pl.color }}>
              {pl.icon}
            </span>
            <span>
              {pl.name}
              <small>{pl.itemIds.length} items</small>
            </span>
            <span className={`pl-check${has ? ' on' : ''}`}>{has ? <Icon name="check" size={16} /> : null}</span>
          </button>
        )
      })}
      {naming === null ? (
        <button className="pl-menu-row accent" onClick={() => setNaming('')}>
          <Icon name="add" size={20} />
          <span>New playlist{one ? ' with this' : ` with these ${t.ids.length}`}</span>
        </button>
      ) : (
        <form
          className="pl-menu-new"
          onSubmit={async (e) => {
            e.preventDefault()
            const name = naming.trim() || 'My playlist'
            const pl = newPlaylist({ name, smart: false, itemIds: [...t.ids] }, lists)
            await savePlaylist(pl)
            toast(`Added to new playlist ${name}`)
            setTarget(null)
          }}
        >
          <input autoFocus placeholder="Playlist name" value={naming} onChange={(e) => setNaming(e.target.value)} aria-label="New playlist name" />
          <button className="btn pri" type="submit">
            Create
          </button>
        </form>
      )}
      {!manual.length && naming === null ? <p className="pl-menu-note">Smart playlists add items by their rules, so they aren’t listed here.</p> : null}
    </div>
  )
}
