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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.surveil.camera.App
import com.surveil.camera.R
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
                title = { Text(stringResource(R.string.guide_title)) },
                navigationIcon = {
                    actions.back?.let { IconButton(onClick = it) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.guide_back)) } }
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
                        Text(stringResource(R.string.guide_welcome_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.guide_welcome_text), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item {
                Step(1, stringResource(R.string.guide_step_permissions), home.cameraGranted && state.notificationsGranted) {
                    CheckLine(stringResource(R.string.guide_camera_perm), home.cameraGranted, stringResource(R.string.guide_allow), actions.requestCamera)
                    if (Build.VERSION.SDK_INT >= 33) {
                        CheckLine(stringResource(R.string.guide_notify_perm), state.notificationsGranted, stringResource(R.string.guide_allow), actions.requestNotifications)
                    }
                }
            }
            item {
                Step(2, stringResource(R.string.guide_step_background), !home.batteryOptimized) {
                    CheckLine(stringResource(R.string.guide_battery_perm), !home.batteryOptimized, stringResource(R.string.guide_allow), actions.requestBattery)
                    if (DeviceInfo.isSamsung) {
                        Hint(stringResource(R.string.guide_samsung_hint))
                        FilledTonalButton(onClick = actions.openAppSettings) { Text(stringResource(R.string.guide_open_settings)) }
                    } else if (DeviceInfo.isXiaomi) {
                        Hint(stringResource(R.string.guide_xiaomi_hint))
                        FilledTonalButton(onClick = actions.openAppSettings) { Text(stringResource(R.string.guide_open_settings)) }
                    } else if (DeviceInfo.isHuawei) {
                        Hint(stringResource(R.string.guide_huawei_hint))
                        FilledTonalButton(onClick = actions.openAppSettings) { Text(stringResource(R.string.guide_open_settings)) }
                    }
                    Hint(stringResource(R.string.guide_charging_hint))
                }
            }
            item {
                Step(3, stringResource(R.string.guide_step_connect), null) {
                    Text(stringResource(R.string.guide_connect_text), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    val port = if (home.status.serverPort > 0) home.status.serverPort else home.settings.port
                    AddressList(home.addresses, port, rtspPort = home.settings.takeIf { it.bool(Keys.RTSP_ENABLED) }?.int(Keys.RTSP_PORT))
                    Hint(stringResource(R.string.guide_internet_hint))
                }
            }
            item {
                val autostart = home.settings.bool(Keys.AUTOSTART)
                Step(4, stringResource(R.string.guide_step_autostart), if (autostart) state.overlayGranted else null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.guide_autostart_switch), Modifier.weight(1f))
                        Switch(checked = autostart, onCheckedChange = { App.settings.set(Keys.AUTOSTART, it) })
                    }
                    if (autostart) {
                        CheckLine(stringResource(R.string.guide_overlay_perm), state.overlayGranted, stringResource(R.string.guide_allow), actions.requestOverlay)
                        Hint(stringResource(R.string.guide_overlay_hint))
                    }
                }
            }
            item {
                Button(onClick = actions.done, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(if (home.running) stringResource(R.string.guide_done) else stringResource(R.string.guide_done_start))
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
