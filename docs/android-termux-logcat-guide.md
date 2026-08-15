# Android-Handy: TV-Logcat auslesen ohne Laptop (Termux + WLAN-ADB)

Diese Anleitung zeigt, wie du die **ARVIO-Logs vom TCL C7K TV direkt am Handy
ausliest** – ohne Laptop, ohne Kabel. Genau dieselbe WLAN-ADB-Methode wie am
Laptop, nur dass das Handy statt des Laptops der „Empfänger" ist.

Das ist nützlich, weil du zum Testen des Filmpalast-Plugins die ARVIO-Logs
(Logcat) vom TV brauchst, um zu sehen, ob ARVIO unser Plugin lädt und aufruft.

> Diese Anleitung ist die **Handy-Alternative** zu `docs/windows-10-test-guide.md`.
> Beide liefern denselben Log-Inhalt – wähle, was dir praktischer ist.

---

## Was du brauchst

- Android-Handy mit WLAN (im selben Netz wie der TV)
- TCL C7K (eingeschaltet, im selben WLAN)
- ARVIO sideload-APK bereits auf dem TV installiert (v1.9.983)

---

## Schritt 1: Termux installieren

**Wichtig: Nur von F-Droid**, NICHT aus dem Play Store. Die Play-Store-Version
ist veraltet und kaputt.

1. F-Droid installieren: https://f-droid.org (falls nicht vorhanden)
2. In F-Droid suchen: **Termux**
3. Installieren
4. Termux einmal öffnen (es richtet sich ein, dauert ~10s)

---

## Schritt 2: ADB in Termux installieren

In Termux (öffnen → tippen):

```
pkg update
pkg install android-tools
```

Bestätigen mit `y`, falls gefragt. Danach steht `adb` zur Verfügung.
(Das ist das **echte** ADB, identisch zum Laptop – kein reduzierter Klon.)

---

## Schritt 3: Einmalig pairen (nur einmal nötig!)

Das ist der einzige fummelige Schritt, aber du machst ihn **ein einziges Mal**.
Danach reicht für alle künftigen Sessions Schritt 4.

### 3a. Am TV: Wireless Debugging + Paar-Code

1. Am TV: **Einstellungen → System → Info** (oder Über)
2. **7x auf „Build" tippen** (bis „Sie sind jetzt Entwickler") → Entwickleroptionen frei
3. **Einstellungen → System → Entwickleroptionen**
4. **USB-Debugging** AN
5. **Wireless Debugging / Netzwerk-Debugging** AN
6. Auf **„Wireless Debugging"** tippen (nicht der Schalter, der Eintrag)
7. **„Mit Gerät pairen"** tippen
8. Der TV zeigt jetzt:
   - **WLAN-Paarungs-Code** (6-stellig, zählt ~30s runter)
   - **IP-Adresse und Paarungs-Port** (z.B. `192.168.1.42:38471`)
   - **IP-Adresse und Verbindungs-Port** (meist `:5555`, das ist der für später)

   Notiere dir IP + Paar-Port (z.B. `192.168.1.42:38471`) und den 6-stelligen Code.

### 3b. In Termux: pairen

Schnell tippen (während der Code zählt):

```
adb pair 192.168.1.42:38471
```

(Ersetze IP+Port durch die vom TV angezeigte Paar-Adresse.)

Es erscheint:
```
Enter pairing code:
```

Den 6-stelligen Code eingeben, Enter. Erfolgsmeldung:
```
Successfully paired to 192.168.1.42 ...
```

Fertig – das Paaren ist **einmalig**. Der Key bleibt auf dem TV gespeichert.

> **Tipp, falls Pairing klemmt:** Timer abgelaufen? Am TV „Mit Gerät pairen"
> neu tippen → neuer Code/Port. In Termux `adb pair` mit dem NEUEN Port wiederholen.
> Handy und TV müssen im selben WLAN sein.

### 3c. Bonus: Nie wieder pairen (Pairing-Key vom Laptop kopieren)

