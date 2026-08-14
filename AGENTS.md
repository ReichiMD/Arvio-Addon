# AGENTS.md ГўВҖВ“ Ventix Arvio Addon

Dieses Repo baut ein **Cloudstream3-kompatibles Plugin** fГғВјr die **ARVIO** Android-TV-App (sideload-APK).
Ziel: Ventix-FunktionalitГғВӨt (deutsche Web-Scraper + Stalker-VOD) als Plugin in ARVIO laufen lassen ГўВҖВ“ clientseitig, ohne Server.

## ГўВӯВҗ AKTUELLER STAND & NГғВ„CHSTE SCHRITTE (Stand 14.08.2026 ГўВҖВ“ LOGCAT-ERKENNTNIS)

### ENTSCHEIDENDE ERKENNTNIS (14.08.2026, Logcat via USB+adb auf Pixel 7): Die .cs3-Dateien werden NIE heruntergeladen
**Root-Cause gefunden und verifiziert im Logcat + ARVIO-Source.** Das Problem ist NICHT, dass ARVIO den Scraper nicht aufruft ГўВҖВ“ ARVIO ruft ihn auf, findet aber keine Datei.

**Logcat-Befund (arvio-log.txt, arvio-log2.txt, Pixel 7, ARVIO 1.9.983 sideload):**
```
D PluginManager: Streaming execution of 18 scrapers for movie:603   <-- Scraper-Pfad wird durchlaufen (Matrix, TMDB 603) ГўВңВ“
D PluginManager: Executing DEX scraper: FilmPalast                    <-- ARVIO will jeden Scraper ausfГғВјhren ГўВңВ“
E ExtExtensionLoader: DEX file not found for <repoId>:FilmPalast: /data/user/0/com.arvio.tv/files/cs_extensions/<repoId>_FilmPalast.cs3  ГўВңВ— DATEI FEHLT
E ExtExtensionRunner: No API loaded for scraper: <repoId>:FilmPalast  ГўВңВ—
D PluginManager: DEX scraper FilmPalast returned 0 results             ГўВңВ—
```
- Passiert bei ALLEN 18 Scrapern (FilmPalast, HDFilme, Kinoger, ARD, Discovery, Arte, KinoKing, EinschaltenIn, HuhuTo, PlutoTV, Megakino, Serienstream, Netzkino, SpiegelTV, Moflix, Xcine, Welt) ГўВҖВ“ unserem UND GermanProviders. BestГғВӨtigt: ARVIO-seitiges Problem.
- `ExtExtensionLoader: ensureExtractorsLoaded: scanned 0 .cs3 files, registered 0 extractors` ГўВҶВ’ `cs_extensions`-Ordner komplett LEER.
- WebStreamr (Stremio-Addon) funktioniert (3 streams, 757ms/322ms) ГўВҶВ’ Stremio-Pfad lГғВӨuft, nur .cs3-Pfad kaputt.
- **KEIN einziger Download-Versuch im Log.** Kein "Downloading", keine HTTP-Request zu raw.githubusercontent.com, kein "Failed to download extension: HTTP ...", kein "Downloaded extension". ARVIO hat die Scraper-Metadaten (Name/ID/URL aus plugins.json) in der Datenbank, aber die .cs3-Datei nie heruntergeladen.

**Warum der Download nie stattfindet (verifiziert im ARVIO-Source @ v1.9.983):**
- `PluginManager.addRepository()` (PluginManager.kt:426) ruft `downloadDexExtensions(repo.id, parseResult.plugins)` auf ГўВҶВ’ das lГғВӨdt die .cs3-Dateien herunter (parallel via `downloadExtension`).
- **ABER: Der Nutzer fГғВјgt Repos via Cloud-Sync hinzu, nicht via Add-Repository-Dialog!** `CloudSyncRepository.applyCloudPayload()` (CloudSyncRepository.kt:1721-1731) macht beim Restore nur:
  - `pluginDataStore.saveRepositories(repos)` (nur Metadaten in DB)
  - `pluginDataStore.saveScrapers(scrapers)` (nur Metadaten in DB ГўВҖВ“ inkl. URL, aber KEIN Download!)
  - `pluginDataStore.setPluginsEnabled(...)` (global an)
  - **KEIN Aufruf von `downloadDexExtensions`!** Cloud-Sync synchronisiert Scraper-Metadaten, aber NICHT die .cs3-Dateien.
- Folge: Scraper erscheint in der Liste (Metadaten da), Toggle speichert (manifestEnabled=true, status=1), ARVIO versucht AusfГғВјhrung ("Executing DEX scraper") ГўВҖВ“ aber `cs_extensions/` ist leer ГўВҶВ’ "DEX file not found" ГўВҶВ’ "No API loaded" ГўВҶВ’ 0 results.
- Das erklГғВӨrt, warum es auf TV UND Handy identisch ist: Cloud-Sync kopiert nur Metadaten, die .cs3-Downloads werden pro-GerГғВӨt nur bei direktem `addRepository`/`refreshExternalRepository` getriggert ГўВҖВ“ und der Nutzer hat (vermutlich wegen des Touch-Bugs frГғВјher) alles ГғВјber Cloud-Profil gemacht, nie direkt auf dem GerГғВӨt.

**NГғВ„CHSTER SCHRITT (Prio 1): Repo auf dem GerГғВӨt DIREKT hinzufГғВјgen (nicht Cloud-Sync), dabei Logcat mitlaufen lassen**
Ziel: sehen, ob `downloadDexExtensions`ГўВҶВ’`downloadExtension` ГғВјberhaupt aufgerufen wird und ob der Download fehlschlГғВӨgt (HTTP 404/403/Timeout) oder ob ARVIO den Download gar nicht erst triggert.
1. `adb logcat -c` (Puffer leeren).
2. Am Pixel 7 in ARVIO: Repos LГғВ–SCHEN (beide: Arvio-Addon + GermanProviders).
3. Am Pixel 7 in ARVIO: **Add Repository** DIREKT auf dem GerГғВӨt ГўВҶВ’ Repo-URL eingeben (`https://raw.githubusercontent.com/ReichiMD/Arvio-Addon/main/repo.json`) ГўВҶВ’ hinzufГғВјgen. WICHTIG: nicht ГғВјber Cloud-Sync/Profil, sondern direkt den Add-Repo-Dialog auf dem GerГғВӨt nutzen.
4. Warten, bis ARVIO "Repository hinzugefГғВјgt" meldet (sollte .cs3 downloaden).
5. Scraper einschalten.
6. `adb logcat -d | grep -iE "download|ExtExtension|PluginManager|cs3|HTTP|Failed|extension"` ГўВҶВ’ Output kopieren. Worauf achten:
   - `Downloaded extension <id>: <bytes> bytes -> ...` ГўВҶВ’ Download ERFOLGREICH (Problem gelГғВ¶st!).
   - `Failed to download extension <id>: HTTP 404` / `HTTP 403` ГўВҶВ’ URL falsch/blocked (unsere plugins.json URL prГғВјfen).
   - `Error downloading extension <id>: ...` ГўВҶВ’ Exception (Netzwerk/SSL/Timeout).
   - Gar kein Download-Log ГўВҶВ’ `addRepository` wird nicht wie erwartet durchlaufen (Routing-Problem).
7. Falls Download klappt: Quellensuche (Matrix) auslГғВ¶sen ГўВҶВ’ prГғВјfen, ob jetzt Filmpalast-Quellen kommen.
8. Falls Download fehlschlГғВӨgt: unsere `plugins.json`/`.cs3`-URL im builds-Branch prГғВјfen (raw.githubusercontent.com erreichbar? Datei da? status=1?).

**Prio 2 (danach): Am TV dasselbe** ГўВҖВ“ TV per USB ans Laptop, gleicher `adb logcat`-Flow. Da der TV das primГғВӨre ZielgerГғВӨt ist, muss der Download dort auch direkt (Add Repository) getriggert werden, nicht ГғВјber Cloud-Sync. LADB-App scheiterte am Pairing (siehe unten)ГўВҶВ’ TV braucht USB-Verbindung zum Laptop (dazu ggf. lГғВӨngeres USB-Kabel am TV oder USB-Port am TV nutzen).

**Prio 3: GitHub-Issue bei ARVIO** ГўВҖВ“ Cloud-Sync-Restore lГғВӨdt .cs3-Dateien nicht herunter (`saveScrapers` ohne `downloadDexExtensions`). Das ist ein klarer ARVIO-Bug: Wer Plugins via Cloud-Sync auf ein neues GerГғВӨt ГғВјbernimmt, hat leere Scraper. Skizze siehe unten (Prio 2 im alten Stand). AI-Disclosure beachten.

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

### FIX #4 (14.08.2026, v11): kotlin-stdlib IN die .cs3-DEX bündeln
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
**Wichtige Erkenntnis:** `mainPage`/`hasMainPage`/`getMainPage` werden **NUR von der Cloudstream3-App-Startseite** genutzt. ARVIOs Scraper-Pfad (`executeTmdbProvider`) ruft nur `load()` + `loadLinks()` auf — `getMainPage()` wird **nie** aufgerufen. Also ist das alles **toter Code fuer ARVIO**, der nur R8-Strip-Fehlerpunkte schafft.

### FIX #6 (14.08.2026, v13): mainPage/getMainPage komplett entfernt
`hasMainPage`, `mainPage`, `getMainPage()` **komplett geloescht** — keine MainPageData-Konstruktion mehr. Provider ueberschreibt jetzt nur noch, was ARVIO wirklich aufruft: `search()`, `load()`, `loadLinks()`. Das minimiert die R8-geshrinkte cloudstream3-API-Oberflaeche auf das, was ARVIO selbst nutzt.
- Version auf 13 gebumpt. CI gruen. builds-Branch: FilmPalast.cs3 v13 (1268533 Bytes, status=1).
- **Erwartung v13-Test:** plugin.load() durchlaufen OHNE NoSuchMethodError (keine MainPageData mehr). "API loaded" / "Executing DEX scraper: FilmPalast" -> load() laeuft. Falls naechster R8-Strip (z.B. newMovieLoadResponse/newTvSeriesLoadResponse/loadExtractor): jeweilige Methode notieren -> retained-Alternative oder direkten Konstruktor verwenden.

### ENTSCHEIDENDE ERKENNTNIS #7 (14.08.2026, v13-DEX-Analyse + ARVIO-APK-Analyse): R8 hat kotlin.coroutines.Continuation obfuscated — suspend-Overrides funtionieren NICHT
**Root-Cause fuer "load() override wird nicht aufgerufen" gefunden und verifiziert durch DEX-Bytecode-Analyse der ARVIO-APK.**

v13 laedt erfolgreich (Provider registriert, Extractoren registriert, "API loaded" bestätigt in log6). Aber ARVIO ruft bei `api.load(loadJson)` die **PARENT** `TmdbProvider.load()` auf, nicht unseren Override. Das Ergebnis: `ErrorLoadingException: No id found` (parent parst JSON nicht), dann Fallback-URL `themoviedb.org/movie/603` (parent parst URL, ruft `loadFromTmdb` auf, aber wir haben das nicht ueberschrieben), dann `both load() paths failed` → 0 Quellen.

**Warum der Override nicht bindet (verifiziert im ARVIO-APK-Bytecode):**
- ARVIOs R8 (full mode, `isMinifyEnabled=true`) hat **kotlin.coroutines.Continuation zu `j7.d` obfuscated** und **kotlin.jvm.functions.Function1 zu `x7.l`**.
- Die `-keep class com.lagradost.** { *; }`-Regel behaelt cloudstream3-Klassennamen + Methodennamen, aber R8 obfuscated die **Parameter-TYPEN** in Methodensignaturen unabhaengig davon.
- ARVIOs `MainAPI.load()` in der kompilierten APK hat Signatur: `load(Ljava/lang/String;Lj7/d;)Ljava/lang/Object;` (j7.d = obfuscated Continuation).
- Unser `FilmpalastProvider.load()` in der .cs3-DEX hat Signatur: `load(Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;` (unobfuscated, weil wir gegen `pre-release`-Stub kompilieren).
- **Lj7/d; != Lkotlin/coroutines/Continuation;** → JVM findet unseren Override nicht → virtual dispatch faellt auf parent zurueck → parent laeuft.
- **Dasselbe gilt fuer `loadLinks` und `search`:** alle suspend-Methoden haben Continuation-Parameter → alle Overrides sind broken.
- ARVIOs `executeTmdbLoadLinks$completed$1` ruft auf: `invoke-virtual MainAPI->loadLinks(Ljava/lang/String;ZLx7/l;Lx7/l;Lj7/d;)Ljava/lang/Object;` (x7.l = obfuscated Function1, j7.d = obfuscated Continuation).
- Unsere `loadLinks` hat: `(Ljava/lang/String;ZLkotlin/jvm/functions/Function1;Lkotlin/jvm/functions/Function1;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;` → mismatch.

