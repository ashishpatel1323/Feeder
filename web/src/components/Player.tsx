import { useCallback, useEffect, useLayoutEffect, useRef, useState, type ReactNode, type TouchEvent } from 'react'
import { isKokoroVoice, kokoro, KOKORO_PREFIX, KOKORO_VOICES, type KokoroStatus } from '../lib/kokoro'
import { CHARS_PER_SECOND, readAloud, RSVP_WPM_STEP, type ReadAloudState } from '../lib/readaloud'
import { focusIndex, focusLength, RSVP_MAX_WPM, RSVP_MIN_WPM } from '../lib/rsvp'
import { updateSettings, type Settings } from '../lib/settings'
import { NATURAL_VOICE } from './Dialogs'
import { Icon, type IconName } from './Icon'

/** Same speeds as the Android app. Browsers keep audio audible up to 4× (Firefox mutes above it). */
const SPEEDS = [0.75, 1, 1.25, 1.5, 1.75, 2, 2.5, 3, 3.5, 4]
// Muted, the ±10 buttons move ten words
const SKIP_WORDS = 10
const MINI_KEY = 'feeder.readAloudMini'
// A downward swipe this long on the panel shrinks it; upward grows it again
const SWIPE_PX = 40

interface Props {
  state: ReadAloudState
  settings: Settings
  currentTitle: string
  hasPrev: boolean
  hasNext: boolean
  nextTitle: string | null
  onPrev: () => void
  onNext: () => void
  /** A line of status under the title (an ebook's offline audio). */
  status?: ReactNode
}

export const formatSpeed = (speed: number) => `${speed.toFixed(2).replace(/0+$/, '').replace(/\.$/, '')}×`

function formatTime(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000))
  const mm = Math.floor(s / 60) % 60
  const ss = String(s % 60).padStart(2, '0')
  return s >= 3600 ? `${Math.floor(s / 3600)}:${String(mm).padStart(2, '0')}:${ss}` : `${mm}:${ss}`
}

/**
 * The read aloud player, styled like the Android app's: a solid dark panel (in both themes) with
 * a wavy seek line and a big play button. "Show word" puts speed reading inside it, and Mute hands
 * over from the voice to a silent timer reader.
 */
