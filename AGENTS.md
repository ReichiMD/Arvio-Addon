# AGENTS.md – Ventix Arvio Addon

Dieses Repo baut ein **Cloudstream3-kompatibles Plugin** für die **ARVIO** Android-TV-App (sideload-APK).
Ziel: Ventix-Funktionalität (deutsche Web-Scraper + Stalker-VOD) als Plugin in ARVIO laufen lassen – clientseitig, ohne Server.

## Status: Brainstorming abgeschlossen → Implementation steht aus

Dieses Repo ist aktuell leer. Die nächste Session startet die Implementation.
Alle Entscheidungen unten sind **verifiziert** (Code von ARVIO + GermanProviders gelesen, nicht geraten).

---

## Entscheidung: Welcher Plugin-Typ?

**Gewählt: Cloudstream3-Plugin (Kotlin/DEX, ".cs3")** – der "mächtige" Weg.

| Grund | Detail |
|---|---|
| Eigene Konfig-Seite (Portal-URL/MAC) | Nur Cloudstream3-Plugins können UI-Settings haben → nötig für Stalker-VOD |
| Eigene Kataloge/Startseiten in ARVIO | Nur Cloudstream3-Plugins liefern eigene Start-Kataloge |
| Kotlin = gleiche Sprache wie Ventix | Ventix-Scraper (Kotlin) lassen sich **direkt portieren**, nicht nach JS übersetzen |
| Viele Vorlagen | GermanProviders-Repo (Bnyro) ist eine komplette Vorlage mit exakt unseren Scraper-Hostern |
| Cloudstream3-Ökosystem | ARVIO nutzt library v4.7.0; apiVersion 1 ist kompatibel |

**Abgewählt: Nuvio-JS-Plugin** (Weg A) – einfacher, kann aber nur Streams liefern, keine Config-Seite, keine eigenen Kataloge. Da wir Stalker-VOD brauchen (mit Portal/MAC-Eingabe), reicht JS-Plugin nicht.

---

## Ziel: ARVIO-Installation des fertigen Plugins

Der Nutzer installiert das Plugin so in ARVIO (verifizierter Flow):
1. ARVIO **sideload-APK** installieren (nicht Play-Store-Version!)
2. Einstellungen → **Plugins & Extensions** (nur in sideload sichtbar)
3. **Add Repository** → Repo-URL eintragen
4. ARVIO lädt `repo.json` → folgt `pluginLists` → lädt `plugins.json`
5. Plugin-Einträge einschalten → ARVIO lädt `.cs3`-Datei (kompilierter Code)

### Bekannter Bug (Stand Aug 2026): Add-Repo-Dialog auf Handy
Der "Add Repository"-Dialog in ARVIO hat `width(520.dp)` – breiter als Handy-Hochformat (~390dp). Buttons können abgeschnitten/inaktiv wirken.
- **Workaround:** Querformat, oder Tastatur vorher schließen, oder Tablet nutzen.
- Wird vermutlich von ARVIO gefixt (aktives Projekt, 18 Releases in 5 Monaten).

---

## Was das Plugin können muss (Scope)

### Modul 1: Deutsche Web-Scraper (Filmpalast, Serienstream, HdFilme, Megakino, KinoGer, Netzkino, AniWorld)
- **Vorlage:** GermanProviders-Repo (Bnyro/GermanProviders) – hat ALL diese Scraper schon als Cloudstream3-Plugins!
- Möglichkeit 1: GermanProviders forken + anpassen (wenig Eigenarbeit, abhängig von Upstream)
- Möglichkeit 2: Eigenes Plugin schreiben, GermanProviders als Referenz (volle Kontrolle)

### Modul 2: Stalker-VOD (Filme + Serien über Stalker-Portal)
- **Das ist die Neuentwicklung** – GermanProviders hat das nicht.
- ARVIOs eingebaute StalkerApi kennt NUR Live-TV (get_genres, get_all_channels, create_link) – **KEIN VOD, keine Serien**.
- Plugin braucht: eigene Config-Seite (Portal-URL + MAC), VOD-Kategorien, VOD-Liste, createVodLink, Serien/Staffeln/Episoden.
- Vorlage: Ventix-StalkerApi (17 Methoden) – in Kotlin, direkt portierbar.

### Modul 3: Stalker Live-TV
- **Nicht bauen** – ARVIO hat das schon eingebaut (obwohl die UI aktuell fehlt, siehe "ARVIO-Mängel").

---

## Architektur-Referenz: ARVIOs Plugin-System

### Plugin-Formate die ARVIO versteht (verifiziert im Code)
1. **Nuvio-JS-Plugin**: `manifest.json` + `.js`-Dateien mit `getStreams(tmdbId, type, season, episode)`. Engine: QuickJS + Cheerio + CryptoJS. (abgewählt)
2. **Cloudstream3-Plugin (EXTERNAL_DEX)**: `.cs3`-Datei (kompiliertes DEX). Engine: cloudstream3-library v4.7.0. (**gewählt**)

