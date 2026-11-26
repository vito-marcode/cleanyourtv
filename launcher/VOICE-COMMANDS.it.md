# Comandi vocali per il launcher (ramo `voice-commands-upstream`)

Una funzione non ufficiale, aggiunta sopra il launcher di CleanYourTV: si preme il tasto microfono del telecomando,
in qualsiasi app, e si dà un comando a voce in italiano. In basso compare una finestrella "Ti ascolto…"; l'app sotto
continua a funzionare. Il riconoscimento è quello di Google già presente sulla TV.

Provato su due box TIM: Android 8 (tasto microfono) e Android 12 (pressione lunga di Home, perché lì il tasto Google
va sempre all'Assistente). **Non ancora provato su una TV TCL**: vedi [Limiti](#limiti).

## Cosa si può dire

| Esempio | Cosa fa |
|---|---|
| "gattini su YouTube", "cerca gatti su YouTube", "cerca un video di Vasco Rossi" | Apre **SmartTube** sui risultati (il primo video è già selezionato: OK per guardarlo). Senza SmartTube apre YouTube. |
| "Vasco Rossi su Spotify", "metti gli 883 su Spotify" | **Spotify** cerca e avvia il primo risultato |
| "Montalbano su RaiPlay", "cerca Doc su RaiPlay" | Cerca dentro l'app nominata, se accetta la ricerca di Android (RaiPlay sì). Netflix: apre la sua ricerca. Le altre app (Mediaset Infinity) si aprono soltanto |
| "cerca Montalbano" (senza app) | La **ricerca di Google** della TV, con risultati da tutte le app |
| "apri Netflix", "Sky", "metti Prime Video" | Apre l'app installata con quel nome ("YouTube" apre SmartTube). Una parola da sola apre l'app, se c'è |
| "vai alla home", "indietro", "impostazioni", "salvaschermo" | Le azioni del telecomando |
| "muto", "alza il volume", "abbassa il volume", "volume al massimo", "volume a metà", "volume a 8" | Il volume (ogni comando di volume toglie anche il muto) |

Mentre ascolta, il volume si abbassa e torna com'era subito dopo.

Come si usa: **premi il microfono, lascialo e parla**. La frase finisce quando smetti di parlare, oppure premendo
di nuovo il microfono. Indietro chiude la finestrella. Se un telecomando trasmette l'audio solo a tasto premuto,
tienilo premuto mentre parli: il rilascio non interrompe niente.

## Installazione

Serve il repo CleanYourTV già usato per installare il launcher (computer con JDK 17 e Android SDK, oppure Docker:
vedi `tools/docker-build.md`), con la TV raggiungibile via ADB.

1. **Prendi questo ramo** e compila e installa il launcher **con lo script del repo**, non con un APK fatto da
   qualcun altro: lo script mantiene la tua firma e la home della TV al sicuro.

   ```bash
   git clone https://github.com/vito-marcode/cleanyourtv.git
   cd cleanyourtv
   git checkout voice-commands-upstream
   python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt
   export TV_HOST=192.168.1.50                      # l'IP della TV
   INPUT_KEY_SERVICE=1 launcher/build-and-install.sh
   ```

   `INPUT_KEY_SERVICE=1` accende il servizio che riceve i tasti del telecomando in tutte le app (serve anche alla voce).

2. **Concedi il microfono** al launcher (non c'è una richiesta a schermo):

   ```bash
   tools/venv/bin/python -I tools/tv.py 'pm grant com.gathin.tvhome android.permission.RECORD_AUDIO'
   ```

3. **Trova lo scancode del tasto microfono**: lancia questo comando, premi una volta il microfono sul telecomando,
   poi Ctrl+C.

   ```bash
   tools/venv/bin/python -I tools/tv.py 'getevent -t'
   ```

   Compare una riga come `/dev/input/event4: 0001 00d9 00000001`: `0001` vuol dire tasto, `00d9` è lo scancode in
   esadecimale, `00000001` è la pressione. Convertilo in decimale (`00d9` = 217; per esempio
   `python3 -c "print(0x00d9)"`). Lo scancode funziona qualunque sia la mappatura del telecomando.

4. **Collega il tasto alla voce** nel `config.json` del launcher, aggiungendo una riga ad `app_keys`
   (lascia le altre come sono):

   ```bash
   tools/venv/bin/python -I tools/tv.py --pull /sdcard/Android/data/com.gathin.tvhome/files/config.json config.json
   # modifica config.json: "app_keys": ["...", "scan:217:voice"]
   tools/venv/bin/python -I tools/tv.py --push config.json /sdcard/Android/data/com.gathin.tvhome/files/config.json
   tools/venv/bin/python -I tools/theme_restart.py
   ```

   Al posto di `217` metti lo scancode trovato al punto 3. Se `config.json` non esiste ancora, crealo con solo
   `{"app_keys": ["scan:217:voice"]}`. `theme_restart.py` riavvia la home e ricollega i servizi dei tasti, così il
   launcher rilegge la configurazione.

5. **Prova** dalla home: microfono, poi "cerca gatti".

## Limiti

- **Il tasto microfono può essere riservato a Google Assistant.** Su molte TV certificate Google il sistema lo prende
  prima del launcher e la finestrella non compare. In quel caso usa la pressione lunga di un altro tasto, per esempio
  Home: `"KEYCODE_HOME:launcher"` e `"hold:KEYCODE_HOME:voice"` (il tocco di Home apre la home come prima; tenendolo
  mezzo secondo compare "Ti ascolto…": a quel punto lascialo e parla). Oppure un tasto colorato:
  `"hold:KEYCODE_PROG_RED:voice"`. Se `getevent` non mostra niente
  quando premi il microfono, il tasto viaggia per un'altra strada (per esempio solo verso Google) e va usato un altro tasto.
- **Android 11 e successivi non sono provati.** Dalla home dovrebbe funzionare; con un'altra app in primo piano Google
  potrebbe rifiutare di registrare per conto del launcher ("Microfono non autorizzato").
- I comandi sono **solo in italiano**. Serve la connessione a internet (il riconoscimento è quello online di Google).
- "Metti Rai 2" e i comandi sui canali sono pensati per l'app Canali TV di un box TIM: su un'altra TV non trovano il
  canale e provano ad aprire un'app con quel nome.
- L'APK supera il limite di peso che il progetto impone alle pull request: va bene per uso privato, non è pensato
  per essere proposto al progetto ufficiale.

## Per tornare indietro

Togli la riga `...:voice` da `app_keys` e rilancia `tools/theme_restart.py`; per il launcher ufficiale, ricompila dal
ramo `main` del repo originale con lo stesso `launcher/build-and-install.sh`.
