import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { feedDisplayTitle, type Feed, type Item } from '../lib/db'
import {
  cancelVideo,
  formatBytes,
  pauseVideo,
  removeAllVideos,
  removeVideo,
  saveVideo,
  savedSubtitleUrls,
  savedVideoUrl,
  useVideoJobs,
  useVideoSavingAvailable,
  type VideoJob,
} from '../lib/offline'
import {
  formatDuration,
  knownDurations,
  moveInOrder,
  orderDownloads,
  rememberDuration,
  saveOrder,
  savedOrder,
  useDownloadsPrefs,
} from '../lib/downloads'
import { articleVideoId } from '../lib/youtube'
import { Icon, IconButton } from './Icon'

interface Download {
  item: Item
  videoId: string
}

const channelOf = (item: Item, feeds: Map<string, Feed>) => item.author || (feeds.has(item.feedUrl) ? feedDisplayTitle(feeds.get(item.feedUrl)) : '')

/**
 * Downloads: every video saved in this browser. Unfinished downloads first (percent, Pause
 * or Resume, Delete), then the finished ones, which are also the playlist: Play all plays
 * them in order, and dragging a row changes the order.
 */
export function DownloadsPage(p: {
  items: Item[]
  feeds: Map<string, Feed>
  onBack: () => void
  onMenu: () => void
  onOpenArticle: (id: string) => void
  toast: (msg: string) => void
}) {
  const jobs = useVideoJobs()
  const available = useVideoSavingAvailable()
  const [prefs, setPrefs] = useDownloadsPrefs()
  const [order, setOrder] = useState<string[]>(savedOrder)
  const [dragging, setDragging] = useState<string | null>(null)
  const [playingId, setPlayingId] = useState<string | null>(null)
  const [durations, setDurations] = useState<Record<string, number>>(knownDurations)

  const byId = useMemo(() => new Map(p.items.map((i) => [i.id, i])), [p.items])

  const saved = useMemo(() => {
    const out: Download[] = []
    for (const item of p.items) {
      const videoId = item.offlineVideo ? articleVideoId(item) : null
      if (videoId) out.push({ item, videoId })
    }
    return out
  }, [p.items])

  const completed = useMemo(() => {
    const map = new Map(saved.map((d) => [d.item.id, d]))
    const ids = orderDownloads(
      order,
      saved.map((d) => d.item.id),
      (id) => map.get(id)?.item.offlineVideoAt ?? map.get(id)?.item.sortTime ?? 0,
    )
    return ids.map((id) => map.get(id)!)
  }, [saved, order])

  const incomplete = useMemo(
    () =>
      Object.entries(jobs)
        .map(([videoId, job]) => ({ videoId, job, item: job.itemId ? byId.get(job.itemId) : undefined }))
        .filter((d) => !d.item?.offlineVideo),
    [jobs, byId],
  )

  // Keep the saved order in step with what is actually saved
  useEffect(() => {
    if (!dragging) saveOrder(completed.map((d) => d.item.id))
  }, [completed, dragging])

  // Measure lengths once, one video at a time, from the saved copies
  useEffect(() => {
    const missing = completed.filter((d) => durations[d.videoId] === undefined)
    if (!missing.length) return
    let live = true
    void (async () => {
      for (const d of missing) {
        if (!live) return
        const seconds = await measureDuration(d.videoId)
        if (seconds === null || !live) continue
        rememberDuration(d.videoId, seconds)
        setDurations((old) => ({ ...old, [d.videoId]: seconds }))
      }
    })()
    return () => {
      live = false
    }
  }, [completed, durations])

  const playing = completed.find((d) => d.item.id === playingId) ?? null
  const totalSeconds = completed.reduce((n, d) => n + (durations[d.videoId] ?? 0), 0)
  const totalBytes = completed.reduce((n, d) => n + (d.item.offlineVideo ?? 0), 0)

  const indexOf = (id: string | null) => completed.findIndex((d) => d.item.id === id)
  const playNext = (by: number) => {
    const i = indexOf(playingId)
    let next = i + by
    if (next >= completed.length) next = prefs.loop ? 0 : -1
    if (next < 0 && by < 0) next = prefs.loop ? completed.length - 1 : 0
    setPlayingId(next >= 0 ? completed[next].item.id : null)
  }

  const onEnded = () => {
    const finished = playing
    const i = indexOf(playingId)
    const last = i === completed.length - 1
    if (prefs.autoplay && (!last || prefs.loop)) {
      setPlayingId(completed[last ? 0 : i + 1].item.id)
    }
    if (finished && prefs.removeWatched) {
      void removeVideo(finished.item.id, finished.videoId)
      if (last && !prefs.loop) setPlayingId(null)
    }
  }

  const deleteAll = async () => {
    if (!window.confirm('Delete every saved video and every unfinished download from this browser? This can’t be undone.')) return
    setPlayingId(null)
    for (const d of incomplete) cancelVideo(d.videoId)
    await removeAllVideos()
    p.toast('All downloads deleted')
  }

  const move = (id: string, to: { targetId: string } | { by: number }) => setOrder(moveInOrder(completed.map((d) => d.item.id), id, to))

  return (
    <section className="manage-pane dl-page" aria-label="Downloads">
      <div className="mf-page">
        <div className="mf-head">
          <span className="only-narrow">
            <IconButton icon="menu" label="Feeds" onClick={p.onMenu} />
          </span>
          <h1>Downloads</h1>
          <span className="mf-meta">
            {completed.length} {completed.length === 1 ? 'video' : 'videos'}
            {totalSeconds ? ` · ${formatDuration(totalSeconds)}` : ''}
            {totalBytes ? ` · ${formatBytes(totalBytes)}` : ''}
          </span>
          <span className="mf-sp" />
          {completed.length || incomplete.length ? (
            <button type="button" className="mf-btn" onClick={() => void deleteAll()} title="Delete every download from this browser">
              <Icon name="delete" size={18} />
              <span>Delete all</span>
            </button>
          ) : null}
          <IconButton icon="close" label="Close downloads" onClick={p.onBack} />
        </div>

        {!completed.length && !incomplete.length ? (
          <div className="dl-empty">
            <Icon name="download" size={40} />
            <p>No downloads yet.</p>
            <p className="mf-note">
              {available
                ? 'Open a YouTube article and choose Save video offline. It shows up here.'
                : 'Saving videos needs the Feeder Mac app (or Feeder running on localhost) with yt-dlp.'}
            </p>
          </div>
        ) : (
          <div className="dl-layout">
            <div className="dl-stage">
              {playing ? (
                <StagePlayer
                  key={playing.item.id}
                  download={playing}
                  channel={channelOf(playing.item, p.feeds)}
                  onEnded={onEnded}
                  onDuration={(s) => {
                    if (durations[playing.videoId] === undefined) {
                      rememberDuration(playing.videoId, s)
                      setDurations((old) => ({ ...old, [playing.videoId]: s }))
                    }
                  }}
                  onOpenArticle={() => p.onOpenArticle(playing.item.id)}
                />
              ) : (
                <div className="dl-idle">
                  <p>{completed.length ? 'Play your downloads one after another, in the order of the list. Drag a video to move it.' : 'Downloads in progress are listed here.'}</p>
                  {completed.length ? (
                    <button type="button" className="dl-play-all" onClick={() => setPlayingId(completed[0].item.id)}>
                      <Icon name="play" size={22} /> Play all
                    </button>
                  ) : null}
                </div>
              )}
              <div className="dl-controls">
                {playing ? (
                  <>
                    <button type="button" className="mf-btn" onClick={() => playNext(-1)} disabled={indexOf(playingId) === 0 && !prefs.loop}>
                      <Icon name="skipPrev" size={18} />
                      <span>Previous</span>
                    </button>
                    <button type="button" className="mf-btn" onClick={() => playNext(1)}>
                      <Icon name="skipNext" size={18} />
                      <span>Next</span>
                    </button>
                    <button type="button" className="mf-btn" onClick={() => setPlayingId(null)}>
                      <Icon name="close" size={18} />
                      <span>Stop</span>
                    </button>
                  </>
                ) : null}
                <Toggle on={prefs.autoplay} onChange={(v) => setPrefs({ autoplay: v })} label="Autoplay next" />
                <Toggle on={prefs.loop} onChange={(v) => setPrefs({ loop: v })} label="Loop" />
                <Toggle on={prefs.removeWatched} onChange={(v) => setPrefs({ removeWatched: v })} label="Remove after watching" />
              </div>
            </div>

            <div className="dl-side">
              {incomplete.length ? (
                <>
                  <h2 className="dl-section dl-section-warn">Incomplete downloads</h2>
                  <ul className="dl-list">
                    {incomplete.map((d) => (
                      <IncompleteRow
                        key={d.videoId}
                        videoId={d.videoId}
                        job={d.job}
                        item={d.item}
                        channel={d.item ? channelOf(d.item, p.feeds) : ''}
                        canResume={available}
                      />
                    ))}
                  </ul>
                </>
              ) : null}
              {completed.length ? (
                <>
                  <h2 className="dl-section">Completed</h2>
                  <ol className="dl-list" aria-label="Playlist">
                    {completed.map((d, i) => (
                      <li
                        key={d.item.id}
                        className={`dl-row${d.item.id === playingId ? ' playing' : ''}${dragging === d.item.id ? ' dragging' : ''}`}
                        draggable
                        onDragStart={(e) => {
                          e.dataTransfer.effectAllowed = 'move'
                          e.dataTransfer.setData('text/plain', d.item.id)
                          setDragging(d.item.id)
                        }}
                        onDragOver={(e) => {
                          if (!dragging) return
                          e.preventDefault()
                          if (dragging !== d.item.id) move(dragging, { targetId: d.item.id })
                        }}
                        onDrop={(e) => e.preventDefault()}
                        onDragEnd={() => setDragging(null)}
                      >
                        <span
                          className="dl-handle"
                          role="button"
                          tabIndex={0}
                          aria-label={`Move ${d.item.title}. Arrow keys move it up or down.`}
                          title="Drag to change the order (or focus and use the arrow keys)"
                          onKeyDown={(e) => {
                            if (e.key === 'ArrowUp' || e.key === 'ArrowDown') {
                              e.preventDefault()
                              move(d.item.id, { by: e.key === 'ArrowUp' ? -1 : 1 })
                            }
                          }}
                        >
                          <Icon name="dragHandle" size={20} />
                        </span>
                        <button type="button" className="dl-main" onClick={() => setPlayingId(d.item.id)} title="Play from here">
                          <Thumb url={d.item.imageUrl}>
                            {d.item.id === playingId ? <span className="dl-thumb-overlay">Playing</span> : null}
                            {durations[d.videoId] ? <span className="dl-duration">{formatDuration(durations[d.videoId])}</span> : null}
                          </Thumb>
                          <span className="dl-text">
                            <span className="dl-title">
                              <span className="dl-num">{i + 1}.</span> {d.item.title}
                            </span>
                            <span className="dl-sub">{channelOf(d.item, p.feeds)}</span>
                            <span className="dl-sub">{d.item.offlineVideo ? formatBytes(d.item.offlineVideo) : ''}</span>
                          </span>
                        </button>
                        <IconButton
                          icon="delete"
                          label={`Delete ${d.item.title}`}
                          title="Delete this download"
                          onClick={() => {
                            if (d.item.id === playingId) playNext(1)
                            void removeVideo(d.item.id, d.videoId)
                          }}
                        />
                      </li>
                    ))}
                  </ol>
                </>
              ) : null}
            </div>
          </div>
        )}
      </div>
    </section>
  )
}

