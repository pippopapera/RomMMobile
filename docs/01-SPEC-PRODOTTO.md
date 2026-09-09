# 01 — Specifica di prodotto

## 1. Una frase

RomMMobile è un client Android per un server **RomM** self-hosted che permette di sfogliare la libreria con lo stesso linguaggio visivo della web app e di **scaricare le ROM già nella cartella giusta del frontend installato sul device**, usando i controlli fisici dell'handheld.

## 2. Obiettivi

1. **Zero domande sul percorso.** Dopo l'onboarding l'utente non deve mai più rispondere a "dove salvo questo file?". Seleziona il gioco, preme scarica, il file finisce dove il suo frontend lo troverà.
2. **Gamepad-first.** Tutto è raggiungibile con D-pad/stick/pulsanti. Il touch resta pienamente funzionante ma non è mai necessario.
3. **Stile RomM.** L'interfaccia riprende palette, gerarchia e componenti della web app RomM, corretti dove la web app non si adatta bene (schermi quadrati, bassi, TV).
4. **Adattiva davvero.** Stessa app leggibile su schermo quasi quadrato 1240×1080 (Retroid Pocket Classic), su 1920×1080 orizzontale (Retroid Pocket 5, Odin 2), su telefono 20:9 verticale e su TV 720p/1080p.

## 3. Non obiettivi (v1)

- **Non lancia i giochi.** Nessuna integrazione con emulatori, nessun `Intent` di avvio, nessun core embedded. È esplicitamente fuori scope: quel ruolo lo copre il frontend dell'utente (ES-DE, Daijishō…).
- Niente emulazione web (EmulatorJS), niente streaming.
- Niente sync dei salvataggi, niente RetroAchievements (valutabili post-v1, vedi roadmap).
- Niente scraping proprio: i metadati vengono da RomM.
- Niente scrittura/modifica della libreria sul server (upload, rename, delete lato server).

## 4. Device target

| Classe | Esempi | Peculiarità che l'app deve gestire |
|---|---|---|
| Handheld verticale quasi quadrato | Retroid Pocket Classic (3,92", **1240×1080**, ~419 dpi, ~10:9) | altezza in dp molto bassa (~410 dp), niente barra in basso |
| Handheld orizzontale | Retroid Pocket 5, Odin 2/3, AYN Odin, RG Cube (1:1 720×720), Ayaneo Pocket | layout largo e basso, focus a distanza di braccio |
| Telefono | qualsiasi 20:9 | layout classico, bottom bar |
| Android TV / box | Nvidia Shield, Fire TV | **il picker SAF può non esistere**, niente touch, overscan |

Requisiti tecnici: **minSdk 26** (Android 8), **target/compileSdk 35+**. Distribuzione via sideload (APK in Release GitHub), non Play Store — questo autorizza `MANAGE_EXTERNAL_STORAGE`.

## 5. Struttura dell'app

Tre sezioni principali, come RomM, in una bottom bar (o navigation rail laterale, vedi doc UI):

1. **Piattaforme** — griglia di card con l'icona ufficiale della piattaforma e il conteggio ROM. È la home.
2. **Collezioni** — collezioni utente, collezioni virtuali (franchise, genere, publisher, modalità) e smart collection.
3. **Cerca** — ricerca server-side su tutta la libreria.

Fuori dalla navigazione principale: **Download** (coda, accessibile da mini-barra persistente e da menu), **Impostazioni**.

## 6. Flussi

### 6.1 Onboarding (prima esecuzione)

Cinque passi, ognuno navigabile solo con gamepad, ognuno con "Indietro":

1. **Server** — campo URL (`http://192.168.50.11:8080`), normalizzazione automatica (aggiunge `http://` se manca, toglie lo slash finale), test di raggiungibilità in background su `GET /api/heartbeat` con indicatore di stato live (debounce 800 ms). Mostra versione RomM rilevata.
2. **Accesso** — username/password (`POST /api/login`) oppure **scansione QR** del Client API Token (`rmm_…`) generato dalla web app. Il QR è la strada preferita su handheld: scrivere una password con il D-pad è tortura. Se il device non ha fotocamera, resta il campo manuale.
3. **Launcher** — griglia di scelte: **ES-DE**, **Daijishō**, **Beacon**, **iiSU**, **Cocoon**, **Pegasus**, **Dig**, **Altro/Personalizzato**. Una riga di descrizione per ciascuno. La scelta determina il preset di cartelle (doc 05).
4. **Permesso storage e cartella ROM** — richiesta di `MANAGE_EXTERNAL_STORAGE` (via `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`) con spiegazione onesta del perché; se negato o non disponibile, fallback su SAF (`ACTION_OPEN_DOCUMENT_TREE`). Poi selezione/conferma della cartella ROM radice: l'app **propone** i percorsi noti del launcher scelto e quelli trovati sul device (memoria interna + SD), l'utente conferma con un tasto.
5. **Riepilogo e verifica** — l'app scansiona la cartella radice, associa le sottocartelle esistenti alle piattaforme del server (doc 05) e mostra una tabella `piattaforma RomM → cartella sul device` con lo stato: ✅ trovata, ➕ da creare, ✏️ da scegliere. L'utente può correggere ogni riga. Il "Fine" è abilitato anche con righe non risolte (si risolvono al primo download di quella piattaforma).

Regola: l'onboarding non può bloccarsi. Se il picker SAF non si apre (Android TV, Fire TV) o il permesso viene negato, l'app spiega il problema e offre la digitazione manuale del percorso assoluto.

### 6.2 Sfogliare e scaricare

`Piattaforme → [SNES] → griglia copertine → [gioco] → Scarica`

- La griglia mostra la copertina 2D presa dal server (nessun download preventivo di file di gioco).
- **Barra alfabetica a destra** (`# A B C … Z @`) sempre visibile in verticale, come nella web app: seleziona la lettera e la griglia salta a quell'offset. Alimentata da `char_index` dell'API, non calcolata in locale.
- Un tasto commuta **griglia ↔ lista** (la lista mostra nome, regione, dimensione, stato).
- Il download parte **senza chiedere nulla**: destinazione già decisa. Un toast dice dove è andato ("→ ROMs/snes").
- Se la piattaforma non ha ancora una cartella associata, e solo in quel caso, appare un dialog con proposta pronta ("Creo `ROMs/snes`?") con conferma di un tasto.

### 6.3 Gestione download

Mini-barra persistente in basso (sopra la nav): nome del file corrente, percentuale, velocità, `n in coda`. Selezionandola si apre la schermata Download con code Attivi / In attesa / Completati / Falliti, azioni per elemento (pausa, riprendi, annulla, riprova, apri cartella) e azione globale (pausa tutto, svuota completati).

I download sopravvivono all'uscita dall'app: coda persistita su DB, servizio in foreground con notifica.

## 7. Regole non negoziabili

1. **Mai un file parziale nella cartella del frontend.** Si scarica in cache privata dell'app come `.part`, si verifica, poi si sposta. Un ES-DE che scansiona a metà download non deve vedere nulla.
2. **Nomi di file: si usa `fs_name` del server, invariato.** È già il nome corretto (No-Intro/Redump); qualunque rinomina rompe lo scraping del frontend.
3. **Un gioco già presente non si riscarica.** Il controllo è per nome file esatto (più varianti estratte/compresse note), mai per sottostringa.
4. **Nessuna azione distruttiva senza conferma esplicita**, e mai su file che l'app non ha scaricato.
5. **Un gioco malformato non deve far cadere la lista.** Ogni item si renderizza in modo difensivo, campi opzionali nullable con default.
