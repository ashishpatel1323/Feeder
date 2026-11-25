/**
 * This device, as named on progress synced to other devices ("42% played on Pixel 8, 2h ago").
 * The id is random and never changes, so renaming keeps "this device" apart from the others; the
 * name is a guess from the browser ("Chrome on Mac", "Mac app") until renamed in Sync settings.
 * Same fields as the Android app's ThisDevice.
 */

const ID_KEY = 'feeder.deviceId'
const NAME_KEY = 'feeder.deviceName'

/** Who saved a position: device id and the name it had then. */
export interface DeviceStamp {
  dv?: string
  dn?: string
}

function store(): Storage | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage
  } catch {
    return null
  }
}

let cachedId = ''

export function deviceId(): string {
  if (cachedId) return cachedId
  const s = store()
  let id = s?.getItem(ID_KEY) ?? ''
  if (!id) {
    id = typeof crypto !== 'undefined' && 'randomUUID' in crypto ? crypto.randomUUID() : `${Date.now().toString(36)}${Math.random().toString(36).slice(2)}`
    s?.setItem(ID_KEY, id)
  }
  cachedId = id
  return id
}

/** A name from the browser: "Mac app" in the Feeder Mac app, else "Chrome on Mac" and so on. */
export function autoDeviceName(ua = typeof navigator === 'undefined' ? '' : navigator.userAgent): string {
  const os = /iPhone|iPad/.test(ua)
    ? 'iPhone'
    : /Android/.test(ua)
      ? 'Android'
      : /Windows/.test(ua)
        ? 'Windows'
        : /Mac OS X|Macintosh/.test(ua)
          ? 'Mac'
          : /Linux/.test(ua)
            ? 'Linux'
            : ''
  if (/Electron\//.test(ua)) return os ? `${os} app` : 'Desktop app'
  const browser = /Edg\//.test(ua) ? 'Edge' : /Firefox\//.test(ua) ? 'Firefox' : /Chrome\//.test(ua) ? 'Chrome' : /Safari\//.test(ua) ? 'Safari' : 'Browser'
  return os ? `${browser} on ${os}` : browser
}

/** The name set in Sync settings, or the guess. */
export function deviceName(): string {
  return store()?.getItem(NAME_KEY)?.trim() || autoDeviceName()
}

/** Renames this device (blank = back to the guess). Positions saved from now on carry the new name. */
export function setDeviceName(name: string): void {
  const s = store()
  const n = name.trim().slice(0, 40)
  if (!n || n === autoDeviceName()) s?.removeItem(NAME_KEY)
  else s?.setItem(NAME_KEY, n)
}

/** The stamp for a position saved here now. */
export function thisDevice(): Required<DeviceStamp> {
  return { dv: deviceId(), dn: deviceName() }
}

/** "just now", "5 min ago", "2h ago", "3d ago", or a date such as "Nov 3". */
export function agoText(ts: number, now = Date.now()): string {
  const s = Math.max(0, (now - ts) / 1000)
  if (s < 60) return 'just now'
  if (s < 3600) return `${Math.floor(s / 60)} min ago`
  if (s < 86_400) return `${Math.floor(s / 3600)}h ago`
  if (s < 7 * 86_400) return `${Math.floor(s / 86_400)}d ago`
  return new Date(ts).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
}

/**
 * "on Pixel 8, 2h ago" when another device saved this position last, else '' (progress made here
 * is not labelled, so lists stay as they were).
 */
export function otherDeviceText(p: (DeviceStamp & { ts: number }) | null | undefined, now = Date.now()): string {
  if (!p?.dv || !p.dn || p.dv === deviceId()) return ''
  return `on ${p.dn}, ${agoText(p.ts, now)}`
}
