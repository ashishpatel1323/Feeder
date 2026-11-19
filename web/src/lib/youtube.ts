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

export function youtubeEmbedUrl(id: string): string {
  return `https://www.youtube.com/embed/${id}?rel=0&playsinline=1`
}

export function youtubeWatchUrl(id: string): string {
  return `https://www.youtube.com/watch?v=${id}`
}
