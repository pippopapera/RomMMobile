# 06 — Milestone, criteri di accettazione, test

Ordine pensato per avere qualcosa di usabile presto e per non costruire la UI sopra fondamenta di storage sbagliate.

## M0 — Fondamenta

- Progetto Compose + Hilt + Room + DataStore, `minSdk 26`, tema RomM completo (scuro e chiaro) con i token del doc 02.
- Client di rete: normalizzazione URL, `GET /api/heartbeat`, login (`POST /api/login`), `GET /api/users/me`, credenziali in `EncryptedSharedPreferences`, `networkSecurityConfig` per LAN in chiaro.
- `WindowSizeClass` custom a due assi (larghezza e altezza) più rilevamento TV.
- Schermata Diagnostica e log su file.

**Fatto quando:** ci si logga sul server dell'utente in HTTP e si vede la versione RomM; ruotando il device e in split screen non ci sono layout rotti.

## M1 — Navigazione della libreria

- Tre sezioni: Piattaforme (griglia con icone e conteggio), Collezioni, Cerca.
- Griglia giochi con Paging 3, copertine via Coil (priorità `path_cover_*`, fallback `url_cover`), toggle griglia/lista.
- **AlphabetRail** alimentata da `char_index`, con salto da L2/R2.
- Dettaglio gioco con siblings risolti solo lì.
- Navigazione completa da gamepad: focus visibile, `focusRestorer`, mappatura del doc 02.

**Fatto quando:** si naviga una piattaforma da 1.700 titoli senza scatti e senza crash, si salta a una lettera in meno di un secondo, e tutto è raggiungibile senza toccare lo schermo.

## M2 — Onboarding, launcher e cartelle

- Wizard a 5 passi (doc 01), incluso il pairing QR del Client API Token.
- `FileGateway` con le due implementazioni, richiesta di `MANAGE_EXTERNAL_STORAGE` con fallback SAF e percorso manuale.
- Caricamento di `platform_map.json`, scoperta automatica delle cartelle esistenti, tabella di mapping modificabile.

**Fatto quando:** su un device con ES-DE già configurato, l'app rileva da sola le cartelle esistenti e mostra la tabella completa senza chiedere niente all'utente oltre alla cartella radice.

## M3 — Download

- Coda persistita su Room, Foreground Service con notifica, WorkManager, concorrenza configurabile.
- Scrittura `.part` in cache privata, verifica hash, spostamento, stati completi, ripresa con Range, annullamento vero.
- Estrazione zip in streaming, `.7z` lasciato intero per default, sottocartella per gioco sui sistemi a disco.
- `DownloadMiniBar` e schermata Download.
- Indice dei file locali e badge "già presente" sulle card.

**Fatto quando:** si scarica un gioco PSX multi-disco, il `.m3u` finisce nella sottocartella giusta, ES-DE lo vede al riavvio; uccidendo l'app a metà download e riaprendola, il download riprende dal punto in cui era.

## M4 — Rifiniture

- Collezioni virtuali (5 tipi, id stringa) e smart collection.
- Ricerca con filtri e ordinamenti, ricerche recenti.
- Gestione libreria locale: vedere ed eliminare ciò che si è scaricato, spazio occupato per piattaforma.
- Localizzazione italiano e inglese, tema chiaro, impostazione "scambia A/B", schermata test input.
- Ottimizzazione Android TV.

## M5 — Metadati per il frontend

- Scrittura di `gamelist.xml` in formato ES-DE con fusione delle voci esistenti e scrittura atomica, solo a frontend chiuso.
- Download delle copertine in `downloaded_media/<sistema>/covers/`.
- Sezione BIOS/firmware con destinazione `RetroArch/system/` e nessuna sovrascrittura silenziosa.

## Dopo la v1 (non pianificato)

Sync collezione verso il device (solo Wi-Fi, solo in carica), sync dei salvataggi, Cloudflare Access come impostazione di primo livello, "apri con app esterna" post-download (un semplice `ACTION_VIEW`: non ci rende un frontend, ma è comodo), deep link, pubblicazione su F-Droid.

## Definition of done trasversale

Vale per ogni milestone:

1. Nessun crash con record dell'API incompleti: campi nullable e rendering difensivo per item.
2. Nessun blocco della UI su I/O: tutto su dispatcher IO, progressi campionati.
3. Ogni schermata è completamente navigabile da gamepad, con focus visibile e ripristinato.
4. Ogni schermata si comporta correttamente nelle quattro classi di layout della matrice qui sotto.
5. Ogni errore di rete o di filesystem ha un messaggio in italiano che dice cosa è successo e cosa fare.
6. Nessun file parziale lasciato nelle cartelle del frontend, in nessuno scenario di errore.

## Matrice di test schermi

| Profilo | Risoluzione | dp stimati | Cosa verificare |
|---|---|---|---|
| Retroid Pocket Classic | 1240×1080 @ ~420 dpi | ~472 × 411 | rail laterale, top bar 40 dp, minimo 3 colonne, barra lettere leggibile |
| RG Cube (1:1) | 720×720 | ~360 × 360 | caso peggiore: tutto deve entrare, niente testo troncato nella rail |
| Handheld orizzontale (RP5, Odin 2) | 1920×1080 | ~640 × 360 | classe MEDIUM ma SHORT: rail più griglia larga, niente bottom bar |
| Telefono | 2400×1080 | ~393 × 873 | bottom bar, 2–3 colonne, insets edge-to-edge |
| Tablet / dock | 2560×1600 | ~1280 × 800 | due pannelli, 6–8 colonne |
| Android TV 1080p | 1920×1080 | 960 × 540 | overscan, niente picker SAF, solo D-pad |

Ognuno va provato anche con `fontScale` 1.3 e con la libreria vera dell'utente (18.430 ROM, 78 piattaforme).

## Decisioni ancora aperte

1. **Nome pacchetto e identità app** — proposta `com.rommmobile.app`; da confermare, così come icona e nome visualizzato.
2. **Arcade**: la libreria dell'utente usa il bestset FBNeo. Preselezionare la cartella `fbneo` quando esiste, altrimenti `arcade`: da confermare con lui al primo utilizzo reale.
3. **`genesis` contro `megadrive`** e **`segacd` contro `megacd`**: il preset propone la variante europea; se ES-DE è già configurato con l'altra, vince comunque la scoperta automatica.
4. **Client API Token contro login classico**: verificare sul server 5.2 dell'utente che il flusso di pairing QR sia disponibile e documentarne i passi esatti prima di implementarlo in M2.
