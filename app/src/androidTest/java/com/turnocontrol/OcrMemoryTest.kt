package com.turnocontrol

import androidx.test.core.app.ApplicationProvider
import com.turnocontrol.data.ImageImportException
import com.turnocontrol.data.LocalImageOcr
import com.turnocontrol.data.MemoryProbe
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Real decoding with a simulated memory budget; synthetic fixtures only, no network, no real photographs. */
class OcrMemoryTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun ocr(available: Long, lowRam: Boolean = false) = LocalImageOcr(context, object : MemoryProbe {
        override fun availableBytes() = available
        override fun lowRamDevice() = lowRam
    })
    @After fun cleanup() { File(context.cacheDir, "ocr-fixtures").listFiles()?.forEach { it.delete() } }

    @Test fun lowMemoryLoadsAtLowerResolutionKeepingOrientation() = runBlocking {
        val uri = ScheduleFixtures.write(context, "low.jpg", ScheduleFixtures.scene(3000, 4000), 90)
        val image = ocr(160L * 1024 * 1024).load(uri)
        assertTrue(image.bitmap.height > image.bitmap.width) // portrait, EXIF applied once
        assertTrue(image.bitmap.width.toLong() * image.bitmap.height < 3000L * 4000)
        assertTrue(image.bitmap.width.toLong() * image.bitmap.height >= LocalImageOcr.MIN_PIXELS / 2)
    }

    @Test fun insufficientMemoryIsRefusedWithASpanishMessage() = runBlocking {
        val uri = ScheduleFixtures.write(context, "refused.jpg", ScheduleFixtures.scene(4000, 3000), 0)
        val error = runCatching { ocr(16L * 1024 * 1024).load(uri) }.exceptionOrNull()
        assertTrue(error is ImageImportException)
        assertTrue(error!!.message!!.contains("memoria"))
    }

    @Test fun rotationIsRefusedWhenASecondCopyDoesNotFit() = runBlocking {
        val uri = ScheduleFixtures.write(context, "rot.jpg", ScheduleFixtures.scene(4000, 3000), 0)
        val image = ocr(2L * 1024 * 1024 * 1024).load(uri)
        val error = runCatching { ocr(image.bitmap.byteCount.toLong()).rotate(image, 90) }.exceptionOrNull()
        assertTrue(error is ImageImportException)
        assertFalse(image.bitmap.isRecycled)
    }

    @Test fun repeatedImportsReleaseTheirIntermediateBitmaps() = runBlocking {
        val ocr = ocr(2L * 1024 * 1024 * 1024)
        val uri = ScheduleFixtures.write(context, "repeat.jpg", ScheduleFixtures.scene(4000, 3000), 90)
        repeat(5) { ocr.load(uri).bitmap.recycle() }
        val last = ocr.load(uri)
        assertEquals(4000 to 3000, last.bitmap.width to last.bitmap.height) // landscape scene, EXIF 90 undone once
    }
}
