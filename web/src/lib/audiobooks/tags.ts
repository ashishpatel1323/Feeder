/**
 * Reads what an audiobook file says about itself: length, title, author, cover and chapters.
 *
 * MP4 (m4b, m4a, mp4): the length from `mvhd`, tags from `udta/meta/ilst`, and chapters from the
 * Nero `udta/chpl` atom or, failing that, a QuickTime chapter text track (`tref/chap`). This
 * follows the Voice audiobook player's MP4 chapter reader (github.com/PaulWoitaschek/Voice,
 * GPL-3.0), written again here for the browser.
 * MP3: ID3v2 tags (TIT2, TALB, TPE1/TPE2, APIC) and ID3 chapters (CHAP frames).
 *
 * Everything reads through a [RangeReader], so only the few kilobytes or megabytes of metadata are
 * fetched from Dropbox, never the audio itself.
 */

export interface RangeReader {
  size: number
  read(offset: number, length: number): Promise<Uint8Array>
}

export interface Mark {
  /** Start, in milliseconds from the start of the file */
  startMs: number
  title: string
}

export interface FileTags {
  /** Length in seconds, when the file says (MP4 does; for MP3 the player measures it) */
  duration: number | null
  title: string | null
  album: string | null
  artist: string | null
  /** Cover picture bytes and type, when the file has one */
  cover: { data: Uint8Array; type: string } | null
  chapters: Mark[]
}

const empty = (): FileTags => ({ duration: null, title: null, album: null, artist: null, cover: null, chapters: [] })

/** A reader over bytes already in memory (tests, local files). */
export function bytesReader(bytes: Uint8Array): RangeReader {
  return { size: bytes.length, read: async (o, l) => bytes.subarray(o, Math.min(bytes.length, o + l)) }
}

/** A reader over a Blob or File. */
export function blobReader(blob: Blob): RangeReader {
  return { size: blob.size, read: async (o, l) => new Uint8Array(await blob.slice(o, o + l).arrayBuffer()) }
}

const latin1 = new TextDecoder('latin1')
const utf8 = new TextDecoder('utf-8')
const fourcc = (b: Uint8Array, at: number) => latin1.decode(b.subarray(at, at + 4))
const u32 = (b: Uint8Array, at: number) => ((b[at] << 24) >>> 0) + (b[at + 1] << 16) + (b[at + 2] << 8) + b[at + 3]
const u64 = (b: Uint8Array, at: number) => u32(b, at) * 2 ** 32 + u32(b, at + 4)
const u16 = (b: Uint8Array, at: number) => (b[at] << 8) + b[at + 1]
const clean = (s: string) => s.replace(/\u0000+$/g, '').trim()

export function isMp4Name(name: string) {
  return /\.(m4b|m4a|mp4|aac)$/i.test(name)
}

/** Reads the tags of a file, by its name's extension (unknown types give only empty tags). */
export async function readTags(name: string, r: RangeReader): Promise<FileTags> {
  try {
    if (isMp4Name(name)) return await readMp4(r)
    if (/\.mp3$/i.test(name)) return await readId3(r)
  } catch (e) {
    console.warn('Could not read the tags of', name, e)
  }
  return empty()
}

// ---------------------------------------------------------------------------------------------
// MP4

interface Box {
  type: string
  /** Where the box's contents start and end, within the buffer it was found in */
  start: number
  end: number
}

/** The boxes directly inside b[from, to). */
function boxes(b: Uint8Array, from: number, to: number): Box[] {
  const out: Box[] = []
  let at = from
  while (at + 8 <= to) {
    let size = u32(b, at)
    const type = fourcc(b, at + 4)
    let header = 8
    if (size === 1) {
      if (at + 16 > to) break
      size = u64(b, at + 8)
      header = 16
    } else if (size === 0) size = to - at
    if (size < header || at + size > to) break
    out.push({ type, start: at + header, end: at + size })
    at += size
  }
  return out
}

const child = (b: Uint8Array, box: Box | undefined, type: string, skip = 0) => (box ? boxes(b, box.start + skip, box.end).find((x) => x.type === type) : undefined)
const path = (b: Uint8Array, box: Box | undefined, ...types: string[]) => types.reduce<Box | undefined>((at, t) => child(b, at, t), box)

