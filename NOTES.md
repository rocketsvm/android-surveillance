# Камера наблюдения на Android — рабочие заметки

Репозиторий: https://github.com/Paradoximus/nesterov-videonabludenie-android (private).
Исходный черновик заказчика — `SurveillanceCamera.zip` (в git не входит).

## Почему черновик переписан с нуля

Глобальные огрехи черновика (Ktor + CameraX + WebCodecs):

1. **WebCodecs работает только в secure context** (HTTPS или localhost). По `http://192.168.x.x`
   `VideoDecoder` в браузере просто нет — веб-плеер не мог заработать в принципе
   (отсюда в архиве `keystore.p12` и недоделанный fallback на mp4box).
   Решение: fMP4 по WebSocket → Media Source Extensions (MSE не требует HTTPS).
2. **Тип foreground-сервиса `dataSync`** — с Android 14 доступ к камере из фона требует типа
   `camera`; к тому же `dataSync` лимитирован по времени.
3. **Кодирование**: попиксельный CPU-апскейл 640×360→1280×720, NV21 вместо NV12 (перепутанные
   цвета), `inputBuf.remaining()` после записи — неверный размер буфера.
4. **SPS/PPS** в Annex B отдавались в WebCodecs `description` (ожидает avcC).
5. **Одна общая очередь кадров**, из которой клиенты «вычитывают» — второй зритель крадёт кадры;
   старт не с ключевого кадра.
6. `CameraManager.stop()` не отвязывал камеру.

## Архитектура (кратко)

```
Camera2 ──► SurfaceTexture ──► GL-рендер (поворот, зеркало, кроп, OSD дата/время)
                                 ├─► Surface кодера MediaCodec H.264 (HW) ─► StreamHub ─┬─► WebSocket fMP4 (MSE в браузере)
                                 ├─► превью SurfaceView (только пока приложение на экране)     ├─► /live.mp4 (VLC, ffplay, NVR)
                                 ├─► 160×90 luma ─► MotionDetector ─► Recorder (пред-/пост-запись) ─► MP4 + журнал
                                 └─► снимки JPEG (миниатюры, /api/snapshot.jpg)
```

- `:core` — чистый Kotlin/JVM, тестируется на ПК: свой HTTP/1.1+WebSocket сервер, fMP4-муксер,
  MP4-писатель (moov в конце + журнал `.journal` для восстановления после сбоя), детектор движения,
  рекордер, схема настроек (одна на телефон и веб), веб-интерфейс (`core/src/main/resources/web`).
- `:app` — Android: `CameraService` (FGS type camera, wake lock, Wi-Fi lock), `CapturePipeline`
  (камера→GL→кодер, перезапуски, ретраи), Compose-UI (гайд, превью, адреса+QR, настройки).

## Тулчейн на этой машине

- JDK 21 Temurin: `~/.jdks/jdk-21.0.12.1+1` (portable zip с api.adoptium.net).
- Android SDK: `%LOCALAPPDATA%\Android\Sdk` (cmdline-tools latest = 23.0, `commandlinetools-win-16111833`),
  установлены `platforms;android-36`, `platforms;android-37.0`, `build-tools;36.0.0`.
- **Грабли**: из Git Bash `sdkmanager.bat "platforms;android-36"` не работает — cmd.exe режет
  аргумент по `;`. Ставить через `--package_file=<файл со списком пакетов>`.
- Версии (октябрь 2026): AGP 9.4.1 (встроенный Kotlin — плагин `kotlin-android` не нужен),
  Gradle 9.8.1, Kotlin 2.4.21, Compose BOM 2026.09.00 (**требует compileSdk 37**),
  kotlinx-serialization 1.11.0, coroutines 1.11.0, ZXing 3.5.4.
- Сборка: `tools/gw.sh :app:assembleDebug` — кладёт build-каталоги в `~/.build/SurveillanceCamera`,
  чтобы Яндекс.Диск не синхронизировал сотни мегабайт промежуточных файлов.
  APK: `~/.build/SurveillanceCamera/app/outputs/apk/debug/app-debug.apk`.

## Тестовый стенд на ПК (без телефона)

- `tools/gw.sh :core:runDev -PdevArgs="<dataDir> 8090"` — полный сервер + веб-UI с фейковой камерой
  (зацикленный H.264 из ffmpeg `testsrc2`, `-x264-params aud=1`, `-bf 0`) и симулятором движения.
  Статика берётся прямо из `core/src/main/resources/web` (правки видны без перезапуска).
- `node tools/browser-check.mjs <url> [--mobile] [--shot file.png] [--eval js]` — headless Chrome
  по CDP без пакетов: состояние `<video>` (кадры, буфер, задержка), ошибки консоли, скриншот.
