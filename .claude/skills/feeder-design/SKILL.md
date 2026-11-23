---
name: feeder-design
description: Feeder's design system for the web app (web/src) and Android app (ui/compose). Use for any UI change, new screen, button, chip, dialog, colour, spacing or typography work in Feeder, and before opening a PR that touches styles.css or Compose UI.
---

# Feeder design system

Feeder is one product on two platforms. Web and Android share the same green palette, type scale,
corner sizes and control shapes. The frontend-design plugin is enabled for this repo; use its
self-critique habits, but these rules win where they differ.

## Ashish's rules (revised Nov 2026, PR after #96)

Feeder follows Material 3 (and Material 3 Expressive on Android). These rules replace the old
"every action visible and labeled everywhere" rule, which crowded list rows with six buttons.

1. **Lists are for scanning.** A list row shows the thumbnail, a two-line title, one meta line and
   at most **one** action: the main one (Play/Resume for episodes and videos, as a round tonal icon
   button with a tooltip and content description). Articles have no row button: tapping the row reads.
2. **Secondary row actions are gestures, not buttons**: swipe toward the end = Save, toward the start
   = Mark read; long press = multi-select, whose bar has labeled bulk actions (Delete lives there).
   Every gesture action is also a TalkBack custom action. Never put Delete on every row.
3. **Labels where there is room and focus**: the article/episode screen, the selection bar, the
   Now playing sheet and dialogs keep icon + text buttons. Don't hide a label to fit a phone there; wrap.
4. **One primary (filled) action per view.** Everything else is tonal, outlined or text.
5. **Overflow (⋮) menus** only for rare or destructive actions that have no other home (the player's
   Stop and close, the feed's settings). Never for an item's everyday actions.
6. Keyboard hints stay in tooltips and, on wide screens, as `.btn-kbd` next to the label (web).

## Media players (Android and web)

Follow the Material 3 media pattern, as Google Podcasts / YouTube Music do:
- **Mini player** docked above the bottom edge: rounded `surfaceContainerHigh` card, thin progress
  line on top, artwork, one-line title + feed and time, back 10 s, and a large play/pause. Tapping it
  opens the **Now playing** sheet.
- **Now playing sheet**: collapse chevron, ⋮ menu, large artwork (28 dp corners), title, a seek bar
  with a wavy played part while playing (`LinearWavyProgressIndicator` as the `Slider` track),
  round back 10 / forward 30 buttons around a large play/pause whose corners tighten while playing,
  then labeled Speed, Sleep and Transcript buttons.
- In-page episode blocks show artwork, progress and one full-width Play/Resume/Pause button only.
  No flat grey bars of small icon buttons, and no Stop button in the main controls.

## Web tokens (`web/src/styles.css`, top of `:root`)

| Use | Token |
|---|---|
| Badges, key hints | `--fs-2xs` 11px |
| Meta, captions | `--fs-xs` 12px |
| Chips, secondary text, section labels | `--fs-sm` 13px |
| Buttons, controls | `--fs-md` 14px |
| Body | `--fs-base` 15px |
| Section titles | `--fs-lg` 18px |
| Page titles (h1) | `--fs-xl` 22px, weight 700 |
| Article titles | `--fs-2xl` 26px |
| Radius: inputs/buttons, cards, panels, dialogs, chips | `--r-sm` 8, `--r-md` 12, `--r-lg` 16, `--r-xl` 24, `--r-pill` |
| Spacing | `--sp-1`..`--sp-6` = 4, 8, 12, 16, 24, 32 |
| Heights | `--control-h` 36 (buttons, inputs, segmented), `--chip-h` 32 |
| Fonts | `--font-ui` for interface, `--font-read` (serif) for article titles/text only, `--font-mono` |
| Colours | `--bg --surface --surface-2 --surface-3 --text --text-2 --text-3 --border --accent --accent-ink --accent-soft --danger --disabled-bg --disabled-ink` |

Rules:
- No raw px font sizes, radii or hex colours in new rules. Only weights 500, 600, 700.
- Every colour token has a dark value; never hard-code a light-only colour. Check both themes.
- `--text-3` is the lightest text allowed on `--bg`/`--surface-2` (WCAG AA). On `--accent-soft`, use `--text-2` or darker.

## Web components (reuse, don't re-invent)

The "design system" block at the end of `styles.css` normalises these; new screens use the classes directly:
- **Buttons**: `.mf-btn` (outlined), `.mf-btn.primary` (filled accent), `.mf-btn.ghost`, `.mf-btn.danger`. One per view is primary. Disabled = flat grey (`--disabled-bg`), not faded green.
- **Filter chips**: `.chip` / `.home-chip` (pill, `--chip-h`), selected `.on` = `--accent-soft` + 600 weight.
- **Segmented buttons** (one exclusive choice): `.segmented > .seg` or `.home-seg > button`, one outlined pill with dividers, selected `.on` = `--accent-soft`.
- **Page header**: `.mf-head` with `h1` + optional `.mf-meta`, actions on the right, divider below. Sidebar destinations have no close (X) button.
- **Section labels**: sentence case, `--fs-sm` 600 `--text-2`. Never `text-transform: uppercase`.
- **Toasts** use `--surface-3`, not black.

## Android (`ui/compose`)

- Check new UI against the official Material 3 guidance (m3.material.io: lists, buttons, icon
  buttons, bottom sheets, progress indicators), not only these tokens. Material3 1.5 has the
  Expressive parts (`LinearWavyProgressIndicator`, shape morphing): use them for media.
- Rows use `ui/compose/item/ItemRow.kt`; the player is `ui/compose/feedarticle/PodcastPlayerBar.kt`.

- Text: `MaterialTheme.typography` roles only (`labelSmall` 11, `bodySmall` 12, `titleSmall` section labels, `bodyMedium` 14, `bodyLarge` body, `titleMedium`/`titleLarge` titles). Never `fontSize = N.sp` except artwork tiles and car mode.
- Spacing and corners: `FeederTokens.Space.*`, `FeederTokens.Radius.*` (same values as web).
- Selected `FilterChip` / `SegmentedButton`: default colours (secondaryContainer). Don't override to `primary`.
- Colours from `MaterialTheme.colorScheme`; no `Color(0x…)` outside theme files and artwork tiles.
- Section labels sentence case: no `.uppercase()`.

## Before you finish a UI change

1. Web: run the dev server on a spare port (not 5173) and screenshot light + dark at 1440×900 and 390×844. Check nothing overflows and the rules above hold (one row action, labels on detail screens).
2. Run axe-core (wcag2a, wcag2aa) on the changed page: no new `color-contrast` or `button-name` violations.
3. Web: `npm test` and `npm run build` in `web/` (Node 24 at /usr/local/bin).
4. Android: `./gradlew :app:ktlintMainSourceSetCheck` for touched files, then install on the emulator and screenshot the screen.
5. Put before/after screenshots in the PR.
