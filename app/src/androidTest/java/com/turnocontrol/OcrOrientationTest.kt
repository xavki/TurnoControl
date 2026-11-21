package com.turnocontrol

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.turnocontrol.data.LocalImageOcr
import com.turnocontrol.domain.*
import com.turnocontrol.ui.ImportState
import com.turnocontrol.ui.ImportViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Real ML Kit on synthetic, deterministic fixtures. It proves orientation and resolution handling,
 * not accuracy on real photographs. One OCR glyph confusion (e.g. COC read as C0C) is tolerated: the
 * review screen marks such a day as a validation error instead of guessing.
 */
class OcrOrientationTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val ocr by lazy { LocalImageOcr(context) }
    @After fun cleanup() { File(context.cacheDir, "ocr-fixtures").listFiles()?.forEach { it.delete() } }

    private fun interpret(document: OcrDocument, row: Int? = null) = WorkerRowsInterpreter().interpret(document, WorkerSelection(ScheduleFixtures.WORKER, confirmedRowId = row))

    /** Runs the same import pipeline as the app (load, OCR, automatic upright correction) through ImportViewModel. */
    private fun imported(uri: Uri): ImportState = runBlocking {
        lateinit var model: ImportViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync { model = ImportViewModel(ocr); model.select(uri) }
        withTimeout(90_000) { model.state.first { !it.loading && it.document != null } }
    }

    @Test fun portraitAndLandscapePhotosOfTheSameTableGiveEquivalentShifts() {
        val landscape = imported(ScheduleFixtures.write(context, "l.jpg", ScheduleFixtures.scene(4000, 3000), 0))
        val portrait = imported(ScheduleFixtures.write(context, "p.jpg", ScheduleFixtures.scene(3000, 4000), 90))
        val portraitNoExif = imported(ScheduleFixtures.write(context, "pn.jpg", ScheduleFixtures.scene(3000, 4000), null))
        assertEquals(4000 to 3000, landscape.image!!.bitmap.width to landscape.image!!.bitmap.height)
        assertEquals(3000 to 4000, portrait.image!!.bitmap.width to portrait.image!!.bitmap.height)
        assertEquals(3000 to 4000, portraitNoExif.image!!.bitmap.width to portraitNoExif.image!!.bitmap.height)
        for ((name, state) in listOf("landscape" to landscape, "portrait" to portrait, "portrait-noexif" to portraitNoExif)) {
            assertEquals("$name auto rotation", 0, state.autoRotation)
            val result = interpret(state.document!!)
            assertTrue("$name: ${result.issues} ${result.days.map { it.reviewText() }}", ScheduleFixtures.correctDays(result) >= 6)
        }
    }

    @Test fun exifRotationsAreAppliedExactlyOnce() {
        for (degrees in listOf(90, 180, 270)) {
            val state = imported(ScheduleFixtures.write(context, "e$degrees.jpg", ScheduleFixtures.scene(4000, 3000), degrees))
            assertEquals("EXIF $degrees size", 4000 to 3000, state.image!!.bitmap.width to state.image!!.bitmap.height)
            assertEquals("EXIF $degrees must not trigger a second rotation", 0, state.autoRotation)
            assertTrue("EXIF $degrees", ScheduleFixtures.correctDays(interpret(state.document!!)) >= 6)
        }
    }

    @Test fun sidewaysAndUpsideDownContentIsCorrectedAutomatically() {
        for ((turn, correction) in listOf(90 to 270, 270 to 90, 180 to 180)) {
            val state = imported(ScheduleFixtures.write(context, "s$turn.jpg", ScheduleFixtures.rotate(ScheduleFixtures.scene(4000, 3000), turn), null))
            assertEquals("content turned $turn", correction, state.autoRotation)
            assertEquals(4000, state.image!!.bitmap.width)
            assertTrue("content turned $turn", ScheduleFixtures.correctDays(interpret(state.document!!)) >= 6)
        }
    }

    @Test fun manualRotationRerunsOcrWithoutTouchingTheFile() = runBlocking {
        val uri = ScheduleFixtures.write(context, "m.jpg", ScheduleFixtures.scene(4000, 3000), 0)
        val file = File(context.cacheDir, "ocr-fixtures/m.jpg")
        val bytes = file.readBytes()
        lateinit var model: ImportViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync { model = ImportViewModel(ocr); model.select(uri) }
        withTimeout(90_000) { model.state.first { !it.loading && it.document != null } }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { model.rotate(90) }
        val turned = withTimeout(90_000) { model.state.first { !it.loading && it.image?.rotation == 90 && it.document != null } }
        assertEquals(3000 to 4000, turned.image!!.bitmap.width to turned.image!!.bitmap.height)
        assertEquals(0, ScheduleFixtures.correctDays(interpret(turned.document!!))) // sideways text is not interpreted
        InstrumentationRegistry.getInstrumentation().runOnMainSync { model.rotate(-90) }
        val back = withTimeout(90_000) { model.state.first { !it.loading && it.image?.rotation == 0 && it.document != null } }
        assertTrue(ScheduleFixtures.correctDays(interpret(back.document!!)) >= 6)
        assertArrayEquals("The selected file is never rewritten", bytes, file.readBytes())
    }

    @Test fun smallPortraitCopiesAndScreenshotsAreEnlargedBeforeOcr() {
        val whatsapp = imported(ScheduleFixtures.write(context, "w.jpg", ScheduleFixtures.scene(1200, 1600), null, 75))
        assertEquals(2400 to 3200, whatsapp.image!!.bitmap.width to whatsapp.image!!.bitmap.height)
        val result = interpret(whatsapp.document!!)
        // Exact name matching may fail on OCR punctuation; the manual row choice must then recover the week.
        val row = result.candidates.firstOrNull { it.label.contains("X124") }?.id
        val days = if (ScheduleFixtures.correctDays(result) >= 6) ScheduleFixtures.correctDays(result) else ScheduleFixtures.correctDays(interpret(whatsapp.document!!, row))
        assertTrue("whatsapp portrait: candidates=${result.candidates.map { it.label }}", days >= 6)
        val screenshot = imported(ScheduleFixtures.write(context, "ss.jpg", ScheduleFixtures.scene(1080, 2340, 1f), null, 95))
        assertEquals(2160 to 4680, screenshot.image!!.bitmap.width to screenshot.image!!.bitmap.height)
    }
}
