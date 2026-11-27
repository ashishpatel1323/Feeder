import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { useLiveQuery } from 'dexie-react-hooks'
import { feedDisplayTitle, sortFolders, type Feed, type Item } from '../lib/db'
import {
  deletePlaylist,
  describeRules,
  EMPTY_RULES,
  formatTotal,
  ITEM_KIND_LABELS,
  ITEM_KINDS,
  kindOf,
  loadPlaylists,
  seedPlaylists,
  matchesRules,
  moveItem,
  newPlaylist,
  PLAY_STATUS_LABELS,
  PLAY_STATUSES,
  PLAYLIST_COLORS,
  PLAYLIST_ICONS,
  PLAYLIST_SORTS,
  playlistItems,
  removeFromPlaylist,
  restorePlaylists,
  savePlaylist,
  SORT_LABELS,
  totalSeconds,
  WITHIN_CHOICES,
  withinLabel,
  type Playlist,
  type PlaylistContext,
  type SmartRules,
} from '../lib/playlists'
import { playQueue, useQueue } from '../lib/playqueue'
import { BATCH_SIZE as PREPARE_BATCH, playlistAudio, type PlaylistAudioState } from '../lib/playlistAudio'
import { readAloud } from '../lib/readaloud'
import { saveVideo, useVideoSavingAvailable } from '../lib/offline'
import { articleVideoId } from '../lib/youtube'
import { Icon, IconButton } from './Icon'
import { deleteItemWithUndo, ItemRow, usePlaylistContext } from './ItemRow'
import { MoreMenu } from './MoreMenu'
import { confirmDelete } from '../lib/confirmDelete'

export { usePlaylistContext }

type Toast = (msg: string, action?: { label: string; run: () => void }) => void

interface Props {
  /** null = the list of playlists, else one playlist's page. */
  playlistId: string | null
  items: Item[]
  feeds: Map<string, Feed>
  readRate: number
  onRoute: (id: string | null) => void
  onOpenArticle: (id: string, order: string[]) => void
  /** Opens Flip mode on these items, in this order. */
  onFlip: (order: string[]) => void
  onMenu: () => void
  onBack: () => void
  toast: Toast
}

export function usePlaylists(): Playlist[] {
  useEffect(() => void seedPlaylists().catch(() => undefined), [])
  return useLiveQuery(() => loadPlaylists(), []) ?? []
}

function Tile({ pl, size = 44 }: { pl: Pick<Playlist, 'icon' | 'color'>; size?: number }) {
  return (
    <span className="pl-tile" style={{ background: pl.color, width: size, height: size, fontSize: size * 0.45 }} aria-hidden="true">
      {pl.icon}
    </span>
  )
}

