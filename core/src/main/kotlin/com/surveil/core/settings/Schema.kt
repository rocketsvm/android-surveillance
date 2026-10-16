package com.surveil.core.settings

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/** What a change of the setting requires from the running engine. */
enum class Apply { LIVE, PIPELINE, SERVER }

data class Option(val value: String, val label: String)

/**
 * One user-facing setting. The schema drives both the phone UI and the web UI, and validates
 * every update regardless of where it came from.
 */
sealed class Setting(
    val key: String,
    val title: String,
    val help: String?,
    val apply: Apply,
    val advanced: Boolean,
) {
    abstract val defaultValue: JsonElement
    /** Normalises an incoming value; null if it is invalid. */
    abstract fun coerce(v: JsonElement): JsonElement?
    protected abstract fun describe(b: MutableMap<String, JsonElement>, options: List<Option>?)

    fun schemaJson(options: List<Option>?): JsonObject {
        val m = LinkedHashMap<String, JsonElement>()
        m["key"] = JsonPrimitive(key)
        m["title"] = JsonPrimitive(title)
        help?.let { m["help"] = JsonPrimitive(it) }
        m["apply"] = JsonPrimitive(apply.name.lowercase())
        m["advanced"] = JsonPrimitive(advanced)
        describe(m, options)
        return JsonObject(m)
    }
}

class IntSetting(
    key: String, title: String, val default: Int, val min: Int, val max: Int, val step: Int = 1,
    val unit: String? = null, help: String? = null, apply: Apply = Apply.LIVE, advanced: Boolean = false,
) : Setting(key, title, help, apply, advanced) {
    override val defaultValue = JsonPrimitive(default)
    override fun coerce(v: JsonElement): JsonElement? {
        val p = v as? JsonPrimitive ?: return null
        val n = p.intOrNull ?: p.doubleOrNull?.roundToInt() ?: p.content.trim().toIntOrNull() ?: return null
        return JsonPrimitive(n.coerceIn(min, max))
    }
    override fun describe(b: MutableMap<String, JsonElement>, options: List<Option>?) {
        b["type"] = JsonPrimitive("int"); b["min"] = JsonPrimitive(min); b["max"] = JsonPrimitive(max)
        b["step"] = JsonPrimitive(step); unit?.let { b["unit"] = JsonPrimitive(it) }
        b["default"] = defaultValue
    }
}

class FloatSetting(
    key: String, title: String, val default: Double, val min: Double, val max: Double, val step: Double,
    val unit: String? = null, help: String? = null, apply: Apply = Apply.LIVE, advanced: Boolean = false,
) : Setting(key, title, help, apply, advanced) {
    override val defaultValue = JsonPrimitive(default)
    override fun coerce(v: JsonElement): JsonElement? {
        val p = v as? JsonPrimitive ?: return null
        val d = p.doubleOrNull ?: p.content.trim().replace(',', '.').toDoubleOrNull() ?: return null
        if (d.isNaN()) return null
        val stepped = Math.round(d.coerceIn(min, max) / step) * step
        return JsonPrimitive(Math.round(stepped * 1000) / 1000.0)
    }
    override fun describe(b: MutableMap<String, JsonElement>, options: List<Option>?) {
        b["type"] = JsonPrimitive("float"); b["min"] = JsonPrimitive(min); b["max"] = JsonPrimitive(max)
        b["step"] = JsonPrimitive(step); unit?.let { b["unit"] = JsonPrimitive(it) }
        b["default"] = defaultValue
    }
}

class BoolSetting(
    key: String, title: String, val default: Boolean,
    help: String? = null, apply: Apply = Apply.LIVE, advanced: Boolean = false,
) : Setting(key, title, help, apply, advanced) {
    override val defaultValue = JsonPrimitive(default)
    override fun coerce(v: JsonElement): JsonElement? {
        val p = v as? JsonPrimitive ?: return null
        return JsonPrimitive(p.booleanOrNull ?: when (p.content.lowercase()) {
            "1", "on", "yes" -> true
            "0", "off", "no" -> false
            else -> return null
        })
    }
    override fun describe(b: MutableMap<String, JsonElement>, options: List<Option>?) {
        b["type"] = JsonPrimitive("bool"); b["default"] = defaultValue
    }
}

/**
 * A choice among options. [dynamic] options come from the device at runtime (cameras, resolutions,
 * storage volumes); for those any well-formed value is accepted and the engine falls back sensibly.
 */
