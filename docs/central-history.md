# Central History (web + Android)

One log of everything consumed or changed, shown as **History** (a row in Library and a selectable
bottom-bar tab). Web: `web/src/lib/recent.ts`; Android: `model/history/CentralHistory.kt`.

It is separate from the per-item event timeline of PR #141 (sync kind `history`, immutable events):
that stays as is; audiobook/ebook/ZIM opens also update the central log.

## Entry

| field | meaning |
|---|---|
| `t` | type: `article` `youtube` `podcast` `audiobook` `ebook` `zim` `highlight` `note` `feed` |
| `a` | action: `open` (consumed), `create` (highlight/note made, feed subscribed), `delete` |
| `i` | item id: article/YouTube/podcast = `feedUrl + "\n" + guid` (same as `video`/`read` keys); audiobook / ebook id (same as `audiopos`/`bookpos`); ZIM page = `zimBookId + "\n" + path`; highlight/note = highlight id; feed = feed URL |
| `n` | title: article/video/episode/book title, ZIM page title, feed title; for highlight/note the parent article (or book) title |
| `s` | source: feed / channel / podcast title, book author or ZIM book title (optional) |
| `img` | thumbnail URL, only `http(s)` (optional) |
| `at` | when (ms): last opened, or when created/deleted |
| `f` | 0–1 progress (optional) |
| `pos`, `dur` | position and length in seconds for media (optional) |
| `d` | `1` when read / watched / finished (optional) |
| `pl`, `pn` | playlist id and name when it was played from a playlist (sticky once set) |
| `x` | where to go back to: YouTube video id, ebook CFI, audiobook `"fileIndex:ms"`, ZIM path; for highlight/note the article item id (`feedUrl\nguid`) or `ebook:\n<id>` |
| `q` | snapshot text (≤ 280 chars): highlight text, or note text |
| `c` | highlight colour (`yellow` `green` `blue` `pink`) |
| `u` | URL: feed URL for `feed`, article link for highlight/note (optional) |
| `dv`, `dn` | device id and name (ThisDevice / device.ts) |

## Sync (own sync v2, kind `recent`)

- Key: `t + ":" + i` for `open`; `t + ":" + i + ":" + a` for `create`/`delete` (so "Subscribed"
  and "Deleted feed" are two rows).
- Value: the entry fields above (omit empty ones), or `{ "deleted": true }` once removed from history.
- Record `ts` = the entry's last change (open time, a later progress update, or the removal time).
  Each change uses `max(now, previous ts + 1)`. Applying a remote record: keep it only when its `ts`
  is newer than the local one (latest wins). Like `history`, it is not kept in syncKnown; each entry
  has its own `synced` flag (unsynced on change, on join/leave/force sync everything is unsynced).
- **Clear history**: key `"*"` with value `{ "before": ms }`, record ts = `before`. Every entry with
  `at <= before` is dropped locally, and remote entries with `at <= before` are ignored.
- Kept locally: newest 2000 entries, none older than 180 days (trimmed locally, no tombstones).

## Recording

- Article opened in the reader → `open` (progress `d: 1` once marked read).
- YouTube video / podcast episode opened or played → `open`, `pos`/`dur`/`f` refreshed when the
  saved position changes, `d: 1` when watched/finished.
- Played from a playlist → same entry with `pl`/`pn`.
- Audiobook / ebook / ZIM page opened → `open` with progress and `x`.
- Highlight created / deleted, note added (first non-empty note) / note cleared or its highlight
  deleted → `create` / `delete` with snapshot. Feed subscribed / unsubscribed → `create` / `delete`
  (OPML import: skipped).
- Backfill once per device: YouTube/podcast positions, ebook and audiobook positions, and the PR #141
  events (latest open per item). Read articles are **not** backfilled: "Mark all read" would flood it.

## UI

Chips (one scrollable row): All, Articles, YouTube, Podcasts, Playlists (entries with `pl`),
Audiobooks, Ebooks, ZIM, Highlights & notes, Feeds. Mail-style date groups (the existing
date-bucket helpers). Row: thumbnail or type icon, title, meta line (source · time · progress ·
device). Text for change rows: "Highlighted in …", "Added note on …", "Subscribed to …",
"Deleted highlight / note / feed". Tapping reopens at the position; a deleted thing shows its
snapshot only (a deleted feed offers Subscribe again). Remove one entry and Clear history each ask
first, naming the entry (or "all history") and saying it is removed from history on all devices; the
content itself is never touched.