export function PlaylistsPage(p: Props) {
  const lists = usePlaylists()
  const ctx = usePlaylistContext(p.feeds, p.readRate)
  const byId = useMemo(() => new Map(p.items.map((i) => [i.id, i])), [p.items])
  const [editing, setEditing] = useState<Playlist | null>(null)
  const current = p.playlistId ? lists.find((l) => l.id === p.playlistId) : null

  const play = (pl: Playlist, start = 0, shuffle = false) => {
    let ids = playlistItems(pl, p.items, ctx, byId).map((i) => i.id)
    if (!ids.length) return p.toast(`${pl.name} has nothing to play right now`)
    if (shuffle) {
      ids = [...ids]
      for (let i = ids.length - 1; i > 0; i--) {
        const j = Math.floor(Math.random() * (i + 1))
        ;[ids[i], ids[j]] = [ids[j], ids[i]]
      }
    }
    playQueue.start(ids, { name: pl.name, playlistId: pl.id, start })
  }

  const remove = async (pl: Playlist) => {
    if (!(await confirmDelete({ title: 'Delete playlist?', itemName: pl.name, scope: 'all-devices', extra: 'The articles and episodes in it stay in Feeder.' }))) return
    const before = lists
    await deletePlaylist(pl.id)
    p.onRoute(null)
    p.toast(`Deleted ${pl.name}`, { label: 'Undo', run: () => void restorePlaylists(before) })
  }

  return (
    <section className="manage-pane pl-page" aria-label="Playlists">
      <div className="mf-page">
        {current ? (
          <PlaylistDetail
            pl={current}
            items={p.items}
            byId={byId}
            feeds={p.feeds}
            ctx={ctx}
            onBack={() => p.onRoute(null)}
            onMenu={p.onMenu}
            onPlay={(start, shuffle) => play(current, start, shuffle)}
            onEdit={() => setEditing(current)}
            onDelete={() => void remove(current)}
            onFlip={() => {
              const ids = playlistItems(current, p.items, ctx, byId).map((i) => i.id)
              if (!ids.length) return p.toast(`${current.name} has nothing to flip through right now`)
              p.onFlip(ids)
            }}
            onOpenArticle={p.onOpenArticle}
            toast={p.toast}
          />
        ) : (
          <PlaylistIndex
            lists={lists}
            items={p.items}
            byId={byId}
            ctx={ctx}
            onOpen={(id) => p.onRoute(id)}
            onPlay={(pl) => play(pl)}
            onNew={(smart) => setEditing(newPlaylist({ name: smart ? 'New smart playlist' : 'New playlist', smart }, lists))}
            onMenu={p.onMenu}
            onBack={p.onBack}
          />
        )}
      </div>
      {editing ? (
        <PlaylistEditor
          initial={editing}
          isNew={!lists.some((l) => l.id === editing.id)}
          items={p.items}
          feeds={p.feeds}
          ctx={ctx}
          onCancel={() => setEditing(null)}
          onSave={async (pl, isNew) => {
            await savePlaylist(pl)
            setEditing(null)
            if (isNew) p.onRoute(pl.id)
            p.toast(isNew ? `Created ${pl.name}` : 'Playlist saved')
          }}
        />
      ) : null}
    </section>
  )
}

function PlaylistIndex(p: {
  lists: Playlist[]
  items: Item[]
  byId: Map<string, Item>
  ctx: PlaylistContext
  onOpen: (id: string) => void
  onPlay: (pl: Playlist) => void
  onNew: (smart: boolean) => void
  onMenu: () => void
  onBack: () => void
}) {
  const q = useQueue()
  const row = (pl: Playlist) => {
    const its = playlistItems(pl, p.items, p.ctx, p.byId)
    const t = totalSeconds(its, p.ctx)
    const playingThis = q.playlistId === pl.id && q.kind
    return (
      <li key={pl.id} className="pl-row">
        <button className="pl-row-main" onClick={() => p.onOpen(pl.id)}>
          <Tile pl={pl} />
          <span className="pl-row-text">
            <span className="pl-row-name">{pl.name}</span>
            <span className="pl-row-meta">
              {pl.smart ? 'Smart' : 'Manual'} · {its.length} {its.length === 1 ? 'item' : 'items'}
              {t.seconds ? ` · ${formatTotal(t.seconds)}` : ''}
              {playingThis ? ' · playing now' : ''}
            </span>
          </span>
        </button>
        <button className="pl-play" onClick={() => p.onPlay(pl)} aria-label={`Play ${pl.name}`} title={`Play ${pl.name}`} disabled={!its.length}>
          <Icon name="play" size={22} />
          <span>Play</span>
        </button>
      </li>
    )
  }
  const smart = p.lists.filter((l) => l.smart)
  const manual = p.lists.filter((l) => !l.smart)
  return (
    <>
      <div className="mf-head">
        <span className="only-narrow">
          <IconButton icon="menu" label="Feeds" onClick={p.onMenu} />
        </span>
        <h1>Playlists</h1>
        <span className="mf-meta">Listen while you drive: podcasts play as audio, articles are read aloud</span>
        <span className="mf-sp" />
        <button className="mf-btn" onClick={() => p.onNew(true)}>
          <Icon name="bolt" size={18} />
          <span>New smart playlist</span>
        </button>
        <button className="mf-btn primary" onClick={() => p.onNew(false)}>
          <Icon name="add" size={18} />
          <span>New playlist</span>
        </button>
      </div>
      <h2 className="pl-section">Smart playlists · fill themselves from rules</h2>
      {smart.length ? <ul className="pl-list">{smart.map(row)}</ul> : <p className="mf-note">No smart playlists. Make one to collect, say, every unread episode under 40 minutes.</p>}
      <h2 className="pl-section">My playlists · you add and order</h2>
      {manual.length ? (
        <ul className="pl-list">{manual.map(row)}</ul>
      ) : (
        <p className="mf-note">
          None yet. Use the <b>Playlist</b> button on any article, episode or video to start one.
        </p>
      )}
    </>
  )
}

