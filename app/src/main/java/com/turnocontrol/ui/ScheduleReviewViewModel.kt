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

data class ReviewDayDraft(val source: ProvisionalDay, val dateText: String, val timeText: String, val dirty: Boolean = false, val error: String? = null, val originalIssues: List<ScheduleIssue> = source.issues)
data class ScheduleReviewState(
    val name: String = "", val alias: String = "", val month: String = "",
    val loading: Boolean = false, val result: ScheduleResult? = null, val documentWidth: Int = 0, val documentHeight: Int = 0,
    val drafts: List<ReviewDayDraft> = emptyList(), val message: String? = null,
)

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
                mutable.update { it.copy(loading = false, result = result, drafts = result.days.map { day ->
                    ReviewDayDraft(day, day.date?.toString().orEmpty(), day.reviewText())
                }) }
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                ensureActive()
                mutable.update { it.copy(loading = false, message = "No se pudo interpretar esta tabla. Conserva el OCR y revisa el formato.") }
            }
        }
    }
    fun edit(index: Int, date: String = state.value.drafts[index].dateText, time: String = state.value.drafts[index].timeText) {
        mutable.update { state -> state.copy(drafts = state.drafts.mapIndexed { i, draft -> if (i == index) draft.copy(dateText = date, timeText = time, dirty = true, error = null) else draft }) }
    }
    fun correct(index: Int) {
        val draft = state.value.drafts[index]
        val date = runCatching { LocalDate.parse(draft.dateText.trim()) }.getOrNull()
        val parsed = ReportCellParser.parse(draft.timeText)
        if (date == null || (parsed.kind == DayKind.UNKNOWN && parsed.absence == null) || state.value.drafts.withIndex().any { it.index != index && it.value.source.date == date }) {
            mutable.update { state -> state.copy(drafts = state.drafts.mapIndexed { i, d -> if (i == index) d.copy(error = "Indica una fecha AAAA-MM-DD no duplicada y rangos HH:mm-HH:mm separados por /, o LIBRE.") else d }) }
            return
        }
        val corrected = draft.source.copy(date = date, kind = parsed.kind, intervals = parsed.intervals, absence = parsed.absence, status = InterpretationStatus.CORRECTED,
            issues = (parsed.issues + ScheduleIssue.MANUAL_CORRECTION).distinct())
        mutable.update { state ->
            val days = state.result!!.days.toMutableList().apply { this[index] = corrected }
            state.copy(result = state.result.copy(days = days), drafts = state.drafts.mapIndexed { i, d -> if (i == index) d.copy(source = corrected, timeText = corrected.reviewText(), dirty = false, error = null) else d })
        }
    }
    fun addManualDay() {
        val day = ProvisionalDay(null, "Entrada manual", "", DayKind.UNKNOWN, emptyList(), InterpretationStatus.INCOMPLETE, listOf(ScheduleIssue.MISSING_DATE, ScheduleIssue.EMPTY_CELL))
        mutable.update { state -> state.copy(result = (state.result ?: ScheduleResult()).let { it.copy(days = it.days + day) }, drafts = state.drafts + ReviewDayDraft(day, "", "")) }
    }
    fun confirmableDrafts() = state.value.drafts.filter { it.source.absence == null }
}
