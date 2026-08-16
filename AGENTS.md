# AGENTS.md –ď—ě–í“Ė–í‚Äú Ventix Arvio Addon

Dieses Repo baut ein **Cloudstream3-kompatibles Plugin** f–ď“ď–í—ėr die **ARVIO** Android-TV-App (sideload-APK).
Ziel: Ventix-Funktionalit–ď“ď–í”®t (deutsche Web-Scraper + Stalker-VOD) als Plugin in ARVIO laufen lassen –ď—ě–í“Ė–í‚Äú clientseitig, ohne Server.

## –ď—ě–í”Į–í“ó AKTUELLER STAND & N–ď“ď–í‚ÄěCHSTE SCHRITTE (Stand 14.08.2026 –ď—ě–í“Ė–í‚Äú LOGCAT-ERKENNTNIS)

### ENTSCHEIDENDE ERKENNTNIS (14.08.2026, Logcat via USB+adb auf Pixel 7): Die .cs3-Dateien werden NIE heruntergeladen
**Root-Cause gefunden und verifiziert im Logcat + ARVIO-Source.** Das Problem ist NICHT, dass ARVIO den Scraper nicht aufruft –ď—ě–í“Ė–í‚Äú ARVIO ruft ihn auf, findet aber keine Datei.

**Logcat-Befund (arvio-log.txt, arvio-log2.txt, Pixel 7, ARVIO 1.9.983 sideload):**
```
D PluginManager: Streaming execution of 18 scrapers for movie:603   <-- Scraper-Pfad wird durchlaufen (Matrix, TMDB 603) –ď—ě–í“£–í‚Äú
D PluginManager: Executing DEX scraper: FilmPalast                    <-- ARVIO will jeden Scraper ausf–ď“ď–í—ėhren –ď—ě–í“£–í‚Äú
E ExtExtensionLoader: DEX file not found for <repoId>:FilmPalast: /data/user/0/com.arvio.tv/files/cs_extensions/<repoId>_FilmPalast.cs3  –ď—ě–í“£–í‚ÄĒ DATEI FEHLT
E ExtExtensionRunner: No API loaded for scraper: <repoId>:FilmPalast  –ď—ě–í“£–í‚ÄĒ
D PluginManager: DEX scraper FilmPalast returned 0 results             –ď—ě–í“£–í‚ÄĒ
```
- Passiert bei ALLEN 18 Scrapern (FilmPalast, HDFilme, Kinoger, ARD, Discovery, Arte, KinoKing, EinschaltenIn, HuhuTo, PlutoTV, Megakino, Serienstream, Netzkino, SpiegelTV, Moflix, Xcine, Welt) –ď—ě–í“Ė–í‚Äú unserem UND GermanProviders. Best–ď“ď–í”®tigt: ARVIO-seitiges Problem.
- `ExtExtensionLoader: ensureExtractorsLoaded: scanned 0 .cs3 files, registered 0 extractors` –ď—ě–í“∂–í‚Äô `cs_extensions`-Ordner komplett LEER.
- WebStreamr (Stremio-Addon) funktioniert (3 streams, 757ms/322ms) –ď—ě–í“∂–í‚Äô Stremio-Pfad l–ď“ď–í”®uft, nur .cs3-Pfad kaputt.
- **KEIN einziger Download-Versuch im Log.** Kein "Downloading", keine HTTP-Request zu raw.githubusercontent.com, kein "Failed to download extension: HTTP ...", kein "Downloaded extension". ARVIO hat die Scraper-Metadaten (Name/ID/URL aus plugins.json) in der Datenbank, aber die .cs3-Datei nie heruntergeladen.

**Warum der Download nie stattfindet (verifiziert im ARVIO-Source @ v1.9.983):**
- `PluginManager.addRepository()` (PluginManager.kt:426) ruft `downloadDexExtensions(repo.id, parseResult.plugins)` auf –ď—ě–í“∂–í‚Äô das l–ď“ď–í”®dt die .cs3-Dateien herunter (parallel via `downloadExtension`).
- **ABER: Der Nutzer f–ď“ď–í—ėgt Repos via Cloud-Sync hinzu, nicht via Add-Repository-Dialog!** `CloudSyncRepository.applyCloudPayload()` (CloudSyncRepository.kt:1721-1731) macht beim Restore nur:
  - `pluginDataStore.saveRepositories(repos)` (nur Metadaten in DB)
  - `pluginDataStore.saveScrapers(scrapers)` (nur Metadaten in DB –ď—ě–í“Ė–í‚Äú inkl. URL, aber KEIN Download!)
  - `pluginDataStore.setPluginsEnabled(...)` (global an)
  - **KEIN Aufruf von `downloadDexExtensions`!** Cloud-Sync synchronisiert Scraper-Metadaten, aber NICHT die .cs3-Dateien.
- Folge: Scraper erscheint in der Liste (Metadaten da), Toggle speichert (manifestEnabled=true, status=1), ARVIO versucht Ausf–ď“ď–í—ėhrung ("Executing DEX scraper") –ď—ě–í“Ė–í‚Äú aber `cs_extensions/` ist leer –ď—ě–í“∂–í‚Äô "DEX file not found" –ď—ě–í“∂–í‚Äô "No API loaded" –ď—ě–í“∂–í‚Äô 0 results.
- Das erkl–ď“ď–í”®rt, warum es auf TV UND Handy identisch ist: Cloud-Sync kopiert nur Metadaten, die .cs3-Downloads werden pro-Ger–ď“ď–í”®t nur bei direktem `addRepository`/`refreshExternalRepository` getriggert –ď—ě–í“Ė–í‚Äú und der Nutzer hat (vermutlich wegen des Touch-Bugs fr–ď“ď–í—ėher) alles –ď“ď–í—ėber Cloud-Profil gemacht, nie direkt auf dem Ger–ď“ď–í”®t.

**N–ď“ď–í‚ÄěCHSTER SCHRITT (Prio 1): Repo auf dem Ger–ď“ď–í”®t DIREKT hinzuf–ď“ď–í—ėgen (nicht Cloud-Sync), dabei Logcat mitlaufen lassen**
Ziel: sehen, ob `downloadDexExtensions`–ď—ě–í“∂–í‚Äô`downloadExtension` –ď“ď–í—ėberhaupt aufgerufen wird und ob der Download fehlschl–ď“ď–í”®gt (HTTP 404/403/Timeout) oder ob ARVIO den Download gar nicht erst triggert.
1. `adb logcat -c` (Puffer leeren).
2. Am Pixel 7 in ARVIO: Repos L–ď“ď–í‚ÄďSCHEN (beide: Arvio-Addon + GermanProviders).
3. Am Pixel 7 in ARVIO: **Add Repository** DIREKT auf dem Ger–ď“ď–í”®t –ď—ě–í“∂–í‚Äô Repo-URL eingeben (`https://raw.githubusercontent.com/ReichiMD/Arvio-Addon/main/repo.json`) –ď—ě–í“∂–í‚Äô hinzuf–ď“ď–í—ėgen. WICHTIG: nicht –ď“ď–í—ėber Cloud-Sync/Profil, sondern direkt den Add-Repo-Dialog auf dem Ger–ď“ď–í”®t nutzen.
4. Warten, bis ARVIO "Repository hinzugef–ď“ď–í—ėgt" meldet (sollte .cs3 downloaden).
5. Scraper einschalten.
6. `adb logcat -d | grep -iE "download|ExtExtension|PluginManager|cs3|HTTP|Failed|extension"` –ď—ě–í“∂–í‚Äô Output kopieren. Worauf achten:
   - `Downloaded extension <id>: <bytes> bytes -> ...` –ď—ě–í“∂–í‚Äô Download ERFOLGREICH (Problem gel–ď“ď–í¬∂st!).
   - `Failed to download extension <id>: HTTP 404` / `HTTP 403` –ď—ě–í“∂–í‚Äô URL falsch/blocked (unsere plugins.json URL pr–ď“ď–í—ėfen).
   - `Error downloading extension <id>: ...` –ď—ě–í“∂–í‚Äô Exception (Netzwerk/SSL/Timeout).
   - Gar kein Download-Log –ď—ě–í“∂–í‚Äô `addRepository` wird nicht wie erwartet durchlaufen (Routing-Problem).
7. Falls Download klappt: Quellensuche (Matrix) ausl–ď“ď–í¬∂sen –ď—ě–í“∂–í‚Äô pr–ď“ď–í—ėfen, ob jetzt Filmpalast-Quellen kommen.
8. Falls Download fehlschl–ď“ď–í”®gt: unsere `plugins.json`/`.cs3`-URL im builds-Branch pr–ď“ď–í—ėfen (raw.githubusercontent.com erreichbar? Datei da? status=1?).

**Prio 2 (danach): Am TV dasselbe** –ď—ě–í“Ė–í‚Äú TV per USB ans Laptop, gleicher `adb logcat`-Flow. Da der TV das prim–ď“ď–í”®re Zielger–ď“ď–í”®t ist, muss der Download dort auch direkt (Add Repository) getriggert werden, nicht –ď“ď–í—ėber Cloud-Sync. LADB-App scheiterte am Pairing (siehe unten)–ď—ě–í“∂–í‚Äô TV braucht USB-Verbindung zum Laptop (dazu ggf. l–ď“ď–í”®ngeres USB-Kabel am TV oder USB-Port am TV nutzen).

**Prio 3: GitHub-Issue bei ARVIO** –ď—ě–í“Ė–í‚Äú Cloud-Sync-Restore l–ď“ď–í”®dt .cs3-Dateien nicht herunter (`saveScrapers` ohne `downloadDexExtensions`). Das ist ein klarer ARVIO-Bug: Wer Plugins via Cloud-Sync auf ein neues Ger–ď“ď–í”®t –ď“ď–í—ėbernimmt, hat leere Scraper. Skizze siehe unten (Prio 2 im alten Stand). AI-Disclosure beachten.

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

### FIX #4 (14.08.2026, v11): kotlin-stdlib IN die .cs3-DEX b√ľndeln
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
**Wichtige Erkenntnis:** `mainPage`/`hasMainPage`/`getMainPage` werden **NUR von der Cloudstream3-App-Startseite** genutzt. ARVIOs Scraper-Pfad (`executeTmdbProvider`) ruft nur `load()` + `loadLinks()` auf ‚ÄĒ `getMainPage()` wird **nie** aufgerufen. Also ist das alles **toter Code fuer ARVIO**, der nur R8-Strip-Fehlerpunkte schafft.

### FIX #6 (14.08.2026, v13): mainPage/getMainPage komplett entfernt
`hasMainPage`, `mainPage`, `getMainPage()` **komplett geloescht** ‚ÄĒ keine MainPageData-Konstruktion mehr. Provider ueberschreibt jetzt nur noch, was ARVIO wirklich aufruft: `search()`, `load()`, `loadLinks()`. Das minimiert die R8-geshrinkte cloudstream3-API-Oberflaeche auf das, was ARVIO selbst nutzt.
- Version auf 13 gebumpt. CI gruen. builds-Branch: FilmPalast.cs3 v13 (1268533 Bytes, status=1).
- **Erwartung v13-Test:** plugin.load() durchlaufen OHNE NoSuchMethodError (keine MainPageData mehr). "API loaded" / "Executing DEX scraper: FilmPalast" -> load() laeuft. Falls naechster R8-Strip (z.B. newMovieLoadResponse/newTvSeriesLoadResponse/loadExtractor): jeweilige Methode notieren -> retained-Alternative oder direkten Konstruktor verwenden.

### ENTSCHEIDENDE ERKENNTNIS #7 (14.08.2026, v13-DEX-Analyse + ARVIO-APK-Analyse): R8 hat kotlin.coroutines.Continuation obfuscated ‚ÄĒ suspend-Overrides funtionieren NICHT
**Root-Cause fuer "load() override wird nicht aufgerufen" gefunden und verifiziert durch DEX-Bytecode-Analyse der ARVIO-APK.**

v13 laedt erfolgreich (Provider registriert, Extractoren registriert, "API loaded" best√§tigt in log6). Aber ARVIO ruft bei `api.load(loadJson)` die **PARENT** `TmdbProvider.load()` auf, nicht unseren Override. Das Ergebnis: `ErrorLoadingException: No id found` (parent parst JSON nicht), dann Fallback-URL `themoviedb.org/movie/603` (parent parst URL, ruft `loadFromTmdb` auf, aber wir haben das nicht ueberschrieben), dann `both load() paths failed` ‚Üí 0 Quellen.

**Warum der Override nicht bindet (verifiziert im ARVIO-APK-Bytecode):**
- ARVIOs R8 (full mode, `isMinifyEnabled=true`) hat **kotlin.coroutines.Continuation zu `j7.d` obfuscated** und **kotlin.jvm.functions.Function1 zu `x7.l`**.
- Die `-keep class com.lagradost.** { *; }`-Regel behaelt cloudstream3-Klassennamen + Methodennamen, aber R8 obfuscated die **Parameter-TYPEN** in Methodensignaturen unabhaengig davon.
- ARVIOs `MainAPI.load()` in der kompilierten APK hat Signatur: `load(Ljava/lang/String;Lj7/d;)Ljava/lang/Object;` (j7.d = obfuscated Continuation).
- Unser `FilmpalastProvider.load()` in der .cs3-DEX hat Signatur: `load(Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;` (unobfuscated, weil wir gegen `pre-release`-Stub kompilieren).
- **Lj7/d; != Lkotlin/coroutines/Continuation;** ‚Üí JVM findet unseren Override nicht ‚Üí virtual dispatch faellt auf parent zurueck ‚Üí parent laeuft.
- **Dasselbe gilt fuer `loadLinks` und `search`:** alle suspend-Methoden haben Continuation-Parameter ‚Üí alle Overrides sind broken.
- ARVIOs `executeTmdbLoadLinks$completed$1` ruft auf: `invoke-virtual MainAPI->loadLinks(Ljava/lang/String;ZLx7/l;Lx7/l;Lj7/d;)Ljava/lang/Object;` (x7.l = obfuscated Function1, j7.d = obfuscated Continuation).
- Unsere `loadLinks` hat: `(Ljava/lang/String;ZLkotlin/jvm/functions/Function1;Lkotlin/jvm/functions/Function1;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;` ‚Üí mismatch.

**Verifizierte Obfuscation-Map (aus ARVIO-APK extrahiert, classes4.dex/classes5.dex):**
| Original-Typ | Obfuscated | Wo gefunden |
|---|---|---|
| `kotlin.coroutines.Continuation` | `j7.d` | MainAPI.load/loadLinks/search Signaturen; interface mit getContext():j7.j + resumeWith(Object):V |
| `kotlin.coroutines.CoroutineContext` | `j7.j` | j7.d.getContext() return type |
| `kotlin.jvm.functions.Function1` | `x7.l` | MainAPI.loadLinks callback-Parameter; interface mit invoke(Object):Object, extends d7.o |
| `kotlin.jvm.functions.Function` | `d7.o` | x7.l's super-interface |

**Nicht-obfuscated (durch `-keep` geschuetzt oder primitiv):**
- `com.lagradost.cloudstream3.**` (Klassen + Methoden-Namen; `-keep class com.lagradost.** { *; }`)
- `loadFromTmdb(I)Lcom/lagradost/cloudstream3/LoadResponse;` (int-Parameter = primitiv, LoadResponse = kept) **‚Üí dieser Override wuerde funktionieren!**
- `loadFromImdb(Ljava/lang/String;)Lcom/lagradost/cloudstream3/LoadResponse;` (String + LoadResponse = both unobfuscated) **‚Üí wuerde auch funktionieren!**

**WARUM das ALLE .cs3-Plugins in ARVIO betrifft (nicht nur unseres):**
- Jedes Cloudstream3-Plugin, das gegen den unobfuscated cloudstream3-Stub (GitHub `pre-release`/`v4.7.0`) kompiliert, hat unobfuscated Continuation/Function1 in seinen Override-Signaturen.
- ARVIOs R8-obfuscated Runtime hat j7.d/x7.l ‚Üí **KEIN** externes .cs3-Plugin kann suspend-Methoden (load/loadLinks/search/getMainPage) korrekt ueberschreiben.
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

### ENTSCHEIDENDE ERKENNTNIS #8 (14.08.2026, v14-TV-Test, arvio-tv-log-v14.txt): v14 DEX ist KAPUTT ‚ÄĒ ART-Verifizierung schlaegt fehl

v14 wurde heruntergeladen (1.268.540 Bytes), ABER ARVIOs ART-DEX-Verifier lehnt die DEX-Datei ab ‚ÄĒ sie ist strukturell beschaedigt. Das Plugin wird NIE geladen:
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

**Root-Cause (verifiziert per DEX-Analyse der builds-FilmPalast.cs3):** Der v14-Build kompilierte gegen die dex2jar-extrahierte obfuszierte ARVIO-JAR (`libs/arvio-cloudstream3-v1.9.983.jar` via `cloudstream(files(arvioJar))`). Dadurch bekamen die Override-Signaturen korrekt obfuszierte Typen (`load(Ljava/lang/String;Lj7/d;)` etc. ‚ÄĒ verifiziert mit androguard, korrekt!). ABER dex2jars unvollstaendige Decompilierung der obfuszierten Interface-Klassen (`j7/d` = Continuation, `j7/j` = CoroutineContext, `x7/l` = Function1) wurde mit in die .cs3-DEX gebuendelt (3 class_defs: j7/d, j7/j, x7/l). Diese dex2jar-Klassen mit fehlerhaftem Bytecode/Gefuege korrumpten die DEX-Struktur (Sektionsgrenzen landeten mitten in string_data) -> ART-Verifier lehnt ab.
- Das DEX-Patch-Skript (Ansatz 2) war ein **No-Op** in v14 (0 Strings gepatched): die Signaturen waren bereits obfusziert (von der JAR), das Skript fand keine unobfuszierten Strings mehr. Der Patch hat also NICHT die Korrumpierung verursacht ‚ÄĒ die dex2jar-Klassen haben es.
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

**Erwartung v15-Test:** Plugin laedt (valide DEX, keine dex2jar-Klassen), Override-Signaturen obfusziert (j7/d) -> ARVIO ruft UNSERN load()/loadLinks() auf statt Parent. Naechster moeglicher Fehler: Scraper-Logik (Jsoup-Selektoren, Hoster-Extraktion) ‚ÄĒ dannEbene 2.

