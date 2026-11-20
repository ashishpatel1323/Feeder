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
