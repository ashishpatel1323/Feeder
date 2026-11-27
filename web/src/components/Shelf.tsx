import { useEffect, useRef, useState, type ReactNode } from 'react'
import { formatBytes } from '../lib/offline'
import { DropboxButton } from './DropboxPanel'
import { Icon, IconButton, type IconName } from './Icon'
import { STATUS_LABEL, STATUS_ORDER, type BookStatus } from '../lib/shelfMarks'
import { BOOK_TABS, bookCounts, rememberBookCount, rememberBookTab, tabKeys, type BookTab } from './Tabs'

/**
 * The shared listing of the Audiobooks, Ebooks and ZIM tabs: what is on this device (ready, also
 * offline) and what is only in Dropbox, as cards or a list. [LibraryListing] is the whole page
 * (header, jobs, empty state, controls, shelf), so the three tabs look and behave the same. The
 * Android app's shelf/LibraryListing.kt + Shelf.kt draw the same thing.
 */

/** both = on this device and in Dropbox; device = only here; cloud = only in Dropbox */
export type Where = 'both' | 'device' | 'cloud'
export type Show = 'all' | 'device' | 'cloud'
export type ShelfView = 'cards' | 'list'
/**
 * How a picture sits in the square cover every tab uses: fill crops it to the square (audiobook
 * art), fit shows all of it (a book cover), icon shows it small in the middle (a ZIM's site icon).
 */
export type CoverFit = 'fill' | 'fit' | 'icon'

export interface ShelfAction {
  label: string
  run: () => void
}

export interface ShelfItem {
  key: string
  title: string
  /** Author, or a ZIM's description */
  subtitle?: string
  /** A short line such as "42% read" or "6.5 M pages · 2025-01" */
  detail?: string
  cover: string | null
  /** How [cover] sits in the square; fill when left out */
  coverFit?: CoverFit
  coverIcon: IconName
  /** A page to show shrunk as the cover when there is no [cover] (a ZIM's landing page) */
  preview?: string
  /** 0–1 through it (listened), drawn as a bar along the cover */
  progress?: number
  /** The main action right on the cover: an audiobook's Play, a book's Open */
  quick?: { icon: IconName; label: string; run: () => void }
  where: Where
  size: number
  /** Downloading or uploading right now */
  busy?: string
  onOpen: () => void
  actions: ShelfAction[]
  /** Current, Not started yet or Completed: the shelf groups by it when items have one */
  status?: BookStatus
  /** Order inside its group, newest first (last read or played, added, finished) */
  sortAt?: number
  /** Picks the status by hand, from the item's ⋮ menu */
  onStatus?: (s: BookStatus) => void
}

function useStored<T extends string>(key: string, allowed: readonly T[], fallback: T): [T, (v: T) => void] {
  const [v, setV] = useState<T>(() => {
    try {
      const s = localStorage.getItem(key) as T | null
      return s && allowed.includes(s) ? s : fallback
    } catch {
      return fallback
    }
  })
  return [
    v,
    (next: T) => {
      setV(next)
      try {
        localStorage.setItem(key, next)
      } catch {
        /* private mode */
      }
    },
  ]
}

/** What to show and how, remembered per tab ("ebooks", "zim", "audiobooks"). */
export function useShelfPrefs(tab: string, defaultView: ShelfView) {
  const [show, setShow] = useStored<Show>(`feeder.${tab}Show`, ['all', 'device', 'cloud'], 'all')
  const [view, setView] = useStored<ShelfView>(`feeder.${tab}View`, ['cards', 'list'], defaultView)
  // The Completed group starts closed, so finished books don't push the rest down
  const [done, setDone] = useStored<'open' | 'closed'>(`feeder.${tab}Done`, ['open', 'closed'], 'closed')
  return { show, setShow, view, setView, doneOpen: done === 'open', setDoneOpen: (o: boolean) => setDone(o ? 'open' : 'closed') }
}

const STATUS_ICON: Record<BookStatus, IconName> = { new: 'hourglass', current: 'book', done: 'check' }

