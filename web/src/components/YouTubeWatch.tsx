/**
 * The video page in Flow's layout (and Android's VideoPage.kt): the player on top, title with
 * "…more", the channel row, a row of chips (likes, Save, Download, Share, Open in YouTube), the
 * comments card (a sheet with all of them) and related videos as big cards. While browsing it
 * collapses into a mini player above the bottom bar: the same element changes shape, so the video
 * keeps playing. Channel, likes, comments and related come from /api/youtube/next (server/youtube.ts);
 * each part hides when that fails.
 */
import { useEffect, useState } from 'react'
import { db, type Feed, type Item } from '../lib/db'
import { fetchComments, fetchNext, ageText, thumbUrl, channelFeedUrl, type Comment, type CommentPage, type RelatedVideo, type VideoRow, type WatchNext } from '../lib/youtubeTab'
import { youtubeChannelId } from '../lib/refresh'
import { compactNumber } from '../lib/discover'
import { youtubeWatchUrl } from '../lib/youtube'
import { saveVideo, useVideoJobs, useVideoSavingAvailable } from '../lib/offline'
import { YouTubeFrame } from './YouTubeFrame'
import { Icon, IconButton } from './Icon'
import { MoreMenu } from './MoreMenu'
import { ChannelAvatar, subscribe, unsubscribe } from './YouTubeTab'

export interface WatchTarget {
  videoId: string
  /** The article it belongs to, or null (a related video from outside the subscriptions) */
  itemId: string | null
}

