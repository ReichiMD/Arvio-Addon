#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
# ARVIO-Log am HANDY speichern + filtern (nicht TV!)
# Aufruf in Termux NACH dem Testen in ARVIO am Handy:
#   ~/save-handy-log.sh           # -> Default-Name arvio-handy-log.txt
#   ~/save-handy-log.sh kinoger   # -> arvio-handy-log-kinoger.txt
#   ~/save-handy-log.sh v32       # -> arvio-handy-log-v32.txt
# ============================================================
# WICHTIG: Bevor du das aufrufst, musst du in ARVIO die Quellensuche
# ausgelöst haben (z. B. Matrix suchen, "Nach Quellen suchen", 15s warten).
# Sonst ist die Log-Datei leer, weil nichts passiert ist.

set -e

# Name aus erstem Argument, Default "leer" (= arvio-handy-log.txt)
NAME="${1:-}"
if [ -n "$NAME" ]; then
  LOG_FILE="arvio-handy-log-${NAME}.txt"
else
  LOG_FILE="arvio-handy-log.txt"
fi

DOWNLOAD_DIR="$HOME/storage/downloads/arvio-logs"

echo "=== Filtere ARVIO-Logs am Handy ==="
# Logcat lokal auslesen (kein adb nötig, Berechtigung wurde erteilt),
# filtern nach allen Scrapern + ARVIO-Engine, in Datei speichern.
logcat -d -v time \
  | grep -iE "Filmpalast|Serienstream|Kinoger|ArvioAddon|ExternalExtension|ExtExt|PluginManager|No API loaded|ErrorLoading|verify dex|MISSING CLASS|CloudstreamPlugin|Executing DEX|resolveHost|resolveIncvideo|resolveVoe|resolveDoodstream|genericResolve|emitLink|loadLinks|fetchTmdbMeta|searchSeries|searchKinoger|buildSeriesResponse|parseShowArrays|httpGet|httpPost|detectQuality" \
  > "$HOME/${LOG_FILE}" 2>/dev/null || true

LINES=$(wc -l < "$HOME/${LOG_FILE}" 2>/dev/null || echo 0)
echo "Gefiltert: ${LINES} Zeilen -> ${LOG_FILE}"
echo

echo "=== Kopiere in Download-Ordner ==="
mkdir -p "$DOWNLOAD_DIR"
cp "$HOME/${LOG_FILE}" "$DOWNLOAD_DIR/"
echo "Kopiert nach: Download/arvio-logs/${LOG_FILE}"
echo

echo "=== Medienscan (damit Chat-Apps die Datei finden) ==="
am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
  -d "file:///storage/emulated/0/Download/arvio-logs/${LOG_FILE}" 2>/dev/null \
  | grep -i "result" || echo "Scan ausgeloest."
echo

echo "=============================================="
echo "FERTIG!"
echo "Datei:   Download/arvio-logs/${LOG_FILE}"
echo "Zeilen:  ${LINES}"
echo
if [ "$LINES" -eq 0 ]; then
  echo "!! ACHTUNG: Log ist leer. Warscheinlich hast du in ARVIO"
  echo "!! nicht gesucht, oder der Scraper lief nicht mit."
  echo "!! Ablauf: logcat -c -> ARVIO: Matrix suchen, 15s warten -> ~/save-handy-log.sh"
else
  echo "So weiterleiten:"
  echo "  Dateimanager -> Downloads -> arvio-logs ->"
  echo "  Datei lange druecken -> Teilen -> in Chat hochladen"
fi
echo "=============================================="
