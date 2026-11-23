import { useMemo, useState } from 'react'
import { useLiveQuery } from 'dexie-react-hooks'
import { feedDisplayTitle, getKv, setKv, OPEN_WITH_BROWSER, OPEN_WITH_READER, opensInBrowser, updateFeed, type Feed, type Item } from '../lib/db'
import { detectKind, FEED_KINDS, FEED_KINDS_KEY, KIND_LABELS, type FeedKind } from '../lib/home'
import { computeFeedStats, noStats, timeAgo } from '../lib/feedstats'
import { syncNow } from '../lib/sync2'
import { DeleteFeedDialog, deleteFeedEverywhere, EditFeedForm } from './Dialogs'
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
  const [confirmDelete, setConfirmDelete] = useState(false)
  const kinds = useLiveQuery(() => getKv<Record<string, FeedKind>>(FEED_KINDS_KEY, {}), []) ?? {}
  const guessed = useMemo(() => detectKind({ url: p.url }, p.items.filter((i) => i.feedUrl === p.url)), [p.items, p.url])
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
  const remove = async () => {
    setConfirmDelete(false)
    await deleteFeedEverywhere(f.url)
    p.toast('Feed deleted')
    p.onBack()
  }
  const setKind = async (v: string) => {
    const next = { ...(await getKv<Record<string, FeedKind>>(FEED_KINDS_KEY, {})) }
    if (v) next[f.url] = v as FeedKind
    else delete next[f.url]
    await setKv(FEED_KINDS_KEY, next)
  }
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
          <button type="button" className="mf-btn danger" title="Delete this feed and its articles" onClick={() => setConfirmDelete(true)}>
            <Icon name="delete" size={18} />
            <span>Delete feed</span>
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
          <label className="fp-row">
            <span>Type on Home</span>
            <select className="mf-select" value={kinds[f.url] ?? ''} onChange={(e) => void setKind(e.target.value)} title="Which type filter on Home this feed belongs to">
              <option value="">Automatic ({KIND_LABELS[guessed]})</option>
              {FEED_KINDS.map((k) => (
                <option key={k} value={k}>
                  {KIND_LABELS[k]}
                </option>
              ))}
            </select>
          </label>
          <EditFeedForm key={f.url} url={f.url} inline onDone={() => undefined} onDeleted={p.onBack} toast={p.toast} />
        </section>
      </div>
      {confirmDelete ? (
        <DeleteFeedDialog title={title} articles={stats.stored} saved={stats.saved} onConfirm={remove} onClose={() => setConfirmDelete(false)} />
      ) : null}
    </section>
  )
}
