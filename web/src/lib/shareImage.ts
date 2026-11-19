import { proxiedUrl } from './api'
import type { HighlightColor } from './db'
import { HIGHLIGHT_INK } from './highlights'

/** How the quote sits on the picture. */
export type ShareLayout = 'photo' | 'blur' | 'split' | 'color'
export const SHARE_LAYOUTS: { id: ShareLayout; label: string }[] = [
  { id: 'photo', label: 'Full photo' },
  { id: 'blur', label: 'Blurred + card' },
  { id: 'split', label: 'Photo on top' },
  { id: 'color', label: 'Color' },
]

export type ShareSize = 'story' | 'post'
export const SHARE_SIZES: Record<ShareSize, { w: number; h: number; label: string }> = {
  story: { w: 1080, h: 1920, label: 'Story 9:16' },
  post: { w: 1080, h: 1350, label: 'Post 4:5' },
}

/** Plain backgrounds, also used when there's no picture. */
export const SHARE_GRADIENTS: { id: string; label: string; stops: [string, string, string] }[] = [
  { id: 'green', label: 'Green', stops: ['#164f36', '#1f6f4a', '#2f8c60'] },
  { id: 'blue', label: 'Blue', stops: ['#1d2b64', '#2f45a0', '#4f6bd8'] },
  { id: 'sunset', label: 'Sunset', stops: ['#ff7a1a', '#f2551d', '#c2185b'] },
  { id: 'night', label: 'Night', stops: ['#111412', '#1f2622', '#2c332e'] },
]

export interface ShareContent {
  text: string
  note: string
  title: string
  source: string
  color: HighlightColor
}

export interface ShareOptions {
  layout: ShareLayout
  size: ShareSize
  /** A picture to put behind the text, or null to use the gradient. */
  image: ImageBitmap | null
  gradient: string
  showNote: boolean
  showMark: boolean
}

/** Loads a picture through the app's backend so the canvas can be exported. */
export async function loadShareImage(url: string): Promise<ImageBitmap | null> {
  try {
    const res = await fetch(proxiedUrl(url))
    if (!res.ok) return null
    const blob = await res.blob()
    if (!blob.type.startsWith('image/')) return null
    return await createImageBitmap(blob)
  } catch {
    return null
  }
}

export async function fileToImage(file: File): Promise<ImageBitmap | null> {
  try {
    return await createImageBitmap(file)
  } catch {
    return null
  }
}

const SERIF = 'Georgia, "Times New Roman", serif'
const SANS = 'system-ui, -apple-system, "Segoe UI", Roboto, sans-serif'

function wrap(ctx: CanvasRenderingContext2D, text: string, maxWidth: number): string[] {
  const lines: string[] = []
  text.split(/\n+/).forEach((para, i) => {
    // A blank line between paragraphs (several highlights in one image)
    if (i > 0 && lines.length) lines.push('')
    let line = ''
    for (const word of para.split(/\s+/).filter(Boolean)) {
      const next = line ? `${line} ${word}` : word
      if (ctx.measureText(next).width <= maxWidth || !line) line = next
      else {
        lines.push(line)
        line = word
      }
    }
    if (line) lines.push(line)
  })
  return lines
}

/** Picks the biggest font size (down to `min`) at which the text fits the box; cuts it with … if it never does. */
function fitText(ctx: CanvasRenderingContext2D, text: string, font: (px: number) => string, width: number, height: number, max: number, min: number, lineHeight = 1.32) {
  for (let px = max; px >= min; px -= 2) {
    ctx.font = font(px)
    const lines = wrap(ctx, text, width)
    if (lines.length * px * lineHeight <= height) return { lines, px, lh: px * lineHeight, font: font(px) }
  }
  ctx.font = font(min)
  const lh = min * lineHeight
  const all = wrap(ctx, text, width)
  const lines = all.slice(0, Math.max(1, Math.floor(height / lh)))
  if (lines.length < all.length) {
    let last = lines[lines.length - 1]
    while (last && ctx.measureText(`${last}…`).width > width) last = last.replace(/\s*\S+$/, '')
    lines[lines.length - 1] = `${last}…`
  }
  return { lines, px: min, lh, font: font(min) }
}

type Fitted = ReturnType<typeof fitText>

function drawCover(ctx: CanvasRenderingContext2D, img: ImageBitmap, x: number, y: number, w: number, h: number) {
  const scale = Math.max(w / img.width, h / img.height)
  const sw = w / scale
  const sh = h / scale
  ctx.drawImage(img, (img.width - sw) / 2, (img.height - sh) / 2, sw, sh, x, y, w, h)
}

/** Blur that works in every browser: shrink the picture a lot, then stretch it back. */
function drawBlurred(ctx: CanvasRenderingContext2D, img: ImageBitmap, w: number, h: number) {
  const small = document.createElement('canvas')
  small.width = 36
  small.height = Math.round((36 * h) / w)
  const sctx = small.getContext('2d')!
  drawCover(sctx, img, 0, 0, small.width, small.height)
  ctx.imageSmoothingEnabled = true
  ctx.imageSmoothingQuality = 'high'
  ctx.drawImage(small, 0, 0, w, h)
}

