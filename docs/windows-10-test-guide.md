# Windows 10: Filmpalast v14 auf TCL C7K TV testen — Schritt-für-Schritt

Diese Anleitung führt dich durch den Test des neuen **Filmpalast v14** Plugins
auf deinem **TCL C7K 65" Google TV** über **WLAN-ADB** von einem **Windows 10 Laptop**.

v14 ist der hoffentliche Durchbruch: Die Methoden-Signaturen wurden per DEX-Patching
an ARVIOs R8-obfuszierte Runtime angepasst. Wenn ARVIO jetzt unseren `load()`-Override
aufruft (statt der leeren Parent-Klasse), sollten Filmpalast-Quellen erscheinen.

---

## Was du brauchst

- Windows 10 Laptop mit WLAN
- TCL C7K 65" Google TV (eingeschaltet, im selben WLAN)
- ARVIO sideload-APK bereits auf dem TV installiert (v1.9.983)
- Handy mit ARVIO-Cloud-Profil (für Plugin-Update, falls TV-UI Probleme macht)

---

## Schritt 1: ADB auf Windows installieren (ohne Android Studio)

Du brauchst nur die `platform-tools` (~10 MB), keine vollständige Android-Studio-Installation.

1. Öffne im Browser: **https://developer.android.com/tools/releases/platform-tools**
2. Scrolle zum Abschnitt "Command line tools only"
3. Lade **"SDK Platform-Tools for Windows"** herunter (`platform-tools_rXXXX-windows.zip`)
4. Entpacke die ZIP z.B. nach `C:\platform-tools` (oder `C:\Users\DeinName\platform-tools`)
5. Es enthält `adb.exe` — das ist alles, was du brauchst

**Tipp:** Merke dir den Pfad, z.B. `C:\platform-tools`. Du brauchst ihn gleich.

---

## Schritt 2: WLAN-ADB auf dem TCL TV aktivieren

1. Auf dem TCL TV: **Einstellungen → System → Über** (oder "Geräte-Präferenzen")
2. Scrolle ganz nach unten, **7× schnell auf "Build-Nummer"** (oder "TV Build-Nummer") tippen
3. Es erscheint "Sie sind jetzt Entwickler!" / "You are now a developer!"
4. Zurück zu **Einstellungen → System → Entwickleroptionen** (Developer Options)
5. **USB-Debugging** → AN (falls sichtbar, ignoriere — wir nutzen WLAN)
6. **WLAN-Debugging** (oder "ADB-Debugging über WLAN", "Network debugging") → AN
7. Es zeigt eine **IP-Adresse + Port** an, z.B. `192.168.1.50:5555`

**Wichtig:** Diese IP + Port notieren! Der Port kann sich ändern, wenn der TV neu startet.

---

## Schritt 3: ADB-Verbindung vom Laptop herstellen

1. Öffne **Eingabeaufforderung** (cmd):
   - Windows-Taste drücken, `cmd` eingeben, Enter
2. Wechsle in den platform-tools-Ordner:
   ```
   cd C:\platform-tools
   ```
   (oder woimmer du sie entpackt hast)
3. Verbinde dich mit dem TV:
   ```
   adb connect 192.168.1.50:5555
   ```
   (IP+Port von Schritt 2 verwenden)
4. Falls ein Dialog auf dem TV erscheint ("USB-Debugging erlauben?"):
   - **Immer zulassen von diesem Computer** anhaken
   - **OK** drücken
5. Prüfe die Verbindung:
   ```
   adb devices
   ```
   Ausgabe sollte sein:
   ```
   List of devices attached
   192.168.1.50:5555    device
   ```
   Steht dort `unauthorized` statt `device`: Dialog auf dem TV bestätigen.

---

## Schritt 4: v14 Plugin in ARVIO aktualisieren

ARVIO aktualisiert ein Plugin **nicht** automatisch, wenn man nur den Toggle an/aus
schaltet (`toggleScraper` lädt die `.cs3` nicht neu). Du musst das Repository
**löschen und neu hinzufügen**:

