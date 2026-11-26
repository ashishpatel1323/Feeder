/**
 * Ebooks from Dropbox /Ebooks. A book's id is its Dropbox content hash, the same id Feeder uses
 * for reading positions ("bookpos") and ebook highlights (item "ebook:\n<id>").
 *
 * Reads EPUB (table of contents from nav or NCX) and PDF (outline, else 20-page parts). Books are
 * downloaded and chunked the first time they're asked for, then kept.
 */
import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import JSZip from 'jszip'
import { BOOKS_DIR } from './config.js'
import type { Db } from './db.js'
import { parseHtml } from './dom.js'
import { Dropbox, EBOOKS_FOLDER } from './dropbox.js'
import { chunksOf, hasDocument, putDocument, type StoredChunk } from './store.js'
import { chunkBlocks, elementToBlocks, estTokens, type Block } from './text.js'

export const BOOK_EXTENSIONS = ['epub', 'pdf', 'mobi', 'azw3', 'azw', 'kf8', 'fb2', 'fbz', 'cbz']
export const READABLE = new Set(['epub', 'pdf'])
export const BOOK_ITEM_PREFIX = 'ebook:\n'

export function extOf(name: string): string {
  const n = name.toLowerCase()
  if (n.endsWith('.fb2.zip')) return 'fbz'
  return n.match(/\.([a-z0-9]+)$/)?.[1] ?? ''
}

export const titleFromName = (name: string) =>
  name
    .replace(/\.fb2\.zip$/i, '')
    .replace(/\.[a-z0-9]+$/i, '')
    .replace(/_/g, ' ')
    .trim() || 'Untitled'

export interface BookRow {
  id: string
  path: string
  name: string
  size: number
  title: string | null
  author: string | null
  local_path: string | null
  toc_json: string | null
}

export type ChapterKind = 'front' | 'chapter' | 'back'

export interface TocEntry {
  index: number
  label: string
  kind: ChapterKind
  /** 1-based among kind=chapter entries */
  chapterNo?: number
  tokens: number
  /** Part/section this chapter sits under, when the book groups chapters */
  part?: string
}

export interface BookContent {
  title: string
  author: string
  toc: TocEntry[]
  /** Text of each TOC entry, same order */
  chapters: Block[][]
}

/** Lists /Ebooks in Dropbox into the files table. */
export async function refreshBooks(db: Db, dbx: Dropbox): Promise<number> {
  const files = (await dbx.listFolder(EBOOKS_FOLDER)).filter((f) => BOOK_EXTENSIONS.includes(extOf(f.name)))
  const now = Date.now()
  db.transaction(() => {
    const up = db.prepare(
      `INSERT INTO files (id, kind, path, name, size, title, seen_at) VALUES (?, 'book', ?, ?, ?, ?, ?)
       ON CONFLICT(id) DO UPDATE SET path = excluded.path, name = excluded.name, size = excluded.size, seen_at = excluded.seen_at`,
    )
    for (const f of files) up.run(f.hash, f.path, f.name, f.size, titleFromName(f.name), now)
    db.prepare(`DELETE FROM files WHERE kind = 'book' AND seen_at < ?`).run(now)
  })()
  return files.length
}

export function listBooks(db: Db): BookRow[] {
  return db.prepare(`SELECT id, path, name, size, title, author, local_path, toc_json FROM files WHERE kind = 'book' ORDER BY title`).all() as BookRow[]
}

async function localCopy(db: Db, book: BookRow): Promise<string> {
  if (book.local_path && existsSync(book.local_path)) return book.local_path
  const dbx = Dropbox.fromDb(db)
  if (!dbx) throw new Error('Not signed in to Dropbox in Feeder (Settings › Ebooks), so books can’t be read.')
  const dest = join(BOOKS_DIR, `${book.id}.${extOf(book.name) || 'epub'}`)
  if (!existsSync(dest)) await dbx.downloadTo(book.path, dest)
  db.prepare('UPDATE files SET local_path = ? WHERE id = ?').run(dest, book.id)
  return dest
}

