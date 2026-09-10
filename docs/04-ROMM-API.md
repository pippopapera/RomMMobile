# 04 — RomM API Contract

Reference server: **RomM 5.2** at `http://192.168.1.10:8080`.

> **Golden rule:** the source of truth is `{base}/openapi.json` (**not** `/api/openapi.json`) of the server in use, plus the Swagger UI at `{base}/api/docs`. RomM's public documentation lags behind on some endpoints. Before implementing, download the OpenAPI spec and generate/verify the DTOs.

## 1. Authentication

| Method | Endpoint | Notes |
|---|---|---|
| User login | `POST /api/login` | **caution: the docs say `/api/auth/login`, which is a 404 on 5.x** |
| Current session | `GET /api/users/me` | used at startup to find out whether the user is logged in |
| Client API token | `Authorization` header with an `rmm_…` token | via QR pairing: `POST /api/client-tokens/{id}/pair` plus exchange |
| Basic | `Authorization: Basic base64(user:pass)` | fallback, Base64 over **UTF-8** bytes |
| Heartbeat | `GET /api/heartbeat` | no auth, returns the version: use it for the reachability test |

Order of preference for the app: **client API token via QR** (convenient on a handheld) → login with credentials → Basic.

## 2. Platforms

`GET /api/platforms` → list with `id`, `slug`, `name`, `display_name`/`custom_name`, `rom_count`, logo data.

- Show only those with `rom_count > 0`.
- Label from `display_name`/`custom_name`, never from `name` alone.
- Icon: `{base}/assets/platforms/{slug}.ico`.
- **This list is the source of truth for slugs**: `platform_map.json` is only a mapping seed, not an authoritative list.

## 3. ROMs

`GET /api/roms` with the parameters:

| Parameter | Value |
|---|---|
| `platform_ids` | plural; the old singular `platform_id` is ignored by recent servers |
| `collection_id`, `virtual_collection_id`, `smart_collection_id` | collection filters |
| `search_term` | server-side search (500 ms debounce, minimum 2 characters) |
| `order_by` | `name`, `fs_size_bytes`, `created_at`, `first_release_date`, `average_rating` |
| `order_dir` | `asc` / `desc` |
| `limit`, `offset` | pagination, recommended page size 20–40 |
| `group_by_meta_id=1` | **essential**: groups the releases of the same game into one card with `siblings[]` |

Response: `{ items, total, limit, offset, char_index }`.

- **`char_index`** is the letter → offset map: it is what feeds the alphabet rail. `romm-mobile` ignored it; we build the fast-scroll on top of it.
- Map it directly onto Paging 3 (`limit`/`offset`).

Useful ROM fields: `id`, `name`, `fs_name` (the real file name, **to be used unchanged**), `fs_size_bytes`, `platform_id`, `platform_slug`, `regions`, `revision`, `md5_hash`/`sha1_hash`/`crc_hash`, `path_cover_small`/`path_cover_large`, `url_cover`, `siblings`, `files[]`, `first_release_date`, `summary`, `genres`.

Detail: `GET /api/roms/{id}`.

## 4. Covers

Priority: **`{base}{path_cover_small|large}`** (server cache, includes covers uploaded manually by the user) and only as a fallback `url_cover` (external CDN). The reverse order is bug #21 of `romm-mobile`: custom covers never showed up.

In Coil, the authentication header must be added **only** for requests to the host of the user's own server, never to the CDN.

## 5. Download

| Case | Endpoint |
|---|---|
| Whole game | `GET /api/roms/{id}/content/{fs_name}` |
| Only some files | `GET /api/roms/{id}/content/{fs_name}?file_ids=1,2,3` |
| Single file of a multi-file game | `GET /api/roms/{id}/files/content/{file_name}` |
| Firmware / BIOS | `GET /api/firmware/{id}/content/{file_name}` |

Verified notes:

- `Accept-Ranges` supported, **206** responses on resume.
- The `content` endpoint **without** `file_ids` always returns the whole game: asking for a single 90-byte `.cue` there downloads everything (their bug #20). For a single file use `files/content/`.
- Multi-disc: the server produces a **zip on the fly containing an `.m3u`**. It must be extracted (see doc 05 for the destination).
- Build URLs with normal `/`: their bug with backslashes in firmware URLs is real.

## 6. Collections

| Type | Endpoint |
|---|---|
| User collections | `GET /api/collections` |
| Virtual collections | `GET /api/collections/virtual?type=...` with the 5 types: `collection`, `franchise`, `genre`, `company`, `mode` |
| Smart collections | dedicated resource in RomM 5.x, ROMs filterable with `smart_collection_id` |

Caution: **virtual collection ids are strings**, not integers.

## 7. Startup behaviour

In parallel: `GET /api/heartbeat` (server version, no auth) and `GET /api/users/me` (valid session?). The first data request **must not depend** on the outcome of the version detection.

## 8. Errors

| Code | Behaviour |
|---|---|
| 401 | clear the session, go back to login |
| 403 | explicit message (token without permissions or Cloudflare Access) |
| 404 on known endpoints | likely version mismatch: show the server version and the endpoint attempted |
| non-JSON response | reverse proxy or login page: dedicated message, not a parsing crash |
| timeout/IO | retry with backoff, offline banner, content from cache |