export function YouTubeWatch(p: {
  target: WatchTarget
  mini: boolean
  item: Item | undefined
  feeds: Feed[]
  /** The list it was opened from, for previous / next */
  order: VideoRow[]
  /** Every subscription video, to open related videos that are in a feed as that article */
  rows: VideoRow[]
  onOpen: (t: WatchTarget) => void
  onCollapse: () => void
  onExpand: () => void
  onClose: () => void
  toast: (m: string) => void
}) {
  const { videoId, itemId } = p.target
  const item = p.item
  const [next, setNext] = useState<WatchNext | null>(null)
  const [comments, setComments] = useState<CommentPage | null>(null)
  const [sheet, setSheet] = useState(false)
  const [more, setMore] = useState(false)
  const [playing, setPlaying] = useState(true)

  // The embed reports its state to this page (enablejsapi): play/pause for the mini player's button
  useEffect(() => {
    const onMessage = (e: MessageEvent) => {
      if (!/^https:\/\/www\.youtube\.com$/.test(e.origin) || typeof e.data !== 'string') return
      try {
        const d = JSON.parse(e.data) as { event?: string; info?: unknown }
        const state = d.event === 'onStateChange' ? d.info : (d.info as { playerState?: number } | null)?.playerState
        if (typeof state === 'number' && state !== 3 && state !== -1) setPlaying(state === 1)
      } catch {
        /* not the player's message */
      }
    }
    window.addEventListener('message', onMessage)
    return () => window.removeEventListener('message', onMessage)
  }, [])
  const togglePlay = () => {
    const frame = document.querySelector<HTMLIFrameElement>('.ytw-player iframe')
    frame?.contentWindow?.postMessage(JSON.stringify({ event: 'command', func: playing ? 'pauseVideo' : 'playVideo', args: [] }), 'https://www.youtube.com')
    setPlaying(!playing)
  }

  useEffect(() => {
    let live = true
    setNext(null)
    setComments(null)
    setMore(false)
    setSheet(false)
    void fetchNext(videoId).then(async (n) => {
      if (!live) return
      setNext(n)
      const c = n?.commentsToken ? await fetchComments(n.commentsToken) : null
      if (live) setComments(c)
    })
    return () => {
      live = false
    }
  }, [videoId])

  // Opening a video marks its article read, as opening it anywhere else does
  useEffect(() => {
    if (!itemId) return
    void db.items.get(itemId).then((it) => {
      if (it && !it.read) void db.items.update(itemId, { read: 1, readTime: Date.now(), readSynced: 0 })
    })
  }, [itemId])

  const feed = item ? p.feeds.find((f) => f.url === item.feedUrl) : next?.channelId ? p.feeds.find((f) => youtubeChannelId(f.url) === next.channelId) : undefined
  const title = item?.title || next?.title || ''
  const channel = next?.channelName || (feed ? feed.customTitle || feed.title : '')
  const index = itemId ? p.order.findIndex((r) => r.item.id === itemId) : -1
  const prev = index > 0 ? p.order[index - 1] : null
  const after = index >= 0 ? (p.order[index + 1] ?? null) : null
  const openRelated = (r: RelatedVideo) => {
    const row = p.rows.find((x) => x.videoId === r.videoId)
    p.onOpen({ videoId: r.videoId, itemId: row?.item.id ?? null })
  }
  const share = async () => {
    const url = youtubeWatchUrl(videoId)
    try {
      if (navigator.share) return await navigator.share({ title, url })
    } catch {
      return
    }
    await navigator.clipboard?.writeText(url).catch(() => undefined)
    p.toast('Link copied')
  }

  const metaLine = [shortViews(next?.viewsText), next?.dateText || (item ? ageText(item.sortTime) : '')].filter(Boolean).join(' · ')
  return (
    <div className={`ytw ${p.mini ? 'mini' : 'full'}`} role={p.mini ? 'region' : 'dialog'} aria-label={p.mini ? 'Mini player' : title || 'Video'}>
      <div className="ytw-scroll">
        <div className="ytw-top">
          <IconButton icon="expand" label="Collapse to mini player" onClick={p.onCollapse} />
          <span className="ytw-top-gap" />
          <IconButton icon="skipPrev" label="Previous video" disabled={!prev} onClick={() => prev && p.onOpen({ videoId: prev.videoId, itemId: prev.item.id })} />
          <IconButton icon="skipNext" label="Next video" disabled={!after} onClick={() => after && p.onOpen({ videoId: after.videoId, itemId: after.item.id })} />
          <IconButton icon="close" label="Close player" onClick={p.onClose} />
        </div>
        <div className="ytw-grid">
          <div className="ytw-main">
            <div className="ytw-player">
              <YouTubeFrame itemId={itemId} videoId={videoId} title={title} autoplay />
            </div>
            <div className="ytw-mini-bar">
              <button type="button" className="ytw-mini-text" onClick={p.onExpand} aria-label={`Open ${title}`}>
                <span className="ytw-mini-title">{title}</span>
                <span className="ytw-mini-channel">{channel}</span>
              </button>
              <button type="button" className="ytw-mini-play" aria-label={playing ? 'Pause' : 'Play'} title={playing ? 'Pause' : 'Play'} onClick={togglePlay}>
                <Icon name={playing ? 'pause' : 'play'} size={26} />
              </button>
              <IconButton icon="close" label="Close player" onClick={p.onClose} />
            </div>
            <div className="ytw-info">
              <h1 className="ytw-title">{title}</h1>
              <p className="ytw-meta">
                {metaLine}
                {next?.description ? (
                  <button type="button" className="ytw-more" aria-expanded={more} onClick={() => setMore(!more)}>
                    {more ? 'Show less' : '…more'}
                  </button>
                ) : null}
              </p>
              {more && next?.description ? <p className="ytw-desc">{next.description}</p> : null}
              {channel ? (
                <div className="ytw-channel">
                  <ChannelAvatar src={next?.channelAvatar ?? feed?.imageUrl ?? null} name={channel} size={40} />
                  <span className="ytw-channel-text">
                    <span className="ytw-channel-name">{channel}</span>
                    {next?.subscribersText ? <span className="ytw-sub-count">{next.subscribersText}</span> : null}
                  </span>
                  {feed ? (
                    <MoreMenu
                      label={`Subscribed to ${channel}`}
                      pill="Subscribed"
                      entries={[{ icon: 'delete', label: 'Unsubscribe', danger: true, onClick: () => void unsubscribe(feed, channel).then((ok) => ok && p.toast(`Unsubscribed from ${channel}`)) }]}
                    />
                  ) : next?.channelId ? (
                    <button type="button" className="mf-btn primary" onClick={() => void subscribe(channelFeedUrl(next.channelId!), '').then(p.toast)}>
                      Subscribe
                    </button>
                  ) : null}
                </div>
              ) : null}
              <Chips item={item} videoId={videoId} likes={next?.likesText ?? null} onShare={() => void share()} toast={p.toast} />
            </div>
            {comments && comments.comments.length ? (
              <button type="button" className="ytw-comments-card" onClick={() => setSheet(true)}>
                <span className="ytw-comments-head">
                  Comments <span className="muted">{comments.countText?.replace(/\s*Comments?$/i, '')}</span>
                </span>
                <span className="ytw-comment-top">
                  <ChannelAvatar src={comments.comments[0].avatar} name={comments.comments[0].author} size={24} />
                  <span className="ytw-comment-line">{comments.comments[0].text}</span>
                </span>
              </button>
            ) : null}
          </div>
          {next?.related.length ? (
            <div className="ytw-side" aria-label="Related videos">
              {next.related.map((r) => (
                <RelatedCard key={r.videoId} r={r} onOpen={() => openRelated(r)} />
              ))}
            </div>
          ) : null}
        </div>
      </div>
      {sheet && comments ? <CommentsSheet first={comments} onClose={() => setSheet(false)} /> : null}
    </div>
  )
}

/** "1,469,563 views" → "1.5M views", as Flow shows it; other wordings stay. */
function shortViews(text: string | null | undefined): string {
  const m = text?.match(/^([\d,]+) (views?|watching)$/)
  return m ? `${compactNumber(Number(m[1].replace(/,/g, '')))} ${m[2]}` : (text ?? '')
}

