package com.surveil.camera.ui

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.surveil.camera.App
import com.surveil.core.EngineStatus
import com.surveil.core.NetAddress
import com.surveil.core.settings.Keys
import com.surveil.core.settings.Settings

/** State gathered by [MainActivity] for the screens. */
class HomeState(
    val running: Boolean,
    val status: EngineStatus,
    val settings: Settings,
    val addresses: List<NetAddress>,
    val cameraGranted: Boolean,
    val batteryOptimized: Boolean,
)

class HomeActions(
    val start: () -> Unit,
    val stop: () -> Unit,
    val requestCamera: () -> Unit,
    val requestBattery: () -> Unit,
    val openSettings: () -> Unit,
    val openGuide: () -> Unit,
    val dimScreen: () -> Unit,
    val setManual: (Boolean) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(state: HomeState, actions: HomeActions) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.settings.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        Text(subtitle(state), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    IconButton(onClick = actions.openGuide) { Icon(Icons.AutoMirrored.Outlined.HelpOutline, "Как подключиться") }
                    IconButton(onClick = actions.openSettings) { Icon(Icons.Filled.Settings, "Настройки") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        if (landscape) {
            Row(Modifier.padding(pad).fillMaxSize().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1.25f).fillMaxHeight().padding(bottom = 12.dp), contentAlignment = Alignment.Center) {
                    PreviewCard(state, actions, Modifier.fillMaxWidth())
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    homeItems(state, actions)
                }
            }
        } else {
            LazyColumn(
                Modifier.padding(pad).fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { PreviewCard(state, actions, Modifier.fillMaxWidth()) }
                homeItems(state, actions)
            }
        }
    }
}

private fun subtitle(s: HomeState): String = when {
    !s.running -> "Остановлена"
    s.status.camera.state == "error" -> "Ошибка камеры"
    s.status.camera.state == "starting" -> "Запуск камеры…"
    s.status.camera.state == "running" -> buildString {
        append("Работает")
        if (s.status.viewers > 0) append(" · зрителей: ${s.status.viewers}")
    }
    else -> "Запуск…"
}

private fun LazyListScope.homeItems(state: HomeState, actions: HomeActions) {
    if (!state.cameraGranted) item { PermissionCard(actions) }
    if (state.running) item { ControlsCard(state, actions) }
    item { ConnectCard(state) }
    if (state.batteryOptimized) item { BatteryCard(actions) }
    item { ServiceCard(state, actions) }
}

@Composable
private fun PreviewCard(state: HomeState, actions: HomeActions, modifier: Modifier) {
    var previewOn by rememberSaveable { mutableStateOf(true) }
    val cam = state.status.camera
    val aspect = if (cam.width > 0 && cam.height > 0) cam.width.toFloat() / cam.height else 16f / 9f
    Box(
        modifier
            .aspectRatio(aspect.coerceIn(0.4f, 2.5f))
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black),
    ) {
        when {
            !state.running -> FillBox {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Камера остановлена", color = Color.White)
                    Spacer(Modifier.height(10.dp))
                    if (state.cameraGranted) Button(onClick = actions.start) {
                        Icon(Icons.Filled.PlayArrow, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Запустить")
                    }
                }
            }
            !previewOn -> FillBox {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                    Icon(Icons.Filled.VisibilityOff, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("Превью скрыто — камера продолжает работать", color = Color.White.copy(alpha = 0.8f))
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = { previewOn = true }) { Text("Показать", color = Color.White) }
                }
            }
            else -> {
                CameraPreview(Modifier.fillMaxSize())
                MotionBoxOverlay(if (state.status.motionActive) state.status.motionBox else null, Modifier.fillMaxSize())
                if (cam.state != "running") FillBox {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                        if (cam.state == "error") {
                            Icon(Icons.Filled.Warning, null, tint = Palette.Motion, modifier = Modifier.size(32.dp))
                            Spacer(Modifier.height(6.dp))
                            Text(cam.error ?: "Ошибка камеры", color = Color.White)
                            Text("Повторная попытка автоматически…", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                        } else {
                            CircularProgressIndicator(color = Color.White)
                        }
                    }
                }
            }
        }
        if (state.running) {
            Row(Modifier.align(Alignment.BottomStart).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (state.status.recordingId != null) StatusChip("REC", Palette.Rec)
                if (state.status.motionActive) StatusChip("Движение", Palette.Motion)
            }
            if (previewOn) {
                IconButton(
                    onClick = { previewOn = false },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.45f)),
                ) { Icon(Icons.Filled.VisibilityOff, "Скрыть превью", tint = Color.White) }
            }
        }
    }
}

