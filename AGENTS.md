# AGENTS.md вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә Ventix Arvio Addon

Dieses Repo baut ein **Cloudstream3-kompatibles Plugin** fвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—r die **ARVIO** Android-TV-App (sideload-APK).
Ziel: Ventix-FunktionalitвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®t (deutsche Web-Scraper + Stalker-VOD) als Plugin in ARVIO laufen lassen вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә clientseitig, ohne Server.

---

## вҖјпёҸ NEUESTER STAND (18.08.2026, Ende Session вҖ” VOR ALLEM ANDEREN LESEN)

**DREI Scraper liefern Quellen (stabil, getestet), SERIENSTREAM BLOCKIERT (Turnstile-iframe rendert nicht).**

**Builds-Branch (nach CI-Push von Commit da36007):**
- **KinoGer.cs3 v11** (status=1) вҖ” вң… liefert 3 Quellen (Incvideo 360p/720p/1080p MP4).
- **FilmPalast.cs3 v37** (status=1) вҖ” вң… liefert 2 Quellen (Odysseusa 720p m3u8, Vidsonic 720p m3u8).
- **Vavoo.cs3 v5** (status=1) вҖ” вң… liefert 6 Mirrors (API-basiert, MediaHubMX). Vavoo-Module gelГӨuft seit v48 (Download + load + 30 episodes + ping + mediahubmx-source.json + 6 Mirrors). Hoster-Extraktion: vidoza/dood 403/404 (DDoS-Guard), Rest via genericResolve.
- **Serienstream.cs3 v53** (status=1) вҖ” вқҢ Turnstile-iframe rendert nicht. **Strategie D + shouldInterceptRequest-Bridge implementiert, aber Widget blockiert.**

### SERIENSTREAM TURNSTILE-BYPASS вҖ” VOLLSTГ„NDIGE DOKUMENTATION (Stand 18.08.2026)

**Das Ziel:** Serienstream.to nutzt fГјr /r?-Redirect-Gate `turnstile_altcha` (Cloudflare Turnstile + ALTCHA-PoW). Hoster-URLs sind hinter diesem Gate versteckt. Ohne LГ¶sung = 0 Quellen.

**ARCHITEKTUR (Strategie D, implementiert in v45-v53):**
- `TurnstileSolver.kt` nutzt echten Android WebView, um Serienstreams EIGENE Gate-JS laufen zu lassen.
- Flow: WebView lГӨdt Episode-Seite вҶ’ injiziert "driver-JS" вҶ’ driver-JS setzt `player-iframe.src = /r?t=...` вҶ’ iframe lГӨdt Gate-Seite вҶ’ iframe postMessage({type:"frameBridge", v:1, t:"<prepare-token>"}) an Parent вҶ’ Serienstreams eigene Gate-JS rendert Turnstile + ALTCHA in Modal вҶ’ driver-JS pollt bis beide Tokens da вҶ’ driver-JS submittet Form (POST /r) вҶ’ iframe lГӨdt Antwort вҶ’ postMessage({t:"<hoster-url>"}) вҶ’ driver-JS gibt hoster-url an Kotlin via Bridge.
- **WICHTIG:** /r?t= MUSS im iframe geladen werden (nicht top-level!). Top-level = Redirect zur Startseite. (Erkenntnis v47.)
- **WICHTIG:** data-play-url ist RELATIV (/r?t=...). WebView.loadUrl macht KEINE relative AuflГ¶sung (interpretiert als file:///). Muss via `new URL(pu, window.location.origin).href` im JS zu absolutem URL machen. (Erkenntnis v46.)

**was FUNKTIONIERT (v53 bestГӨtigt):**
- ✅ driver-JS lГӨuft (Log: `driver start`, `form found=true`, `iframe found=true`)
- ✅ iframe lГӨdt, `frameBridge: t=... err=` postMessage kommt an (nach ~150ms)
- ✅ Gate-JS initialisiert (`gateInit=1`), Modal sichtbar (`modal=show`)
- ✅ Turnstile-API geladen (`tsApi=yes`, `tsScript=1`), `turnstile.render()` aufgerufen (hidden input `cf-turnstile-response` im DOM)
- ✅ **shouldInterceptRequest-DoH-Bridge FUNKTIONIERT**: `interceptCloudflareChallenge: brunhild.challenges.cloudflare.com -> 204 (0B) via 104.18.94.41 SNI=brunhild.challenges.cloudflare.com` (kein ERR_NAME_NOT_RESOLVED mehr!)

**was NICHT FUNKTIONIERT (v53 bestГӨtigt, offen):**
- вқҢ `tpIF=0` (konstant Гјber alle 8 diag-Versuche): Turnstile-Widget rendert KEINEN iframe. `turnstile.render()` wurde aufgerufen (hidden input da), aber der challenge-iframe fehlt. Das Widget bleibt im "Loading"-Zustand, kein Token.
- вқҢ `ts=` (cf-turnstile-response bleibt leer): kein Token.
- вқҢ `alScript=0`, `apW=0`: ALTCHA-Widget wird GAR NICHT geladen. Kein `<script src*="altcha">`, kein `<altcha-widget>` custom element im DOM.
- вқҢ Cloudflare liefert HTTP 204 (No Content, 0 Bytes) fГјr die brunhild-Challenge-Ressource. Erwartet wГӨre HTTP 200 mit Challenge-Script. MГ¶gliche Ursache: fehlende Cookies, fehlende Headers, oder der TrustManager (akzeptiert alle Zertifikate) stГ¶rt.

**DIAGNOSE-LOGS (v53, SchlГјsselzeilen):**
```
episode page ready (form found, __ddg=true), starting gate flow: https://serienstream.to/r?t=...
gate driver JS injected
bridge.log: frameBridge: t=eyJpdiI6... err=
interceptCloudflareChallenge: brunhild.challenges.cloudflare.com/... -> 204 (0B) via 104.18.94.41 SNI=brunhild.challenges.cloudflare.com
diag try=1/11/21/.../71: ts= al= tpIF=0 tsApi=yes tsScript=1 alScript=0 gateInit=1 apW=0 modal=show err=
tpHtml=<div><input type="hidden" name="cf-turnstile-response" id="cf-chl-widget-ujuyf_response"></div>
submit poll timeout
```

### NAECHSTE SCHRITTE FГңR SERIENSTREAM (Prio 1, Reihenfolge beachten)

**Problem 1 (hГ¶chste Prio): Turnstile-iframe rendert nicht (tpIF=0).**
- Hypothese A: HTTP 204 statt 200. Die shouldInterceptRequest-Bridge liefert eine leere Antwort. Cloudflare erwartet vielleicht echte Challenge-Daten. PrГјfen: ob Cookies weitergegeben werden (Cookie-Header im Request?), ob der TrustManager stГ¶rt, ob der SNI/Host-Header korrekt ist, ob HTTP/2 nГ¶tig ist (wir nutzen HTTP/1.1).
- Hypothese B: Die IP des Handys wird als "medium-trust" eingestuft, und das Widget braucht eine sichtbare Challenge (Bilderauswahl), die im unsichtbaren WebView nicht gelГ¶st werden kann. Test: WebView sichtbar machen? Nicht mГ¶glich im Plugin.
- Hypothese C: Der WebView-Cookie-Speicher und der shouldInterceptRequest-Cookie-Header sind getrennt. Die Challenge-Ressource braucht vielleicht DDoS-Guard-Cookies (`__ddg*`), die im WebView gesetzt wurden, aber im shouldInterceptRequest nicht weitergegeben werden.
- **Fix-Versuche (Reihenfolge):**
  1. PrГјfen, welche Cookies der WebView fГјr *.challenges.cloudflare.com gesetzt hat (CookieManager.getCookie), und sie im shouldInterceptRequest als Cookie-Header mitsenden. (Aktuell wird nur der Cookie aus request.requestHeaders weitergegeben вҖ” aber der WebView setzt die Cookies vielleicht nicht in requestHeaders.)
  2. PrГјfen, ob HTTP/2 nГ¶tig ist. Cloudflare liefert Гјber HTTP/2 vielleicht andere Antworten. Wir nutzen HTTP/1.1 Гјber den raw socket.
  3. PrГјfen, ob der TrustManager (akzeptiert alle Zertifikate) das Problem ist. Echten TrustManager nutzen (system default).
  4. Alternativ: die gesamte Challenge-Ressource Гјber java.net + DohHttp laden (wie die anderen Scraper), nicht Гјber raw socket. DohHttp hat schon SNI + Cookie + HTTP/2 (Гјber HttpURLConnection). Aber: HttpURLConnection nutzt HTTP/1.1, kein HTTP/2.
  5. Diagnose: den Response-Body der 204-Antwort loggen ( aktuell 0 Bytes, aber vielleicht sind es gar nicht 0 Bytes, sondern der InputStream ist geschlossen).

**Problem 2: ALTCHA-Widget fehlt (alScript=0, apW=0).**
- Das Gate-JS ruft `await x()` auf, das `import("./altcha-BhBXWxP7.js")` macht (ES-Module-Import). Der Import schlГӨgt vielleicht fehl (CSP, oder der WebView unterstГјtzt keine ES-Module, oder der relative Pfad ist falsch).
- PrГјfung: loggen, ob der Import fehlschlГӨgt (console.error abfangen). Oder: ALTCHA manuell nachladen (script-tag fГјr altcha-BhBXWxP7.js injizieren).
- Bedingung im Gate-JS: `if("turnstile_altcha"===c&&y&&m&&window.isSecureContext)`. PrГјfen, ob `window.isSecureContext` true ist (sollte true sein bei HTTPS, aber WebView verhГӨlt sich evtl. anders).
- **Fix-Versuche:**
  1. `window.isSecureContext` in diag loggen. Wenn false: WebView-Konfiguration anpassen.
  2. ALTCHA-Modul manuell laden: script-tag fГјr `https://serienstream.to/build/assets/altcha-BhBXWxP7.js` injizieren, dann das `<altcha-widget>` custom element manuell erstellen.
  3. ALTCHA selbst lГ¶sen (PoW in Kotlin, wie in alter solveAltcha-Methode) und das Form-Feld `altcha` manuell setzen. Das umgeht das ALTCHA-Widget komplett.

**Problem 3 (fallback): Falls Turnstile nicht lГ¶sbar, ALTCHA allein nutzen.**
- Das Gate heiГҹt `turnstile_altcha` вҖ” es braucht BEIDE. Ohne Turnstile-Token lehnt der Server ab. ALTCHA allein reicht nicht (in v32 bestГӨtigt: "Das hat leider nicht geklappt").
- Falls Turnstile gar nicht lГ¶sbar: Serienstream DEAKTIVIEREN (wie in v39, status=0 + registerMainAPI auskommentiert). Provider-Code + TurnstileSolver + Bridge BEHALTEN fГјr spГӨtere Reaktivierung.

### TECHNISCHE DETAILS FГңR DIE NГ„CHSTE SESSION

**WICHTIGE DATEIEN:**
- `Serienstream/src/main/kotlin/com/reichi/arflioaddon/serienstream/TurnstileSolver.kt` вҖ” der ganze WebView-Flow (solveGate, checkEpisodePageReadyAndStartGate, buildGateDriverJs, interceptCloudflareChallenge, readLineFromStream, resolveAgainstEpisode). Version in `Serienstream/build.gradle.kts` (aktuell v53).
- `Serienstream/src/main/kotlin/com/reichi/arflioaddon/serienstream/SerienstreamProvider.kt` вҖ” ruft `TurnstileSolver.solveGate(episodePageUrl, hosterIndex)` in resolveHost. Hoster-AuflГ¶sung SEQUENTIELL (nicht parallel, sonst WebView-Race).
- `Serienstream/src/main/kotlin/com/reichi/arflioaddon/serienstream/SerienstreamPlugin.kt` вҖ” init(context) speichert Activity-Context fГјr WebView.

**shouldInterceptRequest-Logik (v53):**
- FГӨngt alle URLs mit `.challenges.cloudflare.com` ab (Suffix-Match).
- LГӨdt die Ressource Гјber einen raw TLS-Socket (SSLSocket) mit:
  - IP = `challenges.cloudflare.com` (via `InetAddress.getByName`, Fallback 104.18.94.41)
  - SNI = originaler Host (z.B. brunhild.challenges.cloudflare.com) via `params.serverNames = listOf(SNIHostName(host))` (API 24+)
  - Host-Header = originaler Host
  - TrustManager akzeptiert ALLE Zertifikate (X509TrustManager mit leeren Methoden)
  - HTTP/1.1, Connection: close
  - Header: User-Agent, Accept, Referer, Cookie (aus request.requestHeaders)
- Antwort: parsed status line + headers + body, zurГјck als `WebResourceResponse` mit `setStatusCodeAndReasonPhrase`.
- Log: `interceptCloudflareChallenge: <url> -> <status> (<bytes>B) via <ip> SNI=<host>`

**driver-JS (buildGateDriverJs, v53):**
- Injiziert via `webView.evaluateJavascript(driverJs)`.
- Loggt via `console.log` (abgefangen von `WebChromeClient.onConsoleMessage`, zuverlГӨssig, R8-unabhГӨngig) UND `Bridge.onLog` (JavascriptInterface, als Fallback).
- Ergebnis via `Bridge.onResult(url)` (JavascriptInterface) mit `console.log('DONE:'+url)` als Fallback.
- WICHTIG: console.log ist zuverlГӨssiger als Bridge (R8 kann JavascriptInterface @Annotationen strippen вҖ” Erkenntnis v50).
- driver-JS muss syntaktisch korrekt sein! (v48-v50 scheiterten an SyntaxError in verschachteltem Ternary вҖ” immer `node --check` vor Build!)
- diag-Felder: `ts= al= tpIF= tsApi= tsScript= alScript= gateInit= apW= modal= err= tpHtml=`

**diagnose-polling (v52):**
- Alle 10 tries (5 Sekunden) loggt der driver-JS den vollen Zustand:
  - tpIF: iframe im turnstile-div? (0/1)
  - tsApi: typeof window.turnstile !== 'undefined'? (yes/no)
  - tsScript: <script src*=challenges.cloudflare.com> vorhanden? (0/1)
  - alScript: <script src*=altcha> vorhanden? (0/1)
  - gateInit: data-episode-redirect-gate-init auf root? (0/1/?)
  - apW: <altcha-widget> im altcha-div? (0/1)
  - modal: playerPrepareModal.classList.contains('show')? (show/hidden)
  - err: player-prepare-error.textContent?
  - tpHtml: innerHTML von turnstile-div (erste 120 Zeichen)

**Cloudflare-Turnstile-Sitekey:** `0x4AAAAAAAFBfchmT6XFij7y` (aus data-turnstile-sitekey).
**ALTCHA-Challenge-URL:** `https://serienstream.to/api/inline/verify-init` (aus data-altcha-challenge-url).
**ALTCHA-Modul-URL:** `https://serienstream.to/build/assets/altcha-BhBXWxP7.js` (aus Gate-JS import()).
**Gate-JS-URL:** `https://serienstream.to/build/assets/episode-redirect-gate-C_Px7kjn.js` (live analysiert, 4526 Bytes).
**brunhild-Domain:** `brunhild.challenges.cloudflare.com` вҖ” rotierende Subdomain, KEIN Г¶ffentlicher A-Record (DoH 1.1.1.1 liefert nur SOA). Гңber IP von challenges.cloudflare.com (104.18.94.41) + Host-Header erreichbar (curl --resolve bestГӨtigt, HTTP 404 auf bogus path, HTTP 204 auf echte Challenge-URL).

### VERSIONSVERLAUF SERIENSTREAM (v39-v53)
- v39: DEAKTIVIERT (status=0, Turnstile unlГ¶sbar).
- v45-v46: Strategie D (WebView full flow), relativer /r?t= URL Bug (ERR_ACCESS_DENIED).
- v47: iframe-basierte Flow (Strategie D vollstГӨndig) вҖ” frameBridge postMessage kommt an, aber Turnstile rendert nicht.
- v48-v50: Diagnose-Polling, SyntaxError im driver-JS (verschachtelter Ternary), Bridge.log fehlte (R8 strippte @JavascriptInterface).
- v51: SyntaxError behoben (separate var-Statements, `node --check`), console.log via onConsoleMessage.
- v52: diag zeigte tsApi=yes, tsScript=1, gateInit=1, tpIF=0, alScript=0, apW=0, ERR_NAME_NOT_RESOLVED fГјr brunhild.
- v53: shouldInterceptRequest-DoH-Bridge fГјr *.challenges.cloudflare.com (raw TLS-Socket, SNI+Host, IP von challenges.cloudflare.com). DNS-Fehler WEG, aber HTTP 204 (No Content) und Turnstile-iframe rendert immer noch nicht.

### TEST-ABLAUF FГңR NГ„CHSTE SESSION (unverГӨndert)
1. In ARVIO: Repo LГ–SCHEN + neu hinzufГјgen DIREKT (NICHT Cloud-Sync!) вҶ’ URL `https://raw.githubusercontent.com/ReichiMD/Arvio-Addon/main/repo.json` вҶ’ Scraper einschalten.
2. In Termux: `logcat -c` (Handy) oder `adb logcat -c` (TV via WLAN-ADB `adb connect 192.168.0.59:5555`).
3. In ARVIO: Film/Serie suchen вҶ’ "Nach Quellen suchen" вҶ’ 60s warten (Serienstream braucht lГӨnger wegen WebView).
4. In Termux: `~/save-handy-log.sh vXX` (Handy) oder `~/save-tv-log.sh vXX` (TV).
5. Log-Datei weiterleiten (Dateimanager вҶ’ Downloads вҶ’ arvio-logs вҶ’ Teilen).
**Filter:** `Filmpalast|Kinoger|Vavoo|Serienstream|ArvioAddon|ExternalExtension|ExtExt|PluginManager|No API loaded|ErrorLoading|verify dex|emitLink|loadLinks|detectQuality|httpGet|httpPost|resolveHost|DohResolver|TurnstileSolver|bridge\.log|js:|diag try|frameBridge|interceptCloudflare|ERR_|onReceivedError|submit poll|solveGate`

### NГ„CHSTE SCHRITTE NACH SERIENSTREAM (Prio 2-4)

**Prio 2 вҖ” Hoster-Vielfalt erweitern (Vavoo + FilmPalast haben noch unerschlossene Hoster).**
- VOE (voesx.py, voe_decode): ROT+Base64+Caesar-Decrypt, 200+ Mirror-Domains. Algorithmus dokumentiert (siehe "VOE-EXTRACTOR: KOMPLETTE LOGIK" weiter unten).
- FileMoon, DoodStream, Streamtape: resolveurl Python als Vorlage.
- Workflow: resolveurl Python lesen вҶ’ Kotlin portieren вҶ’ curl testen вҶ’ TV testen.

**Prio 3 вҖ” Stalker-VOD als .cs3-Modul (BLOCKED bis Nutzer Portal+MAC hat).**
- Ventix StalkerApi.kt (17 Methoden, VOD+Serien) als Cloudstream3-Provider portieren + Config-Seite fГјr Portal-URL + MAC.
- BLOCKED: Nutzer hat aktuell keine Portal-URL + MAC-Adresse.

**Prio 4 вҖ” GitHub-Issue bei ARVIO (nach Absprache mit Nutzer).**
- Drei dokumentierte Bugs (R8-Obfuskation, Cloud-Sync-Download, ehem. Touch-Bug behoben).
- NOCH NICHT erГ¶ffnen вҖ” erst nach Nutzer-Freigabe.

---



## вҖјГ”ЕӮЕ№ WICHTIG FвҖ“ДҸвҖ”ДӣвҖ“Гӯ"ДӘR ALLE SESSIONS: NUTZER-PROFIL & KOMMUNIKATION

**Der Nutzer ist KEIN Programmierer.** Er erklвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®rt sich selbst als Laie im technischen Bereich und bittet darum:
- **Einfache, verstвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®ndliche Sprache** verwenden вҖ” keine unerklaerten Fachbegriffe, keine Code-Snippets ohne Erklaerung, was sie bewirken.
- **Schritt-fuer-Schritt-Anleitungen** fuer alles, was der Nutzer am TV/Handy/Termux tun muss вҖ” mit genauen Befehlen, die er 1:1 kopieren kann.
- **Ergebnisse in Alltagssprache** zusammenfassen вҖ” "Es funktioniert jetzt" oder "Es klappt noch nicht, weil X" statt technische Details ohne Kontext.
- **Geduld**, wenn technische Konzepte erklaert werden muessen вҖ” der Nutzer fragt nach, wenn etwas unklar ist.
- Der **Nutzer testet selbst am TV** (TCL C7K, WLAN-ADB via Termux/Handy) und schickt Logcat-Dateien. Die Analyse macht die AI.
- Der Nutzer entscheidet ueber Prioritaeten und genehmigt AnsвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®tze (z.B. "Option A zuerst, Option B nur falls scheitert").

## вҖјпёҸ NEU (17.08.2026): DOH-DNS-BYPASS = PLUGINS LAUFEN JETZT AUCH MOBIL (WICHTIG FUER ALLE SESSIONS)

## вҖјпёҸвҖјпёҸ NEUESTER STAND (17.08.2026, Ende Session вҖ” LESEN VOR ALLEM ANDEREN)

**VIER Module aktiv, DREI liefern Quellen, Serienstream DEAKTIVIERT (Turnstile).**

**Builds-Branch (nach CI-Push von Commit bec7762):**
- **KinoGer.cs3 v11** (status=1) вҖ” вң… liefert 3 Quellen (Incvideo 360p/720p/1080p MP4). **1080p-Erkennung funktioniert** (URL-Filename `1014657.mp4` вҶ’ quality=1080).
- **FilmPalast.cs3 v37** (status=1) вҖ” вң… liefert 2 Quellen (Odysseusa 720p m3u8, Vidsonic 720p m3u8).
- **Vavoo.cs3 v5** (status=1) вҖ” вң… liefert 2 Quellen (Vidsonic 720p m3u8, VOE 480p m3u8). **480p-VOE-Erkennung funktioniert** (BANDWIDTH-Fallback aus m3u8-Manifest).
- **Serienstream.cs3 v43** (status=1, RE-AKTIVIERT) вҖ” Provider + ALTCHA-PoW-Solver + **Strategie D: WebView macht den GESAMTEN /r-Gate-Flow** (`TurnstileSolver.solveGate`). Strategie A (v40-v42, WebView lГ¶st nur Turnstile, java.net macht POST /r) scheiterte am Cookie/Session-Mismatch: der /r?t= Token war an die java.net-Session gebunden, der WebView hatte eine eigene -> Serienstream redirectete auf die Startseite. Strategie D macht ALLES in EINEM WebView: Episode-Seite laden (Cookies sammeln) -> /r?t= laden (Turnstile rendert) -> auf cf-turnstile-response warten -> Form per injiziertem JS submitten (CSRF+t+altcha+Turnstile-Token) -> Hoster-URL aus `var t="..."` der POST /r Antwort extrahieren. ALTCHA-PoW bleibt in Kotlin, nur Turnstile+Submit im WebView. Timeout 45s. **TV-TEST AUSSTEHEND** (entscheidend ob Strategie D die Session-Problematik lГ¶st). Siehe "ERKENNTNIS #40-WebView-Turnstile" Strategie D unten.

**Letzter Handy-Test (17.08.2026, arvio-handy-log-v11.txt, Silo S1E1):** KinoGer 3 Quellen + FilmPalast 2 + Vavoo 2 = 7 Quellen fГјr Silo. detectQuality funktioniert (1080p/720p/480p korrekt). Serienstream 0 (Turnstile-Blockade, siehe unten).

**QUALITГ„TSERKENNUNG (detectQuality) вҖ” FUNKTIONIERT, implementiert in Vavoo v5, FilmPalast v37, Kinoger v11.** Drei Strategien (je Provider in der `detectQuality()`-Funktion):
1. URL/Filename-QualitГӨtsmarker parsen (z.B. `_720p.mp4`, `1014657.mp4` ohne Marker вҶ’ 1080).
2. JS-Redirect bei m3u8 folgen (Supervideo liefert HTML `window.location.replace` vor Playlist) + `RESOLUTION=WxH` aus Manifest.
3. `BANDWIDTH=` als Fallback, wenn keine `RESOLUTION`-Zeile (VOE liefert nur BANDWIDTH вҶ’ 480p).
Verifiziert im v11-Log: `emitLink: source=Incvideo ... quality=1080`, `emitLink: source=VOE ... quality=480`.

**SUPERVIDEO-DoH-FIX (frГјher, Vavoo v4/FilmPalast v36/Kinoger v10/Serienstream v38):** `supervideo.cc` zu `DOH_HOSTS` in allen 4 `DohHttp.kt` hinzugefГјgt вҶ’ 403вҶ’200. Von User im v10-Log bestГӨtigt funktioniert.

### ENTSCHEIDENDE ERKENNTNIS #20-Seriens: Serienstream /r-Gate braucht Cloudflare Turnstile (browser-CAPTCHA), nicht nur ALTCHA-PoW

**v11-Handy-Test (arvio-handy-log-v11.txt, Silo S1E1) = Scraper lГӨuft KOMPLETT durch, ALTCHA-PoW lГ¶st, aber Turnstile fehlt вҶ’ 0 Quellen.**
- Download 1492545 bytes, plugin.load(), Provider+Extractoren registriert.
- Dispatch bindet: `TmdbProvider Serienstream: load({"id":125988,"type":"tv"})` (unser Override!).
- TMDB-Meta: `title='Silo' year=2023`. Suche: 12 Kandidaten, korrekter Match `serie/silo`.
- `buildSeriesResponse: 3 seasons, 29 episodes` (S1E1вҖ“S3E9) вҖ” Episoden-Katalog funktioniert.
- `loadLinks: 4 hoster buttons` (VOE/Provider je Deutsch/Englisch) вҖ” Hoster-Extraktion funktioniert.
- **`solveAltcha: PoW solved, n=23995`** вҖ” unser ALTCHA-PoW funktioniert! (SHA-256-Loop, java.security.MessageDigest).
- **`POST /r -> 200 (937B)`** вҖ” der POST geht durch.
- **ABER: `redirectGate: server rejected ALTCHA: Das hat leider nicht geklappt. Bitte versuche es erneut.`** вҖ” Server lehnt ab.
- `/r?t=`-Preflight liefert **410 Gone** am Handy (am Laptop 403 DDoS-Guard). 410 = вҖһdauerhaft entfernt" fГјr den direkten GET, aber der POST-Pfad funktioniert noch (200).

**Root-Cause (verifiziert durch Episode-Seiten-Analyse + Gate-JS `episode-redirect-gate-C_Px7kjn.js`):** Der `/r`-Redirect-Gate-Tier heiГҹt **`turnstile_altcha`** вҖ” der Server verlangt **BEIDE** Challenges gleichzeitig:
1. **ALTCHA-PoW** (das lГ¶sen wir вң…, `solveAltcha`).
2. **Cloudflare Turnstile** (browser-basiertes CAPTCHA, Sitekey `0x4AAAAAAAFBfchmT6XFij7y`) вҖ” das fehlt вқҢ.

Gate-JS (`episode-redirect-gate-C_Px7kjn.js`, 4,5 KB) lГӨdt `https://challenges.cloudflare.com/turnstile/v0/api.js` und rendert das Turnstile-Widget (`window.turnstile.render(p,{sitekey:u,theme:"dark"})`). Das Formular `POST /r` bekommt die Felder `_token` (CSRF) + `t` (Token) + `altcha` (PoW-Payload) + `cf-turnstile-response` (Turnstile-Token). Wir senden `cf-turnstile-response=` (leer) вҶ’ Server lehnt ab.

**Cloudflare Turnstile ist NICHT ohne Browser lГ¶sbar** (Recherche 17.08.2026): Turnstile prГјft Browser-Fingerprint (Canvas, WebGL, Audio), IP-Verhalten und Interaktion. вҖһCannot be bypassed by solving a challenge token alone. You need the right IP (residential), the right browser fingerprint, and the right behavior. All three simultaneously." (humanbrowser.cloud). LГ¶sungs-Dienste (2captcha/CapMonster) brauchen API-Key + Geld. Eigenbau unmГ¶glich (Cloudflare passt stГӨndig an).

**Fix #20-Seriens (IMPLEMENTIERT, v39): Serienstream DEAKTIVIERT** (status=0 + registerMainAPI auskommentiert). Provider-Code + ALTCHA-Solver + CookieJar **BEHALTEN** fГјr spГӨtere Reaktivierung, falls:
- (a) ein Cloudflare-Turnstile-Bypass existiert (browser-engine im Plugin unrealistisch), ODER
- (b) ein Serienstream-Spiegel ohne Turnstile auftaucht (unwahrscheinlich, Gate ist serverseitig), ODER
- (c) ARVIO irgendwann Turnstile nativ unterstГјtzt (ARVIO hat einen WebView im Stremio-Addon-Pfad, aber nicht im .cs3-Plugin-Pfad).

### ENTSCHEIDENDE ERKENNTNIS #20-AniWorld: AniWorld = DDoS-Guard WebSocket+eval-Challenge (NICHT lГ¶sbar)

**AniWorld (aniworld.to) komplett DDoS-Guard-gesperrt, selbst mit korrekter IP (190.115.16.17).** DDoS-Guard-Challenge vollstaendig deobfusziert (17.08.2026):
- Schritt 1: GET / -> 403 + Challenge-HTML (902B) + 3 JS-Dateien (`view.js` 61KB, `index.js` 191KB, `check.js` 152B).
- Schritt 2: `check.js` lГӨdt Image von `/.well-known/ddos-guard/id/<id>` -> setzt `__ddg2_`-Cookie (mit java.net nachbaubar вң…).
- Schritt 3: `index.js` sammelt Browser-Fingerprint (Canvas, Audio, Screen, Navigator, WebGL, Plugins, Fonts, Timezone) -> `POST /.well-known/ddos-guard/mark/`. GefГӨlschte Werte -> 200 aber KEIN `__ddg1_`-Validierungscookie.
- Schritt 4 (das Hindernis): **WebSocket `wss://aniworld.to/.well-known/ddos-guard/mark/ws`**. Server schickt **beliebiges JavaScript**, Client fГјhrt es mit `eval(JSCode)` aus und gibt Ergebnis zurГјck: `ws.onmessage=function(ref2){message=JSON.parse(ref2.data);for(key in message){JSCode=message[key].code;eval(JSCode);response=window.DDG;responses[key]={value:response}}ws.send(JSON.stringify(responses))}`. Code wird **dynamisch pro Verbindung generiert** вҖ” nicht vorhersagbar.
- Schritt 5 (optional): Picasso-Hash (MD5 Гјber Canvas-toDataURL), aber `mark/picasso` liefert 204 (deaktiviert).
- **Fazit: ohne echte JS-Engine + Canvas/Audio-Simulation nicht lГ¶sbar.** Browser-Engine im .cs3-Plugin unrealistisch (GrГ¶Гҹe, R8). AniWorld NICHT weiter verfolgen.

### ENTSCHEIDENDE ERKENNTNIS #20-BS: Burning Series = Google reCAPTCHA v2 invisible (NICHT lГ¶sbar)

**Burning Series (bs.to) CUII-DNS-gesperrt in Deutschland, aber Spiegel `burningseries.ac`/`burningseries.cx`/`bs.cine.to` frei erreichbar (200, kein DDoS-Guard-JS-Challenge auf Hauptseite).** Struktur sauber (17.08.2026):
- `/serie/<Title-Slug>` (z.B. `/serie/Silo`) -> 200, 15KB. Staffel-Auswahl `/serie/Silo/1/de`. Episoden-Tabelle mit Hoster-Icons (VOE/Doodstream/Filemoon) direkt sichtbar.
- Episode+Hoster-URL: `/serie/Silo/1/1-Freiheitstag/de/VOE` -> 200, 17KB. `data-lid="7629374"` auf `hoster-player`-Div.
- **ABER: echte Hoster-URL hinter `ajax/embed.php` POST mit reCAPTCHA-Ticket.** JS (`page.18dad0637254.js`): `$.ajax({url:"ajax/embed.php",data:{LID:t,ticket:e}})` wobei `e` = Google reCAPTCHA v2 invisible Token (Sitekey `6Ldd07ogAAAAACktG1QNsMTcUWuwcwtkneCnPDOL`, via `series.init(1,1,'<sitekey>')`). Response `{success,embed,link}`. Ohne Ticket -> 400 Bad Request.
- **Google reCAPTCHA v2 invisible = browser-basiert, NICHT ohne Browser lГ¶sbar.** Gleiche HГјrde wie Turnstile/DDoS-Guard.
- **Aufwand-SchГӨtzung:** Teil 1 (Katalog, ~1 Session, niedrig) machbar, aber Teil 2 (Hoster-AuflГ¶sung, BLOCKIERT) und Teil 3 (reCAPTCHA-Solver, unmГ¶glich ohne Drittanbieter+Geld) machen es wertlos вҖ” 0 Quellen am Ende. **Burning Series NICHT weiter verfolgen.**

### BRANCHEN-TREND 2026: Alle deutschen Serien-Portale browser-CAPTCHA-gesperrt
Alle drei bekannten deutschen Serien-Streaming-Portale sind 2026 hinter browser-basierten CAPTCHAs:
- **Serienstream**: Cloudflare Turnstile + ALTCHA-PoW (Turnstile nicht lГ¶sbar).
- **AniWorld**: DDoS-Guard WebSocket+eval-Challenge (nicht lГ¶sbar).
- **Burning Series**: Google reCAPTCHA v2 invisible (nicht lГ¶sbar).
**FГјr Serien liefern KinoGer + Vavoo + FilmPalast zuverlГӨssig Quellen** (alle drei unterstГјtzen TvSeries via TmdbProvider). Das reicht.

### ERKENNTNIS #40-WebView-Turnstile (17.08.2026, v40 IMPLEMENTIERT): WebView-basierter Turnstile-Solver — ARVIO gibt Activity-Context!

**v40-Implementierung: Serienstream RE-AKTIVIERT mit WebView-basiertem Cloudflare-Turnstile-Solver.**

**Root-Cause der LГ¶sung (verifiziert im ARVIO-Source `/tmp/arvio_ref`):** ARVIOs `Plugin.load(context)` wird mit `(activity as Context?) ?: context` aufgerufen (`ExternalExtensionLoader.kt:317/486/611`). Das bedeutet: **wenn eine Activity verfГјgbar ist, bekommt das Plugin einen Activity-Context** вҖ” und das ist EXACT der Context, den `android.webkit.WebView` braucht. Die Hauptschluss-HГјrde aus der Recherche ("Ob ARVIO uns einen Context gibt, der WebView erlaubt, ist NICHT verifiziert") ist **gelГ¶st**: ARVIO gibt uns einen Activity-Context. WebView-Erstellung ist mГ¶glich.

**Neue Datei `TurnstileSolver.kt` (implementiert):**
- `init(context: Context)`: speichert den applicationContext (in `SerienstreamPlugin.load()` aufgerufen).
- `solveTurnstileToken(url, timeoutMs=20000): SolveResult?`: non-suspend, blockierend (wie die anderen java.net-Aufrufe). Erstellt auf dem Main-Thread (via `Handler(Looper.getMainLooper()).post`) einen WebView, lГӨdt die `/r?t=<token>`-Seite, die das Turnstile-Widget rendert. Pollt alle 500ms via `evaluateJavascript` auf `cf-turnstile-response` (hidden input ODER `turnstile.getResponse()`). Gibt `SolveResult(token, cookies)` zurГјck. Timeout 20s (ARVIOs `LOADLINKS_TIMEOUT_MS=60s` deckt das locker). WebView-Cleanup (destroy) auf Main-Thread nach LГ¶sung/Timeout.
- Cookies vom WebView (`CookieManager.getInstance().getCookie(url)`) werden exportiert und via `CookieJar.importCookieHeader` in den java.net CookieJar Гјbernommen, damit der `POST /r` dieselbe DDoS-Guard-Session nutzt (sonst IP/Session-Mismatch -> "Das hat leider nicht geklappt").
- WebView-Einstellungen: `javaScriptEnabled=true`, `domStorageEnabled=true`, `setAcceptThirdPartyCookies=true` (Turnstile-iframe braucht das), Desktop-Chrome-UA (hГӨlt Turnstile in high-trust/non-interactive mode).

**`resolveRedirectGate` geГӨndert (SerienstreamProvider.kt:653-694):** Schritt 4 neu: vor dem `POST /r` wird `TurnstileSolver.solveTurnstileToken(redirectUrl)` aufgerufen. Der Token geht in `cf-turnstile-response=` (vorher leer). Log-Zeile zeigt `cfToken=empty` oder `cfToken=present`.

**`SerienstreamPlugin.load(context)`:** ruft `TurnstileSolver.init(context)` + `registerMainAPI(SerienstreamProvider())` (vorher auskommentiert). **status=1** in build.gradle.kts. Version=40.

**CI grГјn (17.08.2026, Run 32031277518, 3m4s).** builds-Branch: `Serienstream.cs3` v40 (1.498.482 Bytes, status=1). `compileDebugKotlin` fГјr Serienstream durchgelaufen (nur eine harmlose Warnung "No cast needed" in TurnstileSolver.kt:145). DEX-Patch (patch_class_obfuscation.py) lГӨuft wie bisher Гјber alle Module.

**Architektur-Entscheidung (Strategie A, nicht D):** WebView lГ¶st NUR das Turnstile, java.net macht den `POST /r` mit Token + ALTCHA + WebView-Cookies. Strategie D (WebView macht alles inkl. ALTCHA in JS) wГӨre cookie-sicherer aber viel komplexer (SHA-256 in JS, Form-Submit im WebView). FГјr PoC erstmal Strategie A. **Falls Cookie-Mismatch auftritt** (POST /r 200 aber err="Das hat leider nicht geklappt" trotz Token present): auf Strategie D umsteigen = WebView lГӨdt Episode-Seite, wir injizieren ALTCHA-Payload via `evaluateJavascript`, das Formular submittet sich selbst im WebView, WebView folgt Redirect zur Hoster-URL, wir lesen `WebView.getUrl()` aus.

**ERWARTUNG v40-TV-TEST (entscheidend, 17.08.2026):**
1. **Best-Case:** `TurnstileSolver: token=...` + `redirectGate: Turnstile-Token erhalten` + `POST /r -> 200` + `redirectGate: resolved to https://voe.sx/...` -> **ERSTE SERIENSTREAM-QUELLE!** рҹҺҜ
2. **Falls `solveTurnstileToken: no token produced` / TIMEOUT:** WebView erstellt aber Turnstile-Widget lГӨuft nicht durch (residential IP wurde als "medium trust" eingestuft -> sichtbares Widget, braucht Klick den wir nicht simulieren). LГ¶sung: Maus-Bewegung/Touch im WebView simulieren (Strategie D + `webView.dispatchTouchEvent`).
3. **Falls `webView create threw` (z.B. `java.lang.RuntimeException: Can't toast on a thread that has not called Looper.prepare()` oder `WrongThreadException`):** WebView kann nicht aus dem Netzwerk-Thread / dem suspend-Kontext gestartet werden, selbst mit `Handler(mainLooper).post` nicht. LГ¶sung: Activity-Context via `runOnUiThread` nutzen oder ARVIO-interne WebView-Helper finden.
4. **Falls `NoClassDefFoundError: android.webkit.WebView` oder R8-stripped WebView-Helfer:** wie frГјher (okhttp3, kotlin-reflect) -> WebView-Klassen sind Android-built-in, sollten NIE von R8 geshrinkt werden (anderen als okhttp3). Aber `WebResourceRequest`/`WebResourceError` (API 23+) kГ¶nnten fehlen -> auf die alte `onReceivedError(view, errorCode, description, failingUrl)`-Гңberladung zurГјckfallen (habe ich in v40 schon auf die neue API gesetzt; falls Build/Run schlГӨgt fehl, auf alte API wechseln).
5. **Falls POST /r 200 aber err="Das hat leider nicht geklappt":** Token da aber Cookie/Session-Mismatch -> Strategie D (WebView macht alles).

**TEST-ABLAUF fГјr v40 (am TV, mit Handy+Termux, Standard):**
1. In ARVIO: Repo LГ–SCHEN + neu hinzufГјgen DIREKT (`https://raw.githubusercontent.com/ReichiMD/Arvio-Addon/main/repo.json`) -> Scraper einschalten (Serienstream + die 3 laufenden).
2. In Termux: `adb logcat -c` (TV via `adb connect 192.168.0.59:5555`).
3. In ARVIO: Serie suchen (z.B. **Silo**), auf eine Episode, "nach Quellen suchen", 25s warten (lГӨnger als sonst wegen Turnstile-Timeout 20s).
4. In Termux: `~/save-tv-log.sh v40` (Filter ist schon aktualisiert auf `TurnstileSolver|turnstile|cf-turnstile`).
5. Log an AI weiterleiten.

**Was die AI im Log sucht (entscheidend):**
- `TurnstileSolver: loadUrl: https://serienstream.to/r?t=...` -> WebView startet.
- `TurnstileSolver: onPageFinished: ...` -> Seite geladen.
- `TurnstileSolver: solveTurnstileToken: token=...` -> **TURNSTILE GELГ–ST!**
- `redirectGate: Turnstile-Token erhalten (...)` -> Token an POST /r Гјbergeben.
- `redirectGate: POST /r -> 200 (NB) cfToken=present` -> POST mit Token.
- `redirectGate: resolved to https://voe.sx/...` -> **ERSTE SERIENSTREAM-QUELLE!** рҹҺҜ
- `solveTurnstileToken: TIMEOUT after 20000ms` -> Turnstile lГӨuft nicht durch (sichtbares Widget nГ¶tig).
- `webView create threw ...` -> WebView kann nicht erstellt werden (Threading/Context-Problem).
- `redirectGate: server rejected ALTCHA: Das hat leider nicht geklappt` -> Cookie/Session-Mismatch -> Strategie D.

### RECHERCHE (17.08.2026): Browser-CAPTCHA-Bypass — Weltweite LГ¶sungsansГӨtze & RealitГӨtscheck

**AuslГ¶ser:** Vermutung des Nutzers, dass browser-basierte CAPTCHAs der "neue Standard 2026" werden und irgendjemand auf der Welt das Problem vielleicht schon gelГ¶st hat. **Die Vermutung bestГӨtigt sich vollumfГӨnglich** — die gesamte Scraping-Industrie kГӨmpft 2026 genau damit, und es gibt KEINE einfache LГ¶sung. ABER: FГјr unseren Spezialfall (ARVIO auf echtem TV mit residential IP) gibt es einen vielversprechenden Pfad, den wir bisher Гјbersehen haben.

**Drei LГ¶sungskategorien, die die Industrie nutzt (mit RealitГӨtscheck fГјr uns):**

**Kategorie 1: CAPTCHA-Solver-Dienste (2captcha, CapMonster, CapSolver, NSLSolver, NoCaptchaAI, CapSkip)**
- **Wie sie arbeiten:** Du sendest sitekey + URL per API, der Dienst lГ¶st das CAPTCHA (per Browser-Farm oder KI) und gibt dir einen Token zurГјck, den du ins Formular einfГјgst.
- **Kosten (Stand 2026, pro 1000 LГ¶sungen):** Cloudflare Turnstile: NSLSolver $0.40, CapSolver $1.20, 2Captcha $1.45, CapMonster $1.20, Anti-Captcha $2.99. reCAPTCHA v2: $1.00-$2.00. Speed: NSLSolver ~250ms, CapSolver 5-10s, 2Captcha ~8s.
- **вҡ пёҸ ENTSCHEIDENDES PROBLEM fГјr Serienstream:** Cloudflare Turnstile prГјft **gleichzeitig drei Signale** (laut humanbrowser.cloud, Scrapfly, Browserless): (1) richtige IP (residential, sauber), (2) richtiger Browser-Fingerprint (echtes GerГӨt, konsistenter UA/GPU/Fonts/Canvas), (3) richtiges Verhalten (Maus-Bewegung, Interaktion). "Cannot be bypassed by solving a challenge token alone. You need the right IP, the right browser fingerprint, and the right behavior. All three simultaneously." Das Token, das ein Solver-Dienst liefert, wurde auf einer ANDEREN IP gelГ¶st. Cloudflare validiert, dass die IP, die den Token erzeugt hat, mit der IP Гјbereinstimmt, die den POST /r schickt. Token von 2captcha/etc. werden voraussichtlich mit "Das hat leider nicht geklappt" abgelehnt (wie unser ALTCHA-ohne-Turnstile-Versuch im v11-Log). **Fazit: Solver-Dienste sind fГјr Turnstile mit IP-Bindung NICHT zuverlГӨssig.** Schon in AGENTS.md dokumentiert, Recherche bestГӨtigt es branchenweit.
- **Wo Solver-Dienste WIRKLICH funktionieren:** Bei sichtbaren/managed Widgets OHNE IP-Bindung, bei klassischer reCAPTCHA v2 (Bilder erkennen), bei Token-only-Verifikation. FГјr unser Turnstile_altcha-Gate ungeeignet.

**Kategorie 2: FlareSolverr & ГӨhnliche Open-Source-Browser-Proxies (FlareSolverr, Byparr)**
- **Wie sie arbeiten:** FlareSolverr ist ein lokaler Proxy-Server (Docker), der Selenium + undetected-chromedriver nutzt, um echte Chrome-Browser-Instanzen zu starten, die Cloudflare/DDoS-Guard-Challenges lГ¶sen, und HTML + Cookies zurГјckgeben. Integration in Prowlarr/Jackett Гјblich.
- **UnterstГјtzung:** Cloudflare-Challenge (JS-Puzzle), DDoS-Guard (laut eigenem README), **aber "struggles with modern Cloudflare Turnstile"** (laut iproyal.com 2026). FГјr interaktive CAPTCHA-Puzzles braucht es zusГӨtzlich einen Solver-Dienst.
- **вҡ пёҸ ENTSCHEIDENDES PROBLEM fГјr uns:** FlareSolverr lГӨuft auf einem **Server** (Docker, Chrome, Xvfb). Wir wollen aber **clientseitig** im TV arbeiten (ohne zusГӨtzlichen Server im WLAN). WГјrde bedeuten: ein Raspberry Pi / Mini-PC im WLAN, auf dem FlareSolverr lГӨuft, und das .cs3-Plugin ruft diesen lokalen Service auf. Das widerspricht unserem "kein Server"-Prinzip. AuГҹerdem: Turnstile-Support schwach, DDoS-Guard brauchen wir nicht mehr (Serienstream nutzt ALTCHA+Turnstile, nicht DDoS-Guard-JS-Challenge).
- **Fazit:** Nicht passend fГјr unsere Architektur. WГӨre nur relevant, wenn wir einen lokalen Server betreiben wollten (was der Nutzer ablehnt).

**Kategorie 3: Echter Browser / WebView (DER VIELVERSPRECHENDE PFAD FГңR UNS!) вӯҗвӯҗвӯҗ**
- **Kern-Erkenntnis:** ARVIO lГӨuft auf einem **echten Android-TV (TCL C7K)** mit **residential IP** (Wohnung). Das ist **GENAU die Kombination, die Cloudflare Turnstile als vertrauenswГјrdig einstuft**: real device + residential IP + echtes Android-System-Webview = "high trust" = Turnstile lГӨuft im **Managed Mode oft INVISIBLE automatisch durch** (kein sichtbares Widget, kein Klick nГ¶tig). Das ist kein Bypass-Hack, sondern das **von Cloudflare dokumentierte und vorgesehene Verhalten** fГјr legitime mobile Apps.
- **Cloudflare dokumentiert selbst (https://developers.cloudflare.com/turnstile/get-started/mobile-implementation):** "For native mobile applications, Turnstile does not run directly as a native SDK. It needs a browser environment. The practical pattern is to load a small Turnstile page inside a WebView." Der offizielle Mobile-Pattern: App Г¶ffnet invisible Turnstile in WebView -> Token-Callback an native Code -> native Code sendet Token an Backend -> Backend validiert. **Genau das, was wir fГјr Serienstream brauchen wГјrde.**
- **Warum das fГјr uns besser ist als jeder Server-Ansatz:** Server-Scraper (FlareSolverr, 2captcha) haben das Problem, dass ihre IP (Rechenzentrum) als "low trust" eingestuft wird -> Turnstile eskaliert zu sichtbarem Challenge. Unser TV hat eine **residential IP** -> "high trust" -> oft automatischer Pass. Der gleiche Grund, warum KinoGer am TV lГӨuft (200) aber auf Render 403 war: die IP macht den Unterschied.
- **Technische Machbarkeit im .cs3-Plugin:** Ein .cs3-Plugin lГӨuft im ARVIO-Prozess (DexClassLoader mit ARVIO als Parent). Es kann theoretisch `android.webkit.WebView` instanziieren, JavaScript aktivieren, die Episode-Seite laden, Turnstile im Hintergrund rendern lassen, den `cf-turnstile-response`-Token via `evaluateJavascript` / `addJavascriptInterface` extrahieren, und zusammen mit unserem bereits funktionierenden ALTCHA-PoW in den `POST /r` senden. Das wГӨre der mobile-app-Pattern, den Cloudflare vorsieht.
- **вҡ пёҸ Offene HГјrden (ehrlich):** (1) WebView muss auf dem **UI-Thread** erstellt werden und braucht einen **Context** (Activity). Cloudstream3-Plugins bekommen normalerweise keinen Activity-Context, nur einen Plugin-Context. Ob ARVIO uns einen Context gibt, der WebView erlaubt, ist **NICHT verifiziert**. (2) WebView im Hintergrund (unsichtbar) ist mГ¶glich, aber Timing/Callbacks aus dem suspend-`loadLinks` heraus sind heikel (WebView ist asynchron, `loadLinks` braucht synchrones Ergebnis -> braucht Bridge aus JS-Callback zu Kotlin-Coroutine). (3) ARVIOs R8 hat eventuell WebView-Helfer geschrumpft (wie okhttp3, kotlin-reflect) -> mГјsste man testen. (4) GrГ¶Гҹe: WebView selbst ist Android-built-in (kein zusГӨtzlicher Code im .cs3), aber die JS-BrГјcken-Logik muss gebaut werden.
- **Realistische SchГӨtzung:** Mittel bis hoch. Der Ansatz ist **technisch plausibel** (Cloudflare dokumentiert ihn fГјr Mobile-Apps, ARVIO hat residential IP), aber **unbekannter Aufwand** fГјr die Context/Threading-Problematik im .cs3-Plugin. WГӨre ein eigener Forschungs-Strang (1-3 Sessions Experimentieren).

**Branchen-BestГӨtigung, dass browser-CAPTCHAs der neue Standard sind (laut Recherche):**
- **Scrapfly (groГҹer Scraping-Dienst):** "Turnstile combines browser fingerprinting, behavioral analysis, and cryptographic proof-of-work... HTTP-only clients like Python's requests can't complete Turnstile challenges because they lack a JavaScript engine." LГ¶sung: "cloud browsers" (echte Browser, nicht Token-Solver).
- **Browserless.io:** "Turnstile CAPTCHAs are harder to bypass than older versions. Puppeteer can't solve Turnstile independently unless you integrate an external solver or reroute the challenge." LГ¶sung: Cloud-Browser.
- **humanbrowser.cloud:** "Cloudflare Turnstile cannot be bypassed by solving a challenge token alone. You need the right IP (residential), the right browser fingerprint (real device), and the right behavior. All three simultaneously." LГ¶sung: Human-Browser (echte Browser auf realen GerГӨten).
- **Data Journal (Medium):** "Five Layers of Modern Bot Detection: IP Reputation, Browser Fingerprint, Behavioral Analysis, TLS Fingerprinting, Active Challenges. You must defeat all five simultaneously." 
- **Fazit der Industrie:** Die LГ¶sung fГјr browser-CAPTCHAs 2026 ist **echter Browser mit residential IP**, NICHT Token-Solver. Dienste wie Scrapfly/Browserless vermieten genau das: echte Cloud-Browser mit residential IPs. **Wir haben das auf dem TV bereits nativ** — mГјssen es nur nutzen.

**Was das fГјr unsere drei blockierten Portale bedeutet (neue Bewertung nach Recherche):**
- **Serienstream (Cloudflare Turnstile + ALTCHA):** **Beste Chancen via WebView-Ansatz.** Turnstile lГӨuft auf real device + residential IP wahrscheinlich automatisch durch. ALTCHA-PoW haben wir bereits. Workflow: WebView Г¶ffnet Episode-Seite -> Turnstile rendert -> Token extrahieren -> POST /r mit Token + ALTCHA -> Hoster-URL. **Prio: wenn Prio 0 (v11-BestГӨtigung) und Prio 1 (Hoster) erledigt sind, dann als Forschungs-Strang angehen.**
- **AniWorld (DDoS-Guard WebSocket+eval):** WebView wГјrde auch hier helfen (echtes JS-Engine, echtes Canvas), aber AniWorld ist Anime-spezifisch (niedrigere PrioritГӨt, deutsche Serien haben wir Гјber KinoGer/Vavoo/FilmPalast). **Vorerst nicht angehen**, aber der WebView-Ansatz wГӨre theoretisch auch hier der Weg.
- **Burning Series (reCAPTCHA v2 invisible):** WebView wГјrde helfen (reCAPTCHA v2 invisible lГӨuft in WebView). Aber Burning Series bietet keinen Mehrwert Гјber KinoGer/Vavoo/FilmPalast. **Vorerst nicht angehen.**

**EMPFEHLUNG FГңR NUTZER (laienverstГӨndlich, 17.08.2026):**
1. **Das Problem ist weltweit bekannt und ungelГ¶st** — niemand hat einen einfachen "CAPTCHA-Solver" fГјr diese Art browser-basierte Challenges gebaut, der ohne Browser funktioniert. Die Industrie vermietet echte Browser (kostenpflichtig). Token-Solver-Dienste ($0.40-$2.99/1000) funktionieren bei Turnstile nicht zuverlГӨssig, weil Cloudflare die IP prГјft.
2. **FГјr uns gibt es einen Pfad, den die Server-Scraper nicht haben:** ARVIO lГӨuft auf einem echten TV mit echter Wohnungs-IP. Cloudflare Turnstile stuft das als "vertrauenswГјrdig" ein und lГӨuft oft automatisch durch (ohne dass ein Mensch etwas klickt). Das ist kein Hack, sondern das von Cloudflare vorgesehene Verhalten fГјr echte Apps.
3. **Der Weg wГӨre:** Ein unsichtbarer WebView im Plugin lГӨdt die Serienstream-Seite, Turnstile lГӨuft im Hintergrund durch, wir fangen den Token ab und schicken ihn zusammen mit unserer bereits funktionierenden "Zahlensuche" (ALTCHA-PoW) an Serienstream. Das ist baubar, aber es ist Forschungsarbeit (1-3 Sessions), weil unklar ist, ob ein Plugin Гјberhaupt einen WebView Г¶ffnen darf.
4. **RealitГӨtscheck:** Serienstream ist aktuell deaktiviert (v39). FГјr Serien haben wir bereits KinoGer + Vavoo + FilmPalast (7 Quellen fГјr Silo im v11-Test). Serienstream wГӨre "nice to have" (mehr Ausfallsicherheit), aber kein Muss. Der WebView-Ansatz lohnt sich erst, wenn die drei laufenden Scraper stabil am TV sind und der Nutzer Serienstream explizit will.
5. **Geld ausgeben (2captcha/CapSolver) wГјrde ich NICHT empfehlen** — es funktioniert bei Turnstile nicht zuverlГӨssig (IP-Mismatch), und wir mГјssten pro Film/Serie zahlen. Der WebView-Ansatz ist kostenlos und hГ¶her-chance.

### NÄCHSTE SCHRITTE (Stand 17.08.2026, für nächste Session — REIHENFOLGE BEACHTEN)

**Prio 0 — BESTÄTIGEN: v11 am TV testen (oder bestätigen dass v11 schon da ist).**
Der v11-Handy-Test war erfolgreich (3 Scraper liefern Quellen, detectQuality funktioniert). Die Versionen KinoGer v11 / FilmPalast v37 / Vavoo v5 / Serienstream v39(deaktiviert) sind auf `builds` nach CI-Push von Commit `bec7762`. Nutzer sollte am TV: Repo LÖSCHEN + neu hinzufügen DIREKT (`https://raw.githubusercontent.com/ReichiMD/Arvio-Addon/main/repo.json`), dann Film/Serie suchen. Erwartung: KinoGer 1080p MP4 + FilmPalast/Vavoo Quellen, korrekte Qualitätsanzeigen in ARVIO. Falls schon getan (Handy-Test war v11): bestätigen, dann Prio 1.

**Prio 1 — Hoster-Vielfalt erweitern (höchster Mehrwert, mittlerer Aufwand).**
Aktuell liefern die 3 Scraper 7 Quellen für Silo. Mehr Hoster = mehr Ausfallsicherheit + mehr Quellen. Reihenfolge (resolveurl Python als Vorlage, siehe RESOLVEURL-REPOS):
- **VOE** (voesx.py, voe_decode): ROT+Base64+Caesar-Decrypt, 200+ Mirror-Domains. Häufigster Hoster. Algorithmus in AGENTS.md unten dokumentiert („VOE-EXTRACTOR: KOMPLETTE LOGIK"). **Prio 1a** (Vavoo+FilmPalast haben VOE-Links).
- **FileMoon** (filemoon?): dekompilieren/Reverse-Engineeren. **Prio 1b** (Serienstream/BS nutzen Filemoon, aber beide deaktiviert; Vavoo/Filmpalast seltener).
- **DoodStream** (doodstream.py): dsplayer.hotkeys -> token -> /pass -> mp4. Algorithmus dokumentiert. **Prio 1c** (nur relevant falls ein Scraper DoodStream-Links hat; aktuell keiner der aktiven).
- **Streamtape** (streamtape.py): linko-Algorithmus. **Prio 1d** (niedrigste Prio).
Workflow pro Hoster: resolveurl Python lesen -> Kotlin portieren (java.net + Regex + org.json) -> curl testen -> TV testen.

**Prio 2 — Stalker-VOD als .cs3-Modul (Konsolidierung Phase 4, BLOCKED bis Nutzer Portal+MAC hat).**
Siehe „KONSOLIDIERUNGS-PLAN Phase 4" unten. Ventix StalkerApi.kt (17 Methoden, VOD+Serien) als Cloudstream3-Provider portieren + Config-Seite für Portal-URL + MAC. **BLOCKED**: Nutzer hat aktuell keine Portal-URL + MAC-Adresse. Kommt zum Schluss, wenn Daten verfügbar. Kein Bot-Schutz (Stalker-Portale sind normale HTTP/JSON-APIs, java.net.HttpURLConnection reicht).

**Prio 3 — Vavoo Live-TV als .cs3-Modul (Konsolidierung Phase 5, unsicher).**
Siehe „KONSOLIDIERUNGS-PLAN Phase 5". Cloudstream3-Plugins sind für VOD gebaut, Live-TV-Kataloge sind Spezialfall. mainPage-Kataloge hatten früher R8-Probleme (Erkenntnis #6, deshalb entfernt). Fallback: Stremio-Addon für Live-TV behalten (VavooLive.ts funktioniert dort zuverlässig). **Zuletzt, ungewisser Ausgang.**

**Prio 4 — GitHub-Issue bei ARVIO (professionell, nach Absprache mit Nutzer).**
Drei dokumentierte Bugs (NOCH NICHT eröffnen — erst nach Nutzer-Freigabe):
1. **R8 obfuscated kotlin.coroutines.Continuation + okhttp3 + stript kotlin-reflect + DefaultConstructorMarker** (Haupt-Bug, Erkenntnis #7+#13+#14+#15+#16+#18) -> externe .cs3-Plugins können suspend-Overrides, app.get, loadExtractor, Jackson-JSON UND Default-Arg-Konstruktoren nicht nutzen. Wir haben es durch massive Workarounds zum Laufen gebracht (DEX-Patching, java.net-HTTP, org.json, primary-ctor, eigene Extractoren) -> beweist den Bug eindrucksvoll.
2. **Cloud-Sync-Restore lädt .cs3-Dateien nicht herunter** (Erkenntnis #1).
3. ~~Touch-Bug Add-Repo-Dialog~~ — behoben in 1.9.994.
Vorgehen (Vorbild Issue #537): Environment -> Summary -> Steps to reproduce -> Expected vs. Actual -> Root cause (mit Code-Verweis) -> Proposed fix -> References (#459, #273, #500) -> Logcat-Auszug -> **AI-Disclosure** ("created by an AI agent (OpenHands) on behalf of [user]"). Optional: Fork + Fix-PR (ProGuard-Regel für `kotlin.coroutines.Continuation` + `kotlin.jvm.functions.*` unobfuscated).

**TEST-ABLAUF FÜR NÄCHSTE SESSION (Standard, unverändert):**
1. In ARVIO: Repo LÖSCHEN + neu hinzufügen DIREKT (NICHT Cloud-Sync!) -> URL `https://raw.githubusercontent.com/ReichiMD/Arvio-Addon/main/repo.json` -> Scraper einschalten.
2. In Termux: `logcat -c` (Handy) oder `adb logcat -c` (TV via WLAN-ADB `adb connect 192.168.0.59:5555`).
3. In ARVIO: Film/Serie suchen -> „Nach Quellen suchen" -> 15s warten.
4. In Termux: `~/save-handy-log.sh <label>` (Handy) oder `~/save-tv-log.sh <label>` (TV).
5. Log-Datei weiterleiten (Dateimanager -> Downloads -> arvio-logs -> Teilen).
**Filter (in save-*-log.sh):** `Filmpalast|Kinoger|Vavoo|Serienstream|ArvioAddon|ExternalExtension|PluginManager|No API loaded|ErrorLoading|verify dex|emitLink|loadLinks|detectQuality|httpGet|httpPost|resolveHost|DohResolver` (Serienstream bleibt im Filter falls später reaktiviert).

---

## вҖјпёҸ NEU (17.08.2026): DOH-DNS-BYPASS = PLUGINS LAUFEN JETZT AUCH MOBIL (WICHTIG FUER ALLE SESSIONS)

**Meilenstein 17.08.2026: KinoGer v7 + FilmPalast v33 + Serienstream v37 liefern Quellen AUCH im Mobilfunk** (zuvor nur WLAN). Das war die letzte grosse Huerde.

**Root-Cause der Mobilfunk-Probleme (vor v33/v7):** HttpURLConnection nimmt den SYSTEM-DNS. Deutsche Mobilfunk-Provider (Telekom/Vodafone/O2) blockieren Streaming-Seiten (kinoger.com, filmpalast.to, serienstream.to) per DNS вҶ’ die aufgeloeste IP liefert keine echte TLS-Antwort вҶ’ вҖһUnable to parse TLS packet header" bzw. вҖһSSLV3_ALERT_HANDSHAKE_FAILURE" вҶ’ 0 Quellen. TMDB (api.themoviedb.org, nicht blockiert) ging immer. Webstreamr (serverseitig auf Render) ging immer, weil das Handy nicht direkt mit der Streaming-Seite spricht.
- Nutzer-Tests bewiesen: ARVIOs integrierte DNS-Einstellung reicht NICHT fuer unser Plugin (wirkt nur auf ARVIOs eigenen OkHttp, nicht auf unser java.net.HttpURLConnection). System-DNS auf AdGuard (1.1.1.1) вҶ’ Quellen. вҶ’ DNS-Level-Sperre, umgehbar. (Cloudstream3-App hatte kein Problem, weil sie OkHttp mit eigenem DNS/DoH nutzt.)

**FIX: DNS-over-HTTPS direkt im Plugin.** Pro Modul eine `DohHttp.kt` (Pfade: `FilmPalast/.../DohHttp.kt`, `Kinoger/.../DohHttp.kt`, `Serienstream/.../DohHttp.kt` вҖ” identisch bis auf package-Zeile):
- `DohResolver.resolve(host)`: fragt Cloudflare DoH `https://1.1.1.1/dns-query?name=HOST&type=A` (per IP erreichbar, selbst nie blockiert), cached A-Record fuer TTL (60вҖ“3600s) in einer `ConcurrentHashMap`.
- `openDohConnection(url)`: verbindet sich zur aufgeloesten IP mit korrektem SNI (`SniSocketFactory`), `Host`-Header = Original-Hostname, `HostnameVerifier` prueft Zertifikat gegen Original-Hostname. Fallback auf normalen System-DNS, falls DoH ausfaellt (nie schlechter als vorher).
- Alle `httpGet`/`httpPost`/`doRequest`-Aufrufstellen nutzen `openDohConnection(url)` statt `URL(url).openConnection()`.

**вҡ пёҸ ZWEI WICHTIGE PITFALLS (falls DoH mal angepasst werden muss):**
1. **WHITELIST, nicht вҖһDoH fuer alles".** DoH nur fuer `DOH_HOSTS = {kinoger.com, filmpalast.to, serienstream.to}` (Suffix-Match via `shouldUseDoh`). Alles andere (TMDB, Hoster-Embeds voe.sx/fsst.online/vidsonic.net/odysseusa.cc) bleibt auf normalem System-DNS. Grund: die Custom-SNI-SocketFactory bricht manche CDNs (TMDB/CloudFront вҶ’ вҖһSSLV3_ALERT_HANDSHAKE_FAILURE"). v5 hatte DoH fuer ALLES angewendet вҶ’ TMDB kaputt вҶ’ Scraper kam nie bis zur Suche. **Falls ein Hoster spaeter auch mobil gesperrt ist: Domain zur `DOH_HOSTS`-Liste in der jeweiligen `DohHttp.kt` hinzufuegen (eine Zeile).**
2. **SNI VOR dem Handshake setzen.** `sslParameters.serverNames` MUSS vor dem TLS-Handshake stehen. HttpsURLConnection nutzt die *layered* `createSocket(plainSocket, host, port, autoClose)` nach Aufbau des plain-TCP. Korrekt: `delegate.createSocket(s, null, port, autoClose)` (null-Host вҶ’ startet KEIN Auto-Handshake, setzt kein IP-SNI), dann `serverNames = SNIHostName(originalHost)`, dann `startHandshake()` selbst aufrufen. Zu spaet setzen вҶ’ kein SNI вҶ’ Cloudflare lehnt ab (v6-Fehlversuch). SNI nur auf API 24+ (per `Build.VERSION.SDK_INT >= 24` guard); minSdk 21 bleibt sicher (no-Op).

**Verifiziert im v7-Handy-Log (mobil unterwegs):** `resolve: kinoger.com -> 104.21.45.164`, `SniSocketFactory: starting handshake with SNI=kinoger.com`, `httpGet: kinoger.com/... -> 200`, `DEX scraper Kinoger returned 3 results`, `DEX scraper FilmPalast returned 2 results`. Odysseusa-URL enthielt die mobile IPv6 des Handys вҶ’ Stream wirklich vom Mobilfunk.

**Performance-Nachteile DoH:** ca. 50вҖ“150ms beim ERSTEN Aufruf pro Host (ein DoH-Lookup), danach cached, kein weiterer Overhead. Videoqualitaet/Speed unberuehrt. Cloudflare sieht aufgeloeste Hostnamen (nicht den Video-Inhalt, der bleibt HTTPS-verschluesselt).

**DoH-Debug-Log-Zeilen (im Logcat):** `D/ArvioAddon[DohResolver]: resolve: HOST -> IP (ttl Ns)` (DoH liefert IP), `D/ArvioAddon[DohResolver]: SniSocketFactory: starting handshake with SNI=HOST` (SNI vor Handshake gesetzt вҖ” sollte bei Scraper-Seiten kommen). Bleibt `SSLV3_ALERT_HANDSHAKE_FAILURE` вҶ’ SNI-Setup scheitert oder Host fehlt in `DOH_HOSTS`.

**Plan B (NICHT noetig geworden, nur falls SNI bei einem neuen CDN bricht):** OkHttp direkt ins .cs3-DEX buendeln + eigenen OkHttpClient mit DoH-Dns-Interface (wie Cloudstream3). Groesserer Umbau, aber garantiert SNI/HTTP2/TLS sauber.

**Naechste Phase (Prio 1): Vavoo Filme/Serien als .cs3-Modul (Konsolidierung Phase 2).** Siehe вҖһKONSOLIDIERUNGS-PLAN" weiter unten. Vavoo API-basiert (POST ping вҶ’ addonSig вҶ’ POST mediahubmx-source.json вҶ’ Mirror-Liste), kein Bot-Schutz, Vorlage `src/source/Vavoo.ts` im Stremio-Addon (TypeScript, ~297 Zeilen). DoH-Fix greift auch hier (Vavoo-Domains ggf. zur `DOH_HOSTS` addieren, falls mobil gesperrt). Aufbau wie FilmPalast/Kinoger: TmdbProvider, java.net+DoH, org.json, ExtractorLink primary-ctor, catch(Throwable).

---


## вҖјГ”ЕӮЕ№ KURZ-STAND (Stand 16.08.2026, fuer naechste Session вҖ” LESEN BEVOR ARBEIT BEGINNT)

**Aktueller Stand:** Drei Scraper-Module auf `builds`-Branch (FilmPalast v30 вң… funktioniert, Serienstream v32 TV-Test ausstehend, **Kinoger v2 TV-Test ausstehend**). **Handy-Logcat erfolgreich eingerichtet** (Termux + adb, keine LADB/Shizuku nГ¶tig).

**AKTUELLSTE PRIORITГ„T: Kinoger v2 am Handy/TV testen.**
- v1-TV-Test (16.08., Handy-Logcat) zeigte: Scraper lГӨuft KOMPLETT durch (Download, plugin.load, `TmdbProvider Kinoger: load({"id":603})`, TMDB-Meta, HTTP-Search `200`), ABER `searchKinoger: CSS selector matched 0 elements` вҶ’ 0 Quellen.
- **v2-Fix:** KinoGer-Suchergebnisse sind in `div.content_text.searchresult_img` (NICHT `section.post`!), Titel im `<img alt>`, Link `a[href*=/stream/]`. Live validiert: 6 Ergebnisse fГјr вҖһMatrix", korrekter Match вҖһMatrix (1999)".
- **NГӨchster Schritt:** Nutzer testet v2 (Repo lГ¶schen + neu hinzufГјgen DIREKT, Matrix-Suche), schickt Log via `~/save-handy-log.sh kinoger2`. Erwartung: `resolveIncvideo: [1080p]https://вҖҰmp4` = erste KinoGer-Quelle.

**Zweit-PrioritГӨt: Serienstream v32-TV-Test** (ALTCHA-PoW-Solver). v31 zeigte Scraper lГӨuft komplett durch (29 Episoden, 4 Hoster), aber `/r?t=`-Redirects blockten. v32 implementiert ALTCHA-PoW (`solveAltcha` + `resolveRedirectGate` + `doRequestPost`). Hypothese: am TV (Wohn-IP) kommt `/r?t=` mit 200 durch, am Laptop 403.

**Handy-Logcat-Setup (erfolgreich, 16.08.):** Nur Termux + adb (robuste Methode, keine LADB/Shizuku). Einrichtung: `pkg install android-tools` вҶ’ `adb pair <IP>:<PairingPort>` вҶ’ **WICHTIG: `adb connect <IP>:5555`** (nicht Pairing-Port!) вҶ’ `adb shell pm grant com.termux android.permission.READ_LOGS` вҶ’ `adb disconnect`. Danach nur noch `logcat` (ohne adb). PrГјfen: `logcat -d | head` вҶ’ вҖһbeginning of kernel" = Berechtigung da. Details siehe вҖһHANDY-LOGCAT-SETUP" im AKTUELLER STAND-Abschnitt + `docs/handy-logcat-ladb-termux.md`.

Siehe вҖһAKTUELLER STAND" ganz unten fuer volle Details.

## вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖқД®вҖ“ГӯвҖңГі AKTUELLER STAND & NвҖ“ДҸвҖңДҸвҖ“ГӯвҖҡГ„ДӣCHSTE SCHRITTE (Stand 14.08.2026 вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә LOGCAT-ERKENNTNIS)

### ENTSCHEIDENDE ERKENNTNIS (14.08.2026, Logcat via USB+adb auf Pixel 7): Die .cs3-Dateien werden NIE heruntergeladen
**Root-Cause gefunden und verifiziert im Logcat + ARVIO-Source.** Das Problem ist NICHT, dass ARVIO den Scraper nicht aufruft вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә ARVIO ruft ihn auf, findet aber keine Datei.

**Logcat-Befund (arvio-log.txt, arvio-log2.txt, Pixel 7, ARVIO 1.9.983 sideload):**
```
D PluginManager: Streaming execution of 18 scrapers for movie:603   <-- Scraper-Pfad wird durchlaufen (Matrix, TMDB 603) вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңВЈвҖ“ГӯвҖҡГ„Гә
D PluginManager: Executing DEX scraper: FilmPalast                    <-- ARVIO will jeden Scraper ausfвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—hren вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңВЈвҖ“ГӯвҖҡГ„Гә
E ExtExtensionLoader: DEX file not found for <repoId>:FilmPalast: /data/user/0/com.arvio.tv/files/cs_extensions/<repoId>_FilmPalast.cs3  вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңВЈвҖ“ГӯвҖҡГ„Д’ DATEI FEHLT
E ExtExtensionRunner: No API loaded for scraper: <repoId>:FilmPalast  вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңВЈвҖ“ГӯвҖҡГ„Д’
D PluginManager: DEX scraper FilmPalast returned 0 results             вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңВЈвҖ“ГӯвҖҡГ„Д’
```
- Passiert bei ALLEN 18 Scrapern (FilmPalast, HDFilme, Kinoger, ARD, Discovery, Arte, KinoKing, EinschaltenIn, HuhuTo, PlutoTV, Megakino, Serienstream, Netzkino, SpiegelTV, Moflix, Xcine, Welt) вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә unserem UND GermanProviders. BestвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®tigt: ARVIO-seitiges Problem.
- `ExtExtensionLoader: ensureExtractorsLoaded: scanned 0 .cs3 files, registered 0 extractors` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ `cs_extensions`-Ordner komplett LEER.
- WebStreamr (Stremio-Addon) funktioniert (3 streams, 757ms/322ms) вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ Stremio-Pfad lвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®uft, nur .cs3-Pfad kaputt.
- **KEIN einziger Download-Versuch im Log.** Kein "Downloading", keine HTTP-Request zu raw.githubusercontent.com, kein "Failed to download extension: HTTP ...", kein "Downloaded extension". ARVIO hat die Scraper-Metadaten (Name/ID/URL aus plugins.json) in der Datenbank, aber die .cs3-Datei nie heruntergeladen.

**Warum der Download nie stattfindet (verifiziert im ARVIO-Source @ v1.9.983):**
- `PluginManager.addRepository()` (PluginManager.kt:426) ruft `downloadDexExtensions(repo.id, parseResult.plugins)` auf вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ das lвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®dt die .cs3-Dateien herunter (parallel via `downloadExtension`).
- **ABER: Der Nutzer fвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—gt Repos via Cloud-Sync hinzu, nicht via Add-Repository-Dialog!** `CloudSyncRepository.applyCloudPayload()` (CloudSyncRepository.kt:1721-1731) macht beim Restore nur:
  - `pluginDataStore.saveRepositories(repos)` (nur Metadaten in DB)
  - `pluginDataStore.saveScrapers(scrapers)` (nur Metadaten in DB вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә inkl. URL, aber KEIN Download!)
  - `pluginDataStore.setPluginsEnabled(...)` (global an)
  - **KEIN Aufruf von `downloadDexExtensions`!** Cloud-Sync synchronisiert Scraper-Metadaten, aber NICHT die .cs3-Dateien.
- Folge: Scraper erscheint in der Liste (Metadaten da), Toggle speichert (manifestEnabled=true, status=1), ARVIO versucht AusfвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—hrung ("Executing DEX scraper") вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә aber `cs_extensions/` ist leer вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ "DEX file not found" вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ "No API loaded" вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ 0 results.
- Das erklвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®rt, warum es auf TV UND Handy identisch ist: Cloud-Sync kopiert nur Metadaten, die .cs3-Downloads werden pro-GerвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®t nur bei direktem `addRepository`/`refreshExternalRepository` getriggert вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә und der Nutzer hat (vermutlich wegen des Touch-Bugs frвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—her) alles вҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—ber Cloud-Profil gemacht, nie direkt auf dem GerвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®t.

**NвҖ“ДҸвҖңДҸвҖ“ГӯвҖҡГ„ДӣCHSTER SCHRITT (Prio 1): Repo auf dem GerвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®t DIREKT hinzufвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—gen (nicht Cloud-Sync), dabei Logcat mitlaufen lassen**
Ziel: sehen, ob `downloadDexExtensions`вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ`downloadExtension` вҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—berhaupt aufgerufen wird und ob der Download fehlschlвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®gt (HTTP 404/403/Timeout) oder ob ARVIO den Download gar nicht erst triggert.
1. `adb logcat -c` (Puffer leeren).
2. Am Pixel 7 in ARVIO: Repos LвҖ“ДҸвҖңДҸвҖ“ГӯвҖҡГ„ДҸSCHEN (beide: Arvio-Addon + GermanProviders).
3. Am Pixel 7 in ARVIO: **Add Repository** DIREKT auf dem GerвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®t вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ Repo-URL eingeben (`https://raw.githubusercontent.com/ReichiMD/Arvio-Addon/main/repo.json`) вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ hinzufвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—gen. WICHTIG: nicht вҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—ber Cloud-Sync/Profil, sondern direkt den Add-Repo-Dialog auf dem GerвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®t nutzen.
4. Warten, bis ARVIO "Repository hinzugefвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—gt" meldet (sollte .cs3 downloaden).
5. Scraper einschalten.
6. `adb logcat -d | grep -iE "download|ExtExtension|PluginManager|cs3|HTTP|Failed|extension"` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ Output kopieren. Worauf achten:
   - `Downloaded extension <id>: <bytes> bytes -> ...` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ Download ERFOLGREICH (Problem gelвҖ“ДҸвҖңДҸвҖ“ГӯВ¬вҲӮst!).
   - `Failed to download extension <id>: HTTP 404` / `HTTP 403` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ URL falsch/blocked (unsere plugins.json URL prвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—fen).
   - `Error downloading extension <id>: ...` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ Exception (Netzwerk/SSL/Timeout).
   - Gar kein Download-Log вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ `addRepository` wird nicht wie erwartet durchlaufen (Routing-Problem).
7. Falls Download klappt: Quellensuche (Matrix) auslвҖ“ДҸвҖңДҸвҖ“ГӯВ¬вҲӮsen вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ prвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—fen, ob jetzt Filmpalast-Quellen kommen.
8. Falls Download fehlschlвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®gt: unsere `plugins.json`/`.cs3`-URL im builds-Branch prвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—fen (raw.githubusercontent.com erreichbar? Datei da? status=1?).

**Prio 2 (danach): Am TV dasselbe** вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә TV per USB ans Laptop, gleicher `adb logcat`-Flow. Da der TV das primвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®re ZielgerвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®t ist, muss der Download dort auch direkt (Add Repository) getriggert werden, nicht вҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—ber Cloud-Sync. LADB-App scheiterte am Pairing (siehe unten)вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ TV braucht USB-Verbindung zum Laptop (dazu ggf. lвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®ngeres USB-Kabel am TV oder USB-Port am TV nutzen).

**Prio 3: GitHub-Issue bei ARVIO** вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә Cloud-Sync-Restore lвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®dt .cs3-Dateien nicht herunter (`saveScrapers` ohne `downloadDexExtensions`). Das ist ein klarer ARVIO-Bug: Wer Plugins via Cloud-Sync auf ein neues GerвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®t вҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—bernimmt, hat leere Scraper. Skizze siehe unten (Prio 2 im alten Stand). AI-Disclosure beachten.

### ENTSCHEIDENDE ERKENNTNIS #2 (14.08.2026, TV-Logcat TCL C7K via WLAN-ADB): Download klappt DIREKT am TV, aber plugin.load() crasht an kotlin/io/FilesKt
Nach dem Fix des Cloud-Sync-Problems (Repo direkt am TV hinzugefuegt, NICHT Cloud-Sync) klappt der .cs3-Download:
```
D PluginManager: URL ends in .json - trying external format first: https://raw.githubusercontent.com/ReichiMD/Arvio-Addon/main/repo.json
D ExtExtensionLoader: Downloaded extension 06513e3e...:FilmPalast: 45272 bytes -> .../cs_extensions/..._FilmPalast.cs3
D PluginManager: Downloaded DEX extension: FilmPalast (45272 bytes)
D ExtExtractorRegistry: Registered extractor: Abstream (https://abstream.to)
D ExtExtensionLoader: ensureExtractorsLoaded: scanned 1 .cs3 files, registered 2 extractors
```
ABER plugin.load() schlaegt fehl (Root-Cause #2 gefunden):
```
D ExtExtensionLoader: Loading plugin class from manifest: com.reichi.arflioaddon.filmpalast.FilmpalastPlugin
W ExtExtensionLoader: plugin.load() MISSING CLASS: Failed (0 APIs so far)
W ExtExtensionLoader: java.lang.NoClassDefFoundError: Failed resolution of: Lkotlin/io/FilesKt;
W ExtExtensionLoader:    at com.reichi.arflioaddon.filmpalast.DebugLog.init(DebugLog.kt:47)
W ExtExtensionLoader:    at com.reichi.arflioaddon.filmpalast.FilmpalastPlugin.load(FilmpalastPlugin.kt:13)
```
Erklaerung: ARVIO laedt .cs3 via DexClassLoader mit ARVIOs (release-build, R8-geshrinktem) Classloader als Parent. Unsere DebugLog.init() rief `markerFile?.writeText(...)` auf - eine Kotlin-stdlib-Extension aus kotlin/io/FilesKt. ARVIOs Release-APK shrinkt ungenutzte kotlin-stdlib-Klassen weg, also ist kotlin/io/FilesKt im Parent-Classloader NICHT vorhanden -> NoClassDefFoundError. Da NoClassDefFoundError ein Error (keine Exception) ist, wurde er vom `catch (e: Exception)` NICHT gefangen und killte plugin.load() -> "0 APIs so far" -> "No API loaded" -> 0 Quellen. GermanProviders-Plugins nutzen keine kotlin.io File-Erweiterungen im load()-Pfad, deshalb wuerden sie (wenn heruntergeladen) laufen - wir aber nicht.

### FIX #2 (14.08.2026, v9): kotlin-stdlib-Erweiterungen aus dem load()-Pfad entfernt
Alle Kotlin-stdlib-IO-Erweiterungen durch reines java.io/java.lang ersetzt (kein Risiko mehr, dass ARVIOs geshrinkter Classloader die stdlib-Klasse nicht bereitstellt):
- DebugLog.kt: writeText()->writeTextJava() (java.io.FileOutputStream), appendText()->appendTextJava(). Catch-Bloecke von Exception auf Throwable geaendert (faengt nun auch NoClassDefFoundError). Hilfsfunktionen nutzen String.getBytes(StandardCharsets.UTF_8) statt toByteArray() und try/finally statt .use {} (CloseableKt koennte ebenfalls fehlen).
- DownloadsLogWriter.kt: writeText()->writeTextJava(); MediaStore-Pfad .use {}/.toByteArray()->writeAndClose()/truncateAndClose() (java.io, try/finally). Cursor .use {}->try/finally.
- DebugServer.kt: kotlin.concurrent.thread{}->java.lang.Thread (mit .isDaemon/.name/.start()); String.toByteArray()->String.getBytes(StandardCharsets.UTF_8).
- FilmpalastPlugin.kt: load() umschliesst DebugLog.init+DebugServer.start in `try { } catch (t: Throwable)` (best-effort - ein Diagnose-Fehler killt nie mehr den Scraper). registerMainAPI/registerExtractorAPI laufen immer.
- FilmpalastProvider/FilmpalastExtractors nutzen KEINE kotlin-stdlib-IO-Erweiterungen (verifiziert) - Scraper-Pfad ist sicher.
- Version auf 9 gebumpt. CI baut beim Push auf main automatisch die neue FilmPalast.cs3 und pusht auf builds.
**ABER v9 crashte ARVIO trotzdem** (siehe ERKENNTNIS #3 unten) - der try/catch(Throwable) half nicht, weil der Crash in einem asynchronen Thread passierte, der ausserhalb von load() lief.

### ENTSCHEIDENDE ERKENNTNIS #3 (14.08.2026, v9-TV-Test, Crash-ID 628a90de...): DebugLog$Level enum braucht kotlin.enums.EnumEntriesKt -> FATAL CRASH
v9 crashte ARVIO beim Quellen-Suchen (ARVIO zeigte "Bericht an Discord senden", Crash-ID 628a90dec76d4c21bd162f80d63a271d, Error: NoClassDefFoundError: com.reichi.arflioaddon.filmpalast.DebugLog$Level).
TV-Logcat (arvio-tv-log2.txtp):
```
W ArvioAddon[DownloadsLogWriter]: writeMarker failed: Failed resolution of: Lkotlin/text/Channels;
I com.arvio.tv: Rejecting re-init on previously-failed class DebugLog$Level: NoClassDefFoundError: Lkotlin/enums/EnumEntriesKt
I com.arvio.tv:   at DebugLog$Level.<clinit>() (DebugLog.kt:35)
I com.arvio.tv:   at DebugLog.init(DebugLog.kt:64)
I com.arvio.tv:   at FilmpalastPlugin.load(FilmpalastPlugin.kt:15)
E AndroidRuntime: FATAL EXCEPTION: ArvioAddon-DebugServer
E AndroidRuntime: java.lang.NoClassDefFoundError: com.reichi.arflioaddon.filmpalast.DebugLog$Level
E AndroidRuntime:  at DebugLog.t(DebugLog.kt:70)
E AndroidRuntime:  at DebugServer.start$lambda$0(DebugServer.kt:41)   <- asynchroner Thread!
E AndroidRuntime:  ... at Thread.run
```
**Erklaerung:** ARVIOs R8-geshrinkter Classloader fehlt MEHRERE kotlin-stdlib-Klassen: kotlin.io.FilesKt (v8), kotlin.text.Channels (DownloadsLogWriter), kotlin.enums.EnumEntriesKt (DebugLog$Level enum). Modernes Kotlin kompiliert `enum class` via EnumEntriesKt. DebugLog$Level.<clinit> (Klassenladen) beim ersten DebugLog.t()-Aufruf braucht EnumEntriesKt -> NoClassDefFoundError. Das passierte im **DebugServer-Hintergrund-Thread** (DebugServer.kt:41 ruft DebugLog.t("listening...")) - der Thread laeuft asynchron NACH load() und liegt NICHT im load()-try/catch(Throwable) -> Exception entkommt zum UncaughtExceptionHandler -> ARVIO-Prozess crasht (FATAL). Der defensive try/catch in load() half also nicht.

### FIX #3 (14.08.2026, v10): gesamte Diagnose-Infrastruktur entfernt, nur noch android.util.Log
Jetzt wo logcat via WLAN-ADB zuverlaessig verfuegbar ist, ist die ganze Diagnose-Infrastruktur (die nie funktioniert hat, weil das Plugin nie weit genug lief) net-negativ und crasht die App. Komplett entfernt, ersetzt durch plain android.util.Log (immer verfuegbar, keine kotlin-stdlib-Abhaengigkeit):
- **DebugLog.kt neu geschrieben**: minimaler android.util.Log-Wrapper. KEIN enum Level (enum <clinit> brauchte EnumEntriesKt), kein File-IO (FilesKt), kein Ring-Buffer, keine Threads. Methoden t/w/e leiten direkt an Log.d/w/e weiter.
- **DebugServer.kt geloescht** (ServerSocket-Thread + kotlin.concurrent.thread).
- **DownloadsLogWriter.kt geloescht** (MediaStore + FilesKt/Channels).
- **FilmpalastPlugin.load()**: nur registerMainAPI + registerExtractorAPI + ein Log.d. Keine Diagnose-Threads mehr -> kein asynchroner Crash-Pfad.
- **FilmpalastProvider**: debugLoadResponse()/emitTraceAsSources() (Fake-Quellen) entfernt; pluginVersion-Feld entfernt; load() gibt bei Fehler null zurueck (ARVIO handled null); loadLinks() emittiert keine Fake-Debug-Quellen mehr. echtes Tracing geht nur noch ins logcat.
- Version auf 10 gebumpt. CI gruen. builds-Branch: FilmPalast.cs3 v10 (33368 Bytes, status=1).
**ABER v10 crashte ARVIO auch** (siehe ERKENNTNIS #4) - weil nicht die Diagnose das Problem war, sondern ARVIOs Classloader die KERN-kotlin-stdlib fehlt.

### ENTSCHEIDENDE ERKENNTNIS #4 (14.08.2026, v10-TV-Test): kotlin.collections.SetsKt fehlt -> ARVIO hat praktisch die GESAMTE kotlin-stdlib geschrumpft
v10 (ohne Diagnose-Code) crashte ARVIO immer noch beim Quellen-Suchen:
```
W ExtExtensionLoader: plugin.load() MISSING CLASS: Failed (0 APIs so far)
W ExtExtensionLoader: java.lang.NoClassDefFoundError: Failed resolution of: Lkotlin/collections/SetsKt;
W ExtExtensionLoader: Caused by: java.lang.ClassNotFoundException: kotlin.collections.SetsKt
```
`kotlin.collections.SetsKt` ist die fundamentalste kotlin-stdlib-Klasse (setOf, listOf etc.). DAS BEWEIST: ARVIOs R8-Shrinking hat praktisch die GESAMTE kotlin-stdlib aus dem Parent-Classloader entfernt, die ARVIO selbst nicht direkt nutzt. Stueckweises Patchen (Whack-a-Mole: FilesKt -> EnumEntriesKt -> Channels -> SetsKt -> ...) ist UNMOEGLICH, weil jeder Kotlin-Code zwangslaeufig kotlin.collections nutzt.
**Architektur-Problem:** Das cloudstream3-Plugin-Modell geht davon aus, dass die Host-App die volle kotlin-stdlib bereitstellt (die echte Cloudstream3-App tut das; ARVIO tut es NICHT wegen R8). Der cloudstream3-Gradle-Plugin kompiliert nur die Plugin-eigenen Klassen in die .cs3-DEX (compileDex.input = compileDebugKotlin.destinationDirectory) - die stdlib wird von der Host-App erwartet. GermanProviders (identische Build-Config) wuerde auf ARVIO dasselbe Problem haben (erklaert die urspruengliche "0 Quellen"-Beobachtung).

### FIX #4 (14.08.2026, v11): kotlin-stdlib IN die .cs3-DEX bвҲҡДҫndeln
Statt auf den Parent-Classloader zu vertrauen, wird die kotlin-stdlib (+ kotlinx-coroutines) direkt in die .cs3-DEX kompiliert. Der DexClassLoader findet die stdlib-Klassen dann in der Plugin-eigenen DEX, unabhaengig vom Parent.
- **build.gradle.kts (root):** neue `bundleStdlib`-Configuration (kotlin-stdlib:2.3.0 + kotlinx-coroutines-core:1.10.1). `extractStdlibForDex`-Task (doLast, java.util.zip.ZipFile) entpackt die kotlin/** + kotlinx/** + .kotlin_module-Entries aus den JARs in ein Build-Verzeichnis. `compileDex`-Task (CompileDexTask) bekommt dieses Verzeichnis als zusaetzliches `input` -> die stdlib-Klassen werden mit in die classes.dex kompiliert.
- CI-Iterationen noetig wegen Kotlin-Gradle-DSL-Typ-Inferenz-Problemen bei register/named -> finale Loesung: `tasks.create` + `doLast` + raw ZipFile (keine Gradle-Copy/Sync-DSL-Typ-Probleme).
- Version auf 11 gebumpt. CI gruen. builds-Branch: FilmPalast.cs3 v11 (1.269.376 Bytes = ~1,27 MB statt 33 KB -> stdlib ist drin). status=1.

### ENTSCHEIDENDE ERKENNTNIS #5 (14.08.2026, v11-TV-Test): stdlib-Fix hat funktioniert! Aber mainPageOf(Pair) von R8 geschrumpft
v11-Test (arvio-tv-log4.txt) zeigt GROSSEN Fortschritt:
- v11 wird heruntergeladen (1269376 Bytes - stdlib drin), `load()` laeuft, Provider-Klasse wird instanziiert, Extractoren gefunden (`Fallback found ExtractorApi: Abstream`, `Ryderjet`). **Der `NoClassDefFoundError: kotlin.collections.SetsKt` ist WEG.** stdlib-Buendeln hat funktioniert.
- **NEUER Fehler:** `NoSuchMethodError: No static method mainPageOf([Lkotlin/Pair;)Ljava/util/List; in class com.lagradost.cloudstream3.MainAPIKt` bei `FilmpalastProvider.<init>(FilmpalastProvider.kt:58)`.
- ARVIOs R8-Shrinking hat die `mainPageOf(vararg Pair<String,String>)`-Ueberladung aus der cloudstream3-library entfernt (ARVIOs eigene Provider nutzen sie nicht). Unsere `mainPage = mainPageOf("" to "Neu", ...)` ruft genau diese geschrumpfte Ueberladung auf -> `NoSuchMethodError` bei der Provider-Instanziierung -> "No API loaded" -> 0 Quellen.
- Dies ist das **erste R8-geshrinkte cloudstream3-API-Methode** (im Gegensatz zu kotlin-stdlib). Hauptunterschied: ARVIO nutzt cloudstream3 selbst (eingebaute TmdbProvider/MainAPI), also sind die meisten MainAPI-Helper/Datenklassen retained. `mainPageOf(Pair)` ist eine Convenience-Funktion die ARVIOs eigene Provider nicht nutzen.
- **webstreamr leer** (im selben Log): `StreamRepository: timeout addon=WebStreamrMBG timeoutMs=6000`. webstreamr ist ein serverseitiges Stremio-Addon (baby-beamup.club) - der Server antwortet nicht innerhalb 6s. Netzwerk/Server-Problem bei webstreamr, unabhaengig von uns.

### FIX #5 (14.08.2026, v12): mainPage direkt als listOf(MainPageData) statt mainPageOf(Pair)
`mainPageOf(vararg Pair)` ersetzt durch direkte `listOf(MainPageData(name=..., data=...))`-Konstruktion. `MainPageData` ist eine Datenklasse die ARVIOs eigene Provider nutzen -> von R8 retained. `mainPageOf`-Import entfernt, `MainPageData`-Import hinzugefuegt.
- Version auf 12 gebumpt. CI gruen. builds-Branch: FilmPalast.cs3 v12 (1269371 Bytes, status=1).
- **Erwartung v12-Test:** plugin.load() durchlaufen OHNE NoSuchMethodError (MainPageData ist retained). "API loaded" / "Executing DEX scraper: FilmPalast" sichtbar. Falls eine ANDERE cloudstream3-Methode geshrumpft ist (z.B. newMovieLoadResponse/newTvSeriesLoadResponse/loadExtractor), wird der naechste NoSuchMethodError/NoClassDef auftauchen -> dann jeweilige Methode durch retained-Alternative ersetzen.

### ENTSCHEIDENDE ERKENNTNIS #6 (14.08.2026, v12-TV-Test): mainPageOf weg, aber MainPageData-ctor von R8 geschrumpft
v12-Test (arvio-tv-log5.txt): `mainPageOf`-Fehler WEG, aber neue Huerde:
```
NoSuchMethodError: No direct method <init>(Ljava/lang/String;Ljava/lang/String;ZILkotlin/jvm/internal/DefaultConstructorMarker;)V
in class com.lagradost.cloudstream3.MainPageData
at FilmpalastProvider.<init>(FilmpalastProvider.kt:62)
```
R8 hat den **synthetischen Default-Argument-Konstruktor** von `MainPageData` entfernt. Unser `MainPageData(name=..., data=...)` nutzt Default-Args -> Kotlin generiert den synthetischen `(String,String,boolean,int,DefaultConstructorMarker)`-ctor, den R8 geschrumpft hat. MainPageData ist zwar retained, aber nur der explizite 2-Parameter-ctor wuerde funktionieren (Default-Args sind weg).
**Wichtige Erkenntnis:** `mainPage`/`hasMainPage`/`getMainPage` werden **NUR von der Cloudstream3-App-Startseite** genutzt. ARVIOs Scraper-Pfad (`executeTmdbProvider`) ruft nur `load()` + `loadLinks()` auf вҖҡГ„Д’ `getMainPage()` wird **nie** aufgerufen. Also ist das alles **toter Code fuer ARVIO**, der nur R8-Strip-Fehlerpunkte schafft.

### FIX #6 (14.08.2026, v13): mainPage/getMainPage komplett entfernt
`hasMainPage`, `mainPage`, `getMainPage()` **komplett geloescht** вҖҡГ„Д’ keine MainPageData-Konstruktion mehr. Provider ueberschreibt jetzt nur noch, was ARVIO wirklich aufruft: `search()`, `load()`, `loadLinks()`. Das minimiert die R8-geshrinkte cloudstream3-API-Oberflaeche auf das, was ARVIO selbst nutzt.
- Version auf 13 gebumpt. CI gruen. builds-Branch: FilmPalast.cs3 v13 (1268533 Bytes, status=1).
- **Erwartung v13-Test:** plugin.load() durchlaufen OHNE NoSuchMethodError (keine MainPageData mehr). "API loaded" / "Executing DEX scraper: FilmPalast" -> load() laeuft. Falls naechster R8-Strip (z.B. newMovieLoadResponse/newTvSeriesLoadResponse/loadExtractor): jeweilige Methode notieren -> retained-Alternative oder direkten Konstruktor verwenden.

### ENTSCHEIDENDE ERKENNTNIS #7 (14.08.2026, v13-DEX-Analyse + ARVIO-APK-Analyse): R8 hat kotlin.coroutines.Continuation obfuscated вҖҡГ„Д’ suspend-Overrides funtionieren NICHT
**Root-Cause fuer "load() override wird nicht aufgerufen" gefunden und verifiziert durch DEX-Bytecode-Analyse der ARVIO-APK.**

v13 laedt erfolgreich (Provider registriert, Extractoren registriert, "API loaded" bestвҲҡВ§tigt in log6). Aber ARVIO ruft bei `api.load(loadJson)` die **PARENT** `TmdbProvider.load()` auf, nicht unseren Override. Das Ergebnis: `ErrorLoadingException: No id found` (parent parst JSON nicht), dann Fallback-URL `themoviedb.org/movie/603` (parent parst URL, ruft `loadFromTmdb` auf, aber wir haben das nicht ueberschrieben), dann `both load() paths failed` вҖҡГңГӯ 0 Quellen.

**Warum der Override nicht bindet (verifiziert im ARVIO-APK-Bytecode):**
- ARVIOs R8 (full mode, `isMinifyEnabled=true`) hat **kotlin.coroutines.Continuation zu `j7.d` obfuscated** und **kotlin.jvm.functions.Function1 zu `x7.l`**.
- Die `-keep class com.lagradost.** { *; }`-Regel behaelt cloudstream3-Klassennamen + Methodennamen, aber R8 obfuscated die **Parameter-TYPEN** in Methodensignaturen unabhaengig davon.
- ARVIOs `MainAPI.load()` in der kompilierten APK hat Signatur: `load(Ljava/lang/String;Lj7/d;)Ljava/lang/Object;` (j7.d = obfuscated Continuation).
- Unser `FilmpalastProvider.load()` in der .cs3-DEX hat Signatur: `load(Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;` (unobfuscated, weil wir gegen `pre-release`-Stub kompilieren).
- **Lj7/d; != Lkotlin/coroutines/Continuation;** вҖҡГңГӯ JVM findet unseren Override nicht вҖҡГңГӯ virtual dispatch faellt auf parent zurueck вҖҡГңГӯ parent laeuft.
- **Dasselbe gilt fuer `loadLinks` und `search`:** alle suspend-Methoden haben Continuation-Parameter вҖҡГңГӯ alle Overrides sind broken.
- ARVIOs `executeTmdbLoadLinks$completed$1` ruft auf: `invoke-virtual MainAPI->loadLinks(Ljava/lang/String;ZLx7/l;Lx7/l;Lj7/d;)Ljava/lang/Object;` (x7.l = obfuscated Function1, j7.d = obfuscated Continuation).
- Unsere `loadLinks` hat: `(Ljava/lang/String;ZLkotlin/jvm/functions/Function1;Lkotlin/jvm/functions/Function1;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;` вҖҡГңГӯ mismatch.

**Verifizierte Obfuscation-Map (aus ARVIO-APK extrahiert, classes4.dex/classes5.dex):**
| Original-Typ | Obfuscated | Wo gefunden |
|---|---|---|
| `kotlin.coroutines.Continuation` | `j7.d` | MainAPI.load/loadLinks/search Signaturen; interface mit getContext():j7.j + resumeWith(Object):V |
| `kotlin.coroutines.CoroutineContext` | `j7.j` | j7.d.getContext() return type |
| `kotlin.jvm.functions.Function1` | `x7.l` | MainAPI.loadLinks callback-Parameter; interface mit invoke(Object):Object, extends d7.o |
| `kotlin.jvm.functions.Function` | `d7.o` | x7.l's super-interface |

**Nicht-obfuscated (durch `-keep` geschuetzt oder primitiv):**
- `com.lagradost.cloudstream3.**` (Klassen + Methoden-Namen; `-keep class com.lagradost.** { *; }`)
- `loadFromTmdb(I)Lcom/lagradost/cloudstream3/LoadResponse;` (int-Parameter = primitiv, LoadResponse = kept) **вҖҡГңГӯ dieser Override wuerde funktionieren!**
- `loadFromImdb(Ljava/lang/String;)Lcom/lagradost/cloudstream3/LoadResponse;` (String + LoadResponse = both unobfuscated) **вҖҡГңГӯ wuerde auch funktionieren!**

**WARUM das ALLE .cs3-Plugins in ARVIO betrifft (nicht nur unseres):**
- Jedes Cloudstream3-Plugin, das gegen den unobfuscated cloudstream3-Stub (GitHub `pre-release`/`v4.7.0`) kompiliert, hat unobfuscated Continuation/Function1 in seinen Override-Signaturen.
- ARVIOs R8-obfuscated Runtime hat j7.d/x7.l вҖҡГңГӯ **KEIN** externes .cs3-Plugin kann suspend-Methoden (load/loadLinks/search/getMainPage) korrekt ueberschreiben.
- Das erklaert endgueltig, warum GermanProviders (bewaehrtes Repo) in ARVIO auch 0 Quellen liefert, und warum GitHub-Issues #459/#273 dasselbe Symptom berichten.
- **ARVIOs EIGENE eingebaute Provider** sind davon nicht betroffen, weil sie im selben APK kompiliert wurden (gleiche obfuscated Typen).

### FIX #7 IMPLEMENTIERT (14.08.2026, v14): Post-Build DEX-Patching fuer obfuszierte Typen

**Ansatz 1 (gescheitert): Gegen obfuszierte cloudstream3 JAR kompilieren.**
ARVIO-APK -> dex2jar -> `arvio-cloudstream3-v1.9.983.jar` (10MB, gitignored) als `compileOnly`-Dependency. Aber: Der Kotlin-Compiler ignoriert die @Metadata-JVM-Signaturen der Parent-Klasse. Er generiert suspend-Override-Methoden IMMER mit `kotlin.coroutines.Continuation` (Kotlin-Sprachsemantik). Die .cs3-DEX enthielt immer noch `Lkotlin/coroutines/Continuation;` in den Method-Deskriptoren. Gescheitert.

**Ansatz 2 (ERFOLGREICH): Post-Build DEX-Patching.**
Da der Kotlin-Compiler die obfuszierten Typen nicht generiert, patchen wir die fertige DEX-Datei nach dem Build.
- **Skript:** `scripts/patch_dex_obfuscation.py`
- Ersetzt in der DEX-String-Tabelle: `Lkotlin/coroutines/Continuation;`->`Lj7/d;`, `Lkotlin/jvm/functions/Function1;`->`Lx7/l;`, `Lkotlin/coroutines/CoroutineContext;`->`Lj7/j;`, `Lkotlin/jvm/functions/Function;`->`Ld7/o;`
- **Padding-Trick:** Die Ersatzstrings sind kuerzer als die Originale. Freed space wird mit Nullen aufgefuellt -> keine Offset-Verschiebung -> string_id/type_id/proto_id/method_id-Tabellen bleiben unveraendert.
- SHA-1-Signatur + Adler32-Checksumme neu berechnet.
- **Integration:** In `build.gradle.kts` als `doLast` auf dem `make`-Task. CI (`build.yml`) laedt ARVIO APK automatisch herunter, extrahiert obfuszierte JAR via dex2jar (`scripts/extract_arvio_jar.py`), baut, patcht automatisch.
- `.gitignore`: `libs/arvio-cloudstream3-*.jar` excluded (10MB, regenerated by CI).

**Verifizierte Signaturen nach Patch (androguard-Check):**
```
FilmpalastProvider.load:      (Ljava/lang/String; Lj7/d;)Ljava/lang/Object;
FilmpalastProvider.loadLinks: (Ljava/lang/String; Z Lx7/l; Lx7/l; Lj7/d;)Ljava/lang/Object;
FilmpalastProvider.search:    (Ljava/lang/String; Lj7/d;)Ljava/lang/Object;
```
Diese Signaturen matchen ARVIOs Runtime -> Virtual Dispatch sollte unseren Override aufrufen.

**Alternative falls DEX-Patching nicht reicht (loadFromTmdb-Ansatz, noch nicht noetig):**
Da `loadFromTmdb(Int): LoadResponse?` eine nicht-suspend-Methode ist (kein Continuation-Parameter) und ihre Signatur in ARVIO unobfuscated ist, wuerde ein Override hier funktionieren. Aber `loadLinks` ist suspend -> bleibt broken. Nur fuer den Load-Teil nutzbar. NOCH NICHT noetig falls DEX-Patching funktioniert.

### AKTUELLER STAND (Stand 14.08.2026, Ende Session)

**Version 14 ist gebaut, gepatched und live auf `builds`-Branch:**
- `FilmPalast.cs3` v14 (1.268.540 Bytes, status=1, version=14)
- DEX gepatched: `Lj7/d;` und `Lx7/l;` in Override-Signaturen
- CI gruen (Run 31823358971, "fix(ci): use correct dex2jar repo URL...")
- Commits auf `main`: `829c057` (fix #7), `8d5b562` (CI fix), `1da4c1f` (docs)
- `plugins.json` auf builds: `version=14`, `fileSize=1268540`, `url=https://raw.githubusercontent.com/ReichiMD/Arvio-Addon/builds/FilmPalast.cs3`

**Was als naechstes passiert: NUTZER TESTET v14 AM TCL C7K TV (Windows 10 Laptop + WLAN-ADB)**

### ENTSCHEIDENDE ERKENNTNIS #8 (14.08.2026, v14-TV-Test, arvio-tv-log-v14.txt): v14 DEX ist KAPUTT вҖҡГ„Д’ ART-Verifizierung schlaegt fehl

v14 wurde heruntergeladen (1.268.540 Bytes), ABER ARVIOs ART-DEX-Verifier lehnt die DEX-Datei ab вҖҡГ„Д’ sie ist strukturell beschaedigt. Das Plugin wird NIE geladen:
```
D ExtExtensionLoader: Downloaded extension ...:FilmPalast: 1268540 bytes -> .../cs_extensions/..._FilmPalast.cs3
W com.arvio.tv: Failure to verify dex file '...FilmPalast.cs3': Non-zero padding b before section of type 8196 at offset 0x3111d2
E ExtExtensionLoader: Failed to load manifest class com.reichi.arflioaddon.filmpalast.FilmpalastPlugin: Didn't find class "...FilmpalastPlugin"
E ExtExtensionLoader: java.lang.ClassNotFoundException: Didn't find class "...FilmpalastPlugin"
E ExtExtensionLoader: No @CloudstreamPlugin class found in ...:FilmPalast
E ExtExtensionRunner: No API loaded for scraper: ...:FilmPalast
D PluginManager: DEX scraper FilmPalast returned 0 results
```
`8196 = 0x2004` (ENCODED_ARRAY_ITEM); `0x3111d2` liegt in der string_data-Region (String-Index 23018 = "zip$default", Byte 0x0b = ULEB128-Laenge 11). ART erwartet an einer Sektionsgrenze Null-Padding, findet aber String-Daten.

**Root-Cause (verifiziert per DEX-Analyse der builds-FilmPalast.cs3):** Der v14-Build kompilierte gegen die dex2jar-extrahierte obfuszierte ARVIO-JAR (`libs/arvio-cloudstream3-v1.9.983.jar` via `cloudstream(files(arvioJar))`). Dadurch bekamen die Override-Signaturen korrekt obfuszierte Typen (`load(Ljava/lang/String;Lj7/d;)` etc. вҖҡГ„Д’ verifiziert mit androguard, korrekt!). ABER dex2jars unvollstaendige Decompilierung der obfuszierten Interface-Klassen (`j7/d` = Continuation, `j7/j` = CoroutineContext, `x7/l` = Function1) wurde mit in die .cs3-DEX gebuendelt (3 class_defs: j7/d, j7/j, x7/l). Diese dex2jar-Klassen mit fehlerhaftem Bytecode/Gefuege korrumpten die DEX-Struktur (Sektionsgrenzen landeten mitten in string_data) -> ART-Verifier lehnt ab.
- Das DEX-Patch-Skript (Ansatz 2) war ein **No-Op** in v14 (0 Strings gepatched): die Signaturen waren bereits obfusziert (von der JAR), das Skript fand keine unobfuszierten Strings mehr. Der Patch hat also NICHT die Korrumpierung verursacht вҖҡГ„Д’ die dex2jar-Klassen haben es.
- ARVIOs EIGENE DEX (im APK) hat die identische map_list-Struktur und laedt einwandfrei -> die map_list ist NICHT das Problem; die Korrumpierung ist in unserem DEX-Body durch die dex2jar-Klassen.

**Warum v13 lud, v14 aber nicht:** v11-v13 kompilierten gegen den UNOBFUSZIERTEN Stub (`cloudstream3:pre-release`) -> keine dex2jar-Klassen -> valide DEX (aber Dispatch broken, da Signaturen unobfusziert). v14 wechselte auf die dex2jar-JAR -> korrekte Signaturen, ABER korrumpierte DEX. Beide Probleme (Dispatch + DEX-Gueltigkeit) wurden nie gleichzeitig geloest.

### FIX #8 (14.08.2026, v15): Zurueck zum unobfuszierten Stub + Post-Build-DEX-Patching (ohne dex2jar)

Die Loesung kombiniert v13s valide DEX-Struktur mit dem DEX-Patch-Ansatz (Ansatz 2), OHNE dex2jar:
- **build.gradle.kts:** Kompiliert wieder gegen den unobfuszierten Stub (`cloudstream("com.lagradost:cloudstream3:pre-release")`). Die dex2jar-JAR wird NICHT mehr verwendet -> keine dex2jar-Klassen in der DEX -> keine Korrumpierung. Die `libs/arvio-cloudstream3-*.jar` + der ganze dex2jar-Extraktionsschritt entfallen.
- **stdlib bleibt gebuendelt** (v11-Fix, unveraendert): kotlin-stdlib + kotlinx-coroutines werden in die .cs3-DEX kompiliert (ARVIOs Classloader hat sie geshrinkt). Die 4 suspend/coroutine-Typen (Continuation, CoroutineContext, Function, Function1) werden UNOBFUSZIERT gebuendelt.
- **Post-Build-DEX-Patch** (`scripts/patch_dex_obfuscation.py`, als `doLast` auf `make`): ersetzt die 4 Strings `Lkotlin/coroutines/Continuation;`->`Lj7/d;`, `Lkotlin/coroutines/CoroutineContext;`->`Lj7/j;`, `Lkotlin/jvm/functions/Function1;`->`Lx7/l;`, `Lkotlin/jvm/functions/Function;`->`Ld7/o;` in der DEX-String-Tabelle (Padding-Trick, keine Offset-Verschiebung, SHA-1+Adler32 neu). Das Skript ist KEIN No-Op mehr (der Stub erzeugt unobfuszierte Strings).
- **Dispatch-Logik (warum das klappt):** DexClassLoader nutzt parent-first-Delegation. Wir buendeln kotlin.coroutines.Continuation, patchen seinen Type-Descriptor-String zu `Lj7/d;` -> unsere DEX DEFINIERT eine (tote) Klasse j7/d. Aber bei Laufzeit loest jede `j7/d`-Referenz parent-first auf -> ARVIOs eigene j7/d (Continuation). Unsere tote j7/d wird nie geladen (kein Konflikt, keine Verify-Probleme da lazy-verify). Die Override-Methoden-Deskriptoren nutzen nun `Lj7/d;` -> beim Virtual Dispatch matcht ARVIOs MainAPI.load(String, j7/d) unseren Override (gleicher Klassen-Name, gleicher Classloader-Parent-Pfad) -> Dispatch bindet an UNSREN Override statt an den Parent.
- **CI (`build.yml`):** dex2jar-Extraktionsschritt entfernt (schneller, keine externen Downloads, kein Failure-Point). Python bleibt (fuer das Patch-Skript).
- **patch_dex_obfuscation.py:** `__main__` exitet bei 0 gepatchten Strings nicht mehr mit Code 1 (nur Warnung) -> kein Build-Bruch falls DEX bereits obfusziert.
- Version auf 15 gebumpt. CI baut beim Push auf main automatisch die neue FilmPalast.cs3 und pusht auf builds.

**Erwartung v15-Test:** Plugin laedt (valide DEX, keine dex2jar-Klassen), Override-Signaturen obfusziert (j7/d) -> ARVIO ruft UNSERN load()/loadLinks() auf statt Parent. Naechster moeglicher Fehler: Scraper-Logik (Jsoup-Selektoren, Hoster-Extraktion) вҖҡГ„Д’ dannEbene 2.

### ENTSCHEIDENDE ERKENNTNIS #9 (15.08.2026, v15-TV-Test, arvio-tv-log-v15-filtered.txt): v15 DEX KORRUPT вҖҡГ„Д’ Patch-Skript-Null-Padding bricht DEX-Struktur

v15 scheitert an **exakt demselben DEX-Verify-Fehler wie v14** (derselbe Offset `0x3111d2`, dieselbe DateigrвҲҡвҲӮвҲҡГјe 1.268.540 Bytes):
```
Failure to verify dex file '...FilmPalast.cs3': Non-zero padding b before section of type 8196 at offset 0x3111d2
ClassNotFoundException: Didn't find class "...FilmpalastPlugin"
No @CloudstreamPlugin class found in ...:FilmPalast
No API loaded for scraper: ...:FilmPalast
DEX scraper FilmPalast returned 0 results
```

**Root-Cause (verifiziert per DEX-Bytecode-Analyse der builds-FilmPalast.cs3):** Der Fehler liegt **NICHT** in dex2jar (wie bei v14 vermutet), sondern im **Patch-Skript selbst** (`scripts/patch_dex_obfuscation.py`).

Das Skript ersetzt lange Kotlin-Typ-Strings durch kurze obfuszierte Namen (z.B. `Lkotlin/coroutines/Continuation;` 34 Zeichen вҖҡГңГӯ `Lj7/d;` 6 Zeichen) und fuellt die Laengendifferenz (**26вҖҡГ„ДҸ30 Bytes**) mit **Null-Padding** auf, um keine Offsets verschieben zu muessen. **ABER:** die DEX `string_data`-Sektion packt Strings direkt hintereinander (Format: ULEB128(utf16_size) + mutf8_data + \x00). Nach jedem gekuerzten String liegen jetzt 26вҖҡГ„ДҸ30 Null-Bytes **mitten in der Sektion**. ARTs Verifier erwartet dort KEINE Null-Padding-Sequenzen (nur am Sektionsende) und lehnt die DEX ab.

Verifizierte Beweislage:
- **3 Strings korrekt gepatched:** `Lj7/d;` (string[10933] @ 0x2a4576, 26B padding), `Lj7/j;` (string[10946] @ 0x2a47e6, 30B padding), `Lx7/l;` (string[11172] @ 0x2a6ff0, 26B padding). Patch-Mechanismus funktioniert prinzipiell.
- **Aber:** 3вҲҡГі ~28B = ~84B Null-Padding mitten in der string_data-Sektion вҖҡГңГӯ DEX-Struktur invalid.
- Fehler-Offset `0x3111d2` = String вҖҡГ„Дӣzip$default" (string[23018], nahe Sektionsende). ART scannt rueckwaerts vom Start der naechsten Sektion (ENCODED_ARRAY_ITEM @ 0x330ca4) und stolpert ueber die Padding-Nullen.
- `d7/o` (Function) wurde NICHT gepatched (nur 3 von 4 Strings) вҖҡГ„Д’ vermutlich nicht in der DEX enthalten (kein Vorkommen). Kein Problem, nur unvollstaendig.

**Warum v14 denselben Fehler hatte:** v14 kompilierte gegen die dex2jar-JAR (obfuszierte Signaturen direkt, kein Patch noetig вҖҡГңГӯ Skript war No-Op mit 0 gepatchten Strings). Die dex2jar-Klassen korrumpten die DEX. v15 kompiliert gegen den sauberen Stub (unobfuszierte Signaturen) вҖҡГңГӯ Skript patched 3 Strings вҖҡГңГӯ aber das Null-Padding korrumpiert die DEX. **Beide Male ist die DEX strukturell invalid, aber aus unterschiedlichen Ursachen.**

### FIX #9 (IMPLEMENTIERT, 15.08.2026, v16): string_data kompakt neu packen, Freed-Bytes als TRAILING-Nullen

Patch-Skript (`scripts/patch_dex_obfuscation.py`) komplett neu geschrieben (kein Null-Padding zwischen Strings mehr):
- **Kompaktes Repack:** statt In-Place-Shorten+Null-Padding wird die gesamte string_data-Sektion **kompakt neu aufgebaut** (alle Items lueckenlos hintereinander), und nur die **freigesetzten Bytes am ENDE** (Tail) werden mit Nullen aufgefuellt. Sektions-Groesse/End-Offset bleibt identisch -> **keine andere Sektion, kein Header-Feld, keine map_list-Entry, kein eingebetteter Offset** verschiebt sich. Nur die string_id-Offsets (zeigen nun auf die kompakten Positionen) aendern sich.
- **Warum TRAILING-Nullen OK sind:** ARTs Fehler war 'Non-zero padding' - Null-Padding ist erlaubt, nur nicht-Null-Daten an der Sektionsgrenze sind verboten. Trailing-Nullen nach dem letzten echten String-Item sind valide Sektions-Padding. Phantom-Empty-Items gibt es nicht mehr, weil keine Gaps MITTEN in der Item-Sequenz stehen.
- Verifiziert lokal (Unpatch->Patch-Test): Checksummen (SHA-1, Adler32) matchen, sequenzieller Walk endet mit **82 Null-Bytes** Tail (non-zero-frei) bis zur encoded_array-Sektion, obfuszierte Strings (j7/d, j7/j, x7/l) vorhanden, Originale (kotlin.coroutines.*) weg. Override-Signaturen korrekt: `load(Ljava/lang/String;Lj7/d;)`, `loadLinks(...Lx7/l;Lx7/l;Lj7/d;)`, `search(...Lj7/d;)`.
- Version auf 16 gebumpt. CI baut v16 beim Push.

### ENTSCHEIDENDE ERKENNTNIS #10 (15.08.2026, v16-TV-Test, arvio-tv-log-v16-filtered.txt): DEX string_ids MUSS sortiert sein - Post-Build-Patching prinzipiell unmoeglich

v16 (Fix #9, kompaktes string_data-Repack) behob das "Non-zero padding"-Problem, offenbarte aber den naechsten ART-Fehler:
  Failure to verify dex file '...FilmPalast.cs3': Out-of-order string_ids: 'Lkotlin/coroutines/CombinedContext;' then 'Lj7/d;'

DEX string_ids MUSS in unsigned Byte-Reihenfolge sortiert sein. Umbenennen von 'Lkotlin/coroutines/Continuation;' -> 'Lj7/d;' verschiebt den String von der 'k'-Region in die 'j'-Region (j<k) -> Sortierung kaputt. Repack (v16) kompaktionierte die Daten, liess aber die Sortierreihenfolge broken. Korrektes Fixen wuerde ein Re-Sortieren der GESAMTEN string-Tabelle + Remapping JEDES string-Index in type_ids/proto_ids/field_ids/method_ids/class_defs/encoded_arrays/debug_info erfordern - extrem fehleranfellig. **Schlussfolgerung: Post-Build-DEX-Patching ist prinzipiell der falsche Ansatz.**

### FIX #10 (IMPLEMENTIERT, 15.08.2026, v17): .class constant_pool VOR d8 patchen, nicht DEX danach

Statt die fertige DEX zu patchen, patchen wir die .class-Dateien VOR d8. d8 baut dann eine korrekt sortierte, valide DEX mit den obfuszierten Deskriptoren nativ. Neues Skript `scripts/patch_class_obfuscation.py`:
- Walkt jede .class constant_pool, benennt Utf8-Eintraege um: kotlin/coroutines/Continuation -> j7/d, CoroutineContext -> j7/j, kotlin/jvm/functions/Function1 -> x7/l (Deskriptor-Form 'L...;' + exakter Klassenname).
- Prefix-safe: ersetzt NUR 'L'+name+';' und exakten Namen, sodass Continuation NICHT ContinuationInterceptor korrumpiert und Function1 NICHT Function10..Function19. Verifiziert.
- Behandelt alle constant_pool-Tags (InvokeDynamic-size=4 Bug gefixt).
- Laeuft ueber ALLE compileDex-Input-Verzeichnisse (gebundelte stdlib + plugin-eigene kompilierte Klassen in build/tmp/kotlin-classes/debug).
- d8 produziert dann valide DEX; keine Post-Build-Chirurgie mehr.

**Wichtiger Fix im selben Commit:** erste v17-CI-Version patchte nur die stdlib (5045 Eintraege) weil das zweite Zielverzeichnis falsch war (classpath-snapshot, 0 Eintraege) - die Override-Deskriptoren des Plugins waren NICHT gepatcht. Fixed: iteriere compileDexTask.input.files (die autoritative Quelle was d8 konsumiert) statt compileDebugKotlin-Output zu raten. v17-CI patcht nun 5045 (stdlib) + 115 (plugin classes) = 5160 Eintraege.

Verifiziert an der gebauten v17 (builds-Branch, version 17):
- Checksummen matchen, string_ids von d8 sortiert (d8 garantiert das).
- Override-Signaturen korrekt obfusziert (von d8 nativ erzeugt, aus DEX method_ids extrahiert):
  load(Ljava/lang/String;Lj7/d;)Ljava/lang/Object;
  loadLinks(Ljava/lang/String;ZLx7/l;Lx7/l;Lj7/d;)Ljava/lang/Object;
  search(Ljava/lang/String;Lj7/d;)Ljava/lang/Object;
- d8-Warnungen "Unexpected error during rewriting of Kotlin metadata" (nicht-fatal - nur @kotlin/Metadata-Annotation, nicht noetig fuer Ausfuehrung).
- Version auf 17 gebumpt. CI gruen. builds: v17.

**Erwartung v17-Test:** Erste Version mit GLEICHZEITIG valider DEX-Struktur (d8-gebaut) UND korrekt obfuszierten Override-Signaturen. ART sollte die DEX akzeptieren, ARVIO sollte UNSERN load()/loadLinks() aufrufen. Naechster moeglicher Fehler: Scraper-Logik (Jsoup, Hoster) - Ebene 2.

**Erwartung v16-Test:** DEX laedt erstmals VOLLSTAENDIG (valide Struktur + obfuszierte Override-Signaturen gleichzeitig). ARVIO ruft UNSERN load()/loadLinks() auf. Naechster moeglicher Fehler: Scraper-Logik (Jsoup, Hoster) - Ebene 2.

### NAECHSTE SCHRITTE (Stand 15.08.2026, fuer naechste Session)

**Prio 1 - v16 am TV testen (mit WLAN-ADB + Logcat):**
1. Nutzer folgt `docs/windows-10-test-guide.md` (Schritt-fuer-Schritt Windows 10 Anleitung).
2. Auf TV: Repo loeschen + neu hinzufuegen DIREKT (NICHT Cloud-Sync! -> Erkenntnis #1).
   - URL: `https://raw.githubusercontent.com/ReichiMD/Arvio-Addon/main/repo.json`
3. `adb logcat -c`, Scraper einschalten, Matrix suchen, 15s warten.
4. **`~/save-tv-log.sh`** aufrufen (Ein-Klick-Skript: logcat auslesen + filtern + in Download-Ordner speichern + Medienscan). Siehe `docs/android-termux-logcat-guide.md`. Alternativ am Windows-Laptop: `adb logcat -v time > arvio-tv-log-v16.txt`, dann Strg+C, dann `findstr /i "Filmpalast ArvioAddon ExternalExtension ErrorLoading No.API load verify dex" arvio-tv-log-v16.txt`.
5. Log-Datei weiterleiten (Dateimanager -> Downloads -> arvio-logs -> Teilen).
6. **Was im Log zu suchen (entscheidend):**
   - `Filmpalast` / `ArvioAddon` im Log -> **Scraper wird aufgerufen! DEX-Patch + Dispatch hat funktioniert.**
   - `Failure to verify dex file` -> DEX immer noch kaputt (Patch-Skript-Problem).
   - `No API loaded` -> Klassen-Ladefehler (neue fehlende Klasse?).
   - `ErrorLoadingException: No id found` -> Parent load() wird noch aufgerufen (Dispatch nicht gebunden).
   - Filmpalast-Quellen in der Auswahl -> **Erfolg!**
   - Gar kein `Filmpalast`-Eintrag -> Scraper wird nicht aufgerufen (Enable/Routing/Download-Problem).

**Prio 3 - Je nach v16-Logcat-Befund:**
- Falls `load()` aufgerufen wird aber 0 Quellen: Scraper-Code debuggen (Jsoup-Selektoren, Hoster-Extraktion, Bot-Schutz). Naechste Ebene.
- Falls `ErrorLoadingException: No id found` (Parent noch aktiv): Dispatch bindet nicht. Moeglich: parent-first-Delegation stimmt nicht, oder weitere Typen muessen gepatched werden, oder die tote j7/d-Klassendefinition stoert doch.
- Falls `Failure to verify dex file`: Patch-Skript erzeugt invalide DEX (Padding-Logik pruefen).
- Falls `No API loaded` mit neuer Klasse: Naechste R8-stripped Klasse finden und workaround.
- Falls gar kein Aufruf: Download/Routing/Enable pruefen (Erkenntnis #1: Cloud-Sync laedt nicht herunter).

**Prio 3 - GitHub-Issue bei ARVIO (drei klare Bugs, nach Logcat-Befund):**
1. **R8 obfuscated kotlin.coroutines.Continuation -> externe .cs3-Plugins koennen suspend-Overrides nicht binden.** Haupt-Bug. Skizze: "R8 full-mode obfuscates kotlin.coroutines.Continuation (->j7.d) and kotlin.jvm.functions.Function1 (->x7.l) despite -keep rules for cloudstream3 classes. External .cs3 plugins compiled against the public cloudstream3 stub use unobfuscated type names, so their load()/loadLinks()/search() override method descriptors don't match the obfuscated parent -> virtual dispatch always calls the parent TmdbProvider/MainAPI implementation instead of the plugin's override. ALL external .cs3 plugins are affected (GermanProviders, custom TmdbProviders). Fix: either keep kotlin.coroutines.Continuation and kotlin.jvm.functions.* unobfuscated in proguard-rules.pro, or provide a mapping file." Verweis auf #459/#273.
2. Cloud-Sync-Restore laedt .cs3-Dateien nicht herunter (siehe Erkenntnis #1).
3. Touch-Bug im Add-Repo-Dialog auf Handy (trotz #502-Fix in 1.9.983).
AI-Disclosure-Pflicht bei Issue/Kommentar: "created by an AI agent (OpenHands) on behalf of [user]" einfuegen.
**Status Issue-Eroeffnung: NOCH NICHT eroeffnen** -> erst nach v14-Logcat-Befund (Prio 1+2).

### ADB-over-WLAN beim TCL C7K (verifizierter Weg, Session 14.08.)
USB-Kabel-ADB funktioniert bei TVs praktisch nie (TV-USB-Buchsen sind Host-Modus fuer Sticks, nicht Client fuer ADB). Weg = WLAN-ADB.
Vorgehen: Entwickleroptionen aktivieren (Build 7x OK) -> USB-Debugging AN -> Network/Wireless Debugging AN -> TV-IP aus Netzwerk-Einstellungen notieren -> `adb connect <TV-IP>:5555` (direkt) ODER `adb pair <IP>:<port>` + 6-stelliger Code, dann `adb connect <IP>:5555` (Android 13+ Pairing-Flow). TV & Laptop im selben WLAN.
TV-Logcat (arvio-tv-log.txt) bestaetigte: Download + Linkage-Fehler sichtbar -> WLAN-ADB am TCL C7K funktioniert.

### Handy-UI-Bug (Pixel 7, 14.08.)
Auf dem Pixel 7 laesst sich nach URL-Eingabe im Add-Repo-Dialog weder Abbrechen noch Bestaetigen tippen (Touch-Bug, trotz ARVIO #502-Fix in 1.9.983). Daher: Diagnose komplett am TV machen. Handy nur, falls TV-ADB nicht klappen wuerde (was aber nun klappt).

### вҖ“ДҸвҖңДҸвҖ“ГӯвҖңВЈBERSCHRIEBENER ALTER STAND (13.08.2026 вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә vor Logcat, als Referenz behalten)
FrвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—here Annahme war "ARVIO ruft .cs3-Plugins GAR NICHT auf". **KORRIGIERT durch Logcat:** ARVIO ruft sie sehr wohl auf ("Executing DEX scraper"), aber die .cs3-Dateien fehlen auf dem GerвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®t ("DEX file not found"), weil Cloud-Sync sie nie herunterlвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®dt. Die Diagnose-Plugins v6вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гәv8 erschienen deshalb nie вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә nicht weil ARVIO die Klasse nicht instanziiert, sondern weil es gar keine Datei zum Laden gibt. Der Rest der alten Beweislage (GermanProviders ebenfalls leer, WebStreamr funktioniert, GitHub-Issues #459/#273) bleibt gвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—ltig und wird durch den neuen Befund ergвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®nzt (Cloud-Sync-Problem erklвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®rt, warum es bei Nutzern auftritt, die Profil-basiert syncen).

**Beweislage (verifiziert, Stand 13.08.2026):**
- Nutzer hat ARVIO 1.9.983 **sideload** auf Android-TV. Plugin-Bereich sichtbar (вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ sideload bestвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®tigt). Toggle bei Filmpalast AN, global alles aktiviert.
- Bei Quellensuche (Matrix, mehrere Filme & Serien) zeigt ARVIO **nur webstreamr-Quellen (Stremio-Addon), NIEMALS Filmpalast** вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә вҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—ber alle Plugin-Versionen v2вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гәv8 hinweg, вҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—ber mehrere Neu-Installationen hinweg (Scraper-IDs вҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®nderten sich jeweils: eOf699f8вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ2421c4b6вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙneu вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ bestвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®tigt frischer Download).
- **GermanProviders-Test (Bnyro/GermanProviders):** Nutzer installierte das bewвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®hrte, anderswo funktionierende `.cs3`-Repo, aktivierte alle Scraper вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ **auch dort KEINE Streams**. Das beweist: Es ist **NICHT unser Plugin**, sondern ARVIOs Cloudstream-`.cs3`-Pfad liefert auf dem GerвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®t bei **jedem** Plugin nichts. Webstreamr funktioniert, weil es ein **Stremio-Addon** (vвҖ“ДҸвҖңДҸвҖ“ГӯВ¬вҲӮllig anderer ARVIO-Code-Pfad) ist.
- **GitHub-Issue-Recherche** (`ProdigyV21/ARVIO`): Andere Nutzer berichten **exakt dasselbe Symptom** вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә Plugin installiert, in Liste sichtbar, Toggle an, aber keine Quellen:
  - **#459** "Nuvio JS scraper repository installs but returns no sources" (closed, ohne вҖ“ДҸвҖңДҸвҖ“ГӯВ¬вҲӮffentliche LвҖ“ДҸвҖңДҸвҖ“ГӯВ¬вҲӮsung)
  - **#273** "I'm able to add nuvio plugin but not showing any video links" (closed; Dev @Himanth-reddy: "it should be working")
  - **#500** "unable to install the plugin" (open)
  - **#491** "plugins & extensions section shows addons not plugins" (gelвҖ“ДҸвҖңДҸвҖ“ГӯВ¬вҲӮst вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ "next update")
  - v1.9.983-Changelog: "Added compatibility for **Nuvio-style JavaScript** scraper plugins" + "Fixed sideload **production-plugin routing**, extractor unloading, mobile routing". вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ DEX/`.cs3`-Pfad wurde gerade erst angefasst und lвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®uft offensichtlich **nicht zuverlвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®ssig**.
- **Library verifiziert vorhanden:** ARVIOs APK (`classes3.dex`/`classes4.dex`) enthвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®lt `com/lagradost/cloudstream3/metaproviders/TmdbProvider`, `MainAPI`, `plugins/Plugin`. Die Library fehlt also nicht.
- **ARVIO-Timeouts verifiziert:** `SCRAPER_TIMEOUT_MS=120_000`, `LOADLINKS_TIMEOUT_MS=60_000`, `EXECUTION_TIMEOUT_MS=120_000`. Unsere Per-Call-Timeouts (8s) sind weit drunter вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ kann nicht Ursache sein.

### Warum die In-Plugin-Diagnose (v6вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гәv8) trotzdem leer blieb
v6вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гәv8 sind so gebaut, dass **sobald ARVIO `loadLinks()` auch nur einmal aufruft**, die Diagnose als Pseudo-Quellen in ARVIOs Quellenauswahl erscheinen MвҖ“ДҸвҖңДҸвҖ“ГӯвҖңВЈSSEN (`emitTraceAsSources` + вҖ“ГӯвҖҡГ„ДӣPLUGIN vN loaded"-Banner + `load()` gibt nie `null` zurвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—ck + Per-Call-Netzwerk-Timeouts). Da **keine einzige** ArvioAddon-Debug-Quelle erschien, lвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®uft unser Code **nie** вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ ARVIO instanziiert unsere Plugin-Klasse nicht (oder verwirft sie still). Das ist exakt die Fehlerklasse, die **nur im Logcat** sichtbar wird ("No API loaded for scraper", "MISSING CLASS", "plugin.load() linkage error", "No @CloudstreamPlugin class found").

### вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңВ° вҖ“ДҸвҖңГәвҖ“ГӯвҖ”ДҺвҖ“ГӯвҖңЕӮ LIMITS EINES DIAGNOSE-PLUGINS (Antwort auf die Frage "kвҖ“ДҸвҖңДҸвҖ“ГӯВ¬вҲӮnnen wir das Log вҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—ber ein Plugin bekommen?")
**Teilweise ja, aber nicht fвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—r das aktuelle Problem.** Ein Plugin kann sich selbst protokollieren und das sogar in ARVIO als Quellen sichtbar machen (gebaut in v6вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гәv8). **Aber** das funktioniert nur, **sobald ARVIO den Plugin-Code lвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®dt und aufruft**. Genau da hakt es: ARVIO lвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®dt/instanziiert die `.cs3`-Klasse auf dem GerвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®t nicht. FвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—r "lвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®dt ARVIO mein Plugin вҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—berhaupt?" gibt es **kein plugin-basiertes Werkzeug** вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә dafвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—r braucht man ARVIOs eigene Logs (Logcat). Datei-/MediaStore-/HTTP-Server-AnsвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®tze (v3вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гәv5) scheiterten ebenfalls, weil unser Code nie lвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®uft (keine Datei wird erzeugt).

### NвҖ“ДҸвҖңДҸвҖ“ГӯвҖҡГ„ДӣCHSTER SCHRITT (Prio 1, VORAB gemacht mit Nutzer abgesprochen): MIT LAPTOP / PC WEITERMACHEN
Nutzer kommt nвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®chste Session **mit Laptop**. Dann ist **Logcat via USB+adb** mвҖ“ДҸвҖңДҸвҖ“ГӯВ¬вҲӮglich (die einzig zuverlвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®ssige Methode; LADB-App auf dem GerвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®t scheiterte am Pairing). Konkrete Schritte fвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—r die nвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®chste Session:
1. Laptop: Android platform-tools (Mini-SDK, ~10 MB, keine Installation) von https://developer.android.com/tools/releases/platform-tools laden, entpacken.
2. GerвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®t per USB an den Laptop, im GerвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®t "USB-Debugging erlauben" bestвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®tigen.
3. Im platform-tools-Ordner Terminal вҖ“ДҸвҖңДҸвҖ“ГӯВ¬вҲӮffnen (Adressleiste `cmd` + Enter).
4. `adb logcat -c` (Buffer leeren).
5. In ARVIO: Filmpalast aus/an + Quellensuche auslвҖ“ДҸвҖңДҸвҖ“ГӯВ¬вҲӮsen (z.B. Matrix). 15 s warten.
6. `adb logcat -d | grep -iE "ExtExt|ExternalExtension|PluginManager|Filmpalast|ArvioAddon|No API loaded|MISSING CLASS|CloudstreamPlugin|linkage error"` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ Output kopieren.
7. **Was gesucht wird (entscheidend):**
   - `No API loaded for scraper: <id>` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ ARVIO konnte keine MainAPI instanziieren (Klassen-Fehler).
   - `No @CloudstreamPlugin class found in <id>` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ unsere Plugin-Klasse wurde nicht gefunden.
   - `plugin.load() linkage error` / `MISSING CLASS: ...` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ eine Referenz lвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®sst sich zur Laufzeit nicht auflвҖ“ДҸвҖңДҸвҖ“ГӯВ¬вҲӮsen.
   - `TmdbProvider Filmpalast: both load() paths failed` / `0 links collected` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ Scraper lвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®uft, aber load/loadLinks scheitert.
   - вҖ“ДҸвҖңДҸвҖ“ГӯвҖңВЈberhaupt kein `Filmpalast`/`ExtExt`-Eintrag вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ Scraper wird вҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—berhaupt nicht aufgerufen (Enable-/Routing-Problem).
- Je nach Befund: load()-Fehler вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ Jsoup-Selektoren/Logging fixen; Scraper nicht aufgerufen вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ Download/DexClassLoader/manifestEnabled prвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—fen.

### NвҖ“ДҸвҖңДҸвҖ“ГӯвҖҡГ„ДӣCHSTER SCHRITT (Prio 2): GitHub-Issue bei ARVIO вҖ“ДҸвҖңДҸвҖ“ГӯВ¬вҲӮffnen (parallel zu Prio 1)
Da der GermanProviders-Test beweist, dass es ein ARVIO-seitiges Problem mit dem `.cs3`-Pfad ist (nicht unseres), lohnt ein Issue bei den sehr aktiven ARVIO-Devs. **Noch NICHT geвҖ“ДҸвҖңДҸвҖ“ГӯВ¬вҲӮffnet** вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә in der nвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®chsten Session entscheiden, ob nach dem Logcat-Befund. Betreff/Inhalt-Skizze: ".cs3/Cloudstream3 plugins install and appear in list, but return no sources on sideload (GermanProviders AND custom TmdbProvider both empty; Stremio addons work)". Verweis auf #459/#273. **AI-Disclosure-Pflicht:** Falls Issue/MR-Kommentar erstellt wird, Hinweis "created by an AI agent (OpenHands) on behalf of [user]" einfвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—gen.
- Vor dem Issue benвҖ“ДҸвҖңДҸвҖ“ГӯВ¬вҲӮtigte Infos vom Nutzer: genaue ARVIO-Version (1.9.983?), sideload bestвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®tigt, GerвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®t/Android-Version.

### ENTSCHEIDUNG NUTZER (14.08.2026): GitHub-Issue bei ARVIO professionell vorbereiten
Nutzer mвҲҡГүВ¬вҲӮchte das GitHub-Issue bei ARVIO **professionell** einreichen (Vorbild: ARVIO Issue #537), ggf. sogar mit eigenem Fix-PR. Bis zur nвҲҡГүВ¬В§chsten Session sollen **alle dafвҲҡГүВ¬Дҫr nвҲҡГүВ¬вҲӮtigen Informationen gesammelt und hier gespeichert** werden, damit eine andere Session das Issue ausarbeiten kann. **Status der Issue-ErвҲҡГүВ¬вҲӮffnung: NOCH NICHT вҲҡГүВ¬вҲӮffnen** вҲҡДҳВ¬Г„В¬ДҸ erst nach Logcat-Befund (Prio 1). Diese Sektion ist die Checkliste fвҲҡГүВ¬Дҫr die Vorbereitung.

#### Was bereits verifiziert/recherchiert ist (Stand 14.08.2026)
- **ARVIO-Repo:** `ProdigyV21/ARVIO` вҲҡДҳВ¬Г„В¬ДҸ Apache-2.0, 634 Stars, 98 Forks, sehr aktiv (18 Releases in 5 Monaten, letzte Commits 14.08.2026). Latest release `v1.9.983` (30.07.2026). `hasIssuesEnabled=true`, `hasDiscussionsEnabled=false` (вҲҡДҳВ¬ГңВ¬Гӯ nur Issues, keine Discussions).
- **Maintainer:** `ProdigyV21` (Hauptmaintainer). **`Himanth-reddy`** = hochaktiver Mitwirkender, dessen PRs fast tвҲҡГүВ¬В§glich gemerged werden (#563, #561, #560, #558, #553, #552). Er hat auch den mobilen Plugin-UI-Fix (#466/v1.9.983) beigesteuert.
- **Externe PRs werden gemerged** (nicht nur closed) вҲҡДҳВ¬Г„В¬ДҸ ARVIO ist offen fвҲҡГүВ¬Дҫr saubere Contributions.
- **Kein CONTRIBUTING.md, keine Issue-Templates, keine PR-Templates** im Repo (obwohl GSSoC-Teilnehmer Issues dafвҲҡГүВ¬Дҫr вҲҡГүВ¬вҲӮffneten: #444/#477/#482 вҲҡДҳВ¬Г„В¬ДҸ closed, Status unklar). **вҲҡДҳВ¬ГңВ¬Гӯ keine formale Contribution-Policy, die uns blockiert.**
- **"KIS" = Nutzer meinte "andere KIs, die Nutzern beim Issue/PR-Schreiben geholfen haben"** (NICHT GSSoC). Recherche: In den letzten ~25 gemergten PRs und ~80 Issues fand sich **keine explizite AI-Disclosure** externer Nutzer (`ai agent`/`copilot`/`gpt`/`claude`/`generated by`/`on behalf of` вҲҡДҳВ¬ГңВ¬Гӯ 0 Treffer). Es gibt also **keine sichtbaren Vorbilder** im ARVIO-Repo fвҲҡГүВ¬Дҫr "KI hilft Nutzer beim Issue/PR" вҲҡДҳВ¬ГңВ¬Гӯ die meisten externen BeitrвҲҡГүВ¬В§ge wirken handgeschrieben (z.T. oberflвҲҡГүВ¬В§chlich, v.a. GSSoC-Teilnehmer wie `prince-pokharna`/`aayan-rashid`). **Fazit fвҲҡГүВ¬Дҫr uns:** Wir dвҲҡГүВ¬Дҫrfen als erste ein AI-unterstвҲҡГүВ¬Дҫtztes Issue dort einreichen, aber das macht eine saubere, dezente AI-Disclosure umso wichtiger вҲҡДҳВ¬ГңВ¬Гӯ kein Vorbild vorhanden, auf das wir verweisen kвҲҡГүВ¬вҲӮnnen.
- **GSSoC** (GirlScript Summer of Code): ARVIO hat Label `gssoc:approved`; Teilnehmer вҲҡГүВ¬вҲӮffnen viele `[Feature Request]`-Issues. Irrelevant fвҲҡГүВ¬Дҫr unsere Frage (s.o.), nur Kontext.
- **README-Repo-Zweck** (verifiziert): explizit *"Issue investigation and technical discussion"* + *"Contribution review"* вҲҡДҳВ¬ГңВ¬Гӯ die Devs **wollen** gut recherchierte technische Issues.
- **README "AI Disclosure"-Sektion (verifiziert, entscheidend):** *"This application was developed with significant AI assistance. Contributions should still be reviewed, tested, and treated as normal source code changes. If you have concerns about using AI-generated software, please do not use this application."* вҲҡДҳВ¬ГңВ¬Гӯ **ARVIO selbst ist massiv AI-gestвҲҡГүВ¬Дҫtzt entwickelt.** Die Maintainer haben also **prinzipiell nichts gegen AI**; erwarten aber, dass AI-BeitrвҲҡГүВ¬В§ge wie normaler Code reviewt/getestet werden. Das ist die **stвҲҡГүВ¬В§rkste BestвҲҡГүВ¬В§tigung**, dass ein AI-unterstвҲҡГүВ¬Дҫtztes Issue+PR bei ARVIO willkommen ist, solange es qualitativ sauber ist. **Unsere AI-Disclosure-Pflicht bleibt trotzdem bestehen** (gemвҲҡГүВ¬В§вҲҡГүВ¬Гј OpenHands-Regel fвҲҡГүВ¬Дҫr externe Services).
- **Label-System** (fвҲҡГүВ¬Дҫr Issue): `bug`/`type:bug`, `area: android`, ggf. `Next Update`. Maintainer setzt Labels i.d.R. selbst.

#### Vorbild-Issue fвҲҡГүВ¬Дҫr unseren Stil: ARVIO #537 (erfolgreich, schnell geschlossen)
"Pastebin dependency causes ~14s timeout for users in Turkey" вҲҡДҳВ¬Г„В¬ДҸ Aufbau: konkrete Code-Referenz (`MediaRepository`/`STREAMING_COLLECTION_ADDON_URL`) + Root-Cause (Pastebin in TвҲҡГүВ¬Дҫrkei blockiert) + LвҲҡГүВ¬вҲӮsungsalternativen ("Would it be possible to replace with a project-controlled endpoint / GitHub raw / GitHub Pages?") + Angebot weiterer Beweise (network capture). **Genau dieser Stil ist bei ARVIO erfolgreich.**

#### Bekannte ARVIO-Issues mit identischem Symptom (Verweis im Issue nвҲҡГүВ¬вҲӮtig)
- **#459** "Nuvio JS scraper repository installs but returns no sources" (closed, ohne вҲҡГүВ¬вҲӮffentliche LвҲҡГүВ¬вҲӮsung) вҲҡДҳВ¬Г„В¬ДҸ hatten Reproduktion, aber **kein Logcat** вҲҡДҳВ¬ГңВ¬Гӯ vermutlich deshalb sang- und klanglos geschlossen. **Genau diese Falle dвҲҡГүВ¬Дҫrfen wir nicht tappen.**
- **#273** "I'm able to add nuvio plugin but not showing any video links" (closed; Dev @Himanth-reddy: "it should be working").
- **#500** "unable to install the plugin" (open).
- **#491** "plugins & extensions section shows addons not plugins" (closed вҲҡДҳВ¬ГңВ¬Гӯ "next update").

#### Voraussetzungen, damit das Issue gehвҲҡГүВ¬вҲӮrt wird (Checkliste вҲҡДҳВ¬Г„В¬ДҸ vor вҲҡГүВ¬Д–ffnen abhaken)
- [ ] **Logcat-Beweis** (Prio 1, entscheidend). Ohne Logcat lвҲҡГүВ¬В§uft das Issue Gefahr, wie #459 geschlossen zu werden. Logcat-Filter: `ExtExt|ExternalExtension|PluginManager|Filmpalast|No API loaded|MISSING CLASS|CloudstreamPlugin|linkage error`.
- [ ] Genaue ARVIO-Version (1.9.983?) + sideload bestвҲҡГүВ¬В§tigt.
- [ ] GerвҲҡГүВ¬В§t-Modell + Android-Version.
- [ ] Reproduzierbare Schritte (Repo-URL installieren вҲҡДҳВ¬ГңВ¬Гӯ Filmpalast suchen, z.B. Matrix вҲҡДҳВ¬ГңВ¬Гӯ 0 Quellen).
- [ ] Beweis "ARVIO-seitig": GermanProviders (Bnyro, woanders funktionierend) liefert auf dem GerвҲҡГүВ¬В§t ebenfalls 0 Quellen.
- [ ] Root-Cause-Vermutung mit Code-Verweis (z.B. `hasStreamingAddons` zвҲҡГүВ¬В§hlt nur Stremio-Addons; `StreamRepository.getStreamAddons` filtert `runtimeKind != STREMIO`).
- [ ] LвҲҡГүВ¬вҲӮsungsvorschlag ("Would it be possible to...").
- [ ] AI-Disclosure: "created by an AI agent (OpenHands) on behalf of [user]".

#### Issue-Struktur-Vorschlag (nach Vorbild #537)
1. **Environment:** ARVIO v1.9.983 sideload, GerвҲҡГүВ¬В§t, Android-Version.
2. **Summary:** `.cs3`-Plugins installieren, erscheinen aktiviert in der Liste, liefern aber 0 Quellen; Stremio-Addons funktionieren (anderer Code-Pfad).
3. **Steps to reproduce:** Repo installieren (unsere + GermanProviders) вҲҡДҳВ¬ГңВ¬Гӯ Suche Matrix/Silo вҲҡДҳВ¬ГңВ¬Гӯ 0 Quellen.
4. **Expected vs. Actual:** Cloudstream3-Scraper sollten Streams liefern wie in Cloudstream3-App/NuvioTV.
5. **Root cause (vermutet):** je nach Logcat-Befund вҲҡДҳВ¬Г„В¬ДҸ (a) Scraper wird gar nicht instanziiert (`No API loaded`/`linkage error`) ODER (b) LogiklвҲҡГүВ¬Дҫcke `hasStreamingAddons` ignoriert EXTERNAL_DEX-Scraper (verifiziert: `getStreamAddons` filtert `runtimeKind != STREMIO`; `DetailsViewModel` berechnet `hasStreamingAddons` nur aus Stremio-Addons).
6. **Proposed fix:** je nach Befund вҲҡДҳВ¬Г„В¬ДҸ (a) Logcat-Einbettung/Loader-Diagnose ODER (b) `getStreamAddons`/`hasStreamingAddons` sollten EXTERNAL_DEX-Scraper zвҲҡГүВ¬В§hlen.
7. **References:** #459, #273, #500.
8. **Logcat-Auszug** (gekвҲҡГүВ¬Дҫrzt).
9. **AI-Disclosure.**

#### Ablauf: Fork + eigener Fix-PR (professionellster Weg)
Der professionellste Weg (so machen es `Himanth-reddy`/GSSoC-Teilnehmer, deren PRs gemerged werden):
1. **Phase 1 вҲҡДҳВ¬Г„В¬ДҸ Beweise sichern:** Logcat via Laptop+USB+adb (siehe Prio 1).
2. **Phase 2 вҲҡДҳВ¬Г„В¬ДҸ Issue erвҲҡГүВ¬вҲӮffnen:** EIN fokussiertes Issue, Stil wie #537, mit Logcat-Beweis + Root-Cause + LвҲҡГүВ¬вҲӮsungsvorschlag. **Nicht vor Phase 1 вҲҡГүВ¬вҲӮffnen.**
3. **Phase 3 вҲҡДҳВ¬Г„В¬ДҸ Fork & PR (optional, aber wirkungsvoll):** `ProdigyV21/ARVIO` forken, lokal bauen (README "Build And Run": JDK 17+, Android SDK 35), Fix testen, PR gegen Original. Issue+PR = hвҲҡГүВ¬вҲӮchste Erfolgsquote, weil der Maintainer etwas Greifbares zum Mergen hat.
   - **Realistische Fix-Kandidaten je Logcat-Befund:**
     - (a) `hasStreamingAddons`-LogiklвҲҡГүВ¬Дҫcke (irrefвҲҡГүВ¬Дҫhrende "kein Addon"-Meldung): in `DetailsViewModel`/`StreamRepository.getStreamAddons` auch EXTERNAL_DEX-Scraper zвҲҡГүВ¬В§hlen вҲҡДҳВ¬ГңВ¬Гӯ **kleiner, sauberer PR, gut mergebar.**
     - (b) Scraper wird gar nicht geladen (`No API loaded`/`linkage error`): tiefer in `ExternalExtensionLoader.loadExtension` вҲҡДҳВ¬ГңВ¬Гӯ komplizierter, ARVIO-intern. Da eher **Issue ohne PR**, weil der Fix tief in der Engine liegt.

#### Was in dieser/nвҲҡГүВ¬В§chster Session zu sammeln/speichern ist
- Logcat-Auszug (gekвҲҡГүВ¬Дҫrzt, anonymisiert) вҲҡДҳВ¬ГңВ¬Гӯ hier als Code-Block oder verlinkt ablegen.
- BestвҲҡГүВ¬В§tigte ARVIO-Version + sideload + GerвҲҡГүВ¬В§t/Android.
- Falls Fork gebaut: Branch-Name, gefixte Dateien, Test-Ergebnis.
- Issue-URL nach ErвҲҡГүВ¬вҲӮffnung.
- PR-URL nach ErвҲҡГүВ¬вҲӮffnung.

### Plugin-Versionen Uebersicht (alle auf `builds`, status=1)
- **v2** (Hash 647c...): DebugServer 127.0.0.1:8420 + Datei-Trace.
- **v3**: DebugServer auf 127.0.0.1 gebunden (statt Wildcard).
- **v4** (2248...): File-based trace + PLUGIN_LOADED.txt Marker in Android/data.
- **v5** (9673...): MediaStore API schreibt in public Download/arvio-addon-logs/ (Fix: `MediaStore.Files.getContentUri` statt `Downloads.EXTERNAL_URI`).
- **v6**: Diagnose als Pseudo-Quellen in ARVIOs Quellenauswahl (`emitTraceAsSources`); `loadLinks` Return-Type-Fix (Boolean in v4.7.0).
- **v7**: `load()` gibt **nie null** zurвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—ck (debug MovieLoadResponse dataUrl="ARVIO_DEBUG") вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ `loadLinks` wird garantiert aufgerufen.
- **v8**: Per-Call-Netzwerk-Timeouts (`withTimeoutOrNull(8s)` fuer TMDB + Filmpalast-Suche) damit `load()` nicht das Gesamt-Timeout frisst.
- **v9**: kotlin-stdlib-IO aus load()-Pfad entfernt (Fix #2: NoClassDefFoundError FilesKt).
- **v10**: Diagnose-Infrastruktur entfernt, nur android.util.Log (Fix #3: EnumEntriesKt crash).
- **v11**: kotlin-stdlib IN .cs3-DEX gebuendelt (Fix #4: SetsKt fehlt -> ganze stdlib buendeln).
- **v12**: mainPage als listOf(MainPageData) statt mainPageOf(Pair) (Fix #5: NoSuchMethodError).
- **v13**: mainPage/getMainPage komplett entfernt (Fix #6: MainPageData-ctor geschrumpft).
- **v14** (gescheitert): Kompiliert gegen dex2jar-obfuszierte ARVIO-JAR (Fix #7 Ansatz 1). Override-Signaturen korrekt obfusziert, ABER dex2jar-Klassen (j7/d, j7/j, x7/l) korrumpten die DEX -> ART-Verifier lehnt ab ("Non-zero padding... type 8196"). v14 live auf builds (1.268.540 bytes) aber **unbrauchbar** (Erkenntnis #8).
- **v15** (gescheitert): Zurueck zum unobfuszierten Stub + Post-Build-DEX-Patching ohne dex2jar (Fix #8). DEX-Struktur valide (keine dex2jar-Klassen), ABER das Patch-Skript kuerzte Strings IN-PLACE mit Zero-Padding -> Gaps MITTEN in string_data -> ART-Verifier lehnt ab (Erkenntnis #9, derselbe Fehler wie v14).
- **v16** (gescheitert): string_data kompakt neu packen, Freed-Bytes als TRAILING-Nullen (Fix #9). Behob "Non-zero padding", aber ART lehnt ab mit "Out-of-order string_ids" - DEX string_ids MUSS sortiert sein, Umbenennen verschiebt Sortierposition. Post-Build-DEX-Patching prinzipiell unmoeglich (Erkenntnis #10).
### ENTSCHEIDENDE ERKENNTNIS #11 (15.08.2026, v17-TV-Test): DURCHBRUCH - Scraper laeuft! Zwei Runtime-Fehler auf Ebene 2

v17-TV-Test (arvio-tv-log-v17-filtered.txt) = MEGA-DURCHBRUCH. Erstmals laeuft unser Code:
- Download 1268062 bytes, plugin.load() ausgefuehrt, provider+extractors registriert.
- ARVIO ruft UNSERN load()-Override auf (Dispatch bindet! DEX-Patch hat funktioniert!).
- load() called with url={"id":603,"type":"movie"} und load: parsed tmdbId=603 isTv=false - UNSER Code laeuft.
ABER zwei Runtime-Fehler blockieren (Ebene 2, Scraper-Logik):
- Fehler 1: NoClassDefFoundError: Lkotlinx/serialization/KSerializer; (NiceHttp/cloudstream3 braucht es).
- Fehler 2: NoSuchMethodError: get(Lkotlin/coroutines/CoroutineContext$Key;)Lkotlin/coroutines/CoroutineContext$Element; in class Lj7/j; (verschachtelte Coroutine-Typen in ARVIO auch obfiziert, v17 nur Top-Level umbenannt).

### FIX #11 (IMPLEMENTIERT, 15.08.2026, v18): volle Obfuskations-Map + Serialization buendeln
Map extrahiert aus ARVIO v1.9.983 APK (classes5.dex) durch Matchen der Methodensignaturen. patch_class_obfuscation.py RENAMES erweitert auf 35 Eintraege (alle kotlin.coroutines.* + kotlin.jvm.functions.*: Continuation->j7/d, CoroutineContext->j7/j, $Element->j7/j$a, $Key->j7/j$b, ContinuationInterceptor->j7/g, Function0..21->x7/a..x7/n, Function->d7/o). build.gradle.kts: kotlinx-serialization-core+-json gebuendelt. v18 verifiziert. CI gruen. builds: v18.
### ERKENNTNIS #12 (15.08.2026, v18-TV-Test): ContinuationInterceptor.Key-Feld von R8 verschoben

v18-TV-Test (arvio-tv-log-v18-filtered.txt): FEHLER #1 (KSerializer) WEG (Serialization-Bundle funktioniert!). load() laeuft, TMDB parsed, ABER:
NoSuchFieldError: No static field Key of type Lj7/f; in class Lj7/g;
Root cause: ARVIOs R8 hat das statische Companion-Feld "Key" auf ContinuationInterceptor (j7/g) ENTFERNT und das Key-Singleton als statisches Feld "i" auf der Key-Klasse selbst (j7/f) abgelegt. Unsere gebundelten kotlin.coroutines.ContinuationImpl + kotlinx-coroutines machen noch getstatic j7/g->Key (Klassenname umbenannt, Feldname nicht) -> ARVIOs j7/g hat kein Feld "Key" -> NoSuchFieldError. ARVIO hat ContinuationImpl GANZ entfernt (weder obfusziert noch unobfusziert) -> unsere gebundelte Version wird geladen -> sie hat den falschen Feld-Ref.
Verifiziert im ARVIO-DEX: j7/g hat 0 statische Felder; einziges Feld vom Typ j7/f ist j7/f->i (Singleton, access 0x1019 public static final volatile).

### FIX #12 (IMPLEMENTIERT, 15.08.2026, v19): Fieldref-Rewrite ContinuationInterceptor.Key -> j7/f.i
patch_class_obfuscation.py um Phase 2 erweitert: nach Utf8-Renames den Constant Pool walken, fuer jeden Fieldref (Class=j7/g, Name=Key, Type=Lj7/f;) die class_index+name_and_type_index umschreiben auf (Class=j7/f, Name=i, Type=Lj7/f). Class_info + NameAndType-Eintraege werden bei Bedarf am Pool-Ende angehaengt (bestehende Indizes bleiben stabil). WICHTIG: CONSTANT_Class_info nutzt internal-name (j7/g) nicht Deskriptor (Lj7/g;) in .class-Dateien.
Verifiziert: ContinuationImpl.intercepted()/releaseIntercepted() machen jetzt sget-object Lj7/f;->i (war Lj7/g;->Key). Override-Signaturen korrekt. CI gruen. builds: v19.
### ERKENNTNIS #13 (15.08.2026, v19+v20-TV-Tests): okhttp3 + coroutine-Resume broken -> app.get unbrauchbar
v19-TV-Test: ContinuationInterceptor.Key-Fehler WEG (Fieldref-Rewrite funktioniert). load() laeuft, TMDB parsed. ABER:
NoSuchMethodError: get$default(...Lokhttp3/Interceptor;...Lj7/d;...) in com.lagradost.nicehttp.Requests
Root cause: ARVIO obfuscated das GANZE okhttp3-Paket zu rb/* (nur ~3 Klassen erhalten). Unsere app.get-Aufrufsite nutzt unobfuscated okhttp3.Interceptor, ARVIOs get$default erwartet rb/c0 -> Signatur-Mismatch.
v20-Fix: okhttp3/Interceptor -> rb/c0 zur RENAMES-Map hinzugefuegt. CI gruen. v20-TV-Test: okhttp-Fehler WEG, ABER:
ClassCastException: k7.a cannot be cast to com.lagradost.nicehttp.NiceResponse
k7/a ist ein 3-Wert-Enum. app.get (suspend, ARVIO-provided) resume-t nicht korrekt aus unserem externen Plugin -> gibt stray Enum statt NiceResponse zurueck. Die coroutine-Machinery ist fuer externe .cs3-Aufrufe von ARVIOs suspend-Funktionen grundsaetzlich gestoert (okhttp3 ~100 Klassen obfuscated, Whack-a-Mole endlos).

### FIX #13 (IMPLEMENTIERT, 15.08.2026, v21): HTTP komplett auf java.net + jsoup umgestellt (app.get entfernt)
Strategiewechsel: nicht mehr jede obfuscated okhttp/coroutine-Type einzeln jagen. Statt ARVIOs suspend app.get (NiceHttp/okhttp) nutzen wir plain java.net.HttpURLConnection (JDK, NIE obfuscated) + Jsoup.parse (jsoup von ARVIO unobfuscated kept, 330 Klassen verifiziert). Alle internen HTTP-Helfer (fetchTmdbMeta, searchFilmpalast, buildMovieResponse, genericResolve) -> httpGet()-Helper, nicht-suspend. withTimeoutOrNull entfernt (stattdessen java.net connect/read timeouts). load()/loadLinks()/search() bleiben suspend (cloudstream3 API-Vertrag, DEX-patched j7/d) aber haben keine inneren suspend-Aufrufe mehr (coroutine state machine trivial -> obfuscated-Type-Breakage umgangen). AUSNAHME: loadExtractor + newMovieLoadResponse/newTvSeriesLoadResponse bleiben suspend-Aufrufe (ARVIO->ARVIO intern, funktionieren wie ARVIOs eigene Scraper). Builds: v21. CI gruen.
- **v21**: HTTP auf java.net.HttpURLConnection + Jsoup.parse umgestellt, app.get entfernt (Fix #13). Umgeht okhttp3+coroutine-Obfuskation komplett. CI gruen. builds: v21.
- **v22**: Jackson/parseJson durch org.json ersetzt (Fix #15). v21-TV-Test war MEGA-DURCHBRUCH (Dispatch bindet, httpGet funktioniert, TMDB-Meta geholt), aber parseJson<TmdbMeta> crashte wegen kotlin-reflect von R8 gestript ("This callable does not support a default call"). org.json = Android built-in, nie obfuscated, keine Reflection. CI gruen. builds: v22 (1478891 bytes).
- **v23**: loadExtractor entfernt + eigene Hoster-Extraktion + Regex-Fix (Fix #16). v22-TV-Test: Scraper lief KOMPLETT durch, ABER loadExtractor crashte (ClassCastException, ARVIO-suspend broken) + genericResolve-Regex unbalanciert. Fix: loadExtractor entfernt, resolveHost/resolveVoe, Regex fixiert. CI gruen. builds: v23 (1479448 bytes).
- **v24**: odysseusa.cc-Extractor (api/stream POST) + matchResults exakt-Match-Fix (Fix #17). v23-TV-Test: KEIN CRASH mehr! Scraper laeuft sauber durch, 0 Quellen, clean termination. Hoster-Analyse (live curl): odysseusa.cc hat /api/stream POST -> JSON streaming_url (master.m3u8, live getestet!), voe.sx = DDoS-Guard, vidsonic.net = obfuscated JS, flyfile.app = Cloudflare. Fix: resolveOdysseusa + httpPost + matchResults exakt-Match-Sort + resolveVoe status-tolerant. CI gruen. builds: v24 (1481193 bytes).
- **v25** (AKTUELL): ExtractorLink primary ctor (no default-args) + catch Throwable (Fix #18). v24-TV-Test: FAST AM ZIEL! resolveOdysseusa extrahierte master.m3u8, ABER \"0 links collected\" wegen NoSuchMethodError (synthetischer DefaultConstructorMarker-ctor von R8 geschrumpft). Fix: ExtractorLink primary ctor (9 positionale Args), alle catch-Blocks Exception->Throwable (14 Bloecke), emitLink try/catch(Throwable). CI gruen. builds: v25 (1481491 bytes).
- **v25-TV-TEST (15.08.2026) = DURCHBRUCH! ERSTE FILMPALAST-QUELLE IN ARVIO!** resolveOdysseusa: streaming_url extrahiert, emitLink klappt (primary ctor), `loadLinks: DONE, any=true`, ARVIO `1 links, 0 subs`, `returned 1 results`. odysseusa-Quelle in ARVIO sichtbar! VOE+vidsonic found=false (DDoS-Guard/obfuscated, erwartet).

### ENTSCHEIDENDE ERKENNTNIS #18 (15.08.2026, v24-TV-Test): streaming_url extrahiert! Aber ExtractorLink-ctor von R8 geschrumpft

v24-TV-Test (arvio-tv-log-v24-filtered.txt) = **FAST AM ZIEL!** Drei Fixes funktionieren:
1. matchResults-Fix: `match: Matrix` ist jetzt ERSTER Match (war \"Matrix Revolutions\").
2. buildMovieResponse laedt die richtige Seite: `GET https://filmpalast.to/stream/matrix` (war matrix-revolutions).
3. **resolveOdysseusa funktioniert!** `httpPost: https://odysseusa.cc/api/stream -> 200 (426 bytes)` -> `resolveOdysseusa: streaming_url=https://s25-wyl1.s1q2105.com/hls/.../master.m3u8?token=...` -> **m3u8-URL extrahiert!**

ABER: `0 links collected` trotz extrahierter streaming_url. Fehler: `TmdbProvider Filmpalast loadLinks error: No (0 links collected)` - das \"No\" ist trunciert (NoClassDefFoundError oder NoSuchMethodError).

**Root-Cause (verifiziert an cloudstream3-JAR):** `ExtractorLink` hat 2 Konstruktoren:
- Primary (9 Params, keine Defaults): `(String, String, String, String, int, Map, String, ExtractorLinkType, List)`
- Synthetic (11 Params mit DefaultConstructorMarker): fuer Default-Args
Unser Code nutzte named args (`source=`, `type=`) und liess `headers`/`extractorData`/`audioTracks` auf Defaults -> Kotlin generiert den synthetischen DefaultConstructorMarker-ctor -> **R8 hat diesen geschrumpft** (wie MainPageData, Erkenntnis #6) -> NoSuchMethodError.
Zusaetzlich: der Error entwischt, weil `resolveHost`/`emitLink` nur `Exception` fingen - `NoSuchMethodError` ist ein **Error** (keine Exception), gleiche Fehlerklasse wie Erkenntnis #2/#3.

**Fix #18 (IMPLEMENTIERT, v25):**
- **ExtractorLink primary ctor**: alle 9 positionale Args (`source, name, url, mainUrl, Qualities.Unknown.value, emptyMap(), \"\", type, emptyList()`) - keine Defaults, kein DefaultConstructorMarker.
- **Alle 14 catch-Blocks**: `Exception` -> `Throwable` (faengt nun NoClassDefFoundError/NoSuchMethodError).
- **emitLink**: try/catch(Throwable) mit vollem Fehler-Logging (`t.javaClass.name`).
- Verifiziert: v25 (1481491 bytes), emitLink-Signatur korrekt, loadExtractor=0 refs. CI gruen. builds: v25.

**Erwartung v25-Test:** ExtractorLink-Konstruktion klappt (primary ctor ist von -keep retained). callback.invoke(link) emittiert die Quelle an ARVIO. `N links collected` (N>0) -> **Filmpalast-Quelle in ARVIO sichtbar = ZIEL ERREICHT!** Falls doch ein Error (neue geschrumpfte Klasse): try/catch(Throwable) faengt ihn, volles Logging im Logcat zeigt welche Klasse/Methode fehlt.

### ENTSCHEIDENDE ERKENNTNIS #19 (16.08.2026, Serienstream-Modul): DDoS-Guard auf /r?-Redirect = HAUPT-Huerde

**Serienstream (serienstream.to) als neues Provider-Modul gebaut (v31, Phase D).** Struktur verifiziert (live curl, Aug 2026):
- Suche `/suche?term=<q>&tab=shows` -> `.results-group a[href=/serie/<slug>]`. Funktioniert vom Laptop (HTTP 200).
- Serien-Seite `/serie/<slug>` -> `#season-nav ul li a[href=/staffel-N]`. Funktioniert.
- Season-Seite `/serie/<slug>/staffel-N` -> `tr.episode-row` mit `onclick="window.location='/serie/<slug>/staffel-N/episode-M'"`, `.episode-number-cell`, `.episode-title-cell`. Funktioniert (Struktur = GermanProviders-Vorlage).
- Episode-Seite `/serie/<slug>/staffel-N/episode-M` -> `.link-wrapper > button` mit `data-play-url="/r?t=<encrypted>"` + `data-provider-name` + `data-language-label`. Funktioniert.

**ABER: Hoster-Redirect `/r?t=...` ist DDoS-Guard-geschuetzt.** Die `data-play-url` ist ein Laravel-AES-verschluesselter Redirect (Server entschluesselt, wir bekommen nur den Blob). Der `/r?`-Endpunkt liefert eine **DDoS-Guard js-Challenge** (403) statt der direkten Hoster-URL. Cookies von der Episode-Seite (`__ddg8_/__ddg9_/__ddg1_`) allein reichen NICHT (verifiziert: mit Session-Cookies immer noch 403 + Challenge-Body).

**xStream (michaz) DDoS-Guard-Bypass gefunden (requestHandler.py:255-275):** Bei 403+DDoS-Guard -> lade `https://check.ddos-guard.net/check.js` -> extrahiere Image-URL (`Image.*?'([^']+)'; new`) -> lade sie auf dem Target-Host (setzt `__ddg2_`-Cookie) -> retry Original-Request. **ABER live getestet: dieser OLD bypass setzt zwar `__ddg2_`, reicht aber fuer Serienstreams `/r?`-Endpunkte NICHT mehr** вҖ” diese nutzen mittlerweile eine neuere js-Challenge (`view.js` + `index.js`), die echtes JS-Ausfuehren erfordert. xStream's Bypass war fuer die aeltere Challenge; aktuelle Serienstream-`/r?` bleibt 403.

**resolveurl (Gujal00/ResolveURL 5.1.206, aktuellste Version) loest DDoS-Guard NICHT.** net.py wirft nur `ResolverError('Cloudflare challenge')` bei Cloudflare, kein DDoS-Guard-Solver vorhanden. resolveurl resolved Hoster-URLs direkt (voe.sx/e/xxx, dood.so/e/xxx) вҖ” JEMAND muss das `/r?`-Redirect VORHER aufloesen. Doodstream/FileMoon/VidHide sind direkt erreichbar (HTTP 200, kein DDoS-Guard); nur der Serienstream-Redirect ist das Hindernis.

**Fix #19 / Option A (IMPLEMENTIERT, v31):** Serienstream-Modul als TmdbProvider (wie Filmpalast), mit java.net-HTTP + CookieJar + xStream-DDoS-Guard-Bypass-Versuch (check.js Image-Trick) in httpGet. Falls Bypass scheitert (neuere js-Challenge), resolveHost faellt auf genericResolve zurueck. **TV-Test entscheidet**: der TV (andere IP/Wohn-IP vs Rechenzentrum) koennte durchkommen, wo Laptop blockt. Bei VOE bekam der TV immerhin eine Challenge-Seite (nur keine echte Embed-Seite) вҖ” bei `/r?` koennte es anders laufen.

**Option B (Falls A scheitert, NOCH NICHT umgesetzt):** view.js-js-Challenge reverse-engineeren + Token-Berechnungsalgorithmus nach Kotlin portieren. Vorher: GitHub-Recherche nach DDoS-Guard-js-Challenge-Solver (cloudscraper-aehnlich fuer DDoS-Guard).

**Serienstream-Hoster-Spektrum (live verifiziert, Aug 2026):** VOE (haeufig, DDoS-Guard-blocked), Doodstream (direkt erreichbar!), FileMoon, VidHide, Streamtape. Doodstream-Extractor aus resolveurl doodstream.py portiert (dsplayer.hotkeys -> token -> /pass -> mp4). VOE-Extractor (voe_decode) aus Filmpalast reuse'd. Streamtape/FileMoon/VidHide via genericResolve (sources-JSON + m3u8/mp4-Regex).

### FIX #19 / v31: Serienstream-Modul (Phase D, Option A)
- **Neues Modul `Serienstream/`** (settings.gradle auto-include). Package `com.reichi.arflioaddon.serienstream`.
- **SerienstreamProvider : TmdbProvider** (series-only, tvTypes=[TvSeries]). load({"id":<tmdbId>,"type":"tv"}) -> TMDB-Meta -> searchSeries (Titel-Match) -> buildSeriesResponse (Seasons + Episoden). loadLinks: episode-Seite -> .link-wrapper buttons -> /r? redirect follow -> resolveHost.
- **CookieJar.kt**: in-memory cookie jar (java.net, kein okhttp), captureSetCookie (auch bei 403), toCookieHeader fuer retry. Scoped pro httpGetInternal-Aufruf.
- **httpGet mit DDoS-Guard-Bypass**: bei 403+DDoS-Guard -> tryDdosGuardBypass (check.js + Image -> __ddg2_) -> retry. instanceFollowRedirects=true damit /r? redirect zur finalen Hoster-URL verfolgt wird (wenn nicht blockiert).
- **resolveHost**: folgt /r? redirect, dispatcht per finaler Domain: Doodstream (dsplayer.hotkeys-Algorithmus), VOE (voe_decode), Streamtape (linko), FileMoon/VidHide (genericResolve), generic fallback.
- **ExtractorLink primary ctor** (9 Args, wie v25/Filmpalast). Alle catch Throwable.
- Version 31. CI baut beim Push auf main. **TV-Test ausstehend.**

### ENTSCHEIDENDE ERKENNTNIS #20 (16.08.2026, v31-TV-Test + ALTCHA-Analyse): /r?-Redirect nutzt ALTCHA Proof-of-Work, NICHT unГјberwindbare DDoS-Guard-JS-Challenge

**v31-TV-Test = MEGA-DURCHBRUCH.** Der Serienstream-Scraper lГӨuft KOMPLETT durch:
- Download 1485090 bytes, plugin.load(), Provider+Extractoren registriert.
- **Dispatch bindet!** ARVIO ruft UNSERN load()-Override auf: `TmdbProvider Serienstream: load({"id":125988,"type":"tv"})`.
- TMDB-Meta geholt: `title='Silo' year=2023`.
- `searchSeries: 12 candidates` вҖ” Suche funktioniert.
- `buildSeriesResponse: built 29 episodes` вҖ” alle 3 Staffeln + Episoden korrekt (S1E1 "Freiheitstag", S2E1 "Die Ingenieurin", etc.).
- `loadLinks: 4 hoster buttons` вҖ” 4 Hoster gefunden (VOE Deutsch/Englisch, Provider Deutsch/Englisch).
- `resolveHost: VOE/Provider https://serienstream.to/r?t=... -> final=https://serienstream.to/r?t=... (code=200)` вҖ” ABER final=gleiche URL, keine Weiterleitung zur echten Hoster-URL.
- `genericResolve: ... found=false (html 883 chars)` вҖ” die 883-Byte-Antwort ist die DDoS-Guard-Challenge-Seite, keine echte Hoster-Embed-Seite.
- `0 links collected` вҖ” keine Serienstream-Quelle.

**ABER: Das ist NICHT das unГјberwindbare DDoS-Guard-JS-Problem!** Analyse der 883/902-Byte-Antwort + der Episode-Seite enthГјllte den echten Mechanismus:

**Serienstream nutzt ALTCHA Proof-of-Work (statt DDoS-Guard-JS-Challenge) fГјr /r?-Redirects:**
1. Episode-Seite enthГӨlt: `<div id="episode-redirect-gate-root" data-redirect-gate-tier="turnstile_altcha" data-altcha-challenge-url="https://serienstream.to/api/inline/verify-init">` + `<form id="player-prepare-form" method="POST" action="/r"><input name="_token" value="<CSRF>"><input name="t" id="player-prepare-token"><input name="altcha"></form>`.
2. `GET /api/inline/verify-init` liefert JSON: `{"algorithm":"SHA-256","challenge":"<hash>","maxnumber":100000,"salt":"<salt>","signature":"<sig>"}`.
3. **PoW lГ¶sen:** finde n in 0..100000 sodass `SHA-256(salt + str(n)) == challenge`. **VERIFIZIERT:** n=77975/68285/88473/76262 (variiert pro Challenge) liefert exakt den Challenge-Hash. Trivial in Kotlin (java.security.MessageDigest, JDK, nie R8-obfuscated).
4. `payload = base64(JSON{algorithm, challenge, number, salt, signature})`.
5. `POST /r` mit Form-Fields `_token` (CSRF) + `t` (der /r?t= token, URL-decoded) + `altcha` (payload) вҶ’ 200 mit JS-Body.
6. Bei Erfolg: Body enthГӨlt `var t = "<echte-hoster-url>"` (postMessage an Parent-Frame) вҶ’ das ist die echte Hoster-URL!
7. Bei Misserfolg: Body enthГӨlt `var err = "Das hat leider nicht geklappt..."`.

**Vom Laptop:** POST /r liefert 200 + `err="Das hat leider nicht geklappt"` вҖ” weil der Laptop die `/r?t=`-Preflight-Seite mit 403 (DDoS-Guard) bekommt und somit keine validen DDoS-Guard-Session-Cookies hat. **Am TV:** `/r?t=` liefert 200 (mit Challenge-iframe) вҶ’ valide DDoS-Guard-Cookies вҶ’ POST /r sollte success-body mit echter Hoster-URL liefern.

**Fix #20 (IMPLEMENTIERT, v32): ALTCHA-PoW-Solver + POST /r-Flow in resolveRedirectGate**
- `resolveRedirectGate(redirectUrl, episodePageUrl)`: lГӨdt Episode-Seite (CookieJar fГјr Session!), extrahiert CSRF _token + t-token, holt verify-init, lГ¶st PoW, POST /r mit _token+t+altcha, parst 200-Body nach `var t = "<hoster-url>"` / direkter Hoster-URL.
- `solveAltcha(initJson)`: SHA-256-Loop (java.security.MessageDigest), baut base64(JSON)-Payload.
- `doRequestPost(url, body, headers, cookieJar)`: POST mit CookieJar-Support (Session/XSRF-TOKEN mГјssen persistieren von Episode-Seite zu POST /r).
- `resolveHost`: erkennt `/r?t=` вҶ’ ruft `resolveRedirectGate` statt direktem `httpGet`.
- CookieJar ist pro resolveRedirectGate-Aufruf (thread-safe fГјr parallele Hoster-AuflГ¶sung).
- Version 32. CI baut beim Push. **TV-Test entscheidend:** falls der TV (Wohn-IP) die `/r?t=`-Preflight mit 200 bekommt, liefert POST /r die echte Hoster-URL вҶ’ erste Serienstream-Quelle!

**OFFIZIELLE ALTCHA-QUELLEN (fГјr nГӨchste Session, falls der Flow angepasst werden muss):**
- **ALTCHA Spec (offiziell):** https://altcha.org/docs/v2/proof-of-work-captcha вҖ” PoW v2 nutzt KDFs (PBKDF2/Argon2id), aber Serienstream nutzt die **v1 (legacy)** Variante: `hash(salt + n) == challenge` (einfaches SHA-256, keine KDF). Die v1 ist unter "_v1/V1" suffix dokumentiert.
- **ALTCHA Python-Library (Referenz-Implementierung):** https://github.com/altcha-org/altcha-lib-py вҖ” `create_challenge(algorithm="SHA-256", max_number=1000000)`, verify: `hash(salt + str(number)) == challenge`.
- **ALTCHA Bypass-Doku (2captcha, erklГӨrt den Flow gut):** https://2captcha.com/h/how-to-bypass-altcha вҖ” "Server sends JSON with challenge/difficulty/salt/algorithm, browser iterates nonce until hash meets condition, validation is stateless/mathematical."
- **Serienstream-spezifisch:** `episode-redirect-gate-C_Px7kjn.js` (https://serienstream.to/build/assets/episode-redirect-gate-C_Px7kjn.js) вҖ” lГӨdt `altcha-BhBXWxP7.js` Widget, das die Challenge lГ¶st und als Form-Feld `name="altcha"` (base64 JSON) an `POST /r` sendet.
- **verify-init Endpoint:** `GET https://serienstream.to/api/inline/verify-init` вҶ’ `{"algorithm":"SHA-256","challenge":"<64-char-hex>","maxnumber":100000,"salt":"<20-char>","signature":"<64-char-hex>"}`.

**LIVE-TEST-BEFUND (Laptop, 16.08.2026, Python-Curl):**
- `GET /api/inline/verify-init` вҶ’ 200, Challenge params geliefert. вң… (Laptop darf die API)
- PoW gelГ¶st: n=77975/68285/88473/76262 (variiert pro Challenge, alle ~50k-90k Iterationen). вң…
- `POST /r` mit `_token`+`t`+`altcha` вҶ’ **200**, aber Body: `var err = "Das hat leider nicht geklappt. Bitte versuche es erneut."` вқҢ
- **Warum am Laptop fehlgeschlagen:** `/r?t=` liefert vom Laptop 403 (DDoS-Guard blockt) вҶ’ keine validen DDoS-Guard-Session-Cookies (`__ddg1_`, `__ddg8_`, `__ddg9_`, `__ddg10_`) вҶ’ POST /r lehnt ab. Ohne vorherigen 200-Besuch der `/r?t=`-Preflight-Seite fehlt die DDoS-Guard-Session.
- **Warum am TV funktionieren sollte:** v31-Log zeigte `/r?t=` liefert am TV **200** (883 Bytes, Challenge-iframe) вҶ’ TV hat valide DDoS-Guard-Cookies вҶ’ POST /r sollte success-body (`var t = "<hoster-url>"`) liefern. Das ist die Hypothese, die v32-TV-Test verifiziert.

**WICHTIG fГјr ALTCHA-Debugging (falls v32-TV-Test scheitert):**
- Falls `redirectGate: server rejected ALTCHA: ...` im Log вҶ’ PoW akzeptiert, aber Token ungГјltig. MГ¶gliche Ursachen: (a) `t`-Token ist an Session gebunden und verfГӨllt, (b) Turnstile-Token fehlt (das Gate ist `turnstile_altcha` вҖ” vielleicht braucht Cloudflare-Turnstile UND ALTCHA), (c) `t` muss als URL-encoded statt decoded gesendet werden.
- Falls `redirectGate: verify-init -> HTTP 403` вҶ’ DDoS-Guard blockt die API am TV (unwahrscheinlich, da Episode-Seite durchkam).
- Falls `solveAltcha: no solution found` вҶ’ Challenge nicht in 0..100000 lГ¶sbar (Server hat maxnumber geГӨndert? Algorithmus gewechselt?).
- Falls POST /r 200 + `var t = "<url>"` aber URL ist keine Hoster-URL вҶ’ postMessage-Format anders als erwartet, Body-Parser anpassen.
- **Turnstile-Hypothese (Prio fГјr Fallback):** Das Gate heiГҹt `turnstile_altcha` вҖ” mГ¶glicherweise braucht Serienstream BEIDE: Cloudflare-Turnstile (CAPTCHA) UND ALTCHA (PoW). Falls ja, ist Turnstile ohne Browser nicht lГ¶sbar вҶ’ dann wГӨre Serienstream doch nicht umgehbar. v32-Log zeigt ob ALTCHA allein reicht.

### AKTUELLER STAND (Stand 16.08.2026, KinoGer v2 + Handy-Logcat-Setup erfolgreich)

**Builds-Branch:**
- **FilmPalast.cs3 v30** (1.486.293 Bytes, status=1, Movie+TvSeries) вҖ” funktioniert, liefert odysseusa+vidsonic-Quellen.
- **Serienstream.cs3 v32** (1.488.388 Bytes, status=1, TvSeries) вҖ” ALTCHA-PoW-Solver implementiert. TV-Test ausstehend.
- **Kinoger.cs3 v2** (1.486.572 Bytes, status=1, Movie+TvSeries) вҖ” v2 mit korrigiertem Such-Parser (v1 hatte falschen CSS-Selektor). **TV-Test ausstehend.**
- `plugins.json` auf builds enthГӨlt alle 3 Module.

**MEILENSTEIN (16.08.2026): KinoGer-Scraper lГӨuft KOMPLETT durch, Dispatch bindet, nur Such-Parser war falsch (v2 fixt das).**
- Handy-Logcat erfolgreich eingerichtet (Termux + adb pair/connect + READ_LOGS-Berechtigung).
- KinoGer v1-TV-Test zeigte: Download klappt, plugin.load() lГӨuft, `TmdbProvider Kinoger: load({"id":603,"type":"movie"})` wird aufgerufen, TMDB-Meta geholt (`title='Matrix' year=1999`), HTTP-Search lГӨuft (`kinoger.com/?do=search... -> 200 (98900 bytes)`).
- **ABER v1-Bug:** `searchKinoger: CSS selector matched 0 elements` вҖ” v1 suchte `section.post` + `h2 a`, aber KinoGer nutzt `div.content_text.searchresult_img` + `<img alt="...">`. v2 korrigiert (live validiert: 6 Ergebnisse fГјr "Matrix", korrekter Match "Matrix (1999)").
- **NГ„CHSTER SCHRITT: Nutzer testet v2 am Handy/TV** (Repo lГ¶schen + neu hinzufГјgen DIREKT, Matrix-Suche).

**KinoGer-Seitenstruktur (live verifiziert, 16.08.2026, KORRIGIERT fГјr v2):**
- KinoGer ist von der Heim-IP erreichbar (HTTP 200, kein 403 wie bei Render-Server-IP) вҖ” bestГӨtigt die Konsolidierungs-Hypothese.
- Suche: `https://kinoger.com/?do=search&subaction=search&titleonly=3&story=<query>&x=0&y=0&submit=submit` -> HTML mit `div.content_text.searchresult_img`-BlГ¶cken.
  - **Selektor v2:** `doc.select("div.content_text.searchresult_img")` (NICHT `section.post`!).
  - **Titel:** `<img alt="Matrix (1999)">` Attribut im Block (NICHT `h2 a` text!).
  - **Link:** `selectFirst("a[href*=/stream/]")`, `#comment`-Suffix strippen.
  - **WICHTIG: Desktop-UA nГ¶tig** вҖ” mobiles UA liefert JS-only-Template OHNE Hoster-Arrays (httpGet nutzt Desktop-UA).
- Stream-Seite: Hoster in `<script>` als `VAR.init(); VAR.show(SEASON, [[[S1E1_hosters...],[...]],...], 0.2)` вҖ” 3D-Array StaffelnГ—EpisodenГ—Hoster. Alle 4 Player-Vars (pw, fsst, go, ollhd) zeigen dieselbe Struktur mit verschiedenen Hoster-Domains.
- **fsst.online/embed/<id>** leitet zu **incvideo1.online** weiter und enthГӨlt Playerjs-Config `file:"[360p]url/,[720p]url/,[1080p]url/"` -> DIREKTE MP4-URLs, kein JS nГ¶tig. Das ist die Hauptquelle (resolveIncvideo).
- Andere Hosters (kinoger.pw, kinoger.embed4me.vip, kinoger.seekplays.pro) -> genericResolve (voraussichtlich leer, Phase 3-Hoster).

**KinoGer-Hoster (live verifiziert):**
| Hoster-Domain | Extraktor | Direkt? | Status |
|---|---|---|---|
| fsst.online / incvideo1.online | resolveIncvideo (Playerjs file-Regex) | JA (360p/720p/1080p mp4) | вң… Phase 1 fertig |
| kinoger.pw | genericResolve | ? | Phase 3 |
| kinoger.embed4me.vip | genericResolve | ? | Phase 3 |
| kinoger.seekplays.pro | genericResolve | ? | Phase 3 |

**Quellcode-Stand:**
- KinoGer-Modul komplett in `Kinoger/` (build.gradle.kts + KinogerPlugin.kt + KinogerProvider.kt + DebugLog.kt).
- settings.gradle.kts auto-include findet das Modul (keine Г„nderung nГ¶tig).
- Build: `./gradlew make makePluginsJson` -> alle 3 Module bauen grГјn.
- **Lokaler Build erfordert:** JDK 21 (openjdk-21-jdk-headless, Debian Trixie hat kein 17) + Android SDK 35 (cmdline-tools + platforms;android-35 + build-tools;35.0.0) + `local.properties` (sdk.dir). JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64.
- CI auf GitHub nutzt JDK 17 (setup-java@v4 distribution=adopt), funktioniert dort auch.

**HANDY-LOGCAT-SETUP ERFOLGREICH (16.08.2026, Pixel 7, ARVIO 1.9.994 sideload):**
- **Methode: Nur Termux + adb (keine LADB/Shizuku nГ¶tig).** Das ist die robusteste Methode.
- Einrichtung dokumentiert in `docs/handy-logcat-ladb-termux.md` (Abschnitt вҖһNur mit Termux").
- **Einmalige Schritte:** `pkg install android-tools` -> Entwickleroptionen вҖһDrahtloses Debugging" AN -> вҖһGerГӨt mit Pairing-Code koppeln" -> `adb pair <IP>:<Pairing-Port>` -> Code eingeben -> **WICHTIG: danach `adb connect <IP>:5555` (oder der Port aus den Entwickleroptionen, NICHT der Pairing-Port!)** -> `adb shell pm grant com.termux android.permission.READ_LOGS` -> `adb disconnect`.
- **Der hГӨufigste Fehler (Nutzer erlebt):** Nach `adb pair` vergessen, `adb connect` aufzurufen -> `adb shell` sagt вҖһno devices". Pairing вү  Verbindung. Pairing-Port (z. B. 45357) вү  Verbindungs-Port (meist 5555 oder 3xxxx, steht oben in Entwickleroptionen).
- **READ_LOGS-Berechtigung wird erst nach Prozess-Neustart aktiv:** `pm grant` setzt die Berechtigung aufs Paket, aber der laufende Termux-Prozess merkt es erst nach вҖһBeenden erzwingen" (Einstellungen вҶ’ Apps вҶ’ Termux вҶ’ Beenden erzwingen) + neu Г¶ffnen. Vorher sieht man nur Termux-eigene Logs (вҖһbeginning of main" + ImeTracker), danach sieht man вҖһbeginning of kernel" + System-Logs = Berechtigung aktiv.
- **PrГјfen ob Berechtigung da:** `logcat -d | head` -> вҖһbeginning of kernel" + trusty/faceauth-Zeilen = JA; nur Termux-Logs = NEIN.
- **Einmal eingerichtet, braucht man NIE WIEDER adb** вҖ” nur noch `logcat` (ohne `adb` davor) im Test-Ablauf.
- **Fertig-Skripte:**
  - `docs/save-handy-log.sh` (Handy-Logcat, kein TV): `~/save-handy-log.sh kinoger2` вҖ” installierbar via `curl -sL https://raw.githubusercontent.com/ReichiMD/Arvio-Addon/main/docs/save-handy-log.sh -o ~/save-handy-log.sh && chmod +x ~/save-handy-log.sh`. Braucht einmalig `termux-setup-storage`.
  - `docs/save-tv-log.sh` (TV-Logcat via WLAN-ADB): `~/save-tv-log.sh v32` вҖ” verbindet sich mit TV (192.168.0.59:5555).
- **Voller Befehl ohne Skript:** `logcat -d -v time | grep -iE "Filmpalast|Serienstream|Kinoger|ArvioAddon|ExternalExtension|ExtExt|PluginManager|No API loaded|ErrorLoading|verify dex|MISSING CLASS|CloudstreamPlugin|Executing DEX|resolveHost|resolveIncvideo|resolveVoe|resolveDoodstream|genericResolve|emitLink|loadLinks|fetchTmdbMeta|searchSeries|searchKinoger|buildSeriesResponse|parseShowArrays|httpGet|httpPost|detectQuality" > ~/storage/downloads/arvio-logs/arvio-handy-log.txt`

**TEST-ABLAUF FГңR NГ„CHSTE SESSION (Handy, Standard):**
1. In ARVIO: Repo LГ–SCHEN + neu hinzufГјgen DIREKT (NICHT Cloud-Sync!) -> URL: `https://raw.githubusercontent.com/ReichiMD/Arvio-Addon/main/repo.json` -> Scraper einschalten.
2. In Termux: `logcat -c`
3. In ARVIO: Film/Serie suchen (z. B. Matrix) -> вҖһNach Quellen suchen" -> 15s warten.
4. In Termux: `~/save-handy-log.sh kinoger2` (oder voller Befehl oben).
5. Datei aus Downloads/arvio-logs/ teilen.

**ERWARTUNG v2-TEST (was im Log stehen sollte):**
- `searchKinoger: CSS selector matched 6 elements` (statt 0 wie bei v1) -> Parser fixt.
- `match: Matrix (1999) | https://kinoger.com/stream/1499-matrix-1999.html` -> korrekter Match.
- `buildMovieResponse: GET https://kinoger.com/stream/1499-matrix-1999.html -> 200` -> Stream-Seite geladen.
- `resolveIncvideo: вҖҰ [1080p]https://вҖҰmp4` -> ERSTE KINOGER-QUELLE! рҹҺҜ
- Falls neue Fehler (Jsoup-Selektor auf Stream-Seite falsch, Hoster-Extraktion trifft nicht): Logcat zeigt wo, dann gezielt fixen.

**NAECHSTE SCHRITTE (Stand 16.08.2026, fuer naechste Session):**
1. **Prio 1: v2-TV/Handy-Test** вҖ” Nutzer testet Kinoger v2, schickt Log. Wenn `resolveIncvideo` durchlГӨuft, erste KinoGer-Quelle (1080p MP4) in ARVIO sichtbar.
2. **Prio 2: Serienstream v32-TV-Test** вҖ” ALTCHA-PoW-Solver, noch ausstehend.
3. **Prio 3 (je nach v2-Befund):** Falls Stream-Seite-Parse-Fehler (show()-Arrays, Hoster-Extraktion): Selektoren/Regex anpassen. Falls neue R8-Stripped-Klasse: wie frГјher workarounden.
4. **Prio 4: GitHub-Issue bei ARVIO** (noch NICHT eroeffnen вҖ” erst nach Tests). Drei Bugs dokumentiert (R8-Obfuskation, Cloud-Sync-Download, ehem. Touch-Bug behoben).

### ENTSCHEIDENDE ERKENNTNIS #17 (15.08.2026, v23-TV-Test + Hoster-Analyse): KEIN CRASH, echte Hoster-Extraktion, odysseusa-API gefunden

v23-TV-Test (arvio-tv-log-v23-filtered.txt) = **KEIN CRASH mehr!** loadExtractor-Entfernung (Fix #16) funktioniert. Scraper laeuft sauber durch, alle 4 Hoster found=false (Extraktion trifft nicht, aber clean termination). **Voll auf Ebene 2 = echte Hoster-Extraktion, kein Obfuskations-Problem mehr.**

**Hoster-Analyse (live curl aller 4 Embed-Seiten, 15.08.2026):**
- **odysseusa.cc**: JWPlayer, POST `/api/stream` mit `{filecode,device}` -> JSON `streaming_url` (master.m3u8 mit token). **Live getestet, funktioniert!** -> resolveOdysseusa gebaut.
- **voe.sx**: DDoS-Guard JS-Challenge (kein echtes 404). Nicht von java.net umgehbar. resolveVoe scannt jetzt Body auch bei non-200.
- **vidsonic.net**: Stark obfusziertes JS (atob/charCodeAt), andere Platform, kein /api/stream. genericResolve.
- **flyfile.app**: Cloudflare-Challenge. Nicht umgehbar. Skip.

**Zusaetzlicher Bug: matchResults zu lax** -> nahm \"Matrix Revolutions\" statt \"Matrix\". Fix: sortiert nach exaktem Titel-Match + Jahr-Naehe.

**Fix #17 (IMPLEMENTIERT, v24):** resolveOdysseusa (filecode + POST /api/stream + JSON streaming_url), httpPost helper, matchResults exakt-Match-Sort, resolveVoe status-tolerant. Verifiziert: v24 (1481193 bytes), loadExtractor=0 refs. CI gruen. builds: v24.

**Erwartung v24-Test:** odysseusa.cc sollte die **ERSTE Filmpalast-Quelle** in ARVIO liefern! (`resolveOdysseusa: streaming_url=https://...master.m3u8` -> Quelle sichtbar). Plus korrekter Film gematcht.

### ENTSCHEIDENDE ERKENNTNIS #16 (15.08.2026, v22-TV-Test): Scraper laeuft KOMPLETT durch + loadExtractor broken + Regex-Bug

v22-TV-Test (arvio-tv-log-v22-filtered.txt) = weiterer MEGA-Fortschritt. Jackson-Fix (v22) funktioniert, Scraper laeuft KOMPLETT durch bis zur Hoster-Extraktion:
- `load: TMDB meta -> title='Matrix' year=1999` -> org.json-Parsing klappt!
- `searchFilmpalast: CSS selector matched 5 elements` -> Filmpalast-Suche laeuft!
- `buildMovieResponse: collected 4 hoster links` (odysseusa.cc, voe.sx, vidsonic.net, flyfile.app) -> Hoster extrahiert!
- `TmdbProvider Filmpalast: loaded MovieLoadResponse` + `loadLinks data={"links":[...]}` -> loadLinks aufgerufen!

ABER zwei Probleme in loadLinks:

**Problem 1 (CRASH): loadExtractor wirft ClassCastException**
```
loadExtractor('https://voe.sx/...') threw ClassCastException: k7.a cannot be cast to java.lang.Boolean
loadExtractor('https://flyfile.app/...') threw ClassCastException: d7.d0 cannot be cast to java.lang.Boolean
```
- `loadExtractor` ist eine ARVIO-provided suspend-Funktion (liefert Boolean). **Gleiches Problem wie app.get (Erkenntnis #13/#14):** die Coroutine-Machinery ist fuer externe .cs3-Plugins broken - resume liefert stray obfuszierte Enum/Objekte (k7.a = 3-Wert-Enum, d7.d0) statt Boolean -> ClassCastException.
- Zusaetzlich: `0 links collected` trotz `any=true` -> die callback-Emissionen erreichen ARVIO ebenfalls nicht (callback ist auch eine suspend-beteiligte Function1, deren Typ-Deskriptor mismatched).
- Das beweist: **JEDE ARVIO-provided suspend-Funktion** ist fuer externe .cs3-Plugins broken (app.get, loadExtractor, und vermutlich alle anderen). Die Coroutine Continuation-Typen mismatchen (Erkenntnis #7).

**Problem 2 (BUG): genericResolve-Regex unbalanciert**
```
PatternSyntaxException: Incorrectly nested parentheses near index 64
["'(]((?:/[^"')\s]+|\.\./[^"')\s]+|[^"')\s]+\.(?:m3u8|mp4))["')]
```
- Der 3. Regex-Pattern hatte 3 oeffnende `(` aber nur 2 schliessende `)` - die `(?:` fuer die Alternation wurde nie geschlossen. War schon in v21 drin, wurde aber nie erreicht (weil v21 vorher crashte).

**Fix #16 (IMPLEMENTIERT, 15.08.2026, v23):** Konsistent mit v21-Strategie (keine ARVIO-provided suspend-Funktionen aus unserem Plugin aufrufen):
- **loadExtractor komplett entfernt.** Neue `resolveHost(url, callback)` dispatcht per Domain zu hoster-spezifischen non-suspend Extractoren.
- **resolveVoe** fuer voe.sx (der haeufigste Filmpalast-Hoster): parst die VOE-Embed-Seite nach hls/mp4 URLs (JSON-ish `"hls":"..."`/`"file":"..."`/`"src":"..."` Blob, p.a.c.k.e.r'd script unpacking, base64-decoded body fallback).
- **genericResolve** als Fallback fuer unbekannte Hoster: fetcht Embed-Seite, sucht direkte mp4/m3u8 URLs. Regex fixiert (balanciert).
- **Alle Extraktion non-suspend** (java.net + jsoup), umgeht ARVIO-Coroutine-Machinery komplett.
- Verifiziert: FilmPalast.cs3 v23 (1479448 bytes), **0 loadExtractor-Referenzen** in DEX, resolveVoe/resolveHost vorhanden, Override-Signaturen korrekt obfusziert. CI gruen. builds: v23.

**Erwartung v23-Test:** loadExtractor-Crash weg. resolveHost/resolveVoe laufen. Falls VOE-Embed-Seite eine direkte m3u8-URL enthaelt (oder im p.a.c.k.e.r'd/base64-Body), erscheint eine VOE-Quelle in ARVIO. Falls nicht (VOE hat die URL tiefer verschachtelt): resolveVoe findet nichts, genericResolve versucht odysseusa/vidsonic/flyfile. Naechster moeglicher Fehler: Hoster-spezifische Extraktion trifft nicht (VOE aendert Obfuskations-Pattern) -> 0 Quellen aber KEIN Crash. Dann resolveVoe-Logik nachschaerfen (Logcat zeigt `resolveVoe: ... found=false` -> Embed-Seite-HTML analysieren).

### ENTSCHEIDENDE ERKENNTNIS #15 (15.08.2026, v21-TV-Test): DURCHBRUCH + Jackson/kotlin-reflect von R8 gestript

v21-TV-Test (arvio-tv-log-v21-filtered.txt) = MEGA-DURCHBRUCH. Erstmals laeuft der Scraper wirklich:
- Download 1481547 bytes, plugin.load() ausgefuehrt, provider+extractors registriert.
- **Dispatch bindet!** ARVIO ruft UNSERN load()-Override auf (DEX-Patch hat funktioniert!): `TmdbProvider Filmpalast: load({"id":603,"type":"movie"})` -> `load() called with url={"id":603,"type":"movie"}` -> `load: parsed tmdbId=603 isTv=false`.
- **httpGet via java.net FUNKTIONIERT!** `httpGet: https://api.themoviedb.org/3/movie/603 -> 200 (1745 bytes)` + `fetchTmdbMeta: GET -> 200`. v21-Strategiewechsel (app.get entfernt) = Erfolg.
- ABER dann: `load() threw t1: This callable does not support a default call: public constructor TmdbMeta(@JsonProperty id: Int? = ..., title: String? = ..., ...)`. Dann Fallback load(themoviedb.org/movie/603) -> derselbe Fehler -> `both load() paths failed` -> 0 results.

**Root-Cause:** cloudstream3's `AppUtils.parseJson<T>` nutzt Jackson + `jackson-module-kotlin`. jackson-module-kotlin braucht **kotlin-reflect** (`KFunction.callBy()`), um Kotlin-Datenklassen mit Default-Args zu instanziieren. ARVIOs R8-Shrinking hat kotlin-reflect entfernt (gleiche Problemklasse wie frueher kotlin-stdlib: R8 stript alles, was ARVIO selbst nicht direkt nutzt). Jackson kann `TmdbMeta` nicht konstruieren -> `callBy()` schlaegt fehl -> "This callable does not support a default call".

**Fix #15 (IMPLEMENTIERT, 15.08.2026, v22):** Alle parseJson/toJson durch `org.json` ersetzt. Konsistent mit v21-Strategie (JDK/Android built-ins statt ARVIO-provided libs, die geshrinkt sein koennen). org.json (JSONObject/JSONArray) ist Android-built-in, wird von ARVIOs Classloader bereitgestellt, wird NIE obfuscated/stripped, und braucht keine Reflection.
- `TmdbInput` parse: `JSONObject(url)` statt `parseJson<TmdbInput>(url)`.
- `TmdbMeta`: von `data class` mit `@JsonProperty` + Default-Args zu plain `class` (keine Default-Args, keine Jackson-Annotation). Geparst via `JSONObject(res.text).optString(...)`.
- `LoadData.toJson()`: durch hand-built JSON-String `linksToJson(links)` (minimales JSON-Escaping).
- `parseJson<LoadData>(data)`: durch `JSONObject(data).optJSONArray("links")` (parseLinksJson).
- Entfernte Imports: `com.fasterxml.jackson.annotation.JsonProperty`, `AppUtils.parseJson`, `AppUtils.toJson`.
- Verifiziert im Build: FilmPalast.cs3 v22 (1478891 bytes), **0 Jackson/AppUtils-Referenzen** in DEX (v21 hatte noch welche), org.json vorhanden (JSONObject, JSONArray). Override-Signaturen korrekt obfusziert (load=(String,j7/d), loadLinks=(String,Z,x7/l,x7,l,j7/d), search=(String,j7/d)). CI gruen. builds: v22.

**Erwartung v22-Test:** Jackson-Fehler weg. load() parst TMDB-Meta, sucht Filmpalast, matcht, baut LoadResponse. Naechster moeglicher Fehler: Scraper-Logik (Jsoup-Selektoren, Hoster-Extraktion, Bot-Schutz) oder naechste ARVIO-lib die geshrinkt ist (z.B. newMovieLoadResponse/newTvSeriesLoadResponse wenn sie intern Jackson nutzen - aber die sind ARVIO->ARVIO intern, sollten wie ARVIOs eigene Scraper funktionieren).

### ENTSCHEIDENDE ERKENNTNIS #14 (15.08.2026, v20-TV-Test-Log arvio-tv-log-v20-filtered.txt): k7.a ClassCastException = app.get fuer externe Plugins broken
v19-fix (ContinuationInterceptor.Key fieldref) WIRKT: NoSuchFieldError WEG. v20-fix (okhttp3/Interceptor->rb/c0) WIRKT: NoSuchMethodError get$default WEG. load() laeuft, TMDB parsed ("parsed tmdbId=603 isTv=false"). ABER dann:
`ClassCastException: k7.a cannot be cast to com.lagradost.nicehttp.NiceResponse` in fetchTmdbMeta (unser Code ruft app.get auf).
- k7/a ist ein 3-Wert-Enum (super=Enum, fields i/l/m, valueOf/values) вҖ” KEIN HttpResponse/Coroutine-Typ.
- app.get ist eine ARVIO-provided suspend Extension. Aus unserem externen .cs3-Plugin aufgerufen, resume-t die coroutine-Machinery nicht korrekt -> gibt stray Enum statt NiceResponse zurueck.
- Das ist dasselbe grundsaetzliche Problem wie Erkenntnis #7 (R8 obfuscated kotlin.coroutines.Continuation -> j7/d): unsere Plugin-suspend-Aufrufe an ARVIOs suspend-Funktionen (app.get) sind gestoert, weil die Coroutine-Machinery zwischen externem Plugin und obfuscated ARVIO-Runtime nicht zusammenpasst.
- okhttp3 hat ~100 obfuscated Klassen -> Whack-a-Mole endlos -> Strategiewechsel noetig (Fix #13).

### ENTSCHEIDUNG / FIX #13 (15.08.2026, v21): HTTP komplett auf java.net + jsoup umgestellt (app.get entfernt)
Statt jede obfuscated okhttp/coroutine-Type einzeln zu jagen: ALLE unsere HTTP-Aufrufe von ARVIOs suspend `app.get` (NiceHttp/okhttp) auf plain java.net.HttpURLConnection umgestellt.
- `httpGet(url, params, headers): HttpResp(code, text)` Helper: java.net.HttpURLConnection (JDK, NIE obfuscated), connectTimeout/readTimeout = NET_TIMEOUT_MS, instanceFollowRedirects, UTF-8 body. Non-suspend.
- jsoup.parse(text) statt res.document вҖ” jsoup von ARVIO unobfuscated kept (330 Klassen verifiziert), Jsoup.parse(String) verfuegbar.
- Interne Helfer non-suspend gemacht: fetchTmdbMeta, searchFilmpalast, genericResolve (nutzen httpGet). AUSNAHME suspend geblieben: buildMovieResponse + buildSeriesResponse (rufen newMovieLoadResponse/newTvSeriesLoadResponse auf, die selbst suspend cloudstream3-API sind вҖ” ARVIO->ARVIO intern, funktionieren wie ARVIOs eigene Scraper).
- loadExtractor (suspend, ARVIO-provided) bleibt in loadLinks вҖ” laeuft ARVIO->ARVIO intern.
- withTimeoutOrNull entfernt (java.net timeouts statt Coroutinen-Timeout).
- Konsequenz: load()/loadLinks()/search() bleiben suspend (cloudstream3 API-Vertrag, DEX-patched j7/d) aber haben keine eigenen inneren app.get-suspend-Aufrufe mehr -> coroutine state machine trivial -> obfuscated-Type-Breakage umgangen. build*Response+loadExtractor bleiben suspend-Aufrufe an ARVIO (wie ARVIOs eigene Scraper, sollten funktionieren).
- v21 auf builds (status=1). CI gruen. AUF TV/HANDY-TEST AUSSTEHEND.
- Verifiziert im Build: plugin-classes patched 66 Utf8 (weniger als v20's 120, weil app.get/okhttp-Referenzen entfernt), stdlib patched 7740.

### ARVIO v1.9.994 (15.08.2026 veroeffentlicht) вҖ” NUTZER HAT UPGEDATET (TV + HANDY)
- VERIFIZIERT: Obfuskations-Map UNVERAENDERT (j7/d=Continuation, rb/c0=okhttp3.Interceptor) -> unsere DEX-Patches kompatibel.
- NUTZER HAT auf v1.9.994 geupdatet (TV + Handy). WICHTIG: unsere Patches/Analyse basieren auf 1.9.983-DEX, aber da Obfuskation identisch, gilt alles weiter.
- Neue nuetzliche Features fuer uns: "Refresh Add-ons" (#511) вҖ” ABER Nutzer korrigiert: das ist fuer Stremio-Add-ons, NICHT fuer .cs3-Plugins -> fuer Plugin-Update weiterhin Repo loeschen+neu hinzufuegen.
- "Fixed release dependency injection for sideload builds" (#525).
- Release-Notes erwaehnen NICHT den Cloudstream3-.cs3-Plugin-Obfuskations-Bug -> Kernproblem von ARVIO nicht geloest.
- Touch-Bug im Add-Repo-Dialog auf Handy: NUTZER BESTAETIGT BEHOBEN in 1.9.994 ("ich kann jetzt Plugin-Repo eintragen und bestaetigen, das ist mega"). -> Testen kuenftig auch auf dem Handy moeglich (UI geht jetzt).

### LOGCAT AM HANDY (neu ab 15.08.2026) вҖ” Setup dokumentiert in docs/handy-logcat-ladb-termux.md
Nutzer kann ab sofort auch auf dem HANDY testen (UI-Bug behoben). Fuer Logcat ohne Laptop: LADB (einmalig) + Termux.
- Einmalig: LADB pairen (Drahtloses Debugging + Pairing-Code), dann `pm grant com.termux android.permission.READ_LOGS` in LADB-Shell -> Termux darf logcat lesen. Ueberlebt Neustarts.
- Danach nur Termux: `logcat -c` -> ARVIO-Suche -> `logcat -d | grep -iE "Filmpalast|ArvioAddon|ExtExt|Error|No.API|load" > /sdcard/arvio-log-v21.txt` -> Datei teilen.
- KEIN WLAN/Netz noetig (Loopback localhost). WLAN-Schalter AN lassen genuegt.
- LADB-Pairing zickig (60s Timer) -> Fallback Shizuku (stabiler) oder TV+Laptop WLAN-ADB.
- Alternativ-Setup: Shizuku (gratis) statt LADB, dann Logcat-Reader-App die Shizuku nutzt.
- Nutzer moechte Setup SPAETER machen (Session-Wechsel), hat Termux schon, LADB frueher nicht geklappt.
- Vollstaendige Anleitung: docs/handy-logcat-ladb-termux.md
- ACHTUNG: ARVIO auf Handy = gleiche sideload-APK wie TV -> gleiche Obfuskation -> Tests auf Handy repraesentativ fuer TV.

### NAECHSTE SCHRITTE (Stand 15.08.2026, fuer naechste Session)

**MEILENSTEIN ERREICHT: v25 liefert Filmpalast-Quellen in ARVIO, Playback startet!** (odysseusa.cc master.m3u8, `1 links collected`, `returned 1 results`, Nutzer bestaetigt: Video startet). Naechstes Ziel = Quellenvielfalt (weitere Hoster).

### BESTE METHODE FUER WEITERE HOSTER (entscheidend, 15.08.2026)

**VIER Methoden verglichen, Empfehlung = D (resolveurl Python) als PRIMAER-Quelle + B/C zum Testen:**

**Methode D: resolveurl Python-Resolver (BESTE METHODE, Goldschatz!) вӯҗвӯҗвӯҗ**
- `Gujal00/ResolveURL` (GitHub) = Fork von tknorris UrlResolver, von Kodi-Community gepflegt, **227 fertige Hoster-Resolver in lesbarem Python**!
- Jeder Resolver ist eine ~50-80 Zeilen Python-Datei in `lib/resolveurl/plugins/<hoster>.py` mit der KOMPLETTEN Extraktionslogik: Regex, API-Endpoints, Decrypt-Methoden, Header-Requirements.
- Verfuegbare Resolver fuer UNSERE Hoster (live verifiziert 15.08.2026): `voesx.py`, `vidsonic.py`, `supervideo.py`, `firestream.py`, `doodstream.py`, `streamtape.py` + 221 weitere.
- xStream (michaz) nutzt genau diese resolveurl-Library: `import resolveurl as resolver; resolver.resolve(url)` (verifiziert in xstreamscraper/scraper.py:259 + seizu/plugin.video.filmpalast.ex/default.py:343).
- Pro: LESBARER Python-Code (kein Bytecode-Decompiling noetig!), alle Decrypt-Methoden sehen als klare Python-Funktionen, alle Regexes direkt lesbar. **Die komplette Hoster-Extraktionslogik existiert schon fertig** вҖ” wir muessen sie nur von Python nach Kotlin portieren.
- Con: Python -> Kotlin Portierung noetig (aber straightforward: requests->java.net, re->kotlin.Regex, json->org.json).
- Best for: ALLE Hoster. Das ist die PRIMAER-Quelle fuer Hoster-Extraktionslogik.

**Methode B: Built-in cloudstream3-Extractoren dekompilieren** (Zweitbeste, Fallback) вӯҗ
- cloudstream3.jar hat Extractoren fuer Voe, Supervideo, VidHidePro, Firestream, FileMoon вҖ” aber Bytecode (javap), schwerer zu lesen als Python.
- Nuetzlich als Kreuzcheck falls resolveurl veraltet ist, aber resolveurl ist aktueller (227 Plugins, 2026 gepflegt).

**Methode C: Direkt curl vom Server** (zum Testen)
- Schnelle Iteration, kein Geraet noetig. Bei Bot-Schutz nur Challenge-Seite.

**Methode A: Logcat Debug-Logging** (Fallback bei Bot-Schutz)
- Nur noetig wenn curl keine echte Seite liefert (DDoS-Guard/Cloudflare). Geraet bekommt echte Seite (118949B bei VOE).

**EMPFOHLENER WORKFLOW PRO HOSTER (aktualisiert):**
1. `cat /tmp/resolveurl/script.module.resolveurl/lib/resolveurl/plugins/<hoster>.py` -> Algorithmus in lesbarem Python lesen. **Das ist Schritt 1 вҖ” alles steht da.**
2. Python-Logik nach Kotlin portieren (java.net + kotlin.Regex + org.json).
3. Mit `curl` vom Server testen (wenn kein Bot-Schutz: funktioniert sofort).
4. Falls Bot-Schutz: Debug-Logging + Geraet-Test.

### RESOLVEURL-REPOS (geklont nach /tmp, 15.08.2026)

**AKTUALITAETS-CHECK durchgefuehrt (15.08.2026) вҖ” Gujal00/ResolveURL ist die aktuellste Quelle:**

| Repo | Letzter Commit | Status |
|---|---|---|
| **Gujal00/ResolveURL** | 12.08.2026 (3 Tage her) | вӯҗ AKTUELL вҖ” nutze diese |
| jsergio123/script.module.resolveurl | 21.02.2020 (6 Jahre alt) | VERALTET вҖ” nicht nutzen |
| Gujal00/smrzips | Release-Repo (zips only, v5.1.206) | Nur Zips, kein Quellcode |

- **Gujal00/ResolveURL** = `https://github.com/Gujal00/ResolveURL` (Hauptquelle, 227 Plugins, geklont nach `/tmp/resolveurl`). Pfad: `script.module.resolveurl/lib/resolveurl/plugins/<hoster>.py`. Version 5.1.206. Letzter Commit 12.08.2026 ("voesx-prefer-hls-settings"). Sehr aktiv gepflegt (commits fast woechentlich).
- `jsergio123/script.module.resolveurl` = VERALTET (letzter Commit 2020, 6 Jahre alt). Nicht nutzen.
- `Gujal00/smrzips` = Release-Repo (nur .zip-Dateien, kein Quellcode). Version 5.1.206 als Zip. Der Quellcode ist im ResolveURL-Repo.
- **michaz1988/michaz1988.github.io** = `https://github.com/michaz1988/michaz1988.github.io` (michaz Repo, geklont nach `/tmp/michaz-repo`). Enthaelt `script.module.xstreamscraper` (Filmpalast-Scraper, geklont nach `/tmp/xstreamscraper`) + `plugin.video.xship` (xStream-Nachfolger) + `repository.gujal` (verweist auf Gujal00/smrzips fuer resolveurl). michaz hostet KEIN eigenes resolveurl вҖ” er nutzt Gujal00's resolveurl ueber das repository.gujal Dependency.
- **seizu/plugin.video.filmpalast.ex** = `https://github.com/seizu/plugin.video.filmpalast.ex` (Filmpalast Kodi-Plugin, geklont nach `/tmp/seizu-filmpalast`). Nutzt resolveurl fuer Hoster-Aufloesung.
- **Fazit:** Gujal00/ResolveURL ist EINZIGE aktuell gepflegte resolveurl-Quelle. xStream/michaz nutzt genau diese. Wir nutzen sie auch. Keine Alternative noetig.
- WICHTIG: Diese Repos sind nach /tmp geklont (nicht persistent ueber Resets). Bei naechster Session ggf. neu klonen: `git clone --depth 1 https://github.com/Gujal00/ResolveURL.git /tmp/resolveurl`

### VOE-EXTRACTOR: KOMPLETTE LOGIK (aus resolveurl voesx.py, lesbar!)

VOE-Extraktionsalgorithmus (aus `voesx.py` `get_media_url()` + `voe_decode()`):
1. GET `https://voe.sx/e/<media_id>` mit `User-Agent: <random>` (mobile UA OK).
2. Redirect-Loop: solange `'const currentUrl' in html`: Regex `window\.location\.href\s*=\s*'([^']+)'` -> neuer GET auf redirect-URL.
3. Pattern 1 (primaer): `json">\["([^"]+)"]</script>\s*<script\s*src="([^"]+)` -> extrahiert (a) encoded string + (b) JS-Datei-URL.
4. GET JS-Datei-URL -> `re.search(r"(\[(?:'\W{2}'[,\]]){1,9})", html2)` -> LUT (lookup table) fuer Decode.
5. **`voe_decode(ct, luts)`** вҖ” die Decrypt-Methode (Python, lesbar!):
   ```python
   def voe_decode(ct, luts):
       lut = [''.join([('\\'+x) if x in '.*+?^${}()|[]\\' else x for x in i]) for i in luts[2:-2].split("','")]
       txt = ''
       for i in ct:  # ROT-basierte Buchstaben-Verschiebung
           x = ord(i)
           if 64 < x < 91: x = (x - 52) % 26 + 65     # Grossbuchstaben
           elif 96 < x < 123: x = (x - 84) % 26 + 97   # Kleinbuchstaben
           txt += chr(x)
       for i in lut: txt = re.sub(i, '', txt)  # LUT-Elemente entfernen
       ct = helpers.b64decode(txt)               # Base64 decode
       txt = ''.join([chr(ord(i) - 3) for i in ct])  # Caesar -3
       txt = helpers.b64decode(txt[::-1])       # Base64 decode reversed
       return json.loads(txt)  # JSON mit 'file', 'source', 'direct_access_url', 'captions'
   ```
   Ergebnis-JSON: `{"file": "<mp4-url>", "source": "<m3u8-url>", "direct_access_url": "<mp4-url>", "captions": [...]}`.
6. Quelle waehlen: `source` (m3u8) ODER `file`/`direct_access_url` (mp4). Header: `verifypeer: false`.
7. Pattern 2 (Fallback falls Pattern 1 nicht matcht): `scrape_sources` mit Regexes:
   - `mp4["']:\s*["'](?P<url>[^"']+)["'],\s*["']video_height["']:\s*(?P<label>[^,]+)`
   - `hls':\s*'(?P<url>[^']+)'`
   - `hls":\s*"(?P<url>[^"]+)",\s*"video_height":\s*(?P<label>[^,]+)`

**VOE-Domain-Liste (voesx.py):** 200+ Mirror-Domains! `voe.sx`, `voe-unblock.com`, `donaldlineelse.com`, `kinoger.ru`, `smoki.cc`, `ogladaj.me` usw. Wichtig fuer resolveHost-Dispatch вҖ” nicht nur `voe.sx` checken, sondern alle Mirrors.

**Kotlin-Portierung fuer resolveVoe (Plan):**
1. GET embed-URL (bereits vorhanden).
2. Redirect-Loop mit `window.location.href` Regex.
3. Regex `json">\["([^"]+)"]</script>\s*<script\s*src="([^"]+)` -> (encoded, jsUrl).
4. GET jsUrl -> LUT-Regex `(\[(?:'\W{2}'[,\]]){1,9})` -> luts.
5. `voeDecode(ct, luts)` in Kotlin nachbauen:
   - LUT-Parsing: `luts[2:-2].split("','")` -> escaped regex patterns.
   - ROT-Verschiebung: Grossbuchstaben `(x-52)%26+65`, Klein `(x-84)%26+97`.
   - LUT-Elemente mit `Regex.replace(input, "")` entfernen.
   - `java.util.Base64.decoder.decode(txt)` -> String.
   - Caesar -3: `chr(ord(c) - 3)` fuer jedes Zeichen.
   - Base64 decode reversed: `String(reversed)`, dann Base64 decode.
   - `JSONObject(result)` -> `optString("source")` (m3u8) / `optString("file")` (mp4).
6. `emitLink("VOE", streamUrl, callback)` mit Header `verifypeer: false` (in ExtractorLink headers).
7. Fallback: direkte hls/mp4 Regex-Suche im HTML (Pattern 2).

### VIDSONIC-EXTRACTOR: KOMPLETTE LOGIK (aus resolveurl vidsonic.py, lesbar!)

VidSonic-Algorithmus (aus `vidsonic.py` `get_media_url()`):
1. GET `https://vidsonic.net/e/<media_id>` mit Headers: `User-Agent`, `Referer: https://vidsonic.net/`, `Origin: https://vidsonic.net`.
2. Regex `const\s*_0x1\s*=\s*'([^']+)'` -> hex-encoded String (mit `|` als Trennzeichen).
3. `binascii.unhexlify(hex_string.replace('|', '')).decode()[::-1]` -> hex-decode + reverse = direkte Video-URL!
4. Return URL + Header (Referer/Origin).

**Kotlin-Portierung:** `hexString.replace("|","").chunked(2).map { it.toInt(16).toChar() }.joinToString("").reversed()` -> direkte URL. Sehr einfach!

### FIRESTREAM-EXTRACTOR: KOMPLETTE LOGIK (aus resolveurl firestream.py, lesbar!)

FireStream-Algorithmus:
1. GET `https://firestream.to/e/<media_id>` mit `User-Agent`.
2. Regex `id="token-blob"[^>]+>([^<]+)` -> token blob.
3. POST `https://firestream.to/api/videos/<media_id>/resolve` mit JSON `{"blob": "<token>"}` + Headers (Referer, Origin).
4. JSON-Response `signedVideoUrl` = direkte Video-URL.

**Kotlin-Portierung:** httpGet embed -> Regex token-blob -> httpPost API -> JSONObject `signedVideoUrl`. Aehnlich wie odysseusa-Pattern!

### SUPERVIDEO-EXTRACTOR: KOMPLETTE LOGIK (aus resolveurl supervideo.py, lesbar!)

SuperVideo-Algorithmus:
1. GET `https://supervideo.cc/embed-<media_id>.html` mit `User-Agent`, `Referer`.
2. `helpers.get_packed_data(html)` -> p.a.c.k.e.r'd JS entpacken.
3. Regex `{\s*file:\s*"(?P<url>[^"]+)"\s*}` -> Video-URL.
4. Fallback: `download_video.+?'([^']+)','([^']+)'\)` -> download API -> `dl?op=download_orig&id=...&mode=...&hash=...` -> `btn_direct-download` href.

### HOSTER-PRIORITAETEN (aktualisiert mit resolveurl-Verfuegbarkeit, 15.08.2026)

| Hoster | resolveurl Plugin? | Bot-Schutz? | Algorithmus | Aufwand | Prioritaet |
|---|---|---|---|---|---|
| odysseusa.cc | Nein (eigene API) | Nein | Ja (POST /api/stream) | ERLEDIGT v24 | вң… Done |
| **voe.sx** | Ja (voesx.py) | Ja (DDoS-Guard) | Ja (voe_decode, ROT+Base64+Caesar) | Mittel (Decrypt portieren) | **Prio 1** вӯҗ |
| **vidsonic.net** | Ja (vidsonic.py) | Nein | Ja (hex+reverse, TRIVIAL!) | **Niedrig** (3 Zeilen!) | **Prio 2** вӯҗ |
| firestream.to | Ja (firestream.py) | ? | Ja (token-blob POST API) | Niedrig-Mittel | Prio 3 |
| supervideo.cc | Ja (supervideo.py) | ? | Ja (packed JS + file regex) | Mittel | Prio 3 |
| vidhide.com | ? (vidhd.py?) | ? | ? (dekompilieren) | Mittel | Prio 3 |
| filemoon.sx | ? (filemoon?) | ? | ? (dekompilieren) | Mittel | Prio 3 |
| flyfile.app | Nein | Ja (Cloudflare) | Nein | Hoch | Niedrig |

**VIDSONIC ist jetzt Prio 2** weil der resolveurl-Code zeigt: es ist TRIVIAL (hex-decode + reverse = direkte URL, 3 Zeilen Kotlin)! Voher als "schwer obfusziertes JS" bewertet вҖ” aber resolveurl hat schon die Loesung.

### NAECHSTE SESSION: ERST QUALITAET + SPEED, DANN NEUE HOSTER (Nutzer-Entscheidung 15.08.2026)

**Nutzer hat entschieden: Erst alles richtig einstellen (Qualitaet + Speed), BEVOR neue Hoster hinzugefuegt werden.**

Reihenfolge (Prio 1-3 vor neuen Hostern):

#### PRIO 1: Qualitaetserkennung вҖ” echte Aufloesung senden statt Unknown (notwendig fuer Auto-Play)

**Problem:** Aktuell senden wir `Qualities.Unknown.value` (400) вҶ’ ARVIO zeigt "Unknown" вҶ’ Auto-Play-Score = 0 вҶ’ ARVIOs Auto-Play zaudert (2s Timeout, kein Score-bewerteter Stream). Nutzer will echte Qualitaet sehen (1080p, 720p, 4K) UND Auto-Play soll funktionieren.

**ARVIO Auto-Play-Logik (verifiziert in AutoPlaySourcePlanner.kt, v1.9.994):**
- `qualityScoreForAutoPlay(stream)`: prueft stream.quality-String auf "2160p"/"4K"вҶ’4, "1080p"вҶ’3, "720p"вҶ’2, "480p"вҶ’1, sonst 0.
- `bestAutoPlayStream`: filtert streams mit score >= minQualityThreshold (default "Any"вҶ’0), sortiert absteigend.
- `AUTOPLAY_MAX_WAIT_MS = 2000` (2s Timeout). Wenn kein Score-bewerteter Stream in 2s: Auto-Play bricht ab вҶ’ Quellenauswahl.
- Unsere `Qualities.Unknown` (400) вҶ’ `getStringByInt(400)` = "" вҶ’ `ifEmpty{null}` вҶ’ `toStreamSource`: quality="Unknown" вҶ’ score=0. Auto-Play ignoriert uns effektiv.

**Qualities-Enum-Werte (verifiziert aus cloudstream3.jar Bytecode):**
```
Unknown = 400, P144 = 144, P240 = 240, P360 = 360, P480 = 480,
P720 = 720, P1080 = 1080, P1440 = 1440, P2160 = 2160
```
`getStringByInt(value)`: 0вҶ’"Auto", 400вҶ’""(emptyвҶ’nullвҶ’"Unknown"), 2160вҶ’"4K", sonstвҶ’"<value>p" (z.B. 1080вҶ’"1080p").

**Implementierung (~50 Zeilen):**
1. `emitLink` erweitern: vor dem Emit bei m3u8-URLs das HLS-Manifest fetchen (httpGet, ~1-2 KB).
2. Aus dem Manifest `#EXT-X-STREAM-INF:BANDWIDTH=...,RESOLUTION=1920x1080` parsen.
3. Auf Qualities-Wert mappen: height >= 2160 вҶ’ P2160 (4K), >= 1080 вҶ’ P1080, >= 720 вҶ’ P720, >= 480 вҶ’ P480, sonst Unknown.
4. Bei mp4/unknown: Default P720 (besser als Unknown вҖ” mp4 ist meist DVD/HD-Qualitaet).
5. `emitLink` bekommt quality-Parameter, nutzt ihn im ExtractorLink statt `Qualities.Unknown.value`.
6. ARVIO zeigt dann "1080p" / "720p" вҶ’ Auto-Play-Score 3/2 вҶ’ Auto-Play erkennt Quelle вҶ’ spielt direkt ab.

**Aufloesung aus m3u8 parsen (Code-Skizze):**
```kotlin
private fun detectQuality(url: String): Int {
    if (!url.contains(".m3u8")) return Qualities.P720.value // mp4 default
    return try {
        val res = httpGet(url, headers = mapOf("Range" to "bytes=0-4096")) // nur Header
        val m = Regex("RESOLUTION=(\\d+)x(\\d+)").find(res.text)
        val h = m?.groupValues?.get(2)?.toIntOrNull() ?: return Qualities.P720.value
        when {
            h >= 2160 -> Qualities.P2160.value
            h >= 1080 -> Qualities.P1080.value
            h >= 720 -> Qualities.P720.value
            h >= 480 -> Qualities.P480.value
            else -> Qualities.P360.value
        }
    } catch (_: Throwable) { Qualities.P720.value }
}
```
Wichtig: Range-Header `bytes=0-4096` damit wir nur die ersten 4KB laden (m3u8 hat RESOLUTION in den ersten Zeilen). Falls Server kein Range unterstuetzt: volle Datei (klein, ~5-20KB).

**Nachteile:** +100ms pro m3u8-Stream (ein zusaetzlicher GET). Aber: noetig fuer Auto-Play, und wenn wir Speed-Optimierungen (Prio 2+3) machen, gleicht sich das aus.

#### PRIO 2: TMDB-Cache (~15 Zeilen, gratis Ersparnis)
- `private val tmdbCache = java.util.concurrent.ConcurrentHashMap<Int, TmdbMeta>()`
- In `fetchTmdbMeta`: erst `tmdbCache[tmdbId]` checken, falls vorhanden вҶ’ return (0ms statt 300ms).
- Bei Treffer: `tmdbCache[tmdbId] = meta`.
- ConcurrentHashMap = JDK, nie obfuscated, thread-safe.
- Ersparnis: ~300ms bei wiederholter Suche (selber Film). Erste Suche = Cache-Miss = keine Ersparnis.
- Nachteile: keine echte. Memory: vernachlaessigbar (einige Eintraege).

#### PRIO 3: Hoster parallel auflГ¶sen (~30 Zeilen, bringt unter 2s Auto-Play-Timeout)
- Aktuell: `for (link in links) { resolveHost(fixed, callback) }` вҖ” SEQUENTIELL. 3 Hoster = ~900ms+350ms load() = ~1.6s + 100ms quality = ~2.6s.
- Neu: `java.util.concurrent.Executors.newFixedThreadPool(3)`, jeder `resolveHost` als Callable, mit `invokeAll(2s timeout)` sammeln.
- Alle Hoster parallel вҶ’ statt 900ms nur ~350ms (laengster einzelner).
- Ersparnis: ~500ms bei 3 Hostern.
- Nachteile: Thread-Pool-Komplexitaet (mit `finally { pool.shutdown() }` lГ¶sbar). Thread-Sicherheit: callback ist nur eine Funktion (`(ExtractorLink) -> Unit`), sollte thread-safe sein (ARVIO sammelt in einer Thread-safe Collection). java.util.concurrent = JDK, nie obfuscated.
- Code-Skizze:
```kotlin
val pool = Executors.newFixedThreadPool(minOf(links.size, 3))
try {
    val futures = links.mapNotNull { fixUrlNull(it) }.map { link ->
        pool.submit<Boolean> { resolveHost(link, callback) }
    }
    futures.forEach { f -> try { f.get(2, TimeUnit.SECONDS) } catch (_: Throwable) {} }
} finally { pool.shutdown() }
```

#### KOMBINIERTE WIRKUNG:
```
Aktuell:  ~2.6s (3 Hoster sequenziell, keine Qualitaet, kein Cache)
Prio 1-3: ~1.8s (3 Hoster parallel, TMDB-Cache, + Qualitaetserkennung)
          вҶ’ unter 2s Auto-Play-Timeout вҶ’ Auto-Play funktioniert!
```

#### PRIO 4 (danach): Neue Hoster (vidsonic, VOE, firestream)
- Siehe unten "HOSTER-PRIORITAETEN"-Tabelle. Reihenfolge: vidsonic (trivial!) вҶ’ VOE (Prio 1 fuer Haeufigkeit) вҶ’ firestream.
- Jeder Hoster: resolveurl Python lesen (Methode D) вҶ’ Kotlin portieren вҶ’ curl testen.

**Prio 4 - GitHub-Issue bei ARVIO (noch NICHT eroeffnen):**
Siehe unten "Entscheidung Nutzer: GitHub-Issue bei ARVIO professionell vorbereiten". Drei klare Bugs:
1. R8 obfuscated kotlin.coroutines.Continuation + okhttp3 + stript kotlin-reflect + stript DefaultConstructorMarker-ctors -> externe .cs3-Plugins koennen suspend-Overrides, app.get, loadExtractor, Jackson-JSON UND Default-Arg-Konstruktoren nicht nutzen (Haupt-Bug, Erkenntnis #7+#13+#14+#15+#16+#18). Trotzdem haben wir es durch massive Workarounds zum Laufen gebracht (DEX-Patching, java.net-HTTP, org.json, primary-ctor, eigene Extractoren) -> beweist den Bug eindrucksvoll.
2. Cloud-Sync-Restore laedt .cs3-Dateien nicht herunter (Erkenntnis #1).
3. (ehemals Touch-Bug Add-Repo-Dialog - BEHOBEN in 1.9.994, Nutzer bestaetigt).
AI-Disclosure-Pflicht bei Issue/Kommentar: "created by an AI agent (OpenHands) on behalf of [user]".

### EMPFOHLENE SCHRITTWEISE STRATEGIE (Stand 16.08.2026, nach Serienstream-Modul v31)

**VORBEMERKUNG вҖ” Prio 1-3 sind bereits erledigt:** Bei Durchsicht des FilmPalast-Codes (v30) zeigt sich, dass die AGENTS.md-Prio-Liste Prio 1-3 bereits implementiert sind (und ins Serienstream-Modul v31 Гјbernommen wurden):
- Prio 1 (Qualitaetserkennung): `detectQuality()` in beiden Providern вҖ” fetcht m3u8-Manifest, parst `RESOLUTION=WxH`, mappt auf hoechste Variante (P2160/P1080/P720/P480/P360), mp4-Default P720. ARVIO zeigt echte Aufloesung, Auto-Play-Score > 0.
- Prio 2 (TMDB-Cache): `tmdbCache = ConcurrentHashMap<Int, TmdbMeta>()` in beiden Providern.
- Prio 3 (parallele Hoster-Aufloesung): `Executors.newFixedThreadPool(minOf(n,4))` mit 2-3s Future-Timeouts in beiden Providern.
вҶ’ Meilenstein вҖһunter 2s Auto-Play-Timeout" auf Code-Ebene erreicht. Was bleibt = TV-Validierung + Prio 4.

**REIHENFOLGE (aktualisiert):**

#### PHASE 0 вҖ” Serienstream v32-TV-Test (JETZT, naechste Session, entscheidend)
Warum vor Prio 4: Das gerade gebaute Serienstream-Modul (v32) mit ALTCHA-PoW-Solver ist der kritische Pfad. v31 zeigte der Scraper laeuft komplett durch, aber `/r?t=`-Redirects blockten. v32 loest die ALTCHA-Challenge. Ob der POST /r-Flow am TV durchkommt (am Laptop schlaegt er fehl wegen 403-Preflight), entscheidet, ob wir Serienstream-Quellen haben.

**Schritt-fuer-Schritt fuer den Nutzer (am TV, mit Handy+Termux):**
1. Auf dem Handy (Termux): zuerst das aktualisierte Log-Skript holen (wichtig вҖ” das alte filtert nicht Serienstream!):
   ```
   curl -sL https://raw.githubusercontent.com/ReichiMD/Arvio-Addon/main/docs/save-tv-log.sh -o ~/save-tv-log.sh && chmod +x ~/save-tv-log.sh
   ```
2. In ARVIO am TV: **Plugins & Extensions вҶ’ Ventix Arvio Addon вҶ’ Repo LвҖ“ДҸвҖңДҸвҖ“ГӯВ¬вҲӮSCHEN** (beide: FilmPalast + Serienstream).
   - WICHTIG: Repo loeschen, nicht nur updaten! Sonst laedt ARVIO die neue .cs3 nicht herunter (Erkenntnis #1: Cloud-Sync/Profil laedt keine .cs3-Dateien).
3. In ARVIO: **Add Repository DIREKT auf dem Geraet** (NICHT Cloud-Sync!) вҶ’ URL eingeben:
   ```
   https://raw.githubusercontent.com/ReichiMD/Arvio-Addon/main/repo.json
   ```
   вҶ’ ARVIO laedt beide .cs3-Dateien herunter (FilmPalast v30 + Serienstream v32).
4. In ARVIO: **Serienstream-Scraper einschalten** (Toggle auf AN).
5. Auf dem Handy (Termux): `adb logcat -c` (Log-Puffer leeren).
6. In ARVIO am TV: eine Serie suchen, z.B. **вҖһSilo"** вҶ’ auf eine Episode gehen вҶ’ **вҖһnach Quellen suchen"** вҶ’ 15 Sekunden warten.
7. Auf dem Handy (Termux): `~/save-tv-log.sh v32` aufrufen (liest Logcat aus, filtert nach Serienstream+ALTCHA, speichert in Downloads).
8. Log-Datei an die AI weiterleiten: Dateimanager вҶ’ Downloads вҶ’ arvio-logs вҶ’ `arvio-tv-log-v32-filtered.txt` вҶ’ lange druecken вҶ’ Teilen вҶ’ in den Chat hochladen (Plus-Symbol вҶ’ Datei).

**Was die AI im Log sucht (entscheidend):**
- `solveAltcha: PoW solved, n=...` вҶ’ ALTCHA-PoW funktioniert! (die вҖһZahlensuche" war erfolgreich)
- `redirectGate: POST /r -> 200` вҶ’ POST erfolgreich (Serienstream hat die Loesung akzeptiert)
- `redirectGate: resolved to https://voe.sx/...` (oder doodstream/streamtape/etc.) вҶ’ **ERSTE SERIENSTREAM-QUELLE!** рҹҺҜ Ziel erreicht!
- `redirectGate: server rejected ALTCHA: Das hat leider nicht geklappt...` вҶ’ PoW akzeptiert aber Token ungГјltig вҶ’ Hypothese: Turnstile (CAPTCHA) fehlt вҶ’ dann Fallback-Suche
- `redirectGate: verify-init -> HTTP 403` вҶ’ DDoS-Guard blockt die API am TV
- `solveAltcha: no solution found` вҶ’ Challenge nicht loesbar
- `0 links collected` вҶ’ keine Quelle emittiert (Flow irgendwo gescheitert)
- Gar kein `Serienstream`-Eintrag вҶ’ Scraper wird nicht aufgerufen (Enable/Routing/Download-Problem)

#### PRIO 4a вҖ” Weitere FilmPalast-Hoster (niedrig, bei Bedarf)
Reihenfolge laut HOSTER-PRIORITAETEN-Tabelle: VOE (вң… v30), vidsonic (вң… v29), firestream (вң… v30). Verbleibend: Supervideo/VidHide/FileMoon nur falls neue Filmpalast-Hoster-Domains auftreten. Workflow: resolveurl Python lesen -> Kotlin portieren -> curl testen.

#### PRIO 4b вҖ” Serienstream Option B (nur falls Phase 0 scheitert)
Nur falls TV-Test zeigt, dass `/r?` blockiert bleibt:
1. **GitHub-Recherche** (Task 6, Nutzer explizit angefordert): Suche вҖһddos-guard bypass", вҖһddos-guard js challenge solver", cloudscraper-Aequivalent fuer DDoS-Guard (nicht Cloudflare). Repos: `VeNoMouS/cloudscraper` (Cloudflare, nicht DDG), dedizierte DDG-Solver.
2. Falls Solver gefunden: Algorithmus nach Kotlin portieren (java.net + Regex + ggf. JS-Engine вҖ” Achtung: JS-Engine koennte R8-Probleme verursachen wie app.get).
3. Falls kein Solver: **view.js-Challenge reverse-engineeren** (offline): Challenge-Seite fetchen, JS deobfuszieren, Token-Berechnung nachvollziehen, in Kotlin nachbauen.
4. In `httpGet` einbauen: bei 403+DDoS-Guard -> Solver -> retry.

#### PRIO 4c вҖ” GitHub-Issue bei ARVIO (nach Phase 0, professionell)
Status: NOCH NICHT eroeffnen вҖ” erst nach Serienstream-TV-Test, damit das Issue Beweise enthaelt. Drei dokumentierte Bugs:
1. R8 obfuscated Continuation/okhttp3 + stript kotlin-reflect + DefaultConstructorMarker (Haupt-Bug, Erkenntnis #7+#13+#14+#15+#16+#18).
2. Cloud-Sync-Restore laedt .cs3-Dateien nicht herunter (Erkenntnis #1).
3. ~~Touch-Bug~~ вҖ” behoben in 1.9.994.
Vorgehen (Vorbild Issue #537): Environment -> Summary -> Steps to reproduce -> Expected vs. Actual -> Root cause (mit Code-Verweis) -> Proposed fix -> References (#459, #273, #500) -> Logcat-Auszug -> **AI-Disclosure**. Optional: Fork + Fix-PR (ProGuard-Regel fuer `kotlin.coroutines.Continuation` + `kotlin.jvm.functions.*` unobfusziert).

**ZUSAMMENFASSUNG:** Flaschenhals = Phase 0 (TV-Test). Alles Weitere (Option B, Issue) haengt vom TV-Test-Befund ab. Sobald der Nutzer den TV erreichter kann, sollte Phase 0 an erster Stelle stehen.

### NEUE ARVIO-VERSION v1.9.994 (15.08.2026)
ARVIO v1.9.994 heute veroeffentlicht. VERIFIZIERT: Obfuskations-Map UNVERAENDERT (j7/d immer noch Continuation, rb/c0 immer noch okhttp3.Interceptor) -> unsere DEX-Patches funktionieren weiterhin. Neue nuetzliche Features: "Refresh Add-ons"-Aktion (#511, Plugin-Update ohne Loeschen/Neu-Hinzufuegen), "Fixed release dependency injection for sideload builds" (#525). Release-Notes erwaehnen NICHT den Cloudstream3-.cs3-Plugin-Obfuskations-Bug -> Kernproblem von ARVIO nicht geloest, nur unsere Patches bleiben kompatibel. Nutzer kann auf 1.9.994 updaten (sicher).
- Letzter Commit auf `main`: v17 (Fix #10, pre-d8 .class patching). Builds-Version: 17.

### Was fertig ist (unverвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®ndert gвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—ltig)
Filmpalast-Plugin als Cloudstream3-`TmdbProvider` implementiert, gebaut, auf `builds`-Branch (`status=1`, `tvTypes=[Movie,TvSeries]`). CI grвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—n. Nutzer hat v13 in ARVIO 1.9.983 (sideload) installiert; v14 steht auf builds-Branch bereit zum Test. Python-E2E-Simulation lвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®uft durch; filmpalast.to + TMDB per HTTP erreichbar. **Das Problem ist rein ARVIO-seitig beim Laden/AusfвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—hren von `.cs3`-Plugins.**

---

### (Veraltet, aber als Referenz behalten) FrвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—here Logcat-Optionen ohne PC
ARVIO hat **keine Log-Datei-Exportfunktion** und schreibt **keine App-Logs in Dateien** (verifiziert im gesamten ARVIO-Quellcode). Scraper-Logs (`Log.d/w` in `ExternalExtensionRunner.kt`) gehen **nur an Androids Logcat-Kernel-Buffer** (flвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—chtig, ohne Root nicht direkt auslesbar). Optionen ohne PC:
- **LADB-App:** scheiterte am Pairing ("no devices/emulators found"); "Pair & shell"-Schalter musste AN sein; 30-s-Pairing-Timer extrem zickig. **FвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—r diesen Nutzer nicht praktikabel.**
- **Bug Report:** Android-Einstellungen вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ Entwickleroptionen вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ Fehlerbericht (unhandlich, riesiger ZIP).
- **Nur mit Root:** Logcat-Reader-App.
- **WICHTIG:** ARVIOs integrierter "Test Scraper"-Button (`PluginManager.testScraper()`/`executeWithDiagnostics`) ist im Code vorhanden, aber in `PluginScreen.kt` **NICHT in die UI eingebaut** (Strings + ViewModel-Logik existieren, kein Compose-Button ruft `PluginUiEvent.TestScraper` auf). Halbfertige ARVIO-Funktion. FвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—r uns irrelevant, solange der Scraper ohnehin nie geladen wird.
вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ **Fazit: PC+USB+adb ist der Weg.** Siehe Prio 1 oben.

---

### Wichtige Dateien & Referenzen
- **Filmpalast-Code:** `/workspace/project/Arvio-Addon/FilmPalast/src/main/kotlin/com/reichi/arflioaddon/filmpalast/` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә `FilmpalastProvider.kt` (load/loadLinks/diagnose), `FilmpalastPlugin.kt`, `FilmpalastExtractors.kt`, `DebugLog.kt`, `DebugServer.kt`, `DownloadsLogWriter.kt`
- **ARVIO-Referenz:** `ProdigyV21/ARVIO` @ v1.9.983 (neu klonen nach `/tmp/arvio_ref`, wird nicht persistiert). SchlвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—ssel-Dateien:
  - `app/src/sideload/kotlin/com/arflix/tv/core/plugin/PluginManager.kt` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә `executeScrapers` (625), `executeScrapersStreaming` (672), `enabledScrapers` (271), `executeExternalDexScraper` (831, mit `SCRAPER_TIMEOUT_MS=120_000` bei 840), `downloadDexExtensions` (1057), `manifestEnabled = plugin.status == 1` (1079), `toggleScraper` (589, lвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®dt NICHT neu), `refreshExternalRepository` (566, lвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®dt neu)
  - `app/src/sideload/kotlin/com/arflix/tv/core/plugin/cloudstream/ExternalExtensionLoader.kt` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә `downloadExtension` (203, DEX read-only fвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—r API28+), `loadExtension` (259), `findAndLoadPlugin` (701, liest `manifest.json`-`pluginClassName`), `plugin.load()`-Aufruf (317, fвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®ngt Exception+Error), **Fallback-DEX-Scan bei `apis.isEmpty()||extractors.isEmpty()`** (336), `getApi` (420, apiCache)
  - `app/src/sideload/kotlin/com/arflix/tv/core/plugin/cloudstream/ExternalExtensionRunner.kt` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә `execute` (60), `executeInternal` (342), `executeTmdbProvider` (367), `executeTmdbLoadLinks` (430, `LOADLINKS_TIMEOUT_MS=60_000` bei 442), `extractData` (738: MovieвҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„ГҙdataUrl, TvSeriesвҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„ГҙfindEpisode.data), `filterValid` (870: nur http(s)-URLs!), `toLocalScraperResult` (884), `EXECUTION_TIMEOUT_MS=120_000`
  - `app/src/main/kotlin/com/arflix/tv/domain/model/Plugin.kt` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә `ScraperInfo` (77), `supportsType` (92, normalisiert series/tv/animeвҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙtv)
  - `app/src/sideload/kotlin/com/arflix/tv/core/plugin/cloudstream/TvTypeExtensions.kt` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә `tvTypeFromString`, `toNuvioType`
  - `app/src/main/kotlin/com/arflix/tv/ui/screens/details/DetailsViewModel.kt` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә `loadStreams` (1405), `pluginScraperJob` (1511, ruft `executeScrapersStreaming`), `hasStreamingAddons` zвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®hlt NUR Stremio-Addons (1601/1634/1651/1690) вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ irrefвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—hrende "kein Add-on"-Meldung bei reinen Cloudstream-Plugins
- **GermanProviders-Referenz:** `Bnyro/GermanProviders` (Repo-URL: `https://raw.githubusercontent.com/Bnyro/GermanProviders/refs/heads/master/repo.json`). Filmpalast dort = `MainAPI` (search-based). **Auf dem GerвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®t des Nutzers ebenfalls 0 Quellen** вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ beweist ARVIO-seitiges `.cs3`-Problem.
- **Builds-Branch:** v14 veroeffentlicht (DEX-Patching), `status=1`, `internalName=FilmPalast`. `plugins.json`+`FilmPalast.cs3` auf `builds`.
- **cloudstream3 library:** v4.7.0 (`com.github.recloudstream.cloudstream:library-android:v4.7.0`). Built-in Extractoren: `Voe()`, `Firestream()`, `FileMoonSx()`, `Supervideo()`, `VidHidePro()` + ~270 andere via `installGlobal()`. **Wichtig:** `loadLinks` gibt in v4.7.0 `Boolean` zurвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—ck (nicht Unit) вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ Override muss `: Boolean` deklarieren.

### ARVIO-Scraper-Aufruf-Pfad (verifiziert, entscheidend fвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—rs Debugging)
1. `DetailsViewModel.loadStreams` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ `pluginScraperJob` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ `pluginManager.executeScrapersStreaming(tmdbId, mediaType, season, episode)`
2. `executeScrapersStreaming`: prвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—ft `pluginsEnabled` + `enabledScrapers.filter{supportsType}`; leer вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ return; sonst pro Scraper `executeScraperWithSingleFlight` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ `executeExternalDexScraper` (mit `SCRAPER_TIMEOUT_MS=120_000`)
3. `executeExternalDexScraper` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ `externalExtensionRunner.execute(scraperId,...)` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ `extensionLoader.getApi(scraperId)` (leer вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ "No API loaded" вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ emptyList, **still**)
4. `execute` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ `executeInternal` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ **wenn `api is TmdbProvider`:** `executeTmdbProvider`; **sonst:** `executeSearchBased`
5. `executeTmdbProvider`: `api.load("""{"id":$tmdbIdInt,"type":"$type"}""")` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ null-fallback `api.load("https://www.themoviedb.org/<type>/<id>")` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ `extractData(loadResponse)` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ `api.loadLinks(data)`
6. `extractData`: `MovieLoadResponse`вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ`dataUrl`, `TvSeriesLoadResponse`вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ`findEpisode(...).data`
7. `executeTmdbLoadLinks`: sammelt `ExtractorLink`s via callback, `filterValid` (nur http(s)), `toLocalScraperResult` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ erscheinen in ARVIOs Quellenauswahl. **Unsere Debug-Quellen (url=`https://arvio-addon.invalid/...`) passieren filterValid.**
8. **Inkonsistenz (Test-Pfad):** `executeTmdbProviderWithDiagnostics` ruft `loadLinks` mit `TmdbLink(...).toJson()` direkt auf (ohne `load()`) вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә anderer data-Vertrag. Unser `loadLinks` ist auf den load()-Pfad ausgelegt. Falls ARVIO den Test-Button aktiviert, muss `loadLinks` auch TmdbLink-JSON verarbeiten.
9. **WICHTIG fвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—r "Quellen aktualisieren":** `toggleScraper` (589) lвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®dt die `.cs3` NICHT neu вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ nur Datenbank-Toggle. Neudownload NUR via `addRepository` oder `refreshExternalRepository`. **Daher: fвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—r Plugin-Update immer Repo lвҖ“ДҸвҖңДҸвҖ“ГӯВ¬вҲӮschen + neu hinzufвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—gen** (`https://raw.githubusercontent.com/ReichiMD/Arvio-Addon/main/repo.json`).

### Diagnose-Tooling (HINWEIS: v10-v14 haben keine In-Plugin-Diagnose mehr, nur android.util.Log)вҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—r die Logcat-вҖ“ДҸвҖңДҸвҖ“ГӯвҖҡГ„Дӣra falls Scraper doch lвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®uft)
- `DebugLog.kt`: in-memory Ring-Buffer (2000) + `snapshot()`/`format()` fвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—r `emitTraceAsSources`.
- `emitTraceAsSources(callback)`: emittiert Trace als `ExtractorLink` (source="ArvioAddon-Debug", url=`https://arvio-addon.invalid/debug/<n>`) вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гә sichtbar in ARVIO-Quellenauswahl. Erstes Banner: "PLUGIN vN loaded".
- `debugLoadResponse()`: `MovieLoadResponse` mit `dataUrl="ARVIO_DEBUG"` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ `loadLinks` wird auch bei load()-Fehlern aufgerufen.
- Per-Call-Timeouts (`NET_TIMEOUT_MS=8000`) um `fetchTmdbMeta`/`searchFilmpalast`.
- `DebugServer.kt` (127.0.0.1:8420) + `DownloadsLogWriter.kt` (MediaStore) noch vorhanden, aber **nur nutzbar, wenn der Scraper lвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®uft** (was aktuell nicht der Fall ist).

---

## Entscheidung: Welcher Plugin-Typ?

**GewвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®hlt: Cloudstream3-Plugin (Kotlin/DEX, ".cs3")** вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә der "mвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®chtige" Weg.

| Grund | Detail |
|---|---|
| Eigene Konfig-Seite (Portal-URL/MAC) | Nur Cloudstream3-Plugins kвҖ“ДҸвҖңДҸвҖ“ГӯВ¬вҲӮnnen UI-Settings haben вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ nвҖ“ДҸвҖңДҸвҖ“ГӯВ¬вҲӮtig fвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—r Stalker-VOD |
| Eigene Kataloge/Startseiten in ARVIO | Nur Cloudstream3-Plugins liefern eigene Start-Kataloge |
| Kotlin = gleiche Sprache wie Ventix | Ventix-Scraper (Kotlin) lassen sich **direkt portieren**, nicht nach JS вҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—bersetzen |
| Viele Vorlagen | GermanProviders-Repo (Bnyro) ist eine komplette Vorlage mit exakt unseren Scraper-Hostern |
| Cloudstream3-вҖ“ДҸвҖңДҸвҖ“ГӯвҖҡГ„ДҸkosystem | ARVIO nutzt library v4.7.0; apiVersion 1 ist kompatibel |

**AbgewвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®hlt: Nuvio-JS-Plugin** (Weg A) вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә einfacher, kann aber nur Streams liefern, keine Config-Seite, keine eigenen Kataloge. Da wir Stalker-VOD brauchen (mit Portal/MAC-Eingabe), reicht JS-Plugin nicht.

---

## Ziel: ARVIO-Installation des fertigen Plugins

Der Nutzer installiert das Plugin so in ARVIO (verifizierter Flow):
1. ARVIO **sideload-APK** installieren (nicht Play-Store-Version!)
2. Einstellungen вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ **Plugins & Extensions** (nur in sideload sichtbar)
3. **Add Repository** вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ Repo-URL eintragen
4. ARVIO lвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®dt `repo.json` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ folgt `pluginLists` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ lвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®dt `plugins.json`
5. Plugin-EintrвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®ge einschalten вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ ARVIO lвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®dt `.cs3`-Datei (kompilierter Code)

### Bekannter Bug: Add-Repo-Dialog/Plugin-Settings auf Handy (GEFIXT in 1.9.983)
Der "Add Repository"-Dialog + Plugin-Settings-Screen nutzten TV-only `androidx.tv.material3.Surface`-Buttons, die auf Touch-GerвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®ten (Handy/Tablet) nicht reagierten. **Behoben in ARVIO Issue #502** ("fix(mobile): resolve touch issues in plugins settings") вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә `PluginScreen.kt` hat jetzt `LocalDeviceType.current.isTouchDevice()` mit separatem Mobile-Layout. **Fix ist in 1.9.983 enthalten** (verifiziert). Nutzer hat das Plugin erfolgreich вҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—ber ein Cloud-Profil auf dem Handy installiert.

---

## Was das Plugin kвҖ“ДҸвҖңДҸвҖ“ГӯВ¬вҲӮnnen muss (Scope)

### Modul 1: Deutsche Web-Scraper (Filmpalast, Serienstream, HdFilme, Megakino, KinoGer, Netzkino, AniWorld)
- **Vorlage:** GermanProviders-Repo (Bnyro/GermanProviders) вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә hat ALL diese Scraper schon als Cloudstream3-Plugins!
- MвҖ“ДҸвҖңДҸвҖ“ГӯВ¬вҲӮglichkeit 1: GermanProviders forken + anpassen (wenig Eigenarbeit, abhвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®ngig von Upstream)
- MвҖ“ДҸвҖңДҸвҖ“ГӯВ¬вҲӮglichkeit 2: Eigenes Plugin schreiben, GermanProviders als Referenz (volle Kontrolle)

### Modul 2: Stalker-VOD (Filme + Serien вҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—ber Stalker-Portal)
- **Das ist die Neuentwicklung** вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә GermanProviders hat das nicht.
- ARVIOs eingebaute StalkerApi kennt NUR Live-TV (get_genres, get_all_channels, create_link) вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә **KEIN VOD, keine Serien**.
- Plugin braucht: eigene Config-Seite (Portal-URL + MAC), VOD-Kategorien, VOD-Liste, createVodLink, Serien/Staffeln/Episoden.
- Vorlage: Ventix-StalkerApi (17 Methoden) вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә in Kotlin, direkt portierbar.

### Modul 3: Stalker Live-TV
- **Nicht bauen** вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә ARVIO hat das schon eingebaut (obwohl die UI aktuell fehlt, siehe "ARVIO-MвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®ngel").

---

## Architektur-Referenz: ARVIOs Plugin-System

### Plugin-Formate die ARVIO versteht (verifiziert im Code)
1. **Nuvio-JS-Plugin**: `manifest.json` + `.js`-Dateien mit `getStreams(tmdbId, type, season, episode)`. Engine: QuickJS + Cheerio + CryptoJS. (abgewвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®hlt)
2. **Cloudstream3-Plugin (EXTERNAL_DEX)**: `.cs3`-Datei (kompiliertes DEX). Engine: cloudstream3-library v4.7.0. (**gewвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®hlt**)

### ARVIO Repository-Manifest-Format (`repo.json`)
```json
{
  "name": "Ventix Arvio Addon",
  "description": "Deutsche Scraper + Stalker VOD fвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—r ARVIO",
  "manifestVersion": 1,
  "pluginLists": ["https://raw.githubusercontent.com/ReichiMD/Arvio-Addon/<branch>/plugins.json"]
}
```
- ARVIOs `ExternalRepoParser` erkennt Repos am `"pluginLists"`-Key (verifiziert).

### ARVIO `plugins.json` Format (pro Plugin-Eintrag)
```json
{
  "name": "Ventix Scraper",
  "internalName": "VentixScraper",
  "description": "...",
  "version": 1,
  "apiVersion": 1,
  "status": 1,
  "authors": ["ReichiMD"],
  "tvTypes": ["Movie", "TvSeries"],
  "url": "https://raw.githubusercontent.com/ReichiMD/Arvio-Addon/builds/VentixScraper.cs3",
  "fileSize": 12345,
  "repositoryUrl": "https://github.com/ReichiMD/Arvio-Addon"
}
```

### Cloudstream3-Plugin-Aufbau (pro Plugin-Modul, z.B. Filmpalast)
- `@CloudstreamPlugin`-annotierte `Plugin`-Klasse вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ `registerMainAPI(...)` + `registerExtractorAPI(...)`
- `MainAPI`-Subklasse вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ `mainUrl`, `name`, `supportedTypes`, `mainPage`, `search()`, `load()`, `loadLinks()`
- `ExtractorApi`-Subklassen fвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—r Hoster (VOE, FileMoon, Supervideo, VidHidePro etc.)

---

## Ventix-Referenz (Quell-Projekt, NICHT in dieses Repo kopieren)

Ventix liegt im Schwester-Repo `ReichiMD/IPTV-App`. Scraper-Quellcode zum Portieren:
- `app/src/main/java/com/iptv/stalker/data/scraping/` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә FilmpalastScraper, HdFilmeScraper, KinogerScraper, MegakinoScraper, SerienstreamScraper, AniWorldScraper, NetzkinoScraper + extractor/
- `app/src/main/java/com/iptv/stalker/data/api/StalkerApi.kt` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә Stalker-Middleware (17 Methoden: handshake, get_profile, get_events, VOD-+Serien-+EPG-Endpoints, createVodLink, getSeasons, M3U-Export)
- `app/upstream-reference/` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә Cloudstream3-Upstream-Referenzen (bereits als Referenz genutzt!)
- `VideoHostExtractor.kt` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә Hoster-Extraktoren (VOE, FileMoon, VidGuard, Veev, Vidsonic, DoodStream etc.)

Ventix und ARVIO nutzen BEIDE Cloudstream3-Upstream-Referenzen вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә das vereinfacht das Portieren.

---

## GermanProviders-Referenz (Vorlage-Repo, geklont nach /tmp/german-providers)

`Bnyro/GermanProviders` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә Cloudstream3-Multi-Provider-Repo, hat bereits 21 fertige Plugins:
ARD, Aniworld, Arte, C3TV, Discovery, EinschaltenIn, **FilmPalast**, HDFilme, HuhuTo, IptvOrg, KinoKing, **Kinoger**, **Megakino**, Moflix, **Netzkino**, PlutoTV, **Serienstream**, Southpark, SpiegelTV, Welt, Xcine.

Installations-URL (Test): `https://raw.githubusercontent.com/Bnyro/GermanProviders/refs/heads/master/repo.json`
- `builds`-Branch enthвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®lt `plugins.json` + fertige `.cs3`-Dateien.
- Aufbau: root `build.gradle.kts` (cloudstream3-gradle-plugin `com.github.recloudstream:gradle`), pro Provider ein Modul-Ordner mit `build.gradle.kts` + `src/`.
- Settings-Gradle: auto-include aller Modul-Ordner.

**Diese Scraper (Filmpalast, Serienstream etc.) sind identisch mit Ventix' Ziel-Set.** GermanProviders ist die primвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®re Vorlage fвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—r Modul 1.

---

## ARVIO-Referenz (geklot nach /tmp/arvio_ref)

`ProdigyV21/ARVIO` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә die Ziel-App. Version 1.9.983 (versionCode 306), sehr aktiv.

### ARVIO Build-Flavors (verifiziert in `app/build.gradle.kts`)
| Flavor | `FEATURE_PLUGINS_ENABLED` | `SELF_UPDATE_ENABLED` | Plugin-Engine |
|---|---|---|---|
| `play` (Play Store) | **false** | false | вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңГөвҖ“ГӯвҖңДҳ abgeschaltet |
| `sideload` (GitHub-APK) | **true** | true | вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңВЈвҖ“ГӯвҖҡГ„В¶ voll aktiv |

вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ **Plugin funktioniert NUR in der sideload-APK**, nicht im Play-Store-Build. Google-Policy verbietet dynamischen Code im Store.

### ARVIO sideload-Download
`https://github.com/ProdigyV21/ARVIO/releases/download/v1.9.983/ARVIO-v1.9.983-sideload-release.apk` (135 MB)

### ARVIO Plugin-Engine (nur in `app/src/sideload/`)
- `PluginManager.kt` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә Repository-Verwaltung, `addRepository()`, Format-Auto-Detection
- `PluginRuntime.kt` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә QuickJS-Engine (fвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—r JS-Plugins) + `__native_fetch`, `__cheerio_*`, CryptoJS
- `cloudstream/ExternalExtensionLoader.kt` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә lвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®dt `.cs3`-Plugins via DexClassLoader
- `cloudstream/ExternalExtensionRunner.kt` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә fвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—hrt `MainAPI.search()`/`load()`/`loadLinks()` aus
- `cloudstream/ExternalExtractorRegistry.kt` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә verwaltet `ExtractorApi`-Extraktoren
- `cloudstream/ExternalRepoParser.kt` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә parsed `repo.json` (erkennt `"pluginLists"`-Key) + `plugins.json`

### ARVIO-MвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®ngel (Stand Aug 2026, die wir im Plugin adressieren)
1. **Stalker-VOD fehlt komplett** вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә StalkerApi kennt nur Live-TV (4 Methoden: handshake, getProfile, getChannels, resolveStreamUrl). Kein VOD, keine Serien, kein EPG fвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—r VOD.
2. **Stalker-Dateneingabe fehlt in der UI** вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә `saveStalkerConfig()` existiert im SettingsViewModel (Zeile 2280), wird aber von KEINEM UI-Element aufgerufen. Kein Button, kein Dialog. Backend halbfertig, UI fehlt.
3. **Add-Repo-Dialog Handy-Bug** вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә `width(520.dp)` zu breit fвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—r Hochformat (Workaround: Querformat).

---

## Stremio-Addon-Referenz (paralleles Projekt)

`ReichiMD/Stremio-Addon` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә serverseitiges Node.js-Addon (deployed auf Render), Quellen: Vavoo/KinoGer/Filmpalast/MovieBox/VidSrc/Einschalten + MediaFlowProxy.
- **Problem:** Stremio-Addon (serverseitig) kann manche Streams nicht liefern (z.B. KinoGer 403 вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә Render-DC-IP blockiert). Ventix (clientseitig) kann das.
- **Dieses ARVIO-Addon lвҖ“ДҸвҖңДҸвҖ“ГӯВ¬вҲӮst das:** lвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®uft clientseitig in der App вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ EndgerвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®t-IP вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ kein Bot-Schutz вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ kein Server, kein Geld.
- Logik der Scraper ist im Stremio-Addon bereits in JavaScript/TypeScript vorhanden (kann als Referenz dienen, wird aber neu in Kotlin als Cloudstream3-Plugin geschrieben).

`ReichiMD/mediaflow-proxy` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә MediaFlowProxy (fest codiert im Stremio-Addon). FвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—r ARVIO-Addon nicht nвҖ“ДҸвҖңДҸвҖ“ГӯВ¬вҲӮtig (clientseitig braucht keinen Proxy).

---

## Build & Release

### Plugin kompilieren (Cloudstream3-gradle-plugin)
- Multi-Modul-Setup wie GermanProviders: root `build.gradle.kts` mit `com.github.recloudstream:gradle`-Plugin, pro Plugin ein Modul.
- Output: `.cs3`-Datei pro Plugin-Modul.
- CI: GitHub Actions baut `.cs3`-Dateien, pusht sie auf einen `builds`-Branch (wie GermanProviders), generiert/aktualisiert `plugins.json`.

### Datei-Struktur (geplant)
```
Arvio-Addon/
вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖҡГ„ДҡвҖ“ГӯвҖңВЈвҖ“ДҸвҖ”ДӣвҖ“ГӯвҖҡГ„ДҡвҖ“ГӯвҖңД–вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖҡГ„ДҡвҖ“ГӯвҖңД– AGENTS.md                          # diese Datei
вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖҡГ„ДҡвҖ“ГӯвҖңВЈвҖ“ДҸвҖ”ДӣвҖ“ГӯвҖҡГ„ДҡвҖ“ГӯвҖңД–вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖҡГ„ДҡвҖ“ГӯвҖңД– README.md                          # (spвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®ter)
вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖҡГ„ДҡвҖ“ГӯвҖңВЈвҖ“ДҸвҖ”ДӣвҖ“ГӯвҖҡГ„ДҡвҖ“ГӯвҖңД–вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖҡГ„ДҡвҖ“ГӯвҖңД– build.gradle.kts                   # root, cloudstream3-gradle-plugin
вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖҡГ„ДҡвҖ“ГӯвҖңВЈвҖ“ДҸвҖ”ДӣвҖ“ГӯвҖҡГ„ДҡвҖ“ГӯвҖңД–вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖҡГ„ДҡвҖ“ГӯвҖңД– settings.gradle.kts                # auto-include Module
вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖҡГ„ДҡвҖ“ГӯвҖңВЈвҖ“ДҸвҖ”ДӣвҖ“ГӯвҖҡГ„ДҡвҖ“ГӯвҖңД–вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖҡГ„ДҡвҖ“ГӯвҖңД– repo.json                          # Installations-Manifest fвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—r ARVIO
вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖҡГ„ДҡвҖ“ГӯвҖңВЈвҖ“ДҸвҖ”ДӣвҖ“ГӯвҖҡГ„ДҡвҖ“ГӯвҖңД–вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖҡГ„ДҡвҖ“ГӯвҖңД– GermanScraper/                     # Modul 1: deutsche Web-Scraper (oder pro Scraper ein Modul)
вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖҡГ„ДҡвҖ“ГӯвҖқДЈ   вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖҡГ„ДҡвҖ“ГӯвҖңВЈвҖ“ДҸвҖ”ДӣвҖ“ГӯвҖҡГ„ДҡвҖ“ГӯвҖңД–вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖҡГ„ДҡвҖ“ГӯвҖңД– build.gradle.kts
вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖҡГ„ДҡвҖ“ГӯвҖқДЈ   вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖҡГ„ДҡвҖ“ГӯвҖҡГ„ДҡвҖ“ДҸвҖ”ДӣвҖ“ГӯвҖҡГ„ДҡвҖ“ГӯвҖңД–вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖҡГ„ДҡвҖ“ГӯвҖңД– src/main/kotlin/.../GermanScraperPlugin.kt + Provider + Extractors
вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖҡГ„ДҡвҖ“ГӯвҖҡГ„ДҡвҖ“ДҸвҖ”ДӣвҖ“ГӯвҖҡГ„ДҡвҖ“ГӯвҖңД–вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖҡГ„ДҡвҖ“ГӯвҖңД– StalkerVod/                        # Modul 2: Stalker-VOD (Config-Seite + VOD/Serien)
    вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖҡГ„ДҡвҖ“ГӯвҖңВЈвҖ“ДҸвҖ”ДӣвҖ“ГӯвҖҡГ„ДҡвҖ“ГӯвҖңД–вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖҡГ„ДҡвҖ“ГӯвҖңД– build.gradle.kts
    вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖҡГ„ДҡвҖ“ГӯвҖҡГ„ДҡвҖ“ДҸвҖ”ДӣвҖ“ГӯвҖҡГ„ДҡвҖ“ГӯвҖңД–вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖҡГ„ДҡвҖ“ГӯвҖңД– src/main/kotlin/.../StalkerVodPlugin.kt + StalkerApi + Provider
```

### branches
- `main` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә Quellcode
- `builds` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә fertige `.cs3`-Dateien + `plugins.json` (von CI gepusht, wie GermanProviders)

---

## NвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®chste Schritte (PrioritвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®t)

1. **Proof-of-Concept:** GermanProviders in ARVIO-sideload testen (Button-Bug workaronden) вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ prвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—fen welche Scraper laufen.
2. **Repo-Setup:** GermanProviders-Architektur (root build.gradle + Modul-Struktur) hier nachbauen.
3. **Modul 1 (Web-Scraper):** GermanProviders-Plugins adaptieren ODER eigene Implementierung. Hoster-Extraktoren (VOE, FileMoon etc.) aus Ventix' `VideoHostExtractor` portieren.
4. **Modul 2 (Stalker-VOD):** Ventix' `StalkerApi.kt` (VOD/Serien-Teil) als Cloudstream3-Provider portieren + Config-Seite fвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—r Portal/MAC.
5. **CI:** GitHub Actions workflow fвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—r `.cs3`-Build + `builds`-Branch-Push.

---

## Versionshistorie dieses Addons

(noch keine вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә Repo ist leer)

---

## Recherche: ARVIO-Plugin-Integration (Stand Aug 2026, ARVIO v1.9.983)

Verifiziert im ARVIO-Quellcode (`ProdigyV21/ARVIO` @ v1.9.983, geklont nach `/tmp/arvio_ref`).
Recherche anlвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®sslich zweier Nutzer-Probleme beim Testen von GermanProviders als Cloudstream3-Plugin in ARVIO.

### Problem 1: Plugin-Einrichtung funktioniert nur auf TV, nicht auf Handy/Tablet

**Beobachtung (Nutzer):** Add-Repository / Plugin-Aktivierung ging auf Handy & Tablet nicht; erst ein ARVIO-Cloud-Profil (auf TV erstellt, aufs Handy synchronisiert) brachte die Plugins aufs Handy. TV funktionierte direkt.

**Rechercheergebnis:**
- ARVIO hat ein Layout-Force-Feature ("Force TV, Tablet, or Phone layout") UND Auto-Detect fвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—r TV-Modus bei GerвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®ten ohne Touchscreen (CHANGELOG v1.9.3). Die UI wird je Formfaktor unterschiedlich gerendert.
- Der Plugin-Bereich wurde in v1.9.983 neu gebaut: CHANGELOG-Eintrag "redesigned plugin settings for TV and mobile. Contributor: @Himanth-reddy via #466" вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә d.h. die mobile Plugin-UI ist **sehr neu** (Juli 2026).
- Begleitend in v1.9.983: "Fixed sideload production-plugin routing, extractor unloading, **mobile routing**, and TV focus limits" (#466) вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә ein mobiler Routing-Fix wurde *explizit* fвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—r diese Version gebraucht. Das deutet darauf hin, dass mobile Plugin-Pfade vorher fehlerhaft waren.
- Ein **bekannter, вҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®lterer Bug** (AGENTS.md bereits notiert): Add-Repo-Dialog `width(520.dp)` zu breit fвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—r Handy-Hochformat (~390dp) вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ Buttons abgeschnitten/inaktiv im Hochformat.
- Vergleichs-Befund aus dem Nuvio-вҖ“ДҸвҖңДҸвҖ“ГӯвҖҡГ„ДҸkosystem (Schwester-App, gleiche Plugin-Architektur): NuvioMobile Issue #1190 вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә *"If Cloudstream Plugin Repositories are loaded in the Plugins list in the Mobile app, they get removed from Plugins list in the TV app"* (closed as not planned). Cloudstream-Plugin-Listen zwischen Mobile- und TV-UI synchron halten ist **branchenweit ein Problem**, nicht ARVIO-spezifisch.

**Fazit Problem 1:** Sehr wahrscheinlich ein **ARVIO-seitiger Bug in der (neuen) mobilen Plugin-UI** вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә entweder Routing (in v1.9.983 gerade erst gefixt, evtl. nicht vollstвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®ndig) oder der bekannte `width(520.dp)`-Dialog-Bug. Dass der Cloud-Sync-Workaround funktioniert, bestвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®tigt: Die Plugin-Daten selbst sind korrekt; nur die mobile Einrichtungspath-UI ist defekt. Keine andere Nutzerberichte als direktes Duplikat gefunden, aber die CHANGELOG-Historie (mobiler Plugin-Routing-Fix in der *aktuellen* Version) zeigt, dass ARVIO genau diese Klasse von Bug gerade behebt.

**Workarounds fвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—r Nutzer:** Querformat beim Add-Repo; oder Plugin-Konfiguration auf TV vornehmen + ARVIO-Cloud-Sync aufs Handy (funktioniert laut Nutzer bereits); oder `web.arvio.tv` (Web-App, vollstвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®ndige ARVIO-UI im Browser, laut CHANGELOG mit TV-D-pad-Navigation).

### Problem 2: Aktivierte Provider erscheinen nicht bei Quellensuche ("kein Add-on eingerichtet / keine Quellen")

**Beobachtung (Nutzer):** In den Plugin-Einstellungen Provider (z.B. Einschalten) aktiviert вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ auf eine Silo-Episode gegangen вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ "nach Quellen gesucht" вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ Meldung "kein Add-on eingerichtet, keine Quellen gefunden".

**Verifizierte Ursache im ARVIO-Code:** ARVIO hat **zwei komplett getrennte Quell-AuflвҖ“ДҸвҖңДҸвҖ“ГӯВ¬вҲӮsungspfade**, und Cloudstream3-Plugins (.cs3) laufen вҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—ber den Pfad, der die "kein Add-on"-Meldung **nicht steuert**:

1. **Stremio-Addon-Pfad** (`StreamRepository` + `AddonRuntimeAggregator`): Hier laufen klassische Stremio-kompatible Addons (HTTP `stream/movie/<imdbId>.json`), Home-Server (Jellyfin/Plex/Emby) und HTTP-Local-Scrapers. Die UI-Variable `hasStreamingAddons` (die "No Streaming Addons" / "kein Add-on eingerichtet" anzeigt) wird **ausschlieвҖ“ДҸвҖңДҸвҖ“ГӯвҖңД»lich** aus `streamRepository.installedAddons.count { it.isVodStreamingAddon() }` berechnet (`DetailsViewModel.kt` Z. 1600/1633/1650/1689). `isVodStreamingAddon()` prвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—ft nur `isEnabled && type != SUBTITLE && !sportsOnly` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә das sind Stremio-Addons, **keine Cloudstream-Scraper**. Filter `getStreamAddons()` (`StreamRepository.kt` Z. 1440) wirft sogar hart raus: `if (addon.runtimeKind != RuntimeKind.STREMIO) return@filter false` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә und `RuntimeKind` kennt nur `STREMIO`/`TELEGRAM`, keinen Cloudstream/EXTERNAL_DEX-Wert (`Models.kt` Z. 305).

2. **Cloudstream-Plugin-Pfad** (`PluginManager` + `ExternalExtensionRunner`, sideload-only): Aktivierte `.cs3`-Scraper werden in `DetailsViewModel.loadStreams()` вҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—ber `pluginManager.executeScrapersStreaming(...)` in einem **parallelen Job** (`pluginScraperJob`, Z. 1510вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә1552) ausgefвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—hrt. Ergebnisse mergen sich asynchron in `streams`. Dieser Pfad startet **nur**, wenn `dataStore.pluginsEnabled` true ist UND `enabledScrapers` (nach `supportsType(mediaType)`) nicht leer ist (`PluginManager.kt` Z. 631вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә640, 681).

**Warum trotzdem "kein Add-on"-Meldung + keine Quellen bei Silo:** Weil `hasStreamingAddons` Stremio-Addons zвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®hlt. Hat der Nutzer **kein einziges** Stremio-Addon installiert (nur Cloudstream-Plugins), ist `hasStreamingAddons=false` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ UI zeigt "No Streaming Addons / kein Add-on eingerichtet" an. Die Meldung ist in diesem Fall **irrefвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—hrend**: Die Cloudstream-Scraper suchen im Hintergrund trotzdem, finden aber fвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—r "Silo" vermutlich nichts (siehe Problem 2b), und die UI bleibt bei der "Setup Required"-Meldung stehen, obwohl die Plugins aktiv sind.

**Problem 2b вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә warum die Cloudstream-Scraper fвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—r "Silo" trotzdem 0 Quellen liefern (verifiziert):**
GermanProviders-Plugins (Filmpalast, Serienstream, AniWorld etc.) sind **keine** `TmdbProvider` (sie вҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—berschreiben nicht `load()` fвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—r TMDB-JSON), sondern **search-basierte** `MainAPI`-Provider. ARVIOs `ExternalExtensionRunner.executeSearchBased()` (Z. 473вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә620) macht fвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—r search-basierte Provider:
1. TMDB-Enrichment holen вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ `localizedTitle` + `year` + alt-Titel
2. `api.search(title)` aufrufen + bei Trefferlosigkeit Retry mit vereinfachtem Titel und parallelen Alt-Titeln
3. `findBestMatch()` (вҖ“ДҸвҖңДҸвҖ“ГӯвҖҡГ„Дӣhnlichkeits-Score) вҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—ber Suchergebnisse вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ `api.load(bestMatch.url)` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ `extractData()` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ `api.loadLinks()`

Scheitern kann es an **mehreren Stellen**:
- **Sprache:** Silo ist eine Apple TV+-Serie. Deutsche Scraper wie Filmpalast/Serienstream listen "Silo" u.U. nur unter deutschem Titel oder garnicht (Apple-TV+-Originals sind seltener auf deutschen Scraper-Seiten als Netflix/Prime). TMDB `localizedTitle` fвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—r Silo DE = "Silo" вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә passt, aber die Scraper-Seite muss die Serie auch im Katalog haben.
- **`findBestMatch`-Mismatch:** Wenn der Scraper "Silo" z.B. als "Silo - Season 1" oder mit Jahr-Abweichung zurвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—ckgibt, fвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®llt der Similarity-Score unter die Schwelle вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ `return emptyList()` (Z. 567). Das ist ein **hвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®ufiges** Cloudstream-Problem bei ARVIO, weil ARVIO eigenes Title-Matching macht statt die Provider-`load()` direkt mit der Scraper-eigenen URL zu fвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—ttern.
- **Season/Episode-Mapping:** `extractData(loadResponse, mediaType, season, episode)` baut das `data`-JSON, das `loadLinks()` erwartet. Bei Serien muss `load()` eine `TvSeriesLoadResponse` liefern, aus der ARVIO die Episoden-URL extrahiert. GermanProviders' `load()`-Implementierungen sind fвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—r Cloudstream3-App geschrieben; ARVIO ruft sie leicht anders auf вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ kann `data=null` geben вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ `return emptyList()` (Z. 590).
- **Host-Dead / Bot-Schutz:** Deutsche Scraper-Seiten blockieren oft. ARVIO fвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®ngt `hostUnreachable` ab und skippt (Z. 552). Da ARVIO clientseitig lвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®uft (GerвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®t-IP), sollte das seltener sein als beim serverseitigen Stremio-Addon вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә aber mвҖ“ДҸвҖңДҸвҖ“ГӯВ¬вҲӮglich.

**Fazit Problem 2:** Zwei Dinge вҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—berlagern sich:
- (a) **ARVIO-UI-Bug/DesignschwвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®che:** Die "kein Add-on eingerichtet"-Meldung wird nur aus dem Stremio-Addon-Pfad gespeist und ignoriert aktivierte Cloudstream-Plugins vollstвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®ndig. Solange kein Stremio-Addon aktiv ist, zeigt die UI "Setup Required", **selbst wenn** Cloudstream-Scraper im Hintergrund laufen. Das ist eine ARVIO-seitige LogiklвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—cke, nicht des Addons Schuld.
- (b) **Scraper-Matching:** Selbst wenn die Cloudstream-Scraper laufen, liefern sie fвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—r bestimmte Titel (wie Silo) oft 0 Treffer wegen ARVIOs eigenem Title-Matching / `findBestMatch` / Episode-Mapping, das nicht 1:1 der Cloudstream3-App entspricht.

CHANGELOG-Belege, dass ARVIO dieses Themenfeld aktiv bearbeitet:
- v1.9.983: "Added compatibility for Nuvio-style JavaScript scraper plugins and redesigned plugin settings for TV and mobile" (#466) + "Fixed sideload production-plugin routing, extractor unloading, mobile routing, and TV focus limits" (#466)
- v1.9.92: "Improved FlixStreams/anime addon matching and fallback stream lookup for episode sources" + "Fixed configured add-ons occasionally failing to appear in the source list until a later retry"
- v1.8.2: "Source selector shows setup instructions instead of generic 'No sources found' when no addons are installed" + "When no streaming addons are configured, the app now shows a friendly setup guide instead of a playback error"

**Handlungsempfehlung (fвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—r unser Addon / Nutzer):**
1. **FвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—r saubere UI-Anzeige:** ZusвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®tzlich zu den Cloudstream-Plugins **mindestens ein** Stremio-Addon (auch ein inaktives/dummy) installieren, damit `hasStreamingAddons=true` wird und die Meldung verschwindet. Das ist ein Workaround fвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—r ARVIOs LogiklвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—cke (a).
2. **FвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—r echte Quellen bei Serien wie Silo:** Eigenes ARVIO-Addon bauen (Ziel dieses Repos) вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә aber dabei darauf achten, dass die `MainAPI`-Implementierung robustes `search()` + `load()` + `loadLinks()` bietet, das ARVIOs `findBestMatch`-basiertem Aufruffluss standhвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®lt. Ideal: Provider als `TmdbProvider` implementieren (dann nimmt ARVIO den direkteren `executeTmdbProvider`-Pfad ohne fragiles Title-Matching). Das ist eine **Konsequenz fвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—r die Modul-1-Architektur** dieses Addons.
3. **GitHub-Issue bei ARVIO erwвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®gen:** (a) ist klar ein ARVIO-Bug ("hasStreamingAddons ignoriert aktivierte Cloudstream-Scraper"). Lohnt sich als Issue zu melden, da ARVIO aktiv ist (18 Releases in 5 Monaten) und #466 genau dieses Gebiet gerade anfasst.

---

## Implementation: Filmpalast-Plugin als TmdbProvider (Proof-of-Concept)

**Status: gebaut und kompiliert.** `FilmPalast/build/FilmPalast.cs3` (вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД®вҖ“ГӯвҖңвүӨ23 KB) + `build/plugins.json` werden lokal via `./gradlew make makePluginsJson` erzeugt; CI (`.github/workflows/build.yml`) pusht beides auf den `builds`-Branch.

### Architektur-Entscheidung (verbindlich fвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—r alle Modul-1-Scraper)
**Alle Provider als `TmdbProvider` implementieren**, nicht als plain `MainAPI`. BegrвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—ndung (siehe oben "Recherche"): ARVIO hat zwei Dispatch-Pfade in `ExternalExtensionRunner.execute()`:
- `executeTmdbProvider` (wenn `api is TmdbProvider`): ruft `api.load("{\"id\":<tmdbId>,\"type\":\"movie\"|\"tv\"}")` direkt auf вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ kein fragiles `findBestMatch`-Title-Matching.
- `executeSearchBased` (sonst): sucht Titel, matcht via Similarity-Score, mappt Season/Episode вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ hвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®ufig 0 Treffer bei Serien.

TmdbProvider ist der zuverlвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®ssige Pfad. GermanProviders' Scraper sind alles *search-based* (kein TmdbProvider) вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ das ist mit ein Grund, warum sie in ARVIO bei Serien oft leer bleiben.

### TmdbProvider-Vertrag (verifiziert am cloudstream3-Source `TmdbProvider.kt`)
- ARVIO ruft `load("{\"id\":<tmdbId>,\"type\":...}")`; Fallback `load("https://www.themoviedb.org/<type>/<id>")`. Beide Formen mвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—ssen `parseTmdbInput` akzeptieren.
- `load()` muss zurвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—ckgeben: `MovieLoadResponse` (Filme, `dataUrl`=JSON) ODER `TvSeriesLoadResponse` mit `Episode`-Liste (Serien, `episode.data`=URL).
- `loadLinks(data, ...)`: fвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—r Filme ist `data` das JSON aus `dataUrl`; fвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—r Serien ist `data` die Episoden-URL aus `episode.data`.
- `useMetaLoadResponse = false` (wir bauen die LoadResponse selbst, nicht вҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—ber TMDB-Meta-Provider).

### Filmpalast-Seitenstruktur (live verifiziert, Stand Aug 2026)
- Suche `/search/title/<query>`: listet Serien **pro Episode** (`/stream/silo-s03e06`), Filme als einzelne Seite. Keine Serien-Stammseite mit Staffeln.
- Stream-Seite `/stream/<slug>`: Hoster-Links in `ul.currentStreamLinks a.iconPlay` mit `data-player-url` (primвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®r) bzw. `href` (fallback).
- Gesehene Hoster: firestream.to, vidaraa.cc, voe.sx, vidsonic.net вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ gemappt auf `Voe1`, `FileMoonSx`, `VidHidePro` (Ryderjet), `Supervideo` (AbstreamTo).

### Filmpalast-spezifische `load()`-Logik
1. TMDB-Meta holen (`api.themoviedb.org/3`, de-DE) вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ `displayTitle` + `year`.
2. Filmpalast-Suche nach `displayTitle`.
3. Treffer matchen (normalisierter Titel-Vergleich, Typ movie/tv). Serie `"Silo S03E06"` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ Basisname `"Silo"` wird gegen TMDB-Titel gematcht.
4. Serie: alle Episoden sammeln вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ `TvSeriesLoadResponse` (Season/Episode aus Titel geparst). Film: `MovieLoadResponse` mit `dataUrl=JSON{links:[...]}`.
5. `loadLinks`: FilmвҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„ГҙJSON-Links; SerieвҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„ГҙEpisoden-URL fetchen + Host-Links sammeln вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ `loadExtractor()` pro registriertem Hoster.

### Bekannte Vorbehalte (Proof-of-Concept)
- **Apple-TV+-Serien (Silo):** deutsche Scraper haben solche Titel u.U. nicht oder zeitverzвҖ“ДҸвҖңДҸвҖ“ГӯВ¬вҲӮgert. TMDB-Titel passt, aber Filmpalast muss die Serie im Katalog haben.
- **TMDB-API-Key:** fest codiert (вҖ“ДҸвҖңДҸвҖ“ГӯВ¬вҲӮffentlich bekannter Cloudstream-Key). FвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—r Produktion ggf. eigener Key.
- **Hoster-Dead:** Filmpalast-Hosterdomains rotieren; Extractor-Mapping muss ggf. nachjustiert werden. Neue Domains via `registerExtractorAPI` hinzufвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—gen.

### вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңВ°вҖ“ГӯВ¬вҖ вҖ“ДҸвҖңГәвҖ“ГӯвҖ”ДҺвҖ“ГӯвҖңЕӮ status-Wert MUSS 1 sein (verifiziert im ARVIO-Code)
Der cloudstream-gradle-plugin-Default ist `status = 3` ("Beta only"). **Das bricht ARVIO.**
- `PluginManager.downloadDexExtensions` (PluginManager.kt:1079): `manifestEnabled = plugin.status == 1`
- `PluginDataStore.setScraperEnabled` (PluginDataStore.kt:152): `if (enabled && !scraper.manifestEnabled) return` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ speichert das Enable **nicht**, wenn `manifestEnabled=false`.
- Folge: Plugin sichtbar in der Liste, aber Toggle speichert nicht вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ Scraper lвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®uft nicht вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ keine Quellen.
- **Fix:** Im Modul-`build.gradle.kts` IMMER `status = 1` setzen (wie GermanProviders: alle 21 Plugins `status=1`). Nie Default `3` lassen.

### вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңВ°вҖ“ГӯВ¬вҖ вҖ“ДҸвҖңГәвҖ“ГӯвҖ”ДҺвҖ“ГӯвҖңЕӮ Hoster-Extraktion: built-in cloudstream3-Extractoren nutzen, nicht re-registrieren (verifiziert)
Filmpalast rotiert Hostnamen pro Episode/Load. Verifizierte Hostnamen (Aug 2026):
- **Built-in in cloudstream3** (ARVIO lвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®dt sie via `ExternalExtractorRegistry.installGlobal()` automatisch): `voe.sx` (Voe), `firestream.to` (Firestream), `filemoon.sx` (FileMoonSx), `supervideo.cc` (Supervideo), `vidhide.com` (VidHidePro).
- **NICHT built-in** (Filmpalast-spezifisch, eigene Extractor-Aliase nвҖ“ДҸвҖңДҸвҖ“ГӯВ¬вҲӮtig): `ryderjet.com`, `abstream.to`.
- **Obskur / API-basiert** (kein statischer Extractor mвҖ“ДҸвҖңДҸвҖ“ГӯВ¬вҲӮglich): `vidaraa.cc`, `vidsonic.net`, `odysseusa.cc`, `MoneyGalactic.com` (JWPlayer mit `t.streaming_url` aus API-Call вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә generischer Fallback findet nur sometimes direkte URLs).

**Fehler, der "no sources" verursachte (behoben in b6e3c1b):**
1. `loadLinks` setzte `any=true`, sobald `loadExtractor` *aufgerufen* wurde вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә ignorierte den RвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—ckgabewert. Wenn alle `loadExtractor` `false` zurвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—ckgaben (kein passender Extractor), blieb `any` trotzdem `true` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ irrefвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—hrend. Fix: `any` nur auf `true` wenn `loadExtractor` true ODER generischer Fallback findet URL.
2. `Voe1()` registriert вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә `Voe1.mainUrl = "https://donaldlineelse.com"` (rotierender VOE-Mirror), matched **nicht** auf `voe.sx`-Links. Built-in `Voe()` (mainUrl=`voe.sx`) matched korrekt. Fix: `Voe1`/`FileMoonSx` nicht mehr re-registrieren (built-in reicht).
3. **Generischer Fallback** (`genericResolve`): fetcht Embed-Seite, sucht nach direkten mp4/m3u8-URLs (Regex). Best-Effort fвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—r obskure JWPlayer-Hoster; fвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®ngt nicht alle (vidaraa braucht API-Call), aber fвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®ngt z.B. firestream-Video-Pfade.

### Recherche: ARVIO Test-Funktion & Log-MвҖ“ДҸвҖңДҸвҖ“ГӯВ¬вҲӮglichkeit (Aug 2026, ARVIO 1.9.983)
**ARVIO hat KEINE Log-Datei-Exportfunktion.** `DiagnosticsManager` ist nur fвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—r Sentry/Crashlytics-Reporting, keine In-App-Log-Anzeige. Der einzige Weg an die Scraper-Logs zu kommen ist **Logcat** (`adb logcat` вҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—ber USB am PC).
- ARVIO hat im Code eine **"Test Scraper"-Funktion** (`PluginManager.testScraper()` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ `executeWithDiagnostics()`), die mit The Matrix (TMDB 603) testet und `TestDiagnostics` mit Einzelschritten zurвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—ckgibt (TMDB-Metadaten, search-Ergebnisse, HTTP-Requests, loadLinks, "Missing extractors: ..."). **ABER: der "Test"-Button ist in `PluginScreen.kt` NICHT in die UI eingebaut** вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә Strings (`plugin_test_btn`, `plugin_diagnostics_expand`) und ViewModel-Logik existieren, aber kein Compose-Button ruft `PluginUiEvent.TestScraper` auf. Halbfertige ARVIO-Funktion (wie Stalker-VOD-UI).
- **WICHTIGE INKONSISTENZ:** `executeTmdbProviderWithDiagnostics` (Test-Pfad) ruft `loadLinks` mit `TmdbLink(...).toJson()` direkt auf (OHNE `load()`), wвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®hrend `executeTmdbProvider` (echte Suche) erst `api.load({"id":...,"type":...})` aufruft und `extractData()` das `dataUrl`/`episode.data` extrahiert. Mein `loadLinks` ist auf den load()-Pfad ausgelegt (`{"links":[...]}` oder `http`-URL), wвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—rde also im Test-Pfad leer laufen. Falls ARVIO den Test-Button irgendwann aktiviert, muss mein `loadLinks` auch TmdbLink-JSON verarbeiten.

### Recherche: Touch-Bug auf Handy/Tablet (ARVIO Issue #502)
**BestвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®tigt und (teilweise) behoben in ARVIO 1.9.983.** ARVIO Issue #502 "fix(mobile): resolve touch issues and unify button styling in plugins settings":
- Ursache: Plugin-Settings-Screen + Add-Repo-Dialog nutzten TV-only `androidx.tv.material3.Surface`-Buttons, die auf Touch-GerвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®ten nicht reagierten.
- Fix: `PluginScreen.kt` hat jetzt `LocalDeviceType.current.isTouchDevice()` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ separates Mobile-Layout mit touch-friendly Compose-Box-Buttons. **In 1.9.983 enthalten** (verifiziert: `isTouchDevice` existiert in `PluginScreen.kt`).
- Falls der Nutzer noch eine вҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®ltere Version als 1.9.983 hat, sollte er updaten. Der Fix erklвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®rt, warum der Nutzer es вҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—ber Cloud-Profil auf dem Handy zum Laufen brachte.

### Recherche: "nur webstreamr-Quellen, nicht Filmpalast" вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә mвҖ“ДҸвҖңДҸвҖ“ГӯВ¬вҲӮgliche Ursachen (Aug 2026)
Da webstreamr (Stremio-Addon, serverseitig) Quellen liefert, mein Filmpalast-Scraper (Cloudstream-DEX) aber nicht, sind die Scraper-Logs nвҖ“ДҸвҖңДҸвҖ“ГӯВ¬вҲӮtig. MвҖ“ДҸвҖңДҸвҖ“ГӯВ¬вҲӮgliche Ursachen (in absteigender Wahrscheinlichkeit):
1. **Scraper wird aufgerufen, aber `load()` schlвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®gt fehl** вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ `loadResponse` null вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ `executeTmdbProvider` "both load() paths failed" вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ emptyList. KвҖ“ДҸвҖңДҸвҖ“ГӯВ¬вҲӮnnte ein Kotlin-spezifisches Problem sein (Jsoup-Selektor-Unterschied zu Python-Regex, oder Exception in `fetchTmdbMeta`/`searchFilmpalast`).
2. **Scraper ist nicht in `enabledScrapers`** вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә Plugin-Download fehlgeschlagen, oder `manifestEnabled` false, oder Toggle aus. (Weniger wahrscheinlich, da `status=1` verifiziert und Plugin sichtbar ist.)
3. **`loadLinks` findet Hoster aber `loadExtractor` liefert 0 Links** вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә Filmpalast rotiert Hostnamen; wenn nur nicht-built-in-Hoster (vidaraa.cc etc.) online, fвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®llt alles durch. (Mein generischer Fallback fвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®ngt nur direkte mp4/m3u8.)
- **Ohne Logcat nicht eindeutig trennbar.** Logcat-Filter die helfen: `ExtExtractorRegistry`, `ExternalExtensionRunner`, `PluginManager`, `TmdbProvider Filmpalast`, `ExtExtRunner`.


Selbst bei korrekt aktiviertem Cloudstream-Scraper zeigt ARVIO oft "keine Streaming-Addons eingerichtet". Ursache ist eine ARVIO-seitige LogiklвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—cke:
- `StreamRepository.getStreamAddons` (StreamRepository.kt:1440): `if (addon.runtimeKind != RuntimeKind.STREMIO) return@filter false` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ **nur Stremio-Addons** kommen in die Stream-Auswahl.
- `DetailsViewModel` berechnet `hasStreamingAddons` aus `streamRepository.installedAddons.count { it.isVodStreamingAddon() }` (DetailsViewModel.kt:1633) вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ zвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®hlt **nur Stremio-Addons**, nicht Cloudstream-Scraper.
- Cloudstream-Scraper sind eine **getrennte Liste** (`PluginManager.scrapers`), nicht in `installedAddons` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ werden fвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—r `hasStreamingAddons` nicht gezвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®hlt.
- **Aber:** `DetailsViewModel` (DetailsViewModel.kt:1516) ruft `pluginManager.executeScrapersStreaming()` separat auf вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ Cloudstream-Scraper **laufen im Hintergrund** und mergen Streams in `streams`. Nur die *Meldung* ist falsch, nicht das Scraping.
- **Workaround:** ZusвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®tzlich ein (Dummy-)Stremio-Addon aktivieren вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ `addonCount > 0` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ `hasStreamingAddons=true` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ Meldung verschwindet. Scraper-Ergebnisse erscheinen dann in der Liste.
- **ARVIO-seitiger Fix nвҖ“ДҸвҖңДҸвҖ“ГӯВ¬вҲӮtig:** `getStreamAddons`/`hasStreamingAddons` sollten auch EXTERNAL_DEX-Scraper zвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®hlen. Lohnt als GitHub-Issue.

### Build (lokal)
JDK 17+ und Android SDK 35 nвҖ“ДҸвҖңДҸвҖ“ГӯВ¬вҲӮtig. Im Env: `JAVA_HOME` + `ANDROID_HOME` (oder `local.properties` mit `sdk.dir`).
```
./gradlew make makePluginsJson
# -> FilmPalast/build/FilmPalast.cs3
# -> build/plugins.json
```

## Schritt-fвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—r-Schritt: Diagnose-Log auslesen (v1.2+)

Das Plugin schreibt jeden Schritt des Filmpalast-Scrapers in einen internen Trace und stellt ihn вҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—ber einen lokalen HTTP-Server auf `http://localhost:8420/` bereit. So liest du das Log:

1. **Neues Plugin in ARVIO laden.** ARVIO-Einstellungen вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ Plugins & Extensions вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ Filmpalast aktualisieren/einschalten. Ab v1.2 startet beim Laden des Plugins automatisch der Diagnose-Server (im ARVIO-Prozess, nur loopback).
2. **Quellensuche auslвҖ“ДҸвҖңДҸвҖ“ГӯВ¬вҲӮsen** (das, was bisher leer blieb): вҖ“ДҸвҖңДҸвҖ“ГӯвҖҡГ„ДҸffne in ARVIO z.B. "Matrix" (Film) oder "Silo" (Serie) вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ "nach Quellen suchen". Das triggert ARVIOs Aufruf von `load()`/`loadLinks()` und erzeugt Trace-EintrвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®ge.
3. **Log im Handy-Browser ansehen:** вҖ“ДҸвҖңДҸвҖ“ГӯвҖҡГ„ДҸffne einen Browser auf **demselben GerвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®t**, auf dem ARVIO lвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®uft (Chrome/Firefox), und gehe zu `http://localhost:8420/`.
   - Die Seite aktualisiert sich automatisch alle 3 Sekunden.
   - `http://localhost:8420/raw` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ reiner Text (zum Kopieren).
   - `http://localhost:8420/clear` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ Trace lвҖ“ДҸвҖңДҸвҖ“ГӯВ¬вҲӮschen (vor einer neuen Suche).
4. **Trace lesen / interpretieren:**
   - **Gar kein Trace-Eintrag** nach einer Suche вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ ARVIO ruft den Scraper nicht auf (ARVIO-Seite: `manifestEnabled`/`enabledScrapers`/`supportsType`). Der Diagnose-Server selbst sollte aber beim Plugin-Laden "listening on http://localhost:8420" geloggt haben вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңД–вҖ“ГӯвҖҡГ„Гә taucht das nicht auf, lief das Plugin gar nicht.
   - `load: could not parse TMDB input` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ ARVIO ruft `load()` mit einem Format auf, das wir nicht erwarten.
   - `fetchTmdbMeta: request threw ...` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ TMDB-Erreichbarkeit/Key-Problem.
   - `searchFilmpalast: CSS selector matched 0 elements` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ Filmpalast-Seitenstruktur hat sich geвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®ndert (Jsoup-Selektor veraltet) ODER Bot-Schutz/403.
   - `load: after matchResults -> 0 matches` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ Suche liefert Treffer, aber `matchResults` filtert alle raus (Titel-Normalisierung zu streng).
   - `loadLinks: 0 links -> returning false` вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ `collectHosterLinks` findet nichts (Selektor/`data-player-url`-Attribut geвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®ndert).
   - `loadExtractor('...') -> matched=false` fвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—r ALLE Links вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖңвҲӮвҖ“ГӯвҖҡГ„Гҙ keine built-in Extractoren fвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—r die aktuellen Hoster-Domains.
5. **Log fвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—r mich aufheben:** Entweder den `/raw`-Text kopieren und in der nвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®chsten Session einfвҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—gen, ODER die gespiegelte Datei `Android/data/com.arflix.tv/files/arvio-addon-logs/filmpalast-trace.log` (ab Android 13 evtl. nur вҖ“ДҸвҖңДҸвҖ“ГӯвҖ”Д—ber ADB erreichbar).
6. **Falls der Browser die Seite nicht lвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®dt:** Server lвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®uft nur, solange der ARVIO-Prozess lebt. ARVIO zwischendrin nicht beenden. Alternativ via ADB: `adb forward tcp:8420 tcp:8420` dann am PC `curl http://localhost:8420/raw`.

## Versionshistorie dieses Addons

- **v1 (Proof-of-Concept):** Filmpalast-Plugin als TmdbProvider. Baut & kompiliert. Noch nicht in ARVIO endgeraet-getestet.
- **v1.1 (Aug 2026, Commits b6e3c1b bis 8aa09d3):** Hoster-Extraktion gefixt (loadLinks respektiert loadExtractor-Return; Voe1 entfernt; generischer Fallback fuer unbekannte Hostnamen); endgeraet-getestet in ARVIO 1.9.983 (sideload) von Nutzer. Plugin laedt, ist sichtbar & aktivierbar. **Aber:** bei Quellensuche (Matrix/Silo) zeigt ARVIO nur webstreamr-Quellen, nicht Filmpalast - Root-Cause offen, Logcat vom Geraet noetig (siehe "AKTUELLER STAND" ganz oben). AGENTS.md umfassend mit ARVIO-Scraper-Pfad, Touch-Bug-Fix #502, Test-Funktion-Status und Logcat-Optionen dokumentiert.
- **v1.2 (13.08.2026):** Selbst-Diagnose-Modus statt Logcat. `DebugLog.kt` + `DebugServer.kt` (lokaler HTTP-Server `localhost:8420`), `FilmpalastProvider` vollstвҖ“ДҸвҖңДҸвҖ“ГӯвҖқВ®ndig instrumentiert, Version auf 2 gebumpt. Ersetzt Logcat-Zugang fuer unseren eigenen Scraper-Code. Siehe "Schritt-fuer-Schritt: Diagnose-Log auslesen".
- **v1.3 (13.08.2026, Commits bis ca9f81f):** Diagnose-Tooling massiv ausgebaut, aber **Kernerkenntnis: ARVIO ruft .cs3-Plugins auf dem Geraet GAR NICHT auf.** Beweise: (a) GermanProviders (bewaehrtes .cs3-Repo) liefert auf dem Geraet ebenfalls 0 Quellen, (b) unsere v6-v8 haetten bei JEDEM loadLinks-Aufruf ArvioAddon-Debug-Quellen emittieren muessen - erschienen nie, (c) GitHub-Issues #459/#273 berichten exakt dasselbe Symptom. Webstreamr (Stremio-Addon) funktioniert = anderer ARVIO-Code-Pfad. Versionen: v3 DebugServer auf 127.0.0.1; v4 File-Trace+PLUGIN_LOADED Marker; v5 MediaStore->public Download; v6 Diagnose als Pseudo-Quellen in ARVIO-Quellenauswahl; v7 load() gibt nie null zurueck (debugLoadResponse) damit loadLinks garantiert laeuft; v8 Per-Call-Netzwerk-Timeouts. ARVIO library (TmdbProvider/MainAPI/Plugin) verifiziert vorhanden in classes3/4.dex. ARVIO-Timeouts (120s/60s) schliessen Timeout als Ursache aus. **Naechster Schritt: mit Laptop weiter (Logcat via USB+adb); ggf. GitHub-Issue bei ARVIO.** Siehe "AKTUELLER STAND" ganz oben.
- **v9-v13 (14.08.2026, Logcat-Aera):** Nach USB-ADB+Logcat am TV: Erkenntnis #1 (.cs3 nie heruntergeladen bei Cloud-Sync) вҖҡГңГӯ Erkenntnis #2 (kotlin/io/FilesKt von R8 geshrinkt) вҖҡГңГӯ FIX #2 (v9: kotlin-stdlib-IO entfernt) вҖҡГңГӯ Erkenntnis #3 (DebugServer-Thread-Crash) вҖҡГңГӯ FIX #3 (v10: DebugServer removed) вҖҡГңГӯ Erkenntnis #4 (kotlin.collections.SetsKt von R8 geshrinkt) вҖҡГңГӯ FIX #4 (v11: kotlin-stdlib in .cs3 gebundled) вҖҡГңГӯ Erkenntnis #5 (mainPageOf von R8 geshrinkt) вҖҡГңГӯ FIX #5 (v12: listOf(MainPageData)) вҖҡГңГӯ Erkenntnis #6 (MainPageData-ctor von R8 geshrumpft) вҖҡГңГӯ FIX #6 (v13: mainPage komplett entfernt). v13 laedt erstmals VOLLSTAENDIG (Provider+Extractoren registriert, "API loaded" bestвҲҡВ§tigt).
- **Erkenntnis #7 (14.08.2026, v13-DEX+APK-Analyse):** **Root-Cause gefunden.** ARVIOs R8 hat `kotlin.coroutines.Continuation` zu `j7.d` obfuscated. Unsere suspend-Override-Methoden (load/loadLinks/search) haben `Lkotlin/coroutines/Continuation;` in der Signatur, ARVIOs Parent hat `Lj7/d;` вҖҡГңГӯ JVM findet Override nicht вҖҡГңГӯ parent laeuft вҖҡГңГӯ `ErrorLoadingException: No id found` вҖҡГңГӯ 0 Quellen. **Betrifft ALLE externen .cs3-Plugins.** Geplanter Fix #7: gegen ARVIOs obfuscated cloudstream3-JAR kompilieren (dex2jar aus APK extrahieren). Siehe "ENTSCHEIDENDE ERKENNTNIS #7" oben.
- **v14 (14.08.2026, Commit 829c057):** **Post-Build DEX-Patching fuer R8-obfuszierte Typen (Fix #7).** Ansatz 1 (gegen obfuszierte dex2jar-JAR kompilieren) wurde verwendet; Override-Signaturen korrekt obfusziert (load=(Ljava/lang/String;Lj7/d;)...). v14 live auf builds (1.268.540 bytes).
- **Erkenntnis #8 + v15 (14.08.2026):** v14-TV-Test (arvio-tv-log-v14.txt) zeigte: DEX ist KAPUTT вҖ” ART-Verifier lehnt ab ("Failure to verify dex file: Non-zero padding b before section of type 8196 at offset 0x3111d2"). Root-Cause: dex2jar-Klassen (j7/d, j7/j, x7/l) wurden mit in die DEX gebuendelt und korrumpten deren Struktur. **Fix #8 (v15):** zurueck zum unobfuszierten Stub (keine dex2jar-Klassen -> valide DEX) + Post-Build-DEX-Patching (4 Typ-Strings). DexClassLoader parent-first-Delegation loest j7/d auf ARVIOs eigene Klasse -> Override-Deskriptoren matchen -> Dispatch bindet. CI baut v15 beim Push auf main. **Test auf TCL C7K TV ausstehend** (Windows 10 Anleitung: `docs/windows-10-test-guide.md`, Log-Datei `arvio-tv-log-v16.txt`).

---

## вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖқД®вҖ“ГӯвҖңГі KONSOLIDIERUNGS-PLAN: Alle Scraper ins .cs3-Plugin (Stand 16.08.2026, fuer naechste Session)

### Entscheidung (Nutzer, 16.08.2026)
Langfristig sollen **alle Scraper im .cs3-Plugin** konsolidiert werden (ein System statt zwei). Das Stremio-Addon (ReichiMD/Stremio-Addon) bleibt als **funktionierendes Backup**, bis alle Scraper im .cs3-Plugin laufen. Nicht das funktionierende System wegwerfen, bevor das neue vollstaendig laeuft.

### Warum Konsolidierung auf .cs3-Plugin sinnvoll ist
1. **Clientseitig (Heim-IP):** kein Render-403 bei KinoGer, kein Bot-Schutz durch Rechenzentrums-IP. KinoGer wuerde sofort funktionieren (Heim-IP wird nicht blockiert).
2. **Eine Codebasis pflegen** statt zwei (TypeScript + Kotlin).
3. **Config-Seiten moeglich:** Cloudstream3-Plugins koennen UI-Settings haben (fuer Stalker-VOD: Portal-URL + MAC-Eingabe вҖ” das war der Grund, warum wir Cloudstream3 statt Nuvio-JS gewaehlt haben).
4. **Vavoo Viewer-IP-Trick entfaellt:** Im .cs3-Plugin laeuft alles direkt auf dem TV вҖ” die Heim-IP ist automatisch die richtige IP. Der komplizierte X-Forwarded-For/IP-Rewrite-Trick aus dem Stremio-Addon (tvvoo) ist nicht noetig.

### Risiken / Einschraenkungen (ehrlich dokumentiert)
1. **.cs3-Plugin war extrem schwer zu bauen:** 25+ Versionen bis FilmPalast lief. Jeder neue Scraper kann neue R8-Probleme aufdecken (Obfuskation, geschrumpfte libs).
2. **Vavoo Live-TV als .cs3-Plugin = Spezialfall:** Cloudstream3-Plugins sind fuer VOD (Filme/Serien) gebaut, nicht fuer Live-TV-Kataloge. ARVIO hat dafuer einen eigenen Stremio-Addon-Code-Pfad. Ob ein .cs3-Plugin Live-TV-Kanaele als Katalog liefern kann, ist **NICHT verifiziert**. Zuletzt portieren, ungewisser Ausgang.
3. **Doppelte Arbeit pro Hoster-Extraktor:** Jeder Extraktor muss in Kotlin (.cs3) UND TypeScript (Stremio-Addon) gepflegt werden, solange beide Systeme laufen. Die Algorithmus-Recherche (resolveurl Python) faellt nur einmal an, das Uebersetzen in beide Sprachen ist mechanisch.

### EMPFOHLENE REIHENFOLGE (schrittweise, nicht alles auf einmal)

#### PHASE 1 вҖ” KinoGer als .cs3-Plugin (hoechste Prioritaet, grosster sofortiger Mehrwert)
- **Warum zuerst:** KinoGer wird auf Render 403-blockiert (Server-IP). Im .cs3-Plugin (Heim-IP) wuerde es sofort funktionieren. Groesster sofortiger Nutzen.
- **Vorlage:** `src/source/KinoGer.ts` im Stremio-Addon (TypeScript) + xStream/michaz `script.module.xstreamscraper` (Python).
- **Aufbau:** TmdbProvider (wie FilmPalast), java.net-HTTP + Jsoup (kein app.get/okhttp вҖ” R8-Obfuskation umgehen, wie bei FilmPalast v21+).
- **Estimate:** Mittel. KinoGer-Scraper-Logik ist aehnlich wie FilmPalast (Suche + Stream-Seite + Hoster-Links).

#### PHASE 2 вҖ” Vavoo Filme/Serien als .cs3-Plugin
- **Warum:** API-basiert (MediaHubMX), kein Bot-Schutz, zuverlaessig. Gleiche API wie Stremio-Addon, aber clientseitig.
- **Vorlage:** `src/source/Vavoo.ts` im Stremio-Addon (TypeScript, ~297 Zeilen). Flow: POST ping -> addonSig -> POST mediahubmx-source.json -> Mirror-Liste.
- **API-Endpoints:** `https://www.vypn.net/api/app/ping` (oder `https://cache.vypn.net/api/app/ping`) -> Signatur; `https://vavoo.to/mediahubmx-source.json` -> Mirrors.
- **Ping-Payload:** statische Desktop-Payload (device/OS/app/version-Felder), `ipLocation: null` fuer Filme/Serien (Server-IP reicht, kein Viewer-IP-Trick noetig).
- **Hoster:** Vidara, Vidsonic, Vidoza, Firestream, SuperVideo, Dropload, SaveFiles (direkt extrahierbar), DoodStream/VOE/FileMoon (unzuverlaessig, nach unten sortieren).
- **Estimate:** Mittel. API ist simpler als HTML-Scraping, aber Signatur-Caching + Payload-Bau noetig.

#### PHASE 3 вҖ” Weitere Hoster-Extraktoren (profitieren BEIDE Systeme)
- **Warum:** Die fehlenden Streams sind blockierte Hoster (VOE, FileMoon, DoodStream), nicht fehlende Scraper. Bessere Extraktoren helfen .cs3-Plugin UND Stremio-Addon.
- **Vorlage:** Gujal00/ResolveURL (Python, 227 Resolver, siehe RESOLVEURL-REPOS unten).
- **Prioritaet pro Hoster:**
  1. **VOE** (voesx.py): voe_decode (ROT+Base64+Caesar), 200+ Mirror-Domains. Prio 1 (haeufigster Hoster). Algorithmus in AGENTS.md bereits dokumentiert (siehe "VOE-EXTRACTOR: KOMPLETTE LOGIK").
  2. **FileMoon** (filemoon?.py): dekompilieren/Reverse-Engineeren.
  3. **DoodStream** (doodstream.py): dsplayer.hotkeys -> token -> /pass -> mp4. Algorithmus dokumentiert.
  4. **Streamtape** (streamtape.py): linko-Algorithmus.
- **Aufwand pro Hoster:** Python lesen -> Kotlin portieren -> (optional) TypeScript portieren -> curl testen -> TV testen.

#### PHASE 4 вҖ” Stalker-VOD als .cs3-Plugin (wenn Nutzer Portal+MAC hat)
- **Status:** Nutzer hat aktuell keine Portal-URL + MAC-Adresse. Kommt zum Schluss, wenn Daten verfuegbar.
- **Warum es funktioniert (trotz ARVIOs Live-TV-only-Stalker):** Ein .cs3-Plugin bringt seine EIGENE Stalker-VOD-Logik mit (die 17 Methoden aus Ventix). ARVIOs eingebauter Stalker-Client (nur 4 Methoden, nur Live-TV) wird umgangen вҖ” das Plugin spricht direkt mit dem Stalker-Portal (getVodCategories, getVodList, createVodLink, getSeriesList, getSeasons).
- **Vorlage:** `app/src/main/java/com/iptv/stalker/data/api/StalkerApi.kt` im Ventix-Repo (ReichiMD/IPTV-App, 17 Kotlin-Methoden, VOD+Serien+Seasons).
- **Config-Seite:** Cloudstream3-Plugin-Settings fuer Portal-URL + MAC-Eingabe.
- **Kein Bot-Schutz:** Stalker-Portale sind normale HTTP/JSON-APIs (wie FilmPalast, java.net.HttpURLConnection).
- **Estimate:** Mittel. Kotlin-Code fertig in Ventix, muss an Cloudstream3-TmdbProvider-Format angepasst werden.

#### PHASE 5 вҖ” Vavoo Live-TV als .cs3-Plugin (zuletzt, ungewiss)
- **Warum zuletzt:** Cloudstream3-Plugins sind fuer VOD gebaut. Live-TV-Kataloge sind ein Spezialfall, ARVIO hat dafuer einen eigenen Code-Pfad. Ob .cs3-Plugins das koennen, ist NICHT verifiziert.
- **Vorlage:** `src/source/VavooLive.ts` im Stremio-Addon (catalog.json + resolve.json API).
- **Vorteil wenn es klappt:** Kein Viewer-IP-Trick noetig (laeuft direkt auf TV, IP ist automatisch richtig).
- **Risiko:** mainPage-Kataloge in .cs3-Plugins hatten R8-Probleme (Erkenntnis #6, deshalb entfernt). Live-TV-Katalog wuerde mainPage brauchen. Ungewiss ob das funktioniert.
- **Fallback:** Falls .cs3-Plugin Live-TV nicht kann, Stremio-Addon fuer Live-TV behalten (VavooLive.ts funktioniert dort zuverlaessig).

### ZUSAMMENFASSUNG DER PHASEN
| Phase | Scraper | Mehrwert | Aufwand | Status |
|---|---|---|---|---|
| 1 | KinoGer .cs3 | Heim-IP umgeht Render-403 | Mittel | TODO (naechste Session) |
| 2 | Vavoo Filme/Serien .cs3 | API-basiert, zuverlaessig | Mittel | TODO |
| 3 | Hoster-Extraktoren (VOE, FileMoon, etc.) | Mehr Streams in BEIDEN Systemen | Mittel pro Hoster | TODO |
| 4 | Stalker-VOD .cs3 | Komplett neue Quelle! | Mittel | BLOCKED (Portal+MAC fehlen) |
| 5 | Vavoo Live-TV .cs3 | Ein System, kein Render | Hoch/Risiko | TODO (zuletzt, ungewiss) |

---

## вҖ“ДҸвҖ”ДӣвҖ“ГӯвҖқД®вҖ“ГӯвҖңГі QUELLEN-REFERENZEN (alle Scraper-Quellen, Stand 16.08.2026)

### Unser Stremio-Addon (funktionierendes Backup, TypeScript)
- **Repo:** `ReichiMD/Stremio-Addon` (privat, Deployment auf Render: `https://stremio-stream-scraper.onrender.com`).
- **Sprache:** TypeScript / Node.js (CommonJS) + Express.
- **7 Scraper-Quellen registriert** (`src/source/index.ts`): MovieBox, VidSrc, VixSrc, **Vavoo** (Filme/Serien), **KinoGer**, **FilmpalastTO**, Einschalten.
- **Vavoo Live-TV** (`src/source/VavooLive.ts`): separater Service (catalog.json + resolve.json), Viewer-IP-Trick (X-Forwarded-For).
- **Vavoo Filme/Serien** (`src/source/Vavoo.ts`, 297 Zeilen): API-basiert (Ping -> Signatur -> mediahubmx-source.json), kein Bot-Schutz, `contentTypes: ['movie', 'series']`.
- **14 Hoster-Extractoren** (`src/extractor/`): DoodStream, Dropload, ExternalUrl, Firestream, MediaFlow, MovieBox, SaveFiles, SuperVideo, VidSrc, Vidara, Vidoza, Vidsonic, VixSrc, Voe.
- **Bekanntes Limit:** KinoGer wird von Render 403-blockiert (Server-IP). Lokal funktioniert es. .cs3-Plugin (Heim-IP) wuerde es loesen.
- **AGENTS.md des Stremio-Addons:** enthaelt detaillierte Vavoo-API-Doku (Ping-Payload, mediahubmx-source.json Flow, Viewer-IP-Trick, Hoster-Priorisierung).

### Ventix IPTV-App (Stalker-VOD-Vorlage, Kotlin)
- **Repo:** `ReichiMD/IPTV-App` (privat).
- **StalkerApi.kt:** `app/src/main/java/com/iptv/stalker/data/api/StalkerApi.kt` вҖ” 17 Kotlin-Methoden:
  - Auth: `handshake`, `getProfile`, `activateSession`.
  - Live-TV: `getGenres`, `getChannels`, `createChannelLink`, `getAllFavChannels`, `setFavChannels`.
  - **VOD (Filme):** `getVodCategories`, `getVodList`, `createVodLink`, `setVodFav`.
  - **Serien:** `getSeriesCategories`, `getSeriesList`, `getSeasons`, `setSeriesFav`.
  - Sonstiges: `exportM3u`, `getEpgTable`, `sendWatchdog`.
- **ARVIO-Vergleich:** ARVIOs `StalkerApi.kt` hat nur 4 Methoden (handshake, getProfile, getChannels, resolveStreamUrl) вҖ” nur Live-TV, kein VOD. Ventix hat die fehlenden 13 VOD/Serien-Methoden fertig.
- **Stalker-API ist offener Standard:** `http://portal-url/stalker_portal/server/api/...` mit `mac=00:1A:...`, `type=stb`, `action=...`. Normale HTTP/JSON, kein Bot-Schutz.

### resolveurl (Hoster-Extraktions-Logik, Python вҖ” Goldschatz)
- **Repo:** `Gujal00/ResolveURL` (`https://github.com/Gujal00/ResolveURL`). Pfad: `script.module.resolveurl/lib/resolveurl/plugins/<hoster>.py`. Version 5.1.206. Letzter Commit 12.08.2026. AKTUELL.
- **227 fertige Hoster-Resolver** in lesbarem Python (Regex, API-Endpoints, Decrypt-Methoden).
- **Wichtig fuer uns:** voesx.py (VOE, voe_decode), vidsonic.py (hex+reverse, trivial!), firestream.py (token-blob POST API), supervideo.py (packed JS), doodstream.py (dsplayer.hotkeys).
- **Klonen bei Bedarf:** `git clone --depth 1 https://github.com/Gujal00/ResolveURL.git /tmp/resolveurl` (nicht persistent, bei Reset neu klonen).
- **VERALTET (nicht nutzen):** `jsergio123/script.module.resolveurl` (2020, 6 Jahre alt).

### xStream / michaz (Filmpalast + KinoGer + Vavoo Vorlagen, Python)
- **Repo:** `michaz1988/michaz1988.github.io` (`https://github.com/michaz1988/michaz1988.github.io`).
- **Enthaelt:** `script.module.xstreamscraper` (Filmpalast/KinoGer/HDFilme-Scraper, Python), `plugin.video.vavooto` (Vavoo-Kodi-Addon, Vorlage fuer Vavoo-API), `plugin.video.xship` (xStream-Nachfolger).
- **DDoS-Guard-Bypass** (requestHandler.py:255-275): check.js Image-Trick вҖ” ABER veraltet, reicht fuer neuere Serienstream-Turnstile nicht mehr.

### GermanProviders (Cloudstream3-Plugin-Vorlagen, Kotlin)
- **Repo:** `Bnyro/GermanProviders` (`https://raw.githubusercontent.com/Bnyro/GermanProviders/refs/heads/master/repo.json`).
- **21 fertige Cloudstream3-Plugins:** ARD, Aniworld, Arte, C3TV, Discovery, EinschaltenIn, FilmPalast, HDFilme, HuhuTo, IptvOrg, KinoKing, Kinoger, Megakino, Moflix, Netzkino, PlutoTV, Serienstream, Southpark, SpiegelTV, Welt, Xcine.
- **Aufbau-Referenz:** root build.gradle.kts mit cloudstream3-gradle-plugin, pro Provider ein Modul-Ordner. Status=1 (wichtig!).
- **ABER:** GermanProviders-Scraper sind search-based (kein TmdbProvider) вҶ’ ARVIOs fragiles findBestMatch-Title-Matching. Wir nutzen TmdbProvider (direkter Pfad, zuverlaessiger).

### seizu/plugin.video.filmpalast.ex (Filmpalast Kodi-Plugin, Python)
- **Repo:** `seizu/plugin.video.filmpalast.ex` (`https://github.com/seizu/plugin.video.filmpalast.ex`).
- **Nutzt resolveurl** fuer Hoster-Aufloesung. Referenz fuer Filmpalast-Scraper-Logik.

### stubebox / STUBE.BOX.LEGACY 2 (analysiert 16.08.2026, NICHT als Vorlage nutzbar)
- **Quelle:** archive.org вҖһSTUBE.BOX.LEGACY 2" APK (68 MB). Eingebettetes Kodi-Repo: `gok.bplaced.net/.stubeboxle/repo/`.
- **3 Addons:** vavoobox (Vavoo Live-TV), stalker.macsimum (Stalker Live-TV+VOD), script.mediabox (Hilfsskript).
- **NICHT nutzbar:** Alle Addons nutzen вҖһBadEncodeX"-VerschlГјsseler (.pyc mit eval/exec/__import__/_rasputin). Code zur Laufzeit entschluesselt, statisch nicht auslesbar.
- **Vavoo-Auth gefunden:** `https://www.vavoo.tv/api/box/ping2` (proprietГӨre Cloud-API, nicht offen wie MediaHubMX).
- **Vavoo settings.xml:** nur вҖһStream Auswahl", keine Portal/MAC-Eingabe (Vavoo funktioniert anders als Stalker).
- **Fazit:** stubebox beweist, dass Stalker-VOD mit URL/MAC-Wechsel in der Praxis laeuft вҖ” aber der Code ist verschluesselt und nicht als Vorlage nutzbar. Ventix-StalkerApi (offen, Kotlin) ist die bessere Vorlage.

### ARVIO-Referenz (Ziel-App)
- **Repo:** `ProdigyV21/ARVIO` (`https://github.com/ProdigyV21/ARVIO`). Version v1.9.994 (15.08.2026). Sideload-APK fuer Plugin-Support.
- **Klonen:** `git clone --depth 1 https://github.com/ProdigyV21/ARVIO.git /tmp/arvio_ref` (nicht persistent).
- **Obfuskations-Map (verifiziert, v1.9.983 + v1.9.994 identisch):** kotlin.coroutines.ContinuationвҶ’j7/d, CoroutineContextвҶ’j7/j, Function1вҶ’x7/l, FunctionвҶ’d7/o, okhttp3.InterceptorвҶ’rb/c0, ContinuationInterceptorвҶ’j7/g, ContinuationInterceptor.KeyвҶ’j7/f.i (Feld verschoben). Siehe ERKENNTNIS #7-#12.
- **ARVIO StalkerApi** (`app/src/main/kotlin/com/arflix/tv/data/api/StalkerApi.kt`): nur 4 Methoden (handshake, getProfile, getChannels, resolveStreamUrl) вҖ” nur Live-TV, kein VOD.
- **ARVIO IptvRepository** (`app/src/main/kotlin/com/arflix/tv/data/repository/IptvRepository.kt`, 8249+ Zeilen): Xtream Codes VOD (getVodCategories, getSeriesCategories) вҖ” aber Xtream, nicht Stalker. Stalker-VOD fehlt komplett.

---

## вҖ“ДҸвҖңДҸвҖ“ГӯвҖңГөвҖ“ДҸвҖңДҸвҖ“ГӯВ¬вҖ  HANDY + TERMUX: TV-LOGCAT AUSLESEN (Standard-Workflow des Nutzers, Stand 16.08.2026)

### Setup (einmalig)
Der Nutzer liest die ARVIO-Logs vom TCL C7K TV **direkt am Handy** aus (kein Laptop nГ¶tig). Setup-Dokumentation siehe `docs/android-termux-logcat-guide.md` (ausfГјhrlich, 350+ Zeilen).

**Braucht:** Android-Handy mit WLAN (im selben Netz wie der TV) + Termux (NUR von F-Droid, NICHT Play Store вҖ” Play-Store-Version ist veraltet/kaputt).

**Einmalige Schritte:**
1. **F-Droid** installieren: https://f-droid.org вҶ’ Termux suchen + installieren.
2. In Termux: `pkg update` + `pkg install android-tools` (installiert echtes `adb`).
3. **Einmalig pairen** (nur 1x nГ¶tig): Am TV Entwickleroptionen (Build 7x tippen) вҶ’ USB-Debugging AN вҶ’ Wireless Debugging AN вҶ’ вҖһMit GerГӨt paaren" вҶ’ zeigt 6-stelligen Code + IP:Paar-Port. In Termux: `adb pair <IP>:<Paar-Port>` вҶ’ Code eingeben. Pairing-Key bleibt gespeichert.
4. **Download-Ordner-Freigabe** (1x): `termux-setup-storage` вҶ’ Dialog bestГӨtigen. Oder `mkdir -p /storage/emulated/0/Download/arvio-logs` + вҖһErlauben" antippen.
5. **save-tv-log-Skript** installieren (1x): `curl -sL https://raw.githubusercontent.com/ReichiMD/Arvio-Addon/main/docs/save-tv-log.sh -o ~/save-tv-log.sh && chmod +x ~/save-tv-log.sh`. Falls TV-IP nicht `192.168.0.59`: `nano ~/save-tv-log.sh` вҶ’ `TV_IP=` anpassen.

### Standard-Test-Ablauf (jede Session, wenn TV-IP gepaired)
1. Falls TV neu gestartet: am TV Wireless Debugging wieder AN stellen (kein neues Paaren nГ¶tig).
2. In Termux verbinden: `adb connect 192.168.0.59:5555` (IP anpassen, Port meist 5555).
3. Test-Verbindung: `adb devices` вҶ’ sollte `device` zeigen (nicht `unauthorized`).
4. Log-Puffer leeren: `adb logcat -c`.
5. **Am TV:** Repo lГ¶schen + neu hinzufГјgen DIREKT (NICHT Cloud-Sync!) вҶ’ URL `https://raw.githubusercontent.com/ReichiMD/Arvio-Addon/main/repo.json` вҶ’ Scraper einschalten.
6. **Am TV:** Film/Serie Г¶ffnen (z.B. Matrix oder Silo) вҶ’ вҖһnach Quellen suchen" вҶ’ 15s warten.
7. **In Termux (EIN Befehl):** `~/save-tv-log.sh v32` (Version anpassen, z.B. v32 fГјr Serienstream v32-Test). Das Skript macht automatisch:
   - `adb connect` (falls disconnected)
   - `adb logcat -d -v time` вҶ’ rohe Log-Datei (`~/arvio-tv-log-v32.txt`)
   - Filtern (grep) вҶ’ `arvio-tv-log-v32-filtered.txt`
   - Kopieren nach `Download/arvio-logs/`
   - Medienscan (Chat-Apps finden die Datei)
   - Vorschau: erste 20 gefilterte Zeilen im Terminal
8. **Weiterleiten:** Dateimanager вҶ’ Downloads вҶ’ arvio-logs вҶ’ Datei lange drГјcken вҶ’ Teilen вҶ’ in Chat hochladen.

### Der Filter (im save-tv-log.sh, deckt BEIDE Scraper ab)
```
grep -iE "Filmpalast|Serienstream|ArvioAddon|ExternalExtension|ExtExt|PluginManager|No API loaded|ErrorLoading|verify dex|MISSING CLASS|CloudstreamPlugin|Executing DEX|ddg|ddos|guard|resolveHost|resolveVoe|resolveDoodstream|resolveStreamtape|resolveFileMoon|resolveVidHide|genericResolve|emitLink|loadLinks|fetchTmdbMeta|searchSeries|buildSeriesResponse|collectEpisodes|httpGet|httpPost|doRequest|CookieJar|voeDecode|detectQuality"
```
- FilmPalast-Filter: `Filmpalast|ArvioAddon|ExternalExtension|...`
- Serienstream-Filter: `Serienstream|resolveHost|resolveVoe|resolveDoodstream|...|CookieJar|voeDecode`
- ARVIO-Engine: `PluginManager|No API loaded|verify dex|MISSING CLASS|CloudstreamPlugin|Executing DEX`
- Falls ein NEUER Scraper dazukommt (KinoGer, Vavoo): Filter im Skript um `KinoGer|Vavoo|mediahubmx|resolveOdysseusa` erweitern.

### Manuelle Alternative (ohne Skript, falls Skript fehlt)
```
adb connect 192.168.0.59:5555
adb logcat -c
# ... am TV Suche auslГ¶sen, 15s warten ...
adb logcat -d -v time | grep -iE "Filmpalast|Serienstream|ArvioAddon|ExternalExtension|No API loaded|verify dex" > ~/arvio-tv-log-v32-filtered.txt
cp ~/arvio-tv-log-v32-filtered.txt ~/storage/downloads/arvio-logs/
```

### Schnell-Check (ohne Datei, nur im Terminal lesen)
```
adb logcat -d -v time | grep -iE "Filmpalast|Serienstream|ArvioAddon|ExternalExtension|No API loaded|verify dex"
```

### Was die Log-EintrГӨge bedeuten (Kurzauswertung)
| Im Log gesehen | Bedeutung |
|---|---|
| `Executing DEX scraper: FilmPalast` | Scraper wird aufgerufen вҖ” gut |
| `Downloaded extension вҖҰ: N bytes` | Download geklappt |
| `Failure to verify dex file вҖҰ` | DEX kaputt вҶ’ Patch-Skript-Problem |
| `No API loaded for scraper: вҖҰ` | Plugin-Klasse konnte nicht geladen werden |
| `MISSING CLASS` / `NoClassDefFoundError` | fehlt eine Kotlin/cloudstream3-Klasse (R8) |
| `API loaded` / Filmpalast-Quellen | **Erfolg!** Override bindet, Scraper lГӨuft |
| `ErrorLoadingException: No id found` | Parent lГӨuft noch вҶ’ Dispatch nicht gebunden |
| `solveAltcha: PoW solved` (Serienstream) | ALTCHA-PoW funktioniert |
| `redirectGate: POST /r -> 200` (Serienstream) | POST erfolgreich |
| `redirectGate: resolved to https://вҖҰ` (Serienstream) | **ERSTE SERIENSTREAM-QUELLE!** |
| gar kein Scraper-Eintrag | Scraper wird nicht aufgerufen (Enable/Routing/Download) |

### TV-IP: 192.168.0.59 (TCL C7K, verifiziert)
Die TV-IP steht hartkodiert im `save-tv-log.sh` als `TV_IP="192.168.0.59"`. Falls sich die IP ГӨndert (Router-Neustart etc.): TV-IP in Netzwerk-Einstellungen am TV nachschauen + `nano ~/save-tv-log.sh` anpassen.

### Dateien
- `docs/android-termux-logcat-guide.md` вҖ” ausfГјhrliche Schritt-fГјr-Schritt-Anleitung (350+ Zeilen, mit Pairing, Troubleshooting, Kurzauswertung).
- `docs/save-tv-log.sh` вҖ” Ein-Klick-Skript (Logcat auslesen + filtern + kopieren + Medienscan + Vorschau).
- `docs/handy-logcat-ladb-termux.md` вҖ” Alternative mit LADB/Shizuku (falls WLAN-ADB mal nicht klappt; LADB-Pairing war frГјher zickig, siehe unten).
- `docs/windows-10-test-guide.md` вҖ” Laptop-Alternative (USB/WLAN-ADB am Windows-PC).

### Bekannte Probleme / Hinweise
- **LADB-App** (Alternative ohne WLAN): Pairing scheiterte frГјher am 30s-Timer (вҖһno devices/emulators found"). WLAN-ADB via Termux ist zuverlГӨssiger. Shizuku (gratis) als stabilere Alternative zu LADB dokumentiert.
- **TV neu gestartet** вҶ’ Wireless Debugging schaltet sich AB. Am TV wieder AN stellen (kein neues Paaren nГ¶tig вҖ” Pairing-Key bleibt), dann `adb connect` wiederholen.
- **`unauthorized`** bei `adb devices` вҶ’ TV zeigt вҖһUSB-Debugging zulassen?"-Dialog, am TV bestГӨtigen.
- **ARVIO auf Handy** = gleiche sideload-APK wie TV вҶ’ gleiche Obfuskation вҶ’ Tests auf Handy reprГӨsentativ fГјr TV (aber Handy-UI-Bug bei Add-Repo behoben in v1.9.994, siehe unten).

---

## Referenz-Notiz: Externe Stremio-Addons (19.08.2026, Recherche vom Nutzer)
- **PenguPlay (pengu.uk):** kostenloses Stremio-Addon (HTTP-Scraper, kein Debrid/Torrent noetig). Config-Website erzeugt manifest.json-URL (Google-Login noetig). 16 Provider: 4KHDHub, MovieBox, VegaMovies, MoviesDrives, Miruro/Antova (Anime), CineFreak, MKVBase, VAPlayer, VidKing, ZXCStream, VidLink, VidFast, HDGharTV, Cinejoy, 2Peckle. Fokus: englischer Mainstream, Bollywood/Regional, Anime, Asian Drama. **KEINE deutschen Inhalte** (kein deutscher Scraper dabei; nur vereinzelt Multi-Audio-Spuren). Fuer ARVIO als Ergaenzung fuer internationale Inhalte nutzbar (als Stremio-Addon, nicht .cs3) - fuer Deutsch bleiben unsere eigenen .cs3-Plugins (KinoGer, FilmPalast, Vavoo) die Loesung.

---

## Stalker-Middleware: Verifizierter Stand (19.08.2026, im aktuellen ARVIO-main + Ventix-Repo nachgeprueft)

**Trennung Live-TV vs. VOD ist real (zwei getrennte Wege):**
- **ARVIO eingebauter Stalker-Client = NUR Live-TV.** Verifiziert in `app/src/main/kotlin/com/arflix/tv/data/api/StalkerApi.kt`: exakt 4 Methoden (handshake, getProfile, getChannels, resolveStreamUrl). Kein VOD, keine Serien. TvScreen.kt loest Stalker-Portal-cmds zu Stream-URLs auf.
- **Stalker-Config-UI fehlt weiterhin in ARVIO** (Stand main, 19.08.2026): `saveStalkerConfig(portalUrl, macAddress)` existiert in SettingsViewModel.kt:2295 + IptvRepository.kt:603, wird aber von KEINEM UI-Element aufgerufen (grep bestaetigt: nur Definition, kein Aufruf in Screens). Stalker-Live-TV in ARVIO daher praktisch nicht nutzbar; Live-TV laeuft bei ARVIO nur via M3U/Xtream (dafuer UI vorhanden).
- **ARVIO ruft `openSettings` fuer .cs3-Plugins NIE auf** (verifiziert: `var openSettings` in sideload `Plugin.kt:24` definiert, aber kein einziger Aufruf in ganz ARVIO). -> Plugin-eigene Config-Seiten sind in ARVIO NICHT sichtbar, auch wenn das Cloudstream3-Format sie vorsieht.

**Konsequenz fuer unser Stalker-VOD-Modul (Konsolidierungs-Phase 4):**
- Ventix `StalkerApi.kt` (ReichiMD/IPTV-App, `app/src/main/java/com/iptv/stalker/data/api/`) verifiziert vorhanden, 17+ Methoden: handshake, getProfile, activateSession, getGenres, getChannels, createChannelLink, getAllFavChannels, setFavChannels, **getVodCategories, getVodList, createVodLink, setVodFav** (VOD), **getSeriesCategories, getSeriesList, getSeasons, setSeriesFav** (Serien), exportM3u, getEpgTable, sendWatchdog. Plus AuthManager/ResponseParser/StalkerInterceptor/StalkerSession im selben Ordner.
- **Config ohne UI loesen via Config-Datei** (z.B. `stalker.json` im Download-Ordner, Plugin liest sie beim Start, Nutzer editiert mit Dateimanager). Alternativen: Portal+MAC beim Build fest einbacken, oder ARVIO-Issue (fehlende Stalker-UI + openSettings).
- **Xtream-Abkuerzung:** ARVIO hat eingebaute Xtream-Codes-Unterstuetzung (VOD+Live+UI). Falls der Anbieter des Nutzers auch Xtream-Zugang (Server-URL+User+Pass) bietet, waere VOD sofort ohne Plugin nutzbar. Beim Anbieter nachfragen.
- **Weiterhin BLOCKED:** Nutzer braucht Portal-URL + MAC-Adresse vom Anbieter.

**Hinzu verifiziert (19.08.2026, Fragen: Plugin injiziert Stalker-Login? Mehrere Logins?):**
- **Stalker-Config-Speicher (verifiziert):** `IptvRepository.saveStalkerConfig` schreibt in Preferences-DataStore (Protobuf). Portal-URL AES-256-GCM-verschluesselt mit Android-Keystore-Key (Alias CONFIG_KEY_ALIAS, selbstgeneriert). M3U/EPG-URLs ebenfalls verschluesselt (ENC_PREFIX + `ivB64:dataB64`). MAC-Klartext. Config-Keys sind PER-PROFIL (profileId im Key).
- **Plugin-Injection theoretisch moeglich, praktisch fragil:** Plugin laeuft im ARVIO-Prozess (gleiche UID) -> koennte Keystore-Alias nutzen + DataStore-Datei schreiben. ABER: Protobuf-Format, DataStore cached in-memory (externe Edits werden ueberschrieben/erst nach Neustart sichtbar), per-profile Keys, R8-Obfuskation der internen Klassen. -> Hack, bricht bei Updates. Harter Fix = ARVIO-UI (GitHub-Issue, ~15 Zeilen). Workaround HEUTE: Stalker-Portal->M3U-Link (viele Portale liefern M3U; Ventix exportM3u als Vorlage) -> ARVIO M3U-Playlists (UI vorhanden).
- **Mehrere Logins (verifiziert):** M3U/Xtream = **bis zu 3 Playlists** gleichzeitig (`savePlaylists`: normalize, `.take(3)`, enabled-Flag pro Eintrag). Stalker = **1 Login pro Profil** (ein portalUrl+macAddress-Paar in IptvConfig). ARVIO **Profile** (ProfileRepository/ProfileSelectionScreen) = mehrere Logins ueber mehrere Profile moeglich (jede Profil-ID eigene Config).
- Xtream-Eingabe wird in kanonische M3U-URL konvertiert (`Accept common Xtream Codes inputs and convert to a canonical M3U URL`).

---

## Stalker Portal des Nutzers: ERFOLGREICH GETESTET (19.08.2026) - UNBLOCKED

**Wichtig:** Nutzer hat mittlerweile Portal+MAC (Portal `http://a01.live:8080/c/`, MAC-Form `00:2A:01:97:13:EC`). Phase 4 ist NICHT MEHR BLOCKED.
- **ASN-Block:** Der Anbieter sperrt Cloud/Rechenzentrums-IPs (tuerk. „ASN'inizden erisim engellenmistir"). Test muss vom HEIMNETZ des Nutzers laufen (Termux auf Handy). Scripte in `docs/test-stalker.sh` / `test-stalker2.sh` / `test-stalker3.sh` / `test-stalker4.sh` (keine Credentials im Repo!).
- **API-Basis verifiziert (Handy-Test):** `http://a01.live:8080/server/load.php` (Ventix-Regel: `/c/` wegstreifen -> `/server/load.php`). Handshake -> Token OK, Profil OK.
- **Inhalt:** 44 Kategorien (alle deutsch: Deutschland/HEVC/RAW/UHD/RTL+/Sky Cinema/Regional/Doku/News/Musik/Sky Bundesliga/DAZN/Champions League/Sport/Sky Sport/MyTeam Sport/DEL.2 Event), **2116 Kanaele**. Kanal-JSON beim Nutzer auf Handy in ~/stalker-channels.json.
- **M3U Direkt-Export (`type=itv&action=export_m3u`): NEIN** (leere Antwort). M3U muss aus Kanal-JSON selbst gebaut werden (wie Ventix exportM3u-Fallback). Stream-URL-Zugriff noch zu verifizieren (ob Kanaele ohne Header direkt abspielbar sind = ARVIO-M3U moeglich).
- **VOD/Serien ungeprueft** (test-stalker4.sh fragt `type=vod`+`type=series` Kategorien ab + testet create_link fuer 3 Kanaele auf direkte HTTP-Erreichbarkeit).
- **Naechste Schritte:** (1) Wenn create_link ohne Header abspielbar -> M3U fuer ARVIO-LiveTV bauen (2116 Kanaele!). (2) Falls VOD/Serien vorhanden -> Stalker-VOD-.cs3-Modul bauen (Phase 4, Ventix-StalkerApi als Vorlage, Config via Download-Ordner-JSON).

**Test 2+3 Ergebnis (19.08.2026, Handy, alles positiv):**
- **VOD: JA.** 40 deutsche Kategorien (Vision Kino 2025-2026, Kino, IMDB Top 250 HQ, Prime Filme, Apple TV, Action/Komoedie/Horror/Drama...). Beispiele: Toy Story 5 (2026), Karate Tiger u.v.m. Titel-Liste in ~/stalker-vod.json auf Nutzer-Handy.
- **Serien: JA.** 29 deutsche Kategorien (Netflix, Paramount+, Prime, Disney+, Drama, Krimi, IMDB Top 100...).
- **Live-Streams: DIREKT ABPIELBAR, ohne play_token!** create_link -> 302 -> CDN `http://192.142.24.175:8080/live/play/<token64>/<streamId>` -> 200 video/mp2t. Wichtig: `play/live.php?mac=<MAC>&stream=<id>&extension=ts` funktioniert AUCH OHNE play_token (verifiziert 3 Kanaele, alle 200). -> M3U-URL-Schema stabil: `$ROOT/play/live.php?mac=<MAC>&stream=<id>&extension=ts`. Stream-IDs aus Kanal-cmd `ffmpeg http://localhost/ch/<id>_`.
- **ARVIO akzeptiert M3U nur via http(s)-URL** (`validatedIptvHttpUrl`, kein file://, kein File-Picker). -> M3U-Datei liegt auf Handy, wird per `python -m http.server 8088` im eigenen WLAN bereitgestellt, ARVIO holt `http://<handy-ip>:8088/stalker-playlist.m3u`. Alternativ Termux direkt auf dem TV.
- **build-stalker-m3u.sh** (docs/) baut die Playlist lokal aus ~/stalker-channels.json. NIEMALS die .m3u (enthaelt MAC) oeffentlich hochladen.
- **Nutzer lehnt Handy-Server-Loesung ab** („keine gute Loesung" - will einmal eingeben und fertig). -> Handy-Termux-Server nur als Notfall-Fallback dokumentiert lassen.

**Alternativen recherchiert (19.08.2026):**
- **TiviMate hat eingebauten Stalker-Client** (Playlist-Typ „Stalker Portal": nur Server-Adresse + MAC, kein M3U/Server noetig). Free-Version: nur 1 Playlist (reicht fuer 1 Portal). Premium ~$10/Jahr oder ~$30 Lifetime (5 Geraete, unbegrenzte Playlists, Aufnahme, EPG-Auto-Sync). Kostenlose Alternative: STBemu (MAG-Box-Emulation, Portal+MAC).
- **PRIORITAET: Anbieter nach Xtream-Codes-Zugang fragen** (Server + Benutzername + Passwort). ARVIO kann Xtream NATIV mit UI (Live+VOD+Serien) - dann alles in ARVIO ohne Plugin/Server. Viele Stalker-Anbieter betreiben parallel Xtream auf demselben Server.
- **M3U-Weg bleibt Fallback** (nutzt das verifizierte URL-Schema `play/live.php?mac=...&stream=<id>&extension=ts`, play_token NICHT noetig).

**Feature-Vergleich + VOD-Prioritaet (19.08.2026, aus ARVIO-Code gelesen):**
- **TiviMate/STBemu = closed source** (Code nicht lesbar). Vergleich nur ueber ARVIO-Code moeglich.
- **ARVIO-Stalker ist minimal:** StalkerApi nur 4 Methoden (handshake/getProfile/getChannels/resolveStreamUrl). KEIN EPG vom Portal, kein Catch-up, kein VOD, keine Aufnahme. Auch nach UI-Fix waere ARVIO-Stalker nur "Kanaele+Play". TiviMate (Premium hat der Nutzer!) bleibt fuer Stalker-LiveTV das staerkere Geraet.
- **ARVIO Xtream ist dagegen vollstaendig:** VOD+Serien+EPG+Catchup in IptvRepository. Wenn Anbieter Xtream anbietet -> ARVIO all-in-one nativ.
- **Quellen-Merge in ARVIO (verifiziert DetailsViewModel.kt):** vodAppendJob (iptv_xtream_vod + HomeServer = "supplemental", werden ans ENDE gehaengt) + pluginScraperJob (.cs3) + Stremio laufen PARALLEL, alles landet in einer Liste (`progressive.streams + existingVod`, sortPlayableStreamsFirst sortiert nur pending-debrid nach unten, sonst keine Anbieter-Prioritaet). -> "IPTV zuerst" nicht nativ; Annäherung moeglich: unser VOD-Plugin meldet hohe Qualitaet (1080p) -> AutoPlaySourcePlanner.qualityScoreForAutoPlay bevorzugt es automatisch. Feste Anbieter-Prioritaet = ARVIO-Feature-Request (Issue-Liste).
- **Nutzer-Entscheidung Live-TV:** TiviMate Premium vorhanden -> Live-TV via TiviMate (oder spaeter Xtream-in-ARVIO). VOD-Plugin fuer Filme/Serien in ARVIO ist das naechste Bauprojekt.

**ARVIO-Stalker-UI: Warum fehlt sie? (recherchiert 19.08.2026):**
- Stalker kam in v1.9.4 (25.03.2026) als Teil eines Riesen-Releases (Trailer, IPTV-Kategorien, Player-Umbau). Backend (`saveStalkerConfig`) da, UI-Dialog nie gebaut. Release-Notes: "Stalker portal (MAC address) support".
- **NULL GitHub-Issues zu Stalker bei ARVIO** - nie nachgefragt, deshalb nie priorisiert. Keine technische Sperre, keine Absicht - einfach vergessen/halbfertig.
- UI-Screens mit "Stalker": nur TvViewModel/TvScreen (Playback-Aufloesung) + SettingsViewModel (saveStalkerConfig, ungenutzt). Kein Settings-Screen mit Stalker-Eingabe.

**Fork-Strategie (NEU, wichtig):** ARVIO forken + Stalker komplett ausbauen (UI + VOD/Serien/EPG aus Ventix-StalkerApi portieren) + Pull Request an ProdigyV21/ARVIO = SAUBERER Weg. **R8-Problem ENTFAELLT KOMPLETT bei Fork** (eigener Code wird mit-einkompiliert, Obfuskation konsistent). ARVIO nimmt externe PRs regelmaessig an (Himanth-reddy etc.), README begruesst AI-gestuetzte Beitraege. Fork-Vorgehen: JDK 17+Android SDK 35 (wie lokal dokumentiert), Sideload-Flavor bauen. Kandidat-Beitraege: (1) Stalker-UI-Dialog -> saveStalkerConfig verdrahten, (2) StalkerApi um VOD/Serien/EPG erweitern, (3) spaeter: R8-keep-Regeln fuer .cs3-Plugins, (4) Cloud-Sync-.cs3-Download-Bug.

**Fork-Projekt-Setup (beschlossen 19.08.2026):**
- Fork: `ReichiMD/ARVIO` (Fork-Button oder API). Branch: `feat/stalker-portal` (nie main).
- **Test-APK parallel installierbar** (eigene applicationId-Suffix, z.B. `.stalker`) -> offizielles ARVIO bleibt unberuehrt. Debug-Build braucht KEINE ARVIO-Geheimnisse (Discord-SDK optional via hasDiscordSdk-Check; secrets.defaults.properties als Platzhalter). Eigener kleiner Workflow im Fork baut APK als Actions-Artifact (Download: Fork -> Actions -> Lauf -> Artifact). Optional spaeter stabiler Release-Link.
- **AGENTS.md-Trennung:** Fork bekommt eine FRISCHE, kleine AGENTS.md (nur Fork-Projekt). **Vor PR-Einreichung aus dem Branch entfernen** -> PR bleibt sauber. Diese grosse AGENTS.md hier bleibt das Gedaechtnis fuer Plugin-Arbeit; Archivierung der Historie nach docs/AGENTS-ARCHIV.md geplant.
- **ARVIO CI-Wissen:** build-check.yml = Unit-Tests bei PR (testSideloadDebugUnitTest, secrets.defaults.properties). Signierter Release-APK braucht deren Secrets (Supabase/TMDB/Keystore/Discord-SDK via privatem Repo) - haben wir nicht, brauchen wir nicht. Discord-SDK optional (`hasDiscordSdk = aar.isFile`).
- **PR-Regeln (ARVIO):** Englisch, klein+fokussiert (2 PRs: erst UI, dann VOD/Serien/EPG), AI-Disclosure, deren Commit-Stil (feat:/fix:), Apache-2.0. Fork ist oeffentlich -> NIEMALS Credentials/MAC committen.
- **Test-Pflichten Nutzer (TV):** Test-APK installieren, Portal+MAC eingeben, Live-TV zappen, VOD/Serien, Regression (Scraper+Suche laufen noch).