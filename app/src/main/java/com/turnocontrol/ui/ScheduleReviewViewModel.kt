package com.turnocontrol.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.turnocontrol.domain.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

/** "Reviewed" is a decision held in memory only; nothing is written until the final confirmation. */
enum class ReviewStatus(val label: String) { PENDING("Pendiente de revisar"), REVIEWED("Revisado"), INVALID("Error de validación") }
data class IntervalText(val start: String = "", val end: String = "")
data class DayEditor(val date: String, val free: Boolean, val intervals: List<IntervalText>)

data class ReviewDayDraft(
    val source: ProvisionalDay,
    val dateText: String,
    val timeText: String,
    val reviewed: Boolean = false,
    val included: Boolean = true,
    val edited: Boolean = false,
    val editor: DayEditor? = null,
    val planned: PlannedDay? = null,
    val error: String? = null,
    val originalIssues: List<ScheduleIssue> = source.issues,
) {
    val status: ReviewStatus get() = when { error != null -> ReviewStatus.INVALID; reviewed -> ReviewStatus.REVIEWED; else -> ReviewStatus.PENDING }
    val canReview: Boolean get() = included && editor == null && error == null
}
data class ScheduleReviewState(
    val name: String = "", val alias: String = "", val month: String = "",
    val loading: Boolean = false, val result: ScheduleResult? = null, val documentWidth: Int = 0, val documentHeight: Int = 0,
    val drafts: List<ReviewDayDraft> = emptyList(), val message: String? = null,
)

/** Strict validation shared by the review list and the final gate. Never fills in missing hours. */
internal fun validateDraft(dateText: String, timeText: String): Pair<PlannedDay?, String?> {
    val date = runCatching { LocalDate.parse(dateText.trim()) }.getOrNull() ?: return null to "Falta una fecha válida (AAAA-MM-DD)."
    val parsed = ReportCellParser.parse(timeText)
    if (parsed.absence == ProvisionalAbsence.VACATION) return null to "VAC (vacaciones) no se importa como turno ni como libre. Exclúyelo o corrígelo."
    if (parsed.kind == DayKind.UNKNOWN) return null to when {
        timeText.isBlank() -> "Sin horario reconocido. Indica los intervalos o márcalo como libre."
        else -> "No se reconoce «${timeText.replace('\n', ' ')}» como turno válido. Corrige las horas o márcalo como libre."
    }
    val day = PlannedDay(date, parsed.kind, parsed.intervals.map { it.copy(isExtra = false) })
    PlannedScheduleValidator.validate(listOf(day))?.let { return null to it }
    return day to null
}

private fun ProvisionalInterval.text() = "$start-${if (endDayOffset > startDayOffset && end.toString() == "00:00") "24:00" else end}"

@HiltViewModel
class ScheduleReviewViewModel @Inject constructor(private val interpreter: ScheduleInterpreter) : ViewModel() {
    private val mutable = MutableStateFlow(ScheduleReviewState())
    val state = mutable.asStateFlow()
    private var document: OcrDocument? = null
    private var job: Job? = null

