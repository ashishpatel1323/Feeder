import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { db, feedDisplayTitle, hideItems, unhideItems, type Feed, type Item } from '../lib/db'
import { summarize } from '../lib/extras'
import { htmlToText } from '../lib/feedparse'
import { timeAgo } from '../lib/feedstats'
import { countWords, paragraphsOf, takeWords, WheelFlipper } from '../lib/flip'
import { readAloud, type ReadAloudState } from '../lib/readaloud'
import { syncNow } from '../lib/sync2'
import type { Toast } from '../lib/toast'
import { Icon } from './Icon'
import { openAddToPlaylist } from './AddToPlaylist'
import { PodcastEpisode } from './Podcast'
import { YouTubeFrame } from './YouTubeFrame'
import { articleVideoId } from '../lib/youtube'
import { playQueue, useQueue } from '../lib/playqueue'
import { itemFacts } from '../lib/itemview'
import { TypeBadge, usePlaylistContext } from './ItemRow'

/**
 * Flip mode for desktop: one article per page, turned like a magazine page. The page sits in the
 * middle with "Up next" on the left and the summary and read aloud controls on the right. Opens
 * in browser full screen with the rest of the app hidden; Esc or ✕ leaves.
 */

interface Props {
  /** Article ids in list order when Flip mode was opened. */
  queue: string[]
  startId: string | null
  items: Item[]
  feeds: Map<string, Feed>
  rate: number
  onExit: () => void
  /** Read full article: leave Flip mode and open it in the reader. */
  onOpenInReader: (id: string) => void
  toast: Toast
}

const SPEAK_KEY = 'feeder.flipReadAloud'
const AUTO_TURN_KEY = 'feeder.flipAutoTurn'
const TURN_MS = 560
const RATES = [0.8, 1, 1.2, 1.4, 1.6, 2]

function loadFlag(key: string, fallback: boolean): boolean {
  try {
    const v = localStorage.getItem(key)
    return v === null ? fallback : v === '1'
  } catch {
    return fallback
  }
}
function saveFlag(key: string, on: boolean) {
  try {
    localStorage.setItem(key, on ? '1' : '0')
  } catch {
    /* private mode */
  }
}

const markRead = (it: Item | undefined) => {
  if (it && !it.read) void db.items.update(it.id, { read: 1, readTime: Date.now(), readSynced: 0 })
}

