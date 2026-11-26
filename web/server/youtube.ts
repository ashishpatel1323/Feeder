/**
 * A few calls to YouTube's own web API (InnerTube, the API youtube.com uses), without signing in,
 * for the YouTube tab: browsers can't call it themselves (CORS). Same calls and parsing as the
 * Android app (model/youtube/InnerTube.kt):
 *  - /api/youtube/next?v=<id>          channel, likes, description, comments token, related videos
 *  - /api/youtube/comments?token=<t>   one page of comments
 *  - /api/youtube/meta?ids=<id,id,…>   length, views, live and Shorts flag for list rows
 * Answers are trimmed JSON. Every call may fail; the page hides what is missing.
 */

type J = Record<string, unknown>

const CLIENT_VERSION = '2.20251120.01.00'
const USER_AGENT = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36'
const VIDEO_ID = /^[A-Za-z0-9_-]{11}$/
/** At most this many videos per meta call. */
export const META_BATCH = 20

export interface VideoMeta {
  videoId: string
  /** Seconds; 0 for live and upcoming streams or when not known. */
  duration: number
  views: number | null
  likes: number | null
  isLive: boolean
  isShort: boolean
}

export interface RelatedVideo {
  videoId: string
  title: string
  thumbnail: string | null
  /** "12:34", or "" (live, or not given). */
  durationText: string
  channel: string
  channelAvatar: string | null
  /** "43K views", as YouTube words it. */
  viewsText: string
  /** "3 weeks ago". */
  ageText: string
}

export interface Comment {
  id: string
  author: string
  avatar: string | null
  text: string
  published: string
  likes: string
  replies: string
}

export interface CommentPage {
  comments: Comment[]
  countText: string | null
  next: string | null
}

export interface WatchNext {
  title: string | null
  viewsText: string | null
  dateText: string | null
  description: string | null
  likesText: string | null
  channelName: string | null
  channelId: string | null
  channelAvatar: string | null
  subscribersText: string | null
  commentsToken: string | null
  related: RelatedVideo[]
}

// ---------------------------------------------------------------- JSON helpers

const isObj = (v: unknown): v is J => !!v && typeof v === 'object' && !Array.isArray(v)
const obj = (o: unknown, key: string): J | null => (isObj(o) && isObj(o[key]) ? (o[key] as J) : null)
const arr = (o: unknown, key: string): unknown[] => (isObj(o) && Array.isArray(o[key]) ? (o[key] as unknown[]) : [])
const str = (o: unknown, key: string): string | null => (isObj(o) && typeof o[key] === 'string' ? (o[key] as string) : null)
const objs = (a: unknown[]): J[] => a.filter(isObj)

function text(o: unknown): string | null {
  if (!isObj(o)) return null
  return str(o, 'simpleText') ?? str(o, 'content') ?? (objs(arr(o, 'runs')).map((r) => str(r, 'text') ?? '').join('') || null)
}

function findContinuation(e: unknown): string | null {
  if (Array.isArray(e)) {
    for (const x of e) {
      const t = findContinuation(x)
      if (t) return t
    }
    return null
  }
  if (!isObj(e)) return null
  const own = str(obj(e, 'continuationCommand'), 'token')
  if (own) return own
  for (const v of Object.values(e)) {
    const t = findContinuation(v)
    if (t) return t
  }
  return null
}

/** The first string value named [key] anywhere under [e], depth first. */
function findString(e: unknown, key: string): string | null {
  if (Array.isArray(e)) {
    for (const x of e) {
      const t = findString(x, key)
      if (t) return t
    }
    return null
  }
  if (!isObj(e)) return null
  const own = str(e, key) ?? str(obj(e, key), 'content')
  if (own) return own
  for (const v of Object.values(e)) {
    const t = findString(v, key)
    if (t) return t
  }
  return null
}

function findSources(e: unknown): unknown[] | null {
  if (Array.isArray(e)) {
    for (const x of e) {
      const t = findSources(x)
      if (t) return t
    }
    return null
  }
  if (!isObj(e)) return null
  if (Array.isArray(e.sources)) return e.sources
  for (const v of Object.values(e)) {
    const t = findSources(v)
    if (t) return t
  }
  return null
}

const absolute = (url: string) => (url.startsWith('//') ? `https:${url}` : url)

function bestOf(sources: unknown[]): string | null {
  let best: J | null = null
  for (const s of objs(sources)) if (!best || Number(s.width ?? 0) > Number(best.width ?? 0)) best = s
  const url = best ? str(best, 'url') : null
  return url ? absolute(url) : null
}

const bestThumbnail = (o: unknown) => bestOf(arr(o, 'thumbnails'))

// ---------------------------------------------------------------- parsers

