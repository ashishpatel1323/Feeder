/**
 * The YouTube tab, like Flow's Subscriptions (and the Android tab in ui/compose/youtube):
 * the videos of Feeder's own YouTube feeds, newest first, with groups (the folders those feeds are
 * in), a Shorts shelf, list or grid, search, and a channel manager. Videos open in YouTubeWatch.
 */
import { useEffect, useMemo, useRef, useState } from 'react'
import { useLiveQuery } from 'dexie-react-hooks'
import { db, newFeed, updateFeed, deleteFeeds, type Feed, type Item } from '../lib/db'
import { addFeed, importOpml, refreshFeeds } from '../lib/refresh'
import { syncNow } from '../lib/sync2'
import { noteFeedsDeleted } from '../lib/recent'
import {
  ageText,
  buildTab,
  channelFeedUrl,
  channelInputToUrl,
  channelsShown,
  durationText,
  filterRows,
  loadPrefs,
  parseTakeoutCsv,
  savePrefs,
  thumbUrl,
  viewsText,
  wantMeta,
  type Channel,
  type ChannelSort,
  type TabPrefs,
  type VideoRow,
} from '../lib/youtubeTab'
import { timeAgo } from '../lib/feedstats'
import { Icon, IconButton } from './Icon'
import { MoreMenu } from './MoreMenu'
import { Modal } from './Dialogs'

const sync = () => syncNow().catch(() => undefined)
/** Rows drawn at first; more as the list is scrolled. */
const PAGE = 40

export type YouTubeView = 'subs' | 'channels'

/** The tab's data, shared by the screens and the video page (prev/next, Subscribe). */
export function useYouTubeData(feeds: Feed[], items: Item[], group: string | null) {
  const meta = useLiveQuery(() => db.ytMeta.toArray(), [])
  const positions = useLiveQuery(() => db.videoPos.toArray(), [])
  return useMemo(
    () => buildTab(feeds, items, new Map((meta ?? []).map((m) => [m.id, m])), new Map((positions ?? []).map((p) => [p.id, p])), group),
    [feeds, items, meta, positions, group],
  )
}

export function useTabPrefs(): [TabPrefs, (p: Partial<TabPrefs>) => void] {
  const [prefs, setPrefs] = useState(loadPrefs)
  const update = (p: Partial<TabPrefs>) =>
    setPrefs((old) => {
      const next = { ...old, ...p }
      savePrefs(next)
      return next
    })
  return [prefs, update]
}

export function YouTubeTab(p: {
  view: YouTubeView
  feeds: Feed[]
  items: Item[]
  onView: (v: YouTubeView) => void
  onOpenVideo: (row: VideoRow, order: VideoRow[]) => void
  onMenu: () => void
  toast: (m: string) => void
}) {
  const [prefs, setPrefs] = useTabPrefs()
  const state = useYouTubeData(p.feeds, p.items, prefs.group)
  if (p.view === 'channels') return <ChannelManager channels={state.channels} groups={state.groups} prefs={prefs} setPrefs={setPrefs} onBack={() => p.onView('subs')} toast={p.toast} />
  return <Subscriptions state={state} prefs={prefs} setPrefs={setPrefs} {...p} />
}

// ---------------------------------------------------------------- Subscriptions