/** Opens (downloads, parses and chunks, once) a book. Returns its TOC and chunk ids per chapter. */
export async function openBook(db: Db, book: BookRow): Promise<{ title: string; author: string; toc: TocEntry[] }> {
  if (book.toc_json && hasDocument(db, `book:${book.id}:1`)) {
    const t = JSON.parse(book.toc_json) as { title: string; author: string; toc: TocEntry[] }
    // Re-run the classification so books opened by an older version get the current numbering
    const kinds = classify(t.toc.map((e) => e.label), [])
    return { ...t, toc: numberToc(t.toc.map((e, i) => ({ ...e, kind: kinds[i] }))) }
  }
  const ext = extOf(book.name)
  if (!READABLE.has(ext)) throw new Error(`Can’t read .${ext} books yet (EPUB and PDF only).`)
  const path = await localCopy(db, book)
  const data = readFileSync(path)
  const content = ext === 'pdf' ? await readPdf(data, titleFromName(book.name)) : await readEpub(data, titleFromName(book.name))
  db.transaction(() => {
    content.toc.forEach((e, n) => {
      const chunks = chunkBlocks(content.chapters[n], { book: book.id, chapter_index: e.index, label: e.label })
      const head = e.chapterNo ? `${content.title} › Chapter ${e.chapterNo}: ${e.label}` : `${content.title} › ${e.label}`
      putDocument(db, `book:${book.id}:${e.index}`, 'book', book.id, head, chunks)
    })
    db.prepare('UPDATE files SET title = ?, author = ?, toc_json = ? WHERE id = ?').run(
      content.title,
      content.author,
      JSON.stringify({ title: content.title, author: content.author, toc: content.toc }),
      book.id,
    )
  })()
  return { title: content.title, author: content.author, toc: content.toc }
}

export function chapterChunks(db: Db, bookId: string, index: number): StoredChunk[] {
  return chunksOf(db, `book:${bookId}:${index}`)
}

// ---------------------------------------------------------------------------------------------
// Front matter / back matter

const FRONT = /^(cover|title( page)?|half[- ]title|copyright|contents|table of contents|toc|dedication|epigraph|foreword|preface|prologue note|acknowledg\w*|about (this|the) (book|edition)|also by|praise for|frontispiece|map|maps|list of (illustrations|figures|tables)|introduction to the .* edition|note on the text|imprint|colophon|landmarks|start)\b/i
const BACK = /^(acknowledg\w*|notes|endnotes|bibliography|references|index|about the author|appendix|glossary|further reading|also by|copyright|credits|discussion questions|reading group|sources|permissions|colophon|footnotes)\b/i

export function classify(labels: string[], hrefs: string[]): ChapterKind[] {
  const kinds: ChapterKind[] = labels.map(() => 'chapter')
  // Front matter: leading entries that look like it
  for (let i = 0; i < labels.length; i++) {
    if (FRONT.test(labels[i].trim()) || /(cover|titlepage|copyright|toc|nav|dedication)/i.test(hrefs[i] ?? '')) kinds[i] = 'front'
    else break
  }
  // Back matter: trailing entries that look like it
  for (let i = labels.length - 1; i >= 0 && kinds[i] !== 'front'; i--) {
    if (BACK.test(labels[i].trim())) kinds[i] = 'back'
    else break
  }
  // A book of only front/back entries: call them all chapters rather than none
  if (!kinds.includes('chapter')) return labels.map(() => 'chapter')
  return kinds
}

function finishToc(raw: { label: string; href: string; part?: string }[], chapters: Block[][]): TocEntry[] {
  const kinds = classify(
    raw.map((r) => r.label),
    raw.map((r) => r.href),
  )
  return numberToc(
    raw.map((r, i) => ({
      index: i + 1,
      label: r.label,
      kind: kinds[i],
      tokens: chapters[i].reduce((s, b) => s + estTokens(b.text), 0),
      ...(r.part ? { part: r.part } : {}),
    })),
  )
}

const WORDS = ['one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight', 'nine', 'ten', 'eleven', 'twelve', 'thirteen', 'fourteen', 'fifteen', 'sixteen', 'seventeen', 'eighteen', 'nineteen', 'twenty']

/** The number a label gives itself: "1. Title", "Chapter 3", "CHAPTER ONE: …", "12 – Title". */
export function labelNumber(label: string): number | null {
  const t = label.trim()
  const m = t.match(/^(?:chapter|ch\.?)\s+(\d+|[a-z]+)\b/i) ?? t.match(/^(\d+)\s*[.:)\-–—]?\s+\S/) ?? t.match(/^(\d+)$/)
  if (!m) return null
  const v = m[1].toLowerCase()
  if (/^\d+$/.test(v)) return Number(v)
  const w = WORDS.indexOf(v)
  return w >= 0 ? w + 1 : null
}