    fun prepare(source: OcrDocument, name: String, alias: String) {
        if (document === source) return
        clear()
        document = source
        mutable.value = ScheduleReviewState(name = name, alias = alias, documentWidth = source.width, documentHeight = source.height)
    }
    fun clear() { job?.cancel(); document = null; mutable.value = ScheduleReviewState() }
    fun updateIdentity(name: String = state.value.name, alias: String = state.value.alias, month: String = state.value.month) {
        job?.cancel()
        mutable.value = ScheduleReviewState(name = name, alias = alias, month = month, documentWidth = document?.width ?: 0, documentHeight = document?.height ?: 0)
    }
    fun interpret(rowId: Int? = null, band: ManualRowBand? = null) {
        val source = document ?: run { mutable.update { it.copy(message = "Vuelve a importar una imagen para revisar sus turnos.") }; return }
        val input = state.value
        val context = if (input.month.isBlank()) null else runCatching { YearMonth.parse(input.month.trim()) }.getOrElse {
            mutable.update { it.copy(message = "Mes no válido: utiliza AAAA-MM. No se utilizará la fecha actual.") }; return
        }
        job?.cancel()
        mutable.update { it.copy(loading = true, result = null, drafts = emptyList(), message = null) }
        job = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.Default) { interpreter.interpret(source, WorkerSelection(input.name, input.alias, rowId, band), context) }
                ensureActive()
                mutable.update { it.copy(loading = false, result = result, drafts = revalidate(result.days.map { day ->
                    // Vacations are not persisted by the OCR import; they start excluded but stay visible.
                    ReviewDayDraft(day, day.date?.toString().orEmpty(), day.reviewText(), included = day.absence == null)
                })) }
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                ensureActive()
                mutable.update { it.copy(loading = false, message = "No se pudo interpretar esta tabla. Conserva el OCR y revisa el formato.") }
            }
        }
    }

    private fun revalidate(drafts: List<ReviewDayDraft>): List<ReviewDayDraft> {
        val checked = drafts.map { draft -> validateDraft(draft.dateText, draft.timeText).let { (day, error) -> draft.copy(planned = day, error = error) } }
        val duplicates = checked.filter { it.included }.mapNotNull { it.planned?.date }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        return checked.map { draft ->
            when {
                !draft.included -> draft.copy(reviewed = draft.reviewed && draft.error == null)
                draft.planned?.date in duplicates -> draft.copy(planned = null, error = "Fecha repetida en esta importación. Corrige o excluye uno de los días.", reviewed = false)
                draft.error != null -> draft.copy(reviewed = false)
                else -> draft
            }
        }
    }
    private fun change(index: Int, block: (ReviewDayDraft) -> ReviewDayDraft) {
        mutable.update { s -> if (index !in s.drafts.indices) s else s.copy(message = null, drafts = revalidate(s.drafts.mapIndexed { i, d -> if (i == index) block(d) else d })) }
    }

    /** Confirms the detected values as they are; no editor needs to be opened. */
    fun markReviewed(index: Int) {
        val draft = state.value.drafts.getOrNull(index) ?: return
        if (!draft.canReview) {
            mutable.update { it.copy(message = when {
                !draft.included -> "El día ${index + 1} está excluido de la importación."
                draft.editor != null -> "Aplica o cancela la edición del día ${index + 1} antes de marcarlo como revisado."
                else -> "El día ${index + 1} tiene un error de validación: ${draft.error}"
            }) }
            return
        }
        change(index) { it.copy(reviewed = true) }
    }
    fun markPending(index: Int) = change(index) { it.copy(reviewed = false) }
    fun setIncluded(index: Int, included: Boolean) = change(index) { it.copy(included = included) }

    fun startEdit(index: Int) = change(index) { draft ->
        val parsed = ReportCellParser.parse(draft.timeText)
        draft.copy(editor = DayEditor(draft.dateText, parsed.kind == DayKind.FREE,
            parsed.intervals.map { IntervalText(it.start.toString(), it.text().substringAfter('-')) }.ifEmpty { listOf(IntervalText()) }))
    }
    fun cancelEdit(index: Int) = change(index) { it.copy(editor = null) }
    fun editDate(index: Int, date: String) = editor(index) { it.copy(date = date) }
    fun editFree(index: Int, free: Boolean) = editor(index) { it.copy(free = free) }
    fun editInterval(index: Int, slot: Int, start: String? = null, end: String? = null) = editor(index) { e ->
        e.copy(intervals = e.intervals.mapIndexed { i, interval -> if (i == slot) IntervalText(start ?: interval.start, end ?: interval.end) else interval })
    }
    fun addInterval(index: Int) = editor(index) { it.copy(intervals = it.intervals + IntervalText()) }
    fun removeInterval(index: Int, slot: Int) = editor(index) { e -> e.copy(intervals = e.intervals.filterIndexed { i, _ -> i != slot }.ifEmpty { listOf(IntervalText()) }) }
    private fun editor(index: Int, block: (DayEditor) -> DayEditor) {
        mutable.update { s -> s.copy(drafts = s.drafts.mapIndexed { i, d -> if (i == index && d.editor != null) d.copy(editor = block(d.editor)) else d }) }
    }

    /** Applies the editor's values. A change always returns the day to pending; the editor stays open while invalid. */
    fun applyEdit(index: Int) {
        val draft = state.value.drafts.getOrNull(index) ?: return
        val editor = draft.editor ?: return
        val time = if (editor.free) "LIBRE" else editor.intervals.filter { it.start.isNotBlank() || it.end.isNotBlank() }
            .joinToString(" / ") { "${it.start.trim()}-${it.end.trim()}" }
        val date = editor.date.trim()
        val changed = date != draft.dateText || time != draft.timeText
        val valid = validateDraft(date, time).second == null
        change(index) { it.copy(dateText = date, timeText = time, edited = it.edited || changed, reviewed = it.reviewed && !changed, editor = if (valid) null else editor) }
    }

    fun addManualDay() {
        val day = ProvisionalDay(null, "Entrada manual", "", DayKind.UNKNOWN, emptyList(), InterpretationStatus.INCOMPLETE, listOf(ScheduleIssue.MISSING_DATE, ScheduleIssue.EMPTY_CELL))
        mutable.update { state -> state.copy(result = state.result ?: ScheduleResult(), message = null,
            drafts = revalidate(state.drafts + ReviewDayDraft(day, "", "", edited = true, editor = DayEditor("", false, listOf(IntervalText()))))) }
    }

    /** Included days that block saving, described for the user. */
    fun attention(): List<String> = state.value.drafts.withIndex().filter { it.value.included }.mapNotNull { (i, d) ->
        val label = "Día ${i + 1} (${d.dateText.ifBlank { "sin fecha" }})"
        when {
            d.editor != null -> "$label: edición sin aplicar."
            d.error != null -> "$label: error de validación. ${d.error}"
            !d.reviewed -> "$label: pendiente de revisar."
            else -> null
        }
    }

    /** Final gate before the conflict check: every included day must be valid and explicitly reviewed. */
    fun readyForSave(): List<PlannedDay>? {
        val included = state.value.drafts.filter { it.included }
        if (included.isEmpty()) { mutable.update { it.copy(message = "No hay días incluidos para guardar.") }; return null }
        val problems = attention()
        if (problems.isNotEmpty()) {
            mutable.update { it.copy(message = "No se puede guardar todavía. Revisa, corrige o excluye:\n" + problems.joinToString("\n")) }
            return null
        }
        mutable.update { it.copy(message = null) }
        return included.map { it.planned!! }
    }
}