export function parsePlayer(videoId: string, o: J): Omit<VideoMeta, 'isShort'> | null {
  const details = obj(o, 'videoDetails')
  if (!details) return null
  const micro = obj(obj(o, 'microformat'), 'playerMicroformatRenderer')
  const num = (s: string | null) => (s != null && /^\d+$/.test(s) ? Number(s) : null)
  return {
    videoId,
    duration: num(str(details, 'lengthSeconds')) ?? 0,
    views: num(str(details, 'viewCount')),
    likes: num(str(micro, 'likeCount')),
    isLive: details.isLive === true || str(obj(o, 'playabilityStatus'), 'status') === 'LIVE_STREAM_OFFLINE',
  }
}

export function parseNext(o: J): WatchNext {
  const watch = obj(obj(o, 'contents'), 'twoColumnWatchNextResults')
  const results = objs(arr(obj(obj(watch, 'results'), 'results'), 'contents'))
  const primary = results.map((r) => obj(r, 'videoPrimaryInfoRenderer')).find(Boolean) ?? null
  const secondary = results.map((r) => obj(r, 'videoSecondaryInfoRenderer')).find(Boolean) ?? null
  const owner = obj(obj(secondary, 'owner'), 'videoOwnerRenderer')
  const commentsSection = results
    .map((r) => obj(r, 'itemSectionRenderer'))
    .find((s) => s && (str(s, 'sectionIdentifier') === 'comment-item-section' || str(s, 'targetId') === 'comments-section'))
  const related = objs(arr(obj(obj(watch, 'secondaryResults'), 'secondaryResults'), 'results'))
    // Sometimes wrapped in an item section
    .flatMap((item) => {
      const inner = obj(item, 'itemSectionRenderer')
      return inner ? objs(arr(inner, 'contents')) : [item]
    })
    .map((it) => parseLockup(it) ?? parseCompactVideo(it))
    .filter((r): r is RelatedVideo => !!r)
  const viewCount = obj(obj(primary, 'viewCount'), 'videoViewCountRenderer')
  return {
    title: text(obj(primary, 'title')),
    viewsText: text(obj(viewCount, 'shortViewCount')) ?? text(obj(viewCount, 'viewCount')),
    dateText: text(obj(primary, 'relativeDateText')) ?? text(obj(primary, 'dateText')),
    description: str(obj(secondary, 'attributedDescription'), 'content') ?? text(obj(secondary, 'description')),
    likesText: findString(o, 'likeCountIfIndifferent'),
    channelName: text(obj(owner, 'title')),
    channelId: str(obj(obj(owner, 'navigationEndpoint'), 'browseEndpoint'), 'browseId'),
    channelAvatar: bestThumbnail(obj(owner, 'thumbnail')),
    subscribersText: text(obj(owner, 'subscriberCountText')),
    commentsToken: commentsSection ? findContinuation(commentsSection) : null,
    related,
  }
}

export function parseComments(o: J): CommentPage {
  const items = objs(
    objs(arr(o, 'onResponseReceivedEndpoints')).flatMap((e) => {
      const c = obj(e, 'reloadContinuationItemsCommand') ?? obj(e, 'appendContinuationItemsAction')
      return arr(c, 'continuationItems')
    }),
  )
  const countText = text(obj(items.map((i) => obj(i, 'commentsHeaderRenderer')).find(Boolean), 'countText'))
  const keys = items.map((i) => str(obj(obj(obj(i, 'commentThreadRenderer'), 'commentViewModel'), 'commentViewModel'), 'commentKey')).filter((k): k is string => !!k)
  const payloads = new Map<string, J>()
  for (const m of objs(arr(obj(obj(o, 'frameworkUpdates'), 'entityBatchUpdate'), 'mutations'))) {
    const p = obj(obj(m, 'payload'), 'commentEntityPayload')
    if (p) payloads.set(str(p, 'key') ?? '', p)
  }
  const comments: Comment[] = []
  for (const key of keys) {
    const p = payloads.get(key)
    const props = obj(p, 'properties')
    if (!p || !props) continue
    const author = obj(p, 'author')
    const toolbar = obj(p, 'toolbar')
    comments.push({
      id: str(props, 'commentId') ?? key,
      author: str(author, 'displayName') ?? '',
      avatar: str(author, 'avatarThumbnailUrl'),
      text: str(obj(props, 'content'), 'content') ?? '',
      published: str(props, 'publishedTime') ?? '',
      likes: (str(toolbar, 'likeCountNotliked') ?? '').trim(),
      replies: (str(toolbar, 'replyCount') ?? '').trim(),
    })
  }
  const last = items[items.length - 1]
  const cont = obj(last, 'continuationItemRenderer')
  return { comments, countText, next: cont ? findContinuation(cont) : null }
}

