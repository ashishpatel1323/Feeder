/*
 * Fast reading: the first part of every word is bold, so the eye can jump between these
 * fixation points and the brain fills in the rest of the word.
 *
 * Ported from text-vide by Gumball12 (https://github.com/Gumball12/text-vide), MIT License,
 * Copyright (c) 2022 Gumball12. The Android reader (FastReading.kt) uses the same port, so both
 * apps bold the same letters.
 */

/** How much of each word is bold, from 1 (most) to 5 (least), as in text-vide. */
export const FAST_READING_FIXATION_POINT = 3

// For fixation point N, a word of length L gets L - i bold letters, where i is the first index with L <= boundary[i]
const FIXATION_BOUNDARIES: number[][] = [
  [0, 4, 12, 17, 24, 29, 35, 42, 48],
  [1, 2, 7, 10, 13, 14, 19, 22, 25, 28, 31, 34, 37, 40, 43, 46, 49],
  [1, 2, 5, 7, 9, 11, 13, 15, 17, 19, 21, 23, 25, 27, 29, 31, 33, 35, 37, 39, 41, 43, 45, 47, 49],
  [0, 2, 4, 5, 6, 8, 9, 11, 14, 15, 17, 18, 20, 0, 21, 23, 24, 26, 27, 29, 30, 32, 33, 35, 36, 38, 39, 41, 42, 44, 45, 47, 48],
  [0, 2, 3, 5, 6, 7, 8, 10, 11, 12, 14, 15, 17, 19, 20, 21, 23, 24, 25, 26, 28, 29, 30, 32, 33, 34, 35, 37, 38, 39, 41, 42, 43, 44, 46, 47, 48],
]

// A word holds at least one letter; digits only count inside words
const WORD = /[\p{L}\p{Nd}]*\p{L}[\p{L}\p{Nd}]*/gu

export function fixationLength(wordLength: number, fixationPoint = FAST_READING_FIXATION_POINT): number {
  const boundaries = FIXATION_BOUNDARIES[fixationPoint - 1] ?? FIXATION_BOUNDARIES[0]
  const index = boundaries.findIndex((b) => wordLength <= b)
  const bold = index === -1 ? wordLength - boundaries.length : wordLength - index
  return Math.max(bold, 0)
}

/** The parts of `text` to make bold, as [start, end) pairs. */
export function fastReadingRanges(text: string, fixationPoint = FAST_READING_FIXATION_POINT): [number, number][] {
  const out: [number, number][] = []
  for (const m of text.matchAll(WORD)) {
    const length = fixationLength(m[0].length, fixationPoint)
    if (length > 0) out.push([m.index!, m.index! + length])
  }
  return out
}

// Code keeps its own look; the rest mirrors what read aloud skips
const SKIP_TAGS = new Set(['SCRIPT', 'STYLE', 'NOSCRIPT', 'SVG', 'CODE', 'PRE', 'KBD', 'SAMP', 'TEXTAREA'])

/**
 * Wraps the start of every word in `<b class="fr">`. Only elements are added, never text, so
 * read aloud highlighting and tap-to-read see exactly the same text as before.
 */
export function applyFastReading(html: string, fixationPoint = FAST_READING_FIXATION_POINT): string {
  const template = document.createElement('template')
  template.innerHTML = html
  const root = template.content
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
    acceptNode: (n) => {
      for (let p = n.parentElement; p; p = p.parentElement) {
        if (SKIP_TAGS.has(p.tagName.toUpperCase())) return NodeFilter.FILTER_REJECT
      }
      return /\p{L}/u.test(n.textContent ?? '') ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT
    },
  })
  const nodes: Text[] = []
  for (let n = walker.nextNode(); n; n = walker.nextNode()) nodes.push(n as Text)
  for (const node of nodes) {
    const text = node.data
    const ranges = fastReadingRanges(text, fixationPoint)
    if (!ranges.length) continue
    const fragment = document.createDocumentFragment()
    let last = 0
    for (const [start, end] of ranges) {
      if (start > last) fragment.append(text.slice(last, start))
      const b = document.createElement('b')
      b.className = 'fr'
      b.textContent = text.slice(start, end)
      fragment.append(b)
      last = end
    }
    if (last < text.length) fragment.append(text.slice(last))
    node.replaceWith(fragment)
  }
  return template.innerHTML
}
