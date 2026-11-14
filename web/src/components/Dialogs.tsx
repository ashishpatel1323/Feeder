import { useEffect, useState, type ReactNode } from 'react'
import { useLiveQuery } from 'dexie-react-hooks'
import { db, type Feed } from '../lib/db'
import { addFeed, deleteFeed, exportOpml, importOpml, refreshFeeds } from '../lib/refresh'
import {
  addDeviceLink,
  createChain,
  getDevices,
  getSyncState,
  joinChain,
  leaveChain,
  removeDevice,
  syncNow,
  type Device,
} from '../lib/sync'
import { parseJoinLink } from '../lib/crypto'
import { getAccessToken, health, setAccessToken } from '../lib/api'
import { updateSettings, type Settings } from '../lib/settings'
import { isKokoroVoice, kokoro, KOKORO_PREFIX, KOKORO_VOICES, webGpuAvailable, type KokoroStatus } from '../lib/kokoro'
import { readAloud } from '../lib/readaloud'
import { IconButton } from './Icon'

function Modal({ title, onClose, children, wide }: { title: string; onClose: () => void; children: ReactNode; wide?: boolean }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])
  return (
    <div className="modal-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className={`modal${wide ? ' wide' : ''}`} role="dialog" aria-modal="true" aria-label={title}>
        <header className="modal-head">
          <h2>{title}</h2>
          <IconButton icon="close" label="Close" onClick={onClose} />
        </header>
        <div className="modal-body">{children}</div>
      </div>
    </div>
  )
}

const errText = (e: unknown) => (e instanceof Error ? e.message : String(e))

// -----------------------------------------------------------------------------------------------

