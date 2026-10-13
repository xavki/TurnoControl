package com.turnocontrol.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun ImportScreen(model: ImportViewModel, back: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    var pickerError by remember { mutableStateOf<String?>(null) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia(), model::select)
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), model::select)
    fun launchPicker(action: () -> Unit) {
        pickerError = null
        try { action() }
        catch (_: ActivityNotFoundException) { pickerError = "No hay un selector disponible. Prueba el otro botón." }
        catch (_: SecurityException) { pickerError = "Android no permite abrir el selector. Prueba el otro botón." }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Importar imagen · OCR", style = MaterialTheme.typography.headlineSmall)
        Text("Selecciona una imagen, incluidas las guardadas desde WhatsApp. El reconocimiento se realiza en este dispositivo; no interpreta horarios ni guarda turnos.")
        Button(enabled = !state.loading, onClick = { launchPicker { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } }) { Text("Seleccionar de galería") }
        OutlinedButton(enabled = !state.loading, onClick = { launchPicker { files.launch(arrayOf("image/*")) } }) { Text("Seleccionar archivo") }
        Text("Usa una imagen guardada en el dispositivo. Un proveedor de archivos en la nube puede descargarla al seleccionarla. TurnoControl no tiene acceso a Internet.")
        Text("Hasta 20 MiB. Las imágenes grandes se reducen a un máximo de 2048 píxeles por lado para limitar la memoria.")
        if (state.loading) CircularProgressIndicator()
        pickerError?.let { Text(it) }
        state.message?.let { Text(it) }
        state.image?.let { image ->
            Image(image.bitmap.asImageBitmap(), contentDescription = "Vista previa de la imagen seleccionada",
                modifier = Modifier.fillMaxWidth().height(280.dp), contentScale = ContentScale.Fit)
            Text("Vista previa: ${image.bitmap.width} × ${image.bitmap.height} px")
        }
        state.document?.let { document ->
            Text("Texto reconocido", style = MaterialTheme.typography.titleMedium)
            if (document.text.isNotBlank()) SelectionContainer { Text(document.text) }
            Text("${document.blocks.size} bloques · ${document.blocks.sumOf { it.children.size }} líneas. Coordenadas conservadas respecto a la vista previa.")
            Text("El OCR puede contener errores. Revisa el texto; no se han identificado personas ni creado turnos.")
        }
        if (state.loading || state.image != null || state.document != null) {
            OutlinedButton(onClick = { pickerError = null; model.clear() }) { Text(if (state.loading) "Cancelar reconocimiento" else "Descartar imagen y texto") }
        }
        Text("La imagen y el resultado son temporales: se pierden al cerrar el proceso de la aplicación.")
        TextButton(onClick = back) { Text("Volver al calendario") }
    }
}
