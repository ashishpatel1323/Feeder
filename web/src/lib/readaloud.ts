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

import { isKokoroVoice, kokoro, KOKORO_PREFIX, wavBlob, webGpuAvailable, type KokoroAudio } from './kokoro'
import { FOOTNOTE_SECTION, isFootnoteLink, isFootnoteMarker, isSkippedLine, speechRemovals } from './speechclean'
import { minutesLeft, RSVP_DEFAULT_WPM, RSVP_MAX_WPM, RSVP_MIN_WPM, rsvpWords, wordDelay, wordIndexAt, type RsvpWord } from './rsvp'

/** Speaking pace used to estimate times and seek by seconds: about 14 characters a second at 1x. */
export const CHARS_PER_SECOND = 14
export const RSVP_WPM_STEP = 25
/** Natural voice: how much listening to have generated (or queued) past the current sentence. */
const KOKORO_AHEAD_SECONDS = 25
const KOKORO_AHEAD_MAX_SENTENCES = 8
const KOKORO_CACHE_SIZE = 40
const WPM_KEY = 'feeder.rsvpWpm'
// The first words after (re)starting the silent reader are shown a little longer so the eye can settle
const RAMP = [1.8, 1.4, 1.15]

function loadWpm(): number {
  try {
    const v = Number(localStorage.getItem(WPM_KEY))
    if (v >= RSVP_MIN_WPM && v <= RSVP_MAX_WPM) return v
  } catch {
    // storage blocked
  }
  return RSVP_DEFAULT_WPM
}

interface Segment {
  node: Text
  start: number
  end: number
  // Where this piece starts inside its text node: skipped clutter can split a node into pieces
  nodeStart: number
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
  /** Auto scroll: the article keeps the spoken text in view until the reader scrolls away. */
  following: boolean
  /**
   * Muted: the voice is off and a timer moves through the words at [wpm] (silent speed reading),
   * still highlighting and scrolling the article.
   */
  muted: boolean
  /** Offset of the current word in the indexed text, -1 for none. */
  wordStart: number
  /** The current word, for the speed reading word display. */
  word: string
  /** Reading position in the indexed text (current word, else the sentence start). */
  offset: number
  /** Length of the indexed text. */
  textLength: number
  /** Silent reader speed in words per minute. */
  wpm: number
  /** Silent reader position and word count (muted only). */
  wordIndex: number
  wordCount: number
}

type Listener = (s: ReadAloudState) => void

// Read aloud keeps to paragraphs and headings: no captions, tables or code listings' chrome
const SKIP_TAGS = new Set(['SCRIPT', 'STYLE', 'NOSCRIPT', 'SVG', 'FIGCAPTION', 'BUTTON', 'TABLE', 'CAPTION'])

/** Text before [el] in its paragraph, to tell an exponent (10⁶) from a footnote marker. */
function charBefore(el: Element): string | undefined {
  for (let n: Node | null = el; n; n = n.parentNode) {
    for (let p = n.previousSibling; p; p = p.previousSibling) {
      const t = p.textContent
      if (t) return t[t.length - 1]
    }
    if (n.parentElement && BLOCK_TAGS.test(n.parentElement.tagName)) return undefined
  }
  return undefined
}

/** A paragraph-like block with no blocks inside, so a credit check never drops a whole short article. */
function isLeafBlock(el: Element): boolean {
  if (!BLOCK_TAGS.test(el.tagName)) return false
  for (const c of el.children) if (BLOCK_TAGS.test(c.tagName) && tagOf(c) !== 'BR') return false
  return true
}

/** Clutter elements that read aloud skips with everything inside them. */
function isClutter(el: Element): boolean {
  const tag = tagOf(el)
  if (SKIP_TAGS.has(tag) || el.getAttribute('aria-hidden') === 'true') return true
  const role = el.getAttribute('role')
  if (role === 'doc-noteref' || role === 'doc-endnotes' || role === 'doc-footnote') return true
  // Ebooks mark note references and notes with epub:type
  const epubType = el.getAttributeNS?.(EPUB_NS, 'type')
  if (epubType && /\b(noteref|footnote|endnote|rearnote)s?\b/.test(epubType)) return true
  if (el.id && FOOTNOTE_SECTION.test(el.id)) return true
  if (tag === 'SUP') return isFootnoteMarker(el.textContent ?? '', !!el.closest('a'), charBefore(el))
  if (tag === 'A') return isFootnoteLink(el.getAttribute('href') ?? '', el.textContent ?? '')
  return false
}
const EPUB_NS = 'http://www.idpf.org/2007/ops'
/** Tag names are lower case in XHTML pages (ebooks). */
const tagOf = (el: Element) => el.tagName.toUpperCase()
const BLOCK_TAGS =
  /^(P|DIV|LI|H[1-6]|BLOCKQUOTE|PRE|TD|TH|SECTION|ARTICLE|HEADER|FOOTER|FIGURE|UL|OL|TABLE|TR|BR|HR|DT|DD)$/i

