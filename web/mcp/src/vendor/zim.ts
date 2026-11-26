/**
 * Reads ZIM files (offline websites such as Wikipedia, made by Kiwix/openZIM) straight from a
 * Blob, without loading them into memory: only the header, the directory entries that a lookup
 * touches and the clusters that hold the wanted items are read.
 *
 * Format: https://wiki.openzim.org/wiki/ZIM_file_format. Clusters are zstd (current ZIMs),
 * xz (older ZIMs) or stored uncompressed (most images).
 */
import { decompress as zstdDecompress } from 'fzstd'

export interface ZimSource {
  size: number
  read(offset: number, length: number): Promise<Uint8Array>
}

export function blobSource(blob: Blob): ZimSource {
  return {
    size: blob.size,
    read: async (offset, length) => new Uint8Array(await blob.slice(offset, Math.min(blob.size, offset + length)).arrayBuffer()),
  }
}

export interface Dirent {
  /** Index in the path-ordered list. */
  index: number
  mimeIndex: number
  namespace: string
  /** Raw UTF-8 bytes of the path, for exact ordering. */
  pathBytes: Uint8Array
  path: string
  title: string
  redirect: boolean
  redirectIndex: number
  cluster: number
  blob: number
}

export interface ZimItem {
  mime: string
  data: Uint8Array
  /** The path that was served, after redirects ("A/Foo" in old ZIMs, "Foo" in new ones). */
  path: string
  title: string
}

const MAGIC = 72173914
const REDIRECT = 0xffff
const NONE = 0xffffffff
const utf8 = new TextDecoder()
const enc = new TextEncoder()

function u64(v: DataView, at: number): number {
  return Number(v.getBigUint64(at, true))
}

function compareBytes(a: Uint8Array, b: Uint8Array): number {
  const n = Math.min(a.length, b.length)
  for (let i = 0; i < n; i++) if (a[i] !== b[i]) return a[i] - b[i]
  return a.length - b.length
}

function startsWith(a: Uint8Array, prefix: Uint8Array): boolean {
  if (a.length < prefix.length) return false
  for (let i = 0; i < prefix.length; i++) if (a[i] !== prefix[i]) return false
  return true
}

/** A tiny least-recently-used cache. */
class Lru<K, V> {
  private map = new Map<K, V>()
  constructor(private max: number) {}
  get(k: K): V | undefined {
    const v = this.map.get(k)
    if (v !== undefined) {
      this.map.delete(k)
      this.map.set(k, v)
    }
    return v
  }
  set(k: K, v: V) {
    this.map.delete(k)
    this.map.set(k, v)
    if (this.map.size > this.max) this.map.delete(this.map.keys().next().value as K)
  }
}

interface Cluster {
  /** Decompressed cluster (after the info byte), for compressed clusters. */
  data?: Uint8Array
  /** File offset of the first byte after the info byte, for uncompressed clusters. */
  rawStart?: number
  extended: boolean
}

export class ZimArchive {
  readonly uuid: string
  readonly entryCount: number
  readonly clusterCount: number
  readonly mainPage: number
  readonly majorVersion: number
  readonly minorVersion: number
  mimeTypes: string[] = []
  private pathPtrPos: number
  private titlePtrPos: number
  private clusterPtrPos: number
  private checksumPos: number
  private dirents = new Lru<number, Dirent>(4096)
  private clusters = new Lru<number, Promise<Cluster>>(12)
  /** Front-article title index (X/listing/titleOrdered/v1): file offset of the u32 list and its length. */
  private titleListing: { offset?: number; data?: Uint8Array; count: number } | null | undefined
  /** New-namespace ZIMs keep every content item in "C" and links leave the namespace out. */
  newNamespace = false

  private constructor(
    readonly src: ZimSource,
    header: DataView,
  ) {
    if (header.getUint32(0, true) !== MAGIC) throw new Error('This is not a ZIM file')
    this.majorVersion = header.getUint16(4, true)
    this.minorVersion = header.getUint16(6, true)
    this.uuid = [...new Uint8Array(header.buffer, header.byteOffset + 8, 16)].map((b) => b.toString(16).padStart(2, '0')).join('')
    this.entryCount = header.getUint32(24, true)
    this.clusterCount = header.getUint32(28, true)
    this.pathPtrPos = u64(header, 32)
    this.titlePtrPos = u64(header, 40)
    this.clusterPtrPos = u64(header, 48)
    this.mainPage = header.getUint32(64, true)
    this.checksumPos = u64(header, 72)
  }