**Verifizierte Obfuscation-Map (aus ARVIO-APK extrahiert, classes4.dex/classes5.dex):**
| Original-Typ | Obfuscated | Wo gefunden |
|---|---|---|
| `kotlin.coroutines.Continuation` | `j7.d` | MainAPI.load/loadLinks/search Signaturen; interface mit getContext():j7.j + resumeWith(Object):V |
| `kotlin.coroutines.CoroutineContext` | `j7.j` | j7.d.getContext() return type |
| `kotlin.jvm.functions.Function1` | `x7.l` | MainAPI.loadLinks callback-Parameter; interface mit invoke(Object):Object, extends d7.o |
| `kotlin.jvm.functions.Function` | `d7.o` | x7.l's super-interface |

**Nicht-obfuscated (durch `-keep` geschuetzt oder primitiv):**
- `com.lagradost.cloudstream3.**` (Klassen + Methoden-Namen; `-keep class com.lagradost.** { *; }`)
- `loadFromTmdb(I)Lcom/lagradost/cloudstream3/LoadResponse;` (int-Parameter = primitiv, LoadResponse = kept) **→ dieser Override wuerde funktionieren!**
- `loadFromImdb(Ljava/lang/String;)Lcom/lagradost/cloudstream3/LoadResponse;` (String + LoadResponse = both unobfuscated) **→ wuerde auch funktionieren!**

**WARUM das ALLE .cs3-Plugins in ARVIO betrifft (nicht nur unseres):**
- Jedes Cloudstream3-Plugin, das gegen den unobfuscated cloudstream3-Stub (GitHub `pre-release`/`v4.7.0`) kompiliert, hat unobfuscated Continuation/Function1 in seinen Override-Signaturen.
- ARVIOs R8-obfuscated Runtime hat j7.d/x7.l → **KEIN** externes .cs3-Plugin kann suspend-Methoden (load/loadLinks/search/getMainPage) korrekt ueberschreiben.
- Das erklaert endgueltig, warum GermanProviders (bewaehrtes Repo) in ARVIO auch 0 Quellen liefert, und warum GitHub-Issues #459/#273 dasselbe Symptom berichten.
- **ARVIOs EIGENE eingebaute Provider** sind davon nicht betroffen, weil sie im selben APK kompiliert wurden (gleiche obfuscated Typen).

### GEPLANTER FIX #7: Gegen ARVIOs obfuscated cloudstream3 kompilieren
**Ansatz:** ARVIOs cloudstream3-Klassen aus der APK extrahieren (dex2jar) und als `compileOnly`-Dependency verwenden. Der Kotlin-Compiler liest die `@kotlin.Metadata`-Annotation auf MainAPI/TmdbProvider (vorhanden → verifiziert), die die obfuscated JVM-Signaturen enthaelt. Der Compiler generiert dann unsere Overrides mit `j7.d`/`x7.l` als Parameter-Typen → Signaturen matchen → virtual dispatch funktioniert.

**Schritte:**
1. ARVIO-sideload-APK herunterladen (`ARVIO-v1.9.983-sideload-release.apk`, 135MB).
2. `classes4.dex` extrahieren (enthält cloudstream3: MainAPI, TmdbProvider, LoadResponse etc.).
3. Mit dex2jar (`d2j-dex2jar.sh classes4.dex -o arvio-cloudstream3.jar`) zu JAR konvertieren.
4. JAR als `compileOnly`-Dependency im `FilmPalast/build.gradle.kts` einbinden (statt `pre-release`-Stub).
5. Kotlin-Compiler liest @Metadata → generiert Overrides mit obfuscated Typen.
6. `.cs3` bauen, deployen, am TV testen.
7. **Risiko:** Obfuscation-Map ändert sich bei jedem ARVIO-Release → JAR muss pro ARVIO-Version aktualisiert werden. Aber ARVIO v1.9.983 ist die aktuelle Version, und der Nutzer hat sie.

**Alternative falls Fix #7 nicht funktioniert (DEX-Patching):**
Falls der Kotlin-Compiler die @Metadata-JVM-Signaturen NICHT respektiert (immer noch `kotlin.coroutines.Continuation` generiert), muessten wir die .cs3-DEX post-processen: in den proto_id/method_id-Items unserer load/loadLinks/search-Methoden den Parameter-Typ von `Lkotlin/coroutines/Continuation;` auf `Lj7/d;` (und Function1 auf `Lx7/l;`) patchen. Komplex aber machbar (DEX-Binary-Patching der type_id-Referenzen).

**Alternative falls beides nicht funktioniert (loadFromTmdb-Ansatz):**
Da `loadFromTmdb(Int): LoadResponse?` eine **nicht-suspend**-Methode ist (kein Continuation-Parameter) und ihre Signatur in ARVIO unobfuscated ist (`loadFromTmdb(I)Lcom/lagradost/cloudstream3/LoadResponse;`), wuerde ein Override hier **funktionieren**. ARVIOs `executeTmdbProvider` ruft `api.load(tmdbUrl)` auf → parent parst URL → extrahiert ID → ruft `loadFromTmdb(id)` auf → unser Override wuerde aufgerufen. **Aber:** `loadLinks` ist suspend → bleibt broken. Nur fuer den Load-Teil nutzbar, nicht fuer Link-Resolution.

### NEUE NÄCHSTE SCHRITTE (Stand 14.08.2026, nach Erkenntnis #7)
**Prio 1 - Fix #7 implementieren (gegen obfuscated cloudstream3 kompilieren):**
1. ARVIO-APK → classes4.dex → dex2jar → `arvio-cloudstream3.jar`.
2. In `FilmPalast/build.gradle.kts` als `compileOnly` einbinden (ohne `pre-release`).
3. Build testen: pruefen ob DEX-Output `j7.d` in load()-Signatur hat (androguard-Check).
4. Falls ja: `.cs3` deployen, am TV testen.
5. Falls nein: DEX-Patching-Ansatz evaluieren.

**Prio 2 - v14 am TV testen (mit WLAN-ADB + Logcat):**
1. Fix #7 als v14 bauen + deployen (builds-Branch).
2. Am TV: Repo loeschen + neu hinzufuegen (direkt, NICHT Cloud-Sync).
3. `adb logcat -c`, Scraper einschalten, Matrix suchen, 15s warten.
4. `adb logcat -d | grep -iE "Filmpalast|ExtExtension|ArvioAddon|load|Tmdb|link|result"` pruefen:
   - ArvioAddon-Debug-Trace-Zeilen sichtbar? (load() wird jetzt aufgerufen)
   - loadLinks-Ergebnisse? (ExtractorLink-Ausgabe)
   - "0 links collected" → naechste Ebene (Jsoup-Selektoren/Hoster).