/** Where an item is, in words and an icon. */
export function Availability({ where, size, busy }: { where: Where; size: number; busy?: string }) {
  if (busy)
    return (
      <span className="avail avail-cloud">
        <Icon name="download" size={14} />
        {busy}
      </span>
    )
  if (where === 'both')
    return (
      <span className="avail avail-device" title="On this device and in Dropbox">
        <Icon name="check" size={14} />
        Downloaded
      </span>
    )
  if (where === 'device')
    return (
      <span className="avail avail-only" title="On this device only: not backed up in Dropbox">
        <Icon name="check" size={14} />
        Not in Dropbox
      </span>
    )
  return (
    <span className="avail avail-cloud" title="In Dropbox: downloads when you open it">
      <Icon name="cloud" size={14} />
      In Dropbox · {formatBytes(size)}
    </span>
  )
}

export function ShelfControls(p: { show: Show; setShow: (s: Show) => void; view: ShelfView; setView: (v: ShelfView) => void; items: ShelfItem[]; hideView?: boolean }) {
  const cloud = p.items.filter((i) => i.where === 'cloud').length
  const counts = { all: p.items.length, device: p.items.length - cloud, cloud }
  return (
    <div className="shelf-controls">
      {counts.cloud ? (
        <span className="feeds-switch" role="radiogroup" aria-label="Show">
          {(
            [
              ['all', `All ${counts.all}`],
              ['device', `On this device ${counts.device}`],
              ['cloud', `In Dropbox ${counts.cloud}`],
            ] as const
          ).map(([k, label]) => (
            <button key={k} type="button" role="radio" aria-checked={p.show === k} className={p.show === k ? 'on' : ''} onClick={() => p.setShow(k)}>
              {label}
            </button>
          ))}
        </span>
      ) : (
        <span />
      )}
      {p.hideView ? null : <span className="feeds-switch" role="radiogroup" aria-label="View">
        <button type="button" role="radio" aria-checked={p.view === 'cards'} className={p.view === 'cards' ? 'on' : ''} onClick={() => p.setView('cards')}>
          <Icon name="gridView" size={16} />
          Cards
        </button>
        <button type="button" role="radio" aria-checked={p.view === 'list'} className={p.view === 'list' ? 'on' : ''} onClick={() => p.setView('list')}>
          <Icon name="viewAgenda" size={16} />
          List
        </button>
      </span>}
    </div>
  )
}

/** A picture, or [fallback] when it fails to load (a picture from the web while offline, say). */
export function PictureOr({ src, fallback, className, lazy }: { src: string; fallback: ReactNode; className?: string; lazy?: boolean }) {
  const [failed, setFailed] = useState<string | null>(null)
  if (failed === src) return <>{fallback}</>
  return <img className={className} src={src} alt="" loading={lazy ? 'lazy' : undefined} onError={() => setFailed(src)} />
}

/** A cover made from the title when there is no picture: the same colour for the same title. */
export function GeneratedCover({ title, icon, small }: { title: string; icon: IconName; small?: boolean }) {
  let h = 0
  for (const c of title) h = (h * 31 + c.charCodeAt(0)) >>> 0
  return (
    <span className={`gen-cover${small ? ' small' : ''}`} style={{ ['--gen-h' as string]: String(h % 360) }}>
      <Icon name={icon} size={small ? 20 : 28} />
      {small ? null : <span>{title}</span>}
    </span>
  )
}

/**
 * "On this device" and "In Dropbox" sections, filtered by [show], as cards or rows. [streams]:
 * what is in Dropbox plays from there right away (audiobooks), so it isn't dimmed.
 */