export function Player({ state, settings, currentTitle, hasPrev, hasNext, nextTitle, onPrev, onNext, status }: Props) {
  const [voice, setVoice] = useState<KokoroStatus>(kokoro.status)
  useEffect(() => kokoro.subscribe(setVoice), [])
  const [scrub, setScrub] = useState<number | null>(null)
  // Shrunk to one line (handle, or swipe down on a phone) so more of the article shows
  const [mini, setMini] = useState(() => {
    try {
      return localStorage.getItem(MINI_KEY) === '1'
    } catch {
      return false
    }
  })
  const setMiniSaved = (on: boolean) => {
    setMini(on)
    try {
      localStorage.setItem(MINI_KEY, on ? '1' : '0')
    } catch {
      // Private window: only this page remembers
    }
  }
  const swipeY = useRef<number | null>(null)

  const muted = state.muted
  const playing = state.status === 'playing'
  const kokoroVoice = isKokoroVoice(settings.readAloudVoice)
  const downloading = !muted && kokoroVoice && voice.state === 'loading'
  const voiceError = !muted && kokoroVoice && voice.state === 'error' ? `The natural voice could not load: ${voice.error}` : null

  const lastWord = Math.max(1, state.wordCount - 1)
  const fraction = scrub ?? (muted ? state.wordIndex / lastWord : state.textLength ? state.offset / state.textLength : 0)
  const charsPerMs = (CHARS_PER_SECOND * state.rate) / 1000
  const seekable = muted ? state.wordCount > 1 : state.textLength > 0 && !downloading

  let label: string
  let title: string
  if (muted) {
    const left = readAloud.silentMillisLeft()
    label = ''
    title = left < 60000 ? `${Math.max(1, Math.round(left / 1000))} s left` : `${Math.round(left / 60000)} min left`
  } else if (nextTitle) {
    label = 'Up next'
    title = nextTitle
  } else {
    label = 'Now reading'
    title = currentTitle || '(no title)'
  }

  let leftTime: string
  let rightTime: string
  if (muted) {
    leftTime = `Word ${Math.round(fraction * lastWord) + 1}`
    rightTime = `${state.wordCount} words`
  } else {
    leftTime = formatTime((fraction * state.textLength) / charsPerMs)
    rightTime = `~${formatTime(state.textLength / charsPerMs)}`
  }

  const setShowWord = (on: boolean) => updateSettings({ readAloudShowWord: on })
  const skipBy = (sign: number) => (muted ? readAloud.seekBy(sign * SKIP_WORDS) : readAloud.seekBy(sign * 10))

  const handle = (
    <button
      type="button"
      className="player-handle"
      onClick={() => setMiniSaved(!mini)}
      title={mini ? 'Show full player' : 'Shrink player'}
      aria-label={mini ? 'Show full player' : 'Shrink player'}
      aria-expanded={!mini}
    >
      <i aria-hidden />
    </button>
  )
  const swipe = {
    onTouchStart: (e: TouchEvent) => {
      // A drag that starts in a popup menu scrolls the menu, not the panel
      const inMenu = (e.target as HTMLElement).closest('.player-menu')
      swipeY.current = inMenu ? null : (e.touches[0]?.clientY ?? null)
    },
    onTouchEnd: (e: TouchEvent) => {
      const start = swipeY.current
      swipeY.current = null
      const end = e.changedTouches[0]?.clientY
      if (start == null || end == null) return
      if (end - start > SWIPE_PX) setMiniSaved(true)
      else if (start - end > SWIPE_PX) setMiniSaved(false)
    },
  }

  if (mini) {
    return (
      <div className="player mini" role="region" aria-label="Read aloud" {...swipe}>
        {handle}
        <div className="player-inner player-mini-row">
          <button
            type="button"
            className={`player-play small${playing ? ' playing' : ''}`}
            onClick={() => readAloud.toggle()}
            title={playing ? 'Pause' : 'Play'}
            aria-label={playing ? 'Pause' : 'Play'}
          >
            {downloading ? <span className="player-spinner" aria-hidden /> : null}
            <Icon name={playing ? 'pause' : 'play'} size={24} />
          </button>
          <div className="player-mini-main">
            <span className="player-title" title={currentTitle}>
              {currentTitle || 'Reading'}
            </span>
            <div className="player-mini-progress" aria-hidden>
              <i style={{ width: `${Math.round(Math.min(1, Math.max(0, fraction)) * 100)}%` }} />
            </div>
          </div>
          <button type="button" className="player-skip" onClick={onNext} disabled={!hasNext} title="Next article" aria-label="Next article">
            <Icon name="skipNext" />
          </button>
          <RoundButton icon="close" label="Close" onClick={() => readAloud.stop()} />
        </div>
      </div>
    )
  }

  return (
    <div className="player" role="region" aria-label="Read aloud" {...swipe}>
      {handle}
      <div className="player-inner">
        <div className="player-top">
          {label ? <span className="player-label">{label}</span> : null}
          <span className="player-title" title={title}>
            {title}
          </span>
          {muted ? <WpmStepper wpm={state.wpm} /> : <SpeedPill speed={state.rate} />}
          <RoundButton icon={muted ? 'volumeOff' : 'volumeUp'} label={muted ? 'Unmute (read aloud again)' : 'Mute (speed read silently)'} onClick={() => readAloud.setMuted(!muted)} active={muted} />
          <MoreMenu state={state} settings={settings} hasNext={hasNext} onNext={onNext} onSetShowWord={setShowWord} />
          <RoundButton icon="close" label="Close" onClick={() => readAloud.stop()} />
        </div>

        {status && !muted ? status : null}
        {settings.readAloudShowWord ? <RsvpWordView word={state.word} /> : null}

        {downloading ? (
          <div className="player-prepare">
            <div className="player-download" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(voice.progress * 100)}>
              <div style={{ width: `${Math.round(voice.progress * 100)}%` }} />
            </div>
            <div className="player-times">Downloading natural voice… {Math.round(voice.progress * 100)}%</div>
          </div>
        ) : (
          <>
            <WavySeekBar
              fraction={Math.min(1, Math.max(0, fraction))}
              playing={playing}
              enabled={seekable}
              valueText={`${leftTime} of ${rightTime}`}
              onScrub={setScrub}
              onScrubEnd={(f) => {
                setScrub(null)
                readAloud.seekToFraction(f)
              }}
              onStep={skipBy}
            />
            <div className="player-times">
              <span>{leftTime}</span>
              <span>{rightTime}</span>
            </div>
          </>
        )}
        {voiceError ? <div className="player-error">{voiceError}</div> : null}

        <div className="player-controls">
          <button type="button" className="player-skip" onClick={onPrev} disabled={!hasPrev} title="Previous article" aria-label="Previous article">
            <Icon name="skipPrev" />
          </button>
          <button
            type="button"
            className="player-seek"
            onClick={() => skipBy(-1)}
            disabled={!seekable}
            title={muted ? 'Back 10 words' : 'Back 10 seconds'}
            aria-label={muted ? 'Back 10 words' : 'Back 10 seconds'}
          >
            <Icon name="replay10" size={26} />
          </button>
          <button
            type="button"
            className={`player-play${playing ? ' playing' : ''}`}
            onClick={() => readAloud.toggle()}
            title={playing ? 'Pause' : 'Play'}
            aria-label={playing ? 'Pause' : 'Play'}
          >
            {downloading ? <span className="player-spinner" aria-hidden /> : null}
            <Icon name={playing ? 'pause' : 'play'} size={34} />
          </button>
          <button
            type="button"
            className="player-seek"
            onClick={() => skipBy(1)}
            disabled={!seekable}
            title={muted ? 'Ahead 10 words' : 'Ahead 10 seconds'}
            aria-label={muted ? 'Ahead 10 words' : 'Ahead 10 seconds'}
          >
            <Icon name="forward10" size={26} />
          </button>
          <button type="button" className="player-skip" onClick={onNext} disabled={!hasNext} title="Next article" aria-label="Next article">
            <Icon name="skipNext" />
          </button>
        </div>
      </div>
    </div>
  )
}