/**
 * Chapter numbers. When the book numbers its own chapters ("1. Title", "Chapter 1"), those numbers
 * are used and unnumbered entries in between (Introduction, interludes) get none. Otherwise every
 * entry that isn't front or back matter is numbered in order.
 */
export function numberToc(toc: TocEntry[]): TocEntry[] {
  const own = toc.map((e) => (e.kind === 'chapter' ? labelNumber(e.label) : null))
  const numbered = own.filter((n) => n != null).length
  let no = 0
  return toc.map((e, i) => {
    const { chapterNo: _drop, ...rest } = e
    if (e.kind !== 'chapter') return rest
    if (numbered >= 3) return own[i] != null ? { ...rest, chapterNo: own[i]! } : rest
    return { ...rest, chapterNo: ++no }
  })
}

// ---------------------------------------------------------------------------------------------
// EPUB

interface NavPoint {
  label: string
  href: string
  children: NavPoint[]
}

const xml = (text: string) => new DOMParser().parseFromString(text, 'application/xml')
const byLocal = (root: Document | Element, name: string) => Array.from(root.getElementsByTagName('*')).filter((e) => e.localName === name)
const childrenByLocal = (el: Element, name: string) => Array.from(el.children).filter((e) => e.localName === name)

function resolvePath(base: string, href: string): string {
  const dir = base.includes('/') ? base.slice(0, base.lastIndexOf('/') + 1) : ''
  const parts = (dir + decodeURIComponent(href.split('#')[0])).split('/')
  const out: string[] = []
  for (const p of parts) {
    if (p === '..') out.pop()
    else if (p !== '.' && p !== '') out.push(p)
  }
  return out.join('/')
}

