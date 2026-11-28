# Feeder for Mac

The web app in its own macOS window, with a built-in server on `127.0.0.1:47815`:
feed fetching, sync (through the deployed Feeder sync server) and YouTube saving with
yt-dlp. No terminal or dev server needed.

Build (needs Node 22+, e.g. `/usr/local/bin/node`):

```bash
cd web/mac && npm install && npm run build
codesign --force --deep -s - release/mac-arm64/Feeder.app
```

The app ends up in `release/mac-arm64/Feeder.app`. yt-dlp and ffmpeg are taken from
`~/.local/bin`, `~/homebrew/bin`, `/opt/homebrew/bin` or `/usr/local/bin`.
The app has its own storage: join your sync once in Settings to get your feeds.

## Open in IINA

YouTube articles have an **Open in IINA** button (on any Mac, in the app or a browser). It
opens `iina://weblink?url=…`; the app hands it to IINA directly, a browser asks once. IINA
plays it with yt-dlp, so live streams work too. A saved offline video has **Offline copy in
IINA**, which writes the MP4 and its subtitles to a temp folder and opens it (needs the local
server: this app or `npm run dev`).

Auto-generated subtitles are an IINA setting, not part of the link. With IINA's Online Media
plugin: Settings → Plugins → Online Media → turn on *Include auto-generated subtitles*, and
set *Raw options* to `--sub-langs en.*,hi` (otherwise yt-dlp lists every auto-translated
language). Pick the track from IINA's subtitle menu if it does not show on its own.

## Playlist briefs (Claude Code)

On any device, a playlist's ⋮ menu has **Brief with Claude**. While this app is open, it picks the
request up through sync, fetches each article's full text, and runs Claude Code (`claude -p`) to write
the big picture plus a summary of every article. The brief syncs back and shows at the top of the
playlist on every device. Nothing is marked read.

It uses your own Claude sign-in, so there's no API key: install Claude Code and sign in once
(`claude` in Terminal). Each brief counts toward your Claude plan's usage. The app finds `claude` in
`~/.local/bin`, `~/.claude/local`, `/opt/homebrew/bin`, `/usr/local/bin` or your login shell's PATH.
Code: `src/lib/briefs.ts`, `src/lib/macBrief.ts`, `/api/brief/*` in `server.ts`.

## AI agents (MCP)

Settings › **AI agents (MCP)** has one switch: "Let AI agents read my Feeder library". It runs Feeder's
MCP server (`web/mcp`, copied into `Feeder.app/Contents/Resources/mcp` by `after-pack.cjs`) with the
app's own binary as Node (`ELECTRON_RUN_AS_NODE=1`), so nothing else needs installing.

- On: the sync code and key go into the macOS Keychain (service `feeder-mcp`), an HTTP MCP endpoint
  starts on `127.0.0.1:47816` (token in the URL; "Copy Claude Code command" in Settings), and a
  `feeder` entry is added to Claude Desktop's config that starts the same server over stdio, so it
  works even when Feeder is closed. The switch stays on across restarts.
- Off: stops the server, removes the Claude Desktop entry (only the one this app wrote) and the
  Keychain item. The index in `~/Library/Application Support/feeder-mcp` is kept.
- `/api/mcp/*` on the app's local server only answers the app's own page (Origin check plus an
  `x-feeder-app` header and a JSON body), since it hands over the sync key.