function RoundButton({ icon, label, onClick, active }: { icon: IconName; label: string; onClick: () => void; active?: boolean }) {
  return (
    <button type="button" className="player-round" onClick={onClick} title={label} aria-label={label} aria-pressed={active}>
      <Icon name={icon} size={20} />
    </button>
  )
}

/** A popup anchored above its button; closes on Escape or a click outside. */
function usePopup() {
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
        ;(ref.current?.querySelector('button') as HTMLButtonElement | null)?.focus()
      }
    }
    document.addEventListener('pointerdown', onDown, true)
    window.addEventListener('keydown', onKey, true)
    return () => {
      document.removeEventListener('pointerdown', onDown, true)
      window.removeEventListener('keydown', onKey, true)
    }
  }, [open])
  return { open, setOpen, ref }
}

function SpeedPill({ speed }: { speed: number }) {
  const { open, setOpen, ref } = usePopup()
  const pick = (s: number) => {
    readAloud.setRate(s)
    void updateSettings({ readAloudRate: s })
    setOpen(false)
  }
  return (
    <div className="player-popup-anchor" ref={ref}>
      <button type="button" className="player-pill" onClick={() => setOpen(!open)} title="Speed" aria-label={`Speed ${formatSpeed(speed)}`} aria-haspopup="menu" aria-expanded={open}>
        {formatSpeed(speed)}
      </button>
      {open ? (
        <div className="player-menu narrow" role="menu">
          {SPEEDS.map((s) => (
            <button key={s} type="button" role="menuitemradio" aria-checked={Math.abs(s - speed) < 0.001} className="menu-item" onClick={() => pick(s)}>
              <span className="menu-text">{formatSpeed(s)}</span>
              {Math.abs(s - speed) < 0.001 ? <Icon name="check" size={20} /> : null}
            </button>
          ))}
        </div>
      ) : null}
    </div>
  )
}

function WpmStepper({ wpm }: { wpm: number }) {
  return (
    <div className="player-stepper" role="group" aria-label="Words per minute">
      <button type="button" onClick={() => readAloud.setWpm(wpm - RSVP_WPM_STEP)} disabled={wpm <= RSVP_MIN_WPM} title="Slower" aria-label="Slower">
        <Icon name="remove" size={18} />
      </button>
      <span aria-live="polite">{wpm} wpm</span>
      <button type="button" onClick={() => readAloud.setWpm(wpm + RSVP_WPM_STEP)} disabled={wpm >= RSVP_MAX_WPM} title="Faster" aria-label="Faster">
        <Icon name="add" size={18} />
      </button>
    </div>
  )
}