Wenn dein Laptop den TV schon einmal gepaired hat, liegt dort der Schlüssel:
- Windows: `%USERPROFILE%\.android\adbkey` (z.B. `C:\Users\Du\.android\adbkey`)
- (auch `adbkey.pub` mit dabei)

Kopiere die `adbkey`-Datei aufs Handy (z.B. per E-Mail, USB, Cloud) und in Termux:

```
mkdir -p ~/.android
cp /sdcard/Download/adbkey ~/.android/adbkey
```

(Pfad anpassen, je nachdem wo du sie abgelegt hast.) Danach erkennt der TV
das Handy als „denselben bekannten Client" – `adb connect` ohne Code. Optional,
aber spart das Fummeln mit dem 30-Sekunden-Timer.

---

## Schritt 4: Verbinden und Logs lesen

### 4a. Verbinden (immer, wenn der TV neu gestartet wurde)

```
adb connect 192.168.1.42:5555
```

(Ersetze IP durch die vom TV, Port ist meist `5555`. Den Port findest du am TV
unter Wireless Debugging – „IP-Adresse und Port", NICHT der Paar-Port.)

Bestätigung:
```
connected to 192.168.1.42:5555
```

> **Wichtig:** Wenn der TV neu startet, schaltet sich Wireless Debugging AB.
> Dann am TV Wireless Debugging wieder AN stellen (kein neues Paaren nötig!)
> und `adb connect` wiederholen. Der Pairing-Key ist noch da.

### 4b. Test: Passt die Verbindung?

```
adb devices
```

Sollte zeigen:
```
List of devices attached
192.168.1.42:5555    device
```

Steht dort `device` → alles gut. Steht `unauthorized` → TV zeigt einen
„USB-Debugging zulassen?"-Dialog, am TV bestätigen.

---

## Schritt 5: Logcat aufzeichnen (für den Plugin-Test)

Jetzt der eigentliche Test. Wir nehmen das Log als **Textdatei** auf, die du
anschließend weiterleiten kannst.

### 5a. Vorbereitung am TV

1. In ARVIO: **Repository löschen** (Plugin-Einstellungen → Filmpalast-Repo
   entfernen) – damit frisch geladen wird, NICHT der alte Cloud-Sync-Cache.
2. **Repo neu hinzufügen**, DIREKT am TV (NICHT Cloud-Sync):
   - URL: `https://raw.githubusercontent.com/ReichiMD/Arvio-Addon/main/repo.json`
3. Filmpalast-Scraper **einschalten**.

### 5b. Aufzeichnung starten (in Termux)

```
adb logcat -c
```
(Log-Puffer leeren, damit nur NEUE Einträge kommen.)

### 5c. Am TV: Suche auslösen

- In ARVIO einen Film öffnen (z.B. **Matrix**) → „nach Quellen suchen"
- 15 Sekunden warten

### 5d. Log in Datei speichern (in Termux)

```
adb logcat -d -v time > ~/arvio-tv-log-v15.txt
```

Erklärung:
- `-d` = aktuellen Puffer ausgeben und beenden (läuft nicht endlos)
- `-v time` = mit Zeitstempel
- `> ~/arvio-tv-log-v15.txt` = in eine Datei schreiben (im Termux-Home)

**Prüfen, dass etwas drin ist:**
```
wc -l ~/arvio-tv-log-v15.txt
```
Sollte mehrere tausend Zeilen zeigen. Steht `0` → Verbindung/Logcat Problem.

---

## Schritt 6: Log filtern (nur das Wichtige)

Die rohe Datei ist riesig und voller anderer App-Logs. Für die Diagnose
brauchen wir nur die ARVIO/Filmpalast-Zeilen.

```
grep -iE "Filmpalast|ArvioAddon|ExternalExtension|PluginManager|No API loaded|ErrorLoading|verify dex" ~/arvio-tv-log-v15.txt > ~/arvio-tv-log-v15-filtered.txt
```

Prüfen:
```
wc -l ~/arvio-tv-log-v15-filtered.txt
cat ~/arvio-tv-log-v15-filtered.txt
```

Die gefilterte Datei ist klein genug zum Weiterleiten.

---

## Schritt 7: Datei in den Download-Ordner kopieren (zum Weiterleiten)

**Abkürzung: Ab jetzt reicht EIN Befehl.** Siehe „Ein-Klick-Skript" unten.
Die folgende Einzel-Schritt-Version ist nur zur Erklärung, falls etwas schiefgeht.

### 7a. Freigabe erteilen (nur einmal nötig)

Termux darf standardmäßig nicht auf den Download-Ordner schreiben. Einmalig
erlauben:

```
mkdir -p /storage/emulated/0/Download/arvio-logs
```

Falls Termux hier nachfragt: **„Erlauben"** antippen und den Download-Ordner
auswählen. (Ab Android 11 braucht Termux diese Scoped-Storage-Freigabe.)

Falls der Ordner schon existiert oder die Freigabe schon erteilt ist, kommt
keine Frage – dann weiter bei 7b.

> **Falls „Permission denied":** Führe aus:
> ```
> termux-setup-storage
> ```
> Bestätige den Freigabe-Dialog am Handy. Danach legt Termux Verknüpfungen
> zu den Standard-Ordnern unter `~/storage/` an (inkl. `~/storage/downloads`).
> Dann kopieren mit:
> ```
> cp ~/arvio-tv-log-v15-filtered.txt ~/storage/downloads/arvio-tv-log-v15-filtered.txt
> ```

### 7b. Datei kopieren

```
cp ~/arvio-tv-log-v15-filtered.txt /storage/emulated/0/Download/arvio-logs/arvio-tv-log-v15-filtered.txt
```

(Oder die ungefilterte Version:
`cp ~/arvio-tv-log-v15.txt /storage/emulated/0/Download/arvio-logs/arvio-tv-log-v15.txt`)

### 7c. Am Handy weiterleiten

Jetzt liegt die Datei im Download-Ordner deines Handys:
```
Download/arvio-logs/arvio-tv-log-v15-filtered.txt
```

Du findest sie über:
- **Dateien-App** (vorinstalliert) → Downloads → arvio-logs
- **Teilen:** Datei lange drücken → Teilen → E-Mail / WhatsApp / Telegram /
  Bluetooth / Drive

So kannst du die Log-Datei direkt von der nächsten Session auswerten lassen
oder in einen Chat pasten.

---

## Schritt 8: Schnell-Ergebnis am Handy lesen (ohne Datei)

Falls du nur schnell wissen willst, ob's läuft, ohne eine Datei zu speichern:

```
adb logcat -d -v time | grep -iE "Filmpalast|ExternalExtension|No API loaded|verify dex"
```

Das zeigt die Treffer direkt im Termux-Fenster. Zum Weiterleiten aber lieber
Schritt 6+7 (Datei), weil der Chat-formatierte Text besser lesbar ist.

---

## Was die Log-Einträge bedeuten (Kurzauswertung)

| Du siehst im Log … | Bedeutung |
|---|---|
| `Executing DEX scraper: FilmPalast` | Scraper wird aufgerufen – gut |
| `Downloaded extension …FilmPalast: N bytes` | Download geklappt |
| `Failure to verify dex file …` | DEX kaputt → würde nächsten Bug zeigen |
| `No API loaded for scraper: …FilmPalast` | Plugin-Klasse konnte nicht geladen/instanziiert werden |
| `MISSING CLASS` / `NoClassDefFoundError` | fehlt eine Kotlin/cloudstream3-Klasse (R8) |
| `API loaded` / „Filmpalast"-Quellen | **Erfolg!** Override bindet, Scraper läuft |
| `ErrorLoadingException: No id found` | Parent läuft noch → Patch/Dispatch nicht gebunden |
| gar kein `Filmpalast`-Eintrag | Scraper wird gar nicht aufgerufen (Enable/Routing) |

---

## Ein-Klick-Skript: `save-tv-log` (alles in einem Befehl)

Statt Schritt 5+6+7 jedesmal einzeln tippen, gibt es ein Skript, das alles macht:
Logcat auslesen → filtern → in Download-Ordner kopieren → Medienscan (damit
Chat-Apps die Datei finden) → Vorschau der ersten Zeilen.

### Einmalig einrichten

Das Skript liegt im Repo unter `docs/save-tv-log.sh`. Kopiere es nach Termux
und mach es ausführbar. In Termux (einmalig):

```
# Wenn du das Repo geklont hast:
cp docs/save-tv-log.sh ~/save-tv-log.sh

# Sonst direkt aus GitHub raw laden:
curl -sL https://raw.githubusercontent.com/ReichiMD/Arvio-Addon/main/docs/save-tv-log.sh -o ~/save-tv-log.sh

chmod +x ~/save-tv-log.sh
```

**IP anpassen (falls dein TV nicht 192.168.0.59 hat):**
```
nano ~/save-tv-log.sh
```
Zeile `TV_IP="192.168.0.59"` anpassen, speichern (Strg+O, Enter, Strg+X).

### Künftig: nur noch das hier

Nach dem Testen am TV (Suche ausgelöst, 15s gewartet) in Termux:

```
~/save-tv-log.sh
```

Das Skript macht automatisch:
1. `adb connect 192.168.0.59:5555`
2. Logcat auslesen → `arvio-tv-log-v15.txt`
3. Filtern → `arvio-tv-log-v15-filtered.txt`
4. Kopieren → `Download/arvio-logs/`
5. Medienscan (Chat-Apps finden die Datei)
6. Vorschau der ersten 20 Zeilen im Terminal

Danach: Dateimanager → Downloads → arvio-logs → Datei lange drücken → Teilen.

### Skript-Inhalt (falls du es manuell anlegen willst)

```bash
#!/data/data/com.termux/files/usr/bin/bash
set -e
TV_IP="192.168.0.59"
TV_PORT="5555"
LOG_RAW="$HOME/arvio-tv-log-v15.txt"
LOG_FILTERED="arvio-tv-log-v15-filtered.txt"
DOWNLOAD_DIR="$HOME/storage/downloads/arvio-logs"

adb connect ${TV_IP}:${TV_PORT}
adb logcat -d -v time > "$LOG_RAW"
grep -iE "Filmpalast|ArvioAddon|ExternalExtension|PluginManager|No API loaded|ErrorLoading|verify dex|MISSING CLASS|CloudstreamPlugin|Executing DEX" \
  "$LOG_RAW" > "$HOME/${LOG_FILTERED}"
mkdir -p "$DOWNLOAD_DIR"
cp "$HOME/${LOG_FILTERED}" "$DOWNLOAD_DIR/"
am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
  -d "file:///storage/emulated/0/Download/arvio-logs/${LOG_FILTERED}" 2>/dev/null
echo "FERTIG: Download/arvio-logs/${LOG_FILTERED}"
head -20 "$HOME/${LOG_FILTERED}"
```

---

## Kurz-Cheat-Sheet (für später, wenn alles eingerichtet ist)

Nach der Ersteinrichtung (Schritte 1–3 einmalig) reicht pro Test-Session:

```
adb connect 192.168.1.42:5555      # verbinden (TV muss an + WDebug an)
adb logcat -c                      # Puffer leeren
# → am TV: Matrix suchen, 15s warten
adb logcat -d -v time > ~/arvio-tv-log-v15.txt
grep -iE "Filmpalast|ExternalExtension|No API loaded|verify dex" ~/arvio-tv-log-v15.txt > ~/arvio-tv-log-v15-filtered.txt
cp ~/arvio-tv-log-v15-filtered.txt /storage/emulated/0/Download/arvio-logs/
```

Dann in der Dateien-App: `Downloads/arvio-logs/arvio-tv-log-v15-filtered.txt`
teilen.
