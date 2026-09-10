# 02 — Design system and UI

Design goal: **reproduce the style of the RomM web app as faithfully as possible**, while fixing its weak spot, adaptation to unusual form factors. So: colours, typography and components are copied; the **layout is redesigned** to be truly responsive.

## 1. Colour tokens (extracted from `rommapp/romm`, `frontend/src/styles/themes.ts` — use these exact values)

### Dark theme (app default)

| Token | Hex | Use |
|---|---|---|
| `primary` | `#8B74E8` | main accent, focus, actions |
| `primary-lighten` | `#A18FFF` | focus ring, hover, active states |
| `primary-darken` | `#6043C8` | pressed, fills |
| `secondary` | `#9E8CD6` | secondary accent |
| `secondary-lighten` | `#EBE7FA` | text on accented surfaces |
| `secondary-darken` | `#7A6BB4` | |
| `accent` | `#E1A38D` | rare highlights (special badges) |
| `background` | `#0D1117` | screen background |
| `surface` | `#161B22` | cards, bars |
| `toplayer` | `#1C2330` | dialogs, menus, bottom sheets, download mini-bar |

### Light theme (optional, same structure)

`primary #371F69` · `secondary #553E98` · `accent #E1A38D` · `background #F2F4F8` · `surface #FFFFFF` · `toplayer #E4E9F0` · `primary-lighten #7850E6` · `primary-darken #452788`.

### Semantic colours shared by both themes

`romm-red #DA3633` (errors, failed downloads) · `romm-green #3FB950` (completed, already on the device) · `romm-blue #0070F3` (info, in progress) · `romm-white #FEFDFE` · `romm-gray #5D5D5D` (disabled text, borders) · `romm-black #000000` · `romm-gold #FFD700` (favourites).

Implementation: a Material 3 `ColorScheme` built by hand from these values (do not use dynamic color/Monet: it breaks the RomM identity). `toplayer` does not exist in M3; expose it as an extra token via `CompositionLocal`.

## 2. Typography

RomM uses **Roboto** (variable, weights 100–900): it is the Android system font, so `FontFamily.Default` with the right weights. No custom fonts to bundle.

| Role | Size / weight | Notes |
|---|---|---|
| Screen title | 20 sp / Medium | 18 sp if `heightDp < 480` |
| Section title | 16 sp / Medium | |
| Game name (card) | 13 sp / Normal, max 2 lines, ellipsis | as in RomM: below the cover, centred |
| Game name (list) | 15 sp / Normal | |
| Secondary metadata | 12 sp / Normal, opacity 0.7 | |
| Badge | 10 sp / Medium, uppercase | |

Honour the system `fontScale` up to 1.3; beyond that, compress secondary text only.

## 3. Shape, spacing, effects

