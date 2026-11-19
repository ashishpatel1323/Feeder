# Manage feeds page (web) — spec

## Goal
One page to see and organise every folder (tag) and feed: add, unsubscribe, rename, move between folders, and reorder. Every action is a visible button on the row. Nothing hides in a ⋮ overflow menu.

## Where it lives
- A full page at `#/feeds` that replaces the article list and reader. The sidebar stays, so you can drag feeds onto sidebar folders too.
- You get there from a new "Manage feeds" button (tune icon) in the sidebar top bar and rail, and from a link in Settings. The back arrow (or Esc) returns to the articles.
- The existing pencil on sidebar rows keeps opening the Edit feed dialog.

## Layout options
- **A. One list grouped by folder (recommended).** Shows every folder as a card with its feeds under it, Inoreader style. Best for scanning everything at once and dragging across folders. Screens: `A-list-clean.png`, `A-list-active.png`, `A-mobile.png`.
- **B. Two panes.** Folders on the left (counts, rename and delete on each row, "New folder" box at the bottom), the selected folder's feeds on the right. Better if you have many folders. Screen: `B-two-pane.png`.

## Page parts (both layouts)
- **Header:** back, title, counts, Import OPML, Export OPML (moved here from Settings; Settings keeps them too).
- **Add bar, always visible:** URL or website box + folder picker + "Add feed". Uses the existing `addFeed(input, tag)`, which also finds the feed on a website page. Each folder header also has "+ Add feed", which pre-selects that folder.
- **Tools:** a filter box (name or URL), "New folder", and "Only feeds with errors".
- **Feed row:** drag handle · checkbox · icon · title and URL · folder dropdown · **Opens in** dropdown (Default / Reader / Browser tab) · **Full article** switch (fetch the full article instead of the RSS text) · status (last update, or the error in red) · unread count · **Rename** (edits inline, Enter saves, Esc cancels) · **Feed options** (opens the existing Edit feed dialog for full text, filters, and so on) · **Unsubscribe** (asks to confirm).
- **Folder header:** drag handle · name · counts · + Add feed · **Rename** (inline) · **Delete**. Delete asks: "Move its N feeds to No folder" or "Unsubscribe from all N".
- **Bulk bar:** appears when any box is ticked. "N selected · Move to [folder] · Unsubscribe · Clear". The header checkbox selects all visible rows.
- **Drag and drop:** reuses PR #49 (`moveFeedToTag`, `placeInOrder`, `feedOrder`). Drop on a row to place the feed before or after it (a line shows where). Drop on a folder header or card to move the feed to the end of that folder. Folders can also be dragged to reorder them.
- **Mobile:** rows stack (title, URL, folder dropdown) and the actions stay as visible icons. Long press on the handle drags.

## Sync chain behaviour
The chain syncs feeds (EncryptedFeed: url, title, customTitle, tag, settings, whenModified). Folders are not their own objects; a folder is just a tag on its feeds.

| Action | Data change | Syncs to phone? |
|---|---|---|
| Add feed | new Feed row | Yes |
| Unsubscribe | delete feed + items (existing `deleteFeed`) | Yes (removed from the chain feed list) |
| Opens in | `openArticlesWith` ("" default, "0" reader, "2" browser; Android's "1" web view and "3" custom tab show as Browser tab and are kept unless you change them), bump `whenModified`. The web reader will start honouring it. | Yes |
| Full article switch | `fullTextByDefault`, bump `whenModified` | Yes |
| Rename feed | `customTitle`, bump `whenModified` | Yes |
| Move feed to folder | `tag`, bump `whenModified` (existing) | Yes |
| Rename folder | set `tag` on all its feeds, bump each `whenModified`, in one transaction. Renaming to an existing name merges the two. | Yes |
| Delete folder (keep feeds) | `tag = ""` on its feeds | Yes |
| New empty folder | kv `emptyFolders` | No, until a feed is moved in (the chain has no empty folders) |
| Reorder feeds | kv `feedOrder` (existing) | No, local to this browser |
| Reorder folders | new kv `folderOrder`; folders without a position stay alphabetical after the ones you placed | No, local to this browser |

All changes call the existing sync trigger after they write. The page shows a one-line note that order and empty folders stay in this browser.

## Out of scope
- Syncing order to Android (needs a new field in the chain format and an app change).
- Nested folders.

## Build plan (after go-ahead)
1. `lib/db.ts`: `renameFeed`, `renameTag`, `deleteTag`, `folderOrder` and `emptyFolders` helpers, plus unit tests next to `move-feed.test.ts`.
2. `components/ManageFeeds.tsx` and styles. Pull the drag logic out of `Sidebar.tsx` into a shared hook so both use it.
3. Hash route and the entry buttons in the sidebar and Settings.
4. Have the sidebar use `folderOrder` so both views match.
5. Tests, `npm run build`, check in a browser on 5173, then open the PR.
