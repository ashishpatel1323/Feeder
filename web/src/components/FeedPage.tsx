import { useMemo } from 'react'
import { feedDisplayTitle, OPEN_WITH_BROWSER, OPEN_WITH_READER, opensInBrowser, updateFeed, type Feed, type Item } from '../lib/db'
import { computeFeedStats, noStats, timeAgo } from '../lib/feedstats'
import { syncNow } from '../lib/sync2'
import { EditFeedForm } from './Dialogs'
import { Icon } from './Icon'
import { StatsPanel } from './ManageFeeds'
import { FeedIcon } from './Sidebar'

interface Props {
  url: string
  feed: Feed | undefined
  items: Item[]
  onBack: () => void
  /** Shows this feed's articles in the list. */
  onShowArticles: (url: string) => void
  toast: (m: string) => void
}

/**
 * One feed's own page, opened by clicking a feed name: the stats panel and the settings from
 * Manage feeds and the Edit feed dialog, together on one page.
 */
export function FeedPage(p: Props) {
  const f = p.feed
  const stats = useMemo(() => computeFeedStats(p.items.filter((i) => i.feedUrl === p.url)).get(p.url) ?? noStats(), [p.items, p.url])

  if (!f) {
    return (
      <section className="manage-pane">
        <div className="mf-page">
          <header className="mf-head">
            <button type="button" className="mf-ib" title="Back (Esc)" aria-label="Back" onClick={p.onBack}>
              <Icon name="back" size={22} />
            </button>
            <h1>Feed not found</h1>
          </header>
          <p className="mf-nothing">You are not subscribed to {p.url}.</p>
        </div>
      </section>
    )
  }

  const title = feedDisplayTitle(f)
  const openWith = opensInBrowser(f) ? OPEN_WITH_BROWSER : f.openArticlesWith === OPEN_WITH_READER ? OPEN_WITH_READER : ''
  const setOpenWith = async (v: string) => {
    if (await updateFeed(f.url, { openArticlesWith: v })) syncNow().catch(() => undefined)
  }

  return (
    <section className="manage-pane feed-page">
      <div className="mf-page">
        <header className="mf-head">
          <button type="button" className="mf-ib" title="Back (Esc)" aria-label="Back" onClick={p.onBack}>
            <Icon name="back" size={22} />
          </button>
          <FeedIcon feed={f} />
          <div className="fp-title">
            <h1>{title}</h1>
            <a className="mf-url" href={f.url} target="_blank" rel="noopener noreferrer">
              {f.url}
            </a>
          </div>
          <span className="mf-sp" />
          <button type="button" className="mf-btn primary" onClick={() => p.onShowArticles(f.url)}>
            <Icon name="article" size={18} />
            <span>Show articles{stats.unread ? ` (${stats.unread} unread)` : ''}</span>
          </button>
        </header>

        <p className={`fp-status${f.lastError ? ' err' : ''}`}>
          {f.lastError ? `Last check failed: ${f.lastError}` : `Checked ${timeAgo(f.lastFetched)}`}
          {f.tag ? ` · Folder: ${f.tag}` : ''}
        </p>

        <h2 className="fp-h">Stats</h2>
        <section className="mf-card">
          <StatsPanel s={stats} feed={f} />
        </section>

        <h2 className="fp-h">Settings</h2>
        <section className="mf-card fp-settings">
          <label className="fp-row">
            <span>Opens in</span>
            <select className="mf-select" value={openWith} onChange={(e) => setOpenWith(e.target.value)} title="How articles from this feed open">
              <option value="">Default</option>
              <option value={OPEN_WITH_READER}>Reader</option>
              <option value={OPEN_WITH_BROWSER}>Browser tab</option>
            </select>
          </label>
          <EditFeedForm key={f.url} url={f.url} inline onDone={() => undefined} onDeleted={p.onBack} toast={p.toast} />
        </section>
      </div>
    </section>
  )
}
