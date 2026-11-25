import { useLiveQuery } from 'dexie-react-hooks'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { db, type AudioPos, type Audiobook } from '../lib/db'
import { onDropboxChange } from '../lib/dropbox'
import { otherDeviceText } from '../lib/device'
import { formatBytes } from '../lib/offline'
import { ACCEPT_AUDIO, addLocal, downloadAudiobook, isAudioName, localSupported, onDevice, pickedFromFolder, refreshAudiobooks, removeFromDevice, uploadAudiobook, type Picked } from '../lib/audiobooks/library'
import { audiobookPlayer, useAudiobookPlayer, type BookPlayerState } from '../lib/audiobooks/player'
import { bookLengthMs, chaptersOf, clock, lengthText } from '../lib/audiobooks/timeline'
import { DropboxPanel, useDropbox } from './DropboxPanel'
import { Icon, IconButton } from './Icon'
import { formatSpeed } from './Player'
import { GeneratedCover, Shelf, ShelfControls, useShelfPrefs, type ShelfItem } from './Shelf'

const RATES = [0.8, 0.9, 1, 1.1, 1.2, 1.3, 1.5, 1.75, 2, 2.5, 3]
const SLEEP_MINUTES = [5, 10, 15, 30, 45, 60, 90]

/**
 * The Audiobooks tab: books on this device and in Dropbox › Apps › Feeder › Audiobooks (a file is
 * a book, a folder is a book of several files), and a book's player with its chapters, speed and
 * sleep timer.
 */
export function AudiobooksPage(p: { book: string | null; onOpen: (id: string | null) => void; onMenu: () => void; toast: (m: string) => void }) {
  useEffect(() => {
    audiobookPlayer.onMessage = p.toast
  }, [p.toast])
  return p.book ? <BookPlayer key={p.book} id={p.book} onBack={() => p.onOpen(null)} /> : <Library onOpen={(id) => p.onOpen(id)} onMenu={p.onMenu} toast={p.toast} />
}

/** "42% listened on Pixel 8, 2 h ago" / "Finished" / "Not started" */
function progressText(b: Audiobook, pos: AudioPos | undefined): string {
  if (!pos || (pos.ms < 1000 && pos.i === 0)) return 'Not started'
  if (pos.f >= 0.999) return 'Finished'
  const where = otherDeviceText(pos)
  const what = pos.f ? `${Math.max(1, Math.round(pos.f * 100))}% listened` : b.tracks.length > 1 ? `File ${pos.i + 1} of ${b.tracks.length}` : `${clock(pos.ms)} in`
  return where ? `${what} ${where}` : what
}

function Cover({ book, className }: { book: Pick<Audiobook, 'cover' | 'title'>; className: string }) {
  return book.cover ? (
    <img className={className} src={book.cover} alt="" />
  ) : (
    <span className={className}>
      <GeneratedCover title={book.title} icon="headphones" />
    </span>
  )
}

interface Job {
  label: string
  done: number
  total: number
  verb: string
}

