# Surveillance Camera — notes for developers and agents

Android app that turns a phone into a LAN IP camera: H.264 live stream in the browser,
motion detection, recording, web UI with all settings. User-facing docs: README.md (English),
README_RU.md (Russian). Working notes, toolchain quirks and test results: NOTES.md.

## Localization

The phone app language and the web UI language are **independent**.

- Phone app: Android resource strings in `app/src/main/res/values/` (English, default) and
  `values-ru/` (Russian). Language setting `sys.language` (auto/en/ru) in Settings → System;
  `auto` follows the system locale. Applying it calls `setLocale()` + `activity.recreate()`
  (must run on the main thread; do not recreate on startup — that loops).
- Web UI language: dropdown `#lang-btn` in the header (EN/RU). Stored in localStorage
  (`surveil_lang`); on first visit the system language is resolved once and saved as an explicit
  choice. `sys.language` is deliberately hidden from the web settings page (`renderSettings`).
- Web UI translation is client-side in `core/src/main/resources/web/assets/app.js`:
  - `STRINGS` + `t()` for UI text; `applyLang()` also processes `data-i18n` / `data-i18n-title`
    attributes in `index.html` (static HTML is written in English, Russian is applied at runtime).
  - Server strings arrive in Russian from `Schema.kt` / `DeviceInfo.kt`. They are translated by
    **setting key**, never by matching Russian text: `GROUP_TITLES`, `SETTING_TITLES`,
    `SETTING_HELP`, `CHOICE_LABELS`, `UNITS_EN`. `SETTING_HELP` must only contain keys that have
    `help` on the server — never invent help text for settings that have none.
  - When Russian is selected, these lookups return null so the server's original Russian wins.
  - Unit/date formatters are language-aware: `fmtBytes`, `fmtUptime`, `fmtValue`, `fmtDay`.
- Events are localized via structured messages, not string matching: `LogEvent` carries `code` +
  `args` (see `EventLog.add(kind, message, recordingId, code, args)`); the client renders them from
  `EVENT_STRINGS` in `eventText()`. `message` stays the Russian original — it is what Telegram gets,
  and what legacy log entries (no `code`) fall back to. New events: add a code server-side and an
  EN+RU template in `EVENT_STRINGS`.
- Event log can be cleared: `EventLog.clear()` via `POST /api/events/clear` (Clear button).
- Not translated: camera names (provided by the camera HAL) and Telegram message bodies.

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
  - `EventLog` — persistent ring of events (`LogEvent`: timeMs, kind, message, recordingId, code,
    args) shown in the web UI; `code` + `args` make web-side localization possible (see Localization).
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
  Gradle configuration cache is enabled in `gradle.properties` (build ~7s → ~4s).
- Check the web UI JS after editing: `node --check core/src/main/resources/web/assets/app.js`.

## Testing

- `tools/gw.sh :core:test` — JVM unit tests (container checks use ffprobe when on PATH).
- `node tools/browser-check.mjs <url> [--mobile] [--shot x.png]` — headless Chrome smoke test.
- `node tools/firefox-check.mjs <url> [--shot x.png]` — the same in headless Firefox (WebDriver BiDi).
- `tools/soak-test.sh <phone-ip> [minutes]` — long run against a phone (viewer + status sampling).
- On this PC a system HTTP proxy is set: use `curl --noproxy '*'` for LAN addresses.
