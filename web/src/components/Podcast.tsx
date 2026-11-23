import { useEffect, useRef, useState } from 'react'
import type { Item } from '../lib/db'
import { cueIndexAt, type TranscriptCue } from '../lib/transcriptFormats'
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
 * A podcast episode in the reader: the audio player, and its transcript as subtitles that follow
 * playback (CC button). Same behavior as the Android app's podcast player.
 */
export function PodcastEpisode({ item }: { item: Item }) {
  const audioRef = useRef<HTMLAudioElement>(null)
  const [showTranscript, setShowTranscript] = useState(false)
  const [position, setPosition] = useState(0)

  useEffect(() => {
    const audio = audioRef.current
    if (!audio || !showTranscript) return
    let frame = 0
    const tick = () => setPosition(Math.round(audio.currentTime * 1000))
    // timeupdate fires only about four times a second; this keeps lines changing on time
    const id = window.setInterval(() => {
      if (!audio.paused) {
        cancelAnimationFrame(frame)
        frame = requestAnimationFrame(tick)
      }
    }, 200)
    audio.addEventListener('seeked', tick)
    tick()
    return () => {
      window.clearInterval(id)
      cancelAnimationFrame(frame)
      audio.removeEventListener('seeked', tick)
    }
  }, [showTranscript])

  const seek = (ms: number) => {
    const audio = audioRef.current
    if (!audio) return
    audio.currentTime = ms / 1000
    if (audio.paused) void audio.play().catch(() => {})
  }

  return (
    <section className="podcast-episode">
      <div className="podcast-bar">
        <audio ref={audioRef} controls preload="metadata" src={item.enclosureUrl ?? undefined} />
        <button
          type="button"
          className={`icon-btn podcast-cc${showTranscript ? ' active' : ''}`}
          aria-pressed={showTranscript}
          title={showTranscript ? 'Hide transcript' : 'Show transcript'}
          onClick={() => setShowTranscript((v) => !v)}
        >
          <Icon name="closedCaption" size={22} /> Transcript
        </button>
      </div>
      {showTranscript ? <TranscriptPanel item={item} positionMs={position} onSeek={seek} /> : null}
    </section>
  )
}

function TranscriptPanel({ item, positionMs, onSeek }: { item: Item; positionMs: number; onSeek: (ms: number) => void }) {
  const state = useTranscript(item.id)
  useEffect(() => {
    void loadTranscript(item)
  }, [item])
  return (
    <div className="transcript">
      <TranscriptStatusRow item={item} state={state} />
      {state.cues.length ? <TranscriptLines cues={state.cues} positionMs={positionMs} onSeek={onSeek} /> : null}
    </div>
  )
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

function TranscriptLines({ cues, positionMs, onSeek }: { cues: TranscriptCue[]; positionMs: number; onSeek: (ms: number) => void }) {
  const listRef = useRef<HTMLOListElement>(null)
  const current = cueIndexAt(cues, positionMs)
  const [handScrolledAt, setHandScrolledAt] = useState(0)
  const following = Date.now() - handScrolledAt > FOLLOW_AGAIN_MS
  const autoScrolling = useRef(false)

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
                <span className="transcript-text">{cue.text}</span>
              </button>
            </li>
          )
        })}
      </ol>
      {!following && current >= 0 ? (
        <button type="button" className="read-pill transcript-follow" onClick={() => setHandScrolledAt(0)}>
          <Icon name="myLocation" size={16} /> Follow playback
        </button>
      ) : null}
    </div>
  )
}