function Library({ onOpen, onMenu, toast }: { onOpen: (id: string) => void; onMenu: () => void; toast: (m: string) => void }) {
  const books = useLiveQuery(() => db.audiobooks.toArray(), [])
  const positions = useLiveQuery(() => db.audioPos.toArray(), []) ?? []
  const [busy, setBusy] = useState(false)
  const [adding, setAdding] = useState<Job | null>(null)
  const [jobs, setJobs] = useState<Record<string, Job>>({})
  const [addMenu, setAddMenu] = useState(false)
  const fileInput = useRef<HTMLInputElement>(null)
  const folderInput = useRef<HTMLInputElement>(null)
  const prefs = useShelfPrefs('audiobooks', 'cards')
  const dbx = useDropbox()
  const ab = useAudiobookPlayer()

  const refresh = useCallback(() => {
    setBusy(true)
    refreshAudiobooks()
      .catch((e) => toast(e instanceof Error ? e.message : String(e)))
      .finally(() => setBusy(false))
  }, [toast])
  useEffect(() => {
    refresh()
    return onDropboxChange(refresh)
  }, [refresh])

  const posOf = useMemo(() => new Map(positions.map((x) => [x.id, x])), [positions])
  const sorted = useMemo(
    () => [...(books ?? [])].sort((a, b) => (b.lastOpenedAt || 0) - (a.lastOpenedAt || 0) || b.addedAt - a.addedAt || a.title.localeCompare(b.title)),
    [books],
  )

  /** Progress at most 4 times a second */
  const throttled = (set: (done: number, total: number) => void) => {
    let last = 0
    return (done: number, total: number) => {
      if (Date.now() - last < 250 && done < total) return
      last = Date.now()
      set(done, total)
    }
  }

  const add = async (picked: Picked[], folderName: string) => {
    if (!picked.length) return
    const audio = picked.filter((x) => isAudioName(x.file.name))
    let title = folderName || audio[0]?.file.name.replace(/\.[a-z0-9]+$/i, '') || 'Audiobook'
    const loose = !folderName && audio.length > 1 && !audio.every((x) => /\.m4b$/i.test(x.file.name))
    if (loose) {
      const name = window.prompt(`Name of the book these ${audio.length} files make`, commonPrefix(audio.map((x) => x.file.name)) || title)
      if (!name) return
      title = name
    }
    setAdding({ label: title, done: 0, total: 0, verb: 'Adding to this device' })
    try {
      const n = await addLocal(
        picked,
        title,
        throttled((done, total) => setAdding({ label: title, done, total, verb: 'Adding to this device' })),
      )
      toast(n === 1 ? `Added ${title} to this device` : `Added ${n} audiobooks to this device`)
    } catch (e) {
      toast(e instanceof Error ? e.message : String(e))
    } finally {
      setAdding(null)
    }
  }

  const job = async (b: Audiobook, verb: string, run: (p: (done: number, total: number) => void) => Promise<void>, done: string) => {
    if (jobs[b.id]) return
    const set = (j: Job | null) =>
      setJobs((all) => {
        const next = { ...all }
        if (j) next[b.id] = j
        else delete next[b.id]
        return next
      })
    set({ label: b.title, done: 0, total: b.size, verb })
    try {
      await run(throttled((d, total) => set({ label: b.title, done: d, total, verb })))
      toast(done)
    } catch (e) {
      toast(e instanceof Error ? e.message : String(e))
    } finally {
      set(null)
    }
  }

  const items: ShelfItem[] = sorted.map((b) => {
    const pos = posOf.get(b.id)
    const len = bookLengthMs(b)
    const here = onDevice(b)
    const playing = ab.bookId === b.id && ab.status === 'playing'
    const started = !!pos && pos.f > 0 && pos.f < 0.999
    const j = jobs[b.id]
    const actions: ShelfItem['actions'] = []
    if (!here && b.dropboxPath && localSupported())
      actions.push({ label: 'Download to this device', run: () => void job(b, 'Downloading', (p) => downloadAudiobook(b.id, p), `${b.title} is on this device`) })
    if (here && !b.dropboxPath && dbx.signedIn)
      actions.push({ label: 'Upload to Dropbox', run: () => void job(b, 'Uploading', (p) => uploadAudiobook(b.id, p), `${b.title} is in Dropbox`) })
    if (here || b.tracks.some((t) => t.local))
      actions.push({
        label: b.dropboxPath ? 'Remove from this device' : 'Delete',
        run: () => {
          if (!b.dropboxPath && !confirm(`Delete ${b.title} from this device? It is not in Dropbox. Where you were stays.`)) return
          void removeFromDevice(b.id).then(() => toast(b.dropboxPath ? `Removed ${b.title} from this device; it stays in Dropbox` : `Deleted ${b.title}`))
        },
      })
    return {
      key: b.id,
      title: b.title,
      subtitle: b.author,
      detail: [progressText(b, pos), len ? lengthText(len) : b.tracks.length > 1 ? `${b.tracks.length} files` : formatBytes(b.size)].join(' · '),
      cover: b.cover,
      coverIcon: 'headphones',
      where: here ? (b.dropboxPath ? 'both' : 'device') : 'cloud',
      size: b.size,
      progress: pos?.f,
      busy: j ? `${j.verb} ${j.total ? Math.floor((j.done / j.total) * 100) : 0}%` : undefined,
      quick: {
        icon: playing ? 'pause' : 'play',
        label: playing ? `Pause ${b.title}` : started ? `Resume ${b.title}` : `Play ${b.title}`,
        run: () => (playing ? audiobookPlayer.pause() : void audiobookPlayer.open(b.id)),
      },
      onOpen: () => onOpen(b.id),
      actions,
    }
  })

  return (
    <section
      className="manage-pane books-page ab-page"
      aria-label="Audiobooks"
      onDragOver={(e) => e.preventDefault()}
      onDrop={(e) => {
        e.preventDefault()
        void add([...e.dataTransfer.files].map((file) => ({ file, rel: file.name })), '')
      }}
    >
      <div className="mf-page">
        <div className="mf-head">
          <span className="only-narrow">
            <IconButton icon="menu" label="Feeds" onClick={onMenu} />
          </span>
          <h1>Audiobooks</h1>
          <span className="zim-actions">
            {dbx.signedIn ? <IconButton icon="refresh" label="Check Dropbox for new audiobooks" onClick={refresh} disabled={busy} /> : null}
            <span className="ab-menu-wrap">
              <button
                type="button"
                className="mf-btn primary"
                aria-haspopup="menu"
                aria-expanded={addMenu}
                onClick={() => setAddMenu((o) => !o)}
                disabled={!!adding || !localSupported()}
                title="Add audiobooks from this device. They stay here, nothing is uploaded."
              >
                <Icon name="add" size={18} />
                Add audiobook
              </button>
              {addMenu ? (
                <span className="book-menu ab-menu" role="menu" onMouseLeave={() => setAddMenu(false)}>
                  <button type="button" role="menuitem" onClick={() => (setAddMenu(false), fileInput.current?.click())}>
                    <Icon name="headphones" size={18} />
                    Files…
                  </button>
                  <button type="button" role="menuitem" onClick={() => (setAddMenu(false), folderInput.current?.click())}>
                    <Icon name="folder" size={18} />
                    A folder…
                  </button>
                </span>
              ) : null}
            </span>
          </span>
          <input
            ref={fileInput}
            type="file"
            accept={ACCEPT_AUDIO}
            multiple
            hidden
            onChange={(e) => {
              void add([...(e.target.files ?? [])].map((file) => ({ file, rel: file.name })), '')
              e.target.value = ''
            }}
          />
          <input
            ref={folderInput}
            type="file"
            hidden
            {...({ webkitdirectory: '' } as Record<string, string>)}
            onChange={(e) => {
              const { name, files } = pickedFromFolder([...(e.target.files ?? [])])
              void add(files, name)
              e.target.value = ''
            }}
          />
        </div>
        <DropboxPanel what="Audiobooks" toast={toast} />

        {adding ? (
          <div className="lib-list zim-jobs">
            <div className="lib-row">
              <Icon name="download" size={22} />
              <span className="lib-text">
                <span className="lib-title">{adding.label}</span>
                <span className="lib-desc">
                  {adding.verb}
                  {adding.total ? `: ${Math.floor((adding.done / adding.total) * 100)}%` : '…'}
                </span>
                <progress max={adding.total || 1} value={adding.done || undefined} />
              </span>
            </div>
          </div>
        ) : null}

        {books === undefined ? (
          <p className="muted">Loading…</p>
        ) : !sorted.length ? (
          <div className="zim-empty">
            <Icon name="headphones" size={40} />
            <p>
              No audiobooks yet. Add files or a folder from this device (they stay here), or put them in Dropbox › Apps › Feeder › Audiobooks: a file there is one book (M4B, M4A, MP3…),
              and a folder there is one book whose files play in name order.
            </p>
          </div>
        ) : (
          <>
            <ShelfControls {...prefs} counts={{ all: items.length, device: items.filter((i) => i.where !== 'cloud').length, cloud: items.filter((i) => i.where === 'cloud').length }} />
            <Shelf items={items} show={prefs.show} view={prefs.view} shape="square" streams emptyDevice="No audiobooks on this device yet. Add some, or download one from the In Dropbox list." />
          </>
        )}
      </div>
    </section>
  )
}

