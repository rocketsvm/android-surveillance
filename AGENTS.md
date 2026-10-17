# Surveillance Camera — notes for developers and agents

Android app that turns a phone into a LAN IP camera: H.264 live stream in the browser,
motion detection, recording, web UI with all settings. User-facing docs: README.md (English),
README_RU.md (Russian). Working notes, toolchain quirks and test results: NOTES.md.

## Localization

- Phone app: Android resource strings in `app/src/main/res/values/` (English) and `values-ru/` (Russian).
- Web UI: client-side translation in `core/src/main/resources/web/assets/app.js` (`STRINGS` objects,
  `t()` function). Language preference stored in localStorage, defaults to system language.
- Settings group titles: translated client-side via `GROUP_TITLES` map (server returns Russian titles).
- Language setting: `sys.language` (auto/en/ru) in Settings → System.

## Modules

- `:core` — pure Kotlin/JVM, no Android dependencies. Everything that can be tested on a PC lives here.
  - `http/` — own HTTP/1.1 server (thread per connection, keep-alive, Range, close-delimited
    streams) and RFC 6455 WebSocket. Deliberately not Ktor/Netty: tiny, no JVM-only classes,
    full control over threads and back-pressure.
  - `h264/` — Annex B ↔ AVCC, SPS parser (codec string, dimensions), avcC builder.
  - `mp4/` — `Fmp4` (init segment + one-sample fragments for live/MSE), `Mp4FileWriter`
    (progressive MP4 with moov at the end + `.journal` sidecar for crash recovery).
  - `stream/` — `StreamHub` (fan-out, GOP cache), `FrameQueue` (per-consumer bounded queue that
    drops to the next keyframe), `LiveStreamer` (WS + HTTP fMP4; holds each frame until the next
    one to get exact durations).
  - `motion/` — background-subtraction detector on a ~160×90 luma image, zone mask.
  - `record/` — `Recorder` (pre-roll from a keyframe, post-roll on the stream clock, splitting,
    cleanup) and `RecordingsRepo` (files + JSON sidecars, multiple storage roots).
  - `settings/` — `Schema` (single source of truth for phone UI, web UI and validation),
    `SettingsStore` (JSON file, StateFlow, PBKDF2 password).
  - `web/` — routes (`WebApi`), auth (cookie session or HTTP Basic), event push (`EventBus`).
  - `rtsp/` — RTSP 1.0 server: SDP, RTP H.264 (single NAL / FU-A, SPS+PPS before IDR),
    AAC (RFC 3640), TCP interleaved and UDP, RTCP sender reports; Basic auth.
  - `notify/` — `TelegramNotifier`: motion photo with cooldown, clip video, power/camera alerts,
    chat discovery via getUpdates; all network work on one background thread.
  - `CoreEngine` — wires it all; `Platform` is the interface the host implements.
  - `resources/web/` — the web UI (vanilla JS, no build step, no CDN: works offline).
- `:app` — Android.
  - `capture/CapturePipeline` — camera → GL → encoder lifecycle, live updates vs restarts
    (`Apply` level in the schema), retries with back-off, auto orientation.
  - `gl/GlRenderer` — one camera frame → encoder surface (fps-limited, with OSD), preview,
    motion luma readback, snapshots. Rotation/mirror/crop happen here.
  - `capture/VideoEncoder` (MediaCodec, surface input), `AudioCapture` (AAC), `CameraSource` (Camera2).
  - `service/CameraService` — foreground service type camera (+microphone when audio is on),
    wake lock, Wi-Fi lock. `EngineHolder` exposes the running engine to the UI.
  - `ui/` — Compose: guide, home (preview, addresses + QR, controls), schema-driven settings,
    zone editor.

## Data flow

```
Camera2 → SurfaceTexture → GlRenderer ─┬→ MediaCodec H.264 → StreamHub ─┬→ LiveStreamer (WS / live.mp4), RtspServer
                                       ├→ preview SurfaceView          ├→ Recorder → MP4 + journal
                                       ├→ luma 160×90 → MotionDetector ┘   (motion events → Recorder)
                                       └→ snapshots (JPEG)
AudioRecord → AAC ─────────────────────────→ StreamHub (same CLOCK_MONOTONIC as video)
```

## Rules that matter

- Android 14+: a camera foreground service must be *created* while the app is visible. Boot and
  package-replaced auto-start go through the activity (needs "display over other apps") or a
  one-tap notification. Never try to start the camera FGS from a receiver directly.
- The browser player must stay on MSE: WebCodecs needs a secure context, LAN http is not one.
- Keep `:core` free of Android imports; test it with `tools/gw.sh :core:test` and the desktop
  harness `:core:runDev` (fake H.264 + AAC source, synthetic motion).
- Settings: add new ones only in `settings/Schema.kt`; both UIs render them automatically.
  Pick the `Apply` level honestly (LIVE / PIPELINE / SERVER).
- Build outputs go outside the synced folder: always build via `tools/gw.sh`.

## Testing

- `tools/gw.sh :core:test` — JVM unit tests (container checks use ffprobe when on PATH).
- `node tools/browser-check.mjs <url> [--mobile] [--shot x.png]` — headless Chrome smoke test.
- `node tools/firefox-check.mjs <url> [--shot x.png]` — the same in headless Firefox (WebDriver BiDi).
- `tools/soak-test.sh <phone-ip> [minutes]` — long run against a phone (viewer + status sampling).
- On this PC a system HTTP proxy is set: use `curl --noproxy '*'` for LAN addresses.