export function Shelf(p: { items: ShelfItem[]; show: Show; view: ShelfView; emptyDevice: string; streams?: boolean; doneOpen?: boolean; setDoneOpen?: (o: boolean) => void }) {
  const [menu, setMenu] = useState<string | null>(null)
  const onDevice = p.items.filter((i) => i.where !== 'cloud')
  const cloud = p.items.filter((i) => i.where === 'cloud')
  // The chips are hidden while nothing is only in Dropbox: a filter chosen before (say In Dropbox,
  // then its last book was deleted) must not keep hiding everything with no chip to undo it
  const show: Show = cloud.length ? p.show : 'all'

  const more = (i: ShelfItem) =>
    i.actions.length || i.onStatus ? (
      <span className="book-more" onClick={(e) => e.stopPropagation()}>
        <IconButton icon="moreVert" label={`More for ${i.title}`} onClick={() => setMenu(menu === i.key ? null : i.key)} />
        {menu === i.key ? (
          <span className="book-menu" role="menu" onMouseLeave={() => setMenu(null)} ref={keepInPage}>
            {i.onStatus ? (
              <span className="status-picker" role="radiogroup" aria-label="Status">
                {(['new', 'current', 'done'] as const).map((st) => (
                  <button
                    key={st}
                    type="button"
                    role="radio"
                    aria-checked={(i.status ?? 'new') === st}
                    className={(i.status ?? 'new') === st ? 'on' : ''}
                    onClick={() => {
                      setMenu(null)
                      i.onStatus?.(st)
                    }}
                  >
                    <Icon name={STATUS_ICON[st]} size={20} />
                    {STATUS_LABEL[st]}
                  </button>
                ))}
              </span>
            ) : null}
            {i.actions.map((a) => (
              <button
                key={a.label}
                type="button"
                role="menuitem"
                onClick={() => {
                  setMenu(null)
                  a.run()
                }}
              >
                {a.label}
              </button>
            ))}
          </span>
        ) : null}
      </span>
    ) : null

  /** A menu that would stick out past the page's left edge opens to the right instead */
  const keepInPage = (el: HTMLSpanElement | null) => {
    if (!el) return
    const page = el.closest('.manage-pane')?.getBoundingClientRect()
    if (page && el.getBoundingClientRect().left < page.left) {
      el.style.right = 'auto'
      el.style.left = '0'
    }
  }

  const quick = (i: ShelfItem, cls: string) =>
    i.quick ? (
      <span className={cls} onClick={(e) => e.stopPropagation()}>
        <IconButton icon={i.quick.icon} label={i.quick.label} onClick={i.quick.run} />
      </span>
    ) : null

  const progress = (i: ShelfItem) =>
    i.progress && i.progress > 0 && i.progress < 0.999 ? (
      <span className="shelf-progress" aria-hidden>
        <i style={{ width: `${i.progress * 100}%` }} />
      </span>
    ) : null

  /** The same square cover on every tab */
  const cover = (i: ShelfItem, small: boolean) => {
    const cls = small ? 'shelf-thumb' : 'shelf-cover'
    if (!i.cover && i.preview)
      return (
        <span className={`${cls} shelf-preview`}>
          <PagePreview src={i.preview} />
        </span>
      )
    if (!i.cover)
      return (
        <span className={cls}>
          <GeneratedCover title={i.title} icon={i.coverIcon} small={small} />
        </span>
      )
    return (
      <span className={`${cls} cover-${i.coverFit ?? 'fill'}`}>
        <PictureOr src={i.cover} lazy fallback={<GeneratedCover title={i.title} icon={i.coverIcon} small={small} />} />
      </span>
    )
  }

  const dim = (i: ShelfItem) => (i.where === 'cloud' && !p.streams ? ' in-cloud' : '')

  const card = (i: ShelfItem) => (
    <div key={i.key} className={`book-card${dim(i)}`}>
      <button type="button" className="book-open" onClick={i.onOpen} title={i.where === 'cloud' && !p.streams ? `Download and open ${i.title}` : `Open ${i.title}`}>
        <span className={`shelf-cover-wrap${progress(i) ? ' has-progress' : ''}`}>
          {cover(i, false)}
          <Availability where={i.where} size={i.size} busy={i.busy} />
          {progress(i)}
        </span>
        <span className="book-title">{i.title}</span>
        <span className="book-meta">
          {i.subtitle ? <span className="book-author">{i.subtitle}</span> : null}
          {i.detail ? <span className="book-where">{i.detail}</span> : null}
        </span>
      </button>
      {quick(i, 'shelf-quick')}
      {more(i)}
    </div>
  )

  const row = (i: ShelfItem) => (
    <div key={i.key} className={`lib-row shelf-row${dim(i)}`} role="button" tabIndex={0} onClick={i.onOpen} onKeyDown={(e) => e.key === 'Enter' && i.onOpen()}>
      {cover(i, true)}
      <span className="lib-text">
        <span className="lib-title">{i.title}</span>
        {i.subtitle ? <span className="lib-desc shelf-sub">{i.subtitle}</span> : null}
        <span className="shelf-row-meta">
          <Availability where={i.where} size={i.size} busy={i.busy} />
          {i.detail ? <span className="lib-desc">{i.detail}</span> : null}
        </span>
        {p.view === 'list' && progress(i)}
      </span>
      {quick(i, 'shelf-row-quick')}
      {more(i)}
    </div>
  )

  const group = (list: ShelfItem[]) => (p.view === 'cards' ? <div className="books-grid">{list.map(card)}</div> : <div className="lib-list">{list.map(row)}</div>)

  if (p.items.some((i) => i.status)) {
    // Grouped like the Voice app; where a book is stays the chips' filter and each cover's badge
    const shown = show === 'all' ? p.items : show === 'device' ? onDevice : cloud
    if (!shown.length && show === 'device') return <p className="muted shelf-none">{p.emptyDevice}</p>
    return (
      <>
        {STATUS_ORDER.map((st) => {
          const list = shown.filter((i) => (i.status ?? 'new') === st).sort((a, b) => (b.sortAt ?? 0) - (a.sortAt ?? 0))
          if (!list.length) return null
          const collapsible = st === 'done'
          const open = !collapsible || !!p.doneOpen
          const head = (
            <>
              {STATUS_LABEL[st]} <span className="status-count">{list.length}</span>
              {collapsible ? <Icon name={open ? 'expandLess' : 'expandMore'} size={20} /> : null}
            </>
          )
          return (
            <section key={st} className="status-group" aria-label={STATUS_LABEL[st]}>
              <h2 className="status-head">
                {collapsible ? (
                  <button type="button" aria-expanded={open} onClick={() => p.setDoneOpen?.(!open)}>
                    {head}
                  </button>
                ) : (
                  head
                )}
              </h2>
              {open ? group(list) : null}
            </section>
          )
        })}
      </>
    )
  }

  return (
    <>
      {show !== 'cloud' && onDevice.length ? (
        <>
          {cloud.length ? (
            <h2 className="lib-section shelf-section">
              <Icon name="check" size={18} />
              On this device <span className="muted">· {p.streams ? 'plays offline too' : 'ready to read, also offline'}</span>
            </h2>
          ) : null}
          {group(onDevice)}
        </>
      ) : null}
      {show === 'device' && !onDevice.length ? <p className="muted shelf-none">{p.emptyDevice}</p> : null}
      {show !== 'device' && cloud.length ? (
        <>
          <h2 className="lib-section shelf-section">
            <Icon name="cloud" size={18} />
            In Dropbox <span className="muted">· {p.streams ? 'plays from Dropbox, needs a connection' : 'not on this device yet, downloads when you open it'}</span>
          </h2>
          {group(cloud)}
        </>
      ) : null}
    </>
  )
}

