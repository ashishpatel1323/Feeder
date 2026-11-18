/**
 * Keeps clutter out of read aloud (and speed reading, which uses the same text): footnote
 * markers, [1]-style reference numbers, URLs, (Author, 2020) citations, photo credits and
 * copyright lines. The article on screen stays as it is; only what is spoken skips them.
 * Keep the rules in step with the Android app's SpeechTextCleaner.kt.
 */

const ORDINAL_SUFFIX = /^\s*(?:st|nd|rd|th|er|e|re|ème|o|a)\s*$/i
const FOOTNOTE_MARKER = /^[\s[(]*(?:\d+|[*†‡§¶#]+|[ivx]+|[a-z])(?:\s*[,–-]\s*(?:\d+|[a-z]))*[\s\])]*$/
const DIGITS = /^\s*\d+\s*$/

/** A superscript that points to a note, not an exponent like 10⁶ or an ordinal like 1st. */
export function isFootnoteMarker(content: string, linked: boolean, previous: string | undefined): boolean {
  if (!content.trim() || ORDINAL_SUFFIX.test(content)) return false
  if (linked) return true
  if (!FOOTNOTE_MARKER.test(content)) return false
  return !(DIGITS.test(content) && !!previous && /\d/.test(previous))
}

const FOOTNOTE_HREF = /#(?:fn|footnote|endnote|cite_note|note|ref)/i
const SHORT_MARKER = /^[\s[(]*(?:\d{1,3}|[*†‡§a-z])[\s\])]*$/

/** A plain link like `<a href="#fn1">1</a>` that jumps to a footnote. */
export function isFootnoteLink(href: string, content: string): boolean {
  return FOOTNOTE_HREF.test(href) && SHORT_MARKER.test(content)
}

/** Ids and classes of footnote and reference sections, which read aloud skips entirely. */
export const FOOTNOTE_SECTION = /^(?:(?:fn[:_-]?\d|footnote|endnote|cite_note)(?!.*ref)|references?$)/i

// "© 2026 …", "Copyright 2026 …", "… All rights reserved." but not "Copyright law is changing."
const COPYRIGHT = /^\s*(?:©|\(c\)\s*\d{4}|copyright\s*(?:©|\(c\)|\d{4}))|\ball rights reserved\W*$/i
const CREDIT =
  /^\s*(?:(?:(?:header|featured|lead|top|cover|hero)\s+)?(?:photo|image|picture|illustration|graphic|video)s?(?:\s+credits?)?\s*:|(?:photo(?:graph)?|image|illustration)s?\s+(?:by|courtesy of|via)\s|credits?\s*:)/i
// "Jane Doe / Getty Images", "(Photo via Reuters)" but not "… in a statement to Reuters."
const AGENCY_CREDIT = /(?:[/|©]|\bvia\b|\bcourtesy\b)[^/|]*?(?:getty images|shutterstock|ap photo|reuters|afp|unsplash|istock)\W*$/i

/** A whole paragraph that is only a copyright notice or a photo credit. */
export function isSkippedLine(text: string): boolean {
  const t = text.trim()
  if (!t) return false
  if (t.length < 300 && COPYRIGHT.test(t)) return true
  if (t.length < 200 && CREDIT.test(t)) return true
  return t.length < 120 && AGENCY_CREDIT.test(t)
}

const BRACKET_REF =
  /\[\s*(?:\d+[a-z]?(?:\s*[,–—-]\s*\d+[a-z]?)*|[a-z]|citation needed|clarification needed|edit|note\s+\d+|nb\s+\d+)\s*]/gi
const URL = /(?:https?:\/\/|www\.)[^\s<>"]+/gi
const NAME = String.raw`\p{Lu}[\p{L}'’\-]+`
const MONTH = String.raw`(?:January|February|March|April|May|June|July|August|September|October|November|December|Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sept?|Oct|Nov|Dec|Spring|Summer|Fall|Autumn|Winter)\b`
const AUTHORS = String.raw`(?!${MONTH})${NAME}(?:(?:\s+|,\s*)(?:${NAME}|et\s+al\.?|and|&))*`
const YEAR = String.raw`(?:\d{4}[a-z]?|n\.\s?d\.)(?:,\s*pp?\.\s*\d+(?:\s*[–-]\s*\d+)?)?`
const CITE = String.raw`${AUTHORS},\s*${YEAR}(?:\s*,\s*${YEAR})*`
const CITATION = new RegExp(
  String.raw`[ \t ]*\(\s*(?:see\s+(?:also\s+)?|e\.g\.,?\s+|cf\.\s+)?${CITE}(?:\s*;\s*${CITE})*\s*\)`,
  'gu',
)
const URL_TRAILING = '.,;:!?\'"”’'

/** Character ranges [start, end) of [text] that read aloud skips: reference numbers, URLs and citations. */
export function speechRemovals(text: string): [number, number][] {
  const out: [number, number][] = []
  for (const m of text.matchAll(BRACKET_REF)) out.push([m.index!, m.index! + m[0].length])
  for (const m of text.matchAll(CITATION)) out.push([m.index!, m.index! + m[0].length])
  for (const m of text.matchAll(URL)) {
    let start = m.index!
    let end = start + m[0].length
    while (end - 1 > start) {
      const c = text[end - 1]
      const url = text.slice(start, end)
      const unbalanced = c === ')' && url.split(')').length > url.split('(').length
      if (URL_TRAILING.includes(c) || unbalanced) end--
      else break
    }
    // "(https://example.com)" goes entirely, with the space before it
    if (text[start - 1] === '(' && text[end] === ')') {
      start--
      end++
      while (start > 0 && text[start - 1] === ' ') start--
    }
    out.push([start, end])
  }
  return out
}

/** [text] without the parts read aloud skips. */
export function cleanSpeechText(text: string): string {
  const removed = new Uint8Array(text.length)
  for (const [s, e] of speechRemovals(text)) removed.fill(1, s, e)
  let out = ''
  for (let i = 0; i < text.length; i++) if (!removed[i]) out += text[i]
  return out
}