function parseLockup(o: J): RelatedVideo | null {
  const lockup = obj(o, 'lockupViewModel')
  if (!lockup || str(lockup, 'contentType') !== 'LOCKUP_CONTENT_TYPE_VIDEO') return null
  const id = str(lockup, 'contentId')
  if (!id) return null
  const thumb = obj(obj(lockup, 'contentImage'), 'thumbnailViewModel')
  const meta = obj(obj(lockup, 'metadata'), 'lockupMetadataViewModel')
  const title = str(obj(meta, 'title'), 'content')
  if (!title) return null
  const rows = objs(arr(obj(obj(meta, 'metadata'), 'contentMetadataViewModel'), 'metadataRows')).map((row) =>
    objs(arr(row, 'metadataParts'))
      .map((p) => str(obj(p, 'text'), 'content'))
      .filter((s): s is string => s != null),
  )
  const second = rows[1] ?? []
  const views = second[0]
  const avatarSources = findSources(obj(meta, 'image'))
  return {
    videoId: id,
    title,
    thumbnail: bestOf(arr(obj(thumb, 'image'), 'sources')),
    durationText: (thumb && findString(thumb, 'text')) ?? '',
    channel: rows[0]?.[0] ?? '',
    channelAvatar: avatarSources ? bestOf(avatarSources) : null,
    viewsText: views ? (views.endsWith('views') || views.endsWith('watching') ? views : `${views} views`) : '',
    ageText: second[1] ?? '',
  }
}

function parseCompactVideo(o: J): RelatedVideo | null {
  const v = obj(o, 'compactVideoRenderer')
  const id = str(v, 'videoId')
  const title = text(obj(v, 'title'))
  if (!v || !id || !title) return null
  return {
    videoId: id,
    title,
    thumbnail: bestThumbnail(obj(v, 'thumbnail')),
    durationText: text(obj(v, 'lengthText')) ?? '',
    channel: text(obj(v, 'longBylineText')) ?? '',
    channelAvatar: bestThumbnail(obj(v, 'channelThumbnail')),
    viewsText: text(obj(v, 'shortViewCountText')) ?? text(obj(v, 'viewCountText')) ?? '',
    ageText: text(obj(v, 'publishedTimeText')) ?? '',
  }
}

// ---------------------------------------------------------------- calls

async function post(endpoint: 'player' | 'next', body: J, signal?: AbortSignal): Promise<J | null> {
  try {
    const res = await fetch(`https://www.youtube.com/youtubei/v1/${endpoint}?prettyPrint=false`, {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        'user-agent': USER_AGENT,
        'x-youtube-client-name': '1',
        'x-youtube-client-version': CLIENT_VERSION,
      },
      body: JSON.stringify({ context: { client: { clientName: 'WEB', clientVersion: CLIENT_VERSION, hl: 'en', gl: 'US' } }, ...body }),
      signal: signal ?? AbortSignal.timeout(15000),
    })
    if (!res.ok) return null
    const v: unknown = await res.json()
    return isObj(v) ? v : null
  } catch {
    return null
  }
}

/** A Short: youtube.com/shorts/<id> answers itself instead of redirecting to the watch page. */
async function isShort(videoId: string): Promise<boolean> {
  try {
    const res = await fetch(`https://www.youtube.com/shorts/${videoId}`, {
      method: 'HEAD',
      redirect: 'manual',
      headers: { 'user-agent': USER_AGENT },
      signal: AbortSignal.timeout(8000),
    })
    return res.status === 200
  } catch {
    return false
  }
}

function reply(status: number, body: unknown, maxAge: number): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      'content-type': 'application/json; charset=utf-8',
      // Shared caches (Vercel's CDN) may keep good answers; nothing here is per user
      'cache-control': status === 200 && maxAge > 0 ? `public, max-age=${maxAge}, s-maxage=${maxAge}` : 'no-store',
    },
  })
}

/** Handles /api/youtube/* (sub is the part after "youtube/"). */
export async function handleYouTube(request: Request, sub: string): Promise<Response> {
  if (request.method !== 'GET') return reply(405, { error: 'GET only' }, 0)
  const q = new URL(request.url).searchParams
  if (sub === 'next') {
    const v = q.get('v') ?? ''
    if (!VIDEO_ID.test(v)) return reply(400, { error: 'Bad video id' }, 0)
    const o = await post('next', { videoId: v })
    return o ? reply(200, parseNext(o), 1800) : reply(502, { error: 'YouTube did not answer' }, 0)
  }
  if (sub === 'comments') {
    const token = q.get('token') ?? ''
    if (!token || token.length > 4000) return reply(400, { error: 'Bad token' }, 0)
    const o = await post('next', { continuation: token })
    return o ? reply(200, parseComments(o), 600) : reply(502, { error: 'YouTube did not answer' }, 0)
  }
  if (sub === 'meta') {
    const ids = [...new Set((q.get('ids') ?? '').split(',').filter((id) => VIDEO_ID.test(id)))].slice(0, META_BATCH)
    if (!ids.length) return reply(400, { error: 'No video ids' }, 0)
    const items = await Promise.all(
      ids.map(async (id): Promise<VideoMeta> => {
        const [player, short] = await Promise.all([post('player', { videoId: id }), isShort(id)])
        const m = player ? parsePlayer(id, player) : null
        return { videoId: id, duration: m?.duration ?? 0, views: m?.views ?? null, likes: m?.likes ?? null, isLive: m?.isLive ?? false, isShort: short }
      }),
    )
    return reply(200, { items }, 3600)
  }
  return reply(404, { error: 'Not found' }, 0)
}
