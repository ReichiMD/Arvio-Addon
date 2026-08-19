#!/bin/sh
# Stalker Test 4: 302-Umleitungen bis zum Ziel folgen + play_token-Notwendigkeit pruefen.
PORTAL="$1"
MAC="$2"
if [ -z "$PORTAL" ] || [ -z "$MAC" ]; then
  echo "Aufruf: sh ~/test-stalker6.sh <portal-url> <mac>"
  exit 1
fi
UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
XUA="Model: MAG254, Link: Ethernet"
ROOT=$(echo "$PORTAL" | sed -E 's#^(https?://[^/]+).*#\1#')
BASE="$ROOT/server/load.php"

RESP=$(curl -s --max-time 15 "$BASE?type=stb&action=handshake&mac=$MAC" -H "User-Agent: $UA" -H "X-User-Agent: $XUA" -H "Referer: $PORTAL")
TOKEN=$(echo "$RESP" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
if [ -z "$TOKEN" ]; then echo "FEHLER: kein Token."; exit 1; fi
curl -s --max-time 15 "$BASE?type=stb&action=activate_session&mac=$MAC" -H "User-Agent: $UA" -H "Authorization: Bearer $TOKEN" > /dev/null
echo "[OK] Login."

if [ ! -s ~/stalker-channels.json ]; then
  curl -s --max-time 60 "$BASE?type=itv&action=get_all_channels&JsHttpRequest=1-xml&mac=$MAC" -H "User-Agent: $UA" -H "Authorization: Bearer $TOKEN" -H "X-User-Agent: $XUA" > ~/stalker-channels.json
fi

grep -o '"cmd":"[^"]*"' ~/stalker-channels.json | cut -d'"' -f4 | sed 's#\\/#/#g' | head -3 | while read C; do
  ENC=$(echo "$C" | tr -s ' ' | sed 's/ /%20/g')
  L=$(curl -s --max-time 20 "$BASE?type=itv&action=create_link&cmd=$ENC&JsHttpRequest=1-xml&mac=$MAC" -H "User-Agent: $UA" -H "Authorization: Bearer $TOKEN" -H "X-User-Agent: $XUA")
  U=$(echo "$L" | grep -o '"cmd":"[^"]*"' | head -1 | cut -d'"' -f4 | sed 's#\\/#/#g;s#^[^ ]* ##')
  echo "---"
  echo "URL: $U"
  # A) Umleitung ansehen + bis zum Ziel folgen
  LOC=$(curl -s -I --max-time 15 "$U" | sed -n 's/^[Ll]ocation: *//p' | tr -d '\r')
  echo "  Umleitung -> $LOC"
  FINAL=$(curl -s -L -o /dev/null -w "%{http_code} %{content_type} %{url_effective}" --max-time 20 "$U")
  echo "  Ziel      -> $FINAL"
  # B) ohne play_token probieren
  NOTOK=$(echo "$U" | sed 's/&play_token=[^&]*//')
  F2=$(curl -s -L -o /dev/null -w "%{http_code} %{content_type}" --max-time 20 "$NOTOK")
  echo "  ohne Token -> $F2"
done
echo "=== FERTIG. Ausgabe bitte an die AI. ==="
