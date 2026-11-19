import 'fake-indexeddb/auto'
import { beforeEach, describe, expect, it } from 'vitest'
import {
    addEmptyFolder,
    db,
    deleteFeeds,
    deleteTag,
    EMPTY_FOLDERS_KEY,
    FOLDER_ORDER_KEY,
    getKv,
    itemId,
    newFeed,
    opensInBrowser,
    placeFolder,
    renameFeed,
    renameTag,
    setKv,
    sortFolders,
    updateFeed,
} from '../db'

const item = (feedUrl: string, guid: string) => ({
    id: itemId(feedUrl, guid),
    feedUrl,
    guid,
    title: guid,
    link: null,
    contentHtml: '',
    snippet: '',
    imageUrl: null,
    author: null,
    sortTime: 1,
    firstSeen: 1,
    wordCount: 1,
    read: 0,
    readTime: null,
    readSynced: 0,
    saved: 0,
})

beforeEach(async () => {
    await db.feeds.clear()
    await db.items.clear()
    await db.kv.clear()
})

describe('feed settings from the manage page', () => {
    it('bumps whenModified so the change syncs, and skips no-op changes', async () => {
        await db.feeds.put(
            newFeed('https://a/rss', {
                whenModified: 5,
                fullTextByDefault: true,
            }),
        )
        expect(await updateFeed('https://a/rss', { fullTextByDefault: true })).toBe(false)
        expect((await db.feeds.get('https://a/rss'))?.whenModified).toBe(5)
        expect(
            await updateFeed('https://a/rss', {
                fullTextByDefault: false,
                openArticlesWith: '2',
            }),
        ).toBe(true)
        const f = await db.feeds.get('https://a/rss')
        expect(f?.fullTextByDefault).toBe(false)
        expect(f?.whenModified).toBeGreaterThan(5)
        expect(opensInBrowser(f)).toBe(true)
    })

    it('renames a feed through customTitle; its own title clears it', async () => {
        await db.feeds.put(newFeed('https://a/rss', { title: 'Orig' }))
        await renameFeed('https://a/rss', '  Mine ')
        expect((await db.feeds.get('https://a/rss'))?.customTitle).toBe('Mine')
        await renameFeed('https://a/rss', 'Orig')
        expect((await db.feeds.get('https://a/rss'))?.customTitle).toBe('')
    })

    it('unsubscribes several feeds with their articles', async () => {
        await db.feeds.bulkPut([newFeed('https://a/rss'), newFeed('https://b/rss'), newFeed('https://c/rss')])
        await db.items.bulkPut([item('https://a/rss', '1'), item('https://c/rss', '2')])
        await deleteFeeds(['https://a/rss', 'https://b/rss'])
        expect((await db.feeds.toArray()).map((f) => f.url)).toEqual(['https://c/rss'])
        expect(await db.items.count()).toBe(1)
    })
})

describe('folders', () => {
    it('renames a folder on every feed and in the local lists, merging into an existing one', async () => {
        await db.feeds.bulkPut([
            newFeed('https://a/rss', { tag: 'Tech', whenModified: 1 }),
            newFeed('https://b/rss', { tag: 'Tech', whenModified: 1 }),
            newFeed('https://c/rss', { tag: 'News' }),
        ])
        await setKv(FOLDER_ORDER_KEY, ['News', 'Tech'])
        expect(await renameTag('Tech', 'News')).toBe(2)
        const feeds = await db.feeds.toArray()
        expect(feeds.every((f) => f.tag === 'News')).toBe(true)
        expect(feeds.find((f) => f.url === 'https://a/rss')!.whenModified).toBeGreaterThan(1)
        expect(await getKv(FOLDER_ORDER_KEY, [])).toEqual(['News'])
    })

    it('renames an empty folder', async () => {
        expect(await addEmptyFolder('Later')).toBe(true)
        expect(await addEmptyFolder('Later')).toBe(false)
        await renameTag('Later', 'Someday')
        expect(await getKv(EMPTY_FOLDERS_KEY, [])).toEqual(['Someday'])
    })

    it('deletes a folder keeping its feeds, or unsubscribing from them', async () => {
        await db.feeds.bulkPut([newFeed('https://a/rss', { tag: 'Tech' }), newFeed('https://b/rss', { tag: 'Old' })])
        expect(await deleteTag('Tech', 'keep')).toBe(1)
        expect((await db.feeds.get('https://a/rss'))?.tag).toBe('')
        expect(await deleteTag('Old', 'unsubscribe')).toBe(1)
        expect(await db.feeds.get('https://b/rss')).toBeUndefined()
    })

    it('orders folders by manual position, then alphabetically', () => {
        expect(sortFolders(['b', 'C', 'a', 'd'], ['d', 'b'])).toEqual(['d', 'b', 'a', 'C'])
        expect(placeFolder(['a', 'b', 'c'], 'c', 'a')).toEqual(['c', 'a', 'b'])
        expect(placeFolder(['a', 'b', 'c'], 'a', null)).toEqual(['b', 'c', 'a'])
    })
})
