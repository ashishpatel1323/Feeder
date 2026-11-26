import { useEffect, useMemo, useState } from 'react'
import { useLiveQuery } from 'dexie-react-hooks'
import type { RecentEntry } from '../lib/db'
import { agoText } from '../lib/device'
import {
  backfillRecent,
  clearRecent,
  FILTERS,
  groupRecent,
  matchesFilter,
  matchesQuery,
  progressText,
  recentEntries,
  removeRecent,
  thingText,
  titleText,
  TYPE_LABEL,
  type RecentFilter,
  type RecentType,
} from '../lib/recent'
import { syncNow } from '../lib/sync2'
import type { Toast } from '../lib/toast'
import { Modal } from './Dialogs'
import { confirmDelete } from '../lib/confirmDelete'
import { Icon, IconButton, type IconName } from './Icon'
import { MoreMenu } from './MoreMenu'

const ICONS: Record<RecentType, IconName> = {
  article: 'article',
  youtube: 'subscriptions',
  podcast: 'voice',
  audiobook: 'headphones',
  ebook: 'book',
  zim: 'public',
  highlight: 'highlight',
  note: 'note',
  feed: 'rss',
}

const timeText = (at: number) => new Date(at).toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })

/** "Yesterday 9:14 PM" style time for a row: the clock today and yesterday, else a short date. */
function whenText(at: number, now = Date.now()): string {
  const days = (now - at) / 86_400_000
  if (days < 2) return timeText(at)
  return new Date(at).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
}

/** The meta line: source · time · progress · device. */
function metaText(e: RecentEntry): string {
  const parts: string[] = []
  if (e.t === 'feed') parts.push(e.a === 'delete' ? 'Unsubscribed' : 'Subscribed')
  else if (e.s) parts.push(e.s)
  else parts.push(TYPE_LABEL[e.t])
  if (e.pn) parts.push(`from ${e.pn}`)
  parts.push(whenText(e.at))
  const p = progressText(e)
  if (p) parts.push(p)
  if (e.dn) parts.push(e.dn)
  return parts.join(' · ')
}

/**
 * History: everything opened on any synced device (articles, videos, episodes, audiobooks,
 * ebooks, ZIM pages), and highlights, notes and feeds made or deleted, newest first in Mail-style
 * groups. Tapping an entry goes back to it. Removing entries never deletes the things themselves.
 */
