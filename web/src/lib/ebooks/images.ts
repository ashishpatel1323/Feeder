/**
 * Pictures in book pages (figures, diagrams, maps) that the reader can open full screen and zoom,
 * like Kindle and Kobo. Shared by the web Ebooks tab and the Android reader page.
 *
 * A tap on a picture opens it (taps in a thin strip at each edge still turn pages), and so does
 * a long press anywhere on it.
 */

/** Taps this close to the page's left or right edge (share of its width) turn pages, even on a picture. */
export const IMAGE_EDGE = 0.1

/** Smaller than this (CSS px, both sides) is a decoration or an inline icon, not worth zooming. */
const MIN_SIDE = 48
/** Vector pictures are drawn this big on their longest side, so they stay sharp when zoomed. */
const VECTOR_SIDE = 2400
/** Our own marks drawn into pages: handwriting, margin sketches. */
const OWN = '.feeder-ink, .feeder-ink-live, .feeder-ink-mark, .feeder-sketch'

/** The picture under a tap, or null. */
export function bookImageAt(target: EventTarget | null): Element | null {
  const t = target as Element | null
  if (!t?.closest || t.closest(OWN)) return null
  const el = t.closest('img, svg, image')
  if (!el) return null
  // An <image> inside an <svg> wrapper (common for covers and full-page figures): use the wrapper
  const pic = el.localName === 'image' ? (el.closest('svg') ?? el) : el.localName === 'svg' ? outermostSvg(el) : el
  if (pic.closest(OWN)) return null
  const r = pic.getBoundingClientRect()
  if (r.width < MIN_SIDE && r.height < MIN_SIDE) return null
  return pic
}

function outermostSvg(el: Element): Element {
  let top = el
  for (let p = el.parentElement?.closest('svg'); p; p = p.parentElement?.closest('svg')) top = p
  return top
}

/** A caption for the viewer: the figure's caption, or the picture's alt text or title. */
export function bookImageLabel(el: Element): string {
  const cap = el.closest('figure')?.querySelector('figcaption')?.textContent
  const alt = el.getAttribute('alt') ?? el.getAttribute('aria-label') ?? el.querySelector('title')?.textContent
  return (cap || alt || '').replace(/\s+/g, ' ').trim().slice(0, 200)
}

/**
 * The picture as a file: the book's own image when it's a plain raster image, otherwise
 * (SVG files and drawings) drawn into a PNG big enough to zoom into.
 */
export async function bookImageBlob(el: Element): Promise<Blob | null> {
  if (el.localName === 'img') {
    const img = el as HTMLImageElement
    const src = img.currentSrc || img.src
    if (!src) return null
    const blob = await fetch(src).then((r) => r.blob())
    if (/^image\/(png|jpe?g|gif|webp|avif|bmp)$/.test(blob.type)) return blob
    // SVG files, or an unknown type: draw it
    return rasterize(URL.createObjectURL(blob), img.naturalWidth || img.width, img.naturalHeight || img.height, true)
  }
  const svg = el as SVGSVGElement
  // An <svg> that only wraps one <image> (a figure scaled to the page): use that image itself
  const images = svg.querySelectorAll('image')
  const drawn = svg.querySelectorAll('path, rect, circle, ellipse, line, polyline, polygon, text')
  if (images.length === 1 && drawn.length === 0) {
    const href = images[0].getAttribute('href') ?? images[0].getAttributeNS('http://www.w3.org/1999/xlink', 'href')
    if (href) {
      const url = new URL(href, svg.ownerDocument.baseURI).href
      const blob = await fetch(url).then((r) => r.blob())
      if (/^image\/(png|jpe?g|gif|webp|avif|bmp)$/.test(blob.type)) return blob
    }
  }
  return rasterize(await svgUrl(svg), svg.getBoundingClientRect().width, svg.getBoundingClientRect().height, true)
}

