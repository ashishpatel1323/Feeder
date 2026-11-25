import { useLiveQuery } from 'dexie-react-hooks'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { db, type Book, type Highlight, type HighlightColor } from '../lib/db'
import { onDropboxChange } from '../lib/dropbox'
import {
  ACCEPT,
  bookFile,
  bookItemId,
  deleteBook,
  ebooksSupported,
  importBook,
  uploadBook,
  refreshFromDropbox,
  removeFromDevice,
  savePosition,
} from '../lib/ebooks/library'
import { loadFoliate } from '../lib/ebooks/foliate'
import { atEnd, atStart, drawFixedHighlights, markFixedPage, readableContent, redrawFixedHighlights, nextSection, pageNumbers, prevSection, reveal, setStyles } from '../lib/ebooks/nav'
import { bookCss, injectCsp, loadReaderPrefs, saveReaderPrefs, type ReaderPrefs } from '../lib/ebooks/reader'
import { deleteHighlight, HIGHLIGHT_COLORS, quoteText, saveHighlight, updateHighlight } from '../lib/highlights'
import { bookChip, trackOpen, type Where } from '../lib/history'
import { formatBytes } from '../lib/offline'
import { readAloud, type ReadAloudState } from '../lib/readaloud'
import type { Settings } from '../lib/settings'
import type { FoliateView, Location, TocItem } from '../vendor/foliate-js/view.js'
import { DropboxPanel, useDropbox } from './DropboxPanel'
import { HighlightBar } from './Highlights'
import { HistoryList } from './History'
import { Icon, IconButton } from './Icon'
import { Player } from './Player'
import { otherDeviceText } from '../lib/device'
import { Shelf, ShelfControls, useShelfPrefs, type ShelfItem } from './Shelf'

/**
 * The Ebooks tab: books from Dropbox (/Ebooks) and this device, read with foliate-js.
 * #/books is the library, #/books/<id> reads one (optionally at a CFI: #/books/<id>@<cfi>).
 */
export function EbooksPage(p: {
  book: string | null
  at: string | null
  settings: Settings
  onOpen: (id: string | null, at?: string) => void
  onMenu: () => void
  toast: (m: string) => void
}) {
  if (!ebooksSupported())
    return (
      <section className="manage-pane books-page" aria-label="Ebooks">
        <div className="mf-page">
          <div className="mf-head">
            <h1>Ebooks</h1>
          </div>
          <p className="muted">This browser cannot keep files on the device. Use a current Chrome, Edge, Safari or Firefox.</p>
        </div>
      </section>
    )
  return p.book ? (
    <BookReader key={p.book} id={p.book} at={p.at} settings={p.settings} onBack={() => p.onOpen(null)} toast={p.toast} />
  ) : (
    <Library onOpen={(id) => p.onOpen(id)} onMenu={p.onMenu} toast={p.toast} />
  )
}

interface Job {
  key: string
  label: string
  done: number
  total: number
}

