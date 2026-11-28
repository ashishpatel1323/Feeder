import { useLiveQuery } from 'dexie-react-hooks'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { db, type Highlight, type HighlightColor } from '../lib/db'
import { deleteHighlight, quoteText, saveHighlight, updateHighlight } from '../lib/highlights'
import { trackOpen } from '../lib/history'
import { noteZimPage } from '../lib/recent'
import { readAloud, type ReadAloudState } from '../lib/readaloud'
import type { Settings } from '../lib/settings'
import { firstVisibleText, highlightAt, injectMarkStyles, keepInView, paintHighlights, parseZimItemId, readableRoot, scrollToRange, selectionAnchor, sketchAt, zimItemId } from '../lib/zim/marks'
import { formatBytes } from '../lib/offline'
import { onDropboxChange, type DropboxFile } from '../lib/dropbox'
import {
  catalogIcon,
  deleteBook,
  deleteFromDropbox,
  downloadFromDropbox,
  downloadUrl,
  listDropboxZims,
  sameZim,
  uploadToDropbox,
  importFile,
  lastPath,
  listBooks,
  onLibraryChange,
  openBook,
  parseZimUrl,
  rememberPath,
  searchCatalog,
  storageUsage,
  zimSupported,
  zimUrl,
  type CatalogEntry,
  type ZimBook,
} from '../lib/zim/library'
import { confirmDropboxDelete, needsDropbox, useDropbox } from './DropboxPanel'
import { LibraryListing, ListingUnavailable, useShelfPrefs, type ShelfItem } from './Shelf'
import { autoStatus, effectiveStatus, markKey, setStatus } from '../lib/shelfMarks'
import { HighlightBar } from './Highlights'
import { SketchEditor } from './SketchEditor'
import { InkPreview, PenBar, usePageInk, usePenState } from './PageInk'
import { encodeInk, inkMarkAt } from '../lib/pageink'
import { anchorOf, locateRange, textOf } from '../lib/textanchor'
import { rangeAt } from '../lib/zim/marks'
import { HistoryList } from './History'
import { Icon, IconButton } from './Icon'
import { Player } from './Player'
import { confirmDelete, confirmDeleteHighlight } from '../lib/confirmDelete'

/**
 * The ZIM tab: offline websites (Wikipedia, Stack Exchange, docs made with zimit…) kept on this
 * device. #/zim is the library, #/zim/<book> reads one book.
 */
/** A page to go to in the open book (from search); n changes each time, so the same page can be asked for again. */
export interface ZimJump {
  path: string
  n: number
  /** A highlight on that page to scroll to. */
  highlight?: string
}

export function ZimPage(p: { book: string | null; jump?: ZimJump | null; settings: Settings; onOpen: (id: string | null) => void; onMenu: () => void; toast: (m: string) => void }) {
  if (!zimSupported()) return <ListingUnavailable title="ZIM library" bookTab="zim" />
  return p.book ? <ZimReader key={p.book} id={p.book} jump={p.jump ?? null} settings={p.settings} onBack={() => p.onOpen(null)} toast={p.toast} /> : <ZimLibrary onOpen={p.onOpen} onMenu={p.onMenu} toast={p.toast} />
}

interface Job {
  key: string
  label: string
  done: number
  total: number
  abort: AbortController
}