/** An inline drawing as an SVG file, with its images inlined (an SVG image can't load other files). */
async function svgUrl(svg: SVGSVGElement): Promise<string> {
  const copy = svg.cloneNode(true) as SVGSVGElement
  inlineStyles(svg, copy)
  const r = svg.getBoundingClientRect()
  copy.setAttribute('xmlns', 'http://www.w3.org/2000/svg')
  copy.setAttribute('xmlns:xlink', 'http://www.w3.org/1999/xlink')
  if (!copy.getAttribute('viewBox')) copy.setAttribute('viewBox', `0 0 ${r.width} ${r.height}`)
  copy.setAttribute('width', String(r.width))
  copy.setAttribute('height', String(r.height))
  // Text and lines that follow the page's colour show black on the white the drawing is put on
  copy.style.color = '#000'
  for (const im of Array.from(copy.querySelectorAll('image'))) {
    const href = im.getAttribute('href') ?? im.getAttributeNS('http://www.w3.org/1999/xlink', 'href')
    if (!href || href.startsWith('data:')) continue
    try {
      const blob = await fetch(new URL(href, svg.ownerDocument.baseURI).href).then((x) => x.blob())
      const data = await new Promise<string>((ok, fail) => {
        const fr = new FileReader()
        fr.onload = () => ok(String(fr.result))
        fr.onerror = () => fail(fr.error)
        fr.readAsDataURL(blob)
      })
      im.setAttribute('href', data)
      im.removeAttributeNS('http://www.w3.org/1999/xlink', 'href')
    } catch {
      // leave it out
    }
  }
  const text = new XMLSerializer().serializeToString(copy)
  return URL.createObjectURL(new Blob([text], { type: 'image/svg+xml' }))
}

/** Drawing properties a chapter's stylesheet may set; the standalone copy has no stylesheet. */
const SVG_PROPS = ['fill', 'fill-opacity', 'fill-rule', 'stroke', 'stroke-width', 'stroke-opacity', 'stroke-dasharray', 'stroke-linecap', 'stroke-linejoin', 'opacity', 'visibility', 'display', 'font-family', 'font-size', 'font-weight', 'font-style', 'text-anchor', 'dominant-baseline', 'marker-start', 'marker-mid', 'marker-end']

/** Copies the computed drawing styles of every element onto the copy, so the figure looks the same on its own. */
function inlineStyles(from: Element, to: Element) {
  const cs = from.ownerDocument.defaultView?.getComputedStyle(from)
  // The page is another frame: its elements fail instanceof checks against this window's classes
  if (cs && (to as SVGElement).style) {
    for (const p of SVG_PROPS) {
      const v = cs.getPropertyValue(p)
      if (v) (to as SVGElement).style.setProperty(p, v)
    }
  }
  const a = from.children
  const b = to.children
  for (let i = 0; i < a.length && i < b.length; i++) inlineStyles(a[i], b[i])
}

/** Draws an image into a PNG, vector images scaled up to VECTOR_SIDE on their longest side. */
async function rasterize(url: string, w: number, h: number, revoke: boolean): Promise<Blob | null> {
  try {
    const img = new Image()
    img.decoding = 'async'
    img.src = url
    await img.decode()
    const iw = img.naturalWidth || w || 800
    const ih = img.naturalHeight || h || 600
    const scale = Math.max(1, VECTOR_SIDE / Math.max(iw, ih))
    const canvas = document.createElement('canvas')
    canvas.width = Math.round(iw * scale)
    canvas.height = Math.round(ih * scale)
    const ctx = canvas.getContext('2d')!
    // Drawings often have no background: put them on white, like the page they came from
    ctx.fillStyle = '#ffffff'
    ctx.fillRect(0, 0, canvas.width, canvas.height)
    ctx.drawImage(img, 0, 0, canvas.width, canvas.height)
    return await new Promise<Blob | null>((ok) => canvas.toBlob(ok, 'image/png'))
  } catch {
    return null
  } finally {
    if (revoke) URL.revokeObjectURL(url)
  }
}

/**
 * Opens a picture on a long press (touch): listens for the context menu a long press brings up
 * on phones and tablets. A mouse right-click keeps the browser's own menu.
 */
export function attachImageLongPress(doc: Document, open: (el: Element) => void) {
  let touch = false
  doc.addEventListener('pointerdown', (e) => (touch = e.pointerType === 'touch'), { capture: true })
  doc.addEventListener('contextmenu', (e) => {
    if (!touch) return
    const el = bookImageAt(e.target)
    if (!el) return
    e.preventDefault()
    doc.getSelection()?.removeAllRanges()
    open(el)
  })
}
