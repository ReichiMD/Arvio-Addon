#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
# ARVIO TV-Log speichern + filtern + in Download-Ordner legen
# Aufruf in Termux NACH dem Testen am TV:
#   save-tv-log           # -> Version aus Dateiname (Default v31)
#   save-tv-log v31       # -> explizite Version
#   save-tv-log v32       # -> naechste Version
# (oder: bash ~/save-tv-log.sh v31)
# ============================================================

set -e
TV_IP="192.168.0.59"      # <-- ggf. an deine TV-IP anpassen
TV_PORT="5555"

# Version aus erstem Argument, Default v31
VERSION="${1:-v31}"
LOG_RAW="$HOME/arvio-tv-log-${VERSION}.txt"
LOG_FILTERED="arvio-tv-log-${VERSION}-filtered.txt"
DOWNLOAD_DIR="$HOME/storage/downloads/arvio-logs"

echo "=== 1/5 Verbinde mit TV ==="
adb connect ${TV_IP}:${TV_PORT}
echo

echo "=== 2/5 Lese Logcat aus (roh) ==="
adb logcat -d -v time > "$LOG_RAW"
LINES=$(wc -l < "$LOG_RAW")
echo "Gelesen: ${LINES} Zeilen -> ${LOG_RAW}"
echo

echo "=== 3/5 Filtere (ARVIO + FilmPalast + Serienstream + KinoGer + DDoS-Guard) ==="
# Filter deckt ALLE Scraper ab: FilmPalast, Serienstream UND KinoGer, plus ARVIO-Engine-Logs
# und die Serienstream-spezifischen DDoS-Guard/resolve-Helfer (Erkenntnis #19) sowie
# KinoGer-spezifische resolveIncvideo/parseShowArrays (Phase 1).
# Eine Zeile (keine Zeilenfortsetzung) fuer maximale Termux/bash-Kompatibilitaet.
grep -iE "Filmpalast|Serienstream|Kinoger|ArvioAddon|ExternalExtension|ExtExt|PluginManager|No API loaded|ErrorLoading|verify dex|MISSING CLASS|CloudstreamPlugin|Executing DEX|ddg|ddos|guard|resolveHost|resolveVoe|resolveIncvideo|resolveDoodstream|resolveStreamtape|resolveFileMoon|resolveVidHide|genericResolve|emitLink|loadLinks|fetchTmdbMeta|searchSeries|searchKinoger|buildSeriesResponse|collectEpisodes|parseShowArrays|httpGet|httpPost|doRequest|CookieJar|voeDecode|detectQuality|TurnstileSolver|turnstile|cf-turnstile|redirectGate|solveAltcha|onPageFinished|WebView" \
  "$LOG_RAW" > "$HOME/${LOG_FILTERED}"
FLINES=$(wc -l < "$HOME/${LOG_FILTERED}")
echo "Gefiltert: ${FLINES} Zeilen -> ${LOG_FILTERED}"
echo

echo "=== 4/5 Kopiere in Download-Ordner ==="
mkdir -p "$DOWNLOAD_DIR"
cp "$HOME/${LOG_FILTERED}" "$DOWNLOAD_DIR/"
echo "Kopiert nach: Download/arvio-logs/${LOG_FILTERED}"
echo

echo "=== 5/5 Medienscan (damit Chat-Apps die Datei finden) ==="
am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
  -d "file:///storage/emulated/0/Download/arvio-logs/${LOG_FILTERED}" 2>/dev/null \
  | grep -i "result" || echo "Scan ausgeloest."
echo

echo "=============================================="
echo "FERTIG!"
echo "Version: ${VERSION}"
echo "Datei:   Download/arvio-logs/${LOG_FILTERED}"
echo "Gefiltert: ${FLINES} Zeilen"
echo
echo "So weiterleiten:"
echo "  Dateimanager -> Downloads -> arvio-logs ->"
echo "  Datei lange druecken -> Teilen -> WhatsApp/E-Mail"
echo "=============================================="
echo
echo "--- Vorschau (erste 20 gefilterte Zeilen) ---"
head -20 "$HOME/${LOG_FILTERED}"
