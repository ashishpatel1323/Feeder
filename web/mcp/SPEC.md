# Feeder MCP server — spec (draft v0.1)

Status: **draft for sign-off, nothing built.** 2026-11-25.
Grounded in `~/Developer/Feeder-master` at `44837a32` (after PR #126).

Defaults chosen where Ashish hasn't answered yet are marked **[default]**. Change any of them and the spec changes with it.

---

## 1. What this is

- An **MCP server** ("feeder-mcp") that gives an AI agent read access to everything in Feeder, as chunks it can search and read: articles, feeds and folders, playlists, Saved, highlights and notes, podcast and YouTube transcripts, ebooks (Dropbox `Apps/Feeder/Ebooks`) and ZIM files (Dropbox `Apps/Feeder/ZIM`).
- The agent (the MCP **client**) is Claude Desktop and Claude Code **[default]**. Section 9 says how the client side uses it. Any MCP client works, since it speaks plain MCP.
- Runs **locally on the Mac** over stdio **[default]**. Remote access is a later phase (section 10).
- **Read-only in v1** **[default]**. Write-back (mark read, save, add highlight) is v2 and goes through Feeder's own sync, so it reaches the phone too.

### Use cases that shape the design

| # | Ask | What the server must do |
|---|---|---|
| U1 | "Give me a summary of the feeds in this playlist" | Resolve a playlist by name (manual or smart), list its items in playlist order, return each item's text (article body, or transcript for podcast/video) in budget-sized pages |
| U2 | "Summarize the first three chapters of this ebook" | Resolve a book by fuzzy title, return its table of contents with front matter flagged, return chapters 1–3 as ordered chunks |
| U3 | "What have I highlighted about X?" | Search highlights + notes, ebook highlights too |
| U4 | "Find what I've read about X in the last month" | Hybrid search over articles/transcripts with date, feed, kind and read filters |
| U5 | "Look up X in my offline Wikipedia" | Title search inside ZIM files, return an article as chunks |

U1 and U2 are v1. That puts **playlists and ebooks (with chapter access) in v1**.

---

## 2. Where the data comes from

Feeder has no single store with everything in it. Findings from the repo:

| Data | Where it lives today | How feeder-mcp gets it |
|---|---|---|
| Feed list, folders (`tag`), per-feed settings | Sync kind `feed` (key = feed URL) | Sync pull |
| Feed/folder order | Sync kind `layout` ("main") | Sync pull |
| Read / unread | Sync kind `read` (key = `feedUrl\nguid`) | Sync pull |
| Saved | Sync kind `saved`, with a small article copy in `a` (title, link, sortTime, author, image, 300-char snippet) | Sync pull |
| Deleted for good | Sync kind `hidden` | Sync pull (excluded from results) |
| Playlists (manual `itemIds` + smart `rules`) | Sync kind `playlist` | Sync pull; smart rules evaluated locally (port of `playlists.ts` `matchesRules`/`sortItems`) |
| Highlights + notes (article and ebook) | Sync kind `highlight` (text, prefix, note, color, title, feed, `cfi` for ebooks) | Sync pull |
| Video / episode progress | Sync kind `video` (`{t, d}`) | Sync pull |
| Ebook position | Sync kind `bookpos` (`{cfi, f}`), keyed by Dropbox content hash | Sync pull |
| Dropbox sign-in | Sync kind `dropbox` (`appKey`, `refreshToken`) | Sync pull → reuse token, no new sign-in |
| **Article bodies** | **Not synced.** Each device fetches feeds itself and keeps items in IndexedDB (Dexie `items`) | feeder-mcp **fetches the feeds itself** from the synced feed list (reusing `feedparse.ts` + Readability for full text) |
| Podcast transcripts | Feed `<podcast:transcript>` (VTT/SRT/JSON), or Whisper run in the browser and kept in the browser's IndexedDB `feeder-transcripts` | Feed transcripts fetched directly (reuse `transcriptFormats.ts`). Whisper-made ones: see 2.3 |
| YouTube transcripts | Android fetches captions; web skips them | `yt-dlp --write-subs --write-auto-subs --skip-download` (yt-dlp is already on the Mac) |
| Ebooks | Dropbox `/Ebooks` in the app folder; id = Dropbox content hash | Dropbox API with the synced refresh token; cached under the server's data folder |
| ZIM files | Dropbox `/ZIM` | Same; read with the existing pure-TS `zim/zim.ts` reader |

### 2.1 The sync server is E2E encrypted

- `POST /api/v2/sync` on Vercel/Neon stores `{id, ts, data}` records. `id` is an HMAC of kind+key, `data` is AES-128-CBC + HMAC-SHA256 (`crypto.ts`). The server never sees the key.
- feeder-mcp acts as **one more sync device**: it derives `account`/`auth` from the sync code + secret key (`deriveKeys` in `sync2.ts`), pulls with `since = cursor, push = []`, and decrypts each record to `{k, key, v, a?}` locally.
- The sync code and secret key are stored in the **macOS Keychain** (`security add-generic-password -s feeder-mcp`), entered once by `feeder-mcp setup` from the "add device" link. They never go in a config file or the MCP client config.
- v1 never pushes, so it can't change anything on other devices.

### 2.2 Why not read the Mac app's or browser's storage directly

- The Electron app keeps data in Chromium IndexedDB (LevelDB under `~/Library/Application Support/Feeder/`). Its on-disk format is Chromium-internal and changes between versions, and the app holds a lock on it while running. Too fragile.
- Everything that matters is either synced or re-fetchable, so feeder-mcp builds its own index and doesn't depend on the app running.

### 2.3 Gaps (decide later, not blocking v1)

- **Whisper transcripts made in the browser** stay in that browser. Options: (a) feeder-mcp runs `whisper.cpp` on demand for an episode the agent asks about (slow, but local); (b) the Mac app exports them to a folder feeder-mcp reads. **[default: (a), behind a `transcribe: true` flag, v1.1]**
- **Articles older than the feed's window** (the feed only lists its last N items): feeder-mcp only knows items it has seen since it started, plus Saved/highlighted ones via their synced copies (title, link, snippet), whose full text it fetches from `link` on demand.
- **MOBI/AZW3** ebooks: v1 reads EPUB and PDF. Others through Calibre's `ebook-convert` if installed, else listed but not readable.

---

## 3. Architecture

```
Claude Desktop / Claude Code
        │  MCP over stdio
        ▼
feeder-mcp (Node 22, TypeScript)
  ├─ tools / resources / prompts  (@modelcontextprotocol/sdk)
  ├─ query layer  ── SQLite (better-sqlite3) + FTS5 + sqlite-vec
  └─ indexer (background, in-process)
       ├─ sync puller  ──▶ POST feeder-web…vercel.app/api/v2/sync  (decrypt locally)
       ├─ feed fetcher ──▶ RSS/Atom/JSON feeds, Readability full text
       ├─ transcripts  ──▶ podcast:transcript files, yt-dlp subtitles
       ├─ Dropbox      ──▶ /Ebooks, /ZIM  (refresh token from sync)
       └─ embedder     ──▶ local model (transformers.js), no network
```

- **Code home:** `web/mcp/` in the Feeder fork, so it reuses `crypto.ts`, the sync2 key/payload code, `feedparse.ts`, `transcriptFormats.ts`, `playlists.ts` rules and `zim/zim.ts`. Small refactor needed: split the DOM-free parts of `sync2.ts` (types, `deriveKeys`, `recordId`, payload decode) into `sync2-core.ts`, and give `feedparse.ts`/`transcriptFormats.ts` an injectable `DOMParser` (linkedom in Node).
- **Data folder:** `~/Library/Application Support/feeder-mcp/` → `index.db`, `books/`, `zim/` (or symlinks to an existing local copy), `models/`.
- **Indexer schedule:** sync pull every 5 min, feed refresh every 30 min, Dropbox listing every 30 min, embeddings as a background queue. A `feeder_refresh` tool forces one now.
- Ebooks and transcripts are fetched and chunked **lazily** the first time they're asked for, then kept. Articles are chunked as they arrive.

---

## 4. Data model (SQLite)

```sql
feeds(url PK, title, custom_title, folder, site_url, kind /*article|podcast|youtube*/, modified_at)
items(id PK /* feedUrl\nguid, same as Feeder */, feed_url, guid, title, link, author,
      published_at, kind, duration_s, word_count, enclosure_url,
      read INT, saved INT, hidden INT, progress_s, has_full_text INT, has_transcript INT)
playlists(id PK, name, icon, smart INT, rules_json, item_ids_json, sort, modified_at)
highlights(id PK, item_id, item_title, feed_title, link, text, note, color, prefix, cfi, created_at, modified_at)
books(id PK /* Dropbox content hash */, path, title, author, ext, size, position_f, position_cfi, toc_json)
zims(id PK, path, title, language, article_count, has_fulltext INT)
documents(doc_id PK, source /*item|transcript|book|zim|highlight*/, ref_id, title, meta_json, text_hash)
chunks(chunk_id PK, doc_id, seq, text, tokens, locator_json, heading_path)
chunks_fts USING fts5(text, title, heading_path, content='chunks', tokenize='porter unicode61')
chunks_vec USING vec0(embedding float[384])
sync_state(key PK, value)  -- cursor, last pulls
```

- Ids stay Feeder's own (`feedUrl\nguid`, highlight id, Dropbox content hash), so the agent can hand them back to tools and later v2 writes map 1:1 to sync records.
- In tool I/O, ids are **opaque base64url strings** (`it_…`, `bk_…`, `pl_…`, `hl_…`), mapped to Feeder ids in the db. Avoids newline and URL escaping trouble in JSON.

---

## 5. Chunking per content type

Targets: ~500 tokens per chunk (range 300–800), 1 sentence overlap for prose, never split mid-sentence. Every chunk carries a **locator** so the agent can cite and jump back.

| Type | Text source | Split on | Locator | Chunk header (prepended for search + display) |
|---|---|---|---|---|
| Article | Full text (Readability) if fetched, else feed content; HTML → Markdown | `h1–h3` sections, then paragraphs | `{item, para_start, para_end}` | `Title — Feed (date)` + heading path |
| Podcast transcript | Feed transcript cues or Whisper | Cue boundaries, ~2–3 min windows, prefer speaker changes and pauses | `{item, start_ms, end_ms}` | `Episode — Podcast` + `[12:30–15:10]` |
| YouTube transcript | yt-dlp VTT (manual subs first, auto subs fallback), deduped rolling captions | Same as podcast | `{item, start_ms, end_ms}` + `&t=` link | `Video — Channel` + timestamps |
| Highlight | Highlight text + note | One chunk each (never split) | `{highlight, item}` | `Highlight in Title — Feed` + note |
| Ebook | EPUB spine docs (XHTML → Markdown); PDF via pdfjs page text | Chapter (TOC entry) → sections → ~500-token pieces | `{book, chapter_index, toc_label, spine_href, char_start}` | `Book — Author › Chapter 3: Title` |
| ZIM article | Article HTML → Markdown | Sections (`h2/h3`), then paragraphs | `{zim, path, section}` | `Article — ZIM title` |
| Feed / folder / playlist | Metadata only | Not chunked; returned by list tools | — | — |

### Ebook chapters (for U2)

- The TOC comes from EPUB 3 `nav` or EPUB 2 `toc.ncx`; PDF from its outline, else fixed 20-page "parts".
- Each top-level TOC entry gets `index` (1-based), `label`, `kind`: `front` | `chapter` | `back`. Front matter is detected by label/filename (`cover`, `title page`, `copyright`, `contents`, `dedication`, `epigraph`, `foreword`, `preface`, `acknowledg…`) and by `epub:type` (`frontmatter`, `toc`, `cover`, …).
- **"Chapter N" means the Nth `kind=chapter` entry** by default, so "first three chapters" skips the cover, copyright and contents. The tool says which TOC labels it mapped to, so the agent can correct course ("Chapter 1" might be labelled "Part One"); `include_front_matter: true` switches to raw TOC order.
- Ebook highlights (by `cfi`) and the reading position (`bookpos.f`) are attached to the chapter they fall in.

### Large ZIMs

- Wikipedia-size ZIMs are 50–100 GB: no full-text indexing. v1 indexes **titles only** (from the ZIM's title list) and chunks an article when it's read. A ZIM under 1 GB can be fully indexed if asked (`feeder-mcp index-zim <name>`).
- ZIMs stay in Dropbox and are read with HTTP range requests (`zim.ts` only reads the header, directory entries and needed clusters), or from a local copy if one exists (e.g. `~/Zimit/…`).

---

## 6. Search and indexing

- **Hybrid [default]:** SQLite FTS5 (BM25) + vector search via `sqlite-vec`, merged with reciprocal rank fusion (k = 60). Keyword finds names and exact phrases; vectors find "things about X" worded differently.
- **Embeddings, local:** `bge-small-en-v1.5` (384-d) through `@huggingface/transformers` in Node (already a dependency of the web app), ~35 MB, runs on CPU. Nothing leaves the Mac. Alternative if Ollama is installed: `nomic-embed-text` (768-d) — switchable in config, reindex required.
- **What gets embedded:** article chunks, transcript chunks, highlights, ebook chunks once a book is opened (or all books with `feeder-mcp index-books`). ZIM: titles only.
- **Rough size:** 5,000 articles × ~4 chunks = 20k vectors ≈ 30 MB. Embedding 20k chunks on an M-series CPU is a few minutes at first run, then incremental.
- **Filters on every search:** `kinds`, `feeds`, `folders`, `playlist`, `book`, `since`/`until`, `read`, `saved`, `highlighted_only`.
- **Freshness:** `text_hash` per document, so a re-fetched article only re-embeds when its text changed. Items marked `hidden` are dropped.

---

## 7. MCP surface

### 7.1 Tools

All tools are read-only in v1 (annotated `readOnlyHint: true`). Long results use **`max_tokens` + `cursor`** paging: each call returns at most `max_tokens` (default 6,000, max 20,000) of text and a `next_cursor` when there's more. Text is Markdown. Every chunk returned has `{chunk_id, doc_title, locator, text}` plus a `cite` string such as `Title — Feed, 12:30` or `Book › Ch. 2`.

| Tool | Purpose | Input (JSON Schema, abbreviated) | Output |
|---|---|---|---|
| `search` | Hybrid search across everything | `query: string`, `kinds?: ("article"\|"podcast"\|"youtube"\|"highlight"\|"book"\|"zim")[]`, `feeds?: string[]`, `folders?: string[]`, `playlist?: string`, `book?: string`, `since?: date`, `until?: date`, `read?: bool`, `saved?: bool`, `mode?: "hybrid"\|"keyword"\|"semantic"`, `limit?: int ≤50 (10)` | `hits: [{chunk_id, doc_id, kind, title, feed, published_at, score, snippet, locator, cite}]` |
| `list_feeds` | Feeds and folders | `folder?: string`, `kind?: …`, `with_counts?: bool` | `folders: [{name, feeds: [{id, title, kind, unread, total}]}]` |
| `list_items` | Browse items | `feed?`, `folder?`, `kinds?`, `read?`, `saved?`, `since?`, `until?`, `sort?: newest\|oldest\|shortest\|longest`, `limit?`, `cursor?` | `items: [{id, title, feed, kind, published_at, minutes, read, saved, progress}]`, `next_cursor` |
| `get_item` | One item's full text, paged | `id: string`, `prefer?: "full_text"\|"feed"\|"transcript"`, `max_tokens?`, `cursor?` | `{item: {...meta}, chunks: [...], next_cursor}` — podcast/video items return the transcript; if none exists, says so and offers `transcribe` (v1.1) |
| `list_playlists` | Playlists | — | `playlists: [{id, name, icon, smart, item_count, minutes}]` |
| `get_playlist` | Playlist items in order (U1) | `playlist: string` (id or fuzzy name), `limit?: int (50)`, `statuses?: ("unread"\|"inprogress"\|"read")[]` | `{playlist, resolved_from, items: [{id, title, feed, kind, minutes, status, has_text}]}` |
| `get_playlist_content` | The text of a playlist's items, ordered, paged (U1) | `playlist: string`, `per_item_tokens?: int (1,500)`, `max_tokens?`, `cursor?`, `item_ids?: string[]` | `{playlist, sections: [{item, chunks}], next_cursor}` — `per_item_tokens` caps each item (first chunks, then "…truncated, call get_item for the rest") so one long episode doesn't eat the budget |
| `list_books` | Ebook library | `query?: string` | `books: [{id, title, author, ext, progress_pct, readable}]` |
| `get_book_toc` | Chapters (U2) | `book: string` (id or fuzzy title) | `{book, resolved_from, toc: [{index, chapter_no?, label, kind, tokens, has_highlights}]}` |
| `read_book` | Chapter text, paged (U2) | `book: string`, `chapters: string` (`"1-3"`, `"2,5"`, `"Chapter 4"`), `include_front_matter?: bool (false)`, `max_tokens?`, `cursor?` | `{book, mapped: [{chapter_no, label}], chunks: [...], next_cursor}` |
| `list_highlights` | Highlights + notes (U3) | `item?`, `book?`, `feed?`, `color?`, `has_note?`, `since?`, `query?`, `limit?`, `cursor?` | `highlights: [{id, text, note, color, source_title, feed, cite, created_at}]` |
| `list_zims` | ZIM files | — | `zims: [{id, title, language, articles, fulltext_indexed}]` |
| `zim_lookup` | Find articles in a ZIM by title (U5) | `zim?: string`, `query: string`, `limit?` | `hits: [{zim, path, title}]` |
| `zim_read` | Read a ZIM article | `zim: string`, `path: string`, `max_tokens?`, `cursor?` | `{title, chunks, next_cursor}` |
| `status` | Index health | — | last sync, items, chunks, embedded %, Dropbox ok, errors |
| `refresh` | Pull sync / refetch now | `what?: ("sync"\|"feeds"\|"dropbox")[]` | what changed |

Name resolution (`playlist`, `book`, `feed`): exact id → exact name → case-insensitive → fuzzy (trigram). When more than one fits closely, the tool returns `ambiguous: [{id, name}]` instead of guessing, and the agent asks.

### 7.2 Resources

Readable by URI so a client can attach them (Claude Desktop's "+" menu) without a tool call:

- `feeder://item/{id}` — item as Markdown (meta header + text/transcript)
- `feeder://playlist/{id}` — playlist as a Markdown list of items with links
- `feeder://book/{id}/toc` and `feeder://book/{id}/chapter/{n}`
- `feeder://highlights` (recent 200) and `feeder://highlights/{item_or_book_id}`
- `feeder://zim/{id}/{path}`
- Resource templates are listed so clients can complete ids. `resources/list` returns playlists, the 50 most recent Saved items and recently opened books, not the whole library.

### 7.3 Prompts

| Prompt | Arguments | What it sets up |
|---|---|---|
| `summarize_playlist` (U1) | `playlist`, `style?: brief\|detailed (brief)`, `only_unread?: bool` | Calls `get_playlist_content` page by page, writes a 2–4 line summary per item with its cite, then a short "themes across the playlist" section. Map-reduce when the content is bigger than one page. |
| `summarize_book_chapters` (U2) | `book`, `chapters (e.g. "1-3")`, `depth?: brief\|detailed` | `get_book_toc` → confirm the chapter mapping in one line → `read_book` page by page → per-chapter summary + key ideas, plus Ashish's highlights in those chapters. |
| `unread_digest` | `folder?`, `since? (1 day)` | Groups unread items by topic, one line each, top 5 worth reading. |
| `highlights_review` | `since?`, `topic?` | Gathers highlights + notes, groups them by theme, quotes them with cites. |
| `ask_my_library` | `question` | `search` first, then `get_item`/`read_book` on the top hits, answer with cites only from Feeder content. |

---

## 8. Auth and privacy

- **Local (v1):** stdio, launched by the client as a child process. No network listener, so no auth on the MCP side.
- **Secrets:** sync code + secret key in Keychain; the Dropbox refresh token comes from the decrypted `dropbox` record and is kept only in memory (plus the short-lived access token).
- **At rest:** `index.db` holds decrypted article text and highlights in the user's Library folder. FileVault covers it. Optional: SQLCipher with the key in Keychain **[default: off]**.
- **To the model:** whatever the agent reads goes to the model provider as part of the conversation. That's inherent to the use case; the server never sends anything anywhere else (only outbound calls: Feeder sync, the feeds themselves, Dropbox, YouTube via yt-dlp).
- **Remote (v2):** see section 10. Never expose the stdio server directly.

---

## 9. How the agent (client) uses it

- **Claude Desktop** — `~/Library/Application Support/Claude/claude_desktop_config.json`:

  ```json
  { "mcpServers": { "feeder": { "command": "/usr/local/bin/node",
      "args": ["/path/to/Feeder/web/mcp/dist/index.js"] } } }
  ```
- **Claude Code** — `claude mcp add feeder -s user -- /usr/local/bin/node …/web/mcp/dist/index.js`
- Uses Node 22+ from `/usr/local/bin` (not nvm 20), same as the Mac app build.
- **Typical flows:**
  - U1: `/summarize_playlist playlist="Morning drive"` → `get_playlist` → `get_playlist_content` (2–3 pages) → summary.
  - U2: `/summarize_book_chapters book="Thinking Fast" chapters="1-3"` → `get_book_toc` → `read_book` (paged) → summary.
  - Free questions: the agent calls `search` with filters, then reads the hits.
- **Context budget:** a three-chapter read is ~30–60k tokens. Paging keeps each tool result under `max_tokens`, and the prompts tell the agent to summarize each page before fetching the next (map-reduce), so it works in a normal context window.
- A custom agent (Agent SDK, LangGraph, …) connects the same way over stdio; nothing in the server is Claude-specific.

---

## 10. Phases

| Phase | Scope |
|---|---|
| **v1** | Sync pull + decrypt, feed fetching, articles + feed/podcast transcripts + YouTube subs (yt-dlp), Saved, highlights, playlists (manual + smart), ebooks EPUB/PDF with chapters, ZIM title lookup + read, hybrid search, tools/resources/prompts above, stdio, `setup`/`status` CLI |
| v1.1 | Whisper transcription on demand (`whisper.cpp`), MOBI/AZW3 via Calibre, full-text index for small ZIMs |
| v2 | Write-back through sync: `mark_read`, `save_item`, `add_highlight`, `add_to_playlist` (encrypt with the same key, `ts = now`, last writer wins; confirm before each write) |
| v2 | Remote: Streamable HTTP on the Mac behind Tailscale (tailnet only) with a bearer token, or OAuth if it ever goes on the public internet. The index stays on the Mac |

---

## 11. How to run (once built)

```bash
cd ~/Developer/Feeder-master/web/mcp && npm install && npm run build
node dist/cli.js setup          # paste the "add device" link → Keychain; first sync pull
node dist/cli.js index          # first feed fetch + embeddings (a few minutes)
node dist/cli.js status
```

Then add it to Claude Desktop / Claude Code as in section 9. The indexer keeps running inside the server process while the client is open; `node dist/cli.js index --watch` runs it as a LaunchAgent if you want the index fresh even when no client is open **[default: off]**.

### Testing

- Unit: sync decrypt against fixtures made with `encryptString`, chunkers per type, chapter mapping on 5 real EPUBs (fiction, non-fiction with Parts, one with no NCX), smart-playlist parity with `playlists.ts` tests.
- Integration: `MemoryStore` sync server from `server/sync2.ts` seeded with records → full index → MCP Inspector (`npx @modelcontextprotocol/inspector`) run of every tool.
- Acceptance: U1 and U2 run end to end in Claude Desktop on real data.

---

## 12. Open questions for Ashish

1. Agent: Claude Desktop + Claude Code, or a custom one? (affects only section 9)
2. Local only for now, or do you need phone/cloud access soon? (pulls v2 remote forward)
3. Read-only v1 OK, or is one write (e.g. mark read) needed from day one?
4. Hybrid search with a local embedding model OK, or keyword only to keep it simple?
5. Where should the code live: `web/mcp/` in the Feeder fork **[default]**, or a separate repo?
6. Which ZIMs do you actually use, and are they local (`~/Zimit`) or only in Dropbox?