function Library({ onOpen, onMenu, toast }: { onOpen: (id: string) => void; onMenu: () => void; toast: (m: string) => void }) {
  const books = useLiveQuery(() => db.books.toArray(), [])
  const positions = useLiveQuery(() => db.bookPos.toArray(), []) ?? []
  const [jobs, setJobs] = useState<Job[]>([])
  const [busy, setBusy] = useState(false)
  const [downloading, setDownloading] = useState<Set<string>>(new Set())
  const [uploading, setUploading] = useState<Record<string, number>>({})
  const prefs = useShelfPrefs('ebooks', 'cards')
  const fileInput = useRef<HTMLInputElement>(null)
  const dbx = useDropbox()

  const refresh = useCallback(() => {
    setBusy(true)
    refreshFromDropbox()
      .catch((e) => toast(e instanceof Error ? e.message : String(e)))
      .finally(() => setBusy(false))
  }, [toast])
  useEffect(() => {
    refresh()
    return onDropboxChange(refresh)
  }, [refresh])

  const progressOf = useMemo(() => new Map(positions.map((x) => [x.id, x])), [positions])
  const sorted = useMemo(
    () => [...(books ?? [])].sort((a, b) => (b.lastOpenedAt || 0) - (a.lastOpenedAt || 0) || b.addedAt - a.addedAt || a.title.localeCompare(b.title)),
    [books],
  )

  const onFiles = (files: FileList | null) => {
    for (const f of files ?? []) {
      const key = `${f.name}:${Date.now()}`
      setJobs((j) => [...j, { key, label: f.name, done: 0, total: f.size }])
      let last = 0
      importBook(f, (done, total) => {
        if (Date.now() - last < 250 && done < total) return
        last = Date.now()
        setJobs((j) => j.map((x) => (x.key === key ? { ...x, done, total } : x)))
      })
        .then((b) => toast(b.dropboxPath ? `Added ${b.title} (also in Dropbox)` : `Added ${b.title} to this device`))
        .catch((e) => toast(e instanceof Error ? e.message : String(e)))
        .finally(() => setJobs((j) => j.filter((x) => x.key !== key)))
    }
  }

  const act = async (b: Book, what: 'device' | 'everywhere') => {
    if (what === 'device') {
      await removeFromDevice(b.id)
      toast(b.dropboxPath ? `Removed ${b.title} from this device; it stays in Dropbox` : `Deleted ${b.title}`)
    } else {
      if (!confirm(`Delete ${b.title} from Dropbox and this device? Highlights stay.`)) return
      try {
        await deleteBook(b.id)
        toast(`Deleted ${b.title}`)
      } catch (e) {
        toast(e instanceof Error ? e.message : String(e))
      }
    }
  }

  const download = async (b: Book) => {
    setDownloading((d) => new Set(d).add(b.id))
    try {
      await bookFile(b.id)
      toast(`${b.title} is on this device`)
    } catch (e) {
      toast(e instanceof Error ? e.message : String(e))
    } finally {
      setDownloading((d) => {
        const n = new Set(d)
        n.delete(b.id)
        return n
      })
    }
  }

  const upload = async (b: Book) => {
    setUploading((u) => ({ ...u, [b.id]: 0 }))
    try {
      await uploadBook(b.id, (done, total) => setUploading((u) => ({ ...u, [b.id]: total ? done / total : 0 })))
      toast(`${b.title} is in Dropbox`)
    } catch (e) {
      toast(e instanceof Error ? e.message : String(e))
    } finally {
      setUploading((u) => {
        const n = { ...u }
        delete n[b.id]
        return n
      })
    }
  }

  const items: ShelfItem[] = sorted.map((b) => {
    const pos = progressOf.get(b.id)
    const f = pos?.f
    const where = otherDeviceText(pos)
    const actions: ShelfItem['actions'] = []
    if (!b.local) actions.push({ label: 'Download to this device', run: () => void download(b) })
    if (b.local && !b.dropboxPath && dbx.signedIn && uploading[b.id] === undefined) actions.push({ label: 'Upload to Dropbox', run: () => void upload(b) })
    if (b.local) actions.push({ label: b.dropboxPath ? 'Remove from this device' : 'Delete', run: () => void act(b, 'device') })
    if (b.dropboxPath) actions.push({ label: 'Delete from Dropbox', run: () => void act(b, 'everywhere') })
    return {
      key: b.id,
      title: b.title,
      subtitle: b.author,
      detail: [f ? `${pos?.n ? `${pos.p} of ${pos.n} pages` : `${Math.round(f * 100)}%`} read${where ? ` ${where}` : ''}` : 'Not started', b.ext.toUpperCase(), formatBytes(b.size)].join(' · '),
      cover: b.cover,
      coverIcon: 'book',
      where: b.local ? (b.dropboxPath ? 'both' : 'device') : 'cloud',
      size: b.size,
      busy: downloading.has(b.id) ? 'Downloading…' : uploading[b.id] !== undefined ? `Uploading ${Math.floor(uploading[b.id] * 100)}%` : undefined,
      onOpen: () => onOpen(b.id),
      actions,
    }
  })

  return (
    <section
      className="manage-pane books-page"
      aria-label="Ebooks"
      onDragOver={(e) => e.preventDefault()}
      onDrop={(e) => {
        e.preventDefault()
        onFiles(e.dataTransfer.files)
      }}
    >
      <div className="mf-page">
        <div className="mf-head">
          <span className="only-narrow">
            <IconButton icon="menu" label="Feeds" onClick={onMenu} />
          </span>
          <h1>Ebooks</h1>
          <span className="zim-actions">
            {dbx.signedIn ? <IconButton icon="refresh" label="Check Dropbox for new books" onClick={refresh} disabled={busy} /> : null}
            <button type="button" className="mf-btn primary" onClick={() => fileInput.current?.click()}>
              <Icon name="upload" size={18} />
              Add book
            </button>
          </span>
          <input
            ref={fileInput}
            type="file"
            accept={ACCEPT}
            multiple
            hidden
            onChange={(e) => {
              onFiles(e.target.files)
              e.target.value = ''
            }}
          />
        </div>
        <DropboxPanel what="Books" toast={toast} />

        {jobs.length ? (
          <div className="lib-list zim-jobs">
            {jobs.map((j) => (
              <div key={j.key} className="lib-row">
                <Icon name="upload" size={22} />
                <span className="lib-text">
                  <span className="lib-title">{j.label}</span>
                  <span className="lib-desc">Adding{j.done ? `: ${formatBytes(j.done)} of ${formatBytes(j.total)} uploaded` : '…'}</span>
                  <progress max={j.total || 1} value={j.done || undefined} />
                </span>
              </div>
            ))}
          </div>
        ) : null}

        {books === undefined ? (
          <p className="muted">Loading…</p>
        ) : sorted.length === 0 && !jobs.length ? (
          <div className="zim-empty">
            <Icon name="book" size={40} />
            <p>
              No books yet. Add an EPUB, MOBI, AZW3, FB2, CBZ or PDF file (you can also drop it here)
              {dbx.signedIn ? ', or put books in Dropbox › Apps › Feeder › Ebooks' : ''}.
            </p>
          </div>
        ) : (
          <>
            <ShelfControls {...prefs} counts={{ all: sorted.length, device: sorted.filter((b) => b.local).length, cloud: sorted.filter((b) => !b.local).length }} />
            <Shelf items={items} show={prefs.show} view={prefs.view} shape="book" emptyDevice="No books on this device yet. Books in Dropbox download when you open them." />
          </>
        )}
      </div>
    </section>
  )
}

