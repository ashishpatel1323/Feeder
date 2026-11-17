import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  focusIndex,
  minutesLeft,
  RSVP_DEFAULT_WPM,
  RSVP_MAX_WPM,
  RSVP_MIN_WPM,
  rsvpWords,
  sentenceStartBefore,
  wordDelay,
  wordIndexAt,
  type RsvpWord,
} from '../lib/rsvp'
import { Icon, IconButton } from './Icon'
import './rsvp.css'

const WPM_KEY = 'feeder.rsvpWpm'
const WPM_STEP = 25
// The first words after (re)starting are shown a little longer so the eye can settle
const RAMP = [1.8, 1.4, 1.15]

function loadWpm(): number {
  try {
    const v = Number(localStorage.getItem(WPM_KEY))
    if (v >= RSVP_MIN_WPM && v <= RSVP_MAX_WPM) return v
  } catch {
    // storage blocked
  }
  return RSVP_DEFAULT_WPM
}

function saveWpm(wpm: number) {
  try {
    localStorage.setItem(WPM_KEY, String(wpm))
  } catch {
    // storage blocked
  }
}

interface Props {
  /** The article text, as indexed for read aloud. */
  text: string
  /** Offset in [text] to start from. */
  startOffset: number
  title: string
  /** Called with the offset of the word reached, so the article can scroll there. */
  onClose: (offset: number) => void
}