  static async open(src: ZimSource): Promise<ZimArchive> {
    const head = await src.read(0, 80)
    if (head.length < 80) throw new Error('This is not a ZIM file')
    const zim = new ZimArchive(src, new DataView(head.buffer, head.byteOffset, 80))
    const mimeListPos = u64(new DataView(head.buffer, head.byteOffset, 80), 56)
    // The MIME list ends with an empty string; it sits between the header and the path pointers
    const mimeBytes = await src.read(mimeListPos, Math.max(64, Math.min(64 * 1024, zim.pathPtrPos - mimeListPos)))
    let at = 0
    while (at < mimeBytes.length) {
      const end = mimeBytes.indexOf(0, at)
      if (end <= at) break
      zim.mimeTypes.push(utf8.decode(mimeBytes.subarray(at, end)))
      at = end + 1
    }
    // New-namespace ZIMs (minor version 1+) put content in C
    zim.newNamespace = zim.majorVersion >= 6 && zim.minorVersion >= 1
    return zim
  }

  mime(d: Dirent): string {
    return this.mimeTypes[d.mimeIndex] ?? 'application/octet-stream'
  }

  private async ptr(index: number): Promise<number> {
    const b = await this.src.read(this.pathPtrPos + index * 8, 8)
    return u64(new DataView(b.buffer, b.byteOffset, 8), 0)
  }

  async direntAt(index: number): Promise<Dirent> {
    const hit = this.dirents.get(index)
    if (hit) return hit
    const offset = await this.ptr(index)
    let len = 512
    for (;;) {
      const b = await this.src.read(offset, len)
      const d = this.parseDirent(b, index)
      if (d) {
        this.dirents.set(index, d)
        return d
      }
      if (offset + len >= this.src.size) throw new Error('Broken ZIM entry')
      len *= 4
    }
  }

  private parseDirent(b: Uint8Array, index: number): Dirent | null {
    const v = new DataView(b.buffer, b.byteOffset, b.length)
    if (b.length < 12) return null
    const mimeIndex = v.getUint16(0, true)
    const namespace = String.fromCharCode(b[3])
    const redirect = mimeIndex === REDIRECT
    let at = redirect ? 12 : 16
    if (b.length < at) return null
    const pathEnd = b.indexOf(0, at)
    if (pathEnd < 0) return null
    const titleEnd = b.indexOf(0, pathEnd + 1)
    if (titleEnd < 0) return null
    const pathBytes = b.slice(at, pathEnd)
    const path = utf8.decode(pathBytes)
    const title = titleEnd > pathEnd + 1 ? utf8.decode(b.subarray(pathEnd + 1, titleEnd)) : path
    at = titleEnd + 1
    return {
      index,
      mimeIndex,
      namespace,
      pathBytes,
      path,
      title,
      redirect,
      redirectIndex: redirect ? v.getUint32(8, true) : -1,
      cluster: redirect ? -1 : v.getUint32(8, true),
      blob: redirect ? -1 : v.getUint32(12, true),
    }
  }

  /** Finds an entry by namespace and path (binary search over the path pointer list). */
  async findEntry(namespace: string, path: string): Promise<Dirent | null> {
    const want = enc.encode(path)
    const ns = namespace.charCodeAt(0)
    let lo = 0
    let hi = this.entryCount - 1
    while (lo <= hi) {
      const mid = (lo + hi) >>> 1
      const d = await this.direntAt(mid)
      const c = d.namespace.charCodeAt(0) - ns || compareBytes(d.pathBytes, want)
      if (c === 0) return d
      if (c < 0) lo = mid + 1
      else hi = mid - 1
    }
    return null
  }

  /** Finds an entry by the path used in URLs: "Foo" (content) in new ZIMs, "A/Foo" in old ones. */
  async findByUrlPath(urlPath: string): Promise<Dirent | null> {
    if (this.newNamespace) {
      const hit = await this.findEntry('C', urlPath)
      if (hit) return hit
    }
    if (urlPath.length > 2 && urlPath[1] === '/') return this.findEntry(urlPath[0], urlPath.slice(2))
    return null
  }

