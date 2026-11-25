import { useCallback, useEffect, useRef, useState } from 'react'
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
import { DropboxPanel, useDropbox } from './DropboxPanel'
import { Shelf, ShelfControls, useShelfPrefs, type ShelfItem } from './Shelf'
import { Icon, IconButton } from './Icon'

/**
 * The ZIM tab: offline websites (Wikipedia, Stack Exchange, docs made with zimit…) kept on this
 * device. #/zim is the library, #/zim/<book> reads one book.
 */
/** A page to go to in the open book (from search); n changes each time, so the same page can be asked for again. */
export interface ZimJump {
  path: string
  n: number
}

export function ZimPage(p: { book: string | null; jump?: ZimJump | null; onOpen: (id: string | null) => void; onMenu: () => void; toast: (m: string) => void }) {
  if (!zimSupported())
    return (
      <section className="manage-pane zim-page" aria-label="ZIM library">
        <div className="mf-page">
          <div className="mf-head">
            <h1>ZIM library</h1>
          </div>
          <p className="muted">This browser cannot keep files on the device. Use a current Chrome, Edge, Safari or Firefox.</p>
        </div>
      </section>
    )
  return p.book ? <ZimReader key={p.book} id={p.book} jump={p.jump ?? null} onBack={() => p.onOpen(null)} /> : <ZimLibrary onOpen={p.onOpen} onMenu={p.onMenu} toast={p.toast} />
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
  const prefs = useShelfPrefs('zim', 'list')
  const [icons, setIcons] = useState<Record<string, string>>({})
  const signedIn = useRef(false)
  signedIn.current = dbx.signedIn

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

  /** Puts a book that just arrived on this device into Dropbox too, for the other devices. */
  const upload = useCallback(
    (file: string, label: string) =>
      run(`Uploading ${label} to Dropbox`, (prog, signal) => uploadToDropbox(file, prog, signal), () => {
        toast(`${label} is in Dropbox`)
        refreshRemote()
      }),
    [run, toast, refreshRemote],
  )
  const added = (label: string) => (file: string) => {
    toast(`${label} is ready to read`)
    if (signedIn.current) upload(file, label)
  }

  const onFiles = (files: FileList | null) => {
    for (const f of files ?? []) run(f.name, (prog, signal) => importFile(f, prog, signal), added(f.name))
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
    if (!confirm(`Delete ${name} (${formatBytes(f.size)}) from Dropbox? Your other devices can no longer download it.${onDevice ? ' The copy on this device stays.' : ''}`)) return
    try {
      await deleteFromDropbox(f)
      toast(`Deleted ${name} from Dropbox`)
    } catch (e) {
      toast(e instanceof Error ? e.message : String(e))
    }
    refreshRemote()
  }

  const remove = async (b: ZimBook) => {
    if (!confirm(`Delete ${b.title} (${formatBytes(b.size)}) from this device?${inDropbox(b) ? ' It stays in Dropbox.' : ''}`)) return
    await deleteBook(b.file)
    toast(`Deleted ${b.title}`)
  }

  const jobFor = (label: string) => jobs.find((j) => j.label === label)
  const percent = (j: Job | undefined, verb: string) => (j ? `${verb} ${j.total ? `${Math.round((j.done / j.total) * 100)}%` : '…'}` : undefined)
  const items: ShelfItem[] = [
    ...(books ?? []).map((b): ShelfItem => {
      const remoteCopy = remote?.find((f) => sameZim(b, f))
      const actions: ShelfItem['actions'] = []
      if (dbx.signedIn && remote && !remoteCopy) actions.push({ label: 'Upload to Dropbox', run: () => upload(b.file, b.title) })
      if (remoteCopy) actions.push({ label: 'Delete from Dropbox', run: () => void removeFromDropbox(remoteCopy, true) })
      actions.push({ label: remoteCopy ? 'Remove from this device' : 'Delete', run: () => void remove(b) })
      return {
        key: `local:${b.file}`,
        title: b.title,
        subtitle: b.description,
        detail: [formatBytes(b.size), b.flavour, b.articleCount ? `${b.articleCount.toLocaleString()} pages` : '', b.date].filter(Boolean).join(' · '),
        cover: b.icon,
        coverIcon: 'public',
        where: remoteCopy ? 'both' : 'device',
        size: b.size,
        busy: percent(jobs.find((j) => j.label === `Uploading ${b.title} to Dropbox`), 'Uploading'),
        onOpen: () => onOpen(b.id),
        actions,
      }
    }),
    ...onlyInDropbox.map(
      (f): ShelfItem => ({
        key: `dbx:${f.id}`,
        title: f.name.replace(/\.zim$/i, '').replace(/_/g, ' '),
        detail: formatBytes(f.size),
        cover: icons[f.name] ?? null,
        coverIcon: 'public',
        where: 'cloud',
        size: f.size,
        busy: percent(jobFor(f.name), 'Downloading'),
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
    <section
      className="manage-pane zim-page"
      aria-label="ZIM library"
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
          <h1>ZIM library</h1>
          <span className="zim-actions">
            <button type="button" className={`mf-btn${catalog ? '' : ' primary'}`} onClick={() => setCatalog((c) => !c)} aria-expanded={catalog}>
              <Icon name="explore" size={18} />
              Get ZIM files
            </button>
            <button type="button" className="mf-btn" onClick={() => fileInput.current?.click()}>
              <Icon name="upload" size={18} />
              Add .zim file
            </button>
          </span>
          <input
            ref={fileInput}
            type="file"
            accept=".zim,application/x-zim"
            multiple
            hidden
            onChange={(e) => {
              onFiles(e.target.files)
              e.target.value = ''
            }}
          />
        </div>
        <p className="zim-intro muted">Whole websites to read offline, such as Wikipedia, Wiktionary, Stack Exchange or your own sites made with zimit.</p>
        <DropboxPanel what="ZIM books" toast={toast} />

        {catalog ? <Catalog onDownload={download} /> : null}

        {jobs.length ? (
          <div className="lib-list zim-jobs">
            {jobs.map((j) => (
              <div key={j.key} className="lib-row">
                <Icon name="download" size={22} />
                <span className="lib-text">
                  <span className="lib-title">{j.label}</span>
                  <span className="lib-desc">
                    {formatBytes(j.done)}
                    {j.total ? ` of ${formatBytes(j.total)}` : ''}
                  </span>
                  <progress max={j.total || 1} value={j.total ? j.done : undefined} />
                </span>
                <IconButton icon="close" label="Stop" onClick={() => j.abort.abort()} />
              </div>
            ))}
          </div>
        ) : null}

        {books === null ? (
          <p className="muted">Loading…</p>
        ) : books.length === 0 && !jobs.length && !onlyInDropbox.length ? (
          <div className="zim-empty">
            <Icon name="public" size={40} />
            <p>No books yet. Get one from the Kiwix library, or add a .zim file you already have (you can also drop it here).</p>
          </div>
        ) : (
          <>
            <ShelfControls {...prefs} counts={{ all: items.length, device: items.filter((i) => i.where !== 'cloud').length, cloud: items.filter((i) => i.where === 'cloud').length }} />
            <Shelf items={items} show={prefs.show} view={prefs.view} shape="tile" emptyDevice="No ZIM books on this device yet. Download one from the In Dropbox list." />
          </>
        )}
        {broken.length ? <p className="muted">Could not open: {broken.join(', ')}</p> : null}
        {usage ? (
          <p className="zim-usage muted">
            Feeder uses {formatBytes(usage.used)} on this device{usage.quota ? `, ${formatBytes(usage.quota)} available to it` : ''}.
          </p>
        ) : null}
      </div>
    </section>
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

function ZimReader({ id, jump, onBack }: { id: string; jump: ZimJump | null; onBack: () => void }) {
  const frame = useRef<HTMLIFrameElement>(null)
  const [ready, setReady] = useState(false)
  const [title, setTitle] = useState(id)
  const [pageTitle, setPageTitle] = useState('')
  const [src, setSrc] = useState(() => zimUrl(id, lastPath(id) ?? ''))
  const [query, setQuery] = useState('')
  const [hits, setHits] = useState<{ title: string; path: string }[]>([])
  const [active, setActive] = useState(0)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let live = true
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
    go(jump.path)
  }, [jump])

  const onLoad = () => {
    const w = frame.current?.contentWindow
    if (!w) return
    try {
      const at = parseZimUrl(w.location.pathname)
      if (at?.id === id && at.path) rememberPath(id, at.path)
      setPageTitle(w.document.title)
      // Links out of the book open in a new tab
      w.document.addEventListener('click', (e) => {
        const a = (e.target as Element | null)?.closest?.('a[href]') as HTMLAnchorElement | null
        if (!a) return
        const url = new URL(a.href, w.location.href)
        if (url.origin !== location.origin || !url.pathname.startsWith('/zim/')) {
          e.preventDefault()
          window.open(url.href, '_blank', 'noopener')
        }
      })
    } catch {
      /* a page outside the book */
    }
  }

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
      </header>
      {error ? <p className="muted zim-error">{error}</p> : ready ? <iframe ref={frame} className="zim-frame" src={src} title={title} onLoad={onLoad} /> : null}
    </section>
  )
}
