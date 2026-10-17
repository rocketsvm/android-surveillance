package com.surveil.camera.ui

import com.surveil.core.settings.Keys

/** Phone UI localization: translates schema titles based on language setting. */
object LocaleHelper {
    private val GROUP_TITLES = mapOf(
        "video" to mapOf("en" to "Video", "ru" to "Видео"),
        "camera" to mapOf("en" to "Camera", "ru" to "Камера"),
        "osd" to mapOf("en" to "On-screen display", "ru" to "Надписи на видео"),
        "motion" to mapOf("en" to "Motion detection", "ru" to "Детектор движения"),
        "record" to mapOf("en" to "Recording", "ru" to "Запись"),
        "audio" to mapOf("en" to "Audio", "ru" to "Звук"),
        "notify" to mapOf("en" to "Telegram notifications", "ru" to "Уведомления в Telegram"),
        "network" to mapOf("en" to "Access", "ru" to "Доступ"),
        "system" to mapOf("en" to "System", "ru" to "Система"),
    )

    private val SETTING_TITLES = mapOf(
        Keys.RESOLUTION to mapOf("en" to "Resolution", "ru" to "Разрешение"),
        Keys.FPS to mapOf("en" to "Frame rate", "ru" to "Частота кадров"),
        Keys.BITRATE to mapOf("en" to "Bitrate", "ru" to "Битрейт"),
        Keys.ORIENTATION to mapOf("en" to "Orientation", "ru" to "Ориентация"),
        Keys.MIRROR to mapOf("en" to "Mirror", "ru" to "Зеркально отразить"),
        Keys.LOW_LIGHT_FPS to mapOf("en" to "Reduce frame rate in low light", "ru" to "Снижать частоту кадров в темноте"),
        Keys.BITRATE_MODE to mapOf("en" to "Bitrate mode", "ru" to "Режим битрейта"),
        Keys.KEYFRAME_SEC to mapOf("en" to "Keyframe interval", "ru" to "Интервал ключевых кадров"),
        Keys.PROFILE to mapOf("en" to "H.264 profile", "ru" to "Профиль H.264"),
        Keys.CAMERA_ID to mapOf("en" to "Camera", "ru" to "Камера"),
        Keys.FOCUS to mapOf("en" to "Focus", "ru" to "Фокус"),
        Keys.EXPOSURE to mapOf("en" to "Exposure compensation", "ru" to "Экспокоррекция"),
        Keys.ZOOM to mapOf("en" to "Zoom", "ru" to "Зум"),
        Keys.TORCH to mapOf("en" to "Flashlight", "ru" to "Фонарик"),
        Keys.ANTIBANDING to mapOf("en" to "Flicker reduction", "ru" to "Подавление мерцания ламп"),
        Keys.AWB to mapOf("en" to "White balance", "ru" to "Баланс белого"),
        Keys.STABILIZATION to mapOf("en" to "Stabilization", "ru" to "Стабилизация"),
        Keys.OSD_TIME to mapOf("en" to "Date and time", "ru" to "Дата и время"),
        Keys.OSD_NAME to mapOf("en" to "Camera name", "ru" to "Название камеры"),
        Keys.OSD_POSITION to mapOf("en" to "Position", "ru" to "Положение"),
        Keys.OSD_SIZE to mapOf("en" to "Text size", "ru" to "Размер текста"),
        Keys.MOTION_ENABLED to mapOf("en" to "Motion detection", "ru" to "Детектор движения"),
        Keys.MOTION_SENSITIVITY to mapOf("en" to "Sensitivity", "ru" to "Чувствительность"),
        Keys.MOTION_MIN_AREA to mapOf("en" to "Minimum area", "ru" to "Минимальная площадь"),
        Keys.MOTION_ZONES to mapOf("en" to "Detection zones", "ru" to "Зоны детекции"),
        Keys.MOTION_IGNORE_LIGHT to mapOf("en" to "Ignore lighting changes", "ru" to "Игнорировать смену освещения"),
        Keys.MOTION_FPS to mapOf("en" to "Analysis rate", "ru" to "Частота анализа"),
        Keys.REC_MODE to mapOf("en" to "Recording mode", "ru" to "Режим записи"),
        Keys.REC_PRE_SEC to mapOf("en" to "Pre-event recording", "ru" to "Запись до события"),
        Keys.REC_POST_SEC to mapOf("en" to "Post-event recording", "ru" to "Запись после события"),
        Keys.REC_MAX_CLIP_MIN to mapOf("en" to "Max clip length", "ru" to "Макс. длина файла"),
        Keys.REC_STORAGE to mapOf("en" to "Storage", "ru" to "Хранилище"),
        Keys.REC_MIN_FREE_GB to mapOf("en" to "Keep free", "ru" to "Оставлять свободным"),
        Keys.REC_MAX_USAGE_GB to mapOf("en" to "Max storage used", "ru" to "Макс. объём записей"),
        Keys.REC_MAX_AGE_DAYS to mapOf("en" to "Keep recordings for", "ru" to "Хранить записи"),
        Keys.AUDIO_ENABLED to mapOf("en" to "Microphone audio", "ru" to "Звук с микрофона"),
        Keys.AUDIO_GAIN to mapOf("en" to "Gain", "ru" to "Усиление"),
        Keys.TG_ENABLED to mapOf("en" to "Send notifications", "ru" to "Присылать уведомления"),
        Keys.TG_TOKEN to mapOf("en" to "Bot token", "ru" to "Токен бота"),
        Keys.TG_CHAT to mapOf("en" to "Chat ID", "ru" to "ID чата"),
        Keys.TG_PHOTO to mapOf("en" to "Photo on motion", "ru" to "Снимок при движении"),
        Keys.TG_VIDEO to mapOf("en" to "Video after recording", "ru" to "Видео записи после окончания"),
        Keys.TG_POWER to mapOf("en" to "Power and camera errors", "ru" to "Отключение зарядки и ошибки камеры"),
        Keys.TG_COOLDOWN_MIN to mapOf("en" to "Max once every", "ru" to "Не чаще одного раза в"),
        Keys.PORT to mapOf("en" to "Web server port", "ru" to "Порт веб-сервера"),
        Keys.RTSP_ENABLED to mapOf("en" to "RTSP server", "ru" to "RTSP-сервер"),
        Keys.RTSP_PORT to mapOf("en" to "RTSP port", "ru" to "Порт RTSP"),
        Keys.USERNAME to mapOf("en" to "Username", "ru" to "Имя пользователя"),
        Keys.PASSWORD to mapOf("en" to "Password", "ru" to "Пароль"),
        Keys.NAME to mapOf("en" to "Camera name", "ru" to "Название камеры"),
        Keys.AUTOSTART to mapOf("en" to "Auto-start after reboot", "ru" to "Автозапуск после перезагрузки"),
        Keys.LANGUAGE to mapOf("en" to "UI language", "ru" to "Язык интерфейса"),
    )