/** Speed reading: one word at a time at a fixed spot, with a highlighted focus letter. */
export function Rsvp({ text, startOffset, title, onClose }: Props) {
  const words = useMemo(() => rsvpWords(text), [text])
  const [index, setIndex] = useState(() => Math.min(wordIndexAt(words, startOffset), Math.max(0, words.length - 1)))
  const [playing, setPlaying] = useState(false)
  const [wpm, setWpmState] = useState(loadWpm)
  const shownSincePlay = useRef(0)
  const rootRef = useRef<HTMLDivElement>(null)

  const done = index >= words.length - 1 && !playing
  const word: RsvpWord | undefined = words[index]

  const setWpm = (v: number) => {
    const clamped = Math.min(RSVP_MAX_WPM, Math.max(RSVP_MIN_WPM, Math.round(v / WPM_STEP) * WPM_STEP))
    setWpmState(clamped)
    saveWpm(clamped)
  }

  const play = useCallback(() => {
    shownSincePlay.current = 0
    setIndex((i) => (i >= words.length - 1 ? 0 : i))
    setPlaying(true)
  }, [words.length])

  const togglePlay = useCallback(() => (playing ? setPlaying(false) : play()), [playing, play])

  const jump = useCallback(
    (to: number) => {
      shownSincePlay.current = 0
      setIndex(Math.min(Math.max(0, to), Math.max(0, words.length - 1)))
    },
    [words.length],
  )

  const close = useCallback(() => onClose(word?.start ?? startOffset), [onClose, word, startOffset])

  // Advance through the words
  useEffect(() => {
    if (!playing || !word) return
    const ramp = RAMP[shownSincePlay.current] ?? 1
    const t = window.setTimeout(() => {
      shownSincePlay.current++
      if (index >= words.length - 1) setPlaying(false)
      else setIndex(index + 1)
    }, wordDelay(word, wpm) * ramp)
    return () => window.clearTimeout(t)
  }, [playing, index, word, wpm, words.length])

  // Start right away
  useEffect(() => {
    rootRef.current?.focus()
    if (words.length) play()
  }, [words.length, play])

  // Keys win over the app's own shortcuts while open
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.ctrlKey || e.metaKey || e.altKey) return
      const target = e.target as HTMLElement | null
      if (target?.tagName === 'INPUT' && (target as HTMLInputElement).type !== 'range') return
      switch (e.key) {
        case ' ':
          togglePlay()
          break
        case 'ArrowLeft':
          jump(e.shiftKey ? index - 1 : sentenceStartBefore(words, index))
          break
        case 'ArrowRight':
          jump(index + 1)
          break
        case 'ArrowUp':
          setWpm(wpm + WPM_STEP)
          break
        case 'ArrowDown':
          setWpm(wpm - WPM_STEP)
          break
        case 'Escape':
        case 'v':
          close()
          break
        default:
          // Swallow the other single-key shortcuts (next article, save…) while speed reading
          if (e.key.length !== 1) return
      }
      e.preventDefault()
      e.stopPropagation()
    }
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  })

  const focus = word ? focusIndex(word.text) : 0
  const focusLen = word ? Array.from(word.text.slice(focus))[0]?.length ?? 1 : 1
  const left = word ? word.text.slice(0, focus) : ''
  const pivot = word ? word.text.slice(focus, focus + focusLen) : ''
  const right = word ? word.text.slice(focus + focusLen) : ''

  // When paused, show the sentence around the word for context
  const context = useMemo(() => {
    if (playing || !words.length) return null
    const start = sentenceStartBefore(words, index + 1)
    let end = index
    while (end < words.length - 1 && !words[end].paragraphEnd && !/[.!?…]["'”’)\]]*$/.test(words[end].text)) end++
    const from = Math.max(start, index - 30)
    const to = Math.min(end, index + 30)
    return { from, to }
  }, [playing, words, index])

  const mins = minutesLeft(words, index, wpm)
  const timeLeft = mins < 1 ? `${Math.max(1, Math.round(mins * 60))} s left` : `${Math.round(mins)} min left`

  return (
    <div className="rsvp" role="dialog" aria-modal="true" aria-label="Speed reading" ref={rootRef} tabIndex={-1}>
      <header className="pane-bar rsvp-bar">
        <IconButton icon="close" label="Close speed reading (Esc)" onClick={close} />
        <span className="rsvp-title" title={title}>
          {title}
        </span>
        <span className="rsvp-left">{words.length ? timeLeft : ''}</span>
      </header>

      <div className="rsvp-stage" onClick={togglePlay} aria-live="off">
        {word ? (
          <div className="rsvp-frame">
            <div className="rsvp-guide top" />
            <div className="rsvp-word" key={index}>
              <span className="rsvp-l">{left}</span>
              <span className="rsvp-pivot">{pivot}</span>
              <span className="rsvp-r">{right}</span>
            </div>
            <div className="rsvp-guide bottom" />
          </div>
        ) : (
          <p className="rsvp-empty">There's no text to speed read in this article.</p>
        )}
        {context ? (
          <p className="rsvp-context">
            {words.slice(context.from, context.to + 1).map((w, i) => (
              <span key={context.from + i} className={context.from + i === index ? 'current' : undefined}>
                {w.text}{' '}
              </span>
            ))}
          </p>
        ) : null}
        {!playing && words.length ? <p className="rsvp-hint">{done ? 'Finished' : 'Paused · tap to continue'}</p> : null}
      </div>

      <div className="rsvp-controls">
        <input
          className="rsvp-progress"
          type="range"
          min={0}
          max={Math.max(0, words.length - 1)}
          value={index}
          onChange={(e) => jump(Number(e.target.value))}
          aria-label="Position"
        />
        <div className="rsvp-buttons">
          <IconButton icon="replay" label="Back one sentence (←)" onClick={() => jump(sentenceStartBefore(words, index))} />
          <IconButton icon="prev" label="Back one word (Shift ←)" onClick={() => jump(index - 1)} />
          <button className="play-btn" onClick={togglePlay} aria-label={playing ? 'Pause (Space)' : 'Play (Space)'} title={playing ? 'Pause (Space)' : 'Play (Space)'}>
            <Icon name={playing ? 'pause' : done ? 'replay' : 'play'} size={28} />
          </button>
          <IconButton icon="next" label="Forward one word (→)" onClick={() => jump(index + 1)} />
        </div>
        <div className="rsvp-speed">
          <button className="chip" onClick={() => setWpm(wpm - WPM_STEP)} aria-label="Slower (↓)" title="Slower (↓)">
            −
          </button>
          <input
            type="range"
            min={RSVP_MIN_WPM}
            max={RSVP_MAX_WPM}
            step={WPM_STEP}
            value={wpm}
            onChange={(e) => setWpm(Number(e.target.value))}
            aria-label="Words per minute"
          />
          <button className="chip" onClick={() => setWpm(wpm + WPM_STEP)} aria-label="Faster (↑)" title="Faster (↑)">
            +
          </button>
          <span className="rsvp-wpm">{wpm} wpm</span>
        </div>
      </div>
    </div>
  )
}