function Subscriptions(p: {
  state: ReturnType<typeof useYouTubeData>
  prefs: TabPrefs
  setPrefs: (p: Partial<TabPrefs>) => void
  onView: (v: YouTubeView) => void
  onOpenVideo: (row: VideoRow, order: VideoRow[]) => void
  onMenu: () => void
  toast: (m: string) => void
}) {
  const { state, prefs } = p
  const [query, setQuery] = useState('')
  const [searching, setSearching] = useState(false)
  const [shown, setShown] = useState(PAGE)
  const [refreshing, setRefreshing] = useState<string | null>(null)
  const [editGroups, setEditGroups] = useState(false)
  const meta = useLiveQuery(() => db.ytMeta.toArray(), [])
  const videos = useMemo(() => filterRows(state.videos, query), [state.videos, query])
  const shorts = useMemo(() => (query ? [] : state.shorts), [state.shorts, query])
  const page = videos.slice(0, shown)
  const more = useRef<HTMLDivElement>(null)

  // Lengths, views and Shorts flags for the rows on screen (and the next few)
  const wanted = useMemo(() => [...shorts.slice(0, 10), ...videos.slice(0, shown + 10)].map((r) => r.videoId), [shorts, videos, shown])
  useEffect(() => {
    if (!meta) return
    void wantMeta(wanted, new Map(meta.map((m) => [m.id, m])))
    // Only when the rows change, not on every lookup answer
  }, [wanted.join(','), !!meta])

  // Draws more rows when the end of the list comes into view
  useEffect(() => {
    const el = more.current
    if (!el || shown >= videos.length) return
    const io = new IntersectionObserver((es) => es.some((e) => e.isIntersecting) && setShown((n) => n + PAGE), { rootMargin: '600px' })
    io.observe(el)
    return () => io.disconnect()
  }, [shown, videos.length])

  useEffect(() => setShown(PAGE), [prefs.group, query])

  const refresh = async () => {
    if (refreshing) return
    const urls = state.channels.map((c) => c.url)
    if (!urls.length) return
    setRefreshing(`0/${urls.length}`)
    try {
      const n = await refreshFeeds(urls, (pr) => setRefreshing(`${pr.done}/${pr.total}`))
      p.toast(n ? `${n} new video${n === 1 ? '' : 's'}` : 'No new videos')
    } finally {
      setRefreshing(null)
    }
  }

  const checked = state.videos.length + state.shorts.length
  return (
    <section className="manage-pane yt-page" aria-label="YouTube subscriptions">
      <div className="yt-head">
        <span className="only-narrow">
          <IconButton icon="menu" label="Feeds" onClick={p.onMenu} />
        </span>
        <h1>Subscriptions</h1>
        <span className="yt-head-actions">
          <IconButton icon="search" label="Search subscriptions" active={searching} onClick={() => (searching ? (setSearching(false), setQuery('')) : setSearching(true))} />
          <IconButton icon={prefs.grid ? 'viewAgenda' : 'gridView'} label={prefs.grid ? 'Show as list' : 'Show as cards'} onClick={() => p.setPrefs({ grid: !prefs.grid })} />
          <IconButton icon="refresh" label={refreshing ? `Refreshing ${refreshing}` : 'Refresh YouTube feeds'} disabled={!!refreshing} onClick={() => void refresh()} />
          <IconButton icon="tune" label="Manage subscriptions" onClick={() => p.onView('channels')} />
        </span>
      </div>
      {searching ? (
        <div className="yt-search">
          <Icon name="search" size={18} />
          <input autoFocus type="search" placeholder="Search subscriptions…" aria-label="Search subscriptions" value={query} onChange={(e) => setQuery(e.target.value)} onKeyDown={(e) => e.key === 'Escape' && (setSearching(false), setQuery(''))} />
        </div>
      ) : null}
      {state.channels.length ? (
        <>
          <div className="yt-chips" role="radiogroup" aria-label="Groups">
            {[null, ...state.groups].map((g) => (
              <button key={g ?? ''} type="button" role="radio" aria-checked={state.group === g} className={`chip${state.group === g ? ' on' : ''}`} onClick={() => p.setPrefs({ group: g })}>
                {g ?? 'All'}
              </button>
            ))}
            <IconButton icon="edit" label="Edit groups" onClick={() => setEditGroups(true)} />
          </div>
          <p className="yt-status">
            {refreshing ? `Refreshing ${refreshing}` : `Last refreshed ${timeAgo(state.lastRefreshed)}`} · {checked} video{checked === 1 ? '' : 's'} checked
          </p>
          {shorts.length ? (
            <div className="yt-shorts">
              <h2 className="yt-section">
                <Icon name="bolt" size={20} /> Shorts
              </h2>
              <div className="yt-shorts-row">
                {shorts.map((r) => (
                  <button key={r.item.id} type="button" className="yt-short" onClick={() => p.onOpenVideo(r, shorts)} title={r.item.title}>
                    <img src={thumbUrl(r.videoId, true)} alt="" loading="lazy" referrerPolicy="no-referrer" />
                    <span className="yt-short-title">{r.item.title}</span>
                    {r.views != null ? <span className="yt-short-views">{viewsText(r.views)}</span> : null}
                  </button>
                ))}
              </div>
            </div>
          ) : null}
          {videos.length ? (
            <div className={prefs.grid ? 'yt-grid' : 'yt-list'}>
              {page.map((r) => (
                <VideoTile key={r.item.id} row={r} grid={prefs.grid} onOpen={() => p.onOpenVideo(r, videos)} />
              ))}
            </div>
          ) : (
            <p className="yt-empty-line">{query ? 'No videos match.' : 'No videos yet. Refresh to fetch the newest.'}</p>
          )}
          {shown < videos.length ? <div ref={more} className="yt-more" aria-hidden="true" /> : null}
        </>
      ) : (
        <div className="yt-empty">
          <Icon name="subscriptions" size={48} />
          <h2>No YouTube subscriptions yet</h2>
          <p>Your YouTube channel feeds show up here. Add a channel or import your subscriptions.</p>
          <button type="button" className="mf-btn primary" onClick={() => p.onView('channels')}>
            Add channels
          </button>
        </div>
      )}
      {editGroups ? <GroupsDialog channels={state.channels} groups={state.groups} current={state.group} onCurrent={(g) => p.setPrefs({ group: g })} onClose={() => setEditGroups(false)} /> : null}
    </section>
  )
}