### Option A: Über das Handy (empfohlen — funktioniert zuverlässig)

1. Auf dem Handy: ARVIO öffnen → **Einstellungen → Plugins & Extensions**
2. **Repository** finden (Filmpalast / ReichiMD Arvio-Addon)
3. Repository **löschen** (lange drücken → Löschen, oder Icon → Löschen)
4. **Add Repository**:
   - URL: `https://raw.githubusercontent.com/ReichiMD/Arvio-Addon/main/repo.json`
5. ARVIO lädt das Repo → Filmpalast-Eintrag erscheint
6. **Filmpalast einschalten** (Toggle AN)
7. ARVIO lädt v14 `.cs3` herunter
8. Warte bis der Download fertig ist (kurzer Ladebalken)
9. ARVIO-Cloud-Sync sorgt dafür, dass es aufs TV synchronisiert wird

### Option B: Direkt auf dem TV

Falls du es direkt auf dem TCL TV machst (mit D-Pad-Navigation):
1. ARVIO → **Einstellungen → Plugins & Extensions**
2. Altes Filmpalast-Repo löschen
3. **Add Repository** → `https://raw.githubusercontent.com/ReichiMD/Arvio-Addon/main/repo.json`
4. Filmpalast einschalten

---

## Schritt 5: Logcat starten (BEVOR du suchst!)

Das ist der entscheidende Teil — wir wollen sehen, was ARVIO macht, wenn du
eine Quellensuche auslöst.

1. Im cmd-Fenster (immer noch in `C:\platform-tools`):
   ```
   adb logcat -c
   ```
   Das leert den Logcat-Puffer (frischer Start).

2. **Jetzt losgelöst:** Logcat aufzeichnen in eine Datei:
   ```
   adb logcat -v time > %USERPROFILE%\Desktop\arvio-tv-log-v14.txt
   ```
   Das Fenster "hängt" jetzt — es zeichnet auf. **Lass es offen!**

---

## Schritt 6: Quellensuche auslösen

1. Auf dem TCL TV: ARVIO öffnen
2. Einen Film suchen, z.B. **"Matrix"** (TMDB ID 603) oder **"Silo"** (Serie)
3. Auf den Film/ Serie gehen → **"Nach Quellen suchen"** (oder "Sources")
4. **15 Sekunden warten** (ARVIO hat 120s Scraper-Timeout, aber bei Erfolg sollte es schnell sein)

---

## Schritt 7: Logcat auswerten

1. Gehe zurück zum cmd-Fenster
2. Drücke **Strg+C** (stoppt die Aufzeichnung)
3. Die Datei `arvio-tv-log-v14.txt` ist jetzt auf deinem Desktop
4. Öffne die Datei und suche (Strg+F) nach:
   - **`Filmpalast`** — unsere Scraper-Referenzen
   - **`ArvioAddon`** — unsere Debug-Banner
   - **`No API loaded`** — ARVIO konnte Plugin-Klasse nicht instanziieren
   - **`load()`** / **`loadLinks`** — ob unsere Methoden aufgerufen werden
   - **`ErrorLoadingException`** — Fehler in load()
   - **`ExtExt`** / **`ExternalExtension`** — ARVIOs Plugin-Runner

### Was die Ergebnisse bedeuten:

| Was du findest | Bedeutung |
|---|---|
| `Filmpalast` oder `ArvioAddon` im Log | **GUT!** ARVIO ruft unseren Code auf. v14 DEX-Patch hat funktioniert. |
| `ArvioAddon-Debug` Quellen im TV | **SUPER!** Unsere Diagnose-Quellen erscheinen → Scraper läuft. |
| `No API loaded for scraper` | ARVIO kann Plugin-Klasse nicht laden — Klassen-Fehler. |
| `ErrorLoadingException: No id found` | Parent load() wird noch aufgerufen — Patch hat nicht funktioniert. |
| Gar kein `Filmpalast`-Eintrag | Scraper wird überhaupt nicht aufgerufen — Enable/Routing-Problem. |

