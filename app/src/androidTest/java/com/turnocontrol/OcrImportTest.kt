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

    private fun openImport() {
        compose.onNodeWithText("Ajustes").performClick()
        compose.onNodeWithText("Importar horario").performScrollTo().performClick()
    }

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
        compose.waitUntil(30000) { compose.onAllNodes(hasText("Revisar turnos del trabajador")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Revisar turnos del trabajador").performScrollTo().performClick()
        compose.onNodeWithText("Trabajador: nombre completo").performScrollTo().performTextReplacement("SIN COINCIDENCIA")
        compose.onNodeWithText("Alias exacto (opcional)").performScrollTo().performTextClearance()
        compose.onNodeWithText("Interpretar trabajador").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onAllNodes(hasText("Seleccionar otra fila o fotografía")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Seleccionar otra fila o fotografía").performScrollTo().performClick()
        compose.onNodeWithText("Seleccionar en fotografía").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Superficie de selección de fila").performTouchInput { click(androidx.compose.ui.geometry.Offset(width * .1f, height * 400f / 900f)) }
        compose.onNodeWithText("Confirmar fila seleccionada").assertIsEnabled().performClick()
        compose.waitUntil(10000) { compose.onAllNodes(hasText("Fila seleccionada: BETA. S. (X124)")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Día 7 · 2032-03-16"))
        compose.onNodeWithText("Día 7 · 2032-03-16").assertExists()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Revisar conflictos y confirmar"))
        compose.onNodeWithText("Revisar conflictos y confirmar").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onAllNodes(hasText("Propuesta 3: 2032-03-13")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Propuesta 7:", substring = true).assertDoesNotExist()
        compose.onNodeWithText("08:15-12:00 / 16:00-20:30", substring = true).assertExists()
    }

    @Test fun syntheticScheduleOcrRequiresReviewAndConfirmationBeforeSaving() {
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
        openImport()
        compose.onNodeWithText("Seleccionar archivo").performScrollTo().performClick()
        compose.waitUntil(30000) { compose.onAllNodes(hasText("Revisar turnos del trabajador")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Revisar turnos del trabajador").performScrollTo().performClick()
        compose.onNodeWithText("Trabajador: nombre completo").performScrollTo().performTextReplacement("ALFA")
        compose.onNodeWithText("Alias exacto (opcional)").performScrollTo().performTextClearance()
        compose.onNodeWithText("Interpretar trabajador").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onAllNodes(hasText("Fila seleccionada: ALFA")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Fecha 1 (AAAA-MM-DD)").performScrollTo().performTextReplacement("2026-10-15")
        compose.onNodeWithText("Intervalos 1 o LIBRE").performScrollTo().performTextReplacement("22:00-02:00")
        compose.onNodeWithText("Aplicar corrección al día 1").performScrollTo().performClick()
        compose.onNodeWithText("Corregido manualmente · provisional").assertExists()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Corregido manualmente · provisional").assertExists()
        val repository = ScheduleRepository(EntryPointAccessors.fromApplication(context, CalendarTestDatabase::class.java).database())
        val before = runBlocking { repository.snapshot() }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Revisar conflictos y confirmar"))
        compose.onNodeWithText("Revisar conflictos y confirmar").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onAllNodes(hasText("Propuesta 1: 2026-10-15")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(before, runBlocking { repository.snapshot() })
        try {
            compose.onAllNodes(hasText("Editar"))[0].performScrollTo().performClick()
            compose.onAllNodes(isToggleable())[0].performScrollTo().performClick()
            compose.onNodeWithText("Guardar días confirmados").performScrollTo().performClick()
            compose.waitUntil(10000) { compose.onAllNodes(hasText("Hoy")).fetchSemanticsNodes().isNotEmpty() }
            val saved = runBlocking { repository.snapshot() }.first { it.day.date == "2026-10-15" }
            assertEquals("OCR_REVIEWED", saved.day.origin)
            assertEquals(1, saved.planned().intervals.single().endDayOffset)
        } finally {
            // Restore the isolated test calendar instead of keeping synthetic OCR output.
            runBlocking {
                val current = repository.snapshot()
                val original = before.firstOrNull { it.day.date == "2026-10-15" }
                repository.save(current, listOfNotNull(original?.planned()), if (original == null) setOf(java.time.LocalDate.parse("2026-10-15")) else emptySet())
            }
        }
        compose.onNodeWithText("Ajustes").performClick()
        compose.onNodeWithText("TurnoControl · Ajustes").assertExists()
    }

    @Test fun documentPickerImportsImageAndRunsRealBundledOcr() {
        val uri = fixture()
        Intents.intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(ActivityResult(Activity.RESULT_OK, Intent().setData(uri)))
        openImport()
        compose.onNodeWithText("Seleccionar archivo").performScrollTo().performClick()
        compose.waitUntil(30000) { compose.onAllNodes(hasText("ALFA BETA 123", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Vista previa de la imagen seleccionada").assertExists()
        compose.onNodeWithText("PRUEBA", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("ALFA BETA 123", substring = true).assertExists()
        Intents.intended(hasAction(Intent.ACTION_OPEN_DOCUMENT))
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
        compose.waitUntil(30000) { compose.onAllNodes(hasText("ALFA BETA 123", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Vista previa de la imagen seleccionada").assertExists()
        Intents.intended(hasAction(pickerIntent.action))
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
        val uri = fixture("rotated.jpg", width = 3000, height = 1000)
        ExifInterface(File(directory, "rotated.jpg")).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val image = ocr.load(uri)
        assertEquals(3000, image.sourceWidth)
        assertEquals(1000, image.sourceHeight)
        assertEquals(500, image.bitmap.width)
        assertEquals(1500, image.bitmap.height)
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, image.exifOrientation)
        val file = File(directory, "oversize.png")
        java.io.RandomAccessFile(file, "rw").use { it.setLength(LocalImageOcr.MAX_BYTES.toLong() + 1) }
        try {
            ocr.load(FileProvider.getUriForFile(context, "${context.packageName}.ocr-fixtures", file))
            fail("Oversize input should be rejected")
        } catch (expected: com.turnocontrol.data.ImageImportException) { assertTrue(expected.message!!.contains("20 MiB")) }
    }
}