/** Width in CSS pixels the page is laid out at before it's shrunk: a phone screen. */
const PREVIEW_WIDTH = 420

/**
 * A page drawn small and still, as a cover: laid out at phone width, scaled to fit, no scripts and
 * no clicks (the cover's own button opens the book).
 */
function PagePreview({ src }: { src: string }) {
  const box = useRef<HTMLSpanElement>(null)
  const [scale, setScale] = useState(0)
  useEffect(() => {
    const el = box.current
    if (!el) return
    const ro = new ResizeObserver(() => setScale(el.clientWidth / PREVIEW_WIDTH))
    ro.observe(el)
    return () => ro.disconnect()
  }, [])
  return (
    <span ref={box} className="page-preview" aria-hidden="true">
      {scale > 0 ? (
        <iframe
          src={src}
          title=""
          tabIndex={-1}
          loading="lazy"
          sandbox="allow-same-origin"
          style={{ width: PREVIEW_WIDTH, height: PREVIEW_WIDTH, transform: `scale(${scale})` }}
        />
      ) : null}
    </span>
  )
}

/** One way to add to a library: in the header's Add menu and as a button in the empty state. */
export interface ShelfAdd {
  icon: IconName
  label: string
  run: () => void
}

/** Something being added or fetched that isn't a shelf item yet (an import, a catalog download). */
export interface ShelfJob {
  key: string
  label: string
  /** What is happening; bytes so far (of total) when left out */
  detail?: string
  done: number
  /** 0 = unknown: the bar just moves */
  total: number
  /** Stop it, where it can be stopped */
  onCancel?: () => void
}

/**
 * The one listing page of Books' subtabs (Ebooks, Audiobooks, ZIM): the Books header (search,
 * Dropbox account, ⋮ with Add, Check Dropbox and Cards / List), the subtabs, jobs, then the filter
 * chips and the shelf, or the empty state. [children] go in the header (hidden file inputs);
 * [extra] goes under it (the ZIM catalog). [ and ] switch subtabs. Same as Android's Books screen.
 */
