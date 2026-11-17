import { describe, expect, it } from 'vitest'
import { focusIndex, rsvpWords, sentenceStartBefore, wordDelay, wordIndexAt } from '../rsvp'

describe('rsvpWords', () => {
  it('splits words and marks paragraph ends', () => {
    const words = rsvpWords('Hello there, world.\n\nNew para — here')
    expect(words.map((w) => w.text)).toEqual(['Hello', 'there,', 'world.', 'New', 'para', 'here'])
    expect(words.map((w) => w.paragraphEnd)).toEqual([false, false, true, false, false, true])
    expect(words[3].start).toBe('Hello there, world.\n\n'.length)
  })

  it('splits very long words into hyphenated pieces', () => {
    const words = rsvpWords('Pneumonoultramicroscopicsilicovolcanoconiosis')
    expect(words.length).toBeGreaterThan(2)
    expect(words.slice(0, -1).every((w) => w.text.endsWith('-'))).toBe(true)
    expect(words.map((w) => w.text.replace(/-$/, '')).join('')).toBe('Pneumonoultramicroscopicsilicovolcanoconiosis')
    expect(words[1].start).toBe(words[0].text.length - 1)
  })

  it('breaks long compounds at their own hyphens', () => {
    const words = rsvpWords('state-of-the-art-machine-learning')
    expect(words.map((w) => w.text)).toEqual(['state-of-the-', 'art-machine-', 'learning'])
  })
})

describe('focusIndex', () => {
  it('picks a letter left of centre', () => {
    expect(focusIndex('a')).toBe(0)
    expect(focusIndex('word')).toBe(1)
    expect(focusIndex('reading')).toBe(2)
    expect(focusIndex('presentation')).toBe(3)
  })
  it('skips leading punctuation', () => {
    expect(focusIndex('"Hello')).toBe(2)
  })
})

describe('timing', () => {
  it('pauses longer at the end of sentences and paragraphs', () => {
    const [plain, comma, stop] = rsvpWords('word word, word.')
    expect(wordDelay(plain, 300)).toBe(200)
    expect(wordDelay(comma, 300)).toBeGreaterThan(200)
    expect(wordDelay(stop, 300)).toBeGreaterThan(wordDelay(comma, 300))
  })
})

describe('navigation', () => {
  const words = rsvpWords('One two three. Four five six seven. Eight nine.')
  it('rewinds to the start of the sentence, or the one before when just started', () => {
    expect(sentenceStartBefore(words, 7)).toBe(3)
    expect(sentenceStartBefore(words, 4)).toBe(0)
    expect(sentenceStartBefore(words, 0)).toBe(0)
  })
  it('finds the word at an offset', () => {
    expect(wordIndexAt(words, 0)).toBe(0)
    expect(wordIndexAt(words, 'One two three. Fo'.length)).toBe(3)
  })
})
