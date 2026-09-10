# 03 — Technical architecture

## 1. Stack

| Area | Choice | Reason |
|---|---|---|
| Language | Kotlin 2.x | |
| UI | Jetpack Compose (up-to-date BOM) + Material 3 | focus and gamepad input can be handled explicitly |
| TV components | `androidx.tv:tv-material` only where needed (rails, focus on TV) | same choice as Argosy |
| DI | Hilt | |
| Local DB | Room | download queue, library cache, folder mapping |
| Preferences | DataStore (Preferences) | non-sensitive settings |
| Secrets | `EncryptedSharedPreferences` | tokens and passwords |
| Network | OkHttp + Retrofit + `kotlinx.serialization` | Range, Authenticator, interceptors |
| Images | Coil 3 | auth header sent only to the host of the user's own server |
| Pagination | Paging 3 | libraries with tens of thousands of titles |
| Background work | WorkManager + Foreground Service (`dataSync`) | downloads must survive the app going to the background |
| Archives | `commons-compress` (zip, 7z) | 7z must be supported: RomM serves it |

`minSdk 26`, `compileSdk`/`targetSdk` 35 or higher.

## 2. Modules and packages

A single `:app` module to begin with (small project), split by package:

```
core/
  design/      colour tokens, typography, reusable components, custom WindowSizeClass
  input/       gamepad handling, keycodes, mappings, focus helpers
  network/     OkHttp, auth interceptor, Retrofit, error handling
  storage/     FileGateway abstraction (File API + SAF behind the same interface)
data/
  api/         RomM DTOs and services
  db/          Room entities, DAOs
  repo/        PlatformRepository, RomRepository, CollectionRepository, DownloadRepository
  mapping/     PlatformMapper, platform_map.json loading, user overrides
feature/
  onboarding/  server, login/QR, launcher, permissions, folders
  platforms/   platform grid
  library/     game grid/list, AlphabetRail, filters
  game/        detail, siblings, files
  collections/ user, virtual and smart collections
  search/      server-side search
  downloads/   queue, service, notifications
  settings/    Settings, folder mapping, input test
```

## 3. Local data model (Room)

```
PlatformEntity(id, slug, displayName, romCount, iconUrl, updatedAt)
RomEntity(id, platformId, name, fsName, fileSizeBytes, coverPathSmall, coverUrl,
          regions, revision, siblingCount, md5, sha1, crc, updatedAt)
DownloadEntity(id, romId, fileName, targetPath, totalBytes, downloadedBytes,
               state, error, attempt, createdAt, updatedAt, priority)
LocalFileEntity(platformSlug, fileName, sizeBytes, path, romId?, verifiedAt)
FolderMappingEntity(platformSlug, folderUri, source)   -- source: PRESET|DISCOVERED|USER|CREATED
```

- The library cache serves two purposes: browsing offline and quickly knowing what is already on the device. It is not the source of truth: the server is.
- `LocalFileEntity` is the local index of the files actually present on the device, updated by an incremental scan of the mapped folders (at startup and after every download).

## 4. Storage: the delicate part

### 4.1 Permission strategy, in this order

1. **`MANAGE_EXTERNAL_STORAGE`** (All files access) as the primary route. It is what ES-DE itself uses, it works on Android TV and Fire TV where **the SAF picker often does not exist** (a bug confirmed by the `romm-mobile` issues), and files written this way are immediately visible to the other emulators.
2. **SAF** (`ACTION_OPEN_DOCUMENT_TREE` + `takePersistableUriPermission`) as the fallback if the permission is denied.
3. **Manually typed path** as the last resort (TV without a picker and with the permission denied).

The app is sideloaded, so the Play Store policy on `MANAGE_EXTERNAL_STORAGE` is not an obstacle. The rationale must still be stated on the request screen.

### 4.2 `FileGateway`

A single interface with two implementations (`DirectFileGateway` on `java.io.File`, `SafFileGateway` on `DocumentsContract`), because the rest of the code must not know which route is active:

```kotlin
interface FileGateway {
    fun exists(dir: String, name: String): Boolean
    fun list(dir: String): List<FileInfo>
    fun createDir(parent: String, name: String): String
    fun openOutput(dir: String, name: String): OutputStream
    fun move(fromTemp: File, dir: String, name: String): Boolean
    fun delete(dir: String, name: String): Boolean
    fun freeSpaceBytes(dir: String): Long
}
```