function commonPrefix(names: string[]): string {
  let p = names[0] ?? ''
  for (const n of names) while (p && !n.startsWith(p)) p = p.slice(0, -1)
  return p.replace(/[\s\-_.0-9]+$/, '').trim()
}

/** A small menu under a button (Speed, Sleep). */
function MenuButton({ label, icon, text, children, active }: { label: string; icon: 'speed' | 'bedtime'; text: string; children: (close: () => void) => React.ReactNode; active?: boolean }) {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLSpanElement>(null)
  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => !ref.current?.contains(e.target as Node) && setOpen(false)
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false)
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])
  return (
    <span className="ab-menu-wrap" ref={ref}>
      <button type="button" className={`mf-btn${active ? ' on' : ''}`} aria-label={label} aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen((o) => !o)}>
        <Icon name={icon} size={18} />
        {text}
      </button>
      {open ? (
        <span className="book-menu ab-menu" role="menu">
          {children(() => setOpen(false))}
        </span>
      ) : null}
    </span>
  )
}

function sleepText(s: BookPlayerState['sleep'], now: number): string {
  if (!s) return 'Sleep'
  if (s.endOfChapter) return 'End of chapter'
  return clock(Math.max(0, (s.endsAt ?? now) - Math.max(now, Date.now())))
}

