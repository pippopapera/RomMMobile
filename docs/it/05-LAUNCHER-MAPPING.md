# 05 — Launcher, cartelle e mapping delle piattaforme

Questo è il cuore del progetto: **è il problema che l'app esiste per risolvere**.

## 1. Il problema

RomM organizza la libreria come `library/roms/<slug>/`, dove lo slug segue la nomenclatura IGDB (`sms`, `genesis`, `neo-geo-pocket-color`, `sega32`, `zxs`…). Ogni frontend Android usa invece le proprie convenzioni: ES-DE vuole `mastersystem`, `megadrive`, `ngpc`, `sega32x`, `zxspectrum`; Daijishō, Beacon e iiSU lasciano scegliere all'utente una cartella per piattaforma, quindi la convenzione è quella che l'utente si è costruito nel tempo.

Risultato con qualunque client generico: a ogni download l'app chiede dove mettere il file, oppure lo mette in una cartella che il frontend non guarda. L'utente vuole che questo **sparisca completamente**.

## 2. Soluzione: risoluzione a cascata

Per ogni piattaforma, la cartella di destinazione si risolve in quest'ordine e il risultato si memorizza in `FolderMappingEntity`:

1. **Override utente** (`source = USER`) — vince sempre, non viene mai riscritto in automatico.
2. **Cartella già esistente sul device** (`source = DISCOVERED`) — si elencano le sottocartelle della radice ROM e si confrontano con gli alias della piattaforma, senza distinzione di maiuscole e ignorando spazi, trattini e underscore. È il caso più frequente: chi ha già un frontend configurato ha già le cartelle giuste.
3. **Preset del launcher scelto** (`source = PRESET`) — dal `platform_map.json`, campo del launcher selezionato in onboarding.
4. **Creazione** (`source = CREATED`) — se non esiste nulla, si propone la cartella del preset con un dialog a conferma singola. Solo qui l'utente vede una domanda, e una volta sola per piattaforma.

Regole aggiuntive:

- La corrispondenza a più candidati (es. esistono sia `megadrive` sia `genesis`) va risolta chiedendo una volta, mostrando quante ROM contiene ciascuna cartella: la più popolata è preselezionata.
- Il mapping è **modificabile in ogni momento** da Impostazioni → Cartelle piattaforme, con una tabella `piattaforma → cartella → numero di file trovati`.
- Cambiare launcher in un secondo momento **non cancella** gli override utente: rilancia solo la fase 2 e 3.

### Pseudocodice

```kotlin
fun resolveFolder(platform: Platform): FolderMapping? {
    userOverride(platform.slug)?.let { return it }
    val entry = platformMap[platform.slug]              // può essere null
    val aliases = buildSet {
        add(platform.slug)
        entry?.let { addAll(listOfNotNull(it.esde) + it.esdeAlt + it.aliases) }
    }.map(::normalize)                                   // lowercase, via spazi/-/_
    val existing = fileGateway.list(root).filter { it.isDirectory }
    val hits = existing.filter { normalize(it.name) in aliases }
    when {
        hits.size == 1 -> return save(platform.slug, hits.first(), DISCOVERED)
        hits.size > 1  -> return askUser(hits)            // preselezione: più file dentro
    }
    val preset = entry?.folderFor(selectedLauncher) ?: platform.slug
    return proposeCreate(preset)                          // conferma singola
}
```

## 3. Launcher supportati

| Launcher | Radice tipica | Convenzione cartelle | Metadati | Affidabilità del preset |
|---|---|---|---|---|
| **ES-DE** | `/storage/emulated/0/ROMs` | **fissa e nota** (`snes`, `megadrive`, `gc`…), minuscolo | `ES-DE/gamelists/<sys>/gamelist.xml` + `ES-DE/downloaded_media/<sys>/` | alta: mappata da `es_systems.xml` ufficiale |
| **Cocoon** | come ES-DE | importa nativamente la struttura ES-DE (ES-DE Migration/Link) | formato ES-DE | alta: eredita il preset ES-DE |
| **Daijishō** | `/storage/emulated/0/Roms` (varia) | **definita dall'utente**: ogni piattaforma ha uno o più "sync path" | database interno, scraping proprio | media: si propone il nome breve comune, si conta sulla scoperta automatica |
| **Beacon** | variabile | definita dall'utente, una cartella per piattaforma | interni | media |
| **iiSU** | variabile; asset in `/storage/emulated/0/Android/media/com.iisulauncher/iiSULauncher/assets/` | definita dall'utente; sa importare i metadati ES-DE | asset gestiti a mano dall'utente | media |
| **Pegasus** | variabile | definita dall'utente | `metadata.pegasus.txt` per cartella | media |
| **Dig** | variabile | definita dall'utente | interni | bassa (progetto fermo) |
| **Personalizzato / solo RetroArch** | scelta libera | slug RomM invariato | nessuno | — |

