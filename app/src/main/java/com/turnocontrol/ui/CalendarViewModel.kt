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
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class ImportChoice { KEEP, REPLACE, EDIT }
data class ImportDayChoice(val date: String, val text: String, val warnings: List<String>, val choice: ImportChoice = ImportChoice.KEEP, val confirmed: Boolean = false)
data class IntervalDraft(val start: LocalTime? = null, val end: LocalTime? = null, val startOffset: Int = 0, val endOffset: Int = 0, val extra: Boolean = false)
data class ShiftEditor(val split: Boolean = false, val slots: List<IntervalDraft> = listOf(IntervalDraft()), val editingIndex: Int? = null)
data class CalendarState(
    val month: YearMonth = YearMonth.now(), val days: List<StoredScheduleDay> = emptyList(), val loading: Boolean = true,
    val selected: LocalDate? = null, val focused: LocalDate = LocalDate.now(), val week: Boolean = false,
    val editor: ShiftEditor? = null, val emptyChoice: Boolean = false,
    val busy: Boolean = false, val editorReady: Boolean = false, val message: String? = null,
    val imports: List<ImportDayChoice> = emptyList(), val importReady: Boolean = false, val importSaved: Boolean = false,
)

fun PlannedDay.rangeText() = if (kind == DayKind.FREE) "LIBRE" else intervals.joinToString(" / ") { "${it.start}${if (it.startDayOffset == 1) " (+1 día)" else ""}–${it.end}${if (it.endDayOffset == 1) " (+1 día)" else ""}${if (it.isExtra) " · Extra declarada" else ""}" }

