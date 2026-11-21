package com.turnocontrol

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.turnocontrol.data.LocalImageOcr
import com.turnocontrol.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/** Measurement only: records how each framing behaves. Assertions live in OcrOrientationTest. */
class OrientationDiagnosticTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun measurePortraitLandscapeAndRotations(): Unit = runBlocking {
        val ocr = LocalImageOcr(context)
        val report = StringBuilder("variant;bitmap;textChars;medianElementHeight;medianLineAngle;layout;issues;correctDays;autoRotation;ms\n")
        fun run(name: String, uri: android.net.Uri) {
            val started = System.currentTimeMillis()
            var image = runBlocking { ocr.load(uri) }
            var document = runBlocking { ocr.recognize(image) }
            // Same policy as ImportViewModel.select.
            val correction = document.uprightCorrection()
            var auto = 0
            if (correction != 0) {
                val rotated = runBlocking { ocr.rotate(image, correction) }
                val again = runBlocking { ocr.recognize(rotated) }
                if (again.uprightCorrection() == 0 && again.text.isNotBlank()) { image = rotated; document = again; auto = correction }
            }
            val elapsed = System.currentTimeMillis() - started
            val elements = document.blocks.flatMap { it.children }.flatMap { it.children }
            val heights = elements.mapNotNull { it.box?.let { b -> b.bottom - b.top } }.sorted()
            val angles = document.blocks.flatMap { it.children }.mapNotNull { it.angleDegrees }.sorted()
            val result = WorkerRowsInterpreter().interpret(document, WorkerSelection(ScheduleFixtures.WORKER))
            val line = "$name;${image.bitmap.width}x${image.bitmap.height};${document.text.length};${heights.getOrNull(heights.size / 2)};" +
                "${angles.getOrNull(angles.size / 2)};${result.layout};${result.issues};${ScheduleFixtures.correctDays(result)};$auto;$elapsed"
            Log.i("TurnoDiag", line)
            report.appendLine(line)
            result.days.forEachIndexed { i, d ->
                val exp = ScheduleFixtures.expected.getOrNull(i)
                if (exp == null || d.kind != exp.first || d.reviewText() != exp.second) report.appendLine("  day$i: '${d.sourceText.replace("\n", " | ")}' -> ${d.kind} ${d.reviewText()} ${d.issues}")
            }
            // Experiment: does a 2x upscale help ML Kit on small sources?
            if (image.bitmap.width * image.bitmap.height < 3_000_000) {
                val up = android.graphics.Bitmap.createScaledBitmap(image.bitmap, image.bitmap.width * 2, image.bitmap.height * 2, true)
                val upDoc = runBlocking { ocr.recognize(image.copy(bitmap = up)) }
                val upResult = WorkerRowsInterpreter().interpret(upDoc, WorkerSelection(ScheduleFixtures.WORKER))
                report.appendLine("$name-upscaled2x;${up.width}x${up.height};${upDoc.text.length};;;${upResult.layout};${upResult.issues};${ScheduleFixtures.correctDays(upResult)};;")
                report.appendLine("  candidates: ${upResult.candidates.map { it.label }}")
                up.recycle()
            }
            image.bitmap.recycle()
        }
        fun scene(w: Int, h: Int, fill: Float = .92f) = ScheduleFixtures.scene(w, h, fill)
        run("landscape-12mp-exif0", ScheduleFixtures.write(context, "l.jpg", scene(4000, 3000), 0))
        run("portrait-12mp-exif90", ScheduleFixtures.write(context, "p.jpg", scene(3000, 4000), 90))
        run("portrait-12mp-noexif", ScheduleFixtures.write(context, "pn.jpg", scene(3000, 4000), null))
        run("landscape-12mp-exif180", ScheduleFixtures.write(context, "l180.jpg", scene(4000, 3000), 180))
        run("landscape-12mp-exif270", ScheduleFixtures.write(context, "l270.jpg", scene(4000, 3000), 270))
        run("sideways-cw-noexif", ScheduleFixtures.write(context, "s90.jpg", ScheduleFixtures.rotate(scene(4000, 3000), 90), null))
        run("sideways-ccw-noexif", ScheduleFixtures.write(context, "s270.jpg", ScheduleFixtures.rotate(scene(4000, 3000), 270), null))
        run("upside-down-noexif", ScheduleFixtures.write(context, "u.jpg", ScheduleFixtures.rotate(scene(4000, 3000), 180), null))
        run("whatsapp-portrait-1200x1600", ScheduleFixtures.write(context, "wp.jpg", scene(1200, 1600), null, 75))
        run("whatsapp-landscape-1600x1200", ScheduleFixtures.write(context, "wl.jpg", scene(1600, 1200), null, 75))
        run("screenshot-1080x2340", ScheduleFixtures.write(context, "ss.jpg", scene(1080, 2340, 1f), null, 95))
        // Discriminating experiment: crop small portrait frames to the paper, OCR without any enlargement.
        fun direct(name: String, bitmap: android.graphics.Bitmap) {
            val doc = runBlocking { ocr.recognize(com.turnocontrol.data.ImportedImage(bitmap, bitmap.width, bitmap.height, 0)) }
            val auto = WorkerRowsInterpreter().interpret(doc, WorkerSelection(ScheduleFixtures.WORKER))
            val row = auto.candidates.firstOrNull { it.label.contains("X124") }?.id
            val manual = row?.let { WorkerRowsInterpreter().interpret(doc, WorkerSelection(ScheduleFixtures.WORKER, confirmedRowId = it)) }
            report.appendLine("$name;${bitmap.width}x${bitmap.height};${doc.text.length};;;${auto.layout};${auto.issues};${ScheduleFixtures.correctDays(auto)};manualRow=${manual?.let(ScheduleFixtures::correctDays)};")
        }
        fun crop(w: Int, h: Int, fill: Float): android.graphics.Bitmap {
            val s = ScheduleFixtures.scene(w, h, fill)
            val pw = (w * fill).toInt(); val ph = pw * 1350 / 2400
            return android.graphics.Bitmap.createBitmap(s, (w - pw) / 2, (h - ph) / 2, pw, ph)
        }
        direct("whatsapp-portrait-frame-noscale", ScheduleFixtures.scene(1200, 1600))
        direct("whatsapp-portrait-cropped-noscale", crop(1200, 1600, .92f))
        direct("screenshot-cropped-noscale", crop(1080, 2340, 1f))
        ScheduleFixtures.scene(1200, 1600).let { direct("whatsapp-portrait-frame-2x", android.graphics.Bitmap.createScaledBitmap(it, 2400, 3200, true)) }
        crop(1080, 2340, 1f).let { direct("screenshot-cropped-2x", android.graphics.Bitmap.createScaledBitmap(it, it.width * 2, it.height * 2, true)) }
        // Peak-memory check: a 64 MP (9248 × 6936) source must load within the pixel budget.
        val big = ScheduleFixtures.write(context, "64mp.jpg", scene(6936, 9248), 90, 85)
        val bigStart = System.currentTimeMillis()
        val bigImage = ocr.load(big)
        report.appendLine("load-64mp-exif90;${bigImage.bitmap.width}x${bigImage.bitmap.height};file=${File(context.cacheDir, "ocr-fixtures/64mp.jpg").length()};ms=${System.currentTimeMillis() - bigStart}")
        bigImage.bitmap.recycle()
        File(context.getExternalFilesDir(null), "orientation-diagnostic.csv").writeText(report.toString())
        File(context.cacheDir, "ocr-fixtures").listFiles()?.forEach { it.delete() }
    }
}
