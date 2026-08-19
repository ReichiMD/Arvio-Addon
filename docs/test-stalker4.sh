#!/bin/sh
# Stalker Test 2 (nach erfolgreichem v3): VOD/Serien pruefen + Stream-Links direkt testen.
# Aufruf: sh ~/test-stalker4.sh <portal-url> <mac>
PORTAL="$1"
MAC="$2"
if [ -z "$PORTAL" ] || [ -z "$MAC" ]; then
  echo "Aufruf: sh ~/test-stalker4.sh <portal-url> <mac>"
  exit 1
fi
UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
XUA="Model: MAG254, Link: Ethernet"
ROOT=$(echo "$PORTAL" | sed -E 's#^(https?://[^/]+).*#\1#')
BASE="$ROOT/server/load.php"

echo "=== STALKER TEST 2 (VOD + Stream-Links) ==="

# Login (Handshake)
RESP=$(curl -s --max-time 15 "$BASE?type=stb&action=handshake&mac=$MAC" -H "User-Agent: $UA" -H "X-User-Agent: $XUA" -H "Referer: $PORTAL")
TOKEN=$(echo "$RESP" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
if [ -z "$TOKEN" ]; then echo "FEHLER: kein Token. Abbruch."; exit 1; fi
echo "[OK] Login erfolgreich."
curl -s --max-time 15 "$BASE?type=stb&action=activate_session&mac=$MAC" -H "User-Agent: $UA" -H "X-User-Agent: $XUA" -H "Authorization: Bearer $TOKEN" > /dev/null

# --- VOD (Filme) ---
V=$(curl -s --max-time 20 "$BASE?type=vod&action=get_categories&JsHttpRequest=1-xml&mac=$MAC" -H "User-Agent: $UA" -H "Authorization: Bearer $TOKEN" -H "X-User-Agent: $XUA")
VC=$(echo "$V" | grep -o '"title":"[^"]*"' | wc -l)
echo "VOD-KATEGORIEN: $VC"
echo "$V" | grep -o '"title":"[^"]*"' | cut -d'"' -f4 | head -15

if [ "$VC" -gt 0 ]; then
  curl -s --max-time 40 "$BASE?type=vod&action=get_ordered_list&sortby=added&JsHttpRequest=1-xml&mac=$MAC" -H "User-Agent: $UA" -H "Authorization: Bearer $TOKEN" -H "X-User-Agent: $XUA" > ~/stalker-vod.json
  VT=$(grep -o '"total_items": *"?[0-9]*' ~/stalker-vod.json | head -1 | grep -o '[0-9]*')
  echo "VOD-TITEL GESAMT: $VT (Beispiele:)"
  grep -o '"name":"[^"]*"' ~/stalker-vod.json | cut -d'"' -f4 | head -10
fi

# --- Serien ---
S=$(curl -s --max-time 20 "$BASE?type=series&action=get_categories&JsHttpRequest=1-xml&mac=$MAC" -H "User-Agent: $UA" -H "Authorization: Bearer $TOKEN" -H "X-User-Agent: $XUA" -H "Referer: $PORTAL")
SC=$(echo "$S" | grep -o '"title":"[^"]*"' | wc -l)
echo "SERIEN-KATEGORIEN: $SC"
echo "$S" | grep -o '"title":"[^"]*"' | cut -d'"' -f4 | head -15

# --- Stream-Link-Test: 3 Live-Kanaele aufdirekt testen ---
echo "--- Stream-Link-Test (3 Kanaele) ---"
if [ ! -s ~/stalker-channels.json ]; then
  echo "HINWEIS: ~/stalker-channels.json fehlt (lade zuerst test-stalker3.sh). Versuche sie kurz neu zu holen."
  curl -s --max-time 60 "$BASE?type=itv&action=get_all_channels&JsHttpRequest=1-xml&mac=$MAC" -H "User-Agent: $UA" -H "Authorization: Bearer $TOKEN" -H "X-User-Agent: $XUA" > ~/stalker-channels.json
fi

N=0
grep -o '"name":"[^"]*","[^"]*":"[^"]*"\|"cmd":"[^"]*"' ~/stalker-channels.json > /dev/null 2>&1
CMDS=$(grep -o '"cmd":"[^"]*"' ~/stalker-channels.json | cut -d'"' -f4)
for C in $CMDS; do
  if [ "$N" -ge 3 ]; then break; fi
  N=$((N+1))
  RAW=$(echo "$C" | sed 's#\\/#/#g')
  PAD=$(echo "$RAW" | sed 's/ /%20/g')
  L=$(curl -s --max-time 20 "$BASE?type=itv&action=create_link&cmd=$PAD&JsHttpRequest=1-xml&mac=$MAC" -H "User-Agent: $UA" -H "Authorization: Bearer $TOKEN" -H "X-User-Agent: $XUA")
  U=$(echo "$L" | grep -o '"cmd":"[^"]*"' | head -1 | cut -d'"' -f4 | sed 's#\\/#/#g;s/^fft* *//' )
  echo "  link #$N: $(echo "$U" | head -c 120)"
  if [ -n "$U" ]; then
    H=$(curl -s -o /dev/null -w "%{http_code}" --max-time 15 "$U")
    echo "        -> HTTP $H (m3u8? $(echo "$U" | grep -c 'm3u8'))"
  fi
done

echo "=== FERTIG. Bitte Ausgabe an die AI. ==="
