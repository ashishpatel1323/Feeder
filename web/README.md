# Feeder Web

Feeder in the browser. It joins the same sync chain as Feeder on your phone, so your feeds
(with their per-feed settings) and read status stay in step. Fork-only, not for upstream.

## What it has

- Feeds, tags, article list and reader, full text (Readability), OPML import/export
- Sync with Feeder's sync chain: join with the phone's "add device" link, or start a chain here
- Grouping (None/Date/Feed/Tag/Length/Status/Site), collapsible sticky group headers with
  unread counts and mark-group-read, long-press a header to collapse all, filter chips
  (Unread only/Saved/Today/Short reads/Read), Newest/Oldest/Shortest/Longest sort
- Always-visible article buttons: read aloud, full text, open original; bottom bar with
  Share/Unread/Summarize/Save
- Read aloud with the browser's voices: sentence and word highlight, tap a word for
  "Read from here", tap while reading to jump, compact player with speed, previous/next
  article, up-next strip and auto-continue, media keys (Media Session)
- Next/previous follows the list order at the time you opened the article; swipe on touch,
  J/K or arrow keys on a keyboard (also R read aloud, S save, M unread, O open original)
- Hacker News score badge (Settings, off by default), og:image thumbnails (per feed, synced)
- Summaries through any OpenAI-compatible API (Ollama's `/v1`, OpenAI, LM Studio…)
- Per-feed block/allow rules (synced), installable as an app (PWA)

## What syncs

Exactly what Feeder's sync carries: the feed list with per-feed settings, and "marked as
read". Saved articles, marking unread again, and app settings stay on each device.

## How it works

```
browser (React + IndexedDB)  ──/api/fetch──▶  Pages Function  ──▶  feed / article sites
       │ encrypts with your key            └──/api/sync──▶  feeder-sync server
```

The `/api` function (`server/handler.ts`) stores nothing. It fetches feeds and pages the
browser can't fetch itself (CORS), and forwards sync calls with the fixed login the Android
app uses. Sync data is encrypted in the browser (`src/lib/crypto.ts`, byte-compatible with
the app's `AesCbcWithIntegrity`), so the function only sees ciphertext.

## Develop

```sh
cd web
npm install
npm run dev        # http://localhost:5173, /api served by the same handler
npm test
```

Environment variables for `npm run dev`: `SYNC_SERVER` (a self-hosted feeder-sync),
`ACCESS_TOKEN`, `ALLOW_PRIVATE_HOSTS=1` (lets the fetcher reach localhost, for testing).

## Deploy (Cloudflare Pages, free tier)

The `Web` workflow deploys on every push to `master` that touches `web/`, once these
repository secrets exist:

- `CLOUDFLARE_API_TOKEN`: a token with the "Cloudflare Pages: Edit" permission
- `CLOUDFLARE_ACCOUNT_ID`

The site is then at `https://feeder-web.pages.dev`. In the Pages project settings, set the
environment variable `ACCESS_TOKEN` to a long random string and enter the same string in the
app under Settings › Backend access, so nobody else can use the feed fetcher.

## Known limits

- Article ids match the phone for normal feeds. Feeds with repeated ids or "alternative id"
  turned on hash the article text, and the browser's text extraction can differ from the
  app's, so read marks for those feeds may not carry over.
- Feeds refresh while the tab is open (and when you come back to it), not in the background.
- The phone hashes the feed list with Kotlin's `hashCode`, which a browser can't reproduce.
  After you change feeds on one side, the other side downloads and re-uploads the list once.