export function VideoTile({ row: r, grid, onOpen }: { row: VideoRow; grid: boolean; onOpen: () => void }) {
  const meta = [viewsText(r.views), ageText(r.item.sortTime)].filter(Boolean).join(' · ')
  return (
    <button type="button" className={grid ? 'yt-card' : 'yt-row'} onClick={onOpen}>
      <span className="yt-thumb">
        <img src={thumbUrl(r.videoId, grid)} alt="" loading="lazy" referrerPolicy="no-referrer" />
        {r.isLive ? <span className="yt-badge live">LIVE</span> : r.duration ? <span className="yt-badge">{durationText(r.duration)}</span> : null}
        {r.progress > 0 ? (
          <span className="yt-watched" aria-label={r.progress >= 1 ? 'Watched' : `${Math.round(r.progress * 100)}% watched`}>
            <span style={{ width: `${Math.max(4, r.progress * 100)}%` }} />
          </span>
        ) : null}
      </span>
      <span className="yt-text">
        {grid ? <ChannelAvatar src={r.avatar} name={r.channel} /> : null}
        <span className="yt-text-col">
          <span className="yt-title">{r.item.title}</span>
          <span className="yt-meta">{grid ? [r.channel, meta].filter(Boolean).join(' · ') : r.channel}</span>
          {grid ? null : <span className="yt-meta">{meta}</span>}
        </span>
      </span>
    </button>
  )
}

export function ChannelAvatar({ src, name, size = 36 }: { src: string | null; name: string; size?: number }) {
  const [broken, setBroken] = useState(false)
  const style = { width: size, height: size }
  if (!src || broken)
    return (
      <span className="yt-avatar letter" style={style} aria-hidden="true">
        {(name.trim()[0] ?? '?').toUpperCase()}
      </span>
    )
  return <img className="yt-avatar" style={style} src={src} alt="" loading="lazy" referrerPolicy="no-referrer" onError={() => setBroken(true)} />
}

// ---------------------------------------------------------------- groups

/** Makes `name` a folder of exactly `urls` (a new group, or one whose channels were picked again). */
async function saveGroup(channels: Channel[], oldName: string | null, name: string, urls: Set<string>) {
  const newName = name.trim()
  if (!newName) return
  // Channels left out of the group go to no folder
  for (const c of channels) if ((c.tag === oldName || c.tag === newName) && !urls.has(c.url)) await updateFeed(c.url, { tag: '' })
  for (const url of urls) await updateFeed(url, { tag: newName })
  void sync()
}