/** Finds the top-level `moov` by reading only box headers, wherever it is in the file. */
async function findMoov(r: RangeReader): Promise<Uint8Array | null> {
  let at = 0
  for (let n = 0; n < 64 && at + 8 <= r.size; n++) {
    const h = await r.read(at, 16)
    if (h.length < 8) return null
    let size = u32(h, 0)
    const type = fourcc(h, 4)
    if (size === 1) size = u64(h, 8)
    else if (size === 0) size = r.size - at
    if (size < 8) return null
    if (type === 'moov') {
      // Chapter pictures can make it big, but not this big
      if (size > 64 * 1024 * 1024) return null
      const moov = await r.read(at, size)
      return moov.length === size ? moov : null
    }
    at += size
  }
  return null
}

export async function readMp4(r: RangeReader): Promise<FileTags> {
  const out = empty()
  const b = await findMoov(r)
  if (!b) return out
  const moov = boxes(b, 0, b.length)[0]
  if (!moov || moov.type !== 'moov') return out

  const mvhd = child(b, moov, 'mvhd')
  if (mvhd) {
    const v = b[mvhd.start]
    const scale = v === 1 ? u32(b, mvhd.start + 20) : u32(b, mvhd.start + 12)
    const dur = v === 1 ? u64(b, mvhd.start + 24) : u32(b, mvhd.start + 16)
    if (scale > 0 && dur > 0) out.duration = dur / scale
  }

  const udta = child(b, moov, 'udta')
  readIlst(b, udta, out)
  // QuickTime puts tags straight in moov/meta too
  if (!out.title && !out.album) readIlst(b, moov, out)

  const chpl = child(b, udta, 'chpl')
  if (chpl) out.chapters = readChpl(b, chpl)
  if (!out.chapters.length) out.chapters = await readChapterTrack(b, moov, r)
  out.chapters = tidyMarks(out.chapters, out.duration)
  return out
}

function readIlst(b: Uint8Array, parent: Box | undefined, out: FileTags) {
  const meta = child(b, parent, 'meta')
  if (!meta) return
  // iTunes' meta is a full box (4 bytes of version and flags); QuickTime's is not
  const full = fourcc(b, meta.start + 4) !== 'hdlr'
  const ilst = child(b, meta, 'ilst', full ? 4 : 0)
  if (!ilst) return
  for (const item of boxes(b, ilst.start, ilst.end)) {
    const data = child(b, item, 'data')
    if (!data || data.end - data.start < 8) continue
    const kind = u32(b, data.start) & 0xffffff
    const payload = b.subarray(data.start + 8, data.end)
    const text = () => clean(utf8.decode(payload)) || null
    switch (item.type) {
      case '©nam':
        out.title ??= text()
        break
      case '©alb':
        out.album ??= text()
        break
      case '©ART':
      case 'aART':
      case '©wrt':
        out.artist ??= text()
        break
      case 'covr':
        if (!out.cover && payload.length) out.cover = { data: payload.slice(), type: kind === 14 ? 'image/png' : 'image/jpeg' }
        break
    }
  }
}

/** Nero chapters: times in 100 ns units. */
function readChpl(b: Uint8Array, chpl: Box): Mark[] {
  const v = b[chpl.start]
  if (v !== 0 && v !== 1) return []
  let at = chpl.start + 4 + (v === 1 ? 4 : 0)
  const count = b[at++]
  const out: Mark[] = []
  for (let i = 0; i < count && at < chpl.end; i++) {
    const t = v === 1 ? u64(b, at) : u32(b, at)
    at += v === 1 ? 8 : 4
    const len = b[at++]
    out.push({ startMs: Math.round(t / 10_000), title: clean(utf8.decode(b.subarray(at, at + len))) })
    at += len
  }
  return out
}

interface Track {
  id: number
  box: Box
}

