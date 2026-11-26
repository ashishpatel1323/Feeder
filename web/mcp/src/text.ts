/** HTML → blocks of plain text (headings and paragraphs), and blocks → chunks. */
import { parseHtml } from './dom.js'

export interface Block {
  /** 0 = paragraph, 1–6 = heading level */
  level: number
  text: string
}

const SKIP = new Set(['SCRIPT', 'STYLE', 'NOSCRIPT', 'TEMPLATE', 'SVG', 'IFRAME', 'NAV', 'BUTTON', 'FORM', 'HEAD'])
const BLOCK = new Set([
  'P', 'DIV', 'SECTION', 'ARTICLE', 'MAIN', 'HEADER', 'FOOTER', 'ASIDE', 'BLOCKQUOTE', 'PRE', 'UL', 'OL', 'LI', 'TABLE', 'TR',
  'FIGURE', 'FIGCAPTION', 'DL', 'DT', 'DD', 'BR', 'HR', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6', 'BODY', 'HTML',
])

const clean = (s: string) => s.replace(/[ \t\f\v\r\n ]+/g, ' ').trim()

export function htmlToBlocks(html: string): Block[] {
  const doc = parseHtml(`<body>${html}</body>`)
  return elementToBlocks(doc.body)
}

export function elementToBlocks(root: Element): Block[] {
  const out: Block[] = []
  let buf = ''
  let prefix = ''
  const flush = () => {
    const t = clean(buf)
    if (t) out.push({ level: 0, text: prefix + t })
    buf = ''
    prefix = ''
  }
  const walk = (n: Node) => {
    if (n.nodeType === 3) {
      buf += n.nodeValue ?? ''
      return
    }
    if (n.nodeType !== 1) return
    const el = n as Element
    const tag = el.tagName.toUpperCase()
    if (SKIP.has(tag)) return
    if (tag === 'IMG') {
      const alt = clean(el.getAttribute('alt') ?? '')
      if (alt.length > 3) buf += ` [image: ${alt}] `
      return
    }
    const m = /^H([1-6])$/.exec(tag)
    if (m) {
      flush()
      const t = clean(el.textContent ?? '')
      if (t) out.push({ level: Number(m[1]), text: t })
      return
    }
    if (!BLOCK.has(tag)) {
      for (const c of Array.from(el.childNodes)) walk(c)
      return
    }
    flush()
    if (tag === 'LI') prefix = '- '
    else if (tag === 'BLOCKQUOTE') prefix = '> '
    for (const c of Array.from(el.childNodes)) walk(c)
    flush()
  }
  walk(root)
  flush()
  return out
}

export function textToBlocks(text: string): Block[] {
  return text
    .split(/\n\s*\n/)
    .map((p) => clean(p))
    .filter(Boolean)
    .map((t) => ({ level: 0, text: t }))
}

export const estTokens = (s: string) => Math.ceil(s.length / 4)

export interface Chunk {
  seq: number
  heading: string
  text: string
  tokens: number
  locator: Record<string, unknown>
}

const TARGET = 500
const MAX = 800

/** Splits a long paragraph at sentence ends so no chunk passes MAX. */
function splitLong(text: string): string[] {
  if (estTokens(text) <= MAX) return [text]
  const sentences = text.match(/[^.!?]+[.!?]+["')\]]*\s*|[^.!?]+$/g) ?? [text]
  const out: string[] = []
  let cur = ''
  for (const s of sentences) {
    if (cur && estTokens(cur + s) > TARGET) {
      out.push(cur.trim())
      cur = ''
    }
    if (estTokens(s) > MAX) {
      // No sentence breaks at all: cut by length
      for (let i = 0; i < s.length; i += MAX * 4) out.push(s.slice(i, i + MAX * 4).trim())
      continue
    }
    cur += s
  }
  if (cur.trim()) out.push(cur.trim())
  return out
}

/**
 * Groups blocks into ~500-token chunks. Headings start a new chunk and are remembered as the
 * chunk's heading path; paragraphs are never split mid-sentence.
 */
export function chunkBlocks(blocks: Block[], baseLocator: Record<string, unknown> = {}): Chunk[] {
  const chunks: Chunk[] = []
  const path: string[] = []
  let parts: string[] = []
  let tokens = 0
  let paraStart = 0
  let para = 0
  const heading = () => path.filter(Boolean).join(' › ')
  const emit = () => {
    if (!parts.length) return
    const text = parts.join('\n\n')
    chunks.push({ seq: chunks.length, heading: heading(), text, tokens: estTokens(text), locator: { ...baseLocator, para_start: paraStart, para_end: para - 1 } })
    parts = []
    tokens = 0
    paraStart = para
  }
  for (const b of blocks) {
    if (b.level > 0) {
      emit()
      path.length = Math.min(path.length, b.level - 1)
      while (path.length < b.level - 1) path.push('')
      path.push(b.text)
      continue
    }
    for (const piece of splitLong(b.text)) {
      const t = estTokens(piece)
      if (tokens && tokens + t > TARGET) emit()
      parts.push(piece)
      tokens += t
    }
    para++
  }
  emit()
  return chunks
}

/** Timed transcript cues → chunks of about 2–3 minutes, cut where a speaker changes or at cue ends. */
export function chunkCues(cues: { startMs: number; endMs?: number | null; text: string; speaker?: string }[], baseLocator: Record<string, unknown> = {}): Chunk[] {
  const chunks: Chunk[] = []
  let lines: string[] = []
  let start = 0
  let end = 0
  let tokens = 0
  let lastSpeaker: string | undefined
  const emit = () => {
    if (!lines.length) return
    const text = lines.join(' ').replace(/\s+/g, ' ').trim()
    chunks.push({
      seq: chunks.length,
      heading: `[${clock(start)}–${clock(end)}]`,
      text,
      tokens: estTokens(text),
      locator: { ...baseLocator, start_ms: start, end_ms: end },
    })
    lines = []
    tokens = 0
  }
  for (const c of cues) {
    const t = estTokens(c.text)
    const speakerChange = c.speaker && c.speaker !== lastSpeaker
    if (tokens && (tokens + t > MAX || (tokens > TARGET * 0.6 && speakerChange) || tokens + t > TARGET)) emit()
    if (!lines.length) start = c.startMs
    lines.push(speakerChange ? `${c.speaker}: ${c.text}` : c.text)
    lastSpeaker = c.speaker ?? lastSpeaker
    end = c.endMs ?? c.startMs
    tokens += t
  }
  emit()
  return chunks
}

export function clock(ms: number): string {
  const s = Math.floor(ms / 1000)
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = s % 60
  return h ? `${h}:${String(m).padStart(2, '0')}:${String(sec).padStart(2, '0')}` : `${m}:${String(sec).padStart(2, '0')}`
}
