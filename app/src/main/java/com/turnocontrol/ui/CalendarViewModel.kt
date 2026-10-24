package com.turnocontrol.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.turnocontrol.data.*
import com.turnocontrol.domain.*
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.YearMonth
import java.time.LocalTime
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import android.database.sqlite.SQLiteException
import java.io.IOException

enum class ImportChoice { KEEP, REPLACE, EDIT }
data class ImportDayChoice(val date: String, val text: String, val warnings: List<String>, val choice: ImportChoice = ImportChoice.KEEP, val confirmed: Boolean = false)
data class IntervalDraft(val start: LocalTime? = null, val end: LocalTime? = null, val startOffset: Int = 0, val endOffset: Int = 0, val extra: Boolean = false)
data class ShiftEditor(val split: Boolean = false, val slots: List<IntervalDraft> = listOf(IntervalDraft()), val editingIndex: Int? = null)
enum class SaveOutcome { NONE, REJECTED, CONFIRMED, UNKNOWN }
data class ReconciliationEntry(val date: LocalDate, val proposed: PlannedDay?, val stored: PlannedDay?) {
    val matches: Boolean get() = proposed == stored
}
/** A later read is evidence for comparison, never proof of the earlier transaction's outcome. */
data class WriteReconciliation(
    val replacements: List<PlannedDay>, val deletions: Set<LocalDate>, val fromImport: Boolean,
    val recovered: List<StoredScheduleDay>? = null, val reviewed: Boolean = false,
) {
    fun comparisons(): List<ReconciliationEntry>? = recovered?.let { rows ->
        (replacements.map { it.date } + deletions).distinct().sorted().map { date ->
            ReconciliationEntry(date, replacements.firstOrNull { it.date == date }, rows.firstOrNull { it.day.date == date.toString() }?.planned())
        }
    }
}
data class CalendarState(
    val month: YearMonth = YearMonth.now(), val days: List<StoredScheduleDay> = emptyList(), val loading: Boolean = true,
    val selected: LocalDate? = null, val focused: LocalDate = LocalDate.now(), val week: Boolean = false,
    val editor: ShiftEditor? = null, val emptyChoice: Boolean = false,
    val busy: Boolean = false, val editorReady: Boolean = false, val message: String? = null,
    val imports: List<ImportDayChoice> = emptyList(), val importReady: Boolean = false, val importSaved: Boolean = false,
    val observationError: String? = null, val saveOutcome: SaveOutcome = SaveOutcome.NONE, val needsReconciliation: Boolean = false,
    val reconciliation: WriteReconciliation? = null, val editorHidden: Boolean = false,
)

fun PlannedDay.rangeText() = if (kind == DayKind.FREE) "LIBRE" else intervals.joinToString(" / ") { "${it.start}${if (it.startDayOffset == 1) " (+1 día)" else ""}–${it.end}${if (it.endDayOffset == 1) " (+1 día)" else ""}${if (it.isExtra) " · Extra declarada" else ""}" }

