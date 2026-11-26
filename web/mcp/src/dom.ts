/**
 * The vendored Feeder modules (feedparse.ts, transcriptFormats.ts) use the browser's DOMParser.
 * Node has none, so jsdom's is installed as a global before they run.
 */
import { JSDOM } from 'jsdom'

const win = new JSDOM('').window
const g = globalThis as Record<string, unknown>
if (!g.DOMParser) g.DOMParser = win.DOMParser
if (!g.Node) g.Node = win.Node

const parser = new win.DOMParser()

/**
 * Parses HTML with the one shared jsdom window. A new JSDOM per call keeps a whole window alive per
 * document and ran the process out of memory indexing ~12k articles.
 */
export function parseHtml(html: string, url?: string): Document {
  const doc = parser.parseFromString(html, 'text/html')
  if (url && !doc.querySelector('base')) {
    const base = doc.createElement('base')
    base.setAttribute('href', url)
    doc.head?.prepend(base)
  }
  return doc
}
