/**
 * Where each YouTube video was left, per article, so the reader's player resumes there.
 * Synced to other devices as the "video" record kind (sync2.ts), newest wins.
 */
import { db, type VideoPos } from './db'

/** Resume this many seconds before where the video was left. */
export const RESUME_BACK = 3
/** The last this many seconds count as watched: the next play starts over. */
export const WATCHED_TAIL = 30
/** Positions this early are not worth keeping. */
const MIN_SAVE = 5

/** True when the video was watched to (near) its end. */
export function isWatched(p: Pick<VideoPos, 't' | 'd'>): boolean {
  return p.d > 0 && p.t >= p.d - WATCHED_TAIL
}

/** Where the player should start, in whole seconds (0 = from the beginning). */
export function resumeAt(p: Pick<VideoPos, 't' | 'd'> | null | undefined): number {
  if (!p || isWatched(p) || p.t < MIN_SAVE) return 0
  return Math.max(0, Math.floor(p.t - RESUME_BACK))
}

/** Saves where the video is now. Skips tiny moves and the first few seconds of a fresh start. */
export async function saveVideoPos(id: string, t: number, d: number, now = Date.now()): Promise<void> {
  if (!Number.isFinite(t) || t < 0) return
  const dur = Number.isFinite(d) && d > 0 ? Math.round(d) : 0
  const time = Math.round(t)
  await db.transaction('rw', db.videoPos, async () => {
    const old = await db.videoPos.get(id)
    if (!old && time < MIN_SAVE) return
    if (old && old.t === time && (old.d === dur || !dur)) return
    await db.videoPos.put({ id, t: time, d: dur || old?.d || 0, ts: Math.max(now, (old?.ts ?? 0) + 1) })
  })
}

/** "4:12" or "1:02:05". */
export function formatClock(sec: number): string {
  const s = Math.max(0, Math.floor(sec))
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const ss = String(s % 60).padStart(2, '0')
  return h ? `${h}:${String(m).padStart(2, '0')}:${ss}` : `${m}:${ss}`
}

/** "4:12 / 14:19", "4:12" without a length, or "Watched". */
export function positionLabel(p: Pick<VideoPos, 't' | 'd'>): string {
  if (isWatched(p)) return 'Watched'
  return p.d > 0 ? `${formatClock(p.t)} / ${formatClock(p.d)}` : formatClock(p.t)
}