function GroupsDialog(p: { channels: Channel[]; groups: string[]; current: string | null; onCurrent: (g: string | null) => void; onClose: () => void }) {
  const [editing, setEditing] = useState<{ old: string | null; name: string; urls: Set<string> } | null>(null)
  const [filter, setFilter] = useState('')
  const counts = (g: string) => p.channels.filter((c) => c.tag === g).length
  if (editing) {
    const list = channelsShown(p.channels, filter, false, 'name').concat(channelsShown(p.channels, filter, true, 'name'))
    return (
      <Modal title={editing.old ? `Edit ${editing.old}` : 'New group'} onClose={() => setEditing(null)}>
        <label>
          Group name
          <input autoFocus value={editing.name} onChange={(e) => setEditing({ ...editing, name: e.target.value })} />
        </label>
        <h3 className="yt-dialog-label">Channels in this group</h3>
        <input type="search" placeholder="Search channels…" aria-label="Search channels" value={filter} onChange={(e) => setFilter(e.target.value)} />
        <ul className="yt-pick">
          {list.map((c) => (
            <li key={c.url}>
              <label className="check">
                <input
                  type="checkbox"
                  checked={editing.urls.has(c.url)}
                  onChange={(e) => {
                    const urls = new Set(editing.urls)
                    if (e.target.checked) urls.add(c.url)
                    else urls.delete(c.url)
                    setEditing({ ...editing, urls })
                  }}
                />
                <ChannelAvatar src={c.avatar} name={c.title} size={28} />
                <span className="yt-pick-name">{c.title}</span>
                {c.tag && c.tag !== editing.old ? <span className="muted small">{c.tag}</span> : null}
              </label>
            </li>
          ))}
        </ul>
        <div className="form-actions">
          <button type="button" onClick={() => setEditing(null)}>
            Cancel
          </button>
          <button
            type="button"
            className="primary"
            disabled={!editing.name.trim()}
            onClick={async () => {
              await saveGroup(p.channels, editing.old, editing.name, editing.urls)
              if (editing.old && p.current === editing.old) p.onCurrent(editing.name.trim())
              setEditing(null)
            }}
          >
            {editing.old ? 'Done' : 'Create and move'}
          </button>
        </div>
      </Modal>
    )
  }
  return (
    <Modal title="Edit groups" onClose={p.onClose}>
      <p className="muted small">Groups are the folders your YouTube feeds are in, the same as in Feeds.</p>
      <ul className="yt-groups">
        {p.groups.map((g) => (
          <li key={g}>
            <span className="yt-group-name">{g}</span>
            <span className="muted small">
              {counts(g)} channel{counts(g) === 1 ? '' : 's'}
            </span>
            <IconButton icon="edit" label={`Edit ${g}`} onClick={() => setEditing({ old: g, name: g, urls: new Set(p.channels.filter((c) => c.tag === g).map((c) => c.url)) })} />
            <IconButton
              icon="delete"
              label={`Delete ${g}`}
              onClick={async () => {
                if (!confirm(`Delete ${g}? Its channels stay subscribed and move out of the group (the folder in Feeds).`)) return
                for (const c of p.channels) if (c.tag === g) await updateFeed(c.url, { tag: '' })
                if (p.current === g) p.onCurrent(null)
                void sync()
              }}
            />
          </li>
        ))}
      </ul>
      <div className="form-actions">
        <button type="button" onClick={() => setEditing({ old: null, name: '', urls: new Set() })}>
          New group
        </button>
        <button type="button" className="primary" onClick={p.onClose}>
          Done
        </button>
      </div>
    </Modal>
  )
}

// ---------------------------------------------------------------- channel manager

const SORTS: { sort: ChannelSort; label: string }[] = [
  { sort: 'name', label: 'A–Z' },
  { sort: 'recent', label: 'Recently added' },
  { sort: 'active', label: 'Most active' },
]

/** Unsubscribe: deletes the feed, as everywhere else in Feeder (after asking). */
export async function unsubscribe(feed: Pick<Feed, 'url'>, title: string): Promise<boolean> {
  const n = await db.items.where('feedUrl').equals(feed.url).count()
  if (!confirm(`Unsubscribe from ${title}? This deletes the feed and its ${n} video${n === 1 ? '' : 's'} here and on your other devices.`)) return false
  await noteFeedsDeleted([feed.url])
  await deleteFeeds([feed.url])
  void sync()
  return true
}

/** Subscribes to a channel given as a link, @handle or channel id: adds its feed. Returns a message. */
export async function subscribe(input: string, tag: string): Promise<string> {
  const url = channelInputToUrl(input)
  if (!url) return "Couldn't find a YouTube channel there"
  if (url.includes('/feeds/videos.xml') && (await db.feeds.get(url))) return 'You already follow this channel'
  try {
    const before = await db.feeds.count()
    await addFeed(url, tag)
    void sync()
    return (await db.feeds.count()) > before ? "Subscribed: the channel's videos are on their way" : 'You already follow this channel'
  } catch {
    return "Couldn't find a YouTube channel there"
  }
}