@Composable
private fun ControlsCard(state: HomeState, actions: HomeActions) {
    val s = state.settings
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardTitle("Запись")
            val modes = listOf("off" to "Не писать", "motion" to "По движению", "continuous" to "Всегда")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                modes.forEachIndexed { i, (value, label) ->
                    SegmentedButton(
                        selected = s.string(Keys.REC_MODE) == value,
                        onClick = { App.settings.set(Keys.REC_MODE, value) },
                        shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                        label = { Text(label, maxLines = 1) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                val manual = state.status.manualRecording
                FilledTonalButton(
                    onClick = { actions.setManual(!manual) },
                    modifier = Modifier.weight(1f),
                    colors = if (manual) ButtonDefaults.filledTonalButtonColors(containerColor = Palette.Rec, contentColor = Color.White)
                    else ButtonDefaults.filledTonalButtonColors(),
                ) {
                    Icon(if (manual) Icons.Filled.Stop else Icons.Filled.FiberManualRecord, null, tint = if (manual) Color.White else Palette.Rec)
                    Spacer(Modifier.width(6.dp))
                    Text(if (manual) "Стоп" else "Записать")
                }
                val torch = s.bool(Keys.TORCH)
                FilledTonalButton(onClick = { App.settings.set(Keys.TORCH, !torch) }, modifier = Modifier.weight(1f)) {
                    Icon(if (torch) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (torch) "Свет вкл" else "Свет")
                }
                FilledTonalButton(onClick = actions.dimScreen, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Bedtime, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Экран", maxLines = 1)
                }
            }
            val motionText = when {
                !s.bool(Keys.MOTION_ENABLED) -> "Детектор движения выключен"
                state.status.motionActive -> "Обнаружено движение"
                else -> "Движения нет (уровень %.1f%%, порог %.1f%%)".format(state.status.motionScore, s.double(Keys.MOTION_MIN_AREA))
            }
            Text(motionText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ConnectCard(state: HomeState) {
    val context = LocalContext.current
    val port = if (state.status.serverPort > 0) state.status.serverPort else state.settings.port
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardTitle("Как смотреть камеру")
            Text(
                "Откройте адрес в браузере на компьютере или другом телефоне в той же Wi-Fi сети. Там же — записи и все настройки.",
                style = MaterialTheme.typography.bodyMedium,
            )
            AddressList(state.addresses, port)
            if (state.status.serverError != null) {
                Text(state.status.serverError!!, color = MaterialTheme.colorScheme.error)
            }
            if (!state.running) {
                Text("Адрес заработает после запуска камеры.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            } else {
                OutlinedButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:$port/"))) }
                }) {
                    Icon(Icons.Filled.OpenInBrowser, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Открыть веб-интерфейс здесь")
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(actions: HomeActions) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Нужен доступ к камере", style = MaterialTheme.typography.titleMedium)
            Text("Без него приложение не сможет показывать и записывать видео.", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = actions.requestCamera) { Text("Разрешить") }
        }
    }
}

@Composable
private fun BatteryCard(actions: HomeActions) {
    SectionCard(Modifier.clickable(onClick = actions.requestBattery)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, null, tint = Palette.Motion)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Разрешите работу без ограничений", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Иначе система может усыпить камеру при выключенном экране.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButtonLike("Разрешить", actions.requestBattery)
        }
    }
}

@Composable
private fun TextButtonLike(text: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick) { Text(text) }
}

@Composable
private fun ServiceCard(state: HomeState, actions: HomeActions) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.running) {
                Text(
                    "Камера продолжит работать при выключенном экране и после выхода из приложения — до нажатия «Остановить». " +
                        "Телефон лучше держать на зарядке.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = actions.stop, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Stop, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Остановить камеру")
                }
            } else {
                Button(onClick = actions.start, enabled = state.cameraGranted, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Запустить камеру")
                }
            }
        }
    }
}

/** Black full-screen overlay at minimum brightness; the camera keeps running. Tap to exit. */
@Composable
fun DimOverlay(onExit: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(onClick = onExit),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Text(
            "Камера работает. Коснитесь экрана, чтобы выйти.",
            color = Color.White.copy(alpha = 0.25f),
            modifier = Modifier.padding(32.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
