import { useEffect, useRef, useState } from 'react'
import { formatBytes } from '../lib/offline'
import { Icon, IconButton, type IconName } from './Icon'

/**
 * The shared shelf of the Ebooks, ZIM and Audiobooks tabs: what is on this device (ready, also
 * offline) and what is only in Dropbox, as cards or a list. The tabs look and behave the same.
 */

/** both = on this device and in Dropbox; device = only here; cloud = only in Dropbox */
export type Where = 'both' | 'device' | 'cloud'
export type Show = 'all' | 'device' | 'cloud'
export type ShelfView = 'cards' | 'list'

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
  coverIcon: IconName
  /** A page to show shrunk as the cover when there is no [cover] (a ZIM's landing page) */
  preview?: string
  /** 0–1 through it (listened), drawn as a bar along the cover */
  progress?: number
  /** One action right on the cover (an audiobook's Play) */
  quick?: { icon: IconName; label: string; run: () => void }
  where: Where
  size: number
  /** Downloading or uploading right now */
  busy?: string
  onOpen: () => void
  actions: ShelfAction[]
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
  return { show, setShow, view, setView }
}

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

export function ShelfControls(p: { show: Show; setShow: (s: Show) => void; view: ShelfView; setView: (v: ShelfView) => void; counts: { all: number; device: number; cloud: number } }) {
  return (
    <div className="shelf-controls">
      {p.counts.cloud ? (
        <span className="feeds-switch" role="radiogroup" aria-label="Show">
          {(
            [
              ['all', `All ${p.counts.all}`],
              ['device', `On this device ${p.counts.device}`],
              ['cloud', `In Dropbox ${p.counts.cloud}`],
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
      <span className="feeds-switch" role="radiogroup" aria-label="View">
        <button type="button" role="radio" aria-checked={p.view === 'cards'} className={p.view === 'cards' ? 'on' : ''} onClick={() => p.setView('cards')}>
          <Icon name="gridView" size={16} />
          Cards
        </button>
        <button type="button" role="radio" aria-checked={p.view === 'list'} className={p.view === 'list' ? 'on' : ''} onClick={() => p.setView('list')}>
          <Icon name="viewAgenda" size={16} />
          List
        </button>
      </span>
    </div>
  )
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
export function Shelf(p: { items: ShelfItem[]; show: Show; view: ShelfView; shape: 'book' | 'tile' | 'square'; emptyDevice: string; streams?: boolean }) {
  const [menu, setMenu] = useState<string | null>(null)
  const onDevice = p.items.filter((i) => i.where !== 'cloud')
  const cloud = p.items.filter((i) => i.where === 'cloud')

  const more = (i: ShelfItem) =>
    i.actions.length ? (
      <span className="book-more" onClick={(e) => e.stopPropagation()}>
        <IconButton icon="moreVert" label={`More for ${i.title}`} onClick={() => setMenu(menu === i.key ? null : i.key)} />
        {menu === i.key ? (
          <span className="book-menu" role="menu">
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

  const cover = (i: ShelfItem, small: boolean) =>
    p.shape === 'square' ? (
      i.cover ? (
        <img className={small ? 'shelf-thumb shelf-square' : 'shelf-square-cover'} src={i.cover} alt="" />
      ) : small ? (
        <span className="shelf-thumb shelf-square">
          <GeneratedCover title={i.title} icon={i.coverIcon} small />
        </span>
      ) : (
        <span className="shelf-square-cover">
          <GeneratedCover title={i.title} icon={i.coverIcon} />
        </span>
      )
    ) : p.shape === 'book' ? (
      i.cover ? (
        <img className={small ? 'shelf-thumb' : 'book-cover'} src={i.cover} alt="" />
      ) : (
        <span className={`${small ? 'shelf-thumb' : 'book-cover'} book-cover-blank`}>{small ? <Icon name={i.coverIcon} size={18} /> : <span>{i.title}</span>}</span>
      )
    ) : (
      <span className={`${small ? 'shelf-thumb shelf-tile' : 'shelf-tile-cover'}${!i.cover && i.preview ? ' shelf-preview' : ''}`}>
        {i.cover ? <img src={i.cover} alt="" /> : i.preview ? <PagePreview src={i.preview} /> : <Icon name={i.coverIcon} size={small ? 22 : 44} />}
      </span>
    )

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

  return (
    <>
      {p.show !== 'cloud' && onDevice.length ? (
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
      {p.show === 'device' && !onDevice.length ? <p className="muted">{p.emptyDevice}</p> : null}
      {p.show !== 'device' && cloud.length ? (
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
          style={{ width: PREVIEW_WIDTH, height: PREVIEW_WIDTH * 0.75, transform: `scale(${scale})` }}
        />
      ) : null}
    </span>
  )
}