export function HistoryPage(p: {
  onOpen: (e: RecentEntry) => void
  /** A deleted feed: subscribe to it again */
  onResubscribe: (e: RecentEntry) => Promise<void>
  onMenu: () => void
  toast: Toast
}) {
  const [filter, setFilter] = useState<RecentFilter>('all')
  const [query, setQuery] = useState('')
  const [deletedFeed, setDeletedFeed] = useState<RecentEntry | null>(null)
  const entries = useLiveQuery(recentEntries, [])

  useEffect(() => {
    void backfillRecent().catch(() => undefined)
  }, [])

  const shown = useMemo(() => (entries ?? []).filter((e) => matchesFilter(e, filter) && matchesQuery(e, query)), [entries, filter, query])
  const groups = useMemo(() => groupRecent(shown), [shown])

  /** Asks first (the shared delete confirmation), then removes one entry or all of them. */
  const remove = async (r: RecentEntry | 'all') => {
    const ok = await confirmDelete(
      r === 'all'
        ? { title: 'Clear all history?', scope: 'all-devices', confirmLabel: 'Clear', extra: 'Your articles, videos, books, highlights, notes and feeds stay as they are.' }
        : { title: 'Remove from history?', itemName: titleText(r), scope: 'all-devices', confirmLabel: 'Remove', extra: `The ${thingText(r.t)} itself is not deleted.` },
    )
    if (!ok) return
    if (r === 'all') {
      await clearRecent()
      p.toast('History cleared')
    } else {
      await removeRecent(r.key)
      p.toast('Removed from history')
    }
    syncNow().catch(() => undefined)
  }

  const open = (e: RecentEntry) => {
    if (e.a === 'delete') {
      if (e.t === 'feed') setDeletedFeed(e)
      return
    }
    p.onOpen(e)
  }

  return (
    <section className="manage-pane hist-page" aria-label="History">
      <div className="mf-page">
        <div className="mf-head">
          <span className="only-narrow">
            <IconButton icon="menu" label="Feeds" onClick={p.onMenu} />
          </span>
          <h1>History</h1>
          {entries?.length ? <span className="mf-meta">{entries.length} {entries.length === 1 ? 'entry' : 'entries'}</span> : null}
          <span className="mf-sp" />
          <MoreMenu
            label="History"
            entries={[{ icon: 'delete', label: 'Clear history', danger: true, disabled: !entries?.length, onClick: () => void remove('all') }]}
          />
        </div>
        <label className="hist-search">
          <Icon name="search" size={18} />
          <input type="search" placeholder="Search history" aria-label="Search history" value={query} onChange={(e) => setQuery(e.target.value)} />
        </label>
        <div className="hist-chips" role="group" aria-label="Show">
          {FILTERS.map((f) => (
            <button key={f.id} type="button" className={`chip${filter === f.id ? ' on' : ''}`} aria-pressed={filter === f.id} onClick={() => setFilter(f.id)}>
              {filter === f.id ? <Icon name="check" size={16} /> : null}
              {f.label}
            </button>
          ))}
        </div>
        {!entries ? null : !shown.length ? (
          <div className="dl-empty">
            <Icon name="history" size={40} />
            <p>{entries.length ? 'Nothing here for this filter.' : 'No history yet.'}</p>
            {!entries.length ? <p className="mf-note">Articles, videos, episodes, books and ZIM pages you open show up here, with highlights, notes and feeds you add or delete, from all your synced devices.</p> : null}
          </div>
        ) : (
          groups.map((g) => (
            <section key={g.key} className="hist-group" aria-label={g.label}>
              <h2 className="hist-group-label">{g.label}</h2>
              <div className="lib-list">
                {g.entries.map((e) => (
                  <div key={e.key} className={`hist-entry${e.a === 'delete' ? ' deleted' : ''}`}>
                    <button
                      type="button"
                      className="hist-main"
                      onClick={() => open(e)}
                      disabled={e.a === 'delete' && e.t !== 'feed'}
                      title={e.a === 'delete' ? (e.t === 'feed' ? 'Subscribe again' : 'Deleted') : `Open ${thingText(e.t)}`}
                    >
                      <span className={`hist-thumb t-${e.t}${e.c ? ` c-${e.c}` : ''}`} aria-hidden="true">
                        {e.img && e.t !== 'highlight' && e.t !== 'note' ? <img src={e.img} alt="" loading="lazy" onError={(ev) => ((ev.target as HTMLImageElement).style.display = 'none')} /> : null}
                        <Icon name={ICONS[e.t]} size={20} />
                        {e.a === 'open' && typeof e.f === 'number' && !e.d && e.f > 0 ? (
                          <span className="hist-bar">
                            <span style={{ width: `${Math.round(e.f * 100)}%` }} />
                          </span>
                        ) : null}
                      </span>
                      <span className="lib-text">
                        <span className="hist-title">{titleText(e)}</span>
                        {e.q ? <span className={`hist-quote${e.c ? ` c-${e.c}` : ''}`}>{e.q}</span> : null}
                        <span className="lib-desc" title={new Date(e.at).toLocaleString()}>
                          {metaText(e)}
                        </span>
                      </span>
                    </button>
                    <MoreMenu label={`More for ${titleText(e)}`} entries={[{ icon: 'delete', label: 'Remove from history', onClick: () => void remove(e) }]} />
                  </div>
                ))}
              </div>
            </section>
          ))
        )}
      </div>
      {deletedFeed ? (
        <Modal title={deletedFeed.n} onClose={() => setDeletedFeed(null)}>
          <div className="form">
            <p>You unsubscribed {agoText(deletedFeed.at)}.</p>
            <p className="muted small">{deletedFeed.u ?? deletedFeed.i}</p>
            <div className="form-actions">
              <span className="spacer" />
              <button type="button" onClick={() => setDeletedFeed(null)}>
                Close
              </button>
              <button
                type="button"
                className="primary"
                autoFocus
                onClick={() => {
                  const e = deletedFeed
                  setDeletedFeed(null)
                  void p.onResubscribe(e)
                }}
              >
                Subscribe again
              </button>
            </div>
          </div>
        </Modal>
      ) : null}
    </section>
  )
}
