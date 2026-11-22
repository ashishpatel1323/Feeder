import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useLiveQuery } from 'dexie-react-hooks'
import { db, feedDisplayTitle, hideItems, unhideItems, type Feed, type Highlight, type HighlightColor, type Item } from '../lib/db'
import { anchorOf, articlePicture, applyHighlights, deleteHighlight, newHighlight, quoteText, saveHighlight, updateHighlight } from '../lib/highlights'
import { syncNow } from '../lib/sync2'
import type { Toast } from '../lib/toast'
import { copyText, HighlightBar, HighlightsRail } from './Highlights'
import { combinedSubject, ShareDialog, type ShareSubject } from './ShareDialog'
import { readingMinutes, wordsOf } from '../lib/listquery'
import { loadFullText, sanitize, summarize } from '../lib/extras'
import { htmlToText } from '../lib/feedparse'
import { readAloud, type ReadAloudState } from '../lib/readaloud'
import { updateSettings, type Settings } from '../lib/settings'
import { applyFastReading } from '../lib/fastReading'
import { AUTO_SCROLL_SPEEDS, useAutoScroll } from '../lib/autoscroll'
import { Icon, IconButton } from './Icon'
import { Player } from './Player'
import { articleVideoId, youtubeVideoId, youtubeWatchUrl } from '../lib/youtube'
import { YouTubeFrame } from './YouTubeFrame'
import { LocalVideo, VideoOfflineControls } from './Offline'
import { preferSavedImages, useOnline } from '../lib/offline'

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
  /** A highlight to scroll to once the article shows (from the Highlights page). */
  focusHighlight: string | null
  onFocusConsumed: () => void
  /** Opens the feed's own page (stats and settings). */
  onOpenFeed: (url: string) => void
}

type Anchor = { start: number; text: string; prefix: string }
/** What the highlight bar is working on: a fresh selection or a saved highlight. */
type BarTarget = { kind: 'new'; anchor: Anchor } | { kind: 'edit'; id: string }

const pendingHighlightsSupported = typeof CSS !== 'undefined' && 'highlights' in CSS

