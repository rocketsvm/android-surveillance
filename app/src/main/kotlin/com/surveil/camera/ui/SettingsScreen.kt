package com.surveil.camera.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.surveil.camera.R
import com.surveil.camera.platform.AndroidPlatform
import com.surveil.core.settings.BoolSetting
import com.surveil.core.settings.ChoiceSetting
import com.surveil.core.settings.FloatSetting
import com.surveil.core.settings.IntSetting
import com.surveil.core.settings.Keys
import com.surveil.core.settings.Option
import com.surveil.core.settings.Schema
import com.surveil.core.settings.Setting
import com.surveil.core.settings.Settings
import com.surveil.core.settings.TextSetting
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt
import androidx.compose.runtime.rememberCoroutineScope
import com.surveil.camera.App

/** Phone-side settings, rendered from the same schema as the web UI. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: Settings,
    options: (String) -> List<Option>?,
    onBack: () -> Unit,
    onZones: () -> Unit,
) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.settings_back)) } },
                actions = {
                    Text(stringResource(R.string.settings_advanced), style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = advanced, onCheckedChange = { advanced = it })
                    Spacer(Modifier.width(12.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(Schema.groups, key = { it.id }) { g ->
                SectionCard {
                    Column {
                        Text(groupTitle(g.id), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        val visible = g.settings.filter { advanced || !it.advanced }
                        for (i in visible.indices) {
                            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            SettingRow(visible[i], settings, options, onZones)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun groupTitle(id: String): String {
    return when (id) {
        "video" -> stringResource(R.string.group_video)
        "camera" -> stringResource(R.string.group_camera)
        "osd" -> stringResource(R.string.group_osd)
        "motion" -> stringResource(R.string.group_motion)
        "record" -> stringResource(R.string.group_record)
        "audio" -> stringResource(R.string.group_audio)
        "notify" -> stringResource(R.string.group_notify)
        "network" -> stringResource(R.string.group_network)
        "system" -> stringResource(R.string.group_system)
        else -> id
    }
}

@Composable
private fun settingTitle(key: String): String {
    return when (key) {
        Keys.RESOLUTION -> stringResource(R.string.setting_resolution)
        Keys.FPS -> stringResource(R.string.setting_fps)
        Keys.BITRATE -> stringResource(R.string.setting_bitrate)
        Keys.ORIENTATION -> stringResource(R.string.setting_orientation)
        Keys.MIRROR -> stringResource(R.string.setting_mirror)
        Keys.LOW_LIGHT_FPS -> stringResource(R.string.setting_low_light_fps)
        Keys.BITRATE_MODE -> stringResource(R.string.setting_bitrate_mode)
        Keys.KEYFRAME_SEC -> stringResource(R.string.setting_keyframe_sec)
        Keys.PROFILE -> stringResource(R.string.setting_profile)
        Keys.CAMERA_ID -> stringResource(R.string.setting_camera_id)
        Keys.FOCUS -> stringResource(R.string.setting_focus)
        Keys.EXPOSURE -> stringResource(R.string.setting_exposure)
        Keys.ZOOM -> stringResource(R.string.setting_zoom)
        Keys.TORCH -> stringResource(R.string.setting_torch)
        Keys.ANTIBANDING -> stringResource(R.string.setting_antibanding)
        Keys.AWB -> stringResource(R.string.setting_awb)
        Keys.STABILIZATION -> stringResource(R.string.setting_stabilization)
        Keys.NOISE_REDUCTION -> stringResource(R.string.setting_noise_reduction)
        Keys.EDGE_MODE -> stringResource(R.string.setting_edge_mode)
        Keys.DENOISE -> stringResource(R.string.setting_denoise)
        Keys.OSD_TIME -> stringResource(R.string.setting_osd_time)
        Keys.OSD_NAME -> stringResource(R.string.setting_osd_name)
        Keys.OSD_POSITION -> stringResource(R.string.setting_osd_position)
        Keys.OSD_SIZE -> stringResource(R.string.setting_osd_size)
        Keys.MOTION_ENABLED -> stringResource(R.string.setting_motion_enabled)
        Keys.MOTION_SENSITIVITY -> stringResource(R.string.setting_motion_sensitivity)
        Keys.MOTION_MIN_AREA -> stringResource(R.string.setting_motion_min_area)
        Keys.MOTION_ZONES -> stringResource(R.string.setting_motion_zones)
        Keys.MOTION_IGNORE_LIGHT -> stringResource(R.string.setting_motion_ignore_light)
        Keys.MOTION_FPS -> stringResource(R.string.setting_motion_fps)
        Keys.REC_MODE -> stringResource(R.string.setting_rec_mode)
        Keys.REC_PRE_SEC -> stringResource(R.string.setting_rec_pre_sec)
        Keys.REC_POST_SEC -> stringResource(R.string.setting_rec_post_sec)
        Keys.REC_MAX_CLIP_MIN -> stringResource(R.string.setting_rec_max_clip_min)
        Keys.REC_STORAGE -> stringResource(R.string.setting_rec_storage)
        Keys.REC_MIN_FREE_GB -> stringResource(R.string.setting_rec_min_free_gb)
        Keys.REC_MAX_USAGE_GB -> stringResource(R.string.setting_rec_max_usage_gb)
        Keys.REC_MAX_AGE_DAYS -> stringResource(R.string.setting_rec_max_age_days)
        Keys.AUDIO_ENABLED -> stringResource(R.string.setting_audio_enabled)
        Keys.AUDIO_GAIN -> stringResource(R.string.setting_audio_gain)
        Keys.TG_ENABLED -> stringResource(R.string.setting_tg_enabled)
        Keys.TG_TOKEN -> stringResource(R.string.setting_tg_token)
        Keys.TG_CHAT -> stringResource(R.string.setting_tg_chat)
        Keys.TG_PHOTO -> stringResource(R.string.setting_tg_photo)
        Keys.TG_VIDEO -> stringResource(R.string.setting_tg_video)
        Keys.TG_POWER -> stringResource(R.string.setting_tg_power)
        Keys.TG_COOLDOWN_MIN -> stringResource(R.string.setting_tg_cooldown_min)
        Keys.PORT -> stringResource(R.string.setting_port)
        Keys.RTSP_ENABLED -> stringResource(R.string.setting_rtsp_enabled)
        Keys.RTSP_PORT -> stringResource(R.string.setting_rtsp_port)
        Keys.USERNAME -> stringResource(R.string.setting_username)
        Keys.PASSWORD -> stringResource(R.string.setting_password)
        Keys.NAME -> stringResource(R.string.setting_name)
        Keys.AUTOSTART -> stringResource(R.string.setting_autostart)
        Keys.LANGUAGE -> stringResource(R.string.setting_language)
        else -> key
    }
}

@Composable
private fun settingHelp(key: String): String? {
    return when (key) {
        Keys.BITRATE -> stringResource(R.string.setting_bitrate_help)
        Keys.ORIENTATION -> stringResource(R.string.setting_orientation_help)
        Keys.LOW_LIGHT_FPS -> stringResource(R.string.setting_low_light_fps_help)
        Keys.KEYFRAME_SEC -> stringResource(R.string.setting_keyframe_sec_help)
        Keys.TORCH -> stringResource(R.string.setting_torch_help)
        Keys.STABILIZATION -> stringResource(R.string.setting_stabilization_help)
        Keys.NOISE_REDUCTION -> stringResource(R.string.setting_noise_reduction_help)
        Keys.EDGE_MODE -> stringResource(R.string.setting_edge_mode_help)
        Keys.DENOISE -> stringResource(R.string.setting_denoise_help)
        Keys.MOTION_SENSITIVITY -> stringResource(R.string.setting_motion_sensitivity_help)
        Keys.MOTION_MIN_AREA -> stringResource(R.string.setting_motion_min_area_help)
        Keys.MOTION_ZONES -> stringResource(R.string.setting_motion_zones_help)
        Keys.MOTION_IGNORE_LIGHT -> stringResource(R.string.setting_motion_ignore_light_help)
        Keys.REC_MIN_FREE_GB -> stringResource(R.string.setting_rec_min_free_gb_help)
        Keys.REC_MAX_USAGE_GB -> stringResource(R.string.setting_rec_max_usage_gb_help)
        Keys.REC_MAX_AGE_DAYS -> stringResource(R.string.setting_rec_max_age_days_help)
        Keys.AUDIO_ENABLED -> stringResource(R.string.setting_audio_enabled_help)
        Keys.AUDIO_GAIN -> stringResource(R.string.setting_audio_gain_help)
        Keys.TG_ENABLED -> stringResource(R.string.setting_tg_enabled_help)
        Keys.TG_CHAT -> stringResource(R.string.setting_tg_chat_help)
        Keys.TG_VIDEO -> stringResource(R.string.setting_tg_video_help)
        Keys.TG_COOLDOWN_MIN -> stringResource(R.string.setting_tg_cooldown_min_help)
        Keys.RTSP_ENABLED -> stringResource(R.string.setting_rtsp_enabled_help)
        Keys.PASSWORD -> stringResource(R.string.setting_password_help)
        Keys.AUTOSTART -> stringResource(R.string.setting_autostart_help)
        Keys.LANGUAGE -> stringResource(R.string.setting_language_help)
        else -> null
    }
}

@Composable
private fun unitString(unit: String?): String? {
    return when (unit) {
        "к/с" -> stringResource(R.string.unit_fps)
        "fps" -> stringResource(R.string.unit_fps)
        "кбит/с" -> stringResource(R.string.unit_kbps)
        "kbps" -> stringResource(R.string.unit_kbps)
        "с" -> stringResource(R.string.unit_seconds)
        "min" -> stringResource(R.string.unit_minutes)
        "ГБ" -> stringResource(R.string.unit_gb)
        "GB" -> stringResource(R.string.unit_gb)
        "дн." -> stringResource(R.string.unit_days)
        "days" -> stringResource(R.string.unit_days)
        "%" -> stringResource(R.string.unit_percent)
        "дБ" -> stringResource(R.string.unit_db)
        "dB" -> stringResource(R.string.unit_db)
        "×" -> stringResource(R.string.unit_x)
        "EV" -> stringResource(R.string.unit_ev)
        else -> unit
    }
}

@Composable
private fun SettingRow(def: Setting, settings: Settings, options: (String) -> List<Option>?, onZones: () -> Unit) {
    var dialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val title = settingTitle(def.key)
    val help = settingHelp(def.key)

    val titleComposable: @Composable () -> Unit = {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        help?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }

    when (def) {
        is BoolSetting -> Row(
            Modifier.fillMaxWidth().clickable { save(def.key, !settings.bool(def.key)) }.padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) { titleComposable() }
            Switch(checked = settings.bool(def.key), onCheckedChange = { save(def.key, it) })
        }

        is ChoiceSetting -> {
            val opts = (if (def.dynamic) options(def.key) else null) ?: def.options
            val current = settings.string(def.key)
            val opt = opts.firstOrNull { it.value == current }
            val label = if (opt != null) {
                when (opt.value) {
                    "auto" -> stringResource(R.string.opt_auto)
                    "landscape" -> stringResource(R.string.opt_landscape)
                    "landscape_flip" -> stringResource(R.string.opt_landscape_flip)
                    "portrait" -> stringResource(R.string.opt_portrait)
                    "portrait_flip" -> stringResource(R.string.opt_portrait_flip)
                    "continuous" -> stringResource(R.string.opt_continuous_af)
                    "infinity" -> stringResource(R.string.opt_infinity)
                    "daylight" -> stringResource(R.string.opt_daylight)
                    "cloudy" -> stringResource(R.string.opt_cloudy)
                    "incandescent" -> stringResource(R.string.opt_incandescent)
                    "fluorescent" -> stringResource(R.string.opt_fluorescent)
                    "50hz" -> stringResource(R.string.opt_50hz)
                    "60hz" -> stringResource(R.string.opt_60hz)
                    "off" -> stringResource(R.string.opt_off)
                    "high" -> stringResource(R.string.opt_high)
                    "main" -> stringResource(R.string.opt_main)
                    "baseline" -> stringResource(R.string.opt_baseline)
                    "vbr" -> stringResource(R.string.opt_vbr)
                    "cbr" -> stringResource(R.string.opt_cbr)
                    "internal" -> stringResource(R.string.opt_internal)
                    "tl" -> stringResource(R.string.opt_tl)
                    "tr" -> stringResource(R.string.opt_tr)
                    "bl" -> stringResource(R.string.opt_bl)
                    "br" -> stringResource(R.string.opt_br)
                    "motion" -> stringResource(R.string.opt_motion)
                    else -> opt.label
                }
            } else if (current.isEmpty()) stringResource(R.string.settings_auto) else current
            Row(Modifier.fillMaxWidth().clickable { dialog = true }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { titleComposable() }
                Spacer(Modifier.width(12.dp))
                Text(label, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
            }
            if (dialog) ChoiceDialog(title, opts, current, onDismiss = { dialog = false }) { v ->
                dialog = false
                if (def.key == Keys.LANGUAGE) save(def.key, v, context)
                else save(def.key, v)
            }
        }

        is IntSetting, is FloatSetting -> {
            val isFloat = def is FloatSetting
            val min = if (def is IntSetting) def.min.toDouble() else (def as FloatSetting).min
            val max = if (def is IntSetting) def.max.toDouble() else (def as FloatSetting).max
            val step = if (def is IntSetting) def.step.toDouble() else (def as FloatSetting).step
            val unit = unitString(if (def is IntSetting) def.unit else (def as FloatSetting).unit)
            val value = settings.double(def.key)
            // Use text input for wide ranges (ports)
            if (def.key == Keys.PORT || def.key == Keys.RTSP_PORT) {
                Row(Modifier.fillMaxWidth().clickable { dialog = true }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { titleComposable() }
                    Spacer(Modifier.width(12.dp))
                    Text(value.roundToInt().toString(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                }
                if (dialog) TextDialog(title, value.roundToInt().toString(), KeyboardType.Number, secret = false, onDismiss = { dialog = false }) { t ->
                    dialog = false
                    t.toIntOrNull()?.let { save(def.key, it) }
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { titleComposable() }
                    Spacer(Modifier.width(12.dp))
                    Text(formatNumber(value, isFloat, unit), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                }
                Slider(
                    value = value.toFloat(),
                    onValueChange = { v ->
                        if (isFloat) save(def.key, v.toDouble())
                        else save(def.key, v.roundToInt())
                    },
                    valueRange = min.toFloat()..max.toFloat(),
                    steps = if (step > 0) ((max - min) / step).toInt() - 1 else 0,
                )
            }
        }

        is TextSetting -> {
            val current = settings.string(def.key)
            if (def.editor == "zones") {
                Row(Modifier.fillMaxWidth().clickable { onZones() }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { titleComposable() }
                    Spacer(Modifier.width(12.dp))
                    Text(if (current.isEmpty()) stringResource(R.string.settings_not_set) else "✓", color = MaterialTheme.colorScheme.primary)
                }
            } else if (def.editor == "tg-chat") {
                TelegramChatRow(def, current, titleComposable)
            } else if (def.secret) {
                val isSet = current.isNotEmpty()
                Row(Modifier.fillMaxWidth().clickable { dialog = true }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { titleComposable() }
                    Spacer(Modifier.width(12.dp))
                    Text(if (isSet) "••••••" else stringResource(R.string.settings_not_set), color = MaterialTheme.colorScheme.primary)
                }
                if (dialog) SecretDialog(def, isSet, onDismiss = { dialog = false }) { pw ->
                    dialog = false
                    save(def.key, pw)
                }
            } else {
                Row(Modifier.fillMaxWidth().clickable { dialog = true }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { titleComposable() }
                    Spacer(Modifier.width(12.dp))
                    Text(current.ifEmpty { stringResource(R.string.settings_not_set) }, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                }
                if (dialog) TextDialog(title, current, KeyboardType.Text, secret = false, onDismiss = { dialog = false }) { t ->
                    dialog = false
                    save(def.key, t)
                }
            }
        }
    }
}

@Composable
private fun ChoiceDialog(title: String, options: List<Option>, current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { o ->
                    val label = when (o.value) {
                        "auto" -> stringResource(R.string.opt_auto)
                        "landscape" -> stringResource(R.string.opt_landscape)
                        "landscape_flip" -> stringResource(R.string.opt_landscape_flip)
                        "portrait" -> stringResource(R.string.opt_portrait)
                        "portrait_flip" -> stringResource(R.string.opt_portrait_flip)
                        "continuous" -> stringResource(R.string.opt_continuous_af)
                        "infinity" -> stringResource(R.string.opt_infinity)
                        "daylight" -> stringResource(R.string.opt_daylight)
                        "cloudy" -> stringResource(R.string.opt_cloudy)
                        "incandescent" -> stringResource(R.string.opt_incandescent)
                        "fluorescent" -> stringResource(R.string.opt_fluorescent)
                        "50hz" -> stringResource(R.string.opt_50hz)
                        "60hz" -> stringResource(R.string.opt_60hz)
                        "off" -> stringResource(R.string.opt_off)
                        "high" -> stringResource(R.string.opt_high)
                        "main" -> stringResource(R.string.opt_main)
                        "baseline" -> stringResource(R.string.opt_baseline)
                        "vbr" -> stringResource(R.string.opt_vbr)
                        "cbr" -> stringResource(R.string.opt_cbr)
                        "internal" -> stringResource(R.string.opt_internal)
                        "tl" -> stringResource(R.string.opt_tl)
                        "tr" -> stringResource(R.string.opt_tr)
                        "bl" -> stringResource(R.string.opt_bl)
                        "br" -> stringResource(R.string.opt_br)
                        "motion" -> stringResource(R.string.opt_motion)
                        else -> o.label
                    }
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = o.value == current, onClick = { onPick(o.value) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = o.value == current, onClick = { onPick(o.value) })
                        Text(label)
                    }
                }
            }
        },
    )
}

@Composable
private fun TextDialog(title: String, initial: String, keyboard: KeyboardType, secret: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onSave(text.trim()); onDismiss() }) { Text(stringResource(R.string.settings_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            )
        },
    )
}

@Composable
private fun SecretDialog(def: TextSetting, isSet: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val isPassword = def.key == Keys.PASSWORD
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onSave(text.trim()) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.settings_save)) } },
        dismissButton = {
            Row {
                if (isSet) TextButton(onClick = { onSave("") }) { Text(stringResource(R.string.settings_remove)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
            }
        },
        title = { Text(if (isPassword) stringResource(R.string.password_dialog_title) else settingTitle(def.key)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (isPassword) stringResource(R.string.password_dialog_text)
                    else stringResource(R.string.token_dialog_text),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    label = { Text(if (isSet) stringResource(R.string.settings_new_value) else settingTitle(def.key)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
        },
    )
}

@Composable
private fun TelegramChatRow(def: TextSetting, current: String, title: @Composable () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var chats by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            title()
            Text(current.ifEmpty { stringResource(R.string.settings_not_set) }, color = MaterialTheme.colorScheme.primary)
        }
        TextButton(onClick = {
            scope.launch {
                val tg = telegram()
                if (tg == null) return@launch
                val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { tg.findChats() }
                r.fold(
                    onSuccess = { list ->
                        when (list.size) {
                            0 -> android.widget.Toast.makeText(context, context.getString(R.string.tg_no_chats), android.widget.Toast.LENGTH_LONG).show()
                            1 -> save(def.key, list[0].first)
                            else -> chats = list
                        }
                    },
                    onFailure = { android.widget.Toast.makeText(context, it.message ?: context.getString(R.string.tg_find_error), android.widget.Toast.LENGTH_LONG).show() },
                )
            }
        }) { Text(stringResource(R.string.settings_find)) }
    }
    chats?.let { list ->
        ChoiceDialog(settingTitle(def.key), list.map { Option(it.first, "${it.second} (${it.first})") }, current, onDismiss = { chats = null }) { v ->
            chats = null
            save(def.key, v)
        }
    }
}

private fun save(key: String, value: Any, context: android.content.Context? = null) {
    kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        com.surveil.camera.App.settings.set(key, value)
        if (key == com.surveil.core.settings.Keys.LANGUAGE && context != null) {
            val platform = AndroidPlatform(context, com.surveil.camera.capture.CameraCatalog(context))
            platform.setLocale(value.toString())
        }
    }
}

private fun telegram() = com.surveil.camera.service.EngineHolder.current.value?.core?.telegram

@Composable
private fun formatNumber(value: Double, isFloat: Boolean, unit: String?): String {
    val txt = if (isFloat) {
        (Math.round(value * 100) / 100).toString().replace('.', ',')
    } else {
        value.roundToInt().toString()
    }
    return if (unit != null) "$txt $unit" else txt
}