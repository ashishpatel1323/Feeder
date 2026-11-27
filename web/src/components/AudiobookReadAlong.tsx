/**
 * The audiobook's transcript in the whole window (design B, same as Android): the lines under
 * chapter headings with Search and time stamps, as YouTube transcripts show them, and the controls
 * in a dock at the bottom. "Transcribe this book" starts Whisper working ahead of where it plays.
 */
import { useEffect, useMemo } from 'react'
import type { Audiobook } from '../lib/db'
import { audiobookPlayer, useAudiobookPlayer } from '../lib/audiobooks/player'
import { chaptersOf, clock, locate } from '../lib/audiobooks/timeline'
import { readyUntil, TRANSCRIBE_AHEAD_CHOICES, WHOLE_BOOK } from '../lib/audiobooks/transcriptRules'
import { kickAhead, setTranscriptViewing, setTranscriptWanted, useAbTranscript, type AbTranscriptState } from '../lib/audiobooks/transcripts'
import { MAC_ACTIVE_MS, seenAgo, useMacStatus } from '../lib/macBookAudio'
import { updateSettings, useSettings } from '../lib/settings'
import { WHISPER_MODEL_MB } from '../lib/transcripts'
import { transcriptSections } from '../lib/transcriptSections'
import { ChapterTranscript } from './ChapterTranscript'
import { Icon, IconButton } from './Icon'

export function AudiobookReadAlong({ book, onClose, extras }: { book: Audiobook; onClose: () => void; extras: React.ReactNode }) {
  const s = useAudiobookPlayer()
  const mine = s.bookId === book.id
  const t = useAbTranscript(book.id)
  useEffect(() => {
    setTranscriptViewing(book.id)
    return () => setTranscriptViewing(null)
  }, [book.id])
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])
  // Reading along: the screen stays on while it plays
  useEffect(() => {
    let lock: { release: () => Promise<void> } | null = null
    const nav = navigator as Navigator & { wakeLock?: { request: (t: 'screen') => Promise<{ release: () => Promise<void> }> } }
    nav.wakeLock?.request('screen').then((l) => (lock = l), () => undefined)
    return () => void lock?.release()
  }, [])

  const chapters = mine ? s.chapters : chaptersOf(book)
  const positionMs = mine ? s.bookMs : 0
  const sections = useMemo(
    () =>
      transcriptSections(
        t.cues,
        chapters.map((c) => ({
          startMs: c.bookMs,
          title: c.title,
          // Transcribed on the Mac (design B's small badge)
          ...(t.macRanges.some(([a, b]) => a < c.bookMs + c.lengthMs && b > c.bookMs) ? { badge: 'Mac' } : {}),
        })),
      ),
    [t.cues, t.macRanges, chapters],
  )
  const chapter = mine ? chapters[s.chapter] : undefined
  const playing = mine && (s.status === 'playing' || s.status === 'loading')
  const chapterLen = chapter?.lengthMs ?? 0
  const inChapter = mine && chapter ? (s.i === chapter.i ? s.ms - chapter.startMs : 0) : 0
  const seek = (ms: number) => {
    if (mine) audiobookPlayer.seekToBookMs(ms)
    else void audiobookPlayer.open(book.id, { at: locate(book, ms) })
  }

  return (
    <section className="manage-pane ab-readalong" aria-label={`${book.title} transcript`}>
      <div className="ab-top">
        <IconButton icon="close" label="Close transcript" onClick={onClose} />
        <div className="ab-ra-title">
          <span className="ab-top-title">{chapter?.title ?? book.title}</span>
          <span className="ab-ra-book">{book.title}</span>
        </div>
      </div>
      <div className="ab-ra-body">
        <ChapterTranscript
          cues={t.cues}
          sections={sections}
          positionMs={positionMs}
          onSeek={seek}
          resetKey={book.id}
          title={book.title}
          pendingText={t.wanted ? 'Transcribing this chapter…' : 'Not transcribed yet'}
          status={<AheadStatus t={t} positionMs={positionMs} bookId={book.id} />}
        />
      </div>
      <div className="ab-ra-dock">
        <div className="ab-ra-seek">
          <span>{clock(inChapter)}</span>
          <input
            type="range"
            min={0}
            max={Math.max(1, chapterLen)}
            step={1000}
            value={Math.min(inChapter, chapterLen)}
            disabled={!mine || !chapterLen}
            aria-label="Position in chapter"
            onChange={(e) => chapter && audiobookPlayer.seekToBookMs(chapter.bookMs + Number(e.target.value))}
            style={{ ['--fill' as string]: `${chapterLen ? (inChapter / chapterLen) * 100 : 0}%` }}
          />
          <span>{chapterLen ? `−${clock(chapterLen - inChapter)}` : ''}</span>
        </div>
        <div className="ab-ra-controls">
          <IconButton icon="skipPrev" label="Previous chapter" onClick={() => audiobookPlayer.prevChapter()} disabled={!mine} />
          <IconButton icon="replay10" label="Back 10 seconds" onClick={() => audiobookPlayer.seekBy(-10)} disabled={!mine} />
          <button type="button" className={`ab-play${playing ? ' playing' : ''}`} aria-label={playing ? 'Pause' : 'Play'} onClick={() => (mine ? audiobookPlayer.toggle() : void audiobookPlayer.open(book.id))}>
            <Icon name={playing ? 'pause' : 'play'} size={30} />
          </button>
          <IconButton icon="forward10" label="Ahead 30 seconds" onClick={() => audiobookPlayer.seekBy(30)} disabled={!mine} />
          <IconButton icon="skipNext" label="Next chapter" onClick={() => audiobookPlayer.nextChapter()} disabled={!mine || s.chapter >= chapters.length - 1} />
          <span className="ab-ra-extras">{extras}</span>
        </div>
      </div>
    </section>
  )
}

