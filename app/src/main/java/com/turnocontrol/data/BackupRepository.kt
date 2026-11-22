package com.turnocontrol.data

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.room.withTransaction
import com.turnocontrol.domain.DataFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

data class AppVersion(val name: String, val code: Long)
sealed interface RecoveryOutcome {
    data object None : RecoveryOutcome
    /** The database already matched the backup; the settings step was completed. */
    data object RolledForward : RecoveryOutcome
    /** The interrupted restore never changed the database; the original data is intact. */
    data object Discarded : RecoveryOutcome
    /** The database matches neither side: the user must choose. Nothing is changed automatically. */
    data class NeedsDecision(val message: String) : RecoveryOutcome
}

/**
 * Local backup and restore. Room and DataStore cannot share one transaction, so a restore is:
 *  1. validate the whole backup;
 *  2. write a pre-restore safety copy of the current data, the target backup and a journal (each atomically);
 *  3. replace every Room table in a single transaction (all or nothing);
 *  4. write the DataStore settings;
 *  5. re-read everything and compare with the backup; only then remove the journal.
 * If the process dies between steps, [recoverInterrupted] runs at the next start and either completes step 4
 * (database already equals the backup), discards the journal (database untouched) or asks the user.
 */
@Singleton
class BackupRepository internal constructor(
    private val db: TurnoDatabase,
    private val readTheme: suspend () -> Boolean,
    private val writeTheme: suspend (Boolean) -> Unit,
    private val directory: File,
    private val version: AppVersion,
    private val streams: StreamOpener? = null,
    private val clock: () -> Instant = Instant::now,
) {
    interface StreamOpener { fun input(uri: Uri): InputStream?; fun output(uri: Uri): OutputStream? }

    @Inject constructor(db: TurnoDatabase, configuration: ConfigurationRepository, @ApplicationContext context: Context) : this(
        db, { configuration.darkTheme.first() }, configuration::setDarkTheme, File(context.filesDir, "backup-recovery"), appVersion(context),
        object : StreamOpener {
            override fun input(uri: Uri) = context.contentResolver.openInputStream(uri)
            override fun output(uri: Uri) = context.contentResolver.openOutputStream(uri, "wt")
        })

    private val lock = Mutex()
    internal val journal get() = File(directory, "restore.journal")
    internal val target get() = File(directory, "restore-target.json")
    val safetyCopy get() = File(directory, "pre-restore.json")

    /** Consistent snapshot: all tables read inside one transaction. Settings are read separately (not atomic with Room). */
    suspend fun snapshot(): BackupSnapshot {
        val dao = db.backup()
        val room = db.withTransaction { BackupSnapshot(dao.profiles(), dao.contracts(), dao.payRules(), dao.days(), dao.intervals(), false) }
        return room.copy(darkTheme = readTheme()).normalized()
    }
    private fun header() = BackupHeader(BackupCodec.FORMAT_VERSION, version.name, version.code, DataFormat.ROOM_SCHEMA_VERSION, DataFormat.CURRENT, clock())

    suspend fun export(uri: Uri): BackupFile = exportTo({ streams!!.output(uri) }, { streams!!.input(uri) })
    suspend fun read(uri: Uri): BackupFile = withContext(Dispatchers.IO) { readFrom { streams!!.input(uri) } }

    /** Writes the backup, then reads it back and decodes it: success is reported only for a verified file. */
    internal suspend fun exportTo(open: () -> OutputStream?, reopen: () -> InputStream?): BackupFile = withContext(Dispatchers.IO) { lock.withLock {
        val file = BackupFile(header(), snapshot())
        // Validate before writing: a value this version cannot restore must not produce a backup file at all.
        try { BackupCodec.validate(file.snapshot) } catch (e: BackupException) {
            throw BackupException("No se puede crear la copia: los datos guardados contienen un valor que esta versión no puede restaurar (${e.message?.removePrefix("Copia no válida: ")}). No se ha escrito ningún archivo.")
        }
        val text = BackupCodec.encode(file)
        (open() ?: throw BackupException("No se pudo crear el archivo de copia.")).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        val written = readFrom(reopen)
        if (written.snapshot != file.snapshot) throw BackupException("La copia escrita no coincide con los datos. Vuelve a intentarlo en otra ubicación.")
        file
    } }

    internal fun readFrom(open: () -> InputStream?): BackupFile {
        val bytes = (open() ?: throw BackupException("No se pudo abrir el archivo seleccionado.")).use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (output.size() + count > BackupCodec.MAX_BYTES) throw BackupException("El archivo es demasiado grande para ser una copia de TurnoControl.")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val text = try { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString() }
            catch (e: java.nio.charset.CharacterCodingException) { throw BackupException("El archivo no es una copia de TurnoControl válida o está dañado.") }
        return BackupCodec.decode(text)
    }

    /** [afterRoom] exists only so tests can simulate an interruption between the Room and DataStore steps. */
    suspend fun restore(file: BackupFile, afterRoom: suspend () -> Unit = {}): BackupSnapshot = withContext(Dispatchers.IO) { lock.withLock {
        val wanted = file.snapshot.normalized()
        BackupCodec.validate(wanted)
        directory.mkdirs()
        writeAtomically(safetyCopy, BackupCodec.encode(BackupFile(header(), snapshot())))
        writeAtomically(target, BackupCodec.encode(BackupFile(file.header, wanted)))
        writeAtomically(journal, "restore-started ${clock()}")
        val dao = db.backup()
        try {
            db.withTransaction {
                dao.deleteIntervals(); dao.deleteDays(); dao.deletePayRules(); dao.deleteContracts(); dao.deleteProfiles()
                dao.insertProfiles(wanted.profiles); dao.insertContracts(wanted.contracts); dao.insertPayRules(wanted.payRules)
                dao.insertDays(wanted.days); dao.insertIntervals(wanted.intervals)
                db.sync().onUserDataReplaced() // Same transaction: never a restored calendar with a stale outbox.
            }
        } catch (e: CancellationException) {
            throw e // The journal stays: the next start compares the database and decides.
        } catch (e: Exception) {
            // The transaction rolled back, so the database still holds the original data.
            journal.delete(); target.delete()
            throw BackupException("No se pudo restaurar la copia. Tus datos actuales no se han modificado.")
        }
        afterRoom()
        writeTheme(wanted.darkTheme)
        val stored = snapshot()
        if (stored != wanted) throw BackupException("La restauración no se pudo verificar. Al volver a abrir la aplicación se revisará el estado de los datos.")
        journal.delete(); target.delete()
        stored
    } }

    suspend fun recoverInterrupted(): RecoveryOutcome = withContext(Dispatchers.IO) { lock.withLock {
        if (!journal.exists()) return@withLock RecoveryOutcome.None
        val wanted = runCatching { BackupCodec.decode(target.readText()).snapshot }.getOrNull()
        val original = runCatching { BackupCodec.decode(safetyCopy.readText()).snapshot }.getOrNull()
        val current = snapshot()
        when {
            wanted != null && current.roomData() == wanted.roomData() -> {
                writeTheme(wanted.darkTheme)
                if (snapshot() != wanted) return@withLock RecoveryOutcome.NeedsDecision("La restauración anterior se interrumpió y no se pudo completar la configuración.")
                journal.delete(); target.delete()
                RecoveryOutcome.RolledForward
            }
            original != null && current.roomData() == original.roomData() -> {
                // Settings are written only after Room, so they were not touched either.
                journal.delete(); target.delete()
                RecoveryOutcome.Discarded
            }
            else -> RecoveryOutcome.NeedsDecision("Una restauración anterior se interrumpió y los datos no coinciden ni con la copia ni con el estado previo.")
        }
    } }

    /** Explicit user choice after [RecoveryOutcome.NeedsDecision]: keep what is stored now. */
    suspend fun keepCurrentData() = withContext(Dispatchers.IO) { lock.withLock { journal.delete(); target.delete() } }

    /** Explicit user choice: put back the data saved automatically before the interrupted restore. */
    suspend fun restoreSafetyCopy(): BackupSnapshot {
        val previous = withContext(Dispatchers.IO) { BackupCodec.decode(safetyCopy.readText()) }
        withContext(Dispatchers.IO) { lock.withLock { journal.delete(); target.delete() } }
        return restore(previous)
    }

    private fun writeAtomically(file: File, text: String) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(text)
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        fun appVersion(context: Context): AppVersion {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            @Suppress("DEPRECATION")
            return AppVersion(info.versionName ?: "?", if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong())
        }
    }
}
