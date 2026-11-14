/**
 * Read aloud with the browser's own voices (Web Speech API), sentence and word highlighting
 * (CSS Custom Highlight API) and "read from here". The web counterpart of the Android fork's
 * ReadAloudService + tap-to-read.
 *
 * Text is spoken one sentence per utterance: Chrome cuts long utterances short, and it gives a
 * natural place to highlight, skip and resume. Word highlighting uses the engine's boundary
 * events when it sends them; many online voices don't, so it falls back to an estimate by word
 * length, like the Android app does for engines without word timings.
 */

interface Segment {
  node: Text
  start: number
  end: number
}

interface Sentence {
  start: number
  end: number
}

export type ReadAloudStatus = 'idle' | 'playing' | 'paused'

export interface ReadAloudState {
  status: ReadAloudStatus
  itemId: string | null
  sentence: number
  sentences: number
  rate: number
  wordTiming: 'exact' | 'estimated' | null
}

type Listener = (s: ReadAloudState) => void

const SKIP_TAGS = new Set(['SCRIPT', 'STYLE', 'NOSCRIPT', 'SVG', 'FIGCAPTION', 'BUTTON'])
const BLOCK_TAGS =
  /^(P|DIV|LI|H[1-6]|BLOCKQUOTE|PRE|TD|TH|SECTION|ARTICLE|HEADER|FOOTER|FIGURE|UL|OL|TABLE|TR|BR|HR|DT|DD)$/

const highlights = () => (typeof CSS !== 'undefined' && 'highlights' in CSS ? CSS.highlights : null)

/** A tiny silent WAV, looped while reading so the browser shows media controls (Media Session). */
function silentWavUrl(): string {
  const sampleRate = 8000
  const samples = sampleRate // 1 second
  const buf = new ArrayBuffer(44 + samples)
  const v = new DataView(buf)
  const w = (o: number, s: string) => [...s].forEach((c, i) => v.setUint8(o + i, c.charCodeAt(0)))
  w(0, 'RIFF')
  v.setUint32(4, 36 + samples, true)
  w(8, 'WAVE')
  w(12, 'fmt ')
  v.setUint32(16, 16, true)
  v.setUint16(20, 1, true)
  v.setUint16(22, 1, true)
  v.setUint32(24, sampleRate, true)
  v.setUint32(28, sampleRate, true)
  v.setUint16(32, 1, true)
  v.setUint16(34, 8, true)
  w(36, 'data')
  v.setUint32(40, samples, true)
  for (let i = 0; i < samples; i++) v.setUint8(44 + i, 128)
  return URL.createObjectURL(new Blob([buf], { type: 'audio/wav' }))
}

export class ReadAloudEngine {
  private segments: Segment[] = []
  private text = ''
  private sentences: Sentence[] = []
  private container: HTMLElement | null = null
  private listeners = new Set<Listener>()
  private generation = 0
  private failures = 0
  private estimateTimer: number | null = null
  private silentAudio: HTMLAudioElement | null = null
  private lang = 'en'

  state: ReadAloudState = { status: 'idle', itemId: null, sentence: 0, sentences: 0, rate: 1, wordTiming: null }
  voiceURI = ''
  onFinished: ((itemId: string) => void) | null = null
  onError: ((message: string) => void) | null = null
  onMediaNext: (() => void) | null = null
  onMediaPrevious: (() => void) | null = null
  title = ''
  artist = ''

  get supported(): boolean {
    return typeof window !== 'undefined' && 'speechSynthesis' in window
  }

  subscribe(l: Listener): () => void {
    this.listeners.add(l)
    l(this.state)
    return () => this.listeners.delete(l)
  }

  private set(patch: Partial<ReadAloudState>) {
    this.state = { ...this.state, ...patch }
    this.listeners.forEach((l) => l(this.state))
  }

