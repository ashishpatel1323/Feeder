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
