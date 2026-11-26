/**
 * The Shorts shelf in full screen, like YouTube Shorts (and the Android ShortsPlayer.kt): one
 * Short at a time, upright, playing at once and looping. Swipe, scroll the wheel, or press ↑/↓
 * (or K/J) for the previous or next one; tap or Space pauses; Esc closes. The bin at the bottom
 * left deletes the Short through Feeds' delete, which for one article has Undo instead of asking.
 */
import { useEffect, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import type { Item } from '../lib/db'
import { thumbUrl, type VideoRow } from '../lib/youtubeTab'
import { Icon, IconButton } from './Icon'
import { YouTubeFrame, type YTPlayer } from './YouTubeFrame'

/** Watching this long marks a Short watched, as opening a video does. */
const MARK_WATCHED_MS = 2000
/** A swipe this far (px) moves to the next or previous Short. */
const SWIPE_PX = 60
/** One wheel gesture moves one Short, however many wheel events it sends. */
const WHEEL_GAP_MS = 450
const PLAYING = 1

export function ShortsPlayer(p: {
  /** The shelf as it is now: deleted Shorts drop out, an Undo brings them back in place */
  live: VideoRow[]
  startId: string
  onClose: () => void
  onSetRead: (items: Item[], read: boolean) => void
  onDelete: (items: Item[]) => void
}) {
  // The order when it opened, so Shorts found meanwhile don't move the one playing
  const [order] = useState(() => p.live.map((r) => r.item.id))
  const rows = useMemo(() => {
    const byId = new Map(p.live.map((r) => [r.item.id, r]))
    return order.map((id) => byId.get(id)).filter((r): r is VideoRow => !!r)
  }, [order, p.live])

  const [index, setIndex] = useState(() => Math.max(0, order.indexOf(p.startId)))
  const current = useRef(p.startId)
  // When the one playing is deleted, the next one takes its place; after an Undo it stays put
  useEffect(() => {
    const at = rows.findIndex((r) => r.item.id === current.current)
    if (at >= 0) {
      if (at !== index) setIndex(at)
    } else if (rows.length) {
      setIndex((i) => Math.min(i, rows.length - 1))
    }
  }, [rows]) // eslint-disable-line react-hooks/exhaustive-deps
  const row = rows[Math.min(index, rows.length - 1)]
  if (row) current.current = row.item.id

  const closeRef = useRef(p.onClose)
  closeRef.current = p.onClose
  useEffect(() => {
    if (!rows.length) closeRef.current()
  }, [rows.length])

  // The next Short slides in from below, the previous one from above (one player stays in place)
  const stage = useRef<HTMLDivElement>(null)
  const go = (step: number) => {
    const next = Math.min(Math.max(index + step, 0), rows.length - 1)
    if (next === index) return
    setIndex(next)
    stage.current?.animate?.([{ transform: `translateY(${step > 0 ? 30 : -30}%)`, opacity: 0.3 }, { transform: 'none', opacity: 1 }], { duration: 220, easing: 'ease-out' })
  }

  const player = useRef<YTPlayer | null>(null)
  const [paused, setPaused] = useState(false)
  const togglePlay = () => {
    const pl = player.current
    if (!pl) return
    try {
      if (pl.getPlayerState() === PLAYING) {
        pl.pauseVideo()
        setPaused(true)
      } else {
        pl.playVideo()
        setPaused(false)
      }
    } catch {
      // player gone
    }
  }
  useEffect(() => setPaused(false), [row?.item.id])

  // Watched after a moment on screen
  const setRead = useRef(p.onSetRead)
  setRead.current = p.onSetRead
  useEffect(() => {
    if (!row || row.item.read) return
    const t = window.setTimeout(() => setRead.current([row.item], true), MARK_WATCHED_MS)
    return () => clearTimeout(t)
  }, [row?.item.id]) // eslint-disable-line react-hooks/exhaustive-deps

  // Progress line at the bottom; the thumbnail shows until it plays
  const [progress, setProgress] = useState(0)
  const [started, setStarted] = useState(false)
  useEffect(() => {
    setProgress(0)
    setStarted(false)
    const t = window.setInterval(() => {
      const pl = player.current
      if (!pl) return
      try {
        if (pl.getPlayerState() === PLAYING) setStarted(true)
        const d = pl.getDuration()
        setProgress(d > 0 ? Math.min(1, pl.getCurrentTime() / d) : 0)
      } catch {
        // player gone
      }
    }, 250)
    return () => clearInterval(t)
  }, [row?.item.id])

  // Keys: ↑/K previous, ↓/J next, Space pause, Esc close (before the app's own shortcuts)
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.metaKey || e.ctrlKey || e.altKey) return
      if (e.key === 'ArrowDown' || e.key === 'j') go(1)
      else if (e.key === 'ArrowUp' || e.key === 'k') go(-1)
      else if (e.key === ' ' || e.key === 'Enter') togglePlay()
      else if (e.key === 'Escape') p.onClose()
      else return
      e.preventDefault()
      e.stopPropagation()
    }
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  })

  // The page under it doesn't scroll while it is open
  useEffect(() => {
    const prev = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.body.style.overflow = prev
    }
  }, [])

  const lastWheel = useRef(0)
  const onWheel = (e: React.WheelEvent) => {
    if (Math.abs(e.deltaY) < 8) return
    const now = Date.now()
    const fresh = now - lastWheel.current > WHEEL_GAP_MS
    lastWheel.current = now
    if (fresh) go(e.deltaY > 0 ? 1 : -1)
  }
  const touch = useRef<{ y: number; moved: boolean } | null>(null)
  const [drag, setDrag] = useState(0)

  if (!row) return null
  // On the page itself: a transformed parent would make position: fixed relative to it
  return createPortal(
    <div className="shorts-player" role="dialog" aria-modal="true" aria-label="Shorts" onWheel={onWheel}>
      <div className="shorts-top">
        <IconButton icon="back" label="Close Shorts (Esc)" onClick={p.onClose} />
        <span className="shorts-title">Shorts</span>
        <span className="shorts-count">
          {index + 1} / {rows.length}
        </span>
      </div>
      <div className="shorts-stage" ref={stage} style={drag ? { transform: `translateY(${drag}px)` } : undefined}>
        <YouTubeFrame
          itemId={row.item.id}
          videoId={row.videoId}
          title={row.item.title}
          autoplay
          fromStart
          onPlayer={(pl) => (player.current = pl)}
          onEnded={() => {
            // Loops, like YouTube Shorts
            try {
              player.current?.seekTo(0, true)
              player.current?.playVideo()
            } catch {
              // player gone
            }
          }}
        />
        {/* The picture until the video plays */}
        {started ? null : <img className="shorts-thumb" src={thumbUrl(row.videoId, true)} alt="" referrerPolicy="no-referrer" />}
        {/* Over the video: taps pause, swipes move; YouTube's own controls stay out of the way */}
        <div
          className="shorts-touch"
          aria-hidden="true"
          onClick={() => {
            if (touch.current?.moved) return
            togglePlay()
          }}
          onTouchStart={(e) => {
            touch.current = { y: e.touches[0].clientY, moved: false }
          }}
          onTouchMove={(e) => {
            const t = touch.current
            if (!t) return
            const dy = e.touches[0].clientY - t.y
            if (Math.abs(dy) > 10) t.moved = true
            setDrag(dy)
          }}
          onTouchEnd={() => {
            const d = drag
            setDrag(0)
            if (d < -SWIPE_PX) go(1)
            else if (d > SWIPE_PX) go(-1)
            window.setTimeout(() => (touch.current = null), 0)
          }}
        />
        {paused ? (
          <span className="shorts-paused" aria-hidden="true">
            <Icon name="play" size={72} />
          </span>
        ) : null}
        <div className="shorts-info">
          <span className="shorts-channel">{row.channel}</span>
          <span className="shorts-video-title">{row.item.title}</span>
          <button type="button" className="shorts-delete" title="Delete" aria-label={`Delete ${row.item.title}`} onClick={() => p.onDelete([row.item])}>
            <Icon name="delete" size={20} />
          </button>
        </div>
        <div className="shorts-progress" aria-hidden="true">
          <span style={{ width: `${progress * 100}%` }} />
        </div>
      </div>
      <div className="shorts-nav">
        <IconButton icon="arrowUp" label="Previous Short (↑)" disabled={index === 0} onClick={() => go(-1)} />
        <IconButton icon="arrowDown" label="Next Short (↓)" disabled={index >= rows.length - 1} onClick={() => go(1)} />
      </div>
    </div>,
    document.body,
  )
}
