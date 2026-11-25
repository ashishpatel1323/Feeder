import { useState } from 'react'
import { formatBytes } from '../lib/offline'
import { Icon, IconButton, type IconName } from './Icon'

/**
 * The shared shelf of the Ebooks and ZIM tabs: what is on this device (ready, also offline) and
 * what is only in Dropbox, as cards or a list. Both tabs look and behave the same.
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

/** What to show and how, remembered per tab ("ebooks", "zim"). */
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
      <span className="avail avail-only" title="Not in Dropbox: only this device has it">
        <Icon name="check" size={14} />
        Only on this device
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

/** "On this device" and "In Dropbox" sections, filtered by [show], as cards or rows. */
export function Shelf(p: { items: ShelfItem[]; show: Show; view: ShelfView; shape: 'book' | 'tile'; emptyDevice: string }) {
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

  const cover = (i: ShelfItem, small: boolean) =>
    p.shape === 'book' ? (
      i.cover ? (
        <img className={small ? 'shelf-thumb' : 'book-cover'} src={i.cover} alt="" />
      ) : (
        <span className={`${small ? 'shelf-thumb' : 'book-cover'} book-cover-blank`}>{small ? <Icon name={i.coverIcon} size={18} /> : <span>{i.title}</span>}</span>
      )
    ) : (
      <span className={small ? 'shelf-thumb shelf-tile' : 'shelf-tile-cover'}>
        {i.cover ? <img src={i.cover} alt="" /> : <Icon name={i.coverIcon} size={small ? 22 : 44} />}
      </span>
    )

  const card = (i: ShelfItem) => (
    <div key={i.key} className={`book-card${i.where === 'cloud' ? ' in-cloud' : ''}`}>
      <button type="button" className="book-open" onClick={i.onOpen} title={i.where === 'cloud' ? `Download and open ${i.title}` : `Open ${i.title}`}>
        <span className="shelf-cover-wrap">
          {cover(i, false)}
          <Availability where={i.where} size={i.size} busy={i.busy} />
        </span>
        <span className="book-title">{i.title}</span>
        <span className="book-meta">
          {i.subtitle ? <span className="book-author">{i.subtitle}</span> : null}
          {i.detail ? <span className="book-where">{i.detail}</span> : null}
        </span>
      </button>
      {more(i)}
    </div>
  )

  const row = (i: ShelfItem) => (
    <div key={i.key} className={`lib-row shelf-row${i.where === 'cloud' ? ' in-cloud' : ''}`} role="button" tabIndex={0} onClick={i.onOpen} onKeyDown={(e) => e.key === 'Enter' && i.onOpen()}>
      {cover(i, true)}
      <span className="lib-text">
        <span className="lib-title">{i.title}</span>
        {i.subtitle ? <span className="lib-desc shelf-sub">{i.subtitle}</span> : null}
        <span className="shelf-row-meta">
          <Availability where={i.where} size={i.size} busy={i.busy} />
          {i.detail ? <span className="lib-desc">{i.detail}</span> : null}
        </span>
      </span>
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
              On this device <span className="muted">· ready to read, also offline</span>
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
            In Dropbox <span className="muted">· not on this device yet, downloads when you open it</span>
          </h2>
          {group(cloud)}
        </>
      ) : null}
    </>
  )
}
