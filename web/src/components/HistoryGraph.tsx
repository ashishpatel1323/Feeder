import { useLiveQuery } from 'dexie-react-hooks'
import { useMemo, useState, type ReactNode } from 'react'
import { db, type HistoryEvent, type RecentEntry } from '../lib/db'
import { clockText, type HistoryKind } from '../lib/history'
import { chapterAt, countByDay, countByHour, dayStart, daysText, graphOf, hourIndex, hoursOfToday, minutesByHour, minutesText, progressFrom, sessions, type HistoryGraph } from '../lib/historyStats'

export interface GraphChapter {
  bookMs: number
  lengthMs: number
  title?: string
}

const WEEKDAY = new Intl.DateTimeFormat(undefined, { weekday: 'narrow' })
const DAY = new Intl.DateTimeFormat(undefined, { weekday: 'short', day: 'numeric', month: 'short' })
const SHORT = new Intl.DateTimeFormat(undefined, { day: 'numeric', month: 'short' })
const HOUR = new Intl.DateTimeFormat(undefined, { hour: 'numeric' })

/** What the bars and the progress line cover: today hour by hour, or the last 14 days */
type Range = 'today' | '14d'
const RANGE_KEY = 'feeder.historyRange'

function useRange(): [Range, (r: Range) => void] {
  const [range, setRange] = useState<Range>(() => {
    try {
      return localStorage.getItem(RANGE_KEY) === 'today' ? 'today' : '14d'
    } catch {
      return '14d'
    }
  })
  return [
    range,
    (r: Range) => {
      setRange(r)
      try {
        localStorage.setItem(RANGE_KEY, r)
      } catch {
        // private window: the choice lasts until the page closes
      }
    },
  ]
}

/** One bar's place on the strip: its axis label (often none), tooltip, and whether it is now or still to come */
interface Slot {
  key: number
  label: string
  title: string
  current: boolean
  future?: boolean
}

function daySlots(g: HistoryGraph): Slot[] {
  return g.days.map((d, k) => ({ key: d, label: WEEKDAY.format(d), title: DAY.format(d), current: k === g.days.length - 1 }))
}

/** Today's hours: 00, 06, 12, 18 and Now under the bars, later hours greyed out */
function hourSlots(now: number): Slot[] {
  const at = hourIndex(now)
  return hoursOfToday(now).map((h, k) => {
    const hour = new Date(h).getHours()
    const label = k === at ? 'Now' : hour % 6 === 0 && Math.abs(k - at) > 1 ? String(hour).padStart(2, '0') : ''
    return { key: h, label, title: HOUR.format(h), current: k === at, future: k > at }
  })
}

function RangeSwitch({ range, setRange }: { range: Range; setRange: (r: Range) => void }) {
  return (
    <div className="segmented hgraph-range" role="radiogroup" aria-label="Graph covers">
      {(['today', '14d'] as const).map((r) => (
        <button key={r} type="button" role="radio" aria-checked={range === r} className={`seg${range === r ? ' on' : ''}`} onClick={() => setRange(r)}>
          {r === 'today' ? 'Today' : '14d'}
        </button>
      ))}
    </div>
  )
}

