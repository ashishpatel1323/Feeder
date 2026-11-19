import { useEffect, useRef, useState } from 'react'
import { db } from '../lib/db'
import { syncNow } from '../lib/sync2'
import { resumeAt, saveVideoPos } from '../lib/videopos'
import { youtubeEmbedUrl } from '../lib/youtube'

/** The bits of YouTube's IFrame Player API used here. */
interface YTPlayer {
  getCurrentTime(): number
  getDuration(): number
  getPlayerState(): number
}
interface YTNamespace {
  Player: new (el: HTMLIFrameElement, opts: { events: Record<string, (e: { data: number; target: YTPlayer }) => void> }) => YTPlayer
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
 * YouTube's own player for one article's video. Remembers where it was left (per article,
 * synced) and starts there next time, a few seconds back. Give it key={itemId}.
 */
export function YouTubeFrame({ itemId, videoId, title }: { itemId: string; videoId: string; title: string }) {
  // The start position goes in the embed URL, so wait for it before showing the player
  const [start, setStart] = useState<number | null>(null)
  const frame = useRef<HTMLIFrameElement>(null)

  useEffect(() => {
    let live = true
    db.videoPos
      .get(itemId)
      .then((p) => live && setStart(resumeAt(p)))
      .catch(() => live && setStart(0))
    return () => {
      live = false
    }
  }, [itemId])

  useEffect(() => {
    const el = frame.current
    if (start === null || !el) return
    let player: YTPlayer | null = null
    let stopped = false
    /** Last position seen while it played here; nothing is saved until it has played. */
    let last: { t: number; d: number } | null = null
    let lastSaved = 0

    const read = () => {
      if (!player) return
      try {
        if (player.getPlayerState() !== PLAYING) return
        last = { t: player.getCurrentTime(), d: player.getDuration() }
      } catch {
        // player gone
      }
    }
    /** Writes the position down; when it stopped (pause, end, leaving) also sends it to other devices. */
    const save = (send = false) => {
      lastSaved = Date.now()
      if (!last) return
      const done = saveVideoPos(itemId, last.t, last.d)
      if (send) void done.then(() => syncNow()).catch(() => undefined)
    }
    const stop = () => save(true)
    const tick = window.setInterval(() => {
      read()
      if (last && Date.now() - lastSaved >= SAVE_EVERY_MS) save()
    }, 1000)
    const onHide = () => document.visibilityState === 'hidden' && stop()
    document.addEventListener('visibilitychange', onHide)
    window.addEventListener('pagehide', stop)

    loadApi()
      .then((YT) => {
        if (stopped) return
        player = new YT.Player(el, {
          events: {
            onStateChange: (e) => {
              if (e.data !== PAUSED && e.data !== ENDED) return void read()
              // Paused or ended: write down where it stopped and send it
              try {
                const d = e.target.getDuration()
                if (e.data === ENDED) last = { t: d, d }
                else if (last) last = { t: e.target.getCurrentTime(), d }
              } catch {
                // keep the last position seen
              }
              stop()
            },
          },
        })
      })
      .catch((e) => console.warn(e))

    return () => {
      stopped = true
      clearInterval(tick)
      document.removeEventListener('visibilitychange', onHide)
      window.removeEventListener('pagehide', stop)
      stop()
      // No player.destroy(): it removes the iframe, which React owns
    }
  }, [itemId, start])

  if (start === null) return <div className="video-frame" />
  return (
    <div className="video-frame">
      {/* youtube.com (not -nocookie) so the browser's YouTube sign-in applies: Premium, history, Watch later.
          The embed needs the referrer, so no no-referrer policy here. */}
      <iframe
        ref={frame}
        src={youtubeEmbedUrl(videoId, { start, origin: window.location.origin })}
        title={title}
        allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share"
        referrerPolicy="strict-origin-when-cross-origin"
        allowFullScreen
      />
    </div>
  )
}
