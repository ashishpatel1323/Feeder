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

  it('stops on the last word at the end, still muted, without moving on', () => {
    const e = engineWith(TEXT)
    const done = vi.fn()
    e.onFinished = done
    e.setWpm(1000)
    e.speedReadFrom(e.indexedText.indexOf('fifteen'))
    vi.advanceTimersByTime(10000)
    expect(done).not.toHaveBeenCalled()
    expect(e.state.status).toBe('paused')
    expect(e.state.muted).toBe(true)
    expect(e.state.wordIndex).toBe(15)
    expect(e.state.word).toBe('sixteen.')
    // Play starts over from the first word
    e.play()
    expect(e.state.status).toBe('playing')
    expect(e.state.wordIndex).toBe(0)
  })

  it('stop ends muted mode', () => {
    const e = engineWith(TEXT)
    e.speedReadFrom(0)
    e.stop()
    expect(e.state.status).toBe('idle')
    expect(e.state.muted).toBe(false)
  })

  it('seeking to the end clamps to the last word', () => {
    const e = engineWith(TEXT)
    const last = e.indexedText.lastIndexOf('sixteen')
    e.seekToFraction(1)
    expect(e.state.offset).toBe(last)
    expect(e.state.word).toBe('sixteen')
    e.moveTo(0)
    e.seekBy(10_000)
    expect(e.state.offset).toBe(last)
    // Muted: the last word index
    e.speedReadFrom(0)
    e.pause()
    e.seekBy(1000)
    expect(e.state.wordIndex).toBe(15)
    e.seekToFraction(1)
    expect(e.state.wordIndex).toBe(15)
  })

  it('seeking to the end while paused keeps the endpoint for play', () => {
    const e = engineWith(TEXT)
    e.seekToFraction(1)
    // Unmuting reads from the last word, it does not repeat the last sentence
    e.setMuted(true)
    expect(e.state.word).toBe('sixteen.')
    e.setMuted(false)
    e.setMuted(true)
    expect(e.state.wordIndex).toBe(15)
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

describe('voice seeking to the end', () => {
  const spoken: string[] = []
  beforeEach(() => {
    spoken.length = 0
    ;(globalThis as Record<string, unknown>).SpeechSynthesisUtterance = class {
      text: string
      constructor(t: string) {
        this.text = t
      }
    }
    Object.defineProperty(window, 'speechSynthesis', {
      configurable: true,
      value: { speak: (u: { text: string }) => spoken.push(u.text), cancel() {}, getVoices: () => [] },
    })
  })
  afterEach(() => {
    delete (window as unknown as Record<string, unknown>).speechSynthesis
    document.body.innerHTML = ''
  })

  it('speaks the final word, not the whole last sentence again', () => {
    const e = engineWith(TEXT)
    e.play()
    expect(spoken[0]).toBe('One two three four five.')
    e.seekToFraction(1)
    expect(e.state.status).toBe('playing')
    expect(spoken[spoken.length - 1]).toBe('sixteen.')
    e.pause()
    e.seekBy(-1000)
    e.seekToFraction(1)
    e.play()
    expect(spoken[spoken.length - 1]).toBe('sixteen.')
  })
})
