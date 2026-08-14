# AGENTS.md â Ventix Arvio Addon

Dieses Repo baut ein **Cloudstream3-kompatibles Plugin** fÃ¼r die **ARVIO** Android-TV-App (sideload-APK).
Ziel: Ventix-FunktionalitÃ¤t (deutsche Web-Scraper + Stalker-VOD) als Plugin in ARVIO laufen lassen â clientseitig, ohne Server.

## â­ AKTUELLER STAND & NÃCHSTE SCHRITTE (Stand 13.08.2026 â fÃ¼r die nÃ¤chste Session)

### ENTSCHEIDENDE ERKENNTNIS DIESER SESSION: ARVIO ruft .cs3-Plugins auf dem GerÃ¤t GAR NICHT auf

**Beweislage (verifiziert, Stand 13.08.2026):**
- Nutzer hat ARVIO 1.9.983 **sideload** auf Android-TV. Plugin-Bereich sichtbar (â sideload bestÃ¤tigt). Toggle bei Filmpalast AN, global alles aktiviert.
- Bei Quellensuche (Matrix, mehrere Filme & Serien) zeigt ARVIO **nur webstreamr-Quellen (Stremio-Addon), NIEMALS Filmpalast** â Ã¼ber alle Plugin-Versionen v2âv8 hinweg, Ã¼ber mehrere Neu-Installationen hinweg (Scraper-IDs Ã¤nderten sich jeweils: eOf699f8â2421c4b6âneu â bestÃ¤tigt frischer Download).
- **GermanProviders-Test (Bnyro/GermanProviders):** Nutzer installierte das bewÃ¤hrte, anderswo funktionierende `.cs3`-Repo, aktivierte alle Scraper â **auch dort KEINE Streams**. Das beweist: Es ist **NICHT unser Plugin**, sondern ARVIOs Cloudstream-`.cs3`-Pfad liefert auf dem GerÃ¤t bei **jedem** Plugin nichts. Webstreamr funktioniert, weil es ein **Stremio-Addon** (vÃ¶llig anderer ARVIO-Code-Pfad) ist.
- **GitHub-Issue-Recherche** (`ProdigyV21/ARVIO`): Andere Nutzer berichten **exakt dasselbe Symptom** â Plugin installiert, in Liste sichtbar, Toggle an, aber keine Quellen:
  - **#459** "Nuvio JS scraper repository installs but returns no sources" (closed, ohne Ã¶ffentliche LÃ¶sung)
  - **#273** "I'm able to add nuvio plugin but not showing any video links" (closed; Dev @Himanth-reddy: "it should be working")
  - **#500** "unable to install the plugin" (open)
  - **#491** "plugins & extensions section shows addons not plugins" (gelÃ¶st â "next update")
  - v1.9.983-Changelog: "Added compatibility for **Nuvio-style JavaScript** scraper plugins" + "Fixed sideload **production-plugin routing**, extractor unloading, mobile routing". â DEX/`.cs3`-Pfad wurde gerade erst angefasst und lÃ¤uft offensichtlich **nicht zuverlÃ¤ssig**.
- **Library verifiziert vorhanden:** ARVIOs APK (`classes3.dex`/`classes4.dex`) enthÃ¤lt `com/lagradost/cloudstream3/metaproviders/TmdbProvider`, `MainAPI`, `plugins/Plugin`. Die Library fehlt also nicht.
- **ARVIO-Timeouts verifiziert:** `SCRAPER_TIMEOUT_MS=120_000`, `LOADLINKS_TIMEOUT_MS=60_000`, `EXECUTION_TIMEOUT_MS=120_000`. Unsere Per-Call-Timeouts (8s) sind weit drunter â kann nicht Ursache sein.

