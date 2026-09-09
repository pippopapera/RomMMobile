# 03 — Architettura tecnica

## 1. Stack

| Ambito | Scelta | Motivo |
|---|---|---|
| Linguaggio | Kotlin 2.x | |
| UI | Jetpack Compose (BOM aggiornato) + Material 3 | focus e input da gamepad gestibili in modo esplicito |
| Componenti TV | `androidx.tv:tv-material` solo dove serve (rail, focus su TV) | stessa scelta di Argosy |
| DI | Hilt | |
| DB locale | Room | coda download, cache libreria, mapping cartelle |
| Preferenze | DataStore (Preferences) | impostazioni non sensibili |
| Segreti | `EncryptedSharedPreferences` | token e password |
| Rete | OkHttp + Retrofit + `kotlinx.serialization` | Range, Authenticator, interceptor |
| Immagini | Coil 3 | header di auth solo verso l'host del proprio server |
| Paginazione | Paging 3 | librerie da decine di migliaia di titoli |
| Lavoro in background | WorkManager + Foreground Service (`dataSync`) | i download devono sopravvivere all'app in background |
| Archivi | `commons-compress` (zip, 7z) | 7z va supportato: RomM lo serve |

`minSdk 26`, `compileSdk`/`targetSdk` 35 o superiore.

## 2. Moduli e package

Modulo singolo `:app` all'inizio (progetto piccolo), diviso per package:

```
core/
  design/      token colori, tipografia, componenti riusabili, WindowSizeClass custom
  input/       gestione gamepad, keycode, mappature, focus helpers
  network/     OkHttp, interceptor auth, Retrofit, gestione errori
  storage/     astrazione FileGateway (File API + SAF dietro la stessa interfaccia)
data/
  api/         DTO e servizi RomM
  db/          entità Room, DAO
  repo/        PlatformRepository, RomRepository, CollectionRepository, DownloadRepository
  mapping/     PlatformMapper, caricamento platform_map.json, override utente
feature/
  onboarding/  server, login/QR, launcher, permessi, cartelle
  platforms/   griglia piattaforme
  library/     griglia/lista giochi, AlphabetRail, filtri
  game/        dettaglio, siblings, file
  collections/ collezioni utente, virtuali, smart
  search/      ricerca server-side
  downloads/   coda, servizio, notifiche
  settings/    impostazioni, mapping cartelle, test input
```

## 3. Modello dati locale (Room)

```
PlatformEntity(id, slug, displayName, romCount, iconUrl, updatedAt)
RomEntity(id, platformId, name, fsName, fileSizeBytes, coverPathSmall, coverUrl,
          regions, revision, siblingCount, md5, sha1, crc, updatedAt)
DownloadEntity(id, romId, fileName, targetPath, totalBytes, downloadedBytes,
               state, error, attempt, createdAt, updatedAt, priority)
LocalFileEntity(platformSlug, fileName, sizeBytes, path, romId?, verifiedAt)
FolderMappingEntity(platformSlug, folderUri, source)   -- source: PRESET|DISCOVERED|USER|CREATED
```

- La cache libreria serve a due cose: navigare offline e sapere in fretta cosa è già presente. Non è la fonte di verità: il server lo è.
- `LocalFileEntity` è l'indice dei file effettivamente presenti sul device, aggiornato da una scansione incrementale delle cartelle mappate (all'avvio e dopo ogni download).

## 4. Storage: la parte delicata

### 4.1 Strategia dei permessi, in quest'ordine

1. **`MANAGE_EXTERNAL_STORAGE`** (All files access) come via principale. È ciò che usa ES-DE stesso, funziona su Android TV e Fire TV dove **il picker SAF spesso non esiste** (bug confermato dalle issue di `romm-mobile`), e i file scritti così sono immediatamente visibili agli altri emulatori.
2. **SAF** (`ACTION_OPEN_DOCUMENT_TREE` + `takePersistableUriPermission`) come ripiego se il permesso viene negato.
3. **Percorso digitato a mano** come ultima spiaggia (TV senza picker e con permesso negato).

L'app è sideloadata, quindi la policy Play Store su `MANAGE_EXTERNAL_STORAGE` non è un ostacolo. Va comunque dichiarata la motivazione nella schermata di richiesta.

### 4.2 `FileGateway`

Interfaccia unica con due implementazioni (`DirectFileGateway` su `java.io.File`, `SafFileGateway` su `DocumentsContract`), perché il resto del codice non deve sapere quale via è attiva:

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

Trappole note (documentate nello studio di `romm-mobile`): su SAF `moveDocument` lancia errori spuri anche quando l'operazione riesce, quindi **dopo ogni move si verifica esistenza e dimensione**, e non si ingoiano le eccezioni con catch vuoti.

## 5. Motore di download

