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
        compose.onNodeWithText("Calendario").performClick()
        compose.onNodeWithText("Importar imagen").performClick()
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
