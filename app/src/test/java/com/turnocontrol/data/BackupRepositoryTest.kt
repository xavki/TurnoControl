package com.turnocontrol.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.turnocontrol.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.LocalDate

/** Isolated synthetic databases only; never the real application. */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class BackupRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var db: TurnoDatabase
    private var theme = false
    private var stores = 0
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TurnoDatabase::class.java).allowMainThreadQueries().build() }
    @After fun cleanup() { db.close() }
    private fun repository(directory: File = File(temporary.root, "recovery")) =
        BackupRepository(db, { theme }, { theme = it }, directory, AppVersion("0.2.0", 2), clock = { Instant.parse("2026-11-21T20:00:00Z") })

    private suspend fun seed(name: String, dates: List<String>, kind: DayKind = DayKind.WORK) {
        val configuration = ConfigurationRepository(db, PreferenceDataStoreFactory.create(produceFile = { File(temporary.root, "seed-${stores++}.preferences_pb") }))
        configuration.save(ConfigurationDraft(name = name, zone = "UTC", hours = "40", unit = "WEEK", from = "2026-01-01", hourlyRate = "10", extraRates = mapOf("EXTRA_HOUR" to "15")))
        val repo = ScheduleRepository(db)
        repo.save(repo.snapshot(), dates.map { PlannedDay(LocalDate.parse(it), kind, if (kind == DayKind.WORK) TimeCellParser.parse("22:00-02:00").intervals else emptyList()) })
    }
    private fun exportBytes(repository: BackupRepository): Pair<BackupFile, ByteArray> {
        val output = ByteArrayOutputStream()
        var bytes = ByteArray(0)
        val file = kotlinx.coroutines.runBlocking { repository.exportTo({ object : java.io.FilterOutputStream(output) { override fun close() { super.close(); bytes = output.toByteArray() } } }, { ByteArrayInputStream(bytes) }) }
        return file to bytes
    }

    @Test fun exportIsVerifiedAndRestoreReplacesEverythingIncludingSettings() = runTest {
        seed("Perfil A", listOf("2026-03-02", "2026-03-04"))
        theme = true
        val repository = repository()
        val (exported, bytes) = exportBytes(repository)
        assertEquals(repository.snapshot(), exported.snapshot)
        assertTrue(String(bytes).contains("\"format\": \"turnocontrol-backup\""))
        // Replace the data with something else, then restore the export.
        val dao = db.backup()
        db.openHelper.writableDatabase.execSQL("DELETE FROM Profile")
        seed("Perfil B", listOf("2026-05-01"), DayKind.VACATION)
        theme = false
        val before = repository.snapshot()
        val read = repository.readFrom { ByteArrayInputStream(bytes) }
        val restored = repository.restore(read)
        assertEquals(exported.snapshot, restored)
        assertEquals(exported.snapshot, repository.snapshot())
        assertTrue(theme)
        assertEquals("Perfil A", dao.profiles().single().displayName)
        assertEquals(1, ScheduleRepository(db).snapshot().first { it.day.date == "2026-03-02" }.planned().intervals.single().endDayOffset)
        // The previous data was saved automatically before replacing it; the journal is gone.
        assertEquals(before, BackupCodec.decode(repository.safetyCopy.readText()).snapshot)
        assertFalse(repository.journal.exists())
        assertFalse(repository.target.exists())
    }

    @Test fun failedRoomTransactionKeepsTheOriginalDataUntouched() = runTest {
        seed("Perfil A", listOf("2026-03-02"))
        val repository = repository()
        val (exported, _) = exportBytes(repository)
        db.openHelper.writableDatabase.execSQL("DELETE FROM Profile")
        seed("Perfil B", listOf("2026-05-01", "2026-05-02"))
        val before = repository.snapshot()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER synthetic_failure BEFORE INSERT ON ScheduledInterval BEGIN SELECT RAISE(ABORT, 'synthetic'); END")
        val error = runCatching { repository.restore(exported) }.exceptionOrNull()
        assertTrue(error is BackupException)
        assertTrue(error!!.message!!.contains("no se han modificado"))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER synthetic_failure")
        assertEquals(before, repository.snapshot())
        assertFalse(repository.journal.exists())
    }

    @Test fun interruptionAfterRoomIsCompletedAtNextStart() = runTest {
        seed("Perfil A", listOf("2026-03-02"))
        theme = true
        val repository = repository()
        val (exported, _) = exportBytes(repository)
        db.openHelper.writableDatabase.execSQL("DELETE FROM Profile")
        seed("Perfil B", listOf("2026-05-01"))
        theme = false
        val crash = runCatching { repository.restore(exported) { throw IllegalStateException("Synthetic process death") } }
        assertTrue(crash.isFailure)
        assertTrue(repository.journal.exists())
        assertFalse("Settings were not written yet", theme)
        // Next start: the database already equals the backup, so only the settings step remains.
        assertEquals(RecoveryOutcome.RolledForward, repository().recoverInterrupted())
        assertTrue(theme)
        assertEquals(exported.snapshot, repository.snapshot())
        assertFalse(repository.journal.exists())
        assertEquals(RecoveryOutcome.None, repository().recoverInterrupted())
    }

    @Test fun interruptionBeforeTheRoomCommitIsDiscarded() = runTest {
        seed("Perfil A", listOf("2026-03-02"))
        val repository = repository()
        val current = repository.snapshot()
        val other = current.copy(days = emptyList(), intervals = emptyList())
        File(temporary.root, "recovery").mkdirs()
        repository.safetyCopy.writeText(BackupCodec.encode(BackupFile(BackupHeader(1, "0.2.0", 2, 4, 2, Instant.EPOCH), current)))
        repository.target.writeText(BackupCodec.encode(BackupFile(BackupHeader(1, "0.2.0", 2, 4, 2, Instant.EPOCH), other)))
        repository.journal.writeText("restore-started")
        assertEquals(RecoveryOutcome.Discarded, repository.recoverInterrupted())
        assertEquals(current, repository.snapshot())
        assertFalse(repository.journal.exists())
    }

    @Test fun unknownStateRequiresAnExplicitChoice() = runTest {
        seed("Perfil A", listOf("2026-03-02"))
        val repository = repository()
        val original = repository.snapshot()
        val (exported, _) = exportBytes(repository)
        runCatching { repository.restore(exported.copy(snapshot = original.copy(days = emptyList(), intervals = emptyList()))) { throw IllegalStateException("Synthetic") } }
        // Something else changed the data after the interruption: neither side matches.
        seed("Perfil A", listOf("2026-07-07"))
        val outcome = repository.recoverInterrupted()
        assertTrue(outcome is RecoveryOutcome.NeedsDecision)
        assertTrue(repository.journal.exists())
        repository.restoreSafetyCopy()
        assertEquals(original.roomData(), repository.snapshot().roomData())
        assertFalse(repository.journal.exists())
    }

    @Test fun oversizedAndNonTextFilesAreRejectedBeforeParsing() {
        val repository = repository()
        val big = ByteArray(BackupCodec.MAX_BYTES + 1) { ' '.code.toByte() }
        assertTrue(assertThrows(BackupException::class.java) { repository.readFrom { ByteArrayInputStream(big) } }.message!!.contains("demasiado grande"))
        assertThrows(BackupException::class.java) { repository.readFrom { ByteArrayInputStream(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00)) } }
    }

    @Test fun exportRefusesUnrestorableDataBeforeWritingAnyFile() = runTest {
        seed("Perfil A", listOf("2026-03-02"))
        db.openHelper.writableDatabase.execSQL("UPDATE ScheduledDay SET origin = 'FUTURE_SOURCE'")
        var opened = false
        val error = runCatching { repository().exportTo({ opened = true; ByteArrayOutputStream() }, { ByteArrayInputStream(ByteArray(0)) }) }.exceptionOrNull()
        assertTrue(error is BackupException)
        assertTrue(error!!.message!!.contains("No se ha escrito ningún archivo"))
        assertFalse("No file must be created", opened)
    }

    @Test fun emptyDatabaseCanBeBackedUpAndRestored() = runTest {
        val repository = repository()
        val (exported, bytes) = exportBytes(repository)
        assertTrue(exported.snapshot.days.isEmpty())
        seed("Perfil B", listOf("2026-05-01"))
        repository.restore(repository.readFrom { ByteArrayInputStream(bytes) })
        assertTrue(db.backup().profiles().isEmpty())
        assertTrue(db.backup().days().isEmpty())
    }
}
