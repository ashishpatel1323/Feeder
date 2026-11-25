/**
 * An audiobook's timeline: its files one after another, and its chapters across them. A file with
 * chapter marks (m4b) gives one chapter per mark; a file without gives one chapter, named by its
 * title tag or its file name. Same rules as the Android app (model/audiobooks/).
 */
import type { AudioPos, AudioTrack, Audiobook } from '../db'

export interface Chapter {
  /** File index in play order */
  i: number
  /** Start within that file, ms */
  startMs: number
  /** Start within the whole book, ms (0 when earlier files' lengths aren't known yet) */
  bookMs: number
  /** Length in ms, 0 when not known */
  lengthMs: number
  title: string
}

/** "02 - The Road_South.mp3" → "02 - The Road South" */
export const nameWithoutExt = (name: string) => name.replace(/\.[a-z0-9]{2,4}$/i, '').replace(/_/g, ' ').trim() || name

const trackMs = (t: AudioTrack) => (t.duration ? Math.round(t.duration * 1000) : 0)

/** The book's length in ms, 0 when any file's length is still unknown. */
export function bookLengthMs(b: Pick<Audiobook, 'tracks'>): number {
  let sum = 0
  for (const t of b.tracks) {
    if (!t.duration) return 0
    sum += trackMs(t)
  }
  return sum
}

/** Where file [i] starts in the book, ms. */
export function trackStartMs(b: Pick<Audiobook, 'tracks'>, i: number): number {
  let sum = 0
  for (let k = 0; k < i && k < b.tracks.length; k++) sum += trackMs(b.tracks[k])
  return sum
}

export function chaptersOf(b: Pick<Audiobook, 'tracks'>): Chapter[] {
  const out: Chapter[] = []
  let base = 0
  b.tracks.forEach((t, i) => {
    const len = trackMs(t)
    const marks = t.marks.length ? t.marks : [{ startMs: 0, title: t.title || nameWithoutExt(t.name) }]
    marks.forEach((m, k) => {
      const end = k + 1 < marks.length ? marks[k + 1].startMs : len
      out.push({ i, startMs: m.startMs, bookMs: base + m.startMs, lengthMs: Math.max(0, end - m.startMs), title: m.title })
    })
    base += len
  })
  return out
}

/** Index of the chapter playing at [ms] into file [i]. */
export function chapterAt(chapters: Chapter[], i: number, ms: number): number {
  let found = -1
  for (let k = 0; k < chapters.length; k++) {
    const c = chapters[k]
    if (c.i < i || (c.i === i && c.startMs <= ms + 250)) found = k
    else break
  }
  return Math.max(0, found)
}

/**
 * Where to carry on from a saved position: the file it names, found again by its content hash if
 * the folder changed, else by index. Null to start from the beginning.
 */
export function resumePoint(b: Pick<Audiobook, 'tracks'>, pos: Pick<AudioPos, 'i' | 'h' | 'ms' | 'f'> | null | undefined): { i: number; ms: number } | null {
  if (!pos || !b.tracks.length) return null
  let i = pos.h ? b.tracks.findIndex((t) => t.hash === pos.h) : -1
  if (i < 0) i = pos.i >= 0 && pos.i < b.tracks.length ? pos.i : -1
  if (i < 0) return null
  const len = trackMs(b.tracks[i])
  // Finished: start over
  if (pos.f >= 0.999 && i === b.tracks.length - 1) return null
  let ms = Math.max(0, pos.ms)
  if (len && ms > len - 2000) ms = Math.max(0, len - 2000)
  return { i, ms }
}

/** 0–1 through the book at [ms] into file [i]; 0 while lengths are unknown. */
export function fractionAt(b: Pick<Audiobook, 'tracks'>, i: number, ms: number): number {
  const total = bookLengthMs(b)
  if (!total) return 0
  return Math.max(0, Math.min(1, (trackStartMs(b, i) + ms) / total))
}

/** The file and offset at [bookMs] into the whole book. */
export function locate(b: Pick<Audiobook, 'tracks'>, bookMs: number): { i: number; ms: number } {
  let base = 0
  for (let i = 0; i < b.tracks.length; i++) {
    const len = trackMs(b.tracks[i])
    if (!len || bookMs < base + len || i === b.tracks.length - 1) return { i, ms: Math.max(0, bookMs - base) }
    base += len
  }
  return { i: 0, ms: 0 }
}

/** "1:02:03" or "4:05" */
export function clock(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000))
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = String(s % 60).padStart(2, '0')
  return h ? `${h}:${String(m).padStart(2, '0')}:${sec}` : `${m}:${sec}`
}

/** "5 h 12 min" or "42 min" */
export function lengthText(ms: number): string {
  const min = Math.round(ms / 60000)
  if (min < 60) return `${Math.max(1, min)} min`
  const h = Math.floor(min / 60)
  return min % 60 ? `${h} h ${min % 60} min` : `${h} h`
}
