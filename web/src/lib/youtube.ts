/**
 * YouTube videos open in YouTube's own player inside the reader. The player is
 * youtube.com/embed (not youtube-nocookie) so the browser's YouTube sign-in applies:
 * Premium (no ads), watch history and Watch later all work as on youtube.com.
 */

const ID = /^[A-Za-z0-9_-]{11}$/

/** The video id of a YouTube watch, short, shorts, live or embed link, or null. */
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
    else {
      const m = u.pathname.match(/^\/(?:shorts|live|embed|v)\/([^/?#]+)/)
      id = m ? m[1] : null
    }
  }
  return id && ID.test(id) ? id : null
}

/**
 * enablejsapi lets the reader read the play position (IFrame Player API), and `start`
 * resumes where the video was left. `origin` is this page, as the API asks.
 */
/**
 * The article's video: its link when that is a YouTube page, else the first YouTube player
 * embedded in the feed's text (blogs and newsletters that embed a video).
 */
export function articleVideoId(item: { link: string | null; contentHtml?: string | null }): string | null {
  const own = youtubeVideoId(item.link)
  if (own || !item.contentHtml || !/youtu/i.test(item.contentHtml)) return own
  const m = item.contentHtml.match(/<(?:iframe|embed)[^>]+src=["']([^"']*(?:youtube(?:-nocookie)?\.com|youtu\.be)[^"']*)["']/i)
  return m ? youtubeVideoId(m[1].startsWith('//') ? `https:${m[1]}` : m[1].replace(/&amp;/g, '&')) : null
}

export function youtubeEmbedUrl(id: string, opts: { start?: number; origin?: string } = {}): string {
  const q = new URLSearchParams({ rel: '0', playsinline: '1', enablejsapi: '1' })
  if (opts.origin) q.set('origin', opts.origin)
  if (opts.start && opts.start > 0) q.set('start', String(Math.floor(opts.start)))
  return `https://www.youtube.com/embed/${id}?${q}`
}

export function youtubeWatchUrl(id: string): string {
  return `https://www.youtube.com/watch?v=${id}`
}

/**
 * Opens a page (a YouTube watch or live link) in IINA, the Mac video player. IINA plays it
 * with yt-dlp, so live streams work; its Online Media plugin settings decide subtitles.
 */
export function iinaUrl(url: string): string {
  return `iina://weblink?url=${encodeURIComponent(url)}`
}

/** IINA only exists on macOS. */
export function isMac(): boolean {
  return typeof navigator !== 'undefined' && /Mac/i.test(navigator.platform || navigator.userAgent)
}
