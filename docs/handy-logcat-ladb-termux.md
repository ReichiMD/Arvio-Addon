# Logcat am Handy: LADB (einmalig) + Termux

Ziel: Die ARVIO-Scraper-Logs (Logcat) direkt am Handy auslesen — **ohne Laptop, ohne USB-Kabel, ohne WLAN-Verbindung**. Einmal eingerichtet, danach brauchst du nur noch Termux.

> Warum zwei Apps? Termux kann den Befehl `logcat` ausführen, aber Android blockiert Logcat für normale Apps. LADB (einmalig) gibt Termux die nötige Berechtigung — danach brauchst du LADB nicht mehr.

---

## Was du brauchst

- **LADB** (App, ca. 3 € im Play Store — oder gratis via GitHub)
- **Termux** (App, gratis — am besten von F-Droid, die Play-Store-Version ist veraltet)
- Dein Handy (Pixel 7) mit aktivierten Entwickleroptionen

---

## Teil 1: Vorbereitung (einmalig)

### Schritt 1 — Entwickleroptionen aktivieren
Falls schon gemacht, überspringen.

1. Handy-Einstellungen → **Über das Telefon**.
2. 7× schnell hintereinander auf **Build-Nummer** tippen, bis „Sie sind jetzt Entwickler" kommt.
3. Zurück in die Einstellungen → **System** → **Entwickleroptionen** erscheint.

### Schritt 2 — Drahtloses Debugging einschalten
1. Entwickleroptionen → **Drahtloses Debugging** (Wireless Debugging) → **AN**.
2. Falls eine Warnung kommt: bestätigen.
3. Tippe auf „Drahtloses Debugging" (den Eintrag selbst, nicht nur den Schalter), um in das Untermenü zu kommen.
   - Dort siehst du: IP-Adresse & Port (z. B. `192.168.1.5:43567`).
   - Unten gibt es den Punkt **„Gerät mit Pairing-Code koppeln"**.

> WLAN-Schalter AN lassen (auch ohne verbundenes Netz). Manche Handys verlangen das, damit Drahtloses Debugging läuft.

### Schritt 3 — Termux installieren
1. Termux installieren (F-Droid-Empfehlung: f-droid.org → „Termux").
2. Öffne Termux einmal kurz, damit es sich initialisiert, dann wieder schließen.

---

## Teil 2: LADB einmalig einrichten + Termux Berechtigung geben

### Schritt 4 — Pairing-Code holen
1. Entwickleroptionen → Drahtloses Debugging → **„Gerät mit Pairing-Code koppeln"** tippen.
2. Es erscheint ein Fenster mit:
   - **IP-Adresse & Pairing-Port** (z. B. `192.168.1.5:43567` — Achtung: der Pairing-Port ist ein ANDERER Port als oben!)
   - **6-stelliger Pairing-Code** (z. B. `435271`)
3. Lass dieses Fenster **offen** — du hast ca. 60 Sekunden Zeit.

### Schritt 5 — In LADB pairen
1. **LADB** öffnen.
2. LADB fragt nach IP, Port und Pairing-Code → genau eintragen, was im Pairing-Fenster steht.
3. Auf „Verbinden"/„Pair" tippen.
4. Wenn's klappt: LADB zeigt eine ADB-Shell (so was wie ein Kommandozeilen-Fenster mit `$`).
   - Falls „no devices/emulators found" oder Pairing abgelaufen: Schritt 4 wiederholen (neuer Code, neuer Versuch). Der Timer ist zickig — beim 2. oder 3. Versuch klappt's meistens.

### Schritt 6 — Termux die Logcat-Berechtigung geben (DER einmalige Schritt!)
Jetzt, wo du in der LADB-Shell bist, **genau diesen einen Befehl** eintippen und Enter drücken:

```
pm grant com.termux android.permission.READ_LOGS
```

- Wenn nichts als Fehlermeldung kommt → hat geklappt (keine Rückmeldung = Erfolg).
- Falls „Permission Denial" oder Fehler → nochmal exakt so eintippen.

**Das war's mit LADB!** Du kannst LADB jetzt schließen. Die Berechtigung überlebt Neustarts und ARVIO-Updates. Du musst das **nie wieder** machen.

> Falls du prüfen willst, ob's geklappt hat: in Termux (nicht LADB) `logcat -d | head` eingeben. Wenn du viele Textzeilen siehst → Berechtigung da. Wenn „permission denied" → Schritt 6 wiederholen.

---

## Teil 3: Logcat holen (jedes Mal, wenn du testest)

Jetzt nur noch **Termux**. Der Ablauf beim Testen:

### Schritt 7 — Puffer leeren
Termux öffnen, eintippen:
```
logcat -c
```
(Das leert den Protokoll-Speicher, damit du nur die neuen Einträge vom Test bekommst.)

### Schritt 8 — In ARVIO die Suche auslösen
1. ARVIO öffnen.
2. Filmpalast einschalten (falls nicht an).
3. Z. B. **Matrix** suchen → auf den Film → **„Nach Quellen suchen"**.
4. Ca. **15 Sekunden warten** (bis ARVIO fertig gesucht hat).

### Schritt 9 — Log in eine Datei schreiben
Zurück in Termux, eintippen:
```
logcat -d | grep -iE "Filmpalast|ArvioAddon|ExtExt|Error|No.API|load" > /sdcard/arvio-log-v21.txt
```

Erklärung (musst du nicht merken):
- `logcat -d` = holt das gesamte Protokoll.
- `grep -iE "..."` = filtert nur die wichtigen Zeilen (Filmpalast, Fehler, etc.).
- `> /sdcard/arvio-log-v21.txt` = speichert das Ergebnis als Datei im internen Speicher.

### Schritt 10 — Datei finden und mir schicken
1. Dateimanager öffnen (z. B. „Dateien" von Google).
2. Interner Speicher → die Datei `arvio-log-v21.txt` suchen.
   - Pfad: `/sdcard/` bzw. „Interner gemeinsamer Speicher".
3. Datei lange drücken → **Teilen** → hier in den Chat ziehen/hochladen.

---

## Wenn etwas nicht klappt

| Problem | Lösung |
|---|---|
| LADB: „no devices/emulators found" | Pairing-Timer abgelaufen. Schritt 4+5 wiederholen (neuer Code). |
| LADB: Pairing klappt einfach nicht | Alternative: **Shizuku** (gratis, stabiler beim Pairing). Gleicher Ablauf, nur Shizuku statt LADB für Schritt 5-6. |
| Termux: „permission denied" bei logcat | Schritt 6 (Berechtigung) nicht geklappt. LADB nochmal öffnen, Befehl wiederholen. |
| Termux (Play-Store-Version): funktioniert nicht richtig | Play-Store-Termux ist veraltet. F-Droid-Version installieren. |
| Datei `arvio-log-v21.txt` leer | Vielleicht war ARVIO nicht aktiv beim Suchen, oder Filter zu streng. Ohne Filter probieren: `logcat -d > /sdcard/arvio-log-v21.txt` |
| Gar nichts geht am Handy | Zurück zum TV + Laptop (WLAN-ADB, bewährte Methode). |

---

## Zusammenfassung (Einzeiler nach der Einrichtung)

Einmal eingerichtet, reicht für jeden Test dieser Dreiklang in Termux:
```
logcat -c               # leeren
                        # ... ARVIO: Matrix suchen, 15s warten ...
logcat -d | grep -iE "Filmpalast|ArvioAddon|ExtExt|Error|No.API|load" > /sdcard/arvio-log-v21.txt
```
