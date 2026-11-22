package com.turnocontrol.ocr

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.SharedMemory
import android.system.OsConstants
import androidx.annotation.RequiresApi
import com.turnocontrol.data.MlKitTextRecognitionEngine
import com.turnocontrol.data.OcrDocumentCodec
import com.turnocontrol.data.TextRecognitionEngine
import com.turnocontrol.domain.OcrDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/*
 * PROOF OF CONCEPT, verification build only, NOT VALIDATED ON ANDROID (phase 5D). Not used by any screen.
 * Runs only the ML Kit call in an `isolatedProcess` service (ephemeral UID, SELinux isolated_app: no network even if the
 * app later gets INTERNET). The bitmap crosses as SharedMemory (never in a Parcel: ~1 MB limit), the result comes back as
 * text + geometry. Decoding, EXIF, rotation, memory budget, review and saving stay in the app process unchanged.
 * Open questions to validate on an emulator: ML Kit initialization without its ContentProvider, the bundled model load,
 * and whether the play-services-mlkit dependency needs Google Play services (an isolated process cannot bind to it).
 */
internal object IsolatedOcrProtocol {
    const val DESCRIPTOR = "com.turnocontrol.ocr.IsolatedOcr"
    const val RECOGNIZE = IBinder.FIRST_CALL_TRANSACTION
    const val OK = 0
    const val ERROR = 1
}

class IsolatedOcrService : Service() {
    override fun onCreate() {
        super.onCreate()
        // No ContentProvider runs in an isolated process, so ML Kit is initialized explicitly (public API).
        runCatching { com.google.mlkit.common.MlKit.initialize(applicationContext) }
    }

    override fun onBind(intent: Intent): IBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != IsolatedOcrProtocol.RECOGNIZE) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(IsolatedOcrProtocol.DESCRIPTOR)
            val outcome = runCatching {
                check(Build.VERSION.SDK_INT >= 27) { "OCR aislado no disponible en esta versión de Android." }
                recognize(data)
            }
            reply?.writeInt(if (outcome.isSuccess) IsolatedOcrProtocol.OK else IsolatedOcrProtocol.ERROR)
            reply?.writeString(outcome.getOrNull() ?: (outcome.exceptionOrNull()?.message ?: "Error de OCR"))
            return true
        }
    }

    @RequiresApi(27)
    private fun recognize(data: Parcel): String {
        val memory = SharedMemory.CREATOR.createFromParcel(data)
        val width = data.readInt(); val height = data.readInt()
        try {
            val buffer = memory.mapReadOnly()
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try { bitmap.copyPixelsFromBuffer(buffer) } finally { SharedMemory.unmap(buffer) }
            return OcrDocumentCodec.encode(runBlocking { MlKitTextRecognitionEngine.recognize(bitmap) }).also { bitmap.recycle() }
        } finally { memory.close() }
    }
}

/** Client side of the proof of concept. Same contract as the in-process engine; not injected anywhere. */
class IsolatedProcessTextRecognitionEngine(private val context: Context, private val timeoutMillis: Long = 60_000) : TextRecognitionEngine {
    override suspend fun recognize(bitmap: Bitmap): OcrDocument = withContext(Dispatchers.IO) {
        check(Build.VERSION.SDK_INT >= 27) { "OCR aislado no disponible en esta versión de Android." }
        withTimeout(timeoutMillis) {
            var connection: ServiceConnection? = null
            try {
                val binder = suspendCancellableCoroutine<IBinder> { continuation ->
                    val c = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName, service: IBinder) { if (continuation.isActive) continuation.resume(service) }
                        override fun onServiceDisconnected(name: ComponentName) = Unit
                    }
                    connection = c
                    if (!context.bindService(Intent(context, IsolatedOcrService::class.java), c, Context.BIND_AUTO_CREATE))
                        continuation.cancel(IllegalStateException("No se pudo iniciar el OCR aislado."))
                }
                transact(binder, bitmap)
            } finally {
                connection?.let { runCatching { context.unbindService(it) } } // Always released, also on timeout or cancellation.
            }
        }
    }

    @RequiresApi(27)
    private fun transact(binder: IBinder, source: Bitmap): OcrDocument {
        val bitmap = if (source.config == Bitmap.Config.ARGB_8888) source else source.copy(Bitmap.Config.ARGB_8888, false)
        SharedMemory.create("turnocontrol-ocr", bitmap.byteCount).use { memory ->
            val buffer = memory.mapReadWrite()
            try { bitmap.copyPixelsToBuffer(buffer) } finally { SharedMemory.unmap(buffer) }
            memory.setProtect(OsConstants.PROT_READ) // The isolated side can only read the pixels.
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(IsolatedOcrProtocol.DESCRIPTOR)
                memory.writeToParcel(data, 0)
                data.writeInt(bitmap.width); data.writeInt(bitmap.height)
                // A crash of the isolated process surfaces here as DeadObjectException: reported as an OCR failure.
                binder.transact(IsolatedOcrProtocol.RECOGNIZE, data, reply, 0)
                val status = reply.readInt(); val payload = reply.readString().orEmpty()
                if (status != IsolatedOcrProtocol.OK) throw IllegalStateException(payload)
                return OcrDocumentCodec.decode(payload)
            } finally { data.recycle(); reply.recycle(); if (bitmap !== source) bitmap.recycle() }
        }
    }
}
