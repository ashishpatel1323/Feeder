import { useLiveQuery } from 'dexie-react-hooks'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { db, type AudioPos, type MediaFile } from '../lib/db'
import { onDropboxChange } from '../lib/dropbox'
import { otherDeviceText } from '../lib/device'
import { formatBytes } from '../lib/offline'
import {
  ACCEPT_MEDIA,
  addLocalMedia,
  deleteMediaFromDropbox,
  downloadMedia,
  filesUnder,
  folderView,
  localSupported,
  pickedMediaFolder,
  playsHere,
  refreshMedia,
  removeMediaFromDevice,
  subtitlesUrl,
  titleOf,
  uploadMedia,
} from '../lib/media/library'
import { playMedia } from '../lib/media/play'
import { audiobookPlayer, useAudiobookPlayer } from '../lib/audiobooks/player'
import { clock, lengthText } from '../lib/audiobooks/timeline'
import { confirmDelete } from '../lib/confirmDelete'
import { confirmDropboxDelete, DropboxButton, needsDropbox, useDropbox } from './DropboxPanel'
import { Icon, IconButton } from './Icon'
import { MoreMenu } from './MoreMenu'
import { BooksTabs, GeneratedCover, Shelf, ShelfControls, useShelfPrefs, type ShelfAction, type ShelfItem, type Where } from './Shelf'
import { SleepMenu, SpeedMenu } from './AudiobooksPage'
import { rememberBookCount, rememberBookTab } from './Tabs'

/**
 * Books > Player: any audio or video file on this device or in Dropbox › Apps › Feeder › Media,
 * with Dropbox's folders as folders, and one file's page (the video, or the sound's controls, then
 * what plays next in its folder). The Android app's Player screens (ui/compose/media) are the same.
 *
 * [page]: '' = the top, 'f:<folder>' = inside a folder, 'p:<id>' = one file's page.
 */
export function PlayerPage(p: { page: string; onPage: (page: string, replace?: boolean) => void; onBack: () => void; onMenu: () => void; toast: (m: string) => void }) {
  useEffect(() => {
    audiobookPlayer.onMessage = p.toast
  }, [p.toast])
  if (p.page.startsWith('p:')) return <MediaWatch key={p.page} id={p.page.slice(2)} onPage={p.onPage} onBack={p.onBack} toast={p.toast} />
  return <MediaListing folder={p.page.startsWith('f:') ? p.page.slice(2) : ''} onPage={p.onPage} onMenu={p.onMenu} toast={p.toast} />
}

interface Job {
  label: string
  verb: string
  done: number
  total: number
}

const where = (m: MediaFile): Where => (m.local ? (m.dropboxPath ? 'both' : 'device') : 'cloud')
const verbOf = (m: Pick<MediaFile, 'kind'>) => (m.kind === 'video' ? 'watched' : 'played')

/** "42% watched on Pixel 8, 2 h ago" / "Finished" / "" */
export function progressText(m: MediaFile, pos: AudioPos | undefined): string {
  if (!pos || pos.ms < 1000) return ''
  if (pos.f >= 0.99) return m.kind === 'video' ? 'Watched' : 'Played'
  const what = pos.f ? `${Math.max(1, Math.round(pos.f * 100))}% ${verbOf(m)}` : `${clock(pos.ms)} in`
  const elsewhere = otherDeviceText(pos)
  return elsewhere ? `${what} ${elsewhere}` : what
}