/**
 * The card at the top of an item's History: minutes a day over the last 14 days (or, with the
 * Today | 14d switch, minutes an hour today, which the progress line follows too) with this week's
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
  const [range, setRange] = useRange()
  const now = Date.now()
  if (!events.length) return null
  const today = range === 'today' ? minutesByHour(sessions(events, now), now).minutes : []
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
        <div className="hgraph-label hgraph-split hgraph-head">
          <span>{range === 'today' ? 'Minutes an hour · today' : 'Minutes a day · last 14 days'}</span>
          <RangeSwitch range={range} setRange={setRange} />
        </div>
        {range === 'today' ? (
          <Bars slots={hourSlots(now)} values={today} what={(v) => minutesText(v)} short={(v) => `${v}m`} hours />
        ) : (
          <Bars slots={daySlots(g)} values={g.minutes} what={(v) => minutesText(v)} short={(v) => `${v}m`} />
        )}
      </div>
      <div className="hgraph-col">
        {c === 'zim' ? (
          <ZimPages g={g} range={range} now={now} entries={(zimPages ?? []).filter((e) => !e.del && e.a === 'open')} />
        ) : (
          <Progress c={c} g={g} range={range} now={now} events={events} chapters={chapters} bookLengthMs={bookLengthMs} />
        )}
      </div>
    </section>
  )
}

function Bars({ slots, values, what, short, small, hours }: { slots: Slot[]; values: number[]; what: (v: number) => string; short: (v: number) => string; small?: boolean; hours?: boolean }) {
  const max = Math.max(1, ...values)
  const top = values.indexOf(max)
  const summary = slots
    .filter((s) => !s.future)
    .map((s, k) => `${s.title}: ${what(values[k] ?? 0)}`)
    .join(', ')
  return (
    <div className={`hgraph-bars${small ? ' small' : ''}${hours ? ' hours' : ''}`} role="img" aria-label={summary}>
      {slots.map((s, k) => {
        const v = values[k] ?? 0
        return (
          <div key={s.key} className={`hgraph-day${s.current ? ' today' : ''}${s.future ? ' future' : ''}`} title={s.future ? undefined : `${s.title}: ${what(v)}`}>
            <div className="hgraph-slot">
              {v > 0 && k === top ? <span className="hgraph-max">{short(v)}</span> : null}
              <span className={`hgraph-bar${v ? '' : ' none'}`} style={v ? { height: `${Math.max(6, (v / max) * 100)}%` } : undefined} />
            </div>
            <span className="hgraph-wd" aria-hidden="true">
              {s.label}
            </span>
          </div>
        )
      })}
    </div>
  )
}

function Progress({ c, g, range, now, events, chapters, bookLengthMs }: { c: HistoryKind; g: HistoryGraph; range: Range; now: number; events: HistoryEvent[]; chapters?: GraphChapter[]; bookLengthMs?: number }) {
  const f = g.f
  const isToday = range === 'today'
  const from = isToday ? dayStart(now) : g.days[0]
  const points = isToday ? progressFrom(events, from) : g.progress
  if (f === null || points.length === 0) return <p className="hgraph-label">Progress shows up here after the next pause or close.</p>
  const to = Math.max(now, points[points.length - 1].at)
  const gain = Math.round((f - points[0].f) * 100)
  let lo = Math.min(...points.map((p) => p.f))
  let hi = Math.max(...points.map((p) => p.f))
  if (hi - lo < 0.02) {
    lo = Math.max(0, lo - 0.01)
    hi = Math.min(1, hi + 0.01)
  }
  const X = (t: number) => ((t - from) / (to - from || 1)) * 100
  const Y = (v: number) => 36 - ((v - lo) / (hi - lo || 1)) * 32
  const pts = [...points, { at: to, f }]
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
      {isToday ? (
        <p className="hgraph-label hgraph-split">
          <span>Progress · today</span>
          {gain > 0 ? <b>+ {gain}% today</b> : null}
        </p>
      ) : (
        <p className="hgraph-label">Progress</p>
      )}
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
        <span>{isToday ? '00:00' : SHORT.format(from)}</span>
        <span className="today">{isToday ? 'Now' : 'Today'}</span>
      </p>
      {detail}
      {foot ? <p className="hgraph-foot">{foot}</p> : null}
    </>
  )
}

function ZimPages({ g, range, now, entries }: { g: HistoryGraph; range: Range; now: number; entries: { at: number; n: string }[] }) {
  const isToday = range === 'today'
  const times = entries.map((e) => e.at)
  const pages = isToday ? countByHour(times, now) : countByDay(times, now)
  const total = pages.reduce((s, n) => s + n, 0)
  const latest = [...entries].sort((a, b) => b.at - a.at)[0]
  return (
    <>
      <p className="hgraph-label hgraph-split">
        <span>{isToday ? 'Pages visited · today' : 'Pages visited a day'}</span>
        <b>{total === 1 ? '1 page' : `${total} pages`}</b>
      </p>
      <Bars slots={isToday ? hourSlots(now) : daySlots(g)} values={pages} what={(v) => (v === 1 ? '1 page' : `${v} pages`)} short={(v) => String(v)} small hours={isToday} />
      {latest ? <p className="hgraph-foot">Last page: {latest.n}</p> : null}
      <p className="hgraph-note">No progress line: a ZIM site has no end. Each page counts once, on the day it was last opened.</p>
    </>
  )
}
