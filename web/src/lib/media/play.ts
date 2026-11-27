/**
 * Plays Library > Player's files with the shared player (audiobooks/player.ts): one file, or a
 * folder's files one after another (the queue is the files after it, in play order).
 */
import { db } from '../db'
import { audiobookPlayer } from '../audiobooks/player'

export async function playMedia(id: string, queue: string[] = [], opts: { play?: boolean; at?: { i: number; ms: number } } = {}): Promise<void> {
  const m = await db.media.get(id)
  if (!m) return
  await audiobookPlayer.open(id, { media: m, queue, play: opts.play, at: opts.at })
}

audiobookPlayer.openMediaById = (id, queue) => playMedia(id, queue)
