# Logcat am Handy auslesen (Einrichtung + täglicher Ablauf)

Ziel: Die ARVIO-Scraper-Logs (Logcat) direkt am Handy auslesen — **ohne Laptop, ohne USB-Kabel**. Einmal eingerichtet, brauchst du danach nur noch Termux.

> **Kurz erklärt, warum das so umständlich aussieht:** Android blockiert den System-Protokoll-Speicher (logcat) für normale Apps — aus Datenschutzgründen. Termux kann den Befehl `logcat` ausführen, bekommt aber ohne einen einmaligen „Berechtigungs-Stempel" keine Daten. Diesen Stempel gibt man Termux über eine App, die Drahtloses Debugging nutzt. Dafür gibt es **zwei Apps**: LADB (einfach, aber beim Pairing oft zickig) oder **Shizuku (empfohlen — stabiler, gratis)**. Du brauchst nur EINE der beiden.

---

## Was du brauchst (Checkliste)

- ✅ **Termux** hast du schon. Gut. (Wichtig: von **F-Droid**, NICHT Play Store — die Play-Store-Version ist veraltet und funktioniert nicht richtig.)
- ✅ Entwickleroptionen aktiviert (falls nicht: Einstellungen → Über das Telefon → 7× auf Build-Nummer tippen).
- Entweder **LADB** (ca. 3 € im Play Store, oder gratis via GitHub-Build) **ODER Shizuku** (gratis, F-Droid oder Play Store). **Empfehlung: Shizuku**, weil LADB beim Pairing oft Probleme macht (die du schon kennst).

> ARVIO auf dem Handy ist dieselbe sideload-APK wie am TV → die Scraper-Tests am Handy sind repräsentativ für den TV (gleiche R8-Obfuskation). Du kannst also bequem am Handy testen.

---

## Teil 1: Vorbereitung (einmalig, 5 Minuten)

### Schritt 1 — Drahtloses Debugging einschalten
1. Handy-Einstellungen → **System** → **Entwickleroptionen**.
2. **Drahtloses Debugging** (Wireless Debugging) → Schalter auf **AN**.
3. Falls eine Warnung/Zugriff-Frage kommt: **Zulassen**.

> WLAN-Schalter AN lassen (das Handy muss nicht in einem Netz eingeloggt sein, aber der WLAN-Schalter muss an sein, sonst läuft Drahtloses Debugging nicht).

---

## Teil 2: Shizuku einrichten (EMPFOHLEN) ODER LADB

### Weg A: Shizuku (empfohlen, stabiler)

Shizuku ist eine kleine Dienst-App, die im Hintergrund läuft und anderen Apps über Drahtloses Debugging höhere Rechte gibt — ohne dass du jedes Mal pairen musst.

**Schritt A1 — Shizuku installieren**
- F-Droid oder Play Store → „Shizuku" suchen + installieren.

**Schritt A2 — Shizuku starten (über Drahtloses Debugging)**
1. Shizuku öffnen.
2. Oben siehst du den Abschnitt „Über Drahtloses Debugging starten" mit einer Anleitung.
3. Folge der Anleitung: Shizuku zeigt dir, dass du in den Entwickleroptionen „Drahtloses Debugging" anmachen sollst (hast du schon in Schritt 1).
4. Shizuku fragt nach **IP-Adresse und Port** (die stehen in den Entwickleroptionen unter Drahtloses Debugging, z. B. `192.168.0.5:43567`). Genau diese eintragen.
5. Shizuku verbindet sich. Wenn es klappt, zeigt Shizuku oben „Laufend" mit einer Nummer (z. B. „Shizuku läuft (12)“).