const PANEL_TITLES = { toc: 'Contents', highlights: 'Highlights', look: 'Text and theme', history: 'History' } as const

type Panel = null | 'toc' | 'highlights' | 'look' | 'history'

/** Where a book is, for its history: "p. 41 of 310" (or "37%"), the chapter, and the CFI. */
function bookWhere(l: Location, fixed: boolean): Where {
  const f = Number.isFinite(l.fraction) ? l.fraction : 0
  const pages = pageNumbers(l, fixed)
  return { p: bookChip(f, pages ? { p: pages.page, n: pages.pages } : null), s: l.tocItem?.label, f, x: l.cfi }
}

interface Pending {
  cfi: string
  text: string
}

function BookReader({ id, at, settings, onBack, toast }: { id: string; at: string | null; settings: Settings; onBack: () => void; toast: (m: string) => void }) {
  const host = useRef<HTMLDivElement>(null)
  const viewRef = useRef<FoliateView | null>(null)
  const book = useLiveQuery(() => db.books.get(id), [id])
  const highlights = useLiveQuery(() => db.highlights.where('itemId').equals(bookItemId(id)).toArray(), [id]) ?? []
  const highlightsRef = useRef<Highlight[]>([])
  highlightsRef.current = highlights
  const [status, setStatus] = useState<{ loading: boolean; done: number; total: number; error: string | null }>({ loading: true, done: 0, total: 0, error: null })
  const [loc, setLoc] = useState<Location | null>(null)
  const [toc, setToc] = useState<TocItem[]>([])
  const [panel, setPanel] = useState<Panel>(null)
  const [prefs, setPrefs] = useState<ReaderPrefs>(loadReaderPrefs)
  const [pending, setPending] = useState<Pending | null>(null)
  const [editing, setEditing] = useState<Highlight | null>(null)
  const [ra, setRa] = useState<ReadAloudState>(readAloud.state)
  useEffect(() => readAloud.subscribe(setRa), [])
  const itemId = bookItemId(id)
  const listening = ra.itemId === itemId && (ra.status !== 'idle' || ra.muted)
  /** Read aloud reached the end of a chapter: keep going in the next one once it loads. */
  const continueNext = useRef(false)
  const titleRef = useRef('')
  titleRef.current = book?.title ?? ''

  // Opening the book
  useEffect(() => {
    let live = true
    let saveTimer = 0
    const abort = new AbortController()
    const el = host.current!
    // History: opened once the first page shows, closed with the reader
    const opened = trackOpen('ebook', id, () => {
      const l = viewRef.current?.lastLocation
      return l ? bookWhere(l, !!viewRef.current?.isFixedLayout) : null
    })
    ;(async () => {
      await loadFoliate()
      const file = await bookFile(id, (done, total) => live && setStatus((s) => ({ ...s, done, total })), abort.signal)
      if (!live) return
      const { makeBook } = await loadFoliate()
      const b = await makeBook(file)
      // Books can carry scripts; never run them (they'd share this app's storage)
      b.transformTarget?.addEventListener('data', (e) => {
        const d = (e as CustomEvent<{ type: string; data: unknown }>).detail
        if (/html|xml/.test(d.type) && !/svg|ncx|opf|oebps-package/.test(d.type)) {
          d.data = Promise.resolve(d.data).then((x) => (typeof x === 'string' ? injectCsp(x) : x))
        }
      })
      const view = document.createElement('foliate-view') as FoliateView
      view.className = 'book-view'
      el.append(view)
      viewRef.current = view
      await view.open(b)
      if (!live) return
      setToc(b.toc ?? [])
      view.renderer.setAttribute('flow', loadReaderPrefs().flow)
      view.renderer.setAttribute('max-inline-size', '720px')
      view.renderer.setAttribute('gap', '6%')
      setStyles(view, bookCss(loadReaderPrefs()))

      view.addEventListener('relocate', (e) => {
        const l = (e as CustomEvent<Location>).detail
        setLoc(l)
        opened.touch()
        // Fixed-layout pages (PDF) may already be loaded: carry on reading aloud once the page shows
        if (continueNext.current && view.isFixedLayout) {
          continueNext.current = false
          window.setTimeout(() => startListening(0), 300)
        }
        window.clearTimeout(saveTimer)
        saveTimer = window.setTimeout(() => void savePosition(id, l.cfi, l.fraction ?? 0, pageNumbers(l, view.isFixedLayout)), 800)
      })
      view.addEventListener('create-overlay', () => {
        for (const h of highlightsRef.current) if (h.cfi) void view.addAnnotation({ value: h.cfi, color: h.color })
      })
      view.addEventListener('draw-annotation', async (e) => {
        const { draw, annotation } = (e as CustomEvent<{ draw: (f: unknown, o: unknown) => void; annotation: { color?: HighlightColor } }>).detail
        const { Overlayer } = await import('../vendor/foliate-js/overlayer.js')
        draw(Overlayer.highlight, { color: HIGHLIGHT_COLORS.find((c) => c.id === annotation.color)?.swatch ?? '#ffe066' })
      })
      view.addEventListener('show-annotation', (e) => {
        const { value } = (e as CustomEvent<{ value: string }>).detail
        const h = highlightsRef.current.find((x) => x.cfi === value)
        if (h) {
          setPending(null)
          setEditing(h)
        }
      })
      view.addEventListener('load', (e) => {
        const { doc, index } = (e as CustomEvent<{ doc: Document; index: number }>).detail
        markFixedPage(view, doc)
        drawFixedHighlights(view, doc, index, highlightsRef.current)
        const checkSelection = () =>
          window.setTimeout(() => {
            const sel = doc.getSelection()
            if (!sel || sel.isCollapsed || !sel.rangeCount) return
            const range = sel.getRangeAt(0)
            const text = range.toString().trim()
            if (!text) return
            setEditing(null)
            setPending({ cfi: view.getCFI(index, range), text })
          }, 10)
        doc.addEventListener('pointerup', checkSelection)
        doc.addEventListener('keyup', (k) => k.shiftKey && checkSelection())
        doc.addEventListener('keydown', onKey)
        // Taps near the edges turn pages (swipes are handled by foliate-js)
        doc.addEventListener('click', (c) => {
          if (doc.getSelection()?.isCollapsed === false) return
          if ((c.target as Element | null)?.closest?.('a[href]')) return
// The page's iframe is as wide as the whole chapter: measure the tap on the screen instead
          const frame = doc.defaultView?.frameElement?.getBoundingClientRect()
          const x = (frame?.left ?? 0) + c.clientX
          const box = view.getBoundingClientRect()
          const frac = box.width ? (x - box.left) / box.width : 0.5
          if (frac < 0.22) void view.goLeft()
          else if (frac > 0.78) void view.goRight()
        })
        if (continueNext.current) {
          continueNext.current = false
          window.setTimeout(() => startListening(0), 50)
        }
      })

      const pos = await db.bookPos.get(id)
      await view.init({ lastLocation: at || pos?.cfi || null, showTextStart: !at && !pos })
      if (live) setStatus({ loading: false, done: 0, total: 0, error: null })
    })().catch((e) => {
      if (live && !abort.signal.aborted) setStatus({ loading: false, done: 0, total: 0, error: e instanceof Error ? e.message : String(e) })
    })
    return () => {
      live = false
      abort.abort()
      window.clearTimeout(saveTimer)
      const v = viewRef.current
      opened.stop()
      if (v?.lastLocation) void savePosition(id, v.lastLocation.cfi, v.lastLocation.fraction ?? 0, pageNumbers(v.lastLocation, v.isFixedLayout))
      if (readAloud.state.itemId === bookItemId(id)) readAloud.stop()
      readAloud.revealHook = null
      v?.close()
      v?.remove()
      viewRef.current = null
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id])

  // Jumping to a highlight from the Highlights page while the book is open
  useEffect(() => {
    if (at && viewRef.current && !status.loading) void viewRef.current.goTo(at)
  }, [at, status.loading])

  // Highlights added, recolored or synced while reading
  const drawn = useRef(new Map<string, HighlightColor>())
  useEffect(() => {
    const view = viewRef.current
    if (!view || status.loading) return
    const now = new Map(highlights.filter((h) => h.cfi).map((h) => [h.cfi!, h.color]))
    for (const [cfi, color] of now) if (drawn.current.get(cfi) !== color) void view.addAnnotation({ value: cfi, color })
    for (const cfi of drawn.current.keys()) if (!now.has(cfi)) void view.deleteAnnotation({ value: cfi })
    drawn.current = now
    redrawFixedHighlights(view, highlights)
  }, [highlights, status.loading])

  useEffect(() => {
    setStyles(viewRef.current, bookCss(prefs))
    viewRef.current?.renderer?.setAttribute('flow', prefs.flow)
    saveReaderPrefs(prefs)
  }, [prefs])

  const onKey = (e: KeyboardEvent) => {
    const t = e.target as HTMLElement | null
    if (t && /INPUT|TEXTAREA|SELECT/.test(t.tagName)) return
    const view = viewRef.current
    if (!view) return
    if (e.key === 'ArrowLeft' || e.key === 'PageUp') {
      e.preventDefault()
      void view.goLeft()
    } else if (e.key === 'ArrowRight' || e.key === 'PageDown' || (e.key === ' ' && !e.shiftKey)) {
      e.preventDefault()
      void view.goRight()
    } else if (e.key === ' ' && e.shiftKey) {
      e.preventDefault()
      void view.goLeft()
    }
  }
  useEffect(() => {
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  })

  /** Read aloud from the top of the current page (or `offset` into the chapter). */
  const startListening = (offset?: number) => {
    const view = viewRef.current
    const c = readableContent(view)
    if (!view || !c?.doc.body) return
    readAloud.title = titleRef.current
    readAloud.artist = view.lastLocation?.tocItem?.label ?? ''
    readAloud.attach(c.doc.body, itemId, c.doc.documentElement.lang || undefined)
    readAloud.revealHook = (r) => {
      const shown = viewRef.current?.lastLocation?.range
      try {
        if (shown && r.compareBoundaryPoints(Range.START_TO_START, shown) >= 0 && r.compareBoundaryPoints(Range.START_TO_END, shown) <= 0) return true
      } catch {
        // a range from a chapter that's no longer shown
      }
      void reveal(viewRef.current, r)
      return true
    }
    readAloud.onFinished = (done) => {
      if (done !== itemId) return
      const v = viewRef.current
      if (!v || atEnd(v)) return
      continueNext.current = true
      void nextSection(v)
    }
    let from = offset
    if (from === undefined) {
      const r = view.lastLocation?.range
      from = r ? Math.max(0, readAloud.offsetOf(r.startContainer, r.startOffset)) : 0
    }
    readAloud.play(from)
  }

  const saveNew = async (color: HighlightColor, note: string) => {
    if (!pending) return
    const h: Highlight = {
      id: crypto.randomUUID(),
      itemId,
      itemTitle: book?.title ?? '',
      itemLink: null,
      feedTitle: book?.author ?? '',
      imageUrl: null,
      text: pending.text,
      start: 0,
      prefix: '',
      color,
      note: note.trim(),
      createdAt: Date.now(),
      cfi: pending.cfi,
    }
    await saveHighlight(h)
    viewRef.current?.deselect()
    setPending(null)
    toast('Highlighted')
  }

  const closeBar = () => {
    setPending(null)
    setEditing(null)
  }

  const fraction = Number.isFinite(loc?.fraction) ? loc!.fraction : 0
  const chapter = loc?.tocItem?.label ?? ''
  const pages = pageNumbers(loc, !!viewRef.current?.isFixedLayout)
  const pageLabel = pages ? `${pages.page} / ${pages.pages}` : ''

  return (
    <section className={`book-reader theme-${prefs.theme}`} aria-label={book?.title ?? 'Book'}>
      <header className="zim-bar book-bar">
        <IconButton icon="back" label="Ebooks" onClick={onBack} />
        <span className="zim-titles">
          <span className="zim-book-title">{book?.title ?? ''}</span>
          {chapter ? <span className="zim-page-title">{chapter}</span> : null}
        </span>
        <span className="book-bar-actions">
          <IconButton icon="toc" label="Contents" onClick={() => setPanel(panel === 'toc' ? null : 'toc')} />
          <IconButton icon="highlight" label={`Highlights (${highlights.length})`} onClick={() => setPanel(panel === 'highlights' ? null : 'highlights')} />
          <IconButton icon="textSize" label="Text size and theme" onClick={() => setPanel(panel === 'look' ? null : 'look')} />
          <IconButton icon="history" label="History" onClick={() => setPanel(panel === 'history' ? null : 'history')} />
          <IconButton icon="headphones" label="Listen: read aloud" text="Listen" onClick={() => (listening ? readAloud.toggle() : startListening())} disabled={status.loading || !!status.error} className="bar-primary" />
        </span>
      </header>

      <div className="book-body">
        <div ref={host} className="book-host" />
        {status.loading ? (
          <div className="book-status">
            <p>{status.total ? `Downloading from Dropbox: ${formatBytes(status.done)} of ${formatBytes(status.total)}` : 'Opening…'}</p>
            {status.total ? <progress max={status.total} value={status.done} /> : null}
          </div>
        ) : null}
        {status.error ? (
          <div className="book-status">
            <p>{status.error}</p>
          </div>
        ) : null}
        {!status.loading && !status.error ? (
          <>
            <button type="button" className="book-turn left" aria-label="Previous page" onClick={() => void viewRef.current?.goLeft()}>
              <Icon name="prev" size={24} />
            </button>
            <button type="button" className="book-turn right" aria-label="Next page" onClick={() => void viewRef.current?.goRight()}>
              <Icon name="next" size={24} />
            </button>
          </>
        ) : null}

        {panel ? (
          <aside className="book-panel" aria-label={PANEL_TITLES[panel]}>
            <div className="book-panel-head">
              <h2>{PANEL_TITLES[panel]}</h2>
              <IconButton icon="close" label="Close" onClick={() => setPanel(null)} />
            </div>
            {panel === 'toc' ? (
              <TocList
                items={toc}
                current={loc?.tocItem?.href ?? null}
                onGo={(href) => {
                  void viewRef.current?.goTo(href)
                  if (window.innerWidth < 900) setPanel(null)
                }}
              />
            ) : panel === 'highlights' ? (
              highlights.length ? (
                <div className="book-hls">
                  {[...highlights]
                    .sort((a, b) => a.createdAt - b.createdAt)
                    .map((h) => (
                      <button
                        key={h.id}
                        type="button"
                        className="book-hl"
                        onClick={() => {
                          if (h.cfi) void viewRef.current?.goTo(h.cfi)
                          if (window.innerWidth < 900) setPanel(null)
                        }}
                      >
                        <span className={`book-hl-swatch hl-${h.color}`} />
                        <span className="book-hl-text">
                          {h.text}
                          {h.note ? <span className="book-hl-note">{h.note}</span> : null}
                        </span>
                      </button>
                    ))}
                </div>
              ) : (
                <p className="muted">Select text in the book to highlight it.</p>
              )
            ) : panel === 'history' ? (
              <HistoryList
                c="ebook"
                i={id}
                onJump={(e) => {
                  if (e.x) void viewRef.current?.goTo(e.x)
                  if (window.innerWidth < 900) setPanel(null)
                }}
              />
            ) : (
              <LookPanel prefs={prefs} onChange={setPrefs} />
            )}
          </aside>
        ) : null}
      </div>

      <footer className="book-foot">
        <input
          type="range"
          min={0}
          max={1}
          step={0.001}
          value={fraction}
          aria-label="Position in the book"
          onChange={(e) => void viewRef.current?.goToFraction(Number(e.target.value))}
          disabled={status.loading}
        />
        <span className="book-pct">{pageLabel ? `${pageLabel} · ` : ''}{Math.round(fraction * 100)}%</span>
      </footer>

      {pending || editing ? (
        <HighlightBar
          text={pending?.text ?? editing!.text}
          existing={editing}
          onSave={async (color, note) => {
            if (editing) {
              await updateHighlight(editing.id, { color, note: note.trim() })
              setEditing({ ...editing, color, note: note.trim() })
            } else await saveNew(color, note)
          }}
          onShare={async (color, note) => {
            const text = quoteText({ text: pending?.text ?? editing!.text, itemTitle: book?.title ?? '', feedTitle: book?.author ?? '', itemLink: null }, note)
            if (!editing) await saveNew(color, note)
            if (navigator.share) await navigator.share({ text }).catch(() => undefined)
            else {
              await navigator.clipboard.writeText(text)
              toast('Quote copied')
            }
          }}
          onCopyText={async (note) => {
            await navigator.clipboard.writeText(quoteText({ text: pending?.text ?? editing!.text, itemTitle: book?.title ?? '', feedTitle: book?.author ?? '', itemLink: null }, note))
            toast('Copied')
          }}
          onDelete={async () => {
            if (!editing) return
            await deleteHighlight(editing.id)
            setEditing(null)
            toast('Highlight deleted')
          }}
          onClose={closeBar}
        />
      ) : null}

      {listening ? (
        <Player
          state={ra}
          settings={settings}
          currentTitle={chapter || (book?.title ?? '')}
          hasPrev={!atStart(viewRef.current)}
          hasNext={!atEnd(viewRef.current)}
          nextTitle={null}
          onPrev={() => {
            continueNext.current = true
            readAloud.stop()
            void prevSection(viewRef.current)
          }}
          onNext={() => {
            continueNext.current = true
            readAloud.stop()
            void nextSection(viewRef.current)
          }}
        />
      ) : null}
    </section>
  )
}

function TocList({ items, current, onGo, depth = 0 }: { items: TocItem[]; current: string | null; onGo: (href: string) => void; depth?: number }) {
  return (
    <ul className="book-toc" style={depth ? { paddingLeft: 14 } : undefined}>
      {items.map((t, i) => (
        <li key={`${t.href}:${i}`}>
          <button type="button" className={t.href === current ? 'on' : ''} onClick={() => onGo(t.href)}>
            {t.label?.trim() || 'Untitled'}
          </button>
          {t.subitems?.length ? <TocList items={t.subitems} current={current} onGo={onGo} depth={depth + 1} /> : null}
        </li>
      ))}
    </ul>
  )
}

function LookPanel({ prefs, onChange }: { prefs: ReaderPrefs; onChange: (p: ReaderPrefs) => void }) {
  return (
    <div className="book-look">
      <label className="book-look-row">
        <span>Text size</span>
        <span className="book-look-steps">
          <button type="button" className="mf-btn" onClick={() => onChange({ ...prefs, fontSize: Math.max(70, prefs.fontSize - 10) })} aria-label="Smaller text">
            A−
          </button>
          <span className="book-look-val">{prefs.fontSize}%</span>
          <button type="button" className="mf-btn" onClick={() => onChange({ ...prefs, fontSize: Math.min(220, prefs.fontSize + 10) })} aria-label="Bigger text">
            A+
          </button>
        </span>
      </label>
      <label className="book-look-row">
        <span>Line spacing</span>
        <input type="range" min={1.2} max={2} step={0.1} value={prefs.lineHeight} onChange={(e) => onChange({ ...prefs, lineHeight: Number(e.target.value) })} />
      </label>
      <div className="book-look-row">
        <span>Theme</span>
        <span className="book-look-steps">
          {(['auto', 'light', 'sepia', 'dark'] as const).map((t) => (
            <button key={t} type="button" className={`mf-btn${prefs.theme === t ? ' primary' : ''}`} onClick={() => onChange({ ...prefs, theme: t })}>
              {t === 'auto' ? 'App' : t[0].toUpperCase() + t.slice(1)}
            </button>
          ))}
        </span>
      </div>
      <div className="book-look-row">
        <span>Pages</span>
        <span className="book-look-steps">
          <button type="button" className={`mf-btn${prefs.flow === 'paginated' ? ' primary' : ''}`} onClick={() => onChange({ ...prefs, flow: 'paginated' })}>
            Turn pages
          </button>
          <button type="button" className={`mf-btn${prefs.flow === 'scrolled' ? ' primary' : ''}`} onClick={() => onChange({ ...prefs, flow: 'scrolled' })}>
            Scroll
          </button>
        </span>
      </div>
      <label className="book-look-row">
        <input type="checkbox" checked={prefs.publisherFont} onChange={(e) => onChange({ ...prefs, publisherFont: e.target.checked })} />
        <span>Use the book&apos;s own fonts</span>
      </label>
    </div>
  )
}
