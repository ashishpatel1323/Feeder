import { useEffect, useRef, useState } from 'react'
import { dropboxAppKey, getDropbox, onDropboxChange, setDropboxAppKey, signOutDropbox, startDropboxSignIn } from '../lib/dropbox'
import { Modal } from './Dialogs'
import { Icon, IconButton } from './Icon'
import { confirmDelete } from '../lib/confirmDelete'

export function useDropbox(): { signedIn: boolean; name: string; email: string; ready: boolean } {
  const [s, setS] = useState({ signedIn: false, name: '', email: '', ready: false })
  useEffect(() => {
    const load = () =>
      void getDropbox()
        .then((d) => setS({ signedIn: !!d, name: d?.name || d?.email || '', email: d?.email || '', ready: true }))
        // Can't read the sign-in: offer to sign in rather than showing nothing
        .catch(() => setS({ signedIn: false, name: '', email: '', ready: true }))
    load()
    return onDropboxChange(load)
  }, [])
  return s
}

/**
 * Dropbox in the header of the Ebooks, ZIM and Audiobooks pages: a cloud icon button. Signed in, it
 * opens a small menu with the account, the folder (Apps › Feeder › [folder]) and Sign out. Signed
 * out (crossed-out cloud), it signs in. Signing in once is enough: the other devices follow through sync.
 */
export function DropboxButton({ folder, toast }: { folder: string; toast: (m: string) => void }) {
  const dbx = useDropbox()
  const [pos, setPos] = useState<{ top: number; right: number } | null>(null)
  const [askKey, setAskKey] = useState(false)
  const [confirmOut, setConfirmOut] = useState(false)
  const [key, setKey] = useState('')
  const wrap = useRef<HTMLSpanElement>(null)

  useEffect(() => {
    if (!pos) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.stopPropagation()
        setPos(null)
      }
    }
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  }, [pos])

  const signIn = async () => {
    const appKey = await dropboxAppKey()
    if (!appKey) {
      setAskKey(true)
      return
    }
    await startDropboxSignIn(appKey)
  }

  const open = () => {
    const r = wrap.current?.getBoundingClientRect()
    if (r) setPos({ top: r.bottom + 4, right: Math.max(8, window.innerWidth - r.right) })
  }

  return (
    <span ref={wrap} className="more-menu">
      {dbx.signedIn ? (
        <IconButton icon="cloud" label="Dropbox account" title={`Dropbox: ${dbx.name}`} onClick={() => (pos ? setPos(null) : open())} active={!!pos} />
      ) : (
        <IconButton icon="cloudOff" label="Sign in to Dropbox" onClick={() => void signIn()} />
      )}
      {pos ? (
        <div className="ctx-backdrop" onPointerDown={(e) => e.target === e.currentTarget && setPos(null)} onWheel={() => setPos(null)}>
          <div className="ctx-menu dbx-menu" role="menu" aria-label="Dropbox account" style={{ top: pos.top, right: pos.right }}>
            <div className="dbx-menu-head">
              <span className="dbx-menu-name">{dbx.name || 'Dropbox'}</span>
              {dbx.email && dbx.email !== dbx.name ? <span className="dbx-menu-meta">{dbx.email}</span> : null}
              <span className="dbx-menu-meta">Apps › Feeder › {folder}</span>
            </div>
            <div className="ctx-sep" role="separator" />
            <button
              type="button"
              role="menuitem"
              className="ctx-item"
              autoFocus
              onClick={() => {
                setPos(null)
                setConfirmOut(true)
              }}
            >
              <Icon name="logout" size={20} />
              <span className="ctx-label">Sign out</span>
            </button>
          </div>
        </div>
      ) : null}
      {confirmOut ? (
        <Modal title="Sign out of Dropbox?" onClose={() => setConfirmOut(false)}>
          <div className="form">
            <p>This signs out on every device. The files stay in Dropbox.</p>
            <div className="form-actions">
              <span className="spacer" />
              <button type="button" onClick={() => setConfirmOut(false)} autoFocus>
                Cancel
              </button>
              <button
                type="button"
                className="danger"
                onClick={async () => {
                  setConfirmOut(false)
                  await signOutDropbox()
                  toast('Signed out of Dropbox')
                }}
              >
                Sign out
              </button>
            </div>
          </div>
        </Modal>
      ) : null}
      {askKey ? (
        <Modal title="Sign in to Dropbox" onClose={() => setAskKey(false)}>
          <form
            className="form"
            onSubmit={async (e) => {
              e.preventDefault()
              if (!key.trim()) return
              await setDropboxAppKey(key)
              await startDropboxSignIn(key.trim())
            }}
          >
            <label>
              Dropbox app key
              <input value={key} onChange={(e) => setKey(e.target.value)} autoFocus />
            </label>
            <div className="form-actions">
              <span className="spacer" />
              <button type="button" onClick={() => setAskKey(false)}>
                Cancel
              </button>
              <button type="submit" className="primary">
                Sign in
              </button>
            </div>
          </form>
        </Modal>
      ) : null}
    </span>
  )
}

/** The shared delete confirmation for a file in Dropbox. [keepsCopy]: this device keeps its copy. */
export function confirmDropboxDelete(title: string, keepsCopy: boolean): Promise<boolean> {
  return confirmDelete({ title: 'Delete from Dropbox?', itemName: title, scope: 'dropbox', keepsLocalCopy: keepsCopy })
}

/** Uploading needs Dropbox: signed out, say how to sign in instead. */
export function needsDropbox(signedIn: boolean, toast: (m: string) => void, run: () => void) {
  if (signedIn) run()
  else toast('Sign in to Dropbox first: the crossed-out cloud at the top of the page')
}