@HiltViewModel
class CalendarViewModel internal constructor(
    private val days: Flow<List<StoredScheduleDay>>,
    private val readSnapshot: suspend () -> List<StoredScheduleDay>,
    private val writeDays: suspend (List<StoredScheduleDay>, List<PlannedDay>, Set<LocalDate>, String) -> Unit,
) : ViewModel() {
    // Internal callbacks allow deterministic storage failures in tests; production still uses the same repository.
    @Inject constructor(repository: ScheduleRepository) : this(repository.days, repository::snapshot,
        { expected, replacements, deletions, origin -> repository.save(expected, replacements, deletions, origin) })
    private val mutable = MutableStateFlow(CalendarState())
    val state = mutable.asStateFlow()
    private var expected: List<StoredScheduleDay> = emptyList()
    private var importExpected: List<StoredScheduleDay> = emptyList()
    private var observationJob: Job? = null
    init { retryCalendarObservation() }
    fun retryCalendarObservation() {
        if (observationJob?.isActive == true) return
        mutable.update { it.copy(loading = true, observationError = null) }
        observationJob = viewModelScope.launch {
            try { days.collect { rows -> mutable.update { it.copy(days = rows, loading = false, observationError = null) } } }
            catch (e: Exception) {
                if (e !is IOException && e !is SQLiteException) throw e
                mutable.update { it.copy(loading = false, observationError = "No se pudo observar el calendario. Se conserva la última vista disponible.") }
            }
        }
    }
    fun moveMonth(delta: Long) { mutable.update {
        val target = it.month.plusMonths(delta)
        it.copy(month = target, focused = target.atDay(it.focused.dayOfMonth.coerceAtMost(target.lengthOfMonth())))
    } }
    fun move(delta: Long) { if (state.value.week) mutable.update { val target = it.focused.plusWeeks(delta); it.copy(focused = target, month = YearMonth.from(target)) } else moveMonth(delta) }
    fun week(value: Boolean) { mutable.update { it.copy(week = value) } }
    fun today() { mutable.update { it.copy(month = YearMonth.now(), focused = LocalDate.now()) } }
    fun weekStart() = state.value.focused.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    fun select(date: LocalDate) {
        if (state.value.busy) return
        if (state.value.reconciliation?.reviewed == false) { refreshCalendar(); return }
        mutable.update { it.copy(selected = date, focused = date, month = YearMonth.from(date), editor = null, editorHidden = false,
            reconciliation = null, emptyChoice = false, busy = true, editorReady = false, message = null) }
        viewModelScope.launch {
            try {
                expected = readSnapshot()
                mutable.update { it.copy(days = expected, editorReady = true, needsReconciliation = false) }
            } catch (e: Exception) { reportRead(e) }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun closeEditor() {
        if (state.value.busy) return
        if (state.value.reconciliation?.reviewed == false) { mutable.update { it.copy(editorHidden = true) }; return }
        mutable.update { it.copy(selected = null, editor = null, reconciliation = null, editorReady = false, message = null) }
    }
    fun openShift(index: Int? = null) {
        if (state.value.reconciliation != null) { mutable.update { it.copy(editorHidden = false) }; return }
        if (!state.value.editorReady || state.value.busy) return
        val interval = index?.let { expected.firstOrNull { day -> day.day.date == state.value.selected.toString() }?.planned()?.intervals?.getOrNull(it) }
        mutable.update { it.copy(message = null, editor = ShiftEditor(editingIndex = index, slots = listOf(interval?.let { i -> IntervalDraft(i.start, i.end, i.startDayOffset, i.endDayOffset, i.isExtra) } ?: IntervalDraft()))) }
    }
    fun cancelShift() {
        if (state.value.busy) return
        if (state.value.reconciliation?.reviewed == false) { mutable.update { it.copy(editorHidden = true) }; return }
        mutable.update { it.copy(editor = null, reconciliation = null, message = null) }
    }
    fun split(value: Boolean) { if (state.value.reconciliation != null) return; mutable.update { s -> s.copy(editor = s.editor?.copy(split = value, slots = if (value) listOf(s.editor.slots.first(), IntervalDraft()) else listOf(s.editor.slots.first())), message = null) } }
    fun slot(index: Int, value: IntervalDraft) { if (state.value.reconciliation != null) return; mutable.update { s -> s.copy(editor = s.editor?.copy(slots = s.editor.slots.mapIndexed { i, old -> if (i == index) value else old }), message = null) } }
    fun saveShift() {
        val input = state.value
        val date = input.selected ?: return
        val editor = input.editor ?: return
        if (input.busy || !input.editorReady || input.reconciliation != null) return
        try {
            val intervals = editor.slots.map { draft ->
                require(draft.start != null && draft.end != null) { "Elige la entrada y salida de cada intervalo." }
                ProvisionalInterval(draft.start, draft.end, draft.startOffset, draft.endOffset, draft.extra)
            }
            val previous = expected.firstOrNull { it.day.date == date.toString() }?.planned()?.intervals.orEmpty().toMutableList()
            if (editor.editingIndex != null) previous[editor.editingIndex] = intervals.single() else previous.addAll(intervals)
            persist(listOf(PlannedDay(date, DayKind.WORK, previous.sortedBy(PlannedScheduleValidator::start))))
        } catch (e: IllegalArgumentException) { mutable.update { it.copy(message = e.message) } }
    }
    fun markFree() { state.value.selected?.let { persist(listOf(PlannedDay(it, DayKind.FREE, emptyList()))) } }
    fun deleteDay() { state.value.selected?.let { persist(emptyList(), setOf(it)) } }
    fun deleteInterval(index: Int) {
        if (state.value.busy || !state.value.editorReady || state.value.reconciliation != null) return
        val date = state.value.selected ?: return
        val old = expected.firstOrNull { it.day.date == date.toString() }?.planned() ?: return
        val remaining = old.intervals.filterIndexed { i, _ -> i != index }
        if (remaining.isEmpty()) mutable.update { it.copy(emptyChoice = true) } else persist(listOf(old.copy(intervals = remaining)))
    }
    fun cancelEmptyChoice() { mutable.update { it.copy(emptyChoice = false) } }
    /** Shared write boundary for manual and OCR operations. No write is retried here. */
    private suspend fun write(expectedRows: List<StoredScheduleDay>, intent: WriteReconciliation): Boolean {
        mutable.update { it.copy(saveOutcome = SaveOutcome.NONE) }
        try {
            writeDays(expectedRows, intent.replacements, intent.deletions, if (intent.fromImport) "OCR_REVIEWED" else "MANUAL")
            mutable.update { it.copy(saveOutcome = SaveOutcome.CONFIRMED) }
            return true
        } catch (e: Exception) {
            if (e is IllegalArgumentException || e is ScheduleChangedException) {
                mutable.update { it.copy(saveOutcome = SaveOutcome.REJECTED, message = e.message) }
            } else {
                mutable.update { it.copy(saveOutcome = SaveOutcome.UNKNOWN, reconciliation = intent,
                    editorReady = false, importReady = false, needsReconciliation = true, emptyChoice = false,
                    message = if (e is CancellationException) null else "No se pudo determinar si el cambio se guardó. Lee el calendario y compara los datos antes de decidir otra operación.") }
                // Do not hide cancellation or programming errors behind a storage message.
                if (e !is IOException && e !is SQLiteException) throw e
            }
            return false
        }
    }
    private fun persist(replacements: List<PlannedDay>, deletions: Set<LocalDate> = emptySet()) {
        if (state.value.busy || !state.value.editorReady || state.value.reconciliation != null) return
        mutable.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                if (!write(expected, WriteReconciliation(replacements, deletions, fromImport = false))) return@launch
                mutable.update { it.copy(editor = null, emptyChoice = false, editorReady = false) }
                try {
                    expected = readSnapshot()
                    mutable.update { it.copy(days = expected, editorReady = true, needsReconciliation = false) }
                } catch (e: CancellationException) {
                    mutable.update { it.copy(needsReconciliation = true) }
                    throw e
                } catch (e: Exception) { reportRead(e) }
            } finally { mutable.update { it.copy(busy = false) } }
        }
    }
    /** Read only: preserve both the original request and its drafts, even after a matching snapshot. */
    fun refreshCalendar() {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val rows = readSnapshot()
                val reconciliation = state.value.reconciliation
                if (reconciliation == null) expected = rows
                mutable.update { it.copy(days = rows, needsReconciliation = false,
                    reconciliation = reconciliation?.copy(recovered = rows, reviewed = false),
                    editorReady = reconciliation == null && it.selected != null,
                    message = if (reconciliation != null) "Datos leídos para comparar. El resultado del guardado sigue siendo incierto; no se ha repetido la escritura."
                    else if (it.saveOutcome == SaveOutcome.CONFIRMED) "Calendario actualizado. El guardado anterior se completó." else null) }
            } catch (e: Exception) { reportRead(e) }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun reviewReconciliation() {
        if (state.value.busy) return
        mutable.update { state ->
            val reconciliation = state.reconciliation ?: return@update state
            if (reconciliation.recovered == null) state else state.copy(reconciliation = reconciliation.copy(reviewed = true),
                message = "Comparación revisada. El resultado anterior sigue siendo incierto. No se repetirá ese guardado; para otra operación, vuelve a abrir el día o la revisión OCR.")
        }
    }
    private fun reportRead(e: Exception) {
        if (e !is IOException && e !is SQLiteException) throw e
        mutable.update { it.copy(needsReconciliation = true, message = when (it.saveOutcome) {
            SaveOutcome.CONFIRMED -> "El guardado se completó, pero no se pudo actualizar la vista. Puedes volver a leer sin guardar."
            SaveOutcome.UNKNOWN -> "El resultado del guardado sigue siendo incierto y no se pudo actualizar la vista. No se ha repetido la escritura."
            else -> "No se pudo leer el calendario. Se conservan los últimos datos y borradores disponibles."
        }) }
    }
    private fun parsed(date: String, text: String): PlannedDay {
        val d = runCatching { LocalDate.parse(date.trim()) }.getOrElse { throw IllegalArgumentException("Indica una fecha válida AAAA-MM-DD.") }
        val times = TimeCellParser.parse(text)
        require(times.kind != DayKind.UNKNOWN) { "Indica intervalos completos HH:mm-HH:mm, sin solapamientos, o LIBRE." }
        return PlannedDay(d, times.kind, times.intervals)
    }
    fun prepareImport(drafts: List<ReviewDayDraft>) {
        if (state.value.busy || state.value.reconciliation?.reviewed == false) return
        mutable.update { it.copy(busy = true, reconciliation = null, importReady = false, importSaved = false, imports = emptyList(), message = null) }
        viewModelScope.launch {
            try {
                importExpected = readSnapshot()
                mutable.update { it.copy(importReady = true, imports = drafts.map { draft ->
                    ImportDayChoice(draft.dateText, draft.timeText, (draft.source.issues + draft.originalIssues).distinct().map { issue -> issue.message })
                }) }
            } catch (e: Exception) { reportRead(e) }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun existingImport(date: String) = runCatching { LocalDate.parse(date.trim()).toString() }.getOrNull()?.let { normalized -> importExpected.firstOrNull { it.day.date == normalized }?.planned() }
    fun editImport(index: Int, date: String? = null, text: String? = null, choice: ImportChoice? = null, confirmed: Boolean? = null) {
        if (state.value.reconciliation != null) return
        mutable.update { s -> s.copy(message = null, imports = s.imports.mapIndexed { i, old ->
            if (i != index) old else old.copy(date = date ?: old.date, text = text ?: old.text, choice = choice ?: old.choice,
                confirmed = confirmed ?: if (date != null || text != null || choice != null) false else old.confirmed)
        }) }
    }
    fun saveImport() {
        val input = state.value
        if (input.busy || !input.importReady || input.reconciliation != null) return
        val proposed = try {
            val chosen = input.imports.filter { it.confirmed && it.choice != ImportChoice.KEEP }
            require(chosen.isNotEmpty()) { "Selecciona y confirma al menos un día revisado para guardar." }
            chosen.map { parsed(it.date, it.text) }
        } catch (e: IllegalArgumentException) {
            mutable.update { it.copy(saveOutcome = SaveOutcome.REJECTED, message = e.message) }
            return
        }
        mutable.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                if (!write(importExpected, WriteReconciliation(proposed, emptySet(), fromImport = true))) return@launch
                mutable.update { it.copy(importSaved = true, importReady = false, imports = emptyList(), month = YearMonth.from(proposed.first().date), focused = proposed.first().date,
                    week = false, selected = null, editor = null, message = "Días revisados guardados en el calendario.") }
            } finally { mutable.update { it.copy(busy = false) } }
        }
    }
}