function PlaylistDetail(p: {
  pl: Playlist
  items: Item[]
  byId: Map<string, Item>
  feeds: Map<string, Feed>
  ctx: PlaylistContext
  onBack: () => void
  onMenu: () => void
  onPlay: (start: number, shuffle?: boolean) => void
  onEdit: () => void
  onDelete: () => void
  onFlip: () => void
  onOpenArticle: (id: string, order: string[]) => void
  toast: Toast
}) {
  const { pl } = p
  const q = useQueue()
  const canSave = useVideoSavingAvailable()
  const [dragging, setDragging] = useState<string | null>(null)
  const its = playlistItems(pl, p.items, p.ctx, p.byId)
  const t = totalSeconds(its, p.ctx)
  const chips = pl.smart ? describeRules(pl.rules, p.feeds) : []
  const toDownload = its.filter((i) => kindOf(i, p.ctx) === 'youtube' && !i.offlineVideo)
  const playingId = q.playlistId === pl.id ? q.ids[q.index] : null
  const offline = usePlaylistAudio()
  // The next text articles: after the one playing when this playlist plays, else from the top
  const toPrepare = (() => {
    const order = q.playlistId === pl.id && q.ids.length ? q.ids.slice(q.index + 1).map((id) => its.find((i) => i.id === id)).filter((i): i is Item => !!i) : its
    return order.filter((i) => kindOf(i, p.ctx) === 'article').slice(0, PREPARE_BATCH)
  })()
  const prepare = () => {
    if (!playlistAudio.supported(readAloud.voiceURI)) {
      p.toast('Getting articles ready offline needs the natural voice. Pick one under Settings › Read aloud.')
      return
    }
    void playlistAudio.start(pl.id, toPrepare.map((i) => i.id), readAloud.voiceURI, readAloud.kokoroGpu)
    p.toast(`Getting ${toPrepare.length} article${toPrepare.length === 1 ? '' : 's'} ready offline. Keep Feeder open while it works.`)
  }

  const setSort = (sort: Playlist['sort']) => void savePlaylist({ ...pl, sort })
  const move = (id: string, to: number) => void savePlaylist(moveItem(pl, id, to))
  const downloadAll = async () => {
    for (const i of toDownload) {
      const vid = articleVideoId(i)
      if (vid) void saveVideo(i.id, vid).catch(() => undefined)
    }
    p.toast(`Downloading ${toDownload.length} video${toDownload.length === 1 ? '' : 's'}. Podcasts play straight from the feed.`)
  }

  return (
    <>
      <div className="mf-head pl-head">
        <span className="only-narrow">
          <IconButton icon="menu" label="Feeds" onClick={p.onMenu} />
        </span>
        <IconButton icon="back" label="All playlists" onClick={p.onBack} />
        <Tile pl={pl} size={56} />
        <div className="pl-head-text">
          <div className="pl-head-kind">{pl.smart ? 'Smart playlist' : 'My playlist'}</div>
          <h1>{pl.name}</h1>
          <div className="mf-meta">
            {its.length} {its.length === 1 ? 'item' : 'items'}
            {t.seconds ? ` · ${formatTotal(t.seconds)}` : ''}
            {t.unknown ? ` · ${t.unknown} of unknown length` : ''}
          </div>
        </div>
        <span className="mf-sp" />
        <div className="pl-actions">
          <button className="mf-btn primary big" onClick={() => p.onPlay(0)} disabled={!its.length}>
            <Icon name="play" size={20} />
            <span>Play all</span>
          </button>
          <IconButton icon="shuffle" label="Shuffle" onClick={() => p.onPlay(0, true)} disabled={its.length < 2} />
          <IconButton icon="flip" label="Flip mode" onClick={p.onFlip} disabled={!its.length} />
          <MoreMenu
            label="Playlist"
            entries={[
              { icon: 'edit', label: pl.smart ? 'Edit rules' : 'Rename', onClick: p.onEdit },
              {
                icon: 'download',
                label: `Download videos (${toDownload.length})`,
                onClick: () => void downloadAll(),
                hidden: !(canSave && toDownload.length),
              },
              {
                icon: 'headphones',
                label: `Get next ${toPrepare.length} article${toPrepare.length === 1 ? '' : 's'} ready offline`,
                onClick: prepare,
                hidden: !toPrepare.length,
              },
              'separator',
              { icon: 'delete', label: 'Delete playlist', onClick: p.onDelete, danger: true },
            ]}
          />
        </div>
      </div>
      {pl.smart ? (
        <div className="pl-rules">
          {/* Rules and order on one line, as the article list's filters are */}
          <button className={`pl-rule${chips.length ? ' on' : ''}`} onClick={p.onEdit} title="Change the rules">
            <Icon name="filter" size={16} />
            <span className="pl-rule-text">{chips.length ? chips.map((c) => c.value).join(' · ') : 'Every item matches · add a rule'}</span>
          </button>
          <label className="select-chip">
            <span>Sort</span>
            <select value={pl.sort} onChange={(e) => setSort(e.target.value as Playlist['sort'])}>
              {PLAYLIST_SORTS.map((s) => (
                <option key={s} value={s}>
                  {SORT_LABELS[s]}
                </option>
              ))}
            </select>
          </label>
        </div>
      ) : (
        <p className="mf-note pl-hint">Drag rows (or use the arrows) to change the order. Items leave this playlist once they are played to the end.</p>
      )}
      {offline.playlistId === pl.id && offline.ids.length ? <OfflineArticlesCard s={offline} /> : null}
      {!its.length ? (
        <div className="dl-empty">
          <Icon name="queue" size={40} />
          <p>{pl.smart ? 'Nothing matches these rules right now.' : 'This playlist is empty.'}</p>
          <p className="mf-note">{pl.smart ? 'Loosen a rule, or wait for new episodes and articles.' : 'Use the Playlist button on any article, episode or video to add it here.'}</p>
        </div>
      ) : (
        <ol className="pl-items">
          {its.map((it, index) => {
            const kind = kindOf(it, p.ctx)
            const needsDownload = kind === 'youtube' && !it.offlineVideo
            return (
              <li key={it.id}>
                <ItemRow
                  item={it}
                  feed={p.feeds.get(it.feedUrl)}
                  ctx={p.ctx}
                  className={`pl-item${it.id === playingId ? ' playing' : ''}${dragging === it.id ? ' dragging' : ''}`}
                  rowProps={{
                    draggable: !pl.smart,
                    onDragStart: (e) => {
                      setDragging(it.id)
                      e.dataTransfer.effectAllowed = 'move'
                      e.dataTransfer.setData('text/plain', it.id)
                    },
                    onDragEnd: () => setDragging(null),
                    onDragOver: (e) => {
                      if (!pl.smart && dragging) e.preventDefault()
                    },
                    onDrop: (e) => {
                      e.preventDefault()
                      if (dragging && dragging !== it.id) move(dragging, index)
                      setDragging(null)
                    },
                  }}
                  leading={
                    pl.smart ? (
                      <span className="ir-lead pl-num">{index + 1}</span>
                    ) : (
                      <span className="ir-lead pl-grip" aria-hidden="true">
                        <Icon name="dragHandle" size={18} />
                      </span>
                    )
                  }
                  onOpen={() => p.onOpenArticle(it.id, its.map((x) => x.id))}
                  onPrimary={() => p.onPlay(index)}
                  primaryLabel={it.id === playingId ? 'Playing' : 'Play'}
                  primaryIcon="play"
                  metaExtra={
                    needsDownload ? (
                      <span>Needs download to play</span>
                    ) : offline.ready.has(it.id) ? (
                      <span className="pl-offline">
                        <Icon name="check" size={14} />
                        Ready offline
                      </span>
                    ) : offline.preparingId === it.id ? (
                      <span className="pl-offline">Preparing · {Math.round(offline.progress * 100)}%</span>
                    ) : null
                  }
                  inPlaylist
                  onDelete={() => void deleteItemWithUndo(it, p.toast)}
                  extras={
                    !pl.smart ? (
                      <>
                        <button type="button" className="mf-btn ir-btn" onClick={() => move(it.id, index - 1)} disabled={index === 0}>
                          <Icon name="collapse" size={16} />
                          Move up
                        </button>
                        <button type="button" className="mf-btn ir-btn" onClick={() => move(it.id, index + 1)} disabled={index === its.length - 1}>
                          <Icon name="expand" size={16} />
                          Move down
                        </button>
                        <button
                          type="button"
                          className="mf-btn ir-btn"
                          onClick={async () => {
                            if (!(await confirmDelete({ title: 'Remove from playlist?', itemName: it.title || 'Untitled', scope: 'all-devices', confirmLabel: 'Remove', extra: `Only leaves ${pl.name}; it stays in Feeder.` }))) return
                            void removeFromPlaylist(pl.id, [it.id])
                            p.toast('Taken out of the playlist', { label: 'Undo', run: () => void savePlaylist(pl) })
                          }}
                        >
                          <Icon name="close" size={16} />
                          Remove
                        </button>
                      </>
                    ) : null
                  }
                />
              </li>
            )
          })}
        </ol>
      )}
    </>
  )
}

