package com.turnocontrol.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.turnocontrol.data.ImageImportException
import com.turnocontrol.data.ImageOcr
import com.turnocontrol.data.ImportedImage
import com.turnocontrol.domain.OcrDocument
import com.turnocontrol.domain.uprightCorrection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ImportState(
    val image: ImportedImage? = null,
    val document: OcrDocument? = null,
    val loading: Boolean = false,
    val message: String? = null,
    /** Clockwise degrees applied automatically from the recognized text angle; 0 when none. */
    val autoRotation: Int = 0,
)

@HiltViewModel
class ImportViewModel @Inject constructor(private val ocr: ImageOcr) : ViewModel() {
    private val mutable = MutableStateFlow(ImportState())
    val state = mutable.asStateFlow()
    private var job: Job? = null

    fun select(uri: Uri?) {
        if (uri == null) {
            mutable.value = mutable.value.copy(message = "Selección cancelada. No se ha cambiado la imagen.")
            return
        }
        job?.cancel()
        mutable.value = ImportState(loading = true, message = "Abriendo imagen…")
        job = viewModelScope.launch {
            try {
                val image = ocr.load(uri)
                ensureActive()
                mutable.value = ImportState(image = image, loading = true, message = "Reconociendo texto en el dispositivo…")
                var document = ocr.recognize(image)
                ensureActive()
                var shown = image
                var auto = 0
                val correction = document.uprightCorrection()
                if (correction != 0) {
                    mutable.value = ImportState(image = image, loading = true, message = "El texto aparece girado. Corrigiendo la orientación…")
                    val rotated = ocr.rotate(image, correction)
                    val again = ocr.recognize(rotated)
                    ensureActive()
                    // Keep the correction only if the text is now horizontal; otherwise the user decides manually.
                    if (again.uprightCorrection() == 0 && again.text.isNotBlank()) { shown = rotated; document = again; auto = correction }
                }
                mutable.value = ImportState(shown, document, autoRotation = auto, message = when {
                    document.text.isBlank() -> "No se ha detectado texto. Prueba una imagen más nítida, bien orientada y con mayor contraste, o gírala."
                    auto != 0 -> "Orientación corregida automáticamente (giro de $auto°). Si no es correcta, gira la imagen manualmente."
                    else -> null
                })
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                ensureActive()
                mutable.value = mutable.value.copy(loading = false, message = failure(exception))
            }
        }
    }

    /** Manual quarter turn; OCR runs again on the turned in-memory copy and no automatic correction is applied. */
    fun rotate(degrees: Int) {
        val current = state.value
        val image = current.image ?: return
        if (current.loading) return
        job?.cancel()
        mutable.value = ImportState(image = image, loading = true, message = "Girando la imagen y reconociendo de nuevo…")
        job = viewModelScope.launch {
            try {
                val rotated = ocr.rotate(image, degrees)
                ensureActive()
                mutable.value = ImportState(image = rotated, loading = true, message = "Reconociendo texto en el dispositivo…")
                val document = ocr.recognize(rotated)
                ensureActive()
                mutable.value = ImportState(rotated, document, message = if (document.text.isBlank())
                    "No se ha detectado texto con esta orientación. Prueba otro giro." else "Imagen girada. Texto reconocido de nuevo.")
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                ensureActive()
                // The previous image and its OCR stay available.
                mutable.value = current.copy(loading = false, message = failure(exception))
            }
        }
    }

    private fun failure(exception: Exception) = when (exception) {
        is ImageImportException -> exception.message
        is SecurityException -> "Ya no hay acceso a la imagen. Vuelve a seleccionarla."
        else -> "No se pudo leer la imagen o reconocer su texto. Vuelve a intentarlo con otra imagen."
    }

    fun clear() {
        job?.cancel()
        mutable.value = ImportState(message = "Imagen y texto descartados.")
    }
}