    private val SETTING_HELP = mapOf(
        Keys.BITRATE to mapOf("en" to "Higher = better quality but more traffic and storage", "ru" to "Больше — качественнее картинка, но больше трафик и объём записей"),
        Keys.ORIENTATION to mapOf("en" to "How the phone is mounted", "ru" to "Как закреплён телефон"),
        Keys.LOW_LIGHT_FPS to mapOf("en" to "Camera can use longer exposure: brighter in low light but fewer frames", "ru" to "Камера сможет удлинять выдержку: в темноте картинка ярче, но кадров меньше"),
        Keys.KEYFRAME_SEC to mapOf("en" to "Lower = faster playback start, higher = more efficient stream", "ru" to "Меньше — быстрее старт просмотра, больше — экономнее поток"),
        Keys.TORCH to mapOf("en" to "Phone flash illumination", "ru" to "Подсветка вспышкой телефона"),
        Keys.STABILIZATION to mapOf("en" to "Crops the frame slightly", "ru" to "Немного обрезает кадр"),
        Keys.MOTION_SENSITIVITY to mapOf("en" to "Higher = reacts to weaker image changes", "ru" to "Выше — реагирует на более слабые изменения картинки"),
        Keys.MOTION_MIN_AREA to mapOf("en" to "What fraction of the frame must change to count as motion", "ru" to "Какая доля кадра должна измениться, чтобы считать это движением"),
        Keys.MOTION_ZONES to mapOf("en" to "Frame areas where motion is ignored", "ru" to "Области кадра, в которых движение игнорируется"),
        Keys.MOTION_IGNORE_LIGHT to mapOf("en" to "Lights turning on or clouds won't trigger motion", "ru" to "Включение света или облако не будут считаться движением"),
        Keys.REC_STORAGE to mapOf("en" to "Older recordings are deleted when space runs low", "ru" to "При нехватке места удаляются самые старые записи"),
        Keys.REC_MAX_USAGE_GB to mapOf("en" to "0 = unlimited", "ru" to "0 — без ограничения"),
        Keys.REC_MAX_AGE_DAYS to mapOf("en" to "0 = unlimited", "ru" to "0 — без ограничения"),
        Keys.AUDIO_ENABLED to mapOf("en" to "Adds audio to stream and recordings. Requires microphone permission", "ru" to "Добавляет звук в трансляцию и записи. Нужно разрешение на микрофон"),
        Keys.AUDIO_GAIN to mapOf("en" to "For quiet rooms", "ru" to "Для тихих помещений"),
        Keys.TG_ENABLED to mapOf("en" to "Requires internet on the phone and your own bot: create one at @BotFather and paste the token", "ru" to "Нужен интернет на телефоне и свой бот: создайте его у @BotFather и вставьте токен"),
        Keys.TG_CHAT to mapOf("en" to "Send your bot any message and click Find", "ru" to "Напишите боту любое сообщение и нажмите «Найти»"),
        Keys.TG_VIDEO to mapOf("en" to "Clips up to 45 MB", "ru" to "Ролики до 45 МБ"),
        Keys.TG_COOLDOWN_MIN to mapOf("en" to "For motion; 0 = unlimited", "ru" to "О движении; 0 — без ограничения"),
        Keys.RTSP_ENABLED to mapOf("en" to "Stream rtsp://<address>:8554/live for NVRs, VLC, Home Assistant", "ru" to "Поток rtsp://<адрес>:8554/live для видеорегистраторов, VLC, Home Assistant"),
        Keys.PASSWORD to mapOf("en" to "Empty = no password required", "ru" to "Пусто — вход без пароля"),
        Keys.AUTOSTART to mapOf("en" to "Requires Display over other apps permission", "ru" to "Требует разрешения «Поверх других приложений»"),
        Keys.LANGUAGE to mapOf("en" to "Language of the app and web interface", "ru" to "Язык приложения и веб-интерфейса"),
    )

