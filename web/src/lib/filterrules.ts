/**
 * Per-feed block/allow rules, port of the Android app's model/ArticleFilterRules.kt.
 * One "Field=regex" per line; fields Title, URL, Author, Tag, Content.
 */
import type { ParsedArticle } from './feedparse'
import { htmlToText } from './feedparse'

type Field = 'title' | 'url' | 'author' | 'tag' | 'content'
interface Rule {
  field: Field
  regex: RegExp
}

const MAX_RULES = 100
const MAX_MATCHED_LENGTH = 100_000

export function parseRules(text: string): Rule[] {
  const rules: Rule[] = []
  for (const raw of (text ?? '').split('\n')) {
    const line = raw.trim()
    if (!line) continue
    const sep = line.indexOf('=')
    if (sep < 0) continue
    const field = line.slice(0, sep).trim().toLowerCase() as Field
    const pattern = line.slice(sep + 1).trim()
    if (!['title', 'url', 'author', 'tag', 'content'].includes(field) || !pattern) continue
    if (rules.length >= MAX_RULES) break
    try {
      rules.push({ field, regex: new RegExp(pattern, 'u') })
    } catch {
      // invalid regex: skipped, like the Android app
    }
  }
  return rules
}

function matchValue(rule: Rule, value: string | null | undefined): boolean {
  return rule.regex.test((value ?? '').slice(0, MAX_MATCHED_LENGTH))
}

function matches(rule: Rule, a: ParsedArticle): boolean {
  switch (rule.field) {
    case 'title':
      return matchValue(rule, a.title)
    case 'url':
      return matchValue(rule, a.link)
    case 'author':
      return matchValue(rule, a.author)
    case 'content': {
      const text = htmlToText(a.contentHtml)
      return matchValue(rule, text.trim() ? text : a.contentHtml)
    }
    case 'tag':
      return a.tags.some((t) => matchValue(rule, t))
  }
}

const appliesTo = (rule: Rule, a: ParsedArticle) => (rule.field === 'tag' ? a.tags.length > 0 : true)

export function makeFilter(blockText: string, allowText: string): (a: ParsedArticle) => boolean {
  const block = parseRules(blockText)
  const allow = parseRules(allowText)
  if (!block.length && !allow.length) return () => true
  return (a) => {
    if (block.some((r) => matches(r, a))) return false
    if (!allow.length) return true
    const applicable = allow.filter((r) => appliesTo(r, a))
    return applicable.length === 0 || applicable.some((r) => matches(r, a))
  }
}
