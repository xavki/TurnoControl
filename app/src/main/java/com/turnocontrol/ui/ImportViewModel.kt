package com.turnocontrol.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.turnocontrol.data.ImageImportException
import com.turnocontrol.data.ImageOcr
import com.turnocontrol.data.ImportedImage
import com.turnocontrol.domain.OcrDocument
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
                val document = ocr.recognize(image)
                ensureActive()
                mutable.value = ImportState(image, document, message = if (document.text.isBlank())
                    "No se ha detectado texto. Prueba una imagen más nítida, bien orientada y con mayor contraste." else null)
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                ensureActive()
                mutable.value = mutable.value.copy(loading = false, message = when (exception) {
                    is ImageImportException -> exception.message
                    is SecurityException -> "Ya no hay acceso a la imagen. Vuelve a seleccionarla."
                    else -> "No se pudo leer la imagen o reconocer su texto. Vuelve a intentarlo con otra imagen."
                })
            }
        }
    }

    fun clear() {
        job?.cancel()
        mutable.value = ImportState(message = "Imagen y texto descartados.")
    }
}
