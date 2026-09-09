# RomMMobile

App Android per **[RomM](https://romm.app)**, il gestore di ROM self-hosted. Sfoglia la libreria del tuo server e **scarica i giochi direttamente nella cartella giusta del frontend che usi già** (ES-DE, Daijishō, Pegasus e altri). Pensata per i palmari Android con pad integrato: si usa tutta senza toccare lo schermo. Quack.

RomMMobile **non lancia i giochi**: a quello ci pensa il tuo frontend. L'app si occupa di portare le ROM sul dispositivo, al posto giusto, con la copertina e senza file a metà.

## Cosa fa

- **Piattaforme, collezioni, ricerca**: la libreria di RomM com'è sul server, con copertine, regioni, versioni alternative e indice alfabetico per saltare a una lettera.
- **Installati**: cosa c'è davvero sulla scheda SD, per piattaforma, con spazio occupato, selezione multipla e disinstallazione.
- **Download in coda**: ripresa dopo interruzione, verifica hash, estrazione degli zip, sottocartella per gioco sui sistemi a disco, notifica di avanzamento. Nessun file parziale nelle cartelle del frontend, in nessun caso.
- **Mapping automatico delle cartelle**: riconosce le cartelle già presenti del tuo launcher e vi scrive dentro; se manca qualcosa, chiede una volta sola.
- **Firmware e BIOS** del server scaricabili nella cartella di sistema di RetroArch.
- **Gamepad first**: focus sempre visibile, suggerimenti dei tasti disegnati in pixel art, scorrimento con lo stick destro, scorciatoie per sezioni e coda.
- **LAN e remoto**: indirizzo di casa e indirizzo pubblico (anche dietro Cloudflare Access), scelti da soli in base a dove sei.
- Tema scuro e chiaro, italiano e inglese, layout che si adatta a schermi 4:3, quadrati, telefoni, tablet e TV.

## Requisiti

- Un server **RomM 5.x** raggiungibile dal dispositivo.
- **Android 8.0** (API 26) o successivo. Provata su Retroid Pocket Classic; il layout è pensato anche per RG Cube, Retroid Pocket 5, Odin 2, telefoni, tablet e Android TV.
- Un frontend già installato tra: ES-DE, Cocoon, Daijishō, Beacon, iiSU, Pegasus, oppure una struttura di cartelle personalizzata.

## Installazione

1. Scarica [`release/RomMMobile.apk`](release/RomMMobile.apk).
2. Copialo sul dispositivo e aprilo (serve il permesso "installa app sconosciute" per il file manager che usi).
3. Al primo avvio la procedura guidata ti chiede, in ordine:
   - **Server**: l'indirizzo di RomM (`http://ip:porta` in casa, oppure il dominio HTTPS). L'app lo prova subito e mostra la versione del server.
   - **Accesso**: utente e password, oppure un token client generato da RomM (anche via QR).
   - **Frontend**: quale launcher usi, per sapere come chiamare le cartelle.
   - **Cartella ROM**: la radice dove il frontend legge i giochi (ad esempio `ROMs` sulla SD).
   - **Riepilogo**: la tabella piattaforma → cartella, già compilata con quello che ha trovato.

Tutto è modificabile in seguito da **Impostazioni**.

## Comandi dal pad

| Tasto | Azione |
|---|---|
| A | Apri |
| B | Indietro (alla Home: premi due volte per uscire) |
| X | Negli Installati: selezione multipla, poi elimina |
| Y | Griglia / lista |
| L1 / R1 | Sezione precedente / successiva |
| L3 | Cerca |
| R3 | Coda download |
| Start / Select | Ordinamento e menu contestuale |
| Stick destro | Scorrimento continuo |

La barra in basso mostra sempre i tasti attivi nella schermata corrente.

## Struttura del repository

| Percorso | Contenuto |
|---|---|
| `app/` | Sorgenti dell'app |
| `release/` | APK corrente per il sideload |
| `assets/platform_map.json` | Tabella di mapping piattaforme RomM → cartelle dei launcher |

## Documentazione

L'ordine di lettura è quello dei numeri.

| File | Cosa contiene |
|---|---|
| [docs/01-SPEC-PRODOTTO.md](docs/01-SPEC-PRODOTTO.md) | Cosa fa l'app, cosa NON fa, flussi utente, onboarding |
| [docs/02-UI-DESIGN-SYSTEM.md](docs/02-UI-DESIGN-SYSTEM.md) | Stile di RomM (token esatti), layout responsive, schermi quadrati, gamepad |
| [docs/03-ARCHITETTURA.md](docs/03-ARCHITETTURA.md) | Stack, moduli, storage, motore di download |
| [docs/04-API-ROMM.md](docs/04-API-ROMM.md) | Contratto API RomM 5.x verificato |
| [docs/05-LAUNCHER-MAPPING.md](docs/05-LAUNCHER-MAPPING.md) | Il problema delle cartelle e la sua soluzione completa |
| [docs/06-ROADMAP.md](docs/06-ROADMAP.md) | Milestone, criteri di accettazione, matrice di test |

## Compilare dai sorgenti

Servono JDK 17 e l'SDK Android con piattaforma 37 (`setup-android-sdk.ps1` lo scarica su Windows). Il Gradle wrapper è incluso.

```bash
./gradlew :app:assembleRelease
```

L'APK esce in `app/build/outputs/apk/release/`. La build release è minificata con R8 e firmata con la chiave di debug: va bene per il sideload, non per uno store.

Stack: Kotlin 2.3, Jetpack Compose, Hilt, Room, Paging 3, DataStore, Coil 3, OkHttp/Retrofit, WorkManager. Android Gradle Plugin 9.4, Gradle 9.7.

## Stato

Versione **1.0.0**, in sviluppo attivo. Funzionano libreria, installati, download, mapping cartelle, firmware e navigazione da pad. Non ancora fatto: scrittura di `gamelist.xml` per il frontend, sincronizzazione di collezioni e salvataggi, pubblicazione su F-Droid.

Segnalazioni e proposte: apri una issue su questo repository. Se vedi una scimmia a tre teste dietro di te, allega uno screenshot.

## Licenza

Non ancora scelta. Nel frattempo, quack.