function ZimLibrary({ onOpen, onMenu, toast }: { onOpen: (id: string) => void; onMenu: () => void; toast: (m: string) => void }) {
  const [books, setBooks] = useState<ZimBook[] | null>(null)
  const [broken, setBroken] = useState<string[]>([])
  const [usage, setUsage] = useState<{ used: number; quota: number } | null>(null)
  const [jobs, setJobs] = useState<Job[]>([])
  const [catalog, setCatalog] = useState(false)
  const [remote, setRemote] = useState<DropboxFile[] | null>(null)
  const fileInput = useRef<HTMLInputElement>(null)
  const dbx = useDropbox()
  const prefs = useShelfPrefs('zim', 'cards')
  const picked = useLiveQuery(() => db.shelfStatus.where('key').startsWith('zim:').toArray(), []) ?? []
  // When each site was last opened, on any device (History syncs)
  const opens = useLiveQuery(() => db.recent.filter((e) => e.t === 'zim' && !e.del).toArray(), []) ?? []
  const statusOf = useMemo(() => new Map(picked.map((x) => [x.key, x])), [picked])
  const openedAt = useMemo(() => {
    const m = new Map<string, number>()
    for (const e of opens) {
      const book = e.i.split('\n')[0]
      m.set(book, Math.max(m.get(book) ?? 0, e.at))
    }
    return m
  }, [opens])
  const [icons, setIcons] = useState<Record<string, string>>({})
  // Landing-page covers come from the service worker, like the reader's pages
  const [swReady, setSwReady] = useState(false)
  useEffect(() => {
    let live = true
    navigator.serviceWorker?.ready.then(() => live && setSwReady(true))
    return () => {
      live = false
    }
  }, [])

  const refreshRemote = useCallback(() => {
    listDropboxZims().then(setRemote, (e) => {
      setRemote(null)
      toast(e instanceof Error ? e.message : String(e))
    })
  }, [toast])
  useEffect(() => {
    refreshRemote()
    return onDropboxChange(refreshRemote)
  }, [refreshRemote])

  const refresh = useCallback(() => {
    listBooks()
      .then((r) => {
        setBooks(r.books)
        setBroken(r.broken)
      })
      .catch((e) => {
        setBooks([])
        toast(`Could not read the library: ${e}`)
      })
    storageUsage().then(setUsage, () => undefined)
  }, [toast])
  useEffect(() => {
    refresh()
    return onLibraryChange(refresh)
  }, [refresh])

  const run = useCallback(
    (label: string, work: (onProgress: (d: number, t: number) => void, signal: AbortSignal) => Promise<string>, done?: (file: string) => void) => {
      const key = `${label}:${Date.now()}`
      const abort = new AbortController()
      setJobs((j) => [...j, { key, label, done: 0, total: 0, abort }])
      let last = 0
      work((done, total) => {
        // A few updates a second is enough
        if (Date.now() - last < 250 && done < total) return
        last = Date.now()
        setJobs((j) => j.map((x) => (x.key === key ? { ...x, done, total } : x)))
      }, abort.signal)
        .then((file) => (done ? done(file) : toast(`${label} is ready to read`)))
        .catch((e) => {
          if (!abort.signal.aborted) toast(`${label}: ${e instanceof Error ? e.message : e}`)
        })
        .finally(() => setJobs((j) => j.filter((x) => x.key !== key)))
    },
    [toast],
  )

  /** Puts a book on this device into Dropbox too, for the other devices (only when asked). */
  const upload = useCallback(
    (file: string, label: string) =>
      run(`Uploading ${label} to Dropbox`, (prog, signal) => uploadToDropbox(file, prog, signal), () => {
        toast(`${label} is in Dropbox`)
        refreshRemote()
      }),
    [run, toast, refreshRemote],
  )
  const added = (label: string) => () => toast(`${label} is ready to read on this device`)

  const onFiles = (files: File[]) => {
    for (const f of files) run(f.name, (prog, signal) => importFile(f, prog, signal), added(f.name))
  }

  const download = (e: CatalogEntry) => {
    run(e.title, (prog, signal) =>
      downloadUrl(e.url, prog, signal).catch((err) => {
        // Most mirrors don't let web pages download from them: let the browser save the file
        if (err instanceof TypeError) {
          window.open(e.url, '_blank', 'noopener')
          throw new Error('your browser is downloading it. When it is done, use Add .zim file.')
        }
        throw err
      }),
      added(e.title),
    )
  }

  const fromDropbox = (f: DropboxFile) => run(f.name, (prog, signal) => downloadFromDropbox(f, prog, signal))
  const inDropbox = (b: ZimBook) => !!remote?.some((f) => sameZim(b, f))
  const onlyInDropbox = (remote ?? []).filter((f) => !books?.some((b) => sameZim(b, f)))
  // Covers for books only in Dropbox, from the Kiwix catalog
  const missingIcons = onlyInDropbox.filter((f) => !(f.name in icons)).map((f) => f.name).join('\n')
  useEffect(() => {
    if (!missingIcons) return
    let live = true
    for (const name of missingIcons.split('\n')) {
      void catalogIcon(name).then((url) => live && setIcons((m) => ({ ...m, [name]: url ?? '' })))
    }
    return () => {
      live = false
    }
  }, [missingIcons])

  const removeFromDropbox = async (f: DropboxFile, onDevice: boolean) => {
    const name = f.name.replace(/\.zim$/i, '').replace(/_/g, ' ')
    if (!(await confirmDropboxDelete(name, onDevice))) return
    try {
      await deleteFromDropbox(f)
      toast(`Deleted ${name} from Dropbox`)
    } catch (e) {
      toast(e instanceof Error ? e.message : String(e))
    }
    refreshRemote()
  }

  const remove = async (b: ZimBook) => {
    const ok = await confirmDelete(
      inDropbox(b)
        ? { title: 'Remove from this device?', itemName: `${b.title} (${formatBytes(b.size)})`, scope: 'this-device', confirmLabel: 'Remove', extra: 'It stays in Dropbox.' }
        : { title: 'Delete offline site?', itemName: `${b.title} (${formatBytes(b.size)})`, scope: 'this-device', extra: "It isn't in Dropbox, so this is the only copy." },
    )
    if (!ok) return
    await deleteBook(b.file)
    toast(`Deleted ${b.title}`)
  }

  const jobFor = (label: string) => jobs.find((j) => j.label === label)
  const percent = (j: Job | undefined, verb: string) => (j ? `${verb} ${j.total ? `${Math.round((j.done / j.total) * 100)}%` : '…'}` : undefined)
  const items: ShelfItem[] = [
    ...(books ?? []).map((b): ShelfItem => {
      const remoteCopy = remote?.find((f) => sameZim(b, f))
      const actions: ShelfItem['actions'] = []
      const uploadingNow = jobs.some((j) => j.label === `Uploading ${b.title} to Dropbox`)
      if ((!dbx.signedIn || remote) && !remoteCopy && !uploadingNow) actions.push({ label: 'Upload to Dropbox', run: () => needsDropbox(dbx.signedIn, toast, () => upload(b.file, b.title)) })
      if (remoteCopy) actions.push({ label: 'Delete from Dropbox', run: () => void removeFromDropbox(remoteCopy, true) })
      actions.push({ label: remoteCopy ? 'Remove from this device' : 'Delete', run: () => void remove(b) })
      const mk = markKey('zim', b.id)
      const mine = statusOf.get(mk)
      const opened = openedAt.get(b.id) ?? 0
      // A site has no end, so it never becomes Completed by itself
      const status = effectiveStatus(autoStatus(null, opened > 0 || !!lastPath(b.id), false), mine, opened)
      return {
        status,
        sortAt: Math.max(opened, mine?.ts ?? 0),
        onStatus: (st) => void setStatus(mk, st),
        key: `local:${b.file}`,
        title: b.title,
        subtitle: b.description,
        detail: [formatBytes(b.size), b.flavour, b.articleCount ? `${b.articleCount.toLocaleString()} pages` : '', b.date].filter(Boolean).join(' · '),
        cover: b.icon,
        coverFit: 'icon',
        coverIcon: 'public',
        preview: !b.icon && swReady ? zimUrl(b.id, '') : undefined,
        where: remoteCopy ? 'both' : 'device',
        size: b.size,
        busy: percent(jobs.find((j) => j.label === `Uploading ${b.title} to Dropbox`), 'Uploading'),
        quick: { icon: 'book', label: `Open ${b.title}`, run: () => onOpen(b.id) },
        onOpen: () => onOpen(b.id),
        actions,
      }
    }),
    ...onlyInDropbox.map(
      (f): ShelfItem => ({
        key: `dbx:${f.id}`,
        status: 'new',
        title: f.name.replace(/\.zim$/i, '').replace(/_/g, ' '),
        detail: formatBytes(f.size),
        cover: icons[f.name] || null,
        coverFit: 'icon',
        coverIcon: 'public',
        where: 'cloud',
        size: f.size,
        busy: percent(jobFor(f.name), 'Downloading'),
        quick: jobFor(f.name) ? undefined : { icon: 'download', label: `Download and open ${f.name.replace(/\.zim$/i, '').replace(/_/g, ' ')}`, run: () => fromDropbox(f) },
        onOpen: () => {
          if (!jobFor(f.name)) fromDropbox(f)
        },
        actions: [
          ...(jobFor(f.name) ? [] : [{ label: 'Download to this device', run: () => fromDropbox(f) }]),
          { label: 'Delete from Dropbox', run: () => void removeFromDropbox(f, false) },
        ],
      }),
    ),
  ]

  return (
    <LibraryListing
      bookTab="zim"
      title="ZIM library"
      prefs={prefs}
      items={items}
      emptyIcon="public"
      emptyText="Whole websites to read offline, such as Wikipedia, Wiktionary, Stack Exchange or your own sites made with zimit. No books yet: get one from the Kiwix library, or add a .zim file you already have (you can also drop it here)."
      emptyDevice="No ZIM books on this device yet. Download one from the In Dropbox list."
      addLabel="Add ZIM files"
      addActions={[
        { icon: 'explore', label: 'Get ZIM files…', run: () => setCatalog((c) => !c) },
        { icon: 'upload', label: 'Add .zim file…', run: () => fileInput.current?.click() },
      ]}
      dropboxFolder="ZIM"
      signedIn={dbx.signedIn}
      toast={toast}
      onMenu={onMenu}
      onRefresh={refreshRemote}
      refreshLabel="Check Dropbox for ZIM files"
      loading={books === null}
      jobs={jobs
        .filter((j) => !onlyInDropbox.some((f) => f.name === j.label) && !j.label.startsWith('Uploading '))
        .map((j) => ({ key: j.key, label: j.label, done: j.done, total: j.total, onCancel: () => j.abort.abort() }))}
      extra={catalog ? <Catalog onDownload={download} /> : null}
      footer={
        <>
          {broken.length ? <p className="muted">Could not open: {broken.join(', ')}</p> : null}
          {usage ? (
            <p className="shelf-usage muted">
              Feeder uses {formatBytes(usage.used)} on this device{usage.quota ? `, ${formatBytes(usage.quota)} available to it` : ''}.
            </p>
          ) : null}
        </>
      }
      onDropFiles={onFiles}
    >
      <input
        ref={fileInput}
        type="file"
        accept=".zim,application/x-zim"
        multiple
        hidden
        onChange={(e) => {
          onFiles([...(e.target.files ?? [])])
          e.target.value = ''
        }}
      />
    </LibraryListing>
  )
}