### ARVIO Repository-Manifest-Format (`repo.json`)
```json
{
  "name": "Ventix Arvio Addon",
  "description": "Deutsche Scraper + Stalker VOD für ARVIO",
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
- `@CloudstreamPlugin`-annotierte `Plugin`-Klasse → `registerMainAPI(...)` + `registerExtractorAPI(...)`
- `MainAPI`-Subklasse → `mainUrl`, `name`, `supportedTypes`, `mainPage`, `search()`, `load()`, `loadLinks()`
- `ExtractorApi`-Subklassen für Hoster (VOE, FileMoon, Supervideo, VidHidePro etc.)

---

## Ventix-Referenz (Quell-Projekt, NICHT in dieses Repo kopieren)

Ventix liegt im Schwester-Repo `ReichiMD/IPTV-App`. Scraper-Quellcode zum Portieren:
- `app/src/main/java/com/iptv/stalker/data/scraping/` – FilmpalastScraper, HdFilmeScraper, KinogerScraper, MegakinoScraper, SerienstreamScraper, AniWorldScraper, NetzkinoScraper + extractor/
- `app/src/main/java/com/iptv/stalker/data/api/StalkerApi.kt` – Stalker-Middleware (17 Methoden: handshake, get_profile, get_events, VOD-+Serien-+EPG-Endpoints, createVodLink, getSeasons, M3U-Export)
- `app/upstream-reference/` – Cloudstream3-Upstream-Referenzen (bereits als Referenz genutzt!)
- `VideoHostExtractor.kt` – Hoster-Extraktoren (VOE, FileMoon, VidGuard, Veev, Vidsonic, DoodStream etc.)

Ventix und ARVIO nutzen BEIDE Cloudstream3-Upstream-Referenzen – das vereinfacht das Portieren.

---

## GermanProviders-Referenz (Vorlage-Repo, geklont nach /tmp/german-providers)

`Bnyro/GermanProviders` – Cloudstream3-Multi-Provider-Repo, hat bereits 21 fertige Plugins:
ARD, Aniworld, Arte, C3TV, Discovery, EinschaltenIn, **FilmPalast**, HDFilme, HuhuTo, IptvOrg, KinoKing, **Kinoger**, **Megakino**, Moflix, **Netzkino**, PlutoTV, **Serienstream**, Southpark, SpiegelTV, Welt, Xcine.

Installations-URL (Test): `https://raw.githubusercontent.com/Bnyro/GermanProviders/refs/heads/master/repo.json`
- `builds`-Branch enthält `plugins.json` + fertige `.cs3`-Dateien.
- Aufbau: root `build.gradle.kts` (cloudstream3-gradle-plugin `com.github.recloudstream:gradle`), pro Provider ein Modul-Ordner mit `build.gradle.kts` + `src/`.
- Settings-Gradle: auto-include aller Modul-Ordner.

**Diese Scraper (Filmpalast, Serienstream etc.) sind identisch mit Ventix' Ziel-Set.** GermanProviders ist die primäre Vorlage für Modul 1.

---

## ARVIO-Referenz (geklot nach /tmp/arvio_ref)

`ProdigyV21/ARVIO` – die Ziel-App. Version 1.9.983 (versionCode 306), sehr aktiv.

### ARVIO Build-Flavors (verifiziert in `app/build.gradle.kts`)
| Flavor | `FEATURE_PLUGINS_ENABLED` | `SELF_UPDATE_ENABLED` | Plugin-Engine |
|---|---|---|---|
| `play` (Play Store) | **false** | false | ❌ abgeschaltet |
| `sideload` (GitHub-APK) | **true** | true | ✅ voll aktiv |

→ **Plugin funktioniert NUR in der sideload-APK**, nicht im Play-Store-Build. Google-Policy verbietet dynamischen Code im Store.

### ARVIO sideload-Download
`https://github.com/ProdigyV21/ARVIO/releases/download/v1.9.983/ARVIO-v1.9.983-sideload-release.apk` (135 MB)

### ARVIO Plugin-Engine (nur in `app/src/sideload/`)
- `PluginManager.kt` – Repository-Verwaltung, `addRepository()`, Format-Auto-Detection
- `PluginRuntime.kt` – QuickJS-Engine (für JS-Plugins) + `__native_fetch`, `__cheerio_*`, CryptoJS
- `cloudstream/ExternalExtensionLoader.kt` – lädt `.cs3`-Plugins via DexClassLoader
- `cloudstream/ExternalExtensionRunner.kt` – führt `MainAPI.search()`/`load()`/`loadLinks()` aus
- `cloudstream/ExternalExtractorRegistry.kt` – verwaltet `ExtractorApi`-Extraktoren
- `cloudstream/ExternalRepoParser.kt` – parsed `repo.json` (erkennt `"pluginLists"`-Key) + `plugins.json`

