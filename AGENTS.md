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
