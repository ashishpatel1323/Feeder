/** Ebook reader settings (per device) and the styles put into book pages. */

export interface ReaderPrefs {
  /** Percent of the book's own size */
  fontSize: number
  lineHeight: number
  /** auto = follow the app's light or dark theme */
  theme: 'auto' | 'light' | 'sepia' | 'dark'
  flow: 'paginated' | 'scrolled'
  /** Keep the book's fonts instead of the reader's */
  publisherFont: boolean
}

const KEY = 'feeder.ebookReader'
export const DEFAULT_PREFS: ReaderPrefs = { fontSize: 100, lineHeight: 1.5, theme: 'auto', flow: 'paginated', publisherFont: true }

export function loadReaderPrefs(): ReaderPrefs {
  try {
    const v = JSON.parse(localStorage.getItem(KEY) || '{}') as Partial<ReaderPrefs>
    return { ...DEFAULT_PREFS, ...v }
  } catch {
    return { ...DEFAULT_PREFS }
  }
}

export function saveReaderPrefs(p: ReaderPrefs) {
  try {
    localStorage.setItem(KEY, JSON.stringify(p))
  } catch {
    /* private mode */
  }
}

const THEMES = {
  light: { bg: '#ffffff', fg: '#1b1c1a', link: '#1f7a4d' },
  sepia: { bg: '#f4ecd8', fg: '#3b2f1e', link: '#7a4e1d' },
  dark: { bg: '#0d110f', fg: '#e2e3df', link: '#5bd08f' },
}

export function resolvedTheme(theme: ReaderPrefs['theme'], appDark: boolean): keyof typeof THEMES {
  return theme === 'auto' ? (appDark ? 'dark' : 'light') : theme
}

const appIsDark = () => {
  if (typeof document === 'undefined') return false
  const t = document.documentElement.dataset.theme
  if (t === 'dark') return true
  if (t === 'light') return false
  return typeof matchMedia !== 'undefined' && matchMedia('(prefers-color-scheme: dark)').matches
}

/** CSS for every page of the book: theme, size, spacing, and the read-aloud marks. */
export function bookCss(p: ReaderPrefs, dark = appIsDark()): string {
  const t = THEMES[resolvedTheme(p.theme, dark)]
  const isDark = resolvedTheme(p.theme, dark) === 'dark'
  return `
    @namespace epub "http://www.idpf.org/2007/ops";
    html {
      color-scheme: ${isDark ? 'dark' : 'light'};
      color: ${t.fg} !important;
      background: ${t.bg} !important;
      font-size: ${p.fontSize}% !important;
    }
    body { background: none !important; color: inherit !important; }
    ${p.publisherFont ? '' : 'body, p, li, blockquote, dd, div, span { font-family: Georgia, "Iowan Old Style", "Noto Serif", serif !important; }'}
    p, li, blockquote, dd { line-height: ${p.lineHeight} !important; }
    a:any-link { color: ${t.link} !important; }
    ${isDark ? 'img, svg { filter: brightness(0.9); }' : ''}
    pre { white-space: pre-wrap !important; }
    aside[epub|type~="endnote"], aside[epub|type~="footnote"], aside[epub|type~="note"], aside[epub|type~="rearnote"] { display: none; }
    ::highlight(ra-sentence) { background-color: ${isDark ? 'rgba(140, 213, 172, 0.22)' : 'rgba(31, 111, 74, 0.14)'}; }
    ::highlight(ra-word) { background-color: ${isDark ? 'rgba(140, 213, 172, 0.5)' : 'rgba(31, 111, 74, 0.38)'}; }
    ::selection { background: ${isDark ? 'rgba(140, 213, 172, 0.35)' : 'rgba(31, 111, 74, 0.25)'}; }
  `
}

const CSP = `<meta http-equiv="Content-Security-Policy" content="script-src 'none'; object-src 'none'; frame-src 'none'"/>`

/** Blocks scripts in a book page: they'd run with this app's storage (sync keys, Dropbox). */
export function injectCsp(html: string): string {
  const head = html.match(/<head(\s[^>]*)?>/i)
  if (head) return html.slice(0, head.index! + head[0].length) + CSP + html.slice(head.index! + head[0].length)
  const htmlTag = html.match(/<html(\s[^>]*)?>/i)
  if (htmlTag) return html.slice(0, htmlTag.index! + htmlTag[0].length) + `<head>${CSP}</head>` + html.slice(htmlTag.index! + htmlTag[0].length)
  return CSP + html
}

export function langString(x: unknown): string {
  if (!x) return ''
  if (typeof x === 'string') return x
  if (typeof x === 'object') {
    const o = x as Record<string, string>
    return o.en ?? Object.values(o)[0] ?? ''
  }
  return ''
}

export function authorString(x: unknown): string {
  const one = (a: unknown): string => (typeof a === 'string' ? a : a && typeof a === 'object' && 'name' in a ? langString((a as { name: unknown }).name) : langString(a))
  return (Array.isArray(x) ? x.map(one) : [one(x)]).filter(Boolean).join(', ')
}