export async function readEpub(data: Buffer | Uint8Array, fallbackTitle: string): Promise<BookContent> {
  const zip = await JSZip.loadAsync(data)
  const read = async (p: string) => (await zip.file(p)?.async('string')) ?? null
  const container = await read('META-INF/container.xml')
  const opfPath = container ? (byLocal(xml(container), 'rootfile')[0]?.getAttribute('full-path') ?? '') : ''
  const opfText = opfPath ? await read(opfPath) : null
  if (!opfText) throw new Error('Not a readable EPUB (no package document)')
  const opf = xml(opfText)
  const title = byLocal(opf, 'title')[0]?.textContent?.trim() || fallbackTitle
  const author = byLocal(opf, 'creator')
    .map((e) => e.textContent?.trim())
    .filter(Boolean)
    .join(', ')
  const manifest = new Map<string, { href: string; type: string; props: string }>()
  for (const it of byLocal(opf, 'item'))
    manifest.set(it.getAttribute('id') ?? '', {
      href: resolvePath(opfPath, it.getAttribute('href') ?? ''),
      type: it.getAttribute('media-type') ?? '',
      props: it.getAttribute('properties') ?? '',
    })
  const spineEl = byLocal(opf, 'spine')[0]
  const spine = byLocal(opf, 'itemref')
    .map((r) => manifest.get(r.getAttribute('idref') ?? '')?.href)
    .filter((h): h is string => !!h)

  // Table of contents: EPUB 3 nav, else EPUB 2 NCX
  let nav: NavPoint[] = []
  const navItem = [...manifest.values()].find((m) => m.props.split(/\s+/).includes('nav'))
  if (navItem) {
    const text = await read(navItem.href)
    if (text) {
      const doc = parseHtml(text)
      const tocNav = Array.from(doc.querySelectorAll('nav')).find((n) => (n.getAttribute('epub:type') ?? n.getAttribute('type') ?? '').includes('toc')) ?? doc.querySelector('nav')
      const ol = tocNav?.querySelector('ol')
      const walk = (list: Element): NavPoint[] =>
        Array.from(list.children)
          .filter((li) => li.tagName === 'LI')
          .map((li) => {
            const a = li.querySelector(':scope > a, :scope > span')
            const sub = li.querySelector(':scope > ol')
            return {
              label: (a?.textContent ?? '').replace(/\s+/g, ' ').trim(),
              href: a?.getAttribute('href') ? resolvePath(navItem.href, a.getAttribute('href')!) + hashOf(a.getAttribute('href')!) : '',
              children: sub ? walk(sub) : [],
            }
          })
      if (ol) nav = walk(ol)
    }
  }
  if (!nav.length) {
    const ncxId = spineEl?.getAttribute('toc')
    const ncxItem = (ncxId && manifest.get(ncxId)) || [...manifest.values()].find((m) => m.type === 'application/x-dtbncx+xml')
    const text = ncxItem ? await read(ncxItem.href) : null
    if (text && ncxItem) {
      const doc = xml(text)
      const map = byLocal(doc, 'navMap')[0]
      const walk = (el: Element): NavPoint[] =>
        childrenByLocal(el, 'navPoint').map((np) => {
          const label = byLocal(childrenByLocal(np, 'navLabel')[0] ?? np, 'text')[0]?.textContent?.trim() ?? ''
          const src = childrenByLocal(np, 'content')[0]?.getAttribute('src') ?? ''
          return { label, href: src ? resolvePath(ncxItem.href, src) + hashOf(src) : '', children: walk(np) }
        })
      if (map) nav = walk(map)
    }
  }

  // Which level is "chapters": a book with a handful of Parts holding the chapters uses the level below
  let entries: { label: string; href: string; part?: string }[] = nav.filter((n) => n.href).map((n) => ({ label: n.label, href: n.href }))
  const nested = nav.filter((n) => n.children.length)
  if (nav.length <= 6 && nested.length >= 2) {
    entries = nav.flatMap((n) =>
      n.children.length ? n.children.filter((c) => c.href).map((c) => ({ label: c.label, href: c.href, part: n.label })) : n.href ? [{ label: n.label, href: n.href }] : [],
    )
  }
  // No usable TOC: one entry per spine document
  if (!entries.length) entries = spine.map((h, i) => ({ label: `Section ${i + 1}`, href: h }))

  // Text of each entry: from its start to the next entry's start, across spine documents
  const fragments = new Set(entries.map((e) => e.href).filter((h) => h.includes('#')))
  const spineBlocks: { file: string; blocks: (Block & { anchor?: string })[] }[] = []
  for (const file of spine) {
    const text = await read(file)
    if (!text) continue
    const doc = parseHtml(text)
    const anchors = new Set([...fragments].filter((f) => f.startsWith(file + '#')).map((f) => f.slice(file.length + 1)))
    spineBlocks.push({ file, blocks: anchors.size ? blocksWithAnchors(doc.body, anchors) : elementToBlocks(doc.body) })
  }
  // Flatten to a stream with positions
  const stream: { file: string; block: Block & { anchor?: string } }[] = spineBlocks.flatMap((s) => s.blocks.map((b) => ({ file: s.file, block: b })))
  const startOf = (href: string): number => {
    const [file, frag] = href.split('#')
    if (frag) {
      const i = stream.findIndex((s) => s.file === file && s.block.anchor === frag)
      if (i >= 0) return i
    }
    return stream.findIndex((s) => s.file === file)
  }
  const starts = entries.map((e) => startOf(e.href))
  // Entries whose target can't be found take the next found start
  const valid = entries.map((e, i) => ({ e, s: starts[i] })).filter((x) => x.s >= 0)
  const ordered = valid.sort((a, b) => a.s - b.s)
  const chapters: Block[][] = ordered.map((x, i) => {
    const end = i + 1 < ordered.length ? ordered[i + 1].s : stream.length
    return stream
      .slice(x.s, end)
      .map((s) => s.block)
      .filter((b) => b.level >= 0 && !b.anchor)
  })
  // Text before the first entry (cover, title page) is dropped unless there's no TOC entry at all
  const raw = ordered.map((x) => x.e)
  return { title, author, toc: finishToc(raw, chapters), chapters }
}

const hashOf = (href: string) => (href.includes('#') ? '#' + href.split('#')[1] : '')