  /** Indexes the visible text of an article element. Call again when the text changes. */
  attach(container: HTMLElement, itemId: string, lang?: string) {
    if (this.state.itemId && this.state.itemId !== itemId && this.state.status !== 'idle') this.stop()
    this.container = container
    this.lang = lang || document.documentElement.lang || navigator.language || 'en'
    this.segments = []
    let text = ''
    let lastBlock: Element | null = null
    const walker = document.createTreeWalker(container, NodeFilter.SHOW_TEXT, {
      acceptNode: (n) => {
        for (let p = n.parentElement; p && p !== container; p = p.parentElement) {
          if (SKIP_TAGS.has(p.tagName) || p.getAttribute('aria-hidden') === 'true') return NodeFilter.FILTER_REJECT
        }
        return n.textContent && n.textContent.length ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT
      },
    })
    for (let n = walker.nextNode() as Text | null; n; n = walker.nextNode() as Text | null) {
      let block: Element | null = n.parentElement
      while (block && block !== container && !BLOCK_TAGS.test(block.tagName)) block = block.parentElement
      if (lastBlock && block !== lastBlock && text && !/\s$/.test(text)) {
        // Paragraph break: end the sentence here even without punctuation
        text += '\n\n'
      }
      lastBlock = block
      const start = text.length
      text += n.textContent!
      this.segments.push({ node: n, start, end: text.length })
    }
    this.text = text
    this.sentences = this.splitSentences(text)
    this.set({ itemId, sentences: this.sentences.length, sentence: Math.min(this.state.sentence, this.sentences.length) })
  }

