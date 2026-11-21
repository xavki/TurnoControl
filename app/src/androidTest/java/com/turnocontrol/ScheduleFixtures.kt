package com.turnocontrol

import android.content.Context
import android.graphics.*
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.turnocontrol.domain.*
import java.io.File
import java.time.LocalDate

/** Synthetic weekly report "photographed" in several framings. Deterministic; it does not prove real-photo accuracy. */
object ScheduleFixtures {
    const val WORKER = "BETA. S. (X124)"
    val firstDate: LocalDate = LocalDate.of(2032, 3, 10)

    /** Expected BETA row: split, free, vacation, two overnight, regular, free. */
    val expected: List<Pair<DayKind, String>> = listOf(
        DayKind.WORK to "08:15-12:00 / 16:00-20:30", DayKind.FREE to "LIBRE", DayKind.UNKNOWN to "VAC",
        DayKind.WORK to "18:00-24:00", DayKind.WORK to "19:00-00:45", DayKind.WORK to "09:00-13:00", DayKind.FREE to "LIBRE")

    /** Paper-space table, 2400 × 1350 px. Cell glyphs are 22 px before the "camera" scale is applied. */
    fun paper(): Bitmap {
        val bitmap = Bitmap.createBitmap(2400, 1350, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 24f; typeface = Typeface.DEFAULT_BOLD }
        val cellPaint = Paint(paint).apply { textSize = 22f }
        canvas.drawText("HORARIO POR TRABAJADOR", 45f, 90f, paint)
        canvas.drawText("TRABAJADOR", 30f, 225f, paint)
        repeat(7) { canvas.drawText("${10 + it}/03/2032", 375f + it * 270, 225f, paint) }
        canvas.drawText("Horas Proyectadas", 2235f, 225f, paint)
        listOf("ALFA. S. (X123)", WORKER, "GAMMA. S. (X125)").forEachIndexed { row, name ->
            val y = 375f + row * 225
            canvas.drawText(name, 30f, y, paint)
            repeat(7) { col ->
                val x = 375f + col * 270 + paint.measureText("${10 + col}/03/2032") / 2
                fun cell(text: String, baseline: Float) = canvas.drawText(text, x - cellPaint.measureText(text) / 2, baseline, cellPaint)
                when (col) {
                    0 -> { cell("08:15-12:00 (COC)", y - 25); cell("16:00-20:30 (GER)", y + 25) }
                    1, 6 -> cell("LIB", y)
                    2 -> cell("VAC", y)
                    3 -> cell("18:00-00:00 (Sala)", y)
                    4 -> cell("19:00-00:45 (Rep)", y)
                    else -> cell("09:00-13:00", y)
                }
            }
            canvas.drawText("40:00", 2235f, y, paint)
        }
        canvas.drawText("Horas en el Centro", 30f, 1050f, paint)
        repeat(7) { canvas.drawText("60:00", 375f + it * 270 + (paint.measureText("${10 + it}/03/2032") - paint.measureText("60:00")) / 2, 1050f, paint) }
        return bitmap
    }

    /** Scene as the viewer sees it: paper placed on a grey desk filling [fill] of the frame width. */
    fun scene(width: Int, height: Int, fill: Float = .92f): Bitmap {
        val paper = paper()
        val scene = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(scene).apply { drawColor(Color.rgb(150, 150, 145)) }
        val w = width * fill
        val h = w * paper.height / paper.width
        val left = (width - w) / 2
        val top = (height - h) / 2
        canvas.drawBitmap(paper, null, RectF(left, top, left + w, top + h), Paint(Paint.FILTER_BITMAP_FLAG))
        paper.recycle()
        return scene
    }

    fun rotate(bitmap: Bitmap, degrees: Int): Bitmap =
        if (degrees % 360 == 0) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { setRotate(degrees.toFloat()) }, true)

    /**
     * Writes [displayed] as a camera would: pixels stored rotated by -[exifDegrees] with an EXIF tag that rotates them back.
     * exifDegrees = null writes no orientation tag at all (screenshots, messaging apps that strip metadata).
     */
    fun write(context: Context, name: String, displayed: Bitmap, exifDegrees: Int?, quality: Int = 92): Uri {
        val directory = File(context.cacheDir, "ocr-fixtures").apply { mkdirs() }
        val stored = rotate(displayed, -(exifDegrees ?: 0))
        val file = File(directory, name)
        file.outputStream().use { stored.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        if (stored !== displayed) stored.recycle()
        if (exifDegrees != null) ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, when (exifDegrees) {
                90 -> ExifInterface.ORIENTATION_ROTATE_90; 180 -> ExifInterface.ORIENTATION_ROTATE_180
                270 -> ExifInterface.ORIENTATION_ROTATE_270; else -> ExifInterface.ORIENTATION_NORMAL
            }.toString())
            saveAttributes()
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.ocr-fixtures", file)
    }

    /** Number of the seven BETA days whose date and interpretation match the synthetic source exactly. */
    fun correctDays(result: ScheduleResult): Int = result.days.withIndex().count { (index, day) ->
        val (kind, text) = expected.getOrNull(index) ?: return@count false
        day.date == firstDate.plusDays(index.toLong()) && day.kind == kind && day.reviewText() == text
    }
}