**Prio 3 - GitHub-Issue bei ARVIO (drei klare Bugs):**
1. **R8 obfuscated kotlin.coroutines.Continuation → externe .cs3-Plugins koennen suspend-Overrides nicht binden.** Haupt-Bug. Skizze: "R8 full-mode obfuscates kotlin.coroutines.Continuation (→j7.d) and kotlin.jvm.functions.Function1 (→x7.l) despite -keep rules for cloudstream3 classes. External .cs3 plugins compiled against the public cloudstream3 stub use unobfuscated type names, so their load()/loadLinks()/search() override method descriptors don't match the obfuscated parent → virtual dispatch always calls the parent TmdbProvider/MainAPI implementation instead of the plugin's override. ALL external .cs3 plugins are affected (GermanProviders, custom TmdbProviders). Fix: either keep kotlin.coroutines.Continuation and kotlin.jvm.functions.* unobfuscated in proguard-rules.pro, or provide a mapping file." Verweis auf #459/#273.
2. Cloud-Sync-Restore laedt .cs3-Dateien nicht herunter (siehe Erkenntnis #1).
3. Touch-Bug im Add-Repo-Dialog auf Handy (trotz #502-Fix in 1.9.983).
AI-Disclosure-Pflicht bei Issue/Kommentar: "created by an AI agent (OpenHands) on behalf of [user]" einfuegen.

### ADB-over-WLAN beim TCL C7K (verifizierter Weg, Session 14.08.)
USB-Kabel-ADB funktioniert bei TVs praktisch nie (TV-USB-Buchsen sind Host-Modus fuer Sticks, nicht Client fuer ADB). Weg = WLAN-ADB.
Vorgehen: Entwickleroptionen aktivieren (Build 7x OK) -> USB-Debugging AN -> Network/Wireless Debugging AN -> TV-IP aus Netzwerk-Einstellungen notieren -> `adb connect <TV-IP>:5555` (direkt) ODER `adb pair <IP>:<port>` + 6-stelliger Code, dann `adb connect <IP>:5555` (Android 13+ Pairing-Flow). TV & Laptop im selben WLAN.
TV-Logcat (arvio-tv-log.txt) bestaetigte: Download + Linkage-Fehler sichtbar -> WLAN-ADB am TCL C7K funktioniert.

### Handy-UI-Bug (Pixel 7, 14.08.)
Auf dem Pixel 7 laesst sich nach URL-Eingabe im Add-Repo-Dialog weder Abbrechen noch Bestaetigen tippen (Touch-Bug, trotz ARVIO #502-Fix in 1.9.983). Daher: Diagnose komplett am TV machen. Handy nur, falls TV-ADB nicht klappen wuerde (was aber nun klappt).

### ГғВңBERSCHRIEBENER ALTER STAND (13.08.2026 ГўВҖВ“ vor Logcat, als Referenz behalten)
FrГғВјhere Annahme war "ARVIO ruft .cs3-Plugins GAR NICHT auf". **KORRIGIERT durch Logcat:** ARVIO ruft sie sehr wohl auf ("Executing DEX scraper"), aber die .cs3-Dateien fehlen auf dem GerГғВӨt ("DEX file not found"), weil Cloud-Sync sie nie herunterlГғВӨdt. Die Diagnose-Plugins v6ГўВҖВ“v8 erschienen deshalb nie ГўВҖВ“ nicht weil ARVIO die Klasse nicht instanziiert, sondern weil es gar keine Datei zum Laden gibt. Der Rest der alten Beweislage (GermanProviders ebenfalls leer, WebStreamr funktioniert, GitHub-Issues #459/#273) bleibt gГғВјltig und wird durch den neuen Befund ergГғВӨnzt (Cloud-Sync-Problem erklГғВӨrt, warum es bei Nutzern auftritt, die Profil-basiert syncen).

**Beweislage (verifiziert, Stand 13.08.2026):**
- Nutzer hat ARVIO 1.9.983 **sideload** auf Android-TV. Plugin-Bereich sichtbar (ГўВҶВ’ sideload bestГғВӨtigt). Toggle bei Filmpalast AN, global alles aktiviert.
- Bei Quellensuche (Matrix, mehrere Filme & Serien) zeigt ARVIO **nur webstreamr-Quellen (Stremio-Addon), NIEMALS Filmpalast** ГўВҖВ“ ГғВјber alle Plugin-Versionen v2ГўВҖВ“v8 hinweg, ГғВјber mehrere Neu-Installationen hinweg (Scraper-IDs ГғВӨnderten sich jeweils: eOf699f8ГўВҶВ’2421c4b6ГўВҶВ’neu ГўВҶВ’ bestГғВӨtigt frischer Download).
- **GermanProviders-Test (Bnyro/GermanProviders):** Nutzer installierte das bewГғВӨhrte, anderswo funktionierende `.cs3`-Repo, aktivierte alle Scraper ГўВҶВ’ **auch dort KEINE Streams**. Das beweist: Es ist **NICHT unser Plugin**, sondern ARVIOs Cloudstream-`.cs3`-Pfad liefert auf dem GerГғВӨt bei **jedem** Plugin nichts. Webstreamr funktioniert, weil es ein **Stremio-Addon** (vГғВ¶llig anderer ARVIO-Code-Pfad) ist.
- **GitHub-Issue-Recherche** (`ProdigyV21/ARVIO`): Andere Nutzer berichten **exakt dasselbe Symptom** ГўВҖВ“ Plugin installiert, in Liste sichtbar, Toggle an, aber keine Quellen:
  - **#459** "Nuvio JS scraper repository installs but returns no sources" (closed, ohne ГғВ¶ffentliche LГғВ¶sung)
  - **#273** "I'm able to add nuvio plugin but not showing any video links" (closed; Dev @Himanth-reddy: "it should be working")
  - **#500** "unable to install the plugin" (open)
  - **#491** "plugins & extensions section shows addons not plugins" (gelГғВ¶st ГўВҶВ’ "next update")
  - v1.9.983-Changelog: "Added compatibility for **Nuvio-style JavaScript** scraper plugins" + "Fixed sideload **production-plugin routing**, extractor unloading, mobile routing". ГўВҶВ’ DEX/`.cs3`-Pfad wurde gerade erst angefasst und lГғВӨuft offensichtlich **nicht zuverlГғВӨssig**.
- **Library verifiziert vorhanden:** ARVIOs APK (`classes3.dex`/`classes4.dex`) enthГғВӨlt `com/lagradost/cloudstream3/metaproviders/TmdbProvider`, `MainAPI`, `plugins/Plugin`. Die Library fehlt also nicht.
- **ARVIO-Timeouts verifiziert:** `SCRAPER_TIMEOUT_MS=120_000`, `LOADLINKS_TIMEOUT_MS=60_000`, `EXECUTION_TIMEOUT_MS=120_000`. Unsere Per-Call-Timeouts (8s) sind weit drunter ГўВҶВ’ kann nicht Ursache sein.

### Warum die In-Plugin-Diagnose (v6ГўВҖВ“v8) trotzdem leer blieb
v6ГўВҖВ“v8 sind so gebaut, dass **sobald ARVIO `loadLinks()` auch nur einmal aufruft**, die Diagnose als Pseudo-Quellen in ARVIOs Quellenauswahl erscheinen MГғВңSSEN (`emitTraceAsSources` + В„PLUGIN vN loaded"-Banner + `load()` gibt nie `null` zurГғВјck + Per-Call-Netzwerk-Timeouts). Da **keine einzige** ArvioAddon-Debug-Quelle erschien, lГғВӨuft unser Code **nie** ГўВҶВ’ ARVIO instanziiert unsere Plugin-Klasse nicht (oder verwirft sie still). Das ist exakt die Fehlerklasse, die **nur im Logcat** sichtbar wird ("No API loaded for scraper", "MISSING CLASS", "plugin.load() linkage error", "No @CloudstreamPlugin class found").

### ГўВҡ ГҜВёВҸ LIMITS EINES DIAGNOSE-PLUGINS (Antwort auf die Frage "kГғВ¶nnen wir das Log ГғВјber ein Plugin bekommen?")
**Teilweise ja, aber nicht fГғВјr das aktuelle Problem.** Ein Plugin kann sich selbst protokollieren und das sogar in ARVIO als Quellen sichtbar machen (gebaut in v6ГўВҖВ“v8). **Aber** das funktioniert nur, **sobald ARVIO den Plugin-Code lГғВӨdt und aufruft**. Genau da hakt es: ARVIO lГғВӨdt/instanziiert die `.cs3`-Klasse auf dem GerГғВӨt nicht. FГғВјr "lГғВӨdt ARVIO mein Plugin ГғВјberhaupt?" gibt es **kein plugin-basiertes Werkzeug** ГўВҖВ“ dafГғВјr braucht man ARVIOs eigene Logs (Logcat). Datei-/MediaStore-/HTTP-Server-AnsГғВӨtze (v3ГўВҖВ“v5) scheiterten ebenfalls, weil unser Code nie lГғВӨuft (keine Datei wird erzeugt).

### NГғВ„CHSTER SCHRITT (Prio 1, VORAB gemacht mit Nutzer abgesprochen): MIT LAPTOP / PC WEITERMACHEN
Nutzer kommt nГғВӨchste Session **mit Laptop**. Dann ist **Logcat via USB+adb** mГғВ¶glich (die einzig zuverlГғВӨssige Methode; LADB-App auf dem GerГғВӨt scheiterte am Pairing). Konkrete Schritte fГғВјr die nГғВӨchste Session:
1. Laptop: Android platform-tools (Mini-SDK, ~10 MB, keine Installation) von https://developer.android.com/tools/releases/platform-tools laden, entpacken.
2. GerГғВӨt per USB an den Laptop, im GerГғВӨt "USB-Debugging erlauben" bestГғВӨtigen.
3. Im platform-tools-Ordner Terminal ГғВ¶ffnen (Adressleiste `cmd` + Enter).
4. `adb logcat -c` (Buffer leeren).
5. In ARVIO: Filmpalast aus/an + Quellensuche auslГғВ¶sen (z.B. Matrix). 15 s warten.
6. `adb logcat -d | grep -iE "ExtExt|ExternalExtension|PluginManager|Filmpalast|ArvioAddon|No API loaded|MISSING CLASS|CloudstreamPlugin|linkage error"` ГўВҶВ’ Output kopieren.
7. **Was gesucht wird (entscheidend):**
   - `No API loaded for scraper: <id>` ГўВҶВ’ ARVIO konnte keine MainAPI instanziieren (Klassen-Fehler).
   - `No @CloudstreamPlugin class found in <id>` ГўВҶВ’ unsere Plugin-Klasse wurde nicht gefunden.
   - `plugin.load() linkage error` / `MISSING CLASS: ...` ГўВҶВ’ eine Referenz lГғВӨsst sich zur Laufzeit nicht auflГғВ¶sen.
   - `TmdbProvider Filmpalast: both load() paths failed` / `0 links collected` ГўВҶВ’ Scraper lГғВӨuft, aber load/loadLinks scheitert.
   - ГғВңberhaupt kein `Filmpalast`/`ExtExt`-Eintrag ГўВҶВ’ Scraper wird ГғВјberhaupt nicht aufgerufen (Enable-/Routing-Problem).
- Je nach Befund: load()-Fehler ГўВҶВ’ Jsoup-Selektoren/Logging fixen; Scraper nicht aufgerufen ГўВҶВ’ Download/DexClassLoader/manifestEnabled prГғВјfen.

### NГғВ„CHSTER SCHRITT (Prio 2): GitHub-Issue bei ARVIO ГғВ¶ffnen (parallel zu Prio 1)
Da der GermanProviders-Test beweist, dass es ein ARVIO-seitiges Problem mit dem `.cs3`-Pfad ist (nicht unseres), lohnt ein Issue bei den sehr aktiven ARVIO-Devs. **Noch NICHT geГғВ¶ffnet** ГўВҖВ“ in der nГғВӨchsten Session entscheiden, ob nach dem Logcat-Befund. Betreff/Inhalt-Skizze: ".cs3/Cloudstream3 plugins install and appear in list, but return no sources on sideload (GermanProviders AND custom TmdbProvider both empty; Stremio addons work)". Verweis auf #459/#273. **AI-Disclosure-Pflicht:** Falls Issue/MR-Kommentar erstellt wird, Hinweis "created by an AI agent (OpenHands) on behalf of [user]" einfГғВјgen.
- Vor dem Issue benГғВ¶tigte Infos vom Nutzer: genaue ARVIO-Version (1.9.983?), sideload bestГғВӨtigt, GerГғВӨt/Android-Version.

### ENTSCHEIDUNG NUTZER (14.08.2026): GitHub-Issue bei ARVIO professionell vorbereiten
Nutzer mÃ¶chte das GitHub-Issue bei ARVIO **professionell** einreichen (Vorbild: ARVIO Issue #537), ggf. sogar mit eigenem Fix-PR. Bis zur nÃ¤chsten Session sollen **alle dafÃ¼r nÃ¶tigen Informationen gesammelt und hier gespeichert** werden, damit eine andere Session das Issue ausarbeiten kann. **Status der Issue-ErÃ¶ffnung: NOCH NICHT Ã¶ffnen** â erst nach Logcat-Befund (Prio 1). Diese Sektion ist die Checkliste fÃ¼r die Vorbereitung.

#### Was bereits verifiziert/recherchiert ist (Stand 14.08.2026)
- **ARVIO-Repo:** `ProdigyV21/ARVIO` â Apache-2.0, 634 Stars, 98 Forks, sehr aktiv (18 Releases in 5 Monaten, letzte Commits 14.08.2026). Latest release `v1.9.983` (30.07.2026). `hasIssuesEnabled=true`, `hasDiscussionsEnabled=false` (â nur Issues, keine Discussions).
- **Maintainer:** `ProdigyV21` (Hauptmaintainer). **`Himanth-reddy`** = hochaktiver Mitwirkender, dessen PRs fast tÃ¤glich gemerged werden (#563, #561, #560, #558, #553, #552). Er hat auch den mobilen Plugin-UI-Fix (#466/v1.9.983) beigesteuert.
- **Externe PRs werden gemerged** (nicht nur closed) â ARVIO ist offen fÃ¼r saubere Contributions.
- **Kein CONTRIBUTING.md, keine Issue-Templates, keine PR-Templates** im Repo (obwohl GSSoC-Teilnehmer Issues dafÃ¼r Ã¶ffneten: #444/#477/#482 â closed, Status unklar). **â keine formale Contribution-Policy, die uns blockiert.**
- **"KIS" = Nutzer meinte "andere KIs, die Nutzern beim Issue/PR-Schreiben geholfen haben"** (NICHT GSSoC). Recherche: In den letzten ~25 gemergten PRs und ~80 Issues fand sich **keine explizite AI-Disclosure** externer Nutzer (`ai agent`/`copilot`/`gpt`/`claude`/`generated by`/`on behalf of` â 0 Treffer). Es gibt also **keine sichtbaren Vorbilder** im ARVIO-Repo fÃ¼r "KI hilft Nutzer beim Issue/PR" â die meisten externen BeitrÃ¤ge wirken handgeschrieben (z.T. oberflÃ¤chlich, v.a. GSSoC-Teilnehmer wie `prince-pokharna`/`aayan-rashid`). **Fazit fÃ¼r uns:** Wir dÃ¼rfen als erste ein AI-unterstÃ¼tztes Issue dort einreichen, aber das macht eine saubere, dezente AI-Disclosure umso wichtiger â kein Vorbild vorhanden, auf das wir verweisen kÃ¶nnen.
- **GSSoC** (GirlScript Summer of Code): ARVIO hat Label `gssoc:approved`; Teilnehmer Ã¶ffnen viele `[Feature Request]`-Issues. Irrelevant fÃ¼r unsere Frage (s.o.), nur Kontext.
- **README-Repo-Zweck** (verifiziert): explizit *"Issue investigation and technical discussion"* + *"Contribution review"* â die Devs **wollen** gut recherchierte technische Issues.
- **README "AI Disclosure"-Sektion (verifiziert, entscheidend):** *"This application was developed with significant AI assistance. Contributions should still be reviewed, tested, and treated as normal source code changes. If you have concerns about using AI-generated software, please do not use this application."* â **ARVIO selbst ist massiv AI-gestÃ¼tzt entwickelt.** Die Maintainer haben also **prinzipiell nichts gegen AI**; erwarten aber, dass AI-BeitrÃ¤ge wie normaler Code reviewt/getestet werden. Das ist die **stÃ¤rkste BestÃ¤tigung**, dass ein AI-unterstÃ¼tztes Issue+PR bei ARVIO willkommen ist, solange es qualitativ sauber ist. **Unsere AI-Disclosure-Pflicht bleibt trotzdem bestehen** (gemÃ¤Ã OpenHands-Regel fÃ¼r externe Services).
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
- **v7**: `load()` gibt **nie null** zurГғВјck (debug MovieLoadResponse dataUrl="ARVIO_DEBUG") ГўВҶВ’ `loadLinks` wird garantiert aufgerufen.
- **v8** (aktuell): Per-Call-Netzwerk-Timeouts (`withTimeoutOrNull(8s)` fГғВјr TMDB + Filmpalast-Suche) damit `load()` nicht das Gesamt-Timeout frisst.
- Letzter Commit auf `main`: `ca9f81f` (v8). Builds-Version: 8.

### Was fertig ist (unverГғВӨndert gГғВјltig)
Filmpalast-Plugin als Cloudstream3-`TmdbProvider` implementiert, gebaut, auf `builds`-Branch (`status=1`, `tvTypes=[Movie,TvSeries]`). CI grГғВјn. Nutzer hat v8 in ARVIO 1.9.983 (sideload) installiert. Python-E2E-Simulation lГғВӨuft durch; filmpalast.to + TMDB per HTTP erreichbar. **Das Problem ist rein ARVIO-seitig beim Laden/AusfГғВјhren von `.cs3`-Plugins.**

---

### (Veraltet, aber als Referenz behalten) FrГғВјhere Logcat-Optionen ohne PC
ARVIO hat **keine Log-Datei-Exportfunktion** und schreibt **keine App-Logs in Dateien** (verifiziert im gesamten ARVIO-Quellcode). Scraper-Logs (`Log.d/w` in `ExternalExtensionRunner.kt`) gehen **nur an Androids Logcat-Kernel-Buffer** (flГғВјchtig, ohne Root nicht direkt auslesbar). Optionen ohne PC:
- **LADB-App:** scheiterte am Pairing ("no devices/emulators found"); "Pair & shell"-Schalter musste AN sein; 30-s-Pairing-Timer extrem zickig. **FГғВјr diesen Nutzer nicht praktikabel.**
- **Bug Report:** Android-Einstellungen ГўВҶВ’ Entwickleroptionen ГўВҶВ’ Fehlerbericht (unhandlich, riesiger ZIP).
- **Nur mit Root:** Logcat-Reader-App.
- **WICHTIG:** ARVIOs integrierter "Test Scraper"-Button (`PluginManager.testScraper()`/`executeWithDiagnostics`) ist im Code vorhanden, aber in `PluginScreen.kt` **NICHT in die UI eingebaut** (Strings + ViewModel-Logik existieren, kein Compose-Button ruft `PluginUiEvent.TestScraper` auf). Halbfertige ARVIO-Funktion. FГғВјr uns irrelevant, solange der Scraper ohnehin nie geladen wird.
ГўВҶВ’ **Fazit: PC+USB+adb ist der Weg.** Siehe Prio 1 oben.

---

### Wichtige Dateien & Referenzen
- **Filmpalast-Code:** `/workspace/project/Arvio-Addon/FilmPalast/src/main/kotlin/com/reichi/arflioaddon/filmpalast/` ГўВҖВ“ `FilmpalastProvider.kt` (load/loadLinks/diagnose), `FilmpalastPlugin.kt`, `FilmpalastExtractors.kt`, `DebugLog.kt`, `DebugServer.kt`, `DownloadsLogWriter.kt`
- **ARVIO-Referenz:** `ProdigyV21/ARVIO` @ v1.9.983 (neu klonen nach `/tmp/arvio_ref`, wird nicht persistiert). SchlГғВјssel-Dateien:
  - `app/src/sideload/kotlin/com/arflix/tv/core/plugin/PluginManager.kt` ГўВҖВ“ `executeScrapers` (625), `executeScrapersStreaming` (672), `enabledScrapers` (271), `executeExternalDexScraper` (831, mit `SCRAPER_TIMEOUT_MS=120_000` bei 840), `downloadDexExtensions` (1057), `manifestEnabled = plugin.status == 1` (1079), `toggleScraper` (589, lГғВӨdt NICHT neu), `refreshExternalRepository` (566, lГғВӨdt neu)
  - `app/src/sideload/kotlin/com/arflix/tv/core/plugin/cloudstream/ExternalExtensionLoader.kt` ГўВҖВ“ `downloadExtension` (203, DEX read-only fГғВјr API28+), `loadExtension` (259), `findAndLoadPlugin` (701, liest `manifest.json`-`pluginClassName`), `plugin.load()`-Aufruf (317, fГғВӨngt Exception+Error), **Fallback-DEX-Scan bei `apis.isEmpty()||extractors.isEmpty()`** (336), `getApi` (420, apiCache)
  - `app/src/sideload/kotlin/com/arflix/tv/core/plugin/cloudstream/ExternalExtensionRunner.kt` ГўВҖВ“ `execute` (60), `executeInternal` (342), `executeTmdbProvider` (367), `executeTmdbLoadLinks` (430, `LOADLINKS_TIMEOUT_MS=60_000` bei 442), `extractData` (738: MovieГўВҶВ’dataUrl, TvSeriesГўВҶВ’findEpisode.data), `filterValid` (870: nur http(s)-URLs!), `toLocalScraperResult` (884), `EXECUTION_TIMEOUT_MS=120_000`
  - `app/src/main/kotlin/com/arflix/tv/domain/model/Plugin.kt` ГўВҖВ“ `ScraperInfo` (77), `supportsType` (92, normalisiert series/tv/animeГўВҶВ’tv)
  - `app/src/sideload/kotlin/com/arflix/tv/core/plugin/cloudstream/TvTypeExtensions.kt` ГўВҖВ“ `tvTypeFromString`, `toNuvioType`
  - `app/src/main/kotlin/com/arflix/tv/ui/screens/details/DetailsViewModel.kt` ГўВҖВ“ `loadStreams` (1405), `pluginScraperJob` (1511, ruft `executeScrapersStreaming`), `hasStreamingAddons` zГғВӨhlt NUR Stremio-Addons (1601/1634/1651/1690) ГўВҶВ’ irrefГғВјhrende "kein Add-on"-Meldung bei reinen Cloudstream-Plugins
- **GermanProviders-Referenz:** `Bnyro/GermanProviders` (Repo-URL: `https://raw.githubusercontent.com/Bnyro/GermanProviders/refs/heads/master/repo.json`). Filmpalast dort = `MainAPI` (search-based). **Auf dem GerГғВӨt des Nutzers ebenfalls 0 Quellen** ГўВҶВ’ beweist ARVIO-seitiges `.cs3`-Problem.
- **Builds-Branch:** v8 verГғВ¶ffentlicht, `status=1`, `internalName=FilmPalast`. `plugins.json`+`FilmPalast.cs3` auf `builds`.
- **cloudstream3 library:** v4.7.0 (`com.github.recloudstream.cloudstream:library-android:v4.7.0`). Built-in Extractoren: `Voe()`, `Firestream()`, `FileMoonSx()`, `Supervideo()`, `VidHidePro()` + ~270 andere via `installGlobal()`. **Wichtig:** `loadLinks` gibt in v4.7.0 `Boolean` zurГғВјck (nicht Unit) ГўВҶВ’ Override muss `: Boolean` deklarieren.

### ARVIO-Scraper-Aufruf-Pfad (verifiziert, entscheidend fГғВјrs Debugging)
1. `DetailsViewModel.loadStreams` ГўВҶВ’ `pluginScraperJob` ГўВҶВ’ `pluginManager.executeScrapersStreaming(tmdbId, mediaType, season, episode)`
2. `executeScrapersStreaming`: prГғВјft `pluginsEnabled` + `enabledScrapers.filter{supportsType}`; leer ГўВҶВ’ return; sonst pro Scraper `executeScraperWithSingleFlight` ГўВҶВ’ `executeExternalDexScraper` (mit `SCRAPER_TIMEOUT_MS=120_000`)
3. `executeExternalDexScraper` ГўВҶВ’ `externalExtensionRunner.execute(scraperId,...)` ГўВҶВ’ `extensionLoader.getApi(scraperId)` (leer ГўВҶВ’ "No API loaded" ГўВҶВ’ emptyList, **still**)
4. `execute` ГўВҶВ’ `executeInternal` ГўВҶВ’ **wenn `api is TmdbProvider`:** `executeTmdbProvider`; **sonst:** `executeSearchBased`
5. `executeTmdbProvider`: `api.load("""{"id":$tmdbIdInt,"type":"$type"}""")` ГўВҶВ’ null-fallback `api.load("https://www.themoviedb.org/<type>/<id>")` ГўВҶВ’ `extractData(loadResponse)` ГўВҶВ’ `api.loadLinks(data)`
6. `extractData`: `MovieLoadResponse`ГўВҶВ’`dataUrl`, `TvSeriesLoadResponse`ГўВҶВ’`findEpisode(...).data`
7. `executeTmdbLoadLinks`: sammelt `ExtractorLink`s via callback, `filterValid` (nur http(s)), `toLocalScraperResult` ГўВҶВ’ erscheinen in ARVIOs Quellenauswahl. **Unsere Debug-Quellen (url=`https://arvio-addon.invalid/...`) passieren filterValid.**
8. **Inkonsistenz (Test-Pfad):** `executeTmdbProviderWithDiagnostics` ruft `loadLinks` mit `TmdbLink(...).toJson()` direkt auf (ohne `load()`) ГўВҖВ“ anderer data-Vertrag. Unser `loadLinks` ist auf den load()-Pfad ausgelegt. Falls ARVIO den Test-Button aktiviert, muss `loadLinks` auch TmdbLink-JSON verarbeiten.
9. **WICHTIG fГғВјr "Quellen aktualisieren":** `toggleScraper` (589) lГғВӨdt die `.cs3` NICHT neu ГўВҶВ’ nur Datenbank-Toggle. Neudownload NUR via `addRepository` oder `refreshExternalRepository`. **Daher: fГғВјr Plugin-Update immer Repo lГғВ¶schen + neu hinzufГғВјgen** (`https://raw.githubusercontent.com/ReichiMD/Arvio-Addon/main/repo.json`).

### Diagnose-Tooling in unserem Plugin (v8, fГғВјr die Logcat-ГғВ„ra falls Scraper doch lГғВӨuft)
- `DebugLog.kt`: in-memory Ring-Buffer (2000) + `snapshot()`/`format()` fГғВјr `emitTraceAsSources`.
- `emitTraceAsSources(callback)`: emittiert Trace als `ExtractorLink` (source="ArvioAddon-Debug", url=`https://arvio-addon.invalid/debug/<n>`) ГўВҶВ“ sichtbar in ARVIO-Quellenauswahl. Erstes Banner: "PLUGIN vN loaded".
- `debugLoadResponse()`: `MovieLoadResponse` mit `dataUrl="ARVIO_DEBUG"` ГўВҶВ’ `loadLinks` wird auch bei load()-Fehlern aufgerufen.
- Per-Call-Timeouts (`NET_TIMEOUT_MS=8000`) um `fetchTmdbMeta`/`searchFilmpalast`.
- `DebugServer.kt` (127.0.0.1:8420) + `DownloadsLogWriter.kt` (MediaStore) noch vorhanden, aber **nur nutzbar, wenn der Scraper lГғВӨuft** (was aktuell nicht der Fall ist).

---

## Entscheidung: Welcher Plugin-Typ?

**GewГғВӨhlt: Cloudstream3-Plugin (Kotlin/DEX, ".cs3")** ГўВҖВ“ der "mГғВӨchtige" Weg.

| Grund | Detail |
|---|---|
| Eigene Konfig-Seite (Portal-URL/MAC) | Nur Cloudstream3-Plugins kГғВ¶nnen UI-Settings haben ГўВҶВ’ nГғВ¶tig fГғВјr Stalker-VOD |
| Eigene Kataloge/Startseiten in ARVIO | Nur Cloudstream3-Plugins liefern eigene Start-Kataloge |
| Kotlin = gleiche Sprache wie Ventix | Ventix-Scraper (Kotlin) lassen sich **direkt portieren**, nicht nach JS ГғВјbersetzen |
| Viele Vorlagen | GermanProviders-Repo (Bnyro) ist eine komplette Vorlage mit exakt unseren Scraper-Hostern |
| Cloudstream3-ГғВ–kosystem | ARVIO nutzt library v4.7.0; apiVersion 1 ist kompatibel |

**AbgewГғВӨhlt: Nuvio-JS-Plugin** (Weg A) ГўВҖВ“ einfacher, kann aber nur Streams liefern, keine Config-Seite, keine eigenen Kataloge. Da wir Stalker-VOD brauchen (mit Portal/MAC-Eingabe), reicht JS-Plugin nicht.

---

## Ziel: ARVIO-Installation des fertigen Plugins

Der Nutzer installiert das Plugin so in ARVIO (verifizierter Flow):
1. ARVIO **sideload-APK** installieren (nicht Play-Store-Version!)
2. Einstellungen ГўВҶВ’ **Plugins & Extensions** (nur in sideload sichtbar)
3. **Add Repository** ГўВҶВ’ Repo-URL eintragen
4. ARVIO lГғВӨdt `repo.json` ГўВҶВ’ folgt `pluginLists` ГўВҶВ’ lГғВӨdt `plugins.json`
5. Plugin-EintrГғВӨge einschalten ГўВҶВ’ ARVIO lГғВӨdt `.cs3`-Datei (kompilierter Code)

### Bekannter Bug: Add-Repo-Dialog/Plugin-Settings auf Handy (GEFIXT in 1.9.983)
Der "Add Repository"-Dialog + Plugin-Settings-Screen nutzten TV-only `androidx.tv.material3.Surface`-Buttons, die auf Touch-GerГғВӨten (Handy/Tablet) nicht reagierten. **Behoben in ARVIO Issue #502** ("fix(mobile): resolve touch issues in plugins settings") ГўВҖВ“ `PluginScreen.kt` hat jetzt `LocalDeviceType.current.isTouchDevice()` mit separatem Mobile-Layout. **Fix ist in 1.9.983 enthalten** (verifiziert). Nutzer hat das Plugin erfolgreich ГғВјber ein Cloud-Profil auf dem Handy installiert.

---

## Was das Plugin kГғВ¶nnen muss (Scope)

### Modul 1: Deutsche Web-Scraper (Filmpalast, Serienstream, HdFilme, Megakino, KinoGer, Netzkino, AniWorld)
- **Vorlage:** GermanProviders-Repo (Bnyro/GermanProviders) ГўВҖВ“ hat ALL diese Scraper schon als Cloudstream3-Plugins!
- MГғВ¶glichkeit 1: GermanProviders forken + anpassen (wenig Eigenarbeit, abhГғВӨngig von Upstream)
- MГғВ¶glichkeit 2: Eigenes Plugin schreiben, GermanProviders als Referenz (volle Kontrolle)

### Modul 2: Stalker-VOD (Filme + Serien ГғВјber Stalker-Portal)
- **Das ist die Neuentwicklung** ГўВҖВ“ GermanProviders hat das nicht.
- ARVIOs eingebaute StalkerApi kennt NUR Live-TV (get_genres, get_all_channels, create_link) ГўВҖВ“ **KEIN VOD, keine Serien**.
- Plugin braucht: eigene Config-Seite (Portal-URL + MAC), VOD-Kategorien, VOD-Liste, createVodLink, Serien/Staffeln/Episoden.
- Vorlage: Ventix-StalkerApi (17 Methoden) ГўВҖВ“ in Kotlin, direkt portierbar.

### Modul 3: Stalker Live-TV
- **Nicht bauen** ГўВҖВ“ ARVIO hat das schon eingebaut (obwohl die UI aktuell fehlt, siehe "ARVIO-MГғВӨngel").

---

## Architektur-Referenz: ARVIOs Plugin-System

### Plugin-Formate die ARVIO versteht (verifiziert im Code)
1. **Nuvio-JS-Plugin**: `manifest.json` + `.js`-Dateien mit `getStreams(tmdbId, type, season, episode)`. Engine: QuickJS + Cheerio + CryptoJS. (abgewГғВӨhlt)
2. **Cloudstream3-Plugin (EXTERNAL_DEX)**: `.cs3`-Datei (kompiliertes DEX). Engine: cloudstream3-library v4.7.0. (**gewГғВӨhlt**)

### ARVIO Repository-Manifest-Format (`repo.json`)
```json
{
  "name": "Ventix Arvio Addon",
  "description": "Deutsche Scraper + Stalker VOD fГғВјr ARVIO",
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
- `@CloudstreamPlugin`-annotierte `Plugin`-Klasse ГўВҶВ’ `registerMainAPI(...)` + `registerExtractorAPI(...)`
- `MainAPI`-Subklasse ГўВҶВ’ `mainUrl`, `name`, `supportedTypes`, `mainPage`, `search()`, `load()`, `loadLinks()`
- `ExtractorApi`-Subklassen fГғВјr Hoster (VOE, FileMoon, Supervideo, VidHidePro etc.)

---

## Ventix-Referenz (Quell-Projekt, NICHT in dieses Repo kopieren)

Ventix liegt im Schwester-Repo `ReichiMD/IPTV-App`. Scraper-Quellcode zum Portieren:
- `app/src/main/java/com/iptv/stalker/data/scraping/` ГўВҖВ“ FilmpalastScraper, HdFilmeScraper, KinogerScraper, MegakinoScraper, SerienstreamScraper, AniWorldScraper, NetzkinoScraper + extractor/
- `app/src/main/java/com/iptv/stalker/data/api/StalkerApi.kt` ГўВҖВ“ Stalker-Middleware (17 Methoden: handshake, get_profile, get_events, VOD-+Serien-+EPG-Endpoints, createVodLink, getSeasons, M3U-Export)
- `app/upstream-reference/` ГўВҖВ“ Cloudstream3-Upstream-Referenzen (bereits als Referenz genutzt!)
- `VideoHostExtractor.kt` ГўВҖВ“ Hoster-Extraktoren (VOE, FileMoon, VidGuard, Veev, Vidsonic, DoodStream etc.)

Ventix und ARVIO nutzen BEIDE Cloudstream3-Upstream-Referenzen ГўВҖВ“ das vereinfacht das Portieren.

---

## GermanProviders-Referenz (Vorlage-Repo, geklont nach /tmp/german-providers)

`Bnyro/GermanProviders` ГўВҖВ“ Cloudstream3-Multi-Provider-Repo, hat bereits 21 fertige Plugins:
ARD, Aniworld, Arte, C3TV, Discovery, EinschaltenIn, **FilmPalast**, HDFilme, HuhuTo, IptvOrg, KinoKing, **Kinoger**, **Megakino**, Moflix, **Netzkino**, PlutoTV, **Serienstream**, Southpark, SpiegelTV, Welt, Xcine.

Installations-URL (Test): `https://raw.githubusercontent.com/Bnyro/GermanProviders/refs/heads/master/repo.json`
- `builds`-Branch enthГғВӨlt `plugins.json` + fertige `.cs3`-Dateien.
- Aufbau: root `build.gradle.kts` (cloudstream3-gradle-plugin `com.github.recloudstream:gradle`), pro Provider ein Modul-Ordner mit `build.gradle.kts` + `src/`.
- Settings-Gradle: auto-include aller Modul-Ordner.

**Diese Scraper (Filmpalast, Serienstream etc.) sind identisch mit Ventix' Ziel-Set.** GermanProviders ist die primГғВӨre Vorlage fГғВјr Modul 1.

---

## ARVIO-Referenz (geklot nach /tmp/arvio_ref)

`ProdigyV21/ARVIO` ГўВҖВ“ die Ziel-App. Version 1.9.983 (versionCode 306), sehr aktiv.

### ARVIO Build-Flavors (verifiziert in `app/build.gradle.kts`)
| Flavor | `FEATURE_PLUGINS_ENABLED` | `SELF_UPDATE_ENABLED` | Plugin-Engine |
|---|---|---|---|
| `play` (Play Store) | **false** | false | ГўВқВҢ abgeschaltet |
| `sideload` (GitHub-APK) | **true** | true | ГўВңВ… voll aktiv |

ГўВҶВ’ **Plugin funktioniert NUR in der sideload-APK**, nicht im Play-Store-Build. Google-Policy verbietet dynamischen Code im Store.

### ARVIO sideload-Download
`https://github.com/ProdigyV21/ARVIO/releases/download/v1.9.983/ARVIO-v1.9.983-sideload-release.apk` (135 MB)

### ARVIO Plugin-Engine (nur in `app/src/sideload/`)
- `PluginManager.kt` ГўВҖВ“ Repository-Verwaltung, `addRepository()`, Format-Auto-Detection
- `PluginRuntime.kt` ГўВҖВ“ QuickJS-Engine (fГғВјr JS-Plugins) + `__native_fetch`, `__cheerio_*`, CryptoJS
- `cloudstream/ExternalExtensionLoader.kt` ГўВҖВ“ lГғВӨdt `.cs3`-Plugins via DexClassLoader
- `cloudstream/ExternalExtensionRunner.kt` ГўВҖВ“ fГғВјhrt `MainAPI.search()`/`load()`/`loadLinks()` aus
- `cloudstream/ExternalExtractorRegistry.kt` ГўВҖВ“ verwaltet `ExtractorApi`-Extraktoren
- `cloudstream/ExternalRepoParser.kt` ГўВҖВ“ parsed `repo.json` (erkennt `"pluginLists"`-Key) + `plugins.json`

### ARVIO-MГғВӨngel (Stand Aug 2026, die wir im Plugin adressieren)
1. **Stalker-VOD fehlt komplett** ГўВҖВ“ StalkerApi kennt nur Live-TV (4 Methoden: handshake, getProfile, getChannels, resolveStreamUrl). Kein VOD, keine Serien, kein EPG fГғВјr VOD.
2. **Stalker-Dateneingabe fehlt in der UI** ГўВҖВ“ `saveStalkerConfig()` existiert im SettingsViewModel (Zeile 2280), wird aber von KEINEM UI-Element aufgerufen. Kein Button, kein Dialog. Backend halbfertig, UI fehlt.
3. **Add-Repo-Dialog Handy-Bug** ГўВҖВ“ `width(520.dp)` zu breit fГғВјr Hochformat (Workaround: Querformat).

---

## Stremio-Addon-Referenz (paralleles Projekt)

`ReichiMD/Stremio-Addon` ГўВҖВ“ serverseitiges Node.js-Addon (deployed auf Render), Quellen: Vavoo/KinoGer/Filmpalast/MovieBox/VidSrc/Einschalten + MediaFlowProxy.
- **Problem:** Stremio-Addon (serverseitig) kann manche Streams nicht liefern (z.B. KinoGer 403 ГўВҖВ“ Render-DC-IP blockiert). Ventix (clientseitig) kann das.
- **Dieses ARVIO-Addon lГғВ¶st das:** lГғВӨuft clientseitig in der App ГўВҶВ’ EndgerГғВӨt-IP ГўВҶВ’ kein Bot-Schutz ГўВҶВ’ kein Server, kein Geld.
- Logik der Scraper ist im Stremio-Addon bereits in JavaScript/TypeScript vorhanden (kann als Referenz dienen, wird aber neu in Kotlin als Cloudstream3-Plugin geschrieben).

`ReichiMD/mediaflow-proxy` ГўВҖВ“ MediaFlowProxy (fest codiert im Stremio-Addon). FГғВјr ARVIO-Addon nicht nГғВ¶tig (clientseitig braucht keinen Proxy).

---

## Build & Release

### Plugin kompilieren (Cloudstream3-gradle-plugin)
- Multi-Modul-Setup wie GermanProviders: root `build.gradle.kts` mit `com.github.recloudstream:gradle`-Plugin, pro Plugin ein Modul.
- Output: `.cs3`-Datei pro Plugin-Modul.
- CI: GitHub Actions baut `.cs3`-Dateien, pusht sie auf einen `builds`-Branch (wie GermanProviders), generiert/aktualisiert `plugins.json`.

### Datei-Struktur (geplant)
```
Arvio-Addon/
ГўВ”ВңГўВ”ВҖГўВ”ВҖ AGENTS.md                          # diese Datei
ГўВ”ВңГўВ”ВҖГўВ”ВҖ README.md                          # (spГғВӨter)
ГўВ”ВңГўВ”ВҖГўВ”ВҖ build.gradle.kts                   # root, cloudstream3-gradle-plugin
ГўВ”ВңГўВ”ВҖГўВ”ВҖ settings.gradle.kts                # auto-include Module
ГўВ”ВңГўВ”ВҖГўВ”ВҖ repo.json                          # Installations-Manifest fГғВјr ARVIO
ГўВ”ВңГўВ”ВҖГўВ”ВҖ GermanScraper/                     # Modul 1: deutsche Web-Scraper (oder pro Scraper ein Modul)
ГўВ”ВӮ   ГўВ”ВңГўВ”ВҖГўВ”ВҖ build.gradle.kts
ГўВ”ВӮ   ГўВ”В”ГўВ”ВҖГўВ”ВҖ src/main/kotlin/.../GermanScraperPlugin.kt + Provider + Extractors
ГўВ”В”ГўВ”ВҖГўВ”ВҖ StalkerVod/                        # Modul 2: Stalker-VOD (Config-Seite + VOD/Serien)
    ГўВ”ВңГўВ”ВҖГўВ”ВҖ build.gradle.kts
    ГўВ”В”ГўВ”ВҖГўВ”ВҖ src/main/kotlin/.../StalkerVodPlugin.kt + StalkerApi + Provider
```

### branches
- `main` ГўВҖВ“ Quellcode
- `builds` ГўВҖВ“ fertige `.cs3`-Dateien + `plugins.json` (von CI gepusht, wie GermanProviders)

---

## NГғВӨchste Schritte (PrioritГғВӨt)

1. **Proof-of-Concept:** GermanProviders in ARVIO-sideload testen (Button-Bug workaronden) ГўВҶВ’ prГғВјfen welche Scraper laufen.
2. **Repo-Setup:** GermanProviders-Architektur (root build.gradle + Modul-Struktur) hier nachbauen.
3. **Modul 1 (Web-Scraper):** GermanProviders-Plugins adaptieren ODER eigene Implementierung. Hoster-Extraktoren (VOE, FileMoon etc.) aus Ventix' `VideoHostExtractor` portieren.
4. **Modul 2 (Stalker-VOD):** Ventix' `StalkerApi.kt` (VOD/Serien-Teil) als Cloudstream3-Provider portieren + Config-Seite fГғВјr Portal/MAC.
5. **CI:** GitHub Actions workflow fГғВјr `.cs3`-Build + `builds`-Branch-Push.

---

## Versionshistorie dieses Addons

(noch keine ГўВҖВ“ Repo ist leer)

---

## Recherche: ARVIO-Plugin-Integration (Stand Aug 2026, ARVIO v1.9.983)

Verifiziert im ARVIO-Quellcode (`ProdigyV21/ARVIO` @ v1.9.983, geklont nach `/tmp/arvio_ref`).
Recherche anlГғВӨsslich zweier Nutzer-Probleme beim Testen von GermanProviders als Cloudstream3-Plugin in ARVIO.

### Problem 1: Plugin-Einrichtung funktioniert nur auf TV, nicht auf Handy/Tablet

**Beobachtung (Nutzer):** Add-Repository / Plugin-Aktivierung ging auf Handy & Tablet nicht; erst ein ARVIO-Cloud-Profil (auf TV erstellt, aufs Handy synchronisiert) brachte die Plugins aufs Handy. TV funktionierte direkt.

**Rechercheergebnis:**
- ARVIO hat ein Layout-Force-Feature ("Force TV, Tablet, or Phone layout") UND Auto-Detect fГғВјr TV-Modus bei GerГғВӨten ohne Touchscreen (CHANGELOG v1.9.3). Die UI wird je Formfaktor unterschiedlich gerendert.
- Der Plugin-Bereich wurde in v1.9.983 neu gebaut: CHANGELOG-Eintrag "redesigned plugin settings for TV and mobile. Contributor: @Himanth-reddy via #466" ГўВҖВ“ d.h. die mobile Plugin-UI ist **sehr neu** (Juli 2026).
- Begleitend in v1.9.983: "Fixed sideload production-plugin routing, extractor unloading, **mobile routing**, and TV focus limits" (#466) ГўВҖВ“ ein mobiler Routing-Fix wurde *explizit* fГғВјr diese Version gebraucht. Das deutet darauf hin, dass mobile Plugin-Pfade vorher fehlerhaft waren.
- Ein **bekannter, ГғВӨlterer Bug** (AGENTS.md bereits notiert): Add-Repo-Dialog `width(520.dp)` zu breit fГғВјr Handy-Hochformat (~390dp) ГўВҶВ’ Buttons abgeschnitten/inaktiv im Hochformat.
- Vergleichs-Befund aus dem Nuvio-ГғВ–kosystem (Schwester-App, gleiche Plugin-Architektur): NuvioMobile Issue #1190 ГўВҖВ“ *"If Cloudstream Plugin Repositories are loaded in the Plugins list in the Mobile app, they get removed from Plugins list in the TV app"* (closed as not planned). Cloudstream-Plugin-Listen zwischen Mobile- und TV-UI synchron halten ist **branchenweit ein Problem**, nicht ARVIO-spezifisch.

**Fazit Problem 1:** Sehr wahrscheinlich ein **ARVIO-seitiger Bug in der (neuen) mobilen Plugin-UI** ГўВҖВ“ entweder Routing (in v1.9.983 gerade erst gefixt, evtl. nicht vollstГғВӨndig) oder der bekannte `width(520.dp)`-Dialog-Bug. Dass der Cloud-Sync-Workaround funktioniert, bestГғВӨtigt: Die Plugin-Daten selbst sind korrekt; nur die mobile Einrichtungspath-UI ist defekt. Keine andere Nutzerberichte als direktes Duplikat gefunden, aber die CHANGELOG-Historie (mobiler Plugin-Routing-Fix in der *aktuellen* Version) zeigt, dass ARVIO genau diese Klasse von Bug gerade behebt.

**Workarounds fГғВјr Nutzer:** Querformat beim Add-Repo; oder Plugin-Konfiguration auf TV vornehmen + ARVIO-Cloud-Sync aufs Handy (funktioniert laut Nutzer bereits); oder `web.arvio.tv` (Web-App, vollstГғВӨndige ARVIO-UI im Browser, laut CHANGELOG mit TV-D-pad-Navigation).

### Problem 2: Aktivierte Provider erscheinen nicht bei Quellensuche ("kein Add-on eingerichtet / keine Quellen")

**Beobachtung (Nutzer):** In den Plugin-Einstellungen Provider (z.B. Einschalten) aktiviert ГўВҶВ’ auf eine Silo-Episode gegangen ГўВҶВ’ "nach Quellen gesucht" ГўВҶВ’ Meldung "kein Add-on eingerichtet, keine Quellen gefunden".

**Verifizierte Ursache im ARVIO-Code:** ARVIO hat **zwei komplett getrennte Quell-AuflГғВ¶sungspfade**, und Cloudstream3-Plugins (.cs3) laufen ГғВјber den Pfad, der die "kein Add-on"-Meldung **nicht steuert**:

1. **Stremio-Addon-Pfad** (`StreamRepository` + `AddonRuntimeAggregator`): Hier laufen klassische Stremio-kompatible Addons (HTTP `stream/movie/<imdbId>.json`), Home-Server (Jellyfin/Plex/Emby) und HTTP-Local-Scrapers. Die UI-Variable `hasStreamingAddons` (die "No Streaming Addons" / "kein Add-on eingerichtet" anzeigt) wird **ausschlieГғВҹlich** aus `streamRepository.installedAddons.count { it.isVodStreamingAddon() }` berechnet (`DetailsViewModel.kt` Z. 1600/1633/1650/1689). `isVodStreamingAddon()` prГғВјft nur `isEnabled && type != SUBTITLE && !sportsOnly` ГўВҖВ“ das sind Stremio-Addons, **keine Cloudstream-Scraper**. Filter `getStreamAddons()` (`StreamRepository.kt` Z. 1440) wirft sogar hart raus: `if (addon.runtimeKind != RuntimeKind.STREMIO) return@filter false` ГўВҖВ“ und `RuntimeKind` kennt nur `STREMIO`/`TELEGRAM`, keinen Cloudstream/EXTERNAL_DEX-Wert (`Models.kt` Z. 305).

2. **Cloudstream-Plugin-Pfad** (`PluginManager` + `ExternalExtensionRunner`, sideload-only): Aktivierte `.cs3`-Scraper werden in `DetailsViewModel.loadStreams()` ГғВјber `pluginManager.executeScrapersStreaming(...)` in einem **parallelen Job** (`pluginScraperJob`, Z. 1510ГўВҖВ“1552) ausgefГғВјhrt. Ergebnisse mergen sich asynchron in `streams`. Dieser Pfad startet **nur**, wenn `dataStore.pluginsEnabled` true ist UND `enabledScrapers` (nach `supportsType(mediaType)`) nicht leer ist (`PluginManager.kt` Z. 631ГўВҖВ“640, 681).

**Warum trotzdem "kein Add-on"-Meldung + keine Quellen bei Silo:** Weil `hasStreamingAddons` Stremio-Addons zГғВӨhlt. Hat der Nutzer **kein einziges** Stremio-Addon installiert (nur Cloudstream-Plugins), ist `hasStreamingAddons=false` ГўВҶВ’ UI zeigt "No Streaming Addons / kein Add-on eingerichtet" an. Die Meldung ist in diesem Fall **irrefГғВјhrend**: Die Cloudstream-Scraper suchen im Hintergrund trotzdem, finden aber fГғВјr "Silo" vermutlich nichts (siehe Problem 2b), und die UI bleibt bei der "Setup Required"-Meldung stehen, obwohl die Plugins aktiv sind.

**Problem 2b ГўВҖВ“ warum die Cloudstream-Scraper fГғВјr "Silo" trotzdem 0 Quellen liefern (verifiziert):**
GermanProviders-Plugins (Filmpalast, Serienstream, AniWorld etc.) sind **keine** `TmdbProvider` (sie ГғВјberschreiben nicht `load()` fГғВјr TMDB-JSON), sondern **search-basierte** `MainAPI`-Provider. ARVIOs `ExternalExtensionRunner.executeSearchBased()` (Z. 473ГўВҖВ“620) macht fГғВјr search-basierte Provider:
1. TMDB-Enrichment holen ГўВҶВ’ `localizedTitle` + `year` + alt-Titel
2. `api.search(title)` aufrufen + bei Trefferlosigkeit Retry mit vereinfachtem Titel und parallelen Alt-Titeln
3. `findBestMatch()` (ГғВ„hnlichkeits-Score) ГғВјber Suchergebnisse ГўВҶВ’ `api.load(bestMatch.url)` ГўВҶВ’ `extractData()` ГўВҶВ’ `api.loadLinks()`

Scheitern kann es an **mehreren Stellen**:
- **Sprache:** Silo ist eine Apple TV+-Serie. Deutsche Scraper wie Filmpalast/Serienstream listen "Silo" u.U. nur unter deutschem Titel oder garnicht (Apple-TV+-Originals sind seltener auf deutschen Scraper-Seiten als Netflix/Prime). TMDB `localizedTitle` fГғВјr Silo DE = "Silo" ГўВҖВ“ passt, aber die Scraper-Seite muss die Serie auch im Katalog haben.
- **`findBestMatch`-Mismatch:** Wenn der Scraper "Silo" z.B. als "Silo - Season 1" oder mit Jahr-Abweichung zurГғВјckgibt, fГғВӨllt der Similarity-Score unter die Schwelle ГўВҶВ’ `return emptyList()` (Z. 567). Das ist ein **hГғВӨufiges** Cloudstream-Problem bei ARVIO, weil ARVIO eigenes Title-Matching macht statt die Provider-`load()` direkt mit der Scraper-eigenen URL zu fГғВјttern.
- **Season/Episode-Mapping:** `extractData(loadResponse, mediaType, season, episode)` baut das `data`-JSON, das `loadLinks()` erwartet. Bei Serien muss `load()` eine `TvSeriesLoadResponse` liefern, aus der ARVIO die Episoden-URL extrahiert. GermanProviders' `load()`-Implementierungen sind fГғВјr Cloudstream3-App geschrieben; ARVIO ruft sie leicht anders auf ГўВҶВ’ kann `data=null` geben ГўВҶВ’ `return emptyList()` (Z. 590).
- **Host-Dead / Bot-Schutz:** Deutsche Scraper-Seiten blockieren oft. ARVIO fГғВӨngt `hostUnreachable` ab und skippt (Z. 552). Da ARVIO clientseitig lГғВӨuft (GerГғВӨt-IP), sollte das seltener sein als beim serverseitigen Stremio-Addon ГўВҖВ“ aber mГғВ¶glich.

**Fazit Problem 2:** Zwei Dinge ГғВјberlagern sich:
- (a) **ARVIO-UI-Bug/DesignschwГғВӨche:** Die "kein Add-on eingerichtet"-Meldung wird nur aus dem Stremio-Addon-Pfad gespeist und ignoriert aktivierte Cloudstream-Plugins vollstГғВӨndig. Solange kein Stremio-Addon aktiv ist, zeigt die UI "Setup Required", **selbst wenn** Cloudstream-Scraper im Hintergrund laufen. Das ist eine ARVIO-seitige LogiklГғВјcke, nicht des Addons Schuld.
- (b) **Scraper-Matching:** Selbst wenn die Cloudstream-Scraper laufen, liefern sie fГғВјr bestimmte Titel (wie Silo) oft 0 Treffer wegen ARVIOs eigenem Title-Matching / `findBestMatch` / Episode-Mapping, das nicht 1:1 der Cloudstream3-App entspricht.

CHANGELOG-Belege, dass ARVIO dieses Themenfeld aktiv bearbeitet:
- v1.9.983: "Added compatibility for Nuvio-style JavaScript scraper plugins and redesigned plugin settings for TV and mobile" (#466) + "Fixed sideload production-plugin routing, extractor unloading, mobile routing, and TV focus limits" (#466)
- v1.9.92: "Improved FlixStreams/anime addon matching and fallback stream lookup for episode sources" + "Fixed configured add-ons occasionally failing to appear in the source list until a later retry"
- v1.8.2: "Source selector shows setup instructions instead of generic 'No sources found' when no addons are installed" + "When no streaming addons are configured, the app now shows a friendly setup guide instead of a playback error"

**Handlungsempfehlung (fГғВјr unser Addon / Nutzer):**
1. **FГғВјr saubere UI-Anzeige:** ZusГғВӨtzlich zu den Cloudstream-Plugins **mindestens ein** Stremio-Addon (auch ein inaktives/dummy) installieren, damit `hasStreamingAddons=true` wird und die Meldung verschwindet. Das ist ein Workaround fГғВјr ARVIOs LogiklГғВјcke (a).
2. **FГғВјr echte Quellen bei Serien wie Silo:** Eigenes ARVIO-Addon bauen (Ziel dieses Repos) ГўВҖВ“ aber dabei darauf achten, dass die `MainAPI`-Implementierung robustes `search()` + `load()` + `loadLinks()` bietet, das ARVIOs `findBestMatch`-basiertem Aufruffluss standhГғВӨlt. Ideal: Provider als `TmdbProvider` implementieren (dann nimmt ARVIO den direkteren `executeTmdbProvider`-Pfad ohne fragiles Title-Matching). Das ist eine **Konsequenz fГғВјr die Modul-1-Architektur** dieses Addons.
3. **GitHub-Issue bei ARVIO erwГғВӨgen:** (a) ist klar ein ARVIO-Bug ("hasStreamingAddons ignoriert aktivierte Cloudstream-Scraper"). Lohnt sich als Issue zu melden, da ARVIO aktiv ist (18 Releases in 5 Monaten) und #466 genau dieses Gebiet gerade anfasst.

---

## Implementation: Filmpalast-Plugin als TmdbProvider (Proof-of-Concept)

**Status: gebaut und kompiliert.** `FilmPalast/build/FilmPalast.cs3` (ГўВүВҲ23 KB) + `build/plugins.json` werden lokal via `./gradlew make makePluginsJson` erzeugt; CI (`.github/workflows/build.yml`) pusht beides auf den `builds`-Branch.

### Architektur-Entscheidung (verbindlich fГғВјr alle Modul-1-Scraper)
**Alle Provider als `TmdbProvider` implementieren**, nicht als plain `MainAPI`. BegrГғВјndung (siehe oben "Recherche"): ARVIO hat zwei Dispatch-Pfade in `ExternalExtensionRunner.execute()`:
- `executeTmdbProvider` (wenn `api is TmdbProvider`): ruft `api.load("{\"id\":<tmdbId>,\"type\":\"movie\"|\"tv\"}")` direkt auf ГўВҶВ’ kein fragiles `findBestMatch`-Title-Matching.
- `executeSearchBased` (sonst): sucht Titel, matcht via Similarity-Score, mappt Season/Episode ГўВҶВ’ hГғВӨufig 0 Treffer bei Serien.

TmdbProvider ist der zuverlГғВӨssige Pfad. GermanProviders' Scraper sind alles *search-based* (kein TmdbProvider) ГўВҶВ’ das ist mit ein Grund, warum sie in ARVIO bei Serien oft leer bleiben.

### TmdbProvider-Vertrag (verifiziert am cloudstream3-Source `TmdbProvider.kt`)
- ARVIO ruft `load("{\"id\":<tmdbId>,\"type\":...}")`; Fallback `load("https://www.themoviedb.org/<type>/<id>")`. Beide Formen mГғВјssen `parseTmdbInput` akzeptieren.
- `load()` muss zurГғВјckgeben: `MovieLoadResponse` (Filme, `dataUrl`=JSON) ODER `TvSeriesLoadResponse` mit `Episode`-Liste (Serien, `episode.data`=URL).
- `loadLinks(data, ...)`: fГғВјr Filme ist `data` das JSON aus `dataUrl`; fГғВјr Serien ist `data` die Episoden-URL aus `episode.data`.
- `useMetaLoadResponse = false` (wir bauen die LoadResponse selbst, nicht ГғВјber TMDB-Meta-Provider).

### Filmpalast-Seitenstruktur (live verifiziert, Stand Aug 2026)
- Suche `/search/title/<query>`: listet Serien **pro Episode** (`/stream/silo-s03e06`), Filme als einzelne Seite. Keine Serien-Stammseite mit Staffeln.
- Stream-Seite `/stream/<slug>`: Hoster-Links in `ul.currentStreamLinks a.iconPlay` mit `data-player-url` (primГғВӨr) bzw. `href` (fallback).
- Gesehene Hoster: firestream.to, vidaraa.cc, voe.sx, vidsonic.net ГўВҶВ’ gemappt auf `Voe1`, `FileMoonSx`, `VidHidePro` (Ryderjet), `Supervideo` (AbstreamTo).

### Filmpalast-spezifische `load()`-Logik
1. TMDB-Meta holen (`api.themoviedb.org/3`, de-DE) ГўВҶВ’ `displayTitle` + `year`.
2. Filmpalast-Suche nach `displayTitle`.
3. Treffer matchen (normalisierter Titel-Vergleich, Typ movie/tv). Serie `"Silo S03E06"` ГўВҶВ’ Basisname `"Silo"` wird gegen TMDB-Titel gematcht.
4. Serie: alle Episoden sammeln ГўВҶВ’ `TvSeriesLoadResponse` (Season/Episode aus Titel geparst). Film: `MovieLoadResponse` mit `dataUrl=JSON{links:[...]}`.
5. `loadLinks`: FilmГўВҶВ’JSON-Links; SerieГўВҶВ’Episoden-URL fetchen + Host-Links sammeln ГўВҶВ’ `loadExtractor()` pro registriertem Hoster.

### Bekannte Vorbehalte (Proof-of-Concept)
- **Apple-TV+-Serien (Silo):** deutsche Scraper haben solche Titel u.U. nicht oder zeitverzГғВ¶gert. TMDB-Titel passt, aber Filmpalast muss die Serie im Katalog haben.
- **TMDB-API-Key:** fest codiert (ГғВ¶ffentlich bekannter Cloudstream-Key). FГғВјr Produktion ggf. eigener Key.
- **Hoster-Dead:** Filmpalast-Hosterdomains rotieren; Extractor-Mapping muss ggf. nachjustiert werden. Neue Domains via `registerExtractorAPI` hinzufГғВјgen.

### ГўВҡВ ГҜВёВҸ status-Wert MUSS 1 sein (verifiziert im ARVIO-Code)
Der cloudstream-gradle-plugin-Default ist `status = 3` ("Beta only"). **Das bricht ARVIO.**
- `PluginManager.downloadDexExtensions` (PluginManager.kt:1079): `manifestEnabled = plugin.status == 1`
- `PluginDataStore.setScraperEnabled` (PluginDataStore.kt:152): `if (enabled && !scraper.manifestEnabled) return` ГўВҶВ’ speichert das Enable **nicht**, wenn `manifestEnabled=false`.
- Folge: Plugin sichtbar in der Liste, aber Toggle speichert nicht ГўВҶВ’ Scraper lГғВӨuft nicht ГўВҶВ’ keine Quellen.
- **Fix:** Im Modul-`build.gradle.kts` IMMER `status = 1` setzen (wie GermanProviders: alle 21 Plugins `status=1`). Nie Default `3` lassen.

### ГўВҡВ ГҜВёВҸ Hoster-Extraktion: built-in cloudstream3-Extractoren nutzen, nicht re-registrieren (verifiziert)
Filmpalast rotiert Hostnamen pro Episode/Load. Verifizierte Hostnamen (Aug 2026):
- **Built-in in cloudstream3** (ARVIO lГғВӨdt sie via `ExternalExtractorRegistry.installGlobal()` automatisch): `voe.sx` (Voe), `firestream.to` (Firestream), `filemoon.sx` (FileMoonSx), `supervideo.cc` (Supervideo), `vidhide.com` (VidHidePro).
- **NICHT built-in** (Filmpalast-spezifisch, eigene Extractor-Aliase nГғВ¶tig): `ryderjet.com`, `abstream.to`.
- **Obskur / API-basiert** (kein statischer Extractor mГғВ¶glich): `vidaraa.cc`, `vidsonic.net`, `odysseusa.cc`, `MoneyGalactic.com` (JWPlayer mit `t.streaming_url` aus API-Call ГўВҖВ“ generischer Fallback findet nur sometimes direkte URLs).

**Fehler, der "no sources" verursachte (behoben in b6e3c1b):**
1. `loadLinks` setzte `any=true`, sobald `loadExtractor` *aufgerufen* wurde ГўВҖВ“ ignorierte den RГғВјckgabewert. Wenn alle `loadExtractor` `false` zurГғВјckgaben (kein passender Extractor), blieb `any` trotzdem `true` ГўВҶВ’ irrefГғВјhrend. Fix: `any` nur auf `true` wenn `loadExtractor` true ODER generischer Fallback findet URL.
2. `Voe1()` registriert ГўВҖВ“ `Voe1.mainUrl = "https://donaldlineelse.com"` (rotierender VOE-Mirror), matched **nicht** auf `voe.sx`-Links. Built-in `Voe()` (mainUrl=`voe.sx`) matched korrekt. Fix: `Voe1`/`FileMoonSx` nicht mehr re-registrieren (built-in reicht).
3. **Generischer Fallback** (`genericResolve`): fetcht Embed-Seite, sucht nach direkten mp4/m3u8-URLs (Regex). Best-Effort fГғВјr obskure JWPlayer-Hoster; fГғВӨngt nicht alle (vidaraa braucht API-Call), aber fГғВӨngt z.B. firestream-Video-Pfade.

### Recherche: ARVIO Test-Funktion & Log-MГғВ¶glichkeit (Aug 2026, ARVIO 1.9.983)
**ARVIO hat KEINE Log-Datei-Exportfunktion.** `DiagnosticsManager` ist nur fГғВјr Sentry/Crashlytics-Reporting, keine In-App-Log-Anzeige. Der einzige Weg an die Scraper-Logs zu kommen ist **Logcat** (`adb logcat` ГғВјber USB am PC).
- ARVIO hat im Code eine **"Test Scraper"-Funktion** (`PluginManager.testScraper()` ГўВҶВ’ `executeWithDiagnostics()`), die mit The Matrix (TMDB 603) testet und `TestDiagnostics` mit Einzelschritten zurГғВјckgibt (TMDB-Metadaten, search-Ergebnisse, HTTP-Requests, loadLinks, "Missing extractors: ..."). **ABER: der "Test"-Button ist in `PluginScreen.kt` NICHT in die UI eingebaut** ГўВҖВ“ Strings (`plugin_test_btn`, `plugin_diagnostics_expand`) und ViewModel-Logik existieren, aber kein Compose-Button ruft `PluginUiEvent.TestScraper` auf. Halbfertige ARVIO-Funktion (wie Stalker-VOD-UI).
- **WICHTIGE INKONSISTENZ:** `executeTmdbProviderWithDiagnostics` (Test-Pfad) ruft `loadLinks` mit `TmdbLink(...).toJson()` direkt auf (OHNE `load()`), wГғВӨhrend `executeTmdbProvider` (echte Suche) erst `api.load({"id":...,"type":...})` aufruft und `extractData()` das `dataUrl`/`episode.data` extrahiert. Mein `loadLinks` ist auf den load()-Pfad ausgelegt (`{"links":[...]}` oder `http`-URL), wГғВјrde also im Test-Pfad leer laufen. Falls ARVIO den Test-Button irgendwann aktiviert, muss mein `loadLinks` auch TmdbLink-JSON verarbeiten.

### Recherche: Touch-Bug auf Handy/Tablet (ARVIO Issue #502)
**BestГғВӨtigt und (teilweise) behoben in ARVIO 1.9.983.** ARVIO Issue #502 "fix(mobile): resolve touch issues and unify button styling in plugins settings":
- Ursache: Plugin-Settings-Screen + Add-Repo-Dialog nutzten TV-only `androidx.tv.material3.Surface`-Buttons, die auf Touch-GerГғВӨten nicht reagierten.
- Fix: `PluginScreen.kt` hat jetzt `LocalDeviceType.current.isTouchDevice()` ГўВҶВ’ separates Mobile-Layout mit touch-friendly Compose-Box-Buttons. **In 1.9.983 enthalten** (verifiziert: `isTouchDevice` existiert in `PluginScreen.kt`).
- Falls der Nutzer noch eine ГғВӨltere Version als 1.9.983 hat, sollte er updaten. Der Fix erklГғВӨrt, warum der Nutzer es ГғВјber Cloud-Profil auf dem Handy zum Laufen brachte.

### Recherche: "nur webstreamr-Quellen, nicht Filmpalast" ГўВҖВ“ mГғВ¶gliche Ursachen (Aug 2026)
Da webstreamr (Stremio-Addon, serverseitig) Quellen liefert, mein Filmpalast-Scraper (Cloudstream-DEX) aber nicht, sind die Scraper-Logs nГғВ¶tig. MГғВ¶gliche Ursachen (in absteigender Wahrscheinlichkeit):
1. **Scraper wird aufgerufen, aber `load()` schlГғВӨgt fehl** ГўВҶВ’ `loadResponse` null ГўВҶВ’ `executeTmdbProvider` "both load() paths failed" ГўВҶВ’ emptyList. KГғВ¶nnte ein Kotlin-spezifisches Problem sein (Jsoup-Selektor-Unterschied zu Python-Regex, oder Exception in `fetchTmdbMeta`/`searchFilmpalast`).
2. **Scraper ist nicht in `enabledScrapers`** ГўВҖВ“ Plugin-Download fehlgeschlagen, oder `manifestEnabled` false, oder Toggle aus. (Weniger wahrscheinlich, da `status=1` verifiziert und Plugin sichtbar ist.)
3. **`loadLinks` findet Hoster aber `loadExtractor` liefert 0 Links** ГўВҖВ“ Filmpalast rotiert Hostnamen; wenn nur nicht-built-in-Hoster (vidaraa.cc etc.) online, fГғВӨllt alles durch. (Mein generischer Fallback fГғВӨngt nur direkte mp4/m3u8.)
- **Ohne Logcat nicht eindeutig trennbar.** Logcat-Filter die helfen: `ExtExtractorRegistry`, `ExternalExtensionRunner`, `PluginManager`, `TmdbProvider Filmpalast`, `ExtExtRunner`.


Selbst bei korrekt aktiviertem Cloudstream-Scraper zeigt ARVIO oft "keine Streaming-Addons eingerichtet". Ursache ist eine ARVIO-seitige LogiklГғВјcke:
- `StreamRepository.getStreamAddons` (StreamRepository.kt:1440): `if (addon.runtimeKind != RuntimeKind.STREMIO) return@filter false` ГўВҶВ’ **nur Stremio-Addons** kommen in die Stream-Auswahl.
- `DetailsViewModel` berechnet `hasStreamingAddons` aus `streamRepository.installedAddons.count { it.isVodStreamingAddon() }` (DetailsViewModel.kt:1633) ГўВҶВ’ zГғВӨhlt **nur Stremio-Addons**, nicht Cloudstream-Scraper.
- Cloudstream-Scraper sind eine **getrennte Liste** (`PluginManager.scrapers`), nicht in `installedAddons` ГўВҶВ’ werden fГғВјr `hasStreamingAddons` nicht gezГғВӨhlt.
- **Aber:** `DetailsViewModel` (DetailsViewModel.kt:1516) ruft `pluginManager.executeScrapersStreaming()` separat auf ГўВҶВ’ Cloudstream-Scraper **laufen im Hintergrund** und mergen Streams in `streams`. Nur die *Meldung* ist falsch, nicht das Scraping.
- **Workaround:** ZusГғВӨtzlich ein (Dummy-)Stremio-Addon aktivieren ГўВҶВ’ `addonCount > 0` ГўВҶВ’ `hasStreamingAddons=true` ГўВҶВ’ Meldung verschwindet. Scraper-Ergebnisse erscheinen dann in der Liste.
- **ARVIO-seitiger Fix nГғВ¶tig:** `getStreamAddons`/`hasStreamingAddons` sollten auch EXTERNAL_DEX-Scraper zГғВӨhlen. Lohnt als GitHub-Issue.

### Build (lokal)
JDK 17+ und Android SDK 35 nГғВ¶tig. Im Env: `JAVA_HOME` + `ANDROID_HOME` (oder `local.properties` mit `sdk.dir`).
```
./gradlew make makePluginsJson
# -> FilmPalast/build/FilmPalast.cs3
# -> build/plugins.json
```

## Schritt-fГғВјr-Schritt: Diagnose-Log auslesen (v1.2+)

Das Plugin schreibt jeden Schritt des Filmpalast-Scrapers in einen internen Trace und stellt ihn ГғВјber einen lokalen HTTP-Server auf `http://localhost:8420/` bereit. So liest du das Log:

1. **Neues Plugin in ARVIO laden.** ARVIO-Einstellungen ГўВҶВ’ Plugins & Extensions ГўВҶВ’ Filmpalast aktualisieren/einschalten. Ab v1.2 startet beim Laden des Plugins automatisch der Diagnose-Server (im ARVIO-Prozess, nur loopback).
2. **Quellensuche auslГғВ¶sen** (das, was bisher leer blieb): ГғВ–ffne in ARVIO z.B. "Matrix" (Film) oder "Silo" (Serie) ГўВҶВ’ "nach Quellen suchen". Das triggert ARVIOs Aufruf von `load()`/`loadLinks()` und erzeugt Trace-EintrГғВӨge.
3. **Log im Handy-Browser ansehen:** ГғВ–ffne einen Browser auf **demselben GerГғВӨt**, auf dem ARVIO lГғВӨuft (Chrome/Firefox), und gehe zu `http://localhost:8420/`.
   - Die Seite aktualisiert sich automatisch alle 3 Sekunden.
   - `http://localhost:8420/raw` ГўВҶВ’ reiner Text (zum Kopieren).
   - `http://localhost:8420/clear` ГўВҶВ’ Trace lГғВ¶schen (vor einer neuen Suche).
4. **Trace lesen / interpretieren:**
   - **Gar kein Trace-Eintrag** nach einer Suche ГўВҶВ’ ARVIO ruft den Scraper nicht auf (ARVIO-Seite: `manifestEnabled`/`enabledScrapers`/`supportsType`). Der Diagnose-Server selbst sollte aber beim Plugin-Laden "listening on http://localhost:8420" geloggt haben ГўВҖВ“ taucht das nicht auf, lief das Plugin gar nicht.
   - `load: could not parse TMDB input` ГўВҶВ’ ARVIO ruft `load()` mit einem Format auf, das wir nicht erwarten.
   - `fetchTmdbMeta: request threw ...` ГўВҶВ’ TMDB-Erreichbarkeit/Key-Problem.
   - `searchFilmpalast: CSS selector matched 0 elements` ГўВҶВ’ Filmpalast-Seitenstruktur hat sich geГғВӨndert (Jsoup-Selektor veraltet) ODER Bot-Schutz/403.
   - `load: after matchResults -> 0 matches` ГўВҶВ’ Suche liefert Treffer, aber `matchResults` filtert alle raus (Titel-Normalisierung zu streng).
   - `loadLinks: 0 links -> returning false` ГўВҶВ’ `collectHosterLinks` findet nichts (Selektor/`data-player-url`-Attribut geГғВӨndert).
   - `loadExtractor('...') -> matched=false` fГғВјr ALLE Links ГўВҶВ’ keine built-in Extractoren fГғВјr die aktuellen Hoster-Domains.
5. **Log fГғВјr mich aufheben:** Entweder den `/raw`-Text kopieren und in der nГғВӨchsten Session einfГғВјgen, ODER die gespiegelte Datei `Android/data/com.arflix.tv/files/arvio-addon-logs/filmpalast-trace.log` (ab Android 13 evtl. nur ГғВјber ADB erreichbar).
6. **Falls der Browser die Seite nicht lГғВӨdt:** Server lГғВӨuft nur, solange der ARVIO-Prozess lebt. ARVIO zwischendrin nicht beenden. Alternativ via ADB: `adb forward tcp:8420 tcp:8420` dann am PC `curl http://localhost:8420/raw`.

## Versionshistorie dieses Addons

- **v1 (Proof-of-Concept):** Filmpalast-Plugin als TmdbProvider. Baut & kompiliert. Noch nicht in ARVIO endgeraet-getestet.
- **v1.1 (Aug 2026, Commits b6e3c1b bis 8aa09d3):** Hoster-Extraktion gefixt (loadLinks respektiert loadExtractor-Return; Voe1 entfernt; generischer Fallback fuer unbekannte Hostnamen); endgeraet-getestet in ARVIO 1.9.983 (sideload) von Nutzer. Plugin laedt, ist sichtbar & aktivierbar. **Aber:** bei Quellensuche (Matrix/Silo) zeigt ARVIO nur webstreamr-Quellen, nicht Filmpalast - Root-Cause offen, Logcat vom Geraet noetig (siehe "AKTUELLER STAND" ganz oben). AGENTS.md umfassend mit ARVIO-Scraper-Pfad, Touch-Bug-Fix #502, Test-Funktion-Status und Logcat-Optionen dokumentiert.
- **v1.2 (13.08.2026):** Selbst-Diagnose-Modus statt Logcat. `DebugLog.kt` + `DebugServer.kt` (lokaler HTTP-Server `localhost:8420`), `FilmpalastProvider` vollstГғВӨndig instrumentiert, Version auf 2 gebumpt. Ersetzt Logcat-Zugang fuer unseren eigenen Scraper-Code. Siehe "Schritt-fuer-Schritt: Diagnose-Log auslesen".
- **v1.3 (13.08.2026, Commits bis ca9f81f):** Diagnose-Tooling massiv ausgebaut, aber **Kernerkenntnis: ARVIO ruft .cs3-Plugins auf dem Geraet GAR NICHT auf.** Beweise: (a) GermanProviders (bewaehrtes .cs3-Repo) liefert auf dem Geraet ebenfalls 0 Quellen, (b) unsere v6-v8 haetten bei JEDEM loadLinks-Aufruf ArvioAddon-Debug-Quellen emittieren muessen - erschienen nie, (c) GitHub-Issues #459/#273 berichten exakt dasselbe Symptom. Webstreamr (Stremio-Addon) funktioniert = anderer ARVIO-Code-Pfad. Versionen: v3 DebugServer auf 127.0.0.1; v4 File-Trace+PLUGIN_LOADED Marker; v5 MediaStore->public Download; v6 Diagnose als Pseudo-Quellen in ARVIO-Quellenauswahl; v7 load() gibt nie null zurueck (debugLoadResponse) damit loadLinks garantiert laeuft; v8 Per-Call-Netzwerk-Timeouts. ARVIO library (TmdbProvider/MainAPI/Plugin) verifiziert vorhanden in classes3/4.dex. ARVIO-Timeouts (120s/60s) schliessen Timeout als Ursache aus. **Naechster Schritt: mit Laptop weiter (Logcat via USB+adb); ggf. GitHub-Issue bei ARVIO.** Siehe "AKTUELLER STAND" ganz oben.
- **v9-v13 (14.08.2026, Logcat-Aera):** Nach USB-ADB+Logcat am TV: Erkenntnis #1 (.cs3 nie heruntergeladen bei Cloud-Sync) → Erkenntnis #2 (kotlin/io/FilesKt von R8 geshrinkt) → FIX #2 (v9: kotlin-stdlib-IO entfernt) → Erkenntnis #3 (DebugServer-Thread-Crash) → FIX #3 (v10: DebugServer removed) → Erkenntnis #4 (kotlin.collections.SetsKt von R8 geshrinkt) → FIX #4 (v11: kotlin-stdlib in .cs3 gebundled) → Erkenntnis #5 (mainPageOf von R8 geshrinkt) → FIX #5 (v12: listOf(MainPageData)) → Erkenntnis #6 (MainPageData-ctor von R8 geshrumpft) → FIX #6 (v13: mainPage komplett entfernt). v13 laedt erstmals VOLLSTAENDIG (Provider+Extractoren registriert, "API loaded" bestätigt).
- **Erkenntnis #7 (14.08.2026, v13-DEX+APK-Analyse):** **Root-Cause gefunden.** ARVIOs R8 hat `kotlin.coroutines.Continuation` zu `j7.d` obfuscated. Unsere suspend-Override-Methoden (load/loadLinks/search) haben `Lkotlin/coroutines/Continuation;` in der Signatur, ARVIOs Parent hat `Lj7/d;` → JVM findet Override nicht → parent laeuft → `ErrorLoadingException: No id found` → 0 Quellen. **Betrifft ALLE externen .cs3-Plugins.** Geplanter Fix #7: gegen ARVIOs obfuscated cloudstream3-JAR kompilieren (dex2jar aus APK extrahieren). Siehe "ENTSCHEIDENDE ERKENNTNIS #7" oben.