export function LibraryListing(p: {
  title: string
  bookTab: BookTab
  prefs: ReturnType<typeof useShelfPrefs>
  items: ShelfItem[]
  emptyIcon: IconName
  emptyText: ReactNode
  emptyDevice: string
  addLabel: string
  addActions: ShelfAdd[]
  addDisabled?: boolean
  addTitle?: string
  dropboxFolder: string
  signedIn: boolean
  toast: (m: string) => void
  onMenu: () => void
  onRefresh?: () => void
  refreshLabel?: string
  refreshing?: boolean
  /** The library is still being read */
  loading?: boolean
  jobs?: ShelfJob[]
  /** What is in Dropbox plays from there right away (audiobooks), so it isn't dimmed */
  streams?: boolean
  extra?: ReactNode
  footer?: ReactNode
  onDropFiles?: (files: File[]) => void
  children?: ReactNode
}) {
  const [menu, setMenu] = useState(false)
  const [query, setQuery] = useState('')
  const jobs = p.jobs ?? []
  const tabInfo = BOOK_TABS.find((b) => b.tab === p.bookTab)!
  useEffect(() => rememberBookTab(p.bookTab), [p.bookTab])
  useEffect(() => {
    if (!p.loading) rememberBookCount(p.bookTab, p.items.length)
  }, [p.bookTab, p.items.length, p.loading])
  useBookTabKeys(p.bookTab)
  const q = query.trim().toLowerCase()
  const shown = q ? p.items.filter((i) => i.title.toLowerCase().includes(q) || !!i.subtitle?.toLowerCase().includes(q)) : p.items
  return (
    <section
      className="manage-pane shelf-page"
      aria-label={p.title}
      onDragOver={p.onDropFiles ? (e) => e.preventDefault() : undefined}
      onDrop={
        p.onDropFiles
          ? (e) => {
              e.preventDefault()
              p.onDropFiles?.([...e.dataTransfer.files])
            }
          : undefined
      }
    >
      <div className="mf-page">
        <div className="mf-head">
          <span className="only-narrow">
            <IconButton icon="menu" label="Feeds" onClick={p.onMenu} />
          </span>
          <h1>Books</h1>
          <span className="shelf-actions">
            <label className="books-search">
              <Icon name="search" size={18} />
              <input type="search" value={query} placeholder={tabInfo.search} aria-label={tabInfo.search} onChange={(e) => setQuery(e.target.value)} onKeyDown={(e) => e.key === 'Escape' && setQuery('')} />
              {query ? <IconButton icon="close" label="Clear search" onClick={() => setQuery('')} /> : null}
            </label>
            <DropboxButton folder={p.dropboxFolder} toast={p.toast} />
            <span className="shelf-add-wrap">
              <IconButton icon="moreVert" label="More" onClick={() => setMenu((o) => !o)} />
              {menu ? (
                <span className="book-menu shelf-add-menu" role="menu" onMouseLeave={() => setMenu(false)}>
                  {p.addActions.map((a) => (
                    <button key={a.label} type="button" role="menuitem" onClick={() => (setMenu(false), a.run())} disabled={p.addDisabled} title={p.addTitle}>
                      <Icon name={a.icon} size={18} />
                      {a.label}
                    </button>
                  ))}
                  {p.signedIn && p.onRefresh ? (
                    <button type="button" role="menuitem" onClick={() => (setMenu(false), p.onRefresh?.())} disabled={p.refreshing}>
                      <Icon name="refresh" size={18} />
                      {p.refreshLabel ?? 'Check Dropbox for new files'}
                    </button>
                  ) : null}
                  <span className="book-menu-label">View</span>
                  {(
                    [
                      ['cards', 'gridView', 'Cards'],
                      ['list', 'viewAgenda', 'List'],
                    ] as const
                  ).map(([v, icon, label]) => (
                    <button key={v} type="button" role="menuitemradio" aria-checked={p.prefs.view === v} onClick={() => (setMenu(false), p.prefs.setView(v))}>
                      <Icon name={icon} size={18} />
                      {label}
                      {p.prefs.view === v ? <span className="book-menu-check"><Icon name="check" size={18} /></span> : null}
                    </button>
                  ))}
                </span>
              ) : null}
            </span>
          </span>
          {p.children}
        </div>
        <BooksTabs current={p.bookTab} />
        {p.extra}

        {jobs.length ? (
          <div className="lib-list shelf-jobs">
            {jobs.map((j) => (
              <div key={j.key} className="lib-row">
                <Icon name="download" size={22} />
                <span className="lib-text">
                  <span className="lib-title">{j.label}</span>
                  <span className="lib-desc">{j.detail ?? (j.done ? `${formatBytes(j.done)}${j.total ? ` of ${formatBytes(j.total)}` : ''}` : 'Starting…')}</span>
                  <progress max={j.total || 1} value={j.total ? j.done : undefined} />
                </span>
                {j.onCancel ? <IconButton icon="close" label="Stop" onClick={j.onCancel} /> : null}
              </div>
            ))}
          </div>
        ) : null}

        {p.loading && !p.items.length ? (
          <p className="muted">Loading…</p>
        ) : !p.items.length ? (
          jobs.length ? null : (
            <div className="shelf-empty">
              <Icon name={p.emptyIcon} size={40} />
              <p>{p.emptyText}</p>
              {p.addActions.length ? (
                <span className="shelf-empty-actions">
                  {p.addActions.map((a) => (
                    <button key={a.label} type="button" className="mf-btn" onClick={a.run} disabled={p.addDisabled}>
                      <Icon name={a.icon} size={18} />
                      {a.label}
                    </button>
                  ))}
                </span>
              ) : null}
            </div>
          )
        ) : (
          !shown.length ? (
            <div className="shelf-empty">
              <p>Nothing on this shelf matches “{query.trim()}”</p>
              <span className="shelf-empty-actions">
                <button type="button" className="mf-btn" onClick={() => window.dispatchEvent(new Event(OPEN_SEARCH_EVENT))}>
                  <Icon name="search" size={18} />
                  Search inside books and everything else
                </button>
              </span>
            </div>
          ) : (
            <>
              <ShelfControls {...p.prefs} items={shown} hideView />
              <Shelf items={shown} show={p.prefs.show} view={p.prefs.view} streams={p.streams} emptyDevice={p.emptyDevice} doneOpen={p.prefs.doneOpen} setDoneOpen={p.prefs.setDoneOpen} />
            </>
          )
        )}
        {p.footer}
      </div>
    </section>
  )
}

