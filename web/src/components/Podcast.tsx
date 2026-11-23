import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useLiveQuery } from 'dexie-react-hooks'
import { db, type Item } from '../lib/db'
import { playQueue, useQueue } from '../lib/playqueue'
import { RESUME_BACK, saveVideoPos } from '../lib/videopos'
import { cueIndexAt, findMatches, transcriptPlainText, type TranscriptCue, type TranscriptMatch } from '../lib/transcriptFormats'
import { deleteTranscript, loadTranscript, stopTranscribing, transcribe, useTranscript, WHISPER_MODEL_MB, type TranscriptState } from '../lib/transcripts'
import { Icon } from './Icon'

const AUDIO = /\.(mp3|m4a|m4b|aac|ogg|oga|opus|wav|flac)(\?|#|$)/i
const NOT_AUDIO = /\.(jpe?g|png|gif|webp|avif|svg|mp4|m4v|webm|mov|pdf)(\?|#|$)/i

/** True for an article with a podcast episode attached. */
export function hasEpisode(item: Pick<Item, 'enclosureUrl'>): boolean {
  const url = item.enclosureUrl
  return !!url && (AUDIO.test(url) || !NOT_AUDIO.test(url))
}

function formatTime(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000))
  const mm = Math.floor(s / 60) % 60
  const ss = String(s % 60).padStart(2, '0')
  return s >= 3600 ? `${Math.floor(s / 3600)}:${String(mm).padStart(2, '0')}:${ss}` : `${mm}:${ss}`
}

/**
 * A podcast episode in the reader and on a Flip page: plays in Feeder's own bottom player (the
 * same one playlists use), so where you got to, the speed and the lock-screen controls are the
 * same everywhere. The transcript follows playback (Transcript button).
 */
export function PodcastEpisode({ item, feedTitle = '', compact = false }: { item: Item; feedTitle?: string; compact?: boolean }) {
  const q = useQueue()
  const saved = useLiveQuery(() => db.videoPos.get(item.id), [item.id])
  const [showTranscript, setShowTranscript] = useState(false)
  const current = q.ids[q.index] === item.id && q.kind !== null
  const playing = current && (q.status === 'playing' || q.status === 'loading')
  const position = current ? q.position : (saved?.t ?? 0)
  const duration = (current && q.duration) || item.duration || saved?.d || 0
  const started = position > 5 && (!duration || position < duration - 30)

  const play = () => {
    if (current) playQueue.toggle()
    else playQueue.start([item.id], { name: feedTitle || 'Podcast' })
  }
  const seek = (ms: number) => {
    if (current && duration) return playQueue.seekToFraction(Math.min(1, ms / 1000 / duration))
    // Not playing yet: remember the spot and start there (resume goes back a few seconds)
    void saveVideoPos(item.id, ms / 1000 + RESUME_BACK, duration).then(() => playQueue.start([item.id], { name: feedTitle || 'Podcast' }))
  }

  return (
    <section className={`podcast-episode${compact ? ' compact' : ''}`}>
      <div className="pod-card">
        <button type="button" className="mf-btn primary pod-play" onClick={play}>
          <Icon name={playing ? 'pause' : 'play'} size={20} />
          <span>{playing ? 'Pause' : started ? 'Resume' : 'Play'}</span>
        </button>
        <div className="pod-track">
          <div
            className="pod-bar"
            role="slider"
            aria-label="Position"
            aria-valuemin={0}
            aria-valuemax={Math.round(duration)}
            aria-valuenow={Math.round(position)}
            tabIndex={current ? 0 : -1}
            onClick={(e) => {
              if (!duration) return
              const r = e.currentTarget.getBoundingClientRect()
              seek(((e.clientX - r.left) / r.width) * duration * 1000)
            }}
          >
            <i style={{ width: `${duration ? Math.min(100, (position / duration) * 100) : 0}%` }} />
          </div>
          <span className="pod-time">
            {formatTime(position * 1000)}
            {duration ? ` of ${formatTime(duration * 1000)}` : ''}
          </span>
        </div>
        {current ? (
          <>
            <button type="button" className="mf-btn" onClick={() => playQueue.seekBy(-15)} title="Back 15 seconds">
              <Icon name="replay" size={18} />
              <span>15s</span>
            </button>
            <button type="button" className="mf-btn" onClick={() => playQueue.seekBy(30)} title="Forward 30 seconds">
              <Icon name="forward" size={18} />
              <span>30s</span>
            </button>
          </>
        ) : null}
        {compact ? null : (
          <button type="button" className={`mf-btn${showTranscript ? ' on' : ''}`} aria-pressed={showTranscript} onClick={() => setShowTranscript((v) => !v)}>
            <Icon name="closedCaption" size={18} />
            <span>Transcript</span>
          </button>
        )}
      </div>
      {showTranscript ? <TranscriptPanel item={item} positionMs={Math.round(position * 1000)} onSeek={seek} /> : null}
    </section>
  )
}