const LANGS: [string, string][] = [
  ['', 'All languages'],
  ['eng', 'English'],
  ['hin', 'Hindi'],
  ['fra', 'French'],
  ['deu', 'German'],
  ['spa', 'Spanish'],
]

function Catalog({ onDownload }: { onDownload: (e: CatalogEntry) => void }) {
  const [query, setQuery] = useState('')
  const [lang, setLang] = useState('eng')
  const [results, setResults] = useState<CatalogEntry[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    const abort = new AbortController()
    const t = setTimeout(() => {
      setError(null)
      searchCatalog(query, lang, abort.signal).then(setResults, (e) => {
        if (!abort.signal.aborted) setError(String(e))
      })
    }, 300)
    return () => {
      clearTimeout(t)
      abort.abort()
    }
  }, [query, lang])
  return (
    <div className="zim-catalog">
      <div className="zim-catalog-bar">
        <input type="search" placeholder="Search the Kiwix library (wikipedia, medicine, python…)" value={query} onChange={(e) => setQuery(e.target.value)} autoFocus />
        <select value={lang} onChange={(e) => setLang(e.target.value)} aria-label="Language">
          {LANGS.map(([v, l]) => (
            <option key={v} value={v}>
              {l}
            </option>
          ))}
        </select>
      </div>
      {error ? <p className="muted">{error}</p> : results === null ? <p className="muted">Loading…</p> : null}
      {results?.length === 0 ? <p className="muted">Nothing found.</p> : null}
      <div className="zim-catalog-list">
        {results?.map((e) => (
          <div key={e.id} className="zim-entry">
            {e.icon ? <img className="zim-icon" src={e.icon} alt="" loading="lazy" /> : <Icon name="public" size={32} />}
            <span className="lib-text">
              <span className="lib-title">
                {e.title} {e.flavour ? <span className="chip zim-flavour">{e.flavour}</span> : null}
              </span>
              <span className="lib-desc">{e.summary}</span>
              <span className="lib-desc">{[formatBytes(e.size), `${e.articleCount.toLocaleString()} pages`, e.date].join(' · ')}</span>
            </span>
            <button type="button" className="mf-btn" onClick={() => onDownload(e)} title={e.url}>
              <Icon name="download" size={18} />
              {formatBytes(e.size)}
            </button>
          </div>
        ))}
      </div>
    </div>
  )
}