### ENTSCHEIDENDE ERKENNTNIS #9 (15.08.2026, v15-TV-Test, arvio-tv-log-v15-filtered.txt): v15 DEX KORRUPT ‚ÄĒ Patch-Skript-Null-Padding bricht DEX-Struktur

v15 scheitert an **exakt demselben DEX-Verify-Fehler wie v14** (derselbe Offset `0x3111d2`, dieselbe Dateigr√∂√üe 1.268.540 Bytes):
```
Failure to verify dex file '...FilmPalast.cs3': Non-zero padding b before section of type 8196 at offset 0x3111d2
ClassNotFoundException: Didn't find class "...FilmpalastPlugin"
No @CloudstreamPlugin class found in ...:FilmPalast
No API loaded for scraper: ...:FilmPalast
DEX scraper FilmPalast returned 0 results
```

**Root-Cause (verifiziert per DEX-Bytecode-Analyse der builds-FilmPalast.cs3):** Der Fehler liegt **NICHT** in dex2jar (wie bei v14 vermutet), sondern im **Patch-Skript selbst** (`scripts/patch_dex_obfuscation.py`).

Das Skript ersetzt lange Kotlin-Typ-Strings durch kurze obfuszierte Namen (z.B. `Lkotlin/coroutines/Continuation;` 34 Zeichen ‚Üí `Lj7/d;` 6 Zeichen) und fuellt die Laengendifferenz (**26‚Äď30 Bytes**) mit **Null-Padding** auf, um keine Offsets verschieben zu muessen. **ABER:** die DEX `string_data`-Sektion packt Strings direkt hintereinander (Format: ULEB128(utf16_size) + mutf8_data + \x00). Nach jedem gekuerzten String liegen jetzt 26‚Äď30 Null-Bytes **mitten in der Sektion**. ARTs Verifier erwartet dort KEINE Null-Padding-Sequenzen (nur am Sektionsende) und lehnt die DEX ab.

Verifizierte Beweislage:
- **3 Strings korrekt gepatched:** `Lj7/d;` (string[10933] @ 0x2a4576, 26B padding), `Lj7/j;` (string[10946] @ 0x2a47e6, 30B padding), `Lx7/l;` (string[11172] @ 0x2a6ff0, 26B padding). Patch-Mechanismus funktioniert prinzipiell.
- **Aber:** 3√ó ~28B = ~84B Null-Padding mitten in der string_data-Sektion ‚Üí DEX-Struktur invalid.
- Fehler-Offset `0x3111d2` = String ‚Äězip$default" (string[23018], nahe Sektionsende). ART scannt rueckwaerts vom Start der naechsten Sektion (ENCODED_ARRAY_ITEM @ 0x330ca4) und stolpert ueber die Padding-Nullen.
- `d7/o` (Function) wurde NICHT gepatched (nur 3 von 4 Strings) ‚ÄĒ vermutlich nicht in der DEX enthalten (kein Vorkommen). Kein Problem, nur unvollstaendig.

**Warum v14 denselben Fehler hatte:** v14 kompilierte gegen die dex2jar-JAR (obfuszierte Signaturen direkt, kein Patch noetig ‚Üí Skript war No-Op mit 0 gepatchten Strings). Die dex2jar-Klassen korrumpten die DEX. v15 kompiliert gegen den sauberen Stub (unobfuszierte Signaturen) ‚Üí Skript patched 3 Strings ‚Üí aber das Null-Padding korrumpiert die DEX. **Beide Male ist die DEX strukturell invalid, aber aus unterschiedlichen Ursachen.**

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

