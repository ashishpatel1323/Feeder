import { useEffect, useMemo, useRef, useState } from 'react'
import { feedDisplayTitle, type Feed, type Item } from '../lib/db'
import { playQueue, useQueue } from '../lib/playqueue'
import { readAloud, type ReadAloudState } from '../lib/readaloud'
import { paragraphsOf } from '../lib/flip'
import { loadFullText } from '../lib/extras'
import { isOnline } from '../lib/offline'
import { formatClock } from '../lib/videopos'
import { ITEM_KIND_LABELS, itemKind, readAloudSeconds } from '../lib/playlists'
import { wordsOf } from '../lib/listquery'
import { Icon } from './Icon'
import { formatSpeed } from './Player'

const AUDIO_RATES = [0.8, 1, 1.1, 1.25, 1.5, 1.75, 2, 2.5, 3]
const READ_RATES = [0.75, 1, 1.25, 1.5, 1.75, 2, 2.5, 3, 4]

interface Props {
  items: Item[]
  feeds: Map<string, Feed>
  readRate: number
  onReadRate: (rate: number) => void
  onOpenArticle: (id: string) => void
}

/** Fetches the full text first for feeds set to it, giving up after a few seconds. */
async function textFor(item: Item, feed: Feed | undefined): Promise<string> {
  if (item.fullHtml || !feed?.fullTextByDefault || !item.link || !isOnline()) return item.fullHtml || item.contentHtml || item.snippet
  try {
    return await Promise.race([loadFullText(item), new Promise<string>((_, no) => setTimeout(() => no(new Error('slow')), 10_000))])
  } catch {
    return item.contentHtml || item.snippet
  }
}

/**
 * The Now playing bar for playlists, at the bottom of every page. It plays podcasts and saved
 * videos as audio and reads articles aloud from the text it holds. "Up next" opens the queue and
 * the article text; "Car mode" fills the screen with big buttons.
 */
