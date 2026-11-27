import { useLiveQuery } from 'dexie-react-hooks'
import { useMemo, type ReactNode } from 'react'
import { db, type HistoryEvent, type RecentEntry } from '../lib/db'
import { clockText, type HistoryKind } from '../lib/history'
import { chapterAt, countByDay, daysText, graphOf, minutesText, type HistoryGraph } from '../lib/historyStats'

export interface GraphChapter {
  bookMs: number
  lengthMs: number
  title?: string
}

const WEEKDAY = new Intl.DateTimeFormat(undefined, { weekday: 'narrow' })
const DAY = new Intl.DateTimeFormat(undefined, { weekday: 'short', day: 'numeric', month: 'short' })
const SHORT = new Intl.DateTimeFormat(undefined, { day: 'numeric', month: 'short' })

/**
 * The card at the top of an item's History: minutes a day over the last 14 days with this week's
 * total and the streak, then how far through it is over time (a ZIM site has no end, so it shows
 * pages visited a day instead). Everything comes from the history already recorded.
 */
export function HistoryGraphCard({ c, i, events, chapters, bookLengthMs }: { c: HistoryKind; i: string; events: HistoryEvent[]; chapters?: GraphChapter[]; bookLengthMs?: number }) {
  const g = useMemo(() => graphOf(events), [events])
  // ZIM pages come from central History (recent.ts): one entry per page, at its last visit
  const zimPages = useLiveQuery(
    (): Promise<RecentEntry[]> => (c === 'zim' ? db.recent.where('key').startsWith(`zim:${i}\n`).toArray() : Promise.resolve([])),
    [c, i],
  )
  if (!events.length) return null
  const unit = c === 'audiobook' ? 'listening' : 'reading'
  return (
    <section className="hgraph" aria-label="Progress">
      <div className="hgraph-col">
        <div className="hgraph-stats">
          <span className="hgraph-big">
            {minutesText(g.week)} <span className="hgraph-sub">{unit} this week</span>
          </span>
          {g.streak > 1 ? <span className="hgraph-streak">{g.streak}-day streak</span> : null}
        </div>
        <p className="hgraph-label">Minutes a day · last 14 days</p>
        <Bars g={g} values={g.minutes} what={(v) => minutesText(v)} short={(v) => `${v}m`} />
      </div>
      <div className="hgraph-col">
        {c === 'zim' ? <ZimPages g={g} entries={(zimPages ?? []).filter((e) => !e.del && e.a === 'open')} /> : <Progress c={c} g={g} events={events} chapters={chapters} bookLengthMs={bookLengthMs} />}
      </div>
    </section>
  )
}

function Bars({ g, values, what, short, small }: { g: HistoryGraph; values: number[]; what: (v: number) => string; short: (v: number) => string; small?: boolean }) {
  const max = Math.max(1, ...values)
  const top = values.indexOf(max)
  const last = values.length - 1
  const summary = values.map((v, k) => `${DAY.format(g.days[k])}: ${what(v)}`).join(', ')
  return (
    <div className={`hgraph-bars${small ? ' small' : ''}`} role="img" aria-label={summary}>
      {values.map((v, k) => (
        <div key={g.days[k]} className={`hgraph-day${k === last ? ' today' : ''}`} title={`${DAY.format(g.days[k])}: ${what(v)}`}>
          <div className="hgraph-slot">
            {v > 0 && k === top ? <span className="hgraph-max">{short(v)}</span> : null}
            <span className={`hgraph-bar${v ? '' : ' none'}`} style={v ? { height: `${Math.max(6, (v / max) * 100)}%` } : undefined} />
          </div>
          <span className="hgraph-wd" aria-hidden="true">
            {WEEKDAY.format(g.days[k])}
          </span>
        </div>
      ))}
    </div>
  )
}

