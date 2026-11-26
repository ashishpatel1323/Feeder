# feeder-mcp

An MCP server that gives an AI agent (Claude Desktop, Claude Code, or any MCP client) read-only
access to everything in Feeder, as chunks it can search and read:

- articles (full text fetched when the feed only has a teaser), feeds and folders
- playlists, manual and smart (rules evaluated like the app does)
- Saved, read/unread, highlights and notes (article and ebook)
- podcast transcripts (`podcast:transcript` in the feed) and YouTube captions (yt-dlp)
- ebooks from Dropbox `Apps/Feeder/Ebooks` (EPUB, PDF), with chapter-level access
- ZIM files from Dropbox `Apps/Feeder/ZIM` and `~/Zimit` (small ones fully searchable)

The full design is in [SPEC.md](SPEC.md).

## How it gets the data

Feeder's sync is end-to-end encrypted, so feeder-mcp joins it as one more (read-only) device: it
pulls the records and decrypts them on this Mac with your sync key. Article text isn't synced, so it
fetches your feeds itself. The Dropbox sign-in comes from sync too, so ebooks and ZIMs need no
extra sign-in. Everything is indexed into SQLite (FTS5) at
`~/Library/Application Support/feeder-mcp/index.db`. Nothing is ever pushed back.

## Set up (once)

Easiest: in Feeder for Mac, turn on Settings › AI agents (MCP). Everything below is for running it on its own.

Needs Node 22.13+ (uses Node's built-in SQLite, no native modules). yt-dlp is optional (YouTube captions).

```bash
cd web/mcp && npm install && npm run build
node dist/cli.js setup     # paste Feeder's "add device" link; it goes into the macOS Keychain
node dist/cli.js index     # first full fetch of feeds + Dropbox listing (a few minutes)
node dist/cli.js status
```

Get the link in Feeder: Android Settings › Sync › Add device (copy link), or in the web/Mac app
Settings › Sync.

## Use it from Claude

Claude Code:

```bash
claude mcp add feeder -s user -- /usr/local/bin/node /path/to/Feeder/web/mcp/dist/index.js
```

Claude Desktop, in `~/Library/Application Support/Claude/claude_desktop_config.json`:

```json
{ "mcpServers": { "feeder": { "command": "/usr/local/bin/node", "args": ["/path/to/Feeder/web/mcp/dist/index.js"] } } }
```

While a client has it open, the server refreshes in the background: sync every 5 minutes, feeds
and Dropbox every 30.

Try:

- "Give me a summary of the items in my Morning drive playlist" (or the `summarize_playlist` prompt)
- "Summarize the first three chapters of <book>" (or `summarize_book_chapters`)
- "What have I highlighted about leadership?"

## Tools

`search`, `list_feeds`, `list_items`, `get_item`, `list_playlists`, `get_playlist`,
`get_playlist_content`, `list_books`, `get_book_toc`, `read_book`, `list_highlights`, `list_zims`,
`zim_lookup`, `zim_read`, `status`, `refresh`. Prompts: `summarize_playlist`,
`summarize_book_chapters`, `unread_digest`, `highlights_review`, `ask_my_library`. Resources under
`feeder://` (items, playlists, book TOC and chapters, highlights).

Long text is paged (`max_tokens` + `next_cursor`), so a three-chapter read works in a normal
context window.

## Test

```bash
npm run build && node test/e2e.mjs
```

Starts a fake sync server with a throwaway key, local feeds (one podcast with a transcript) and a
generated EPUB, then drives the real MCP server over stdio through every tool.

## Not yet

- Semantic (embedding) search: keyword search (BM25) only for now
- Whisper transcripts for podcasts whose feed has none
- MOBI/AZW3 ebooks
- Write-back (mark read, add highlight) and remote access

## Remote (HTTP)

`node dist/http.js` serves the same tools over Streamable HTTP at `/mcp`, protected by a token
(`FEEDER_MCP_TOKEN`, else generated once into `<data dir>/http-token`). Send it as
`Authorization: Bearer <token>`, or put it in the path (`/mcp/<token>`) for clients that only take a
URL, such as claude.ai custom connectors. `HOST`/`PORT` default to `127.0.0.1:8787`.

Cloud: the `Dockerfile` + `fly.toml` run it on Fly.io with a 1 GB volume at `/data`. Secrets go in
the host's secret store: `FEEDER_SYNC_CODE`, `FEEDER_SYNC_KEY`, `FEEDER_MCP_TOKEN`.