export function QueuePlayer(p: Props) {
  const q = useQueue()
  const [ra, setRa] = useState<ReadAloudState>(readAloud.state)
  useEffect(() => readAloud.subscribe(setRa), [])
  const [open, setOpen] = useState(false)
  const [car, setCar] = useState(false)
  const textRef = useRef<HTMLDivElement>(null)
  const id = q.ids[q.index] ?? null
  const item = useMemo(() => (id ? (p.items.find((i) => i.id === id) ?? null) : null), [id, p.items])
  const feed = item ? p.feeds.get(item.feedUrl) : undefined
  const [paras, setParas] = useState<{ id: string; list: string[] } | null>(null)
  const byId = useMemo(() => new Map(p.items.map((i) => [i.id, i])), [p.items])

  // Escape leaves car mode
  useEffect(() => {
    if (!car) return
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setCar(false)
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [car])

  // Follow the read-aloud engine for the article now in the queue
  useEffect(() => playQueue.articleStatus(ra.status, ra.itemId), [ra.status, ra.itemId])

  // A new article (or Play on one that was stopped): load its text, then read it aloud
  useEffect(() => {
    if (q.kind !== 'article' || !item || q.status !== 'loading') return
    let cancelled = false
    void textFor(item, feed).then((html) => {
      if (cancelled) return
      setParas({ id: item.id, list: paragraphsOf(html) })
    })
    return () => {
      cancelled = true
    }
  }, [q.kind, q.status, item?.id]) // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (!paras || !item || paras.id !== item.id || q.kind !== 'article' || q.status !== 'loading' || !textRef.current) return
    readAloud.title = item.title
    readAloud.artist = feedDisplayTitle(feed)
    readAloud.attach(textRef.current, item.id)
    readAloud.setRate(p.readRate)
    readAloud.play(0)
  }, [paras, item, q.kind, q.status, feed, p.readRate])

  if (!q.ids.length || !q.kind) return null
  const isArticle = q.kind === 'article'
  const playing = q.status === 'playing'
  const fraction = isArticle ? (ra.textLength ? ra.offset / ra.textLength : 0) : q.duration ? q.position / q.duration : 0
  const articleTotal = item ? readAloudSeconds(wordsOf(item, feed), ra.rate) : 0
  const left = isArticle ? `~${Math.max(1, Math.round((articleTotal * (1 - fraction)) / 60))} min left` : q.duration ? `−${formatClock(q.duration - q.position)}` : ''
  const rate = isArticle ? ra.rate : q.audioRate
  const rates = isArticle ? READ_RATES : AUDIO_RATES
  const nextRate = () => {
    const i = rates.findIndex((r) => r >= rate - 0.001)
    const r = rates[(i + 1) % rates.length]
    if (isArticle) {
      readAloud.setRate(r)
      p.onReadRate(r)
    } else playQueue.setAudioRate(r)
  }
  const kindLabel = isArticle ? 'Article, read aloud' : q.kind === 'youtube' ? 'YouTube, audio' : 'Podcast'
  const where = `${q.name ? `${q.name} · ` : ''}${q.index + 1} of ${q.ids.length}`
  const thumb = (it: Item | null, size: number) =>
    it?.imageUrl ? (
      <img className="qp-thumb" src={it.imageUrl} alt="" width={size} height={size} />
    ) : (
      <span className="qp-thumb gen" style={{ width: size, height: size }}>
        <Icon name={isArticle ? 'article' : 'headphones'} size={size / 2} />
      </span>
    )
  const nextItem = byId.get(q.ids[q.index + 1] ?? '')

  const controls = (big: boolean) => (
    <div className={`qp-controls${big ? ' big' : ''}`}>
      <button className="qp-btn" onClick={() => playQueue.prev()} aria-label="Previous">
        <Icon name="skipPrev" size={big ? 34 : 22} />
        {big ? <span>Previous</span> : null}
      </button>
      <button className="qp-btn" onClick={() => playQueue.seekBy(isArticle ? -10 : -15)} aria-label={isArticle ? 'Back a bit' : 'Back 15 seconds'}>
        <Icon name="replay10" size={big ? 34 : 22} />
        {big ? <span>{isArticle ? 'Back' : '15 s'}</span> : null}
      </button>
      <button className="qp-btn play" onClick={() => playQueue.toggle()} aria-label={playing ? 'Pause' : 'Play'}>
        <Icon name={playing ? 'pause' : 'play'} size={big ? 56 : 26} />
      </button>
      <button className="qp-btn" onClick={() => playQueue.seekBy(isArticle ? 10 : 30)} aria-label={isArticle ? 'Ahead a bit' : 'Ahead 30 seconds'}>
        <Icon name="forward10" size={big ? 34 : 22} />
        {big ? <span>{isArticle ? 'Ahead' : '30 s'}</span> : null}
      </button>
      <button className="qp-btn" onClick={() => void playQueue.next()} aria-label="Next" disabled={q.index >= q.ids.length - 1}>
        <Icon name="skipNext" size={big ? 34 : 22} />
        {big ? <span>Next</span> : null}
      </button>
    </div>
  )

  const bar = (
    <div
      className="qp-progress"
      role="slider"
      aria-label="Position"
      aria-valuenow={Math.round(fraction * 100)}
      onClick={(e) => {
        const r = e.currentTarget.getBoundingClientRect()
        playQueue.seekToFraction(Math.max(0, Math.min(1, (e.clientX - r.left) / r.width)))
      }}
    >
      <i style={{ width: `${Math.min(100, fraction * 100)}%` }} />
    </div>
  )

  return (
    <>
      <div className={`queue-player${open ? ' open' : ''}`} role="region" aria-label="Now playing">
        <div className={`qp-panel${open ? '' : ' offscreen'}`} aria-hidden={!open}>
          <div className="qp-now">
            {isArticle ? (
              <div className="qp-text" ref={textRef} data-scroll>
                {(paras && paras.id === id ? paras.list : []).map((t, i) => (
                  <p key={i}>{t}</p>
                ))}
                {!paras || paras.id !== id ? <p className="muted">Loading the article…</p> : null}
              </div>
            ) : (
              <div className="qp-art">{thumb(item, 220)}</div>
            )}
          </div>
          <div className="qp-queue">
            <div className="qp-queue-head">
              <b>Up next</b>
              <span className="muted">{where}</span>
            </div>
            <ol>
              {q.ids.map((qid, i) => {
                const it = byId.get(qid)
                if (!it) return null
                const k = itemKind(it)
                return (
                  <li key={qid} className={i === q.index ? 'current' : i < q.index ? 'done' : ''}>
                    <button className="qp-q-main" onClick={() => playQueue.jump(i)} title="Play this now">
                      <span className={`ty ${k}`}>{ITEM_KIND_LABELS[k]}</span>
                      <span className="qp-q-title">{it.title}</span>
                    </button>
                    {i !== q.index ? (
                      <button className="qp-q-x" onClick={() => playQueue.remove(i)} aria-label={`Take ${it.title} out of the queue`} title="Take out of the queue">
                        <Icon name="close" size={16} />
                      </button>
                    ) : null}
                  </li>
                )
              })}
            </ol>
          </div>
        </div>
        <div className="qp-bar">
          <button className="qp-info" onClick={() => setOpen((o) => !o)} title={open ? 'Close the queue' : 'Show the queue and article text'}>
            {thumb(item, 48)}
            <span className="qp-titles">
              <span className="qp-title">{item?.title ?? 'Loading…'}</span>
              <span className="qp-sub">
                {kindLabel} · {feedDisplayTitle(feed)} · {where}
              </span>
            </span>
          </button>
          <div className="qp-center">
            {controls(false)}
            <div className="qp-time">
              <span>{isArticle ? '' : formatClock(q.position)}</span>
              {bar}
              <span>{left}</span>
            </div>
          </div>
          <div className="qp-right">
            <button className="btn" onClick={nextRate} title="Speed">
              {formatSpeed(rate)}
            </button>
            <button className={`btn${open ? ' on' : ''}`} onClick={() => setOpen((o) => !o)}>
              <Icon name="queue" size={18} /> Up next ({Math.max(0, q.ids.length - q.index - 1)})
            </button>
            <button className="btn" onClick={() => setCar(true)}>
              <Icon name="car" size={18} /> Car mode
            </button>
            {item && isArticle ? (
              <button className="btn" onClick={() => p.onOpenArticle(item.id)}>
                <Icon name="open" size={18} /> Open
              </button>
            ) : null}
            <button className="btn" onClick={() => playQueue.stop()} title="Stop and close the player">
              <Icon name="close" size={18} /> Stop
            </button>
          </div>
        </div>
      </div>
      {car ? (
        <div className="car-mode" role="dialog" aria-modal="true" aria-label="Car mode">
          <div className="car-top">
            <span className="car-where">{where}</span>
            <button className="btn" onClick={() => setCar(false)}>
              <Icon name="close" size={20} /> Exit car mode
            </button>
          </div>
          <div className="car-now">
            {thumb(item, 160)}
            <div>
              <div className="car-kind">{kindLabel}</div>
              <div className="car-title">{item?.title}</div>
              <div className="car-feed">{feedDisplayTitle(feed)}</div>
            </div>
          </div>
          <div className="car-time">
            {bar}
            <div className="car-time-row">
              <span>{isArticle ? '' : formatClock(q.position)}</span>
              <span>{left}</span>
            </div>
          </div>
          {controls(true)}
          <div className="car-next">{nextItem ? <>Next up: <b>{nextItem.title}</b></> : 'Last item in this playlist'}</div>
          <div className="car-extra">
            <button className="btn big" onClick={nextRate}>
              Speed {formatSpeed(rate)}
            </button>
          </div>
        </div>
      ) : null}
    </>
  )
}