### –ď“ď–í“£BERSCHRIEBENER ALTER STAND (13.08.2026 –ď—ě–í“Ė–í‚Äú vor Logcat, als Referenz behalten)
Fr–ď“ď–í—ėhere Annahme war "ARVIO ruft .cs3-Plugins GAR NICHT auf". **KORRIGIERT durch Logcat:** ARVIO ruft sie sehr wohl auf ("Executing DEX scraper"), aber die .cs3-Dateien fehlen auf dem Ger–ď“ď–í”®t ("DEX file not found"), weil Cloud-Sync sie nie herunterl–ď“ď–í”®dt. Die Diagnose-Plugins v6–ď—ě–í“Ė–í‚Äúv8 erschienen deshalb nie –ď—ě–í“Ė–í‚Äú nicht weil ARVIO die Klasse nicht instanziiert, sondern weil es gar keine Datei zum Laden gibt. Der Rest der alten Beweislage (GermanProviders ebenfalls leer, WebStreamr funktioniert, GitHub-Issues #459/#273) bleibt g–ď“ď–í—ėltig und wird durch den neuen Befund erg–ď“ď–í”®nzt (Cloud-Sync-Problem erkl–ď“ď–í”®rt, warum es bei Nutzern auftritt, die Profil-basiert syncen).

**Beweislage (verifiziert, Stand 13.08.2026):**
- Nutzer hat ARVIO 1.9.983 **sideload** auf Android-TV. Plugin-Bereich sichtbar (–ď—ě–í“∂–í‚Äô sideload best–ď“ď–í”®tigt). Toggle bei Filmpalast AN, global alles aktiviert.
- Bei Quellensuche (Matrix, mehrere Filme & Serien) zeigt ARVIO **nur webstreamr-Quellen (Stremio-Addon), NIEMALS Filmpalast** –ď—ě–í“Ė–í‚Äú –ď“ď–í—ėber alle Plugin-Versionen v2–ď—ě–í“Ė–í‚Äúv8 hinweg, –ď“ď–í—ėber mehrere Neu-Installationen hinweg (Scraper-IDs –ď“ď–í”®nderten sich jeweils: eOf699f8–ď—ě–í“∂–í‚Äô2421c4b6–ď—ě–í“∂–í‚Äôneu –ď—ě–í“∂–í‚Äô best–ď“ď–í”®tigt frischer Download).
- **GermanProviders-Test (Bnyro/GermanProviders):** Nutzer installierte das bew–ď“ď–í”®hrte, anderswo funktionierende `.cs3`-Repo, aktivierte alle Scraper –ď—ě–í“∂–í‚Äô **auch dort KEINE Streams**. Das beweist: Es ist **NICHT unser Plugin**, sondern ARVIOs Cloudstream-`.cs3`-Pfad liefert auf dem Ger–ď“ď–í”®t bei **jedem** Plugin nichts. Webstreamr funktioniert, weil es ein **Stremio-Addon** (v–ď“ď–í¬∂llig anderer ARVIO-Code-Pfad) ist.
- **GitHub-Issue-Recherche** (`ProdigyV21/ARVIO`): Andere Nutzer berichten **exakt dasselbe Symptom** –ď—ě–í“Ė–í‚Äú Plugin installiert, in Liste sichtbar, Toggle an, aber keine Quellen:
  - **#459** "Nuvio JS scraper repository installs but returns no sources" (closed, ohne –ď“ď–í¬∂ffentliche L–ď“ď–í¬∂sung)
  - **#273** "I'm able to add nuvio plugin but not showing any video links" (closed; Dev @Himanth-reddy: "it should be working")
  - **#500** "unable to install the plugin" (open)
  - **#491** "plugins & extensions section shows addons not plugins" (gel–ď“ď–í¬∂st –ď—ě–í“∂–í‚Äô "next update")
  - v1.9.983-Changelog: "Added compatibility for **Nuvio-style JavaScript** scraper plugins" + "Fixed sideload **production-plugin routing**, extractor unloading, mobile routing". –ď—ě–í“∂–í‚Äô DEX/`.cs3`-Pfad wurde gerade erst angefasst und l–ď“ď–í”®uft offensichtlich **nicht zuverl–ď“ď–í”®ssig**.
- **Library verifiziert vorhanden:** ARVIOs APK (`classes3.dex`/`classes4.dex`) enth–ď“ď–í”®lt `com/lagradost/cloudstream3/metaproviders/TmdbProvider`, `MainAPI`, `plugins/Plugin`. Die Library fehlt also nicht.
- **ARVIO-Timeouts verifiziert:** `SCRAPER_TIMEOUT_MS=120_000`, `LOADLINKS_TIMEOUT_MS=60_000`, `EXECUTION_TIMEOUT_MS=120_000`. Unsere Per-Call-Timeouts (8s) sind weit drunter –ď—ě–í“∂–í‚Äô kann nicht Ursache sein.

### Warum die In-Plugin-Diagnose (v6–ď—ě–í“Ė–í‚Äúv8) trotzdem leer blieb
v6–ď—ě–í“Ė–í‚Äúv8 sind so gebaut, dass **sobald ARVIO `loadLinks()` auch nur einmal aufruft**, die Diagnose als Pseudo-Quellen in ARVIOs Quellenauswahl erscheinen M–ď“ď–í“£SSEN (`emitTraceAsSources` + –í‚ÄěPLUGIN vN loaded"-Banner + `load()` gibt nie `null` zur–ď“ď–í—ėck + Per-Call-Netzwerk-Timeouts). Da **keine einzige** ArvioAddon-Debug-Quelle erschien, l–ď“ď–í”®uft unser Code **nie** –ď—ě–í“∂–í‚Äô ARVIO instanziiert unsere Plugin-Klasse nicht (oder verwirft sie still). Das ist exakt die Fehlerklasse, die **nur im Logcat** sichtbar wird ("No API loaded for scraper", "MISSING CLASS", "plugin.load() linkage error", "No @CloudstreamPlugin class found").

### –ď—ě–í“° –ď“ú–í—Ď–í“ł LIMITS EINES DIAGNOSE-PLUGINS (Antwort auf die Frage "k–ď“ď–í¬∂nnen wir das Log –ď“ď–í—ėber ein Plugin bekommen?")
**Teilweise ja, aber nicht f–ď“ď–í—ėr das aktuelle Problem.** Ein Plugin kann sich selbst protokollieren und das sogar in ARVIO als Quellen sichtbar machen (gebaut in v6–ď—ě–í“Ė–í‚Äúv8). **Aber** das funktioniert nur, **sobald ARVIO den Plugin-Code l–ď“ď–í”®dt und aufruft**. Genau da hakt es: ARVIO l–ď“ď–í”®dt/instanziiert die `.cs3`-Klasse auf dem Ger–ď“ď–í”®t nicht. F–ď“ď–í—ėr "l–ď“ď–í”®dt ARVIO mein Plugin –ď“ď–í—ėberhaupt?" gibt es **kein plugin-basiertes Werkzeug** –ď—ě–í“Ė–í‚Äú daf–ď“ď–í—ėr braucht man ARVIOs eigene Logs (Logcat). Datei-/MediaStore-/HTTP-Server-Ans–ď“ď–í”®tze (v3–ď—ě–í“Ė–í‚Äúv5) scheiterten ebenfalls, weil unser Code nie l–ď“ď–í”®uft (keine Datei wird erzeugt).

### N–ď“ď–í‚ÄěCHSTER SCHRITT (Prio 1, VORAB gemacht mit Nutzer abgesprochen): MIT LAPTOP / PC WEITERMACHEN
Nutzer kommt n–ď“ď–í”®chste Session **mit Laptop**. Dann ist **Logcat via USB+adb** m–ď“ď–í¬∂glich (die einzig zuverl–ď“ď–í”®ssige Methode; LADB-App auf dem Ger–ď“ď–í”®t scheiterte am Pairing). Konkrete Schritte f–ď“ď–í—ėr die n–ď“ď–í”®chste Session:
1. Laptop: Android platform-tools (Mini-SDK, ~10 MB, keine Installation) von https://developer.android.com/tools/releases/platform-tools laden, entpacken.
2. Ger–ď“ď–í”®t per USB an den Laptop, im Ger–ď“ď–í”®t "USB-Debugging erlauben" best–ď“ď–í”®tigen.
3. Im platform-tools-Ordner Terminal –ď“ď–í¬∂ffnen (Adressleiste `cmd` + Enter).
4. `adb logcat -c` (Buffer leeren).
5. In ARVIO: Filmpalast aus/an + Quellensuche ausl–ď“ď–í¬∂sen (z.B. Matrix). 15 s warten.
6. `adb logcat -d | grep -iE "ExtExt|ExternalExtension|PluginManager|Filmpalast|ArvioAddon|No API loaded|MISSING CLASS|CloudstreamPlugin|linkage error"` –ď—ě–í“∂–í‚Äô Output kopieren.
7. **Was gesucht wird (entscheidend):**
   - `No API loaded for scraper: <id>` –ď—ě–í“∂–í‚Äô ARVIO konnte keine MainAPI instanziieren (Klassen-Fehler).
   - `No @CloudstreamPlugin class found in <id>` –ď—ě–í“∂–í‚Äô unsere Plugin-Klasse wurde nicht gefunden.
   - `plugin.load() linkage error` / `MISSING CLASS: ...` –ď—ě–í“∂–í‚Äô eine Referenz l–ď“ď–í”®sst sich zur Laufzeit nicht aufl–ď“ď–í¬∂sen.
   - `TmdbProvider Filmpalast: both load() paths failed` / `0 links collected` –ď—ě–í“∂–í‚Äô Scraper l–ď“ď–í”®uft, aber load/loadLinks scheitert.
   - –ď“ď–í“£berhaupt kein `Filmpalast`/`ExtExt`-Eintrag –ď—ě–í“∂–í‚Äô Scraper wird –ď“ď–í—ėberhaupt nicht aufgerufen (Enable-/Routing-Problem).
- Je nach Befund: load()-Fehler –ď—ě–í“∂–í‚Äô Jsoup-Selektoren/Logging fixen; Scraper nicht aufgerufen –ď—ě–í“∂–í‚Äô Download/DexClassLoader/manifestEnabled pr–ď“ď–í—ėfen.

### N–ď“ď–í‚ÄěCHSTER SCHRITT (Prio 2): GitHub-Issue bei ARVIO –ď“ď–í¬∂ffnen (parallel zu Prio 1)
Da der GermanProviders-Test beweist, dass es ein ARVIO-seitiges Problem mit dem `.cs3`-Pfad ist (nicht unseres), lohnt ein Issue bei den sehr aktiven ARVIO-Devs. **Noch NICHT ge–ď“ď–í¬∂ffnet** –ď—ě–í“Ė–í‚Äú in der n–ď“ď–í”®chsten Session entscheiden, ob nach dem Logcat-Befund. Betreff/Inhalt-Skizze: ".cs3/Cloudstream3 plugins install and appear in list, but return no sources on sideload (GermanProviders AND custom TmdbProvider both empty; Stremio addons work)". Verweis auf #459/#273. **AI-Disclosure-Pflicht:** Falls Issue/MR-Kommentar erstellt wird, Hinweis "created by an AI agent (OpenHands) on behalf of [user]" einf–ď“ď–í—ėgen.
- Vor dem Issue ben–ď“ď–í¬∂tigte Infos vom Nutzer: genaue ARVIO-Version (1.9.983?), sideload best–ď“ď–í”®tigt, Ger–ď“ď–í”®t/Android-Version.

### ENTSCHEIDUNG NUTZER (14.08.2026): GitHub-Issue bei ARVIO professionell vorbereiten
Nutzer m√É¬∂chte das GitHub-Issue bei ARVIO **professionell** einreichen (Vorbild: ARVIO Issue #537), ggf. sogar mit eigenem Fix-PR. Bis zur n√É¬§chsten Session sollen **alle daf√É¬ľr n√É¬∂tigen Informationen gesammelt und hier gespeichert** werden, damit eine andere Session das Issue ausarbeiten kann. **Status der Issue-Er√É¬∂ffnung: NOCH NICHT √É¬∂ffnen** √Ę¬Ä¬ď erst nach Logcat-Befund (Prio 1). Diese Sektion ist die Checkliste f√É¬ľr die Vorbereitung.

#### Was bereits verifiziert/recherchiert ist (Stand 14.08.2026)
- **ARVIO-Repo:** `ProdigyV21/ARVIO` √Ę¬Ä¬ď Apache-2.0, 634 Stars, 98 Forks, sehr aktiv (18 Releases in 5 Monaten, letzte Commits 14.08.2026). Latest release `v1.9.983` (30.07.2026). `hasIssuesEnabled=true`, `hasDiscussionsEnabled=false` (√Ę¬Ü¬í nur Issues, keine Discussions).
- **Maintainer:** `ProdigyV21` (Hauptmaintainer). **`Himanth-reddy`** = hochaktiver Mitwirkender, dessen PRs fast t√É¬§glich gemerged werden (#563, #561, #560, #558, #553, #552). Er hat auch den mobilen Plugin-UI-Fix (#466/v1.9.983) beigesteuert.
- **Externe PRs werden gemerged** (nicht nur closed) √Ę¬Ä¬ď ARVIO ist offen f√É¬ľr saubere Contributions.
- **Kein CONTRIBUTING.md, keine Issue-Templates, keine PR-Templates** im Repo (obwohl GSSoC-Teilnehmer Issues daf√É¬ľr √É¬∂ffneten: #444/#477/#482 √Ę¬Ä¬ď closed, Status unklar). **√Ę¬Ü¬í keine formale Contribution-Policy, die uns blockiert.**
- **"KIS" = Nutzer meinte "andere KIs, die Nutzern beim Issue/PR-Schreiben geholfen haben"** (NICHT GSSoC). Recherche: In den letzten ~25 gemergten PRs und ~80 Issues fand sich **keine explizite AI-Disclosure** externer Nutzer (`ai agent`/`copilot`/`gpt`/`claude`/`generated by`/`on behalf of` √Ę¬Ü¬í 0 Treffer). Es gibt also **keine sichtbaren Vorbilder** im ARVIO-Repo f√É¬ľr "KI hilft Nutzer beim Issue/PR" √Ę¬Ü¬í die meisten externen Beitr√É¬§ge wirken handgeschrieben (z.T. oberfl√É¬§chlich, v.a. GSSoC-Teilnehmer wie `prince-pokharna`/`aayan-rashid`). **Fazit f√É¬ľr uns:** Wir d√É¬ľrfen als erste ein AI-unterst√É¬ľtztes Issue dort einreichen, aber das macht eine saubere, dezente AI-Disclosure umso wichtiger √Ę¬Ü¬í kein Vorbild vorhanden, auf das wir verweisen k√É¬∂nnen.
- **GSSoC** (GirlScript Summer of Code): ARVIO hat Label `gssoc:approved`; Teilnehmer √É¬∂ffnen viele `[Feature Request]`-Issues. Irrelevant f√É¬ľr unsere Frage (s.o.), nur Kontext.
- **README-Repo-Zweck** (verifiziert): explizit *"Issue investigation and technical discussion"* + *"Contribution review"* √Ę¬Ü¬í die Devs **wollen** gut recherchierte technische Issues.
- **README "AI Disclosure"-Sektion (verifiziert, entscheidend):** *"This application was developed with significant AI assistance. Contributions should still be reviewed, tested, and treated as normal source code changes. If you have concerns about using AI-generated software, please do not use this application."* √Ę¬Ü¬í **ARVIO selbst ist massiv AI-gest√É¬ľtzt entwickelt.** Die Maintainer haben also **prinzipiell nichts gegen AI**; erwarten aber, dass AI-Beitr√É¬§ge wie normaler Code reviewt/getestet werden. Das ist die **st√É¬§rkste Best√É¬§tigung**, dass ein AI-unterst√É¬ľtztes Issue+PR bei ARVIO willkommen ist, solange es qualitativ sauber ist. **Unsere AI-Disclosure-Pflicht bleibt trotzdem bestehen** (gem√É¬§√É¬ü OpenHands-Regel f√É¬ľr externe Services).
- **Label-System** (f√É¬ľr Issue): `bug`/`type:bug`, `area: android`, ggf. `Next Update`. Maintainer setzt Labels i.d.R. selbst.

#### Vorbild-Issue f√É¬ľr unseren Stil: ARVIO #537 (erfolgreich, schnell geschlossen)
"Pastebin dependency causes ~14s timeout for users in Turkey" √Ę¬Ä¬ď Aufbau: konkrete Code-Referenz (`MediaRepository`/`STREAMING_COLLECTION_ADDON_URL`) + Root-Cause (Pastebin in T√É¬ľrkei blockiert) + L√É¬∂sungsalternativen ("Would it be possible to replace with a project-controlled endpoint / GitHub raw / GitHub Pages?") + Angebot weiterer Beweise (network capture). **Genau dieser Stil ist bei ARVIO erfolgreich.**

#### Bekannte ARVIO-Issues mit identischem Symptom (Verweis im Issue n√É¬∂tig)
- **#459** "Nuvio JS scraper repository installs but returns no sources" (closed, ohne √É¬∂ffentliche L√É¬∂sung) √Ę¬Ä¬ď hatten Reproduktion, aber **kein Logcat** √Ę¬Ü¬í vermutlich deshalb sang- und klanglos geschlossen. **Genau diese Falle d√É¬ľrfen wir nicht tappen.**
- **#273** "I'm able to add nuvio plugin but not showing any video links" (closed; Dev @Himanth-reddy: "it should be working").
- **#500** "unable to install the plugin" (open).
- **#491** "plugins & extensions section shows addons not plugins" (closed √Ę¬Ü¬í "next update").

#### Voraussetzungen, damit das Issue geh√É¬∂rt wird (Checkliste √Ę¬Ä¬ď vor √É¬Ėffnen abhaken)
- [ ] **Logcat-Beweis** (Prio 1, entscheidend). Ohne Logcat l√É¬§uft das Issue Gefahr, wie #459 geschlossen zu werden. Logcat-Filter: `ExtExt|ExternalExtension|PluginManager|Filmpalast|No API loaded|MISSING CLASS|CloudstreamPlugin|linkage error`.
- [ ] Genaue ARVIO-Version (1.9.983?) + sideload best√É¬§tigt.
- [ ] Ger√É¬§t-Modell + Android-Version.
- [ ] Reproduzierbare Schritte (Repo-URL installieren √Ę¬Ü¬í Filmpalast suchen, z.B. Matrix √Ę¬Ü¬í 0 Quellen).
- [ ] Beweis "ARVIO-seitig": GermanProviders (Bnyro, woanders funktionierend) liefert auf dem Ger√É¬§t ebenfalls 0 Quellen.
- [ ] Root-Cause-Vermutung mit Code-Verweis (z.B. `hasStreamingAddons` z√É¬§hlt nur Stremio-Addons; `StreamRepository.getStreamAddons` filtert `runtimeKind != STREMIO`).
- [ ] L√É¬∂sungsvorschlag ("Would it be possible to...").
- [ ] AI-Disclosure: "created by an AI agent (OpenHands) on behalf of [user]".

#### Issue-Struktur-Vorschlag (nach Vorbild #537)
1. **Environment:** ARVIO v1.9.983 sideload, Ger√É¬§t, Android-Version.
2. **Summary:** `.cs3`-Plugins installieren, erscheinen aktiviert in der Liste, liefern aber 0 Quellen; Stremio-Addons funktionieren (anderer Code-Pfad).
3. **Steps to reproduce:** Repo installieren (unsere + GermanProviders) √Ę¬Ü¬í Suche Matrix/Silo √Ę¬Ü¬í 0 Quellen.
4. **Expected vs. Actual:** Cloudstream3-Scraper sollten Streams liefern wie in Cloudstream3-App/NuvioTV.
5. **Root cause (vermutet):** je nach Logcat-Befund √Ę¬Ä¬ď (a) Scraper wird gar nicht instanziiert (`No API loaded`/`linkage error`) ODER (b) Logikl√É¬ľcke `hasStreamingAddons` ignoriert EXTERNAL_DEX-Scraper (verifiziert: `getStreamAddons` filtert `runtimeKind != STREMIO`; `DetailsViewModel` berechnet `hasStreamingAddons` nur aus Stremio-Addons).
6. **Proposed fix:** je nach Befund √Ę¬Ä¬ď (a) Logcat-Einbettung/Loader-Diagnose ODER (b) `getStreamAddons`/`hasStreamingAddons` sollten EXTERNAL_DEX-Scraper z√É¬§hlen.
7. **References:** #459, #273, #500.
8. **Logcat-Auszug** (gek√É¬ľrzt).
9. **AI-Disclosure.**

#### Ablauf: Fork + eigener Fix-PR (professionellster Weg)
Der professionellste Weg (so machen es `Himanth-reddy`/GSSoC-Teilnehmer, deren PRs gemerged werden):
1. **Phase 1 √Ę¬Ä¬ď Beweise sichern:** Logcat via Laptop+USB+adb (siehe Prio 1).
2. **Phase 2 √Ę¬Ä¬ď Issue er√É¬∂ffnen:** EIN fokussiertes Issue, Stil wie #537, mit Logcat-Beweis + Root-Cause + L√É¬∂sungsvorschlag. **Nicht vor Phase 1 √É¬∂ffnen.**
3. **Phase 3 √Ę¬Ä¬ď Fork & PR (optional, aber wirkungsvoll):** `ProdigyV21/ARVIO` forken, lokal bauen (README "Build And Run": JDK 17+, Android SDK 35), Fix testen, PR gegen Original. Issue+PR = h√É¬∂chste Erfolgsquote, weil der Maintainer etwas Greifbares zum Mergen hat.
   - **Realistische Fix-Kandidaten je Logcat-Befund:**
     - (a) `hasStreamingAddons`-Logikl√É¬ľcke (irref√É¬ľhrende "kein Addon"-Meldung): in `DetailsViewModel`/`StreamRepository.getStreamAddons` auch EXTERNAL_DEX-Scraper z√É¬§hlen √Ę¬Ü¬í **kleiner, sauberer PR, gut mergebar.**
     - (b) Scraper wird gar nicht geladen (`No API loaded`/`linkage error`): tiefer in `ExternalExtensionLoader.loadExtension` √Ę¬Ü¬í komplizierter, ARVIO-intern. Da eher **Issue ohne PR**, weil der Fix tief in der Engine liegt.

#### Was in dieser/n√É¬§chster Session zu sammeln/speichern ist
- Logcat-Auszug (gek√É¬ľrzt, anonymisiert) √Ę¬Ü¬í hier als Code-Block oder verlinkt ablegen.
- Best√É¬§tigte ARVIO-Version + sideload + Ger√É¬§t/Android.
- Falls Fork gebaut: Branch-Name, gefixte Dateien, Test-Ergebnis.
- Issue-URL nach Er√É¬∂ffnung.
- PR-URL nach Er√É¬∂ffnung.

### Plugin-Versionen Uebersicht (alle auf `builds`, status=1)
- **v2** (Hash 647c...): DebugServer 127.0.0.1:8420 + Datei-Trace.
- **v3**: DebugServer auf 127.0.0.1 gebunden (statt Wildcard).
- **v4** (2248...): File-based trace + PLUGIN_LOADED.txt Marker in Android/data.
- **v5** (9673...): MediaStore API schreibt in public Download/arvio-addon-logs/ (Fix: `MediaStore.Files.getContentUri` statt `Downloads.EXTERNAL_URI`).
- **v6**: Diagnose als Pseudo-Quellen in ARVIOs Quellenauswahl (`emitTraceAsSources`); `loadLinks` Return-Type-Fix (Boolean in v4.7.0).
- **v7**: `load()` gibt **nie null** zur–ď“ď–í—ėck (debug MovieLoadResponse dataUrl="ARVIO_DEBUG") –ď—ě–í“∂–í‚Äô `loadLinks` wird garantiert aufgerufen.
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

**xStream (michaz) DDoS-Guard-Bypass gefunden (requestHandler.py:255-275):** Bei 403+DDoS-Guard -> lade `https://check.ddos-guard.net/check.js` -> extrahiere Image-URL (`Image.*?'([^']+)'; new`) -> lade sie auf dem Target-Host (setzt `__ddg2_`-Cookie) -> retry Original-Request. **ABER live getestet: dieser OLD bypass setzt zwar `__ddg2_`, reicht aber fuer Serienstreams `/r?`-Endpunkte NICHT mehr** — diese nutzen mittlerweile eine neuere js-Challenge (`view.js` + `index.js`), die echtes JS-Ausfuehren erfordert. xStream's Bypass war fuer die aeltere Challenge; aktuelle Serienstream-`/r?` bleibt 403.

**resolveurl (Gujal00/ResolveURL 5.1.206, aktuellste Version) loest DDoS-Guard NICHT.** net.py wirft nur `ResolverError('Cloudflare challenge')` bei Cloudflare, kein DDoS-Guard-Solver vorhanden. resolveurl resolved Hoster-URLs direkt (voe.sx/e/xxx, dood.so/e/xxx) — JEMAND muss das `/r?`-Redirect VORHER aufloesen. Doodstream/FileMoon/VidHide sind direkt erreichbar (HTTP 200, kein DDoS-Guard); nur der Serienstream-Redirect ist das Hindernis.

**Fix #19 / Option A (IMPLEMENTIERT, v31):** Serienstream-Modul als TmdbProvider (wie Filmpalast), mit java.net-HTTP + CookieJar + xStream-DDoS-Guard-Bypass-Versuch (check.js Image-Trick) in httpGet. Falls Bypass scheitert (neuere js-Challenge), resolveHost faellt auf genericResolve zurueck. **TV-Test entscheidet**: der TV (andere IP/Wohn-IP vs Rechenzentrum) koennte durchkommen, wo Laptop blockt. Bei VOE bekam der TV immerhin eine Challenge-Seite (nur keine echte Embed-Seite) — bei `/r?` koennte es anders laufen.

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
- k7/a ist ein 3-Wert-Enum (super=Enum, fields i/l/m, valueOf/values) — KEIN HttpResponse/Coroutine-Typ.
- app.get ist eine ARVIO-provided suspend Extension. Aus unserem externen .cs3-Plugin aufgerufen, resume-t die coroutine-Machinery nicht korrekt -> gibt stray Enum statt NiceResponse zurueck.
- Das ist dasselbe grundsaetzliche Problem wie Erkenntnis #7 (R8 obfuscated kotlin.coroutines.Continuation -> j7/d): unsere Plugin-suspend-Aufrufe an ARVIOs suspend-Funktionen (app.get) sind gestoert, weil die Coroutine-Machinery zwischen externem Plugin und obfuscated ARVIO-Runtime nicht zusammenpasst.
- okhttp3 hat ~100 obfuscated Klassen -> Whack-a-Mole endlos -> Strategiewechsel noetig (Fix #13).

### ENTSCHEIDUNG / FIX #13 (15.08.2026, v21): HTTP komplett auf java.net + jsoup umgestellt (app.get entfernt)
Statt jede obfuscated okhttp/coroutine-Type einzeln zu jagen: ALLE unsere HTTP-Aufrufe von ARVIOs suspend `app.get` (NiceHttp/okhttp) auf plain java.net.HttpURLConnection umgestellt.
- `httpGet(url, params, headers): HttpResp(code, text)` Helper: java.net.HttpURLConnection (JDK, NIE obfuscated), connectTimeout/readTimeout = NET_TIMEOUT_MS, instanceFollowRedirects, UTF-8 body. Non-suspend.
- jsoup.parse(text) statt res.document — jsoup von ARVIO unobfuscated kept (330 Klassen verifiziert), Jsoup.parse(String) verfuegbar.
- Interne Helfer non-suspend gemacht: fetchTmdbMeta, searchFilmpalast, genericResolve (nutzen httpGet). AUSNAHME suspend geblieben: buildMovieResponse + buildSeriesResponse (rufen newMovieLoadResponse/newTvSeriesLoadResponse auf, die selbst suspend cloudstream3-API sind — ARVIO->ARVIO intern, funktionieren wie ARVIOs eigene Scraper).
- loadExtractor (suspend, ARVIO-provided) bleibt in loadLinks — laeuft ARVIO->ARVIO intern.
- withTimeoutOrNull entfernt (java.net timeouts statt Coroutinen-Timeout).
- Konsequenz: load()/loadLinks()/search() bleiben suspend (cloudstream3 API-Vertrag, DEX-patched j7/d) aber haben keine eigenen inneren app.get-suspend-Aufrufe mehr -> coroutine state machine trivial -> obfuscated-Type-Breakage umgangen. build*Response+loadExtractor bleiben suspend-Aufrufe an ARVIO (wie ARVIOs eigene Scraper, sollten funktionieren).
- v21 auf builds (status=1). CI gruen. AUF TV/HANDY-TEST AUSSTEHEND.
- Verifiziert im Build: plugin-classes patched 66 Utf8 (weniger als v20's 120, weil app.get/okhttp-Referenzen entfernt), stdlib patched 7740.

### ARVIO v1.9.994 (15.08.2026 veroeffentlicht) — NUTZER HAT UPGEDATET (TV + HANDY)
- VERIFIZIERT: Obfuskations-Map UNVERAENDERT (j7/d=Continuation, rb/c0=okhttp3.Interceptor) -> unsere DEX-Patches kompatibel.
- NUTZER HAT auf v1.9.994 geupdatet (TV + Handy). WICHTIG: unsere Patches/Analyse basieren auf 1.9.983-DEX, aber da Obfuskation identisch, gilt alles weiter.
- Neue nuetzliche Features fuer uns: "Refresh Add-ons" (#511) — ABER Nutzer korrigiert: das ist fuer Stremio-Add-ons, NICHT fuer .cs3-Plugins -> fuer Plugin-Update weiterhin Repo loeschen+neu hinzufuegen.
- "Fixed release dependency injection for sideload builds" (#525).
- Release-Notes erwaehnen NICHT den Cloudstream3-.cs3-Plugin-Obfuskations-Bug -> Kernproblem von ARVIO nicht geloest.
- Touch-Bug im Add-Repo-Dialog auf Handy: NUTZER BESTAETIGT BEHOBEN in 1.9.994 ("ich kann jetzt Plugin-Repo eintragen und bestaetigen, das ist mega"). -> Testen kuenftig auch auf dem Handy moeglich (UI geht jetzt).

### LOGCAT AM HANDY (neu ab 15.08.2026) — Setup dokumentiert in docs/handy-logcat-ladb-termux.md
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

**Methode D: resolveurl Python-Resolver (BESTE METHODE, Goldschatz!) ⭐⭐⭐**
- `Gujal00/ResolveURL` (GitHub) = Fork von tknorris UrlResolver, von Kodi-Community gepflegt, **227 fertige Hoster-Resolver in lesbarem Python**!
- Jeder Resolver ist eine ~50-80 Zeilen Python-Datei in `lib/resolveurl/plugins/<hoster>.py` mit der KOMPLETTEN Extraktionslogik: Regex, API-Endpoints, Decrypt-Methoden, Header-Requirements.
- Verfuegbare Resolver fuer UNSERE Hoster (live verifiziert 15.08.2026): `voesx.py`, `vidsonic.py`, `supervideo.py`, `firestream.py`, `doodstream.py`, `streamtape.py` + 221 weitere.
- xStream (michaz) nutzt genau diese resolveurl-Library: `import resolveurl as resolver; resolver.resolve(url)` (verifiziert in xstreamscraper/scraper.py:259 + seizu/plugin.video.filmpalast.ex/default.py:343).
- Pro: LESBARER Python-Code (kein Bytecode-Decompiling noetig!), alle Decrypt-Methoden sehen als klare Python-Funktionen, alle Regexes direkt lesbar. **Die komplette Hoster-Extraktionslogik existiert schon fertig** — wir muessen sie nur von Python nach Kotlin portieren.
- Con: Python -> Kotlin Portierung noetig (aber straightforward: requests->java.net, re->kotlin.Regex, json->org.json).
- Best for: ALLE Hoster. Das ist die PRIMAER-Quelle fuer Hoster-Extraktionslogik.

**Methode B: Built-in cloudstream3-Extractoren dekompilieren** (Zweitbeste, Fallback) ⭐
- cloudstream3.jar hat Extractoren fuer Voe, Supervideo, VidHidePro, Firestream, FileMoon — aber Bytecode (javap), schwerer zu lesen als Python.
- Nuetzlich als Kreuzcheck falls resolveurl veraltet ist, aber resolveurl ist aktueller (227 Plugins, 2026 gepflegt).

**Methode C: Direkt curl vom Server** (zum Testen)
- Schnelle Iteration, kein Geraet noetig. Bei Bot-Schutz nur Challenge-Seite.

**Methode A: Logcat Debug-Logging** (Fallback bei Bot-Schutz)
- Nur noetig wenn curl keine echte Seite liefert (DDoS-Guard/Cloudflare). Geraet bekommt echte Seite (118949B bei VOE).

**EMPFOHLENER WORKFLOW PRO HOSTER (aktualisiert):**
1. `cat /tmp/resolveurl/script.module.resolveurl/lib/resolveurl/plugins/<hoster>.py` -> Algorithmus in lesbarem Python lesen. **Das ist Schritt 1 — alles steht da.**
2. Python-Logik nach Kotlin portieren (java.net + kotlin.Regex + org.json).
3. Mit `curl` vom Server testen (wenn kein Bot-Schutz: funktioniert sofort).
4. Falls Bot-Schutz: Debug-Logging + Geraet-Test.

### RESOLVEURL-REPOS (geklont nach /tmp, 15.08.2026)

**AKTUALITAETS-CHECK durchgefuehrt (15.08.2026) — Gujal00/ResolveURL ist die aktuellste Quelle:**

| Repo | Letzter Commit | Status |
|---|---|---|
| **Gujal00/ResolveURL** | 12.08.2026 (3 Tage her) | ⭐ AKTUELL — nutze diese |
| jsergio123/script.module.resolveurl | 21.02.2020 (6 Jahre alt) | VERALTET — nicht nutzen |
| Gujal00/smrzips | Release-Repo (zips only, v5.1.206) | Nur Zips, kein Quellcode |

- **Gujal00/ResolveURL** = `https://github.com/Gujal00/ResolveURL` (Hauptquelle, 227 Plugins, geklont nach `/tmp/resolveurl`). Pfad: `script.module.resolveurl/lib/resolveurl/plugins/<hoster>.py`. Version 5.1.206. Letzter Commit 12.08.2026 ("voesx-prefer-hls-settings"). Sehr aktiv gepflegt (commits fast woechentlich).
- `jsergio123/script.module.resolveurl` = VERALTET (letzter Commit 2020, 6 Jahre alt). Nicht nutzen.
- `Gujal00/smrzips` = Release-Repo (nur .zip-Dateien, kein Quellcode). Version 5.1.206 als Zip. Der Quellcode ist im ResolveURL-Repo.
- **michaz1988/michaz1988.github.io** = `https://github.com/michaz1988/michaz1988.github.io` (michaz Repo, geklont nach `/tmp/michaz-repo`). Enthaelt `script.module.xstreamscraper` (Filmpalast-Scraper, geklont nach `/tmp/xstreamscraper`) + `plugin.video.xship` (xStream-Nachfolger) + `repository.gujal` (verweist auf Gujal00/smrzips fuer resolveurl). michaz hostet KEIN eigenes resolveurl — er nutzt Gujal00's resolveurl ueber das repository.gujal Dependency.
- **seizu/plugin.video.filmpalast.ex** = `https://github.com/seizu/plugin.video.filmpalast.ex` (Filmpalast Kodi-Plugin, geklont nach `/tmp/seizu-filmpalast`). Nutzt resolveurl fuer Hoster-Aufloesung.
- **Fazit:** Gujal00/ResolveURL ist EINZIGE aktuell gepflegte resolveurl-Quelle. xStream/michaz nutzt genau diese. Wir nutzen sie auch. Keine Alternative noetig.
- WICHTIG: Diese Repos sind nach /tmp geklont (nicht persistent ueber Resets). Bei naechster Session ggf. neu klonen: `git clone --depth 1 https://github.com/Gujal00/ResolveURL.git /tmp/resolveurl`

### VOE-EXTRACTOR: KOMPLETTE LOGIK (aus resolveurl voesx.py, lesbar!)

VOE-Extraktionsalgorithmus (aus `voesx.py` `get_media_url()` + `voe_decode()`):
1. GET `https://voe.sx/e/<media_id>` mit `User-Agent: <random>` (mobile UA OK).
2. Redirect-Loop: solange `'const currentUrl' in html`: Regex `window\.location\.href\s*=\s*'([^']+)'` -> neuer GET auf redirect-URL.
3. Pattern 1 (primaer): `json">\["([^"]+)"]</script>\s*<script\s*src="([^"]+)` -> extrahiert (a) encoded string + (b) JS-Datei-URL.
4. GET JS-Datei-URL -> `re.search(r"(\[(?:'\W{2}'[,\]]){1,9})", html2)` -> LUT (lookup table) fuer Decode.
5. **`voe_decode(ct, luts)`** — die Decrypt-Methode (Python, lesbar!):
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

**VOE-Domain-Liste (voesx.py):** 200+ Mirror-Domains! `voe.sx`, `voe-unblock.com`, `donaldlineelse.com`, `kinoger.ru`, `smoki.cc`, `ogladaj.me` usw. Wichtig fuer resolveHost-Dispatch — nicht nur `voe.sx` checken, sondern alle Mirrors.

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
| odysseusa.cc | Nein (eigene API) | Nein | Ja (POST /api/stream) | ERLEDIGT v24 | ✅ Done |
| **voe.sx** | Ja (voesx.py) | Ja (DDoS-Guard) | Ja (voe_decode, ROT+Base64+Caesar) | Mittel (Decrypt portieren) | **Prio 1** ⭐ |
| **vidsonic.net** | Ja (vidsonic.py) | Nein | Ja (hex+reverse, TRIVIAL!) | **Niedrig** (3 Zeilen!) | **Prio 2** ⭐ |
| firestream.to | Ja (firestream.py) | ? | Ja (token-blob POST API) | Niedrig-Mittel | Prio 3 |
| supervideo.cc | Ja (supervideo.py) | ? | Ja (packed JS + file regex) | Mittel | Prio 3 |
| vidhide.com | ? (vidhd.py?) | ? | ? (dekompilieren) | Mittel | Prio 3 |
| filemoon.sx | ? (filemoon?) | ? | ? (dekompilieren) | Mittel | Prio 3 |
| flyfile.app | Nein | Ja (Cloudflare) | Nein | Hoch | Niedrig |

**VIDSONIC ist jetzt Prio 2** weil der resolveurl-Code zeigt: es ist TRIVIAL (hex-decode + reverse = direkte URL, 3 Zeilen Kotlin)! Voher als "schwer obfusziertes JS" bewertet — aber resolveurl hat schon die Loesung.

### NAECHSTE SESSION: ERST QUALITAET + SPEED, DANN NEUE HOSTER (Nutzer-Entscheidung 15.08.2026)

**Nutzer hat entschieden: Erst alles richtig einstellen (Qualitaet + Speed), BEVOR neue Hoster hinzugefuegt werden.**

Reihenfolge (Prio 1-3 vor neuen Hostern):

#### PRIO 1: Qualitaetserkennung — echte Aufloesung senden statt Unknown (notwendig fuer Auto-Play)

**Problem:** Aktuell senden wir `Qualities.Unknown.value` (400) → ARVIO zeigt "Unknown" → Auto-Play-Score = 0 → ARVIOs Auto-Play zaudert (2s Timeout, kein Score-bewerteter Stream). Nutzer will echte Qualitaet sehen (1080p, 720p, 4K) UND Auto-Play soll funktionieren.

**ARVIO Auto-Play-Logik (verifiziert in AutoPlaySourcePlanner.kt, v1.9.994):**
- `qualityScoreForAutoPlay(stream)`: prueft stream.quality-String auf "2160p"/"4K"→4, "1080p"→3, "720p"→2, "480p"→1, sonst 0.
- `bestAutoPlayStream`: filtert streams mit score >= minQualityThreshold (default "Any"→0), sortiert absteigend.
- `AUTOPLAY_MAX_WAIT_MS = 2000` (2s Timeout). Wenn kein Score-bewerteter Stream in 2s: Auto-Play bricht ab → Quellenauswahl.
- Unsere `Qualities.Unknown` (400) → `getStringByInt(400)` = "" → `ifEmpty{null}` → `toStreamSource`: quality="Unknown" → score=0. Auto-Play ignoriert uns effektiv.

**Qualities-Enum-Werte (verifiziert aus cloudstream3.jar Bytecode):**
```
Unknown = 400, P144 = 144, P240 = 240, P360 = 360, P480 = 480,
P720 = 720, P1080 = 1080, P1440 = 1440, P2160 = 2160
```
`getStringByInt(value)`: 0→"Auto", 400→""(empty→null→"Unknown"), 2160→"4K", sonst→"<value>p" (z.B. 1080→"1080p").

**Implementierung (~50 Zeilen):**
1. `emitLink` erweitern: vor dem Emit bei m3u8-URLs das HLS-Manifest fetchen (httpGet, ~1-2 KB).
2. Aus dem Manifest `#EXT-X-STREAM-INF:BANDWIDTH=...,RESOLUTION=1920x1080` parsen.
3. Auf Qualities-Wert mappen: height >= 2160 → P2160 (4K), >= 1080 → P1080, >= 720 → P720, >= 480 → P480, sonst Unknown.
4. Bei mp4/unknown: Default P720 (besser als Unknown — mp4 ist meist DVD/HD-Qualitaet).
5. `emitLink` bekommt quality-Parameter, nutzt ihn im ExtractorLink statt `Qualities.Unknown.value`.
6. ARVIO zeigt dann "1080p" / "720p" → Auto-Play-Score 3/2 → Auto-Play erkennt Quelle → spielt direkt ab.

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
- In `fetchTmdbMeta`: erst `tmdbCache[tmdbId]` checken, falls vorhanden → return (0ms statt 300ms).
- Bei Treffer: `tmdbCache[tmdbId] = meta`.
- ConcurrentHashMap = JDK, nie obfuscated, thread-safe.
- Ersparnis: ~300ms bei wiederholter Suche (selber Film). Erste Suche = Cache-Miss = keine Ersparnis.
- Nachteile: keine echte. Memory: vernachlaessigbar (einige Eintraege).

#### PRIO 3: Hoster parallel auflösen (~30 Zeilen, bringt unter 2s Auto-Play-Timeout)
- Aktuell: `for (link in links) { resolveHost(fixed, callback) }` — SEQUENTIELL. 3 Hoster = ~900ms+350ms load() = ~1.6s + 100ms quality = ~2.6s.
- Neu: `java.util.concurrent.Executors.newFixedThreadPool(3)`, jeder `resolveHost` als Callable, mit `invokeAll(2s timeout)` sammeln.
- Alle Hoster parallel → statt 900ms nur ~350ms (laengster einzelner).
- Ersparnis: ~500ms bei 3 Hostern.
- Nachteile: Thread-Pool-Komplexitaet (mit `finally { pool.shutdown() }` lösbar). Thread-Sicherheit: callback ist nur eine Funktion (`(ExtractorLink) -> Unit`), sollte thread-safe sein (ARVIO sammelt in einer Thread-safe Collection). java.util.concurrent = JDK, nie obfuscated.
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
          → unter 2s Auto-Play-Timeout → Auto-Play funktioniert!
```

#### PRIO 4 (danach): Neue Hoster (vidsonic, VOE, firestream)
- Siehe unten "HOSTER-PRIORITAETEN"-Tabelle. Reihenfolge: vidsonic (trivial!) → VOE (Prio 1 fuer Haeufigkeit) → firestream.
- Jeder Hoster: resolveurl Python lesen (Methode D) → Kotlin portieren → curl testen.

**Prio 4 - GitHub-Issue bei ARVIO (noch NICHT eroeffnen):**
Siehe unten "Entscheidung Nutzer: GitHub-Issue bei ARVIO professionell vorbereiten". Drei klare Bugs:
1. R8 obfuscated kotlin.coroutines.Continuation + okhttp3 + stript kotlin-reflect + stript DefaultConstructorMarker-ctors -> externe .cs3-Plugins koennen suspend-Overrides, app.get, loadExtractor, Jackson-JSON UND Default-Arg-Konstruktoren nicht nutzen (Haupt-Bug, Erkenntnis #7+#13+#14+#15+#16+#18). Trotzdem haben wir es durch massive Workarounds zum Laufen gebracht (DEX-Patching, java.net-HTTP, org.json, primary-ctor, eigene Extractoren) -> beweist den Bug eindrucksvoll.
2. Cloud-Sync-Restore laedt .cs3-Dateien nicht herunter (Erkenntnis #1).
3. (ehemals Touch-Bug Add-Repo-Dialog - BEHOBEN in 1.9.994, Nutzer bestaetigt).
AI-Disclosure-Pflicht bei Issue/Kommentar: "created by an AI agent (OpenHands) on behalf of [user]".

### EMPFOHLENE SCHRITTWEISE STRATEGIE (Stand 16.08.2026, nach Serienstream-Modul v31)

**VORBEMERKUNG — Prio 1-3 sind bereits erledigt:** Bei Durchsicht des FilmPalast-Codes (v30) zeigt sich, dass die AGENTS.md-Prio-Liste Prio 1-3 bereits implementiert sind (und ins Serienstream-Modul v31 übernommen wurden):
- Prio 1 (Qualitaetserkennung): `detectQuality()` in beiden Providern — fetcht m3u8-Manifest, parst `RESOLUTION=WxH`, mappt auf hoechste Variante (P2160/P1080/P720/P480/P360), mp4-Default P720. ARVIO zeigt echte Aufloesung, Auto-Play-Score > 0.
- Prio 2 (TMDB-Cache): `tmdbCache = ConcurrentHashMap<Int, TmdbMeta>()` in beiden Providern.
- Prio 3 (parallele Hoster-Aufloesung): `Executors.newFixedThreadPool(minOf(n,4))` mit 2-3s Future-Timeouts in beiden Providern.
→ Meilenstein „unter 2s Auto-Play-Timeout" auf Code-Ebene erreicht. Was bleibt = TV-Validierung + Prio 4.

**REIHENFOLGE (aktualisiert):**

#### PHASE 0 — Serienstream-TV-Test (JETZT, naechste Session, entscheidend)
Warum vor Prio 4: Das gerade gebaute Serienstream-Modul (v31, Option A) ist der kritische Pfad. Der DDoS-Guard-Bypass wurde nur vom Laptop getestet (dort blockiert — Erkenntnis #19). Ob der TV durchkommt (andere IP/Wohn-IP), entscheidet, ob wir Serienstream-Quellen haben und ob Option B noetig wird.
1. Nutzer folgt `docs/windows-10-test-guide.md` (WLAN-ADB + Logcat am TCL C7K).
2. In ARVIO: **Repo loeschen + neu hinzufuegen DIREKT** (nicht Cloud-Sync — Erkenntnis #1) mit `https://raw.githubusercontent.com/ReichiMD/Arvio-Addon/main/repo.json`.
3. `adb logcat -c`, Serienstream-Scraper einschalten.
4. Serie suchen (z.B. „Silo"), Quellensuche ausloesen, 15s warten.
5. Log speichern (`~/save-tv-log.sh` oder `adb logcat -v time > arvio-tv-log-v31.txt`), filtern: `findstr /i "Serienstream ArvioAddon ExtExt Error No.API load verify dex DDoS ddg resolve"`.
6. **Entscheidung anhand des Logs:**
   - `Serienstream`/`ArvioAddon`-Eintraege -> Scraper laeuft.
   - `resolveHost: ... final=<hoster-URL>` -> DDoS-Guard durchbrochen! (Option A erfolgreich) -> Quelle emittiert -> ZIEL.
   - `0 links collected` / `final=...serienstream.to/r?...` -> DDoS-Guard blockt weiterhin -> **Option B noetig** (Prio 4b).
   - `Failure to verify dex` / `No API loaded` -> DEX/Dispatch-Problem (unwahrscheinlich, gleiche Bau-Config wie FilmPalast v30).

#### PRIO 4a — Weitere FilmPalast-Hoster (niedrig, bei Bedarf)
Reihenfolge laut HOSTER-PRIORITAETEN-Tabelle: VOE (✅ v30), vidsonic (✅ v29), firestream (✅ v30). Verbleibend: Supervideo/VidHide/FileMoon nur falls neue Filmpalast-Hoster-Domains auftreten. Workflow: resolveurl Python lesen -> Kotlin portieren -> curl testen.

#### PRIO 4b — Serienstream Option B (nur falls Phase 0 scheitert)
Nur falls TV-Test zeigt, dass `/r?` blockiert bleibt:
1. **GitHub-Recherche** (Task 6, Nutzer explizit angefordert): Suche „ddos-guard bypass", „ddos-guard js challenge solver", cloudscraper-Aequivalent fuer DDoS-Guard (nicht Cloudflare). Repos: `VeNoMouS/cloudscraper` (Cloudflare, nicht DDG), dedizierte DDG-Solver.
2. Falls Solver gefunden: Algorithmus nach Kotlin portieren (java.net + Regex + ggf. JS-Engine — Achtung: JS-Engine koennte R8-Probleme verursachen wie app.get).
3. Falls kein Solver: **view.js-Challenge reverse-engineeren** (offline): Challenge-Seite fetchen, JS deobfuszieren, Token-Berechnung nachvollziehen, in Kotlin nachbauen.
4. In `httpGet` einbauen: bei 403+DDoS-Guard -> Solver -> retry.

#### PRIO 4c — GitHub-Issue bei ARVIO (nach Phase 0, professionell)
Status: NOCH NICHT eroeffnen — erst nach Serienstream-TV-Test, damit das Issue Beweise enthaelt. Drei dokumentierte Bugs:
1. R8 obfuscated Continuation/okhttp3 + stript kotlin-reflect + DefaultConstructorMarker (Haupt-Bug, Erkenntnis #7+#13+#14+#15+#16+#18).
2. Cloud-Sync-Restore laedt .cs3-Dateien nicht herunter (Erkenntnis #1).
3. ~~Touch-Bug~~ — behoben in 1.9.994.
Vorgehen (Vorbild Issue #537): Environment -> Summary -> Steps to reproduce -> Expected vs. Actual -> Root cause (mit Code-Verweis) -> Proposed fix -> References (#459, #273, #500) -> Logcat-Auszug -> **AI-Disclosure**. Optional: Fork + Fix-PR (ProGuard-Regel fuer `kotlin.coroutines.Continuation` + `kotlin.jvm.functions.*` unobfusziert).

**ZUSAMMENFASSUNG:** Flaschenhals = Phase 0 (TV-Test). Alles Weitere (Option B, Issue) haengt vom TV-Test-Befund ab. Sobald der Nutzer den TV erreichter kann, sollte Phase 0 an erster Stelle stehen.

### NEUE ARVIO-VERSION v1.9.994 (15.08.2026)
ARVIO v1.9.994 heute veroeffentlicht. VERIFIZIERT: Obfuskations-Map UNVERAENDERT (j7/d immer noch Continuation, rb/c0 immer noch okhttp3.Interceptor) -> unsere DEX-Patches funktionieren weiterhin. Neue nuetzliche Features: "Refresh Add-ons"-Aktion (#511, Plugin-Update ohne Loeschen/Neu-Hinzufuegen), "Fixed release dependency injection for sideload builds" (#525). Release-Notes erwaehnen NICHT den Cloudstream3-.cs3-Plugin-Obfuskations-Bug -> Kernproblem von ARVIO nicht geloest, nur unsere Patches bleiben kompatibel. Nutzer kann auf 1.9.994 updaten (sicher).
- Letzter Commit auf `main`: v17 (Fix #10, pre-d8 .class patching). Builds-Version: 17.

### Was fertig ist (unver–ď“ď–í”®ndert g–ď“ď–í—ėltig)
Filmpalast-Plugin als Cloudstream3-`TmdbProvider` implementiert, gebaut, auf `builds`-Branch (`status=1`, `tvTypes=[Movie,TvSeries]`). CI gr–ď“ď–í—ėn. Nutzer hat v13 in ARVIO 1.9.983 (sideload) installiert; v14 steht auf builds-Branch bereit zum Test. Python-E2E-Simulation l–ď“ď–í”®uft durch; filmpalast.to + TMDB per HTTP erreichbar. **Das Problem ist rein ARVIO-seitig beim Laden/Ausf–ď“ď–í—ėhren von `.cs3`-Plugins.**

---

### (Veraltet, aber als Referenz behalten) Fr–ď“ď–í—ėhere Logcat-Optionen ohne PC
ARVIO hat **keine Log-Datei-Exportfunktion** und schreibt **keine App-Logs in Dateien** (verifiziert im gesamten ARVIO-Quellcode). Scraper-Logs (`Log.d/w` in `ExternalExtensionRunner.kt`) gehen **nur an Androids Logcat-Kernel-Buffer** (fl–ď“ď–í—ėchtig, ohne Root nicht direkt auslesbar). Optionen ohne PC:
- **LADB-App:** scheiterte am Pairing ("no devices/emulators found"); "Pair & shell"-Schalter musste AN sein; 30-s-Pairing-Timer extrem zickig. **F–ď“ď–í—ėr diesen Nutzer nicht praktikabel.**
- **Bug Report:** Android-Einstellungen –ď—ě–í“∂–í‚Äô Entwickleroptionen –ď—ě–í“∂–í‚Äô Fehlerbericht (unhandlich, riesiger ZIP).
- **Nur mit Root:** Logcat-Reader-App.
- **WICHTIG:** ARVIOs integrierter "Test Scraper"-Button (`PluginManager.testScraper()`/`executeWithDiagnostics`) ist im Code vorhanden, aber in `PluginScreen.kt` **NICHT in die UI eingebaut** (Strings + ViewModel-Logik existieren, kein Compose-Button ruft `PluginUiEvent.TestScraper` auf). Halbfertige ARVIO-Funktion. F–ď“ď–í—ėr uns irrelevant, solange der Scraper ohnehin nie geladen wird.
–ď—ě–í“∂–í‚Äô **Fazit: PC+USB+adb ist der Weg.** Siehe Prio 1 oben.

---

### Wichtige Dateien & Referenzen
- **Filmpalast-Code:** `/workspace/project/Arvio-Addon/FilmPalast/src/main/kotlin/com/reichi/arflioaddon/filmpalast/` –ď—ě–í“Ė–í‚Äú `FilmpalastProvider.kt` (load/loadLinks/diagnose), `FilmpalastPlugin.kt`, `FilmpalastExtractors.kt`, `DebugLog.kt`, `DebugServer.kt`, `DownloadsLogWriter.kt`
- **ARVIO-Referenz:** `ProdigyV21/ARVIO` @ v1.9.983 (neu klonen nach `/tmp/arvio_ref`, wird nicht persistiert). Schl–ď“ď–í—ėssel-Dateien:
  - `app/src/sideload/kotlin/com/arflix/tv/core/plugin/PluginManager.kt` –ď—ě–í“Ė–í‚Äú `executeScrapers` (625), `executeScrapersStreaming` (672), `enabledScrapers` (271), `executeExternalDexScraper` (831, mit `SCRAPER_TIMEOUT_MS=120_000` bei 840), `downloadDexExtensions` (1057), `manifestEnabled = plugin.status == 1` (1079), `toggleScraper` (589, l–ď“ď–í”®dt NICHT neu), `refreshExternalRepository` (566, l–ď“ď–í”®dt neu)
  - `app/src/sideload/kotlin/com/arflix/tv/core/plugin/cloudstream/ExternalExtensionLoader.kt` –ď—ě–í“Ė–í‚Äú `downloadExtension` (203, DEX read-only f–ď“ď–í—ėr API28+), `loadExtension` (259), `findAndLoadPlugin` (701, liest `manifest.json`-`pluginClassName`), `plugin.load()`-Aufruf (317, f–ď“ď–í”®ngt Exception+Error), **Fallback-DEX-Scan bei `apis.isEmpty()||extractors.isEmpty()`** (336), `getApi` (420, apiCache)
  - `app/src/sideload/kotlin/com/arflix/tv/core/plugin/cloudstream/ExternalExtensionRunner.kt` –ď—ě–í“Ė–í‚Äú `execute` (60), `executeInternal` (342), `executeTmdbProvider` (367), `executeTmdbLoadLinks` (430, `LOADLINKS_TIMEOUT_MS=60_000` bei 442), `extractData` (738: Movie–ď—ě–í“∂–í‚ÄôdataUrl, TvSeries–ď—ě–í“∂–í‚ÄôfindEpisode.data), `filterValid` (870: nur http(s)-URLs!), `toLocalScraperResult` (884), `EXECUTION_TIMEOUT_MS=120_000`
  - `app/src/main/kotlin/com/arflix/tv/domain/model/Plugin.kt` –ď—ě–í“Ė–í‚Äú `ScraperInfo` (77), `supportsType` (92, normalisiert series/tv/anime–ď—ě–í“∂–í‚Äôtv)
  - `app/src/sideload/kotlin/com/arflix/tv/core/plugin/cloudstream/TvTypeExtensions.kt` –ď—ě–í“Ė–í‚Äú `tvTypeFromString`, `toNuvioType`
  - `app/src/main/kotlin/com/arflix/tv/ui/screens/details/DetailsViewModel.kt` –ď—ě–í“Ė–í‚Äú `loadStreams` (1405), `pluginScraperJob` (1511, ruft `executeScrapersStreaming`), `hasStreamingAddons` z–ď“ď–í”®hlt NUR Stremio-Addons (1601/1634/1651/1690) –ď—ě–í“∂–í‚Äô irref–ď“ď–í—ėhrende "kein Add-on"-Meldung bei reinen Cloudstream-Plugins
- **GermanProviders-Referenz:** `Bnyro/GermanProviders` (Repo-URL: `https://raw.githubusercontent.com/Bnyro/GermanProviders/refs/heads/master/repo.json`). Filmpalast dort = `MainAPI` (search-based). **Auf dem Ger–ď“ď–í”®t des Nutzers ebenfalls 0 Quellen** –ď—ě–í“∂–í‚Äô beweist ARVIO-seitiges `.cs3`-Problem.
- **Builds-Branch:** v14 veroeffentlicht (DEX-Patching), `status=1`, `internalName=FilmPalast`. `plugins.json`+`FilmPalast.cs3` auf `builds`.
- **cloudstream3 library:** v4.7.0 (`com.github.recloudstream.cloudstream:library-android:v4.7.0`). Built-in Extractoren: `Voe()`, `Firestream()`, `FileMoonSx()`, `Supervideo()`, `VidHidePro()` + ~270 andere via `installGlobal()`. **Wichtig:** `loadLinks` gibt in v4.7.0 `Boolean` zur–ď“ď–í—ėck (nicht Unit) –ď—ě–í“∂–í‚Äô Override muss `: Boolean` deklarieren.

### ARVIO-Scraper-Aufruf-Pfad (verifiziert, entscheidend f–ď“ď–í—ėrs Debugging)
1. `DetailsViewModel.loadStreams` –ď—ě–í“∂–í‚Äô `pluginScraperJob` –ď—ě–í“∂–í‚Äô `pluginManager.executeScrapersStreaming(tmdbId, mediaType, season, episode)`
2. `executeScrapersStreaming`: pr–ď“ď–í—ėft `pluginsEnabled` + `enabledScrapers.filter{supportsType}`; leer –ď—ě–í“∂–í‚Äô return; sonst pro Scraper `executeScraperWithSingleFlight` –ď—ě–í“∂–í‚Äô `executeExternalDexScraper` (mit `SCRAPER_TIMEOUT_MS=120_000`)
3. `executeExternalDexScraper` –ď—ě–í“∂–í‚Äô `externalExtensionRunner.execute(scraperId,...)` –ď—ě–í“∂–í‚Äô `extensionLoader.getApi(scraperId)` (leer –ď—ě–í“∂–í‚Äô "No API loaded" –ď—ě–í“∂–í‚Äô emptyList, **still**)
4. `execute` –ď—ě–í“∂–í‚Äô `executeInternal` –ď—ě–í“∂–í‚Äô **wenn `api is TmdbProvider`:** `executeTmdbProvider`; **sonst:** `executeSearchBased`
5. `executeTmdbProvider`: `api.load("""{"id":$tmdbIdInt,"type":"$type"}""")` –ď—ě–í“∂–í‚Äô null-fallback `api.load("https://www.themoviedb.org/<type>/<id>")` –ď—ě–í“∂–í‚Äô `extractData(loadResponse)` –ď—ě–í“∂–í‚Äô `api.loadLinks(data)`
6. `extractData`: `MovieLoadResponse`–ď—ě–í“∂–í‚Äô`dataUrl`, `TvSeriesLoadResponse`–ď—ě–í“∂–í‚Äô`findEpisode(...).data`
7. `executeTmdbLoadLinks`: sammelt `ExtractorLink`s via callback, `filterValid` (nur http(s)), `toLocalScraperResult` –ď—ě–í“∂–í‚Äô erscheinen in ARVIOs Quellenauswahl. **Unsere Debug-Quellen (url=`https://arvio-addon.invalid/...`) passieren filterValid.**
8. **Inkonsistenz (Test-Pfad):** `executeTmdbProviderWithDiagnostics` ruft `loadLinks` mit `TmdbLink(...).toJson()` direkt auf (ohne `load()`) –ď—ě–í“Ė–í‚Äú anderer data-Vertrag. Unser `loadLinks` ist auf den load()-Pfad ausgelegt. Falls ARVIO den Test-Button aktiviert, muss `loadLinks` auch TmdbLink-JSON verarbeiten.
9. **WICHTIG f–ď“ď–í—ėr "Quellen aktualisieren":** `toggleScraper` (589) l–ď“ď–í”®dt die `.cs3` NICHT neu –ď—ě–í“∂–í‚Äô nur Datenbank-Toggle. Neudownload NUR via `addRepository` oder `refreshExternalRepository`. **Daher: f–ď“ď–í—ėr Plugin-Update immer Repo l–ď“ď–í¬∂schen + neu hinzuf–ď“ď–í—ėgen** (`https://raw.githubusercontent.com/ReichiMD/Arvio-Addon/main/repo.json`).

### Diagnose-Tooling (HINWEIS: v10-v14 haben keine In-Plugin-Diagnose mehr, nur android.util.Log)–ď“ď–í—ėr die Logcat-–ď“ď–í‚Äěra falls Scraper doch l–ď“ď–í”®uft)
- `DebugLog.kt`: in-memory Ring-Buffer (2000) + `snapshot()`/`format()` f–ď“ď–í—ėr `emitTraceAsSources`.
- `emitTraceAsSources(callback)`: emittiert Trace als `ExtractorLink` (source="ArvioAddon-Debug", url=`https://arvio-addon.invalid/debug/<n>`) –ď—ě–í“∂–í‚Äú sichtbar in ARVIO-Quellenauswahl. Erstes Banner: "PLUGIN vN loaded".
- `debugLoadResponse()`: `MovieLoadResponse` mit `dataUrl="ARVIO_DEBUG"` –ď—ě–í“∂–í‚Äô `loadLinks` wird auch bei load()-Fehlern aufgerufen.
- Per-Call-Timeouts (`NET_TIMEOUT_MS=8000`) um `fetchTmdbMeta`/`searchFilmpalast`.
- `DebugServer.kt` (127.0.0.1:8420) + `DownloadsLogWriter.kt` (MediaStore) noch vorhanden, aber **nur nutzbar, wenn der Scraper l–ď“ď–í”®uft** (was aktuell nicht der Fall ist).

---

## Entscheidung: Welcher Plugin-Typ?

**Gew–ď“ď–í”®hlt: Cloudstream3-Plugin (Kotlin/DEX, ".cs3")** –ď—ě–í“Ė–í‚Äú der "m–ď“ď–í”®chtige" Weg.

| Grund | Detail |
|---|---|
| Eigene Konfig-Seite (Portal-URL/MAC) | Nur Cloudstream3-Plugins k–ď“ď–í¬∂nnen UI-Settings haben –ď—ě–í“∂–í‚Äô n–ď“ď–í¬∂tig f–ď“ď–í—ėr Stalker-VOD |
| Eigene Kataloge/Startseiten in ARVIO | Nur Cloudstream3-Plugins liefern eigene Start-Kataloge |
| Kotlin = gleiche Sprache wie Ventix | Ventix-Scraper (Kotlin) lassen sich **direkt portieren**, nicht nach JS –ď“ď–í—ėbersetzen |
| Viele Vorlagen | GermanProviders-Repo (Bnyro) ist eine komplette Vorlage mit exakt unseren Scraper-Hostern |
| Cloudstream3-–ď“ď–í‚Äďkosystem | ARVIO nutzt library v4.7.0; apiVersion 1 ist kompatibel |

**Abgew–ď“ď–í”®hlt: Nuvio-JS-Plugin** (Weg A) –ď—ě–í“Ė–í‚Äú einfacher, kann aber nur Streams liefern, keine Config-Seite, keine eigenen Kataloge. Da wir Stalker-VOD brauchen (mit Portal/MAC-Eingabe), reicht JS-Plugin nicht.

---

## Ziel: ARVIO-Installation des fertigen Plugins

Der Nutzer installiert das Plugin so in ARVIO (verifizierter Flow):
1. ARVIO **sideload-APK** installieren (nicht Play-Store-Version!)
2. Einstellungen –ď—ě–í“∂–í‚Äô **Plugins & Extensions** (nur in sideload sichtbar)
3. **Add Repository** –ď—ě–í“∂–í‚Äô Repo-URL eintragen
4. ARVIO l–ď“ď–í”®dt `repo.json` –ď—ě–í“∂–í‚Äô folgt `pluginLists` –ď—ě–í“∂–í‚Äô l–ď“ď–í”®dt `plugins.json`
5. Plugin-Eintr–ď“ď–í”®ge einschalten –ď—ě–í“∂–í‚Äô ARVIO l–ď“ď–í”®dt `.cs3`-Datei (kompilierter Code)

### Bekannter Bug: Add-Repo-Dialog/Plugin-Settings auf Handy (GEFIXT in 1.9.983)
Der "Add Repository"-Dialog + Plugin-Settings-Screen nutzten TV-only `androidx.tv.material3.Surface`-Buttons, die auf Touch-Ger–ď“ď–í”®ten (Handy/Tablet) nicht reagierten. **Behoben in ARVIO Issue #502** ("fix(mobile): resolve touch issues in plugins settings") –ď—ě–í“Ė–í‚Äú `PluginScreen.kt` hat jetzt `LocalDeviceType.current.isTouchDevice()` mit separatem Mobile-Layout. **Fix ist in 1.9.983 enthalten** (verifiziert). Nutzer hat das Plugin erfolgreich –ď“ď–í—ėber ein Cloud-Profil auf dem Handy installiert.

---

## Was das Plugin k–ď“ď–í¬∂nnen muss (Scope)

### Modul 1: Deutsche Web-Scraper (Filmpalast, Serienstream, HdFilme, Megakino, KinoGer, Netzkino, AniWorld)
- **Vorlage:** GermanProviders-Repo (Bnyro/GermanProviders) –ď—ě–í“Ė–í‚Äú hat ALL diese Scraper schon als Cloudstream3-Plugins!
- M–ď“ď–í¬∂glichkeit 1: GermanProviders forken + anpassen (wenig Eigenarbeit, abh–ď“ď–í”®ngig von Upstream)
- M–ď“ď–í¬∂glichkeit 2: Eigenes Plugin schreiben, GermanProviders als Referenz (volle Kontrolle)

### Modul 2: Stalker-VOD (Filme + Serien –ď“ď–í—ėber Stalker-Portal)
- **Das ist die Neuentwicklung** –ď—ě–í“Ė–í‚Äú GermanProviders hat das nicht.
- ARVIOs eingebaute StalkerApi kennt NUR Live-TV (get_genres, get_all_channels, create_link) –ď—ě–í“Ė–í‚Äú **KEIN VOD, keine Serien**.
- Plugin braucht: eigene Config-Seite (Portal-URL + MAC), VOD-Kategorien, VOD-Liste, createVodLink, Serien/Staffeln/Episoden.
- Vorlage: Ventix-StalkerApi (17 Methoden) –ď—ě–í“Ė–í‚Äú in Kotlin, direkt portierbar.

### Modul 3: Stalker Live-TV
- **Nicht bauen** –ď—ě–í“Ė–í‚Äú ARVIO hat das schon eingebaut (obwohl die UI aktuell fehlt, siehe "ARVIO-M–ď“ď–í”®ngel").

---

## Architektur-Referenz: ARVIOs Plugin-System

### Plugin-Formate die ARVIO versteht (verifiziert im Code)
1. **Nuvio-JS-Plugin**: `manifest.json` + `.js`-Dateien mit `getStreams(tmdbId, type, season, episode)`. Engine: QuickJS + Cheerio + CryptoJS. (abgew–ď“ď–í”®hlt)
2. **Cloudstream3-Plugin (EXTERNAL_DEX)**: `.cs3`-Datei (kompiliertes DEX). Engine: cloudstream3-library v4.7.0. (**gew–ď“ď–í”®hlt**)

### ARVIO Repository-Manifest-Format (`repo.json`)
```json
{
  "name": "Ventix Arvio Addon",
  "description": "Deutsche Scraper + Stalker VOD f–ď“ď–í—ėr ARVIO",
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
- `@CloudstreamPlugin`-annotierte `Plugin`-Klasse –ď—ě–í“∂–í‚Äô `registerMainAPI(...)` + `registerExtractorAPI(...)`
- `MainAPI`-Subklasse –ď—ě–í“∂–í‚Äô `mainUrl`, `name`, `supportedTypes`, `mainPage`, `search()`, `load()`, `loadLinks()`
- `ExtractorApi`-Subklassen f–ď“ď–í—ėr Hoster (VOE, FileMoon, Supervideo, VidHidePro etc.)

---

## Ventix-Referenz (Quell-Projekt, NICHT in dieses Repo kopieren)

Ventix liegt im Schwester-Repo `ReichiMD/IPTV-App`. Scraper-Quellcode zum Portieren:
- `app/src/main/java/com/iptv/stalker/data/scraping/` –ď—ě–í“Ė–í‚Äú FilmpalastScraper, HdFilmeScraper, KinogerScraper, MegakinoScraper, SerienstreamScraper, AniWorldScraper, NetzkinoScraper + extractor/
- `app/src/main/java/com/iptv/stalker/data/api/StalkerApi.kt` –ď—ě–í“Ė–í‚Äú Stalker-Middleware (17 Methoden: handshake, get_profile, get_events, VOD-+Serien-+EPG-Endpoints, createVodLink, getSeasons, M3U-Export)
- `app/upstream-reference/` –ď—ě–í“Ė–í‚Äú Cloudstream3-Upstream-Referenzen (bereits als Referenz genutzt!)
- `VideoHostExtractor.kt` –ď—ě–í“Ė–í‚Äú Hoster-Extraktoren (VOE, FileMoon, VidGuard, Veev, Vidsonic, DoodStream etc.)

Ventix und ARVIO nutzen BEIDE Cloudstream3-Upstream-Referenzen –ď—ě–í“Ė–í‚Äú das vereinfacht das Portieren.

---

## GermanProviders-Referenz (Vorlage-Repo, geklont nach /tmp/german-providers)

`Bnyro/GermanProviders` –ď—ě–í“Ė–í‚Äú Cloudstream3-Multi-Provider-Repo, hat bereits 21 fertige Plugins:
ARD, Aniworld, Arte, C3TV, Discovery, EinschaltenIn, **FilmPalast**, HDFilme, HuhuTo, IptvOrg, KinoKing, **Kinoger**, **Megakino**, Moflix, **Netzkino**, PlutoTV, **Serienstream**, Southpark, SpiegelTV, Welt, Xcine.

Installations-URL (Test): `https://raw.githubusercontent.com/Bnyro/GermanProviders/refs/heads/master/repo.json`
- `builds`-Branch enth–ď“ď–í”®lt `plugins.json` + fertige `.cs3`-Dateien.
- Aufbau: root `build.gradle.kts` (cloudstream3-gradle-plugin `com.github.recloudstream:gradle`), pro Provider ein Modul-Ordner mit `build.gradle.kts` + `src/`.
- Settings-Gradle: auto-include aller Modul-Ordner.

**Diese Scraper (Filmpalast, Serienstream etc.) sind identisch mit Ventix' Ziel-Set.** GermanProviders ist die prim–ď“ď–í”®re Vorlage f–ď“ď–í—ėr Modul 1.

---

## ARVIO-Referenz (geklot nach /tmp/arvio_ref)

`ProdigyV21/ARVIO` –ď—ě–í“Ė–í‚Äú die Ziel-App. Version 1.9.983 (versionCode 306), sehr aktiv.

### ARVIO Build-Flavors (verifiziert in `app/build.gradle.kts`)
| Flavor | `FEATURE_PLUGINS_ENABLED` | `SELF_UPDATE_ENABLED` | Plugin-Engine |
|---|---|---|---|
| `play` (Play Store) | **false** | false | –ď—ě–í“õ–í“Ę abgeschaltet |
| `sideload` (GitHub-APK) | **true** | true | –ď—ě–í“£–í‚Ä¶ voll aktiv |

–ď—ě–í“∂–í‚Äô **Plugin funktioniert NUR in der sideload-APK**, nicht im Play-Store-Build. Google-Policy verbietet dynamischen Code im Store.

### ARVIO sideload-Download
`https://github.com/ProdigyV21/ARVIO/releases/download/v1.9.983/ARVIO-v1.9.983-sideload-release.apk` (135 MB)

### ARVIO Plugin-Engine (nur in `app/src/sideload/`)
- `PluginManager.kt` –ď—ě–í“Ė–í‚Äú Repository-Verwaltung, `addRepository()`, Format-Auto-Detection
- `PluginRuntime.kt` –ď—ě–í“Ė–í‚Äú QuickJS-Engine (f–ď“ď–í—ėr JS-Plugins) + `__native_fetch`, `__cheerio_*`, CryptoJS
- `cloudstream/ExternalExtensionLoader.kt` –ď—ě–í“Ė–í‚Äú l–ď“ď–í”®dt `.cs3`-Plugins via DexClassLoader
- `cloudstream/ExternalExtensionRunner.kt` –ď—ě–í“Ė–í‚Äú f–ď“ď–í—ėhrt `MainAPI.search()`/`load()`/`loadLinks()` aus
- `cloudstream/ExternalExtractorRegistry.kt` –ď—ě–í“Ė–í‚Äú verwaltet `ExtractorApi`-Extraktoren
- `cloudstream/ExternalRepoParser.kt` –ď—ě–í“Ė–í‚Äú parsed `repo.json` (erkennt `"pluginLists"`-Key) + `plugins.json`

### ARVIO-M–ď“ď–í”®ngel (Stand Aug 2026, die wir im Plugin adressieren)
1. **Stalker-VOD fehlt komplett** –ď—ě–í“Ė–í‚Äú StalkerApi kennt nur Live-TV (4 Methoden: handshake, getProfile, getChannels, resolveStreamUrl). Kein VOD, keine Serien, kein EPG f–ď“ď–í—ėr VOD.
2. **Stalker-Dateneingabe fehlt in der UI** –ď—ě–í“Ė–í‚Äú `saveStalkerConfig()` existiert im SettingsViewModel (Zeile 2280), wird aber von KEINEM UI-Element aufgerufen. Kein Button, kein Dialog. Backend halbfertig, UI fehlt.
3. **Add-Repo-Dialog Handy-Bug** –ď—ě–í“Ė–í‚Äú `width(520.dp)` zu breit f–ď“ď–í—ėr Hochformat (Workaround: Querformat).

---

## Stremio-Addon-Referenz (paralleles Projekt)

`ReichiMD/Stremio-Addon` –ď—ě–í“Ė–í‚Äú serverseitiges Node.js-Addon (deployed auf Render), Quellen: Vavoo/KinoGer/Filmpalast/MovieBox/VidSrc/Einschalten + MediaFlowProxy.
- **Problem:** Stremio-Addon (serverseitig) kann manche Streams nicht liefern (z.B. KinoGer 403 –ď—ě–í“Ė–í‚Äú Render-DC-IP blockiert). Ventix (clientseitig) kann das.
- **Dieses ARVIO-Addon l–ď“ď–í¬∂st das:** l–ď“ď–í”®uft clientseitig in der App –ď—ě–í“∂–í‚Äô Endger–ď“ď–í”®t-IP –ď—ě–í“∂–í‚Äô kein Bot-Schutz –ď—ě–í“∂–í‚Äô kein Server, kein Geld.
- Logik der Scraper ist im Stremio-Addon bereits in JavaScript/TypeScript vorhanden (kann als Referenz dienen, wird aber neu in Kotlin als Cloudstream3-Plugin geschrieben).

`ReichiMD/mediaflow-proxy` –ď—ě–í“Ė–í‚Äú MediaFlowProxy (fest codiert im Stremio-Addon). F–ď“ď–í—ėr ARVIO-Addon nicht n–ď“ď–í¬∂tig (clientseitig braucht keinen Proxy).

---

## Build & Release

### Plugin kompilieren (Cloudstream3-gradle-plugin)
- Multi-Modul-Setup wie GermanProviders: root `build.gradle.kts` mit `com.github.recloudstream:gradle`-Plugin, pro Plugin ein Modul.
- Output: `.cs3`-Datei pro Plugin-Modul.
- CI: GitHub Actions baut `.cs3`-Dateien, pusht sie auf einen `builds`-Branch (wie GermanProviders), generiert/aktualisiert `plugins.json`.

### Datei-Struktur (geplant)
```
Arvio-Addon/
–ď—ě–í‚ÄĚ–í“£–ď—ě–í‚ÄĚ–í“Ė–ď—ě–í‚ÄĚ–í“Ė AGENTS.md                          # diese Datei
–ď—ě–í‚ÄĚ–í“£–ď—ě–í‚ÄĚ–í“Ė–ď—ě–í‚ÄĚ–í“Ė README.md                          # (sp–ď“ď–í”®ter)
–ď—ě–í‚ÄĚ–í“£–ď—ě–í‚ÄĚ–í“Ė–ď—ě–í‚ÄĚ–í“Ė build.gradle.kts                   # root, cloudstream3-gradle-plugin
–ď—ě–í‚ÄĚ–í“£–ď—ě–í‚ÄĚ–í“Ė–ď—ě–í‚ÄĚ–í“Ė settings.gradle.kts                # auto-include Module
–ď—ě–í‚ÄĚ–í“£–ď—ě–í‚ÄĚ–í“Ė–ď—ě–í‚ÄĚ–í“Ė repo.json                          # Installations-Manifest f–ď“ď–í—ėr ARVIO
–ď—ě–í‚ÄĚ–í“£–ď—ě–í‚ÄĚ–í“Ė–ď—ě–í‚ÄĚ–í“Ė GermanScraper/                     # Modul 1: deutsche Web-Scraper (oder pro Scraper ein Modul)
–ď—ě–í‚ÄĚ–í”ģ   –ď—ě–í‚ÄĚ–í“£–ď—ě–í‚ÄĚ–í“Ė–ď—ě–í‚ÄĚ–í“Ė build.gradle.kts
–ď—ě–í‚ÄĚ–í”ģ   –ď—ě–í‚ÄĚ–í‚ÄĚ–ď—ě–í‚ÄĚ–í“Ė–ď—ě–í‚ÄĚ–í“Ė src/main/kotlin/.../GermanScraperPlugin.kt + Provider + Extractors
–ď—ě–í‚ÄĚ–í‚ÄĚ–ď—ě–í‚ÄĚ–í“Ė–ď—ě–í‚ÄĚ–í“Ė StalkerVod/                        # Modul 2: Stalker-VOD (Config-Seite + VOD/Serien)
    –ď—ě–í‚ÄĚ–í“£–ď—ě–í‚ÄĚ–í“Ė–ď—ě–í‚ÄĚ–í“Ė build.gradle.kts
    –ď—ě–í‚ÄĚ–í‚ÄĚ–ď—ě–í‚ÄĚ–í“Ė–ď—ě–í‚ÄĚ–í“Ė src/main/kotlin/.../StalkerVodPlugin.kt + StalkerApi + Provider
```

### branches
- `main` –ď—ě–í“Ė–í‚Äú Quellcode
- `builds` –ď—ě–í“Ė–í‚Äú fertige `.cs3`-Dateien + `plugins.json` (von CI gepusht, wie GermanProviders)

---

## N–ď“ď–í”®chste Schritte (Priorit–ď“ď–í”®t)

1. **Proof-of-Concept:** GermanProviders in ARVIO-sideload testen (Button-Bug workaronden) –ď—ě–í“∂–í‚Äô pr–ď“ď–í—ėfen welche Scraper laufen.
2. **Repo-Setup:** GermanProviders-Architektur (root build.gradle + Modul-Struktur) hier nachbauen.
3. **Modul 1 (Web-Scraper):** GermanProviders-Plugins adaptieren ODER eigene Implementierung. Hoster-Extraktoren (VOE, FileMoon etc.) aus Ventix' `VideoHostExtractor` portieren.
4. **Modul 2 (Stalker-VOD):** Ventix' `StalkerApi.kt` (VOD/Serien-Teil) als Cloudstream3-Provider portieren + Config-Seite f–ď“ď–í—ėr Portal/MAC.
5. **CI:** GitHub Actions workflow f–ď“ď–í—ėr `.cs3`-Build + `builds`-Branch-Push.

---

## Versionshistorie dieses Addons

(noch keine –ď—ě–í“Ė–í‚Äú Repo ist leer)

---

## Recherche: ARVIO-Plugin-Integration (Stand Aug 2026, ARVIO v1.9.983)

Verifiziert im ARVIO-Quellcode (`ProdigyV21/ARVIO` @ v1.9.983, geklont nach `/tmp/arvio_ref`).
Recherche anl–ď“ď–í”®sslich zweier Nutzer-Probleme beim Testen von GermanProviders als Cloudstream3-Plugin in ARVIO.

### Problem 1: Plugin-Einrichtung funktioniert nur auf TV, nicht auf Handy/Tablet

**Beobachtung (Nutzer):** Add-Repository / Plugin-Aktivierung ging auf Handy & Tablet nicht; erst ein ARVIO-Cloud-Profil (auf TV erstellt, aufs Handy synchronisiert) brachte die Plugins aufs Handy. TV funktionierte direkt.

**Rechercheergebnis:**
- ARVIO hat ein Layout-Force-Feature ("Force TV, Tablet, or Phone layout") UND Auto-Detect f–ď“ď–í—ėr TV-Modus bei Ger–ď“ď–í”®ten ohne Touchscreen (CHANGELOG v1.9.3). Die UI wird je Formfaktor unterschiedlich gerendert.
- Der Plugin-Bereich wurde in v1.9.983 neu gebaut: CHANGELOG-Eintrag "redesigned plugin settings for TV and mobile. Contributor: @Himanth-reddy via #466" –ď—ě–í“Ė–í‚Äú d.h. die mobile Plugin-UI ist **sehr neu** (Juli 2026).
- Begleitend in v1.9.983: "Fixed sideload production-plugin routing, extractor unloading, **mobile routing**, and TV focus limits" (#466) –ď—ě–í“Ė–í‚Äú ein mobiler Routing-Fix wurde *explizit* f–ď“ď–í—ėr diese Version gebraucht. Das deutet darauf hin, dass mobile Plugin-Pfade vorher fehlerhaft waren.
- Ein **bekannter, –ď“ď–í”®lterer Bug** (AGENTS.md bereits notiert): Add-Repo-Dialog `width(520.dp)` zu breit f–ď“ď–í—ėr Handy-Hochformat (~390dp) –ď—ě–í“∂–í‚Äô Buttons abgeschnitten/inaktiv im Hochformat.
- Vergleichs-Befund aus dem Nuvio-–ď“ď–í‚Äďkosystem (Schwester-App, gleiche Plugin-Architektur): NuvioMobile Issue #1190 –ď—ě–í“Ė–í‚Äú *"If Cloudstream Plugin Repositories are loaded in the Plugins list in the Mobile app, they get removed from Plugins list in the TV app"* (closed as not planned). Cloudstream-Plugin-Listen zwischen Mobile- und TV-UI synchron halten ist **branchenweit ein Problem**, nicht ARVIO-spezifisch.

**Fazit Problem 1:** Sehr wahrscheinlich ein **ARVIO-seitiger Bug in der (neuen) mobilen Plugin-UI** –ď—ě–í“Ė–í‚Äú entweder Routing (in v1.9.983 gerade erst gefixt, evtl. nicht vollst–ď“ď–í”®ndig) oder der bekannte `width(520.dp)`-Dialog-Bug. Dass der Cloud-Sync-Workaround funktioniert, best–ď“ď–í”®tigt: Die Plugin-Daten selbst sind korrekt; nur die mobile Einrichtungspath-UI ist defekt. Keine andere Nutzerberichte als direktes Duplikat gefunden, aber die CHANGELOG-Historie (mobiler Plugin-Routing-Fix in der *aktuellen* Version) zeigt, dass ARVIO genau diese Klasse von Bug gerade behebt.

**Workarounds f–ď“ď–í—ėr Nutzer:** Querformat beim Add-Repo; oder Plugin-Konfiguration auf TV vornehmen + ARVIO-Cloud-Sync aufs Handy (funktioniert laut Nutzer bereits); oder `web.arvio.tv` (Web-App, vollst–ď“ď–í”®ndige ARVIO-UI im Browser, laut CHANGELOG mit TV-D-pad-Navigation).

### Problem 2: Aktivierte Provider erscheinen nicht bei Quellensuche ("kein Add-on eingerichtet / keine Quellen")

**Beobachtung (Nutzer):** In den Plugin-Einstellungen Provider (z.B. Einschalten) aktiviert –ď—ě–í“∂–í‚Äô auf eine Silo-Episode gegangen –ď—ě–í“∂–í‚Äô "nach Quellen gesucht" –ď—ě–í“∂–í‚Äô Meldung "kein Add-on eingerichtet, keine Quellen gefunden".

**Verifizierte Ursache im ARVIO-Code:** ARVIO hat **zwei komplett getrennte Quell-Aufl–ď“ď–í¬∂sungspfade**, und Cloudstream3-Plugins (.cs3) laufen –ď“ď–í—ėber den Pfad, der die "kein Add-on"-Meldung **nicht steuert**:

1. **Stremio-Addon-Pfad** (`StreamRepository` + `AddonRuntimeAggregator`): Hier laufen klassische Stremio-kompatible Addons (HTTP `stream/movie/<imdbId>.json`), Home-Server (Jellyfin/Plex/Emby) und HTTP-Local-Scrapers. Die UI-Variable `hasStreamingAddons` (die "No Streaming Addons" / "kein Add-on eingerichtet" anzeigt) wird **ausschlie–ď“ď–í“Ļlich** aus `streamRepository.installedAddons.count { it.isVodStreamingAddon() }` berechnet (`DetailsViewModel.kt` Z. 1600/1633/1650/1689). `isVodStreamingAddon()` pr–ď“ď–í—ėft nur `isEnabled && type != SUBTITLE && !sportsOnly` –ď—ě–í“Ė–í‚Äú das sind Stremio-Addons, **keine Cloudstream-Scraper**. Filter `getStreamAddons()` (`StreamRepository.kt` Z. 1440) wirft sogar hart raus: `if (addon.runtimeKind != RuntimeKind.STREMIO) return@filter false` –ď—ě–í“Ė–í‚Äú und `RuntimeKind` kennt nur `STREMIO`/`TELEGRAM`, keinen Cloudstream/EXTERNAL_DEX-Wert (`Models.kt` Z. 305).

2. **Cloudstream-Plugin-Pfad** (`PluginManager` + `ExternalExtensionRunner`, sideload-only): Aktivierte `.cs3`-Scraper werden in `DetailsViewModel.loadStreams()` –ď“ď–í—ėber `pluginManager.executeScrapersStreaming(...)` in einem **parallelen Job** (`pluginScraperJob`, Z. 1510–ď—ě–í“Ė–í‚Äú1552) ausgef–ď“ď–í—ėhrt. Ergebnisse mergen sich asynchron in `streams`. Dieser Pfad startet **nur**, wenn `dataStore.pluginsEnabled` true ist UND `enabledScrapers` (nach `supportsType(mediaType)`) nicht leer ist (`PluginManager.kt` Z. 631–ď—ě–í“Ė–í‚Äú640, 681).

**Warum trotzdem "kein Add-on"-Meldung + keine Quellen bei Silo:** Weil `hasStreamingAddons` Stremio-Addons z–ď“ď–í”®hlt. Hat der Nutzer **kein einziges** Stremio-Addon installiert (nur Cloudstream-Plugins), ist `hasStreamingAddons=false` –ď—ě–í“∂–í‚Äô UI zeigt "No Streaming Addons / kein Add-on eingerichtet" an. Die Meldung ist in diesem Fall **irref–ď“ď–í—ėhrend**: Die Cloudstream-Scraper suchen im Hintergrund trotzdem, finden aber f–ď“ď–í—ėr "Silo" vermutlich nichts (siehe Problem 2b), und die UI bleibt bei der "Setup Required"-Meldung stehen, obwohl die Plugins aktiv sind.

**Problem 2b –ď—ě–í“Ė–í‚Äú warum die Cloudstream-Scraper f–ď“ď–í—ėr "Silo" trotzdem 0 Quellen liefern (verifiziert):**
GermanProviders-Plugins (Filmpalast, Serienstream, AniWorld etc.) sind **keine** `TmdbProvider` (sie –ď“ď–í—ėberschreiben nicht `load()` f–ď“ď–í—ėr TMDB-JSON), sondern **search-basierte** `MainAPI`-Provider. ARVIOs `ExternalExtensionRunner.executeSearchBased()` (Z. 473–ď—ě–í“Ė–í‚Äú620) macht f–ď“ď–í—ėr search-basierte Provider:
1. TMDB-Enrichment holen –ď—ě–í“∂–í‚Äô `localizedTitle` + `year` + alt-Titel
2. `api.search(title)` aufrufen + bei Trefferlosigkeit Retry mit vereinfachtem Titel und parallelen Alt-Titeln
3. `findBestMatch()` (–ď“ď–í‚Äěhnlichkeits-Score) –ď“ď–í—ėber Suchergebnisse –ď—ě–í“∂–í‚Äô `api.load(bestMatch.url)` –ď—ě–í“∂–í‚Äô `extractData()` –ď—ě–í“∂–í‚Äô `api.loadLinks()`

Scheitern kann es an **mehreren Stellen**:
- **Sprache:** Silo ist eine Apple TV+-Serie. Deutsche Scraper wie Filmpalast/Serienstream listen "Silo" u.U. nur unter deutschem Titel oder garnicht (Apple-TV+-Originals sind seltener auf deutschen Scraper-Seiten als Netflix/Prime). TMDB `localizedTitle` f–ď“ď–í—ėr Silo DE = "Silo" –ď—ě–í“Ė–í‚Äú passt, aber die Scraper-Seite muss die Serie auch im Katalog haben.
- **`findBestMatch`-Mismatch:** Wenn der Scraper "Silo" z.B. als "Silo - Season 1" oder mit Jahr-Abweichung zur–ď“ď–í—ėckgibt, f–ď“ď–í”®llt der Similarity-Score unter die Schwelle –ď—ě–í“∂–í‚Äô `return emptyList()` (Z. 567). Das ist ein **h–ď“ď–í”®ufiges** Cloudstream-Problem bei ARVIO, weil ARVIO eigenes Title-Matching macht statt die Provider-`load()` direkt mit der Scraper-eigenen URL zu f–ď“ď–í—ėttern.
- **Season/Episode-Mapping:** `extractData(loadResponse, mediaType, season, episode)` baut das `data`-JSON, das `loadLinks()` erwartet. Bei Serien muss `load()` eine `TvSeriesLoadResponse` liefern, aus der ARVIO die Episoden-URL extrahiert. GermanProviders' `load()`-Implementierungen sind f–ď“ď–í—ėr Cloudstream3-App geschrieben; ARVIO ruft sie leicht anders auf –ď—ě–í“∂–í‚Äô kann `data=null` geben –ď—ě–í“∂–í‚Äô `return emptyList()` (Z. 590).
- **Host-Dead / Bot-Schutz:** Deutsche Scraper-Seiten blockieren oft. ARVIO f–ď“ď–í”®ngt `hostUnreachable` ab und skippt (Z. 552). Da ARVIO clientseitig l–ď“ď–í”®uft (Ger–ď“ď–í”®t-IP), sollte das seltener sein als beim serverseitigen Stremio-Addon –ď—ě–í“Ė–í‚Äú aber m–ď“ď–í¬∂glich.

**Fazit Problem 2:** Zwei Dinge –ď“ď–í—ėberlagern sich:
- (a) **ARVIO-UI-Bug/Designschw–ď“ď–í”®che:** Die "kein Add-on eingerichtet"-Meldung wird nur aus dem Stremio-Addon-Pfad gespeist und ignoriert aktivierte Cloudstream-Plugins vollst–ď“ď–í”®ndig. Solange kein Stremio-Addon aktiv ist, zeigt die UI "Setup Required", **selbst wenn** Cloudstream-Scraper im Hintergrund laufen. Das ist eine ARVIO-seitige Logikl–ď“ď–í—ėcke, nicht des Addons Schuld.
- (b) **Scraper-Matching:** Selbst wenn die Cloudstream-Scraper laufen, liefern sie f–ď“ď–í—ėr bestimmte Titel (wie Silo) oft 0 Treffer wegen ARVIOs eigenem Title-Matching / `findBestMatch` / Episode-Mapping, das nicht 1:1 der Cloudstream3-App entspricht.

CHANGELOG-Belege, dass ARVIO dieses Themenfeld aktiv bearbeitet:
- v1.9.983: "Added compatibility for Nuvio-style JavaScript scraper plugins and redesigned plugin settings for TV and mobile" (#466) + "Fixed sideload production-plugin routing, extractor unloading, mobile routing, and TV focus limits" (#466)
- v1.9.92: "Improved FlixStreams/anime addon matching and fallback stream lookup for episode sources" + "Fixed configured add-ons occasionally failing to appear in the source list until a later retry"
- v1.8.2: "Source selector shows setup instructions instead of generic 'No sources found' when no addons are installed" + "When no streaming addons are configured, the app now shows a friendly setup guide instead of a playback error"

**Handlungsempfehlung (f–ď“ď–í—ėr unser Addon / Nutzer):**
1. **F–ď“ď–í—ėr saubere UI-Anzeige:** Zus–ď“ď–í”®tzlich zu den Cloudstream-Plugins **mindestens ein** Stremio-Addon (auch ein inaktives/dummy) installieren, damit `hasStreamingAddons=true` wird und die Meldung verschwindet. Das ist ein Workaround f–ď“ď–í—ėr ARVIOs Logikl–ď“ď–í—ėcke (a).
2. **F–ď“ď–í—ėr echte Quellen bei Serien wie Silo:** Eigenes ARVIO-Addon bauen (Ziel dieses Repos) –ď—ě–í“Ė–í‚Äú aber dabei darauf achten, dass die `MainAPI`-Implementierung robustes `search()` + `load()` + `loadLinks()` bietet, das ARVIOs `findBestMatch`-basiertem Aufruffluss standh–ď“ď–í”®lt. Ideal: Provider als `TmdbProvider` implementieren (dann nimmt ARVIO den direkteren `executeTmdbProvider`-Pfad ohne fragiles Title-Matching). Das ist eine **Konsequenz f–ď“ď–í—ėr die Modul-1-Architektur** dieses Addons.
3. **GitHub-Issue bei ARVIO erw–ď“ď–í”®gen:** (a) ist klar ein ARVIO-Bug ("hasStreamingAddons ignoriert aktivierte Cloudstream-Scraper"). Lohnt sich als Issue zu melden, da ARVIO aktiv ist (18 Releases in 5 Monaten) und #466 genau dieses Gebiet gerade anfasst.

---

## Implementation: Filmpalast-Plugin als TmdbProvider (Proof-of-Concept)

**Status: gebaut und kompiliert.** `FilmPalast/build/FilmPalast.cs3` (–ď—ě–í“Į–í“≤23 KB) + `build/plugins.json` werden lokal via `./gradlew make makePluginsJson` erzeugt; CI (`.github/workflows/build.yml`) pusht beides auf den `builds`-Branch.

### Architektur-Entscheidung (verbindlich f–ď“ď–í—ėr alle Modul-1-Scraper)
**Alle Provider als `TmdbProvider` implementieren**, nicht als plain `MainAPI`. Begr–ď“ď–í—ėndung (siehe oben "Recherche"): ARVIO hat zwei Dispatch-Pfade in `ExternalExtensionRunner.execute()`:
- `executeTmdbProvider` (wenn `api is TmdbProvider`): ruft `api.load("{\"id\":<tmdbId>,\"type\":\"movie\"|\"tv\"}")` direkt auf –ď—ě–í“∂–í‚Äô kein fragiles `findBestMatch`-Title-Matching.
- `executeSearchBased` (sonst): sucht Titel, matcht via Similarity-Score, mappt Season/Episode –ď—ě–í“∂–í‚Äô h–ď“ď–í”®ufig 0 Treffer bei Serien.

TmdbProvider ist der zuverl–ď“ď–í”®ssige Pfad. GermanProviders' Scraper sind alles *search-based* (kein TmdbProvider) –ď—ě–í“∂–í‚Äô das ist mit ein Grund, warum sie in ARVIO bei Serien oft leer bleiben.

### TmdbProvider-Vertrag (verifiziert am cloudstream3-Source `TmdbProvider.kt`)
- ARVIO ruft `load("{\"id\":<tmdbId>,\"type\":...}")`; Fallback `load("https://www.themoviedb.org/<type>/<id>")`. Beide Formen m–ď“ď–í—ėssen `parseTmdbInput` akzeptieren.
- `load()` muss zur–ď“ď–í—ėckgeben: `MovieLoadResponse` (Filme, `dataUrl`=JSON) ODER `TvSeriesLoadResponse` mit `Episode`-Liste (Serien, `episode.data`=URL).
- `loadLinks(data, ...)`: f–ď“ď–í—ėr Filme ist `data` das JSON aus `dataUrl`; f–ď“ď–í—ėr Serien ist `data` die Episoden-URL aus `episode.data`.
- `useMetaLoadResponse = false` (wir bauen die LoadResponse selbst, nicht –ď“ď–í—ėber TMDB-Meta-Provider).

### Filmpalast-Seitenstruktur (live verifiziert, Stand Aug 2026)
- Suche `/search/title/<query>`: listet Serien **pro Episode** (`/stream/silo-s03e06`), Filme als einzelne Seite. Keine Serien-Stammseite mit Staffeln.
- Stream-Seite `/stream/<slug>`: Hoster-Links in `ul.currentStreamLinks a.iconPlay` mit `data-player-url` (prim–ď“ď–í”®r) bzw. `href` (fallback).
- Gesehene Hoster: firestream.to, vidaraa.cc, voe.sx, vidsonic.net –ď—ě–í“∂–í‚Äô gemappt auf `Voe1`, `FileMoonSx`, `VidHidePro` (Ryderjet), `Supervideo` (AbstreamTo).

### Filmpalast-spezifische `load()`-Logik
1. TMDB-Meta holen (`api.themoviedb.org/3`, de-DE) –ď—ě–í“∂–í‚Äô `displayTitle` + `year`.
2. Filmpalast-Suche nach `displayTitle`.
3. Treffer matchen (normalisierter Titel-Vergleich, Typ movie/tv). Serie `"Silo S03E06"` –ď—ě–í“∂–í‚Äô Basisname `"Silo"` wird gegen TMDB-Titel gematcht.
4. Serie: alle Episoden sammeln –ď—ě–í“∂–í‚Äô `TvSeriesLoadResponse` (Season/Episode aus Titel geparst). Film: `MovieLoadResponse` mit `dataUrl=JSON{links:[...]}`.
5. `loadLinks`: Film–ď—ě–í“∂–í‚ÄôJSON-Links; Serie–ď—ě–í“∂–í‚ÄôEpisoden-URL fetchen + Host-Links sammeln –ď—ě–í“∂–í‚Äô `loadExtractor()` pro registriertem Hoster.

### Bekannte Vorbehalte (Proof-of-Concept)
- **Apple-TV+-Serien (Silo):** deutsche Scraper haben solche Titel u.U. nicht oder zeitverz–ď“ď–í¬∂gert. TMDB-Titel passt, aber Filmpalast muss die Serie im Katalog haben.
- **TMDB-API-Key:** fest codiert (–ď“ď–í¬∂ffentlich bekannter Cloudstream-Key). F–ď“ď–í—ėr Produktion ggf. eigener Key.
- **Hoster-Dead:** Filmpalast-Hosterdomains rotieren; Extractor-Mapping muss ggf. nachjustiert werden. Neue Domains via `registerExtractorAPI` hinzuf–ď“ď–í—ėgen.

### –ď—ě–í“°–í¬†–ď“ú–í—Ď–í“ł status-Wert MUSS 1 sein (verifiziert im ARVIO-Code)
Der cloudstream-gradle-plugin-Default ist `status = 3` ("Beta only"). **Das bricht ARVIO.**
- `PluginManager.downloadDexExtensions` (PluginManager.kt:1079): `manifestEnabled = plugin.status == 1`
- `PluginDataStore.setScraperEnabled` (PluginDataStore.kt:152): `if (enabled && !scraper.manifestEnabled) return` –ď—ě–í“∂–í‚Äô speichert das Enable **nicht**, wenn `manifestEnabled=false`.
- Folge: Plugin sichtbar in der Liste, aber Toggle speichert nicht –ď—ě–í“∂–í‚Äô Scraper l–ď“ď–í”®uft nicht –ď—ě–í“∂–í‚Äô keine Quellen.
- **Fix:** Im Modul-`build.gradle.kts` IMMER `status = 1` setzen (wie GermanProviders: alle 21 Plugins `status=1`). Nie Default `3` lassen.

### –ď—ě–í“°–í¬†–ď“ú–í—Ď–í“ł Hoster-Extraktion: built-in cloudstream3-Extractoren nutzen, nicht re-registrieren (verifiziert)
Filmpalast rotiert Hostnamen pro Episode/Load. Verifizierte Hostnamen (Aug 2026):
- **Built-in in cloudstream3** (ARVIO l–ď“ď–í”®dt sie via `ExternalExtractorRegistry.installGlobal()` automatisch): `voe.sx` (Voe), `firestream.to` (Firestream), `filemoon.sx` (FileMoonSx), `supervideo.cc` (Supervideo), `vidhide.com` (VidHidePro).
- **NICHT built-in** (Filmpalast-spezifisch, eigene Extractor-Aliase n–ď“ď–í¬∂tig): `ryderjet.com`, `abstream.to`.
- **Obskur / API-basiert** (kein statischer Extractor m–ď“ď–í¬∂glich): `vidaraa.cc`, `vidsonic.net`, `odysseusa.cc`, `MoneyGalactic.com` (JWPlayer mit `t.streaming_url` aus API-Call –ď—ě–í“Ė–í‚Äú generischer Fallback findet nur sometimes direkte URLs).

**Fehler, der "no sources" verursachte (behoben in b6e3c1b):**
1. `loadLinks` setzte `any=true`, sobald `loadExtractor` *aufgerufen* wurde –ď—ě–í“Ė–í‚Äú ignorierte den R–ď“ď–í—ėckgabewert. Wenn alle `loadExtractor` `false` zur–ď“ď–í—ėckgaben (kein passender Extractor), blieb `any` trotzdem `true` –ď—ě–í“∂–í‚Äô irref–ď“ď–í—ėhrend. Fix: `any` nur auf `true` wenn `loadExtractor` true ODER generischer Fallback findet URL.
2. `Voe1()` registriert –ď—ě–í“Ė–í‚Äú `Voe1.mainUrl = "https://donaldlineelse.com"` (rotierender VOE-Mirror), matched **nicht** auf `voe.sx`-Links. Built-in `Voe()` (mainUrl=`voe.sx`) matched korrekt. Fix: `Voe1`/`FileMoonSx` nicht mehr re-registrieren (built-in reicht).
3. **Generischer Fallback** (`genericResolve`): fetcht Embed-Seite, sucht nach direkten mp4/m3u8-URLs (Regex). Best-Effort f–ď“ď–í—ėr obskure JWPlayer-Hoster; f–ď“ď–í”®ngt nicht alle (vidaraa braucht API-Call), aber f–ď“ď–í”®ngt z.B. firestream-Video-Pfade.

### Recherche: ARVIO Test-Funktion & Log-M–ď“ď–í¬∂glichkeit (Aug 2026, ARVIO 1.9.983)
**ARVIO hat KEINE Log-Datei-Exportfunktion.** `DiagnosticsManager` ist nur f–ď“ď–í—ėr Sentry/Crashlytics-Reporting, keine In-App-Log-Anzeige. Der einzige Weg an die Scraper-Logs zu kommen ist **Logcat** (`adb logcat` –ď“ď–í—ėber USB am PC).
- ARVIO hat im Code eine **"Test Scraper"-Funktion** (`PluginManager.testScraper()` –ď—ě–í“∂–í‚Äô `executeWithDiagnostics()`), die mit The Matrix (TMDB 603) testet und `TestDiagnostics` mit Einzelschritten zur–ď“ď–í—ėckgibt (TMDB-Metadaten, search-Ergebnisse, HTTP-Requests, loadLinks, "Missing extractors: ..."). **ABER: der "Test"-Button ist in `PluginScreen.kt` NICHT in die UI eingebaut** –ď—ě–í“Ė–í‚Äú Strings (`plugin_test_btn`, `plugin_diagnostics_expand`) und ViewModel-Logik existieren, aber kein Compose-Button ruft `PluginUiEvent.TestScraper` auf. Halbfertige ARVIO-Funktion (wie Stalker-VOD-UI).
- **WICHTIGE INKONSISTENZ:** `executeTmdbProviderWithDiagnostics` (Test-Pfad) ruft `loadLinks` mit `TmdbLink(...).toJson()` direkt auf (OHNE `load()`), w–ď“ď–í”®hrend `executeTmdbProvider` (echte Suche) erst `api.load({"id":...,"type":...})` aufruft und `extractData()` das `dataUrl`/`episode.data` extrahiert. Mein `loadLinks` ist auf den load()-Pfad ausgelegt (`{"links":[...]}` oder `http`-URL), w–ď“ď–í—ėrde also im Test-Pfad leer laufen. Falls ARVIO den Test-Button irgendwann aktiviert, muss mein `loadLinks` auch TmdbLink-JSON verarbeiten.

### Recherche: Touch-Bug auf Handy/Tablet (ARVIO Issue #502)
**Best–ď“ď–í”®tigt und (teilweise) behoben in ARVIO 1.9.983.** ARVIO Issue #502 "fix(mobile): resolve touch issues and unify button styling in plugins settings":
- Ursache: Plugin-Settings-Screen + Add-Repo-Dialog nutzten TV-only `androidx.tv.material3.Surface`-Buttons, die auf Touch-Ger–ď“ď–í”®ten nicht reagierten.
- Fix: `PluginScreen.kt` hat jetzt `LocalDeviceType.current.isTouchDevice()` –ď—ě–í“∂–í‚Äô separates Mobile-Layout mit touch-friendly Compose-Box-Buttons. **In 1.9.983 enthalten** (verifiziert: `isTouchDevice` existiert in `PluginScreen.kt`).
- Falls der Nutzer noch eine –ď“ď–í”®ltere Version als 1.9.983 hat, sollte er updaten. Der Fix erkl–ď“ď–í”®rt, warum der Nutzer es –ď“ď–í—ėber Cloud-Profil auf dem Handy zum Laufen brachte.

### Recherche: "nur webstreamr-Quellen, nicht Filmpalast" –ď—ě–í“Ė–í‚Äú m–ď“ď–í¬∂gliche Ursachen (Aug 2026)
Da webstreamr (Stremio-Addon, serverseitig) Quellen liefert, mein Filmpalast-Scraper (Cloudstream-DEX) aber nicht, sind die Scraper-Logs n–ď“ď–í¬∂tig. M–ď“ď–í¬∂gliche Ursachen (in absteigender Wahrscheinlichkeit):
1. **Scraper wird aufgerufen, aber `load()` schl–ď“ď–í”®gt fehl** –ď—ě–í“∂–í‚Äô `loadResponse` null –ď—ě–í“∂–í‚Äô `executeTmdbProvider` "both load() paths failed" –ď—ě–í“∂–í‚Äô emptyList. K–ď“ď–í¬∂nnte ein Kotlin-spezifisches Problem sein (Jsoup-Selektor-Unterschied zu Python-Regex, oder Exception in `fetchTmdbMeta`/`searchFilmpalast`).
2. **Scraper ist nicht in `enabledScrapers`** –ď—ě–í“Ė–í‚Äú Plugin-Download fehlgeschlagen, oder `manifestEnabled` false, oder Toggle aus. (Weniger wahrscheinlich, da `status=1` verifiziert und Plugin sichtbar ist.)
3. **`loadLinks` findet Hoster aber `loadExtractor` liefert 0 Links** –ď—ě–í“Ė–í‚Äú Filmpalast rotiert Hostnamen; wenn nur nicht-built-in-Hoster (vidaraa.cc etc.) online, f–ď“ď–í”®llt alles durch. (Mein generischer Fallback f–ď“ď–í”®ngt nur direkte mp4/m3u8.)
- **Ohne Logcat nicht eindeutig trennbar.** Logcat-Filter die helfen: `ExtExtractorRegistry`, `ExternalExtensionRunner`, `PluginManager`, `TmdbProvider Filmpalast`, `ExtExtRunner`.


Selbst bei korrekt aktiviertem Cloudstream-Scraper zeigt ARVIO oft "keine Streaming-Addons eingerichtet". Ursache ist eine ARVIO-seitige Logikl–ď“ď–í—ėcke:
- `StreamRepository.getStreamAddons` (StreamRepository.kt:1440): `if (addon.runtimeKind != RuntimeKind.STREMIO) return@filter false` –ď—ě–í“∂–í‚Äô **nur Stremio-Addons** kommen in die Stream-Auswahl.
- `DetailsViewModel` berechnet `hasStreamingAddons` aus `streamRepository.installedAddons.count { it.isVodStreamingAddon() }` (DetailsViewModel.kt:1633) –ď—ě–í“∂–í‚Äô z–ď“ď–í”®hlt **nur Stremio-Addons**, nicht Cloudstream-Scraper.
- Cloudstream-Scraper sind eine **getrennte Liste** (`PluginManager.scrapers`), nicht in `installedAddons` –ď—ě–í“∂–í‚Äô werden f–ď“ď–í—ėr `hasStreamingAddons` nicht gez–ď“ď–í”®hlt.
- **Aber:** `DetailsViewModel` (DetailsViewModel.kt:1516) ruft `pluginManager.executeScrapersStreaming()` separat auf –ď—ě–í“∂–í‚Äô Cloudstream-Scraper **laufen im Hintergrund** und mergen Streams in `streams`. Nur die *Meldung* ist falsch, nicht das Scraping.
- **Workaround:** Zus–ď“ď–í”®tzlich ein (Dummy-)Stremio-Addon aktivieren –ď—ě–í“∂–í‚Äô `addonCount > 0` –ď—ě–í“∂–í‚Äô `hasStreamingAddons=true` –ď—ě–í“∂–í‚Äô Meldung verschwindet. Scraper-Ergebnisse erscheinen dann in der Liste.
- **ARVIO-seitiger Fix n–ď“ď–í¬∂tig:** `getStreamAddons`/`hasStreamingAddons` sollten auch EXTERNAL_DEX-Scraper z–ď“ď–í”®hlen. Lohnt als GitHub-Issue.

### Build (lokal)
JDK 17+ und Android SDK 35 n–ď“ď–í¬∂tig. Im Env: `JAVA_HOME` + `ANDROID_HOME` (oder `local.properties` mit `sdk.dir`).
```
./gradlew make makePluginsJson
# -> FilmPalast/build/FilmPalast.cs3
# -> build/plugins.json
```

## Schritt-f–ď“ď–í—ėr-Schritt: Diagnose-Log auslesen (v1.2+)

Das Plugin schreibt jeden Schritt des Filmpalast-Scrapers in einen internen Trace und stellt ihn –ď“ď–í—ėber einen lokalen HTTP-Server auf `http://localhost:8420/` bereit. So liest du das Log:

1. **Neues Plugin in ARVIO laden.** ARVIO-Einstellungen –ď—ě–í“∂–í‚Äô Plugins & Extensions –ď—ě–í“∂–í‚Äô Filmpalast aktualisieren/einschalten. Ab v1.2 startet beim Laden des Plugins automatisch der Diagnose-Server (im ARVIO-Prozess, nur loopback).
2. **Quellensuche ausl–ď“ď–í¬∂sen** (das, was bisher leer blieb): –ď“ď–í‚Äďffne in ARVIO z.B. "Matrix" (Film) oder "Silo" (Serie) –ď—ě–í“∂–í‚Äô "nach Quellen suchen". Das triggert ARVIOs Aufruf von `load()`/`loadLinks()` und erzeugt Trace-Eintr–ď“ď–í”®ge.
3. **Log im Handy-Browser ansehen:** –ď“ď–í‚Äďffne einen Browser auf **demselben Ger–ď“ď–í”®t**, auf dem ARVIO l–ď“ď–í”®uft (Chrome/Firefox), und gehe zu `http://localhost:8420/`.
   - Die Seite aktualisiert sich automatisch alle 3 Sekunden.
   - `http://localhost:8420/raw` –ď—ě–í“∂–í‚Äô reiner Text (zum Kopieren).
   - `http://localhost:8420/clear` –ď—ě–í“∂–í‚Äô Trace l–ď“ď–í¬∂schen (vor einer neuen Suche).
4. **Trace lesen / interpretieren:**
   - **Gar kein Trace-Eintrag** nach einer Suche –ď—ě–í“∂–í‚Äô ARVIO ruft den Scraper nicht auf (ARVIO-Seite: `manifestEnabled`/`enabledScrapers`/`supportsType`). Der Diagnose-Server selbst sollte aber beim Plugin-Laden "listening on http://localhost:8420" geloggt haben –ď—ě–í“Ė–í‚Äú taucht das nicht auf, lief das Plugin gar nicht.
   - `load: could not parse TMDB input` –ď—ě–í“∂–í‚Äô ARVIO ruft `load()` mit einem Format auf, das wir nicht erwarten.
   - `fetchTmdbMeta: request threw ...` –ď—ě–í“∂–í‚Äô TMDB-Erreichbarkeit/Key-Problem.
   - `searchFilmpalast: CSS selector matched 0 elements` –ď—ě–í“∂–í‚Äô Filmpalast-Seitenstruktur hat sich ge–ď“ď–í”®ndert (Jsoup-Selektor veraltet) ODER Bot-Schutz/403.
   - `load: after matchResults -> 0 matches` –ď—ě–í“∂–í‚Äô Suche liefert Treffer, aber `matchResults` filtert alle raus (Titel-Normalisierung zu streng).
   - `loadLinks: 0 links -> returning false` –ď—ě–í“∂–í‚Äô `collectHosterLinks` findet nichts (Selektor/`data-player-url`-Attribut ge–ď“ď–í”®ndert).
   - `loadExtractor('...') -> matched=false` f–ď“ď–í—ėr ALLE Links –ď—ě–í“∂–í‚Äô keine built-in Extractoren f–ď“ď–í—ėr die aktuellen Hoster-Domains.
5. **Log f–ď“ď–í—ėr mich aufheben:** Entweder den `/raw`-Text kopieren und in der n–ď“ď–í”®chsten Session einf–ď“ď–í—ėgen, ODER die gespiegelte Datei `Android/data/com.arflix.tv/files/arvio-addon-logs/filmpalast-trace.log` (ab Android 13 evtl. nur –ď“ď–í—ėber ADB erreichbar).
6. **Falls der Browser die Seite nicht l–ď“ď–í”®dt:** Server l–ď“ď–í”®uft nur, solange der ARVIO-Prozess lebt. ARVIO zwischendrin nicht beenden. Alternativ via ADB: `adb forward tcp:8420 tcp:8420` dann am PC `curl http://localhost:8420/raw`.

## Versionshistorie dieses Addons

- **v1 (Proof-of-Concept):** Filmpalast-Plugin als TmdbProvider. Baut & kompiliert. Noch nicht in ARVIO endgeraet-getestet.
- **v1.1 (Aug 2026, Commits b6e3c1b bis 8aa09d3):** Hoster-Extraktion gefixt (loadLinks respektiert loadExtractor-Return; Voe1 entfernt; generischer Fallback fuer unbekannte Hostnamen); endgeraet-getestet in ARVIO 1.9.983 (sideload) von Nutzer. Plugin laedt, ist sichtbar & aktivierbar. **Aber:** bei Quellensuche (Matrix/Silo) zeigt ARVIO nur webstreamr-Quellen, nicht Filmpalast - Root-Cause offen, Logcat vom Geraet noetig (siehe "AKTUELLER STAND" ganz oben). AGENTS.md umfassend mit ARVIO-Scraper-Pfad, Touch-Bug-Fix #502, Test-Funktion-Status und Logcat-Optionen dokumentiert.
- **v1.2 (13.08.2026):** Selbst-Diagnose-Modus statt Logcat. `DebugLog.kt` + `DebugServer.kt` (lokaler HTTP-Server `localhost:8420`), `FilmpalastProvider` vollst–ď“ď–í”®ndig instrumentiert, Version auf 2 gebumpt. Ersetzt Logcat-Zugang fuer unseren eigenen Scraper-Code. Siehe "Schritt-fuer-Schritt: Diagnose-Log auslesen".
- **v1.3 (13.08.2026, Commits bis ca9f81f):** Diagnose-Tooling massiv ausgebaut, aber **Kernerkenntnis: ARVIO ruft .cs3-Plugins auf dem Geraet GAR NICHT auf.** Beweise: (a) GermanProviders (bewaehrtes .cs3-Repo) liefert auf dem Geraet ebenfalls 0 Quellen, (b) unsere v6-v8 haetten bei JEDEM loadLinks-Aufruf ArvioAddon-Debug-Quellen emittieren muessen - erschienen nie, (c) GitHub-Issues #459/#273 berichten exakt dasselbe Symptom. Webstreamr (Stremio-Addon) funktioniert = anderer ARVIO-Code-Pfad. Versionen: v3 DebugServer auf 127.0.0.1; v4 File-Trace+PLUGIN_LOADED Marker; v5 MediaStore->public Download; v6 Diagnose als Pseudo-Quellen in ARVIO-Quellenauswahl; v7 load() gibt nie null zurueck (debugLoadResponse) damit loadLinks garantiert laeuft; v8 Per-Call-Netzwerk-Timeouts. ARVIO library (TmdbProvider/MainAPI/Plugin) verifiziert vorhanden in classes3/4.dex. ARVIO-Timeouts (120s/60s) schliessen Timeout als Ursache aus. **Naechster Schritt: mit Laptop weiter (Logcat via USB+adb); ggf. GitHub-Issue bei ARVIO.** Siehe "AKTUELLER STAND" ganz oben.
- **v9-v13 (14.08.2026, Logcat-Aera):** Nach USB-ADB+Logcat am TV: Erkenntnis #1 (.cs3 nie heruntergeladen bei Cloud-Sync) ‚Üí Erkenntnis #2 (kotlin/io/FilesKt von R8 geshrinkt) ‚Üí FIX #2 (v9: kotlin-stdlib-IO entfernt) ‚Üí Erkenntnis #3 (DebugServer-Thread-Crash) ‚Üí FIX #3 (v10: DebugServer removed) ‚Üí Erkenntnis #4 (kotlin.collections.SetsKt von R8 geshrinkt) ‚Üí FIX #4 (v11: kotlin-stdlib in .cs3 gebundled) ‚Üí Erkenntnis #5 (mainPageOf von R8 geshrinkt) ‚Üí FIX #5 (v12: listOf(MainPageData)) ‚Üí Erkenntnis #6 (MainPageData-ctor von R8 geshrumpft) ‚Üí FIX #6 (v13: mainPage komplett entfernt). v13 laedt erstmals VOLLSTAENDIG (Provider+Extractoren registriert, "API loaded" best√§tigt).
- **Erkenntnis #7 (14.08.2026, v13-DEX+APK-Analyse):** **Root-Cause gefunden.** ARVIOs R8 hat `kotlin.coroutines.Continuation` zu `j7.d` obfuscated. Unsere suspend-Override-Methoden (load/loadLinks/search) haben `Lkotlin/coroutines/Continuation;` in der Signatur, ARVIOs Parent hat `Lj7/d;` ‚Üí JVM findet Override nicht ‚Üí parent laeuft ‚Üí `ErrorLoadingException: No id found` ‚Üí 0 Quellen. **Betrifft ALLE externen .cs3-Plugins.** Geplanter Fix #7: gegen ARVIOs obfuscated cloudstream3-JAR kompilieren (dex2jar aus APK extrahieren). Siehe "ENTSCHEIDENDE ERKENNTNIS #7" oben.
- **v14 (14.08.2026, Commit 829c057):** **Post-Build DEX-Patching fuer R8-obfuszierte Typen (Fix #7).** Ansatz 1 (gegen obfuszierte dex2jar-JAR kompilieren) wurde verwendet; Override-Signaturen korrekt obfusziert (load=(Ljava/lang/String;Lj7/d;)...). v14 live auf builds (1.268.540 bytes).
- **Erkenntnis #8 + v15 (14.08.2026):** v14-TV-Test (arvio-tv-log-v14.txt) zeigte: DEX ist KAPUTT — ART-Verifier lehnt ab ("Failure to verify dex file: Non-zero padding b before section of type 8196 at offset 0x3111d2"). Root-Cause: dex2jar-Klassen (j7/d, j7/j, x7/l) wurden mit in die DEX gebuendelt und korrumpten deren Struktur. **Fix #8 (v15):** zurueck zum unobfuszierten Stub (keine dex2jar-Klassen -> valide DEX) + Post-Build-DEX-Patching (4 Typ-Strings). DexClassLoader parent-first-Delegation loest j7/d auf ARVIOs eigene Klasse -> Override-Deskriptoren matchen -> Dispatch bindet. CI baut v15 beim Push auf main. **Test auf TCL C7K TV ausstehend** (Windows 10 Anleitung: `docs/windows-10-test-guide.md`, Log-Datei `arvio-tv-log-v16.txt`).