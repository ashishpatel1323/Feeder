import { describe, expect, it } from 'vitest'
import { decodeKey, decryptString, encryptString, parseJoinLink } from '../crypto'
import { javaStringHash } from '../feedparse'

const KEY = 'AAECAwQFBgcICQoLDA0ODw==:EBESExQVFhcYGRobHB0eHyAhIiMkJSYnKCkqKywtLi8='
// Produced by javax.crypto with the same algorithm as the Android app's AesCbcWithIntegrity
const FROM_JAVA =
  '+4P6RuKOE//PA+gsczHSuw==:TiOg9EvGNpBBqe+omRgJ9meng82o7GqP1n1mVgekjkM=:IgbFR2JMuJ4vABmXV/dLNuxaqZ6vY9q32Vz8nyojphzSgeJn7EE6td5fi6bO1Fq9dPNakNzhhtqn5uIWKKxwGrSq613FSji/j5Kw+FWSFys='

describe('sync crypto', () => {
  it('decrypts what the JVM encrypted', async () => {
    const keys = await decodeKey(KEY)
    expect(await decryptString(FROM_JAVA, keys)).toBe(
      '{"feedUrl":"https://example.com/feed.xml","articleGuid":"héllo ✓"}',
    )
  })

  it('round-trips and rejects tampering', async () => {
    const keys = await decodeKey(KEY)
    const enc = await encryptString('Feeder Web (Chrome)', keys)
    expect(enc.split(':')).toHaveLength(3)
    expect(await decryptString(enc, keys)).toBe('Feeder Web (Chrome)')
    const [iv, mac, ct] = enc.split(':')
    const flipped = ct.startsWith('A') ? 'B' + ct.slice(1) : 'A' + ct.slice(1)
    await expect(decryptString(`${iv}:${mac}:${flipped}`, keys)).rejects.toThrow()
  })

  it('parses the add-device link', () => {
    const code = 'a'.repeat(64)
    const link = `https://feederapp.nononsenseapps.com/sync/join?sync_code=${code}&key=${encodeURIComponent(KEY)}`
    expect(parseJoinLink(link)).toEqual({ syncCode: code, key: KEY })
    // Already-decoded link (as some apps show it)
    expect(parseJoinLink(`https://x/sync/join?sync_code=${code}&key=${KEY}`)).toEqual({ syncCode: code, key: KEY })
    expect(parseJoinLink('nothing here')).toBeNull()
  })

  it('matches Java String.hashCode', () => {
    expect(javaStringHash('x')).toBe(120)
    expect(javaStringHash('Hello wörld ✓ 😀')).toBe(429889299)
    expect(javaStringHash('')).toBe(0)
  })
})
