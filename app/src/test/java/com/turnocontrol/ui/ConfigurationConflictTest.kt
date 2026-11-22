package com.turnocontrol.ui

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.turnocontrol.data.*
import com.turnocontrol.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant

/** Settings saves blocked by an unresolved synchronization conflict: synthetic in-memory data only. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class ConfigurationConflictTest {
    @get:Rule val temporary = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private lateinit var db: TurnoDatabase
    private lateinit var repository: ConfigurationRepository
    private val draft = ConfigurationDraft(name = "Perfil ficticio", zone = "Europe/Madrid", hours = "40", unit = "WEEK", from = "2026-01-01", hourlyRate = "10", extraRates = mapOf("EXTRA_HOUR" to "15"))

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TurnoDatabase::class.java).build()
        repository = ConfigurationRepository(db, PreferenceDataStoreFactory.create(produceFile = { File(temporary.root, "prefs.preferences_pb") }))
    }
    @After fun cleanup() { store.clear(); db.close(); Dispatchers.resetMain() }

    private suspend fun model() = ConfigurationViewModel(repository).also { store.put("config-${System.nanoTime()}", it); it.state.first { s -> !s.loading } }
    private suspend fun ConfigurationViewModel.saved(): ConfigurationState {
        update(state.value.draft) // Clears the previous message, so the wait below observes this save's outcome.
        save()
        return state.first { !it.saving && it.message != null }
    }
    private suspend fun conflictOn(entity: String, key: String) =
        db.sync().insertConflicts(listOf(SyncConflictRow(entity, key, "VERSION", null, 2, 2, false, 1, null, "otro-dispositivo", null)))
    private suspend fun configurationRows() = db.backup().let { listOf(it.profiles(), it.contracts(), it.payRules()) }

    @Test fun offlineSaveWorksAndCreatesNoSynchronizationState() = runTest(dispatcher) {
        val model = model()
        model.update(draft)
        assertEquals("Configuración guardada en este dispositivo.", model.saved().message)
        assertNull(db.sync().state())
        assertEquals("Perfil ficticio", db.schedule().profile()!!.displayName)
    }

    @Test fun linkedSaveWithoutConflictQueuesExactlyTheChange() = runTest(dispatcher) {
        repository.save(draft)
        SyncStore(db).link("ana", Instant.EPOCH)
        val model = model()
        model.update(model.state.value.draft.copy(name = "Nombre nuevo"))
        assertEquals("Configuración guardada en este dispositivo.", model.saved().message)
        assertEquals(listOf("PROFILE"), db.sync().outbox().map { it.entity })
    }

    @Test fun conflictBlocksTheSaveKeepsEditsAndAllowsRetryAfterResolution() = runTest(dispatcher) {
        repository.save(draft)
        SyncStore(db).link("ana", Instant.EPOCH)
        conflictOn("PAY_RULE", "EXTRA_HOUR@2026-01-01")
        val before = configurationRows()
        val model = model()
        val edited = model.state.value.draft.copy(name = "Nombre editado", extraRates = mapOf("EXTRA_HOUR" to "18"))
        model.update(edited)
        val blocked = model.saved()
        assertEquals("No se puede guardar: la tarifa EXTRA_HOUR tiene un conflicto de sincronización pendiente. Tus cambios no se han perdido ni se ha modificado nada; podrás guardarlos cuando se resuelva el conflicto.", blocked.message)
        assertEquals(edited, blocked.draft) // Unsaved edits are kept.
        assertFalse(blocked.saving)
        assertEquals(before, configurationRows()) // Neither version overwritten; the unrelated name change is not saved either.
        assertTrue(db.sync().outbox().isEmpty()) // No outgoing operation, let alone a duplicate.
        assertEquals(1, db.sync().conflicts().size)
        // Retrying while the conflict is open is still refused, without side effects.
        model.saved()
        assertTrue(db.sync().outbox().isEmpty())
        // Once resolved (the resolution screen comes in a later phase), the same edits save normally, once.
        db.sync().deleteConflicts()
        assertEquals("Configuración guardada en este dispositivo.", model.saved().message)
        assertEquals("Nombre editado", db.schedule().profile()!!.displayName)
        assertEquals(listOf("PAY_RULE:EXTRA_HOUR@2026-01-01", "PROFILE:profile"), db.sync().outbox().map { "${it.entity}:${it.recordKey}" }.sorted())
    }

    @Test fun conflictOnAnotherRecordDoesNotBlockAnUnrelatedEdit() = runTest(dispatcher) {
        repository.save(draft)
        SyncStore(db).link("ana", Instant.EPOCH)
        conflictOn("CONTRACT", "contract")
        val model = model()
        model.update(model.state.value.draft.copy(hourlyRate = "11")) // Only the base rate changes.
        assertEquals("Configuración guardada en este dispositivo.", model.saved().message)
        assertEquals(listOf("PAY_RULE:HOURLY_BASE@2026-01-01"), db.sync().outbox().map { "${it.entity}:${it.recordKey}" })
    }

    @Test fun validationAndStorageFailuresAreReportedWithoutLosingTheDraft() = runTest(dispatcher) {
        val model = model()
        model.update(draft.copy(name = "a".repeat(201)))
        assertEquals("El nombre visible no puede superar 200 caracteres.", model.saved().message)
        assertNull(db.schedule().profile())
        val valid = draft.copy(name = "Válido")
        model.update(valid)
        db.openHelper.writableDatabase.execSQL("DROP TABLE Contract") // Real SQLite failure inside the save transaction.
        val failed = model.saved()
        assertEquals("No se pudo guardar. Reintenta.", failed.message)
        assertEquals(valid, failed.draft)
        assertNull(db.schedule().profile()) // Rolled back: nothing partially written.
    }
}