function Chips({ item, videoId, likes, onShare, toast }: { item: Item | undefined; videoId: string; likes: string | null; onShare: () => void; toast: (m: string) => void }) {
  const canSave = useVideoSavingAvailable()
  const jobs = useVideoJobs()
  const job = jobs[videoId]
  return (
    <div className="ytw-chips">
      {likes ? (
        <span className="ytw-likes" aria-label={`${likes} likes`}>
          <span className="ytw-like">
            <Icon name="thumbUp" size={18} /> {likes}
          </span>
          <span className="ytw-like-sep" />
          <span className="ytw-like">
            <Icon name="thumbDown" size={18} />
          </span>
        </span>
      ) : null}
      {item ? (
        <button type="button" className={`chip${item.saved ? ' on' : ''}`} aria-pressed={!!item.saved} onClick={() => void db.items.update(item.id, { saved: item.saved ? 0 : 1 })}>
          <Icon name={item.saved ? 'bookmarkFilled' : 'bookmark'} size={18} /> {item.saved ? 'Saved' : 'Save'}
        </button>
      ) : null}
      {item && (canSave || item.offlineVideo) ? (
        <button
          type="button"
          className={`chip${item.offlineVideo ? ' on' : ''}`}
          disabled={!!job || !!item.offlineVideo}
          onClick={() => {
            toast('Downloading for offline')
            void saveVideo(item.id, videoId).catch((e) => toast(`Download failed: ${e instanceof Error ? e.message : String(e)}`))
          }}
        >
          <Icon name={item.offlineVideo ? 'check' : 'download'} size={18} /> {item.offlineVideo ? 'Downloaded' : job ? 'Downloading…' : 'Download'}
        </button>
      ) : null}
      <button type="button" className="chip" onClick={onShare}>
        <Icon name="share" size={18} /> Share
      </button>
      <a className="chip" href={youtubeWatchUrl(videoId)} target="_blank" rel="noopener noreferrer">
        <Icon name="open" size={18} /> Open in YouTube
      </a>
    </div>
  )
}

function RelatedCard({ r, onOpen }: { r: RelatedVideo; onOpen: () => void }) {
  return (
    <button type="button" className="yt-card ytw-related" onClick={onOpen}>
      <span className="yt-thumb">
        <img src={r.thumbnail ?? thumbUrl(r.videoId, true)} alt="" loading="lazy" referrerPolicy="no-referrer" />
        {r.durationText ? <span className="yt-badge">{r.durationText}</span> : null}
      </span>
      <span className="yt-text">
        <ChannelAvatar src={r.channelAvatar} name={r.channel} />
        <span className="yt-text-col">
          <span className="yt-title">{r.title}</span>
          <span className="yt-meta">{[r.channel, r.viewsText, r.ageText].filter(Boolean).join(' · ')}</span>
        </span>
      </span>
    </button>
  )
}

function CommentsSheet({ first, onClose }: { first: CommentPage; onClose: () => void }) {
  const [list, setList] = useState<Comment[]>(first.comments)
  const [token, setToken] = useState(first.next)
  const [busy, setBusy] = useState(false)
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])
  const loadMore = async () => {
    if (!token || busy) return
    setBusy(true)
    const page = await fetchComments(token)
    setBusy(false)
    if (!page) return setToken(null)
    setList((l) => [...l, ...page.comments.filter((c) => !l.some((x) => x.id === c.id))])
    setToken(page.next)
  }
  return (
    <div className="ytw-sheet-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="ytw-sheet" role="dialog" aria-modal="true" aria-label="Comments">
        <header className="ytw-sheet-head">
          <h2>
            Comments <span className="muted">{first.countText?.replace(/\s*Comments?$/i, '')}</span>
          </h2>
          <IconButton icon="close" label="Close comments" onClick={onClose} />
        </header>
        <ul className="ytw-comment-list">
          {list.map((c) => (
            <li key={c.id} className="ytw-comment">
              <ChannelAvatar src={c.avatar} name={c.author} size={32} />
              <span className="ytw-comment-body">
                <span className="ytw-comment-author">
                  {c.author} <span className="muted">· {c.published}</span>
                </span>
                <span className="ytw-comment-text">{c.text}</span>
                <span className="ytw-comment-foot">
                  {c.likes ? (
                    <span>
                      <Icon name="thumbUp" size={14} /> {c.likes}
                    </span>
                  ) : null}
                  {c.replies ? <span>{c.replies === '1' ? '1 reply' : `${c.replies} replies`}</span> : null}
                </span>
              </span>
            </li>
          ))}
        </ul>
        {token ? (
          <button type="button" className="mf-btn ytw-load-more" disabled={busy} onClick={() => void loadMore()}>
            {busy ? 'Loading…' : 'More comments'}
          </button>
        ) : null}
      </div>
    </div>
  )
}
