import { useEffect, useRef, useState } from 'react'
import type { HighlightColor } from '../lib/db'
import { allQuotesText, quoteText } from '../lib/highlights'
import { proxiedUrl } from '../lib/api'
import { findOgImage } from '../lib/extras'
import {
  canvasToPng,
  fileToImage,
  loadShareImage,
  renderShareImage,
  SHARE_GRADIENTS,
  SHARE_LAYOUTS,
  SHARE_SIZES,
  type ShareLayout,
  type ShareSize,
} from '../lib/shareImage'
import { Icon, IconButton } from './Icon'

export interface ShareSubject {
  text: string
  note: string
  color: HighlightColor
  itemTitle: string
  feedTitle: string
  itemLink: string | null
  imageUrl: string | null
  /** Several highlights in one image: their texts and notes are joined in `text` and `note`. */
  quotes?: { text: string; note: string }[]
}

/** One share for several highlights of the same article, in reading order. */
export function combinedSubject(hs: { text: string; note: string; color: HighlightColor }[], article: Omit<ShareSubject, 'text' | 'note' | 'color' | 'quotes'>): ShareSubject {
  return {
    ...article,
    text: hs.map((h) => h.text).join('\n'),
    note: hs.map((h) => h.note).filter(Boolean).join('\n'),
    color: hs[0]?.color ?? 'yellow',
    quotes: hs.map((h) => ({ text: h.text, note: h.note })),
  }
}

const PREFS_KEY = 'feeder.shareImage'
interface Prefs {
  layout: ShareLayout
  size: ShareSize
  gradient: string
  showMark: boolean
}
function loadPrefs(): Prefs {
  const d: Prefs = { layout: 'photo', size: 'story', gradient: 'green', showMark: true }
  try {
    return { ...d, ...JSON.parse(localStorage.getItem(PREFS_KEY) ?? '{}') }
  } catch {
    return d
  }
}

/** Background: the article's picture, your own picture, or one of the gradients. */
type Bg = { kind: 'article' } | { kind: 'custom' } | { kind: 'gradient'; id: string }

