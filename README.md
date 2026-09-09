# RomMMobile — specifica di progetto

App Android companion per **RomM** self-hosted. Sfoglia la libreria e **scarica le ROM direttamente nella cartella corretta del frontend/launcher già installato sul device**. Non lancia i giochi.

Questo repo contiene **solo la specifica**: va data in pasto al modello che implementerà il codice (Fable 5.1). L'ordine di lettura è quello dei numeri.

| File | Cosa contiene |
|---|---|
| [docs/01-SPEC-PRODOTTO.md](docs/01-SPEC-PRODOTTO.md) | Cosa fa l'app, cosa NON fa, flussi utente, onboarding |
| [docs/02-UI-DESIGN-SYSTEM.md](docs/02-UI-DESIGN-SYSTEM.md) | Copia dello stile RomM (token esatti), layout responsive, schermi quadrati, gamepad |
| [docs/03-ARCHITETTURA.md](docs/03-ARCHITETTURA.md) | Stack, moduli, storage, motore di download |
| [docs/04-API-ROMM.md](docs/04-API-ROMM.md) | Contratto API RomM 5.x verificato |
| [docs/05-LAUNCHER-MAPPING.md](docs/05-LAUNCHER-MAPPING.md) | Il problema delle cartelle e la sua soluzione completa |
| [docs/06-ROADMAP.md](docs/06-ROADMAP.md) | Milestone, criteri di accettazione, matrice di test |
| [assets/platform_map.json](assets/platform_map.json) | Tabella di mapping piattaforme (va in `app/src/main/assets/`) |

Materiale di supporto **già prodotto in sessioni precedenti, da leggere prima di scrivere codice**:

- `..\STUDIO-ROMM-MOBILE.md` — studio del codice di `mattsays/romm-mobile` (MIT): cosa copiare, quali bug non ripetere, lezioni dalle 32 issue aperte.
- `..\romm-mobile-reference\` — clone locale della stessa app (React Native, MIT). Va usata come riferimento di comportamento, **non** di stack.
- `rommapp/argosy-launcher` (GPL-3.0, Kotlin/Compose, 2.12.0, minSdk 26, compileSdk 35) — client RomM ufficiale gamepad-first. Stack di riferimento confermato: Compose + Hilt + Room + DataStore + Coil + WorkManager + `androidx.tv.material`. **Attenzione alla licenza: GPL-3.0, non copiare codice** — solo studio del comportamento. La sua UI a carosello è esplicitamente NON quello che vogliamo.

## Prompt di partenza suggerito per l'implementatore

> Leggi tutti i file in `RomMMobile/docs/` in ordine, più `STUDIO-ROMM-MOBILE.md`. Poi implementa la Milestone M0 e M1 di `06-ROADMAP.md`. Non deviare dai token grafici di `02-UI-DESIGN-SYSTEM.md` e non introdurre il lancio dei giochi. Prima di ogni assunzione sull'API, verificala su `http://192.168.50.11:8080/openapi.json`.

## Ambiente di riferimento dell'utente

- Server: RomM 5.2 in LXC su Proxmox, `http://192.168.50.11:8080` (LAN 192.168.50.x, HTTP in chiaro).
- Libreria: 18.430 ROM, 78 piattaforme, `library/roms/<slug>/`.
- Accesso remoto futuro: dominio via Cloudflare Tunnel (HTTPS) → l'app deve reggere entrambi i casi senza riconfigurazioni manuali.
