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
import androidx.compose.material.icons.filled.GridOn
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.surveil.camera.App
import com.surveil.core.settings.BoolSetting
import com.surveil.core.settings.ChoiceSetting
import com.surveil.core.settings.FloatSetting
import com.surveil.core.settings.IntSetting
import com.surveil.core.settings.Option
import com.surveil.core.settings.Schema
import com.surveil.core.settings.Setting
import com.surveil.core.settings.Settings
import com.surveil.core.settings.TextSetting
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt

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
                title = { Text("Настройки") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                actions = {
                    Text("Все", style = MaterialTheme.typography.labelLarge)
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
                        Text(g.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        val visible = g.settings.filter { advanced || !it.advanced }
                        visible.forEachIndexed { i, def ->
                            SettingRow(def, settings, options, onZones)
                            if (i < visible.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}

private fun save(key: String, value: Any) {
    App.settings.update(mapOf(key to when (value) {
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }))
}

private fun formatNumber(v: Double, isFloat: Boolean, unit: String?): String {
    val n = if (isFloat) ((v * 100).roundToInt() / 100.0).toString().replace('.', ',').removeSuffix(",0") else v.roundToInt().toString()
    return if (unit != null) "$n $unit" else n
}

@Composable
private fun SettingRow(def: Setting, settings: Settings, options: (String) -> List<Option>?, onZones: () -> Unit) {
    var dialog by remember { mutableStateOf(false) }
    val title: @Composable () -> Unit = {
        Text(def.title, style = MaterialTheme.typography.bodyLarge)
        def.help?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    when (def) {
        is BoolSetting -> Row(
            Modifier.fillMaxWidth().clickable { save(def.key, !settings.bool(def.key)) }.padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) { title() }
            Switch(checked = settings.bool(def.key), onCheckedChange = { save(def.key, it) })
        }

        is ChoiceSetting -> {
            val opts = (if (def.dynamic) options(def.key) else null) ?: def.options
            val current = settings.string(def.key)
            val label = opts.firstOrNull { it.value == current }?.label ?: if (current.isEmpty()) "Авто" else current
            Row(Modifier.fillMaxWidth().clickable { dialog = true }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { title() }
                Spacer(Modifier.width(12.dp))
                Text(label, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
            }
            if (dialog) ChoiceDialog(def.title, opts, current, onDismiss = { dialog = false }) { v ->
                dialog = false
                save(def.key, v)
            }
        }

        is IntSetting, is FloatSetting -> {
            val isFloat = def is FloatSetting
            val min = if (def is IntSetting) def.min.toDouble() else (def as FloatSetting).min
            val max = if (def is IntSetting) def.max.toDouble() else (def as FloatSetting).max
            val step = if (def is IntSetting) def.step.toDouble() else (def as FloatSetting).step
            val unit = if (def is IntSetting) def.unit else (def as FloatSetting).unit
            val value = settings.double(def.key)
            if ((max - min) / step > 2000 || def.key == com.surveil.core.settings.Keys.PORT) {
                Row(Modifier.fillMaxWidth().clickable { dialog = true }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { title() }
                    Text(formatNumber(value, isFloat, null), color = MaterialTheme.colorScheme.primary)
                }
                if (dialog) TextDialog(def.title, formatNumber(value, isFloat, null), KeyboardType.Number, secret = false, onDismiss = { dialog = false }) { t ->
                    dialog = false
                    t.replace(',', '.').toDoubleOrNull()?.let { save(def.key, if (isFloat) it else it.roundToInt()) }
                }
            } else {
                var drag by remember(value) { mutableFloatStateOf(value.toFloat()) }
                Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { title() }
                        Text(formatNumber(drag.toDouble(), isFloat, unit), color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(
                        value = drag,
                        onValueChange = { v -> drag = ((Math.round((v - min) / step) * step) + min).toFloat() },
                        valueRange = min.toFloat()..max.toFloat(),
                        onValueChangeFinished = { save(def.key, if (isFloat) drag.toDouble() else drag.roundToInt()) },
                    )
                }
            }
        }

        is TextSetting -> {
            if (def.editor == "zones") {
                Row(Modifier.fillMaxWidth().clickable(onClick = onZones).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { title() }
                    Icon(Icons.Filled.GridOn, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(if (settings.string(def.key).isEmpty()) "Весь кадр" else "Настроены", color = MaterialTheme.colorScheme.primary)
                }
            } else if (def.secret) {
                val isSet = settings.string(def.key).isNotEmpty()
                Row(Modifier.fillMaxWidth().clickable { dialog = true }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { title() }
                    Text(if (isSet) "Установлен" else "Не задан", color = if (isSet) Palette.Ok else MaterialTheme.colorScheme.error)
                }
                if (dialog) PasswordDialog(isSet, onDismiss = { dialog = false }) { pw ->
                    dialog = false
                    save(def.key, pw)
                }
            } else {
                val current = settings.string(def.key)
                Row(Modifier.fillMaxWidth().clickable { dialog = true }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { title() }
                    Text(current.ifEmpty { "—" }, color = MaterialTheme.colorScheme.primary)
                }
                if (dialog) TextDialog(def.title, current, KeyboardType.Text, secret = false, onDismiss = { dialog = false }) { t ->
                    dialog = false
                    save(def.key, t.trim())
                }
            }
        }
    }
}

@Composable
private fun ChoiceDialog(title: String, options: List<Option>, current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { o ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = o.value == current, onClick = { onPick(o.value) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = o.value == current, onClick = { onPick(o.value) })
                        Text(o.label)
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
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
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
private fun PasswordDialog(isSet: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { if (text.isNotEmpty()) onSave(text) }, enabled = text.isNotEmpty()) { Text("Сохранить") } },
        dismissButton = {
            Row {
                if (isSet) TextButton(onClick = { onSave("") }) { Text("Убрать пароль") }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
        title = { Text("Пароль для веб-доступа") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Браузер будет спрашивать имя пользователя и этот пароль. Без пароля камеру может открыть любой в вашей сети.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    label = { Text(if (isSet) "Новый пароль" else "Пароль") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
        },
    )
}
