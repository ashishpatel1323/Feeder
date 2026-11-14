/** Full text, og:image thumbnails, Hacker News scores and summaries. */
import { Readability } from '@mozilla/readability'
import DOMPurify from 'dompurify'
import { proxyFetch } from './api'
import { db, type Item } from './db'
import { countWords, htmlToText } from './feedparse'
import { getSettings } from './settings'

// ---------------------------------------------------------------------------------------------
// Sanitizing article HTML

DOMPurify.addHook('afterSanitizeAttributes', (node) => {
  if (node.tagName === 'A') {
    node.setAttribute('target', '_blank')
    node.setAttribute('rel', 'noopener noreferrer')
  }
  if (node.tagName === 'IMG') {
    node.setAttribute('loading', 'lazy')
    node.setAttribute('referrerpolicy', 'no-referrer')
  }
})

export function sanitize(html: string, baseUrl: string | null): string {
  const clean = DOMPurify.sanitize(html, {
    FORBID_TAGS: ['style', 'form', 'input', 'button', 'iframe', 'object', 'embed'],
    FORBID_ATTR: ['style', 'class', 'id', 'width', 'height'],
  })
  if (!baseUrl) return clean
  // Resolve relative links and images against the article URL
  const doc = new DOMParser().parseFromString(`<body>${clean}</body>`, 'text/html')
  doc.querySelectorAll('[src],[href]').forEach((el) => {
    for (const attr of ['src', 'href']) {
      const v = el.getAttribute(attr)
      if (v && !/^(https?:|data:|mailto:|#)/i.test(v)) {
        try {
          el.setAttribute(attr, new URL(v, baseUrl).toString())
        } catch {
          // leave it
        }
      }
    }
  })
  return doc.body.innerHTML
}

// ---------------------------------------------------------------------------------------------
// Full text (Readability on the fetched page)

export async function loadFullText(item: Item): Promise<string> {
  if (item.fullHtml) return item.fullHtml
  if (!item.link) throw new Error('This article has no link')
  const { text, finalUrl } = await proxyFetch(item.link)
  const doc = new DOMParser().parseFromString(text, 'text/html')
  const base = doc.createElement('base')
  base.href = finalUrl
  doc.head.prepend(base)
  const article = new Readability(doc).parse()
  if (!article?.content) throw new Error('Could not extract the article text')
  const html = sanitize(article.content, finalUrl)
  await db.items.update(item.id, { fullHtml: html, fullWordCount: countWords(htmlToText(html)) })
  return html
}

// ---------------------------------------------------------------------------------------------
// og:image ("Enhance thumbnails from article metadata")

export async function fetchOgImages(items: Item[], limit = 12): Promise<void> {
  const todo = items.filter((i) => !i.imageUrl && !i.ogChecked && i.link).slice(0, limit)
  await Promise.all(
    todo.map(async (item) => {
      try {
        const { text, finalUrl } = await proxyFetch(item.link!)
        const head = text.slice(0, 200_000)
        const m =
          head.match(/<meta[^>]+property=["']og:image(?::url)?["'][^>]*content=["']([^"']+)["']/i) ??
          head.match(/<meta[^>]+content=["']([^"']+)["'][^>]*property=["']og:image(?::url)?["']/i) ??
          head.match(/<meta[^>]+name=["']twitter:image["'][^>]*content=["']([^"']+)["']/i)
        const url = m ? new URL(m[1].replace(/&amp;/g, '&'), finalUrl).toString() : null
        await db.items.update(item.id, { ogChecked: Date.now(), ...(url ? { imageUrl: url } : {}) })
      } catch {
        await db.items.update(item.id, { ogChecked: Date.now() })
      }
    }),
  )
}

// ---------------------------------------------------------------------------------------------
// Hacker News score, port of model/HackerNewsScores.kt

const TRACKING = new Set(['fbclid', 'gclid', 'mc_cid', 'mc_eid', 'ref', 'ref_src', 'cmpid', 'ncid', 'guccounter'])

export function normalizeLinkForHn(link: string | null | undefined): string | null {
  if (!link?.trim()) return null
  let u: URL
  try {
    u = new URL(link.trim())
  } catch {
    return null
  }
  if (u.protocol !== 'http:' && u.protocol !== 'https:') return null
  const host = u.hostname.toLowerCase().replace(/^www\./, '')
  if (host === 'news.ycombinator.com') return null
  const path = u.pathname.replace(/\/+$/, '')
  const query = u.search
    .replace(/^\?/, '')
    .split('&')
    .filter((p) => {
      const name = p.split('=')[0].toLowerCase()
      return p && !name.startsWith('utm_') && !TRACKING.has(name)
    })
    .join('&')
  return host + path + (query ? `?${query}` : '')
}

const HN_MAX_AGE = 3 * 86_400_000
const HN_REFRESH = 3 * 3_600_000

export async function refreshHnScores(items: Item[], limit = 40): Promise<void> {
  if (!(await getSettings()).showHnScore) return
  const now = Date.now()
  const todo = items
    .filter((i) => i.link && i.sortTime > now - HN_MAX_AGE && (!i.hnChecked || i.hnChecked < now - HN_REFRESH))
    .slice(0, limit)
  for (const item of todo) {
    const target = normalizeLinkForHn(item.link)
    if (!target) {
      await db.items.update(item.id, { hnChecked: now })
      continue
    }
    const params = new URLSearchParams({
      query: target.split('?')[0],
      restrictSearchableAttributes: 'url',
      tags: 'story',
      hitsPerPage: '20',
    })
    try {
      const res = await fetch(`https://hn.algolia.com/api/v1/search?${params}`)
      if (!res.ok) break
      const data = (await res.json()) as { hits?: { url?: string; objectID?: string; points?: number; num_comments?: number }[] }
      const best = (data.hits ?? [])
        .filter((h) => normalizeLinkForHn(h.url) === target && h.objectID)
        .sort((a, b) => (b.points ?? 0) - (a.points ?? 0))[0]
      await db.items.update(item.id, {
        hnChecked: now,
        hnPoints: best?.points ?? null,
        hnComments: best?.num_comments ?? null,
        hnId: best?.objectID ?? null,
      })
    } catch {
      break
    }
  }
}

export const hnDiscussionUrl = (id: string) => `https://news.ycombinator.com/item?id=${id}`

// ---------------------------------------------------------------------------------------------
// Summaries through any OpenAI-compatible API (OpenAI, Ollama's /v1, LM Studio, ...)

export async function summarize(item: Item, text: string): Promise<string> {
  const s = await getSettings()
  if (s.summaryProvider === 'none') throw new Error('Pick a summary provider in Settings first')
  const base = s.summaryBaseUrl.replace(/\/+$/, '')
  if (location.protocol === 'https:' && base.startsWith('http://') && !/^http:\/\/(localhost|127\.0\.0\.1)/.test(base)) {
    throw new Error(
      'This page is https, so the browser blocks calls to a plain http address on your network. Use Ollama on this computer (localhost) or expose it over https.',
    )
  }
  const res = await fetch(`${base}/chat/completions`, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      ...(s.summaryApiKey ? { authorization: `Bearer ${s.summaryApiKey}` } : {}),
    },
    body: JSON.stringify({
      model: s.summaryModel,
      stream: false,
      messages: [
        {
          role: 'system',
          content:
            'You summarize articles. Reply with 3 to 5 short bullet points in the language of the article, then one sentence on why it matters. No preamble.',
        },
        { role: 'user', content: `Summarize:\n\nTitle: ${item.title}\n\n${text.slice(0, 24_000)}` },
      ],
    }),
  })
  if (!res.ok) {
    const body = await res.text()
    throw new Error(`Summary failed (HTTP ${res.status}). ${body.slice(0, 200)}`)
  }
  const data = (await res.json()) as { choices?: { message?: { content?: string } }[] }
  const content = data.choices?.[0]?.message?.content?.trim()
  if (!content) throw new Error('The model returned no text')
  await db.items.update(item.id, { summary: content })
  return content
}
