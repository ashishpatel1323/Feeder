# Feeder Web

Feeder in the browser. It syncs with Feeder on your phone, so your feeds (with their per-feed
settings), read status, Saved and deleted articles stay in step. Fork-only, not for upstream.

## What it has

- Feeds, tags, article list and reader, full text (Readability), OPML import/export
- Sync (feeds, read marks, Saved, deleted articles, feed order): join with the phone's
  "add device" link, or start one here
- Grouping (None/Date/Feed/Tag/Length/Status/Site), collapsible sticky group headers with
  unread counts and mark-group-read, long-press a header to collapse all, filter chips
  (Unread only/Saved/Today/Short reads/Read), Newest/Oldest/Shortest/Longest sort
- Always-visible article buttons: read aloud, full text, open original; bottom bar with
  Share/Unread/Summarize/Save
- Read aloud with the browser's voices: sentence and word highlight, tap a word for
  "Read from here", tap while reading to jump, and a player like the Android app's: dark
  panel, wavy seek line (drag or tap), ±10 s, previous/next article, speed menu, up next,
  ⋮ menu (Show word, Auto-play next, Go to spoken text, Skip to next, Change voice), media keys
- Speed reading inside the player: "Show word" (speedometer button or V) shows the spoken word
  big with a focus letter; Mute hands over to a silent reader at 100–1000 wpm that keeps
  highlighting and scrolling the article, and unmuting reads aloud on from the word reached
- Natural voice that runs on this device (Kokoro via kokoro-js, English): the model downloads
  once from Hugging Face (about 90 MB, or 310 MB with the GPU option) and runs in a worker
- Next/previous follows the list order at the time you opened the article; swipe on touch,
  J/K or arrow keys on a keyboard (also R read aloud, V show word, S save, M unread, O open original)
- Hacker News score badge (Settings, off by default), og:image thumbnails (per feed, synced)
- Summaries through any OpenAI-compatible API (Ollama's `/v1`, OpenAI, LM Studio…)
- Per-feed block/allow rules (synced), installable as an app (PWA)

## What syncs

Feeder's own sync (`server/sync2.ts` + `src/lib/sync2.ts`, shared with the Android app): the
feed list with per-feed settings and unsubscribes, read and unread, Saved, deleted articles
(Delete in the reader, or "Delete read articles" above the list; both have Undo), and the order
of feeds and folders. App settings stay on each device.

```
browser / phone  ──POST /api/v2/sync──▶  /api function  ──▶  Postgres (Neon)
  encrypts every record with your key        stores opaque records, never the key
```

- Every fact is one record: `{ id, ts, data }`. `id` is an HMAC of kind + entity made on the
  device, `data` is encrypted with the same AES-CBC + HMAC format as Feeder's old sync, and the
  higher `ts` wins. One request sends local changes and returns everything changed since the
  device's cursor.
- The account and its secret are derived from the sync code and key the devices already share,
  so the add-device link is the same as before and moving over needs no re-pairing.
- Moving over from Feeder's old sync chain happens once per device: one last pull from the old
  chain, then everything the device has is uploaded (feeds, unsubscribes, read marks, Saved).
- New record kinds (Read later links, …) need no server change; devices keep kinds they don't
  know.

Server environment: `DATABASE_URL` (or `POSTGRES_URL`) for the Neon database; the tables are
created on first use. `SYNC_MAX_ACCOUNTS` (default 3) caps how many sync accounts can exist.
`npm run dev` without `DATABASE_URL` syncs through the deployed app (feeder-web-snowy.vercel.app), so a
local copy stays in step with your devices; `SYNC_UPSTREAM=none` keeps sync data in memory instead.

## Develop

```sh
cd web
npm install
npm run dev        # http://localhost:5173, /api served by the same handler
npm test
```

Environment variables for `npm run dev`: `SYNC_SERVER` (a self-hosted feeder-sync),
`ACCESS_TOKEN`, `ALLOW_PRIVATE_HOSTS=1` (lets the fetcher reach localhost, for testing).

## Deploy

Pick one host. Both run the same `server/handler.ts`, and both free tiers are enough.

### Vercel (Hobby, free)

1. In Vercel, Add New › Project, and import `ashishpatel1323/Feeder`.
2. Set Root Directory to `web`. Vercel reads `web/vercel.json` for the rest (Vite build,
   `dist` output, `/api/*` routed to the edge function in `api/proxy.ts`).
3. Under Environment Variables, add `ACCESS_TOKEN` (a long random string) and enter the same
   string in the app under Settings › Backend access. Optional: `SYNC_SERVER`.
4. Deploy. Every push to `master` redeploys; pull requests get preview URLs. No GitHub
   secrets are needed, and the Cloudflare step in the `Web` workflow stays skipped.

Vercel builds the whole repository's pushes, so in Project › Settings › Git you can set
"Ignored Build Step" to `git diff --quiet HEAD^ HEAD -- .` to skip Android-only commits.

### Cloudflare Pages (free tier)

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