> Shizuku muss **jedes Mal nach Neustart des Handys** neu gestartet werden (einmal antippen → „Starten"). Es bleibt aber den ganzen Tag über aktiv. Kein Pairing-Code-Zickerei wie bei LADB.

**Schritt A3 — Termux erlaubt Shizuku zu nutzen**
1. Öffne Termux.
2. Tippe ein:
   ```
   sh /sdcard/Android/data/moe.shizuku.privileged.api/starter.sh
   ```
   - Falls das einen Fehler bringt (Pfad nicht gefunden): Schau unten bei „Probleme".
3. Dann tippe:
   ```
   rish
   ```
   (`rish` = „Shizuku Interactive Shell" — jetzt läuft diese Shell mit Shizukus Rechten, also mit logcat-Zugriff.)
4. Jetzt den **wichtigen einmaligen Befehl** eintippen (gibt Termux dauerhaft die Logcat-Berechtigung):
   ```
   pm grant com.termux android.permission.READ_LOGS
   ```
   - Wenn keine Fehlermeldung kommt → hat geklappt.

> Falls du `rish` nicht in Termux hast: Installiere es mit `pkg install rish` (manche Termux-Versionen brauchen das). Falls `rish` gar nicht funktioniert → siehe unten „Weg B: LADB" oder die Problemlösung.

**Das war's bei Shizuku!** Die Berechtigung ist jetzt dauerhaft gesetzt (überlebt Neustarts). Du kannst Shizuku schließen.

---

### Weg B: LADB (falls du LADB schon hast oder Shizuku nicht klappt)

LADB ist die einfachere App, aber das Pairing ist zeitkritisch und oft zickig (die Probleme kennst du schon).

**Schritt B1 — Pairing-Fenster öffnen**
1. Entwickleroptionen → Drahtloses Debugging → auf den Eintrag „Drahtloses Debugging" tippen (nicht nur der Schalter, sondern der Text — damit kommst du ins Untermenü).
2. Unten auf **„Gerät mit Pairing-Code koppeln"** tippen.
3. Es erscheint ein Fenster mit:
   - **IP-Adresse & Pairing-Port** (z. B. `192.168.0.5:43567`)
   - **6-stelliger Pairing-Code** (z. B. `435271`)
4. Lass dieses Fenster **offen** — du hast nur ~60 Sekunden!

**Schritt B2 — In LADB pairen**
1. LADB öffnen.
2. IP, Pairing-Port und Pairing-Code eintragen.
3. „Verbinden" tippen.
4. Wenn's klappt: LADB zeigt eine Kommandozeile mit `$`.

> **Wenn das Pairing fehlschlägt** („no devices/emulators found" / „Timer abgelaufen"): Neu starten — Entwickleroptionen → Drahtloses Debugging AUS → ON → „Gerät mit Pairing-Code koppeln" → neuer Code → LADB. Oft klappt es beim 2. oder 3. Versuch. Wenn gar nicht → **Weg A (Shizuku) versuchen**.

**Schritt B3 — Termux die Logcat-Berechtigung geben (DER einmalige Schritt)**
Jetzt in der LADB-Shell genau diesen Befehl eintippen und Enter:
```
pm grant com.termux android.permission.READ_LOGS
```
- Keine Fehlermeldung = geklappt.

**LADB schließen. Fertig. Nie wieder LADB nötig.**

---

## Teil 3: Prüfen, ob die Berechtigung da ist

Bevor du loslegst, in **Termux** (nicht in LADB/rish) eintippen:
```
logcat -d | head
```
- Siehst du viele Textzeilen → Berechtigung da. Weiter zu Teil 4.
- Siehst du „permission denied" → die Einrichtung hat nicht geklappt. Zurück zu Teil 2.

---

## Teil 4: Das save-tv-log-Skript installieren (einmalig, empfohlen)

Damit du beim Testen nicht jeden Befehl einzeln tippen musst, gibt es ein Fertig-Skript. Es holt das Logcat, filtert die wichtigen Zeilen (FilmPalast + Serienstream + Kinoger + ARVIO-Engine), speichert die Datei im Download-Ordner und macht einen Medienscan, damit Chat-Apps die Datei sofort finden.

**Einmalig installieren** (in Termux, nur dieses eine Mal):
```
curl -sL https://raw.githubusercontent.com/ReichiMD/Arvio-Addon/main/docs/save-tv-log.sh -o ~/save-tv-log.sh && chmod +x ~/save-tv-log.sh
```

**Einmalig: Download-Ordner für Termux freigeben** (falls noch nicht gemacht):
```
termux-setup-storage
```
→ es erscheint ein Dialog „Termux darf auf Dateien zugreifen" → **Zulassen**.

> Falls deine TV-IP (für den TV-Test) nicht `192.168.0.59` ist: `nano ~/save-tv-log.sh` → die Zeile `TV_IP="192.168.0.59"` anpassen. Für den **Handy-Test** brauchst du die TV-IP nicht — siehe unten „Variante: Handy-Logcat ohne TV".

---

## Teil 5: Der Test-Ablauf (jedes Mal, wenn du testest)

Jetzt nur noch **Termux**. Der Ablauf beim Testen:

### Schritt 1 — Log-Puffer leeren
Termux öffnen, eintippen:
```
logcat -c
```
(leert den Protokoll-Speicher, damit du nur die neuen Einträge vom Test bekommst)

### Schritt 2 — In ARVIO die Suche auslösen
1. ARVIO öffnen.
2. Scraper einschalten (Filmpalast / Serienstream / Kinoger — was du gerade testest).
3. Z. B. **Matrix** suchen → Film → **„Nach Quellen suchen"**.
4. Ca. **15 Sekunden warten** (bis ARVIO fertig gesucht hat).

### Schritt 3 — Log speichern (EIN Befehl)
Zurück in Termux, eintippen (Version anpassen, z. B. `v32` für Serienstream-Test oder `kinoger` für KinoGer-Test):
```
~/save-tv-log.sh kinoger
```
Das Skript macht automatisch:
- Logcat auslesen (roh)
- filtern nach FilmPalast + Serienstream + Kinoger + ARVIO-Engine
- Datei speichern in `Download/arvio-logs/arvio-tv-log-kinoger-filtered.txt`
- Medienscan, damit WhatsApp/Chat die Datei finden
- Vorschau der ersten 20 Zeilen im Terminal

### Schritt 4 — Datei weiterleiten
1. Dateimanager öffnen.
2. Downloads → **arvio-logs** → `arvio-tv-log-kinoger-filtered.txt`.
3. Datei lange drücken → **Teilen** → in den Chat hochladen (Plus-Symbol → Datei).

---

## Variante: Nur mit Termux (ohne LADB, ohne Shizuku) — die robusteste Methode

Wenn LADB beim Pairing zickt („no devices/emulators found") oder du keine zweite App installieren willst: Termux kann sich selbst über ADB pairen und verbinden. Das ist am zuverlässigsten, weil alles in einem Fenster läuft (kein Tab-Wechsel-Problem).

### Einrichtung (einmalig)

**1 — adb-Werkzeug in Termux installieren:**
```
pkg update && pkg install android-tools
```
(falls „Do you want to continue?" fragt: `y` + Enter)

**2 — Termux Speicherzugriff geben:**
```
termux-setup-storage
```
→ Dialog „Termux darf auf Dateien zugreifen" → **Zulassen**.

**3 — Fertig-Skript installieren:**
```
curl -sL https://raw.githubusercontent.com/ReichiMD/Arvio-Addon/main/docs/save-tv-log.sh -o ~/save-tv-log.sh && chmod +x ~/save-tv-log.sh
```

**4 — Mit dem Handy pairen + verbinden + Berechtigung geben (einmalig):**

- **4a** — Entwickleroptionen → Drahtloses Debugging → „Gerät mit Pairing-Code koppeln" tippen → Fenster offen lassen. Du siehst **IP:Pairing-Port** (z. B. `192.168.0.239:45357`) und **6-stelligen Code**.
- **4b** — In Termux pairen (IP+Pairing-Port aus dem Fenster einsetzen):
  ```
  adb pair 192.168.0.239:45357
  ```
  → `Enter pairing code:` → 6-stelligen Code eingeben → Enter.
  → `Successfully paired to ...` = geklappt. Das Pairing-Fenster am Handy schließt sich von selbst.
- **4c** — In Termux verbinden (WICHTIG: hier **Port 5555**, NICHT den Pairing-Port):
  ```
  adb connect 192.168.0.239:5555
  ```
  → muss kommen: `connected to 192.168.0.239:5555`
  > Falls `5555` nicht geht: In den Entwickleroptionen oben unter „Drahtloses Debugging" steht eine „IP-Adresse & Port"-Zeile. Nimm **genau den Port** daraus (oft 5555, manchmal 3xxxx). Falls am Handy ein Dialog „USB-Debugging zulassen?" poppt → **Zulassen**.
- **4d** — Verbindung prüfen:
  ```
  adb devices
  ```
  → muss zeigen: `192.168.0.239:5555  device`
- **4e** — Berechtigung geben (der einmalige Befehl):
  ```
  adb shell pm grant com.termux android.permission.READ_LOGS
  ```
  → keine Ausgabe = geklappt.
- **4f** — Verbindung trennen (aufräumen):
  ```
  adb disconnect
  ```

**5 — Prüfen, ob die Berechtigung da ist:**
```
logcat -d | head
```
- Siehst du viele Textzeilen → **geschafft**. Die Einrichtung ist fertig, du brauchst sie nie wieder.
- Steht „permission denied" → zurück zu Schritt 4.

### Test-Ablauf (jedes Mal, wenn du testest)

Jetzt nur noch Termux, ganz normal (kein adb-Pairing mehr nötig, die Berechtigung bleibt):

```
logcat -c
```
(dann ARVIO: Scraper an, Matrix/Silo suchen, „Nach Quellen suchen", 15s warten)
```
logcat -d -v time | grep -iE "Filmpalast|Serienstream|Kinoger|ArvioAddon|ExternalExtension|No API loaded|ErrorLoading|verify dex|resolveHost|resolveIncvideo|loadLinks|httpGet" > ~/storage/downloads/arvio-logs/arvio-handy-log.txt
```
Datei in Downloads/arvio-logs/ → lange drücken → Teilen → in den Chat hochladen.

> Wichtig: `adb pair` (mit Pairing-Port) brauchst du **nur einmal** zum Einrichten. Danach bleibt die READ_LOGS-Berechtigung dauerhaft — du brauchst beim Testen nur noch `logcat`, kein adb mehr.
> Nach einem **Handy-Neustart** wird die ADB-Verbindung getrennt, aber die Berechtigung bleibt. Falls du adb wieder brauchst (z. B. um die Berechtigung nochmal zu setzen): Drahtloses Debugging wieder AN, dann `adb connect <IP>:5555` (ohne erneutes Paaren, das Pairing bleibt gespeichert).

---

## Variante: Handy-Logcat OHNE TV (nur Handy testen)

Das `save-tv-log.sh`-Skript versucht standardmäßig, sich mit dem TV zu verbinden. Wenn du **nur am Handy** testest (ARVIO läuft ja auch auf dem Handy), nutze stattdessen die direkte Befehlsfolge aus dem „Nur mit Termux"-Abschnitt oben (Test-Ablauf):
```
logcat -c
```
(dann ARVIO am Handy: Suche auslösen, 15s warten)
```
logcat -d -v time | grep -iE "Filmpalast|Serienstream|Kinoger|ArvioAddon|ExternalExtension|No API loaded|ErrorLoading|verify dex|resolveHost|resolveIncvideo|loadLinks|httpGet" > ~/storage/downloads/arvio-logs/arvio-handy-log.txt
```
Datei dann wie oben teilen.

---

## Wenn etwas nicht klappt (Problemlösung)

| Problem | Lösung |
|---|---|
| **LADB: Pairing klappt einfach nicht** („no devices/emulators found") | Pairing-Timer abgelaufen → neu versuchen. Wenn es nach 3 Versuchen nicht geht → **Shizuku** (Weg A) nutzen, das ist stabiler. |
| **Shizuku: „rish" nicht gefunden** | In Termux: `pkg update && pkg install rish`. Falls dann immer noch nicht → nutze LADB (Weg B). |
| **Shizuku: starter.sh Pfad nicht gefunden** | Der Pfad kann je nach Handy variieren. Suche ihn: `find /sdcard/Android/data -name "starter.sh" 2>/dev/null`. Wenn nicht gefunden → LADB nutzen. |
| **Termux: „permission denied" bei logcat** | Die Berechtigung (Schritt A3/B3) hat nicht geklappt. Shizuku/LADB nochmal, Befehl wiederholen. Prüfen mit `logcat -d \| head`. |
| **Termux (Play-Store-Version) funktioniert nicht** | Play-Store-Termux ist veraltet. F-Droid-Version installieren (f-droid.org → „Termux"). |
| **Datei ist leer** | Entweder ARVIO war nicht aktiv, oder Filter zu streng. Ohne Filter probieren: `logcat -d > ~/storage/downloads/arvio-logs/arvio-handy-log.txt`. |
| **`~/storage/downloads` existiert nicht** | `termux-setup-storage` vergessen (Teil 4). Nachholen, Dialog bestätigen. |
| **Nach Handy-Neustart geht logcat nicht mehr** | Die READ_LOGS-Berechtigung bleibt, aber Shizuku muss neu gestartet werden (Shizuku-App öffnen → „Starten"). Bei LADB war die Berechtigung einmalig und bleibt auch nach Neustart. |
| **Shizuku läuft nicht nach Neustart** | Shizuku-App öffnen, auf „Starten" tippen (jedes Mal nach Neustart nötig). |
| **Gar nichts geht am Handy** | Fallback: TV + Windows-Laptop via WLAN-ADB (siehe `docs/windows-10-test-guide.md`). |

---

## Zusammenfassung (Einzeiler nach der Einrichtung)

Einmal eingerichtet (Shizuku oder LADB hat Termux die READ_LOGS-Berechtigung gegeben), reicht für jeden Test am Handy dieser Dreiklang in Termux:
```
logcat -c
                # ... ARVIO: Matrix/Silo suchen, 15s warten ...
~/save-tv-log.sh kinoger      # oder: v32 / filmpalast
```
Oder ohne Skript (direkt):
```
logcat -d -v time | grep -iE "Kinoger|Filmpalast|ArvioAddon|No API loaded|resolveIncvideo|loadLinks" > ~/storage/downloads/arvio-logs/arvio-handy-log.txt
```

---

## Was die AI im Log sucht (für dich zur Info, damit du weißt, was wichtig ist)

| Im Log gesehen | Bedeutung |
|---|---|
| `Executing DEX scraper: Kinoger` | Scraper wird aufgerufen — gut. |
| `Downloaded extension …: N bytes` | Download geklappt. |
| `Failure to verify dex file …` | DEX kaputt → Patch-Skript-Problem. |
| `No API loaded for scraper: …` | Plugin-Klasse konnte nicht geladen werden. |
| `MISSING CLASS` / `NoClassDefFoundError` | fehlt eine Kotlin/cloudstream3-Klasse (R8). |
| `API loaded` / Kinoger-Quellen in der Auswahl | **Erfolg!** Override bindet, Scraper läuft. |
| `resolveIncvideo: … [1080p]https://…mp4` | KinoGer-Hauptquelle extrahiert! 🎯 |
| `loadLinks: DONE, any=true` | Mindestens eine Quelle emittiert. |
| `ErrorLoadingException: No id found` | Parent läuft noch → Dispatch nicht gebunden. |
| gar kein Scraper-Eintrag | Scraper wird nicht aufgerufen (Enable/Routing/Download-Problem). |
