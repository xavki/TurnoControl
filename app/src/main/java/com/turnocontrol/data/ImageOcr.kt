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

data class ImportedImage(val bitmap: Bitmap, val sourceWidth: Int, val sourceHeight: Int, val exifOrientation: Int)
class ImageImportException(message: String) : IOException(message)

interface ImageOcr {
    suspend fun load(uri: Uri): ImportedImage
    suspend fun recognize(image: ImportedImage): OcrDocument
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
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight).toLong() > MAX_EDGE.toLong() * sample) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: throw ImageImportException("No se pudo decodificar la imagen.")
        val exif = bytes.inputStream().use { ExifInterface(it) }
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
        val matrix = Matrix().apply {
            setRotate(exif.rotationDegrees.toFloat())
            if (exif.isFlipped) postScale(-1f, 1f)
        }
        val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (oriented !== bitmap) bitmap.recycle()
        ImportedImage(oriented, bounds.outWidth, bounds.outHeight, orientation)
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
        const val MAX_EDGE = 2048
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