- Radii: cover card **8 dp**, platform card **12 dp**, dialog **16 dp**, chip/badge **6 dp**, status pill **999 dp**.
- 4 dp spacing grid; standard padding 12 dp (8 dp if `heightDp < 480`).
- Elevation simulated with surfaces, not with heavy shadows: `surface` on `background`, `toplayer` on `surface`.
- **Focus scale 1.07** with a 100 ms transition: this is exactly RomM's `.transform-scale:hover/focus` rule (`common.css`), applied here to gamepad focus.
- Text over a cover always gets a `1px 1px 3px #000` shadow (RomM's `.text-shadow` rule) or sits on a `translucent` band (50% black).

## 4. Components

### 4.1 PlatformCard
`surface` card, centred platform icon (from `{base}/assets/platforms/{slug}.ico`, generic icon as fallback), name below (use `display_name` or `custom_name`, **never `name` alone**: custom platforms would show the wrong name), ROM-count pill in the top right corner. Card ratio 1:1. On focus: scale 1.07 plus a 2 dp `primary-lighten` ring.

### 4.2 GameCard (grid)
Cover with a **1 : 1.4** ratio (width : height), `ContentScale.Crop`, 8 dp radius, skeleton while loading, name below on 2 lines. Overlaid badges:

| Position | Badge |
|---|---|
| top left | region (`US`/`EU`/`JP`) as in the web app |
| top right | platform icon (only in multi-platform views) |
| bottom right | number of versions (siblings) if greater than 1 |
| centre overlay | status: already on the device / downloading with progress ring / paused / failed |

### 4.3 GameRow (list)
Height 56 dp: 40×56 thumbnail, name, secondary line with `platform · region · size`, status on the right. Needed when density matters (lists of thousands of titles).

### 4.4 AlphabetRail — the alphabet rail
Vertical column on the right, width 28 dp (32 dp if `widthDp >= 600`), entries `# A B … Z @`, current letter highlighted with a `primary` pill: identical to the web app screenshot.

- Data source: **`char_index` from the `/api/roms` response** (letter → offset map). Never compute it client-side on the current page: the library is paginated.
- Letters with no results sit at 30% opacity and are skipped during navigation.
- From the gamepad: **R2/L2** jump to the next/previous letter without moving focus onto the rail; the rail remains reachable with D-pad right from the rightmost column of the grid.
- From touch: tap and continuous drag with an enlarged preview of the letter.
- The jump sets the Paging offset and shows a large floating label (48 sp) in the centre for 600 ms.

### 4.5 DownloadMiniBar
`toplayer` bar, 44 dp tall, anchored above the navigation, visible only when the queue is not empty: file name (ellipsis), `primary` progress, percentage, speed, `+n` counter. Focusable. UI updates at most 2–4 times per second, speed averaged over 5 samples.

### 4.6 FilterBar
Row above the grid: sorting (`name`, `first_release_date`, `average_rating`, `fs_size_bytes`, `created_at`), filters (not downloaded only / downloaded only / region), grid-list toggle, search within the platform. On short screens it becomes an icon-only row with the details in a bottom sheet.

### 4.7 States
Every list implements four distinct states: **loading** (skeletons with the same geometry as the cards, no full-screen spinner), **empty** (icon, sentence, action), **error** (an understandable localised cause plus a "Retry" button already focused), **offline** (persistent banner, content from the cache).

## 5. Responsive layout

### 5.1 The problem to solve

Reasoning by width alone is not enough. A Retroid Pocket Classic is **472 × 411 dp** (1240×1080 at ~420 dpi): compact width but **an absurdly small height**. An RG Cube is about 360 × 360 dp. On these screens a 64 dp top bar plus an 80 dp bottom bar eat more than a third of the usable space. This is why classification happens on **two axes**.

### 5.2 Classes

```
widthClass  : COMPACT (<600dp) | MEDIUM (600-839) | EXPANDED (>=840)
heightClass : SHORT (<480dp)   | TALL (>=480)
shape       : ratio = widthDp / heightDp
              PORTRAIT (<0.9) | SQUARE (0.9-1.25) | LANDSCAPE (>1.25)
isTv        : UiModeManager.currentModeType == UI_MODE_TYPE_TELEVISION
```

### 5.3 Navigation rules

| Condition | Navigation | Top bar |
|---|---|---|
| COMPACT + TALL (portrait phone) | **BottomNavBar** 72 dp | 56 dp, title plus actions |
| `heightClass == SHORT` (RP Classic, RG Cube, landscape handhelds) | **NavigationRail** on the left, 56 dp, icons only, label only on the focused item | **40 dp**, title and 2 actions only |
| MEDIUM / EXPANDED | NavigationRail 80 dp with labels | 56 dp |
| `isTv` | NavigationRail 96 dp, focus scaled 1.1, 5% overscan margin | 48 dp |

The rail sits **on the left** because on handhelds the left thumb is on the D-pad: navigation between sections must be on the D-pad side, while the alphabet rail on the right is on the side of the action buttons and the right stick. This symmetry is why this arrangement beats Argosy's carousel.

### 5.4 Grids

Columns computed with `GridCells.Adaptive(minCell)`, never with fixed numbers:

| Context | `minCell` |
|---|---|
| covers, `widthDp < 400` | 104 dp |
| covers, 400–599 | 116 dp |
| covers, 600–839 | 128 dp |
| covers, >= 840 or TV | 148 dp |
| platforms | cover `minCell` plus 16 dp |

Corrections:
- if `heightClass == SHORT`, reduce `minCell` by 8 dp (more columns, less scrolling on a short screen);
- the space taken by the `AlphabetRail` is subtracted before computing the columns, never overlapped;
- hard limit: minimum 2 columns, maximum 8.

### 5.5 Two panes
With `widthClass == EXPANDED` and `shape == LANDSCAPE`, the platform screen uses two panes: platform list on the left (280 dp), game grid on the right. In every other case, full pages.

### 5.6 Insets and unusual screens
- Edge-to-edge is mandatory (Android 15 enforces it): `WindowInsets.safeDrawing` on every screen, never fixed padding for the status bar.
- **"Screen margin" setting, 0–16 dp**, for handhelds with heavily rounded corners or asymmetric bezels and for TV overscan.
- No dependency on the `hover` state: it does not exist on TV and handhelds.
- Test with the screen rotated too: portrait handhelds are used in landscape when docked.

## 6. Gamepad

### 6.1 Mapping

| Input | Action |
|---|---|
| D-pad / left stick | move focus (stick with 0.5 deadzone, repeat every 120 ms) |
| Right stick | continuous list scrolling |
| A (`BUTTON_A`) | confirm / open |
| B (`BUTTON_B`) | back |
| X (`BUTTON_X`) | **download** the focused item |
| Y (`BUTTON_Y`) | toggle grid / list |
| L1 / R1 | previous / next section (Platforms, Collections, Search) |
| L2 / R2 | previous / next letter (`char_index`) |
| L3 | open search |
| R3 | open the Downloads screen |
| Start | context menu for the focused item |
| Select | filters and sorting |

**"Swap A/B"** setting (Nintendo versus Xbox layout), Xbox by default, with device detection where possible. An input test screen showing the received keycodes is also needed: Chinese handhelds map buttons badly and users must be able to diagnose it on their own.

### 6.2 Focus rules

- Every interactive element is focusable and has a visible indicator: 2 dp `primary-lighten` ring plus scale 1.07. Never rely on colour alone.
- `Modifier.focusRestorer()` on every list: when going back, focus lands on the item that was left, never at the top.
- Focus must never end up in a dead end: explicit directional navigation (`focusProperties { left = ...; right = ... }`) between rail, content and alphabet rail.
- The focused item is always brought into view with at least one row of margin (`bringIntoViewRequester`).
- Held button: first delay 400 ms, then 60 ms; after 1.5 s of continuous pressing on the grid, switch to **page scrolling** instead of row scrolling.
- When a dialog or bottom sheet opens, focus enters it and leaves only with B.

### 6.3 Acceptance criterion
Acceptance requirement: **complete onboarding, download a game and open the queue without touching the screen**, on a device with physical controls only.
