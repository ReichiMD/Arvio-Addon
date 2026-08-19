#!/bin/sh
# Stalker-Portal M3U/Erreichbarkeitstest (sicher: laeuft lokal auf DEINEM Geraet)
# Aufruf: sh ~/test-stalker.sh <portal-url> <mac>
# Beispiel: sh ~/test-stalker.sh "http://a01.live:8080/c/" "00:2A:01:97:13:EC"

PORTAL="$1"
MAC="$2"
if [ -z "$PORTAL" ] || [ -z "$MAC" ]; then
  echo "Aufruf: sh ~/test-stalker.sh <portal-url> <mac>"
  exit 1
fi
OUT=~/stalker-test.txt
UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64)"

echo "=== Stalker-Test auf $(head -c 1 "$0" >/dev/null; date) ===" | tee "$OUT"
echo "Portal: $PORTAL" | tee -a "$OUT"
echo "MAC: $MAC" | tee -a "$OUT"

# 1) Handshake -> Token holen
RESP=$(curl -s --max-time 20 "${PORTAL}stalker_portal/server/load.php?type=stb&action=handshake&mac=${MAC}" -H "User-Agent: $UA")
echo "[1] Handshake: $RESP" | tee -a "$OUT"
TOKEN=$(echo "$RESP" | grep -o '"token":"[^"]*"' | cut -d'"' -f3)
if [ -z "$TOKEN" ]; then
  echo "FEHLER: kein Token - Portal blockowrdig oder Pfad falsch." | tee -a "$OUT"
  exit 1
fi
echo "[2] Token erhalten: ${TOKEN}..." | tee -a "$OUT"

# 2) Profil (Account-Check)
RESP2=$(curl -s --max-time 20 "${PORTAL}stalker_portal/server/load.php?type=stb&action=get_profile&mac=${MAC}" -H "User-Agent: $UA" -H "Authorization: Bearer $TOKEN")
echo "[3] Profil: $(echo "$RESP2" | head -c 400)" | tee -a "$OUT"

# 3) Genres (Kategorien) - Zaehlung
RESP3=$(curl -s --max-time 20 "${PORTAL}stalker_portal/server/load.php?type=itv&action=get_genres&JsHttpRequest=1-xml&mac=${MAC}" -H "User-Agent: $UA" -H "Authorization: Bearer $TOKEN")
GEN=$(echo "$RESP3" | grep -o '"id":"[^"]*","title":"[^"]*"' | cut -d'"' -f6)
CNT=$(echo "$GEN" | grep -c .)
echo "[4] Kategorien ($CNT):" | tee -a "$OUT"
echo "$GEN" | head -40 | tee -a "$OUT"

# 4) Alle Kanaele holen und Liste speichern
curl -s --max-time 60 "${PORTAL}stalker_portal/server/load.php?type=itv&action=get_all_channels&JsHttpRequest=1-xml&mac=${MAC}" -H "User-Agent: $UA" -H "Authorization: Bearer $TOKEN" > ~/stalker-channels-raw.json
echo "[5] Kanal-JSON gespeichert in ~/stalker-channels-raw.json ($(wc -c < ~/stalker-channels-raw.json) Bytes)" | tee -a "$OUT"
CH=$(grep -o '"name":"[^"]*"' ~/stalker-channels-raw.json | cut -d'"' -f4)
echo "    Anzahl Kanaele: $(echo "$CH" | grep -c .)" | tee -a "$OUT"
echo "$CH" | head -50 >> "$OUT"

echo "FERTIG. Ergebnis in $OUT" | tee -a "$OUT"