- **На этом ПК системный HTTP-прокси `127.0.0.1:10809`** (VPN-клиент): curl по LAN-адресу
  получает 503 от прокси. Для тестов: `curl --noproxy '*'`, Chrome с `--no-proxy-server`
  (уже в `browser-check.mjs`). ffmpeg/ffprobe на Windows тоже берут прокси из окружения —
  запускать через `env -u HTTP_PROXY ffmpeg ...`.
- **Кириллица в тестовых командах Git Bash ломается дважды**: (1) аргумент `curl -d '{"x":"Камера"}'`
  уходит в cp1251, сервер получает мусор — кириллицу в API слать через Python (`urllib`, UTF-8);
  (2) `python -c` читает пайп в cp1251 — ставить `PYTHONIOENCODING=utf-8` и читать
  `sys.stdin.buffer`. Приложение тут ни при чём: в нём везде UTF-8.
- `python tools/tap.py "<текст>"` — нажать элемент на экране телефона по тексту
  (через `uiautomator dump`; Compose отдаёт текст в дерево доступности). `--list` — что видно.
- **heredoc в Bash съедает обратный слеш** (`\n` в Kotlin-строке превратился в настоящий перенос) —
  тексты со слешами писать через Edit/Write, не через heredoc.

## Телефон для тестов

- Samsung Galaxy A35 5G (SM-A356E), Android 16 (API 36), One UI 8.5, Exynos 1380, arm64.
- Кодер: `c2.exynos.h264.encoder` (HW), High profile, level 3.1 для 720p.
- Камеры, видимые приложению: 0 — задняя основная 70°, 1 — фронтальная 68°,
  **2 — задняя широкоугольная 96°** (хороша для наблюдения), 3 — фронтальная 63°. Разрешения до 4K.
- Wi-Fi «govyadina», IP 192.168.31.39; ПК 192.168.31.247 — одна подсеть.
- Настроено через adb: `settings put global stay_on_while_plugged_in 7`,
  `verifier_verify_adb_installs 0`, `package_verifier_enable 0`. Блокировка экрана выключена.

## Проверено на телефоне (2026-10-16)

- Живой поток H.264 в Chrome на ПК: задержка буфера ~0,5 с, 20 к/с.
- Экран выключен 60 с — 20 к/с без провалов, запись идёт.
- Принудительный Doze (`dumpsys battery unplug` + `dumpsys deviceidle force-idle`) 60 с —
  поток и веб-сервер живы, 20 к/с (FGS camera + partial wake lock достаточно).
- Убийство процесса посреди записи (переустановка APK) → клип восстановлен по журналу при старте.
- `MY_PACKAGE_REPLACED` перезапускает камеру после обновления APK.
- Ориентация «Авто» по акселерометру: перезапуск конвейера при устойчивой смене положения (3 с).
- Записи: MP4 корректный (DTS монотонные, keyframe каждые 2 с), Range-запросы (206), перемотка.
- Длительный прогон 24 мин (зритель + запись по движению): камера всё время running, 16,5–17 к/с
  (ночь, AE удлиняет выдержку в диапазоне [15,20]), PSS 151→170→167 МБ без роста, 28,7 °C ровно.
- Перезагрузка телефона с автозапуском («поверх других приложений» выдано): BOOT_COMPLETED →
  активность → сервис, камера через ~3 с после загрузки, приложение уходит в фон.
- Системная «Камера» забирает камеру → статус error с причиной → после выхода из неё наш поток
  возвращается за секунды (CameraManager.AvailabilityCallback).
- «Закрыть все» в недавних — сервис живёт (тот же PID). «Остановить» в уведомлении — всё
  выключается; при открытии приложения камера сама не стартует (пользователь её остановил).
- Пароль: веб — форма входа; `/api/*`, `/live.mp4` — 401 без пароля, Basic работает; RTSP —
  401 без пароля, `rtsp://admin:пароль@...` работает.
- Звук: включение из веба при свёрнутом приложении сработало (исключение из оптимизации батареи);
  уровень фона −52 дБ (не цифровая тишина). AAC в живом потоке, RTSP и записях.
- RTSP: H.264+AAC по TCP и UDP, ffprobe/ffmpeg без ошибок. Особенность ffmpeg (не наша):
  AAC из любого RTP-источника приходит без флага keyframe, поэтому `ffmpeg -c copy` пишет
  0 аудиопакетов — нужен `-copyinkf` или перекодирование звука; проверено на RTP-потоке самого ffmpeg.
- Release-сборка (R8): веб-API, настройки, плеер работают. Debug подписывается тем же ключом,
  что и release (`keystore/release.jks`, пароли в `keystore.properties` — оба вне git;
  ключ терять нельзя: без него обновить установленное приложение не выйдет).
