# Android Surveillance Camera - Project Memory

## Build & Deploy
- Build debug by default: `tools/gw.sh :app:assembleDebug --no-daemon`
- Release script: `tools/release.sh` (builds release APK, creates GitHub release, renames APK)
- Don't push to GitHub until explicitly told
- **Never create test releases on public repo** — test locally or on private repo first
- APK output: `/home/rocket/.build/SurveillanceCamera/app/outputs/apk/debug/app-debug.apk`
- Install via ADB: `adb -s 192.168.1.110:42347 install -r <apk>`

## Localization Lessons
- Use Android string resources (`values/strings.xml` + `values-ru/strings.xml`)
- Escape apostrophes as `\'` in XML strings
- Camera labels must use string resources (not hardcoded)
- Default camera name should be English ("Camera")
- Language detection on startup: apply locale without recreating activity
- When user changes language: use `updateConfiguration()` + `recreate()` on main thread
- Wide-range settings (ports) should use text input, not slider

## Known Issues
- Camera names from Android API are hardware-provided (not translatable by app)
- SettingsScreen scrolling lag past Telegram notifications block (performance issue)

## Git
- Author: "Qwen"
- Branch: main
- Version: 1.0.15 (versionCode 16)