    fun groupTitle(id: String, lang: String): String {
        return GROUP_TITLES[id]?.get(lang) ?: GROUP_TITLES[id]?.get("ru") ?: id
    }

    fun settingTitle(key: String, lang: String): String {
        return SETTING_TITLES[key]?.get(lang) ?: SETTING_TITLES[key]?.get("ru") ?: key
    }

    fun settingHelp(key: String, lang: String): String? {
        return SETTING_HELP[key]?.get(lang) ?: SETTING_HELP[key]?.get("ru")
    }

    fun optionLabel(value: String, original: String, lang: String): String {
        // Translate specific option labels
        return when (lang) {
            "en" -> when (value) {
                "auto" -> "Auto"
                "landscape" -> "Landscape"
                "landscape_flip" -> "Landscape (flipped)"
                "portrait" -> "Portrait"
                "portrait_flip" -> "Portrait (flipped)"
                "continuous" -> "Continuous autofocus"
                "infinity" -> "Infinity"
                "daylight" -> "Daylight"
                "cloudy" -> "Cloudy"
                "incandescent" -> "Incandescent"
                "fluorescent" -> "Fluorescent"
                "50hz" -> "50 Hz"
                "60hz" -> "60 Hz"
                "off" -> "Off"
                "high" -> "High"
                "main" -> "Main"
                "baseline" -> "Baseline"
                "vbr" -> "Variable (VBR)"
                "cbr" -> "Constant (CBR)"
                "internal" -> "Phone storage"
                "tl" -> "Top left"
                "tr" -> "Top right"
                "bl" -> "Bottom left"
                "br" -> "Bottom right"
                "motion" -> "On motion"
                "continuous" -> "Continuous"
                else -> original
            }
            else -> original
        }
    }
}