function TranscriptPanel({ item, positionMs, onSeek }: { item: Item; positionMs: number; onSeek: (ms: number) => void }) {
  const state = useTranscript(item.id)
  const [searchOpen, setSearchOpen] = useState(false)
  const [query, setQuery] = useState('')
  const [active, setActive] = useState(0)
  const matches = useMemo(() => (searchOpen ? findMatches(state.cues, query) : []), [searchOpen, state.cues, query])
  useEffect(() => {
    void loadTranscript(item)
  }, [item])
  // A new search starts at the first match from where the episode is now
  useEffect(() => {
    const now = cueIndexAt(state.cues, positionMs)
    const next = matches.findIndex((m) => m.cue >= now)
    setActive(next >= 0 ? next : 0)
  }, [query])
  const [copied, setCopied] = useState(false)
  const copyAll = async () => {
    const text = transcriptPlainText(state.cues, item.title)
    let ok = false
    try {
      await navigator.clipboard.writeText(text)
      ok = true
    } catch {
      // Some browsers and embedded views refuse the async clipboard: the older copy command still works there
      const area = document.createElement('textarea')
      area.value = text
      area.setAttribute('readonly', '')
      area.style.position = 'fixed'
      area.style.opacity = '0'
      document.body.appendChild(area)
      area.select()
      ok = document.execCommand('copy')
      area.remove()
    }
    if (ok) {
      setCopied(true)
      window.setTimeout(() => setCopied(false), 2000)
    }
  }
  const step = (by: number) => matches.length && setActive((a) => (a + by + matches.length) % matches.length)
  const close = () => {
    setSearchOpen(false)
    setQuery('')
  }
  return (
    <div className="transcript">
      <div className="transcript-head">
        <div className="transcript-head-status">
          <TranscriptStatusRow item={item} state={state} />
        </div>
        {state.cues.length && !searchOpen ? (
          <>
            <button type="button" className="icon-btn transcript-search-btn" title="Search the transcript" aria-label="Search the transcript" onClick={() => setSearchOpen(true)}>
              <Icon name="search" size={20} />
            </button>
            <button
              type="button"
              className="icon-btn transcript-search-btn"
              title={copied ? 'Copied' : 'Copy the whole transcript'}
              aria-label="Copy the whole transcript"
              onClick={() => void copyAll()}
            >
              <Icon name={copied ? 'check' : 'copy'} size={20} />
            </button>
          </>
        ) : null}
      </div>
      {searchOpen ? (
        <div className="transcript-search" role="search">
          <Icon name="search" size={18} />
          <input
            autoFocus
            type="search"
            placeholder="Search the transcript"
            aria-label="Search the transcript"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault()
                step(e.shiftKey ? -1 : 1)
              } else if (e.key === 'Escape') {
                e.stopPropagation()
                close()
              }
              // Typing here must not trigger the reader's keyboard shortcuts
              e.stopPropagation()
            }}
          />
          <span className="transcript-search-count">{query.trim() ? (matches.length ? `${active + 1} of ${matches.length}` : 'No matches') : ''}</span>
          <button type="button" className="icon-btn" title="Previous match (Shift+Enter)" aria-label="Previous match" disabled={!matches.length} onClick={() => step(-1)}>
            <Icon name="arrowUp" size={20} />
          </button>
          <button type="button" className="icon-btn" title="Next match (Enter)" aria-label="Next match" disabled={!matches.length} onClick={() => step(1)}>
            <Icon name="arrowDown" size={20} />
          </button>
          <button type="button" className="icon-btn" title="Close search" aria-label="Close search" onClick={close}>
            <Icon name="close" size={20} />
          </button>
        </div>
      ) : null}
      {state.cues.length ? (
        <TranscriptLines
          cues={state.cues}
          positionMs={positionMs}
          onSeek={onSeek}
          matches={matches}
          activeMatch={matches.length ? active : -1}
          searching={searchOpen && !!query.trim()}
        />
      ) : null}
    </div>
  )
}

/** The cue's text with the search matches marked; the selected one stands out. */
function markedText(text: string, matches: TranscriptMatch[], active: TranscriptMatch | null): ReactNode {
  if (!matches.length) return text
  const parts: ReactNode[] = []
  let at = 0
  matches.forEach((m, i) => {
    if (m.start > at) parts.push(text.slice(at, m.start))
    parts.push(
      <mark key={i} className={m === active ? 'active' : undefined}>
        {text.slice(m.start, m.end)}
      </mark>,
    )
    at = m.end
  })
  if (at < text.length) parts.push(text.slice(at))
  return parts
}

