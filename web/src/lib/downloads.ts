/**
 * The Downloads page: saved videos in the user's own order (a playlist), and its playback
 * toggles. Kept in this browser only: each device has its own saved videos.
 */
import { useEffect, useState } from 'react'

const ORDER_KEY = 'feeder.downloadsOrder'
const PREFS_KEY = 'feeder.downloadsPrefs'
const DURATIONS_KEY = 'feeder.videoDurations'

export interface DownloadsPrefs {
  autoplay: boolean
  loop: boolean
  removeWatched: boolean
}

const DEFAULT_PREFS: DownloadsPrefs = { autoplay: true, loop: false, removeWatched: false }

function read<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key)
    if (!raw) return fallback
    const parsed: unknown = JSON.parse(raw)
    if (Array.isArray(fallback)) return (Array.isArray(parsed) ? parsed : fallback) as T
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? ({ ...fallback, ...parsed } as T) : fallback
  } catch {
    return fallback
  }
}

function write(key: string, value: unknown) {
  try {
    localStorage.setItem(key, JSON.stringify(value))
  } catch {
    // private window or full storage: the order just won't be remembered
  }
}

/**
 * [ids] in playlist order: the saved order without ids that are gone, then new ones,
 * oldest download first.
 */
export function orderDownloads(saved: string[], ids: string[], downloadedAt: (id: string) => number): string[] {
  const present = new Set(ids)
  const kept = [...new Set(saved)].filter((id) => present.has(id))
  const keptSet = new Set(kept)
  const added = [...new Set(ids)].filter((id) => !keptSet.has(id)).sort((a, b) => downloadedAt(a) - downloadedAt(b))
  return [...kept, ...added]
}

/** Moves [id] to where [targetId] is (drag and drop), or by [by] places (keys). */
export function moveInOrder(order: string[], id: string, to: { targetId: string } | { by: number }): string[] {
  const from = order.indexOf(id)
  if (from < 0) return order
  const target = 'targetId' in to ? order.indexOf(to.targetId) : Math.max(0, Math.min(order.length - 1, from + to.by))
  if (target < 0 || target === from) return order
  const next = [...order]
  next.splice(target, 0, next.splice(from, 1)[0])
  return next
}

export const savedOrder = (): string[] => read<string[]>(ORDER_KEY, [] as string[]).filter((x) => typeof x === 'string')
export const saveOrder = (ids: string[]) => write(ORDER_KEY, ids)

export function useDownloadsPrefs(): [DownloadsPrefs, (p: Partial<DownloadsPrefs>) => void] {
  const [prefs, setPrefs] = useState<DownloadsPrefs>(() => read(PREFS_KEY, DEFAULT_PREFS))
  useEffect(() => write(PREFS_KEY, prefs), [prefs])
  return [prefs, (p) => setPrefs((old) => ({ ...old, ...p }))]
}

/** Video lengths in seconds, by YouTube id, measured once from the saved copy. */
export const knownDurations = (): Record<string, number> => read<Record<string, number>>(DURATIONS_KEY, {})
export function rememberDuration(videoId: string, seconds: number) {
  write(DURATIONS_KEY, { ...knownDurations(), [videoId]: seconds })
}

/** 31:45, or 1:02:03 for an hour or more. */
export function formatDuration(seconds: number): string {
  const s = Math.max(0, Math.round(seconds))
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = String(s % 60).padStart(2, '0')
  return h ? `${h}:${String(m).padStart(2, '0')}:${sec}` : `${m}:${sec}`
}
