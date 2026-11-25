// Runs only with real ZIM files: ZIM_FILES=a.zim,b.zim npx vitest run src/lib/zim
import { describe, it, expect } from 'vitest'
import { openAsBlob } from 'node:fs'
import { ZimArchive, blobSource } from './zim'
const files = (process.env.ZIM_FILES ?? '').split(',').filter(Boolean)
describe.skipIf(!files.length)('real ZIMs', () => {
  for (const f of files) it(f, async () => {
    const z = await ZimArchive.open(blobSource(await openAsBlob(f)))
    console.log(f, z.majorVersion, z.minorVersion, z.entryCount, 'new', z.newNamespace)
    console.log('title', await z.metadata('Title'), '|', await z.metadata('Description'))
    const main = await z.mainPath()
    console.log('main', main)
    const item = await z.get(main!)
    console.log(item!.mime, item!.data.length, new TextDecoder().decode(item!.data.subarray(0, 200)))
    console.log('icon', (await z.illustration())?.length, 'ft', await z.hasFulltextIndex())
    const t0 = Date.now()
    console.log('suggest', await z.suggest('ra', 5), Date.now() - t0, 'ms')
    // a linked resource from main page
    const html = new TextDecoder().decode(item!.data)
    const m = [...html.matchAll(/(?:src|href)="([^"#?:]+)"/g)].map((x) => x[1]).slice(0, 8)
    const base = main!.split('/').slice(0, -1).join('/')
    for (const l of m) {
      const p = new URL(l, 'http://x/' + (base ? base + '/' : '')).pathname.slice(1)
      const it = await z.get(decodeURIComponent(p))
      console.log(' ', l, '->', p, it ? it.mime + ' ' + it.data.length : 'MISSING')
    }
    expect(item).toBeTruthy()
  }, 60000)
})