export function AddFeedDialog({ onClose, toast }: { onClose: () => void; toast: (m: string) => void }) {
  const [url, setUrl] = useState('')
  const [tag, setTag] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const tags = useLiveQuery(async () => [...new Set((await db.feeds.toArray()).map((f) => f.tag).filter(Boolean))].sort(), []) ?? []

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError('')
    try {
      await addFeed(url, tag.trim())
      syncNow().catch(() => undefined)
      toast('Feed added')
      onClose()
    } catch (err) {
      setError(errText(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal title="Add feed" onClose={onClose}>
      <form onSubmit={submit} className="form">
        <label>
          Feed or website address
          <input autoFocus required value={url} onChange={(e) => setUrl(e.target.value)} placeholder="https://example.com/feed.xml" />
        </label>
        <label>
          Tag (optional)
          <input value={tag} onChange={(e) => setTag(e.target.value)} list="tag-list" />
          <datalist id="tag-list">
            {tags.map((t) => (
              <option key={t} value={t} />
            ))}
          </datalist>
        </label>
        {error ? <p className="error">{error}</p> : null}
        <div className="form-actions">
          <button type="button" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" className="primary" disabled={busy}>
            {busy ? 'Adding…' : 'Add'}
          </button>
        </div>
      </form>
    </Modal>
  )
}

// -----------------------------------------------------------------------------------------------

export function EditFeedDialog({ url, onClose, toast }: { url: string; onClose: () => void; toast: (m: string) => void }) {
  const feed = useLiveQuery(() => db.feeds.get(url), [url])
  const [draft, setDraft] = useState<Feed | null>(null)
  useEffect(() => {
    if (feed && !draft) setDraft(feed)
  }, [feed, draft])
  if (!draft) return null

  const set = (patch: Partial<Feed>) => setDraft({ ...draft, ...patch })
  const save = async (e: React.FormEvent) => {
    e.preventDefault()
    await db.feeds.put({ ...draft, whenModified: Date.now() })
    syncNow().catch(() => undefined)
    if (draft.blockRules !== feed?.blockRules || draft.allowRules !== feed?.allowRules || draft.alternateId !== feed?.alternateId) {
      refreshFeeds([url]).catch(() => undefined)
    }
    toast('Feed saved')
    onClose()
  }
  const remove = async () => {
    if (!confirm(`Delete "${draft.customTitle || draft.title || draft.url}"? This also removes it from your other devices.`)) return
    await deleteFeed(url)
    syncNow().catch(() => undefined)
    toast('Feed deleted')
    onClose()
  }

  return (
    <Modal title="Edit feed" onClose={onClose}>
      <form onSubmit={save} className="form">
        <p className="muted small">{draft.url}</p>
        {draft.lastError ? <p className="error small">Last refresh failed: {draft.lastError}</p> : null}
        <label>
          Title
          <input value={draft.customTitle} placeholder={draft.title} onChange={(e) => set({ customTitle: e.target.value })} />
        </label>
        <label>
          Tag
          <input value={draft.tag} onChange={(e) => set({ tag: e.target.value })} />
        </label>
        <label className="check">
          <input type="checkbox" checked={draft.fullTextByDefault} onChange={(e) => set({ fullTextByDefault: e.target.checked })} />
          Show full text by default
        </label>
        <label className="check">
          <input type="checkbox" checked={draft.fetchOgImages} onChange={(e) => set({ fetchOgImages: e.target.checked })} />
          Enhance thumbnails from article metadata
        </label>
        <label className="check">
          <input type="checkbox" checked={draft.alternateId} onChange={(e) => set({ alternateId: e.target.checked })} />
          Use alternative id (for feeds that reuse ids)
        </label>
        <label>
          Block rules (one Field=regex per line; Title, URL, Author, Tag, Content)
          <textarea rows={3} value={draft.blockRules} onChange={(e) => set({ blockRules: e.target.value })} />
        </label>
        <label>
          Allow rules
          <textarea rows={2} value={draft.allowRules} onChange={(e) => set({ allowRules: e.target.value })} />
        </label>
        <div className="form-actions">
          <button type="button" className="danger" onClick={remove}>
            Delete feed
          </button>
          <span className="spacer" />
          <button type="button" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" className="primary">
            Save
          </button>
        </div>
      </form>
    </Modal>
  )
}

// -----------------------------------------------------------------------------------------------

function SyncSection({ toast }: { toast: (m: string) => void }) {
  const state = useLiveQuery(() => getSyncState(), [])
  const [link, setLink] = useState('')
  const [server, setServer] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [devices, setDevices] = useState<Device[] | null>(null)
  const [showLink, setShowLink] = useState(false)

  useEffect(() => {
    if (state) getDevices().then(setDevices).catch(() => setDevices(null))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [state?.syncCode])

  const run = async (fn: () => Promise<unknown>) => {
    setBusy(true)
    setError('')
    try {
      await fn()
    } catch (e) {
      setError(errText(e))
    } finally {
      setBusy(false)
    }
  }

  const join = () =>
    run(async () => {
      const parsed = parseJoinLink(link)
      if (!parsed) throw new Error('Paste the whole "add device" link from Feeder on your phone (it has sync_code= and key=).')
      await joinChain(parsed.syncCode, parsed.key, server.trim())
      await syncNow()
      await refreshFeeds()
      await syncNow()
      setLink('')
      toast('Joined your sync chain')
    })

  if (state === undefined) return null

  if (!state) {
    return (
      <section className="settings-section">
        <h3>Sync with Feeder on your phone</h3>
        <p className="muted">
          In Feeder on your phone, open Device sync and tap Add new device. Share or copy the link it shows and paste it here. This browser joins the
          same chain, so feeds and read status stay in step. (No chain on the phone yet? Start one there first, or start one here and open the
          add-device link on the phone.)
        </p>
        <label>
          Add-device link
          <textarea rows={3} value={link} onChange={(e) => setLink(e.target.value)} placeholder="https://feederapp.nononsenseapps.com/sync/join?sync_code=…&key=…" />
        </label>
        <details>
          <summary>Self-hosted sync server</summary>
          <label>
            Server address (leave empty for Feeder's default)
            <input value={server} onChange={(e) => setServer(e.target.value)} placeholder="https://feeder-sync.nononsenseapps.com" />
          </label>
        </details>
        {error ? <p className="error">{error}</p> : null}
        <div className="form-actions">
          <button onClick={() => run(async () => (await createChain(server.trim()), toast('New sync chain created')))} disabled={busy}>
            Start a new chain here
          </button>
          <button className="primary" onClick={join} disabled={busy || !link.trim()}>
            {busy ? 'Joining…' : 'Join'}
          </button>
        </div>
      </section>
    )
  }

  return (
    <section className="settings-section">
      <h3>Sync</h3>
      <p>
        This browser is <strong>{state.deviceName}</strong> in your sync chain.
        {state.lastSyncAt ? ` Last synced ${new Date(state.lastSyncAt).toLocaleString()}.` : ''}
      </p>
      {state.lastError ? <p className="error small">Last sync failed: {state.lastError}</p> : null}
      {error ? <p className="error">{error}</p> : null}
      <h4>Devices</h4>
      {devices === null ? (
        <p className="muted small">Couldn't load the device list.</p>
      ) : (
        <ul className="device-list">
          {devices.map((d) => (
            <li key={d.deviceId}>
              <span>
                {d.deviceName}
                {d.deviceId === state.deviceId ? ' (this browser)' : ''}
              </span>
              {d.deviceId !== state.deviceId ? (
                <button
                  className="link-btn"
                  onClick={() =>
                    confirm(`Remove ${d.deviceName} from the chain?`) &&
                    run(async () => (await removeDevice(d.deviceId), setDevices(await getDevices())))
                  }
                >
                  Remove
                </button>
              ) : null}
            </li>
          ))}
        </ul>
      )}
      <div className="form-actions wrap">
        <button onClick={() => run(async () => (await syncNow(), toast('Synced')))} disabled={busy}>
          Sync now
        </button>
        <button onClick={() => setShowLink(!showLink)}>{showLink ? 'Hide add-device link' : 'Add another device'}</button>
        <button
          className="danger"
          onClick={() => confirm('Leave the sync chain? Your feeds stay in this browser.') && run(async () => (await leaveChain(), toast('Left the chain')))}
          disabled={busy}
        >
          Leave chain
        </button>
      </div>
      {showLink ? (
        <div className="join-link">
          <p className="muted small">Open this on the other device (or paste it into Feeder's join screen). It contains your sync key, so keep it private.</p>
          <code>{addDeviceLink(state)}</code>
          <button onClick={() => navigator.clipboard.writeText(addDeviceLink(state)).then(() => toast('Link copied'))}>Copy</button>
        </div>
      ) : null}
    </section>
  )
}

/** Browser voices that are neural/network voices and sound natural. */
const NATURAL_VOICE = /natural|neural|online|premium|enhanced|google|siri/i

export function SettingsDialog({ settings, onClose, toast }: { settings: Settings; onClose: () => void; toast: (m: string) => void }) {
  const [token, setToken] = useState(getAccessToken())
  const [tokenRequired, setTokenRequired] = useState(false)
  const [voices, setVoices] = useState<SpeechSynthesisVoice[]>([])
  const [voiceModel, setVoiceModel] = useState<KokoroStatus>(kokoro.status)
  useEffect(() => kokoro.subscribe(setVoiceModel), [])
  const s = settings
  const natural = (v: SpeechSynthesisVoice) => NATURAL_VOICE.test(v.name)
  const sortedVoices = [...voices].sort((a, b) => Number(natural(b)) - Number(natural(a)) || a.name.localeCompare(b.name))

  useEffect(() => {
    health()
      .then((h) => setTokenRequired(h.tokenRequired))
      .catch(() => undefined)
    if (!('speechSynthesis' in window)) return
    const load = () => setVoices(speechSynthesis.getVoices())
    load()
    speechSynthesis.addEventListener('voiceschanged', load)
    return () => speechSynthesis.removeEventListener('voiceschanged', load)
  }, [])

  const onImport = async (file: File) => {
    const n = await importOpml(await file.text())
    toast(`Imported ${n} feeds`)
    refreshFeeds().then(() => syncNow().catch(() => undefined))
  }
  const onExport = async () => {
    const blob = new Blob([await exportOpml()], { type: 'text/x-opml' })
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = 'feeder.opml'
    a.click()
    URL.revokeObjectURL(a.href)
  }

  return (
    <Modal title="Settings" onClose={onClose} wide>
      <SyncSection toast={toast} />

      <section className="settings-section">
        <h3>Reading</h3>
        <label className="check">
          <input type="checkbox" checked={s.markReadOnOpen} onChange={(e) => updateSettings({ markReadOnOpen: e.target.checked })} />
          Mark articles read when opened
        </label>
        <label className="check">
          <input type="checkbox" checked={s.showThumbnails} onChange={(e) => updateSettings({ showThumbnails: e.target.checked })} />
          Show thumbnails in the list
        </label>
        <label className="check">
          <input type="checkbox" checked={s.showHnScore} onChange={(e) => updateSettings({ showHnScore: e.target.checked })} />
          Show Hacker News score (sends article links to hn.algolia.com)
        </label>
        <label className="row-label">
          Snippet lines
          <select value={s.snippetLines} onChange={(e) => updateSettings({ snippetLines: Number(e.target.value) })}>
            {[0, 1, 2, 3, 4, 5].map((n) => (
              <option key={n} value={n}>
                {n === 0 ? 'Hide' : n}
              </option>
            ))}
          </select>
        </label>
        <label className="row-label">
          Text size
          <input type="range" min={14} max={26} value={s.textSize} onChange={(e) => updateSettings({ textSize: Number(e.target.value) })} />
          <span>{s.textSize}px</span>
        </label>
        <label className="row-label">
          Theme
          <select value={s.theme} onChange={(e) => updateSettings({ theme: e.target.value as Settings['theme'] })}>
            <option value="system">System</option>
            <option value="light">Light</option>
            <option value="dark">Dark</option>
          </select>
        </label>
        <label className="row-label">
          Refresh every
          <select value={s.refreshMinutes} onChange={(e) => updateSettings({ refreshMinutes: Number(e.target.value) })}>
            {[15, 30, 60, 120, 0].map((n) => (
              <option key={n} value={n}>
                {n === 0 ? 'Only when I press refresh' : `${n} minutes (while open)`}
              </option>
            ))}
          </select>
        </label>
      </section>

      <section className="settings-section">
        <h3>Read aloud</h3>
        {!readAloud.supported ? <p className="error">This browser has no speech synthesis. Pick a natural voice below.</p> : null}
        <label className="row-label">
          Voice
          <select
            value={s.readAloudVoice}
            onChange={(e) => {
              updateSettings({ readAloudVoice: e.target.value })
              // Start the one-time download as soon as a natural voice is picked
              if (isKokoroVoice(e.target.value)) kokoro.load(s.readAloudKokoroGpu).catch(() => undefined)
            }}
          >
            <option value="">Default for the article's language</option>
            <optgroup label="Natural, runs on this device (English)">
              {KOKORO_VOICES.map((v) => (
                <option key={v.id} value={KOKORO_PREFIX + v.id}>
                  {v.label}
                </option>
              ))}
            </optgroup>
            <optgroup label="Browser voices">
              {sortedVoices.map((v) => (
                <option key={v.voiceURI} value={v.voiceURI}>
                  {natural(v) ? '★ ' : ''}
                  {v.name} ({v.lang}){v.localService ? '' : ' · online'}
                </option>
              ))}
            </optgroup>
          </select>
        </label>
        {isKokoroVoice(s.readAloudVoice) ? (
          <div className="voice-model">
            <p className="muted small">
              {voiceModel.state === 'ready'
                ? `Natural voice ready (${voiceModel.device === 'webgpu' ? 'GPU' : 'CPU'}).`
                : voiceModel.state === 'loading'
                  ? `Downloading the voice model… ${Math.round(voiceModel.progress * 100)}% (once; the browser keeps it)`
                  : voiceModel.state === 'error'
                    ? `The voice model failed to load: ${voiceModel.error}`
                    : `The voice model downloads once (about ${s.readAloudKokoroGpu && webGpuAvailable() ? '310' : '90'} MB) the first time you use it.`}
            </p>
            {voiceModel.state === 'idle' || voiceModel.state === 'error' ? (
              <button onClick={() => kokoro.load(s.readAloudKokoroGpu).catch(() => undefined)}>Download now</button>
            ) : null}
            {webGpuAvailable() ? (
              <label className="check">
                <input type="checkbox" checked={s.readAloudKokoroGpu} onChange={(e) => updateSettings({ readAloudKokoroGpu: e.target.checked })} />
                Use the graphics card (faster, about 310 MB download)
              </label>
            ) : null}
          </div>
        ) : null}
        <label className="row-label">
          Speed
          <input type="range" min={0.6} max={2.5} step={0.1} value={s.readAloudRate} onChange={(e) => updateSettings({ readAloudRate: Number(e.target.value) })} />
          <span>{s.readAloudRate.toFixed(1)}×</span>
        </label>
        <label className="check">
          <input type="checkbox" checked={s.readAloudAutoContinue} onChange={(e) => updateSettings({ readAloudAutoContinue: e.target.checked })} />
          Continue with the next article when one ends
        </label>
        <p className="muted small">★ marks the more natural browser voices (Edge's "Natural" voices are the best). The natural voices above sound most human but need a reasonably fast computer; their word highlight is estimated.</p>
      </section>

      <section className="settings-section">
        <h3>Summaries</h3>
        <label className="row-label">
          Provider
          <select value={s.summaryProvider} onChange={(e) => updateSettings({ summaryProvider: e.target.value as Settings['summaryProvider'] })}>
            <option value="none">Off</option>
            <option value="openai">OpenAI-compatible API (Ollama, OpenAI, LM Studio…)</option>
          </select>
        </label>
        {s.summaryProvider === 'openai' ? (
          <>
            <label>
              Base URL
              <input value={s.summaryBaseUrl} onChange={(e) => updateSettings({ summaryBaseUrl: e.target.value })} />
            </label>
            <label>
              Model
              <input value={s.summaryModel} onChange={(e) => updateSettings({ summaryModel: e.target.value })} />
            </label>
            <label>
              API key (not needed for Ollama)
              <input type="password" value={s.summaryApiKey} onChange={(e) => updateSettings({ summaryApiKey: e.target.value })} />
            </label>
            <p className="muted small">
              Ollama: use http://localhost:11434/v1 when Ollama runs on this computer, and start it with OLLAMA_ORIGINS set to this site's address.
              An Ollama on another machine needs an https address (for example through Cloudflare Tunnel), because browsers block http calls from https pages.
              The key is stored only in this browser.
            </p>
          </>
        ) : null}
      </section>

      <section className="settings-section">
        <h3>Feeds</h3>
        <div className="form-actions wrap">
          <label className="file-btn">
            Import OPML
            <input type="file" accept=".opml,.xml,text/xml" onChange={(e) => e.target.files?.[0] && onImport(e.target.files[0])} />
          </label>
          <button onClick={onExport}>Export OPML</button>
        </div>
      </section>

      <section className="settings-section">
        <h3>Backend access</h3>
        <p className="muted small">
          {tokenRequired
            ? 'This deployment requires an access token for fetching feeds and syncing.'
            : 'This deployment has no access token set, so anyone who finds its address can use its feed fetcher. Set ACCESS_TOKEN in Cloudflare to lock it down.'}
        </p>
        <label>
          Access token
          <input type="password" value={token} onChange={(e) => setToken(e.target.value)} onBlur={() => setAccessToken(token.trim())} />
        </label>
      </section>
    </Modal>
  )
}
