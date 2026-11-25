import { useLiveQuery } from 'dexie-react-hooks'
import type { HistoryEvent } from '../lib/db'
import { actionText, byDay, historyOf, reasonText, timeText, type HistoryKind } from '../lib/history'
import { Icon } from './Icon'

const ICONS = { play: 'play', pause: 'pause', open: 'book', close: 'close' } as const

/**
 * One audiobook's, ebook's or ZIM site's history, newest first and grouped by day: when it was
 * played or paused (and why), opened or closed, where it was then and on which device. Tapping an
 * event with a place goes back there.
 */
export function HistoryList({ c, i, onJump }: { c: HistoryKind; i: string; onJump?: (e: HistoryEvent) => void }) {
  const events = useLiveQuery(() => historyOf(c, i), [c, i])
  if (!events) return null
  if (!events.length) return <p className="muted hist-empty">{c === 'audiobook' ? 'Plays and pauses show up here.' : 'Each time it is opened and closed shows up here.'}</p>
  return (
    <div className="hist">
      {byDay(events).map((g) => (
        <section key={g.day} aria-label={g.day}>
          <h3 className="hist-day">{g.day}</h3>
          <ol>
            {g.events.map((e) => {
              const reason = reasonText(e.r)
              const device = e.dn ?? ''
              const body = (
                <>
                  <span className={`hist-icon ${e.a}`} aria-hidden="true">
                    <Icon name={ICONS[e.a] ?? 'history'} size={18} />
                  </span>
                  <span className="hist-text">
                    <span className="hist-what">
                      {actionText(e.a)}
                      {reason ? <span className="hist-why"> · {reason}</span> : null}
                    </span>
                    <span className="hist-when">
                      {timeText(e.at)}
                      {device ? ` · ${device}` : ''}
                      {e.s ? ` · ${e.s}` : ''}
                    </span>
                  </span>
                  {e.p ? <span className="hist-chip">{e.p}</span> : null}
                </>
              )
              return (
                <li key={e.id}>
                  {onJump && e.x ? (
                    <button type="button" className="hist-row" onClick={() => onJump(e)} title="Go back here">
                      {body}
                    </button>
                  ) : (
                    <div className="hist-row">{body}</div>
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
