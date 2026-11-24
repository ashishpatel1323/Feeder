/**
 * One description of an item (article, podcast episode or YouTube video) for every place that shows
 * it: the article list, Saved, playlists, Downloads, the reader and Flip mode. Same type label,
 * same length, same progress and the same button words everywhere.
 */
import type { Item } from './db'
import { readingMinutes, wordsOf } from './listquery'
import { ITEM_KIND_LABELS, itemSeconds, kindOf, type ItemKind, type PlaylistContext } from './playlists'
import { formatClock, isWatched } from './videopos'

export const KIND_LABELS = ITEM_KIND_LABELS

export interface ItemFacts {
  kind: ItemKind
  kindLabel: string
  /** Short length for the thumbnail badge: "4 min", "24:35", or '' when not known. */
  badge: string
  /** Length in words for the meta line: "4 min read", "24 min", "19 min left". */
  lengthText: string
  /** How much of an episode or video was played: "42% played", "100% watched"; '' when not started. */
  percentText: string
  /** 0..1 when an episode or video was started and not finished, else null. */
  progress: number | null
  /** The main button: Read, Play, Resume, Watch. */
  primaryLabel: string
  /** Mark read / Mark played / Mark watched, or the undo words when already done. */
  readLabel: string
}

const minutes = (sec: number) => Math.max(1, Math.round(sec / 60))

export function itemFacts(item: Item, ctx: PlaylistContext): ItemFacts {
  const kind = kindOf(item, ctx)
  const kindLabel = KIND_LABELS[kind]
  if (kind === 'article') {
    const m = readingMinutes(wordsOf(item, ctx.feeds.get(item.feedUrl)))
    return { kind, kindLabel, badge: `${m} min`, lengthText: `${m} min read`, percentText: '', progress: null, primaryLabel: 'Read', readLabel: item.read ? 'Mark unread' : 'Mark read' }
  }
  const pos = ctx.positions.get(item.id)
  const vid = kind === 'youtube'
  const seconds = itemSeconds(item, ctx)
  const done = !!pos && isWatched(pos)
  const started = !!pos && pos.t > 0 && !done
  const progress = started && seconds ? Math.min(1, pos!.t / seconds) : null
  const badge = seconds ? formatClock(seconds) : ''
  let lengthText = seconds ? `${minutes(seconds)} min` : ''
  if (started && seconds) lengthText = `${minutes(seconds - pos!.t)} min left`
  const doneWord = vid ? 'watched' : 'played'
  // The same numbers as the Android app: 1 to 99 while started, 100 once in the last 30 seconds
  let percentText = ''
  if (done) percentText = `100% ${doneWord}`
  else if (started && seconds) percentText = `${Math.min(99, Math.max(1, Math.floor((pos!.t / seconds) * 100)))}% ${doneWord}`
  return {
    kind,
    kindLabel,
    badge,
    lengthText,
    percentText,
    progress,
    primaryLabel: vid ? (started ? 'Resume' : 'Watch') : started ? 'Resume' : 'Play',
    readLabel: item.read ? `Mark un${doneWord}` : `Mark ${doneWord}`,
  }
}
