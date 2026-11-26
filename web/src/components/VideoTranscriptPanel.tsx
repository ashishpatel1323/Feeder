/**
 * The YouTube video's transcript under its chapter headings (option B, same as Android's
 * full screen panel): other chapters folded to one line so the list reads like a chapter list,
 * the chapter playing open and following playback, its heading stuck to the top. No chapters =
 * 5-minute blocks. Tap a line or a folded chapter's time to jump there.
 */
import { useEffect, useMemo, useRef, useState } from 'react'
import { cueIndexAt } from '../lib/transcriptFormats'
import { descriptionChapters, sectionIndexAt, transcriptSections } from '../lib/transcriptSections'
import { useVideoTranscript } from '../lib/youtubeTranscript'
import { Icon } from './Icon'
import type { YTPlayer } from './YouTubeFrame'

const FOLLOW_AGAIN_AFTER_MS = 5000

export function formatCueTime(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000))
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = String(total % 60).padStart(2, '0')
  return h ? `${h}:${String(m).padStart(2, '0')}:${s}` : `${m}:${s}`
}

export function VideoTranscriptPanel({ videoId, description, player }: { videoId: string; description: string | null; player: YTPlayer | null }) {
  const t = useVideoTranscript(videoId, true)
  const chapters = useMemo(() => descriptionChapters(description), [description])
  const sections = useMemo(() => transcriptSections(t.cues, chapters), [t.cues, chapters])
  const [positionMs, setPositionMs] = useState(0)
  useEffect(() => {
    if (!player) return
    const tick = window.setInterval(() => {
      try {
        setPositionMs(Math.round(player.getCurrentTime() * 1000))
      } catch {
        // player gone
      }
    }, 250)
    return () => clearInterval(tick)
  }, [player])
  const seek = (ms: number) => {
    try {
      player?.seekTo(ms / 1000, true)
      player?.playVideo()
      setPositionMs(ms)
    } catch {
      // player gone
    }
  }

  const current = cueIndexAt(t.cues, positionMs)
  const currentSection = Math.max(0, sectionIndexAt(sections, positionMs))
  const [open, setOpen] = useState<Set<number>>(new Set())
  useEffect(() => setOpen(new Set()), [videoId])
  useEffect(() => {
    setOpen((o) => (o.has(currentSection) ? o : new Set(o).add(currentSection)))
  }, [currentSection])

  const list = useRef<HTMLDivElement>(null)
  const [following, setFollowing] = useState(true)
  const handAt = useRef(0)
  const onHandScroll = () => {
    handAt.current = Date.now()
    setFollowing(false)
  }
  useEffect(() => {
    if (following) return
    const id = window.setTimeout(() => setFollowing(true), FOLLOW_AGAIN_AFTER_MS)
    return () => clearTimeout(id)
  }, [following, positionMs])
  const scrollToNow = (smooth: boolean) => {
    const box = list.current
    if (!box) return
    const line = box.querySelector<HTMLElement>('[data-now="1"]') ?? box.querySelector<HTMLElement>('[data-playing-heading="1"]')
    if (!line) return
    // Under the stuck heading, with one past line still in view
    const heading = box.querySelector<HTMLElement>('.vtp-heading.playing')
    const offset = (line.dataset.now ? (heading?.offsetHeight ?? 0) : 0) + 44
    box.scrollTo({ top: Math.max(0, line.offsetTop - offset), behavior: smooth ? 'smooth' : 'auto' })
  }
  useEffect(() => {
    if (following) scrollToNow(true)
  }, [current, currentSection, following, open])

  let body
  if (t.status === 'loading') body = <p className="transcript-status">Getting the captions…</p>
  else if (t.status === 'unavailable') body = <p className="transcript-status">Transcripts of YouTube videos need the Feeder Mac app, which gets the captions with yt-dlp.</p>
  else if (t.status === 'none') body = <p className="transcript-status">This video has no captions.</p>
  else if (t.status === 'failed') body = <p className="transcript-status">Couldn't get the captions: {t.error}</p>
  else if (!sections.length) {
    body = (
      <ul className="transcript-lines vtp-lines">
        {t.cues.map((c, i) => (
          <Line key={i} index={i} current={current} startMs={c.startMs} text={c.text} onSeek={seek} />
        ))}
      </ul>
    )
  } else {
    body = sections.map((s, si) => {
      const isOpen = open.has(si)
      const empty = s.endCue <= s.firstCue
      return (
        <section key={si} className="vtp-section">
          <button
            type="button"
            className={`vtp-heading${si === currentSection ? ' playing' : ''}`}
            data-playing-heading={si === currentSection ? '1' : undefined}
            aria-expanded={empty ? undefined : isOpen}
            onClick={() => {
              if (empty) return seek(s.startMs)
              setOpen((o) => {
                const n = new Set(o)
                if (n.has(si)) n.delete(si)
                else n.add(si)
                return n
              })
            }}
          >
            <span className="transcript-time vtp-heading-time">{formatCueTime(s.startMs)}</span>
            <span className="vtp-heading-title">{s.title}</span>
            {empty ? null : <Icon name={isOpen ? 'collapse' : 'expand'} size={20} />}
          </button>
          {isOpen ? (
            <ul className="transcript-lines vtp-lines">
              {t.cues.slice(s.firstCue, s.endCue).map((c, k) => (
                <Line key={s.firstCue + k} index={s.firstCue + k} current={current} startMs={c.startMs} text={c.text} onSeek={seek} />
              ))}
            </ul>
          ) : null}
        </section>
      )
    })
  }

  return (
    <div className="vtp" aria-label="Transcript">
      <div className="vtp-scroll" ref={list} onWheel={onHandScroll} onTouchMove={onHandScroll}>
        {body}
      </div>
      {!following && t.status === 'ready' ? (
        <button
          type="button"
          className="mf-btn primary vtp-now"
          onClick={() => {
            setFollowing(true)
            scrollToNow(true)
          }}
        >
          <Icon name="myLocation" size={18} /> Now playing
        </button>
      ) : null}
    </div>
  )
}

function Line({ index, current, startMs, text, onSeek }: { index: number; current: number; startMs: number; text: string; onSeek: (ms: number) => void }) {
  const now = index === current
  return (
    <li className={now ? 'now' : index < current ? 'past' : undefined} data-now={now ? '1' : undefined}>
      <button type="button" onClick={() => onSeek(startMs)}>
        <span className="transcript-time">{formatCueTime(startMs)}</span>
        <span className="transcript-text">{text}</span>
      </button>
    </li>
  )
}
