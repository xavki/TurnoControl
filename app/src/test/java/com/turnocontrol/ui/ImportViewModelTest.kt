package com.turnocontrol.ui

import android.graphics.Bitmap
import android.net.Uri
import com.turnocontrol.data.*
import com.turnocontrol.domain.OcrDocument
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ImportViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val image get() = ImportedImage(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888), 2, 2, 1)
    private val uri get() = Uri.parse("content://synthetic/image")
    private class Fake(private val image: ImportedImage) : ImageOcr {
        var loadError: Exception? = null
        var recognizeError: Exception? = null
        var gate: CompletableDeferred<Unit>? = null
        var text = "PRUEBA OCR"
        override suspend fun load(uri: Uri): ImportedImage { loadError?.let { throw it }; return image }
        override suspend fun recognize(image: ImportedImage): OcrDocument {
            gate?.await()
            recognizeError?.let { throw it }
            return OcrDocument(text, 2, 2, emptyList())
        }
    }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun previewIsAvailableWhileRecognizingThenResultArrives() = runTest(dispatcher) {
        val fake = Fake(image).apply { gate = CompletableDeferred() }
        val model = ImportViewModel(fake)
        model.select(uri)
        runCurrent()
        assertTrue(model.state.value.loading)
        assertNotNull(model.state.value.image)
        assertNull(model.state.value.document)
        fake.gate!!.complete(Unit)
        advanceUntilIdle()
        assertFalse(model.state.value.loading)
        assertEquals("PRUEBA OCR", model.state.value.document!!.text)
        assertNull(model.state.value.message)
    }

    @Test fun pickerCancellationPreservesPreviousResult() = runTest(dispatcher) {
        val model = ImportViewModel(Fake(image))
        model.select(uri); advanceUntilIdle()
        val previous = model.state.value.document
        model.select(null)
        assertEquals(previous, model.state.value.document)
        assertTrue(model.state.value.message!!.contains("cancelada"))
    }

    @Test fun clearCancelsPendingResultAndReleasesPreview() = runTest(dispatcher) {
        val fake = Fake(image).apply { gate = CompletableDeferred() }
        val model = ImportViewModel(fake)
        model.select(uri); runCurrent()
        model.clear()
        fake.gate!!.complete(Unit); advanceUntilIdle()
        assertNull(model.state.value.image)
        assertNull(model.state.value.document)
        assertFalse(model.state.value.loading)
    }

    @Test fun replacementDoesNotShowTextFromPreviousImage() = runTest(dispatcher) {
        val fake = Fake(image)
        val model = ImportViewModel(fake)
        model.select(uri); advanceUntilIdle()
        fake.loadError = ImageImportException("Imagen dañada")
        model.select(uri); advanceUntilIdle()
        assertNull(model.state.value.document)
        assertNull(model.state.value.image)
        assertEquals("Imagen dañada", model.state.value.message)
    }

    @Test fun emptyTextAndOcrFailureHaveHonestStates() = runTest(dispatcher) {
        val fake = Fake(image).apply { text = "" }
        val model = ImportViewModel(fake)
        model.select(uri); advanceUntilIdle()
        assertTrue(model.state.value.message!!.contains("No se ha detectado texto"))
        fake.recognizeError = IllegalStateException("Synthetic failure")
        model.select(uri); advanceUntilIdle()
        assertNotNull(model.state.value.image)
        assertNull(model.state.value.document)
        assertFalse(model.state.value.loading)
        assertTrue(model.state.value.message!!.contains("No se pudo"))
    }

    @Test fun lostUriPermissionIsExplained() = runTest(dispatcher) {
        val model = ImportViewModel(Fake(image).apply { loadError = SecurityException() })
        model.select(uri); advanceUntilIdle()
        assertFalse(model.state.value.loading)
        assertTrue(model.state.value.message!!.contains("Vuelve a seleccionarla"))
    }
}