/** Asks the app to open the command palette (global search). */
export const OPEN_SEARCH_EVENT = 'feeder:open-search'

/** Ebooks · Audiobooks · ZIM under the Books header; links, so Back and new tabs work. */
export function BooksTabs({ current }: { current: BookTab }) {
  const counts = bookCounts()
  return (
    <nav className="books-tabs" aria-label="Books sections">
      {BOOK_TABS.map((b) => (
        <a key={b.tab} href={b.hash} className={b.tab === current ? 'on' : ''} aria-current={b.tab === current ? 'page' : undefined} title={`${b.label} (${tabKeys(b.tab)})`}>
          <Icon name={b.icon} size={18} />
          {b.label}
          {counts[b.tab] ? <span className="books-tab-count">{counts[b.tab]}</span> : null}
        </a>
      ))}
    </nav>
  )
}

/** [ and ] go to the previous / next Books subtab, when not typing. */
function useBookTabKeys(current: BookTab) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.metaKey || e.ctrlKey || e.altKey || (e.key !== '[' && e.key !== ']')) return
      const t = e.target as HTMLElement
      if (/INPUT|TEXTAREA|SELECT/.test(t.tagName) || t.isContentEditable || document.querySelector('[aria-modal="true"]')) return
      const i = BOOK_TABS.findIndex((b) => b.tab === current)
      const next = BOOK_TABS[(i + (e.key === ']' ? 1 : BOOK_TABS.length - 1)) % BOOK_TABS.length]
      e.preventDefault()
      window.location.hash = next.hash
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [current])
}

/** The page shown instead of a listing when this browser can't keep files at all. */
export function ListingUnavailable({ title, bookTab }: { title: string; bookTab: BookTab }) {
  useEffect(() => rememberBookTab(bookTab), [bookTab])
  useBookTabKeys(bookTab)
  return (
    <section className="manage-pane shelf-page" aria-label={title}>
      <div className="mf-page">
        <div className="mf-head">
          <h1>Books</h1>
        </div>
        <BooksTabs current={bookTab} />
        <p className="muted">This browser cannot keep files on the device. Use a current Chrome, Edge, Safari or Firefox.</p>
      </div>
    </section>
  )
}
