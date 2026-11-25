import { useEffect, useState } from 'react'
import { dropboxAppKey, getDropbox, onDropboxChange, setDropboxAppKey, signOutDropbox, startDropboxSignIn } from '../lib/dropbox'
import { Icon } from './Icon'

export function useDropbox(): { signedIn: boolean; name: string; ready: boolean } {
  const [s, setS] = useState({ signedIn: false, name: '', ready: false })
  useEffect(() => {
    const load = () => void getDropbox().then((d) => setS({ signedIn: !!d, name: d?.name || d?.email || '', ready: true }))
    load()
    return onDropboxChange(load)
  }, [])
  return s
}

/**
 * The Dropbox line on the Ebooks and ZIM pages: who is signed in, or a button to sign in. Signing
 * in once is enough: the sign-in reaches the other devices through sync.
 */
export function DropboxPanel({ what, toast }: { what: string; toast: (m: string) => void }) {
  const dbx = useDropbox()
  const [askKey, setAskKey] = useState(false)
  const [key, setKey] = useState('')

  const signIn = async () => {
    const appKey = await dropboxAppKey()
    if (!appKey) {
      setAskKey(true)
      return
    }
    await startDropboxSignIn(appKey)
  }

  if (!dbx.ready) return null
  if (dbx.signedIn)
    return (
      <div className="dbx-panel">
        <Icon name="cloud" size={20} />
        <span className="dbx-text">
          {what} sync with Dropbox{dbx.name ? ` (${dbx.name})` : ''}, in Apps › Feeder.
        </span>
        <button
          type="button"
          className="mf-btn"
          onClick={async () => {
            if (!confirm('Sign out of Dropbox on every device? The files stay in Dropbox.')) return
            await signOutDropbox()
            toast('Signed out of Dropbox')
          }}
        >
          Sign out
        </button>
      </div>
    )
  return (
    <div className="dbx-panel">
      <Icon name="cloud" size={20} />
      <span className="dbx-text">Keep {what.toLowerCase()} in Dropbox to have them on all your devices. Sign in once; your other devices follow through sync.</span>
      {askKey ? (
        <form
          className="dbx-key"
          onSubmit={async (e) => {
            e.preventDefault()
            if (!key.trim()) return
            await setDropboxAppKey(key)
            await startDropboxSignIn(key.trim())
          }}
        >
          <input value={key} onChange={(e) => setKey(e.target.value)} placeholder="Dropbox app key" aria-label="Dropbox app key" autoFocus />
          <button type="submit" className="mf-btn primary">
            Sign in
          </button>
        </form>
      ) : (
        <button type="button" className="mf-btn primary" onClick={() => void signIn()}>
          Sign in to Dropbox
        </button>
      )}
    </div>
  )
}