function ChannelManager(p: { channels: Channel[]; groups: string[]; prefs: TabPrefs; setPrefs: (p: Partial<TabPrefs>) => void; onBack: () => void; toast: (m: string) => void }) {
  const [music, setMusic] = useState(false)
  const [query, setQuery] = useState('')
  const [adding, setAdding] = useState(false)
  const [moving, setMoving] = useState<Channel | null>(null)
  const list = channelsShown(p.channels, query, music, p.prefs.sort)
  const videoCount = p.channels.filter((c) => !c.music).length
  const musicCount = p.channels.length - videoCount
  const sortLabel = SORTS.find((s) => s.sort === p.prefs.sort)!.label
  return (
    <section className="manage-pane yt-page yt-manager" aria-label="Manage subscriptions">
      <div className="yt-head">
        <IconButton icon="back" label="Back to subscriptions" onClick={p.onBack} />
        <h1>Manage subscriptions</h1>
        <span className="yt-head-actions">
          <button type="button" className="mf-btn primary" aria-label="Import or add" title="Import or add channels" onClick={() => setAdding(true)}>
            <Icon name="add" size={18} /> <span className="yt-btn-text">Import or add</span>
          </button>
        </span>
      </div>
      <div className="segmented yt-seg" role="radiogroup" aria-label="Kind of subscriptions">
        <button type="button" role="radio" aria-checked={!music} className={`seg${!music ? ' on' : ''}`} onClick={() => setMusic(false)}>
          <Icon name="subscriptions" size={18} /> Video <span className="yt-seg-count">{videoCount}</span>
        </button>
        <button type="button" role="radio" aria-checked={music} className={`seg${music ? ' on' : ''}`} onClick={() => setMusic(true)}>
          <Icon name="musicNote" size={18} /> Music <span className="yt-seg-count">{musicCount}</span>
        </button>
      </div>
      <div className="yt-manager-tools">
        <div className="yt-search">
          <Icon name="search" size={18} />
          <input type="search" placeholder="Search subscriptions…" aria-label="Search subscriptions" value={query} onChange={(e) => setQuery(e.target.value)} />
        </div>
        <MoreMenu
          label={`Sort: ${sortLabel}`}
          pill={sortLabel}
          entries={SORTS.map((s) => ({ icon: 'sort', label: s.label, checked: p.prefs.sort === s.sort, onClick: () => p.setPrefs({ sort: s.sort }) }))}
        />
      </div>
      <p className="yt-status">
        {list.length} {music ? 'music' : 'video'} channel{list.length === 1 ? '' : 's'}
      </p>
      <ul className="yt-channels">
        {list.map((c) => (
          <li key={c.url} className="yt-channel">
            <ChannelAvatar src={c.avatar} name={c.title} size={44} />
            <span className="yt-channel-text">
              <span className="yt-channel-name">{c.title}</span>
              <span className="yt-meta">{[c.tag, `${c.recentCount} video${c.recentCount === 1 ? '' : 's'} in 30 days`].filter(Boolean).join(' · ')}</span>
            </span>
            <MoreMenu
              label={`Subscribed to ${c.title}`}
              pill="Subscribed"
              entries={[
                { icon: 'folder', label: 'Move to group', onClick: () => setMoving(c) },
                c.music
                  ? { icon: 'subscriptions', label: 'Move to video subscriptions', onClick: () => void updateFeed(c.url, { youtubeMusic: false }).then(sync) }
                  : { icon: 'musicNote', label: 'Move to music subscriptions', onClick: () => void updateFeed(c.url, { youtubeMusic: true }).then(sync) },
                'separator',
                { icon: 'delete', label: 'Unsubscribe', danger: true, onClick: () => void unsubscribe(c.feed, c.title).then((ok) => ok && p.toast(`Unsubscribed from ${c.title}`)) },
              ]}
            />
          </li>
        ))}
      </ul>
      {!list.length ? <p className="yt-empty-line">{query ? 'No channels match.' : music ? 'No music channels. Move a channel here from its Subscribed menu.' : 'No channels yet.'}</p> : null}
      {adding ? <AddChannelsDialog group={p.prefs.group} onClose={() => setAdding(false)} toast={p.toast} /> : null}
      {moving ? <MoveToGroupDialog channel={moving} groups={p.groups} onClose={() => setMoving(null)} /> : null}
    </section>
  )
}

