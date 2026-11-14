import { useEffect, useMemo, useRef, useState } from 'react'
import { db, feedDisplayTitle, type Feed, type Item } from '../lib/db'
import { formatPoints, readingMinutes, wordsOf } from '../lib/listquery'
import { hnDiscussionUrl, loadFullText, sanitize, summarize } from '../lib/extras'
import { htmlToText } from '../lib/feedparse'
import { readAloud, type ReadAloudState } from '../lib/readaloud'
import type { Settings } from '../lib/settings'
import { Icon, IconButton } from './Icon'
import { Player } from './Player'

interface Props {
  item: Item
  feed: Feed | undefined
  settings: Settings
  hasPrev: boolean
  hasNext: boolean
  nextTitle: string | null
  onPrev: () => void
  onNext: () => void
  onBack: () => void
  autoplay: boolean
  onAutoplayConsumed: () => void
  toast: (msg: string) => void
}

export function Reader(p: Props) {
  const { item, feed } = p
  const bodyRef = useRef<HTMLDivElement>(null)
  const scrollRef = useRef<HTMLDivElement>(null)
  const [fullMode, setFullMode] = useState<boolean>(!!feed?.fullTextByDefault)
  const [fullState, setFullState] = useState<'idle' | 'loading' | 'error'>('idle')
  const [fullError, setFullError] = useState('')
  const [summaryState, setSummaryState] = useState<'idle' | 'loading'>('idle')
  const [ra, setRa] = useState<ReadAloudState>(readAloud.state)
  const [pill, setPill] = useState<{ x: number; y: number; offset: number } | null>(null)
  const touch = useRef<{ x: number; y: number; t: number } | null>(null)

  useEffect(() => readAloud.subscribe(setRa), [])

  // New article: reset view state
  useEffect(() => {
    setFullMode(!!feed?.fullTextByDefault)
    setFullState('idle')
    setPill(null)
    readAloud.showTapWord(null)
    scrollRef.current?.scrollTo({ top: 0 })
  }, [item.id, feed?.fullTextByDefault])

  // Load full text when needed
  useEffect(() => {
    if (!fullMode || item.fullHtml) return
    let cancelled = false
    setFullState('loading')
    loadFullText(item)
      .then(() => !cancelled && setFullState('idle'))
      .catch((e) => {
        if (cancelled) return
        setFullState('error')
        setFullError(e instanceof Error ? e.message : String(e))
      })
    return () => {
      cancelled = true
    }
  }, [fullMode, item.id, item.fullHtml, item])

  const html = useMemo(() => {
    const source = fullMode && item.fullHtml ? item.fullHtml : item.contentHtml
    return sanitize(source || `<p>${item.snippet}</p>`, item.link ?? feed?.url ?? null)
  }, [fullMode, item.fullHtml, item.contentHtml, item.snippet, item.link, feed?.url])

  // (Re)index text for read aloud whenever what's shown changes
  useEffect(() => {
    if (!bodyRef.current) return
    setPill(null)
    readAloud.showTapWord(null)
    readAloud.title = item.title
    readAloud.artist = feedDisplayTitle(feed)
    readAloud.attach(bodyRef.current, item.id)
  }, [html, item.id, item.title, feed])

  // Auto-continue from the previous article
  useEffect(() => {
    if (!p.autoplay) return
    if (fullMode && !item.fullHtml && fullState === 'loading') return
    p.onAutoplayConsumed()
    const id = item.id
    // Not cancelled on re-render: consuming the flag re-renders right away
    window.setTimeout(() => readAloud.state.itemId === id && readAloud.play(), 300)
  }, [p.autoplay, fullMode, item.fullHtml, item.id, fullState, p])

  const playing = ra.status !== 'idle' && ra.itemId === item.id

  const onBodyClick = (e: React.MouseEvent) => {
    const target = e.target as HTMLElement
    if (target.closest('a')) return
    if (window.getSelection()?.toString()) return
    const offset = readAloud.offsetAtPoint(e.clientX, e.clientY)
    if (offset < 0 || !readAloud.wordAt(offset)) {
      setPill(null)
      readAloud.showTapWord(null)
      return
    }
    if (playing && ra.status === 'playing') {
      // While reading, a tap jumps there
      readAloud.play(offset)
      return
    }
    const box = scrollRef.current!.getBoundingClientRect()
    const x = Math.min(Math.max(e.clientX - box.left, 90), box.width - 90)
    setPill({ x, y: e.clientY - box.top + scrollRef.current!.scrollTop, offset })
    readAloud.showTapWord(offset)
  }

  const readFromHere = () => {
    if (!pill) return
    readAloud.showTapWord(null)
    readAloud.play(pill.offset)
    setPill(null)
  }

  const toggleRead = () => db.items.update(item.id, { read: item.read ? 0 : 1, readTime: item.read ? null : Date.now(), readSynced: 0 })
  const toggleSave = () => db.items.update(item.id, { saved: item.saved ? 0 : 1 })

  const share = async () => {
    const url = item.link ?? ''
    if (navigator.share) {
      try {
        await navigator.share({ title: item.title, url })
      } catch {
        // cancelled
      }
    } else {
      await navigator.clipboard.writeText(url)
      p.toast('Link copied')
    }
  }

  const doSummary = async () => {
    setSummaryState('loading')
    try {
      await summarize(item, htmlToText(html))
    } catch (e) {
      p.toast(e instanceof Error ? e.message : String(e))
    } finally {
      setSummaryState('idle')
    }
  }

  const onTouchStart = (e: React.TouchEvent) => {
    const t = e.touches[0]
    touch.current = { x: t.clientX, y: t.clientY, t: Date.now() }
  }
  const onTouchEnd = (e: React.TouchEvent) => {
    const start = touch.current
    touch.current = null
    if (!start) return
    const t = e.changedTouches[0]
    const dx = t.clientX - start.x
    const dy = t.clientY - start.y
    if (Math.abs(dx) > 80 && Math.abs(dx) > Math.abs(dy) * 2 && Date.now() - start.t < 600) {
      if (dx < 0 && p.hasNext) p.onNext()
      if (dx > 0 && p.hasPrev) p.onPrev()
    }
  }

  const words = wordsOf(item, feed)
  const date = new Date(item.sortTime)

  return (
    <section className="reader-pane" aria-label="Article">
      <header className="pane-bar reader-bar">
        <span className="only-narrow">
          <IconButton icon="back" label="Back to list" onClick={p.onBack} />
        </span>
        <IconButton icon="prev" label="Previous article (K)" onClick={p.onPrev} disabled={!p.hasPrev} />
        <IconButton icon="next" label="Next article (J)" onClick={p.onNext} disabled={!p.hasNext} />
        <span className="spacer" />
        <IconButton
          icon="headphones"
          label={playing ? 'Stop reading aloud' : 'Read aloud (R)'}
          active={playing}
          onClick={() => (playing ? readAloud.stop() : readAloud.play())}
          disabled={!readAloud.supported}
        />
        <IconButton icon="article" label={fullMode ? 'Show feed text (F)' : 'Full text (F)'} active={fullMode} onClick={() => setFullMode(!fullMode)} />
        {item.link ? (
          <a className="icon-btn" href={item.link} target="_blank" rel="noopener noreferrer" title="Open original page (O)" aria-label="Open original page">
            <Icon name="open" />
          </a>
        ) : null}
      </header>

      <div className="reader-scroll" ref={scrollRef} data-scroll onTouchStart={onTouchStart} onTouchEnd={onTouchEnd}>
        <article className="article" style={{ fontSize: p.settings.textSize }}>
          <h1 className="article-title">
            {item.link ? (
              <a href={item.link} target="_blank" rel="noopener noreferrer">
                {item.title || '(no title)'}
              </a>
            ) : (
              item.title || '(no title)'
            )}
          </h1>
          <div className="article-meta">
            <span>{feedDisplayTitle(feed)}</span>
            {item.author ? <span>{item.author}</span> : null}
            <span>{date.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })}</span>
            <span>{readingMinutes(words)} min read</span>
            {p.settings.showHnScore && item.hnId ? (
              <a className="hn-badge" href={hnDiscussionUrl(item.hnId)} target="_blank" rel="noopener noreferrer">
                ▲ {formatPoints(item.hnPoints ?? 0)} · {item.hnComments ?? 0} comments
              </a>
            ) : null}
          </div>
          {item.imageUrl && !html.includes(item.imageUrl) ? (
            <img className="lead-image" src={item.imageUrl} alt="" referrerPolicy="no-referrer" onError={(e) => ((e.target as HTMLImageElement).style.display = 'none')} />
          ) : null}
          {item.summary ? (
            <aside className="summary">
              <div className="summary-head">
                <Icon name="summarize" size={18} /> Summary
                <button className="link-btn" onClick={() => db.items.update(item.id, { summary: null })}>
                  Hide
                </button>
              </div>
              <div className="summary-text">{item.summary}</div>
            </aside>
          ) : null}
          {fullMode && fullState === 'loading' ? <p className="notice">Loading full text…</p> : null}
          {fullMode && fullState === 'error' ? (
            <p className="notice error">
              Couldn't load the full text: {fullError}. Showing the feed's text instead.
            </p>
          ) : null}
          <div className="article-body" ref={bodyRef} onClick={onBodyClick} dangerouslySetInnerHTML={{ __html: html }} />
        </article>
        {pill ? (
          <button className="read-pill" style={{ left: pill.x, top: pill.y + 18 }} onClick={readFromHere}>
            <Icon name="play" size={16} /> Read from here
          </button>
        ) : null}
      </div>

      {playing ? (
        <Player state={ra} hasPrev={p.hasPrev} hasNext={p.hasNext} onPrev={p.onPrev} onNext={p.onNext} nextTitle={p.nextTitle} settings={p.settings} />
      ) : null}

      <footer className="action-bar">
        <IconButton icon="share" label="Share" onClick={share} showLabel />
        <IconButton icon={item.read ? 'unread' : 'read'} label={item.read ? 'Mark unread' : 'Mark read'} onClick={toggleRead} showLabel />
        <IconButton icon="summarize" label={summaryState === 'loading' ? 'Summarizing…' : 'Summarize'} onClick={doSummary} disabled={summaryState === 'loading'} showLabel />
        <IconButton icon={item.saved ? 'bookmarkFilled' : 'bookmark'} label={item.saved ? 'Saved' : 'Save'} active={!!item.saved} onClick={toggleSave} showLabel />
      </footer>
    </section>
  )
}
