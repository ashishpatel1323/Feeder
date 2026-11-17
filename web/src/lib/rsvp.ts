// Speed reading (RSVP: rapid serial visual presentation). One word at a time, at a fixed spot,
// with a focus letter so the eye never has to move. The same rules live in the Android app
// (ui/compose/rsvp/Rsvp.kt); keep them in step.

export interface RsvpWord {
  /** The word as shown, punctuation included. */
  text: string
  /** Where the word starts in the source text. */
  start: number
  /** Last word of a paragraph. */
  paragraphEnd: boolean
}

export const RSVP_MIN_WPM = 100
export const RSVP_MAX_WPM = 1000
export const RSVP_DEFAULT_WPM = 300

// Words longer than this are shown in hyphenated pieces so they fit and stay readable
const MAX_PIECE = 13

/** Splits text into words. A line break ends a paragraph. */
export function rsvpWords(text: string): RsvpWord[] {
  const words: RsvpWord[] = []
  let lastEnd = 0
  for (const m of text.matchAll(/\S+/g)) {
    const start = m.index ?? 0
    if (words.length && text.slice(lastEnd, start).includes('\n')) words[words.length - 1].paragraphEnd = true
    lastEnd = start + m[0].length
    // Lone punctuation (a dash, a bullet) is not worth a frame of its own
    if (!/[\p{L}\p{N}]/u.test(m[0])) continue
    let offset = start
    for (const [piece, length] of splitLong(m[0])) {
      words.push({ text: piece, start: offset, paragraphEnd: false })
      offset += length
    }
  }
  if (words.length) words[words.length - 1].paragraphEnd = true
  return words
}

/** Index of the word at or just after [offset] in the source text. */
export function wordIndexAt(words: RsvpWord[], offset: number): number {
  let lo = 0
  let hi = words.length - 1
  while (lo < hi) {
    const mid = (lo + hi + 1) >> 1
    if (words[mid].start <= offset) lo = mid
    else hi = mid - 1
  }
  return Math.max(0, lo)
}

/** Pieces of a long word as [shown text, length in the source]. */
function splitLong(token: string): [string, number][] {
  if (token.length <= MAX_PIECE + 2) return [[token, token.length]]
  // Prefer existing hyphens, dashes and slashes as break points
  const parts = token.split(/(?<=[-–—/])/)
  if (parts.length > 1 && parts.every((p) => p.length <= MAX_PIECE + 2)) {
    // Join neighbouring parts while they fit
    const joined: string[] = []
    for (const part of parts) {
      const last = joined.length - 1
      if (last >= 0 && joined[last].length + part.length <= MAX_PIECE + 2) joined[last] += part
      else joined.push(part)
    }
    return joined.map((p) => [p, p.length])
  }
  const pieces: [string, number][] = []
  const count = Math.ceil(token.length / MAX_PIECE)
  const size = Math.ceil(token.length / count)
  for (let i = 0; i < token.length; i += size) {
    const piece = token.slice(i, i + size)
    pieces.push([i + size < token.length ? `${piece}-` : piece, piece.length])
  }
  return pieces
}

/**
 * Index of the focus letter: a little left of the middle of the word's letters, where the eye
 * recognises a word fastest. Leading quotes or brackets are skipped.
 */
export function focusIndex(word: string): number {
  const chars = Array.from(word)
  let start = 0
  while (start < chars.length - 1 && !/[\p{L}\p{N}]/u.test(chars[start])) start++
  let end = chars.length
  while (end > start + 1 && !/[\p{L}\p{N}]/u.test(chars[end - 1])) end--
  const len = end - start
  const pos = len <= 1 ? 0 : len <= 5 ? 1 : len <= 9 ? 2 : len <= 13 ? 3 : 4
  // Index in UTF-16 units, so callers can slice the string
  return chars.slice(0, start + pos).join('').length
}

/** How long to show a word, in milliseconds. Longer words and pauses in the sentence get more time. */
export function wordDelay(word: RsvpWord, wpm: number): number {
  const base = 60000 / wpm
  let factor = 1
  const letters = word.text.replace(/[^\p{L}\p{N}]/gu, '').length
  if (letters > 8) factor += 0.4
  else if (letters <= 3) factor -= 0.1
  if (word.paragraphEnd) factor += 1.5
  else if (/[.!?…]["'”’)\]]*$/.test(word.text)) factor += 1.2
  else if (/[,;:–—]["'”’)\]]*$/.test(word.text)) factor += 0.5
  return Math.round(base * factor)
}

/** Index of the first word of the sentence before [index] (or of its own sentence if well into it). */
export function sentenceStartBefore(words: RsvpWord[], index: number): number {
  const endsSentence = (w: RsvpWord) => w.paragraphEnd || /[.!?…]["'”’)\]]*$/.test(w.text)
  let i = Math.min(index, words.length) - 1
  // Skip back over the start of the current sentence when only a few words in
  let start = i
  while (start >= 0 && !endsSentence(words[start])) start--
  const current = start + 1
  if (index - current > 2) return current
  i = start - 1
  while (i >= 0 && !endsSentence(words[i])) i--
  return Math.max(0, i + 1)
}

/** Minutes left at [wpm] from [index]. */
export function minutesLeft(words: RsvpWord[], index: number, wpm: number): number {
  let ms = 0
  for (let i = index; i < words.length; i++) ms += wordDelay(words[i], wpm)
  return ms / 60000
}