function AheadStatus({ t, positionMs, bookId }: { t: AbTranscriptState; positionMs: number; bookId: string }) {
  const mac = useMacStatus()
  const settings = useSettings()
  if (!t.loaded) return null
  const ready = clock(readyUntil(t.ranges, positionMs))
  if (!t.cues.length && !t.wanted) {
    return (
      <div className="ab-ra-intro">
        <Icon name="closedCaption" size={28} />
        <p>
          See the words as the book plays. Feeder transcribes a chapter or two ahead of where you are, on this computer or on your phone, and your devices
          share it through sync. Nothing leaves your devices.
        </p>
        <button type="button" className="mf-btn primary" onClick={() => void setTranscriptWanted(bookId, true)}>
          Transcribe this book
        </button>
        <p className="muted">The speech model (about {WHISPER_MODEL_MB} MB) downloads once. English only. Long files need the Feeder Mac app.</p>
      </div>
    )
  }
  if (t.modelProgress != null) return <p className="transcript-status">Downloading the speech model {Math.round(t.modelProgress * 100)}%</p>
  const text = t.error
    ? `Couldn't transcribe: ${t.error}`
    : !t.wanted
      ? `From your devices · ready to ${ready}`
      : t.workingAtMs != null
        ? `Ready to ${ready} · transcribing ahead`
        : `Ready to ${ready}`
  const macBook = mac?.transcripts?.books.find((b) => b.id === bookId)
  const macOn = !!mac && Date.now() - mac.seen < MAC_ACTIVE_MS
  return (
    <>
      {mac && macBook ? (
        <div className="ab-ra-mac">
          <Icon name="laptop" size={18} />
          <span>
            {macOn && mac.transcripts?.working?.book === bookId ? 'Your Mac is transcribing' : macOn ? 'Your Mac has transcribed' : "Your Mac is off"} ·{' '}
            {macBook.ready} of {macBook.total} chapters ready · last seen {seenAgo(mac.seen)}
          </span>
        </div>
      ) : null}
      <div className="ab-ra-status">
        {t.workingAtMs != null ? <span className="ab-ra-spinner" aria-hidden /> : null}
        <span className={t.error ? 'ab-error' : undefined}>{text}</span>
        <button type="button" className="mf-btn ghost" onClick={() => void setTranscriptWanted(bookId, !t.wanted)}>
          {t.wanted ? 'Stop' : 'Transcribe ahead'}
        </button>
      </div>
      {t.wanted ? (
        <div className="ab-ra-ahead">
          <span>Transcribe ahead</span>
          <div className="book-audio-seg" role="radiogroup" aria-label="Chapters to transcribe ahead">
            {TRANSCRIBE_AHEAD_CHOICES.map((n) => (
              <button
                key={n}
                type="button"
                role="radio"
                aria-checked={settings.transcribeAhead === n}
                className={[settings.transcribeAhead === n ? 'on' : '', n === WHOLE_BOOK ? 'wide' : ''].join(' ').trim()}
                onClick={() => {
                  void updateSettings({ transcribeAhead: n }).then(() => kickAhead())
                }}
              >
                {n === WHOLE_BOOK ? 'Whole book' : n}
              </button>
            ))}
          </div>
        </div>
      ) : null}
    </>
  )
}