function Toggle({ on, onChange, label }: { on: boolean; onChange: (v: boolean) => void; label: string }) {
  return (
    <button type="button" className={`hl-filter${on ? ' on' : ''}`} aria-pressed={on} onClick={() => onChange(!on)}>
      {on ? <Icon name="check" size={16} /> : null} {label}
    </button>
  )
}

function Thumb({ url, children }: { url: string | null; children?: ReactNode }) {
  const [failed, setFailed] = useState(false)
  return (
    <span className="dl-thumb">
      {url && !failed ? <img src={url} alt="" loading="lazy" onError={() => setFailed(true)} /> : <Icon name="play" size={28} />}
      {children}
    </span>
  )
}

function IncompleteRow({
  videoId,
  job,
  item,
  channel,
  canResume,
}: {
  videoId: string
  job: VideoJob
  item: Item | undefined
  channel: string
  canResume: boolean
}) {
  const status =
    job.phase === 'error'
      ? 'Failed'
      : job.phase === 'paused'
        ? `Paused · ${job.percent}%`
        : job.phase === 'mac'
          ? `Downloading on your Mac · ${job.percent}%`
          : `Saving to this browser · ${job.percent}%`
  return (
    <li className="dl-row">
      <span className="dl-main static">
        <Thumb url={item?.imageUrl ?? null}>
          <span className="dl-thumb-overlay">{job.percent}%</span>
          <span className="dl-progress" style={{ width: `${job.percent}%` }} />
        </Thumb>
        <span className="dl-text">
          <span className="dl-title">{item?.title ?? videoId}</span>
          {channel ? <span className="dl-sub">{channel}</span> : null}
          <span className="dl-sub dl-warn" title={job.error}>
            {status}
          </span>
        </span>
      </span>
      {job.phase === 'error' || job.phase === 'paused' ? (
        <IconButton
          icon={job.phase === 'error' ? 'refresh' : 'play'}
          label={job.phase === 'error' ? 'Try again' : 'Resume'}
          disabled={!canResume || !item}
          onClick={() => item && void saveVideo(item.id, videoId)}
        />
      ) : job.phase === 'mac' ? (
        <IconButton icon="pause" label="Pause" onClick={() => pauseVideo(videoId)} />
      ) : null}
      <IconButton icon="delete" label="Delete" title="Stop and delete this download" onClick={() => cancelVideo(videoId)} />
    </li>
  )
}

