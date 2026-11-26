import { useEffect, useRef, useState } from 'react'
import { useLiveQuery } from 'dexie-react-hooks'
import { db, type Item } from '../lib/db'
import {
  cancelVideo,
  pauseVideo,
  clearVideoError,
  downloadForOffline,
  formatBytes,
  openSavedInIina,
  removeAllVideos,
  removeOfflineCopies,
  removeVideo,
  saveSubtitles,
  saveVideo,
  savedSubtitleUrls,
  savedVideoUrl,
  stopOfflineDownload,
  storageUsed,
  useOfflineJob,
  useSubsRunning,
  useVideoJobs,
  useVideoSavingAvailable,
} from '../lib/offline'
import { resumeAt, saveVideoPos } from '../lib/videopos'
import { syncNow } from '../lib/sync2'
import { updateSettings, useSettings } from '../lib/settings'
import { Icon } from './Icon'
import { isMac } from '../lib/youtube'
import { confirmDelete, itemCount } from '../lib/confirmDelete'

const LIMITS = [
  { value: 100, label: 'Newest 100' },
  { value: 250, label: 'Newest 250' },
  { value: 500, label: 'Newest 500' },
  { value: 0, label: 'All unread' },
]

/** Settings section: the one-time "Download for offline" job, for trips. */
export function OfflineSection({ toast }: { toast: (m: string) => void }) {
  const offlineMode = useSettings().offlineMode
  const job = useOfflineJob()
  const [limit, setLimit] = useState(500)
  const [images, setImages] = useState(true)
  const [used, setUsed] = useState<number | null>(null)
  const unread = useLiveQuery(() => db.items.where('read').equals(0).count(), []) ?? 0
  const saved = useLiveQuery(() => db.items.filter((i) => !!i.offlineAt && i.read === 0).count(), []) ?? 0
  const videos = useLiveQuery(() => db.items.filter((i) => !!i.offlineVideo).toArray(), []) ?? []

  useEffect(() => {
    let live = true
    storageUsed().then((n) => live && setUsed(n))
    return () => {
      live = false
    }
  }, [job.running, job.done, saved, videos.length])

  const pct = job.total ? Math.round((job.done / job.total) * 100) : 0

  return (
    <section className="settings-section">
      <h3>Offline reading</h3>
      <p className="muted small">
        Before a trip, save unread articles with their full text and images so they open without a connection. Read marks and stars made
        offline sync when you are back online.
      </p>
      <label className="check">
        <input type="checkbox" checked={offlineMode} onChange={(e) => updateSettings({ offlineMode: e.target.checked })} /> Offline mode: use only
        downloaded content and fetch nothing, even with internet (also the Go offline button above the list)
      </label>
      <div className="offline-stats">
        <span>
          <strong>{saved}</strong> of {unread} unread saved offline
        </span>
        {videos.length ? (
          <span>
            <strong>{videos.length}</strong> video{videos.length === 1 ? '' : 's'}
          </span>
        ) : null}
        {used !== null ? <span>{formatBytes(used)} used in this browser</span> : null}
      </div>
      <div className="form-actions wrap">
        <select value={limit} onChange={(e) => setLimit(Number(e.target.value))} disabled={job.running} aria-label="How many articles">
          {LIMITS.map((l) => (
            <option key={l.value} value={l.value}>
              {l.label}
            </option>
          ))}
        </select>
        <label className="check inline">
          <input type="checkbox" checked={images} onChange={(e) => setImages(e.target.checked)} disabled={job.running} /> Images
        </label>
        {job.running ? (
          <button onClick={stopOfflineDownload}>Stop</button>
        ) : (
          <button className="primary" onClick={() => void downloadForOffline({ limit, images })} disabled={!navigator.onLine || offlineMode} title={offlineMode ? 'Turn off Offline mode to download' : undefined}>
            <Icon name="download" size={18} /> Download for offline
          </button>
        )}
        <button
          onClick={async () => {
            if (!(await confirmDelete({ title: 'Remove offline copies?', itemName: 'Saved article text and images', scope: 'this-device', confirmLabel: 'Remove' }))) return
            await removeOfflineCopies()
            toast('Removed offline images')
          }}
          disabled={job.running || !saved}
        >
          Remove offline copies
        </button>
      </div>
      {job.running || job.message ? (
        <div className="offline-progress" role="status">
          {job.running ? (
            <div className="progress-bar" aria-hidden="true">
              <span style={{ width: `${pct}%` }} />
            </div>
          ) : null}
          <span className="muted small">
            {job.running ? `${job.done} / ${job.total} articles · ${job.images} images` : job.message}
            {job.failed ? ` · full text unavailable for ${job.failed} (feed text kept)` : ''}
          </span>
        </div>
      ) : null}
      <p className="muted small">
        Saved copies of articles you read are removed a week later; starred ones stay. YouTube videos are saved one at a time with the
        Save video button in the article (needs Feeder running on your Mac).
      </p>
      {videos.length ? (
        <div className="form-actions wrap">
          <button
            onClick={async () => {
              if (!(await confirmDelete({ title: 'Remove offline videos?', itemName: itemCount(videos.length, 'video'), scope: 'this-device', confirmLabel: 'Remove' }))) return
              await removeAllVideos()
              toast('Removed offline videos')
            }}
          >
            Remove {videos.length} offline video{videos.length === 1 ? '' : 's'} ({formatBytes(videos.reduce((n, v) => n + (v.offlineVideo ?? 0), 0))})
          </button>
        </div>
      ) : null}
    </section>
  )
}

