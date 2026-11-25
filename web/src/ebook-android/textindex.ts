/**
 * The text of a book chapter for read aloud on Android, with a map back to the page's text
 * nodes so the app's sentence and word offsets can be marked on the page.
 */
export interface TextIndex {
  text: string
  segments: { node: Text; start: number; end: number }[]
}

const BLOCK = /^(p|div|li|h[1-6]|blockquote|pre|td|th|section|article|header|footer|figure|ul|ol|table|tr|dt|dd|br|hr)$/i
const SKIP = /^(script|style|noscript|svg|rt|rp|figcaption)$/i
const EPUB_NS = 'http://www.idpf.org/2007/ops'

function skipped(el: Element): boolean {
  if (SKIP.test(el.localName) || el.getAttribute('aria-hidden') === 'true') return true
  const role = el.getAttribute('role') ?? ''
  if (/doc-(noteref|footnote|endnote)/.test(role)) return true
  const t = el.getAttributeNS(EPUB_NS, 'type') ?? ''
  return /\b(noteref|footnote|endnote|rearnote|pagebreak)s?\b/.test(t)
}

export function indexText(root: Element): TextIndex {
  const doc = root.ownerDocument
  const segments: TextIndex['segments'] = []
  let text = ''
  let lastBlock: Element | null = null
  const blockOf = (n: Node): Element | null => {
    let b = n.parentElement
    while (b && b !== root && !BLOCK.test(b.localName)) b = b.parentElement
    return b
  }
  const walker = doc.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
    acceptNode: (n) => {
      for (let p = n.parentElement; p && p !== root; p = p.parentElement) if (skipped(p)) return NodeFilter.FILTER_REJECT
      return n.textContent ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT
    },
  })
  for (let n = walker.nextNode() as Text | null; n; n = walker.nextNode() as Text | null) {
    const block = blockOf(n)
    if (lastBlock && block !== lastBlock && text && !/\s$/.test(text)) text += '\n\n'
    lastBlock = block
    const start = text.length
    text += n.data
    segments.push({ node: n, start, end: text.length })
  }
  return { text, segments }
}

export function rangeFor(index: TextIndex, doc: Document, start: number, end: number): Range | null {
  const s = index.segments.find((g) => start >= g.start && start < g.end)
  const e = [...index.segments].reverse().find((g) => end > g.start && end <= g.end)
  if (!s || !e) return null
  const r = doc.createRange()
  r.setStart(s.node, start - s.start)
  r.setEnd(e.node, end - e.start)
  return r
}