function MoveToGroupDialog({ channel, groups, onClose }: { channel: Channel; groups: string[]; onClose: () => void }) {
  const [name, setName] = useState('')
  const move = async (tag: string) => {
    await updateFeed(channel.url, { tag })
    void sync()
    onClose()
  }
  return (
    <Modal title={`Move ${channel.title} to group`} onClose={onClose}>
      <ul className="yt-groups pick">
        {['', ...groups].map((g) => (
          <li key={g}>
            <button type="button" className={`yt-group-btn${channel.tag === g ? ' on' : ''}`} onClick={() => void move(g)}>
              <Icon name={g ? 'folder' : 'close'} size={20} />
              <span>{g || 'No group'}</span>
              {channel.tag === g ? <Icon name="check" size={18} /> : null}
            </button>
          </li>
        ))}
      </ul>
      <form
        className="yt-inline-form"
        onSubmit={(e) => {
          e.preventDefault()
          if (name.trim()) void move(name.trim())
        }}
      >
        <input placeholder="New group" aria-label="New group name" value={name} onChange={(e) => setName(e.target.value)} />
        <button type="submit" disabled={!name.trim()}>
          Create and move
        </button>
      </form>
    </Modal>
  )
}

function AddChannelsDialog({ group, onClose, toast }: { group: string | null; onClose: () => void; toast: (m: string) => void }) {
  const [input, setInput] = useState('')
  const [busy, setBusy] = useState(false)
  const onFile = async (file: File) => {
    setBusy(true)
    try {
      const text = await file.text()
      if (text.trimStart().startsWith('<')) {
        const n = await importOpml(text)
        toast(n ? `Importing ${n} feed${n === 1 ? '' : 's'}…` : 'No new feeds in that file')
        onClose()
        void refreshFeeds().then(sync)
        return
      }
      const rows = parseTakeoutCsv(text)
      if (!rows.length) return toast("Couldn't read that file")
      const added: string[] = []
      for (const r of rows) {
        const url = channelFeedUrl(r.id)
        if (await db.feeds.get(url)) continue
        await db.feeds.put(newFeed(url, { title: r.title ?? url, tag: group ?? '' }))
        added.push(url)
      }
      toast(added.length ? `Subscribed to ${added.length} channel${added.length === 1 ? '' : 's'}` : 'You already follow all of them')
      onClose()
      if (added.length) void refreshFeeds(added).then(sync)
    } catch {
      toast("Couldn't read that file")
    } finally {
      setBusy(false)
    }
  }
  return (
    <Modal title="Import or add" onClose={onClose}>
      <form
        className="yt-add-form"
        onSubmit={async (e) => {
          e.preventDefault()
          if (!input.trim()) return
          setBusy(true)
          const msg = await subscribe(input, group ?? '')
          setBusy(false)
          toast(msg)
          if (msg.startsWith('Subscribed')) onClose()
        }}
      >
        <label>
          Add a channel
          <input autoFocus placeholder="Channel link or @handle" value={input} onChange={(e) => setInput(e.target.value)} disabled={busy} />
        </label>
        <div className="form-actions">
          <button type="submit" className="primary" disabled={busy || !input.trim()}>
            {busy ? 'Adding…' : 'Subscribe'}
          </button>
        </div>
      </form>
      <h3 className="yt-dialog-label">Import</h3>
      <p className="muted small">An OPML file from another reader, or subscriptions.csv from Google Takeout (YouTube and YouTube Music › subscriptions). Each channel becomes a feed{group ? ` in ${group}` : ''}.</p>
      <label className="file-btn">
        Import OPML or subscriptions.csv
        <input type="file" accept=".opml,.xml,.csv,text/xml,text/csv" disabled={busy} onChange={(e) => e.target.files?.[0] && void onFile(e.target.files[0])} />
      </label>
    </Modal>
  )
}