/** The saved MP4 in a plain video player, resuming and saving the position like the YouTube player. */
/** "English", "English (United States)", "Hindi"; auto captions are YouTube's own guesses. */
function langLabel(lang: string): string {
  const base = lang.replace(/-orig$/, '')
  try {
    return new Intl.DisplayNames([navigator.language || 'en'], { type: 'language' }).of(base) ?? lang
  } catch {
    return lang
  }
}

export function LocalVideo({ itemId, videoId, title, subs }: { itemId: string; videoId: string; title: string; subs?: string[] }) {
  const [src, setSrc] = useState<string | null>(null)
  const [tracks, setTracks] = useState<{ lang: string; url: string }[]>([])
  const subsKey = (subs ?? []).join(',')

  useEffect(() => {
    let live = true
    let urls: { url: string }[] = []
    savedSubtitleUrls(videoId, subsKey ? subsKey.split(',') : []).then((t) => {
      urls = t
      if (live) setTracks(t)
      else t.forEach((x) => URL.revokeObjectURL(x.url))
    })
    return () => {
      live = false
      urls.forEach((x) => URL.revokeObjectURL(x.url))
    }
  }, [videoId, subsKey])
  const [missing, setMissing] = useState(false)
  const ref = useRef<HTMLVideoElement>(null)

  useEffect(() => {
    let live = true
    let url: string | null = null
    savedVideoUrl(videoId).then((u) => {
      url = u
      if (!live) return u && URL.revokeObjectURL(u)
      if (u) setSrc(u)
      else setMissing(true)
    })
    return () => {
      live = false
      if (url) URL.revokeObjectURL(url)
    }
  }, [videoId])

  useEffect(() => {
    const el = ref.current
    if (!el || !src) return
    let lastSaved = 0
    const save = (send = false) => {
      if (!el.currentTime) return
      lastSaved = Date.now()
      const done = saveVideoPos(itemId, el.currentTime, el.duration)
      if (send) void done.then(() => syncNow()).catch(() => undefined)
    }
    const onMeta = () => {
      void db.videoPos.get(itemId).then((p) => {
        const at = resumeAt(p)
        if (at) el.currentTime = at
      })
    }
    const onTime = () => Date.now() - lastSaved > 5000 && save()
    const onStop = () => save(true)
    el.addEventListener('loadedmetadata', onMeta)
    el.addEventListener('timeupdate', onTime)
    el.addEventListener('pause', onStop)
    el.addEventListener('ended', onStop)
    return () => {
      el.removeEventListener('loadedmetadata', onMeta)
      el.removeEventListener('timeupdate', onTime)
      el.removeEventListener('pause', onStop)
      el.removeEventListener('ended', onStop)
      save(true)
    }
  }, [itemId, src])

  if (missing) return <div className="video-frame video-missing">The offline copy is gone. Save it again.</div>
  return (
    <div className="video-frame">
      {src ? (
        <video ref={ref} src={src} controls playsInline preload="metadata" title={title}>
          {tracks.map((t, i) => (
            <track key={t.lang} kind="subtitles" srcLang={t.lang.replace(/-orig$/, '')} label={langLabel(t.lang)} src={t.url} default={i === 0} />
          ))}
        </video>
      ) : null}
    </div>
  )
}