/** Ticks once a second while [on], for countdowns. */
function useNow(on: boolean): number {
  const [now, setNow] = useState(Date.now())
  useEffect(() => {
    if (!on) return
    const t = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(t)
  }, [on])
  return now
}

function SpeedMenu({ rate }: { rate: number }) {
  return (
    <MenuButton label={`Speed ${formatSpeed(rate)}`} icon="speed" text={formatSpeed(rate)}>
      {(close) =>
        RATES.map((r) => (
          <button
            key={r}
            type="button"
            role="menuitemradio"
            aria-checked={Math.abs(r - rate) < 0.001}
            onClick={() => {
              void audiobookPlayer.setRate(r)
              close()
            }}
          >
            {Math.abs(r - rate) < 0.001 ? <Icon name="check" size={16} /> : <span className="ab-menu-pad" />}
            {formatSpeed(r)}
          </button>
        ))
      }
    </MenuButton>
  )
}

function SleepMenu({ sleep }: { sleep: BookPlayerState['sleep'] }) {
  const now = useNow(!!sleep?.endsAt)
  return (
    <MenuButton label="Sleep timer" icon="bedtime" text={sleepText(sleep, now)} active={!!sleep}>
      {(close) => (
        <>
          {sleep?.endsAt ? (
            <button type="button" role="menuitem" onClick={() => (audiobookPlayer.extendSleep(), close())}>
              <span className="ab-menu-pad" />
              Add 5 minutes
            </button>
          ) : null}
          {SLEEP_MINUTES.map((m) => (
            <button key={m} type="button" role="menuitem" onClick={() => (audiobookPlayer.setSleep(m), close())}>
              <span className="ab-menu-pad" />
              {m} minutes
            </button>
          ))}
          <button type="button" role="menuitemradio" aria-checked={!!sleep?.endOfChapter} onClick={() => (audiobookPlayer.setSleep('chapter'), close())}>
            {sleep?.endOfChapter ? <Icon name="check" size={16} /> : <span className="ab-menu-pad" />}
            End of chapter
          </button>
          {sleep ? (
            <button type="button" role="menuitem" onClick={() => (audiobookPlayer.cancelSleep(), close())}>
              <span className="ab-menu-pad" />
              Turn off
            </button>
          ) : null}
        </>
      )}
    </MenuButton>
  )
}

