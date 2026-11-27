/**
 * Moves a YouTube video saved in this browser (Downloads, offline.ts) into Books > Player: the
 * file goes to Player › YouTube › <channel> on this device, with its subtitles and where it was
 * watched to, and leaves Downloads. With [upload] it then goes to Dropbox › Apps › Feeder › Media
 * › YouTube › <channel> too, so every device has it. The Android app does the same
 * (model/media/DownloadsToPlayer.kt).
 */
import { db, feedDisplayTitle, type Item } from '../db'
import type { Progress } from '../dropbox'
import { saveAudioPos } from '../audiobooks/library'
import { removeVideo, savedVideoBlob, savedSubtitleBlobs } from '../offline'
import { addLocalMediaIds, uploadMedia } from './library'

/** A file or folder name from a title: no slashes or characters Dropbox and file systems refuse. */
export function safeName(s: string, fallback: string): string {
  return (
    s
      .replace(/[\\/:*?"<>|\u0000-\u001f]+/g, ' ')
      .replace(/\s+/g, ' ')
      .trim()
      .replace(/\.+$/, '')
      .slice(0, 120) || fallback
  )
}

/** Where a moved download goes in Player: YouTube/<channel>/<title>.mp4 */
export function playerPathFor(title: string, channel: string, ext = 'mp4'): { folder: string; name: string } {
  return { folder: `YouTube/${safeName(channel, 'Unknown channel')}`, name: `${safeName(title, 'Video')}.${ext}` }
}

/** The subtitle language to keep: English first, else the first one. */
export function pickSubtitle(langs: string[]): string | null {
  return langs.find((l) => l === 'en') ?? langs.find((l) => l.startsWith('en')) ?? langs[0] ?? null
}

export async function moveDownloadToPlayer(item: Item, videoId: string, opts: { upload: boolean; onProgress?: Progress }): Promise<string> {
  const blob = await savedVideoBlob(videoId)
  if (!blob) throw new Error('The saved copy is gone. Save it again.')
  const feed = await db.feeds.get(item.feedUrl)
  const channel = item.author || (feed ? feedDisplayTitle(feed) : '')
  const { folder, name } = playerPathFor(item.title || videoId, channel)
  const picked = [{ file: new File([blob], name, { type: 'video/mp4' }), rel: `${folder}/${name}` }]
  const lang = pickSubtitle(item.offlineSubs ?? [])
  if (lang) {
    const sub = (await savedSubtitleBlobs(videoId, [lang]))[0]
    if (sub) picked.push({ file: new File([sub.blob], name.replace(/\.mp4$/, '.vtt'), { type: 'text/vtt' }), rel: `${folder}/${name.replace(/\.mp4$/, '.vtt')}` })
  }
  const [id] = await addLocalMediaIds(picked)
  // Where it was watched to carries over (the Player keeps places by file)
  const pos = await db.videoPos.get(item.id)
  if (pos && pos.t > 0 && !(await db.audioPos.get(id))) await saveAudioPos(id, 0, id, pos.t * 1000, pos.d ? Math.min(1, pos.t / pos.d) : 0)
  if (pos?.d) await db.media.update(id, { duration: pos.d })
  await removeVideo(item.id, videoId)
  if (opts.upload) await uploadMedia(id, opts.onProgress)
  return id
}