export function Reader(p: Props) {
  const { item, feed } = p
  const bodyRef = useRef<HTMLDivElement>(null)
  const scrollRef = useRef<HTMLDivElement>(null)
  // A YouTube page has no article text to extract, so videos start on the feed's description
  const fullByDefault = !!feed?.fullTextByDefault && !youtubeVideoId(item.link)
  const [fullMode, setFullMode] = useState<boolean>(fullByDefault)
  const [fullState, setFullState] = useState<'idle' | 'loading' | 'error'>('idle')
  const [fullError, setFullError] = useState('')
  // A saved video plays from the offline copy unless switched to YouTube's player
  const [useLocal, setUseLocal] = useState(true)
  const online = useOnline()
  const [summaryState, setSummaryState] = useState<'idle' | 'loading'>('idle')
  const [ra, setRa] = useState<ReadAloudState>(readAloud.state)
  const [pill, setPill] = useState<{ x: number; y: number; offset: number } | null>(null)
  const touch = useRef<{ x: number; y: number; t: number } | null>(null)

  useEffect(() => readAloud.subscribe(setRa), [])

  // New article: reset view state
  useEffect(() => {
    setFullMode(fullByDefault)
    setUseLocal(true)
    setFullState('idle')
    setPill(null)
    readAloud.showTapWord(null)
    scrollRef.current?.scrollTo({ top: 0 })
  }, [item.id, fullByDefault])

  // Load full text when needed
  useEffect(() => {
    // Offline without a saved copy: the feed text shows instead
    if (!fullMode || item.fullHtml || !online) return
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
  }, [fullMode, item.id, item.fullHtml, item, online])

  const fastReading = p.settings.fastReading
  const highlights = useLiveQuery(() => db.highlights.where('itemId').equals(item.id).toArray(), [item.id])
  const html = useMemo(() => {
    const source = fullMode && item.fullHtml ? item.fullHtml : item.contentHtml
    const sanitized = sanitize(source || `<p>${item.snippet}</p>`, item.link ?? feed?.url ?? null)
    // Saved offline: use the image URLs that were saved (srcset would pick others)
    const clean = item.offlineAt ? preferSavedImages(sanitized) : sanitized
    // Bold word starts and highlight marks only add elements, so read aloud and tap-to-read see the same text
    const marked = applyHighlights(clean, highlights ?? [])
    return fastReading ? applyFastReading(marked) : marked
  }, [fullMode, item.fullHtml, item.contentHtml, item.snippet, item.link, item.offlineAt, feed?.url, fastReading, highlights])

  // Highlights, in reading order
  const [bar, setBar] = useState<BarTarget | null>(null)
  const [railOpen, setRailOpen] = useState(false)
  const [share, setShare] = useState<ShareSubject | null>(null)
  const ordered = useMemo(() => [...(highlights ?? [])].sort((a, b) => a.start - b.start), [highlights])
  const editing = bar?.kind === 'edit' ? (highlights?.find((h) => h.id === bar.id) ?? null) : null

  const clearPending = () => {
    if (pendingHighlightsSupported) CSS.highlights.delete('hl-pending')
  }

  // A text selection in the article opens the highlight bar
  useEffect(() => {
    const onSel = () => {
      const sel = window.getSelection()
      const body = bodyRef.current
      if (!sel || sel.isCollapsed || !sel.rangeCount || !body) return
      const range = sel.getRangeAt(0)
      if (!body.contains(range.commonAncestorContainer)) return
      const anchor = anchorOf(body, range)
      if (!anchor) return
      setPill(null)
      readAloud.showTapWord(null)
      setBar({ kind: 'new', anchor })
      // Keep the chosen text marked while typing a note, when the selection itself goes away
      if (pendingHighlightsSupported) CSS.highlights.set('hl-pending', new window.Highlight(range.cloneRange()))
    }
    document.addEventListener('selectionchange', onSel)
    return () => document.removeEventListener('selectionchange', onSel)
  }, [])

  // New article: close the bar and the share screen
  useEffect(() => {
    setBar(null)
    setShare(null)
    clearPending()
  }, [item.id])

  const closeBar = useCallback(() => {
    setBar(null)
    clearPending()
    window.getSelection()?.removeAllRanges()
  }, [])

  const flash = (id: string) => {
    const marks = bodyRef.current?.querySelectorAll<HTMLElement>(`mark[data-hl="${CSS.escape(id)}"]`)
    if (!marks?.length) return false
    marks[0].scrollIntoView({ behavior: 'smooth', block: 'center' })
    marks.forEach((m) => {
      m.classList.remove('flash')
      void m.offsetWidth
      m.classList.add('flash')
    })
    return true
  }

  // From the Highlights page: scroll to the highlight once it's in the text
  useEffect(() => {
    if (!p.focusHighlight || !highlights) return
    const id = p.focusHighlight
    const t = window.setTimeout(() => {
      if (!flash(id)) p.toast("This highlight isn't in the text shown. Try switching full text (F).")
      p.onFocusConsumed()
    }, 250)
    return () => clearTimeout(t)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [p.focusHighlight, highlights, html])

  const picture = useMemo(() => articlePicture(item.imageUrl, html), [item.imageUrl, html])
  const subjectOf = (h: Pick<Highlight, 'text' | 'note' | 'color'>): ShareSubject => ({
    text: h.text,
    note: h.note,
    color: h.color,
    itemTitle: item.title,
    feedTitle: feedDisplayTitle(feed),
    itemLink: item.link,
    imageUrl: picture,
  })

  const saveFromBar = async (color: HighlightColor, note: string) => {
    if (!bar) return
    if (bar.kind === 'new') {
      await saveHighlight(newHighlight(item, feed, bar.anchor, color, note, picture))
      p.toast('Highlighted')
      closeBar()
    } else {
      await updateHighlight(bar.id, { color, note: note.trim() })
    }
  }

  const shareFromBar = async (color: HighlightColor, note: string) => {
    if (!bar) return
    if (bar.kind === 'new') {
      // Sharing a selection saves it as a highlight too
      const h = newHighlight(item, feed, bar.anchor, color, note, picture)
      await saveHighlight(h)
      closeBar()
      setShare(subjectOf(h))
    } else if (editing) {
      setShare(subjectOf({ ...editing, color, note: note.trim() }))
    }
  }

  const copyFromBar = (note: string) => {
    const text = bar?.kind === 'new' ? bar.anchor.text : editing?.text
    if (!text) return
    copyText(quoteText({ text, itemTitle: item.title, feedTitle: feedDisplayTitle(feed), itemLink: item.link }, note.trim()), p.toast)
  }

  const removeHighlight = async (h: Highlight) => {
    await deleteHighlight(h.id)
    if (bar?.kind === 'edit' && bar.id === h.id) setBar(null)
    p.toast('Highlight deleted')
  }

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
    // Tapping a highlight opens it in the bar (recolor, note, share, delete)
    const mark = target.closest<HTMLElement>('mark.hl')
    if (mark?.dataset.hl && !(playing && ra.status === 'playing')) {
      setPill(null)
      readAloud.showTapWord(null)
      clearPending()
      setBar({ kind: 'edit', id: mark.dataset.hl })
      return
    }
    if (bar) {
      closeBar()
      return
    }
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

  const shareLink = async () => {
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

  const videoId = articleVideoId(item)
  const showLocal = !!item.offlineVideo && useLocal
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
        <button
          type="button"
          className={`hl-chip${railOpen ? ' on' : ''}`}
          onClick={() => setRailOpen(!railOpen)}
          title={railOpen ? 'Hide highlights' : 'Show highlights in this article'}
          aria-pressed={railOpen}
        >
          <Icon name="highlight" size={18} /> {highlights?.length ?? 0}
        </button>
        <IconButton icon="article" label={fullMode ? 'Show feed text (F)' : 'Full text (F)'} active={fullMode} onClick={() => setFullMode(!fullMode)} />
        {item.link ? (
          <a className="icon-btn" href={item.link} target="_blank" rel="noopener noreferrer" title="Open original page (O)" aria-label="Open original page">
            <Icon name="open" />
          </a>
        ) : null}
      </header>

      <div className={`reader-stage${railOpen ? ' with-rail' : ''}`}>
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
              <button type="button" className="meta-feed" title="Open this feed's page: stats and settings" onClick={() => p.onOpenFeed(item.feedUrl)}>
                {feedDisplayTitle(feed)}
              </button>
              {item.author ? <span>{item.author}</span> : null}
              <span>{date.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })}</span>
              <span>{readingMinutes(words)} min read</span>
            </div>
            {videoId ? (
              <div className="video-block">
                {showLocal ? (
                  <LocalVideo key={`${item.id}|${videoId}|local`} itemId={item.id} videoId={videoId} title={item.title || 'YouTube video'} subs={item.offlineSubs} />
                ) : online ? (
                  <YouTubeFrame key={`${item.id}|${videoId}`} itemId={item.id} videoId={videoId} title={item.title || 'YouTube video'} />
                ) : (
                  <div className="video-frame video-missing">You are offline and this video was not saved.</div>
                )}
                <div className="video-actions">
                  <a className="open-youtube" href={youtubeWatchUrl(videoId)} target="_blank" rel="noopener noreferrer">
                    <Icon name="open" size={18} /> Open on YouTube
                  </a>
                  <VideoOfflineControls item={item} videoId={videoId} useLocal={useLocal} onUseLocal={setUseLocal} />
                </div>
              </div>
            ) : item.imageUrl && !html.includes(item.imageUrl) ? (
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
        {railOpen ? (
          <HighlightsRail
            highlights={ordered}
            onClose={() => setRailOpen(false)}
            onJump={(h) => {
              if (!flash(h.id)) p.toast("This highlight isn't in the text shown. Try switching full text (F).")
              if (window.matchMedia('(max-width: 1100px)').matches) setRailOpen(false)
            }}
            onShare={(h) => setShare(subjectOf(h))}
            onEdit={(h) => {
              setBar({ kind: 'edit', id: h.id })
              flash(h.id)
              if (window.matchMedia('(max-width: 1100px)').matches) setRailOpen(false)
            }}
            onDelete={removeHighlight}
            onShareMany={(hs) => setShare(combinedSubject(hs, { itemTitle: item.title, feedTitle: feedDisplayTitle(feed), itemLink: item.link, imageUrl: picture }))}
            toast={p.toast}
          />
        ) : null}
      </div>

      {bar && (bar.kind === 'new' || editing) ? (
        <HighlightBar
          text={bar.kind === 'new' ? bar.anchor.text : editing!.text}
          existing={editing}
          onSave={saveFromBar}
          onShare={shareFromBar}
          onCopyText={copyFromBar}
          onDelete={() => editing && removeHighlight(editing)}
          onClose={closeBar}
        />
      ) : null}
      {share ? <ShareDialog subject={share} onClose={() => setShare(null)} toast={p.toast} /> : null}

      <footer className="action-bar">
        <IconButton icon="share" label="Share" onClick={shareLink} showLabel />
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
