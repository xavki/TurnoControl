package com.turnocontrol.data

import android.app.ActivityManager
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
 * same pixel budget as a landscape one instead of being halved (glyph size still depends on how the table is framed).
 * [sample] is the power-of-two decoder reduction and [scale] the exact factor applied afterwards. Small sources
 * (WhatsApp copies, screenshots) are enlarged 2× within the budget: on the A53, ML Kit read 84 characters of a
 * synthetic 1200 × 1600 table at native size and 539 after this enlargement; cropping without enlarging did not help.
 */
internal data class DecodePlan(val sample: Int, val scale: Float) {
    fun decodedPixels(width: Int, height: Int) = ceilDiv(width, sample).toLong() * ceilDiv(height, sample)
    fun outputPixels(width: Int, height: Int) = (ceilDiv(width, sample) * scale).toLong().coerceAtLeast(1) * (ceilDiv(height, sample) * scale).toLong().coerceAtLeast(1)
    private fun ceilDiv(value: Int, by: Int) = (value + by - 1) / by
}

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

/** Available memory as reported by Android; injectable so the limits can be tested. */
interface MemoryProbe {
    fun availableBytes(): Long
    fun lowRamDevice(): Boolean
}

/**
 * Picks the largest pixel budget whose estimated peak (encoded bytes + decoded bitmap + scaled bitmap, ARGB 4 B/px)
 * fits in a quarter of the memory Android reports as available. Returns null when even the minimum readable budget
 * does not fit: the image is then refused instead of risking an out-of-memory crash or a uselessly small bitmap.
 * With ample memory the result is exactly [decodePlan] (portrait and landscape behaviour unchanged).
 */
internal fun memoryAwarePlan(width: Int, height: Int, encodedBytes: Long, availableBytes: Long, lowRam: Boolean,
    maxPixels: Long = LocalImageOcr.MAX_PIXELS): DecodePlan? {
    var budget = if (lowRam) minOf(maxPixels, LocalImageOcr.LOW_RAM_MAX_PIXELS) else maxPixels
    val allowance = availableBytes / 4
    while (budget >= LocalImageOcr.MIN_PIXELS) {
        val plan = decodePlan(width, height, budget)
        if (encodedBytes + 4 * (plan.decodedPixels(width, height) + plan.outputPixels(width, height)) <= allowance) return plan
        budget /= 2
    }
    return null
}

private const val NO_MEMORY = "No hay memoria suficiente para procesar esta imagen de forma segura. Cierra otras aplicaciones o usa una foto con menos resolución."

@Singleton
class LocalImageOcr internal constructor(private val context: Context, private val memory: MemoryProbe) : ImageOcr {
    @Inject constructor(@ApplicationContext context: Context) : this(context, object : MemoryProbe {
        private val manager = context.getSystemService(ActivityManager::class.java)
        override fun availableBytes() = ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) }.let { if (it.lowMemory) it.availMem / 2 else it.availMem }
        override fun lowRamDevice() = manager.isLowRamDevice
    })
    private val recognitionLock = Mutex()

    private class Encoded(val bytes: ByteArray, val width: Int, val height: Int, val orientation: Int, val rotationDegrees: Int, val flipped: Boolean)

    private suspend fun read(uri: Uri): Encoded = withContext(Dispatchers.IO) {
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
        val exif = runCatching { bytes.inputStream().use { ExifInterface(it) } }.getOrNull()
        Encoded(bytes, bounds.outWidth, bounds.outHeight, exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED) ?: ExifInterface.ORIENTATION_UNDEFINED,
            exif?.rotationDegrees ?: 0, exif?.isFlipped ?: false)
    }

    /** The encoded bytes are only referenced inside this call, so they can be collected before the scaled copy. */
    private suspend fun decode(encoded: Encoded, plan: DecodePlan): Bitmap = withContext(Dispatchers.IO) {
        BitmapFactory.decodeByteArray(encoded.bytes, 0, encoded.bytes.size, BitmapFactory.Options().apply {
            inSampleSize = plan.sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: throw ImageImportException("No se pudo decodificar la imagen.")
    }

    override suspend fun load(uri: Uri): ImportedImage = withContext(Dispatchers.IO) {
        var encoded: Encoded? = read(uri)
        val width = encoded!!.width; val height = encoded.height
        val orientation = encoded.orientation; val degrees = encoded.rotationDegrees; val flipped = encoded.flipped
        val plan = memoryAwarePlan(width, height, encoded.bytes.size.toLong(), memory.availableBytes(), memory.lowRamDevice())
            ?: throw ImageImportException(NO_MEMORY)
        ensureActive()
        // The only places a large allocation happens. OutOfMemoryError is not treated as an ordinary failure:
        // it is caught exactly here, references are dropped, and one retry runs at half the budget.
        var used = plan
        val decoded = try { decode(encoded, plan) } catch (e: OutOfMemoryError) { null } ?: run {
            used = decodePlan(width, height, (plan.outputPixels(width, height) / 2).coerceAtLeast(MIN_PIXELS))
            try { decode(encoded!!, used) } catch (e: OutOfMemoryError) { null }
        } ?: throw ImageImportException(NO_MEMORY)
        encoded = null // Release up to 20 MiB before the scaled copy.
        ensureActive()
        val scale = used.scale
        // Scale, EXIF rotation and mirroring in one pass: the decoder never applies EXIF itself, and the
        // resulting bitmap goes to ML Kit with rotation 0, so orientation is applied exactly once.
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postRotate(degrees.toFloat())
            if (flipped) postScale(-1f, 1f)
        }
        val oriented = try { Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true) }
            catch (e: OutOfMemoryError) { decoded.recycle(); throw ImageImportException(NO_MEMORY) }
        if (oriented !== decoded) decoded.recycle()
        ImportedImage(oriented, width, height, orientation)
    }

    override suspend fun rotate(image: ImportedImage, degrees: Int): ImportedImage = withContext(Dispatchers.Default) {
        val turn = Math.floorMod(degrees, 360)
        require(turn % 90 == 0) { "Only quarter turns are supported." }
        if (turn == 0) return@withContext image
        // A turn needs a second full copy while the previous one is still on screen.
        if (image.bitmap.byteCount.toLong() > memory.availableBytes() / 4) throw ImageImportException("No hay memoria suficiente para girar la imagen. Se conserva la imagen actual.")
        // The previous bitmap may still be on screen; it is left to the garbage collector instead of recycled.
        val rotated = try { Bitmap.createBitmap(image.bitmap, 0, 0, image.bitmap.width, image.bitmap.height, Matrix().apply { setRotate(turn.toFloat()) }, true) }
            catch (e: OutOfMemoryError) { throw ImageImportException("No hay memoria suficiente para girar la imagen. Se conserva la imagen actual.") }
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
        /** Low-RAM devices (Android's own classification) get half the budget. */
        const val LOW_RAM_MAX_PIXELS = 6L * 1024 * 1024
        /** Below about 2 MiPx a full-width weekly table is no longer legible: refuse instead of producing bad OCR. */
        const val MIN_PIXELS = 2L * 1024 * 1024
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
