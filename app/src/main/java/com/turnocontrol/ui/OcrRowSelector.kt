package com.turnocontrol.ui

import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.turnocontrol.domain.*
import kotlin.math.*

@Composable
fun OcrRowSelector(image: Bitmap?, width: Int, height: Int, candidates: List<WorkerRowCandidate>, loading: Boolean,
    choose: (Int) -> Unit, manual: (ManualRowBand) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var photo by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var manualMode by remember { mutableStateOf(false) }
    var pendingId by remember(candidates) { mutableStateOf<Int?>(null) }
    var band by remember { mutableStateOf<ManualRowBand?>(null) }
    OutlinedButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Ocultar selección de fila" else "Seleccionar otra fila o fotografía") }
    if (!expanded) return
    Text("Elige una fila de la lista o tócala en la imagen. La selección siempre necesita confirmación.")
    candidates.forEach { row ->
        OutlinedButton(onClick = { pendingId = row.id; band = null }, enabled = !loading) { Text("Elegir fila ${row.id + 1}: ${row.label}") }
    }
    if (image != null && width > 0 && height > 0) {
        OutlinedButton(onClick = { photo = true; pendingId = null; band = null }) { Text("Seleccionar en fotografía") }
    }
    if (photo && image != null && width > 0 && height > 0) {
        AlertDialog(onDismissRequest = { photo = false }, title = { Text("Seleccionar fila en fotografía") }, text = {
        Column {
        Text("Ampliación")
        Slider(value = zoom, onValueChange = { zoom = it }, valueRange = 1f..4f)
        Row { Checkbox(manualMode, { manualMode = it; pendingId = null; band = null }); Text("Delimitar banda manual") }
        if (manualMode) Text("Arrastra desde el borde superior al inferior de la fila, junto a los nombres. Si incluye otra fila, corrige la banda antes de confirmar.")
        BoxWithConstraints(Modifier.fillMaxWidth().height(320.dp)) {
            val imageWidth = maxWidth * zoom
            val imageHeight = imageWidth * height.toFloat() / width
            Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).verticalScroll(rememberScrollState())) {
                Box(Modifier.width(imageWidth).height(imageHeight)) {
                    Image(image.asImageBitmap(), contentDescription = "Fotografía para seleccionar fila", modifier = Modifier.fillMaxSize())
                    Canvas(Modifier.matchParentSize().semantics { contentDescription = "Superficie de selección de fila" }.pointerInput(candidates, manualMode, width, height) {
                        if (manualMode) {
                            var start = 0
                            detectDragGestures(onDragStart = { start = (it.y / size.height * height).roundToInt() }, onDrag = { change, _ ->
                                change.consume()
                                val end = (change.position.y / size.height * height).roundToInt().coerceIn(0, height)
                                band = ManualRowBand(min(start, end), max(start, end)); pendingId = null
                            })
                        } else detectTapGestures { position ->
                            val x = position.x / size.width * width
                            val y = position.y / size.height * height
                            pendingId = candidates.firstOrNull { row ->
                                val points = row.corners
                                if (points.size == 4) {
                                    val fraction = (x - points[0].x) / (points[1].x - points[0].x).coerceAtLeast(1)
                                    val top = points[0].y + fraction * (points[1].y - points[0].y)
                                    val bottom = points[3].y + fraction * (points[2].y - points[3].y)
                                    fraction in 0f..1f && y >= top && y < bottom
                                } else y >= row.top && y < row.bottom
                            }?.id
                            band = null
                        }
                    }) {
                        val sx = size.width / width
                        val sy = size.height / height
                        candidates.forEach { row ->
                            val color = if (pendingId == row.id) Color.Cyan else Color.Yellow
                            if (row.corners.size == 4) {
                                val path = Path().apply { row.corners.forEachIndexed { i, p -> if (i == 0) moveTo(p.x * sx, p.y * sy) else lineTo(p.x * sx, p.y * sy) }; close() }
                                drawPath(path, color.copy(alpha = if (pendingId == row.id) .22f else .04f))
                                drawPath(path, color, style = Stroke(if (pendingId == row.id) 4f else 1f))
                            } else drawRect(color, Offset(0f, row.top * sy), androidx.compose.ui.geometry.Size(size.width, (row.bottom - row.top) * sy), style = Stroke(2f))
                        }
                        band?.let { drawRect(Color.Cyan.copy(alpha = .3f), Offset(0f, it.top * sy), androidx.compose.ui.geometry.Size(size.width, (it.bottom - it.top) * sy)) }
                    }
                }
            }
        }
        pendingId?.let { Text("Fila seleccionada: ${candidates.firstOrNull { row -> row.id == it }?.label.orEmpty()}") }
        }
        }, confirmButton = {
            TextButton(onClick = { pendingId?.let(choose); band?.let(manual); photo = false }, enabled = !loading && (pendingId != null || band?.let { it.bottom > it.top } == true)) { Text("Confirmar fila seleccionada") }
        }, dismissButton = { TextButton(onClick = { photo = false }) { Text("Cancelar selección") } })
    }
    if (!photo) pendingId?.let { id ->
        Text("Fila pendiente de confirmar: ${candidates.firstOrNull { it.id == id }?.label.orEmpty()}")
        Button(onClick = { choose(id) }, enabled = !loading) { Text("Confirmar fila seleccionada") }
    }
    band?.let { selected -> Button(onClick = { manual(selected) }, enabled = !loading && selected.bottom > selected.top) { Text("Confirmar banda manual") } }
    Text("Si faltan coordenadas o la banda no es precisa, usa la lista o añade días manualmente. No se inventarán horas.")
}
