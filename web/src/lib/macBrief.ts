/**
 * Mac app only: writes the playlist briefs asked for on any device (briefs.ts), with Claude Code
 * on this Mac (`claude -p` through /api/brief/claude in mac/server.ts), signed in with the user's
 * Claude plan, so no API key.
 *
 * While the app is open it syncs every half minute; for each brief asked for it:
 *  - marks it "working" (so the phone shows that the Mac picked it up),
 *  - fetches each article's full text (Readability on the page, as Full article does; the feed's
 *    text when that fails), without marking anything read,
 *  - asks Claude for the Morning brief, a morning radio show: opener, the rundown, 1 to 3 picks to
 *    read in full chosen from the reader's taste (readerTaste), sign-off (long playlists are
 *    summarised in batches first),
 *  - writes the brief back ("done", or "failed" with the reason).
 */
import { db, type Item } from './db'
import { loadFullText } from './extras'
import { htmlToText } from './feedparse'
import {
  batches,
  batchPrompt,
  briefPrompt,
  finalPrompt,
  isPending,
  loadBriefs,
  parseBatch,
  parseClaude,
  putBrief,
  type ArticleText,
  type Taste,
  type BriefItem,
  type BriefValue,
} from './briefs'

const TICK_MS = 30_000
const FETCH_TIMEOUT_MS = 20_000
const appHeaders = { 'x-feeder-app': '1' }

async function askClaude(prompt: string): Promise<string> {
  const res = await fetch('/api/brief/claude', {
    method: 'POST',
    headers: { ...appHeaders, 'content-type': 'application/json' },
    body: JSON.stringify({ prompt }),
  })
  const j = (await res.json().catch(() => ({}))) as {
    text?: string
    error?: string
  }
  if (!res.ok || typeof j.text !== 'string') throw new Error(j.error || `Claude Code failed (${res.status})`)
  return j.text
}

function timeout<T>(p: Promise<T>, ms: number): Promise<T> {
  return Promise.race([p, new Promise<T>((_, no) => setTimeout(() => no(new Error('slow')), ms))])
}

/** The article's full text as plain text; the feed's own text when the page can't be read. Never marks it read. */
export async function fullArticleText(it: BriefItem): Promise<string> {
  const local = await db.items.get(it.id)
  try {
    if (local) return htmlToText(await timeout(loadFullText(local), FETCH_TIMEOUT_MS))
    if (it.u) return htmlToText(await timeout(loadFullText({ id: it.id, link: it.u } as Item), FETCH_TIMEOUT_MS))
  } catch {
    // fall back to the feed's text below
  }
  return local ? htmlToText(local.contentHtml || local.snippet || '') : ''
}

const DAY = 86400_000
const clip = (s: string, n: number) => (s.length > n ? `${s.slice(0, n - 1)}…` : s)

/** What the reader likes, for the brief's picks: most-read feeds (30 days), recent Saved and highlights. */
export async function readerTaste(now = Date.now()): Promise<Taste> {
  const feeds = new Map((await db.feeds.toArray()).map((f) => [f.url, f.customTitle || f.title || f.url]))
  const counts = new Map<string, number>()
  const saved: Item[] = []
  await db.items.each((i) => {
    if (i.read && (i.readTime ?? 0) > now - 30 * DAY) counts.set(i.feedUrl, (counts.get(i.feedUrl) ?? 0) + 1)
    if (i.saved) saved.push(i)
  })
  const topFeeds = [...counts]
    .sort((a, b) => b[1] - a[1])
    .slice(0, 8)
    .map(([url]) => feeds.get(url) ?? url)
  const highlights = (await db.highlights.toArray())
    .sort((a, b) => b.createdAt - a.createdAt)
    .slice(0, 12)
    .map((h) => `"${clip(h.text.replace(/\s+/g, ' '), 200)}" (${clip(h.itemTitle, 80)})${h.note ? `, their note: ${clip(h.note, 120)}` : ''}`)
  return {
    topFeeds,
    saved: saved
      .sort((a, b) => b.sortTime - a.sortTime)
      .slice(0, 12)
      .map((i) => clip(i.title, 100)),
    highlights,
  }
}

class MacBrief {
  available = false
  private started = false
  private running = false

  async start() {
    if (this.started) return
    this.started = true
    this.available = await fetch('/api/brief/status', {
      headers: appHeaders,
    })
      .then((r) => (r.ok ? (r.json() as Promise<{ available?: boolean }>) : null))
      .then((j) => !!j?.available)
      .catch(() => false)
    if (!this.available) return
    const loop = async () => {
      await this.tick().catch((e) => console.warn('Playlist brief failed', e))
      window.setTimeout(loop, TICK_MS)
    }
    void loop()
  }

  private async tick() {
    if (this.running) return
    this.running = true
    try {
      const sync = () => import('./sync2').then((m) => m.syncNow()).catch(() => null)
      await sync()
      const now = Date.now()
      for (const [id, b] of Object.entries(await loadBriefs())) {
        // "working" left behind by an app that was closed mid-way is picked up again too
        if (!isPending(b.v, now)) continue
        await this.make(id, b.v)
        await sync()
      }
    } finally {
      this.running = false
    }
  }

  private async make(id: string, asked: BriefValue) {
    const base: BriefValue = {
      s: 'working',
      asked: asked.asked,
      name: asked.name,
      items: asked.items,
      ...(asked.by ? { by: asked.by } : {}),
    }
    const stillAsked = async () => (await loadBriefs())[id]?.v.asked === asked.asked
    await putBrief(id, { ...base, n: 0 })
    try {
      if (!asked.items.length) throw new Error('This playlist has no articles to sum up')
      const articles: ArticleText[] = []
      for (const [n, it] of asked.items.entries()) {
        articles.push({
          title: it.t,
          link: it.u,
          source: it.f,
          text: await fullArticleText(it),
        })
        // Progress for the phone every few articles
        if ((n + 1) % 5 === 0 && n + 1 < asked.items.length) {
          if (!(await stillAsked())) return
          await putBrief(id, { ...base, n: n + 1 })
        }
      }
      if (!(await stillAsked())) return
      await putBrief(id, { ...base, n: articles.length })

      const taste = await readerTaste()
      const groups = batches(articles)
      let answer: string
      if (groups.length === 1) {
        answer = await askClaude(briefPrompt(asked.name, articles, taste))
      } else {
        // Long playlist: short summaries batch by batch, then the brief from those
        const sums: string[] = articles.map(() => '')
        for (const g of groups) {
          for (const [n, x] of parseBatch(
            await askClaude(
              batchPrompt(
                asked.name,
                g.map((i) => articles[i]),
                g[0],
              ),
            ),
          ))
            if (n >= 1 && n <= sums.length) sums[n - 1] = x
        }
        answer = await askClaude(
          finalPrompt(
            asked.name,
            articles.map((a) => a.title),
            sums,
            taste,
            new Date(),
            articles.map((a) => a.source ?? ''),
          ),
        )
      }
      if (!(await stillAsked())) return
      const done: BriefValue = {
        ...base,
        s: 'done',
        via: 'mac',
        n: articles.length,
        at: Date.now(),
      }
      const parsed = parseClaude(answer, articles.length)
      await putBrief(id, parsed ? { ...done, ...parsed } : { ...done, text: answer.trim() })
    } catch (e) {
      if (!(await stillAsked())) return
      await putBrief(id, {
        ...base,
        s: 'failed',
        err: e instanceof Error ? e.message : String(e),
        at: Date.now(),
      })
    }
  }
}

export const macBrief = new MacBrief()