  /** The path to use in URLs for this entry. */
  urlPath(d: Dirent): string {
    return this.newNamespace && d.namespace === 'C' ? d.path : `${d.namespace}/${d.path}`
  }

  async resolve(d: Dirent): Promise<Dirent> {
    let cur = d
    for (let hops = 0; cur.redirect && hops < 20; hops++) cur = await this.direntAt(cur.redirectIndex)
    if (cur.redirect) throw new Error('Too many redirects')
    return cur
  }

  private async clusterOffset(n: number): Promise<number> {
    const b = await this.src.read(this.clusterPtrPos + n * 8, 8)
    return u64(new DataView(b.buffer, b.byteOffset, 8), 0)
  }

  private cluster(n: number): Promise<Cluster> {
    const hit = this.clusters.get(n)
    if (hit) return hit
    const p = this.loadCluster(n)
    this.clusters.set(n, p)
    p.catch(() => this.clusters.set(n, Promise.reject(new Error('cluster failed'))))
    return p
  }

  private async loadCluster(n: number): Promise<Cluster> {
    const start = await this.clusterOffset(n)
    const end = n + 1 < this.clusterCount ? await this.clusterOffset(n + 1) : this.checksumPos || this.src.size
    const info = (await this.src.read(start, 1))[0]
    const compression = info & 0x0f
    const extended = (info & 0x10) !== 0
    if (compression === 0 || compression === 1) return { rawStart: start + 1, extended }
    const packed = await this.src.read(start + 1, Math.max(0, end - start - 1))
    if (compression === 5) return { data: zstdDecompress(packed), extended }
    if (compression === 4) return { data: await xzDecompress(packed), extended }
    throw new Error(`Unsupported ZIM compression (${compression})`)
  }

  /** The bytes of a (non-redirect) entry. */
  async blobData(d: Dirent): Promise<Uint8Array> {
    const c = await this.cluster(d.cluster)
    const size = c.extended ? 8 : 4
    const offsetAt = (buf: Uint8Array, at: number) => {
      const v = new DataView(buf.buffer, buf.byteOffset + at, size)
      return size === 8 ? u64(v, 0) : v.getUint32(0, true)
    }
    if (c.data) {
      const from = offsetAt(c.data, d.blob * size)
      const to = offsetAt(c.data, (d.blob + 1) * size)
      return c.data.subarray(from, to)
    }
    // Uncompressed: read only the two offsets and the blob itself
    const offs = await this.src.read(c.rawStart! + d.blob * size, size * 2)
    const from = offsetAt(offs, 0)
    const to = offsetAt(offs, size)
    return this.src.read(c.rawStart! + from, to - from)
  }

  /** Where an entry's bytes sit in the file, when its cluster is uncompressed (for large listings). */
  private async rawLocation(d: Dirent): Promise<{ offset: number; length: number } | null> {
    const c = await this.cluster(d.cluster)
    if (c.data) return null
    const size = c.extended ? 8 : 4
    const offs = await this.src.read(c.rawStart! + d.blob * size, size * 2)
    const v = new DataView(offs.buffer, offs.byteOffset, size * 2)
    const from = size === 8 ? u64(v, 0) : v.getUint32(0, true)
    const to = size === 8 ? u64(v, 8) : v.getUint32(4, true)
    return { offset: c.rawStart! + from, length: to - from }
  }

  /** An item by URL path, following redirects. */
  async get(urlPath: string): Promise<ZimItem | null> {
    const d = await this.findByUrlPath(urlPath)
    return d ? this.itemOf(d) : null
  }

  async itemOf(d: Dirent): Promise<ZimItem> {
    const r = await this.resolve(d)
    return { mime: this.mime(r), data: await this.blobData(r), path: this.urlPath(r), title: r.title }
  }

  /** The URL path of the main page, or null. */
  async mainPath(): Promise<string | null> {
    if (this.mainPage !== NONE && this.mainPage < this.entryCount) {
      const d = await this.resolve(await this.direntAt(this.mainPage))
      return this.urlPath(d)
    }
    const w = await this.findEntry('W', 'mainPage')
    if (w) return this.urlPath(await this.resolve(w))
    return null
  }

