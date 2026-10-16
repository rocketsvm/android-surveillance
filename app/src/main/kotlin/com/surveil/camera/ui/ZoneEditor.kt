package com.surveil.camera.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.surveil.camera.App
import com.surveil.camera.service.EngineHolder
import com.surveil.core.motion.ZoneMask
import com.surveil.core.settings.Keys
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Paint cells to exclude them from motion detection (same mask format as the web editor). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZoneEditor(onClose: () -> Unit) {
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    val initial = remember { ZoneMask.parse(App.settings.current.string(Keys.MOTION_ZONES)) }
    var cols by remember { mutableIntStateOf(initial?.cols ?: 16) }
    var rows by remember { mutableIntStateOf(initial?.rows ?: 9) }
    var cells by remember { mutableStateOf(initial?.excluded?.copyOf() ?: BooleanArray(16 * 9)) }
    var version by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        val pipeline = EngineHolder.current.value?.pipeline ?: return@LaunchedEffect
        val jpeg = suspendCancellableCoroutine { cont -> pipeline.snapshotJpeg(960) { cont.resume(it) } }
        val bmp = jpeg?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } ?: return@LaunchedEffect
        image = bmp.asImageBitmap()
        if (initial == null) {
            val ar = bmp.width.toFloat() / bmp.height
            val dims = when {
                ar >= 1.5f -> 16 to 9
                ar >= 1f -> 16 to 12
                ar >= 0.7f -> 12 to 16
                else -> 9 to 16
            }
            cols = dims.first; rows = dims.second
            cells = BooleanArray(cols * rows)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Зоны детекции") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Проведите пальцем по клеткам, которые нужно исключить из детекции (дорога, деревья, телевизор). Исключённые клетки — красные.",
                style = MaterialTheme.typography.bodyMedium,
            )
            val aspect = image?.let { it.width.toFloat() / it.height } ?: (cols.toFloat() / rows * 9f / 9f)
            var paintValue by remember { mutableStateOf<Boolean?>(null) }
            Box(Modifier.fillMaxWidth().aspectRatio(aspect).background(Color.Black)) {
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(cols, rows) {
                            detectTapGestures { p ->
                                val i = cellAt(p, size, cols, rows) ?: return@detectTapGestures
                                cells = cells.copyOf().also { it[i] = !it[i] }
                                version++
                            }
                        }
                        .pointerInput(cols, rows) {
                            detectDragGestures(
                                onDragStart = { p ->
                                    val i = cellAt(p, size, cols, rows)
                                    if (i != null) {
                                        paintValue = !cells[i]
                                        cells = cells.copyOf().also { it[i] = paintValue!! }
                                    }
                                },
                                onDragEnd = { paintValue = null },
                                onDragCancel = { paintValue = null },
                            ) { change, _ ->
                                val i = cellAt(change.position, size, cols, rows) ?: return@detectDragGestures
                                val v = paintValue ?: return@detectDragGestures
                                if (cells[i] != v) cells = cells.copyOf().also { it[i] = v }
                            }
                        },
                ) {
                    image?.let { img ->
                        drawImage(img, srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height), dstSize = IntSize(size.width.toInt(), size.height.toInt()))
                    }
                    val cw = size.width / cols
                    val ch = size.height / rows
                    for (r in 0 until rows) for (c in 0 until cols) {
                        if (cells[r * cols + c]) {
                            drawRect(Color(0x99FF4D5E), Offset(c * cw, r * ch), Size(cw, ch))
                        }
                    }
                    for (c in 1 until cols) drawLine(Color.White.copy(alpha = 0.25f), Offset(c * cw, 0f), Offset(c * cw, size.height))
                    for (r in 1 until rows) drawLine(Color.White.copy(alpha = 0.25f), Offset(0f, r * ch), Offset(size.width, r * ch))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { cells = BooleanArray(cols * rows) }) { Text("Очистить") }
                OutlinedButton(onClick = { cells = BooleanArray(cols * rows) { !cells[it] } }) { Text("Инвертировать") }
            }
            Button(
                onClick = {
                    val mask = ZoneMask(cols, rows, cells)
                    App.settings.set(Keys.MOTION_ZONES, mask.format())
                    onClose()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Сохранить") }
        }
    }
}

private fun cellAt(p: Offset, size: IntSize, cols: Int, rows: Int): Int? {
    if (p.x < 0 || p.y < 0 || p.x >= size.width || p.y >= size.height) return null
    val c = (p.x / size.width * cols).toInt().coerceIn(0, cols - 1)
    val r = (p.y / size.height * rows).toInt().coerceIn(0, rows - 1)
    return r * cols + c
}