function TranscriptStatusRow({ item, state }: { item: Item; state: TranscriptState }) {
  const percent = Math.round(state.progress * 100)
  switch (state.status) {
    case 'unknown':
    case 'checking':
      return <p className="transcript-status">Looking for a transcript…</p>
    case 'none':
      return (
        <div className="transcript-empty">
          <Icon name="closedCaption" size={26} />
          <p>This episode's feed has no transcript.</p>
          <button type="button" className="mf-btn primary" onClick={() => void transcribe(item)}>
            Make one in this browser
          </button>
          <p className="transcript-note">
            Speech to text (Whisper, free and open source) runs on this computer and nothing is uploaded. The first time, it downloads a speech model of about{' '}
            {WHISPER_MODEL_MB} MB. English only.
          </p>
        </div>
      )
    case 'getting-audio':
    case 'loading-model':
    case 'transcribing': {
      const label =
        state.status === 'getting-audio'
          ? `Getting the episode audio… ${percent}%`
          : state.status === 'loading-model'
            ? `Loading the speech model… ${percent}%`
            : `Making transcript… ${percent}% (up to ${formatTime(state.doneMs)})`
      return (
        <div className="transcript-status">
          <div className="transcript-status-row">
            <span>{label}</span>
            <button type="button" className="link-btn" onClick={stopTranscribing}>
              Stop
            </button>
          </div>
          <progress max={1} value={state.progress || undefined} />
        </div>
      )
    }
    case 'paused':
      return (
        <div className="transcript-status transcript-status-row">
          <span>Transcript made up to {formatTime(state.doneMs)}</span>
          <button type="button" className="mf-btn" onClick={() => void transcribe(item)}>
            Continue
          </button>
        </div>
      )
    case 'failed':
      return (
        <div className="transcript-status transcript-status-row error">
          <span>Could not make the transcript. {state.error}</span>
          <button type="button" className="mf-btn" onClick={() => void transcribe(item)}>
            Try again
          </button>
        </div>
      )
    case 'ready':
      return (
        <div className="transcript-status transcript-status-row">
          <span className="transcript-source">{state.source === 'browser' ? 'Transcript made in this browser' : 'Transcript from the podcast'}</span>
          {state.source === 'browser' ? (
            <button type="button" className="link-btn" onClick={() => void deleteTranscript(item)}>
              Make again
            </button>
          ) : null}
        </div>
      )
  }
}

/** In a long pause (music, an ad break) no line is lit. */
const PAUSE_GRACE_MS = 2000
/** After scrolling by hand, the list stops following playback for this long. */
const FOLLOW_AGAIN_MS = 5000

function TranscriptLines({
  cues,
  positionMs,
  onSeek,
  matches,
  activeMatch,
  searching,
}: {
  cues: TranscriptCue[]
  positionMs: number
  onSeek: (ms: number) => void
  matches: TranscriptMatch[]
  activeMatch: number
  searching: boolean
}) {
  const listRef = useRef<HTMLOListElement>(null)
  const current = cueIndexAt(cues, positionMs)
  const [handScrolledAt, setHandScrolledAt] = useState(0)
  // While searching, the list stays on the results instead of following playback
  const following = !searching && Date.now() - handScrolledAt > FOLLOW_AGAIN_MS
  const autoScrolling = useRef(false)
  const byCue = useMemo(() => {
    const map = new Map<number, TranscriptMatch[]>()
    for (const m of matches) map.set(m.cue, [...(map.get(m.cue) ?? []), m])
    return map
  }, [matches])
  const selected = activeMatch >= 0 ? matches[activeMatch] : null

  useEffect(() => {
    const list = listRef.current
    if (!list || !selected) return
    const line = list.children[selected.cue] as HTMLElement | undefined
    if (!line) return
    const top = line.offsetTop - list.offsetTop - list.clientHeight / 3
    // Far jumps go straight there; a long smooth scroll only delays the result
    list.scrollTo({ top, behavior: Math.abs(top - list.scrollTop) > 2000 ? 'instant' : 'smooth' })
  }, [selected])

  useEffect(() => {
    const list = listRef.current
    if (!list || current < 0 || !following) return
    const line = list.children[Math.max(0, current - 1)] as HTMLElement | undefined
    if (!line) return
    autoScrolling.current = true
    list.scrollTo({ top: line.offsetTop - list.offsetTop, behavior: 'smooth' })
    const id = window.setTimeout(() => (autoScrolling.current = false), 600)
    return () => window.clearTimeout(id)
  }, [current, following])

  const onUserScroll = () => {
    if (!autoScrolling.current) setHandScrolledAt(Date.now())
  }

  return (
    <div className="transcript-lines-wrap">
      <ol className="transcript-lines" ref={listRef} onWheel={onUserScroll} onTouchMove={onUserScroll}>
        {cues.map((cue, i) => {
          const now = i === current && positionMs <= cue.endMs + PAUSE_GRACE_MS
          const showSpeaker = cue.speaker && cue.speaker !== cues[i - 1]?.speaker
          return (
            <li key={`${i}:${cue.startMs}`} className={now ? 'now' : i < current ? 'past' : ''}>
              <button type="button" onClick={() => onSeek(cue.startMs)}>
                {showSpeaker ? <span className="transcript-speaker">{cue.speaker}</span> : null}
                <span className="transcript-time">{formatTime(cue.startMs)}</span>
                <span className="transcript-text">{markedText(cue.text, byCue.get(i) ?? [], selected)}</span>
              </button>
            </li>
          )
        })}
      </ol>
      {!following && !searching && current >= 0 ? (
        <button type="button" className="read-pill transcript-follow" onClick={() => setHandScrolledAt(0)}>
          <Icon name="myLocation" size={16} /> Follow playback
        </button>
      ) : null}
    </div>
  )
}
