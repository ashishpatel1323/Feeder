#!/usr/bin/env node
/**
 * feeder-mcp setup    paste Feeder's "add device" link; stores it in the Keychain and pulls sync
 * feeder-mcp index    sync + fetch every feed + list Dropbox + index small ZIMs
 * feeder-mcp status   what the index holds
 * feeder-mcp search <words>
 * feeder-mcp forget   remove the stored sync key
 */
import './quiet.js'
import './dom.js'
import { createInterface } from 'node:readline'
import { DB_PATH, deleteSecret, ensureDirs, loadSecret, saveSecret } from './config.js'
import { openDb } from './db.js'
import { runOnce } from './indexer.js'
import { search } from './search.js'
import { statusOf } from './server.js'
import { pull } from './sync.js'
import { parseJoinLink } from './vendor/crypto.js'

function ask(question: string, hidden = false): Promise<string> {
  return new Promise((resolve) => {
    const rl = createInterface({ input: process.stdin, output: process.stdout, terminal: true })
    if (hidden) {
      const out = rl as unknown as { _writeToOutput: (s: string) => void }
      out._writeToOutput = (s: string) => {
        if (s.includes(question)) process.stdout.write(s)
        else process.stdout.write('*')
      }
    }
    rl.question(question, (a) => {
      rl.close()
      if (hidden) process.stdout.write('\n')
      resolve(a.trim())
    })
  })
}

const [cmd, ...rest] = process.argv.slice(2)
ensureDirs()
const db = openDb(DB_PATH)

switch (cmd) {
  case 'setup': {
    console.log('In Feeder (phone: Settings › Sync › Add device, or web Settings › Sync), copy the "add device" link.')
    const link = await ask('Paste the link here: ', true)
    const parsed = parseJoinLink(link)
    if (!parsed) {
      console.error('That doesn’t look like a Feeder sync link (needs sync_code=… and key=…).')
      process.exit(1)
    }
    const secret = { syncCode: parsed.syncCode, secretKey: parsed.key }
    console.log('Pulling your sync data…')
    const r = await pull(db, secret)
    if (!r.records) {
      console.error('The sync server has nothing for that link. Check you copied the whole link from a device that syncs. Nothing was saved.')
      process.exit(1)
    }
    saveSecret(secret)
    console.log(`Saved (Keychain on a Mac, a private file on Linux). Got ${r.records} records:`, r.byKind)
    console.log('Next: `feeder-mcp index` to fetch your feeds (a few minutes the first time).')
    break
  }
  case 'index': {
    if (!loadSecret()) {
      console.error('Run `feeder-mcp setup` first.')
      process.exit(1)
    }
    const t = Date.now()
    console.log('Syncing, fetching feeds, listing Dropbox, indexing ZIMs…')
    const r = await runOnce(db)
    console.log(JSON.stringify(r, null, 2))
    console.log(`Done in ${Math.round((Date.now() - t) / 1000)} s`)
    break
  }
  case 'status':
    console.log(JSON.stringify({ setup: !!loadSecret(), db: DB_PATH, ...statusOf(db) }, null, 2))
    break
  case 'search': {
    for (const h of search(db, rest.join(' '), {}, 10)) console.log(`- [${h.source}] ${h.title}${h.feed_title ? ' — ' + h.feed_title : ''}\n  ${h.snippet}`)
    break
  }
  case 'forget':
    deleteSecret()
    console.log('Removed the sync key from the Keychain. The index stays at', DB_PATH)
    break
  default:
    console.log('Usage: feeder-mcp setup | index | status | search <words> | forget\nThe MCP server itself is dist/index.js (stdio).')
}
