package com.turnocontrol

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import com.turnocontrol.data.LocalImageOcr
import com.turnocontrol.data.ScheduleRepository
import com.turnocontrol.data.planned
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import androidx.compose.ui.test.SemanticsMatcher
import java.io.File

class OcrImportTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val directory get() = File(context.cacheDir, "ocr-fixtures").apply { mkdirs() }
    @Before fun setup() { Intents.init() }
    @After fun cleanup() {
        Intents.release()
        directory.listFiles()?.forEach { it.delete() }
    }

    private fun fixture(name: String = "synthetic.png", text: Boolean = true, width: Int = 1000, height: Int = 400): Uri {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        if (text) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 72f; typeface = Typeface.DEFAULT_BOLD }
            canvas.drawText("PRUEBA OCR", 60f, 140f, paint)
            canvas.drawText("ALFA BETA 123", 60f, 260f, paint)
        }
        val file = File(directory, name)
        file.outputStream().use { bitmap.compress(if (name.endsWith("jpg")) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 95, it) }
        bitmap.recycle()
        return FileProvider.getUriForFile(context, "${context.packageName}.ocr-fixtures", file)
    }

    /** Checks the recorded intents. Espresso's intended() also waits for window focus, which a headless emulator may never grant. */
    private fun assertLaunched(action: String) {
        val count = Intents.getIntents().count { it.action == action }
        assertEquals("El intent $action debe lanzarse exactamente una vez", 1, count)
    }

    private companion object {
        /** Ceiling only: waitUntil returns as soon as the OCR result is on screen. Cold ML Kit on a software-rendered emulator took ~35 s for a whole test. */
        const val OCR_TIMEOUT_MS = 90_000L
    }

    private fun openImport() {
        compose.onNodeWithContentDescription("Ajustes").performClick()
        compose.onNodeWithText("Importar horario").performScrollTo().performClick()
    }

    private fun lazyTo(matcher: SemanticsMatcher) { compose.onNode(hasScrollToIndexAction()).performScrollToNode(matcher) }
    private fun tapTag(tag: String) { lazyTo(hasTestTag(tag)); compose.onNodeWithTag(tag).performClick() }
    private fun inputTag(tag: String, value: String) { lazyTo(hasTestTag(tag)); compose.onNodeWithTag(tag).performTextReplacement(value) }
    private fun waitText(text: String, timeout: Long = 10000) = compose.waitUntil(timeout) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    @Test fun weeklyReportSupportsVisualWorkerSelectionAndExcludesVacations() {
        val bitmap = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 20f; typeface = Typeface.DEFAULT_BOLD }
        canvas.drawText("HORARIO POR TRABAJADOR", 30f, 60f, paint)
        canvas.drawText("TRABAJADOR", 20f, 150f, paint)
        repeat(7) { canvas.drawText("${10 + it}/03/2032", 250f + it * 180, 150f, paint) }
        canvas.drawText("Horas Proyectadas", 1490f, 150f, paint)
        val cellPaint = Paint(paint).apply { textSize = 18f }
        listOf("ALFA. S. (X123)", "BETA. S. (X124)", "GAMMA. S. (X125)").forEachIndexed { row, name ->
            val y = 250f + row * 150
            canvas.drawText(name, 20f, y, paint)
            repeat(7) { col ->
                val x = 250f + col * 180 + paint.measureText("${10 + col}/03/2032") / 2
                fun cell(text: String, baseline: Float) = canvas.drawText(text, x - cellPaint.measureText(text) / 2, baseline, cellPaint)
                when (col) {
                    0 -> { cell("08:15-12:00 (COC)", y - 17); cell("16:00-20:30 (GER)", y + 17) }
                    1, 6 -> cell("LIB", y)
                    2 -> cell("VAC", y)
                    3 -> cell("18:00-00:00 (Sala)", y)
                    4 -> cell("19:00-00:45 (Rep)", y)
                    else -> cell("09:00-13:00", y)
                }
            }
            canvas.drawText("40:00", 1490f, y, paint)
        }
        canvas.drawText("Horas en el Centro", 20f, 700f, paint)
        repeat(7) { canvas.drawText("60:00", 250f + it * 180 + (paint.measureText("${10 + it}/03/2032") - paint.measureText("60:00")) / 2, 700f, paint) }
        val file = File(directory, "synthetic-weekly-report.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.ocr-fixtures", file)
        Intents.intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(ActivityResult(Activity.RESULT_OK, Intent().setData(uri)))
        openImport()
        compose.onNodeWithText("Seleccionar archivo").performScrollTo().performClick()
        compose.waitUntil(OCR_TIMEOUT_MS) { compose.onAllNodes(hasText("Revisar turnos del trabajador")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Revisar turnos del trabajador").performScrollTo().performClick()
        compose.onNodeWithText("Trabajador: nombre completo").performScrollTo().performTextReplacement("SIN COINCIDENCIA")
        compose.onNodeWithText("Alias exacto (opcional)").performScrollTo().performTextClearance()
        compose.onNodeWithText("Interpretar trabajador").performScrollTo().performClick()
        waitText("Seleccionar otra fila o fotografía")
        compose.onNodeWithText("Seleccionar otra fila o fotografía").performScrollTo().performClick()
        compose.onNodeWithText("Seleccionar en fotografía").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Superficie de selección de fila").performTouchInput { click(androidx.compose.ui.geometry.Offset(width * .1f, height * 400f / 900f)) }
        compose.onNodeWithText("Confirmar fila seleccionada").assertIsEnabled().performClick()
        waitText("Fila seleccionada: BETA. S. (X124)")
        lazyTo(hasText("Día 7 · 2032-03-16"))
        compose.onNodeWithText("Día 7 · 2032-03-16").assertExists()
        // VAC starts excluded; every other detected day can be reviewed directly, without opening Editar.
        lazyTo(hasTestTag("include-2"))
        compose.onNode(hasTestTag("review-day-2") and hasAnyDescendant(hasText("Excluido"))).assertExists()
        listOf(0, 1, 3, 4, 5, 6).forEach { tapTag("review-$it") }
        lazyTo(hasText("Revisados 6 de 6 días incluidos"))
        val repository = ScheduleRepository(EntryPointAccessors.fromApplication(context, CalendarTestDatabase::class.java).database())
        val before = runBlocking { repository.snapshot() }
        tapTag("review-confirm")
        waitText("Se guardarán")
        compose.onNodeWithText("08:15–12:00 / 16:00–20:30", substring = true).assertExists()
        compose.onNodeWithText("2032-03-12").assertDoesNotExist() // the excluded vacation
        assertEquals(before, runBlocking { repository.snapshot() })
        compose.onNodeWithText("Volver a revisión").performScrollTo().performClick()
    }

    /** Clears [dates] in the isolated verification calendar and returns the rows to restore afterwards. */
    private fun isolate(repository: ScheduleRepository, dates: List<String>): List<com.turnocontrol.domain.PlannedDay> = runBlocking {
        val current = repository.snapshot()
        val originals = current.filter { it.day.date in dates }.map { it.planned() }
        if (originals.isNotEmpty()) repository.save(current, emptyList(), originals.map { it.date }.toSet())
        originals
    }
    private fun restore(repository: ScheduleRepository, dates: List<String>, originals: List<com.turnocontrol.domain.PlannedDay>) = runBlocking {
        val current = repository.snapshot()
        repository.save(current, originals, dates.map(java.time.LocalDate::parse).filter { d -> originals.none { it.date == d } && current.any { it.day.date == d.toString() } }.toSet())
    }

    @Test fun reviewWithoutEditingBlocksPendingDaysAndSavesOnlyAfterFinalConfirmation() {
        val bitmap = Bitmap.createBitmap(1100, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 34f; typeface = Typeface.DEFAULT_BOLD }
        canvas.drawText("13/10/2026", 320f, 110f, paint)
        canvas.drawText("14/10/2026", 750f, 110f, paint)
        canvas.drawText("ALFA", 40f, 260f, paint)
        canvas.drawText("08:00-16:00", 320f, 260f, paint)
        canvas.drawText("LIBRE", 750f, 260f, paint)
        canvas.drawText("BETA", 40f, 420f, paint)
        canvas.drawText("09:00-17:00", 320f, 420f, paint)
        canvas.drawText("LIBRE", 750f, 420f, paint)
        val file = File(directory, "synthetic-schedule.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.ocr-fixtures", file)
        Intents.intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(ActivityResult(Activity.RESULT_OK, Intent().setData(uri)))
        val repository = ScheduleRepository(EntryPointAccessors.fromApplication(context, CalendarTestDatabase::class.java).database())
        val dates = listOf("2026-10-13", "2026-10-14")
        val originals = isolate(repository, dates)
        try {
            fun interpretAlfa() {
                openImport()
                compose.onNodeWithText("Seleccionar archivo").performScrollTo().performClick()
                compose.waitUntil(OCR_TIMEOUT_MS) { compose.onAllNodes(hasText("Revisar turnos del trabajador")).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Revisar turnos del trabajador").performScrollTo().performClick()
                compose.onNodeWithText("Trabajador: nombre completo").performScrollTo().performTextReplacement("ALFA")
                compose.onNodeWithText("Alias exacto (opcional)").performScrollTo().performTextClearance()
                compose.onNodeWithText("Interpretar trabajador").performScrollTo().performClick()
                waitText("Fila seleccionada: ALFA")
            }
            interpretAlfa()
            val before = runBlocking { repository.snapshot() }
            // 1. A correct detection is reviewed directly.
            tapTag("review-0")
            compose.onNode(hasTestTag("review-day-0") and hasAnyDescendant(hasText("Revisado"))).assertExists()
            // 2. The pending day blocks saving and is named.
            tapTag("review-confirm")
            lazyTo(hasTestTag("review-message"))
            compose.onNodeWithText("Día 2 (2026-10-14): pendiente de revisar", substring = true).assertExists()
            assertEquals(before, runBlocking { repository.snapshot() })
            // 3. Editing is optional and never reviews by itself: turn day 2 into an overnight shift.
            tapTag("edit-1")
            tapTag("edit-free-1")
            inputTag("edit-start-1-0", "22:00")
            inputTag("edit-end-1-0", "02:00")
            tapTag("edit-apply-1")
            compose.onNode(hasTestTag("review-day-1") and hasAnyDescendant(hasText("Pendiente de revisar"))).assertExists()
            compose.activityRule.scenario.recreate()
            lazyTo(hasTestTag("review-day-1"))
            compose.onNode(hasTestTag("review-day-1") and hasAnyDescendant(hasText("Corregido manualmente"))).assertExists()
            compose.onNode(hasTestTag("review-day-0") and hasAnyDescendant(hasText("Revisado"))).assertExists()
            tapTag("review-1")
            tapTag("review-confirm")
            // 4. Final confirmation: counts, then an explicit dialog before any write.
            waitText("Se guardarán 2 día(s) con 2 turno(s)")
            assertEquals(before, runBlocking { repository.snapshot() })
            compose.onNode(hasText("Confirmar y guardar turnos") and hasClickAction()).performScrollTo().performClick()
            compose.onNodeWithText("¿Guardar los turnos revisados?").assertExists()
            assertEquals(before, runBlocking { repository.snapshot() })
            compose.onNodeWithText("Guardar").performClick()
            compose.waitUntil(10000) { compose.onAllNodes(hasText("Hoy")).fetchSemanticsNodes().isNotEmpty() }
            val saved = runBlocking { repository.snapshot() }.filter { it.day.date in dates }.associateBy { it.day.date }
            assertEquals(setOf("OCR_REVIEWED"), saved.values.map { it.day.origin }.toSet())
            assertEquals("08:00", saved.getValue("2026-10-13").planned().intervals.single().start.toString())
            assertEquals(1, saved.getValue("2026-10-14").planned().intervals.single().endDayOffset)
            // 5. Reimporting the same image: identical day is "Ya guardado", the edited one is a conflict.
            interpretAlfa()
            tapTag("review-0"); tapTag("review-1"); tapTag("review-confirm")
            waitText("Ya guardado")
            compose.onNodeWithText("Conflicto").assertExists()
            compose.onNodeWithText("Decide qué hacer", substring = true).assertExists()
            compose.onNode(hasText("Confirmar y guardar turnos") and hasClickAction()).assertIsNotEnabled()
            compose.onNodeWithText("Conservar el guardado").performScrollTo().performClick()
            compose.onNodeWithText("No hay cambios que guardar", substring = true).assertExists()
            compose.onNode(hasText("Confirmar y guardar turnos") and hasClickAction()).assertIsNotEnabled()
            assertEquals(saved.values.toList(), runBlocking { repository.snapshot() }.filter { it.day.date in dates })
            compose.onNodeWithText("Continuar en calendario manual").performScrollTo().performClick()
        } finally {
            restore(repository, dates, originals)
        }
        compose.onNodeWithContentDescription("Ajustes").performClick()
        compose.onNodeWithText("Perfil del trabajador").assertExists()
    }

    @Test fun documentPickerImportsImageAndRunsRealBundledOcr() {
        val uri = fixture()
        Intents.intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(ActivityResult(Activity.RESULT_OK, Intent().setData(uri)))
        openImport()
        compose.onNodeWithText("Seleccionar archivo").performScrollTo().performClick()
        compose.waitUntil(OCR_TIMEOUT_MS) { compose.onAllNodes(hasText("ALFA BETA 123", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Vista previa de la imagen seleccionada").assertExists()
        compose.onNodeWithText("PRUEBA", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("ALFA BETA 123", substring = true).assertExists()
        assertLaunched(Intent.ACTION_OPEN_DOCUMENT)
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("PRUEBA", substring = true).assertExists()
        compose.onNodeWithText("Descartar imagen y texto").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Vista previa de la imagen seleccionada").assertDoesNotExist()
        compose.onNodeWithText("Imagen y texto descartados.").assertExists()
    }

    @Test fun galleryResultImportsAndRecognizesImage() {
        val pickerIntent = ActivityResultContracts.PickVisualMedia().createIntent(context,
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        Intents.intending(hasAction(pickerIntent.action)).respondWith(ActivityResult(Activity.RESULT_OK, Intent().setData(fixture("gallery.png"))))
        openImport()
        compose.onNodeWithText("Seleccionar de galería").performClick()
        compose.waitUntil(OCR_TIMEOUT_MS) { compose.onAllNodes(hasText("ALFA BETA 123", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Vista previa de la imagen seleccionada").assertExists()
        assertLaunched(pickerIntent.action!!)
    }

    @Test fun galleryPickerCancellationAndUnreadableFileAreHandled() {
        val pickerIntent = ActivityResultContracts.PickVisualMedia().createIntent(context,
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        Intents.intending(hasAction(pickerIntent.action)).respondWith(ActivityResult(Activity.RESULT_CANCELED, null))
        openImport()
        compose.onNodeWithText("Seleccionar de galería").performClick()
        compose.onNodeWithText("Selección cancelada. No se ha cambiado la imagen.").assertExists()
        val file = File(directory, "corrupt.png").apply { writeText("Synthetic invalid image") }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.ocr-fixtures", file)
        Intents.intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(ActivityResult(Activity.RESULT_OK, Intent().setData(uri)))
        compose.onNodeWithText("Seleccionar archivo").performClick()
        compose.waitUntil(10000) { compose.onAllNodes(hasText("El archivo no es una imagen compatible o está dañado.")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Vista previa de la imagen seleccionada").assertDoesNotExist()
    }

    @Test fun localOcrRetainsHierarchyCoordinatesAndConfidenceWithoutNetworkPermissions() = runBlocking {
        val ocr = LocalImageOcr(context)
        val image = ocr.load(fixture())
        val document = ocr.recognize(image)
        // OCR can confuse O with 0; assert stable fragments, without changing the SDK result.
        assertTrue("OCR sintético: ${document.text}", document.text.contains("PRUEBA"))
        assertTrue(document.text.contains("ALFA BETA 123"))
        assertEquals(image.bitmap.width, document.width)
        assertEquals(image.bitmap.height, document.height)
        assertTrue(document.blocks.isNotEmpty())
        document.blocks.forEach { block ->
            assertNotNull(block.box)
            assertEquals(4, block.corners.size)
            assertNull(block.confidence)
            assertTrue(block.children.isNotEmpty())
            block.children.forEach { line ->
                assertNotNull(line.box)
                assertEquals(4, line.corners.size)
                assertTrue(line.confidence!! in 0f..1f)
                assertTrue(line.children.isNotEmpty())
                line.children.forEach { element -> assertNotNull(element.box); assertTrue(element.confidence!! in 0f..1f) }
            }
        }
        val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        listOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE", "android.permission.READ_EXTERNAL_STORAGE", "android.permission.READ_MEDIA_IMAGES", "android.permission.MANAGE_EXTERNAL_STORAGE").forEach { assertFalse(permissions.contains(it)) }
        assertTrue(ocr.recognize(ocr.load(fixture("blank.png", text = false))).text.isBlank())
    }

    @Test fun decoderBoundsMemoryAndAppliesExifOrientation() = runBlocking {
        val ocr = LocalImageOcr(context)
        val uri = fixture("rotated.jpg", width = 4000, height = 1000)
        ExifInterface(File(directory, "rotated.jpg")).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val image = ocr.load(uri)
        assertEquals(4000, image.sourceWidth)
        assertEquals(1000, image.sourceHeight)
        // 4 MP is within the 12 MiPx budget: kept at full resolution, axes swapped exactly once by EXIF.
        assertEquals(1000, image.bitmap.width)
        assertEquals(4000, image.bitmap.height)
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, image.exifOrientation)
        assertEquals(0, image.rotation)
        val turned = ocr.rotate(image, 90)
        assertEquals(4000, turned.bitmap.width)
        assertEquals(1000, turned.bitmap.height)
        assertEquals(90, turned.rotation)
        assertEquals(1000, image.bitmap.width) // The loaded image is not modified in place.
        val file = File(directory, "oversize.png")
        java.io.RandomAccessFile(file, "rw").use { it.setLength(LocalImageOcr.MAX_BYTES.toLong() + 1) }
        try {
            ocr.load(FileProvider.getUriForFile(context, "${context.packageName}.ocr-fixtures", file))
            fail("Oversize input should be rejected")
        } catch (expected: com.turnocontrol.data.ImageImportException) { assertTrue(expected.message!!.contains("20 MiB")) }
    }
}
