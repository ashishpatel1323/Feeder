/**
 * A transcript under its chapter headings, the same look for YouTube videos and audiobooks (and
 * Android's TranscriptView): other chapters folded to one line so the list reads like a chapter
 * list, the chapter playing open and following playback, its heading stuck to the top. Search
 * and Copy sit next to the status line; while searching, every line shows with the matches marked.
 * Tap a line, or a chapter with no lines, to jump there.
 */
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { cueIndexAt, findMatches, transcriptPlainText, type TranscriptCue, type TranscriptMatch } from '../lib/transcriptFormats'
import { sectionIndexAt, type TranscriptSection } from '../lib/transcriptSections'
import { Icon } from './Icon'

const FOLLOW_AGAIN_AFTER_MS = 5000

export function formatCueTime(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000))
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = String(total % 60).padStart(2, '0')
  return h ? `${h}:${String(m).padStart(2, '0')}:${s}` : `${m}:${s}`
}

export function ChapterTranscript({
  cues,
  sections,
  positionMs,
  onSeek,
  status,
  resetKey,
  title,
  pendingText = null,
  ready = true,
}: {
  cues: TranscriptCue[]
  sections: TranscriptSection[]
  positionMs: number
  onSeek: (ms: number) => void
  /** The status line (or an intro with a button) above the lines */
  status: ReactNode
  /** A new video or book: search and folding start over */
  resetKey: string
  /** Heads the copied text */
  title: string
  /** Shown under the playing chapter while it has no lines yet */
  pendingText?: string | null
  /** False while there is nothing to follow (the Now playing button stays hidden) */
  ready?: boolean
}) {
  const current = cueIndexAt(cues, positionMs)
  const currentSection = Math.max(0, sectionIndexAt(sections, positionMs))
  const [open, setOpen] = useState<Set<number>>(new Set())
  useEffect(() => setOpen(new Set()), [resetKey])
  useEffect(() => {
    setOpen((o) => (o.has(currentSection) ? o : new Set(o).add(currentSection)))
  }, [currentSection, resetKey])

  // Search
  const [searchOpen, setSearchOpen] = useState(false)
  const [query, setQuery] = useState('')
  const [active, setActive] = useState(0)
  useEffect(() => {
    setSearchOpen(false)
    setQuery('')
  }, [resetKey])
  const matches = useMemo(() => (searchOpen ? findMatches(cues, query) : []), [searchOpen, cues, query])
  const searching = searchOpen && !!query.trim()
  useEffect(() => {
    if (!matches.length) return
    const next = matches.findIndex((m) => m.cue >= current)
    setActive(next >= 0 ? next : 0)
  }, [query, matches.length > 0])
  const step = (by: number) => matches.length && setActive((a) => (a + by + matches.length) % matches.length)
  const closeSearch = () => {
    setSearchOpen(false)
    setQuery('')
  }

  const [copied, setCopied] = useState(false)
  const copyAll = async () => {
    try {
      await navigator.clipboard.writeText(transcriptPlainText(cues, title))
      setCopied(true)
      window.setTimeout(() => setCopied(false), 2000)
    } catch {
      // clipboard refused
    }
  }

  // Following playback
  const list = useRef<HTMLDivElement>(null)
  const [following, setFollowing] = useState(true)
  const onHandScroll = () => setFollowing(false)
  useEffect(() => {
    if (following) return
    const id = window.setTimeout(() => setFollowing(true), FOLLOW_AGAIN_AFTER_MS)
    return () => clearTimeout(id)
  }, [following, positionMs])
  const scrollToNow = (smooth: boolean) => {
    const box = list.current
    if (!box) return
    const line = box.querySelector<HTMLElement>('[data-now="1"]') ?? box.querySelector<HTMLElement>('[data-playing-heading="1"]')
    if (!line) return
    // Under the stuck heading, with one past line still in view
    const heading = box.querySelector<HTMLElement>('.vtp-heading.playing')
    const offset = (line.dataset.now ? (heading?.offsetHeight ?? 0) : 0) + 44
    box.scrollTo({ top: Math.max(0, line.offsetTop - offset), behavior: smooth ? 'smooth' : 'auto' })
  }
  useEffect(() => {
    if (following && !searching) scrollToNow(true)
  }, [current, currentSection, following, open, searching])
  const selected = matches[active] ?? null
  useEffect(() => {
    if (!selected) return
    list.current?.querySelector<HTMLElement>(`[data-cue="${selected.cue}"]`)?.scrollIntoView({ block: 'center', behavior: 'smooth' })
  }, [selected])

  const line = (i: number, marks: TranscriptMatch[] = []) => (
    <Line key={i} index={i} current={current} startMs={cues[i].startMs} text={cues[i].text} marks={marks} selected={selected} onSeek={onSeek} />
  )

  let body: ReactNode = null
  if (searching) {
    const byCue = new Map<number, TranscriptMatch[]>()
    for (const m of matches) byCue.set(m.cue, [...(byCue.get(m.cue) ?? []), m])
    body = <ul className="transcript-lines vtp-lines">{cues.map((_, i) => line(i, byCue.get(i)))}</ul>
  } else if (!sections.length) {
    body = cues.length ? <ul className="transcript-lines vtp-lines">{cues.map((_, i) => line(i))}</ul> : null
  } else {
    body = sections.map((s, si) => {
      const isOpen = open.has(si)
      const empty = s.endCue <= s.firstCue
      return (
        <section key={si} className="vtp-section">
          <button
            type="button"
            className={`vtp-heading${si === currentSection ? ' playing' : ''}`}
            data-playing-heading={si === currentSection ? '1' : undefined}
            aria-expanded={empty ? undefined : isOpen}
            onClick={() => {
              if (empty) return onSeek(s.startMs)
              setOpen((o) => {
                const n = new Set(o)
                if (n.has(si)) n.delete(si)
                else n.add(si)
                return n
              })
            }}
          >
            <span className="transcript-time vtp-heading-time">{formatCueTime(s.startMs)}</span>
            <span className="vtp-heading-title">{s.title}</span>
            {empty ? null : <Icon name={isOpen ? 'collapse' : 'expand'} size={20} />}
          </button>
          {isOpen && !empty ? <ul className="transcript-lines vtp-lines">{cues.slice(s.firstCue, s.endCue).map((_, k) => line(s.firstCue + k))}</ul> : null}
          {empty && si === currentSection && pendingText ? <p className="transcript-status vtp-pending">{pendingText}</p> : null}
        </section>
      )
    })
  }

  return (
    <div className="vtp" aria-label="Transcript">
      <div className="transcript-head">
        <div className="transcript-head-status">{status}</div>
        {cues.length && !searchOpen ? (
          <>
            <button type="button" className="icon-btn transcript-search-btn" title="Search the transcript" aria-label="Search the transcript" onClick={() => setSearchOpen(true)}>
              <Icon name="search" size={20} />
            </button>
            <button type="button" className="icon-btn transcript-search-btn" title={copied ? 'Copied' : 'Copy the whole transcript'} aria-label="Copy the whole transcript" onClick={() => void copyAll()}>
              <Icon name={copied ? 'check' : 'copy'} size={20} />
            </button>
          </>
        ) : null}
      </div>
      {searchOpen ? (
        <div className="transcript-search" role="search">
          <Icon name="search" size={18} />
          <input
            autoFocus
            type="search"
            placeholder="Search the transcript"
            aria-label="Search the transcript"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault()
                step(e.shiftKey ? -1 : 1)
              } else if (e.key === 'Escape') {
                closeSearch()
              }
              // Typing here must not trigger the page's keyboard shortcuts
              e.stopPropagation()
            }}
          />
          <span className="transcript-search-count">{query.trim() ? (matches.length ? `${active + 1} of ${matches.length}` : 'No matches') : ''}</span>
          <button type="button" className="icon-btn" title="Previous match (Shift+Enter)" aria-label="Previous match" disabled={!matches.length} onClick={() => step(-1)}>
            <Icon name="arrowUp" size={20} />
          </button>
          <button type="button" className="icon-btn" title="Next match (Enter)" aria-label="Next match" disabled={!matches.length} onClick={() => step(1)}>
            <Icon name="arrowDown" size={20} />
          </button>
          <button type="button" className="icon-btn" title="Close search" aria-label="Close search" onClick={closeSearch}>
            <Icon name="close" size={20} />
          </button>
        </div>
      ) : null}
      <div className="vtp-scroll" ref={list} onWheel={onHandScroll} onTouchMove={onHandScroll}>
        {body}
      </div>
      {!following && !searching && ready && cues.length ? (
        <button
          type="button"
          className="mf-btn primary vtp-now"
          onClick={() => {
            setFollowing(true)
            scrollToNow(true)
          }}
        >
          <Icon name="myLocation" size={18} /> Now playing
        </button>
      ) : null}
    </div>
  )
}

function markedText(text: string, marks: TranscriptMatch[], selected: TranscriptMatch | null): ReactNode {
  if (!marks.length) return text
  const parts: ReactNode[] = []
  let at = 0
  marks.forEach((m, i) => {
    if (m.start > at) parts.push(text.slice(at, m.start))
    parts.push(
      <mark key={i} className={m === selected ? 'active' : undefined}>
        {text.slice(m.start, m.end)}
      </mark>,
    )
    at = m.end
  })
  if (at < text.length) parts.push(text.slice(at))
  return parts
}

function Line({
  index,
  current,
  startMs,
  text,
  marks,
  selected,
  onSeek,
}: {
  index: number
  current: number
  startMs: number
  text: string
  marks: TranscriptMatch[]
  selected: TranscriptMatch | null
  onSeek: (ms: number) => void
}) {
  const now = index === current
  return (
    <li className={now ? 'now' : index < current ? 'past' : undefined} data-now={now ? '1' : undefined} data-cue={index}>
      <button type="button" onClick={() => onSeek(startMs)}>
        <span className="transcript-time">{formatCueTime(startMs)}</span>
        <span className="transcript-text">{markedText(text, marks, selected)}</span>
      </button>
    </li>
  )
}
