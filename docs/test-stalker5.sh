#!/bin/sh
# Stalker Test 3 (fix): echte Stream-URLs auf direkte Abspielbarkeit pruefen.
# Aufruf: sh ~/test-stalker5.sh <portal-url> <mac>
PORTAL="$1"
MAC="$2"
if [ -z "$PORTAL" ] || [ -z "$MAC" ]; then
  echo "Aufruf: sh ~/test-stalker5.sh <portal-url> <mac>"
  exit 1
fi
UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
XUA="Model: MAG254, Link: Ethernet"
ROOT=$(echo "$PORTAL" | sed -E 's#^(https?://[^/]+).*#\1#')
BASE="$ROOT/server/load.php"

echo "=== STALKER TEST 3 (Stream-Links, korrigiert) ==="

RESP=$(curl -s --max-time 15 "$BASE?type=stb&action=handshake&mac=$MAC" -H "User-Agent: $UA" -H "X-User-Agent: $XUA" -H "Referer: $PORTAL")
TOKEN=$(echo "$RESP" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
if [ -z "$TOKEN" ]; then echo "FEHLER: kein Token."; exit 1; fi
curl -s --max-time 15 "$BASE?type=stb&action=activate_session&mac=$MAC" -H "User-Agent: $UA" -H "Authorization: Bearer $TOKEN" > /dev/null
echo "[OK] Login."

# Kanalliste neu laden falls zu alt
if [ ! -s ~/stalker-channels.json ]; then
  curl -s --max-time 60 "$BASE?type=itv&action=get_all_channels&JsHttpRequest=1-xml&mac=$MAC" -H "User-Agent: $UA" -H "Authorization: Bearer $TOKEN" -H "X-User-Agent: $XUA" > ~/stalker-channels.json
fi

N=0
grep -o '"cmd":"[^"]*"' ~/stalker-channels.json | cut -d'"' -f4 | sed 's#\\/#/#g' | head -5 | while read C; do
  N=$((N+1))
  # normalize whitespace
  CSET=$(echo "$C" | tr -s ' ')
  ENC=$(echo "$CSET" | sed 's/ /%20/g')
  L=$(curl -s --max-time 20 "$BASE?type=itv&action=create_link&cmd=$ENC&JsHttpRequest=1-xml&mac=$MAC" -H "User-Agent: $UA" -H "Authorization: Bearer $TOKEN" -H "X-User-Agent: $XUA")
  U=$(echo "$L" | grep -o '"cmd":"[^"]*"' | head -1 | cut -d'"' -f4 | sed 's#\\/#/#g;s#^[^ ]* ##')
  echo "---"
  echo "Kanal $N: $C"
  echo "  resolved URL: $(echo "$U" | head -c 100)"
  if [ -n "$U" ]; then
    H1=$(curl -s -o /dev/null -w "%{http_code} %{content_type}" --max-time 15 "$U")
    echo "  ohne Header  : $H1"
    H2=$(curl -s -o /dev/null -w "%{http_code} %{content_type}" --max-time 15 -H "User-Agent: $UA" -H "Referer: $PORTAL" "$U")
    echo "  mit Header   : $H2"
  fi
done
echo "=== FERTIG. Ausgabe an die AI bitte. ==="
