# 04 — Contratto API RomM

Server di riferimento: **RomM 5.2** su `http://192.168.50.11:8080`.

> **Regola d'oro:** la fonte di verità è `{base}/openapi.json` (**non** `/api/openapi.json`) del server dell'utente, e la UI Swagger su `{base}/api/docs`. La documentazione pubblica di RomM è in ritardo su alcuni endpoint. Prima di implementare, scaricare l'OpenAPI e generare/verificare i DTO.

## 1. Autenticazione

| Metodo | Endpoint | Note |
|---|---|---|
| Login utente | `POST /api/login` | **attenzione: i doc dicono `/api/auth/login`, che su 5.x è 404** |
| Sessione corrente | `GET /api/users/me` | usato all'avvio per capire se si è loggati |
| Client API Token | header `Authorization` con token `rmm_…` | via pairing QR: `POST /api/client-tokens/{id}/pair` più exchange |
| Basic | `Authorization: Basic base64(user:pass)` | fallback, Base64 su byte **UTF-8** |
| Heartbeat | `GET /api/heartbeat` | senza auth, restituisce versione: usarlo per il test di raggiungibilità |

Ordine di preferenza per l'app: **Client API Token via QR** (comodo da handheld) → login con credenziali → Basic.

## 2. Piattaforme

`GET /api/platforms` → elenco con `id`, `slug`, `name`, `display_name`/`custom_name`, `rom_count`, dati logo.

- Mostrare solo quelle con `rom_count > 0`.
- Etichetta da `display_name`/`custom_name`, mai dal solo `name`.
- Icona: `{base}/assets/platforms/{slug}.ico`.
- **Questo elenco è la fonte di verità degli slug**: `platform_map.json` è solo un seme di mapping, non un elenco autorevole.

## 3. ROM

`GET /api/roms` con i parametri:

| Parametro | Valore |
|---|---|
| `platform_ids` | plurale; il vecchio `platform_id` singolare viene ignorato dai server recenti |
| `collection_id`, `virtual_collection_id`, `smart_collection_id` | filtri per collezione |
| `search_term` | ricerca server-side (debounce 500 ms, minimo 2 caratteri) |
| `order_by` | `name`, `fs_size_bytes`, `created_at`, `first_release_date`, `average_rating` |
| `order_dir` | `asc` / `desc` |
| `limit`, `offset` | paginazione, pagina consigliata 20–40 |
| `group_by_meta_id=1` | **fondamentale**: raggruppa le release dello stesso gioco in una card con `siblings[]` |

Risposta: `{ items, total, limit, offset, char_index }`.

- **`char_index`** è la mappa lettera → offset: è ciò che alimenta la barra alfabetica. `romm-mobile` lo ignorava, noi ci costruiamo sopra il fast-scroll.
- Mappare direttamente su Paging 3 (`limit`/`offset`).

Campi utili di una ROM: `id`, `name`, `fs_name` (nome file reale, **da usare invariato**), `fs_size_bytes`, `platform_id`, `platform_slug`, `regions`, `revision`, `md5_hash`/`sha1_hash`/`crc_hash`, `path_cover_small`/`path_cover_large`, `url_cover`, `siblings`, `files[]`, `first_release_date`, `summary`, `genres`.

Dettaglio: `GET /api/roms/{id}`.

## 4. Copertine

Priorità: **`{base}{path_cover_small|large}`** (cache del server, include le copertine caricate a mano dall'utente) e solo come fallback `url_cover` (CDN esterno). L'ordine inverso è il bug #21 di `romm-mobile`: le copertine custom non comparivano mai.

In Coil, l'header di autenticazione va aggiunto **solo** per le richieste verso l'host del proprio server, mai verso il CDN.

## 5. Download

| Caso | Endpoint |
|---|---|
| Gioco intero | `GET /api/roms/{id}/content/{fs_name}` |
| Solo alcuni file | `GET /api/roms/{id}/content/{fs_name}?file_ids=1,2,3` |
| Singolo file di un gioco multi-file | `GET /api/roms/{id}/files/content/{file_name}` |
| Firmware / BIOS | `GET /api/firmware/{id}/content/{file_name}` |

Note verificate:

- `Accept-Ranges` supportato, risposte **206** alla ripresa.
- L'endpoint `content` **senza** `file_ids` restituisce sempre il gioco intero: chiedere un singolo `.cue` da 90 byte lì dentro scarica tutto (bug #20 loro). Per il file singolo usare `files/content/`.
- Multi-disco: il server produce uno **zip al volo con dentro un `.m3u`**. Va estratto (vedi doc 05 per la destinazione).
- Costruire gli URL con `/` normali: il loro bug degli URL firmware con backslash è reale.

## 6. Collezioni

| Tipo | Endpoint |
|---|---|
| Collezioni utente | `GET /api/collections` |
| Collezioni virtuali | `GET /api/collections/virtual?type=...` con i 5 tipi: `collection`, `franchise`, `genre`, `company`, `mode` |
| Smart collection | risorsa dedicata in RomM 5.x, ROM filtrabili con `smart_collection_id` |

Attenzione: gli **id delle collezioni virtuali sono stringhe**, non interi.

## 7. Comportamento all'avvio

In parallelo: `GET /api/heartbeat` (versione server, senza auth) e `GET /api/users/me` (sessione valida?). La prima richiesta di dati **non deve dipendere** dall'esito della rilevazione di versione.

## 8. Errori

| Codice | Comportamento |
|---|---|
| 401 | cancella la sessione, torna al login |
| 403 | messaggio esplicito (token senza permessi o Cloudflare Access) |
| 404 su endpoint noti | probabile mismatch di versione: mostrare versione server e endpoint tentato |
| risposta non-JSON | reverse proxy o pagina di login: messaggio dedicato, non crash di parsing |
| timeout/IO | retry con backoff, banner offline, contenuti dalla cache |
