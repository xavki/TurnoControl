package com.turnocontrol.ui

import androidx.lifecycle.ViewModelStore
import com.turnocontrol.data.*
import com.turnocontrol.domain.*
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class RecoveryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val date = LocalDate.parse("2032-03-10")
    private val rows = listOf(StoredScheduleDay(ScheduledDay(1, date = date.toString(), kind = "FREE", zoneId = "UTC", origin = "MANUAL"), emptyList()))
    private var persisted = emptyList<StoredScheduleDay>()
    private var snapshots = 0
    private var writes = 0
    private var readFailure: Exception? = null
    private var writeFailure: Exception? = null
    private var commitBeforeFailure = false
    private var writeGate: CompletableDeferred<Unit>? = null
    private var readGate: CompletableDeferred<Unit>? = null
    private val origins = mutableListOf<String>()
    private var active = 0
    private var maximumActive = 0
    private var collections = 0
    private var failObservation = false
    private val emissions = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val days = flow {
        collections++
        active++
        maximumActive = maxOf(maximumActive, active)
        try {
            emit(rows)
            emissions.collect {
                if (failObservation) throw IOException("Synthetic observation failure")
                emit(persisted)
            }
        } finally { active-- }
    }
    private fun model(): CalendarViewModel = CalendarViewModel(days, {
        snapshots++
        readGate?.await()
        readFailure?.let { throw it }
        persisted
    }, { expected, replacements, deletions, origin ->
        writes++
        origins += origin
        writeGate?.await()
        if (expected != persisted) throw ScheduleChangedException()
        assertTrue(origin in setOf("MANUAL", "OCR_REVIEWED"))
        if (writeFailure == null || commitBeforeFailure) {
            persisted = persisted.filterNot { LocalDate.parse(it.day.date) in deletions || replacements.any { r -> r.date.toString() == it.day.date } } + replacements.mapIndexed { index, day ->
                StoredScheduleDay(ScheduledDay(index.toLong() + 1, date = day.date.toString(), kind = day.kind.name, zoneId = "UTC", origin = "MANUAL"), day.intervals.mapIndexed { position, i ->
                    ScheduledInterval(dayId = index.toLong() + 1, position = position, startMinute = i.start.hour * 60 + i.start.minute, endMinute = i.end.hour * 60 + i.end.minute, startDayOffset = i.startDayOffset, endDayOffset = i.endDayOffset, isExtra = i.isExtra)
                })
            }
        }
        if (writeFailure == null || commitBeforeFailure) emissions.emit(Unit)
        writeFailure?.let { throw it }
    }).also { store.put("calendar", it) }

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }
    private fun edit(model: CalendarViewModel) {
        model.select(date)
        dispatcher.scheduler.runCurrent()
        model.openShift()
        model.slot(0, IntervalDraft(LocalTime.of(8, 15), LocalTime.of(12, 30)))
    }

    @Test fun failedObservationRetainsLastDaysAndExplicitRetryNeverDuplicatesCollectors() = runTest(dispatcher) {
        val model = model()
        runCurrent()
        assertEquals(rows, model.state.value.days)
        failObservation = true
        emissions.emit(Unit)
        runCurrent()
        assertNotNull(model.state.value.observationError)
        assertEquals(rows, model.state.value.days)
        assertEquals(0, active)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, collections)
        failObservation = false
        model.retryCalendarObservation()
        model.retryCalendarObservation()
        runCurrent()
        model.retryCalendarObservation()
        runCurrent()
        assertNull(model.state.value.observationError)
        assertEquals(2, collections)
        assertEquals(1, active)
        assertEquals(1, maximumActive)
        assertEquals(0, writes)
    }

    @Test fun clearingViewModelCancelsObservationWithoutFalseError() = runTest(dispatcher) {
        val model = model()
        runCurrent()
        store.clear()
        runCurrent()
        assertEquals(0, active)
        assertNull(model.state.value.observationError)
        assertNull(model.state.value.message)
    }

    @Test fun rejectedWritePreservesDraftAndReportsNoSuccess() = runTest(dispatcher) {
        val model = model()
        edit(model)
        val draft = model.state.value.editor
        writeFailure = IllegalArgumentException("Synthetic rejected interval")
        model.saveShift()
        runCurrent()
        assertEquals(draft, model.state.value.editor)
        assertEquals(SaveOutcome.REJECTED, model.state.value.saveOutcome)
        assertEquals("Synthetic rejected interval", model.state.value.message)
        assertTrue(persisted.isEmpty())
        assertEquals(1, writes)
        assertEquals(1, snapshots)
    }

    @Test fun confirmedWriteWithFailedSnapshotCanRefreshWithoutAnotherWrite() = runTest(dispatcher) {
        val model = model()
        edit(model)
        readFailure = IOException("Synthetic refresh failure")
        model.saveShift()
        model.saveShift()
        runCurrent()
        assertEquals(SaveOutcome.CONFIRMED, model.state.value.saveOutcome)
        assertTrue(model.state.value.message!!.contains("guardado", ignoreCase = true))
        assertFalse(model.state.value.message!!.contains("No se ha confirmado"))
        assertTrue(model.state.value.needsReconciliation)
        assertFalse(model.state.value.editorReady)
        assertNull(model.state.value.editor)
        assertEquals(1, writes)
        assertEquals(1, persisted.size)
        model.refreshCalendar()
        runCurrent()
        assertTrue(model.state.value.needsReconciliation)
        readFailure = null
        model.refreshCalendar()
        model.refreshCalendar()
        runCurrent()
        assertEquals(persisted, model.state.value.days)
        assertFalse(model.state.value.needsReconciliation)
        assertTrue(model.state.value.editorReady)
        assertEquals(SaveOutcome.CONFIRMED, model.state.value.saveOutcome)
        assertEquals(1, writes)
    }

    @Test fun successfulWriteAndRefreshKeepNormalBehaviour() = runTest(dispatcher) {
        val model = model()
        edit(model)
        model.saveShift()
        runCurrent()
        assertEquals(SaveOutcome.CONFIRMED, model.state.value.saveOutcome)
        assertNull(model.state.value.message)
        assertNull(model.state.value.editor)
        assertTrue(model.state.value.editorReady)
        assertEquals(persisted, model.state.value.days)
        assertEquals(1, writes)
    }

    @Test fun unknownResultNeverRetriesWriteAndReadPreservesDraftForReview() = runTest(dispatcher) {
        val model = model()
        edit(model)
        val draft = model.state.value.editor
        commitBeforeFailure = true
        writeFailure = IOException("Synthetic lost acknowledgement")
        model.saveShift()
        runCurrent()
        assertEquals(SaveOutcome.UNKNOWN, model.state.value.saveOutcome)
        assertEquals(draft, model.state.value.editor)
        assertTrue(model.state.value.message!!.contains("No se pudo determinar"))
        assertFalse(model.state.value.editorReady)
        model.saveShift()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, writes)
        model.refreshCalendar()
        runCurrent()
        assertEquals(persisted, model.state.value.days)
        assertEquals(draft, model.state.value.editor)
        assertEquals(SaveOutcome.UNKNOWN, model.state.value.saveOutcome)
        assertFalse(model.state.value.editorReady)
        model.saveShift()
        runCurrent()
        assertEquals(1, writes)
    }

    @Test fun cancelledWritePreservesDraftAndDoesNotShowFailureOrRetry() = runTest(dispatcher) {
        val model = model()
        edit(model)
        val draft = model.state.value.editor
        writeFailure = CancellationException("Synthetic cancellation")
        model.saveShift()
        runCurrent()
        assertEquals(draft, model.state.value.editor)
        assertNull(model.state.value.message)
        assertEquals(SaveOutcome.UNKNOWN, model.state.value.saveOutcome)
        assertFalse(model.state.value.busy)
        model.saveShift()
        runCurrent()
        assertEquals(1, writes)
    }

    @Test fun observerRecoveryDoesNotDiscardAnOpenDraft() = runTest(dispatcher) {
        val model = model()
        edit(model)
        val draft = model.state.value.editor
        failObservation = true
        emissions.emit(Unit)
        runCurrent()
        model.retryCalendarObservation()
        runCurrent()
        failObservation = false
        persisted = rows.map { it.copy(day = it.day.copy(revision = 9)) }
        emissions.emit(Unit)
        runCurrent()
        assertEquals(persisted, model.state.value.days)
        assertNull(model.state.value.observationError)
        assertEquals(2, collections)
        assertEquals(1, active)
        assertEquals(draft, model.state.value.editor)
        assertEquals(0, writes)
        assertEquals(1, maximumActive)
    }

    @Test fun unknownFailureBeforeCommitAlsoRequiresReviewInsteadOfClaimingRollback() = runTest(dispatcher) {
        val model = model()
        edit(model)
        val draft = model.state.value.editor
        writeFailure = IOException("Synthetic unavailable storage")
        model.saveShift()
        runCurrent()
        assertEquals(SaveOutcome.UNKNOWN, model.state.value.saveOutcome)
        assertTrue(persisted.isEmpty())
        model.refreshCalendar()
        runCurrent()
        assertEquals(draft, model.state.value.editor)
        assertEquals(SaveOutcome.UNKNOWN, model.state.value.saveOutcome)
        model.saveShift()
        runCurrent()
        assertEquals(1, writes)
    }

    @Test fun cancellingPostCommitRefreshNeverChangesConfirmedOutcome() = runTest(dispatcher) {
        val model = model()
        edit(model)
        readFailure = CancellationException("Synthetic cancelled refresh")
        model.saveShift()
        runCurrent()
        assertEquals(SaveOutcome.CONFIRMED, model.state.value.saveOutcome)
        assertNull(model.state.value.message)
        assertTrue(model.state.value.needsReconciliation)
        assertFalse(model.state.value.editorReady)
        assertEquals(1, writes)
        readFailure = null
        model.refreshCalendar()
        runCurrent()
        assertEquals(persisted, model.state.value.days)
        assertEquals(1, writes)
    }

    @Test fun clearingDuringSuspendedWriteCancelsWithoutFalseFailureAndKeepsDraft() = runTest(dispatcher) {
        val model = model()
        edit(model)
        val draft = model.state.value.editor
        writeGate = CompletableDeferred()
        model.saveShift()
        runCurrent()
        assertTrue(model.state.value.busy)
        store.clear()
        runCurrent()
        assertEquals(draft, model.state.value.editor)
        assertNull(model.state.value.message)
        assertFalse(model.state.value.busy)
        assertEquals(SaveOutcome.UNKNOWN, model.state.value.saveOutcome)
        assertEquals(1, writes)
        assertTrue(persisted.isEmpty())
    }

    private fun prepareOcr(model: CalendarViewModel, editDecisions: Boolean = true) {
        val parsed = TimeCellParser.parse("08:15-12:30")
        val source = ProvisionalDay(date, "10/03/2032", "08:15-12:30", DayKind.WORK, parsed.intervals, InterpretationStatus.CORRECTED, emptyList())
        model.prepareImport(listOf(ReviewDayDraft(source, date.toString(), "08:15-12:30")))
        dispatcher.scheduler.runCurrent()
        if (!editDecisions) return
        model.editImport(0, text = "09:00-13:00", choice = ImportChoice.EDIT)
        model.editImport(0, confirmed = true)
    }

    @Test fun uncertainOcrPreservesDecisionsAndCannotRepeatUntilASeparateReviewedOperation() = runTest(dispatcher) {
        val model = model()
        prepareOcr(model)
        val drafts = model.state.value.imports
        commitBeforeFailure = true
        writeFailure = IOException("Synthetic OCR acknowledgement lost")
        model.saveImport()
        runCurrent()
        assertEquals(SaveOutcome.UNKNOWN, model.state.value.saveOutcome)
        assertEquals(listOf("OCR_REVIEWED"), origins)
        assertEquals(drafts, model.state.value.imports)
        assertFalse(model.state.value.importReady)
        model.saveImport()
        model.editImport(0, confirmed = false)
        prepareOcr(model) // Re-entering the screen must not bypass the unresolved operation.
        runCurrent()
        assertEquals(drafts, model.state.value.imports)
        assertEquals(1, writes)
        model.refreshCalendar()
        runCurrent()
        val reconciliation = model.state.value.reconciliation!!
        assertEquals(drafts, model.state.value.imports)
        assertTrue(reconciliation.fromImport)
        assertTrue(reconciliation.comparisons()!!.single().matches)
        assertEquals("09:00", reconciliation.comparisons()!!.single().stored!!.intervals.single().start.toString())
        assertEquals(SaveOutcome.UNKNOWN, model.state.value.saveOutcome)
        model.reviewReconciliation()
        model.saveImport()
        runCurrent()
        assertTrue(model.state.value.reconciliation!!.reviewed)
        assertEquals(drafts, model.state.value.imports)
        assertEquals(1, writes)
        writeFailure = null
        prepareOcr(model) // Explicit new review captures a fresh snapshot and uses the original confirmation policy.
        model.saveImport()
        runCurrent()
        assertEquals(SaveOutcome.CONFIRMED, model.state.value.saveOutcome)
        assertTrue(model.state.value.importSaved)
        assertEquals(2, writes)
    }

    @Test fun ocrKnownRejectionPreservesDecisionsAndSuccessfulSaveIsConfirmed() = runTest(dispatcher) {
        val model = model()
        prepareOcr(model)
        val drafts = model.state.value.imports
        writeFailure = ScheduleChangedException()
        model.saveImport()
        runCurrent()
        assertEquals(SaveOutcome.REJECTED, model.state.value.saveOutcome)
        assertEquals(drafts, model.state.value.imports)
        assertFalse(model.state.value.importSaved)
        assertTrue(persisted.isEmpty())
        writeFailure = null
        model.saveImport()
        runCurrent()
        assertEquals(SaveOutcome.CONFIRMED, model.state.value.saveOutcome)
        assertTrue(model.state.value.importSaved)
    }

    @Test fun confirmedThenReloadFailureKeepsTheConfirmedOutcomeAndMessage() = runTest(dispatcher) {
        val model = model()
        edit(model)
        model.saveShift()
        runCurrent()
        readFailure = IOException("Synthetic reload failure")
        model.select(date)
        runCurrent()
        assertEquals(SaveOutcome.CONFIRMED, model.state.value.saveOutcome)
        assertTrue(model.state.value.message!!.contains("guardado se completó"))
        assertFalse(model.state.value.message!!.contains("No se ha confirmado"))
        assertEquals(1, writes)
    }

    @Test fun unknownReloadPreservesDraftAndComparisonBeforeExplicitNewEditing() = runTest(dispatcher) {
        val model = model()
        edit(model)
        val draft = model.state.value.editor
        commitBeforeFailure = true
        writeFailure = IOException("Synthetic uncertain write")
        model.saveShift()
        runCurrent()
        model.select(date)
        runCurrent()
        assertEquals(draft, model.state.value.editor)
        val comparison = model.state.value.reconciliation!!.comparisons()!!.single()
        assertTrue(comparison.matches)
        assertEquals(comparison.proposed, comparison.stored)
        assertEquals(SaveOutcome.UNKNOWN, model.state.value.saveOutcome)
        model.cancelShift() // The dialog can be hidden without discarding the unreviewed draft.
        assertEquals(draft, model.state.value.editor)
        assertTrue(model.state.value.editorHidden)
        model.openShift()
        assertFalse(model.state.value.editorHidden)
        model.reviewReconciliation()
        assertEquals(draft, model.state.value.editor)
        assertFalse(model.state.value.editorReady)
        model.saveShift()
        assertEquals(1, writes)
        writeFailure = null
        model.select(date) // Explicit reload after reviewing starts a new editing session, not another write.
        runCurrent()
        assertNull(model.state.value.reconciliation)
        assertTrue(model.state.value.editorReady)
        assertEquals(SaveOutcome.UNKNOWN, model.state.value.saveOutcome)
        assertEquals(1, writes)
    }

    @Test fun reconciliationCanShowDifferenceAndDoesNotInventWriteSuccess() = runTest(dispatcher) {
        val model = model()
        edit(model)
        writeFailure = IOException("Synthetic unavailable storage")
        model.saveShift()
        runCurrent()
        model.reviewReconciliation() // Not enabled before a successful read.
        assertFalse(model.state.value.reconciliation!!.reviewed)
        model.refreshCalendar()
        runCurrent()
        val comparison = model.state.value.reconciliation!!.comparisons()!!.single()
        assertFalse(comparison.matches)
        assertNull(comparison.stored)
        assertNotNull(comparison.proposed)
        assertEquals(SaveOutcome.UNKNOWN, model.state.value.saveOutcome)
        assertEquals(1, writes)
    }

    @Test fun failedAndCancelledReconciliationPreserveOcrDraftAndOutcome() = runTest(dispatcher) {
        val model = model()
        prepareOcr(model)
        val drafts = model.state.value.imports
        writeFailure = CancellationException("Synthetic cancelled OCR write")
        model.saveImport()
        runCurrent()
        assertEquals(SaveOutcome.UNKNOWN, model.state.value.saveOutcome)
        assertNull(model.state.value.message)
        readFailure = IOException("Synthetic failed reconciliation")
        model.refreshCalendar()
        runCurrent()
        assertTrue(model.state.value.message!!.contains("incierto"))
        val message = model.state.value.message
        readFailure = CancellationException("Synthetic cancelled reconciliation")
        model.refreshCalendar()
        runCurrent()
        assertEquals(message, model.state.value.message)
        assertEquals(drafts, model.state.value.imports)
        assertFalse(model.state.value.busy)
        assertEquals(SaveOutcome.UNKNOWN, model.state.value.saveOutcome)
        assertEquals(1, writes)
    }

    @Test fun programmingErrorInReadIsPropagatedInsteadOfAStorageMessage() {
        val failure = IllegalStateException("Synthetic programming error")
        val thrown = assertThrows(IllegalStateException::class.java) {
            runTest(dispatcher) {
                val model = model()
                runCurrent()
                readFailure = failure
                model.select(date)
                runCurrent()
            }
        }
        assertSame(failure, thrown)
    }

    @Test fun closingDuringSuspendedReconciliationPreservesRequestWithoutWriting() = runTest(dispatcher) {
        val model = model()
        prepareOcr(model)
        val drafts = model.state.value.imports
        writeFailure = IOException("Synthetic uncertain OCR write")
        model.saveImport()
        runCurrent()
        val message = model.state.value.message
        val request = model.state.value.reconciliation
        readGate = CompletableDeferred()
        model.refreshCalendar()
        runCurrent()
        assertTrue(model.state.value.busy)
        store.clear()
        runCurrent()
        assertEquals(drafts, model.state.value.imports)
        assertEquals(request, model.state.value.reconciliation)
        assertEquals(message, model.state.value.message)
        assertFalse(model.state.value.busy)
        assertEquals(0, active)
        assertEquals(1, writes)
    }

    @Test fun ocrPreparationReadFailureAfterConfirmedWriteDoesNotClaimNoChange() = runTest(dispatcher) {
        val model = model()
        prepareOcr(model)
        model.saveImport()
        runCurrent()
        assertEquals(SaveOutcome.CONFIRMED, model.state.value.saveOutcome)
        readFailure = IOException("Synthetic failed confirmation read")
        prepareOcr(model, editDecisions = false)
        runCurrent()
        assertEquals(SaveOutcome.CONFIRMED, model.state.value.saveOutcome)
        assertTrue(model.state.value.message!!.contains("guardado se completó"))
        assertFalse(model.state.value.message!!.contains("No se ha confirmado"))
        assertEquals(1, writes)
    }

    @Test fun unexpectedWriteProgrammingErrorIsPropagatedWithoutAllowingRepeat() {
        val failure = IllegalStateException("Synthetic programming error")
        val thrown = assertThrows(IllegalStateException::class.java) {
            runTest(dispatcher) {
                val model = model()
                edit(model)
                writeFailure = failure
                model.saveShift()
                runCurrent()
                assertFalse(model.state.value.editorReady)
                assertNotNull(model.state.value.reconciliation)
            }
        }
        assertSame(failure, thrown)
        assertEquals(1, writes)
    }

    @Test fun uncertainDeletionComparesStoredAbsenceWithoutASecondDelete() = runTest(dispatcher) {
        persisted = rows
        val model = model()
        model.select(date)
        runCurrent()
        commitBeforeFailure = true
        writeFailure = IOException("Synthetic lost delete acknowledgement")
        model.deleteDay()
        runCurrent()
        model.refreshCalendar()
        runCurrent()
        val entry = model.state.value.reconciliation!!.comparisons()!!.single()
        assertNull(entry.proposed)
        assertNull(entry.stored)
        assertTrue(entry.matches)
        assertEquals(SaveOutcome.UNKNOWN, model.state.value.saveOutcome)
        model.deleteDay()
        runCurrent()
        assertEquals(1, writes)
    }
}
