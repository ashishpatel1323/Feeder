/**
 * Settings › AI agents (MCP), Mac app only: one switch that runs Feeder's MCP server, so Claude and
 * other AI agents can search and read this library. The section shows only when the app's local
 * server answers /api/mcp/status (the browser version has no such server).
 */
import { useLiveQuery } from 'dexie-react-hooks'
import { useCallback, useEffect, useState } from 'react'
import { getSyncState } from '../lib/sync2'

interface McpStatus {
  available: boolean
  enabled: boolean
  running: boolean
  starting: boolean
  error: string | null
  items: number | null
  lastSync: string | null
  url: string | null
  claudeDesktop: 'added' | 'not-added' | 'not-installed'
  claudeCodeCommand: string | null
}

const headers = { 'x-feeder-app': '1', 'content-type': 'application/json' }

async function call(path: string, body?: unknown): Promise<McpStatus> {
  const res = await fetch(`/api/mcp/${path}`, body === undefined ? { headers } : { method: 'POST', headers, body: JSON.stringify(body) })
  const data = (await res.json()) as McpStatus & { error?: string }
  if (!res.ok) throw new Error(data.error ?? `HTTP ${res.status}`)
  return data
}

export function McpSection({ toast }: { toast: (m: string) => void }) {
  const sync = useLiveQuery(() => getSyncState(), [])
  const [status, setStatus] = useState<McpStatus | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  const refresh = useCallback(() => {
    call('status')
      .then(setStatus)
      .catch(() => setStatus(null))
  }, [])

  useEffect(refresh, [refresh])
  // While it's starting (first index can take a minute), check again every few seconds
  useEffect(() => {
    if (!status?.enabled || status.running) return
    const t = setInterval(refresh, 3000)
    return () => clearInterval(t)
  }, [status?.enabled, status?.running, refresh])

  if (!status?.available) return null

  const toggle = async (on: boolean) => {
    setBusy(true)
    setError('')
    try {
      if (on) {
        if (!sync) throw new Error('Turn on sync first (above): the MCP server reads your library through it.')
        setStatus(await call('enable', { syncCode: sync.syncCode, secretKey: sync.secretKey }))
        toast('AI agents can now read your Feeder library')
      } else {
        setStatus(await call('disable', {}))
        toast('MCP server turned off')
      }
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  const copy = (text: string, what: string) => {
    void navigator.clipboard.writeText(text)
    toast(`${what} copied`)
  }

  const state = !status.enabled ? 'Off' : status.running ? 'Running' : status.error ? 'Stopped' : 'Starting…'

  return (
    <section className="settings-section">
      <h3>AI agents (MCP)</h3>
      <label className="check">
        <input type="checkbox" checked={status.enabled} disabled={busy} onChange={(e) => void toggle(e.target.checked)} />
        Let AI agents read my Feeder library
      </label>
      <p className="muted small">
        Runs Feeder's MCP server on this Mac. Claude and other agents can then search and read your articles, playlists, highlights, podcast and
        YouTube transcripts, ebooks and ZIM files. Read-only. Your library is decrypted here, with your sync key kept in the macOS Keychain; turning
        this off removes the key and the Claude Desktop entry.
      </p>
      {error ? <p className="error">{error}</p> : null}
      {status.enabled ? (
        <>
          <p>
            <strong>{state}</strong>
            {status.running && status.items != null ? ` · ${status.items.toLocaleString()} items indexed` : ''}
            {status.running && status.lastSync && status.lastSync !== 'never' ? ` · synced ${new Date(status.lastSync).toLocaleTimeString()}` : ''}
          </p>
          {status.error ? <p className="error small">{status.error}</p> : null}
          <p className="small">
            {status.claudeDesktop === 'added'
              ? 'Claude Desktop: added. Quit and reopen Claude Desktop to see Feeder under its tools.'
              : status.claudeDesktop === 'not-installed'
                ? 'Claude Desktop isn’t installed on this Mac.'
                : 'Claude Desktop: not added (its config couldn’t be written).'}
          </p>
          <div className="form-actions wrap">
            {status.claudeCodeCommand ? (
              <button onClick={() => copy(status.claudeCodeCommand!, 'Claude Code command')}>Copy Claude Code command</button>
            ) : null}
            {status.url ? <button onClick={() => copy(status.url!, 'MCP address')}>Copy MCP address</button> : null}
            <button onClick={refresh}>Refresh status</button>
          </div>
          <p className="muted small">The MCP address works while Feeder is open. Keep it private: the token in it gives read access to your library.</p>
        </>
      ) : null}
    </section>
  )
}