/** A QuickTime chapter track: an audio track's `tref/chap` names a text track of chapter titles. */
async function readChapterTrack(b: Uint8Array, moov: Box, r: RangeReader): Promise<Mark[]> {
  const tracks: Track[] = boxes(b, moov.start, moov.end)
    .filter((x) => x.type === 'trak')
    .map((box) => {
      const tkhd = child(b, box, 'tkhd')
      return { id: tkhd ? u32(b, tkhd.start + (b[tkhd.start] === 1 ? 20 : 12)) : 0, box }
    })
  let chapId = 0
  for (const t of tracks) {
    const chap = path(b, t.box, 'tref', 'chap')
    if (chap && chap.end - chap.start >= 4) {
      chapId = u32(b, chap.start)
      break
    }
  }
  const track = tracks.find((t) => t.id === chapId)
  if (!track) return []
  const mdia = child(b, track.box, 'mdia')
  const mdhd = child(b, mdia, 'mdhd')
  const stbl = path(b, mdia, 'minf', 'stbl')
  if (!mdhd || !stbl) return []
  const scale = b[mdhd.start] === 1 ? u32(b, mdhd.start + 20) : u32(b, mdhd.start + 12)
  if (!scale) return []

  // Sample durations (stts)
  const durations: number[] = []
  const stts = child(b, stbl, 'stts')
  if (stts) {
    const n = u32(b, stts.start + 4)
    for (let i = 0; i < n; i++) {
      const count = u32(b, stts.start + 8 + i * 8)
      const delta = u32(b, stts.start + 12 + i * 8)
      for (let k = 0; k < count && durations.length < 10_000; k++) durations.push(delta)
    }
  }
  // Sample sizes (stsz)
  const sizes: number[] = []
  const stsz = child(b, stbl, 'stsz')
  if (stsz) {
    const fixed = u32(b, stsz.start + 4)
    const n = u32(b, stsz.start + 8)
    for (let i = 0; i < n && i < 10_000; i++) sizes.push(fixed || u32(b, stsz.start + 12 + i * 4))
  }
  // Chunk offsets (stco / co64)
  const offsets: number[] = []
  const stco = child(b, stbl, 'stco')
  const co64 = child(b, stbl, 'co64')
  if (stco) for (let i = 0, n = u32(b, stco.start + 4); i < n; i++) offsets.push(u32(b, stco.start + 8 + i * 4))
  else if (co64) for (let i = 0, n = u32(b, co64.start + 4); i < n; i++) offsets.push(u64(b, co64.start + 8 + i * 8))
  // Samples per chunk (stsc)
  const stsc = child(b, stbl, 'stsc')
  const runs: { first: number; per: number }[] = []
  if (stsc) for (let i = 0, n = u32(b, stsc.start + 4); i < n; i++) runs.push({ first: u32(b, stsc.start + 8 + i * 12), per: u32(b, stsc.start + 12 + i * 12) })
  if (!runs.length) runs.push({ first: 1, per: 1 })

  // Where each sample is in the file
  const where: number[] = []
  offsets.forEach((off, c) => {
    let per = 1
    for (const run of runs) if (c + 1 >= run.first) per = run.per
    let at = off
    for (let k = 0; k < per && where.length < sizes.length; k++) {
      where.push(at)
      at += sizes[where.length - 1] ?? 0
    }
  })
  const n = Math.min(where.length, sizes.length || where.length)
  if (!n) return []

  // Read the titles: all at once when they sit close together, else one by one
  const lo = Math.min(...where.slice(0, n))
  const hi = Math.max(...where.slice(0, n).map((w, i) => w + (sizes[i] ?? 256)))
  const block = hi - lo <= 4 * 1024 * 1024 ? await r.read(lo, hi - lo) : null
  const out: Mark[] = []
  let t = 0
  for (let i = 0; i < n; i++) {
    const s = block ? block.subarray(where[i] - lo, where[i] - lo + (sizes[i] ?? 256)) : await r.read(where[i], sizes[i] ?? 256)
    out.push({ startMs: Math.round((t * 1000) / scale), title: textSample(s) })
    t += durations[i] ?? 0
  }
  return out
}

/** A 3GPP/QuickTime text sample: 16-bit length, then UTF-8 or UTF-16 (with BOM) text. */
function textSample(s: Uint8Array): string {
  if (s.length < 2) return ''
  const len = Math.min(u16(s, 0), s.length - 2)
  const t = s.subarray(2, 2 + len)
  if (t[0] === 0xfe && t[1] === 0xff) return clean(new TextDecoder('utf-16be').decode(t.subarray(2)))
  if (t[0] === 0xff && t[1] === 0xfe) return clean(new TextDecoder('utf-16le').decode(t.subarray(2)))
  return clean(utf8.decode(t))
}

