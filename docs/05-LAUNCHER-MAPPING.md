# 05 — Launchers, folders and platform mapping

This is the heart of the project: **it is the problem the app exists to solve**.

## 1. The problem

RomM organises the library as `library/roms/<slug>/`, where the slug follows the IGDB naming (`sms`, `genesis`, `neo-geo-pocket-color`, `sega32`, `zxs`…). Every Android frontend uses its own conventions instead: ES-DE expects `mastersystem`, `megadrive`, `ngpc`, `sega32x`, `zxspectrum`; Daijishō, Beacon and iiSU let the user pick one folder per platform, so the convention is whatever the user has built up over time.

The result with any generic client: on every download the app asks where to put the file, or it puts it in a folder the frontend does not watch. This must **disappear completely**.

## 2. Solution: cascading resolution

For each platform, the destination folder is resolved in this order and the result is stored in `FolderMappingEntity`:

1. **User override** (`source = USER`) — always wins, never rewritten automatically.
2. **Folder already present on the device** (`source = DISCOVERED`) — the subfolders of the ROM root folder are listed and compared with the platform aliases, case-insensitively and ignoring spaces, hyphens and underscores. This is the most frequent case: anyone with a frontend already configured already has the right folders.
3. **Preset of the selected launcher** (`source = PRESET`) — from `platform_map.json`, the field of the launcher selected during onboarding.
4. **Creation** (`source = CREATED`) — if nothing exists, the preset folder is proposed with a single-confirmation dialog. This is the only place where the user sees a question, and only once per platform.

Additional rules:

- A match against multiple candidates (e.g. both `megadrive` and `genesis` exist) is resolved by asking once, showing how many ROMs each folder contains: the most populated one is preselected.
- The mapping is **editable at any time** from Settings → Platform folders, with a table `platform → folder → number of files found`.
- Switching launcher later **does not erase** user overrides: it only re-runs phases 2 and 3.

### Pseudocode

```kotlin
fun resolveFolder(platform: Platform): FolderMapping? {
    userOverride(platform.slug)?.let { return it }
    val entry = platformMap[platform.slug]              // may be null
    val aliases = buildSet {
        add(platform.slug)
        entry?.let { addAll(listOfNotNull(it.esde) + it.esdeAlt + it.aliases) }
    }.map(::normalize)                                   // lowercase, strip spaces/-/_
    val existing = fileGateway.list(root).filter { it.isDirectory }
    val hits = existing.filter { normalize(it.name) in aliases }
    when {
        hits.size == 1 -> return save(platform.slug, hits.first(), DISCOVERED)
        hits.size > 1  -> return askUser(hits)            // preselection: most files inside
    }
    val preset = entry?.folderFor(selectedLauncher) ?: platform.slug
    return proposeCreate(preset)                          // single confirmation
}
```

## 3. Supported launchers

| Launcher | Typical root folder | Folder convention | Metadata | Preset reliability |
|---|---|---|---|---|
| **ES-DE** | `/storage/emulated/0/ROMs` | **fixed and known** (`snes`, `megadrive`, `gc`…), lowercase | `ES-DE/gamelists/<sys>/gamelist.xml` + `ES-DE/downloaded_media/<sys>/` | high: mapped from the official `es_systems.xml` |
| **Cocoon** | same as ES-DE | natively imports the ES-DE structure (ES-DE Migration/Link) | ES-DE format | high: inherits the ES-DE preset |
| **Daijishō** | `/storage/emulated/0/Roms` (varies) | **user-defined**: each platform has one or more "sync paths" | internal database, own scraping | medium: the common short name is proposed, relying on automatic discovery |
| **Beacon** | variable | user-defined, one folder per platform | internal | medium |
| **iiSU** | variable; assets in `/storage/emulated/0/Android/media/com.iisulauncher/iiSULauncher/assets/` | user-defined; can import ES-DE metadata | assets managed by hand by the user | medium |
| **Pegasus** | variable | user-defined | `metadata.pegasus.txt` per folder | medium |
| **Dig** | variable | user-defined | internal | low (project inactive) |
| **Custom / RetroArch only** | free choice | RomM slug unchanged | none | — |