### 5.1 Ciclo di vita

```
PENDING -> DOWNLOADING -> VERIFYING -> [EXTRACTING] -> MOVING -> COMPLETED
                |                                        |
                +-> PAUSED -> DOWNLOADING                +-> FAILED -> (retry)
                +-> CANCELLED
```

### 5.2 Regole

- Coda **persistita su Room**, non in memoria: chiudere l'app non deve perdere i download (errore capitale di `romm-mobile`).
- Esecuzione in un **Foreground Service** con notifica di progresso e azioni pausa/annulla; WorkManager per la programmazione e i vincoli (solo Wi-Fi, opzionale).
- Concorrenza configurabile 1–5, default **2**.
- Deduplica per `rom_id + file_name`: premere scarica due volte non accoda due volte.
- Scrittura in cache privata dell'app come `<name>.part`, poi verifica, poi `move` nella cartella di destinazione. Mai scrivere direttamente nella cartella del frontend.
- **Ripresa con HTTP Range** (il server RomM risponde 206, verificato): alla ripresa si invia `Range: bytes=<downloaded>-`; se il server risponde 200 invece di 206, si riparte da zero.
- **Verifica di integrità**: se il server espone `md5`/`sha1`/`crc` per il file, si calcola l'hash in streaming durante il download e si confronta prima del move. Fallimento = file scartato e retry.
- Controllo spazio libero prima di iniziare, tenendo conto che un'estrazione richiede transitoriamente 2–3 volte la dimensione dell'archivio.
- Annullamento vero: `call.cancel()` e cancellazione del `.part`.
- Retry con backoff esponenziale (3 tentativi: 2 s, 8 s, 30 s) su errori di rete; nessun retry automatico su 401/403/404.
- Estrazione: **streaming** con `ZipInputStream`/`SevenZFile` direttamente sul `FileGateway`, mai caricando in memoria (i loro crash su zip grossi nascevano da lì); sottocartelle interne preservate.
- Impostazione "estrai automaticamente" attiva di default per `.zip`, **disattiva per `.7z`** (ES-DE e RetroArch leggono `.7z` nativamente: spesso conviene lasciarlo intero).

### 5.3 Aggiornamento UI
Il servizio pubblica uno `StateFlow` del progresso, campionato a 2–4 Hz per la UI. Niente ricomposizioni a ogni chunk.

## 6. Rete

- URL base normalizzato e salvato una volta; supporto sia `http://IP:porta` (LAN) sia `https://dominio` (Cloudflare Tunnel) **senza riconfigurazione**: l'app salva un profilo server con entrambi gli indirizzi e prova prima quello raggiungibile (LAN se il gateway coincide, altrimenti remoto).
- `networkSecurityConfig` con `cleartextTrafficPermitted` limitato agli indirizzi privati (10/8, 172.16/12, 192.168/16, `.local`) e HTTPS per tutto il resto.
- Supporto opzionale a **Cloudflare Access**: due campi in impostazioni (`CF-Access-Client-Id`, `CF-Access-Client-Secret`) aggiunti come header su ogni richiesta quando valorizzati. Senza questo, un tunnel protetto restituisce HTML di login e l'app sembra rotta.
- Timeout: connect 10 s, read 30 s, write 30 s, **nessun timeout di chiamata sui download**.
- Interceptor che riconosce risposte non-JSON (reverse proxy, pagina di login) e produce un errore leggibile invece di un crash di parsing.
- Le credenziali si cancellano **solo su 401**, mai su un 4xx generico.

## 7. Robustezza (lezioni dalle issue di `romm-mobile`, da non ripetere)

1. Modelli `@Serializable` con `ignoreUnknownKeys = true` e campi opzionali nullable con default: la loro app crashava con `Cannot read property 'rom_id' of undefined` su librerie anche da 17 giochi.
2. Rendering difensivo per item: un record incompleto mostra una card placeholder, non fa cadere la lista.
3. Paging 3 ovunque: liste da 2.300 titoli li facevano crashare.
4. Niente N+1 sui siblings: le versioni si risolvono **solo** nel dettaglio del gioco, su richiesta.
5. Base64 delle credenziali sempre su byte UTF-8 (le password con caratteri speciali rompevano il loro login).
6. Logout selettivo: cancella la sessione, non le impostazioni e non il mapping cartelle.
7. Mai far dipendere la prima chiamata dalla versione del server rilevata (race condition nota).

## 8. Log e diagnostica

Log su file rotante (ultimi 2 MB) esportabile dalle impostazioni: senza questo, diagnosticare un problema su un handheld cinese via chat è impossibile. La schermata "Diagnostica" mostra: versione server, endpoint in uso, permesso storage attivo, cartella radice, mapping risolti, spazio libero, ultimi 20 eventi di download.
