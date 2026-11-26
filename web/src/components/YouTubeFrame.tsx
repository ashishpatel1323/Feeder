import { useEffect, useRef, useState } from 'react'
import { db } from '../lib/db'
import { syncNow } from '../lib/sync2'
import { resumeAt, saveVideoPos } from '../lib/videopos'
import { youtubeEmbedUrl } from '../lib/youtube'

/** The bits of YouTube's IFrame Player API used here. */
export interface YTPlayer {
  getCurrentTime(): number
  seekTo(seconds: number, allowSeekAhead: boolean): void
  playVideo(): void
  getDuration(): number
  getPlayerState(): number
  loadVideoById(opts: { videoId: string; startSeconds?: number }): void
  cueVideoById(opts: { videoId: string; startSeconds?: number }): void
}
interface YTNamespace {
  Player: new (
    el: HTMLIFrameElement,
    opts: { events: Record<string, (e: { data: number; target: YTPlayer }) => void> },
  ) => YTPlayer
}
declare global {
  interface Window {
    YT?: YTNamespace
    onYouTubeIframeAPIReady?: () => void
  }
}

const PLAYING = 1
const ENDED = 0
const PAUSED = 2
/** How often the position is written down while playing. */
const SAVE_EVERY_MS = 5000

let apiLoading: Promise<YTNamespace> | null = null
function loadApi(): Promise<YTNamespace> {
  if (window.YT?.Player) return Promise.resolve(window.YT)
  apiLoading ??= new Promise((resolve, reject) => {
    const prev = window.onYouTubeIframeAPIReady
    window.onYouTubeIframeAPIReady = () => {
      prev?.()
      resolve(window.YT!)
    }
    const s = document.createElement('script')
    s.src = 'https://www.youtube.com/iframe_api'
    s.async = true
    s.onerror = () => {
      apiLoading = null
      reject(new Error('YouTube player API did not load'))
    }
    document.head.appendChild(s)
  })
  return apiLoading
}

/**
 * Gets YouTube's player script and connections ready before the first video opens (the YouTube
 * tab calls it when it shows, a row on hover or touch): the video then starts sooner.
 */
export function preloadYouTubePlayer(): void {
  void loadApi().catch(() => undefined)
}

async function startFor(itemId: string | null): Promise<number> {
  if (!itemId) return 0
  try {
    return resumeAt(await db.videoPos.get(itemId))
  } catch {
    return 0
  }
}

/**
 * YouTube's own player for one article's video. Remembers where it was left (per article,
 * synced) and starts there next time, a few seconds back.
 *
 * A new [videoId] plays in the same player (loadVideoById), without a new iframe, so the next
 * video starts sooner. Reader and Flip give it key={itemId} and get a fresh player per article.
 */
