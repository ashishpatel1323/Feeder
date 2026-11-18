// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ReadAloudEngine } from '../readaloud'

function engineWith(html: string) {
  const el = document.createElement('div')
  el.innerHTML = html
  document.body.appendChild(el)
  const e = new ReadAloudEngine()
  e.attach(el, 'item-1', 'en')
  return e
}

const TEXT = '<p>One two three four five. Six seven eight nine ten.</p><p>Eleven twelve thirteen fourteen fifteen sixteen.</p>'

describe('muted (silent speed reading) mode', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => {
    vi.useRealTimers()
    document.body.innerHTML = ''
  })

  it('speed reads from a word and moves on by itself', () => {
    const e = engineWith(TEXT)
    e.setWpm(600)
    e.speedReadFrom(e.indexedText.indexOf('Six'))
    expect(e.state.muted).toBe(true)
    expect(e.state.status).toBe('playing')
    expect(e.state.word).toBe('Six')
    expect(e.state.wordCount).toBe(16)
    vi.advanceTimersByTime(700)
    expect(e.state.wordIndex).toBeGreaterThan(5)
    expect(e.state.status).toBe('playing')
    // Following the words in the article too
    expect(e.state.offset).toBe(e.indexedText.indexOf(e.state.word.replace(/[.]$/, '')))
  })

  it('skips ten words and seeks by fraction', () => {
    const e = engineWith(TEXT)
    e.speedReadFrom(0)
    e.pause()
    e.seekBy(10)
    expect(e.state.wordIndex).toBe(10)
    expect(e.state.word).toBe('Eleven')
    e.seekBy(-10)
    expect(e.state.wordIndex).toBe(0)
    e.seekToFraction(1)
    expect(e.state.wordIndex).toBe(15)
  })

  it('unmuting while paused keeps the word reached for the next play', () => {
    const e = engineWith(TEXT)
    e.speedReadFrom(0)
    e.pause()
    e.seekBy(10)
    e.setMuted(false)
    expect(e.state.muted).toBe(false)
    expect(e.state.status).toBe('paused')
    // Muting again starts from the same word
    e.setMuted(true)
    expect(e.state.word).toBe('Eleven')
  })

  it('finishes at the end and stop ends muted mode', () => {
    const e = engineWith(TEXT)
    const done = vi.fn()
    e.onFinished = done
    e.setWpm(1000)
    e.speedReadFrom(e.indexedText.indexOf('fifteen'))
    vi.advanceTimersByTime(10000)
    expect(done).toHaveBeenCalledWith('item-1')
    expect(e.state.status).toBe('idle')
    expect(e.state.muted).toBe(false)
  })

  it('clamps and steps the speed', () => {
    const e = engineWith(TEXT)
    e.setWpm(2000)
    expect(e.state.wpm).toBe(1000)
    e.setWpm(312)
    expect(e.state.wpm).toBe(300)
  })

  it('moves the voice position while paused', () => {
    const e = engineWith(TEXT)
    e.moveTo(e.indexedText.indexOf('eight') + 2)
    expect(e.state.offset).toBe(e.indexedText.indexOf('eight'))
    expect(e.state.sentence).toBe(1)
    e.seekToFraction(0)
    expect(e.state.offset).toBe(0)
  })
})