function Switch({ on }: { on: boolean }) {
  return <span className={`menu-switch${on ? ' on' : ''}`} aria-hidden />
}

function MenuItem({ icon, children, trailing, onClick, role = 'menuitem', checked }: { icon?: IconName; children: ReactNode; trailing?: ReactNode; onClick: () => void; role?: string; checked?: boolean }) {
  return (
    <button type="button" role={role} aria-checked={checked} className="menu-item" onClick={onClick}>
      {icon ? <Icon name={icon} size={20} /> : null}
      <span className="menu-text">{children}</span>
      {trailing}
    </button>
  )
}

/** Show word, auto-play next, go to spoken text, skip to next and the voice. */
function MoreMenu({
  state,
  settings,
  hasNext,
  onNext,
  onSetShowWord,
}: {
  state: ReadAloudState
  settings: Settings
  hasNext: boolean
  onNext: () => void
  onSetShowWord: (on: boolean) => void
}) {
  const { open, setOpen, ref } = usePopup()
  const [voices, setVoices] = useState(false)
  const close = () => {
    setOpen(false)
    setVoices(false)
  }
  return (
    <div className="player-popup-anchor" ref={ref}>
      <button
        type="button"
        className="player-round"
        onClick={() => {
          setVoices(false)
          setOpen(!open)
        }}
        title="More"
        aria-label="More"
        aria-haspopup="menu"
        aria-expanded={open}
      >
        <Icon name="moreVert" size={20} />
      </button>
      {open ? (
        <div className="player-menu" role="menu">
          {voices ? (
            <VoiceList settings={settings} onBack={() => setVoices(false)} onPicked={close} />
          ) : (
            <>
              <MenuItem icon="speed" role="menuitemcheckbox" checked={settings.readAloudShowWord} onClick={() => onSetShowWord(!settings.readAloudShowWord)} trailing={<Switch on={settings.readAloudShowWord} />}>
                Show word
              </MenuItem>
              <MenuItem
                icon="repeat"
                role="menuitemcheckbox"
                checked={settings.readAloudAutoContinue}
                onClick={() => updateSettings({ readAloudAutoContinue: !settings.readAloudAutoContinue })}
                trailing={<Switch on={settings.readAloudAutoContinue} />}
              >
                Auto-play next
              </MenuItem>
              <MenuItem
                icon="myLocation"
                onClick={() => {
                  readAloud.goToSpokenText()
                  close()
                }}
                trailing={state.following ? <Icon name="check" size={20} /> : null}
              >
                Go to spoken text
              </MenuItem>
              {hasNext ? (
                <MenuItem
                  icon="fastForward"
                  onClick={() => {
                    onNext()
                    close()
                  }}
                >
                  Skip to next
                </MenuItem>
              ) : null}
              <MenuItem icon="voice" onClick={() => setVoices(true)} trailing={<Icon name="next" size={20} />}>
                Change voice
              </MenuItem>
              {state.wordTiming && !state.muted ? <div className="menu-note">Word timing: {state.wordTiming}</div> : null}
            </>
          )}
        </div>
      ) : null}
    </div>
  )
}

