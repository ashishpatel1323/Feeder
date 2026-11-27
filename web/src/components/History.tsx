import { useLiveQuery } from 'dexie-react-hooks'
import type { HistoryEvent } from '../lib/db'
import { actionText, byDay, historyOf, reasonText, timeText, type HistoryKind } from '../lib/history'
import { Icon } from './Icon'
import { HistoryGraphCard, type GraphChapter } from './HistoryGraph'

const ICONS = { play: 'play', pause: 'pause', open: 'book', close: 'close' } as const

/**
 * One audiobook's, ebook's or ZIM site's history, newest first and grouped by day: when it was
 * played or paused (and why), opened or closed, where it was then and on which device. Tapping an
 * event with a place goes back there.
 */
export function HistoryList({
  c,
  i,
  onJump,
  chapters,
  bookLengthMs,
}: {
  c: HistoryKind
  i: string
  onJump?: (e: HistoryEvent) => void
  /** An audiobook's chapters and length, for the chapter bar under the progress line */
  chapters?: GraphChapter[]
  bookLengthMs?: number
}) {
  const events = useLiveQuery(() => historyOf(c, i), [c, i])
  if (!events) return null
  if (!events.length) return <p className="muted hist-empty">{c === 'audiobook' ? 'Plays and pauses show up here.' : 'Each time it is opened and closed shows up here.'}</p>
  return (
    <div className="hist">
      <HistoryGraphCard c={c} i={i} events={events} chapters={chapters} bookLengthMs={bookLengthMs} />
      {byDay(events).map((g) => (
        <section key={g.day} aria-label={g.day}>
          <h3 className="hist-day">{g.day}</h3>
          <ol className="hist-card">
            {g.events.map((e, k) => {
              const reason = reasonText(e.r)
              // The line down to the older event: solid while it was playing (or open), dotted while paused
              const older = g.events[k + 1]
              const line = !older ? '' : older.a === 'play' || older.a === 'open' ? ' line' : ' dots'
              const body = (
                <>
                  <span className="hist-time">{timeText(e.at)}</span>
                  <span className={`hist-rail${line}`}>
                    <span className={`hist-icon ${e.a}`} aria-hidden="true">
                      <Icon name={ICONS[e.a] ?? 'history'} size={18} />
                    </span>
                  </span>
                  <span className="hist-text">
                    <span className="hist-what">
                      {actionText(e.a)}
                      {reason ? <span className="hist-why"> · {reason}</span> : null}
                    </span>
                    <span className="hist-where">
                      {e.p ? <span className="hist-chip">{e.p}</span> : null}
                      {e.dn ? <span className="hist-device">{e.dn}</span> : null}
                    </span>
                  </span>
                </>
              )
              return (
                <li key={e.id}>
                  {onJump && e.x ? (
                    <button type="button" className="hist-row" onClick={() => onJump(e)} title={e.s ? `${e.s}: go back here` : 'Go back here'}>
                      {body}
                    </button>
                  ) : (
                    <div className="hist-row" title={e.s || undefined}>
                      {body}
                    </div>
                  )}
                </li>
              )
            })}
          </ol>
        </section>
      ))}
    </div>
  )
}