@HiltViewModel
class CalendarViewModel @Inject constructor(private val repository: ScheduleRepository) : ViewModel() {
    private val mutable = MutableStateFlow(CalendarState())
    val state = mutable.asStateFlow()
    private var expected: List<StoredScheduleDay> = emptyList()
    private var importExpected: List<StoredScheduleDay> = emptyList()
    init { viewModelScope.launch {
        try { repository.days.collect { rows -> mutable.update { it.copy(days = rows, loading = false) } } }
        catch (e: Exception) { if (e is CancellationException) throw e; mutable.update { it.copy(loading = false, message = "No se pudo leer el calendario.") } }
    } }
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
        mutable.update { it.copy(selected = date, focused = date, month = YearMonth.from(date), editor = null, emptyChoice = false, busy = true, editorReady = false, message = null) }
        viewModelScope.launch {
            try {
                expected = repository.snapshot()
                mutable.update { it.copy(editorReady = true) }
            } catch (e: Exception) { report(e) }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun closeEditor() { if (!state.value.busy) mutable.update { it.copy(selected = null, editor = null, editorReady = false, message = null) } }
    fun openShift(index: Int? = null) {
        if (!state.value.editorReady || state.value.busy) return
        val interval = index?.let { expected.firstOrNull { day -> day.day.date == state.value.selected.toString() }?.planned()?.intervals?.getOrNull(it) }
        mutable.update { it.copy(message = null, editor = ShiftEditor(editingIndex = index, slots = listOf(interval?.let { i -> IntervalDraft(i.start, i.end, i.startDayOffset, i.endDayOffset, i.isExtra) } ?: IntervalDraft()))) }
    }
    fun cancelShift() { if (!state.value.busy) mutable.update { it.copy(editor = null, message = null) } }
    fun split(value: Boolean) { mutable.update { s -> s.copy(editor = s.editor?.copy(split = value, slots = if (value) listOf(s.editor.slots.first(), IntervalDraft()) else listOf(s.editor.slots.first())), message = null) } }
    fun slot(index: Int, value: IntervalDraft) { mutable.update { s -> s.copy(editor = s.editor?.copy(slots = s.editor.slots.mapIndexed { i, old -> if (i == index) value else old }), message = null) } }
    fun saveShift() {
        val input = state.value
        val date = input.selected ?: return
        val editor = input.editor ?: return
        if (input.busy || !input.editorReady) return
        try {
            val intervals = editor.slots.map { draft ->
                require(draft.start != null && draft.end != null) { "Elige la entrada y salida de cada intervalo." }
                ProvisionalInterval(draft.start, draft.end, draft.startOffset, draft.endOffset, draft.extra)
            }
            val previous = expected.firstOrNull { it.day.date == date.toString() }?.planned()?.intervals.orEmpty().toMutableList()
            if (editor.editingIndex != null) previous[editor.editingIndex] = intervals.single() else previous.addAll(intervals)
            persist(listOf(PlannedDay(date, DayKind.WORK, previous.sortedBy(PlannedScheduleValidator::start))))
        } catch (e: Exception) { report(e) }
    }
    fun markFree() { state.value.selected?.let { persist(listOf(PlannedDay(it, DayKind.FREE, emptyList()))) } }
    fun deleteDay() { state.value.selected?.let { persist(emptyList(), setOf(it)) } }
    fun deleteInterval(index: Int) {
        val date = state.value.selected ?: return
        val old = expected.firstOrNull { it.day.date == date.toString() }?.planned() ?: return
        val remaining = old.intervals.filterIndexed { i, _ -> i != index }
        if (remaining.isEmpty()) mutable.update { it.copy(emptyChoice = true) } else persist(listOf(old.copy(intervals = remaining)))
    }
    fun cancelEmptyChoice() { mutable.update { it.copy(emptyChoice = false) } }
    private fun persist(replacements: List<PlannedDay>, deletions: Set<LocalDate> = emptySet()) {
        if (state.value.busy || !state.value.editorReady) return
        mutable.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                repository.save(expected, replacements, deletions)
                expected = repository.snapshot()
                mutable.update { it.copy(days = expected, editor = null, emptyChoice = false, message = null) }
            } catch (e: Exception) { report(e) }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    private fun parsed(date: String, text: String): PlannedDay {
        val d = runCatching { LocalDate.parse(date.trim()) }.getOrElse { throw IllegalArgumentException("Indica una fecha válida AAAA-MM-DD.") }
        val times = TimeCellParser.parse(text)
        require(times.kind != DayKind.UNKNOWN) { "Indica intervalos completos HH:mm-HH:mm, sin solapamientos, o LIBRE." }
        return PlannedDay(d, times.kind, times.intervals)
    }
    fun prepareImport(drafts: List<ReviewDayDraft>) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, importReady = false, importSaved = false, imports = emptyList(), message = null) }
        viewModelScope.launch {
            try {
                importExpected = repository.snapshot()
                mutable.update { it.copy(importReady = true, imports = drafts.map { draft ->
                    ImportDayChoice(draft.dateText, draft.timeText, (draft.source.issues + draft.originalIssues).distinct().map { issue -> issue.message })
                }) }
            } catch (e: Exception) { report(e) }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun existingImport(date: String) = runCatching { LocalDate.parse(date.trim()).toString() }.getOrNull()?.let { normalized -> importExpected.firstOrNull { it.day.date == normalized }?.planned() }
    fun editImport(index: Int, date: String? = null, text: String? = null, choice: ImportChoice? = null, confirmed: Boolean? = null) {
        mutable.update { s -> s.copy(message = null, imports = s.imports.mapIndexed { i, old ->
            if (i != index) old else old.copy(date = date ?: old.date, text = text ?: old.text, choice = choice ?: old.choice,
                confirmed = confirmed ?: if (date != null || text != null || choice != null) false else old.confirmed)
        }) }
    }
    fun saveImport() {
        val input = state.value
        if (input.busy || !input.importReady) return
        mutable.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                val chosen = input.imports.filter { it.confirmed && it.choice != ImportChoice.KEEP }
                require(chosen.isNotEmpty()) { "Selecciona y confirma al menos un día revisado para guardar." }
                val proposed = chosen.map { parsed(it.date, it.text) }
                repository.save(importExpected, proposed, origin = "OCR_REVIEWED")
                mutable.update { it.copy(importSaved = true, importReady = false, imports = emptyList(), month = YearMonth.from(proposed.first().date), focused = proposed.first().date,
                    week = false, selected = null, editor = null, message = "Días revisados guardados en el calendario.") }
            } catch (e: Exception) { report(e) }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    private fun report(e: Exception) {
        if (e is CancellationException) throw e
        mutable.update { it.copy(message = if (e is IllegalArgumentException || e is ScheduleChangedException) e.message else "No se pudo completar la operación. No se ha confirmado ningún cambio.") }
    }
}
