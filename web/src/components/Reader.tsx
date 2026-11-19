import { useEffect, useMemo, useRef, useState } from 'react'
import { db, feedDisplayTitle, hideItems, unhideItems, type Feed, type Item } from '../lib/db'
import { syncNow } from '../lib/sync2'
import type { Toast } from '../lib/toast'
import { formatPoints, readingMinutes, wordsOf } from '../lib/listquery'
import { hnDiscussionUrl, loadFullText, refreshHnScores, sanitize, summarize } from '../lib/extras'
import { htmlToText } from '../lib/feedparse'
import { readAloud, type ReadAloudState } from '../lib/readaloud'
import { updateSettings, type Settings } from '../lib/settings'
import { applyFastReading } from '../lib/fastReading'
import { AUTO_SCROLL_SPEEDS, useAutoScroll } from '../lib/autoscroll'
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
  toast: Toast
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

  // Opened article: look up its Hacker News score if it's missing or stale
  useEffect(() => {
    if (p.settings.showHnScore) refreshHnScores([item], 1).catch(() => undefined)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [item.id, p.settings.showHnScore])

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

  const fastReading = p.settings.fastReading
  const html = useMemo(() => {
    const source = fullMode && item.fullHtml ? item.fullHtml : item.contentHtml
    const clean = sanitize(source || `<p>${item.snippet}</p>`, item.link ?? feed?.url ?? null)
    // Bold word starts only add elements, so read aloud and tap-to-read see the same text
    return fastReading ? applyFastReading(clean) : clean
  }, [fullMode, item.fullHtml, item.contentHtml, item.snippet, item.link, feed?.url, fastReading])

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

  // Hands-free auto scroll; read aloud and speed reading move through the text themselves
  const auto = useAutoScroll(scrollRef)
  const stopAuto = auto.stop
  useEffect(() => {
    if (playing) stopAuto()
  }, [playing, stopAuto])

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
      // While reading (aloud or the silent speed reader), a tap jumps there
      readAloud.play(offset)
      return
    }
    const box = scrollRef.current!.getBoundingClientRect()
    const x = Math.min(Math.max(e.clientX - box.left, 160), box.width - 160)
    setPill({ x, y: e.clientY - box.top + scrollRef.current!.scrollTop, offset })
    readAloud.showTapWord(offset)
  }

  const readFromHere = () => {
    if (!pill) return
    readAloud.showTapWord(null)
    readAloud.play(pill.offset)
    setPill(null)
  }

  const ensureAttached = () => {
    if (readAloud.state.itemId !== item.id && bodyRef.current) readAloud.attach(bodyRef.current, item.id)
  }

  /** Speedometer (V): show the big word in the player, reading aloud if not already. */
  const showWord = () => {
    setPill(null)
    readAloud.showTapWord(null)
    void updateSettings({ readAloudShowWord: true })
    if (!playing) {
      ensureAttached()
      readAloud.play()
    }
  }

  /** "Speed read" on a tapped word: the silent speed reader from there, word shown. */
  const speedReadFromPill = () => {
    if (!pill) return
    ensureAttached()
    readAloud.showTapWord(null)
    void updateSettings({ readAloudShowWord: true })
    readAloud.speedReadFrom(pill.offset)
    setPill(null)
  }

  // V shows the word (or hides it again while reading), A toggles auto scroll, Delete deletes
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.key !== 'v' && e.key !== 'a' && e.key !== 'Delete') || e.ctrlKey || e.metaKey || e.altKey) return
      const t = e.target as HTMLElement | null
      if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.tagName === 'SELECT' || t.isContentEditable)) return
      if (document.querySelector('.modal-backdrop')) return
      e.preventDefault()
      if (e.key === 'Delete') void remove()
      else if (e.key === 'v') {
        if (playing && ra.status === 'playing' && p.settings.readAloudShowWord) void updateSettings({ readAloudShowWord: false })
        else showWord()
      } else if (!playing) auto.toggle()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  })

  const toggleRead = () => db.items.update(item.id, { read: item.read ? 0 : 1, readTime: item.read ? null : Date.now(), readSynced: 0 })
  const toggleSave = () => db.items.update(item.id, { saved: item.saved ? 0 : 1, readSynced: 0 })
  /** Deletes the article for good on every device, then moves on to the next one. */
  const remove = async () => {
    const copy = item
    if (p.hasNext) p.onNext()
    else p.onBack()
    await hideItems([copy.id])
    syncNow().catch(() => undefined)
    p.toast('Article deleted on all devices', { label: 'Undo', run: () => void unhideItems([copy]).then(() => syncNow().catch(() => undefined)) })
  }

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
        {/* Like the Android app, these hide while the player is open: it has the same controls */}
        {playing ? null : (
          <>
            <IconButton icon="headphones" label="Read aloud (R)" onClick={() => readAloud.play()} disabled={!readAloud.supported} />
            <IconButton icon="speed" label="Speed read: show the word as it is read (V)" onClick={showWord} disabled={!readAloud.supported} />
          </>
        )}
        <IconButton icon="article" label={fullMode ? 'Show feed text (F)' : 'Full text (F)'} active={fullMode} onClick={() => setFullMode(!fullMode)} />
        {item.link ? (
          <a className="icon-btn" href={item.link} target="_blank" rel="noopener noreferrer" title="Open original page (O)" aria-label="Open original page">
            <Icon name="open" />
          </a>
        ) : null}
      </header>

      <div className="reader-stage">
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
            <div className="read-pill-group" style={{ left: pill.x, top: pill.y + 18 }}>
              <button className="read-pill" onClick={readFromHere}>
                <Icon name="play" size={16} /> Read from here
              </button>
              <button className="read-pill secondary" onClick={speedReadFromPill}>
                <Icon name="speed" size={16} /> Speed read
              </button>
            </div>
          ) : null}
        </div>
        {playing && !ra.following ? (
          <button className="read-pill follow-pill" onClick={() => readAloud.goToSpokenText()}>
            <Icon name="autoScroll" size={16} /> Go to spoken text
          </button>
        ) : null}
        {auto.active ? (
          <div className="auto-scroll-bar" role="toolbar" aria-label="Auto scroll">
            <IconButton icon={auto.playing ? 'pause' : 'play'} label={auto.playing ? 'Pause auto scroll' : 'Resume auto scroll'} onClick={() => auto.setPlaying(!auto.playing)} />
            <IconButton icon="remove" label="Slower" onClick={() => auto.changeLevel(-1)} disabled={auto.level <= 1} />
            <span className="auto-scroll-speed">Speed {auto.level}</span>
            <IconButton icon="add" label="Faster" onClick={() => auto.changeLevel(1)} disabled={auto.level >= AUTO_SCROLL_SPEEDS.length} />
            <IconButton icon="close" label="Stop auto scroll (A)" onClick={auto.stop} />
          </div>
        ) : null}
      </div>

      <footer className="action-bar">
        <IconButton icon="share" label="Share" onClick={share} showLabel />
        <IconButton icon={item.read ? 'unread' : 'read'} label={item.read ? 'Mark unread' : 'Mark read'} onClick={toggleRead} showLabel />
        <IconButton icon="summarize" label={summaryState === 'loading' ? 'Summarizing…' : 'Summarize'} onClick={doSummary} disabled={summaryState === 'loading'} showLabel />
        <IconButton icon={item.saved ? 'bookmarkFilled' : 'bookmark'} label={item.saved ? 'Saved' : 'Save'} active={!!item.saved} onClick={toggleSave} showLabel />
        <IconButton icon="delete" label="Delete" title="Delete this article on all your devices (Delete key)" onClick={remove} showLabel />
        <IconButton
          icon="formatBold"
          label="Fast read"
          title={fastReading ? 'Turn off bold word starts (B)' : 'Bold word starts to read faster (B)'}
          active={fastReading}
          onClick={() => updateSettings({ fastReading: !fastReading })}
          showLabel
        />
        <IconButton
          icon="autoScroll"
          label="Scroll"
          title={auto.active ? 'Stop auto scroll (A)' : 'Scroll the article on its own while you read (A)'}
          active={auto.active}
          onClick={auto.toggle}
          disabled={playing}
          showLabel
        />
      </footer>

      {playing ? (
        <Player
          state={ra}
          settings={p.settings}
          currentTitle={item.title}
          hasPrev={p.hasPrev}
          hasNext={p.hasNext}
          nextTitle={p.nextTitle}
          // Keeps reading in the article it moves to, like the media keys
          onPrev={() => (readAloud.onMediaPrevious ? readAloud.onMediaPrevious() : p.onPrev())}
          onNext={() => (readAloud.onMediaNext ? readAloud.onMediaNext() : p.onNext())}
        />
      ) : null}
    </section>
  )
}