export function FlipMode(p: Props) {
  const byId = useMemo(() => new Map(p.items.map((i) => [i.id, i])), [p.items])
  // Deleted articles drop out of the queue; the order is the one from when Flip mode opened
  const [gone, setGone] = useState<Set<string>>(() => new Set())
  const queue = useMemo(() => p.queue.filter((id) => !gone.has(id) && byId.has(id)), [p.queue, gone, byId])
  const [curId, setCurId] = useState<string | null>(() => p.startId ?? p.queue[0] ?? null)
  const idx = Math.max(0, curId ? queue.indexOf(curId) : 0)
  const item = queue.length ? byId.get(queue[idx]) : undefined
  const feed = item ? p.feeds.get(item.feedUrl) : undefined
  const ctx = usePlaylistContext(p.feeds, p.rate)
  const facts = item ? itemFacts(item, ctx) : null
  const kind = facts?.kind ?? 'article'
  const videoId = item && kind === 'youtube' ? articleVideoId(item) : null
  const q = useQueue()

  const [speak, setSpeak] = useState(() => loadFlag(SPEAK_KEY, true))
  const [autoTurn, setAutoTurn] = useState(() => loadFlag(AUTO_TURN_KEY, true))
  const [ra, setRa] = useState<ReadAloudState>(readAloud.state)
  useEffect(() => readAloud.subscribe(setRa), [])
  const [summaryState, setSummaryState] = useState<{ id: string; status: 'loading' | 'error'; msg?: string } | null>(null)

  // Page turn: a copy of the page being left turns away over the new one (forward), or the new
  // page turns in over the old one (back)
  const pageRef = useRef<HTMLDivElement>(null)
  const bodyRef = useRef<HTMLDivElement>(null)
  const [leaf, setLeaf] = useState<{ html: string; key: number } | null>(null)
  const [turnBack, setTurnBack] = useState(0)

  const go = useCallback(
    (target: number) => {
      if (!queue.length || target === idx || target < 0 || target >= queue.length) return false
      const leaving = byId.get(queue[idx])
      markRead(leaving)
      // An episode started on this page stops when the page turns, like read aloud does
      if (leaving && playQueue.currentId === leaving.id) playQueue.stop()
      if (target > idx && pageRef.current) {
        setLeaf({ html: pageRef.current.innerHTML.replace(/<iframe[\s\S]*?<\/iframe>/g, ''), key: Date.now() })
        setTurnBack(0)
      } else {
        setLeaf(null)
        setTurnBack(Date.now())
      }
      setCurId(queue[target])
      return true
    },
    [queue, idx, byId],
  )
  const next = useCallback(() => go(idx + 1), [go, idx])
  const prev = useCallback(() => go(idx - 1), [go, idx])

  useEffect(() => {
    if (!leaf) return
    const t = window.setTimeout(() => setLeaf(null), TURN_MS + 40)
    return () => clearTimeout(t)
  }, [leaf])

  // The page's text: as many words as fit on the page, so it never scrolls and read aloud reads
  // exactly what's shown
  const paragraphs = useMemo(() => (item ? paragraphsOf(item.contentHtml || item.fullHtml || item.snippet) : []), [item?.id]) // eslint-disable-line react-hooks/exhaustive-deps
  const [shown, setShown] = useState<string[]>([])
  useLayoutEffect(() => {
    const body = bodyRef.current
    if (!body) return
    const fill = (ps: string[]) => {
      body.replaceChildren(...ps.map((t) => Object.assign(document.createElement('p'), { textContent: t })))
    }
    const fits = () => body.scrollHeight <= body.clientHeight + 1
    const total = countWords(paragraphs)
    fill(paragraphs)
    if (fits()) return setShown(paragraphs)
    let lo = 0
    let hi = Math.min(total, 600)
    while (lo < hi) {
      const mid = Math.ceil((lo + hi) / 2)
      fill(takeWords(paragraphs, mid))
      if (fits()) lo = mid
      else hi = mid - 1
    }
    const best = takeWords(paragraphs, lo)
    fill(best)
    setShown(best)
  }, [paragraphs, item?.imageUrl])

  // Read aloud follows the page: index what's shown, and start speaking once the turn settles
  useEffect(() => {
    const body = bodyRef.current
    if (!item || !body) return
    readAloud.title = item.title
    readAloud.artist = feedDisplayTitle(feed)
    readAloud.attach(body, item.id)
    if (!speak || kind === 'youtube') return
    const id = item.id
    const t = window.setTimeout(() => {
      // An episode plays itself; an article's page is read aloud
      if (kind === 'podcast') playQueue.start([id], { name: 'Flip mode' })
      else if (readAloud.state.itemId === id) readAloud.play(0)
    }, TURN_MS + 80)
    return () => clearTimeout(t)
  }, [shown, item?.id]) // eslint-disable-line react-hooks/exhaustive-deps

  // When the page has been read out, turn to the next one
  const nextRef = useRef(next)
  nextRef.current = next
  useEffect(() => {
    readAloud.onFinished = () => {
      if (autoTurn && !nextRef.current()) p.toast('That was the last article')
    }
    return () => {
      readAloud.onFinished = null
    }
  }, [autoTurn, p])

  // An episode played to its end turns the page too (Stop or Pause don't)
  const lastPlaying = useRef<string | null>(null)
  useEffect(() => {
    const now = q.ids[q.index] ?? null
    const was = lastPlaying.current
    lastPlaying.current = now
    if (!was || was === now || was !== item?.id || !autoTurn) return
    void db.items.get(was).then((it) => {
      if (it?.read && nextRef.current) nextRef.current()
    })
  }, [q.ids, q.index]) // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    readAloud.setRate(p.rate)
    return () => readAloud.stop()
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  const toggleSpeak = useCallback(() => {
    setSpeak((on) => {
      saveFlag(SPEAK_KEY, !on)
      if (on) readAloud.stop()
      else readAloud.play(0)
      return !on
    })
  }, [])
  const toggleAutoTurn = () =>
    setAutoTurn((on) => {
      saveFlag(AUTO_TURN_KEY, !on)
      return !on
    })
  const playPause = () => {
    if (ra.status === 'playing') readAloud.pause()
    else {
      if (!speak) {
        setSpeak(true)
        saveFlag(SPEAK_KEY, true)
      }
      readAloud.play()
    }
  }
  const cycleRate = () => {
    const i = RATES.findIndex((r) => r >= ra.rate - 0.01)
    readAloud.setRate(RATES[(i + 1) % RATES.length])
  }

  const doSummary = useCallback(async () => {
    if (!item || item.summary) return
    setSummaryState({ id: item.id, status: 'loading' })
    try {
      await summarize(item, htmlToText(item.fullHtml || item.contentHtml || item.snippet))
      setSummaryState(null)
    } catch (e) {
      setSummaryState({ id: item.id, status: 'error', msg: e instanceof Error ? e.message : String(e) })
    }
  }, [item])

  const toggleSave = useCallback(() => {
    if (item) void db.items.update(item.id, { saved: item.saved ? 0 : 1, readSynced: 0 })
  }, [item])

  const toggleRead = useCallback(() => {
    if (item) void db.items.update(item.id, { read: item.read ? 0 : 1, readTime: item.read ? null : Date.now(), readSynced: 0 })
  }, [item])

  const share = useCallback(async () => {
    const url = item?.link ?? ''
    if (!url) return
    if (navigator.share) {
      try {
        await navigator.share({ title: item?.title, url })
      } catch {
        // cancelled
      }
    } else {
      await navigator.clipboard.writeText(url).catch(() => undefined)
      p.toast('Link copied')
    }
  }, [item, p])

  const remove = useCallback(async () => {
    if (!item) return
    const copy = item
    readAloud.stop()
    const after = queue[idx + 1] ?? queue[idx - 1] ?? null
    if (pageRef.current && queue[idx + 1]) setLeaf({ html: pageRef.current.innerHTML, key: Date.now() })
    setGone((g) => new Set(g).add(copy.id))
    setCurId(after)
    await hideItems([copy.id])
    syncNow().catch(() => undefined)
    p.toast('Article deleted on all devices', {
      label: 'Undo',
      run: () => {
        setGone((g) => {
          const n = new Set(g)
          n.delete(copy.id)
          return n
        })
        setCurId(copy.id)
        void unhideItems([copy]).then(() => syncNow().catch(() => undefined))
      },
    })
  }, [item, queue, idx, p])

  const openFull = useCallback(() => {
    if (item) p.onOpenInReader(item.id)
  }, [item, p])

  // Browser full screen while Flip mode is open; leaving full screen (Esc) leaves Flip mode
  const onExit = p.onExit
  useEffect(() => {
    // The whole page goes full screen (not just this overlay) so toasts such as Undo still show
    const el = document.documentElement
    let entered = false
    const onChange = () => {
      if (document.fullscreenElement) entered = true
      else if (entered) onExit()
    }
    document.addEventListener('fullscreenchange', onChange)
    if (el?.requestFullscreen && !document.fullscreenElement) el.requestFullscreen().catch(() => undefined)
    return () => {
      document.removeEventListener('fullscreenchange', onChange)
      if (document.fullscreenElement) document.exitFullscreen().catch(() => undefined)
    }
  }, [onExit])

  // Keyboard
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.ctrlKey || e.metaKey || e.altKey) return
      if (/INPUT|TEXTAREA|SELECT/.test((e.target as HTMLElement).tagName)) return
      switch (e.key) {
        case 'ArrowRight':
        case 'ArrowDown':
        case 'PageDown':
        case 'j':
          next()
          break
        case 'ArrowLeft':
        case 'ArrowUp':
        case 'PageUp':
        case 'k':
          prev()
          break
        case ' ':
          if (e.shiftKey) prev()
          else next()
          break
        case 's':
          void doSummary()
          break
        case 'b':
          toggleSave()
          break
        case 'o':
          openFull()
          break
        case 'm':
          toggleRead()
          break
        case 'r':
          toggleSpeak()
          break
        case 'p':
          if (ra.status === 'playing') readAloud.pause()
          else readAloud.play()
          break
        case 'Delete':
        case 'Backspace':
          void remove()
          break
        case 'Escape':
          onExit()
          break
        default:
          return
      }
      e.preventDefault()
      e.stopPropagation()
    }
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  }, [next, prev, doSummary, toggleSave, toggleRead, openFull, toggleSpeak, remove, onExit, ra.status])

  // Mouse wheel and trackpad: one page per gesture, over the page area only
  const flipper = useMemo(() => new WheelFlipper(), [])
  const stageRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const el = stageRef.current
    if (!el) return
    const onWheel = (e: WheelEvent) => {
      e.preventDefault()
      const dir = flipper.push(e.deltaX, e.deltaY, e.timeStamp || performance.now())
      if (dir > 0) nextRef.current()
      else if (dir < 0) prevRef.current()
    }
    el.addEventListener('wheel', onWheel, { passive: false })
    return () => el.removeEventListener('wheel', onWheel)
  }, [flipper])
  const prevRef = useRef(prev)
  prevRef.current = prev

  // Keep the current article in view in Up next
  const railRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    railRef.current?.querySelector('.flip-next.now')?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
  }, [curId])

  const progress = queue.length ? (idx + 1) / queue.length : 0
  const loading = summaryState?.id === item?.id && summaryState?.status === 'loading'
  const summaryError = summaryState?.id === item?.id && summaryState?.status === 'error' ? summaryState.msg : null
  const sentenceProgress = ra.itemId === item?.id && ra.sentences ? Math.min(1, (ra.status === 'idle' ? 0 : ra.sentence + 1) / ra.sentences) : 0

  const pageInner = item ? (
    <>
      <div className="flip-src">{feedDisplayTitle(feed)}</div>
      <div className="flip-meta">
        {facts ? <TypeBadge kind={facts.kind} label={facts.kindLabel} /> : null}
        {[item.author, timeAgo(item.sortTime), facts?.lengthText].filter(Boolean).join(' · ')}
      </div>
      <h1 className="flip-title">{item.title || 'Untitled'}</h1>
      {videoId ? (
        <div className="flip-media tokens-light">
          <YouTubeFrame
            key={`${item.id}|${videoId}`}
            itemId={item.id}
            videoId={videoId}
            title={item.title || 'YouTube video'}
            onEnded={() => {
              if (autoTurn) nextRef.current()
            }}
          />
        </div>
      ) : kind === 'podcast' ? (
        <div className="flip-media pod tokens-light">
          <PodcastEpisode key={item.id} item={item} feedTitle={feedDisplayTitle(feed)} compact />
        </div>
      ) : item.imageUrl ? (
        <img className="flip-img" src={item.imageUrl} alt="" referrerPolicy="no-referrer" />
      ) : null}
    </>
  ) : null

  return (
    <div className="flip" role="dialog" aria-modal="true" aria-label="Flip mode">
      <div className="flip-progress">
        <i style={{ width: `${progress * 100}%` }} />
      </div>
      <header className="flip-top">
        <button className="flip-x" onClick={onExit} title="Leave Flip mode (Esc)" aria-label="Leave Flip mode">
          <Icon name="close" size={20} />
        </button>
        <span className="flip-count">
          Flip mode · {queue.length ? `${idx + 1} of ${queue.length}` : 'nothing left'}
        </span>
        <span className="flip-spacer" />
        <span className="flip-esc">Esc leaves full screen</span>
      </header>

      <nav className="flip-rail" ref={railRef} aria-label="Up next">
        <h4>Up next</h4>
        {queue.map((id, i) => {
          const it = byId.get(id)!
          if (i < idx - 3 || i > idx + 20) return null
          return (
            <button key={id} className={`flip-next${i === idx ? ' now' : ''}${it.read && i !== idx ? ' done' : ''}`} onClick={() => go(i)}>
              <small>
                {(() => {
                  const f = itemFacts(it, ctx)
                  return `${f.kindLabel} · ${feedDisplayTitle(p.feeds.get(it.feedUrl))}${f.lengthText ? ` · ${f.lengthText}` : ''}`
                })()}
                {i === idx ? ' · now' : ''}
              </small>
              {it.title || 'Untitled'}
            </button>
          )
        })}
      </nav>

      <div className="flip-stage" ref={stageRef}>
        {item ? (
          <>
            <div className="flip-stack s2" />
            <div className="flip-stack s1" />
            <article className={`flip-page${turnBack ? ' turn-in' : ''}`} key={turnBack ? `b${turnBack}` : 'page'}>
              <div ref={pageRef} className="flip-page-inner">
                {pageInner}
                <div className={`flip-body${item.imageUrl || kind !== 'article' ? ' with-img' : ''}`} ref={bodyRef} />
              </div>
              <div className="flip-actions">
                <button className={`flip-btn${item.saved ? ' on' : ''}`} onClick={toggleSave} title="Save (B)" aria-pressed={!!item.saved}>
                  <Icon name={item.saved ? 'bookmarkFilled' : 'bookmark'} size={18} /> {item.saved ? 'Saved' : 'Save'}
                </button>
                <button className="flip-btn" onClick={(e) => openAddToPlaylist([item.id], item.title, e.currentTarget)} title="Add to a playlist, or play next">
                  <Icon name="playlistAdd" size={18} /> Playlist
                </button>
                <button className="flip-btn" onClick={toggleRead} title="Mark read (M)">
                  <Icon name={item.read ? 'unread' : 'read'} size={18} /> {facts?.readLabel ?? 'Mark read'}
                </button>
                <button className="flip-btn pri" onClick={doSummary} disabled={loading || !!item.summary} title="Summarize (S)">
                  <Icon name="summarize" size={18} /> {loading ? 'Summarizing…' : item.summary ? 'Summarized' : 'Summarize'}
                </button>
                <button className="flip-btn" onClick={share} title="Share the link">
                  <Icon name="share" size={18} /> Share
                </button>
                <button className="flip-btn" onClick={openFull} title="Open in the reader (O)">
                  <Icon name="article" size={18} /> {kind === 'article' ? 'Read full article' : 'Open in reader'}
                </button>
                <span className="flip-spacer" />
                <button className="flip-btn del" onClick={remove} title="Delete on all devices (Delete)">
                  <Icon name="delete" size={18} /> Delete
                </button>
              </div>
            </article>
            {leaf ? <article key={leaf.key} className="flip-page flip-leaf" aria-hidden="true"><div className="flip-page-inner" dangerouslySetInnerHTML={{ __html: leaf.html }} /></article> : null}
          </>
        ) : (
          <div className="flip-empty">
            <p>You've flipped through everything.</p>
            <button className="flip-btn pri" onClick={onExit}>
              Back to the list
            </button>
          </div>
        )}
        <div className="flip-keys">
          <span><b>←</b><b>→</b> <b>J</b><b>K</b> flip</span>
          <span><b>S</b> summary</span>
          <span><b>R</b> read aloud</span>
          <span><b>O</b> full article</span>
          <span><b>Del</b> delete</span>
        </div>
      </div>

      <aside className="flip-panel" aria-label="Summary and read aloud">
        <h4>
          <Icon name="summarize" size={16} /> Summary
        </h4>
        <div className="flip-summary">
          {item?.summary ? (
            <div className="flip-summary-text">{item.summary}</div>
          ) : loading ? (
            <p className="flip-muted">Summarizing…</p>
          ) : summaryError ? (
            <p className="flip-muted">{summaryError}</p>
          ) : item ? (
            <button className="flip-btn ghost" onClick={doSummary}>
              <Icon name="summarize" size={18} /> Summarize this article
            </button>
          ) : null}
        </div>
        <div className="flip-player">
          <div className="flip-player-head">
            <span>
              <Icon name="headphones" size={18} /> {ra.status === 'playing' && ra.itemId === item?.id ? 'Reading this page' : speak ? 'Read aloud' : 'Read aloud is off'}
            </span>
            <button className={`flip-switch${speak ? ' on' : ''}`} onClick={toggleSpeak} role="switch" aria-checked={speak} title="Read each page aloud (R)">
              <i />
            </button>
          </div>
          <div className="flip-track">
            <i style={{ width: `${sentenceProgress * 100}%` }} />
          </div>
          <div className="flip-player-btns">
            <button className="flip-chip" onClick={playPause} disabled={!readAloud.supported}>
              <Icon name={ra.status === 'playing' ? 'pause' : 'play'} size={16} /> {ra.status === 'playing' ? 'Pause' : 'Play'}
            </button>
            <button className="flip-chip" onClick={cycleRate} title="Speed">
              {ra.rate.toFixed(1).replace(/\.0$/, '')}×
            </button>
            <button className={`flip-chip${autoTurn ? ' on' : ''}`} onClick={toggleAutoTurn} aria-pressed={autoTurn} title="Turn the page when reading ends">
              Auto-turn {autoTurn ? '✓' : ''}
            </button>
          </div>
        </div>
      </aside>
    </div>
  )
}