/** One book: cover, chapter, position, controls, Speed and Sleep, and the chapter list. */
function BookPlayer({ id, onBack }: { id: string; onBack: () => void }) {
  const book = useLiveQuery(() => db.audiobooks.get(id), [id])
  const pos = useLiveQuery(() => db.audioPos.get(id), [id])
  const s = useAudiobookPlayer()
  const mine = s.bookId === id
  const listRef = useRef<HTMLOListElement>(null)

  // Opening the page loads the book where it was left, without playing it yet
  useEffect(() => {
    if (audiobookPlayer.state.bookId !== id && audiobookPlayer.state.status !== 'playing') void audiobookPlayer.open(id, { play: false })
  }, [id])

  // Keep the chapter playing in view
  useEffect(() => {
    if (!mine) return
    listRef.current?.querySelector('.current')?.scrollIntoView({ block: 'nearest' })
  }, [mine, s.chapter])

  if (book === undefined) return <section className="manage-pane ab-player" aria-label="Audiobook" />
  if (!book)
    return (
      <section className="manage-pane ab-player" aria-label="Audiobook">
        <div className="mf-page">
          <IconButton icon="back" label="Audiobooks" onClick={onBack} />
          <p className="muted">This book is no longer here or in Dropbox.</p>
        </div>
      </section>
    )

  const chapters = mine ? s.chapters : chaptersOf(book)
  const chapter = mine ? chapters[s.chapter] : undefined
  const playing = mine && (s.status === 'playing' || s.status === 'loading')
  const chapterLen = chapter?.lengthMs ?? 0
  const inChapter = mine && chapter ? (s.i === chapter.i ? s.ms - chapter.startMs : 0) : 0
  const bookLen = mine ? s.bookLength : bookLengthMs(book)
  const bookAt = mine ? s.bookMs : 0
  const left = bookLen ? (bookLen - bookAt) / (mine ? s.rate : 1) : 0
  const startOrResume = pos && pos.f > 0 && pos.f < 0.999 ? 'Resume' : 'Play'

  return (
    <section className="manage-pane ab-player" aria-label={book.title}>
      <div className="ab-top">
        <IconButton icon="back" label="Audiobooks" onClick={onBack} />
        <span className="ab-top-title">{book.title}</span>
      </div>
      <div className="ab-body">
        <div className="ab-now">
          <Cover book={book} className="ab-art" />
          <h1 className="ab-title">{book.title}</h1>
          {book.author ? <p className="ab-author">{book.author}</p> : null}
          <p className="ab-chapter">{chapter?.title ?? (pos ? progressText(book, pos) : `${book.tracks.length > 1 ? `${book.tracks.length} files` : ''}`)}</p>

          <div className="ab-seek">
            <input
              type="range"
              min={0}
              max={Math.max(1, chapterLen)}
              step={1000}
              value={Math.min(inChapter, chapterLen)}
              disabled={!mine || !chapterLen}
              aria-label="Position in chapter"
              aria-valuetext={`${clock(inChapter)} of ${clock(chapterLen)}`}
              onChange={(e) => chapter && audiobookPlayer.seekToBookMs(chapter.bookMs + Number(e.target.value))}
              style={{ ['--fill' as string]: `${chapterLen ? (inChapter / chapterLen) * 100 : 0}%` }}
            />
            <div className="ab-times">
              <span>{clock(inChapter)}</span>
              <span>{chapterLen ? `−${clock(chapterLen - inChapter)}` : ''}</span>
            </div>
          </div>

          <div className="ab-controls">
            <IconButton icon="skipPrev" label="Previous chapter" onClick={() => audiobookPlayer.prevChapter()} disabled={!mine} />
            <IconButton icon="replay10" label="Back 10 seconds" onClick={() => audiobookPlayer.seekBy(-10)} disabled={!mine} />
            <button
              type="button"
              className={`ab-play${playing ? ' playing' : ''}`}
              aria-label={playing ? 'Pause' : startOrResume}
              onClick={() => (mine ? audiobookPlayer.toggle() : void audiobookPlayer.open(id))}
            >
              <Icon name={playing ? 'pause' : 'play'} size={36} />
            </button>
            <IconButton icon="forward10" label="Ahead 30 seconds" onClick={() => audiobookPlayer.seekBy(30)} disabled={!mine} />
            <IconButton icon="skipNext" label="Next chapter" onClick={() => audiobookPlayer.nextChapter()} disabled={!mine || s.chapter >= chapters.length - 1} />
          </div>

          <div className="ab-extras">
            <SpeedMenu rate={mine ? s.rate : 1} />
            <SleepMenu sleep={s.sleep} />
          </div>
          <p className="ab-book-time muted">
            {bookLen ? `${clock(bookAt)} of ${clock(bookLen)} · ${lengthText(left)} left${mine && s.rate !== 1 ? ` at ${formatSpeed(s.rate)}` : ''}` : ''}
            {mine && s.scan ? `${bookLen ? ' · ' : ''}Reading chapters, ${s.scan.done} of ${s.scan.total} files` : ''}
          </p>
          {mine && s.error ? <p className="ab-error">{s.error}</p> : null}
        </div>

        <div className="ab-chapters">
          <h2 className="lib-section">
            Chapters <span className="muted">· {chapters.length}</span>
          </h2>
          <ol ref={listRef}>
            {chapters.map((c, k) => (
              <li key={`${c.i}:${c.startMs}`} className={mine && k === s.chapter ? 'current' : ''}>
                <button
                  type="button"
                  onClick={() => {
                    if (mine) audiobookPlayer.jumpToChapter(k)
                    else void audiobookPlayer.open(id, { at: { i: c.i, ms: c.startMs } })
                  }}
                >
                  <span className="ab-ch-n">{k + 1}</span>
                  <span className="ab-ch-title">{c.title}</span>
                  <span className="ab-ch-len">{c.lengthMs ? clock(c.lengthMs) : ''}</span>
                </button>
              </li>
            ))}
          </ol>
        </div>
      </div>
    </section>
  )
}

