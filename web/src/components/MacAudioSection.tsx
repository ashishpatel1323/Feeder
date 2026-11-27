/**
 * Settings › Prepared on this Mac, Mac app only (design B): the phone's next ebook chapters spoken
 * here with the natural voice and put in Dropbox (lib/macBookAudio.ts). Nothing to press: it works
 * whenever the app is open and the switch is on.
 */
import { useEffect, useState } from 'react'
import { macBookAudio, MAC_STALE_DAYS, WHOLE_BOOK, type MacRunState } from '../lib/macBookAudio'
import { updateSettings, useSettings } from '../lib/settings'

function formatBytes(n: number): string {
  if (n >= 1024 * 1024) return `${Math.round(n / 1024 / 1024)} MB`
  return `${Math.max(1, Math.round(n / 1024))} KB`
}

function ago(ts: number): string {
  const days = Math.floor((Date.now() - ts) / 86400_000)
  return days <= 0 ? 'today' : days === 1 ? 'yesterday' : `${days} days ago`
}

export function MacAudioSection() {
  const s = useSettings()
  const [mac, setMac] = useState<MacRunState>(macBookAudio.state)
  useEffect(() => macBookAudio.subscribe(setMac), [])
  if (!mac.available) return null
  const status = mac.status
  const voice = status?.voice ?? 'af_heart'
  const working = status?.working
  return (
    <section className="settings-section mac-audio">
      <h3>Prepared on this Mac</h3>
      <p className="muted small">
        While Feeder is open, this Mac speaks your next ebook chapters with the natural voice and puts them in Dropbox. Your phone downloads them on
        Wi-Fi.
      </p>
      <label className="check">
        <input
          type="checkbox"
          checked={s.macBookAudio}
          onChange={(e) => {
            void updateSettings({ macBookAudio: e.target.checked })
            macBookAudio.setEnabled(e.target.checked)
          }}
        />
        Make audio for my phone
      </label>
      <p className="muted small">
        Uses the graphics card, about 8x faster than real time. Chapters: the one being read and{' '}
        {s.bookAudioAhead >= WHOLE_BOOK ? 'the rest of the book' : `${s.bookAudioAhead} after it`} (your phone's Prepare ahead).
      </p>
      {mac.error ? <p className="error small">{mac.error}</p> : null}
      {mac.enabled && status ? (
        <>
          <div className="mac-audio-sec">Working on now</div>
          {working ? (
            <div className="mac-audio-card">
              <b>
                {working.title} · chapter {working.section + 1}
              </b>
              <small>{Math.round(working.progress * 100)}%</small>
              <div className="mac-audio-prog">
                <i style={{ width: `${Math.round(working.progress * 100)}%` }} />
              </div>
            </div>
          ) : (
            <div className="mac-audio-card">
              <small>Nothing to make right now: every chapter is ready.</small>
            </div>
          )}
          <div className="mac-audio-sec">Books kept ready</div>
          {status.books.length ? (
            status.books.map((b) => {
              const old = Date.now() - b.usedAt > 2 * 86400_000
              return (
                <div className="mac-audio-card" key={b.id}>
                  <b>{b.title || 'Untitled book'}</b>
                  <small>
                    {old ? `Opened ${ago(b.usedAt)}` : `Chapter ${b.current + 1} now`} ·{' '}
                    {b.ready.length >= b.planned.length ? `all ${b.planned.length} ready` : `${b.ready.length} of ${b.planned.length} chapters ready`}
                    {b.bytes ? ` · ${formatBytes(b.bytes)} in Dropbox` : ''}
                    {old ? ` · removed in ${Math.max(1, MAC_STALE_DAYS - Math.floor((Date.now() - b.usedAt) / 86400_000))} days if not opened` : ''}
                  </small>
                </div>
              )
            })
          ) : (
            <div className="mac-audio-card">
              <small>Ebooks in Dropbox you open on any device show here for a week.</small>
            </div>
          )}
          <div className="mac-audio-sec">Voice</div>
          <div className="mac-audio-card mac-audio-voice">
            <span>
              <b>Kokoro · {voice}</b>
              <small>Same voice as read aloud on this Mac</small>
            </span>
            <button type="button" onClick={() => document.getElementById('read-aloud-voice')?.focus()}>
              Change
            </button>
          </div>
        </>
      ) : null}
    </section>
  )
}
