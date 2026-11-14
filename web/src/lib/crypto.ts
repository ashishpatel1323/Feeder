/**
 * Feeder's sync encryption, byte-compatible with the Android app's
 * crypto/AesCbcWithIntegrity.kt:
 *  - key string:    base64(aesKey 16 bytes) ":" base64(hmacKey 32 bytes)
 *  - cipher string: base64(iv 16 bytes) ":" base64(mac) ":" base64(ciphertext)
 *  - AES-128-CBC with PKCS#7 padding, HMAC-SHA256 over (iv || ciphertext)
 * Base64 is the standard alphabet with padding (Android Base64.NO_WRAP).
 */

export interface SecretKeys {
  aes: CryptoKey
  hmac: CryptoKey
  /** The original key string, as shared in the "add device" link. */
  encoded: string
}

export function b64encode(bytes: Uint8Array): string {
  let s = ''
  for (let i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i])
  return btoa(s)
}

export function b64decode(text: string): Uint8Array<ArrayBuffer> {
  // Be lenient: links sometimes turn '+' into ' ', and some encoders use the URL alphabet
  const clean = text.trim().replace(/ /g, '+').replace(/-/g, '+').replace(/_/g, '/')
  const padded = clean + '='.repeat((4 - (clean.length % 4)) % 4)
  const bin = atob(padded)
  const out = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i)
  return out
}

export async function decodeKey(keyString: string): Promise<SecretKeys> {
  const parts = keyString.trim().split(':')
  if (parts.length !== 2) throw new Error('Secret key must look like "aesKey:hmacKey"')
  const aesBytes = b64decode(parts[0])
  const hmacBytes = b64decode(parts[1])
  if (aesBytes.length !== 16) throw new Error('AES key is not 16 bytes')
  if (hmacBytes.length !== 32) throw new Error('HMAC key is not 32 bytes')
  const aes = await crypto.subtle.importKey('raw', aesBytes, { name: 'AES-CBC' }, false, ['encrypt', 'decrypt'])
  const hmac = await crypto.subtle.importKey('raw', hmacBytes, { name: 'HMAC', hash: 'SHA-256' }, false, [
    'sign',
    'verify',
  ])
  return { aes, hmac, encoded: `${b64encode(aesBytes)}:${b64encode(hmacBytes)}` }
}

function concat(a: Uint8Array, b: Uint8Array): Uint8Array<ArrayBuffer> {
  const out = new Uint8Array(a.length + b.length)
  out.set(a, 0)
  out.set(b, a.length)
  return out
}

export async function encryptString(plaintext: string, keys: SecretKeys): Promise<string> {
  const iv = crypto.getRandomValues(new Uint8Array(16))
  const cipher = new Uint8Array(
    await crypto.subtle.encrypt({ name: 'AES-CBC', iv }, keys.aes, new TextEncoder().encode(plaintext)),
  )
  const mac = new Uint8Array(await crypto.subtle.sign('HMAC', keys.hmac, concat(iv, cipher)))
  return `${b64encode(iv)}:${b64encode(mac)}:${b64encode(cipher)}`
}

export async function decryptString(civ: string, keys: SecretKeys): Promise<string> {
  const parts = civ.split(':')
  if (parts.length !== 3) throw new Error('Cannot parse iv:mac:ciphertext')
  const iv = b64decode(parts[0])
  const mac = b64decode(parts[1])
  const cipher = b64decode(parts[2])
  const ok = await crypto.subtle.verify('HMAC', keys.hmac, mac, concat(iv, cipher))
  if (!ok) throw new Error('Integrity check failed (wrong key?)')
  const plain = await crypto.subtle.decrypt({ name: 'AES-CBC', iv }, keys.aes, cipher)
  return new TextDecoder().decode(plain)
}

/**
 * Pulls the sync code and key out of whatever the user pasted: the full "add device" link
 * (https://feederapp.nononsenseapps.com/sync/join?sync_code=...&key=...), or just the parts.
 * Mirrors syncCodeQueryParam / secretKeyQueryParam in the Android SyncScreen.kt.
 */
export function parseJoinLink(input: string): { syncCode: string; key: string } | null {
  const text = input.trim()
  const codeMatch = text.match(/sync_code=([0-9a-fA-F]{64})/)
  const keyMatch = text.match(/[?&]key=([^&\s]+)/)
  if (!codeMatch || !keyMatch) return null
  let key = keyMatch[1]
  try {
    key = decodeURIComponent(key.replace(/\+/g, '%2B'))
  } catch {
    // keep as is
  }
  return { syncCode: codeMatch[1], key }
}