/** The video playing now, from its saved copy, with its saved subtitles. */
function StagePlayer({
  download,
  channel,
  onEnded,
  onDuration,
  onOpenArticle,
}: {
  download: Download
  channel: string
  onEnded: () => void
  onDuration: (seconds: number) => void
  onOpenArticle: () => void
}) {
  const [src, setSrc] = useState<string | null>(null)
  const [tracks, setTracks] = useState<{ lang: string; url: string }[]>([])
  const [missing, setMissing] = useState(false)
  const ref = useRef<HTMLVideoElement>(null)

  useEffect(() => {
    let live = true
    const urls: string[] = []
    void (async () => {
      const url = await savedVideoUrl(download.videoId)
      if (!live) return url && URL.revokeObjectURL(url)
      if (!url) return setMissing(true)
      urls.push(url)
      setSrc(url)
      const subs = await savedSubtitleUrls(download.videoId, download.item.offlineSubs ?? [])
      urls.push(...subs.map((s) => s.url))
      if (live) setTracks(subs)
    })()
    return () => {
      live = false
      urls.forEach((u) => URL.revokeObjectURL(u))
    }
  }, [download.videoId, download.item.offlineSubs])

  return (
    <div className="dl-player">
      <div className="dl-video">
        {missing ? (
          <p className="video-error">The saved copy is gone from this browser.</p>
        ) : src ? (
          <video
            ref={ref}
            src={src}
            controls
            autoPlay
            playsInline
            onEnded={onEnded}
            onLoadedMetadata={(e) => {
              const d = e.currentTarget.duration
              if (Number.isFinite(d)) onDuration(d)
            }}
          >
            {tracks.map((t) => (
              <track key={t.lang} kind="subtitles" srcLang={t.lang} label={t.lang} src={t.url} />
            ))}
          </video>
        ) : null}
      </div>
      <div className="dl-now">
        <h2>{download.item.title}</h2>
        {channel ? <span className="mf-meta">{channel}</span> : null}
        <button type="button" className="link-btn" onClick={onOpenArticle}>
          Open article
        </button>
      </div>
    </div>
  )
}

/** Length of a saved video in seconds, read from its metadata; null if it can't be read. */
async function measureDuration(videoId: string): Promise<number | null> {
  const url = await savedVideoUrl(videoId)
  if (!url) return null
  try {
    return await new Promise<number | null>((resolve) => {
      const v = document.createElement('video')
      v.preload = 'metadata'
      v.onloadedmetadata = () => resolve(Number.isFinite(v.duration) ? v.duration : null)
      v.onerror = () => resolve(null)
      setTimeout(() => resolve(null), 10_000)
      v.src = url
    })
  } finally {
    URL.revokeObjectURL(url)
  }
}