export function YouTubeFrame({
  itemId,
  videoId,
  title,
  onEnded,
  autoplay,
  onPlayer,
}: {
  /** The article the video belongs to; null (a related video) keeps no position */
  itemId: string | null
  videoId: string
  title: string
  onEnded?: () => void
  autoplay?: boolean
  /** The ready player (null when it goes), for a transcript to follow and move it */
  onPlayer?: (player: YTPlayer | null) => void
}) {
  const onPlayerRef = useRef(onPlayer)
  onPlayerRef.current = onPlayer
  const endedRef = useRef(onEnded)
  endedRef.current = onEnded
  // The first video's start goes in the embed URL, so wait for it before showing the player
  const [initial, setInitial] = useState<{ videoId: string; start: number } | null>(null)
  const frame = useRef<HTMLIFrameElement>(null)
  const playerRef = useRef<YTPlayer | null>(null)
  /** A video asked for before the player was ready: it plays from onReady. */
  const queued = useRef<{ itemId: string | null; videoId: string; start: number } | null>(null)
  /** The video in the player now and its article: positions are saved for it. */
  const shown = useRef<{ itemId: string | null; videoId: string } | null>(null)
  /** Last position seen while it played here; nothing is saved until it has played. */
  const last = useRef<{ t: number; d: number } | null>(null)
  const lastSaved = useRef(0)
  const autoplayRef = useRef(autoplay)
  autoplayRef.current = autoplay

  /** Writes the position down; when it stopped (pause, end, leaving) also sends it to other devices. */
  const save = useRef((send = false) => {
    lastSaved.current = Date.now()
    const id = shown.current?.itemId
    const pos = last.current
    if (!pos || !id) return
    const done = saveVideoPos(id, pos.t, pos.d)
    if (send) void done.then(() => syncNow()).catch(() => undefined)
  }).current

  /** Plays [t] in the ready player; positions count for it only once it is in. */
  const switchTo = useRef((player: YTPlayer, t: { itemId: string | null; videoId: string; start: number }) => {
    try {
      const opts = { videoId: t.videoId, startSeconds: t.start }
      if (autoplayRef.current) player.loadVideoById(opts)
      else player.cueVideoById(opts)
    } catch (e) {
      console.warn(e)
      return
    }
    // Where the previous video stopped
    save(true)
    last.current = null
    shown.current = { itemId: t.itemId, videoId: t.videoId }
  }).current

  // Another video (or article): the same player plays it
  useEffect(() => {
    let live = true
    // The player script loads while the saved position is read
    preloadYouTubePlayer()
    void startFor(itemId).then((start) => {
      if (!live) return
      const before = shown.current
      if (before && before.videoId === videoId && before.itemId === itemId) {
        // Back to the video already in the player: a switch queued meanwhile no longer applies
        queued.current = null
        return
      }
      if (!before) {
        shown.current = { itemId, videoId }
        setInitial({ videoId, start })
        return
      }
      const player = playerRef.current
      if (player) {
        switchTo(player, { itemId, videoId, start })
      } else {
        // The player is not ready yet: it plays the latest one asked for once it is
        queued.current = { itemId, videoId, start }
      }
    })
    return () => {
      live = false
    }
  }, [itemId, videoId, save])

  const ready = initial !== null
  useEffect(() => {
    const el = frame.current
    if (!ready || !el) return
    let stopped = false

    const read = () => {
      const player = playerRef.current
      if (!player) return
      try {
        if (player.getPlayerState() !== PLAYING) return
        last.current = { t: player.getCurrentTime(), d: player.getDuration() }
      } catch {
        // player gone
      }
    }
    const stop = () => save(true)
    const tick = window.setInterval(() => {
      read()
      if (last.current && Date.now() - lastSaved.current >= SAVE_EVERY_MS) save()
    }, 1000)
    const onHide = () => document.visibilityState === 'hidden' && stop()
    document.addEventListener('visibilitychange', onHide)
    window.addEventListener('pagehide', stop)

    loadApi()
      .then((YT) => {
        if (stopped) return
        new YT.Player(el, {
          events: {
            onReady: (e) => {
              if (stopped) return
              playerRef.current = e.target
              onPlayerRef.current?.(e.target)
              const next = queued.current
              queued.current = null
              if (next) switchTo(e.target, next)
            },
            onStateChange: (e) => {
              if (e.data !== PAUSED && e.data !== ENDED) return void read()
              // Paused or ended: write down where it stopped and send it
              try {
                const d = e.target.getDuration()
                if (e.data === ENDED) last.current = { t: d, d }
                else if (last.current) last.current = { t: e.target.getCurrentTime(), d }
              } catch {
                // keep the last position seen
              }
              stop()
              if (e.data === ENDED) endedRef.current?.()
            },
          },
        })
      })
      .catch((e) => console.warn(e))

    return () => {
      stopped = true
      playerRef.current = null
      onPlayerRef.current?.(null)
      clearInterval(tick)
      document.removeEventListener('visibilitychange', onHide)
      window.removeEventListener('pagehide', stop)
      stop()
      // No player.destroy(): it removes the iframe, which React owns
    }
  }, [ready, save])

  if (!initial) return <div className="video-frame" />
  return (
    <div className="video-frame">
      {/* youtube.com (not -nocookie) so the browser's YouTube sign-in applies: Premium, history, Watch later.
          The embed needs the referrer, so no no-referrer policy here. */}
      <iframe
        ref={frame}
        src={youtubeEmbedUrl(initial.videoId, { start: initial.start, origin: window.location.origin, autoplay })}
        title={title}
        allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share"
        referrerPolicy="strict-origin-when-cross-origin"
        allowFullScreen
      />
    </div>
  )
}
