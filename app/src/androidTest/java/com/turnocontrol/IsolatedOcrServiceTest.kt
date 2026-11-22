package com.turnocontrol

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.turnocontrol.data.MlKitTextRecognitionEngine
import com.turnocontrol.ocr.IsolatedProcessTextRecognitionEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Isolated-OCR proof of concept on a real device/emulator (verification build, owner approval required to install).
 * Synthetic rendered text only. Expected to FAIL if ML Kit cannot initialize in an isolated process: that result is
 * itself the evidence the proof of concept needs.
 */
@RunWith(AndroidJUnit4::class)
class IsolatedOcrServiceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun synthetic(): Bitmap = Bitmap.createBitmap(1200, 300, Bitmap.Config.ARGB_8888).also { bitmap ->
        Canvas(bitmap).apply { drawColor(Color.WHITE); drawText("LUNES 08:00-16:00", 40f, 180f, Paint().apply { color = Color.BLACK; textSize = 96f; isAntiAlias = true }) }
    }

    @Test fun isolatedEngineMatchesTheInProcessEngine() = runBlocking {
        val bitmap = synthetic()
        val local = MlKitTextRecognitionEngine.recognize(bitmap)
        val isolated = IsolatedProcessTextRecognitionEngine(context).recognize(bitmap)
        assertTrue(isolated.text.contains("08:00"))
        assertEquals(local, isolated)
    }
}