function fillGradient(ctx: CanvasRenderingContext2D, id: string, x: number, y: number, w: number, h: number) {
  const g = SHARE_GRADIENTS.find((s) => s.id === id) ?? SHARE_GRADIENTS[0]
  const grad = ctx.createLinearGradient(x, y, x + w, y + h)
  grad.addColorStop(0, g.stops[0])
  grad.addColorStop(0.55, g.stops[1])
  grad.addColorStop(1, g.stops[2])
  ctx.fillStyle = grad
  ctx.fillRect(x, y, w, h)
}

function drawMark(ctx: CanvasRenderingContext2D, x: number, y: number, color: string, align: CanvasTextAlign = 'left') {
  ctx.save()
  ctx.font = `800 30px ${SANS}`
  ctx.letterSpacing = '6px'
  ctx.fillStyle = color
  ctx.globalAlpha = 0.85
  ctx.textAlign = align
  ctx.textBaseline = 'top'
  ctx.fillText('FEEDER', x, y)
  ctx.restore()
}

/** Title (bold, up to two lines) with the source under it. Returns its height. Drawn upwards from `bottom`. */
function drawSource(ctx: CanvasRenderingContext2D, c: ShareContent, x: number, bottom: number, width: number, color: string, muted: string) {
  ctx.textBaseline = 'alphabetic'
  ctx.textAlign = 'left'
  ctx.font = `400 32px ${SANS}`
  ctx.fillStyle = muted
  if (c.source) ctx.fillText(c.source, x, bottom, width)
  ctx.font = `700 38px ${SANS}`
  ctx.fillStyle = color
  const lines = wrap(ctx, c.title, width).slice(0, 2)
  let y = bottom - (c.source ? 48 : 0)
  for (let i = lines.length - 1; i >= 0; i--) {
    ctx.fillText(lines[i], x, y, width)
    y -= 48
  }
  return bottom - y
}

/** Draws the quote lines from `top`. `stroke` puts a highlighter stroke behind the lower part of each line. */
function drawLines(ctx: CanvasRenderingContext2D, t: Fitted, x: number, top: number, fill: string, stroke?: string, align: CanvasTextAlign = 'left') {
  const { lines, lh, px } = t
  ctx.font = t.font
  ctx.textBaseline = 'alphabetic'
  ctx.textAlign = align
  lines.forEach((line, i) => {
    const base = top + i * lh + px
    if (stroke && line) {
      const w = ctx.measureText(line).width
      const left = align === 'center' ? x - w / 2 : x
      ctx.save()
      ctx.fillStyle = stroke
      ctx.globalAlpha = 0.6
      ctx.fillRect(left - 4, base - px * 0.32, w + 8, px * 0.42)
      ctx.restore()
    }
    ctx.fillStyle = fill
    ctx.fillText(line, x, base)
  })
}

