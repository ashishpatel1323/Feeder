import type { Item } from './db'

/**
 * Keeps one copy of each article in memory across live queries.
 *
 * The app reads every article (with its full text) again whenever anything in the items table
 * changes: a read mark, a sync, each feed a refresh adds to. IndexedDB hands back brand new
 * objects and strings each time, and older renders keep their copy alive through the closures
 * React holds on to, so a big library piled up copy after copy of all its article text until
 * the page ran out of memory and crashed (about 120 MB a copy for 3,500 articles).
 *
 * Here an article that did not change keeps the object from the last read, so older copies of
 * the list point at the same articles and cost only the list itself. When nothing changed at
 * all, the last list comes back as is, which also spares a render.
 */
export function createItemReuser(): (fresh: Item[]) => Item[] {
  let byId = new Map<string, Item>()
  let last: Item[] = []
  return (fresh) => {
    const next = new Map<string, Item>()
    let same = fresh.length === last.length
    const out = fresh.map((item, i) => {
      const prev = byId.get(item.id)
      const kept = prev && sameItem(prev, item) ? prev : item
      next.set(kept.id, kept)
      if (same && last[i] !== kept) same = false
      return kept
    })
    byId = next
    if (same) return last
    last = out
    return out
  }
}

function sameItem(a: Item, b: Item): boolean {
  const ka = Object.keys(a)
  if (ka.length !== Object.keys(b).length) return false
  const ra = a as unknown as Record<string, unknown>
  const rb = b as unknown as Record<string, unknown>
  for (const k of ka) {
    const x = ra[k]
    const y = rb[k]
    if (x === y) continue
    // Lists such as offlineImages come back as new arrays each read: compare what is in them
    if (Array.isArray(x) && Array.isArray(y)) {
      if (x.length !== y.length || x.some((v, i) => v !== y[i])) return false
      continue
    }
    if (x && y && typeof x === 'object' && typeof y === 'object') {
      if (JSON.stringify(x) !== JSON.stringify(y)) return false
      continue
    }
    return false
  }
  return true
}