/** Like elementToBlocks, plus a marker block (level -1) where an element with one of [anchors] as id starts. */
function blocksWithAnchors(root: Element, anchors: Set<string>): (Block & { anchor?: string })[] {
  // Put a text marker before each anchor element, split the blocks on it
  const marks = new Map<string, string>()
  for (const id of anchors) {
    const el = root.ownerDocument.getElementById(id)
    if (!el) continue
    const token = `⁣ANCHOR${marks.size}⁣`
    marks.set(token, id)
    el.insertBefore(root.ownerDocument.createTextNode(token + ' '), el.firstChild)
  }
  const out: (Block & { anchor?: string })[] = []
  for (const b of elementToBlocks(root)) {
    let text = b.text
    let found = true
    while (found) {
      found = false
      for (const [token, id] of marks) {
        const at = text.indexOf(token)
        if (at < 0) continue
        found = true
        const before = text.slice(0, at).trim()
        if (before) out.push({ level: b.level, text: before })
        out.push({ level: -1, text: '', anchor: id })
        text = text.slice(at + token.length).trim()
      }
    }
    if (text) out.push({ level: b.level, text })
  }
  return out
}

// ---------------------------------------------------------------------------------------------
// PDF

export async function readPdf(data: Buffer | Uint8Array, fallbackTitle: string): Promise<BookContent> {
  const pdfjs = await import('pdfjs-dist/legacy/build/pdf.mjs')
  const doc = await pdfjs.getDocument({ data: new Uint8Array(data), useSystemFonts: true }).promise
  const meta = (await doc.getMetadata().catch(() => null)) as { info?: { Title?: string; Author?: string } } | null
  const pages: string[] = []
  for (let p = 1; p <= doc.numPages; p++) {
    const page = await doc.getPage(p)
    const tc = await page.getTextContent()
    let text = ''
    for (const it of tc.items as { str?: string; hasEOL?: boolean }[]) {
      text += it.str ?? ''
      if (it.hasEOL) text += '\n'
    }
    pages.push(text)
  }
  // Outline → page numbers
  const outline = (await doc.getOutline().catch(() => null)) ?? []
  const entries: { label: string; page: number }[] = []
  for (const o of outline) {
    try {
      const dest = typeof o.dest === 'string' ? await doc.getDestination(o.dest) : o.dest
      if (!dest) continue
      const page = (await doc.getPageIndex(dest[0])) + 1
      entries.push({ label: o.title.trim(), page })
    } catch {
      // unresolvable entry
    }
  }
  entries.sort((a, b) => a.page - b.page)
  const parts = entries.length >= 2 ? entries : Array.from({ length: Math.ceil(pages.length / 20) }, (_, i) => ({ label: `Pages ${i * 20 + 1}–${Math.min(pages.length, i * 20 + 20)}`, page: i * 20 + 1 }))
  const chapters = parts.map((e, i) => {
    const end = i + 1 < parts.length ? parts[i + 1].page - 1 : pages.length
    return pages
      .slice(e.page - 1, Math.max(e.page, end))
      .join('\n')
      .split(/\n\s*\n|(?<=[.!?])\n(?=[A-Z])/)
      .map((t) => t.replace(/\s+/g, ' ').trim())
      .filter(Boolean)
      .map((t) => ({ level: 0, text: t }))
  })
  return {
    title: meta?.info?.Title?.trim() || fallbackTitle,
    author: meta?.info?.Author?.trim() || '',
    toc: finishToc(
      parts.map((p) => ({ label: p.label, href: '' })),
      chapters,
    ),
    chapters,
  }
}

/** "1-3", "2,5", "Chapter 4", "4" → TOC entries. Numbers mean chapters (front matter skipped) unless raw. */
export function pickChapters(toc: TocEntry[], spec: string, includeFront: boolean): TocEntry[] {
  const nums = new Set<number>()
  for (const part of spec.split(/[,;]|\band\b/)) {
    const m = part.match(/(\d+)\s*(?:-|–|to|through)\s*(\d+)/i)
    if (m) for (let n = Number(m[1]); n <= Number(m[2]); n++) nums.add(n)
    else {
      const one = part.match(/(\d+)/)
      if (one) nums.add(Number(one[1]))
      else {
        const label = part.trim().toLowerCase()
        const hit = label && toc.find((e) => e.label.toLowerCase().includes(label))
        if (hit) return [hit]
      }
    }
  }
  if (/first\s+(\d+)/i.test(spec)) {
    const n = Number(spec.match(/first\s+(\d+)/i)![1])
    nums.clear()
    for (let i = 1; i <= n; i++) nums.add(i)
  }
  return includeFront ? toc.filter((e) => nums.has(e.index)) : toc.filter((e) => e.chapterNo && nums.has(e.chapterNo))
}
