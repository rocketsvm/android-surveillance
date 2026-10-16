package com.surveil.camera.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.surveil.camera.App
import com.surveil.camera.platform.DeviceInfo
import com.surveil.core.settings.Keys

class GuideState(
    val home: HomeState,
    val notificationsGranted: Boolean,
    val overlayGranted: Boolean,
)

class GuideActions(
    val requestCamera: () -> Unit,
    val requestNotifications: () -> Unit,
    val requestBattery: () -> Unit,
    val openAppSettings: () -> Unit,
    val requestOverlay: () -> Unit,
    val done: () -> Unit,
    val back: (() -> Unit)?,
)

/** First-run walkthrough: permissions, background work, how to connect from another device. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideScreen(state: GuideState, actions: GuideActions) {
    val home = state.home
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройка камеры") },
                navigationIcon = {
                    actions.back?.let { IconButton(onClick = it) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } }
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
            item {
                SectionCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Телефон станет камерой наблюдения", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Видео H.264 в реальном времени, детектор движения и запись роликов. Смотреть, скачивать записи и менять " +
                                "настройки можно из браузера на компьютере или другом телефоне. Экран можно выключить — камера продолжит работать.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            item {
                Step(1, "Разрешения", home.cameraGranted && state.notificationsGranted) {
                    CheckLine("Камера", home.cameraGranted, "Разрешить", actions.requestCamera)
                    if (Build.VERSION.SDK_INT >= 33) {
                        CheckLine("Уведомления (значок работы в шторке)", state.notificationsGranted, "Разрешить", actions.requestNotifications)
                    }
                }
            }
            item {
                Step(2, "Работа с выключенным экраном", !home.batteryOptimized) {
                    CheckLine("Без ограничений батареи", !home.batteryOptimized, "Разрешить", actions.requestBattery)
                    if (DeviceInfo.isSamsung) {
                        Hint(
                            "Samsung: «Настройки → Приложения → Камера наблюдения → Батарея» — выберите «Без ограничений». " +
                                "В «Обслуживание устройства → Автооптимизация» отключите перезагрузку по расписанию — или включите автозапуск ниже.",
                        )
                        FilledTonalButton(onClick = actions.openAppSettings) { Text("Открыть настройки приложения") }
                    } else if (DeviceInfo.isXiaomi) {
                        Hint("Xiaomi: в настройках приложения включите «Автозапуск» и «Контроль активности: нет ограничений».")
                        FilledTonalButton(onClick = actions.openAppSettings) { Text("Открыть настройки приложения") }
                    } else if (DeviceInfo.isHuawei) {
                        Hint("Huawei/Honor: «Запуск приложений» — переключите на ручное управление и разрешите работу в фоне.")
                        FilledTonalButton(onClick = actions.openAppSettings) { Text("Открыть настройки приложения") }
                    }
                    Hint("Держите телефон на зарядке: камера и Wi-Fi работают постоянно.")
                }
            }
            item {
                Step(3, "Подключение с другого устройства", null) {
                    Text(
                        "1. Подключите этот телефон и компьютер (или другой телефон) к одной Wi-Fi сети.\n" +
                            "2. Откройте в браузере адрес ниже — или отсканируйте QR-код.\n" +
                            "3. Чтобы адрес не менялся, закрепите IP за телефоном в настройках роутера (резервирование DHCP).",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    val port = if (home.status.serverPort > 0) home.status.serverPort else home.settings.port
                    AddressList(home.addresses, port)
                    Hint(
                        "Доступ через интернет: установите VPN (например, Tailscale или ZeroTier) на этот телефон и на устройство, " +
                            "с которого смотрите, — адрес VPN появится в списке. Не открывайте порт на роутере без пароля.",
                    )
                }
            }
            item {
                val autostart = home.settings.bool(Keys.AUTOSTART)
                Step(4, "Автозапуск после перезагрузки", if (autostart) state.overlayGranted else null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Запускать камеру после перезагрузки телефона", Modifier.weight(1f))
                        Switch(checked = autostart, onCheckedChange = { App.settings.set(Keys.AUTOSTART, it) })
                    }
                    if (autostart) {
                        CheckLine("Разрешение «Поверх других приложений»", state.overlayGranted, "Разрешить", actions.requestOverlay)
                        Hint("Без него после перезагрузки появится уведомление — камеру можно будет включить одним нажатием. Блокировку экрана лучше отключить.")
                    }
                }
            }
            item {
                Button(onClick = actions.done, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(if (home.running) "Готово" else "Готово, запустить камеру")
                }
            }
        }
    }
}

@Composable
private fun Step(n: Int, title: String, ok: Boolean?, content: @Composable () -> Unit) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$n", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                when (ok) {
                    true -> Icon(Icons.Filled.CheckCircle, "Готово", tint = Palette.Ok)
                    false -> Icon(Icons.Filled.ErrorOutline, "Требует внимания", tint = Palette.Motion)
                    null -> Unit
                }
            }
            content()
        }
    }
}

@Composable
private fun CheckLine(text: String, ok: Boolean, button: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(
            if (ok) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline, null,
            tint = if (ok) Palette.Ok else Palette.Motion, modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (!ok) FilledTonalButton(onClick = onClick) { Text(button) }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
