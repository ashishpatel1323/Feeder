import { useEffect, useRef, useState } from 'react'
import { AHEAD_CHOICES, bookAudio, type BookAudioState, type SectionAudio } from '../lib/bookAudio'
import { updateSettings, type Settings } from '../lib/settings'
import { Icon } from './Icon'

function formatBytes(n: number): string {
  if (n >= 1024 * 1024 * 1024) return `${(n / 1024 / 1024 / 1024).toFixed(1)} GB`
  if (n >= 1024 * 1024) return `${Math.round(n / 1024 / 1024)} MB`
  return `${Math.max(1, Math.round(n / 1024))} KB`
}

export function useBookAudio(): BookAudioState {
  const [s, setS] = useState<BookAudioState>(bookAudio.state)
  useEffect(() => bookAudio.subscribe(setS), [])
  return s
}

/**
 * The read-aloud player's status line for an ebook's offline audio ("3 chapters ready · 41 MB").
 * Clicking it opens the Offline audio panel: each chapter's state and the two settings.
 */
export function BookAudioStatus({ settings }: { settings: Settings }) {
  const s = useBookAudio()
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!open) return
    const onDown = (e: PointerEvent) => {
      if (!ref.current?.contains(e.target as Node)) setOpen(false)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.stopPropagation()
        setOpen(false)
      }
    }
    document.addEventListener('pointerdown', onDown, true)
    window.addEventListener('keydown', onKey, true)
    return () => {
      document.removeEventListener('pointerdown', onDown, true)
      window.removeEventListener('keydown', onKey, true)
    }
  }, [open])

  if (!s.bookId) return null
  const upcoming = s.sections.filter((x) => x.index > s.current)
  const preparing = upcoming.find((x) => x.state === 'preparing')
  const size = s.bytes > 0 ? formatBytes(s.bytes) : ''
  let text: string
  let busy = false
  // Browser voices can't be recorded, so there is nothing to show (no nagging line)
  if (!s.supported || s.current < 0 || !upcoming.length) {
    return null
  } else if (s.readyAhead > 0 && s.readyAhead >= Math.min(settings.bookAudioAhead, upcoming.length)) {
    text = s.readyAhead === 1 ? 'Next chapter ready offline' : `Next ${s.readyAhead} chapters ready offline`
  } else if (preparing) {
    text = `Preparing chapter audio · ${Math.round(preparing.progress * 100)}%`
    busy = true
  } else {
    text = 'Chapters ahead will be prepared'
    busy = true
  }

  return (
    <div className="book-audio" ref={ref}>
      <button type="button" className="book-audio-status" onClick={() => setOpen(!open)} aria-haspopup="dialog" aria-expanded={open}>
        {busy ? <span className="book-audio-spin" aria-hidden /> : <Icon name="check" size={16} />}
        <span className="book-audio-text">{text}</span>
        {size ? <span className="book-audio-size">· {size}</span> : null}
        <Icon name="next" size={16} />
      </button>
      {open ? <BookAudioPanel s={s} settings={settings} /> : null}
    </div>
  )
}

function BookAudioPanel({ s, settings }: { s: BookAudioState; settings: Settings }) {
  return (
    <div className="book-audio-panel" role="dialog" aria-label="Offline audio">
      <h2>Offline audio</h2>
      <p className="book-audio-lead">
        {s.supported
          ? `Chapters ahead are spoken in this browser while Feeder is open, so reading never stops.${s.bytes > 0 ? ` ${formatBytes(s.bytes)}.` : ''}`
          : 'Only the natural voice can be prepared ahead. Pick one under Settings › Read aloud.'}
      </p>
      {s.supported ? (
        <>
          <ul className="book-audio-rows">
            {s.sections.map((x) => (
              <Row key={`${x.state}:${x.index}`} x={x} />
            ))}
          </ul>
          <div className="book-audio-sec">Prepare ahead</div>
          <div className="book-audio-seg" role="radiogroup" aria-label="Chapters to prepare ahead">
            {AHEAD_CHOICES.map((n) => (
              <button
                key={n}
                type="button"
                role="radio"
                aria-checked={settings.bookAudioAhead === n}
                className={settings.bookAudioAhead === n ? 'on' : ''}
                onClick={() => {
                  bookAudio.setAhead(n)
                  void updateSettings({ bookAudioAhead: n })
                }}
              >
                {n === settings.bookAudioAhead ? `${n} chapter${n === 1 ? '' : 's'}` : n}
              </button>
            ))}
          </div>
          <label className="book-audio-switch">
            <span>
              <b>Delete played audio</b>
              <small>After you finish a chapter</small>
            </span>
            <input
              type="checkbox"
              role="switch"
              checked={settings.bookAudioDeletePlayed}
              onChange={(e) => {
                bookAudio.setDeletePlayed(e.target.checked)
                void updateSettings({ bookAudioDeletePlayed: e.target.checked })
              }}
            />
          </label>
        </>
      ) : null}
    </div>
  )
}

function Row({ x }: { x: SectionAudio }) {
  const title = x.label || `Section ${x.index + 1}`
  let sub: string
  switch (x.state) {
    case 'played':
      sub = 'Played · audio deleted'
      break
    case 'playing':
      sub = 'Reading now'
      break
    case 'ready':
      sub = `Ready · ${Math.max(1, Math.round(x.seconds / 60))} min`
      break
    case 'preparing':
      sub = `Preparing · ${Math.round(x.progress * 100)}%`
      break
    default:
      sub = 'Up next'
  }
  return (
    <li className={`book-audio-row ${x.state}`}>
      <span className="book-audio-mark" aria-hidden>
        {x.state === 'played' ? <Icon name="delete" size={20} /> : null}
        {x.state === 'playing' ? <Icon name="volumeUp" size={20} /> : null}
        {x.state === 'ready' ? <Icon name="check" size={20} /> : null}
        {x.state === 'preparing' ? <span className="book-audio-ring" style={{ ['--p' as string]: `${Math.round(x.progress * 100)}%` }} /> : null}
        {x.state === 'waiting' ? <span className="book-audio-dot" /> : null}
      </span>
      <span className="book-audio-row-text">
        <b>{title}</b>
        <small>{sub}</small>
      </span>
    </li>
  )
}
