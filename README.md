# Ventix Arvio Addon

Cloudstream3-Plugins (`.cs3`) für die **ARVIO** Android-TV-App (sideload-APK).
Deutsche Web-Scraper (Filmpalast) + geplant: Stalker-VOD – clientseitig, ohne Server.

---

## ⭐ Installation in ARVIO (Repository-URL)

In ARVIO: **Einstellungen → Plugins & Extensions → Add Repository**, dann diese URL eintragen:

```
https://raw.githubusercontent.com/ReichiMD/Arvio-Addon/main/repo.json
```

Anschließend den Plugin-Eintrag **Filmpalast** einschalten. ARVIO lädt die `.cs3`-Datei
automatisch vom `builds`-Branch.

> Funktioniert **nur in der ARVIO sideload-APK** (GitHub-Release), nicht in der
> Play-Store-Version (dort ist die Plugin-Engine deaktiviert).

---

## 🔧 Selbst-Diagnose-Log (ab Filmpalast v2)

Das Plugin schreibt jeden Schritt des Scrapers in einen internen Trace und stellt ihn
über einen lokalen HTTP-Server bereit – ganz ohne Logcat/Root.

1. Plugin in ARVIO laden/einschalten (startet automatisch den Diagnose-Server).
2. In ARVIO eine Quellensuche auslösen (z. B. „Matrix" öffnen → nach Quellen suchen).
3. Auf **demselben Gerät** im Browser öffnen: **`http://localhost:8420/`**
   - `http://localhost:8420/` → Trace als HTML (auto-refresh 3s)
   - `http://localhost:8420/raw` → reiner Text (zum Kopieren)
   - `http://localhost:8420/clear` → Trace löschen
4. Trace lesen oder den `/raw`-Text kopieren.

> Der Server läuft nur, solange der ARVIO-Prozess lebt – ARVIO also nicht beenden.
> Per ADB möglich: `adb forward tcp:8420 tcp:8420`, dann am PC `curl http://localhost:8420/raw`.

Ausführliche Anleitung & Trace-Interpretation: siehe `AGENTS.md` →
„Schritt-für-Schritt: Diagnose-Log auslesen".

---

## Aufbau

- `main` – Quellcode
- `builds` – fertige `.cs3`-Dateien + `plugins.json` (von GitHub Actions gepusht)
- `FilmPalast/` – Modul 1: Filmpalast-Scraper (TmdbProvider)

## Build

JDK 17+ und Android SDK 35. CI (`.github/workflows/build.yml`) baut automatisch bei
Push auf `main` und pusht die `.cs3` + `plugins.json` auf den `builds`-Branch.