/** Save / progress / remove controls for one article's YouTube video. Always visible. */
export function VideoOfflineControls({
  item,
  videoId,
  useLocal,
  onUseLocal,
}: {
  item: Item
  videoId: string
  useLocal: boolean
  onUseLocal: (v: boolean) => void
}) {
  const jobs = useVideoJobs()
  const available = useVideoSavingAvailable()
  const subsBusy = !!useSubsRunning()[videoId]
  const [subsError, setSubsError] = useState('')
  const [iina, setIina] = useState('')
  const run = jobs[videoId]

  if (item.offlineVideo) {
    return (
      <>
        <button className="open-youtube" onClick={() => onUseLocal(!useLocal)} title="Switch between the saved copy and YouTube's player">
          <Icon name={useLocal ? 'open' : 'download'} size={18} /> {useLocal ? 'Play on YouTube player' : 'Play offline copy'}
        </button>
        {isMac() && available ? (
          <button
            className="open-youtube"
            disabled={iina === 'busy'}
            title="Play the saved copy (with its subtitles) in IINA"
            onClick={() => {
              setIina('busy')
              openSavedInIina(videoId, item.offlineSubs ?? [])
                .then(() => setIina(''))
                .catch((e) => setIina(e instanceof Error ? e.message : String(e)))
            }}
          >
            <Icon name="play" size={18} /> {iina === 'busy' ? 'Opening in IINA…' : 'Offline copy in IINA'}
          </button>
        ) : null}
        {iina && iina !== 'busy' ? <span className="video-error">{iina}</span> : null}
        <button className="open-youtube" onClick={async () => {
            if (!(await confirmDelete({ title: 'Remove offline video?', itemName: item.title || 'Untitled', scope: 'this-device', confirmLabel: 'Remove' }))) return
            void removeVideo(item.id, videoId)
          }} title="Delete the saved copy from this browser">
          <Icon name="delete" size={18} /> Remove offline video ({formatBytes(item.offlineVideo)})
        </button>
        {subsBusy ? (
          <span className="video-saving">Getting subtitles…</span>
        ) : item.offlineSubs?.length ? (
          <span className="video-subs" title="Turn them on or off with the CC button in the player">
            Subtitles: {item.offlineSubs.map(langLabel).join(', ')}
          </span>
        ) : (
          <button
            className="open-youtube"
            disabled={!available}
            title={available ? 'Get subtitles or YouTube auto captions with yt-dlp' : 'Needs Feeder running on your Mac'}
            onClick={() => {
              setSubsError('')
              saveSubtitles(item.id, videoId)
                .then((l) => !l.length && setSubsError('This video has no English or Hindi subtitles'))
                .catch((e) => setSubsError(e instanceof Error ? e.message : String(e)))
            }}
          >
            {item.offlineSubs ? 'Try subtitles again' : 'Add subtitles'}
          </button>
        )}
        {subsError ? <span className="video-error">{subsError}</span> : null}
      </>
    )
  }
  if (run?.phase === 'paused') {
    return (
      <span className="video-saving">
        <span className="progress-bar small" aria-hidden="true">
          <span style={{ width: `${run.percent}%` }} />
        </span>
        Paused {run.percent}%
        <button className="link-btn" onClick={() => void saveVideo(item.id, videoId)} disabled={!available}>
          Resume
        </button>
        <button className="link-btn" onClick={() => cancelVideo(videoId)}>
          Cancel
        </button>
      </span>
    )
  }
  if (run && run.phase !== 'error') {
    return (
      <span className="video-saving">
        <span className="progress-bar small" aria-hidden="true">
          <span style={{ width: `${run.percent}%` }} />
        </span>
        {run.phase === 'mac' ? `Downloading on your Mac ${run.percent}%` : `Saving to this browser ${run.percent}%`}
        {run.phase === 'mac' ? (
          <button className="link-btn" onClick={() => pauseVideo(videoId)}>
            Pause
          </button>
        ) : null}
        <button className="link-btn" onClick={() => cancelVideo(videoId)}>
          Cancel
        </button>
      </span>
    )
  }
  return (
    <>
      <button
        className="open-youtube"
        onClick={() => {
          clearVideoError(videoId)
          void saveVideo(item.id, videoId)
        }}
        disabled={!available}
        title={available ? 'Download this video with yt-dlp for offline watching' : 'Saving videos needs Feeder running on your Mac (localhost) with yt-dlp'}
      >
        <Icon name="download" size={18} /> Save video offline
      </button>
      {run?.phase === 'error' ? <span className="video-error">{run.error}</span> : null}
    </>
  )
}
