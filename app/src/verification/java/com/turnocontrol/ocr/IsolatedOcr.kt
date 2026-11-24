package com.turnocontrol.ocr

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
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
 * PROOF OF CONCEPT, verification build only. Not used by any screen.
 * Runs only the ML Kit call in an `isolatedProcess` service (ephemeral UID, SELinux isolated_app: no network even if the
 * app later gets INTERNET). The bitmap crosses as SharedMemory (never in a Parcel: ~1 MB limit), the result comes back as
 * text + geometry. Decoding, EXIF, rotation, memory budget, review and saving stay in the app process unchanged.
 * Emulator result (phase 5D.1, API 35): binding and the read-only SharedMemory transfer work, but ML Kit 16.0.1 DOES NOT
 * RUN in an isolated process. After working around a Play-services handler crash and the missing SharedPreferences, its
 * native pipeline calls ANeuralNetworks_getRuntimeFeatureLevel, SELinux denies hwbinder and libneuralnetworks crashes
 * (SIGSEGV). No public API avoids that, so production OCR stays in the app process.
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
        // Validated on an emulator (phase 5D.1): ML Kit starts a Play-services GoogleApiManager whose handler thread calls
        // ActivityManager.getMyMemoryState(), which Android forbids in isolated processes. The uncaught SecurityException
        // killed the whole process. Only that exception on that thread is absorbed (it only feeds Play-services telemetry);
        // anything else still reaches the previous handler. Fragile by nature: a reason this stays a proof of concept.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (thread.name == "GoogleApiHandler" && error is SecurityException) android.util.Log.w("IsolatedOcr", "Play-services handler stopped in the isolated process: ${error.message}")
            else previous?.uncaughtException(thread, error)
        }
        // No ContentProvider runs in an isolated process, so ML Kit is initialized explicitly (public API). Its MLHandler
        // thread also reads SharedPreferences, which need a UserManager an isolated process does not have (validated on
        // an emulator): a ContextWrapper keeps those preferences in memory, for the life of this process only.
        runCatching { com.google.mlkit.common.MlKit.initialize(IsolatedContext(applicationContext)) }
    }

    override fun onBind(intent: Intent): IBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != IsolatedOcrProtocol.RECOGNIZE) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(IsolatedOcrProtocol.DESCRIPTOR)
            val outcome = runCatching {
                // An explicit if (not check()) so lint sees the version guard.
                if (Build.VERSION.SDK_INT >= 27) recognize(data) else error("OCR aislado no disponible en esta versión de Android.")
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

/** Context for ML Kit inside the isolated process: in-memory SharedPreferences, nothing written to disk. */
private class IsolatedContext(base: Context) : ContextWrapper(base) {
    private val preferences = HashMap<String, SharedPreferences>()
    override fun getApplicationContext(): Context = this
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = synchronized(preferences) { preferences.getOrPut(name) { InMemoryPreferences() } }
}

private class InMemoryPreferences : SharedPreferences {
    private val values = HashMap<String, Any?>()
    private val listeners = HashSet<SharedPreferences.OnSharedPreferenceChangeListener>()
    @Synchronized override fun getAll(): Map<String, *> = HashMap(values)
    @Synchronized override fun getString(key: String, defValue: String?) = values[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    @Synchronized override fun getStringSet(key: String, defValues: Set<String>?) = (values[key] as? Set<String>)?.toSet() ?: defValues
    @Synchronized override fun getInt(key: String, defValue: Int) = values[key] as? Int ?: defValue
    @Synchronized override fun getLong(key: String, defValue: Long) = values[key] as? Long ?: defValue
    @Synchronized override fun getFloat(key: String, defValue: Float) = values[key] as? Float ?: defValue
    @Synchronized override fun getBoolean(key: String, defValue: Boolean) = values[key] as? Boolean ?: defValue
    @Synchronized override fun contains(key: String) = key in values
    @Synchronized override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) { listeners += listener }
    @Synchronized override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) { listeners -= listener }
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = HashMap<String, Any?>(); private val removals = HashSet<String>(); private var clear = false
        override fun putString(key: String, value: String?) = apply { changes[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) = apply { changes[key] = values?.toSet() }
        override fun putInt(key: String, value: Int) = apply { changes[key] = value }
        override fun putLong(key: String, value: Long) = apply { changes[key] = value }
        override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
        override fun remove(key: String) = apply { removals += key }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean { synchronized(this@InMemoryPreferences) { if (clear) values.clear(); removals.forEach(values::remove); values.putAll(changes) }; return true }
        override fun apply() { commit() }
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
                if (Build.VERSION.SDK_INT >= 27) transact(binder, bitmap) else error("OCR aislado no disponible en esta versión de Android.")
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