For launchers with a free convention the preset uses the **common short name** (the `generic` column of `platform_map.json`, which almost always matches the RomM slug): in practice the user already has the folders, so automatic discovery wins.

**To verify on the device at first launch, do not take it for granted:** ES-DE on Android asks for the ROM folder at first launch and the user may have put it on the SD card (`/storage/XXXX-XXXX/ROMs`). The app must also look for the root folder on the SD card and propose both.

## 4. `platform_map.json`

File in `app/src/main/assets/platform_map.json` (copy in `assets/` of this repo). Structure:

```json
{
  "schemaVersion": 1,
  "platforms": [
    {
      "slug": "sms",
      "label": "Sega Master System",
      "esde": "mastersystem",
      "esdeAlt": ["mark3"],
      "generic": "sms",
      "aliases": ["sega master system", "sms", "mastersystem"]
    }
  ]
}
```

- `slug`: RomM/IGDB slug. The entries cover the roughly 80 platforms actually present in a real library plus the most common ones not present yet.
- `esde`: official ES-DE folder, extracted from `resources/systems/android/es_systems.xml` in the ES-DE repo.
- `esdeAlt`: alternatives accepted by ES-DE (regional variants such as `genesis`/`megadrive`, `segacd`/`megacd`, `tg16`/`pcengine`): they count as aliases during discovery.
- `esde: null` means ES-DE does not have that system: the app uses `generic`, warns the user that the frontend might not show it and does not treat it as an error.
- The file **is not authoritative on the list of platforms**: that comes from `GET /api/platforms`. An unknown slug is not an error; the app falls back to `slug` and to automatic discovery.
- The file must be updatable without recompiling: an override in `Android/data/<pkg>/files/platform_map.json` is planned, read if present.

## 5. Rules for downloaded files

1. **Unchanged name**: the server's `fs_name` is used. It is already No-Intro/Redump and every frontend scrapes on top of it.
2. **Archives**: `.zip` is extracted by default; `.7z` is left intact by default (ES-DE and RetroArch read it; extracting it only makes things worse). Both behaviours are configurable, also per platform.
3. **Multi-file / multi-disc games**: RomM serves them as an on-the-fly zip containing an `.m3u`. Destination: **one folder per game** inside the platform folder (`ROMs/psx/Final Fantasy VII/` containing the `.chd`/`.bin`/`.cue` files and the `.m3u`). ES-DE correctly handles both the `.m3u` in the system folder and the subfolder; the `.m3u` must in any case be left where the frontend sees it. Behaviour configurable per platform (`flat` or `folder-per-game`), default `folder-per-game` for psx, ps2, saturn, segacd, dreamcast, pcenginecd, 3do, neogeocd.
4. **No partial files** in the frontend folder: write to the private cache, verify, move.
5. **Duplicates**: if a file with the same name (or the extracted or compressed version of the same name) already exists, the download is marked "already on the device" and skipped, with the option to force it.
6. **BIOS/firmware**: separate section. Source `GET /api/firmware`, destination **`RetroArch/system/`** (configurable path), not the platform folders. Users typically already have a complete BIOS pack for RetroArch: the feature only serves to fill the gaps, so never overwrite an existing file without asking.

## 6. Metadata for the frontend (post-v1, milestone M4)

No Android frontend exposes an API to register a game. The universal contract is: **a file with the right extension in the watched folder, plus a new scan by the frontend**. ES-DE rescans at every launch.

To go further (covers and descriptions without making the frontend scrape) the reference format is the **ES-DE** one, because Cocoon imports it and iiSU can read it:

- `ES-DE/gamelists/<system>/gamelist.xml` — `<game>` entries with `<path>`, `<name>`, `<desc>`, `<releasedate>`, `<developer>`, `<publisher>`, `<genre>`, `<image>`.
- `ES-DE/downloaded_media/<system>/covers/<ROM file name>.jpg` and `screenshots/`, association **by ROM file name**.
- **Write `gamelist.xml` only while ES-DE is closed** and always with an atomic write (temporary file plus rename), merging the existing entries instead of overwriting the file.