function Progress({ c, g, events, chapters, bookLengthMs }: { c: HistoryKind; g: HistoryGraph; events: HistoryEvent[]; chapters?: GraphChapter[]; bookLengthMs?: number }) {
  const f = g.f
  if (f === null || g.progress.length === 0) return <p className="hgraph-label">Progress shows up here after the next pause or close.</p>
  const from = g.days[0]
  const to = Math.max(Date.now(), g.progress[g.progress.length - 1].at)
  let lo = Math.min(...g.progress.map((p) => p.f))
  let hi = Math.max(...g.progress.map((p) => p.f))
  if (hi - lo < 0.02) {
    lo = Math.max(0, lo - 0.01)
    hi = Math.min(1, hi + 0.01)
  }
  const X = (t: number) => ((t - from) / (to - from || 1)) * 100
  const Y = (v: number) => 36 - ((v - lo) / (hi - lo || 1)) * 32
  const pts = [...g.progress, { at: to, f }]
  const step = c === 'ebook'
  const d = pts.map((p, k) => (k === 0 ? `M${X(p.at)} ${Y(p.f)}` : step ? `H${X(p.at)} V${Y(p.f)}` : `L${X(p.at)} ${Y(p.f)}`)).join(' ')
  const end = c === 'ebook' && g.page ? `p. ${g.page.p}` : `${Math.round(f * 100)}%`
  const last = [...events].sort((a, b) => b.at - a.at)

  let detail: ReactNode = null
  if (c === 'audiobook' && chapters && chapters.length > 1 && bookLengthMs) {
    const at = chapterAt(chapters, bookLengthMs, f)
    if (at) {
      const many = chapters.length > 40
      detail = (
        <>
          <p className="hgraph-label hgraph-split">
            <span>
              Ch. {at.k + 1} of {chapters.length}
              {chapters[at.k].title ? ` · ${chapters[at.k].title}` : ''}
            </span>
            <b>{at.k} done</b>
          </p>
          {many ? (
            <span className="hgraph-track" aria-hidden="true">
              <span style={{ width: `${f * 100}%` }} />
            </span>
          ) : (
            <span className="hgraph-chapters" aria-hidden="true">
              {chapters.map((_, k) => (
                <span key={k} className={k < at.k ? 'done' : ''} style={k === at.k ? { background: `linear-gradient(90deg, var(--accent) ${at.into * 100}%, var(--surface-3) ${at.into * 100}%)` } : undefined} />
              ))}
            </span>
          )}
        </>
      )
    }
  } else if (c === 'audiobook' && bookLengthMs) {
    detail = (
      <>
        <p className="hgraph-label hgraph-split">
          <span>
            {clockText(f * bookLengthMs)} of {clockText(bookLengthMs)}
          </span>
          <b>{minutesText(Math.round(((1 - f) * bookLengthMs) / 60_000))} left</b>
        </p>
        <span className="hgraph-track" aria-hidden="true">
          <span style={{ width: `${f * 100}%` }} />
        </span>
      </>
    )
  } else if (c === 'ebook') {
    const title = last.find((e) => e.s)?.s
    detail =
      title || g.page ? (
        <p className="hgraph-label hgraph-split">
          <span>{title ?? ''}</span>
          {g.page ? <b>{g.page.n - g.page.p} pages left</b> : null}
        </p>
      ) : null
  }

  const pace = c === 'ebook' && g.page && g.fPerDay ? `${Math.max(1, Math.round(g.fPerDay * g.page.n))} pages a day` : `${g.minutesPerDay} min a day`
  const foot = f >= 0.999 ? 'Finished' : g.daysLeft ? `About ${daysText(g.daysLeft)} left at this pace (${pace})` : null

  return (
    <>
      <p className="hgraph-label">Progress</p>
      <div className="hgraph-line">
        <svg viewBox="0 0 100 40" preserveAspectRatio="none" aria-hidden="true">
          <line x1="0" x2="100" y1="38" y2="38" className="hgraph-axis" vectorEffect="non-scaling-stroke" />
          <path d={`${d} L${X(to)} 38 L${X(from)} 38 Z`} className="hgraph-area" />
          <path d={d} className="hgraph-path" vectorEffect="non-scaling-stroke" />
        </svg>
        <span className="hgraph-dot" style={{ left: `${X(to)}%`, top: `${(Y(f) / 40) * 100}%` }} />
        <span className="hgraph-end" style={{ top: `${(Y(f) / 40) * 100}%` }}>
          {end}
        </span>
      </div>
      <p className="hgraph-axis-labels" aria-hidden="true">
        <span>{SHORT.format(from)}</span>
        <span className="today">Today</span>
      </p>
      {detail}
      {foot ? <p className="hgraph-foot">{foot}</p> : null}
    </>
  )
}

function ZimPages({ g, entries }: { g: HistoryGraph; entries: { at: number; n: string }[] }) {
  const pages = countByDay(entries.map((e) => e.at))
  const total = pages.reduce((s, n) => s + n, 0)
  const latest = [...entries].sort((a, b) => b.at - a.at)[0]
  return (
    <>
      <p className="hgraph-label hgraph-split">
        <span>Pages visited a day</span>
        <b>{total === 1 ? '1 page' : `${total} pages`}</b>
      </p>
      <Bars g={g} values={pages} what={(v) => (v === 1 ? '1 page' : `${v} pages`)} short={(v) => String(v)} small />
      {latest ? <p className="hgraph-foot">Last page: {latest.n}</p> : null}
      <p className="hgraph-note">No progress line: a ZIM site has no end. Each page counts once, on the day it was last opened.</p>
    </>
  )
}
