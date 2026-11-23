package com.turnocontrol

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.core.app.ApplicationProvider
import com.turnocontrol.data.MlKitTextRecognitionEngine
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Phase 4B.5 network-experiment-only probe: exercises the real ML Kit text recognizer directly, with no UI and no
 * production database, so it can run against ANY package id (the rest of this module's instrumented tests
 * deliberately hardcode "com.turnocontrol.verification" as a safety guard against running on real data, which this
 * test does not touch at all). Used to generate real recognizer activity while traffic/DataTransport/jobscheduler
 * are measured on the networkBaselineVerification and networkStrategyBVerification build types. Never meaningful
 * (and never installed) outside those two disposable, network-enabled variants.
 */
class NetworkExperimentOcrTest {
    private fun syntheticTable(): Bitmap {
        val bitmap = Bitmap.createBitmap(1000, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 72f; typeface = Typeface.DEFAULT_BOLD }
        canvas.drawText("PRUEBA OCR", 60f, 140f, paint)
        canvas.drawText("ALFA BETA 123", 60f, 260f, paint)
        return bitmap
    }

    @Test fun recognizesSyntheticTextRepeatedly(): Unit = runBlocking {
        // A connectivity/tag probe, so logcat and netstats can distinguish our own traffic from the OS/GMS noise.
        android.net.TrafficStats.setThreadStatsTag(0xC0FFEE)
        repeat(5) {
            val document = MlKitTextRecognitionEngine.recognize(syntheticTable())
            android.util.Log.i("NetworkExperimentOcrTest", "pass $it recognized ${document.blocks.size} blocks: ${document.text}")
        }
        android.net.TrafficStats.clearThreadStatsTag()
        // Context is only read to log which variant actually ran.
        android.util.Log.i("NetworkExperimentOcrTest", "ran as ${ApplicationProvider.getApplicationContext<android.content.Context>().packageName}")
    }
}
