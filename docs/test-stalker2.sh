#!/bin/sh
# Stalker-Pfad-Sucher v2: probiert uebliche Portal-Pfade, zeigt Antwort je Pfad.
# Aufruf: sh ~/test-stalker2.sh <portal-url> <mac>
PORTAL="$1"
MAC="$2"
if [ -z "$PORTAL" ] || [ -z "$MAC" ]; then
  echo "Aufruf: sh ~/test-stalker2.sh <portal-url> <mac>"
  exit 1
fi
UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
XUA="Model: MAG254, Link: Ethernet"
REF="$PORTAL"
ROOT=$(echo "$PORTAL" | sed -E 's#^(https?://[^/]+).*#\1#')

echo "ROOT=$ROOT PORTAL=$PORTAL MAC=$MAC"
for P in \
  "$PORTAL/stalker_portal/server/load.php" \
  "$PORTAL/server/load.php" \
  "$PORTAL/load.php" \
  "$ROOT/stalker_portal/server/load.php" \
  "$ROOT/server/load.php" \
  "$ROOT/c/server/load.php" \
  "$ROOT/c/load.php" \
  "$ROOT/portal.php"
do
  URL="$P?type=stb&action=handshake&mac=$MAC"
  CODE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 15 "$URL" \
        -H "User-Agent: $UA" -H "X-User-Agent: $XUA" -H "Referer: $REF")
  BODY=$(curl -s --max-time 15 "$URL" \
        -H "User-Agent: $UA" -H "X-User-Agent: $XUA" -H "Referer: $REF" | head -c 120)
  echo "TRY $P -> HTTP $CODE -> $BODY"
done