Per i launcher con convenzione libera il preset usa il **nome breve comune** (colonna `generic` del `platform_map.json`, che coincide quasi sempre con lo slug RomM): tanto il caso reale è che l'utente abbia già le cartelle, quindi vince la scoperta automatica.

**Da verificare sul device al primo avvio, non dare per scontato:** ES-DE su Android chiede la cartella ROM al primo avvio e l'utente può averla messa su SD (`/storage/XXXX-XXXX/ROMs`). L'app deve cercare la radice anche sulla scheda SD e proporre entrambe.

## 4. `platform_map.json`

File in `app/src/main/assets/platform_map.json` (copia in `assets/` di questo repo). Struttura:

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

- `slug`: slug RomM/IGDB. Le voci coprono le 78 piattaforme realmente presenti nella libreria dell'utente più le più comuni non ancora presenti.
- `esde`: cartella ES-DE ufficiale, estratta da `resources/systems/android/es_systems.xml` del repo ES-DE.
- `esdeAlt`: alternative accettate da ES-DE (varianti regionali come `genesis`/`megadrive`, `segacd`/`megacd`, `tg16`/`pcengine`): valgono come alias in scoperta.
- `esde: null` significa che ES-DE non ha quel sistema: l'app usa `generic`, avvisa l'utente che il frontend potrebbe non mostrarlo e non tratta la cosa come errore.
- Il file **non è autorevole sull'elenco delle piattaforme**: quello arriva da `GET /api/platforms`. Uno slug sconosciuto non è un errore, si ricade su `slug` e sulla scoperta automatica.
- Il file deve poter essere aggiornato senza ricompilare: previsto override in `Android/data/<pkg>/files/platform_map.json` letto se presente.

## 5. Regole sui file scaricati

1. **Nome invariato**: si usa `fs_name` del server. È già No-Intro/Redump e ogni frontend ci fa lo scraping sopra.
2. **Archivi**: `.zip` estratto di default; `.7z` lasciato intero di default (ES-DE e RetroArch lo leggono; estrarlo peggiora e basta). Entrambi i comportamenti sono impostabili, anche per singola piattaforma.
3. **Giochi multi-file / multi-disco**: RomM li serve come zip al volo contenente un `.m3u`. Destinazione: **sottocartella per gioco** dentro la cartella di piattaforma (`ROMs/psx/Final Fantasy VII/` con dentro i `.chd`/`.bin`/`.cue` e il `.m3u`). ES-DE gestisce correttamente sia il `.m3u` nella cartella di sistema sia la sottocartella; il `.m3u` va comunque lasciato dove il frontend lo vede. Comportamento configurabile per piattaforma (`flat` o `folder-per-game`), default `folder-per-game` per psx, ps2, saturn, segacd, dreamcast, pcenginecd, 3do, neogeocd.
4. **Nessun file parziale** nella cartella del frontend: si scrive in cache privata, si verifica, si sposta.
5. **Duplicati**: se esiste già un file con lo stesso nome (o la versione estratta o compressa dello stesso nome), il download viene marcato "già presente" e saltato, con possibilità di forzare.
6. **BIOS/firmware**: sezione separata. Sorgente `GET /api/firmware`, destinazione **`RetroArch/system/`** (percorso configurabile), non le cartelle di piattaforma. L'utente ha già un pack BIOS completo per RetroArch: la funzione serve solo a colmare i buchi, quindi mai sovrascrivere un file esistente senza chiedere.

## 6. Metadati per il frontend (post-v1, milestone M4)

Nessun frontend Android espone un'API per registrare un gioco. Il contratto universale è: **file con l'estensione giusta nella cartella osservata, più una nuova scansione del frontend**. ES-DE riscansiona a ogni avvio.

Per andare oltre (copertine e descrizioni senza far scrapare il frontend) il formato di riferimento è quello **ES-DE**, perché Cocoon lo importa e iiSU sa leggerlo:

- `ES-DE/gamelists/<sistema>/gamelist.xml` — voci `<game>` con `<path>`, `<name>`, `<desc>`, `<releasedate>`, `<developer>`, `<publisher>`, `<genre>`, `<image>`.
- `ES-DE/downloaded_media/<sistema>/covers/<nome file ROM>.jpg` e `screenshots/`, associazione **per nome del file ROM**.
- **Scrivere il `gamelist.xml` solo a ES-DE chiuso** e sempre con scrittura atomica (file temporaneo più rinomina), fondendo le voci esistenti invece di sovrascrivere il file.