Known traps (documented in the `romm-mobile` study): on SAF, `moveDocument` throws spurious errors even when the operation succeeds, so **after every move the existence and size are verified**, and exceptions are never swallowed with empty catch blocks.

## 5. Download engine

### 5.1 Lifecycle

```
PENDING -> DOWNLOADING -> VERIFYING -> [EXTRACTING] -> MOVING -> COMPLETED
                |                                        |
                +-> PAUSED -> DOWNLOADING                +-> FAILED -> (retry)
                +-> CANCELLED
```

### 5.2 Rules

- Queue **persisted in Room**, not in memory: closing the app must not lose the downloads (the cardinal mistake of `romm-mobile`).
- Execution in a **Foreground Service** with a progress notification and pause/cancel actions; WorkManager for scheduling and constraints (Wi-Fi only, optional).
- Configurable concurrency 1–5, default **2**.
- Deduplication by `rom_id + file_name`: pressing download twice does not enqueue twice.
- Write into the app's private cache as `<name>.part`, then verify, then `move` into the destination folder. Never write directly into the frontend's folder.
- **Resume with HTTP Range** (the RomM server answers 206, verified): on resume the app sends `Range: bytes=<downloaded>-`; if the server answers 200 instead of 206, the download restarts from zero.
- **Integrity check**: if the server exposes `md5`/`sha1`/`crc` for the file, the hash is computed in streaming during the download and compared before the move. Failure = file discarded and retry.
- Free-space check before starting, taking into account that an extraction temporarily needs 2–3 times the size of the archive.
- Real cancellation: `call.cancel()` and deletion of the `.part`.
- Retry with exponential backoff (3 attempts: 2 s, 8 s, 30 s) on network errors; no automatic retry on 401/403/404.
- Extraction: **streaming** with `ZipInputStream`/`SevenZFile` directly onto the `FileGateway`, never loading into memory (their crashes on large zips came from there); internal subfolders preserved.
- The "extract automatically" setting is on by default for `.zip`, **off for `.7z`** (ES-DE and RetroArch read `.7z` natively: it is often better to leave it intact).

### 5.3 UI updates
The service publishes a `StateFlow` of the progress, sampled at 2–4 Hz for the UI. No recompositions on every chunk.

## 6. Network

- Base URL normalised and saved once; support for both `http://IP:port` (LAN) and `https://domain` (Cloudflare Tunnel) **without reconfiguration**: the app stores a server profile with both addresses and tries the reachable one first (LAN if the gateway matches, otherwise remote).
- `networkSecurityConfig` with `cleartextTrafficPermitted` limited to private addresses (10/8, 172.16/12, 192.168/16, `.local`) and HTTPS for everything else.
- Optional support for **Cloudflare Access**: two fields in Settings (`CF-Access-Client-Id`, `CF-Access-Client-Secret`) added as headers on every request when set. Without this, a protected tunnel returns login HTML and the app looks broken.
- Timeouts: connect 10 s, read 30 s, write 30 s, **no call timeout on downloads**.
- An interceptor that recognises non-JSON responses (reverse proxy, login page) and produces a readable error instead of a parsing crash.
- Credentials are cleared **only on 401**, never on a generic 4xx.

## 7. Robustness (lessons from the `romm-mobile` issues, not to be repeated)

1. `@Serializable` models with `ignoreUnknownKeys = true` and optional nullable fields with defaults: their app crashed with `Cannot read property 'rom_id' of undefined` even on libraries of 17 games.
2. Defensive rendering per item: an incomplete record shows a placeholder card, it does not bring down the list.
3. Paging 3 everywhere: lists of 2,300 titles crashed them.
4. No N+1 on siblings: versions are resolved **only** in the game detail, on demand.
5. Base64 of the credentials always over UTF-8 bytes (passwords with special characters broke their login).
6. Selective logout: clears the session, not the Settings and not the folder mapping.
7. Never make the first call depend on the detected server version (known race condition).

## 8. Logging and diagnostics

Rotating file log (last 2 MB) exportable from Settings: without this, diagnosing a problem on a Chinese handheld over chat is impossible. The "Diagnostics" screen shows: server version, endpoint in use, active storage permission, root folder, resolved mappings, free space, last 20 download events.