function Chip({ on, onClick, children }: { on: boolean; onClick: () => void; children: ReactNode }) {
  return (
    <button type="button" className={`chip${on ? ' on' : ''}`} onClick={onClick} aria-pressed={on}>
      {children}
    </button>
  )
}

const toggle = <T,>(list: T[], v: T) => (list.includes(v) ? list.filter((x) => x !== v) : [...list, v])

function PlaylistEditor(p: {
  initial: Playlist
  isNew: boolean
  items: Item[]
  feeds: Map<string, Feed>
  ctx: PlaylistContext
  onCancel: () => void
  onSave: (pl: Playlist, isNew: boolean) => void
}) {
  const [pl, setPl] = useState<Playlist>(p.initial)
  const [showFeeds, setShowFeeds] = useState(p.initial.rules.feeds.length > 0)
  const r = pl.rules
  const setRules = (patch: Partial<SmartRules>) => setPl((x) => ({ ...x, rules: { ...x.rules, ...patch } }))
  const folders = useMemo(() => sortFolders([...new Set([...p.feeds.values()].map((f) => f.tag).filter(Boolean))], []), [p.feeds])
  const feedList = useMemo(
    () => [...p.feeds.values()].filter((f) => !f.url.startsWith('https://shared.')).sort((a, b) => feedDisplayTitle(a).localeCompare(feedDisplayTitle(b))),
    [p.feeds],
  )
  const matching = useMemo(() => (pl.smart ? p.items.filter((i) => matchesRules(i, r, p.ctx)).length : pl.itemIds.length), [pl.smart, p.items, r, p.ctx, pl.itemIds.length])
  const lengthChoices: [string, number | null, number | null][] = [
    ['Any length', null, null],
    ['Under 10 min', null, 10],
    ['Under 20 min', null, 20],
    ['Under 40 min', null, 40],
    ['Under 1 hour', null, 60],
    ['Over 20 min', 20, null],
    ['Over 1 hour', 60, null],
  ]

  return (
    <div className="pl-dialog-scrim" onClick={(e) => e.target === e.currentTarget && p.onCancel()}>
      <div className="pl-dialog" role="dialog" aria-modal="true" aria-label={pl.smart ? 'Smart playlist' : 'Playlist'}>
        <h2>{p.isNew ? (pl.smart ? 'New smart playlist' : 'New playlist') : pl.smart ? 'Edit smart playlist' : 'Edit playlist'}</h2>
        <p className="mf-note">
          {pl.smart ? 'Shows what matches these rules right now. Items you finish are marked read, so with Unread on they drop off by themselves.' : 'Fill it with the Playlist button on any article, episode or video.'}
        </p>
        <div className="pl-field">
          <label className="pl-k" htmlFor="pl-name">
            Name and look
          </label>
          <div className="pl-name-row">
            <Tile pl={pl} size={40} />
            <input id="pl-name" value={pl.name} onChange={(e) => setPl({ ...pl, name: e.target.value })} />
          </div>
          <div className="pl-opts">
            {PLAYLIST_ICONS.map((ic) => (
              <button key={ic} type="button" className={`pl-ic${pl.icon === ic ? ' on' : ''}`} onClick={() => setPl({ ...pl, icon: ic })} aria-label={`Icon ${ic}`}>
                {ic}
              </button>
            ))}
          </div>
          <div className="pl-opts">
            {PLAYLIST_COLORS.map((c) => (
              <button key={c} type="button" className={`pl-color${pl.color === c ? ' on' : ''}`} style={{ background: c }} onClick={() => setPl({ ...pl, color: c })} aria-label={`Color ${c}`} />
            ))}
          </div>
        </div>
        {pl.smart ? (
          <>
            <div className="pl-field">
              <div className="pl-k">Folders and feeds</div>
              <div className="pl-opts">
                <Chip on={!r.folders.length && !r.feeds.length} onClick={() => setRules({ folders: [], feeds: [] })}>
                  All
                </Chip>
                {folders.map((f) => (
                  <Chip key={f} on={r.folders.includes(f)} onClick={() => setRules({ folders: toggle(r.folders, f) })}>
                    <Icon name="folder" size={15} /> {f}
                  </Chip>
                ))}
                <Chip on={showFeeds || r.feeds.length > 0} onClick={() => setShowFeeds((s) => !s)}>
                  {r.feeds.length ? `${r.feeds.length} feeds picked` : 'Pick feeds…'}
                </Chip>
              </div>
              {showFeeds ? (
                <div className="pl-feeds">
                  {feedList.map((f) => (
                    <label key={f.url}>
                      <input type="checkbox" checked={r.feeds.includes(f.url)} onChange={() => setRules({ feeds: toggle(r.feeds, f.url) })} />
                      {feedDisplayTitle(f)}
                      {f.tag ? <span className="mf-note"> · {f.tag}</span> : null}
                    </label>
                  ))}
                </div>
              ) : null}
            </div>
            <div className="pl-field">
              <div className="pl-k">Type</div>
              <div className="pl-opts">
                {ITEM_KINDS.map((k) => (
                  <Chip key={k} on={r.kinds.includes(k)} onClick={() => setRules({ kinds: toggle(r.kinds, k) })}>
                    {k === 'article' ? 'Article (read aloud)' : ITEM_KIND_LABELS[k]}
                  </Chip>
                ))}
              </div>
            </div>
            <div className="pl-field">
              <div className="pl-k">Status</div>
              <div className="pl-opts">
                {PLAY_STATUSES.map((s) => (
                  <Chip key={s} on={r.statuses.includes(s)} onClick={() => setRules({ statuses: toggle(r.statuses, s) })}>
                    {PLAY_STATUS_LABELS[s]}
                  </Chip>
                ))}
                <Chip on={r.savedOnly} onClick={() => setRules({ savedOnly: !r.savedOnly })}>
                  Saved only
                </Chip>
                <Chip on={r.downloadedOnly} onClick={() => setRules({ downloadedOnly: !r.downloadedOnly })}>
                  Downloaded only
                </Chip>
              </div>
            </div>
            <div className="pl-field">
              <div className="pl-k">Published</div>
              <div className="pl-opts">
                {WITHIN_CHOICES.map((d) => (
                  <Chip key={String(d)} on={r.withinDays === d} onClick={() => setRules({ withinDays: d })}>
                    {d == null ? 'Any time' : withinLabel(d).replace('last ', 'Last ')}
                  </Chip>
                ))}
              </div>
            </div>
            <div className="pl-field">
              <div className="pl-k">Length</div>
              <div className="pl-opts">
                {lengthChoices.map(([label, min, max]) => (
                  <Chip key={label} on={r.minMinutes === min && r.maxMinutes === max} onClick={() => setRules({ minMinutes: min, maxMinutes: max })}>
                    {label}
                  </Chip>
                ))}
              </div>
              <div className="pl-len">
                <label>
                  Over
                  <input type="number" min={0} value={r.minMinutes ?? ''} placeholder="–" onChange={(e) => setRules({ minMinutes: e.target.value === '' ? null : Math.max(0, Number(e.target.value)) })} />
                  min
                </label>
                <label>
                  Under
                  <input type="number" min={1} value={r.maxMinutes ?? ''} placeholder="–" onChange={(e) => setRules({ maxMinutes: e.target.value === '' ? null : Math.max(1, Number(e.target.value)) })} />
                  min
                </label>
              </div>
              <p className="mf-note">Articles count as their read-aloud time at your speed. Episodes of unknown length don’t match a length rule.</p>
            </div>
          </>
        ) : null}
        <div className="pl-dialog-actions">
          <button className="mf-btn" onClick={p.onCancel}>
            Cancel
          </button>
          <button className="mf-btn primary" onClick={() => p.onSave({ ...pl, name: pl.name.trim() || (pl.smart ? 'Smart playlist' : 'My playlist'), rules: pl.smart ? pl.rules : EMPTY_RULES }, p.isNew)}>
            Save{pl.smart ? ` · ${matching} ${matching === 1 ? 'item' : 'items'}` : ''}
          </button>
        </div>
      </div>
    </div>
  )
}