/** Sorted, de-duplicated, inside the file, and named ("Chapter 3" for blank ones). */
export function tidyMarks(marks: Mark[], duration: number | null): Mark[] {
  const max = duration ? duration * 1000 : Infinity
  const out: Mark[] = []
  for (const m of [...marks].sort((a, b) => a.startMs - b.startMs)) {
    if (!Number.isFinite(m.startMs) || m.startMs < 0 || m.startMs >= max) continue
    if (out.length && m.startMs - out[out.length - 1].startMs < 500) continue
    out.push({ startMs: m.startMs, title: m.title || `Chapter ${out.length + 1}` })
  }
  // One mark that isn't at the start says nothing useful
  if (out.length === 1) return []
  if (out.length && out[0].startMs > 0) out[0] = { ...out[0], startMs: 0 }
  return out
}

// ---------------------------------------------------------------------------------------------
// ID3v2 (MP3)

const syncsafe = (b: Uint8Array, at: number) => (b[at] << 21) | (b[at + 1] << 14) | (b[at + 2] << 7) | b[at + 3]

function id3Text(b: Uint8Array): string {
  const enc = b[0]
  const body = b.subarray(1)
  if (enc === 1) return clean(new TextDecoder('utf-16').decode(body))
  if (enc === 2) return clean(new TextDecoder('utf-16be').decode(body))
  if (enc === 3) return clean(utf8.decode(body))
  return clean(latin1.decode(body))
}

/** Index just past a terminator for [enc] starting at [at]. */
function skipTerminated(b: Uint8Array, at: number, enc: number): number {
  if (enc === 1 || enc === 2) {
    for (let i = at; i + 1 < b.length; i += 2) if (b[i] === 0 && b[i + 1] === 0) return i + 2
    return b.length
  }
  const i = b.indexOf(0, at)
  return i < 0 ? b.length : i + 1
}

interface Frame {
  id: string
  data: Uint8Array
}

function frames(b: Uint8Array, from: number, to: number, major: number): Frame[] {
  const out: Frame[] = []
  let at = from
  while (at + 10 <= to) {
    const id = latin1.decode(b.subarray(at, at + 4))
    if (!/^[A-Z0-9]{4}$/.test(id)) break
    const size = major >= 4 ? syncsafe(b, at + 4) : u32(b, at + 4)
    if (size <= 0 || at + 10 + size > to) break
    out.push({ id, data: b.subarray(at + 10, at + 10 + size) })
    at += 10 + size
  }
  return out
}

export async function readId3(r: RangeReader): Promise<FileTags> {
  const out = empty()
  const head = await r.read(0, 10)
  if (head.length < 10 || latin1.decode(head.subarray(0, 3)) !== 'ID3') return out
  const major = head[3]
  if (major < 3) return out
  const size = syncsafe(head, 6)
  if (size > 32 * 1024 * 1024) return out
  const b = await r.read(0, 10 + size)
  let from = 10
  // Extended header
  if (head[5] & 0x40) from += major >= 4 ? syncsafe(b, 10) : u32(b, 10) + 4
  for (const f of frames(b, from, b.length, major)) {
    switch (f.id) {
      case 'TIT2':
        out.title ??= id3Text(f.data) || null
        break
      case 'TALB':
        out.album ??= id3Text(f.data) || null
        break
      case 'TPE1':
      case 'TPE2':
        out.artist ??= id3Text(f.data) || null
        break
      case 'APIC': {
        if (out.cover) break
        const enc = f.data[0]
        const mimeEnd = f.data.indexOf(0, 1)
        const mime = latin1.decode(f.data.subarray(1, mimeEnd)) || 'image/jpeg'
        const descEnd = skipTerminated(f.data, mimeEnd + 2, enc)
        out.cover = { data: f.data.slice(descEnd), type: mime.includes('/') ? mime : `image/${mime.toLowerCase()}` }
        break
      }
      case 'CHAP': {
        const idEnd = f.data.indexOf(0)
        if (idEnd < 0) break
        const at = idEnd + 1
        const startMs = u32(f.data, at)
        const sub = frames(f.data, at + 16, f.data.length, major)
        const tit = sub.find((s) => s.id === 'TIT2')
        out.chapters.push({ startMs, title: tit ? id3Text(tit.data) : '' })
        break
      }
    }
  }
  out.chapters = tidyMarks(out.chapters, null)
  return out
}
