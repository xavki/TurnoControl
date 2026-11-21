package com.turnocontrol.ui

import android.graphics.Bitmap
import android.net.Uri
import com.turnocontrol.data.*
import com.turnocontrol.domain.OcrDocument
import com.turnocontrol.domain.OcrRegion
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
        /** Clockwise angle at which the text appears in the unrotated image; null means no line angles. */
        var contentAngle: Float? = null
        /** Simulates a correction that fails to straighten the text. */
        var rotationHelps = true
        val rotations = mutableListOf<Int>()
        var rotateError: Exception? = null
        override suspend fun load(uri: Uri): ImportedImage { loadError?.let { throw it }; return image }
        override suspend fun recognize(image: ImportedImage): OcrDocument {
            gate?.await()
            recognizeError?.let { throw it }
            val angle = contentAngle?.let { a -> if (rotationHelps) ((a + image.rotation + 180) % 360) - 180 else a }
            return OcrDocument(text, 2, 2, listOf(OcrRegion(text, null, emptyList(), "es", children = listOf(
                OcrRegion("LUNES 08:00-16:00 MARTES LIBRE MIERCOLES", null, emptyList(), "es", .9f, angle)))))
        }
        override suspend fun rotate(image: ImportedImage, degrees: Int): ImportedImage {
            rotations += degrees
            rotateError?.let { throw it }
            return image.copy(rotation = Math.floorMod(image.rotation + degrees, 360))
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

    @Test fun sidewaysTextIsRotatedAutomaticallyOnceAndReported() = runTest(dispatcher) {
        val fake = Fake(image).apply { contentAngle = 90f }
        val model = ImportViewModel(fake)
        model.select(uri); advanceUntilIdle()
        assertEquals(listOf(270), fake.rotations)
        assertEquals(270, model.state.value.image!!.rotation)
        assertEquals(270, model.state.value.autoRotation)
        assertTrue(model.state.value.message!!.contains("Orientación corregida"))
    }

    @Test fun uprightTextIsNotRotatedAndUnhelpfulCorrectionIsDiscarded() = runTest(dispatcher) {
        val upright = Fake(image).apply { contentAngle = 0f }
        ImportViewModel(upright).apply { select(uri); advanceUntilIdle() }
        assertTrue(upright.rotations.isEmpty())
        val stubborn = Fake(image).apply { contentAngle = 180f; rotationHelps = false }
        val model = ImportViewModel(stubborn)
        model.select(uri); advanceUntilIdle()
        assertEquals(listOf(180), stubborn.rotations)
        assertEquals(0, model.state.value.image!!.rotation)
        assertEquals(0, model.state.value.autoRotation)
    }

    @Test fun manualRotationRerunsOcrAndKeepsPreviousResultOnFailure() = runTest(dispatcher) {
        val fake = Fake(image)
        val model = ImportViewModel(fake)
        model.select(uri); advanceUntilIdle()
        model.rotate(90); advanceUntilIdle()
        assertEquals(90, model.state.value.image!!.rotation)
        assertNotNull(model.state.value.document)
        model.rotate(-90); advanceUntilIdle()
        assertEquals(0, model.state.value.image!!.rotation)
        assertEquals(listOf(90, -90), fake.rotations)
        val before = model.state.value
        fake.recognizeError = IllegalStateException("Synthetic failure")
        model.rotate(90); advanceUntilIdle()
        assertEquals(before.image, model.state.value.image)
        assertEquals(before.document, model.state.value.document)
        assertTrue(model.state.value.message!!.contains("No se pudo"))
    }

    @Test fun refusedAutomaticRotationKeepsTheOriginalImageAndOcr() = runTest(dispatcher) {
        val fake = Fake(image).apply { contentAngle = 90f; rotateError = ImageImportException("No hay memoria suficiente para girar la imagen.") }
        val model = ImportViewModel(fake)
        model.select(uri); advanceUntilIdle()
        assertNotNull(model.state.value.image)
        assertNotNull(model.state.value.document)
        assertEquals(0, model.state.value.image!!.rotation)
        assertEquals(0, model.state.value.autoRotation)
        assertTrue(model.state.value.message!!.contains("no se pudo corregir"))
        assertFalse(model.state.value.loading)
    }

    @Test fun refusedManualRotationKeepsTheCurrentImage() = runTest(dispatcher) {
        val fake = Fake(image)
        val model = ImportViewModel(fake)
        model.select(uri); advanceUntilIdle()
        val before = model.state.value
        fake.rotateError = ImageImportException("No hay memoria suficiente para girar la imagen. Se conserva la imagen actual.")
        model.rotate(90); advanceUntilIdle()
        assertEquals(before.image, model.state.value.image)
        assertEquals(before.document, model.state.value.document)
        assertTrue(model.state.value.message!!.contains("Se conserva"))
    }
}
