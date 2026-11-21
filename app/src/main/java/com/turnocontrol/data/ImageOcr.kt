package com.turnocontrol.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.turnocontrol.domain.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/** [rotation] is the clockwise quarter turn applied after EXIF, by the user or by automatic correction. */
data class ImportedImage(val bitmap: Bitmap, val sourceWidth: Int, val sourceHeight: Int, val exifOrientation: Int, val rotation: Int = 0)
class ImageImportException(message: String) : IOException(message)

interface ImageOcr {
    suspend fun load(uri: Uri): ImportedImage
    suspend fun recognize(image: ImportedImage): OcrDocument
    /** Returns a new in-memory image turned clockwise; the selected file is never modified. */
    suspend fun rotate(image: ImportedImage, degrees: Int): ImportedImage
}

/**
 * Decode plan bounded by pixel count rather than by the long edge, so a portrait photo or a tall screenshot gets the
 * same pixel budget as a landscape one instead of being halved (glyph size still depends on how the table is framed). [sample] is the power-of-two decoder reduction and [scale]
 * the exact factor applied afterwards. Small sources (WhatsApp copies, screenshots) are enlarged 2× within the
 * budget: on the A53, ML Kit read 84 characters of a synthetic 1200 × 1600 table at native size and 539 after
 * this enlargement; cropping to the table without enlarging did not help.
 */
internal data class DecodePlan(val sample: Int, val scale: Float)

internal fun decodePlan(width: Int, height: Int, maxPixels: Long = LocalImageOcr.MAX_PIXELS, maxEdge: Int = LocalImageOcr.MAX_EDGE): DecodePlan {
    val pixels = width.toLong() * height
    if (pixels * 4 <= maxPixels && maxOf(width, height) * 2 <= maxEdge) return DecodePlan(1, 2f)
    val target = minOf(1.0, kotlin.math.sqrt(maxPixels.toDouble() / pixels), maxEdge.toDouble() / maxOf(width, height))
    var sample = 1
    // Smallest power-of-two reduction that still keeps 70 % of the target linear resolution; the decoded
    // bitmap is then at most about twice the pixel budget before the exact scale.
    while (1.0 / (sample * 2) >= target * .7) sample *= 2
    return DecodePlan(sample, minOf(1.0, target * sample).toFloat())
}

@Singleton
class LocalImageOcr @Inject constructor(@ApplicationContext private val context: Context) : ImageOcr {
    private val recognitionLock = Mutex()

    override suspend fun load(uri: Uri): ImportedImage = withContext(Dispatchers.IO) {
        if (uri.scheme != "content") throw ImageImportException("Selecciona una imagen con el selector de Android.")
        // Read at most 20 MiB, including providers which don't advertise a file size.
        val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                ensureActive()
                val count = stream.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_BYTES) throw ImageImportException("La imagen supera 20 MiB. Selecciona una más pequeña.")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: throw ImageImportException("No se pudo abrir la imagen. Vuelve a seleccionarla.")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw ImageImportException("El archivo no es una imagen compatible o está dañado.")
        val plan = decodePlan(bounds.outWidth, bounds.outHeight)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = plan.sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: throw ImageImportException("No se pudo decodificar la imagen.")
        ensureActive()
        val exif = bytes.inputStream().use { ExifInterface(it) }
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
        // Scale, EXIF rotation and mirroring in one pass: the decoder never applies EXIF itself, and the
        // resulting bitmap goes to ML Kit with rotation 0, so orientation is applied exactly once.
        val matrix = Matrix().apply {
            setScale(plan.scale, plan.scale)
            postRotate(exif.rotationDegrees.toFloat())
            if (exif.isFlipped) postScale(-1f, 1f)
        }
        val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (oriented !== bitmap) bitmap.recycle()
        ImportedImage(oriented, bounds.outWidth, bounds.outHeight, orientation)
    }

    override suspend fun rotate(image: ImportedImage, degrees: Int): ImportedImage = withContext(Dispatchers.Default) {
        val turn = Math.floorMod(degrees, 360)
        require(turn % 90 == 0) { "Only quarter turns are supported." }
        // The previous bitmap may still be on screen; it is left to the garbage collector instead of recycled.
        val rotated = if (turn == 0) image.bitmap else Bitmap.createBitmap(image.bitmap, 0, 0, image.bitmap.width, image.bitmap.height, Matrix().apply { setRotate(turn.toFloat()) }, true)
        image.copy(bitmap = rotated, rotation = (image.rotation + turn) % 360)
    }

    override suspend fun recognize(image: ImportedImage): OcrDocument = withContext(Dispatchers.IO) {
        recognitionLock.withLock {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                // Wait for native processing before releasing the recognizer, even when the UI cancels.
                val result = suspendCoroutine<Text> { continuation ->
                    recognizer.process(InputImage.fromBitmap(image.bitmap, 0))
                        .addOnSuccessListener { continuation.resume(it) }
                        .addOnFailureListener { continuation.resumeWithException(it) }
                }
                result.toDocument(image.bitmap.width, image.bitmap.height)
            } finally { recognizer.close() }
        }
    }

    companion object {
        const val MAX_BYTES = 20 * 1024 * 1024
        /** 12 MiPx (4096 × 3072): a 12 MP phone photo or any screenshot is kept at full resolution. */
        const val MAX_PIXELS = 4096L * 3072L
        const val MAX_EDGE = 8192
    }
}

private fun Rect?.toBox() = this?.let { OcrBox(left, top, right, bottom) }
private fun Array<Point>?.toCorners() = this?.map { OcrPoint(it.x, it.y) }.orEmpty()
internal fun Text.toDocument(width: Int, height: Int) = OcrDocument(text, width, height, textBlocks.map { block ->
    OcrRegion(block.text, block.boundingBox.toBox(), block.cornerPoints.toCorners(), block.recognizedLanguage,
        children = block.lines.map { line ->
            OcrRegion(line.text, line.boundingBox.toBox(), line.cornerPoints.toCorners(), line.recognizedLanguage,
                line.confidence, line.angle, line.elements.map { element ->
                    OcrRegion(element.text, element.boundingBox.toBox(), element.cornerPoints.toCorners(),
                        element.recognizedLanguage, element.confidence, element.angle)
                })
        })
})
