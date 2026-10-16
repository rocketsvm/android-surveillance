package com.surveil.camera.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.surveil.camera.service.EngineHolder
import com.surveil.core.NetAddress

/**
 * SurfaceView that the GL renderer draws the live picture into. The surface is handed to whichever
 * engine is running, also when the service (re)starts while the view already exists.
 */
@Composable
fun CameraPreview(modifier: Modifier = Modifier) {
    val holder = remember { arrayOfNulls<android.view.Surface>(1) }
    val engine by EngineHolder.current.collectAsStateWithLifecycle()
    LaunchedEffect(engine) {
        holder[0]?.takeIf { it.isValid }?.let { engine?.pipeline?.setPreview(it) }
    }
    DisposableEffect(Unit) {
        onDispose {
            EngineHolder.current.value?.pipeline?.setPreview(null)
            holder[0] = null
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceView(ctx).apply {
                this.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(h: SurfaceHolder) = Unit
                    override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {
                        holder[0] = h.surface
                        EngineHolder.current.value?.pipeline?.setPreview(h.surface)
                    }
                    override fun surfaceDestroyed(h: SurfaceHolder) {
                        holder[0] = null
                        EngineHolder.current.value?.pipeline?.setPreview(null)
                    }
                })
            }
        },
    )
}

/** Normalised motion bounding box drawn over the preview. */
@Composable
fun MotionBoxOverlay(box: List<Float>?, modifier: Modifier = Modifier) {
    if (box == null || box.size < 4) return
    Canvas(modifier) {
        drawRect(
            color = Palette.Motion,
            topLeft = Offset(box[0] * size.width, box[1] * size.height),
            size = Size(box[2] * size.width, box[3] * size.height),
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}

fun qrBitmap(text: String, size: Int = 600): ImageBitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val px = IntArray(size * size) { i -> if (m[i % size, i / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}

fun copyToClipboard(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("url", text))
    Toast.makeText(context, "Скопировано: $text", Toast.LENGTH_SHORT).show()
}

fun kindLabel(kind: String): String = when (kind) {
    "wifi" -> "Wi-Fi"
    "ethernet" -> "Ethernet"
    "hotspot" -> "Точка доступа"
    "usb" -> "USB-модем"
    "vpn" -> "VPN"
    "mobile" -> "Мобильная сеть"
    else -> "Сеть"
}

/** List of URLs to open on another device, with copy and QR buttons. */
@Composable
fun AddressList(addresses: List<NetAddress>, port: Int, showVlc: Boolean = true, rtspPort: Int? = null) {
    val context = LocalContext.current
    var qrFor by remember { mutableStateOf<String?>(null) }
    val v4 = addresses.filter { !it.ipv6 && it.kind != "mobile" }
    if (v4.isEmpty()) {
        Text(
            "Телефон не подключён к локальной сети. Подключите его к Wi-Fi — к той же сети, что и компьютер или телефон, с которого будете смотреть.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (a in v4) {
            val url = "http://${a.address}:$port"
            Column {
                Text(
                    url, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${kindLabel(a.kind)} · ${a.iface}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { copyToClipboard(context, url) }) { Icon(Icons.Filled.ContentCopy, "Копировать") }
                    IconButton(onClick = { qrFor = url }) { Icon(Icons.Filled.QrCode2, "QR-код") }
                }
            }
        }
        if (showVlc) {
            val host = v4.first().address
            val text = buildString {
                append("Для VLC и видеорегистраторов:")
                if (rtspPort != null) append("\nrtsp://$host:$rtspPort/live")
                append("\nhttp://$host:$port/live.mp4")
            }
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    qrFor?.let { url ->
        val bmp = remember(url) { qrBitmap(url) }
        AlertDialog(
            onDismissRequest = { qrFor = null },
            confirmButton = { TextButton(onClick = { qrFor = null }) { Text("Закрыть") } },
            title = { Text("Наведите камеру другого телефона") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Image(bmp, null, Modifier.size(260.dp).background(Color.White).padding(6.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(url, fontFamily = FontFamily.Monospace)
                }
            },
        )
    }
}

/** Small status pill. [solid] is for placement over video, where a tinted pill would be unreadable. */
@Composable
fun StatusChip(text: String, color: Color, modifier: Modifier = Modifier, dot: Boolean = true, solid: Boolean = false) {
    val fg = if (solid) Color.White else color
    Surface(color = if (solid) color.copy(alpha = 0.9f) else color.copy(alpha = 0.16f), shape = RoundedCornerShape(50), modifier = modifier) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            if (dot) {
                Box(Modifier.size(8.dp).background(fg, RoundedCornerShape(50)))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, color = fg, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
    ) {
        Box(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
fun CardTitle(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 10.dp),
    )
}

@Composable
fun FillBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