### ARVIO-Mängel (Stand Aug 2026, die wir im Plugin adressieren)
1. **Stalker-VOD fehlt komplett** – StalkerApi kennt nur Live-TV (4 Methoden: handshake, getProfile, getChannels, resolveStreamUrl). Kein VOD, keine Serien, kein EPG für VOD.
2. **Stalker-Dateneingabe fehlt in der UI** – `saveStalkerConfig()` existiert im SettingsViewModel (Zeile 2280), wird aber von KEINEM UI-Element aufgerufen. Kein Button, kein Dialog. Backend halbfertig, UI fehlt.
3. **Add-Repo-Dialog Handy-Bug** – `width(520.dp)` zu breit für Hochformat (Workaround: Querformat).

---

## Stremio-Addon-Referenz (paralleles Projekt)

`ReichiMD/Stremio-Addon` – serverseitiges Node.js-Addon (deployed auf Render), Quellen: Vavoo/KinoGer/Filmpalast/MovieBox/VidSrc/Einschalten + MediaFlowProxy.
- **Problem:** Stremio-Addon (serverseitig) kann manche Streams nicht liefern (z.B. KinoGer 403 – Render-DC-IP blockiert). Ventix (clientseitig) kann das.
- **Dieses ARVIO-Addon löst das:** läuft clientseitig in der App → Endgerät-IP → kein Bot-Schutz → kein Server, kein Geld.
- Logik der Scraper ist im Stremio-Addon bereits in JavaScript/TypeScript vorhanden (kann als Referenz dienen, wird aber neu in Kotlin als Cloudstream3-Plugin geschrieben).

`ReichiMD/mediaflow-proxy` – MediaFlowProxy (fest codiert im Stremio-Addon). Für ARVIO-Addon nicht nötig (clientseitig braucht keinen Proxy).

---

## Build & Release

### Plugin kompilieren (Cloudstream3-gradle-plugin)
- Multi-Modul-Setup wie GermanProviders: root `build.gradle.kts` mit `com.github.recloudstream:gradle`-Plugin, pro Plugin ein Modul.
- Output: `.cs3`-Datei pro Plugin-Modul.
- CI: GitHub Actions baut `.cs3`-Dateien, pusht sie auf einen `builds`-Branch (wie GermanProviders), generiert/aktualisiert `plugins.json`.

### Datei-Struktur (geplant)
```
Arvio-Addon/
├── AGENTS.md                          # diese Datei
├── README.md                          # (später)
├── build.gradle.kts                   # root, cloudstream3-gradle-plugin
├── settings.gradle.kts                # auto-include Module
├── repo.json                          # Installations-Manifest für ARVIO
├── GermanScraper/                     # Modul 1: deutsche Web-Scraper (oder pro Scraper ein Modul)
│   ├── build.gradle.kts
│   └── src/main/kotlin/.../GermanScraperPlugin.kt + Provider + Extractors
└── StalkerVod/                        # Modul 2: Stalker-VOD (Config-Seite + VOD/Serien)
    ├── build.gradle.kts
    └── src/main/kotlin/.../StalkerVodPlugin.kt + StalkerApi + Provider
```

### branches
- `main` – Quellcode
- `builds` – fertige `.cs3`-Dateien + `plugins.json` (von CI gepusht, wie GermanProviders)

---

## Nächste Schritte (Priorität)

1. **Proof-of-Concept:** GermanProviders in ARVIO-sideload testen (Button-Bug workaronden) → prüfen welche Scraper laufen.
2. **Repo-Setup:** GermanProviders-Architektur (root build.gradle + Modul-Struktur) hier nachbauen.
3. **Modul 1 (Web-Scraper):** GermanProviders-Plugins adaptieren ODER eigene Implementierung. Hoster-Extraktoren (VOE, FileMoon etc.) aus Ventix' `VideoHostExtractor` portieren.
4. **Modul 2 (Stalker-VOD):** Ventix' `StalkerApi.kt` (VOD/Serien-Teil) als Cloudstream3-Provider portieren + Config-Seite für Portal/MAC.
5. **CI:** GitHub Actions workflow für `.cs3`-Build + `builds`-Branch-Push.

---

## Versionshistorie dieses Addons

(noch keine – Repo ist leer)

---

## Recherche: ARVIO-Plugin-Integration (Stand Aug 2026, ARVIO v1.9.983)

Verifiziert im ARVIO-Quellcode (`ProdigyV21/ARVIO` @ v1.9.983, geklont nach `/tmp/arvio_ref`).
Recherche anlässlich zweier Nutzer-Probleme beim Testen von GermanProviders als Cloudstream3-Plugin in ARVIO.

### Problem 1: Plugin-Einrichtung funktioniert nur auf TV, nicht auf Handy/Tablet

**Beobachtung (Nutzer):** Add-Repository / Plugin-Aktivierung ging auf Handy & Tablet nicht; erst ein ARVIO-Cloud-Profil (auf TV erstellt, aufs Handy synchronisiert) brachte die Plugins aufs Handy. TV funktionierte direkt.