function VoiceList({ settings, onBack, onPicked }: { settings: Settings; onBack: () => void; onPicked: () => void }) {
  const [browserVoices, setBrowserVoices] = useState<SpeechSynthesisVoice[]>([])
  useEffect(() => {
    if (!('speechSynthesis' in window)) return
    const load = () => setBrowserVoices(speechSynthesis.getVoices())
    load()
    speechSynthesis.addEventListener('voiceschanged', load)
    return () => speechSynthesis.removeEventListener('voiceschanged', load)
  }, [])
  const natural = (v: SpeechSynthesisVoice) => NATURAL_VOICE.test(v.name)
  const sorted = [...browserVoices].sort((a, b) => Number(natural(b)) - Number(natural(a)) || a.name.localeCompare(b.name))
  const pick = (uri: string) => {
    void updateSettings({ readAloudVoice: uri })
    if (isKokoroVoice(uri)) kokoro.load(settings.readAloudKokoroUseGpu).catch(() => undefined)
    onPicked()
  }
  const current = settings.readAloudVoice
  const tick = (uri: string) => (uri === current ? <Icon name="check" size={20} /> : null)
  return (
    <>
      <MenuItem icon="back" onClick={onBack}>
        Change voice
      </MenuItem>
      <div className="menu-divider" />
      <div className="menu-heading">Natural voices (this device)</div>
      {KOKORO_VOICES.map((v) => (
        <MenuItem key={v.id} role="menuitemradio" checked={current === KOKORO_PREFIX + v.id} onClick={() => pick(KOKORO_PREFIX + v.id)} trailing={tick(KOKORO_PREFIX + v.id)}>
          {v.label}
        </MenuItem>
      ))}
      <div className="menu-divider" />
      <div className="menu-heading">Browser voices</div>
      <MenuItem role="menuitemradio" checked={current === ''} onClick={() => pick('')} trailing={tick('')}>
        Default for the article's language
      </MenuItem>
      {sorted.map((v) => (
        <MenuItem key={v.voiceURI} role="menuitemradio" checked={current === v.voiceURI} onClick={() => pick(v.voiceURI)} trailing={tick(v.voiceURI)}>
          {natural(v) ? '★ ' : ''}
          {v.name} <span className="menu-sub">({v.lang})</span>
        </MenuItem>
      ))}
    </>
  )
}

/** One speed-reading word between two guide notches, its focus letter always at the same spot. */
function RsvpWordView({ word }: { word: string }) {
  const focus = Math.min(Math.max(0, focusIndex(word)), word.length)
  const length = word ? Math.min(focusLength(word, focus), word.length - focus) : 0
  const pivotRef = useRef<HTMLSpanElement>(null)
  const [shift, setShift] = useState(0)
  useLayoutEffect(() => setShift((pivotRef.current?.offsetWidth ?? 0) / 2), [word])
  return (
    <div className="rsvp-word" aria-label={word || undefined}>
      <span className="rsvp-guide" aria-hidden />
      <div className="rsvp-line" style={{ transform: `translateX(${-shift}px)` }} aria-hidden>
        <span className="rsvp-pre">
          <span>{word.slice(0, focus)}</span>
        </span>
        <span className="rsvp-focus" ref={pivotRef}>
          {word.slice(focus, focus + length) || ' '}
        </span>
        <span className="rsvp-post">{word.slice(focus + length)}</span>
      </div>
      <span className="rsvp-guide" aria-hidden />
    </div>
  )
}

/**
 * Reading position as a line that waves while playing and lies flat when paused. Tap or drag to
 * move, arrow keys step back and ahead.
 */