### Warum die In-Plugin-Diagnose (v6âv8) trotzdem leer blieb
v6âv8 sind so gebaut, dass **sobald ARVIO `loadLinks()` auch nur einmal aufruft**, die Diagnose als Pseudo-Quellen in ARVIOs Quellenauswahl erscheinen MÃSSEN (`emitTraceAsSources` + PLUGIN vN loaded"-Banner + `load()` gibt nie `null` zurÃ¼ck + Per-Call-Netzwerk-Timeouts). Da **keine einzige** ArvioAddon-Debug-Quelle erschien, lÃ¤uft unser Code **nie** â ARVIO instanziiert unsere Plugin-Klasse nicht (oder verwirft sie still). Das ist exakt die Fehlerklasse, die **nur im Logcat** sichtbar wird ("No API loaded for scraper", "MISSING CLASS", "plugin.load() linkage error", "No @CloudstreamPlugin class found").

### â ï¸ LIMITS EINES DIAGNOSE-PLUGINS (Antwort auf die Frage "kÃ¶nnen wir das Log Ã¼ber ein Plugin bekommen?")
**Teilweise ja, aber nicht fÃ¼r das aktuelle Problem.** Ein Plugin kann sich selbst protokollieren und das sogar in ARVIO als Quellen sichtbar machen (gebaut in v6âv8). **Aber** das funktioniert nur, **sobald ARVIO den Plugin-Code lÃ¤dt und aufruft**. Genau da hakt es: ARVIO lÃ¤dt/instanziiert die `.cs3`-Klasse auf dem GerÃ¤t nicht. FÃ¼r "lÃ¤dt ARVIO mein Plugin Ã¼berhaupt?" gibt es **kein plugin-basiertes Werkzeug** â dafÃ¼r braucht man ARVIOs eigene Logs (Logcat). Datei-/MediaStore-/HTTP-Server-AnsÃ¤tze (v3âv5) scheiterten ebenfalls, weil unser Code nie lÃ¤uft (keine Datei wird erzeugt).

### NÃCHSTER SCHRITT (Prio 1, VORAB gemacht mit Nutzer abgesprochen): MIT LAPTOP / PC WEITERMACHEN
Nutzer kommt nÃ¤chste Session **mit Laptop**. Dann ist **Logcat via USB+adb** mÃ¶glich (die einzig zuverlÃ¤ssige Methode; LADB-App auf dem GerÃ¤t scheiterte am Pairing). Konkrete Schritte fÃ¼r die nÃ¤chste Session:
1. Laptop: Android platform-tools (Mini-SDK, ~10 MB, keine Installation) von https://developer.android.com/tools/releases/platform-tools laden, entpacken.
2. GerÃ¤t per USB an den Laptop, im GerÃ¤t "USB-Debugging erlauben" bestÃ¤tigen.
3. Im platform-tools-Ordner Terminal Ã¶ffnen (Adressleiste `cmd` + Enter).
4. `adb logcat -c` (Buffer leeren).
5. In ARVIO: Filmpalast aus/an + Quellensuche auslÃ¶sen (z.B. Matrix). 15 s warten.
6. `adb logcat -d | grep -iE "ExtExt|ExternalExtension|PluginManager|Filmpalast|ArvioAddon|No API loaded|MISSING CLASS|CloudstreamPlugin|linkage error"` â Output kopieren.
7. **Was gesucht wird (entscheidend):**
   - `No API loaded for scraper: <id>` â ARVIO konnte keine MainAPI instanziieren (Klassen-Fehler).
   - `No @CloudstreamPlugin class found in <id>` â unsere Plugin-Klasse wurde nicht gefunden.
   - `plugin.load() linkage error` / `MISSING CLASS: ...` â eine Referenz lÃ¤sst sich zur Laufzeit nicht auflÃ¶sen.
   - `TmdbProvider Filmpalast: both load() paths failed` / `0 links collected` â Scraper lÃ¤uft, aber load/loadLinks scheitert.
   - Ãberhaupt kein `Filmpalast`/`ExtExt`-Eintrag â Scraper wird Ã¼berhaupt nicht aufgerufen (Enable-/Routing-Problem).
- Je nach Befund: load()-Fehler â Jsoup-Selektoren/Logging fixen; Scraper nicht aufgerufen â Download/DexClassLoader/manifestEnabled prÃ¼fen.

### NÃCHSTER SCHRITT (Prio 2): GitHub-Issue bei ARVIO Ã¶ffnen (parallel zu Prio 1)
Da der GermanProviders-Test beweist, dass es ein ARVIO-seitiges Problem mit dem `.cs3`-Pfad ist (nicht unseres), lohnt ein Issue bei den sehr aktiven ARVIO-Devs. **Noch NICHT geÃ¶ffnet** â in der nÃ¤chsten Session entscheiden, ob nach dem Logcat-Befund. Betreff/Inhalt-Skizze: ".cs3/Cloudstream3 plugins install and appear in list, but return no sources on sideload (GermanProviders AND custom TmdbProvider both empty; Stremio addons work)". Verweis auf #459/#273. **AI-Disclosure-Pflicht:** Falls Issue/MR-Kommentar erstellt wird, Hinweis "created by an AI agent (OpenHands) on behalf of [user]" einfÃ¼gen.
- Vor dem Issue benÃ¶tigte Infos vom Nutzer: genaue ARVIO-Version (1.9.983?), sideload bestÃ¤tigt, GerÃ¤t/Android-Version.

### ENTSCHEIDUNG NUTZER (14.08.2026): GitHub-Issue bei ARVIO professionell vorbereiten
Nutzer mÃ¶chte das GitHub-Issue bei ARVIO **professionell** einreichen (Vorbild: ARVIO Issue #537), ggf. sogar mit eigenem Fix-PR. Bis zur nÃ¤chsten Session sollen **alle dafÃ¼r nÃ¶tigen Informationen gesammelt und hier gespeichert** werden, damit eine andere Session das Issue ausarbeiten kann. **Status der Issue-ErÃ¶ffnung: NOCH NICHT Ã¶ffnen** â erst nach Logcat-Befund (Prio 1). Diese Sektion ist die Checkliste fÃ¼r die Vorbereitung.

#### Was bereits verifiziert/recherchiert ist (Stand 14.08.2026)
- **ARVIO-Repo:** `ProdigyV21/ARVIO` â Apache-2.0, 634 Stars, 98 Forks, sehr aktiv (18 Releases in 5 Monaten, letzte Commits 14.08.2026). Latest release `v1.9.983` (30.07.2026). `hasIssuesEnabled=true`, `hasDiscussionsEnabled=false` (â nur Issues, keine Discussions).
- **Maintainer:** `ProdigyV21` (Hauptmaintainer). **`Himanth-reddy`** = hochaktiver Mitwirkender, dessen PRs fast tÃ¤glich gemerged werden (#563, #561, #560, #558, #553, #552). Er hat auch den mobilen Plugin-UI-Fix (#466/v1.9.983) beigesteuert.
- **Externe PRs werden gemerged** (nicht nur closed) â ARVIO ist offen fÃ¼r saubere Contributions.
- **Kein CONTRIBUTING.md, keine Issue-Templates, keine PR-Templates** im Repo (obwohl GSSoC-Teilnehmer Issues dafÃ¼r Ã¶ffneten: #444/#477/#482 â closed, Status unklar). **â keine formale Contribution-Policy, die uns blockiert.**
- **"KIS" = vermutlich GSSoC** (GirlScript Summer of Code): ARVIO hat Label `gssoc:approved`; Teilnehmer wie `prince-pokharna`/`aayan-rashid` Ã¶ffnen viele `[Feature Request]`-Issues (oft oberflÃ¤chlich). Unser Issue ist tiefgreifender â qualitativ positiv herausstechend.
- **README-Repo-Zweck** (verifiziert): explizit *"Issue investigation and technical discussion"* + *"Contribution review"* â die Devs **wollen** gut recherchierte technische Issues.
- **README AI-Disclosure:** *"This application was developed with significant AI assistance. Contributions should still be reviewed, tested, and treated as normal source code changes."* â Devs haben selbst nichts gegen AI; erwarten aber qualitativ normales Code-Handling. **Unsere AI-Disclosure-Pflicht bleibt trotzdem bestehen.**
- **Label-System** (fÃ¼r Issue): `bug`/`type:bug`, `area: android`, ggf. `Next Update`. Maintainer setzt Labels i.d.R. selbst.

#### Vorbild-Issue fÃ¼r unseren Stil: ARVIO #537 (erfolgreich, schnell geschlossen)
"Pastebin dependency causes ~14s timeout for users in Turkey" â Aufbau: konkrete Code-Referenz (`MediaRepository`/`STREAMING_COLLECTION_ADDON_URL`) + Root-Cause (Pastebin in TÃ¼rkei blockiert) + LÃ¶sungsalternativen ("Would it be possible to replace with a project-controlled endpoint / GitHub raw / GitHub Pages?") + Angebot weiterer Beweise (network capture). **Genau dieser Stil ist bei ARVIO erfolgreich.**

#### Bekannte ARVIO-Issues mit identischem Symptom (Verweis im Issue nÃ¶tig)
- **#459** "Nuvio JS scraper repository installs but returns no sources" (closed, ohne Ã¶ffentliche LÃ¶sung) â hatten Reproduktion, aber **kein Logcat** â vermutlich deshalb sang- und klanglos geschlossen. **Genau diese Falle dÃ¼rfen wir nicht tappen.**
- **#273** "I'm able to add nuvio plugin but not showing any video links" (closed; Dev @Himanth-reddy: "it should be working").
- **#500** "unable to install the plugin" (open).
- **#491** "plugins & extensions section shows addons not plugins" (closed â "next update").

#### Voraussetzungen, damit das Issue gehÃ¶rt wird (Checkliste â vor Ãffnen abhaken)
- [ ] **Logcat-Beweis** (Prio 1, entscheidend). Ohne Logcat lÃ¤uft das Issue Gefahr, wie #459 geschlossen zu werden. Logcat-Filter: `ExtExt|ExternalExtension|PluginManager|Filmpalast|No API loaded|MISSING CLASS|CloudstreamPlugin|linkage error`.
- [ ] Genaue ARVIO-Version (1.9.983?) + sideload bestÃ¤tigt.
- [ ] GerÃ¤t-Modell + Android-Version.
- [ ] Reproduzierbare Schritte (Repo-URL installieren â Filmpalast suchen, z.B. Matrix â 0 Quellen).
- [ ] Beweis "ARVIO-seitig": GermanProviders (Bnyro, woanders funktionierend) liefert auf dem GerÃ¤t ebenfalls 0 Quellen.
- [ ] Root-Cause-Vermutung mit Code-Verweis (z.B. `hasStreamingAddons` zÃ¤hlt nur Stremio-Addons; `StreamRepository.getStreamAddons` filtert `runtimeKind != STREMIO`).
- [ ] LÃ¶sungsvorschlag ("Would it be possible to...").
- [ ] AI-Disclosure: "created by an AI agent (OpenHands) on behalf of [user]".

#### Issue-Struktur-Vorschlag (nach Vorbild #537)
1. **Environment:** ARVIO v1.9.983 sideload, GerÃ¤t, Android-Version.
2. **Summary:** `.cs3`-Plugins installieren, erscheinen aktiviert in der Liste, liefern aber 0 Quellen; Stremio-Addons funktionieren (anderer Code-Pfad).
3. **Steps to reproduce:** Repo installieren (unsere + GermanProviders) â Suche Matrix/Silo â 0 Quellen.
4. **Expected vs. Actual:** Cloudstream3-Scraper sollten Streams liefern wie in Cloudstream3-App/NuvioTV.
5. **Root cause (vermutet):** je nach Logcat-Befund â (a) Scraper wird gar nicht instanziiert (`No API loaded`/`linkage error`) ODER (b) LogiklÃ¼cke `hasStreamingAddons` ignoriert EXTERNAL_DEX-Scraper (verifiziert: `getStreamAddons` filtert `runtimeKind != STREMIO`; `DetailsViewModel` berechnet `hasStreamingAddons` nur aus Stremio-Addons).
6. **Proposed fix:** je nach Befund â (a) Logcat-Einbettung/Loader-Diagnose ODER (b) `getStreamAddons`/`hasStreamingAddons` sollten EXTERNAL_DEX-Scraper zÃ¤hlen.
7. **References:** #459, #273, #500.
8. **Logcat-Auszug** (gekÃ¼rzt).
9. **AI-Disclosure.**

#### Ablauf: Fork + eigener Fix-PR (professionellster Weg)
Der professionellste Weg (so machen es `Himanth-reddy`/GSSoC-Teilnehmer, deren PRs gemerged werden):
1. **Phase 1 â Beweise sichern:** Logcat via Laptop+USB+adb (siehe Prio 1).
2. **Phase 2 â Issue erÃ¶ffnen:** EIN fokussiertes Issue, Stil wie #537, mit Logcat-Beweis + Root-Cause + LÃ¶sungsvorschlag. **Nicht vor Phase 1 Ã¶ffnen.**
3. **Phase 3 â Fork & PR (optional, aber wirkungsvoll):** `ProdigyV21/ARVIO` forken, lokal bauen (README "Build And Run": JDK 17+, Android SDK 35), Fix testen, PR gegen Original. Issue+PR = hÃ¶chste Erfolgsquote, weil der Maintainer etwas Greifbares zum Mergen hat.
   - **Realistische Fix-Kandidaten je Logcat-Befund:**
     - (a) `hasStreamingAddons`-LogiklÃ¼cke (irrefÃ¼hrende "kein Addon"-Meldung): in `DetailsViewModel`/`StreamRepository.getStreamAddons` auch EXTERNAL_DEX-Scraper zÃ¤hlen â **kleiner, sauberer PR, gut mergebar.**
     - (b) Scraper wird gar nicht geladen (`No API loaded`/`linkage error`): tiefer in `ExternalExtensionLoader.loadExtension` â komplizierter, ARVIO-intern. Da eher **Issue ohne PR**, weil der Fix tief in der Engine liegt.

#### Was in dieser/nÃ¤chster Session zu sammeln/speichern ist
- Logcat-Auszug (gekÃ¼rzt, anonymisiert) â hier als Code-Block oder verlinkt ablegen.
- BestÃ¤tigte ARVIO-Version + sideload + GerÃ¤t/Android.
- Falls Fork gebaut: Branch-Name, gefixte Dateien, Test-Ergebnis.
- Issue-URL nach ErÃ¶ffnung.
- PR-URL nach ErÃ¶ffnung.

### Plugin-Versionen dieser Session (alle auf `builds`, status=1)
- **v2** (Hash 647c...): DebugServer 127.0.0.1:8420 + Datei-Trace.
- **v3**: DebugServer auf 127.0.0.1 gebunden (statt Wildcard).
- **v4** (2248...): File-based trace + PLUGIN_LOADED.txt Marker in Android/data.
- **v5** (9673...): MediaStore API schreibt in public Download/arvio-addon-logs/ (Fix: `MediaStore.Files.getContentUri` statt `Downloads.EXTERNAL_URI`).
- **v6**: Diagnose als Pseudo-Quellen in ARVIOs Quellenauswahl (`emitTraceAsSources`); `loadLinks` Return-Type-Fix (Boolean in v4.7.0).
- **v7**: `load()` gibt **nie null** zurÃ¼ck (debug MovieLoadResponse dataUrl="ARVIO_DEBUG") â `loadLinks` wird garantiert aufgerufen.
- **v8** (aktuell): Per-Call-Netzwerk-Timeouts (`withTimeoutOrNull(8s)` fÃ¼r TMDB + Filmpalast-Suche) damit `load()` nicht das Gesamt-Timeout frisst.
- Letzter Commit auf `main`: `ca9f81f` (v8). Builds-Version: 8.

### Was fertig ist (unverÃ¤ndert gÃ¼ltig)
Filmpalast-Plugin als Cloudstream3-`TmdbProvider` implementiert, gebaut, auf `builds`-Branch (`status=1`, `tvTypes=[Movie,TvSeries]`). CI grÃ¼n. Nutzer hat v8 in ARVIO 1.9.983 (sideload) installiert. Python-E2E-Simulation lÃ¤uft durch; filmpalast.to + TMDB per HTTP erreichbar. **Das Problem ist rein ARVIO-seitig beim Laden/AusfÃ¼hren von `.cs3`-Plugins.**

---

### (Veraltet, aber als Referenz behalten) FrÃ¼here Logcat-Optionen ohne PC
ARVIO hat **keine Log-Datei-Exportfunktion** und schreibt **keine App-Logs in Dateien** (verifiziert im gesamten ARVIO-Quellcode). Scraper-Logs (`Log.d/w` in `ExternalExtensionRunner.kt`) gehen **nur an Androids Logcat-Kernel-Buffer** (flÃ¼chtig, ohne Root nicht direkt auslesbar). Optionen ohne PC:
- **LADB-App:** scheiterte am Pairing ("no devices/emulators found"); "Pair & shell"-Schalter musste AN sein; 30-s-Pairing-Timer extrem zickig. **FÃ¼r diesen Nutzer nicht praktikabel.**
- **Bug Report:** Android-Einstellungen â Entwickleroptionen â Fehlerbericht (unhandlich, riesiger ZIP).
- **Nur mit Root:** Logcat-Reader-App.
- **WICHTIG:** ARVIOs integrierter "Test Scraper"-Button (`PluginManager.testScraper()`/`executeWithDiagnostics`) ist im Code vorhanden, aber in `PluginScreen.kt` **NICHT in die UI eingebaut** (Strings + ViewModel-Logik existieren, kein Compose-Button ruft `PluginUiEvent.TestScraper` auf). Halbfertige ARVIO-Funktion. FÃ¼r uns irrelevant, solange der Scraper ohnehin nie geladen wird.
â **Fazit: PC+USB+adb ist der Weg.** Siehe Prio 1 oben.

---

### Wichtige Dateien & Referenzen
- **Filmpalast-Code:** `/workspace/project/Arvio-Addon/FilmPalast/src/main/kotlin/com/reichi/arflioaddon/filmpalast/` â `FilmpalastProvider.kt` (load/loadLinks/diagnose), `FilmpalastPlugin.kt`, `FilmpalastExtractors.kt`, `DebugLog.kt`, `DebugServer.kt`, `DownloadsLogWriter.kt`
- **ARVIO-Referenz:** `ProdigyV21/ARVIO` @ v1.9.983 (neu klonen nach `/tmp/arvio_ref`, wird nicht persistiert). SchlÃ¼ssel-Dateien:
  - `app/src/sideload/kotlin/com/arflix/tv/core/plugin/PluginManager.kt` â `executeScrapers` (625), `executeScrapersStreaming` (672), `enabledScrapers` (271), `executeExternalDexScraper` (831, mit `SCRAPER_TIMEOUT_MS=120_000` bei 840), `downloadDexExtensions` (1057), `manifestEnabled = plugin.status == 1` (1079), `toggleScraper` (589, lÃ¤dt NICHT neu), `refreshExternalRepository` (566, lÃ¤dt neu)
  - `app/src/sideload/kotlin/com/arflix/tv/core/plugin/cloudstream/ExternalExtensionLoader.kt` â `downloadExtension` (203, DEX read-only fÃ¼r API28+), `loadExtension` (259), `findAndLoadPlugin` (701, liest `manifest.json`-`pluginClassName`), `plugin.load()`-Aufruf (317, fÃ¤ngt Exception+Error), **Fallback-DEX-Scan bei `apis.isEmpty()||extractors.isEmpty()`** (336), `getApi` (420, apiCache)
  - `app/src/sideload/kotlin/com/arflix/tv/core/plugin/cloudstream/ExternalExtensionRunner.kt` â `execute` (60), `executeInternal` (342), `executeTmdbProvider` (367), `executeTmdbLoadLinks` (430, `LOADLINKS_TIMEOUT_MS=60_000` bei 442), `extractData` (738: MovieâdataUrl, TvSeriesâfindEpisode.data), `filterValid` (870: nur http(s)-URLs!), `toLocalScraperResult` (884), `EXECUTION_TIMEOUT_MS=120_000`
  - `app/src/main/kotlin/com/arflix/tv/domain/model/Plugin.kt` â `ScraperInfo` (77), `supportsType` (92, normalisiert series/tv/animeâtv)
  - `app/src/sideload/kotlin/com/arflix/tv/core/plugin/cloudstream/TvTypeExtensions.kt` â `tvTypeFromString`, `toNuvioType`
  - `app/src/main/kotlin/com/arflix/tv/ui/screens/details/DetailsViewModel.kt` â `loadStreams` (1405), `pluginScraperJob` (1511, ruft `executeScrapersStreaming`), `hasStreamingAddons` zÃ¤hlt NUR Stremio-Addons (1601/1634/1651/1690) â irrefÃ¼hrende "kein Add-on"-Meldung bei reinen Cloudstream-Plugins
- **GermanProviders-Referenz:** `Bnyro/GermanProviders` (Repo-URL: `https://raw.githubusercontent.com/Bnyro/GermanProviders/refs/heads/master/repo.json`). Filmpalast dort = `MainAPI` (search-based). **Auf dem GerÃ¤t des Nutzers ebenfalls 0 Quellen** â beweist ARVIO-seitiges `.cs3`-Problem.
- **Builds-Branch:** v8 verÃ¶ffentlicht, `status=1`, `internalName=FilmPalast`. `plugins.json`+`FilmPalast.cs3` auf `builds`.
- **cloudstream3 library:** v4.7.0 (`com.github.recloudstream.cloudstream:library-android:v4.7.0`). Built-in Extractoren: `Voe()`, `Firestream()`, `FileMoonSx()`, `Supervideo()`, `VidHidePro()` + ~270 andere via `installGlobal()`. **Wichtig:** `loadLinks` gibt in v4.7.0 `Boolean` zurÃ¼ck (nicht Unit) â Override muss `: Boolean` deklarieren.

### ARVIO-Scraper-Aufruf-Pfad (verifiziert, entscheidend fÃ¼rs Debugging)
1. `DetailsViewModel.loadStreams` â `pluginScraperJob` â `pluginManager.executeScrapersStreaming(tmdbId, mediaType, season, episode)`
2. `executeScrapersStreaming`: prÃ¼ft `pluginsEnabled` + `enabledScrapers.filter{supportsType}`; leer â return; sonst pro Scraper `executeScraperWithSingleFlight` â `executeExternalDexScraper` (mit `SCRAPER_TIMEOUT_MS=120_000`)
3. `executeExternalDexScraper` â `externalExtensionRunner.execute(scraperId,...)` â `extensionLoader.getApi(scraperId)` (leer â "No API loaded" â emptyList, **still**)
4. `execute` â `executeInternal` â **wenn `api is TmdbProvider`:** `executeTmdbProvider`; **sonst:** `executeSearchBased`
5. `executeTmdbProvider`: `api.load("""{"id":$tmdbIdInt,"type":"$type"}""")` â null-fallback `api.load("https://www.themoviedb.org/<type>/<id>")` â `extractData(loadResponse)` â `api.loadLinks(data)`
6. `extractData`: `MovieLoadResponse`â`dataUrl`, `TvSeriesLoadResponse`â`findEpisode(...).data`
7. `executeTmdbLoadLinks`: sammelt `ExtractorLink`s via callback, `filterValid` (nur http(s)), `toLocalScraperResult` â erscheinen in ARVIOs Quellenauswahl. **Unsere Debug-Quellen (url=`https://arvio-addon.invalid/...`) passieren filterValid.**
8. **Inkonsistenz (Test-Pfad):** `executeTmdbProviderWithDiagnostics` ruft `loadLinks` mit `TmdbLink(...).toJson()` direkt auf (ohne `load()`) â anderer data-Vertrag. Unser `loadLinks` ist auf den load()-Pfad ausgelegt. Falls ARVIO den Test-Button aktiviert, muss `loadLinks` auch TmdbLink-JSON verarbeiten.
9. **WICHTIG fÃ¼r "Quellen aktualisieren":** `toggleScraper` (589) lÃ¤dt die `.cs3` NICHT neu â nur Datenbank-Toggle. Neudownload NUR via `addRepository` oder `refreshExternalRepository`. **Daher: fÃ¼r Plugin-Update immer Repo lÃ¶schen + neu hinzufÃ¼gen** (`https://raw.githubusercontent.com/ReichiMD/Arvio-Addon/main/repo.json`).

### Diagnose-Tooling in unserem Plugin (v8, fÃ¼r die Logcat-Ãra falls Scraper doch lÃ¤uft)
- `DebugLog.kt`: in-memory Ring-Buffer (2000) + `snapshot()`/`format()` fÃ¼r `emitTraceAsSources`.
- `emitTraceAsSources(callback)`: emittiert Trace als `ExtractorLink` (source="ArvioAddon-Debug", url=`https://arvio-addon.invalid/debug/<n>`) â sichtbar in ARVIO-Quellenauswahl. Erstes Banner: "PLUGIN vN loaded".
- `debugLoadResponse()`: `MovieLoadResponse` mit `dataUrl="ARVIO_DEBUG"` â `loadLinks` wird auch bei load()-Fehlern aufgerufen.
- Per-Call-Timeouts (`NET_TIMEOUT_MS=8000`) um `fetchTmdbMeta`/`searchFilmpalast`.
- `DebugServer.kt` (127.0.0.1:8420) + `DownloadsLogWriter.kt` (MediaStore) noch vorhanden, aber **nur nutzbar, wenn der Scraper lÃ¤uft** (was aktuell nicht der Fall ist).

---

## Entscheidung: Welcher Plugin-Typ?

**GewÃ¤hlt: Cloudstream3-Plugin (Kotlin/DEX, ".cs3")** â der "mÃ¤chtige" Weg.

| Grund | Detail |
|---|---|
| Eigene Konfig-Seite (Portal-URL/MAC) | Nur Cloudstream3-Plugins kÃ¶nnen UI-Settings haben â nÃ¶tig fÃ¼r Stalker-VOD |
| Eigene Kataloge/Startseiten in ARVIO | Nur Cloudstream3-Plugins liefern eigene Start-Kataloge |
| Kotlin = gleiche Sprache wie Ventix | Ventix-Scraper (Kotlin) lassen sich **direkt portieren**, nicht nach JS Ã¼bersetzen |
| Viele Vorlagen | GermanProviders-Repo (Bnyro) ist eine komplette Vorlage mit exakt unseren Scraper-Hostern |
| Cloudstream3-Ãkosystem | ARVIO nutzt library v4.7.0; apiVersion 1 ist kompatibel |

**AbgewÃ¤hlt: Nuvio-JS-Plugin** (Weg A) â einfacher, kann aber nur Streams liefern, keine Config-Seite, keine eigenen Kataloge. Da wir Stalker-VOD brauchen (mit Portal/MAC-Eingabe), reicht JS-Plugin nicht.

---

## Ziel: ARVIO-Installation des fertigen Plugins

Der Nutzer installiert das Plugin so in ARVIO (verifizierter Flow):
1. ARVIO **sideload-APK** installieren (nicht Play-Store-Version!)
2. Einstellungen â **Plugins & Extensions** (nur in sideload sichtbar)
3. **Add Repository** â Repo-URL eintragen
4. ARVIO lÃ¤dt `repo.json` â folgt `pluginLists` â lÃ¤dt `plugins.json`
5. Plugin-EintrÃ¤ge einschalten â ARVIO lÃ¤dt `.cs3`-Datei (kompilierter Code)

### Bekannter Bug: Add-Repo-Dialog/Plugin-Settings auf Handy (GEFIXT in 1.9.983)
Der "Add Repository"-Dialog + Plugin-Settings-Screen nutzten TV-only `androidx.tv.material3.Surface`-Buttons, die auf Touch-GerÃ¤ten (Handy/Tablet) nicht reagierten. **Behoben in ARVIO Issue #502** ("fix(mobile): resolve touch issues in plugins settings") â `PluginScreen.kt` hat jetzt `LocalDeviceType.current.isTouchDevice()` mit separatem Mobile-Layout. **Fix ist in 1.9.983 enthalten** (verifiziert). Nutzer hat das Plugin erfolgreich Ã¼ber ein Cloud-Profil auf dem Handy installiert.

---

## Was das Plugin kÃ¶nnen muss (Scope)

### Modul 1: Deutsche Web-Scraper (Filmpalast, Serienstream, HdFilme, Megakino, KinoGer, Netzkino, AniWorld)
- **Vorlage:** GermanProviders-Repo (Bnyro/GermanProviders) â hat ALL diese Scraper schon als Cloudstream3-Plugins!
- MÃ¶glichkeit 1: GermanProviders forken + anpassen (wenig Eigenarbeit, abhÃ¤ngig von Upstream)
- MÃ¶glichkeit 2: Eigenes Plugin schreiben, GermanProviders als Referenz (volle Kontrolle)

### Modul 2: Stalker-VOD (Filme + Serien Ã¼ber Stalker-Portal)
- **Das ist die Neuentwicklung** â GermanProviders hat das nicht.
- ARVIOs eingebaute StalkerApi kennt NUR Live-TV (get_genres, get_all_channels, create_link) â **KEIN VOD, keine Serien**.
- Plugin braucht: eigene Config-Seite (Portal-URL + MAC), VOD-Kategorien, VOD-Liste, createVodLink, Serien/Staffeln/Episoden.
- Vorlage: Ventix-StalkerApi (17 Methoden) â in Kotlin, direkt portierbar.

### Modul 3: Stalker Live-TV
- **Nicht bauen** â ARVIO hat das schon eingebaut (obwohl die UI aktuell fehlt, siehe "ARVIO-MÃ¤ngel").

---

## Architektur-Referenz: ARVIOs Plugin-System

### Plugin-Formate die ARVIO versteht (verifiziert im Code)
1. **Nuvio-JS-Plugin**: `manifest.json` + `.js`-Dateien mit `getStreams(tmdbId, type, season, episode)`. Engine: QuickJS + Cheerio + CryptoJS. (abgewÃ¤hlt)
2. **Cloudstream3-Plugin (EXTERNAL_DEX)**: `.cs3`-Datei (kompiliertes DEX). Engine: cloudstream3-library v4.7.0. (**gewÃ¤hlt**)

### ARVIO Repository-Manifest-Format (`repo.json`)
```json
{
  "name": "Ventix Arvio Addon",
  "description": "Deutsche Scraper + Stalker VOD fÃ¼r ARVIO",
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
- `@CloudstreamPlugin`-annotierte `Plugin`-Klasse â `registerMainAPI(...)` + `registerExtractorAPI(...)`
- `MainAPI`-Subklasse â `mainUrl`, `name`, `supportedTypes`, `mainPage`, `search()`, `load()`, `loadLinks()`
- `ExtractorApi`-Subklassen fÃ¼r Hoster (VOE, FileMoon, Supervideo, VidHidePro etc.)

---

## Ventix-Referenz (Quell-Projekt, NICHT in dieses Repo kopieren)

Ventix liegt im Schwester-Repo `ReichiMD/IPTV-App`. Scraper-Quellcode zum Portieren:
- `app/src/main/java/com/iptv/stalker/data/scraping/` â FilmpalastScraper, HdFilmeScraper, KinogerScraper, MegakinoScraper, SerienstreamScraper, AniWorldScraper, NetzkinoScraper + extractor/
- `app/src/main/java/com/iptv/stalker/data/api/StalkerApi.kt` â Stalker-Middleware (17 Methoden: handshake, get_profile, get_events, VOD-+Serien-+EPG-Endpoints, createVodLink, getSeasons, M3U-Export)
- `app/upstream-reference/` â Cloudstream3-Upstream-Referenzen (bereits als Referenz genutzt!)
- `VideoHostExtractor.kt` â Hoster-Extraktoren (VOE, FileMoon, VidGuard, Veev, Vidsonic, DoodStream etc.)

Ventix und ARVIO nutzen BEIDE Cloudstream3-Upstream-Referenzen â das vereinfacht das Portieren.

---

## GermanProviders-Referenz (Vorlage-Repo, geklont nach /tmp/german-providers)

`Bnyro/GermanProviders` â Cloudstream3-Multi-Provider-Repo, hat bereits 21 fertige Plugins:
ARD, Aniworld, Arte, C3TV, Discovery, EinschaltenIn, **FilmPalast**, HDFilme, HuhuTo, IptvOrg, KinoKing, **Kinoger**, **Megakino**, Moflix, **Netzkino**, PlutoTV, **Serienstream**, Southpark, SpiegelTV, Welt, Xcine.

Installations-URL (Test): `https://raw.githubusercontent.com/Bnyro/GermanProviders/refs/heads/master/repo.json`
- `builds`-Branch enthÃ¤lt `plugins.json` + fertige `.cs3`-Dateien.
- Aufbau: root `build.gradle.kts` (cloudstream3-gradle-plugin `com.github.recloudstream:gradle`), pro Provider ein Modul-Ordner mit `build.gradle.kts` + `src/`.
- Settings-Gradle: auto-include aller Modul-Ordner.

**Diese Scraper (Filmpalast, Serienstream etc.) sind identisch mit Ventix' Ziel-Set.** GermanProviders ist die primÃ¤re Vorlage fÃ¼r Modul 1.

---

## ARVIO-Referenz (geklot nach /tmp/arvio_ref)

`ProdigyV21/ARVIO` â die Ziel-App. Version 1.9.983 (versionCode 306), sehr aktiv.

### ARVIO Build-Flavors (verifiziert in `app/build.gradle.kts`)
| Flavor | `FEATURE_PLUGINS_ENABLED` | `SELF_UPDATE_ENABLED` | Plugin-Engine |
|---|---|---|---|
| `play` (Play Store) | **false** | false | â abgeschaltet |
| `sideload` (GitHub-APK) | **true** | true | â voll aktiv |

â **Plugin funktioniert NUR in der sideload-APK**, nicht im Play-Store-Build. Google-Policy verbietet dynamischen Code im Store.

### ARVIO sideload-Download
`https://github.com/ProdigyV21/ARVIO/releases/download/v1.9.983/ARVIO-v1.9.983-sideload-release.apk` (135 MB)

### ARVIO Plugin-Engine (nur in `app/src/sideload/`)
- `PluginManager.kt` â Repository-Verwaltung, `addRepository()`, Format-Auto-Detection
- `PluginRuntime.kt` â QuickJS-Engine (fÃ¼r JS-Plugins) + `__native_fetch`, `__cheerio_*`, CryptoJS
- `cloudstream/ExternalExtensionLoader.kt` â lÃ¤dt `.cs3`-Plugins via DexClassLoader
- `cloudstream/ExternalExtensionRunner.kt` â fÃ¼hrt `MainAPI.search()`/`load()`/`loadLinks()` aus
- `cloudstream/ExternalExtractorRegistry.kt` â verwaltet `ExtractorApi`-Extraktoren
- `cloudstream/ExternalRepoParser.kt` â parsed `repo.json` (erkennt `"pluginLists"`-Key) + `plugins.json`

### ARVIO-MÃ¤ngel (Stand Aug 2026, die wir im Plugin adressieren)
1. **Stalker-VOD fehlt komplett** â StalkerApi kennt nur Live-TV (4 Methoden: handshake, getProfile, getChannels, resolveStreamUrl). Kein VOD, keine Serien, kein EPG fÃ¼r VOD.
2. **Stalker-Dateneingabe fehlt in der UI** â `saveStalkerConfig()` existiert im SettingsViewModel (Zeile 2280), wird aber von KEINEM UI-Element aufgerufen. Kein Button, kein Dialog. Backend halbfertig, UI fehlt.
3. **Add-Repo-Dialog Handy-Bug** â `width(520.dp)` zu breit fÃ¼r Hochformat (Workaround: Querformat).

---

## Stremio-Addon-Referenz (paralleles Projekt)

`ReichiMD/Stremio-Addon` â serverseitiges Node.js-Addon (deployed auf Render), Quellen: Vavoo/KinoGer/Filmpalast/MovieBox/VidSrc/Einschalten + MediaFlowProxy.
- **Problem:** Stremio-Addon (serverseitig) kann manche Streams nicht liefern (z.B. KinoGer 403 â Render-DC-IP blockiert). Ventix (clientseitig) kann das.
- **Dieses ARVIO-Addon lÃ¶st das:** lÃ¤uft clientseitig in der App â EndgerÃ¤t-IP â kein Bot-Schutz â kein Server, kein Geld.
- Logik der Scraper ist im Stremio-Addon bereits in JavaScript/TypeScript vorhanden (kann als Referenz dienen, wird aber neu in Kotlin als Cloudstream3-Plugin geschrieben).

`ReichiMD/mediaflow-proxy` â MediaFlowProxy (fest codiert im Stremio-Addon). FÃ¼r ARVIO-Addon nicht nÃ¶tig (clientseitig braucht keinen Proxy).

---

## Build & Release

### Plugin kompilieren (Cloudstream3-gradle-plugin)
- Multi-Modul-Setup wie GermanProviders: root `build.gradle.kts` mit `com.github.recloudstream:gradle`-Plugin, pro Plugin ein Modul.
- Output: `.cs3`-Datei pro Plugin-Modul.
- CI: GitHub Actions baut `.cs3`-Dateien, pusht sie auf einen `builds`-Branch (wie GermanProviders), generiert/aktualisiert `plugins.json`.

### Datei-Struktur (geplant)
```
Arvio-Addon/
âââ AGENTS.md                          # diese Datei
âââ README.md                          # (spÃ¤ter)
âââ build.gradle.kts                   # root, cloudstream3-gradle-plugin
âââ settings.gradle.kts                # auto-include Module
âââ repo.json                          # Installations-Manifest fÃ¼r ARVIO
âââ GermanScraper/                     # Modul 1: deutsche Web-Scraper (oder pro Scraper ein Modul)
â   âââ build.gradle.kts
â   âââ src/main/kotlin/.../GermanScraperPlugin.kt + Provider + Extractors
âââ StalkerVod/                        # Modul 2: Stalker-VOD (Config-Seite + VOD/Serien)
    âââ build.gradle.kts
    âââ src/main/kotlin/.../StalkerVodPlugin.kt + StalkerApi + Provider
```

### branches
- `main` â Quellcode
- `builds` â fertige `.cs3`-Dateien + `plugins.json` (von CI gepusht, wie GermanProviders)

---

## NÃ¤chste Schritte (PrioritÃ¤t)

1. **Proof-of-Concept:** GermanProviders in ARVIO-sideload testen (Button-Bug workaronden) â prÃ¼fen welche Scraper laufen.
2. **Repo-Setup:** GermanProviders-Architektur (root build.gradle + Modul-Struktur) hier nachbauen.
3. **Modul 1 (Web-Scraper):** GermanProviders-Plugins adaptieren ODER eigene Implementierung. Hoster-Extraktoren (VOE, FileMoon etc.) aus Ventix' `VideoHostExtractor` portieren.
4. **Modul 2 (Stalker-VOD):** Ventix' `StalkerApi.kt` (VOD/Serien-Teil) als Cloudstream3-Provider portieren + Config-Seite fÃ¼r Portal/MAC.
5. **CI:** GitHub Actions workflow fÃ¼r `.cs3`-Build + `builds`-Branch-Push.

---

## Versionshistorie dieses Addons

(noch keine â Repo ist leer)

---

## Recherche: ARVIO-Plugin-Integration (Stand Aug 2026, ARVIO v1.9.983)

Verifiziert im ARVIO-Quellcode (`ProdigyV21/ARVIO` @ v1.9.983, geklont nach `/tmp/arvio_ref`).
Recherche anlÃ¤sslich zweier Nutzer-Probleme beim Testen von GermanProviders als Cloudstream3-Plugin in ARVIO.

### Problem 1: Plugin-Einrichtung funktioniert nur auf TV, nicht auf Handy/Tablet

**Beobachtung (Nutzer):** Add-Repository / Plugin-Aktivierung ging auf Handy & Tablet nicht; erst ein ARVIO-Cloud-Profil (auf TV erstellt, aufs Handy synchronisiert) brachte die Plugins aufs Handy. TV funktionierte direkt.

**Rechercheergebnis:**
- ARVIO hat ein Layout-Force-Feature ("Force TV, Tablet, or Phone layout") UND Auto-Detect fÃ¼r TV-Modus bei GerÃ¤ten ohne Touchscreen (CHANGELOG v1.9.3). Die UI wird je Formfaktor unterschiedlich gerendert.
- Der Plugin-Bereich wurde in v1.9.983 neu gebaut: CHANGELOG-Eintrag "redesigned plugin settings for TV and mobile. Contributor: @Himanth-reddy via #466" â d.h. die mobile Plugin-UI ist **sehr neu** (Juli 2026).
- Begleitend in v1.9.983: "Fixed sideload production-plugin routing, extractor unloading, **mobile routing**, and TV focus limits" (#466) â ein mobiler Routing-Fix wurde *explizit* fÃ¼r diese Version gebraucht. Das deutet darauf hin, dass mobile Plugin-Pfade vorher fehlerhaft waren.
- Ein **bekannter, Ã¤lterer Bug** (AGENTS.md bereits notiert): Add-Repo-Dialog `width(520.dp)` zu breit fÃ¼r Handy-Hochformat (~390dp) â Buttons abgeschnitten/inaktiv im Hochformat.
- Vergleichs-Befund aus dem Nuvio-Ãkosystem (Schwester-App, gleiche Plugin-Architektur): NuvioMobile Issue #1190 â *"If Cloudstream Plugin Repositories are loaded in the Plugins list in the Mobile app, they get removed from Plugins list in the TV app"* (closed as not planned). Cloudstream-Plugin-Listen zwischen Mobile- und TV-UI synchron halten ist **branchenweit ein Problem**, nicht ARVIO-spezifisch.

**Fazit Problem 1:** Sehr wahrscheinlich ein **ARVIO-seitiger Bug in der (neuen) mobilen Plugin-UI** â entweder Routing (in v1.9.983 gerade erst gefixt, evtl. nicht vollstÃ¤ndig) oder der bekannte `width(520.dp)`-Dialog-Bug. Dass der Cloud-Sync-Workaround funktioniert, bestÃ¤tigt: Die Plugin-Daten selbst sind korrekt; nur die mobile Einrichtungspath-UI ist defekt. Keine andere Nutzerberichte als direktes Duplikat gefunden, aber die CHANGELOG-Historie (mobiler Plugin-Routing-Fix in der *aktuellen* Version) zeigt, dass ARVIO genau diese Klasse von Bug gerade behebt.

**Workarounds fÃ¼r Nutzer:** Querformat beim Add-Repo; oder Plugin-Konfiguration auf TV vornehmen + ARVIO-Cloud-Sync aufs Handy (funktioniert laut Nutzer bereits); oder `web.arvio.tv` (Web-App, vollstÃ¤ndige ARVIO-UI im Browser, laut CHANGELOG mit TV-D-pad-Navigation).

### Problem 2: Aktivierte Provider erscheinen nicht bei Quellensuche ("kein Add-on eingerichtet / keine Quellen")

**Beobachtung (Nutzer):** In den Plugin-Einstellungen Provider (z.B. Einschalten) aktiviert â auf eine Silo-Episode gegangen â "nach Quellen gesucht" â Meldung "kein Add-on eingerichtet, keine Quellen gefunden".

**Verifizierte Ursache im ARVIO-Code:** ARVIO hat **zwei komplett getrennte Quell-AuflÃ¶sungspfade**, und Cloudstream3-Plugins (.cs3) laufen Ã¼ber den Pfad, der die "kein Add-on"-Meldung **nicht steuert**:

1. **Stremio-Addon-Pfad** (`StreamRepository` + `AddonRuntimeAggregator`): Hier laufen klassische Stremio-kompatible Addons (HTTP `stream/movie/<imdbId>.json`), Home-Server (Jellyfin/Plex/Emby) und HTTP-Local-Scrapers. Die UI-Variable `hasStreamingAddons` (die "No Streaming Addons" / "kein Add-on eingerichtet" anzeigt) wird **ausschlieÃlich** aus `streamRepository.installedAddons.count { it.isVodStreamingAddon() }` berechnet (`DetailsViewModel.kt` Z. 1600/1633/1650/1689). `isVodStreamingAddon()` prÃ¼ft nur `isEnabled && type != SUBTITLE && !sportsOnly` â das sind Stremio-Addons, **keine Cloudstream-Scraper**. Filter `getStreamAddons()` (`StreamRepository.kt` Z. 1440) wirft sogar hart raus: `if (addon.runtimeKind != RuntimeKind.STREMIO) return@filter false` â und `RuntimeKind` kennt nur `STREMIO`/`TELEGRAM`, keinen Cloudstream/EXTERNAL_DEX-Wert (`Models.kt` Z. 305).

2. **Cloudstream-Plugin-Pfad** (`PluginManager` + `ExternalExtensionRunner`, sideload-only): Aktivierte `.cs3`-Scraper werden in `DetailsViewModel.loadStreams()` Ã¼ber `pluginManager.executeScrapersStreaming(...)` in einem **parallelen Job** (`pluginScraperJob`, Z. 1510â1552) ausgefÃ¼hrt. Ergebnisse mergen sich asynchron in `streams`. Dieser Pfad startet **nur**, wenn `dataStore.pluginsEnabled` true ist UND `enabledScrapers` (nach `supportsType(mediaType)`) nicht leer ist (`PluginManager.kt` Z. 631â640, 681).

**Warum trotzdem "kein Add-on"-Meldung + keine Quellen bei Silo:** Weil `hasStreamingAddons` Stremio-Addons zÃ¤hlt. Hat der Nutzer **kein einziges** Stremio-Addon installiert (nur Cloudstream-Plugins), ist `hasStreamingAddons=false` â UI zeigt "No Streaming Addons / kein Add-on eingerichtet" an. Die Meldung ist in diesem Fall **irrefÃ¼hrend**: Die Cloudstream-Scraper suchen im Hintergrund trotzdem, finden aber fÃ¼r "Silo" vermutlich nichts (siehe Problem 2b), und die UI bleibt bei der "Setup Required"-Meldung stehen, obwohl die Plugins aktiv sind.

**Problem 2b â warum die Cloudstream-Scraper fÃ¼r "Silo" trotzdem 0 Quellen liefern (verifiziert):**
GermanProviders-Plugins (Filmpalast, Serienstream, AniWorld etc.) sind **keine** `TmdbProvider` (sie Ã¼berschreiben nicht `load()` fÃ¼r TMDB-JSON), sondern **search-basierte** `MainAPI`-Provider. ARVIOs `ExternalExtensionRunner.executeSearchBased()` (Z. 473â620) macht fÃ¼r search-basierte Provider:
1. TMDB-Enrichment holen â `localizedTitle` + `year` + alt-Titel
2. `api.search(title)` aufrufen + bei Trefferlosigkeit Retry mit vereinfachtem Titel und parallelen Alt-Titeln
3. `findBestMatch()` (Ãhnlichkeits-Score) Ã¼ber Suchergebnisse â `api.load(bestMatch.url)` â `extractData()` â `api.loadLinks()`

Scheitern kann es an **mehreren Stellen**:
- **Sprache:** Silo ist eine Apple TV+-Serie. Deutsche Scraper wie Filmpalast/Serienstream listen "Silo" u.U. nur unter deutschem Titel oder garnicht (Apple-TV+-Originals sind seltener auf deutschen Scraper-Seiten als Netflix/Prime). TMDB `localizedTitle` fÃ¼r Silo DE = "Silo" â passt, aber die Scraper-Seite muss die Serie auch im Katalog haben.
- **`findBestMatch`-Mismatch:** Wenn der Scraper "Silo" z.B. als "Silo - Season 1" oder mit Jahr-Abweichung zurÃ¼ckgibt, fÃ¤llt der Similarity-Score unter die Schwelle â `return emptyList()` (Z. 567). Das ist ein **hÃ¤ufiges** Cloudstream-Problem bei ARVIO, weil ARVIO eigenes Title-Matching macht statt die Provider-`load()` direkt mit der Scraper-eigenen URL zu fÃ¼ttern.
- **Season/Episode-Mapping:** `extractData(loadResponse, mediaType, season, episode)` baut das `data`-JSON, das `loadLinks()` erwartet. Bei Serien muss `load()` eine `TvSeriesLoadResponse` liefern, aus der ARVIO die Episoden-URL extrahiert. GermanProviders' `load()`-Implementierungen sind fÃ¼r Cloudstream3-App geschrieben; ARVIO ruft sie leicht anders auf â kann `data=null` geben â `return emptyList()` (Z. 590).
- **Host-Dead / Bot-Schutz:** Deutsche Scraper-Seiten blockieren oft. ARVIO fÃ¤ngt `hostUnreachable` ab und skippt (Z. 552). Da ARVIO clientseitig lÃ¤uft (GerÃ¤t-IP), sollte das seltener sein als beim serverseitigen Stremio-Addon â aber mÃ¶glich.

**Fazit Problem 2:** Zwei Dinge Ã¼berlagern sich:
- (a) **ARVIO-UI-Bug/DesignschwÃ¤che:** Die "kein Add-on eingerichtet"-Meldung wird nur aus dem Stremio-Addon-Pfad gespeist und ignoriert aktivierte Cloudstream-Plugins vollstÃ¤ndig. Solange kein Stremio-Addon aktiv ist, zeigt die UI "Setup Required", **selbst wenn** Cloudstream-Scraper im Hintergrund laufen. Das ist eine ARVIO-seitige LogiklÃ¼cke, nicht des Addons Schuld.
- (b) **Scraper-Matching:** Selbst wenn die Cloudstream-Scraper laufen, liefern sie fÃ¼r bestimmte Titel (wie Silo) oft 0 Treffer wegen ARVIOs eigenem Title-Matching / `findBestMatch` / Episode-Mapping, das nicht 1:1 der Cloudstream3-App entspricht.

CHANGELOG-Belege, dass ARVIO dieses Themenfeld aktiv bearbeitet:
- v1.9.983: "Added compatibility for Nuvio-style JavaScript scraper plugins and redesigned plugin settings for TV and mobile" (#466) + "Fixed sideload production-plugin routing, extractor unloading, mobile routing, and TV focus limits" (#466)
- v1.9.92: "Improved FlixStreams/anime addon matching and fallback stream lookup for episode sources" + "Fixed configured add-ons occasionally failing to appear in the source list until a later retry"
- v1.8.2: "Source selector shows setup instructions instead of generic 'No sources found' when no addons are installed" + "When no streaming addons are configured, the app now shows a friendly setup guide instead of a playback error"

**Handlungsempfehlung (fÃ¼r unser Addon / Nutzer):**
1. **FÃ¼r saubere UI-Anzeige:** ZusÃ¤tzlich zu den Cloudstream-Plugins **mindestens ein** Stremio-Addon (auch ein inaktives/dummy) installieren, damit `hasStreamingAddons=true` wird und die Meldung verschwindet. Das ist ein Workaround fÃ¼r ARVIOs LogiklÃ¼cke (a).
2. **FÃ¼r echte Quellen bei Serien wie Silo:** Eigenes ARVIO-Addon bauen (Ziel dieses Repos) â aber dabei darauf achten, dass die `MainAPI`-Implementierung robustes `search()` + `load()` + `loadLinks()` bietet, das ARVIOs `findBestMatch`-basiertem Aufruffluss standhÃ¤lt. Ideal: Provider als `TmdbProvider` implementieren (dann nimmt ARVIO den direkteren `executeTmdbProvider`-Pfad ohne fragiles Title-Matching). Das ist eine **Konsequenz fÃ¼r die Modul-1-Architektur** dieses Addons.
3. **GitHub-Issue bei ARVIO erwÃ¤gen:** (a) ist klar ein ARVIO-Bug ("hasStreamingAddons ignoriert aktivierte Cloudstream-Scraper"). Lohnt sich als Issue zu melden, da ARVIO aktiv ist (18 Releases in 5 Monaten) und #466 genau dieses Gebiet gerade anfasst.

---

## Implementation: Filmpalast-Plugin als TmdbProvider (Proof-of-Concept)

**Status: gebaut und kompiliert.** `FilmPalast/build/FilmPalast.cs3` (â23 KB) + `build/plugins.json` werden lokal via `./gradlew make makePluginsJson` erzeugt; CI (`.github/workflows/build.yml`) pusht beides auf den `builds`-Branch.

### Architektur-Entscheidung (verbindlich fÃ¼r alle Modul-1-Scraper)
**Alle Provider als `TmdbProvider` implementieren**, nicht als plain `MainAPI`. BegrÃ¼ndung (siehe oben "Recherche"): ARVIO hat zwei Dispatch-Pfade in `ExternalExtensionRunner.execute()`:
- `executeTmdbProvider` (wenn `api is TmdbProvider`): ruft `api.load("{\"id\":<tmdbId>,\"type\":\"movie\"|\"tv\"}")` direkt auf â kein fragiles `findBestMatch`-Title-Matching.
- `executeSearchBased` (sonst): sucht Titel, matcht via Similarity-Score, mappt Season/Episode â hÃ¤ufig 0 Treffer bei Serien.

TmdbProvider ist der zuverlÃ¤ssige Pfad. GermanProviders' Scraper sind alles *search-based* (kein TmdbProvider) â das ist mit ein Grund, warum sie in ARVIO bei Serien oft leer bleiben.

### TmdbProvider-Vertrag (verifiziert am cloudstream3-Source `TmdbProvider.kt`)
- ARVIO ruft `load("{\"id\":<tmdbId>,\"type\":...}")`; Fallback `load("https://www.themoviedb.org/<type>/<id>")`. Beide Formen mÃ¼ssen `parseTmdbInput` akzeptieren.
- `load()` muss zurÃ¼ckgeben: `MovieLoadResponse` (Filme, `dataUrl`=JSON) ODER `TvSeriesLoadResponse` mit `Episode`-Liste (Serien, `episode.data`=URL).
- `loadLinks(data, ...)`: fÃ¼r Filme ist `data` das JSON aus `dataUrl`; fÃ¼r Serien ist `data` die Episoden-URL aus `episode.data`.
- `useMetaLoadResponse = false` (wir bauen die LoadResponse selbst, nicht Ã¼ber TMDB-Meta-Provider).

### Filmpalast-Seitenstruktur (live verifiziert, Stand Aug 2026)
- Suche `/search/title/<query>`: listet Serien **pro Episode** (`/stream/silo-s03e06`), Filme als einzelne Seite. Keine Serien-Stammseite mit Staffeln.
- Stream-Seite `/stream/<slug>`: Hoster-Links in `ul.currentStreamLinks a.iconPlay` mit `data-player-url` (primÃ¤r) bzw. `href` (fallback).
- Gesehene Hoster: firestream.to, vidaraa.cc, voe.sx, vidsonic.net â gemappt auf `Voe1`, `FileMoonSx`, `VidHidePro` (Ryderjet), `Supervideo` (AbstreamTo).

### Filmpalast-spezifische `load()`-Logik
1. TMDB-Meta holen (`api.themoviedb.org/3`, de-DE) â `displayTitle` + `year`.
2. Filmpalast-Suche nach `displayTitle`.
3. Treffer matchen (normalisierter Titel-Vergleich, Typ movie/tv). Serie `"Silo S03E06"` â Basisname `"Silo"` wird gegen TMDB-Titel gematcht.
4. Serie: alle Episoden sammeln â `TvSeriesLoadResponse` (Season/Episode aus Titel geparst). Film: `MovieLoadResponse` mit `dataUrl=JSON{links:[...]}`.
5. `loadLinks`: FilmâJSON-Links; SerieâEpisoden-URL fetchen + Host-Links sammeln â `loadExtractor()` pro registriertem Hoster.

### Bekannte Vorbehalte (Proof-of-Concept)
- **Apple-TV+-Serien (Silo):** deutsche Scraper haben solche Titel u.U. nicht oder zeitverzÃ¶gert. TMDB-Titel passt, aber Filmpalast muss die Serie im Katalog haben.
- **TMDB-API-Key:** fest codiert (Ã¶ffentlich bekannter Cloudstream-Key). FÃ¼r Produktion ggf. eigener Key.
- **Hoster-Dead:** Filmpalast-Hosterdomains rotieren; Extractor-Mapping muss ggf. nachjustiert werden. Neue Domains via `registerExtractorAPI` hinzufÃ¼gen.

### â ï¸ status-Wert MUSS 1 sein (verifiziert im ARVIO-Code)
Der cloudstream-gradle-plugin-Default ist `status = 3` ("Beta only"). **Das bricht ARVIO.**
- `PluginManager.downloadDexExtensions` (PluginManager.kt:1079): `manifestEnabled = plugin.status == 1`
- `PluginDataStore.setScraperEnabled` (PluginDataStore.kt:152): `if (enabled && !scraper.manifestEnabled) return` â speichert das Enable **nicht**, wenn `manifestEnabled=false`.
- Folge: Plugin sichtbar in der Liste, aber Toggle speichert nicht â Scraper lÃ¤uft nicht â keine Quellen.
- **Fix:** Im Modul-`build.gradle.kts` IMMER `status = 1` setzen (wie GermanProviders: alle 21 Plugins `status=1`). Nie Default `3` lassen.

### â ï¸ Hoster-Extraktion: built-in cloudstream3-Extractoren nutzen, nicht re-registrieren (verifiziert)
Filmpalast rotiert Hostnamen pro Episode/Load. Verifizierte Hostnamen (Aug 2026):
- **Built-in in cloudstream3** (ARVIO lÃ¤dt sie via `ExternalExtractorRegistry.installGlobal()` automatisch): `voe.sx` (Voe), `firestream.to` (Firestream), `filemoon.sx` (FileMoonSx), `supervideo.cc` (Supervideo), `vidhide.com` (VidHidePro).
- **NICHT built-in** (Filmpalast-spezifisch, eigene Extractor-Aliase nÃ¶tig): `ryderjet.com`, `abstream.to`.
- **Obskur / API-basiert** (kein statischer Extractor mÃ¶glich): `vidaraa.cc`, `vidsonic.net`, `odysseusa.cc`, `MoneyGalactic.com` (JWPlayer mit `t.streaming_url` aus API-Call â generischer Fallback findet nur sometimes direkte URLs).

**Fehler, der "no sources" verursachte (behoben in b6e3c1b):**
1. `loadLinks` setzte `any=true`, sobald `loadExtractor` *aufgerufen* wurde â ignorierte den RÃ¼ckgabewert. Wenn alle `loadExtractor` `false` zurÃ¼ckgaben (kein passender Extractor), blieb `any` trotzdem `true` â irrefÃ¼hrend. Fix: `any` nur auf `true` wenn `loadExtractor` true ODER generischer Fallback findet URL.
2. `Voe1()` registriert â `Voe1.mainUrl = "https://donaldlineelse.com"` (rotierender VOE-Mirror), matched **nicht** auf `voe.sx`-Links. Built-in `Voe()` (mainUrl=`voe.sx`) matched korrekt. Fix: `Voe1`/`FileMoonSx` nicht mehr re-registrieren (built-in reicht).
3. **Generischer Fallback** (`genericResolve`): fetcht Embed-Seite, sucht nach direkten mp4/m3u8-URLs (Regex). Best-Effort fÃ¼r obskure JWPlayer-Hoster; fÃ¤ngt nicht alle (vidaraa braucht API-Call), aber fÃ¤ngt z.B. firestream-Video-Pfade.

### Recherche: ARVIO Test-Funktion & Log-MÃ¶glichkeit (Aug 2026, ARVIO 1.9.983)
**ARVIO hat KEINE Log-Datei-Exportfunktion.** `DiagnosticsManager` ist nur fÃ¼r Sentry/Crashlytics-Reporting, keine In-App-Log-Anzeige. Der einzige Weg an die Scraper-Logs zu kommen ist **Logcat** (`adb logcat` Ã¼ber USB am PC).
- ARVIO hat im Code eine **"Test Scraper"-Funktion** (`PluginManager.testScraper()` â `executeWithDiagnostics()`), die mit The Matrix (TMDB 603) testet und `TestDiagnostics` mit Einzelschritten zurÃ¼ckgibt (TMDB-Metadaten, search-Ergebnisse, HTTP-Requests, loadLinks, "Missing extractors: ..."). **ABER: der "Test"-Button ist in `PluginScreen.kt` NICHT in die UI eingebaut** â Strings (`plugin_test_btn`, `plugin_diagnostics_expand`) und ViewModel-Logik existieren, aber kein Compose-Button ruft `PluginUiEvent.TestScraper` auf. Halbfertige ARVIO-Funktion (wie Stalker-VOD-UI).
- **WICHTIGE INKONSISTENZ:** `executeTmdbProviderWithDiagnostics` (Test-Pfad) ruft `loadLinks` mit `TmdbLink(...).toJson()` direkt auf (OHNE `load()`), wÃ¤hrend `executeTmdbProvider` (echte Suche) erst `api.load({"id":...,"type":...})` aufruft und `extractData()` das `dataUrl`/`episode.data` extrahiert. Mein `loadLinks` ist auf den load()-Pfad ausgelegt (`{"links":[...]}` oder `http`-URL), wÃ¼rde also im Test-Pfad leer laufen. Falls ARVIO den Test-Button irgendwann aktiviert, muss mein `loadLinks` auch TmdbLink-JSON verarbeiten.

### Recherche: Touch-Bug auf Handy/Tablet (ARVIO Issue #502)
**BestÃ¤tigt und (teilweise) behoben in ARVIO 1.9.983.** ARVIO Issue #502 "fix(mobile): resolve touch issues and unify button styling in plugins settings":
- Ursache: Plugin-Settings-Screen + Add-Repo-Dialog nutzten TV-only `androidx.tv.material3.Surface`-Buttons, die auf Touch-GerÃ¤ten nicht reagierten.
- Fix: `PluginScreen.kt` hat jetzt `LocalDeviceType.current.isTouchDevice()` â separates Mobile-Layout mit touch-friendly Compose-Box-Buttons. **In 1.9.983 enthalten** (verifiziert: `isTouchDevice` existiert in `PluginScreen.kt`).
- Falls der Nutzer noch eine Ã¤ltere Version als 1.9.983 hat, sollte er updaten. Der Fix erklÃ¤rt, warum der Nutzer es Ã¼ber Cloud-Profil auf dem Handy zum Laufen brachte.

### Recherche: "nur webstreamr-Quellen, nicht Filmpalast" â mÃ¶gliche Ursachen (Aug 2026)
Da webstreamr (Stremio-Addon, serverseitig) Quellen liefert, mein Filmpalast-Scraper (Cloudstream-DEX) aber nicht, sind die Scraper-Logs nÃ¶tig. MÃ¶gliche Ursachen (in absteigender Wahrscheinlichkeit):
1. **Scraper wird aufgerufen, aber `load()` schlÃ¤gt fehl** â `loadResponse` null â `executeTmdbProvider` "both load() paths failed" â emptyList. KÃ¶nnte ein Kotlin-spezifisches Problem sein (Jsoup-Selektor-Unterschied zu Python-Regex, oder Exception in `fetchTmdbMeta`/`searchFilmpalast`).
2. **Scraper ist nicht in `enabledScrapers`** â Plugin-Download fehlgeschlagen, oder `manifestEnabled` false, oder Toggle aus. (Weniger wahrscheinlich, da `status=1` verifiziert und Plugin sichtbar ist.)
3. **`loadLinks` findet Hoster aber `loadExtractor` liefert 0 Links** â Filmpalast rotiert Hostnamen; wenn nur nicht-built-in-Hoster (vidaraa.cc etc.) online, fÃ¤llt alles durch. (Mein generischer Fallback fÃ¤ngt nur direkte mp4/m3u8.)
- **Ohne Logcat nicht eindeutig trennbar.** Logcat-Filter die helfen: `ExtExtractorRegistry`, `ExternalExtensionRunner`, `PluginManager`, `TmdbProvider Filmpalast`, `ExtExtRunner`.


Selbst bei korrekt aktiviertem Cloudstream-Scraper zeigt ARVIO oft "keine Streaming-Addons eingerichtet". Ursache ist eine ARVIO-seitige LogiklÃ¼cke:
- `StreamRepository.getStreamAddons` (StreamRepository.kt:1440): `if (addon.runtimeKind != RuntimeKind.STREMIO) return@filter false` â **nur Stremio-Addons** kommen in die Stream-Auswahl.
- `DetailsViewModel` berechnet `hasStreamingAddons` aus `streamRepository.installedAddons.count { it.isVodStreamingAddon() }` (DetailsViewModel.kt:1633) â zÃ¤hlt **nur Stremio-Addons**, nicht Cloudstream-Scraper.
- Cloudstream-Scraper sind eine **getrennte Liste** (`PluginManager.scrapers`), nicht in `installedAddons` â werden fÃ¼r `hasStreamingAddons` nicht gezÃ¤hlt.
- **Aber:** `DetailsViewModel` (DetailsViewModel.kt:1516) ruft `pluginManager.executeScrapersStreaming()` separat auf â Cloudstream-Scraper **laufen im Hintergrund** und mergen Streams in `streams`. Nur die *Meldung* ist falsch, nicht das Scraping.
- **Workaround:** ZusÃ¤tzlich ein (Dummy-)Stremio-Addon aktivieren â `addonCount > 0` â `hasStreamingAddons=true` â Meldung verschwindet. Scraper-Ergebnisse erscheinen dann in der Liste.
- **ARVIO-seitiger Fix nÃ¶tig:** `getStreamAddons`/`hasStreamingAddons` sollten auch EXTERNAL_DEX-Scraper zÃ¤hlen. Lohnt als GitHub-Issue.

### Build (lokal)
JDK 17+ und Android SDK 35 nÃ¶tig. Im Env: `JAVA_HOME` + `ANDROID_HOME` (oder `local.properties` mit `sdk.dir`).
```
./gradlew make makePluginsJson
# -> FilmPalast/build/FilmPalast.cs3
# -> build/plugins.json
```

## Schritt-fÃ¼r-Schritt: Diagnose-Log auslesen (v1.2+)

Das Plugin schreibt jeden Schritt des Filmpalast-Scrapers in einen internen Trace und stellt ihn Ã¼ber einen lokalen HTTP-Server auf `http://localhost:8420/` bereit. So liest du das Log:

1. **Neues Plugin in ARVIO laden.** ARVIO-Einstellungen â Plugins & Extensions â Filmpalast aktualisieren/einschalten. Ab v1.2 startet beim Laden des Plugins automatisch der Diagnose-Server (im ARVIO-Prozess, nur loopback).
2. **Quellensuche auslÃ¶sen** (das, was bisher leer blieb): Ãffne in ARVIO z.B. "Matrix" (Film) oder "Silo" (Serie) â "nach Quellen suchen". Das triggert ARVIOs Aufruf von `load()`/`loadLinks()` und erzeugt Trace-EintrÃ¤ge.
3. **Log im Handy-Browser ansehen:** Ãffne einen Browser auf **demselben GerÃ¤t**, auf dem ARVIO lÃ¤uft (Chrome/Firefox), und gehe zu `http://localhost:8420/`.
   - Die Seite aktualisiert sich automatisch alle 3 Sekunden.
   - `http://localhost:8420/raw` â reiner Text (zum Kopieren).
   - `http://localhost:8420/clear` â Trace lÃ¶schen (vor einer neuen Suche).
4. **Trace lesen / interpretieren:**
   - **Gar kein Trace-Eintrag** nach einer Suche â ARVIO ruft den Scraper nicht auf (ARVIO-Seite: `manifestEnabled`/`enabledScrapers`/`supportsType`). Der Diagnose-Server selbst sollte aber beim Plugin-Laden "listening on http://localhost:8420" geloggt haben â taucht das nicht auf, lief das Plugin gar nicht.
   - `load: could not parse TMDB input` â ARVIO ruft `load()` mit einem Format auf, das wir nicht erwarten.
   - `fetchTmdbMeta: request threw ...` â TMDB-Erreichbarkeit/Key-Problem.
   - `searchFilmpalast: CSS selector matched 0 elements` â Filmpalast-Seitenstruktur hat sich geÃ¤ndert (Jsoup-Selektor veraltet) ODER Bot-Schutz/403.
   - `load: after matchResults -> 0 matches` â Suche liefert Treffer, aber `matchResults` filtert alle raus (Titel-Normalisierung zu streng).
   - `loadLinks: 0 links -> returning false` â `collectHosterLinks` findet nichts (Selektor/`data-player-url`-Attribut geÃ¤ndert).
   - `loadExtractor('...') -> matched=false` fÃ¼r ALLE Links â keine built-in Extractoren fÃ¼r die aktuellen Hoster-Domains.
5. **Log fÃ¼r mich aufheben:** Entweder den `/raw`-Text kopieren und in der nÃ¤chsten Session einfÃ¼gen, ODER die gespiegelte Datei `Android/data/com.arflix.tv/files/arvio-addon-logs/filmpalast-trace.log` (ab Android 13 evtl. nur Ã¼ber ADB erreichbar).
6. **Falls der Browser die Seite nicht lÃ¤dt:** Server lÃ¤uft nur, solange der ARVIO-Prozess lebt. ARVIO zwischendrin nicht beenden. Alternativ via ADB: `adb forward tcp:8420 tcp:8420` dann am PC `curl http://localhost:8420/raw`.

## Versionshistorie dieses Addons

- **v1 (Proof-of-Concept):** Filmpalast-Plugin als TmdbProvider. Baut & kompiliert. Noch nicht in ARVIO endgeraet-getestet.
- **v1.1 (Aug 2026, Commits b6e3c1b bis 8aa09d3):** Hoster-Extraktion gefixt (loadLinks respektiert loadExtractor-Return; Voe1 entfernt; generischer Fallback fuer unbekannte Hostnamen); endgeraet-getestet in ARVIO 1.9.983 (sideload) von Nutzer. Plugin laedt, ist sichtbar & aktivierbar. **Aber:** bei Quellensuche (Matrix/Silo) zeigt ARVIO nur webstreamr-Quellen, nicht Filmpalast - Root-Cause offen, Logcat vom Geraet noetig (siehe "AKTUELLER STAND" ganz oben). AGENTS.md umfassend mit ARVIO-Scraper-Pfad, Touch-Bug-Fix #502, Test-Funktion-Status und Logcat-Optionen dokumentiert.
- **v1.2 (13.08.2026):** Selbst-Diagnose-Modus statt Logcat. `DebugLog.kt` + `DebugServer.kt` (lokaler HTTP-Server `localhost:8420`), `FilmpalastProvider` vollstÃ¤ndig instrumentiert, Version auf 2 gebumpt. Ersetzt Logcat-Zugang fuer unseren eigenen Scraper-Code. Siehe "Schritt-fuer-Schritt: Diagnose-Log auslesen".
- **v1.3 (13.08.2026, Commits bis ca9f81f):** Diagnose-Tooling massiv ausgebaut, aber **Kernerkenntnis: ARVIO ruft .cs3-Plugins auf dem Geraet GAR NICHT auf.** Beweise: (a) GermanProviders (bewaehrtes .cs3-Repo) liefert auf dem Geraet ebenfalls 0 Quellen, (b) unsere v6-v8 haetten bei JEDEM loadLinks-Aufruf ArvioAddon-Debug-Quellen emittieren muessen - erschienen nie, (c) GitHub-Issues #459/#273 berichten exakt dasselbe Symptom. Webstreamr (Stremio-Addon) funktioniert = anderer ARVIO-Code-Pfad. Versionen: v3 DebugServer auf 127.0.0.1; v4 File-Trace+PLUGIN_LOADED Marker; v5 MediaStore->public Download; v6 Diagnose als Pseudo-Quellen in ARVIO-Quellenauswahl; v7 load() gibt nie null zurueck (debugLoadResponse) damit loadLinks garantiert laeuft; v8 Per-Call-Netzwerk-Timeouts. ARVIO library (TmdbProvider/MainAPI/Plugin) verifiziert vorhanden in classes3/4.dex. ARVIO-Timeouts (120s/60s) schliessen Timeout als Ursache aus. **Naechster Schritt: mit Laptop weiter (Logcat via USB+adb); ggf. GitHub-Issue bei ARVIO.** Siehe "AKTUELLER STAND" ganz oben.