export function ShareDialog(p: { subject: ShareSubject; onClose: () => void; toast: (msg: string) => void }) {
  const s = p.subject
  const [prefs, setPrefs] = useState<Prefs>(loadPrefs)
  const [articleImg, setArticleImg] = useState<ImageBitmap | null>(null)
  // No picture in the feed or the article text: look for the page's og:image, like link previews do
  const [pictureUrl, setPictureUrl] = useState<string | null>(s.imageUrl)
  const [articleState, setArticleState] = useState<'loading' | 'ok' | 'none'>(s.imageUrl || s.itemLink ? 'loading' : 'none')
  const [customImg, setCustomImg] = useState<ImageBitmap | null>(null)
  const [bg, setBg] = useState<Bg>(s.imageUrl || s.itemLink ? { kind: 'article' } : { kind: 'gradient', id: prefs.gradient })
  const [showNote, setShowNote] = useState(!!s.note)
  const [busy, setBusy] = useState(false)
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const fileRef = useRef<HTMLInputElement>(null)
  const touch = useRef<number | null>(null)

  useEffect(() => {
    try {
      localStorage.setItem(PREFS_KEY, JSON.stringify(prefs))
    } catch {
      /* private mode */
    }
  }, [prefs])

  useEffect(() => {
    let cancelled = false
    const find = async () => (s.imageUrl ? s.imageUrl : s.itemLink ? await findOgImage(s.itemLink).catch(() => null) : null)
    find()
      .then((url) => {
        if (!cancelled) setPictureUrl(url)
        return url ? loadShareImage(url) : null
      })
      .then((img) => {
      if (cancelled) return
      setArticleImg(img)
      setArticleState(img ? 'ok' : 'none')
      if (!img) setBg((b) => (b.kind === 'article' ? { kind: 'gradient', id: prefs.gradient } : b))
    })
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [s.imageUrl, s.itemLink])

  const image = prefs.layout === 'color' ? null : bg.kind === 'article' ? articleImg : bg.kind === 'custom' ? customImg : null
  const gradient = bg.kind === 'gradient' ? bg.id : prefs.gradient

  useEffect(() => {
    if (!canvasRef.current) return
    renderShareImage(
      canvasRef.current,
      { text: s.text, note: s.note, title: s.itemTitle, source: s.feedTitle, color: s.color },
      { layout: prefs.layout, size: prefs.size, image, gradient, showNote, showMark: prefs.showMark },
    )
  }, [s, prefs, image, gradient, showNote])

  // Escape closes
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') p.onClose()
      if (e.key === 'ArrowRight') step(1)
      if (e.key === 'ArrowLeft') step(-1)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  })

  const layoutIndex = SHARE_LAYOUTS.findIndex((l) => l.id === prefs.layout)
  const step = (d: number) => {
    const next = SHARE_LAYOUTS[(layoutIndex + d + SHARE_LAYOUTS.length) % SHARE_LAYOUTS.length]
    setPrefs((pr) => ({ ...pr, layout: next.id }))
  }

  const article = { itemTitle: s.itemTitle, feedTitle: s.feedTitle, itemLink: s.itemLink }
  const caption =
    s.quotes && s.quotes.length > 1
      ? allQuotesText(
          s.quotes.map((q) => ({ ...q, ...article })),
          showNote,
        )
      : quoteText({ text: s.text, ...article }, showNote ? s.note : '')
  const fileName = `feeder-highlight-${Date.now()}.png`

  const run = async (fn: () => Promise<void>) => {
    if (busy) return
    setBusy(true)
    try {
      await fn()
    } finally {
      setBusy(false)
    }
  }

  /** Image and the quote + link together, so pasting into a chat gives both where the app allows it. */
  const copyImage = () =>
    run(async () => {
      const png = canvasToPng(canvasRef.current!)
      const text = new Blob([caption], { type: 'text/plain' })
      try {
        await navigator.clipboard.write([new ClipboardItem({ 'image/png': png, 'text/plain': text })])
        p.toast('Copied image with quote and link')
        return
      } catch {
        // Some browsers take only one type per item
      }
      try {
        await navigator.clipboard.write([new ClipboardItem({ 'image/png': png })])
        p.toast('Copied image. Use "Copy text + link" for the caption')
      } catch {
        p.toast("This browser can't copy images. Use Save image instead")
      }
    })

  const saveImage = () =>
    run(async () => {
      const blob = await canvasToPng(canvasRef.current!)
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = fileName
      a.click()
      setTimeout(() => URL.revokeObjectURL(url), 2000)
    })

  const copyCaption = () =>
    run(async () => {
      try {
        await navigator.clipboard.writeText(caption)
        p.toast('Copied quote and link')
      } catch {
        p.toast("Couldn't copy to the clipboard")
      }
    })

  const canShareFiles = typeof navigator.canShare === 'function' && navigator.canShare({ files: [new File([], 'x.png', { type: 'image/png' })] })
  const shareNative = () =>
    run(async () => {
      const blob = await canvasToPng(canvasRef.current!)
      const file = new File([blob], fileName, { type: 'image/png' })
      // The caption also goes on the clipboard: many apps drop the text when sharing an image
      navigator.clipboard?.writeText(caption).catch(() => undefined)
      try {
        await navigator.share({ files: [file], text: caption })
      } catch {
        // cancelled
      }
    })

  const pickFile = async (f: File | undefined) => {
    if (!f) return
    const img = await fileToImage(f)
    if (!img) return p.toast("Couldn't open that picture")
    setCustomImg(img)
    setBg({ kind: 'custom' })
    if (prefs.layout === 'color') setPrefs((pr) => ({ ...pr, layout: 'photo' }))
  }

  const pickBg = (b: Bg) => {
    setBg(b)
    if (b.kind === 'gradient') setPrefs((pr) => ({ ...pr, gradient: b.id }))
    else if (prefs.layout === 'color') setPrefs((pr) => ({ ...pr, layout: 'photo' }))
  }
  const isBg = (b: Bg) => bg.kind === b.kind && (b.kind !== 'gradient' || (bg.kind === 'gradient' && bg.id === b.id))
  const size = SHARE_SIZES[prefs.size]

  return (
    <div className="modal-backdrop share-backdrop" onClick={(e) => e.target === e.currentTarget && p.onClose()}>
      <div className="share-dialog" role="dialog" aria-modal="true" aria-label="Share highlight">
        <div className="share-head">
          <h2>{s.quotes && s.quotes.length > 1 ? `Share ${s.quotes.length} highlights` : 'Share highlight'}</h2>
          <IconButton icon="close" label="Close" onClick={p.onClose} />
        </div>
        <div className="share-body">
          <div
            className="share-stage"
            onTouchStart={(e) => (touch.current = e.touches[0].clientX)}
            onTouchEnd={(e) => {
              const start = touch.current
              touch.current = null
              if (start === null) return
              const dx = e.changedTouches[0].clientX - start
              if (Math.abs(dx) > 50) step(dx < 0 ? 1 : -1)
            }}
          >
            <IconButton icon="prev" label="Previous layout" onClick={() => step(-1)} />
            <canvas ref={canvasRef} className="share-canvas" style={{ aspectRatio: `${size.w} / ${size.h}` }} />
            <IconButton icon="next" label="Next layout" onClick={() => step(1)} />
          </div>
          <div className="share-controls">
            <div className="share-row" role="group" aria-label="Layout">
              {SHARE_LAYOUTS.map((l) => (
                <button key={l.id} type="button" className={`share-chip${prefs.layout === l.id ? ' on' : ''}`} onClick={() => setPrefs((pr) => ({ ...pr, layout: l.id }))}>
                  {l.label}
                </button>
              ))}
            </div>
            <div className="share-label">Background</div>
            <div className="share-row share-bgs">
              {s.imageUrl || s.itemLink ? (
                <button
                  type="button"
                  className={`share-bg${isBg({ kind: 'article' }) ? ' on' : ''}`}
                  disabled={articleState !== 'ok'}
                  onClick={() => pickBg({ kind: 'article' })}
                  title={articleState === 'none' ? "The article's picture couldn't be loaded" : "The article's picture"}
                  style={articleState === 'ok' && pictureUrl ? { backgroundImage: `url(${JSON.stringify(proxiedUrl(pictureUrl))})` } : undefined}
                >
                  <span>{articleState === 'loading' ? 'Loading…' : 'Article'}</span>
                </button>
              ) : null}
              {customImg ? (
                <button type="button" className={`share-bg custom${isBg({ kind: 'custom' }) ? ' on' : ''}`} onClick={() => pickBg({ kind: 'custom' })}>
                  <span>My photo</span>
                </button>
              ) : null}
              {SHARE_GRADIENTS.map((g) => (
                <button
                  key={g.id}
                  type="button"
                  className={`share-bg${isBg({ kind: 'gradient', id: g.id }) ? ' on' : ''}`}
                  style={{ background: `linear-gradient(150deg, ${g.stops[0]}, ${g.stops[2]})` }}
                  onClick={() => pickBg({ kind: 'gradient', id: g.id })}
                >
                  <span>{g.label}</span>
                </button>
              ))}
              <button type="button" className="share-bg add" onClick={() => fileRef.current?.click()} title="Use your own picture">
                <Icon name="add" size={22} />
                <span>Photo</span>
              </button>
              <input ref={fileRef} type="file" accept="image/*" hidden onChange={(e) => pickFile(e.target.files?.[0])} />
            </div>
            <div className="share-row share-toggles">
              {(Object.keys(SHARE_SIZES) as ShareSize[]).map((k) => (
                <button key={k} type="button" className={`share-chip small${prefs.size === k ? ' on' : ''}`} onClick={() => setPrefs((pr) => ({ ...pr, size: k }))}>
                  {SHARE_SIZES[k].label}
                </button>
              ))}
              <label className={s.note ? '' : 'disabled'} title={s.note ? undefined : 'No notes to include'}>
                <input type="checkbox" checked={showNote} disabled={!s.note} onChange={(e) => setShowNote(e.target.checked)} /> Include {s.quotes && s.quotes.length > 1 ? 'notes' : 'my note'}
              </label>
              <label>
                <input type="checkbox" checked={prefs.showMark} onChange={(e) => setPrefs((pr) => ({ ...pr, showMark: e.target.checked }))} /> Feeder mark
              </label>
            </div>
            <div className="share-actions">
              <button type="button" className="share-action primary" onClick={copyImage} disabled={busy}>
                <Icon name="copy" /> Copy image + link
              </button>
              <button type="button" className="share-action" onClick={saveImage} disabled={busy}>
                <Icon name="download" /> Save image
              </button>
              <button type="button" className="share-action" onClick={copyCaption} disabled={busy}>
                <Icon name="link" /> Copy text + link
              </button>
              {canShareFiles ? (
                <button type="button" className="share-action" onClick={shareNative} disabled={busy}>
                  <Icon name="share" /> Share…
                </button>
              ) : null}
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
