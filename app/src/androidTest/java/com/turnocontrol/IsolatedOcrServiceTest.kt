package com.turnocontrol

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.DeadObjectException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.turnocontrol.data.MlKitTextRecognitionEngine
import com.turnocontrol.ocr.IsolatedProcessTextRecognitionEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Isolated-OCR proof of concept on a real device/emulator (verification build, owner approval required to install).
 * Synthetic rendered text only.
 *
 * Phase 5D.1 (docs/PRIVACIDAD_RED_MLKIT_0.3.0.md §12, INFORME_VALIDACION_EMULADOR_5D1.md §6) found that ML Kit
 * 16.0.1 cannot run in an isolated process: its native pipeline calls ANeuralNetworks_getRuntimeFeatureLevel, SELinux
 * denies isolated_app access to hwbinder, and libneuralnetworks.so crashes with SIGSEGV. No public API avoids that.
 * The isolated process then dies, and the bound client (IsolatedProcessTextRecognitionEngine.transact) observes that
 * as a DeadObjectException from Binder#transact. This test asserts that exact, documented failure: it is the
 * evidence that the proof of concept does not work, not a defect to fix here. It would start failing itself (and
 * need re-investigation) only if a future ML Kit/Android combination made the isolated call stop crashing.
 */
@RunWith(AndroidJUnit4::class)
class IsolatedOcrServiceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun synthetic(): Bitmap = Bitmap.createBitmap(1200, 300, Bitmap.Config.ARGB_8888).also { bitmap ->
        Canvas(bitmap).apply { drawColor(Color.WHITE); drawText("LUNES 08:00-16:00", 40f, 180f, Paint().apply { color = Color.BLACK; textSize = 96f; isAntiAlias = true }) }
    }

    // Block body (not `= runBlocking { ... }`): a JUnit @Test method must return void, and assertThrows' return
    // value (the caught exception) would otherwise become this function's inferred return type.
    @Test fun isolatedEngineCrashesWithDeadObjectExceptionWhileTheInProcessEngineStillWorks() {
        val bitmap = synthetic()
        // Production OCR path: unaffected by the isolated-process proof of concept, must keep working.
        val local = runBlocking { MlKitTextRecognitionEngine.recognize(bitmap) }
        assertTrue(local.text.contains("08:00"))
        // Isolated-process proof of concept: expected to crash, not to return a result.
        assertThrows(DeadObjectException::class.java) { runBlocking { IsolatedProcessTextRecognitionEngine(context).recognize(bitmap) } }
    }
}