/** The ⋮ actions every file has: get it here, put it in Dropbox, free the space, delete. */
function useFileActions(toast: (m: string) => void) {
  const [jobs, setJobs] = useState<Record<string, Job>>({})
  const dbx = useDropbox()
  const job = useCallback(
    async (m: MediaFile, verb: string, run: (p: (done: number, total: number) => void) => Promise<void>, done: string) => {
      const set = (j: Job | null) =>
        setJobs((all) => {
          const next = { ...all }
          if (j) next[m.id] = j
          else delete next[m.id]
          return next
        })
      set({ label: titleOf(m.name), verb, done: 0, total: m.size })
      let last = 0
      try {
        await run((d, total) => {
          if (Date.now() - last < 250 && d < total) return
          last = Date.now()
          set({ label: titleOf(m.name), verb, done: d, total })
        })
        toast(done)
      } catch (e) {
        toast(`${verb} ${titleOf(m.name)} stopped (${e instanceof Error ? e.message : String(e)})`)
      } finally {
        set(null)
      }
    },
    [toast],
  )
  const actionsFor = (m: MediaFile): ShelfAction[] => {
    const out: ShelfAction[] = []
    const title = titleOf(m.name)
    if (jobs[m.id]) return out
    if (!m.local && m.dropboxPath && localSupported()) out.push({ label: 'Download to this device', run: () => void job(m, 'Downloading', (p) => downloadMedia(m.id, p), `${title} is on this device`) })
    if (m.local && !m.dropboxPath)
      out.push({ label: 'Upload to Dropbox', run: () => needsDropbox(dbx.signedIn, toast, () => void job(m, 'Uploading', (p) => uploadMedia(m.id, p), `${title} is in Dropbox`)) })
    if (m.local)
      out.push({
        label: m.dropboxPath ? 'Remove from this device' : 'Delete',
        run: async () => {
          const ok = await confirmDelete(
            m.dropboxPath
              ? { title: 'Remove from this device?', itemName: title, scope: 'this-device', confirmLabel: 'Remove', extra: 'It stays in Dropbox.' }
              : { title: m.kind === 'video' ? 'Delete video?' : 'Delete file?', itemName: title, scope: 'this-device', extra: "It isn't in Dropbox, so this is the only copy." },
          )
          if (!ok) return
          if (!m.dropboxPath && audiobookPlayer.state.bookId === m.id) audiobookPlayer.close()
          void removeMediaFromDevice(m.id).then(() => toast(m.dropboxPath ? `Removed ${title} from this device; it stays in Dropbox` : `Deleted ${title}`))
        },
      })
    if (m.dropboxPath)
      out.push({
        label: 'Delete from Dropbox',
        run: async () => {
          if (!(await confirmDropboxDelete(title, m.local))) return
          if (!m.local && audiobookPlayer.state.bookId === m.id) audiobookPlayer.close()
          void deleteMediaFromDropbox(m.id)
            .then(() => toast(`Deleted ${title} from Dropbox`))
            .catch((e) => toast(e instanceof Error ? e.message : String(e)))
        },
      })
    return out
  }
  return { jobs, actionsFor, dbx }
}

function Breadcrumbs({ folder, onPage }: { folder: string; onPage: (page: string) => void }) {
  if (!folder) return null
  const parts = folder.split('/')
  return (
    <nav className="mp-crumbs" aria-label="Folders">
      <button type="button" onClick={() => onPage('')}>
        Player
      </button>
      {parts.map((name, k) => {
        const path = parts.slice(0, k + 1).join('/')
        const last = k === parts.length - 1
        return (
          <span key={path} className="mp-crumb">
            <Icon name="next" size={16} />
            {last ? (
              <span aria-current="page">{name}</span>
            ) : (
              <button type="button" onClick={() => onPage(`f:${path}`)}>
                {name}
              </button>
            )}
          </span>
        )
      })}
    </nav>
  )
}