---

## Schritt 8: Log an mich senden

1. Die Datei `arvio-tv-log-v14.txt` auf dem Desktop
2. Falls sie sehr groß ist (> 5MB): Nur die relevanten Zeilen filtern:
   ```
   findstr /i "Filmpalast ArvioAddon ExternalExtension ErrorLoading No.API.loaded load" %USERPROFILE%\Desktop\arvio-tv-log-v14.txt > %USERPROFILE%\Desktop\arvio-tv-log-v14-filtered.txt
   ```
3. Sende die (gefilterte) Log-Datei in der nächsten Session

---

## Schnell-Referenz (für Wiederholung)

```
# 1. Verbinden
cd C:\platform-tools
adb connect <TV-IP>:<Port>

# 2. Log leeren
adb logcat -c

# 3. Aufzeichnen (läuft im Hintergrund)
adb logcat -v time > %USERPROFILE%\Desktop\arvio-tv-log-v14.txt

# 4. Auf dem TV: ARVIO → Film → Quellen suchen → 15s warten

# 5. Strg+C im cmd-Fenster

# 6. Filtern (optional)
findstr /i "Filmpalast ArvioAddon ExternalExtension ErrorLoading No.API" %USERPROFILE%\Desktop\arvio-tv-log-v14.txt > %USERPROFILE%\Desktop\arvio-tv-log-v14-filtered.txt
```

---

## Fehlersuche

### `adb` wird nicht erkannt
- Du bist nicht im `platform-tools`-Ordner. `cd C:\platform-tools` (oder dein Pfad)
- Oder `adb.exe` zur PATH-Umgebungsvariable hinzufügen

### `unable to connect to <IP>:<Port>`
- TV und Laptop im selben WLAN?
- WLAN-Debugging auf dem TV noch AN?
- TV neu gestartet? Port kann sich ändern → Entwickleroptionen neu prüfen
- Firewall blockiert? Windows-Fireischer → ADB erlauben

### `unauthorized`
- Auf dem TV erschien ein Dialog "USB-Debugging erlauben?" → bestätigen
- Falls kein Dialog: `adb disconnect`, dann `adb connect` neu versuchen
- Entwickleroptionen auf dem TV AUS dann AN schalten

### Plugin erscheint nicht in ARVIO
- Repository korrekt hinzugefügt? URL exakt: `https://raw.githubusercontent.com/ReichiMD/Arvio-Addon/main/repo.json`
- ARVIO-Cloud-Sync eingeschaltet? (Einstellungen → Cloud)
- ARVIO neu starten (App komplett schließen und neu öffnen)

### Quellensuche zeigt nur webstreamr
- Das bedeutet ARVIO ruft unseren Scraper nicht auf — Logcat prüfen!
- Falls Logcat `No API loaded` zeigt: Plugin-Klassen-Ladefehler
- Falls Logcat unsere `ArvioAddon-Debug`-Quellen zeigt aber keine echten:
  Scraper läuft, aber Jsoup-Selektoren oder Hoster-Extraktion scheitern

---

## Warum v14 anders ist als v6-v13

v6-v13 hatten Diagnose-Tooling (Debug-Quellen als Pseudo-Quellen, Debug-Server,
Datei-Trace), aber **ARVIO hat den Plugin-Code nie ausgeführt** — die Methoden-
Signaturen stimmten nicht überein (R8-obfusziertes `j7.d` vs. unser
`kotlin.coroutines.Continuation`). Der JVM Virtual Dispatch rief die leere
Parent-Klasse auf statt unseres Overrides.

**v14** patcht die DEX-Datei nach dem Build: die String-Tabelle wird so modifiziert,
dass `Lkotlin/coroutines/Continuation;` → `Lj7/d;` wird. Die Methoden-Signaturen
matchen jetzt ARVIOs Runtime. Virtual Dispatch sollte endlich unseren Override
aufrufen.

**Das Logcat wird zeigen, ob das funktioniert hat.**
