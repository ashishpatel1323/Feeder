/** What an item is, ported from web/src/lib/playlists.ts + youtube.ts so smart playlists match the app. */

export type ItemKind = 'podcast' | 'youtube' | 'article'

const ID = /^[A-Za-z0-9_-]{11}$/

export function youtubeVideoId(url: string | null | undefined): string | null {
  if (!url) return null
  let u: URL
  try {
    u = new URL(url)
  } catch {
    return null
  }
  const host = u.hostname.toLowerCase().replace(/^(www\.|m\.|music\.)/, '')
  let id: string | null = null
  if (host === 'youtu.be') id = u.pathname.split('/')[1] ?? null
  else if (host === 'youtube.com' || host === 'youtube-nocookie.com') {
    if (u.pathname === '/watch') id = u.searchParams.get('v')
    else id = u.pathname.match(/^\/(?:shorts|live|embed|v)\/([^/?#]+)/)?.[1] ?? null
  }
  return id && ID.test(id) ? id : null
}

export function articleVideoId(item: { link: string | null; content_html?: string | null }): string | null {
  const own = youtubeVideoId(item.link)
  if (own || !item.content_html || !/youtu/i.test(item.content_html)) return own
  const m = item.content_html.match(/<(?:iframe|embed)[^>]+src=["']([^"']*(?:youtube(?:-nocookie)?\.com|youtu\.be)[^"']*)["']/i)
  return m ? youtubeVideoId(m[1].startsWith('//') ? `https:${m[1]}` : m[1].replace(/&amp;/g, '&')) : null
}

const AUDIO = /\.(mp3|m4a|m4b|aac|ogg|oga|opus|wav|flac)(\?|#|$)/i
const NOT_AUDIO = /\.(jpe?g|png|gif|webp|avif|svg|pdf)(\?|#|$)/i

export function isPlayableEnclosure(url: string | null | undefined): boolean {
  return !!url && (AUDIO.test(url) || !NOT_AUDIO.test(url))
}

export function itemKind(item: { link: string | null; content_html?: string | null; enclosure_url: string | null }): ItemKind {
  if (articleVideoId(item)) return 'youtube'
  if (isPlayableEnclosure(item.enclosure_url)) return 'podcast'
  return 'article'
}

/** Read-aloud time of an article, as the app counts it (14 characters a second). */
export const readAloudSeconds = (words: number) => Math.max(30, Math.round((words * 6) / 14))