const hasSpeech = () => typeof window !== 'undefined' && 'speechSynthesis' in window
const cancelSpeech = () => {
  if (hasSpeech()) speechSynthesis.cancel()
}

/** Kokoro reads at most ~500 phonemes at once: split long sentences at punctuation, then spaces. */
export function chunkForKokoro(text: string, max = 280): { start: number; end: number }[] {
  const out: { start: number; end: number }[] = []
  let pos = 0
  while (pos < text.length) {
    let end = Math.min(text.length, pos + max)
    if (end < text.length) {
      const window = text.slice(pos, end)
      const punct = Math.max(window.lastIndexOf(', '), window.lastIndexOf('; '), window.lastIndexOf(': '), window.lastIndexOf(' – '), window.lastIndexOf(' — '))
      const space = window.lastIndexOf(' ')
      const cut = punct > max / 3 ? punct + 1 : space > 0 ? space : window.length
      end = pos + cut
    }
    if (text.slice(pos, end).trim()) out.push({ start: pos, end })
    pos = end
    while (pos < text.length && /\s/.test(text[pos])) pos++
  }
  return out
}

/**
 * The text read aloud from an element (clutter, footnote markers and citations left out), with
 * where each piece of it is in the page's text nodes.
 */
export function indexSpeechText(root: Element): { text: string; segments: Segment[] } {
  const raw: Segment[] = []
  let text = ''
  let lastBlock: Element | null = null
  const clutter = new Map<Element, boolean>()
  const blockOf = (n: Node): Element | null => {
    let block: Element | null = n.parentElement
    while (block && block !== root && !BLOCK_TAGS.test(block.tagName)) block = block.parentElement
    return block
  }
  const walker = root.ownerDocument.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
    acceptNode: (n) => {
      for (let p = n.parentElement; p && p !== root; p = p.parentElement) {
        let skip = clutter.get(p)
        if (skip === undefined) {
          skip = isClutter(p) || (isLeafBlock(p) && isSkippedLine(p.textContent ?? ''))
          clutter.set(p, skip)
        }
        if (skip) return NodeFilter.FILTER_REJECT
      }
      return n.textContent && n.textContent.length ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT
    },
  })
  for (let n = walker.nextNode() as Text | null; n; n = walker.nextNode() as Text | null) {
    const block = blockOf(n)
    if (lastBlock && block !== lastBlock && text && !/\s$/.test(text)) {
      // Paragraph break: end the sentence here even without punctuation
      text += '\n\n'
    }
    lastBlock = block
    const start = text.length
    text += n.textContent!
    raw.push({ node: n, start, end: text.length, nodeStart: 0 })
  }
  // Leave reference numbers, URLs and citations out of the spoken text; the pieces around them
  // keep pointing at their text nodes so highlights and tap-to-read still land on the right words
  const removed = new Uint8Array(text.length)
  for (const [s, e] of speechRemovals(text)) removed.fill(1, s, e)
  const newIndex = new Int32Array(text.length + 1)
  let kept = ''
  for (let i = 0; i < text.length; i++) {
    newIndex[i] = kept.length
    if (!removed[i]) kept += text[i]
  }
  newIndex[text.length] = kept.length
  const segments: Segment[] = []
  for (const g of raw) {
    for (let i = g.start; i < g.end; ) {
      if (removed[i]) {
        i++
        continue
      }
      let j = i
      while (j < g.end && !removed[j]) j++
      segments.push({ node: g.node, start: newIndex[i], end: newIndex[i] + (j - i), nodeStart: i - g.start })
      i = j
    }
  }
  return { text: kept, segments }
}

/** Sentences of [text] (split again at paragraph breaks), as read aloud speaks them. */
export function splitSentences(text: string, lang: string): Sentence[] {
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
    const seg = new Intl.Segmenter(lang, { granularity: 'sentence' })
    for (const s of seg.segment(text)) push(s.index, s.index + s.segment.length)
  } else {
    const re = /[^.!?\n]+[.!?]*["')\]]*\s*/g
    for (let m = re.exec(text); m; m = re.exec(text)) push(m.index, m.index + m[0].length)
  }
  return out
}

