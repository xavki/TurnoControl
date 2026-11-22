package com.turnocontrol.data

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.turnocontrol.domain.OcrDocument
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/** The OCR recognition boundary: what crosses it, serialization and error propagation. ML Kit itself is not run here. */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class OcrEngineBoundaryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val memory = object : MemoryProbe { override fun availableBytes() = 512L * 1024 * 1024; override fun lowRamDevice() = false }
    private fun image() = ImportedImage(Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888), 64, 48, 1)

    @Test fun productionStillUsesTheInProcessMlKitEngine() {
        val field = LocalImageOcr::class.java.getDeclaredField("engine").apply { isAccessible = true }
        assertSame(MlKitTextRecognitionEngine, field.get(LocalImageOcr(context)))
    }

    @Test fun onlyTheProcessedBitmapCrossesTheBoundaryAndTheResultComesBackUnchanged() = runBlocking {
        val seen = mutableListOf<Bitmap>()
        val document = OcrDocument("LUNES 08:00-16:00", 64, 48, emptyList())
        val ocr = LocalImageOcr(context, memory, object : TextRecognitionEngine {
            override suspend fun recognize(bitmap: Bitmap): OcrDocument { seen += bitmap; return document }
        })
        val input = image()
        assertSame(document, ocr.recognize(input))
        assertEquals(listOf(input.bitmap), seen)
    }

    @Test fun recognitionsAreSerializedAndFailuresAreNotHidden() = runBlocking {
        val running = AtomicInteger(); var peak = 0
        val ocr = LocalImageOcr(context, memory, object : TextRecognitionEngine {
            override suspend fun recognize(bitmap: Bitmap): OcrDocument {
                peak = maxOf(peak, running.incrementAndGet()); delay(20); running.decrementAndGet()
                return OcrDocument("", bitmap.width, bitmap.height, emptyList())
            }
        })
        (1..4).map { async { ocr.recognize(image()) } }.awaitAll()
        assertEquals(1, peak)
        val failing = LocalImageOcr(context, memory, object : TextRecognitionEngine {
            override suspend fun recognize(bitmap: Bitmap): OcrDocument = throw IllegalStateException("motor no disponible")
        })
        assertEquals("motor no disponible", assertThrows(IllegalStateException::class.java) { runBlocking { failing.recognize(image()) } }.message)
    }
}