function MediaListing({ folder, onPage, onMenu, toast }: { folder: string; onPage: (page: string, replace?: boolean) => void; onMenu: () => void; toast: (m: string) => void }) {
  const all = useLiveQuery(() => db.media.toArray(), [])
  const positions = useLiveQuery(() => db.audioPos.toArray(), []) ?? []
  const posOf = useMemo(() => new Map(positions.map((x) => [x.id, x])), [positions])
  const [busy, setBusy] = useState(false)
  const [adding, setAdding] = useState<Job | null>(null)
  const [query, setQuery] = useState('')
  const [menu, setMenu] = useState(false)
  const fileInput = useRef<HTMLInputElement>(null)
  const folderInput = useRef<HTMLInputElement>(null)
  const prefs = useShelfPrefs('player', 'list')
  const s = useAudiobookPlayer()
  const { jobs, actionsFor, dbx } = useFileActions(toast)

  const refresh = useCallback(() => {
    setBusy(true)
    refreshMedia()
      .catch((e) => toast(e instanceof Error ? e.message : String(e)))
      .finally(() => setBusy(false))
  }, [toast])
  useEffect(() => {
    refresh()
    return onDropboxChange(refresh)
  }, [refresh])

  const items = all ?? []
  useEffect(() => rememberBookTab('player'), [])
  useEffect(() => {
    if (all) rememberBookCount('player', all.length)
  }, [all])
  const view = useMemo(() => folderView(items, folder), [items, folder])
  const q = query.trim().toLowerCase()
  // A search looks through every folder
  const files = q ? items.filter((m) => m.name.toLowerCase().includes(q) || m.folder.toLowerCase().includes(q)).sort((a, b) => a.name.localeCompare(b.name)) : view.files
  const folders = q ? view.folders.filter((f) => f.name.toLowerCase().includes(q)) : view.folders
  const playable = (list: MediaFile[]) => list.filter((m) => playsHere(m.name))

  const play = (m: MediaFile, list: MediaFile[]) => {
    const order = playable(list)
    const k = order.findIndex((x) => x.id === m.id)
    void playMedia(m.id, k >= 0 ? order.slice(k + 1).map((x) => x.id) : [])
  }
  const playAll = (shuffle: boolean) => {
    let order = playable(filesUnder(items, folder))
    if (shuffle) order = [...order].sort(() => Math.random() - 0.5)
    if (!order.length) return toast('Nothing here plays in this browser')
    void playMedia(order[0].id, order.slice(1).map((x) => x.id))
    if (order[0].kind === 'video') onPage(`p:${order[0].id}`)
  }

  const add = async (picked: { file: File; rel: string }[]) => {
    if (!picked.length) return
    const label = picked.length === 1 ? picked[0].file.name : `${picked.length} files`
    setAdding({ label, verb: 'Adding to this device', done: 0, total: 0 })
    let last = 0
    try {
      const n = await addLocalMedia(picked, (done, total) => {
        if (Date.now() - last < 250 && done < total) return
        last = Date.now()
        setAdding({ label, verb: 'Adding to this device', done, total })
      })
      toast(n === 1 ? 'Added 1 file to this device' : `Added ${n} files to this device`)
    } catch (e) {
      toast(e instanceof Error ? e.message : String(e))
    } finally {
      setAdding(null)
    }
  }

  const shelfItems: ShelfItem[] = files.map((m) => {
    const pos = posOf.get(m.id)
    const playing = s.bookId === m.id && (s.status === 'playing' || s.status === 'loading')
    const j = jobs[m.id]
    const can = playsHere(m.name)
    const len = m.duration ? lengthText(m.duration * 1000) : ''
    return {
      key: m.id,
      title: titleOf(m.name),
      subtitle: q && m.folder ? m.folder.replace(/\//g, ' › ') : undefined,
      detail: [can ? progressText(m, pos) : 'Plays on Android only', len, m.local ? formatBytes(m.size) : ''].filter(Boolean).join(' · '),
      cover: null,
      coverIcon: m.kind === 'video' ? 'movie' : 'musicNote',
      where: where(m),
      size: m.size,
      progress: pos?.f,
      busy: j ? `${j.verb} ${j.total ? Math.floor((j.done / j.total) * 100) : 0}%` : undefined,
      quick: can
        ? {
            icon: playing ? 'pause' : 'play',
            label: playing ? `Pause ${titleOf(m.name)}` : `Play ${titleOf(m.name)}`,
            run: () => (playing ? audiobookPlayer.pause() : m.kind === 'video' ? (play(m, files), onPage(`p:${m.id}`)) : play(m, files)),
          }
        : undefined,
      onOpen: () => onPage(`p:${m.id}`),
      actions: actionsFor(m),
    }
  })

  const jobRows = [...(adding ? [{ key: 'adding', ...adding }] : []), ...Object.entries(jobs).map(([key, j]) => ({ key, ...j }))]
  const title = folder ? folder.split('/').pop()! : 'Player'
  const total = folder ? filesUnder(items, folder).length : items.length

  return (
    <section
      className="manage-pane shelf-page mp-page"
      aria-label={folder ? `Player: ${title}` : 'Player'}
      onDragOver={(e) => e.preventDefault()}
      onDrop={(e) => {
        e.preventDefault()
        void add([...e.dataTransfer.files].map((file) => ({ file, rel: folder ? `${folder}/${file.name}` : file.name })))
      }}
    >
      <div className="mf-page">
        <div className="mf-head">
          <span className="only-narrow">
            <IconButton icon="menu" label="Feeds" onClick={onMenu} />
          </span>
          <h1>Books</h1>
          <span className="shelf-actions">
            <label className="books-search">
              <Icon name="search" size={18} />
              <input type="search" value={query} placeholder="Search files" aria-label="Search files" onChange={(e) => setQuery(e.target.value)} onKeyDown={(e) => e.key === 'Escape' && setQuery('')} />
              {query ? <IconButton icon="close" label="Clear search" onClick={() => setQuery('')} /> : null}
            </label>
            <DropboxButton folder="Media" toast={toast} />
            <span className="shelf-add-wrap">
              <IconButton icon="moreVert" label="More" onClick={() => setMenu((o) => !o)} />
              {menu ? (
                <span className="book-menu shelf-add-menu" role="menu" onMouseLeave={() => setMenu(false)}>
                  <button type="button" role="menuitem" disabled={!!adding || !localSupported()} onClick={() => (setMenu(false), fileInput.current?.click())}>
                    <Icon name="add" size={18} />
                    Add files…
                  </button>
                  <button type="button" role="menuitem" disabled={!!adding || !localSupported()} onClick={() => (setMenu(false), folderInput.current?.click())}>
                    <Icon name="folder" size={18} />
                    Add a folder…
                  </button>
                  {dbx.signedIn ? (
                    <button type="button" role="menuitem" disabled={busy} onClick={() => (setMenu(false), refresh())}>
                      <Icon name="refresh" size={18} />
                      Check Dropbox for new files
                    </button>
                  ) : null}
                </span>
              ) : null}
            </span>
          </span>
          <input
            ref={fileInput}
            type="file"
            accept={ACCEPT_MEDIA}
            multiple
            hidden
            onChange={(e) => {
              void add([...(e.target.files ?? [])].map((file) => ({ file, rel: folder ? `${folder}/${file.name}` : file.name })))
              e.target.value = ''
            }}
          />
          <input
            ref={folderInput}
            type="file"
            hidden
            {...({ webkitdirectory: '' } as Record<string, string>)}
            onChange={(e) => {
              void add(pickedMediaFolder([...(e.target.files ?? [])]).map((x) => ({ ...x, rel: folder ? `${folder}/${x.rel}` : x.rel })))
              e.target.value = ''
            }}
          />
        </div>
        <BooksTabs current="player" />
        <Breadcrumbs folder={folder} onPage={onPage} />

        {jobRows.length ? (
          <div className="lib-list shelf-jobs">
            {jobRows.map((j) => (
              <div key={j.key} className="lib-row">
                <Icon name={j.verb === 'Uploading' ? 'upload' : 'download'} size={22} />
                <span className="lib-text">
                  <span className="lib-title">{j.label}</span>
                  <span className="lib-desc">
                    {j.verb}
                    {j.total ? `: ${Math.floor((j.done / j.total) * 100)}%` : '…'}
                  </span>
                  <progress max={j.total || 1} value={j.total ? j.done : undefined} />
                </span>
              </div>
            ))}
          </div>
        ) : null}

        {all === undefined ? (
          <p className="muted">Loading…</p>
        ) : !items.length ? (
          adding ? null : (
            <div className="shelf-empty">
              <Icon name="movie" size={40} />
              <p>
                Nothing to play yet. Add audio or video files or a folder from this device (they stay here), or put them in Dropbox › Apps › Feeder › Media. Folders there show up as folders
                here, and their files play one after another.
              </p>
              <span className="shelf-empty-actions">
                <button type="button" className="mf-btn" onClick={() => fileInput.current?.click()} disabled={!localSupported()}>
                  <Icon name="add" size={18} />
                  Add files…
                </button>
                <button type="button" className="mf-btn" onClick={() => folderInput.current?.click()} disabled={!localSupported()}>
                  <Icon name="folder" size={18} />
                  Add a folder…
                </button>
              </span>
            </div>
          )
        ) : (
          <>
            {!q && total ? (
              <div className="mp-playall">
                <button type="button" className="mf-btn primary" onClick={() => playAll(false)}>
                  <Icon name="play" size={18} />
                  Play all
                </button>
                <IconButton icon="shuffle" label="Shuffle" onClick={() => playAll(true)} />
                <span className="muted mp-count">
                  {total} {total === 1 ? 'file' : 'files'}
                </span>
              </div>
            ) : null}
            {folders.length ? (
              <div className="lib-list mp-folders">
                {folders.map((f) => (
                  <button key={f.path} type="button" className="lib-row" onClick={() => onPage(`f:${f.path}`)}>
                    <span className="shelf-thumb mp-folder-icon">
                      <Icon name="folder" size={24} />
                    </span>
                    <span className="lib-text">
                      <span className="lib-title">{f.name}</span>
                      <span className="lib-desc">
                        {f.count} {f.count === 1 ? 'file' : 'files'} · {formatBytes(f.size)}
                      </span>
                    </span>
                    <Icon name="next" size={20} />
                  </button>
                ))}
              </div>
            ) : null}
            {shelfItems.length ? (
              <>
                <ShelfControls {...prefs} items={shelfItems} hideView />
                <Shelf items={shelfItems} show={prefs.show} view="list" streams emptyDevice="Nothing in this folder is on this device yet. Download one from the In Dropbox list." />
              </>
            ) : q && !folders.length ? (
              <p className="muted shelf-none">Nothing matches “{query.trim()}”</p>
            ) : null}
          </>
        )}
      </div>
    </section>
  )
}

/** One file: the video (or the sound's controls), Speed and Sleep, then what plays next in its folder. */
function MediaWatch({ id, onPage, onBack, toast }: { id: string; onPage: (page: string, replace?: boolean) => void; onBack: () => void; toast: (m: string) => void }) {
  const m = useLiveQuery(() => db.media.get(id), [id])
  const all = useLiveQuery(() => db.media.toArray(), []) ?? []
  const pos = useLiveQuery(() => db.audioPos.get(id), [id])
  const s = useAudiobookPlayer()
  const mine = s.bookId === id
  const box = useRef<HTMLDivElement>(null)
  const { actionsFor } = useFileActions(toast)

  // The files after this one in its folder, in play order
  const siblings = useMemo(() => (m ? filesUnder(all, m.folder).filter((x) => x.folder === m.folder && playsHere(x.name)) : []), [all, m])
  const next = useMemo(() => {
    const k = siblings.findIndex((x) => x.id === id)
    return k >= 0 ? siblings.slice(k + 1) : []
  }, [siblings, id])

  // Opening the page loads the file where it was left, without playing it yet (unless nothing else plays)
  const opened = useRef(false)
  useEffect(() => {
    if (!m || opened.current) return
    opened.current = true
    if (audiobookPlayer.state.bookId === id || !playsHere(m.name)) return
    // Something else is playing: leave it, the page's Play starts this one
    if (audiobookPlayer.state.status === 'playing' || audiobookPlayer.state.status === 'loading') return
    void playMedia(
      id,
      next.map((x) => x.id),
      { play: m.kind === 'video' },
    )
  }, [m, id, next])

  // The video shows here while this page is open
  const video = m?.kind === 'video' || (mine && s.video)
  useEffect(() => {
    if (!mine || !video || !box.current) return
    audiobookPlayer.attachVideo(box.current)
    return () => audiobookPlayer.attachVideo(null)
  }, [mine, video])

  // Subtitles next to it
  useEffect(() => {
    if (!mine || !m?.subs) return
    let gone = false
    void subtitlesUrl(m).then((url) => !gone && url && audiobookPlayer.setSubtitles(url))
    return () => {
      gone = true
    }
  }, [mine, m])

  // The next file started from here: follow it
  useEffect(() => {
    if (s.source === 'media' && s.bookId && s.bookId !== id && opened.current && next.some((x) => x.id === s.bookId)) onPage(`p:${s.bookId}`, true)
  }, [s.bookId, s.source, id, next, onPage])

  if (m === undefined) return <section className="manage-pane mp-watch" aria-label="Player" />
  if (!m)
    return (
      <section className="manage-pane mp-watch" aria-label="Player">
        <div className="mf-page">
          <IconButton icon="back" label="Player" onClick={onBack} />
          <p className="muted">This file is no longer here or in Dropbox.</p>
        </div>
      </section>
    )

  const title = titleOf(m.name)
  const playing = mine && (s.status === 'playing' || s.status === 'loading')
  const len = mine ? s.bookLength : (m.duration ?? 0) * 1000
  const at = mine ? s.ms : 0
  const can = playsHere(m.name)
  const actions = actionsFor(m)
  const meta = [m.folder ? m.folder.replace(/\//g, ' › ') : '', len ? lengthText(len) : '', progressText(m, pos), m.local ? (m.dropboxPath ? 'On this device and in Dropbox' : 'Only on this device') : 'In Dropbox']
    .filter(Boolean)
    .join(' · ')

  return (
    <section className={`manage-pane mp-watch${video ? ' is-video' : ''}`} aria-label={title}>
      <div className="ab-top">
        <IconButton icon="back" label="Back" onClick={onBack} />
        <span className="ab-top-title">{title}</span>
        <span className="ab-top-gap" />
        {actions.length ? <MoreMenu label={`More for ${title}`} entries={actions.map((a) => ({ icon: a.label.startsWith('Download') ? 'download' : a.label.startsWith('Upload') ? 'upload' : 'delete', label: a.label, onClick: a.run, danger: a.label.startsWith('Delete') }))} /> : null}
      </div>
      <div className="mp-body">
        <div className="mp-main">
          {video ? (
            <div className="mp-stage" ref={box}>
              {!mine ? (
                <button type="button" className="mp-stage-play" aria-label={can ? `Play ${title}` : 'This browser cannot play it'} disabled={!can} onClick={() => void playMedia(id, next.map((x) => x.id))}>
                  <Icon name={can ? 'playCircle' : 'movie'} size={64} />
                </button>
              ) : null}
            </div>
          ) : (
            <span className="ab-art mp-art">
              <GeneratedCover title={title} icon="musicNote" />
            </span>
          )}
          <h1 className="mp-title">{title}</h1>
          <p className="mp-meta muted">{meta}</p>
          {!can ? <p className="ab-error">This browser can't play .{m.name.split('.').pop()} files. It plays in the Android app.</p> : null}

          {!video ? (
            <>
              <div className="ab-seek">
                <input
                  type="range"
                  min={0}
                  max={Math.max(1, len)}
                  step={1000}
                  value={Math.min(at, len)}
                  disabled={!mine || !len}
                  aria-label="Position"
                  aria-valuetext={`${clock(at)} of ${clock(len)}`}
                  onChange={(e) => audiobookPlayer.seekToBookMs(Number(e.target.value))}
                  style={{ ['--fill' as string]: `${len ? (at / len) * 100 : 0}%` }}
                />
                <div className="ab-times">
                  <span>{clock(at)}</span>
                  <span>{len ? `−${clock(len - at)}` : ''}</span>
                </div>
              </div>
              <div className="ab-controls">
                <IconButton icon="replay10" label="Back 10 seconds" onClick={() => audiobookPlayer.seekBy(-10)} disabled={!mine} />
                <button type="button" className={`ab-play${playing ? ' playing' : ''}`} disabled={!can} aria-label={playing ? 'Pause' : pos && pos.f > 0 && pos.f < 0.99 ? 'Resume' : 'Play'} onClick={() => (mine ? audiobookPlayer.toggle() : void playMedia(id, next.map((x) => x.id)))}>
                  <Icon name={playing ? 'pause' : 'play'} size={36} />
                </button>
                <IconButton icon="forward10" label="Ahead 30 seconds" onClick={() => audiobookPlayer.seekBy(30)} disabled={!mine} />
              </div>
            </>
          ) : null}
          <div className="ab-extras">
            <SpeedMenu rate={mine ? s.rate : 1} />
            <SleepMenu sleep={s.sleep} />
            {next.length ? (
              <button type="button" className="mf-btn" onClick={() => void playMedia(next[0].id, next.slice(1).map((x) => x.id)).then(() => onPage(`p:${next[0].id}`, true))}>
                <Icon name="skipNext" size={18} />
                Next
              </button>
            ) : null}
          </div>
          {mine && s.error ? <p className="ab-error">{s.error}</p> : null}
        </div>

        {next.length ? (
          <div className="mp-next">
            <h2 className="lib-section">Up next in {m.folder ? m.folder.split('/').pop() : 'Player'}</h2>
            <div className="lib-list">
              {next.slice(0, 50).map((x) => (
                <button
                  key={x.id}
                  type="button"
                  className="lib-row"
                  onClick={() => {
                    const k = next.indexOf(x)
                    void playMedia(x.id, next.slice(k + 1).map((y) => y.id)).then(() => onPage(`p:${x.id}`, true))
                  }}
                >
                  <span className="shelf-thumb">
                    <GeneratedCover title={titleOf(x.name)} icon={x.kind === 'video' ? 'movie' : 'musicNote'} small />
                  </span>
                  <span className="lib-text">
                    <span className="lib-title">{titleOf(x.name)}</span>
                    <span className="lib-desc">{[x.duration ? lengthText(x.duration * 1000) : '', x.local ? '' : 'In Dropbox'].filter(Boolean).join(' · ')}</span>
                  </span>
                </button>
              ))}
            </div>
          </div>
        ) : null}
      </div>
    </section>
  )
}
