# 02 — Design system e UI

Obiettivo dichiarato dall'utente: **riportare lo stile della web app RomM il più fedelmente possibile**, correggendo però il suo punto debole, l'adattamento a formati anomali. Quindi: colori, tipografia e componenti si copiano; il **layout si riprogetta** per essere davvero responsive.

## 1. Token colore (estratti da `rommapp/romm`, `frontend/src/styles/themes.ts` — usare questi valori esatti)

### Tema scuro (default dell'app)

| Token | Hex | Uso |
|---|---|---|
| `primary` | `#8B74E8` | accento principale, focus, azioni |
| `primary-lighten` | `#A18FFF` | anello di focus, hover, stati attivi |
| `primary-darken` | `#6043C8` | pressed, riempimenti |
| `secondary` | `#9E8CD6` | accento secondario |
| `secondary-lighten` | `#EBE7FA` | testo su superfici accentate |
| `secondary-darken` | `#7A6BB4` | |
| `accent` | `#E1A38D` | evidenziazioni rare (badge speciali) |
| `background` | `#0D1117` | sfondo schermate |
| `surface` | `#161B22` | card, barre |
| `toplayer` | `#1C2330` | dialog, menu, bottom sheet, mini-barra download |

### Tema chiaro (opzionale, stessa struttura)

`primary #371F69` · `secondary #553E98` · `accent #E1A38D` · `background #F2F4F8` · `surface #FFFFFF` · `toplayer #E4E9F0` · `primary-lighten #7850E6` · `primary-darken #452788`.

### Colori semantici comuni ai due temi

`romm-red #DA3633` (errori, download falliti) · `romm-green #3FB950` (completato, già presente) · `romm-blue #0070F3` (info, in corso) · `romm-white #FEFDFE` · `romm-gray #5D5D5D` (testo disabilitato, bordi) · `romm-black #000000` · `romm-gold #FFD700` (preferiti).