class ChoiceSetting(
    key: String, title: String, val default: String, val options: List<Option>, val dynamic: Boolean = false,
    help: String? = null, apply: Apply = Apply.LIVE, advanced: Boolean = false,
) : Setting(key, title, help, apply, advanced) {
    override val defaultValue = JsonPrimitive(default)
    override fun coerce(v: JsonElement): JsonElement? {
        val s = (v as? JsonPrimitive)?.content ?: return null
        if (dynamic) return if (s.length <= 64 && s.none { it < ' ' }) JsonPrimitive(s) else null
        return if (options.any { it.value == s }) JsonPrimitive(s) else null
    }
    override fun describe(b: MutableMap<String, JsonElement>, options: List<Option>?) {
        b["type"] = JsonPrimitive("choice")
        b["options"] = buildJsonArray {
            for (o in options ?: this@ChoiceSetting.options) add(buildJsonObject { put("value", o.value); put("label", o.label) })
        }
        b["default"] = defaultValue
    }
}

class TextSetting(
    key: String, title: String, val default: String, val maxLength: Int = 64,
    /** Secrets are stored hashed and never sent back to clients. */
    val secret: Boolean = false,
    /** A UI hint for special editors (e.g. "zones"). */
    val editor: String? = null,
    help: String? = null, apply: Apply = Apply.LIVE, advanced: Boolean = false,
) : Setting(key, title, help, apply, advanced) {
    override val defaultValue = JsonPrimitive(default)
    override fun coerce(v: JsonElement): JsonElement? {
        val s = (v as? JsonPrimitive)?.content ?: return null
        if (s.length > maxLength || s.any { it < ' ' }) return null
        return JsonPrimitive(s.trim())
    }
    override fun describe(b: MutableMap<String, JsonElement>, options: List<Option>?) {
        b["type"] = JsonPrimitive(if (secret) "secret" else "text")
        b["maxLength"] = JsonPrimitive(maxLength)
        editor?.let { b["editor"] = JsonPrimitive(it) }
        if (!secret) b["default"] = defaultValue
    }
}

class SettingGroup(val id: String, val title: String, val settings: List<Setting>)

/** Keys of all settings. */
object Keys {
    const val NAME = "sys.name"
    const val AUTOSTART = "sys.autostart"

    const val CAMERA_ID = "camera.id"
    const val RESOLUTION = "video.resolution"
    const val FPS = "video.fps"
    const val BITRATE = "video.bitrate"
    const val BITRATE_MODE = "video.bitrateMode"
    const val KEYFRAME_SEC = "video.keyframeSec"
    const val PROFILE = "video.profile"
    const val ORIENTATION = "video.orientation"
    const val MIRROR = "video.mirror"
    const val LOW_LIGHT_FPS = "video.lowLightFps"

    const val FOCUS = "camera.focus"
    const val EXPOSURE = "camera.exposure"
    const val ZOOM = "camera.zoom"
    const val TORCH = "camera.torch"
    const val ANTIBANDING = "camera.antibanding"
    const val AWB = "camera.awb"
    const val STABILIZATION = "camera.stabilization"

    const val OSD_TIME = "osd.time"
    const val OSD_NAME = "osd.name"
    const val OSD_POSITION = "osd.position"
    const val OSD_SIZE = "osd.size"

    const val MOTION_ENABLED = "motion.enabled"
    const val MOTION_SENSITIVITY = "motion.sensitivity"
    const val MOTION_MIN_AREA = "motion.minArea"
    const val MOTION_ZONES = "motion.zones"
    const val MOTION_FPS = "motion.fps"
    const val MOTION_IGNORE_LIGHT = "motion.ignoreLight"

    const val REC_MODE = "record.mode"
    const val REC_PRE_SEC = "record.preSec"
    const val REC_POST_SEC = "record.postSec"
    const val REC_MAX_CLIP_MIN = "record.maxClipMin"
    const val REC_STORAGE = "record.storage"
    const val REC_MIN_FREE_GB = "record.minFreeGb"
    const val REC_MAX_USAGE_GB = "record.maxUsageGb"
    const val REC_MAX_AGE_DAYS = "record.maxAgeDays"

    const val AUDIO_ENABLED = "audio.enabled"
    const val AUDIO_GAIN = "audio.gain"

    const val PORT = "net.port"
    const val USERNAME = "net.username"
    const val PASSWORD = "net.password"
}

