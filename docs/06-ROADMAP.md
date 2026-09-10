# 06 — Milestones, acceptance criteria, testing

The order is designed to have something usable early and to avoid building the UI on top of the wrong storage foundations.

## M0 — Foundations

- Compose + Hilt + Room + DataStore project, `minSdk 26`, complete RomM theme (dark and light) with the tokens from doc 02.
- Network client: URL normalisation, `GET /api/heartbeat`, login (`POST /api/login`), `GET /api/users/me`, credentials in `EncryptedSharedPreferences`, `networkSecurityConfig` for cleartext LAN traffic.
- Custom two-axis `WindowSizeClass` (width and height) plus TV detection.
- Diagnostics screen and file logging.

**Done when:** you can log in to the server over HTTP and see the RomM version; rotating the device and using split screen produce no broken layouts.

## M1 — Library navigation

- Three sections: Platforms (grid with icons and counts), Collections, Search.
- Game grid with Paging 3, covers via Coil (`path_cover_*` first, `url_cover` as fallback), grid/list toggle.
- **AlphabetRail** fed by `char_index`, with L2/R2 jumps.
- Game detail with siblings resolved only there.
- Full gamepad navigation: visible focus, `focusRestorer`, the mapping from doc 02.

**Done when:** you can browse a 1,700-title platform without stutter or crashes, jump to a letter in under a second, and reach everything without touching the screen.

## M2 — Onboarding, launchers and folders

- 5-step wizard (doc 01), including QR pairing of the client API token.
- `FileGateway` with its two implementations, `MANAGE_EXTERNAL_STORAGE` request with SAF fallback and manual path.
- Loading of `platform_map.json`, automatic discovery of existing folders, editable mapping table.

**Done when:** on a device with ES-DE already configured, the app detects the existing folders on its own and shows the complete table without asking the user for anything beyond the root folder.

## M3 — Downloads

- Queue persisted in Room, Foreground Service with notification, WorkManager, configurable concurrency.
- `.part` written to private cache, hash verification, move, complete state machine, Range-based resume, real cancellation.
- Streaming zip extraction, `.7z` left intact by default, one folder per game on disc-based systems.
- `DownloadMiniBar` and Downloads screen.
- Local file index and "already on the device" badge on cards.

**Done when:** you download a multi-disc PSX game, the `.m3u` ends up in the right subfolder, ES-DE sees it on restart; killing the app mid-download and reopening it, the download resumes from where it left off.

## M4 — Polish

- Virtual collections (5 types, string id) and smart collections.
- Search with filters and sort orders, recent searches.
- Local library management: view and delete what has been downloaded, space used per platform.
- Italian and English localisation, light theme, "swap A/B" setting, input test screen.
- Android TV optimisation.

## M5 — Metadata for the frontend

- Writing `gamelist.xml` in ES-DE format, merging existing entries, atomic write, only while the frontend is closed.
- Cover download into `downloaded_media/<system>/covers/`.
- BIOS/firmware section with `RetroArch/system/` as destination and no silent overwrite.

## After v1 (not planned)

Collection sync to the device (Wi-Fi only, only while charging), save sync, Cloudflare Access as a top-level setting, "open with external app" after download (a plain `ACTION_VIEW`: it does not make us a frontend, but it is convenient), deep links, F-Droid publication.

## Cross-cutting definition of done

Applies to every milestone:

1. No crash on incomplete API records: nullable fields and defensive per-item rendering.
2. No UI blocking on I/O: everything on the IO dispatcher, sampled progress updates.
3. Every screen is fully navigable from the gamepad, with visible and restored focus.
4. Every screen behaves correctly in the four layout classes of the matrix below.
5. Every network or filesystem error has a localised message that says what happened and what to do.
6. No partial file left in the frontend folders, under any error scenario.

## Screen test matrix

| Profile | Resolution | Estimated dp | What to check |
|---|---|---|---|
| Retroid Pocket Classic | 1240×1080 @ ~420 dpi | ~472 × 411 | side rail, 40 dp top bar, at least 3 columns, readable alphabet rail |
| RG Cube (1:1) | 720×720 | ~360 × 360 | worst case: everything must fit, no truncated text in the rail |
| Landscape handheld (RP5, Odin 2) | 1920×1080 | ~640 × 360 | MEDIUM but SHORT class: rail plus wide grid, no bottom bar |
| Phone | 2400×1080 | ~393 × 873 | bottom bar, 2–3 columns, edge-to-edge insets |
| Tablet / dock | 2560×1600 | ~1280 × 800 | two panes, 6–8 columns |
| Android TV 1080p | 1920×1080 | 960 × 540 | overscan, no SAF picker, D-pad only |

Each one must also be tested with `fontScale` 1.3 and with a real library of roughly 18,000 ROMs across about 80 platforms.

## Open decisions

1. **Package name and app identity** — proposed `com.rommmobile.app`; to be confirmed, along with the icon and display name.
2. **Arcade**: a library built on the FBNeo bestset is the reference case. Preselect the `fbneo` folder when it exists, otherwise `arcade`: to be confirmed on first real use.
3. **`genesis` versus `megadrive`** and **`segacd` versus `megacd`**: the preset proposes the European variant; if ES-DE is already configured with the other one, automatic discovery wins anyway.
4. **Client API token versus classic login**: verify on a RomM 5.2 server that the QR pairing flow is available and document its exact steps before implementing it in M2.