function ZimReader({ id, jump, settings, onBack, toast }: { id: string; jump: ZimJump | null; settings: Settings; onBack: () => void; toast: (m: string) => void }) {
  const frame = useRef<HTMLIFrameElement>(null)
  const [ready, setReady] = useState(false)
  const [title, setTitle] = useState(id)
  const [pageTitle, setPageTitle] = useState('')
  const [src, setSrc] = useState(() => zimUrl(id, lastPath(id) ?? ''))
  const [query, setQuery] = useState('')
  const [hits, setHits] = useState<{ title: string; path: string }[]>([])
  const [active, setActive] = useState(0)
  const [error, setError] = useState<string | null>(null)
  /** The page shown, as its path in the book (null until a page of the book loads). */
  const [path, setPath] = useState<string | null>(null)
  /** Bumped on each page load, so marks are painted on the new page. */
  const [loads, setLoads] = useState(0)
  const [pending, setPending] = useState<{ start: number; text: string; prefix: string } | null>(null)
  const [editing, setEditing] = useState<Highlight | null>(null)
  /** A margin sketch being drawn: for a selection (anchor) or an existing sketch. */
  const [sketching, setSketching] = useState<{ anchor: { text: string; start: number; prefix: string } } | { id: string } | null>(null)
  const [panel, setPanel] = useState<false | 'highlights' | 'history'>(false)
  const [ra, setRa] = useState<ReadAloudState>(readAloud.state)
  /** History: the page shown now, and when the book was opened and closed */
  const here = useRef<{ path: string; title: string } | null>(null)
  const opened = useRef<ReturnType<typeof trackOpen> | null>(null)
  useEffect(() => readAloud.subscribe(setRa), [])

  const itemId = path === null ? null : zimItemId(id, path)
  const bookHighlights = useLiveQuery(() => db.highlights.where('itemId').startsWith(zimItemId(id, '')).toArray(), [id]) ?? []
  const highlights = useMemo(() => bookHighlights.filter((h) => h.itemId === itemId), [bookHighlights, itemId])
  const highlightsRef = useRef<Highlight[]>([])
  highlightsRef.current = highlights
  const painted = useRef(new Map<string, Range>())
  // Handwriting on the page (Kobo style): a stylus writes; Draw on page lets fingers and the mouse write
  const pen = usePenState()
  const [inkOpen, setInkOpen] = useState<string | null>(null)
  /** A highlight to scroll to once its page shows (from the Highlights page, search or the panel). */
  const scrollTo = useRef<string | null>(jump?.highlight ?? null)
  const listening = !!itemId && ra.itemId === itemId && (ra.status !== 'idle' || ra.muted)

  const doc = () => {
    try {
      return frame.current?.contentDocument ?? null
    } catch {
      return null
    }
  }

  const inkDoc = path !== null && loads > 0 ? doc() : null
  const inkNotes = useMemo(() => highlights.filter((h) => h.ink), [highlights])
  usePageInk({
    doc: inkDoc,
    root: inkDoc?.body ?? null,
    pen,
    notes: inkNotes,
    version: loads,
    rangeOf: (h) => {
      const body = inkDoc?.body
      if (!body) return null
      const at = locateRange(textOf(body), h)
      return at ? rangeAt(body, at[0], at[1]) : null
    },
    onNote: (range, data) => {
      const body = inkDoc?.body
      const a = body ? anchorOf(body, range) : null
      if (!a || !itemId) return
      void saveHighlight({
        id: crypto.randomUUID(),
        itemId,
        itemTitle: pageTitle || title,
        itemLink: null,
        feedTitle: title,
        imageUrl: null,
        text: a.text,
        start: a.start,
        prefix: a.prefix,
        color: pen.color,
        note: '',
        createdAt: Date.now(),
        ink: encodeInk(data),
      })
    },
    onErase: (id, data) => {
      if (data) void updateHighlight(id, { ink: encodeInk(data) })
      else void deleteHighlight(id)
    },
  })

  useEffect(() => {
    let live = true
    here.current = null
    const tracker = trackOpen('zim', id, () => (here.current ? { p: here.current.title || here.current.path || 'Main page', x: here.current.path } : null))
    opened.current = tracker
    openBook(id)
      .then(async (z) => {
        const t = await z.metadata('Title')
        if (live && t) setTitle(t)
      })
      .catch(() => live && setError('This book is not on this device any more.'))
    // The pages come from the service worker: wait for it, or the iframe would load the app itself
    navigator.serviceWorker?.ready.then(() => live && setReady(true))
    return () => {
      live = false
      tracker.stop()
      if (readAloud.state.itemId?.startsWith(zimItemId(id, ''))) readAloud.stop()
      readAloud.revealHook = null
    }
  }, [id])

  useEffect(() => {
    const q = query.trim()
    if (!q) return setHits([])
    let live = true
    const t = setTimeout(() => {
      openBook(id)
        .then((z) => z.suggest(q, 12))
        .then((h) => {
          if (!live) return
          setHits(h)
          setActive(0)
        })
        .catch(() => undefined)
    }, 120)
    return () => {
      live = false
      clearTimeout(t)
    }
  }, [query, id])

  const go = (path: string) => {
    setQuery('')
    setHits([])
    const url = zimUrl(id, path)
    // Navigate inside the frame, so its Back button history keeps working
    const w = frame.current?.contentWindow
    if (w) w.location.href = url
    else setSrc(url)
  }

  // A search result in the book that's already open: go there (a new book opens at it, see rememberPath)
  const seenJump = useRef(jump?.n)
  useEffect(() => {
    if (!jump || jump.n === seenJump.current) return
    seenJump.current = jump.n
    scrollTo.current = jump.highlight ?? null
    if (jump.path === path && jump.highlight) {
      const r = painted.current.get(jump.highlight)
      const d = doc()
      if (r && d) scrollToRange(d, r)
      scrollTo.current = null
    } else go(jump.path)
  }, [jump])

  // Highlights painted on the page, again when they change (here, synced or on the Highlights page)
  useEffect(() => {
    const d = doc()
    if (!d || path === null) return
    painted.current = paintHighlights(d, highlights.filter((h) => !h.ink))
    const want = scrollTo.current
    if (want && painted.current.has(want)) {
      scrollTo.current = null
      scrollToRange(d, painted.current.get(want)!)
    }
  }, [highlights, loads, path])

  const onLoad = () => {
    const w = frame.current?.contentWindow
    if (!w) return
    setPending(null)
    setEditing(null)
    if (readAloud.state.itemId?.startsWith(zimItemId(id, ''))) readAloud.stop()
    try {
      const at = parseZimUrl(w.location.pathname)
      if (at?.id === id && at.path) rememberPath(id, at.path)
      setPath(at?.id === id ? at.path : null)
      setLoads((n) => n + 1)
      setPageTitle(w.document.title)
      if (at?.id === id) {
        here.current = { path: at.path, title: w.document.title }
        opened.current?.touch()
        // History: every page read is an entry of its own
        void noteZimPage(id, at.path, w.document.title).catch(() => undefined)
      }
      const d = w.document
      injectMarkStyles(d)
      // Links out of the book open in a new tab
      d.addEventListener('click', (e) => {
        const a = (e.target as Element | null)?.closest?.('a[href]') as HTMLAnchorElement | null
        if (!a) {
          // A tap on a [✎] marker shows a note written with other settings
          const ink = inkMarkAt(e.target)
          if (ink) {
            setPending(null)
            setEditing(null)
            setInkOpen(ink)
            return
          }
          // A tap on a sketch opens it to edit
          const sk = sketchAt(e.target)
          if (sk) {
            setPending(null)
            setEditing(null)
            setSketching({ id: sk })
            return
          }
          // A tap on a highlight opens it
          if (d.getSelection()?.isCollapsed === false) return
          const hit = highlightAt(d, e.clientX, e.clientY, painted.current)
          const h = hit ? highlightsRef.current.find((x) => x.id === hit) : null
          if (h) {
            setPending(null)
            setEditing(h)
          }
          return
        }
        const url = new URL(a.href, w.location.href)
        if (url.origin !== location.origin || !url.pathname.startsWith('/zim/')) {
          e.preventDefault()
          window.open(url.href, '_blank', 'noopener')
        }
      })
      // Selecting text offers to highlight it
      const checkSelection = () =>
        window.setTimeout(() => {
          const a = selectionAnchor(d)
          if (!a) return
          setEditing(null)
          setPending(a)
        }, 10)
      d.addEventListener('pointerup', checkSelection)
      d.addEventListener('keyup', (k) => k.shiftKey && checkSelection())
      // Scrolling the page yourself stops read aloud from scrolling it
      const userScroll = () => readAloud.userScrolled()
      d.addEventListener('wheel', userScroll, { passive: true })
      d.addEventListener('touchmove', userScroll, { passive: true })
    } catch {
      /* a page outside the book */
      setPath(null)
    }
  }

  /** Reads the page aloud from the top of what's on screen (or the start). */
  const startListening = () => {
    const d = doc()
    const root = d && readableRoot(d)
    if (!d || !root || !itemId) return
    readAloud.title = pageTitle || title
    readAloud.artist = title
    readAloud.attach(root, itemId, d.documentElement.lang || undefined)
    readAloud.revealHook = (r) => {
      const dd = r.startContainer.ownerDocument
      if (dd) keepInView(dd, r)
      return true
    }
    readAloud.onFinished = null
    const top = firstVisibleText(d)
    readAloud.play(top ? Math.max(0, readAloud.offsetOf(top.node, top.offset)) : 0)
  }

  const saveNew = async (color: HighlightColor, note: string, sketch?: string, anchor = pending) => {
    if (!anchor || !itemId) return
    await saveHighlight({
      id: crypto.randomUUID(),
      itemId,
      itemTitle: pageTitle || title,
      itemLink: null,
      feedTitle: title,
      imageUrl: null,
      text: anchor.text,
      start: anchor.start,
      prefix: anchor.prefix,
      color,
      note: note.trim(),
      createdAt: Date.now(),
      ...(sketch ? { sketch } : {}),
    })
    doc()?.getSelection()?.removeAllRanges()
    setPending(null)
    toast(sketch ? 'Sketch saved' : 'Highlighted')
  }
  const quote = (text: string, note?: string) => quoteText({ text, itemTitle: pageTitle || title, feedTitle: pageTitle && pageTitle !== title ? title : '', itemLink: null }, note)

  const sortedBookHighlights = useMemo(() => [...bookHighlights].sort((a, b) => a.itemTitle.localeCompare(b.itemTitle) || a.start - b.start), [bookHighlights])

  return (
    <section className="zim-reader" aria-label={title}>
      <header className="zim-bar">
        <IconButton icon="back" label="ZIM library" onClick={onBack} />
        <span className="zim-titles">
          <span className="zim-book-title">{title}</span>
          {pageTitle && pageTitle !== title ? <span className="zim-page-title">{pageTitle}</span> : null}
        </span>
        <div className="zim-search">
          <Icon name="search" size={18} />
          <input
            type="search"
            placeholder={`Search ${title}`}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'ArrowDown') {
                e.preventDefault()
                setActive((a) => Math.min(hits.length - 1, a + 1))
              } else if (e.key === 'ArrowUp') {
                e.preventDefault()
                setActive((a) => Math.max(0, a - 1))
              } else if (e.key === 'Enter' && hits[active]) go(hits[active].path)
              else if (e.key === 'Escape') setQuery('')
            }}
            aria-label={`Search titles in ${title}`}
          />
          {hits.length ? (
            <div className="zim-hits" role="listbox">
              {hits.map((h, i) => (
                <button key={h.path} type="button" role="option" aria-selected={i === active} className={i === active ? 'on' : ''} onMouseDown={(e) => e.preventDefault()} onClick={() => go(h.path)}>
                  {h.title}
                </button>
              ))}
            </div>
          ) : query.trim() ? (
            <div className="zim-hits">
              <span className="muted">No titles start with that</span>
            </div>
          ) : null}
        </div>
        <IconButton icon="home" label="Main page" onClick={() => go('')} />
        <IconButton icon="draw" label="Draw on page" active={pen.drawAll} onClick={() => pen.setDrawAll(!pen.drawAll)} />
        <IconButton icon="highlight" label={`Highlights in this book (${bookHighlights.length})`} onClick={() => setPanel((x) => (x === 'highlights' ? false : 'highlights'))} />
        <IconButton icon="history" label="History" onClick={() => setPanel((x) => (x === 'history' ? false : 'history'))} />
        <IconButton icon="headphones" label="Listen: read aloud" text="Listen" onClick={() => (listening ? readAloud.toggle() : startListening())} disabled={!itemId} className="bar-primary" />
      </header>
      <div className="zim-body">
        {error ? <p className="muted zim-error">{error}</p> : ready ? <iframe ref={frame} className="zim-frame" src={src} title={title} onLoad={onLoad} /> : null}
        {panel === 'history' ? (
          <aside className="book-panel" aria-label="History">
            <div className="book-panel-head">
              <h2>History</h2>
              <IconButton icon="close" label="Close" onClick={() => setPanel(false)} />
            </div>
            <HistoryList
              c="zim"
              i={id}
              onJump={(e) => {
                if (e.x !== undefined) go(e.x)
                if (window.innerWidth < 900) setPanel(false)
              }}
            />
          </aside>
        ) : panel ? (
          <aside className="book-panel" aria-label="Highlights">
            <div className="book-panel-head">
              <h2>Highlights</h2>
              <IconButton icon="close" label="Close" onClick={() => setPanel(false)} />
            </div>
            {sortedBookHighlights.length ? (
              <div className="book-hls">
                {sortedBookHighlights.map((h) => (
                  <button
                    key={h.id}
                    type="button"
                    className="book-hl"
                    onClick={() => {
                      const at = parseZimItemId(h.itemId)
                      if (at) {
                        scrollTo.current = h.id
                        if (at.path === path) {
                          const r = painted.current.get(h.id)
                          const d = doc()
                          if (r && d) scrollToRange(d, r)
                          scrollTo.current = null
                        } else go(at.path)
                      }
                      if (window.innerWidth < 900) setPanel(false)
                    }}
                  >
                    <span className={`book-hl-swatch hl-${h.color}`} />
                    <span className="book-hl-text">
                      {h.itemTitle && h.itemTitle !== title ? <span className="book-hl-note">{h.itemTitle}</span> : null}
                      {h.text}
                      {h.note ? <span className="book-hl-note">{h.note}</span> : null}
                    </span>
                  </button>
                ))}
              </div>
            ) : (
              <p className="muted">Select text on a page to highlight it.</p>
            )}
          </aside>
        ) : null}
      </div>

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
            const text = quote(pending?.text ?? editing!.text, note)
            if (!editing) await saveNew(color, note)
            if (navigator.share) await navigator.share({ text }).catch(() => undefined)
            else {
              await navigator.clipboard.writeText(text)
              toast('Quote copied')
            }
          }}
          onCopyText={async (note) => {
            await navigator.clipboard.writeText(quote(pending?.text ?? editing!.text, note))
            toast('Copied')
          }}
          onDelete={async () => {
            if (!editing) return
            if (!(await confirmDeleteHighlight(editing.text))) return
            await deleteHighlight(editing.id)
            setEditing(null)
            toast('Highlight deleted')
          }}
          onClose={() => {
            setPending(null)
            setEditing(null)
          }}
          onSketch={
            pending
              ? () => {
                  setSketching({ anchor: pending })
                  setPending(null)
                  doc()?.getSelection()?.removeAllRanges()
                }
              : undefined
          }
        />
      ) : null}
      {pen.drawAll ? <PenBar pen={pen} onDone={() => pen.setDrawAll(false)} /> : null}
      {inkOpen && highlights.find((h) => h.id === inkOpen) ? (
        <InkPreview
          note={highlights.find((h) => h.id === inkOpen)!}
          canSwitch={false}
          onSwitch={() => undefined}
          onDelete={async () => {
            const h = highlights.find((x) => x.id === inkOpen)!
            if (!(await confirmDeleteHighlight(h.text))) return
            await deleteHighlight(h.id)
            setInkOpen(null)
          }}
          onClose={() => setInkOpen(null)}
        />
      ) : null}
      {sketching ? (() => {
        const existing = 'id' in sketching ? highlights.find((h) => h.id === sketching.id) : undefined
        if ('id' in sketching && !existing) return null
        return (
          <SketchEditor
            quote={'anchor' in sketching ? sketching.anchor.text : existing!.text}
            initial={existing?.sketch ?? null}
            initialColor={existing?.color ?? 'pink'}
            onClose={() => setSketching(null)}
            onSave={async (sketch, color) => {
              if (existing) await updateHighlight(existing.id, { sketch, color })
              else if ('anchor' in sketching) await saveNew(color, '', sketch, sketching.anchor)
              setSketching(null)
            }}
            onDelete={
              existing
                ? async () => {
                    if (!(await confirmDeleteHighlight(existing.text))) return
                    await deleteHighlight(existing.id)
                    setSketching(null)
                    toast('Sketch deleted')
                  }
                : undefined
            }
          />
        )
      })() : null}

      {listening ? <Player state={ra} settings={settings} currentTitle={pageTitle || title} hasPrev={false} hasNext={false} nextTitle={null} onPrev={() => undefined} onNext={() => undefined} /> : null}
    </section>
  )
}