object Schema {
    val groups: List<SettingGroup> = listOf(
        SettingGroup("video", "Видео", listOf(
            ChoiceSetting(Keys.RESOLUTION, "Разрешение", "1280x720",
                listOf(Option("640x480", "640×480"), Option("1280x720", "1280×720 (HD)"), Option("1920x1080", "1920×1080 (Full HD)")),
                dynamic = true, help = "Размер кадра потока и записей", apply = Apply.PIPELINE),
            IntSetting(Keys.FPS, "Частота кадров", 20, 1, 60, unit = "к/с", apply = Apply.PIPELINE),
            IntSetting(Keys.BITRATE, "Битрейт", 2500, 200, 20000, step = 100, unit = "кбит/с",
                help = "Больше — качественнее картинка, но больше трафик и объём записей"),
            ChoiceSetting(Keys.ORIENTATION, "Ориентация", "auto", listOf(
                Option("auto", "Авто (по датчику)"),
                Option("landscape", "Альбомная"),
                Option("landscape_flip", "Альбомная, перевёрнутая"),
                Option("portrait", "Портретная"),
                Option("portrait_flip", "Портретная, перевёрнутая"),
            ), help = "Как закреплён телефон", apply = Apply.PIPELINE),
            BoolSetting(Keys.MIRROR, "Зеркально отразить", false),
            BoolSetting(Keys.LOW_LIGHT_FPS, "Снижать частоту кадров в темноте", true,
                help = "Камера сможет удлинять выдержку: в темноте картинка ярче, но кадров меньше"),
            ChoiceSetting(Keys.BITRATE_MODE, "Режим битрейта", "vbr",
                listOf(Option("vbr", "Переменный (VBR)"), Option("cbr", "Постоянный (CBR)")),
                apply = Apply.PIPELINE, advanced = true),
            IntSetting(Keys.KEYFRAME_SEC, "Интервал ключевых кадров", 2, 1, 10, unit = "с",
                help = "Меньше — быстрее старт просмотра, больше — экономнее поток", apply = Apply.PIPELINE, advanced = true),
            ChoiceSetting(Keys.PROFILE, "Профиль H.264", "auto", listOf(
                Option("auto", "Авто"), Option("high", "High"), Option("main", "Main"), Option("baseline", "Baseline"),
            ), apply = Apply.PIPELINE, advanced = true),
        )),
        SettingGroup("camera", "Камера", listOf(
            ChoiceSetting(Keys.CAMERA_ID, "Камера", "", emptyList(), dynamic = true, apply = Apply.PIPELINE),
            ChoiceSetting(Keys.FOCUS, "Фокус", "continuous", listOf(
                Option("continuous", "Непрерывный автофокус"),
                Option("infinity", "Бесконечность"),
            )),
            FloatSetting(Keys.EXPOSURE, "Экспокоррекция", 0.0, -3.0, 3.0, 0.5, unit = "EV"),
            FloatSetting(Keys.ZOOM, "Зум", 1.0, 1.0, 10.0, 0.1, unit = "×"),
            BoolSetting(Keys.TORCH, "Фонарик", false, help = "Подсветка вспышкой телефона"),
            ChoiceSetting(Keys.ANTIBANDING, "Подавление мерцания ламп", "auto", listOf(
                Option("auto", "Авто"), Option("50hz", "50 Гц"), Option("60hz", "60 Гц"), Option("off", "Выкл"),
            ), advanced = true),
            ChoiceSetting(Keys.AWB, "Баланс белого", "auto", listOf(
                Option("auto", "Авто"), Option("daylight", "Дневной свет"), Option("cloudy", "Облачно"),
                Option("incandescent", "Лампы накаливания"), Option("fluorescent", "Люминесцентные лампы"),
            ), advanced = true),
            BoolSetting(Keys.STABILIZATION, "Стабилизация", false, help = "Немного обрезает кадр", advanced = true),
        )),
        SettingGroup("osd", "Надписи на видео", listOf(
            BoolSetting(Keys.OSD_TIME, "Дата и время", true),
            BoolSetting(Keys.OSD_NAME, "Название камеры", true),
            ChoiceSetting(Keys.OSD_POSITION, "Положение", "tl", listOf(
                Option("tl", "Сверху слева"), Option("tr", "Сверху справа"),
                Option("bl", "Снизу слева"), Option("br", "Снизу справа"),
            )),
            IntSetting(Keys.OSD_SIZE, "Размер текста", 100, 50, 250, step = 10, unit = "%", advanced = true),
        )),
        SettingGroup("motion", "Детектор движения", listOf(
            BoolSetting(Keys.MOTION_ENABLED, "Детектор движения", true),
            IntSetting(Keys.MOTION_SENSITIVITY, "Чувствительность", 60, 1, 100,
                help = "Выше — реагирует на более слабые изменения картинки"),
            FloatSetting(Keys.MOTION_MIN_AREA, "Минимальная площадь", 0.8, 0.1, 30.0, 0.1, unit = "%",
                help = "Какая доля кадра должна измениться, чтобы считать это движением"),
            TextSetting(Keys.MOTION_ZONES, "Зоны детекции", "", maxLength = 400, editor = "zones",
                help = "Области кадра, в которых движение игнорируется"),
            BoolSetting(Keys.MOTION_IGNORE_LIGHT, "Игнорировать смену освещения", true,
                help = "Включение света или облако не будут считаться движением", advanced = true),
            IntSetting(Keys.MOTION_FPS, "Частота анализа", 5, 1, 15, unit = "к/с", advanced = true),
        )),
        SettingGroup("record", "Запись", listOf(
            ChoiceSetting(Keys.REC_MODE, "Режим записи", "motion", listOf(
                Option("off", "Не записывать"), Option("motion", "По движению"), Option("continuous", "Непрерывно"),
            )),
            IntSetting(Keys.REC_PRE_SEC, "Запись до события", 5, 0, 30, unit = "с"),
            IntSetting(Keys.REC_POST_SEC, "Запись после события", 15, 1, 600, unit = "с"),
            IntSetting(Keys.REC_MAX_CLIP_MIN, "Макс. длина файла", 10, 1, 120, unit = "мин", advanced = true),
            ChoiceSetting(Keys.REC_STORAGE, "Хранилище", "internal", listOf(Option("internal", "Память телефона")), dynamic = true),
            FloatSetting(Keys.REC_MIN_FREE_GB, "Оставлять свободным", 2.0, 0.5, 100.0, 0.5, unit = "ГБ",
                help = "При нехватке места удаляются самые старые записи"),
            FloatSetting(Keys.REC_MAX_USAGE_GB, "Макс. объём записей", 0.0, 0.0, 2000.0, 1.0, unit = "ГБ", help = "0 — без ограничения"),
            IntSetting(Keys.REC_MAX_AGE_DAYS, "Хранить записи", 0, 0, 365, unit = "дн.", help = "0 — без ограничения"),
        )),
        SettingGroup("audio", "Звук", listOf(
            BoolSetting(Keys.AUDIO_ENABLED, "Звук с микрофона", false,
                help = "Добавляет звук в трансляцию и записи. Нужно разрешение на микрофон", apply = Apply.PIPELINE),
            IntSetting(Keys.AUDIO_GAIN, "Усиление", 0, 0, 24, step = 3, unit = "дБ",
                help = "Для тихих помещений", apply = Apply.PIPELINE),
        )),
        SettingGroup("network", "Доступ", listOf(
            IntSetting(Keys.PORT, "Порт веб-сервера", 8080, 1024, 65535, apply = Apply.SERVER),
            TextSetting(Keys.USERNAME, "Имя пользователя", "admin", maxLength = 32),
            TextSetting(Keys.PASSWORD, "Пароль", "", maxLength = 64, secret = true, help = "Пусто — вход без пароля"),
        )),
        SettingGroup("system", "Система", listOf(
            TextSetting(Keys.NAME, "Название камеры", "Камера", maxLength = 40),
            BoolSetting(Keys.AUTOSTART, "Автозапуск после перезагрузки", false,
                help = "Требует разрешения «Поверх других приложений»"),
        )),
    )

    val byKey: Map<String, Setting> = groups.flatMap { it.settings }.associateBy { it.key }

    fun json(values: Map<String, JsonElement>, dynamicOptions: (String) -> List<Option>?): JsonArray = buildJsonArray {
        for (g in groups) add(buildJsonObject {
            put("id", g.id)
            put("title", g.title)
            put("settings", buildJsonArray {
                for (s in g.settings) {
                    val opts = if (s is ChoiceSetting && s.dynamic) dynamicOptions(s.key) else null
                    val base = s.schemaJson(opts).toMutableMap()
                    if (s is TextSetting && s.secret) base["isSet"] = JsonPrimitive((values[s.key] as? JsonPrimitive)?.content?.isNotEmpty() == true)
                    else base["value"] = values[s.key] ?: s.defaultValue
                    add(JsonObject(base))
                }
            })
        })
    }
}
