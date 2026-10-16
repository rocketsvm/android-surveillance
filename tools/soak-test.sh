#!/usr/bin/env bash
# Long-running stability check against a phone running the app.
#   tools/soak-test.sh <phone-ip> [minutes=45] [port=8080] [out.csv]
# Keeps one ffmpeg viewer on /live.mp4 and samples status, memory and temperature every 30 s.
set -u
IP="${1:?phone ip}"
MIN="${2:-45}"
PORT="${3:-8080}"
OUT="${4:-soak-$(date +%Y%m%d-%H%M).csv}"
BASE="http://$IP:$PORT"
PKG=com.surveil.camera
unset HTTP_PROXY http_proxy HTTPS_PROXY https_proxy ALL_PROXY all_proxy

env -u HTTP_PROXY -u http_proxy ffmpeg -nostdin -loglevel error -i "$BASE/live.mp4" -f null - 2> "$OUT.viewer.log" &
VIEWER=$!
echo "time,camera,fps,kbps,viewers,recording,battery,tempC,pss_kb,recordings,viewer_alive" > "$OUT"
END=$(( $(date +%s) + MIN * 60 ))
while [ "$(date +%s)" -lt "$END" ]; do
  ST=$(curl -s -m 5 "$BASE/api/status" | python -I -c "
import json,sys
try:
    d=json.load(sys.stdin)
    print(','.join(str(x) for x in [d['camera']['state'], d['stream']['fps'], d['stream']['kbps'], d['stream']['viewers'],
        int(d['recording']['active']), d['device'].get('battery',''), d['device'].get('tempC',''), d['storage']['count']]))
except Exception as e:
    print('DOWN,,,,,,,')
")
  PSS=$(adb shell dumpsys meminfo $PKG 2>/dev/null | grep -m1 -E "TOTAL PSS:|TOTAL:" | grep -oE "[0-9]+" | head -1)
  ALIVE=$(kill -0 $VIEWER 2>/dev/null && echo 1 || echo 0)
  IFS=, read -r CAM FPS KBPS VIEW REC BAT TEMP COUNT <<< "$ST"
  echo "$(date +%H:%M:%S),$CAM,$FPS,$KBPS,$VIEW,$REC,$BAT,$TEMP,${PSS:-},$COUNT,$ALIVE" | tee -a "$OUT"
  sleep 30
done
kill $VIEWER 2>/dev/null
echo "viewer errors:"; head -20 "$OUT.viewer.log"
