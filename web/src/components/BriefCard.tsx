/**
 * A playlist's brief (lib/briefs.ts) at the top of the playlist: waiting for the Mac, being written,
 * failed with Try again, or the big picture and one summary per article (tap a title to read it).
 */
import { useEffect, useState } from 'react'
import { briefAsText, isPending, STALE_ASK_MS, type StoredBrief } from '../lib/briefs'
import { agoText } from '../lib/device'
import { Icon, IconButton } from './Icon'

const POLL_MS = 20_000

export function BriefCard(p: {
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
                ? `Brief of ${total} article${total === 1 ? '' : 's'} · waiting for your Mac`
                : reading
                  ? `Claude is reading the articles · ${v.n ?? 0} of ${total}`
                  : 'Claude is writing the brief'}
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
            <div style={{ width: `${reading ? Math.round(((v.n ?? 0) / Math.max(1, total)) * 70) : 85}%` }} />
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

  const meta = `${total} article${total === 1 ? '' : 's'} · by Claude${v.at ? ` · ${agoText(v.at)}` : ''}`
  return (
    <section className="pl-offline-card pl-brief">
      <div className="pl-offline-row">
        <Icon name="summarize" size={18} />
        <b>Brief</b>
        <span className="pl-brief-meta">{meta}</span>
        <span className="mf-sp" />
        <IconButton icon="copy" label="Copy brief" onClick={copy} />
        <IconButton icon="refresh" label="New brief" onClick={p.onAgain} />
        <IconButton icon={open ? 'expandLess' : 'expandMore'} label={open ? 'Hide brief' : 'Show brief'} onClick={() => setOpen(!open)} />
      </div>
      {open ? (
        <div className="pl-brief-body">
          {v.text ? (
            <p className="pl-brief-text">{v.text}</p>
          ) : (
            <>
              {v.ov?.length ? (
                <>
                  <h3 className="pl-brief-label">The big picture</h3>
                  <ul className="pl-brief-ov">
                    {v.ov.map((s, i) => (
                      <li key={i}>{s}</li>
                    ))}
                  </ul>
                </>
              ) : null}
              <h3 className="pl-brief-label">Article by article</h3>
              <ol className="pl-brief-items">
                {v.items.map((it, i) =>
                  v.sums?.[i] ? (
                    <li key={it.id}>
                      {p.canOpen(it.id) ? (
                        <button type="button" className="pl-brief-title" onClick={() => p.onOpen(it.id)}>
                          {it.t}
                        </button>
                      ) : it.u ? (
                        <a className="pl-brief-title" href={it.u} target="_blank" rel="noopener noreferrer">
                          {it.t}
                        </a>
                      ) : (
                        <span className="pl-brief-title">{it.t}</span>
                      )}
                      <p>{v.sums[i]}</p>
                    </li>
                  ) : null,
                )}
              </ol>
            </>
          )}
          {Date.now() - (v.at ?? v.asked) > STALE_ASK_MS ? <small>The playlist may have changed since. Make a new brief to catch up.</small> : null}
        </div>
      ) : null}
    </section>
  )
}
