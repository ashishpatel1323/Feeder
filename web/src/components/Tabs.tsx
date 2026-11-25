import { useEffect } from 'react'
import { Icon, IconButton, type IconName } from './Icon'

/**
 * The places, as on Android: Feeds (Latest, By feed or Discover), Playlists, Library, Ebooks,
 * Audiobooks and ZIM. Search is not a tab: ⌘K, /, and the "Search everything" bars open it.
 * Discover lives inside Feeds; it is a Tab value only for its G then D shortcut.
 */
export type Tab = 'feeds' | 'discover' | 'playlists' | 'library' | 'books' | 'audiobooks' | 'zim'

export const TABS: { tab: Tab; label: string; icon: IconName; key: string; hint: string }[] = [
  { tab: 'feeds', label: 'Feeds', icon: 'dynamicFeed', key: 'f', hint: 'Latest, By feed and Discover' },
  { tab: 'playlists', label: 'Playlists', icon: 'queue', key: 'p', hint: 'Smart and hand-made playlists' },
  { tab: 'library', label: 'Library', icon: 'library', key: 'l', hint: 'Saved, Highlights and Downloads' },
  { tab: 'books', label: 'Ebooks', icon: 'book', key: 'b', hint: 'Books from Dropbox and this device' },
  { tab: 'audiobooks', label: 'Audiobooks', icon: 'headphones', key: 'a', hint: 'Audiobooks from Dropbox' },
  { tab: 'zim', label: 'ZIM', icon: 'public', key: 'z', hint: 'Offline websites such as Wikipedia' },
]

/** Shortcuts to places that are not tabs of their own. */
const EXTRA_KEYS: { tab: Tab; key: string }[] = [{ tab: 'discover', key: 'd' }]

export const tabKeys = (t: Tab) => `G then ${([...TABS, ...EXTRA_KEYS].find((x) => x.tab === t)?.key ?? '').toUpperCase()}`

const FEEDS_VIEW_KEY = 'feeder.feedsView'

/** The Feeds tab opens on the view used last: By feed (the tiles, the default) or Latest. */
export function feedsViewIsLatest(): boolean {
  try {
    return localStorage.getItem(FEEDS_VIEW_KEY) === 'latest'
  } catch {
    return false
  }
}

export function rememberFeedsView(latest: boolean) {
  try {
    localStorage.setItem(FEEDS_VIEW_KEY, latest ? 'latest' : 'byFeed')
  } catch {
    /* private mode */
  }
}

/** The bottom bar on narrow screens (phones, small windows). Wide screens use the sidebar's tabs. */
export function TabBar({ current, onTab }: { current: Tab | null; onTab: (t: Tab) => void }) {
  return (
    <nav className="tabbar" aria-label="Main">
      {TABS.map((t) => (
        <button
          key={t.tab}
          type="button"
          className={`tabbar-item${current === t.tab ? ' selected' : ''}`}
          aria-current={current === t.tab ? 'page' : undefined}
          title={`${t.label}: ${t.hint} (${tabKeys(t.tab)})`}
          onClick={() => onTab(t.tab)}
        >
          <span className="tabbar-pill">
            <Icon name={t.icon} size={22} />
          </span>
          <span className="tabbar-label">{t.label}</span>
        </button>
      ))}
    </nav>
  )
}

export type FeedsView = 'latest' | 'byFeed' | 'discover'

/** Latest | By feed | Discover, at the top of the Feeds tab. */
export function FeedsSwitch({ view, onChange }: { view: FeedsView; onChange: (view: FeedsView) => void }) {
  const opt = (v: FeedsView, icon: IconName, label: string, hint: string) => (
    <button type="button" role="radio" aria-checked={view === v} className={view === v ? 'on' : ''} title={hint} onClick={() => view !== v && onChange(v)}>
      <Icon name={icon} size={16} />
      <span className="fs-label">{label}</span>
    </button>
  )
  return (
    <span className="feeds-switch" role="radiogroup" aria-label="Feeds view">
      {opt('latest', 'viewAgenda', 'Latest', 'Every new article')}
      {opt('byFeed', 'gridView', 'By feed', 'Your feeds as tiles')}
      {opt('discover', 'explore', 'Discover', `Find new feeds, podcasts and channels (${tabKeys('discover')})`)}
    </span>
  )
}

/** A "Search everything" bar for the top of a tab page. */
export function SearchEverything({ onSearch }: { onSearch: () => void }) {
  const mac = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform)
  return (
    <button type="button" className="search-everything" onClick={onSearch} aria-label="Search everything">
      <Icon name="search" size={18} />
      <span>Search everything</span>
      <kbd>{mac ? '⌘K' : 'Ctrl K'}</kbd>
    </button>
  )
}

/** G then F / D / P / L / B / A / Z switches tabs, when not typing (as in Gmail). */
export function useTabShortcuts(onTab: (t: Tab) => void, disabled: boolean) {
  useEffect(() => {
    if (disabled) return
    let armedUntil = 0
    const onKey = (e: KeyboardEvent) => {
      if (e.metaKey || e.ctrlKey || e.altKey) return
      const t = e.target as HTMLElement
      if (/INPUT|TEXTAREA|SELECT/.test(t.tagName) || t.isContentEditable || document.querySelector('[aria-modal="true"]')) return
      const k = e.key.toLowerCase()
      if (Date.now() < armedUntil) {
        armedUntil = 0
        const hit = [...TABS, ...EXTRA_KEYS].find((x) => x.key === k)
        if (hit) {
          e.preventDefault()
          e.stopImmediatePropagation()
          onTab(hit.tab)
        }
        return
      }
      if (k === 'g' && !e.shiftKey) {
        armedUntil = Date.now() + 1500
        e.preventDefault()
      }
    }
    // Capture, so F after G is not also Flip mode
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  }, [onTab, disabled])
}

/** The Library tab: what you kept, then Settings and Manage feeds. */
export function LibraryPage(p: {
  savedCount: number
  highlightCount: number
  downloadCount: number
  showDownloads: boolean
  onSaved: () => void
  onHighlights: () => void
  onDownloads: () => void
  onSettings: () => void
  onManageFeeds: () => void
  onSearch: () => void
  onMenu: () => void
}) {
  const row = (icon: IconName, title: string, desc: string, count: number | null, onClick: () => void) => (
    <button type="button" className="lib-row" onClick={onClick}>
      <Icon name={icon} size={22} />
      <span className="lib-text">
        <span className="lib-title">{title}</span>
        <span className="lib-desc">{desc}</span>
      </span>
      {count ? <span className="count muted">{count}</span> : null}
    </button>
  )
  return (
    <section className="manage-pane lib-page" aria-label="Library">
      <div className="mf-page">
        <div className="mf-head">
          <span className="only-narrow">
            <IconButton icon="menu" label="Feeds" onClick={p.onMenu} />
          </span>
          <h1>Library</h1>
        </div>
        <SearchEverything onSearch={p.onSearch} />
        <div className="lib-list">
          {row('bookmark', 'Saved', 'Read later and shared links', p.savedCount, p.onSaved)}
          {row('highlight', 'Highlights', 'Notes and quotes', p.highlightCount, p.onHighlights)}
          {p.showDownloads ? row('download', 'Downloads', 'Videos saved for offline', p.downloadCount, p.onDownloads) : null}
        </div>
        <h2 className="lib-section">App</h2>
        <div className="lib-list">
          {row('settings', 'Settings', 'Reading, voice, sync and appearance', null, p.onSettings)}
          {row('tune', 'Manage feeds', 'Folders, order and per-feed settings', null, p.onManageFeeds)}
        </div>
      </div>
    </section>
  )
}