Implementazione: `ColorScheme` Material 3 costruito a mano da questi valori (non usare dynamic color/Monet: rompe l'identità RomM). `toplayer` non esiste in M3, va esposto come token extra via `CompositionLocal`.

## 2. Tipografia

RomM usa **Roboto** (variabile, pesi 100–900): è il font di sistema Android, quindi `FontFamily.Default` con i pesi giusti. Niente font custom da imbarcare.

| Ruolo | Size / peso | Note |
|---|---|---|
| Titolo schermata | 20 sp / Medium | 18 sp se `heightDp < 480` |
| Titolo sezione | 16 sp / Medium | |
| Nome gioco (card) | 13 sp / Normal, max 2 righe, ellissi | come RomM: sotto la copertina, centrato |
| Nome gioco (lista) | 15 sp / Normal | |
| Metadato secondario | 12 sp / Normal, opacità 0,7 | |
| Badge | 10 sp / Medium, maiuscolo | |

Rispettare il `fontScale` di sistema fino a 1.3; oltre, comprimere solo il testo secondario.

## 3. Forma, spaziatura, effetti

- Raggi: card copertina **8 dp**, card piattaforma **12 dp**, dialog **16 dp**, chip/badge **6 dp**, pill di stato **999 dp**.
- Griglia di spaziatura da 4 dp; padding standard 12 dp (8 dp se `heightDp < 480`).
- Elevazione simulata con le superfici, non con ombre pesanti: `surface` su `background`, `toplayer` su `surface`.
- **Scala al focus 1.07** con transizione 100 ms: è esattamente la regola `.transform-scale:hover/focus` di RomM (`common.css`), qui applicata al focus da gamepad.
- Testo sopra una copertina sempre con ombra `1px 1px 3px #000` (regola `.text-shadow` di RomM) oppure su fascia `translucent` (nero al 50%).

## 4. Componenti

### 4.1 PlatformCard
Card `surface`, icona piattaforma centrata (da `{base}/assets/platforms/{slug}.ico`, fallback icona generica), nome sotto (usare `display_name` o `custom_name`, **mai solo `name`**: le piattaforme custom mostrerebbero il nome sbagliato), pill con conteggio ROM in alto a destra. Rapporto della card 1:1. Al focus: scala 1.07 più anello `primary-lighten` 2 dp.

### 4.2 GameCard (griglia)
Copertina con rapporto **1 : 1,4** (larghezza : altezza), `ContentScale.Crop`, raggio 8 dp, scheletro mentre carica, nome sotto su 2 righe. Badge sovrapposti:

| Posizione | Badge |
|---|---|
| alto sinistra | regione (`US`/`EU`/`JP`) come nella web app |
| alto destra | icona piattaforma (solo nelle viste multi-piattaforma) |
| basso destra | numero versioni (siblings) se maggiore di 1 |
| overlay centro | stato: già presente sul device / in download con anello di progresso / in pausa / fallito |

### 4.3 GameRow (lista)
Altezza 56 dp: miniatura 40×56, nome, riga secondaria con `piattaforma · regione · dimensione`, stato a destra. Serve quando l'utente vuole densità (liste da migliaia di titoli).

### 4.4 AlphabetRail — la barra delle lettere
Colonna verticale a destra, larghezza 28 dp (32 dp se `widthDp >= 600`), voci `# A B … Z @`, lettera corrente evidenziata con pill `primary`: identica allo screenshot della web app.

- Sorgente dati: **`char_index` della risposta `/api/roms`** (mappa lettera → offset). Mai calcolarlo lato client sulla pagina corrente: la libreria è paginata.
- Le lettere senza risultati stanno al 30% di opacità e vengono saltate durante la navigazione.
- Da gamepad: **R2/L2** saltano alla lettera successiva/precedente senza spostare il focus sulla barra; la barra resta comunque raggiungibile col D-pad destro dalla colonna più a destra della griglia.
- Da touch: tap e trascinamento continuo con anteprima ingrandita della lettera.
- Il salto imposta l'offset di Paging e mostra un'etichetta fluttuante grande (48 sp) al centro per 600 ms.

### 4.5 DownloadMiniBar
Barra `toplayer` alta 44 dp ancorata sopra la navigazione, visibile solo con coda non vuota: nome file (ellissi), progresso `primary`, percentuale, velocità, contatore `+n`. Focusabile. Aggiornamento UI al massimo 2–4 volte al secondo, velocità mediata su 5 campioni.

### 4.6 FilterBar
Riga sopra la griglia: ordinamento (`name`, `first_release_date`, `average_rating`, `fs_size_bytes`, `created_at`), filtri (solo non scaricati / solo scaricati / regione), toggle griglia-lista, ricerca dentro la piattaforma. Su schermi bassi diventa una riga di sole icone con i dettagli in un bottom sheet.

### 4.7 Stati
Ogni lista implementa quattro stati distinti: **caricamento** (scheletri con la stessa geometria delle card, niente spinner a schermo pieno), **vuoto** (icona, frase, azione), **errore** (causa in italiano comprensibile più "Riprova" già a fuoco), **offline** (banner persistente, contenuti dalla cache).

## 5. Layout responsive

### 5.1 Il problema da risolvere

Non basta ragionare per larghezza. Un Retroid Pocket Classic è **472 × 411 dp** (1240×1080 a ~420 dpi): larghezza compatta ma **altezza ridicola**. Un RG Cube è circa 360 × 360 dp. Su questi schermi una top bar da 64 dp più una bottom bar da 80 dp mangiano oltre un terzo dello spazio utile. Per questo si classifica su **due assi**.

### 5.2 Classi

```
widthClass  : COMPACT (<600dp) | MEDIUM (600-839) | EXPANDED (>=840)
heightClass : SHORT (<480dp)   | TALL (>=480)
shape       : ratio = widthDp / heightDp
              PORTRAIT (<0,9) | SQUARE (0,9-1,25) | LANDSCAPE (>1,25)
isTv        : UiModeManager.currentModeType == UI_MODE_TYPE_TELEVISION
```

### 5.3 Regole di navigazione

| Condizione | Navigazione | Top bar |
|---|---|---|
| COMPACT + TALL (telefono verticale) | **BottomNavBar** 72 dp | 56 dp, titolo più azioni |
| `heightClass == SHORT` (RP Classic, RG Cube, handheld orizzontali) | **NavigationRail** a sinistra, 56 dp, sole icone, etichetta solo sull'elemento a fuoco | **40 dp**, solo titolo e 2 azioni |
| MEDIUM / EXPANDED | NavigationRail 80 dp con etichette | 56 dp |
| `isTv` | NavigationRail 96 dp, focus scalato 1.1, margine overscan 5% | 48 dp |

La rail sta **a sinistra** perché sugli handheld il pollice sinistro è sul D-pad: la navigazione fra sezioni deve stare dal lato del D-pad, mentre la barra delle lettere a destra sta dal lato dei tasti azione e dello stick destro. Questa simmetria è il motivo per cui questa disposizione batte il carosello di Argosy.

### 5.4 Griglie

Colonne calcolate con `GridCells.Adaptive(minCell)`, mai con numeri fissi:

| Contesto | `minCell` |
|---|---|
| copertine, `widthDp < 400` | 104 dp |
| copertine, 400–599 | 116 dp |
| copertine, 600–839 | 128 dp |
| copertine, >= 840 o TV | 148 dp |
| piattaforme | `minCell` copertine più 16 dp |

Correzioni:
- se `heightClass == SHORT`, ridurre `minCell` di 8 dp (più colonne, meno scroll su schermo basso);
- lo spazio della `AlphabetRail` va sottratto prima del calcolo delle colonne, mai sovrapposto;
- limite duro: minimo 2 colonne, massimo 8.

### 5.5 Due pannelli
Con `widthClass == EXPANDED` e `shape == LANDSCAPE`, la schermata piattaforma usa due pannelli: elenco piattaforme a sinistra (280 dp), griglia giochi a destra. In tutti gli altri casi, pagine intere.

### 5.6 Insets e schermi anomali
- Edge-to-edge obbligatorio (Android 15 lo impone): `WindowInsets.safeDrawing` su ogni schermata, mai padding fissi per la status bar.
- Impostazione **"Margine schermo" 0–16 dp** per handheld con angoli molto arrotondati o cornici asimmetriche e per l'overscan TV.
- Nessuna dipendenza dallo stato `hover`: su TV e handheld non esiste.
- Testare anche a schermo ruotato: gli handheld verticali vengono usati in orizzontale nel dock.

## 6. Gamepad

### 6.1 Mappatura

| Input | Azione |
|---|---|
| D-pad / stick sinistro | sposta il focus (stick con deadzone 0,5, ripetizione ogni 120 ms) |
| Stick destro | scroll continuo della lista |
| A (`BUTTON_A`) | conferma / apri |
| B (`BUTTON_B`) | indietro |
| X (`BUTTON_X`) | **scarica** l'elemento a fuoco |
| Y (`BUTTON_Y`) | commuta griglia / lista |
| L1 / R1 | sezione precedente / successiva (Piattaforme, Collezioni, Cerca) |
| L2 / R2 | lettera precedente / successiva (`char_index`) |
| L3 | apri ricerca |
| R3 | apri schermata Download |
| Start | menu contestuale dell'elemento a fuoco |
| Select | filtri e ordinamento |

Impostazione **"Scambia A/B"** (layout Nintendo contro Xbox), default Xbox, con rilevamento del device dove possibile. Serve anche una schermata di test input che mostri i keycode ricevuti: gli handheld cinesi mappano male e l'utente deve poterlo diagnosticare da solo.

### 6.2 Regole di focus

- Ogni elemento interattivo è focusabile e ha un indicatore visibile: anello `primary-lighten` 2 dp più scala 1.07. Mai affidarsi al solo colore.
- `Modifier.focusRestorer()` su ogni lista: tornando indietro il focus ricade sull'elemento da cui si è usciti, mai in cima.
- Il focus non deve mai finire in un vicolo cieco: navigazione direzionale esplicita (`focusProperties { left = ...; right = ... }`) fra rail, contenuto e barra lettere.
- L'elemento a fuoco viene sempre portato in vista con almeno una riga di margine (`bringIntoViewRequester`).
- Tasto tenuto premuto: primo ritardo 400 ms, poi 60 ms; dopo 1,5 s di pressione continua sulla griglia si passa allo **scroll a pagine** invece che a righe.
- All'apertura di dialog e bottom sheet il focus entra dentro e non ne esce se non con B.

### 6.3 Criterio di verifica
Requisito di accettazione: **completare onboarding, scaricare un gioco e aprire la coda senza toccare lo schermo**, su un device con soli controlli fisici.
