/**
 * A playlist's brief (lib/briefs.ts) at the top of the playlist: waiting for the Mac, being written,
 * failed with Try again, or the Morning brief (opener, the rundown, picks for you, sign-off) with
 * Listen, which reads it aloud with the read-aloud voice (tap a title to open the article).
 */
import { useEffect, useRef, useState } from 'react'
import { briefAsText, isPending, STALE_ASK_MS, type StoredBrief } from '../lib/briefs'
import { agoText } from '../lib/device'
import { audioFocus, playQueue } from '../lib/playqueue'
import { readAloud } from '../lib/readaloud'
import { Icon, IconButton } from './Icon'

const POLL_MS = 20_000

export function BriefCard(p: {
  playlistId: string
  brief: StoredBrief
  /** Articles that are on this device (the rest can't be opened from the brief) */
  canOpen: (id: string) => boolean
  onOpen: (id: string) => void
  onAgain: () => void
  toast: (m: string) => void
}) {
  const v = p.brief.v
  const pending = isPending(v)
  const [open, setOpen] = useState(true)
  const bodyRef = useRef<HTMLDivElement>(null)
  const speechId = `brief:${p.playlistId}:${p.brief.ts}`
  const [ra, setRa] = useState(readAloud.state)
  useEffect(() => readAloud.subscribe(setRa), [])
  const speaking = ra.itemId === speechId && ra.status === 'playing'
  // Leaving the playlist stops the brief (it isn't in any queue to come back to)
  useEffect(() => () => void (readAloud.state.itemId === speechId && readAloud.stop()), [speechId])
  // One player at a time: a podcast, video or audiobook pauses while the brief speaks
  const pauseOthers = () => {
    if (playQueue.state.kind !== 'article' && playQueue.state.status === 'playing') playQueue.toggle(false)
    audioFocus.pauseAudiobook?.()
  }
  const listen = () => {
    if (ra.itemId === speechId && ra.status !== 'idle') {
      if (ra.status !== 'playing') pauseOthers()
      return readAloud.toggle()
    }
    pauseOthers()
    if (!bodyRef.current) return
    setOpen(true)
    readAloud.attach(bodyRef.current, speechId)
    readAloud.play(0)
  }

  // While the Mac works on it, look for its answer more often than the usual sync
  useEffect(() => {
    if (!pending) return
    const t = window.setInterval(() => void import('../lib/sync2').then((m) => m.syncNow()).catch(() => undefined), POLL_MS)
    return () => window.clearInterval(t)
  }, [pending])

  const total = v.items.length
  const copy = () => {
    void navigator.clipboard
      .writeText(briefAsText(v))
      .then(() => p.toast('Brief copied'))
      .catch(() => p.toast('Could not copy'))
  }

  if (pending || (v.s !== 'done' && v.s !== 'failed')) {
    const stale = !pending
    const reading = v.s === 'working' && (v.n ?? 0) < total
    return (
      <section className="pl-offline-card pl-brief" aria-live="polite">
        <div className="pl-offline-row">
          {stale ? <Icon name="summarize" size={18} /> : <span className="book-audio-spin" aria-hidden />}
          <b>
            {stale
              ? "Your Mac didn't pick up this brief"
              : v.s === 'asked'
                ? `Morning brief of ${total} article${total === 1 ? '' : 's'} · waiting for your Mac`
                : reading
                  ? `Claude is reading the articles · ${v.n ?? 0} of ${total}`
                  : 'Claude is writing your morning brief'}
          </b>
          <span className="mf-sp" />
          {stale ? (
            <button type="button" className="mf-btn" onClick={p.onAgain}>
              Try again
            </button>
          ) : null}
        </div>
        {v.s === 'working' ? (
          <div className="pl-offline-bar" role="progressbar" aria-valuemin={0} aria-valuemax={total} aria-valuenow={v.n ?? 0}>
            <div
              style={{
                width: `${reading ? Math.round(((v.n ?? 0) / Math.max(1, total)) * 70) : 85}%`,
              }}
            />
          </div>
        ) : null}
        <small>
          {stale
            ? `Asked ${agoText(v.asked)}. Open Feeder on your Mac with Claude Code signed in, then try again.`
            : 'Made by Claude Code in Feeder for Mac, from each article’s full text. Nothing is marked read.'}
        </small>
      </section>
    )
  }

  if (v.s === 'failed') {
    return (
      <section className="pl-offline-card pl-brief">
        <div className="pl-offline-row">
          <Icon name="summarize" size={18} />
          <b>The brief couldn’t be made</b>
          <span className="mf-sp" />
          <button type="button" className="mf-btn" onClick={p.onAgain}>
            Try again
          </button>
        </div>
        <small>{v.err || 'Claude Code stopped without an answer.'}</small>
      </section>
    )
  }

  const meta = `${total} article${total === 1 ? '' : 's'} · by Claude${v.via === 'claude' ? ' in the Claude app' : v.via === 'mac' ? ' on your Mac' : ''}${v.at ? ` · ${agoText(v.at)}` : ''}`
  const title = (i: number) => {
    const it = v.items[i]
    if (!it) return null
    const link = p.canOpen(it.id) ? (
      // A link, not a button: a button can't wrap inline, so on a phone the title broke away from its number
      <a
        className="pl-brief-title"
        href={it.u ?? '#'}
        onClick={(e) => {
          e.preventDefault()
          p.onOpen(it.id)
        }}
      >
        {it.t}
      </a>
    ) : it.u ? (
      <a className="pl-brief-title" href={it.u} target="_blank" rel="noopener noreferrer">
        {it.t}
      </a>
    ) : (
      <span className="pl-brief-title">{it.t}</span>
    )
    return (
      <>
        {link}
        {it.f ? <span className="pl-brief-source"> · {it.f}</span> : null}
      </>
    )
  }
  return (
    <section className="pl-offline-card pl-brief">
      <div className="pl-offline-row">
        <Icon name="voice" size={18} />
        <b>Morning brief</b>
        <span className="pl-brief-meta">{meta}</span>
        <span className="mf-sp" />
        <button type="button" className="mf-btn pl-brief-listen" onClick={listen} aria-pressed={speaking}>
          <Icon name={speaking ? 'pause' : 'play'} size={18} />
          {speaking ? 'Pause' : 'Listen'}
        </button>
        <IconButton icon="copy" label="Copy brief" onClick={copy} />
        <IconButton icon="refresh" label="New morning brief" onClick={p.onAgain} />
        <IconButton icon={open ? 'expandLess' : 'expandMore'} label={open ? 'Hide brief' : 'Show brief'} onClick={() => setOpen(!open)} />
      </div>
      <div className="pl-brief-body" ref={bodyRef} hidden={!open}>
        {v.text ? (
          <p className="pl-brief-text">{v.text}</p>
        ) : (
          <>
            {v.hello ? <p className="pl-brief-hello">{v.hello}</p> : null}
            {v.segs?.length ? (
              <>
                <h3 className="pl-brief-label">The rundown</h3>
                <ol className="pl-brief-items">
                  {v.segs.map((g) => (
                    <li key={g.i}>
                      {title(g.i)}
                      {g.say ? <p>{g.say}</p> : null}
                    </li>
                  ))}
                </ol>
              </>
            ) : null}
            {v.picks?.length ? (
              <>
                <h3 className="pl-brief-label">{v.picks.length === 1 ? 'My pick for you' : 'My picks for you'}</h3>
                <ul className="pl-brief-items pl-brief-picks">
                  {v.picks.map((g) => (
                    <li key={g.i}>
                      {title(g.i)}
                      <p>{g.why}</p>
                    </li>
                  ))}
                </ul>
              </>
            ) : null}
            {v.bye ? <p className="pl-brief-hello">{v.bye}</p> : null}
          </>
        )}
        {Date.now() - (v.at ?? v.asked) > STALE_ASK_MS ? <small>The playlist may have changed since. Make a new brief to catch up.</small> : null}
      </div>
    </section>
  )
}
