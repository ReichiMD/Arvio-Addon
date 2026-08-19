#!/bin/sh
# Stalker-Kompletttest v3: findet API-Pfad, loggt ein, zaehlt Kanaele, testet M3U-Export.
# Kann LOKAL ausgefuehrt werden (es passiert nichts ausser ein paar Lesen beim eigenen Anbieter).
# Aufruf: sh ~/test-stalker3.sh <portal-url> <mac>
PORTAL="$1"
MAC="$2"
if [ -z "$PORTAL" ] || [ -z "$MAC" ]; then
  echo "Aufruf: sh ~/test-stalker3.sh <portal-url> <mac>"
  echo "Beispiel: sh ~/test-stalker3.sh \"http://a01.live:8080/c/\" \"00:2A:01:97:13:EC\""
  exit 1
fi
UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
XUA="Model: MAG254, Link: Ethernet"
ROOT=$(echo "$PORTAL" | sed -E 's#^(https?://[^/]+).*#\1#')

echo "=== STALKER-KOMPLETTTEST ==="
echo "Portal: $PORTAL  MAC: $MAC"

BASE=""
TOKEN=""
for P in \
  "$ROOT/server/load.php" \
  "$ROOT/stalker_portal/server/load.php" \
  "$PORTAL/stalker_portal/server/load.php" \
  "$PORTAL/server/load.php" \
  "$ROOT/c/server/load.php" \
  "$ROOT/load.php"
do
  RESP=$(curl -s --max-time 15 "$P?type=stb&action=handshake&mac=$MAC" \
    -H "User-Agent: $UA" -H "X-User-Agent: $XUA" -H "Referer: $PORTAL")
  TOK=$(echo "$RESP" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
  if [ -n "$TOK" ]; then
    echo "[OK] Pfad gefunden: $P"
    echo "     Token: ${TOK}"
    BASE="$P"
    TOKEN="$TOK"
    break
  else
    SNIPPET=$(echo "$RESP" | tr '\n' ' ' | head -c 100)
    echo "[--] $P -> '${SNIPPET}'"
  fi
done

if [ -z "$BASE" ]; then
  echo "FEHLER: Kein Pfad mit Token gefunden. Bitte diese Zeilen an die AI weitergeben."
  exit 1
fi

# Profil (Account gueltig?)
P2=$(curl -s --max-time 15 "$BASE?type=stb&action=get_profile&mac=$MAC" \
  -H "User-Agent: $UA" -H "X-User-Agent: $XUA" -H "Referer: $PORTAL" \
  -H "Authorization: Bearer $TOKEN")
echo "PROFIL: $(echo "$P2" | tr '\n' ' ' | head -c 250)"

# Session aktivieren (fortgesetzte Anmeldung, wie echte Player)
curl -s --max-time 15 "$BASE?type=stb&action=activate_session&mac=$MAC" \
  -H "User-Agent: $UA" -H "X-User-Agent: $XUA" -H "Referer: $PORTAL" \
  -H "Authorization: Bearer $TOKEN" > /dev/null

# Kategorien
G=$(curl -s --max-time 20 "$BASE?type=itv&action=get_genres&JsHttpRequest=1-xml&mac=$MAC" \
  -H "User-Agent: $UA" -H "X-User-Agent: $XUA" -H "Referer: $PORTAL" \
  -H "Authorization: Bearer $TOKEN")
GC=$(echo "$G" | grep -o '"title":"[^"]*"' | wc -l)
echo "KATEGORIEN ($GC):"
echo "$G" | grep -o '"title":"[^"]*"' | cut -d'"' -f4 | head -25

# Alle Kanaele
curl -s --max-time 60 "$BASE?type=itv&action=get_all_channels&JsHttpRequest=1-xml&mac=$MAC" \
  -H "User-Agent: $UA" -H "X-User-Agent: $XUA" -H "Referer: $PORTAL" \
  -H "Authorization: Bearer $TOKEN" > ~/stalker-channels.json
CNT=$(grep -o '"name":"[^"]*"' ~/stalker-channels.json | wc -l)
echo "ANZAHL KANAELE: $CNT"
echo "erste Kanaele:"
grep -o '"name":"[^"]*"' ~/stalker-channels.json | cut -d'"' -f4 | head -25
echo "(vollstaendige Liste: ~/stalker-channels.json)"

# M3U-Export testen
M=$(curl -s --max-time 30 "$BASE?type=itv&action=export_m3u&mac=$MAC" \
  -H "User-Agent: $UA" -H "X-User-Agent: $XUA" -H "Referer: $PORTAL" \
  -H "Authorization: Bearer $TOKEN")
if echo "$M" | head -5 | grep -q "#EXTM3U"; then
  echo "$M" > ~/stalker-playlist.m3u
  echo "M3U: JA -> gespeichert in ~/stalker-playlist.m3u"
  echo "$M" | head -8
else
  echo "M3U: NEIN (Antwort: $(echo "$M" | tr '\n' ' ' | head -c 120))"
fi
echo "=== FERTIG. Bitte die gesamte Ausgabe an die AI weitergeben. ==="
