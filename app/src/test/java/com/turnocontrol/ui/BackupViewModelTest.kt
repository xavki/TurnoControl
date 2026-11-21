package com.turnocontrol.ui

import android.net.Uri
import androidx.lifecycle.ViewModelStore
import com.turnocontrol.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDateTime

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BackupViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val uri = Uri.parse("content://synthetic/backup.json")
    private val empty = BackupSnapshot(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), false)
    private val incoming = empty.copy(profiles = listOf(Profile(1, "Perfil ficticio", "", "UTC")), days = listOf(ScheduledDay(1, 1, "2026-03-02", "FREE", "UTC", "MANUAL")))
    private val file = BackupFile(BackupHeader(1, "0.2.0", 2, 4, 2, Instant.EPOCH), incoming)
    private var restores = 0
    private var readError: Exception? = null
    private fun model() = BackupViewModel({ file }, { readError?.let { throw it }; file }, { empty }, { restores++; it.snapshot }).also { store.put("b$restores", it) }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }

    @Test fun restoreNeedsAPreviewAndAnExplicitConfirmation() = runTest(dispatcher) {
        val model = model()
        model.pick(uri); runCurrent()
        val preview = model.state.value.preview!!
        assertEquals(1, preview.incoming.days)
        assertEquals(0, preview.current.days)
        assertEquals(0, restores)
        model.confirmRestore(); runCurrent()
        assertEquals(1, restores)
        assertNull(model.state.value.preview)
        assertTrue(model.state.value.dataReplaced)
        assertTrue(model.state.value.message!!.contains("restaurada y comprobada"))
        // Consumed exactly once: a later recomposition (e.g. rotation) sees no pending reset.
        model.consumeDataReplaced()
        assertFalse(model.state.value.dataReplaced)
        runCurrent()
        assertFalse(model.state.value.dataReplaced)
        assertEquals(1, restores)
    }

    @Test fun cancellationAndInvalidFilesNeverRestore() = runTest(dispatcher) {
        val model = model()
        model.pick(uri); runCurrent()
        model.cancelRestore()
        assertNull(model.state.value.preview)
        readError = BackupException("La copia está dañada")
        model.pick(uri); runCurrent()
        assertNull(model.state.value.preview)
        assertTrue(model.state.value.error)
        model.pick(null)
        model.confirmRestore(); runCurrent()
        assertEquals(0, restores)
        assertFalse(model.state.value.dataReplaced)
    }

    @Test fun exportReportsOnlyAfterVerificationAndUsesAPrivateFileName() = runTest(dispatcher) {
        val model = model()
        model.exportTo(uri); runCurrent()
        assertTrue(model.state.value.message!!.contains("comprobada"))
        assertEquals("turnocontrol-backup-20261121-2005.json", model.suggestedName(LocalDateTime.of(2026, 11, 21, 20, 5)))
    }
}