  /** Text metadata such as Title, Description, Language, Creator. */
  async metadata(name: string): Promise<string | null> {
    const d = await this.findEntry('M', name)
    if (!d) return null
    return utf8.decode(await this.blobData(await this.resolve(d)))
  }

  /** The book's 48×48 PNG icon, if any. */
  async illustration(): Promise<Uint8Array | null> {
    const d = (await this.findEntry('M', 'Illustration_48x48@1')) ?? (await this.findEntry('-', 'favicon'))
    if (!d) return null
    return this.blobData(await this.resolve(d))
  }

  /** Whether the ZIM has a Xapian full-text index (only Android can search it). */
  async hasFulltextIndex(): Promise<boolean> {
    return !!((await this.findEntry('X', 'fulltext/xapian')) ?? (await this.findEntry('Z', '/fulltextIndex/xapian')))
  }

  private async loadTitleListing() {
    if (this.titleListing !== undefined) return this.titleListing
    const d = this.newNamespace ? await this.findEntry('X', 'listing/titleOrdered/v1') : null
    if (!d) return (this.titleListing = null)
    const r = await this.resolve(d)
    const raw = await this.rawLocation(r)
    if (raw) return (this.titleListing = { offset: raw.offset, count: raw.length / 4 })
    const data = await this.blobData(r)
    return (this.titleListing = { data, count: data.length / 4 })
  }

  /** Entry index of the i-th item in title order (articles only when the ZIM has a listing). */
  private async byTitle(i: number): Promise<number> {
    const l = await this.loadTitleListing()
    if (l?.data) return new DataView(l.data.buffer, l.data.byteOffset + i * 4, 4).getUint32(0, true)
    const b = l ? await this.src.read(l.offset! + i * 4, 4) : await this.src.read(this.titlePtrPos + i * 4, 4)
    return new DataView(b.buffer, b.byteOffset, 4).getUint32(0, true)
  }

  /** Title suggestions: titles starting with what was typed (tried as typed, Capitalized and lower case). */
  async suggest(query: string, limit = 20): Promise<{ title: string; path: string }[]> {
    const q = query.trim()
    if (!q) return []
    const listing = await this.loadTitleListing()
    const count = listing ? listing.count : this.entryCount
    const ns = this.newNamespace ? 'C' : 'A'
    const variants = [...new Set([q, q[0].toUpperCase() + q.slice(1), q.toLowerCase(), q.replace(/\b\w/g, (c) => c.toUpperCase())])]
    const out: { title: string; path: string }[] = []
    const seen = new Set<string>()
    for (const v of variants) {
      const want = enc.encode(v)
      const key = (d: Dirent) => (listing ? 0 : d.namespace.charCodeAt(0) - ns.charCodeAt(0)) || compareBytes(enc.encode(d.title), want)
      // Lower bound: first title >= v
      let lo = 0
      let hi = count
      while (lo < hi) {
        const mid = (lo + hi) >>> 1
        const d = await this.direntAt(await this.byTitle(mid))
        if (key(d) < 0) lo = mid + 1
        else hi = mid
      }
      for (let i = lo; i < count && out.length < limit; i++) {
        const d = await this.direntAt(await this.byTitle(i))
        if ((!listing && d.namespace !== ns) || !startsWith(enc.encode(d.title), want)) break
        if (!d.redirect && !this.mime(d).startsWith('text/html')) continue
        const target = await this.resolve(d)
        const path = this.urlPath(target)
        if (seen.has(path)) continue
        seen.add(path)
        // A redirect ("Ray charles") shows the page it opens ("Ray Charles")
        out.push({ title: target.title, path })
      }
      if (out.length >= limit) break
    }
    return out
  }
}

async function xzDecompress(data: Uint8Array): Promise<Uint8Array> {
  const { XzReadableStream } = await import('xz-decompress')
  const stream = new XzReadableStream(new Blob([data as BlobPart]).stream() as ReadableStream<Uint8Array>)
  return new Uint8Array(await new Response(stream).arrayBuffer())
}