function usePlaylistAudio(): PlaylistAudioState {
  const [s, setS] = useState<PlaylistAudioState>(playlistAudio.state)
  useEffect(() => playlistAudio.subscribe(setS), [])
  return s
}

const formatMB = (n: number) => (n >= 1024 * 1024 ? `${Math.round(n / 1024 / 1024)} MB` : `${Math.max(1, Math.round(n / 1024))} KB`)

/** "Getting 20 articles ready · 7 of 20" with Cancel, then "20 articles ready offline · 58 MB" with Remove. */
function OfflineArticlesCard({ s }: { s: PlaylistAudioState }) {
  const total = s.ids.length
  const ready = s.ids.filter((id) => s.ready.has(id)).length
  const done = s.ids.filter((id) => s.ready.has(id) || s.failed.has(id)).length
  const within = s.preparingId ? s.progress : 0
  return (
    <div className="pl-offline-card">
      <div className="pl-offline-row">
        {s.running ? <span className="book-audio-spin" aria-hidden /> : null}
        <b>{s.running ? `Getting ${total} articles ready · ${done} of ${total}` : ready ? `${ready} article${ready === 1 ? '' : 's'} ready offline · ${formatMB(s.bytes)}` : 'Nothing could be made ready (no text to read)'}</b>
        <span className="mf-sp" />
        {s.running ? (
          <button type="button" className="mf-btn" onClick={() => playlistAudio.cancel()}>
            Cancel
          </button>
        ) : ready ? (
          <button type="button" className="mf-btn" onClick={() => void playlistAudio.removeAll()}>
            Remove
          </button>
        ) : null}
      </div>
      {s.running ? (
        <div className="pl-offline-bar" role="progressbar" aria-valuemin={0} aria-valuemax={total} aria-valuenow={done}>
          <div style={{ width: `${Math.round(((done + within) / Math.max(1, total)) * 100)}%` }} />
        </div>
      ) : null}
      <small>{s.running ? `${s.bytes ? `${formatMB(s.bytes)} so far · ` : ''}Prepared while Feeder is open` : 'Played articles delete themselves · unplayed ones after 7 days'}</small>
    </div>
  )
}