function WavySeekBar({
  fraction,
  playing,
  enabled,
  valueText,
  onScrub,
  onScrubEnd,
  onStep,
}: {
  fraction: number
  playing: boolean
  enabled: boolean
  valueText: string
  onScrub: (f: number) => void
  onScrubEnd: (f: number) => void
  onStep: (sign: number) => void
}) {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const anim = useRef({ fraction, playing, enabled, amp: playing ? 1 : 0, phase: 0, last: 0, raf: 0 })
  const drag = useRef<{ id: number; f: number } | null>(null)
  anim.current.fraction = fraction
  anim.current.playing = playing
  anim.current.enabled = enabled

  const draw = useCallback(() => {
    const c = canvasRef.current
    if (!c) return
    const a = anim.current
    const dpr = window.devicePixelRatio || 1
    const w = c.clientWidth
    const h = c.clientHeight
    if (!w || !h) return
    if (c.width !== Math.round(w * dpr) || c.height !== Math.round(h * dpr)) {
      c.width = Math.round(w * dpr)
      c.height = Math.round(h * dpr)
    }
    const ctx = c.getContext('2d')
    if (!ctx) return
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    ctx.clearRect(0, 0, w, h)
    const css = getComputedStyle(c)
    const track = css.getPropertyValue('--player-track').trim() || 'rgba(255,255,255,.22)'
    const active = a.enabled ? css.getPropertyValue('--player-accent').trim() || '#5bd08f' : track
    const stroke = 4
    const thumb = 6
    const gap = 4
    const waveHeight = 3 * a.amp
    const wavelength = 24
    const cy = h / 2
    const start = stroke / 2
    const end = w - stroke / 2
    const thumbX = start + (end - start) * a.fraction
    const rtl = css.direction === 'rtl'
    const x = (v: number) => (rtl ? w - v : v)
    ctx.lineCap = 'round'
    ctx.lineWidth = stroke
    const playedEnd = thumbX - thumb - gap
    if (playedEnd > start) {
      ctx.strokeStyle = active
      ctx.beginPath()
      ctx.moveTo(x(start), cy)
      for (let px = start; px < playedEnd; ) {
        px = Math.min(px + 2, playedEnd)
        ctx.lineTo(x(px), cy + waveHeight * Math.sin(2 * Math.PI * (px / wavelength - a.phase)))
      }
      ctx.stroke()
    }
    const trackStart = thumbX + thumb + gap
    if (trackStart < end) {
      ctx.strokeStyle = track
      ctx.beginPath()
      ctx.moveTo(x(trackStart), cy)
      ctx.lineTo(x(end), cy)
      ctx.stroke()
    }
    ctx.fillStyle = active
    ctx.beginPath()
    ctx.arc(x(thumbX), cy, thumb, 0, Math.PI * 2)
    ctx.fill()
  }, [])

  // Wave while playing, settle flat when paused; animate only while there is movement
  useEffect(() => {
    const a = anim.current
    const still = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
    const loop = (t: number) => {
      const dt = a.last ? Math.min(64, t - a.last) : 16
      a.last = t
      if (!still) a.phase = (a.phase + dt / 1600) % 1
      const target = a.playing && !still ? 1 : 0
      a.amp = target > a.amp ? Math.min(target, a.amp + dt / 400) : Math.max(target, a.amp - dt / 400)
      draw()
      if (a.playing || a.amp > 0) a.raf = requestAnimationFrame(loop)
      else {
        a.raf = 0
        a.last = 0
      }
    }
    if (!a.raf && (playing || a.amp > 0)) a.raf = requestAnimationFrame(loop)
    draw()
  }, [playing, fraction, enabled, draw])

  useEffect(() => {
    const a = anim.current
    const c = canvasRef.current
    const ro = typeof ResizeObserver !== 'undefined' && c ? new ResizeObserver(() => draw()) : null
    if (c) ro?.observe(c)
    return () => {
      ro?.disconnect()
      cancelAnimationFrame(a.raf)
      a.raf = 0
    }
  }, [draw])

  const toFraction = (clientX: number) => {
    const r = canvasRef.current!.getBoundingClientRect()
    const f = Math.min(1, Math.max(0, (clientX - r.left) / r.width))
    return getComputedStyle(canvasRef.current!).direction === 'rtl' ? 1 - f : f
  }

  return (
    <canvas
      ref={canvasRef}
      className={`player-wave${enabled ? '' : ' disabled'}`}
      role="slider"
      tabIndex={enabled ? 0 : -1}
      aria-label="Position"
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={Math.round(fraction * 100)}
      aria-valuetext={valueText}
      aria-disabled={!enabled}
      onPointerDown={(e) => {
        if (!enabled || e.button > 0) return
        e.currentTarget.setPointerCapture(e.pointerId)
        const f = toFraction(e.clientX)
        drag.current = { id: e.pointerId, f }
        onScrub(f)
      }}
      onPointerMove={(e) => {
        if (drag.current?.id !== e.pointerId) return
        drag.current.f = toFraction(e.clientX)
        onScrub(drag.current.f)
      }}
      onPointerUp={(e) => {
        if (drag.current?.id !== e.pointerId) return
        const f = drag.current.f
        drag.current = null
        onScrubEnd(f)
      }}
      onPointerCancel={(e) => {
        if (drag.current?.id !== e.pointerId) return
        const f = drag.current.f
        drag.current = null
        onScrubEnd(f)
      }}
      onKeyDown={(e) => {
        if (!enabled) return
        if (e.key === 'ArrowLeft' || e.key === 'ArrowDown') onStep(-1)
        else if (e.key === 'ArrowRight' || e.key === 'ArrowUp') onStep(1)
        else if (e.key === 'Home') onScrubEnd(0)
        else if (e.key === 'End') onScrubEnd(1)
        else return
        e.preventDefault()
        // The app's own arrow keys (next/previous article) must not fire too
        e.stopPropagation()
      }}
    />
  )
}
