# Android Surveillance Camera

[Русская версия](README_RU.md) · [Releases](https://github.com/rocketsvm/android-surveillance/releases)

Turns an Android phone into an IP security camera:

- **Live H.264 video** in your browser on a computer or another phone — with about half a second of latency,
  no plugins, no internet required (everything works on your local network).
- **Works with the screen off** and after you exit the app; survives phone reboots.
- **Motion detection** with adjustable sensitivity and exclusion zones.
- **Motion-triggered recording** (with a few seconds of pre-roll), **continuous**, or **manual**;
  automatic cleanup of old recordings by count, size, or age.
- **Browse and download recordings** from your browser, event log.
- **All settings** — both on the phone and in the browser (same settings, synced in real time).
- Date/time and camera name overlay, flashlight, zoom, camera selection (including wide-angle),
  microphone audio (optional), password protection.
- Stream for **NVRs, VLC, Home Assistant**: `rtsp://<address>:8554/live`
  (H.264 + AAC, RTP over TCP or UDP) and `http://<address>:8080/live.mp4`.
- **Telegram notifications** via your own bot: snapshot on motion, recording clips,
  charger unplugged (e.g., power outage), camera failure.

## Installation

1. Copy `SurveillanceCamera.apk` to your phone and open it (grant permission to install from this source if prompted).
2. Launch "Surveillance Camera" — you'll be guided through setup:
   - camera and notification permissions;
   - "Unrestricted" battery settings (otherwise the system may put the camera to sleep);
   - connection address and QR code;
   - auto-start after reboot (optional).

Requires Android 8.0 or newer. Best used as a dedicated phone on constant charge.

### Tips for specific ROMs

- **Samsung**: "Settings → Apps → Surveillance Camera → Battery → Unrestricted".
  In "Device care → Auto-optimizations", disable scheduled restarts
  (or enable camera auto-start).
- **Xiaomi / Redmi / POCO**: in app settings, enable "Auto-start" and
  "Activity control → No restrictions".
- **Huawei / Honor**: "App launch" → manual management, allow background activity.
- Disable the phone's screen lock: after a reboot, Android only starts apps after the first unlock.

## How to view

The phone and computer (or second phone) must be on the **same Wi-Fi network**.
The address like `http://192.168.1.23:8080` is shown on the app's main screen (with QR code) —
open it in your browser (Chrome, Edge, Firefox, Safari; on iPhone — iOS 17.1 or newer, on older
iPhones use the VLC app with the RTSP address).

- **Camera** — live video, snapshot, manual record, flashlight, recording mode.
- **Recordings** — by day, with thumbnails; play, download, delete.
- **Events** — motion, recording start/stop, errors.
- **Settings** — all camera, detection, recording, and access parameters.

To keep the address stable, reserve an IP for the phone in your router settings (DHCP reservation).

### Internet access

The simplest and safest way — use a VPN network like **Tailscale** or **ZeroTier**: install it
on the camera phone and on the device you're viewing from. The VPN address will appear in the address list
in the app. Only open a router port if you have a password set.

### Password

"Settings → Access → Password". The browser will show a login form; VLC and NVRs can
use addresses like `rtsp://admin:password@192.168.1.23:8554/live` or
`http://admin:password@192.168.1.23:8080/live.mp4`.

### NVR, Home Assistant, VLC

- **VLC**: "Media → Open Network Stream" → `rtsp://192.168.1.23:8554/live`.
- **Home Assistant**: Generic Camera integration, Stream Source `rtsp://192.168.1.23:8554/live`,
  Still Image `http://192.168.1.23:8080/api/snapshot.jpg`.
- **NVRs (Synology, Blue Iris, Frigate, etc.)**: "RTSP / generic camera" with the same address.
  If the NVR copies audio through ffmpeg, it needs the `-copyinkf` flag
  (an ffmpeg quirk for AAC over RTP) or audio re-encoding.

### Telegram notifications

1. In Telegram, open @BotFather → `/newbot` → get your token.
2. Send your bot any message.
3. "Settings → Telegram notifications": paste the token, click "Find" next to the "Chat ID" field,
   enable "Send notifications" and click "Send test message".

The phone needs internet access. Motion notification frequency is limited by
"Max once every … min".

## Where recordings are stored

`Android/data/com.surveil.camera/files/recordings/<date>/` on internal storage or SD card
(selected in settings). Files are standard MP4 (H.264, AAC), playable in any media player.
If power is lost mid-recording, the file is recovered on the next app start.

## For developers

Build (JDK 17+, Android SDK with platform 37):

```bash
tools/gw.sh :app:assembleDebug        # APK: ~/.build/SurveillanceCamera/app/outputs/apk/debug/
tools/gw.sh :core:test                # core unit tests (JVM)
tools/gw.sh :core:runDev -PdevArgs="<folder> 8090"   # web UI on PC with fake camera
```

Architecture and conventions — in [AGENTS.md](AGENTS.md), notes and test results — in [NOTES.md](NOTES.md).

### HTTP API (summary)

| Method | Path | Description |
|---|---|---|
| GET | `/api/status` | camera, stream, detection, recording, phone, address status |
| GET/POST | `/api/settings` | settings schema and values / update (`{"key": value}`) |
| GET | `/api/recordings` | recordings list and storage info |
| GET | `/rec/<id>.mp4` | recording (supports Range; `?download=1` — download) |
| POST | `/api/recordings/delete` | `{"ids": [...]}` |
| GET | `/api/snapshot.jpg?w=1280` | current frame |
| POST | `/api/record` | `{"on": true}` — manual record |
| GET | `/api/events` | event log |
| WS | `/ws/live` | live fMP4 stream for MSE |
| WS | `/ws/events` | real-time status and events |
| GET | `/live.mp4` | live fMP4 stream for VLC/ffplay/NVRs |
| POST | `/api/notify/test` | test Telegram message |
| POST | `/api/notify/chats` | chats that messaged the bot (for "Chat ID" field) |
| RTSP | `rtsp://<address>:8554/live` | H.264 + AAC for NVRs and players |