#!/bin/sh
# Baut aus ~/stalker-channels.json eine M3U-Playlist (lokal, im WLAN bleibt alles privat).
# Aufruf: sh ~/build-stalker-m3u.sh <portal-url> <mac>
PORTAL="$1"
MAC="$2"
if [ -z "$PORTAL" ] || [ -z "$MAC" ]; then
  echo "Aufruf: sh ~/build-stalker-m3u.sh <portal-url> <mac>"
  exit 1
fi
ROOT=$(echo "$PORTAL" | sed -E 's#^(https?://[^/]+).*#\1#')
IN=~/stalker-channels.json
OUT=~/stalker-playlist.m3u

if [ ! -s "$IN" ]; then
  echo "FEHLER: $IN fehlt. Erst test-stalker3.sh ausfuehren."
  exit 1
fi

echo "#EXTM3U" > "$OUT"
sed 's/{"id"/\n{"id"/g' "$IN" | while read L; do
  NAME=$(echo "$L" | sed -n 's/.*"name":"\([^"]*\)".*/\1/p')
  ID=$(echo "$L" | sed -n 's#.*localhost/ch/\([0-9]*\)_.*#\1#p')
  if [ -n "$NAME" ] && [ -n "$ID" ]; then
    echo "#EXTINF:-1,$NAME" >> "$OUT"
    echo "$ROOT/play/live.php?mac=$MAC&stream=$ID&extension=ts" >> "$OUT"
  fi
done

CNT=$(grep -c '^#EXTINF' "$OUT")
echo "Fertig: $CNT Kanaele in $OUT"
IP=$(ip -4 addr show wlan0 2>/dev/null | awk '/inet /{print $2}' | cut -d/ -f1)
echo ""
echo "=== NAECHSTER SCHRITT ==="
echo "1) In Termux:  pkg install python -y  &&  cd ~ && python -m http.server 8088"
echo "2) Handy-IP:  $IP"
echo "3) In ARVIO die Playlist-URL eintragen:"
echo "   http://$IP:8088/stalker-playlist.m3u"
echo "(Der Befehl in 1) muss laufen bleiben, solange du schauen willst.)"