**Rechercheergebnis:**
- ARVIO hat ein Layout-Force-Feature ("Force TV, Tablet, or Phone layout") UND Auto-Detect für TV-Modus bei Geräten ohne Touchscreen (CHANGELOG v1.9.3). Die UI wird je Formfaktor unterschiedlich gerendert.
- Der Plugin-Bereich wurde in v1.9.983 neu gebaut: CHANGELOG-Eintrag "redesigned plugin settings for TV and mobile. Contributor: @Himanth-reddy via #466" – d.h. die mobile Plugin-UI ist **sehr neu** (Juli 2026).
- Begleitend in v1.9.983: "Fixed sideload production-plugin routing, extractor unloading, **mobile routing**, and TV focus limits" (#466) – ein mobiler Routing-Fix wurde *explizit* für diese Version gebraucht. Das deutet darauf hin, dass mobile Plugin-Pfade vorher fehlerhaft waren.
- Ein **bekannter, älterer Bug** (AGENTS.md bereits notiert): Add-Repo-Dialog `width(520.dp)` zu breit für Handy-Hochformat (~390dp) → Buttons abgeschnitten/inaktiv im Hochformat.
- Vergleichs-Befund aus dem Nuvio-Ökosystem (Schwester-App, gleiche Plugin-Architektur): NuvioMobile Issue #1190 – *"If Cloudstream Plugin Repositories are loaded in the Plugins list in the Mobile app, they get removed from Plugins list in the TV app"* (closed as not planned). Cloudstream-Plugin-Listen zwischen Mobile- und TV-UI synchron halten ist **branchenweit ein Problem**, nicht ARVIO-spezifisch.

**Fazit Problem 1:** Sehr wahrscheinlich ein **ARVIO-seitiger Bug in der (neuen) mobilen Plugin-UI** – entweder Routing (in v1.9.983 gerade erst gefixt, evtl. nicht vollständig) oder der bekannte `width(520.dp)`-Dialog-Bug. Dass der Cloud-Sync-Workaround funktioniert, bestätigt: Die Plugin-Daten selbst sind korrekt; nur die mobile Einrichtungspath-UI ist defekt. Keine andere Nutzerberichte als direktes Duplikat gefunden, aber die CHANGELOG-Historie (mobiler Plugin-Routing-Fix in der *aktuellen* Version) zeigt, dass ARVIO genau diese Klasse von Bug gerade behebt.

**Workarounds für Nutzer:** Querformat beim Add-Repo; oder Plugin-Konfiguration auf TV vornehmen + ARVIO-Cloud-Sync aufs Handy (funktioniert laut Nutzer bereits); oder `web.arvio.tv` (Web-App, vollständige ARVIO-UI im Browser, laut CHANGELOG mit TV-D-pad-Navigation).

### Problem 2: Aktivierte Provider erscheinen nicht bei Quellensuche ("kein Add-on eingerichtet / keine Quellen")

**Beobachtung (Nutzer):** In den Plugin-Einstellungen Provider (z.B. Einschalten) aktiviert → auf eine Silo-Episode gegangen → "nach Quellen gesucht" → Meldung "kein Add-on eingerichtet, keine Quellen gefunden".

**Verifizierte Ursache im ARVIO-Code:** ARVIO hat **zwei komplett getrennte Quell-Auflösungspfade**, und Cloudstream3-Plugins (.cs3) laufen über den Pfad, der die "kein Add-on"-Meldung **nicht steuert**:

1. **Stremio-Addon-Pfad** (`StreamRepository` + `AddonRuntimeAggregator`): Hier laufen klassische Stremio-kompatible Addons (HTTP `stream/movie/<imdbId>.json`), Home-Server (Jellyfin/Plex/Emby) und HTTP-Local-Scrapers. Die UI-Variable `hasStreamingAddons` (die "No Streaming Addons" / "kein Add-on eingerichtet" anzeigt) wird **ausschließlich** aus `streamRepository.installedAddons.count { it.isVodStreamingAddon() }` berechnet (`DetailsViewModel.kt` Z. 1600/1633/1650/1689). `isVodStreamingAddon()` prüft nur `isEnabled && type != SUBTITLE && !sportsOnly` – das sind Stremio-Addons, **keine Cloudstream-Scraper**. Filter `getStreamAddons()` (`StreamRepository.kt` Z. 1440) wirft sogar hart raus: `if (addon.runtimeKind != RuntimeKind.STREMIO) return@filter false` – und `RuntimeKind` kennt nur `STREMIO`/`TELEGRAM`, keinen Cloudstream/EXTERNAL_DEX-Wert (`Models.kt` Z. 305).

2. **Cloudstream-Plugin-Pfad** (`PluginManager` + `ExternalExtensionRunner`, sideload-only): Aktivierte `.cs3`-Scraper werden in `DetailsViewModel.loadStreams()` über `pluginManager.executeScrapersStreaming(...)` in einem **parallelen Job** (`pluginScraperJob`, Z. 1510–1552) ausgeführt. Ergebnisse mergen sich asynchron in `streams`. Dieser Pfad startet **nur**, wenn `dataStore.pluginsEnabled` true ist UND `enabledScrapers` (nach `supportsType(mediaType)`) nicht leer ist (`PluginManager.kt` Z. 631–640, 681).

**Warum trotzdem "kein Add-on"-Meldung + keine Quellen bei Silo:** Weil `hasStreamingAddons` Stremio-Addons zählt. Hat der Nutzer **kein einziges** Stremio-Addon installiert (nur Cloudstream-Plugins), ist `hasStreamingAddons=false` → UI zeigt "No Streaming Addons / kein Add-on eingerichtet" an. Die Meldung ist in diesem Fall **irreführend**: Die Cloudstream-Scraper suchen im Hintergrund trotzdem, finden aber für "Silo" vermutlich nichts (siehe Problem 2b), und die UI bleibt bei der "Setup Required"-Meldung stehen, obwohl die Plugins aktiv sind.

**Problem 2b – warum die Cloudstream-Scraper für "Silo" trotzdem 0 Quellen liefern (verifiziert):**
GermanProviders-Plugins (Filmpalast, Serienstream, AniWorld etc.) sind **keine** `TmdbProvider` (sie überschreiben nicht `load()` für TMDB-JSON), sondern **search-basierte** `MainAPI`-Provider. ARVIOs `ExternalExtensionRunner.executeSearchBased()` (Z. 473–620) macht für search-basierte Provider:
1. TMDB-Enrichment holen → `localizedTitle` + `year` + alt-Titel
2. `api.search(title)` aufrufen + bei Trefferlosigkeit Retry mit vereinfachtem Titel und parallelen Alt-Titeln
3. `findBestMatch()` (Ähnlichkeits-Score) über Suchergebnisse → `api.load(bestMatch.url)` → `extractData()` → `api.loadLinks()`

Scheitern kann es an **mehreren Stellen**:
- **Sprache:** Silo ist eine Apple TV+-Serie. Deutsche Scraper wie Filmpalast/Serienstream listen "Silo" u.U. nur unter deutschem Titel oder garnicht (Apple-TV+-Originals sind seltener auf deutschen Scraper-Seiten als Netflix/Prime). TMDB `localizedTitle` für Silo DE = "Silo" – passt, aber die Scraper-Seite muss die Serie auch im Katalog haben.
- **`findBestMatch`-Mismatch:** Wenn der Scraper "Silo" z.B. als "Silo - Season 1" oder mit Jahr-Abweichung zurückgibt, fällt der Similarity-Score unter die Schwelle → `return emptyList()` (Z. 567). Das ist ein **häufiges** Cloudstream-Problem bei ARVIO, weil ARVIO eigenes Title-Matching macht statt die Provider-`load()` direkt mit der Scraper-eigenen URL zu füttern.
- **Season/Episode-Mapping:** `extractData(loadResponse, mediaType, season, episode)` baut das `data`-JSON, das `loadLinks()` erwartet. Bei Serien muss `load()` eine `TvSeriesLoadResponse` liefern, aus der ARVIO die Episoden-URL extrahiert. GermanProviders' `load()`-Implementierungen sind für Cloudstream3-App geschrieben; ARVIO ruft sie leicht anders auf → kann `data=null` geben → `return emptyList()` (Z. 590).
- **Host-Dead / Bot-Schutz:** Deutsche Scraper-Seiten blockieren oft. ARVIO fängt `hostUnreachable` ab und skippt (Z. 552). Da ARVIO clientseitig läuft (Gerät-IP), sollte das seltener sein als beim serverseitigen Stremio-Addon – aber möglich.

**Fazit Problem 2:** Zwei Dinge überlagern sich:
- (a) **ARVIO-UI-Bug/Designschwäche:** Die "kein Add-on eingerichtet"-Meldung wird nur aus dem Stremio-Addon-Pfad gespeist und ignoriert aktivierte Cloudstream-Plugins vollständig. Solange kein Stremio-Addon aktiv ist, zeigt die UI "Setup Required", **selbst wenn** Cloudstream-Scraper im Hintergrund laufen. Das ist eine ARVIO-seitige Logiklücke, nicht des Addons Schuld.
- (b) **Scraper-Matching:** Selbst wenn die Cloudstream-Scraper laufen, liefern sie für bestimmte Titel (wie Silo) oft 0 Treffer wegen ARVIOs eigenem Title-Matching / `findBestMatch` / Episode-Mapping, das nicht 1:1 der Cloudstream3-App entspricht.

CHANGELOG-Belege, dass ARVIO dieses Themenfeld aktiv bearbeitet:
- v1.9.983: "Added compatibility for Nuvio-style JavaScript scraper plugins and redesigned plugin settings for TV and mobile" (#466) + "Fixed sideload production-plugin routing, extractor unloading, mobile routing, and TV focus limits" (#466)
- v1.9.92: "Improved FlixStreams/anime addon matching and fallback stream lookup for episode sources" + "Fixed configured add-ons occasionally failing to appear in the source list until a later retry"
- v1.8.2: "Source selector shows setup instructions instead of generic 'No sources found' when no addons are installed" + "When no streaming addons are configured, the app now shows a friendly setup guide instead of a playback error"

**Handlungsempfehlung (für unser Addon / Nutzer):**
1. **Für saubere UI-Anzeige:** Zusätzlich zu den Cloudstream-Plugins **mindestens ein** Stremio-Addon (auch ein inaktives/dummy) installieren, damit `hasStreamingAddons=true` wird und die Meldung verschwindet. Das ist ein Workaround für ARVIOs Logiklücke (a).
2. **Für echte Quellen bei Serien wie Silo:** Eigenes ARVIO-Addon bauen (Ziel dieses Repos) – aber dabei darauf achten, dass die `MainAPI`-Implementierung robustes `search()` + `load()` + `loadLinks()` bietet, das ARVIOs `findBestMatch`-basiertem Aufruffluss standhält. Ideal: Provider als `TmdbProvider` implementieren (dann nimmt ARVIO den direkteren `executeTmdbProvider`-Pfad ohne fragiles Title-Matching). Das ist eine **Konsequenz für die Modul-1-Architektur** dieses Addons.
3. **GitHub-Issue bei ARVIO erwägen:** (a) ist klar ein ARVIO-Bug ("hasStreamingAddons ignoriert aktivierte Cloudstream-Scraper"). Lohnt sich als Issue zu melden, da ARVIO aktiv ist (18 Releases in 5 Monaten) und #466 genau dieses Gebiet gerade anfasst.

---

## Implementation: Filmpalast-Plugin als TmdbProvider (Proof-of-Concept)

**Status: gebaut und kompiliert.** `FilmPalast/build/FilmPalast.cs3` (≈23 KB) + `build/plugins.json` werden lokal via `./gradlew make makePluginsJson` erzeugt; CI (`.github/workflows/build.yml`) pusht beides auf den `builds`-Branch.

### Architektur-Entscheidung (verbindlich für alle Modul-1-Scraper)
**Alle Provider als `TmdbProvider` implementieren**, nicht als plain `MainAPI`. Begründung (siehe oben "Recherche"): ARVIO hat zwei Dispatch-Pfade in `ExternalExtensionRunner.execute()`:
- `executeTmdbProvider` (wenn `api is TmdbProvider`): ruft `api.load("{\"id\":<tmdbId>,\"type\":\"movie\"|\"tv\"}")` direkt auf → kein fragiles `findBestMatch`-Title-Matching.
- `executeSearchBased` (sonst): sucht Titel, matcht via Similarity-Score, mappt Season/Episode → häufig 0 Treffer bei Serien.

TmdbProvider ist der zuverlässige Pfad. GermanProviders' Scraper sind alles *search-based* (kein TmdbProvider) → das ist mit ein Grund, warum sie in ARVIO bei Serien oft leer bleiben.

### TmdbProvider-Vertrag (verifiziert am cloudstream3-Source `TmdbProvider.kt`)
- ARVIO ruft `load("{\"id\":<tmdbId>,\"type\":...}")`; Fallback `load("https://www.themoviedb.org/<type>/<id>")`. Beide Formen müssen `parseTmdbInput` akzeptieren.
- `load()` muss zurückgeben: `MovieLoadResponse` (Filme, `dataUrl`=JSON) ODER `TvSeriesLoadResponse` mit `Episode`-Liste (Serien, `episode.data`=URL).
- `loadLinks(data, ...)`: für Filme ist `data` das JSON aus `dataUrl`; für Serien ist `data` die Episoden-URL aus `episode.data`.
- `useMetaLoadResponse = false` (wir bauen die LoadResponse selbst, nicht über TMDB-Meta-Provider).

### Filmpalast-Seitenstruktur (live verifiziert, Stand Aug 2026)
- Suche `/search/title/<query>`: listet Serien **pro Episode** (`/stream/silo-s03e06`), Filme als einzelne Seite. Keine Serien-Stammseite mit Staffeln.
- Stream-Seite `/stream/<slug>`: Hoster-Links in `ul.currentStreamLinks a.iconPlay` mit `data-player-url` (primär) bzw. `href` (fallback).
- Gesehene Hoster: firestream.to, vidaraa.cc, voe.sx, vidsonic.net → gemappt auf `Voe1`, `FileMoonSx`, `VidHidePro` (Ryderjet), `Supervideo` (AbstreamTo).

### Filmpalast-spezifische `load()`-Logik
1. TMDB-Meta holen (`api.themoviedb.org/3`, de-DE) → `displayTitle` + `year`.
2. Filmpalast-Suche nach `displayTitle`.
3. Treffer matchen (normalisierter Titel-Vergleich, Typ movie/tv). Serie `"Silo S03E06"` → Basisname `"Silo"` wird gegen TMDB-Titel gematcht.
4. Serie: alle Episoden sammeln → `TvSeriesLoadResponse` (Season/Episode aus Titel geparst). Film: `MovieLoadResponse` mit `dataUrl=JSON{links:[...]}`.
5. `loadLinks`: Film→JSON-Links; Serie→Episoden-URL fetchen + Host-Links sammeln → `loadExtractor()` pro registriertem Hoster.

### Bekannte Vorbehalte (Proof-of-Concept)
- **Apple-TV+-Serien (Silo):** deutsche Scraper haben solche Titel u.U. nicht oder zeitverzögert. TMDB-Titel passt, aber Filmpalast muss die Serie im Katalog haben.
- **TMDB-API-Key:** fest codiert (öffentlich bekannter Cloudstream-Key). Für Produktion ggf. eigener Key.
- **Hoster-Dead:** Filmpalast-Hosterdomains rotieren; Extractor-Mapping muss ggf. nachjustiert werden. Neue Domains via `registerExtractorAPI` hinzufügen.

### ⚠️ status-Wert MUSS 1 sein (verifiziert im ARVIO-Code)
Der cloudstream-gradle-plugin-Default ist `status = 3` ("Beta only"). **Das bricht ARVIO.**
- `PluginManager.downloadDexExtensions` (PluginManager.kt:1079): `manifestEnabled = plugin.status == 1`
- `PluginDataStore.setScraperEnabled` (PluginDataStore.kt:152): `if (enabled && !scraper.manifestEnabled) return` → speichert das Enable **nicht**, wenn `manifestEnabled=false`.
- Folge: Plugin sichtbar in der Liste, aber Toggle speichert nicht → Scraper läuft nicht → keine Quellen.
- **Fix:** Im Modul-`build.gradle.kts` IMMER `status = 1` setzen (wie GermanProviders: alle 21 Plugins `status=1`). Nie Default `3` lassen.

### ⚠️ Hoster-Extraktion: built-in cloudstream3-Extractoren nutzen, nicht re-registrieren (verifiziert)
Filmpalast rotiert Hostnamen pro Episode/Load. Verifizierte Hostnamen (Aug 2026):
- **Built-in in cloudstream3** (ARVIO lädt sie via `ExternalExtractorRegistry.installGlobal()` automatisch): `voe.sx` (Voe), `firestream.to` (Firestream), `filemoon.sx` (FileMoonSx), `supervideo.cc` (Supervideo), `vidhide.com` (VidHidePro).
- **NICHT built-in** (Filmpalast-spezifisch, eigene Extractor-Aliase nötig): `ryderjet.com`, `abstream.to`.
- **Obskur / API-basiert** (kein statischer Extractor möglich): `vidaraa.cc`, `vidsonic.net`, `odysseusa.cc`, `MoneyGalactic.com` (JWPlayer mit `t.streaming_url` aus API-Call – generischer Fallback findet nur sometimes direkte URLs).

**Fehler, der "no sources" verursachte (behoben in b6e3c1b):**
1. `loadLinks` setzte `any=true`, sobald `loadExtractor` *aufgerufen* wurde – ignorierte den Rückgabewert. Wenn alle `loadExtractor` `false` zurückgaben (kein passender Extractor), blieb `any` trotzdem `true` → irreführend. Fix: `any` nur auf `true` wenn `loadExtractor` true ODER generischer Fallback findet URL.
2. `Voe1()` registriert – `Voe1.mainUrl = "https://donaldlineelse.com"` (rotierender VOE-Mirror), matched **nicht** auf `voe.sx`-Links. Built-in `Voe()` (mainUrl=`voe.sx`) matched korrekt. Fix: `Voe1`/`FileMoonSx` nicht mehr re-registrieren (built-in reicht).
3. **Generischer Fallback** (`genericResolve`): fetcht Embed-Seite, sucht nach direkten mp4/m3u8-URLs (Regex). Best-Effort für obskure JWPlayer-Hoster; fängt nicht alle (vidaraa braucht API-Call), aber fängt z.B. firestream-Video-Pfade.

### Recherche: ARVIO Test-Funktion & Log-Möglichkeit (Aug 2026, ARVIO 1.9.983)
**ARVIO hat KEINE Log-Datei-Exportfunktion.** `DiagnosticsManager` ist nur für Sentry/Crashlytics-Reporting, keine In-App-Log-Anzeige. Der einzige Weg an die Scraper-Logs zu kommen ist **Logcat** (`adb logcat` über USB am PC).
- ARVIO hat im Code eine **"Test Scraper"-Funktion** (`PluginManager.testScraper()` → `executeWithDiagnostics()`), die mit The Matrix (TMDB 603) testet und `TestDiagnostics` mit Einzelschritten zurückgibt (TMDB-Metadaten, search-Ergebnisse, HTTP-Requests, loadLinks, "Missing extractors: ..."). **ABER: der "Test"-Button ist in `PluginScreen.kt` NICHT in die UI eingebaut** – Strings (`plugin_test_btn`, `plugin_diagnostics_expand`) und ViewModel-Logik existieren, aber kein Compose-Button ruft `PluginUiEvent.TestScraper` auf. Halbfertige ARVIO-Funktion (wie Stalker-VOD-UI).
- **WICHTIGE INKONSISTENZ:** `executeTmdbProviderWithDiagnostics` (Test-Pfad) ruft `loadLinks` mit `TmdbLink(...).toJson()` direkt auf (OHNE `load()`), während `executeTmdbProvider` (echte Suche) erst `api.load({"id":...,"type":...})` aufruft und `extractData()` das `dataUrl`/`episode.data` extrahiert. Mein `loadLinks` ist auf den load()-Pfad ausgelegt (`{"links":[...]}` oder `http`-URL), würde also im Test-Pfad leer laufen. Falls ARVIO den Test-Button irgendwann aktiviert, muss mein `loadLinks` auch TmdbLink-JSON verarbeiten.

### Recherche: Touch-Bug auf Handy/Tablet (ARVIO Issue #502)
**Bestätigt und (teilweise) behoben in ARVIO 1.9.983.** ARVIO Issue #502 "fix(mobile): resolve touch issues and unify button styling in plugins settings":
- Ursache: Plugin-Settings-Screen + Add-Repo-Dialog nutzten TV-only `androidx.tv.material3.Surface`-Buttons, die auf Touch-Geräten nicht reagierten.
- Fix: `PluginScreen.kt` hat jetzt `LocalDeviceType.current.isTouchDevice()` → separates Mobile-Layout mit touch-friendly Compose-Box-Buttons. **In 1.9.983 enthalten** (verifiziert: `isTouchDevice` existiert in `PluginScreen.kt`).
- Falls der Nutzer noch eine ältere Version als 1.9.983 hat, sollte er updaten. Der Fix erklärt, warum der Nutzer es über Cloud-Profil auf dem Handy zum Laufen brachte.

### Recherche: "nur webstreamr-Quellen, nicht Filmpalast" – mögliche Ursachen (Aug 2026)
Da webstreamr (Stremio-Addon, serverseitig) Quellen liefert, mein Filmpalast-Scraper (Cloudstream-DEX) aber nicht, sind die Scraper-Logs nötig. Mögliche Ursachen (in absteigender Wahrscheinlichkeit):
1. **Scraper wird aufgerufen, aber `load()` schlägt fehl** → `loadResponse` null → `executeTmdbProvider` "both load() paths failed" → emptyList. Könnte ein Kotlin-spezifisches Problem sein (Jsoup-Selektor-Unterschied zu Python-Regex, oder Exception in `fetchTmdbMeta`/`searchFilmpalast`).
2. **Scraper ist nicht in `enabledScrapers`** – Plugin-Download fehlgeschlagen, oder `manifestEnabled` false, oder Toggle aus. (Weniger wahrscheinlich, da `status=1` verifiziert und Plugin sichtbar ist.)
3. **`loadLinks` findet Hoster aber `loadExtractor` liefert 0 Links** – Filmpalast rotiert Hostnamen; wenn nur nicht-built-in-Hoster (vidaraa.cc etc.) online, fällt alles durch. (Mein generischer Fallback fängt nur direkte mp4/m3u8.)
- **Ohne Logcat nicht eindeutig trennbar.** Logcat-Filter die helfen: `ExtExtractorRegistry`, `ExternalExtensionRunner`, `PluginManager`, `TmdbProvider Filmpalast`, `ExtExtRunner`.


Selbst bei korrekt aktiviertem Cloudstream-Scraper zeigt ARVIO oft "keine Streaming-Addons eingerichtet". Ursache ist eine ARVIO-seitige Logiklücke:
- `StreamRepository.getStreamAddons` (StreamRepository.kt:1440): `if (addon.runtimeKind != RuntimeKind.STREMIO) return@filter false` → **nur Stremio-Addons** kommen in die Stream-Auswahl.
- `DetailsViewModel` berechnet `hasStreamingAddons` aus `streamRepository.installedAddons.count { it.isVodStreamingAddon() }` (DetailsViewModel.kt:1633) → zählt **nur Stremio-Addons**, nicht Cloudstream-Scraper.
- Cloudstream-Scraper sind eine **getrennte Liste** (`PluginManager.scrapers`), nicht in `installedAddons` → werden für `hasStreamingAddons` nicht gezählt.
- **Aber:** `DetailsViewModel` (DetailsViewModel.kt:1516) ruft `pluginManager.executeScrapersStreaming()` separat auf → Cloudstream-Scraper **laufen im Hintergrund** und mergen Streams in `streams`. Nur die *Meldung* ist falsch, nicht das Scraping.
- **Workaround:** Zusätzlich ein (Dummy-)Stremio-Addon aktivieren → `addonCount > 0` → `hasStreamingAddons=true` → Meldung verschwindet. Scraper-Ergebnisse erscheinen dann in der Liste.
- **ARVIO-seitiger Fix nötig:** `getStreamAddons`/`hasStreamingAddons` sollten auch EXTERNAL_DEX-Scraper zählen. Lohnt als GitHub-Issue.

### Build (lokal)
JDK 17+ und Android SDK 35 nötig. Im Env: `JAVA_HOME` + `ANDROID_HOME` (oder `local.properties` mit `sdk.dir`).
```
./gradlew make makePluginsJson
# -> FilmPalast/build/FilmPalast.cs3
# -> build/plugins.json
```

## Versionshistorie dieses Addons

- **v1 (Proof-of-Concept):** Filmpalast-Plugin als TmdbProvider. Baut & kompiliert. Noch nicht in ARVIO endgerät-getestet.