/** The text pieces the natural voice is asked for, sentence by sentence, as read aloud asks. */
export function kokoroPieces(text: string, lang: string): string[] {
  const out: string[] = []
  for (const s of splitSentences(text, lang)) {
    for (const c of chunkForKokoro(text.slice(s.start, s.end))) out.push(text.slice(s.start + c.start, s.start + c.end))
  }
  return out
}

type HighlightWindow = Window & { CSS?: typeof CSS; Highlight?: typeof Highlight }

/** The highlight registry of the window `el` is in: an ebook page lives in its own iframe. */
const highlightsOf = (el: Node | null) => {
  const w = (el?.ownerDocument?.defaultView ?? (typeof window !== 'undefined' ? window : null)) as HighlightWindow | null
  const css = w?.CSS
  return css && 'highlights' in css && w?.Highlight ? { registry: css.highlights, make: (r: Range) => new w.Highlight!(r) } : null
}

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
  private kokoroEl: HTMLAudioElement | null = null
  private kokoroUrl: string | null = null
  private kokoroCache = new Map<string, { id: number; audio: Promise<KokoroAudio> }>()
  private wordFrame: number | null = null
  /** Use the GPU for the natural voice when the browser supports WebGPU. */
  kokoroGpu = true
  /** Natural-voice audio made ahead of time (ebook chapters, bookAudio.ts), used before asking the voice. */
  storedAudio: { has(voice: string, text: string): boolean; load(voice: string, text: string): Promise<KokoroAudio> } | null = null
  private storedIds = 0
  /** Audio ran up to this point without a pause or skip, so a wait now is a stall. */
  private kokoroWasPlaying = false
  private kokoroAdvancing = false
  private gpuHintShown = false

  state: ReadAloudState = {
    status: 'idle',
    itemId: null,
    sentence: 0,
    sentences: 0,
    rate: 1,
    wordTiming: null,
    following: true,
    muted: false,
    wordStart: -1,
    word: '',
    offset: 0,
    textLength: 0,
    wpm: loadWpm(),
    wordIndex: 0,
    wordCount: 0,
  }
  /** Silent reader words (muted only). */
  private rsvp: RsvpWord[] = []
  /** Words shown since the silent reader (re)started, for the slower start. */
  private silentShown = 0
  /** Where play() continues after a move while paused. */
  private resumeOffset: number | null = null
  private scroller: HTMLElement | null = null
  private lastWord: { start: number; end: number } | null = null
  voiceURI = ''
  onFinished: ((itemId: string) => void) | null = null
  onError: ((message: string) => void) | null = null
  onMediaNext: (() => void) | null = null
  onMediaPrevious: (() => void) | null = null
  /**
   * Brings spoken text into view instead of scrolling the article, for readers that turn pages
   * (ebooks). Return false to fall back to scrolling.
   */
  revealHook: ((r: Range) => boolean) | null = null
  title = ''
  artist = ''

  get supported(): boolean {
    return hasSpeech() || isKokoroVoice(this.voiceURI)
  }

  /** The text indexed by [attach], which offsets refer to. Speed reading uses it too. */
  get indexedText(): string {
    return this.text
  }

  private get usingKokoro(): boolean {
    return isKokoroVoice(this.voiceURI)
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
    const sameItem = this.state.itemId === itemId
    if (this.state.itemId && !sameItem && (this.state.status !== 'idle' || this.state.muted)) this.stop()
    if (!sameItem) this.resumeOffset = null
    this.container = container
    this.watchUserScroll(container.closest('[data-scroll]') as HTMLElement | null)
    this.lang = lang || document.documentElement.lang || navigator.language || 'en'
    const { text, segments } = indexSpeechText(container)
    this.segments = segments
    this.text = text
    this.sentences = this.splitSentences(text)
    const patch: Partial<ReadAloudState> = {
      itemId,
      sentences: this.sentences.length,
      sentence: Math.min(this.state.sentence, this.sentences.length),
      textLength: text.length,
    }
    if (sameItem && this.state.muted) {
      // The text changed under the silent reader (full text, fast reading): keep the place
      const at = this.rsvp[this.state.wordIndex]?.start ?? 0
      this.rsvp = rsvpWords(text)
      patch.wordCount = this.rsvp.length
      patch.wordIndex = Math.min(wordIndexAt(this.rsvp, at), Math.max(0, this.rsvp.length - 1))
    }
    this.set(patch)
  }

  private splitSentences(text: string): Sentence[] {
    return splitSentences(text, this.lang)
  }

  /** DOM range for a span of the indexed text. */
  rangeFor(start: number, end: number): Range | null {
    const s = this.segments.find((g) => start >= g.start && start < g.end)
    const e = [...this.segments].reverse().find((g) => end > g.start && end <= g.end)
    if (!s || !e) return null
    const r = (this.container?.ownerDocument ?? document).createRange()
    r.setStart(s.node, s.nodeStart + start - s.start)
    r.setEnd(e.node, e.nodeStart + end - e.start)
    return r
  }

  /** Text offset of a DOM point inside the article, or -1. */
  offsetOf(node: Node, offset: number): number {
    const pieces = this.segments.filter((g) => g.node === node)
    if (!pieces.length) return -1
    // A point inside skipped clutter belongs to the next spoken piece of the node
    const seg = pieces.find((g) => offset <= g.nodeStart + (g.end - g.start)) ?? pieces[pieces.length - 1]
    return seg.start + Math.max(0, Math.min(offset - seg.nodeStart, seg.end - seg.start))
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
    const hl = highlightsOf(this.container)
    if (!hl) return
    hl.registry.delete('ra-tap')
    if (offset === null) return
    const w = this.wordAt(offset)
    const r = w && this.rangeFor(w.start, w.end)
    if (r) hl.registry.set('ra-tap', hl.make(r))
  }

  setRate(rate: number) {
    this.set({ rate })
    if (this.state.status !== 'playing' || this.state.muted) return
    // Recorded audio just plays faster; browser voices restart the sentence at the new rate
    if (this.usingKokoro && this.kokoroEl) this.kokoroEl.playbackRate = rate
    else this.speakFrom(this.state.sentence, null)
  }

  play(fromOffset?: number) {
    if (this.state.muted) {
      this.playSilent(fromOffset)
      return
    }
    if (!this.supported || !this.sentences.length) return
    this.startMedia()
    if (!this.state.following) this.set({ following: true })
    if ((fromOffset === undefined || fromOffset < 0) && this.resumeOffset !== null) fromOffset = this.resumeOffset
    this.resumeOffset = null
    if (fromOffset !== undefined && fromOffset >= 0) {
      this.speakFrom(this.sentenceIndexAt(fromOffset), fromOffset)
    } else {
      const i = this.state.sentence >= this.sentences.length ? 0 : this.state.sentence
      this.speakFrom(i, null)
    }
  }

  pause() {
    this.generation++
    cancelSpeech()
    this.stopKokoroAudio()
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
    cancelSpeech()
    this.stopKokoroAudio()
    // Nothing to read ahead for any more (the one sentence already being made still finishes)
    kokoro.dropWaiting(new Set())
    this.clearEstimate()
    highlightsOf(this.container)?.registry.delete('ra-sentence')
    highlightsOf(this.container)?.registry.delete('ra-word')
    this.silentAudio?.pause()
    if ('mediaSession' in navigator) navigator.mediaSession.playbackState = 'none'
    this.rsvp = []
    this.resumeOffset = null
    this.set({ status: 'idle', sentence: 0, wordTiming: null, following: true, muted: false, wordStart: -1, word: '', offset: 0, wordIndex: 0, wordCount: 0 })
  }

  skip(delta: number) {
    if (this.state.muted) {
      this.jumpToWord(this.state.wordIndex + delta * 10)
      return
    }
    const i = Math.max(0, Math.min(this.sentences.length - 1, this.state.sentence + delta))
    this.resumeOffset = null
    if (this.state.status === 'playing') this.speakFrom(i, null)
    else {
      this.set({ sentence: i })
      this.highlightSentence(i)
    }
  }

  /** Start of the last word in the text (0 when there is none). */
  private lastWordStart(): number {
    let i = this.text.length - 1
    while (i > 0 && !/[\p{L}\p{N}]/u.test(this.text[i])) i--
    return this.wordAt(i)?.start ?? Math.max(0, i)
  }

  /**
   * The word start at or after [offset]. Past the last word (seek bar at the end, End key,
   * +10 s near the end) it is the last word, so reading lands on the final word instead of
   * falling back to the start of the last sentence.
   */
  private snapToWord(offset: number): number {
    const last = this.lastWordStart()
    const o = Math.max(0, Math.min(this.text.length, Math.round(offset)))
    if (o >= last) return last
    const w = this.wordAt(o)
    if (w) return Math.min(w.start, last)
    let i = o
    while (i < this.text.length && !/[\p{L}\p{N}]/u.test(this.text[i])) i++
    return Math.min(i, last)
  }

  /** The silent reader reached the end: stop on the last word, still muted; play starts over. */
  private endSilent() {
    this.pause()
  }

  /** Moves the reading position to [offset]: reads on from there when playing, else waits there. */
  moveTo(offset: number) {
    if (this.state.muted) {
      this.jumpToWord(wordIndexAt(this.rsvp, offset))
      return
    }
    const at = this.snapToWord(offset)
    if (this.state.status === 'playing') {
      this.play(at)
      return
    }
    this.resumeOffset = at
    const i = this.sentenceIndexAt(at)
    this.set({ sentence: i })
    this.highlightSentence(i)
    const w = this.wordAt(at)
    if (w) this.highlightWord(w.start, w.end)
  }

  /** Back or ahead by about [seconds] of speech; muted, by that many words. */
  seekBy(seconds: number) {
    if (this.state.muted) {
      this.jumpToWord(this.state.wordIndex + Math.round(seconds))
      return
    }
    this.moveTo(this.state.offset + seconds * CHARS_PER_SECOND * this.state.rate)
  }

  /** Jumps to a fraction (0..1) of the article. */
  seekToFraction(fraction: number) {
    const f = Math.max(0, Math.min(1, fraction))
    if (this.state.muted) this.jumpToWord(Math.round(f * Math.max(0, this.rsvp.length - 1)))
    else this.moveTo(f * this.text.length)
  }

  /** Changes the voice; a sentence being spoken starts again in the new voice. */
  setVoice(voiceURI: string) {
    if (voiceURI === this.voiceURI) return
    this.voiceURI = voiceURI
    if (this.state.status === 'playing' && !this.state.muted) this.restartSentence()
  }

  /** Speaks the current sentence again from its current word (after a voice change). */
  restartSentence() {
    if (this.state.status !== 'playing' || this.state.muted) return
    this.speakFrom(this.state.sentence, this.lastWord?.start ?? null)
  }

  // --- Silent speed reading (muted) ---------------------------------------------------------

  /** Mutes the voice and hands over to a timer that moves through the words, or back. */
  setMuted(muted: boolean) {
    if (muted === this.state.muted) return
    const playing = this.state.status === 'playing'
    if (muted) {
      const s = this.sentences[this.state.sentence]
      const offset = this.resumeOffset ?? this.lastWord?.start ?? s?.start ?? 0
      this.generation++
      cancelSpeech()
      this.stopKokoroAudio()
      this.clearEstimate()
      this.resumeOffset = null
      this.rsvp = rsvpWords(this.text)
      const index = Math.min(wordIndexAt(this.rsvp, offset), Math.max(0, this.rsvp.length - 1))
      this.set({ muted: true, wordCount: this.rsvp.length, wordIndex: index, wordTiming: null })
      if (playing) {
        this.silentShown = 0
        this.runSilent(this.generation, index)
      } else this.showSilentWord(index)
    } else {
      const offset = this.rsvp[this.state.wordIndex]?.start ?? this.state.offset
      this.generation++
      this.clearEstimate()
      this.rsvp = []
      this.set({ muted: false, wordCount: 0, wordIndex: 0 })
      if (playing) this.speakFrom(this.sentenceIndexAt(offset), offset)
      else this.resumeOffset = offset
    }
  }

  /** Speed reads (muted) from [offset]. */
  speedReadFrom(offset: number) {
    if (!this.sentences.length) return
    if (this.state.status === 'playing' && !this.state.muted) this.pause()
    this.setMuted(true)
    this.playSilent(offset)
  }

  setWpm(wpm: number) {
    const v = Math.min(RSVP_MAX_WPM, Math.max(RSVP_MIN_WPM, Math.round(wpm / RSVP_WPM_STEP) * RSVP_WPM_STEP))
    try {
      localStorage.setItem(WPM_KEY, String(v))
    } catch {
      // storage blocked
    }
    this.set({ wpm: v })
  }

  /** Time left for the silent reader at the current speed. */
  silentMillisLeft(): number {
    return minutesLeft(this.rsvp, this.state.wordIndex, this.state.wpm) * 60000
  }

  private playSilent(fromOffset?: number) {
    if (!this.rsvp.length) return
    this.startMedia()
    let index = this.state.wordIndex
    if (fromOffset !== undefined && fromOffset >= 0) index = wordIndexAt(this.rsvp, fromOffset)
    else if (index >= this.rsvp.length - 1) index = 0
    this.set({ status: 'playing', following: true })
    if ('mediaSession' in navigator) navigator.mediaSession.playbackState = 'playing'
    this.silentShown = 0
    this.runSilent(++this.generation, index)
  }

  /** Jumps the silent reader to word [index]. */
  private jumpToWord(index: number) {
    if (!this.rsvp.length) return
    const i = Math.max(0, Math.min(this.rsvp.length - 1, index))
    if (this.state.status === 'playing') {
      this.silentShown = 0
      this.runSilent(++this.generation, i)
    } else this.showSilentWord(i)
  }

  private runSilent(gen: number, index: number) {
    this.clearEstimate()
    if (gen !== this.generation) return
    const word = this.rsvp[index]
    if (!word) {
      this.endSilent()
      return
    }
    this.showSilentWord(index)
    const ramp = RAMP[this.silentShown] ?? 1
    this.silentShown++
    this.estimateTimer = window.setTimeout(() => {
      if (gen !== this.generation) return
      // Unlike the voice, the silent reader never auto-continues to the next article (as on Android)
      if (index >= this.rsvp.length - 1) this.endSilent()
      else this.runSilent(gen, index + 1)
    }, wordDelay(word, this.state.wpm) * ramp)
  }

  /** Shows silent reader word [index] in the article (sentence and word highlight). */
  private showSilentWord(index: number) {
    const w = this.rsvp[index]
    if (!w) return
    const sentence = this.sentenceIndexAt(w.start)
    if (sentence !== this.state.sentence || !this.lastWord) {
      this.set({ sentence })
      this.highlightSentence(sentence)
    }
    // The word as it stands in the text: a long word split in pieces still highlights whole
    const whole = this.wordAt(w.start) ?? { start: w.start, end: w.start + w.text.length }
    this.set({ wordIndex: index })
    this.highlightWord(whole.start, Math.max(whole.end, w.start + 1), w.text)
  }

  private clearEstimate() {
    if (this.estimateTimer !== null) {
      clearTimeout(this.estimateTimer)
      this.estimateTimer = null
    }
    if (this.wordFrame !== null) {
      cancelAnimationFrame(this.wordFrame)
      this.wordFrame = null
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
    const hl = highlightsOf(this.container)
    const s = this.sentences[i]
    if (s && !this.state.muted) {
      const first = this.wordAt(s.start)
      this.set({ offset: s.start, wordStart: first?.start ?? -1, word: first ? this.text.slice(first.start, first.end) : '' })
    }
    this.lastWord = null
    if (!hl || !s) return
    const r = this.rangeFor(s.start, s.end)
    if (!r) return
    hl.registry.set('ra-sentence', hl.make(r))
    hl.registry.delete('ra-word')
    this.lastWord = null
    // Keep the start of the sentence in view (not its whole paragraph, which can be taller than the screen)
    const start = this.wordAt(s.start) ?? { start: s.start, end: Math.min(s.end, s.start + 1) }
    this.keepInView(this.rangeFor(start.start, start.end) ?? r)
  }

  private highlightWord(start: number, end: number, shown?: string) {
    this.lastWord = { start, end }
    this.set({ wordStart: start, offset: start, word: shown ?? this.text.slice(start, end).trim() })
    const hl = highlightsOf(this.container)
    if (!hl) return
    const r = this.rangeFor(start, end)
    if (!r) return
    hl.registry.set('ra-word', hl.make(r))
    this.lastWord = { start, end }
    // Long sentences keep scrolling with the voice
    this.keepInView(r)
  }

  /**
   * Auto scroll: when the spoken text leaves the comfortable reading band, scroll it back to
   * about a third of the way down. The reader scrolling away pauses this until "Go to spoken text".
   */
  private keepInView(r: Range, force = false) {
    if (!this.state.following && !force) return
    if (this.revealHook?.(r)) return
    const scroller = this.scroller ?? (this.container?.closest('[data-scroll]') as HTMLElement | null)
    const rect = r.getClientRects()[0] ?? r.getBoundingClientRect()
    if (!rect || (rect.width === 0 && rect.height === 0)) return
    const view = scroller?.getBoundingClientRect() ?? { top: 0, height: window.innerHeight }
    const top = rect.top - view.top
    if (!force && top > view.height * 0.12 && rect.bottom - view.top < view.height * 0.72) return
    // An absolute target stays right when called again while a smooth scroll is still moving
    const by = top - view.height * 0.33
    if (scroller) scroller.scrollTo({ top: Math.max(0, scroller.scrollTop + by), behavior: 'smooth' })
    else window.scrollTo({ top: Math.max(0, window.scrollY + by), behavior: 'smooth' })
  }

  /** Scrolls back to the spoken text and follows it again. */
  goToSpokenText() {
    this.set({ following: true })
    const s = this.sentences[this.state.sentence]
    const w = this.lastWord ?? (s ? this.wordAt(s.start) : null)
    const r = w && this.rangeFor(w.start, w.end)
    if (r) this.keepInView(r, true)
  }

  /** A wheel, swipe or scroll key on the article means the reader wants to look elsewhere. */
  private watchUserScroll(scroller: HTMLElement | null) {
    if (scroller === this.scroller) return
    const opts: AddEventListenerOptions = { passive: true }
    this.scroller?.removeEventListener('wheel', this.onUserScroll, opts)
    this.scroller?.removeEventListener('touchmove', this.onUserScroll, opts)
    this.scroller?.removeEventListener('keydown', this.onScrollKey)
    this.scroller = scroller
    scroller?.addEventListener('wheel', this.onUserScroll, opts)
    scroller?.addEventListener('touchmove', this.onUserScroll, opts)
    scroller?.addEventListener('keydown', this.onScrollKey)
  }

  /** The reader scrolled a page this can't watch (a ZIM page's iframe): stop following, as onUserScroll. */
  userScrolled() {
    this.onUserScroll()
  }

  private onUserScroll = () => {
    if (this.state.following && this.state.status === 'playing') this.set({ following: false })
  }

  private onScrollKey = (e: KeyboardEvent) => {
    if (['ArrowUp', 'ArrowDown', 'PageUp', 'PageDown', 'Home', 'End', ' '].includes(e.key)) this.onUserScroll()
  }

  private speakFrom(index: number, fromOffset: number | null) {
    const gen = ++this.generation
    // Only moving on to the next sentence by itself keeps counting as uninterrupted playback
    if (!this.kokoroAdvancing) this.kokoroWasPlaying = false
    this.kokoroAdvancing = false
    cancelSpeech()
    this.stopKokoroAudio()
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
    if (word && begin > sentence.start) {
      // Starting mid-sentence: the position (and the word display) starts at the tapped word
      this.lastWord = word
      this.set({ offset: begin, wordStart: begin, word: this.text.slice(word.start, word.end) })
    }
    if ('mediaSession' in navigator) navigator.mediaSession.playbackState = 'playing'

    if (this.usingKokoro) {
      void this.speakKokoro(gen, index, begin, sentence.end)
      return
    }

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

  // --- Natural voice (Kokoro): generated on this device, played as audio --------------------

  private kokoroFor(voice: string, text: string): { id: number; audio: Promise<KokoroAudio> } {
    const key = `${voice}\n${text}`
    let entry = this.kokoroCache.get(key)
    const stored = this.storedAudio
    if (!entry && stored?.has(voice, text)) {
      // Prepared ahead (an ebook chapter): no need to wait for the voice
      entry = { id: -++this.storedIds, audio: stored.load(voice, text).catch(() => kokoro.generate(text, voice).audio) }
      this.kokoroCache.set(key, entry)
    }
    if (!entry) {
      const made = kokoro.generate(text, voice)
      entry = made
      made.audio.catch(() => {
        if (this.kokoroCache.get(key) === made) this.kokoroCache.delete(key)
      })
      this.kokoroCache.set(key, made)
    }
    return entry
  }

  /**
   * Asks for the sentence being read (first in line) and enough of the following ones to cover
   * about KOKORO_AHEAD_SECONDS of listening, so the model keeps working while audio plays instead
   * of waiting for each sentence to start. Waiting requests outside that window are dropped.
   */
  private kokoroWindow(voice: string, index: number, begin: number, end: number) {
    const current = this.kokoroChunks(begin, end).map((c) => ({ ...c, ...this.kokoroFor(voice, this.text.slice(c.start, c.end)) }))
    const keep = new Set(current.map((c) => c.id))
    const aheadChars = KOKORO_AHEAD_SECONDS * CHARS_PER_SECOND * Math.max(1, this.state.rate)
    let chars = 0
    for (let i = index + 1; i < this.sentences.length && i <= index + KOKORO_AHEAD_MAX_SENTENCES && chars < aheadChars; i++) {
      const s = this.sentences[i]
      for (const c of this.kokoroChunks(s.start, s.end)) keep.add(this.kokoroFor(voice, this.text.slice(c.start, c.end)).id)
      chars += s.end - s.start
    }
    const dropped = new Set(kokoro.dropWaiting(keep))
    for (const [key, e] of this.kokoroCache) {
      if (dropped.has(e.id)) this.kokoroCache.delete(key)
    }
    kokoro.prioritize(current.map((c) => c.id))
    // Keep recent audio (going back a sentence is common), drop the oldest beyond that
    for (const [key, e] of this.kokoroCache) {
      if (this.kokoroCache.size <= KOKORO_CACHE_SIZE) break
      if (!keep.has(e.id)) this.kokoroCache.delete(key)
    }
    return current
  }

  /** The text chunks (absolute offsets) to generate for a sentence. */
  private kokoroChunks(begin: number, end: number) {
    return chunkForKokoro(this.text.slice(begin, end)).map((c) => ({ start: begin + c.start, end: begin + c.end }))
  }

  private stopKokoroAudio() {
    if (this.kokoroEl) {
      this.kokoroEl.onended = null
      this.kokoroEl.onerror = null
      this.kokoroEl.pause()
    }
    if (this.kokoroUrl) {
      URL.revokeObjectURL(this.kokoroUrl)
      this.kokoroUrl = null
    }
  }

  private async speakKokoro(gen: number, index: number, begin: number, end: number) {
    const voice = this.voiceURI.slice(KOKORO_PREFIX.length)
    if (this.state.wordTiming !== 'estimated') this.set({ wordTiming: 'estimated' })
    try {
      await kokoro.load(this.kokoroGpu)
    } catch (e) {
      if (gen !== this.generation) return
      this.onError?.(`The natural voice could not load (${e instanceof Error ? e.message : e}).`)
      this.stop()
      return
    }
    if (gen !== this.generation) return
    const chunks = this.kokoroWindow(voice, index, begin, end)
    for (const chunk of chunks) {
      if (gen !== this.generation) return
      let audio: KokoroAudio
      const waitFrom = performance.now()
      try {
        audio = await chunk.audio
        // The processor makes speech slower than it plays, so it falls behind; the GPU keeps up
        if (this.kokoroWasPlaying && performance.now() - waitFrom > 800 && !this.kokoroGpu && !this.gpuHintShown && webGpuAvailable()) {
          this.gpuHintShown = true
          this.onError?.('The natural voice is falling behind. Turn on "Use the graphics card" in Settings for smooth playback.')
        }
      } catch (e) {
        if (gen !== this.generation) return
        console.warn('Natural voice failed', e)
        if (++this.failures >= 3) {
          this.failures = 0
          this.onError?.('The natural voice keeps failing. Try a browser voice in Settings.')
          this.stop()
          return
        }
        continue
      }
      if (gen !== this.generation) return
      this.kokoroWasPlaying = true
      const ok = await this.playKokoroChunk(gen, audio, chunk.start, chunk.end)
      if (!ok) return
    }
    if (gen !== this.generation) return
    this.failures = 0
    this.kokoroAdvancing = true
    this.speakFrom(index + 1, null)
  }

  /** Plays one chunk; resolves true when it finished, false when interrupted. */
  private playKokoroChunk(gen: number, audio: KokoroAudio, start: number, end: number): Promise<boolean> {
    return new Promise((resolve) => {
      this.stopKokoroAudio()
      const el = (this.kokoroEl ??= new Audio())
      this.kokoroUrl = URL.createObjectURL(wavBlob(audio))
      el.src = this.kokoroUrl
      el.playbackRate = this.state.rate
      el.preservesPitch = true
      el.onended = () => {
        this.clearEstimate()
        resolve(gen === this.generation)
      }
      el.onerror = () => resolve(false)
      el.play().then(
        () => this.followWords(gen, el, start, end, audio.samples.length / audio.sampleRate),
        (e) => {
          console.warn('Audio playback failed', e)
          resolve(false)
        },
      )
    })
  }

  /** Word highlight from the audio position: words get time in proportion to their length. */
  private followWords(gen: number, el: HTMLAudioElement, from: number, to: number, duration: number) {
    const words: { start: number; end: number; at: number }[] = []
    const re = /\S+/g
    const slice = this.text.slice(from, to)
    let total = 0
    for (let m = re.exec(slice); m; m = re.exec(slice)) {
      words.push({ start: from + m.index, end: from + m.index + m[0].length, at: total })
      total += m[0].length + 1
    }
    if (!words.length) return
    // Generated speech has a little silence at both ends
    const lead = Math.min(0.15, duration * 0.05)
    const span = Math.max(0.1, duration - lead * 2)
    let shown = -1
    const tick = () => {
      if (gen !== this.generation) return
      const frac = (el.currentTime - lead) / span
      let i = 0
      while (i + 1 < words.length && words[i + 1].at / total <= frac) i++
      if (i !== shown) {
        shown = i
        this.highlightWord(words[i].start, words[i].end)
      }
      this.wordFrame = requestAnimationFrame(tick)
    }
    tick()
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
    this.silentAudio.play()?.catch(() => undefined)
    if (!('mediaSession' in navigator)) return
    const ms = navigator.mediaSession
    ms.metadata = new MediaMetadata({ title: this.title || 'Feeder', artist: this.artist || 'Read aloud', album: 'Feeder Web' })
    const handlers: [MediaSessionAction, (() => void) | null][] = [
      ['play', () => this.play()],
      ['pause', () => this.pause()],
      ['stop', () => this.stop()],
      ['seekbackward', () => this.seekBy(-10)],
      ['seekforward', () => this.seekBy(10)],
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