/** The mini player at the bottom while an audiobook is open and its page isn't. */
export function AudiobookBar({ onOpen }: { onOpen: (id: string) => void }) {
  const s = useAudiobookPlayer()
  const book = audiobookPlayer.book
  if (!s.bookId || !book) return null
  const chapter = s.chapters[s.chapter]
  const playing = s.status === 'playing' || s.status === 'loading'
  const chapterLen = chapter?.lengthMs ?? 0
  const inChapter = chapter && s.i === chapter.i ? s.ms - chapter.startMs : 0
  const fraction = chapterLen ? inChapter / chapterLen : 0
  return (
    <div className="queue-player ab-bar" role="region" aria-label="Audiobook playing">
      <div className="qp-progress ab-bar-progress" aria-hidden>
        <i style={{ width: `${Math.min(100, fraction * 100)}%` }} />
      </div>
      <div className="qp-bar">
        <button className="qp-info" onClick={() => onOpen(book.id)} title="Open the player">
          {book.cover ? <img className="qp-thumb" src={book.cover} alt="" width={48} height={48} /> : <span className="qp-thumb gen" style={{ width: 48, height: 48 }}><Icon name="headphones" size={24} /></span>}
          <span className="qp-titles">
            <span className="qp-title">{chapter?.title ?? book.title}</span>
            <span className="qp-sub">
              {book.title}
              {chapterLen ? ` · −${clock(chapterLen - inChapter)}` : ''}
              {s.sleep ? ' · sleep timer on' : ''}
            </span>
          </span>
        </button>
        <div className="ab-bar-controls">
          <IconButton icon="replay10" label="Back 10 seconds" onClick={() => audiobookPlayer.seekBy(-10)} />
          <button type="button" className="ab-play small" aria-label={playing ? 'Pause' : 'Play'} onClick={() => audiobookPlayer.toggle()}>
            <Icon name={playing ? 'pause' : 'play'} size={26} />
          </button>
          <span className="ab-bar-wide">
            <IconButton icon="forward10" label="Ahead 30 seconds" onClick={() => audiobookPlayer.seekBy(30)} />
            <button type="button" className="mf-btn" onClick={() => void audiobookPlayer.setRate(RATES[(RATES.findIndex((r) => r >= s.rate - 0.001) + 1) % RATES.length])} title="Speed">
              {formatSpeed(s.rate)}
            </button>
          </span>
          <IconButton icon="close" label="Close the audiobook player" onClick={() => audiobookPlayer.close()} />
        </div>
      </div>
    </div>
  )
}