export function renderShareImage(canvas: HTMLCanvasElement, c: ShareContent, o: ShareOptions) {
  const { w: W, h: H } = SHARE_SIZES[o.size]
  canvas.width = W
  canvas.height = H
  const ctx = canvas.getContext('2d')!
  ctx.clearRect(0, 0, W, H)
  const pad = 88
  const ink = HIGHLIGHT_INK[c.color]
  const quote = c.text
    .split('\n')
    .map((t) => `“${t}”`)
    .join('\n')
  const note = o.showNote && c.note ? c.note : ''

  if (o.layout === 'photo') {
    if (o.image) drawCover(ctx, o.image, 0, 0, W, H)
    else fillGradient(ctx, o.gradient, 0, 0, W, H)
    const fade = ctx.createLinearGradient(0, H * 0.25, 0, H)
    fade.addColorStop(0, 'rgba(0,0,0,0)')
    fade.addColorStop(0.55, 'rgba(0,0,0,0.62)')
    fade.addColorStop(1, 'rgba(0,0,0,0.85)')
    ctx.fillStyle = fade
    ctx.fillRect(0, 0, W, H)
    if (o.showMark) drawMark(ctx, pad, pad, '#fff')
    const srcH = drawSource(ctx, c, pad, H - pad, W - pad * 2, '#fff', 'rgba(255,255,255,0.8)')
    let bottom = H - pad - srcH - 40
    if (note) {
      const n = fitText(ctx, note, (px) => `italic 400 ${px}px ${SANS}`, W - pad * 2, 200, 36, 28, 1.35)
      bottom -= n.lines.length * n.lh
      drawLines(ctx, n, pad, bottom, 'rgba(255,255,255,0.88)')
      bottom -= 32
    }
    const t = fitText(ctx, quote, (px) => `700 ${px}px ${SERIF}`, W - pad * 2, bottom - H * 0.2, 76, 30)
    const top = bottom - t.lines.length * t.lh
    drawLines(ctx, t, pad, top, '#fff', ink)
    return
  }

  if (o.layout === 'blur') {
    if (o.image) drawBlurred(ctx, o.image, W, H)
    else fillGradient(ctx, o.gradient, 0, 0, W, H)
    ctx.fillStyle = 'rgba(0,0,0,0.12)'
    ctx.fillRect(0, 0, W, H)
    const cardX = 72
    const cardW = W - cardX * 2
    const inner = cardW - 120
    ctx.font = `600 60px ${SERIF}`
    const t = fitText(ctx, c.text, (px) => `600 ${px}px ${SERIF}`, inner - 28, H * 0.55, 64, 26, 1.4)
    ctx.font = `400 34px ${SANS}`
    const n = note ? fitText(ctx, note, (px) => `italic 400 ${px}px ${SANS}`, inner, 220, 34, 26, 1.35) : null
    ctx.font = `700 38px ${SANS}`
    const titleLines = Math.min(2, wrap(ctx, c.title, inner).length)
    const srcH = titleLines * 48 + (c.source ? 48 : 0)
    const quoteH = t.lines.length * t.lh
    const cardH = 60 + quoteH + (n ? 36 + n.lines.length * n.lh : 0) + 48 + srcH + 50
    const cardY = Math.max(140, (H - cardH) / 2)
    ctx.save()
    ctx.shadowColor = 'rgba(0,0,0,0.28)'
    ctx.shadowBlur = 60
    ctx.shadowOffsetY = 20
    ctx.fillStyle = 'rgba(255,255,255,0.93)'
    ctx.beginPath()
    ctx.roundRect(cardX, cardY, cardW, cardH, 44)
    ctx.fill()
    ctx.restore()
    const qx = cardX + 60
    let y = cardY + 60
    ctx.fillStyle = ink
    ctx.fillRect(qx, y + 6, 10, quoteH - 12)
    drawLines(ctx, t, qx + 38, y - t.lh * 0.12, '#1b1d1c')
    y += quoteH
    if (n) {
      y += 36
      drawLines(ctx, n, qx, y - n.lh * 0.15, '#4f5a52')
      y += n.lines.length * n.lh
    }
    drawSource(ctx, c, qx, y + 48 + srcH - 6, inner, '#1b1d1c', '#4f5a52')
    if (o.showMark) drawMark(ctx, W / 2, H - pad - 30, '#fff', 'center')
    return
  }

  if (o.layout === 'split') {
    const topH = Math.round(H * 0.42)
    ctx.fillStyle = '#fffdf5'
    ctx.fillRect(0, 0, W, H)
    if (o.image) drawCover(ctx, o.image, 0, 0, W, topH)
    else fillGradient(ctx, o.gradient, 0, 0, W, topH)
    ctx.font = `700 150px ${SERIF}`
    ctx.fillStyle = ink
    ctx.textBaseline = 'top'
    ctx.textAlign = 'left'
    ctx.fillText('“', pad - 6, topH + 40)
    const srcH = drawSource(ctx, c, pad, H - pad, W - pad * 2 - (o.showMark ? 200 : 0), '#1d1d1b', '#6c6a60')
    if (o.showMark) drawMark(ctx, W - pad, H - pad - 26, '#1d1d1b', 'right')
    const top = topH + 170
    let avail = H - pad - srcH - 60 - top
    let n = null
    if (note) {
      n = fitText(ctx, note, (px) => `italic 400 ${px}px ${SANS}`, W - pad * 2, 200, 34, 26, 1.35)
      avail -= n.lines.length * n.lh + 32
    }
    const t = fitText(ctx, c.text, (px) => `500 ${px}px ${SERIF}`, W - pad * 2, avail, 62, 26, 1.42)
    drawLines(ctx, t, pad, top, '#1d1d1b')
    if (n) drawLines(ctx, n, pad, top + t.lines.length * t.lh + 32, '#6c6a60')
    return
  }

  // color
  fillGradient(ctx, o.gradient, 0, 0, W, H)
  if (o.showMark) drawMark(ctx, pad, pad, '#fff')
  const srcH = drawSource(ctx, c, pad, H - pad, W - pad * 2, '#fff', 'rgba(255,255,255,0.8)')
  const room = H - pad * 2 - 120 - srcH - 80
  const n = note ? fitText(ctx, note, (px) => `italic 400 ${px}px ${SANS}`, W - pad * 2, 200, 36, 28, 1.35) : null
  const noteH = n ? n.lines.length * n.lh + 36 : 0
  const t = fitText(ctx, quote, (px) => `700 ${px}px ${SANS}`, W - pad * 2, room - noteH, 80, 30, 1.3)
  const blockH = t.lines.length * t.lh + noteH
  const top = pad + 120 + (room - blockH) / 2
  drawLines(ctx, t, pad, top, '#fff')
  if (n) drawLines(ctx, n, pad, top + t.lines.length * t.lh + 36, 'rgba(255,255,255,0.88)')
}

export const canvasToPng = (canvas: HTMLCanvasElement) =>
  new Promise<Blob>((resolve, reject) => canvas.toBlob((b) => (b ? resolve(b) : reject(new Error('Could not make the image'))), 'image/png'))
