package com.turnocontrol.data.accounts

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.turnocontrol.data.ScheduleRepository
import com.turnocontrol.domain.DayKind
import com.turnocontrol.domain.PlannedDay
import com.turnocontrol.domain.auth.AccountId
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Android (not Robolectric): confirms an account database opened through an absolute path outside the default
 * "databases" directory actually works on-device, since AccountStorage.kt now resolves accounts that way (JVM tests
 * only prove it on a Windows Robolectric JVM). Runs only in `.verification`, with a synthetic account id.
 */
@RunWith(AndroidJUnit4::class)
class AccountStorageDirectoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val account = AccountId.parse("00000000-0000-4000-8000-0000000009a1")!!
    private val layout = StorageLayout(context.filesDir)
    private val location = layout.locate(StorageNamespace.forAccount(account))
    private val gate = OperationGate()

    @After fun cleanup() { context.deleteStorageDatabase(location) }

    @Test fun opensAwayFromTheDefaultDatabasesDirectoryAndSurvivesAReopen() = runBlocking {
        assertFalse("La ruta de la cuenta no debe estar en el directorio por defecto",
            location.databasePath == context.getDatabasePath(location.databaseName).path)
        val file = context.databaseFile(location)
        assertTrue(file.path.startsWith(context.filesDir.canonicalPath))

        val guard = SessionGuard()
        val db = StorageDatabases.build(context, location, guard)
        ScheduleRepository(db, gate).let { it.save(it.snapshot(), listOf(PlannedDay(LocalDate.parse("2026-12-01"), DayKind.FREE, emptyList()))) }
        db.close()
        assertTrue(file.exists())

        val reopened = StorageDatabases.build(context, location, SessionGuard())
        val dates = ScheduleRepository(reopened).snapshot().map { it.day.date.toString() }
        assertEquals(1, dates.size)
        assertEquals("2026-12-01", dates.first())
        reopened.close()
    }
}