  private splitSentences(text: string): Sentence[] {
    const out: Sentence[] = []
    const push = (start: number, end: number) => {
      // Split again on paragraph breaks, trim whitespace
      const slice = text.slice(start, end)
      const re = /\S(?:[^\n]|\n(?!\s*\n))*/g
      for (let m = re.exec(slice); m; m = re.exec(slice)) {
        const body = m[0].trimEnd()
        if (/[\p{L}\p{N}]/u.test(body)) out.push({ start: start + m.index, end: start + m.index + body.length })
      }
    }
    if (typeof Intl !== 'undefined' && 'Segmenter' in Intl) {
      const seg = new Intl.Segmenter(this.lang, { granularity: 'sentence' })
      for (const s of seg.segment(text)) push(s.index, s.index + s.segment.length)
    } else {
      const re = /[^.!?\n]+[.!?]*["')\]]*\s*/g
      for (let m = re.exec(text); m; m = re.exec(text)) push(m.index, m.index + m[0].length)
    }
    return out
  }

  /** DOM range for a span of the indexed text. */
  rangeFor(start: number, end: number): Range | null {
    const s = this.segments.find((g) => start >= g.start && start < g.end)
    const e = [...this.segments].reverse().find((g) => end > g.start && end <= g.end)
    if (!s || !e) return null
    const r = document.createRange()
    r.setStart(s.node, start - s.start)
    r.setEnd(e.node, end - e.start)
    return r
  }

  /** Text offset of a DOM point inside the article, or -1. */
  offsetOf(node: Node, offset: number): number {
    const seg = this.segments.find((g) => g.node === node)
    return seg ? seg.start + Math.min(offset, seg.end - seg.start) : -1
  }

  /** Text offset under a screen point (for tap-to-read). */
  offsetAtPoint(x: number, y: number): number {
    const doc = document as Document & {
      caretPositionFromPoint?: (x: number, y: number) => { offsetNode: Node; offset: number } | null
    }
    const pos = doc.caretPositionFromPoint?.(x, y)
    if (pos) return this.offsetOf(pos.offsetNode, pos.offset)
    const range = document.caretRangeFromPoint?.(x, y)
    return range ? this.offsetOf(range.startContainer, range.startOffset) : -1
  }

  /** The word around a text offset. */
  wordAt(offset: number): { start: number; end: number } | null {
    if (offset < 0 || offset > this.text.length) return null
    const isWord = (c: string | undefined) => !!c && /[\p{L}\p{N}'’-]/u.test(c)
    // A caret right after a word (tap on its last letter's right half) belongs to that word
    if (!isWord(this.text[offset]) && isWord(this.text[offset - 1])) offset--
    if (!isWord(this.text[offset])) return null
    let s = offset
    let e = offset
    while (s > 0 && isWord(this.text[s - 1])) s--
    while (e < this.text.length && isWord(this.text[e])) e++
    return { start: s, end: e }
  }

  sentenceIndexAt(offset: number): number {
    const i = this.sentences.findIndex((s) => offset < s.end)
    return i < 0 ? this.sentences.length - 1 : i
  }

  showTapWord(offset: number | null) {
    const hl = highlights()
    if (!hl) return
    hl.delete('ra-tap')
    if (offset === null) return
    const w = this.wordAt(offset)
    const r = w && this.rangeFor(w.start, w.end)
    if (r) hl.set('ra-tap', new Highlight(r))
  }

  setRate(rate: number) {
    this.set({ rate })
    if (this.state.status === 'playing') this.speakFrom(this.state.sentence, null)
  }

  play(fromOffset?: number) {
    if (!this.supported || !this.sentences.length) return
    this.startMedia()
    if (fromOffset !== undefined && fromOffset >= 0) {
      this.speakFrom(this.sentenceIndexAt(fromOffset), fromOffset)
    } else {
      const i = this.state.sentence >= this.sentences.length ? 0 : this.state.sentence
      this.speakFrom(i, null)
    }
  }

  pause() {
    this.generation++
    speechSynthesis.cancel()
    this.clearEstimate()
    this.set({ status: 'paused' })
    this.silentAudio?.pause()
    if ('mediaSession' in navigator) navigator.mediaSession.playbackState = 'paused'
  }

  toggle() {
    if (this.state.status === 'playing') this.pause()
    else this.play()
  }

  stop() {
    this.generation++
    if (this.supported) speechSynthesis.cancel()
    this.clearEstimate()
    highlights()?.delete('ra-sentence')
    highlights()?.delete('ra-word')
    this.silentAudio?.pause()
    if ('mediaSession' in navigator) navigator.mediaSession.playbackState = 'none'
    this.set({ status: 'idle', sentence: 0, wordTiming: null })
  }

  skip(delta: number) {
    const i = Math.max(0, Math.min(this.sentences.length - 1, this.state.sentence + delta))
    if (this.state.status === 'playing') this.speakFrom(i, null)
    else {
      this.set({ sentence: i })
      this.highlightSentence(i)
    }
  }

  private clearEstimate() {
    if (this.estimateTimer !== null) {
      clearTimeout(this.estimateTimer)
      this.estimateTimer = null
    }
  }

  private pickVoice(): SpeechSynthesisVoice | null {
    const voices = speechSynthesis.getVoices()
    if (this.voiceURI) {
      const v = voices.find((v) => v.voiceURI === this.voiceURI)
      if (v) return v
    }
    const base = this.lang.split('-')[0].toLowerCase()
    return (
      voices.find((v) => v.default && v.lang.toLowerCase().startsWith(base)) ??
      voices.find((v) => v.lang.toLowerCase().startsWith(base)) ??
      null
    )
  }

  private highlightSentence(i: number) {
    const hl = highlights()
    const s = this.sentences[i]
    if (!hl || !s) return
    const r = this.rangeFor(s.start, s.end)
    if (!r) return
    hl.set('ra-sentence', new Highlight(r))
    hl.delete('ra-word')
    // Keep the sentence in view
    const rect = r.getBoundingClientRect()
    const scroller = this.container?.closest('[data-scroll]') as HTMLElement | null
    const view = scroller?.getBoundingClientRect() ?? { top: 0, bottom: window.innerHeight }
    if (rect.top < view.top + 60 || rect.bottom > view.bottom - 120) {
      const el = (r.startContainer.parentElement ?? this.container) as HTMLElement
      el.scrollIntoView({ block: 'center', behavior: 'smooth' })
    }
  }

  private highlightWord(start: number, end: number) {
    const hl = highlights()
    if (!hl) return
    const r = this.rangeFor(start, end)
    if (r) hl.set('ra-word', new Highlight(r))
  }

  private speakFrom(index: number, fromOffset: number | null) {
    const gen = ++this.generation
    speechSynthesis.cancel()
    this.clearEstimate()
    const sentence = this.sentences[index]
    if (!sentence) {
      this.finish()
      return
    }
    const start = fromOffset !== null && fromOffset > sentence.start && fromOffset < sentence.end ? fromOffset : sentence.start
    // Start at the beginning of the tapped word
    const word = this.wordAt(start)
    const begin = word ? word.start : start
    const spoken = this.text.slice(begin, sentence.end)

    this.set({ status: 'playing', sentence: index })
    this.highlightSentence(index)
    if ('mediaSession' in navigator) navigator.mediaSession.playbackState = 'playing'

    const u = new SpeechSynthesisUtterance(spoken)
    const voice = this.pickVoice()
    if (voice) {
      u.voice = voice
      u.lang = voice.lang
    } else {
      u.lang = this.lang
    }
    u.rate = this.state.rate
    let gotBoundary = false

    u.onboundary = (e) => {
      if (gen !== this.generation || e.name !== 'word') return
      if (!gotBoundary) {
        gotBoundary = true
        this.clearEstimate()
        if (this.state.wordTiming !== 'exact') this.set({ wordTiming: 'exact' })
      }
      const ws = begin + e.charIndex
      let we = e.charLength ? ws + e.charLength : ws
      if (!e.charLength) while (we < sentence.end && !/\s/.test(this.text[we])) we++
      this.highlightWord(ws, we)
    }
    u.onstart = () => {
      if (gen !== this.generation) return
      // No word events shortly after starting: estimate instead
      this.estimateTimer = window.setTimeout(() => {
        if (gen === this.generation && !gotBoundary) {
          if (this.state.wordTiming !== 'estimated') this.set({ wordTiming: 'estimated' })
          this.estimateWords(gen, begin, sentence.end, 350)
        }
      }, 350)
    }
    u.onend = () => {
      if (gen !== this.generation) return
      this.failures = 0
      this.clearEstimate()
      this.speakFrom(index + 1, null)
    }
    u.onerror = (e) => {
      if (gen !== this.generation) return
      if (e.error === 'interrupted' || e.error === 'canceled') return
      console.warn('Speech error', e.error)
      // A voice that fails every sentence would otherwise race through the whole article
      if (++this.failures >= 3) {
        this.failures = 0
        this.onError?.(`Speech failed (${e.error}). Try another voice in Settings.`)
        this.stop()
        return
      }
      this.speakFrom(index + 1, null)
    }
    speechSynthesis.speak(u)
  }

  /** Moves the word highlight along at an estimated pace (about 14 characters a second at 1x). */
  private estimateWords(gen: number, from: number, to: number, alreadyMs: number) {
    const words: { start: number; end: number }[] = []
    const re = /\S+/g
    const slice = this.text.slice(from, to)
    for (let m = re.exec(slice); m; m = re.exec(slice)) words.push({ start: from + m.index, end: from + m.index + m[0].length })
    const msPerChar = 1000 / (14 * this.state.rate)
    let t = -alreadyMs
    const schedule: { at: number; w: { start: number; end: number } }[] = []
    for (const w of words) {
      schedule.push({ at: Math.max(0, t), w })
      t += (w.end - w.start + 1) * msPerChar + 40 / this.state.rate
    }
    let i = 0
    const step = () => {
      if (gen !== this.generation || i >= schedule.length) return
      this.highlightWord(schedule[i].w.start, schedule[i].w.end)
      i++
      if (i < schedule.length) this.estimateTimer = window.setTimeout(step, schedule[i].at - schedule[i - 1].at)
    }
    step()
  }

  private finish() {
    const itemId = this.state.itemId
    this.stop()
    if (itemId) this.onFinished?.(itemId)
  }

  private startMedia() {
    if (!this.silentAudio) {
      this.silentAudio = new Audio(silentWavUrl())
      this.silentAudio.loop = true
      this.silentAudio.volume = 0.01
    }
    this.silentAudio.play().catch(() => undefined)
    if (!('mediaSession' in navigator)) return
    const ms = navigator.mediaSession
    ms.metadata = new MediaMetadata({ title: this.title || 'Feeder', artist: this.artist || 'Read aloud', album: 'Feeder Web' })
    const handlers: [MediaSessionAction, (() => void) | null][] = [
      ['play', () => this.play()],
      ['pause', () => this.pause()],
      ['stop', () => this.stop()],
      ['seekbackward', () => this.skip(-1)],
      ['seekforward', () => this.skip(1)],
      ['previoustrack', this.onMediaPrevious ? () => this.onMediaPrevious?.() : () => this.skip(-1)],
      ['nexttrack', this.onMediaNext ? () => this.onMediaNext?.() : () => this.skip(1)],
    ]
    for (const [action, handler] of handlers) {
      try {
        ms.setActionHandler(action, handler)
      } catch {
        // action not supported here
      }
    }
  }
}

export const readAloud = new ReadAloudEngine()
