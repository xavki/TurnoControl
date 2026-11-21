package com.turnocontrol.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.turnocontrol.data.BackupException
import com.turnocontrol.data.BackupFile
import com.turnocontrol.data.BackupRepository
import com.turnocontrol.data.BackupSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class DataCounts(val profile: Boolean, val contract: Boolean, val rates: Int, val days: Int, val intervals: Int, val firstDate: String?, val lastDate: String?)
fun BackupSnapshot.counts() = DataCounts(profiles.isNotEmpty(), contracts.isNotEmpty(), payRules.size, days.size, intervals.size, days.minOfOrNull { it.date }, days.maxOfOrNull { it.date })

data class RestorePreview(val file: BackupFile, val incoming: DataCounts, val current: DataCounts)
data class BackupState(val busy: Boolean = false, val message: String? = null, val error: Boolean = false, val preview: RestorePreview? = null, val restored: Int = 0)

@HiltViewModel
class BackupViewModel internal constructor(
    private val export: suspend (Uri) -> BackupFile,
    private val read: suspend (Uri) -> BackupFile,
    private val current: suspend () -> BackupSnapshot,
    private val restore: suspend (BackupFile) -> BackupSnapshot,
) : ViewModel() {
    @Inject constructor(repository: BackupRepository) : this(repository::export, repository::read, repository::snapshot, { repository.restore(it) })
    private val mutable = MutableStateFlow(BackupState())
    val state = mutable.asStateFlow()

    fun suggestedName(now: LocalDateTime = LocalDateTime.now()) = "turnocontrol-backup-${now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))}.json"

    private fun run(block: suspend () -> Unit) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, message = null, error = false) }
        viewModelScope.launch {
            try { block() }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                mutable.update { it.copy(message = (e as? BackupException)?.message ?: "No se pudo completar la operación con el archivo. Tus datos no se han modificado.", error = true) }
            } finally { mutable.update { it.copy(busy = false) } }
        }
    }

    fun exportTo(uri: Uri?) {
        if (uri == null) { mutable.update { it.copy(message = "Exportación cancelada.", error = false) }; return }
        run {
            val file = export(uri)
            val c = file.snapshot.counts()
            mutable.update { it.copy(message = "Copia guardada y comprobada: ${c.days} día(s), ${c.intervals} intervalo(s) y ${c.rates} tarifa(s). Contiene tus horarios: guárdala en un lugar privado.") }
        }
    }

    /** Reads and validates the whole file; nothing is written until [confirmRestore]. */
    fun pick(uri: Uri?) {
        if (uri == null) { mutable.update { it.copy(message = "Restauración cancelada.", error = false) }; return }
        run {
            val file = read(uri)
            mutable.update { it.copy(preview = RestorePreview(file, file.snapshot.counts(), current().counts())) }
        }
    }
    fun cancelRestore() = mutable.update { it.copy(preview = null, message = "Restauración cancelada. No se ha modificado nada.", error = false) }

    fun confirmRestore() {
        val preview = state.value.preview ?: return
        run {
            val stored = restore(preview.file)
            val c = stored.counts()
            mutable.update { it.copy(preview = null, restored = it.restored + 1,
                message = "Copia restaurada y comprobada: ${c.days} día(s), ${c.intervals} intervalo(s) y ${c.rates} tarifa(s). Se guardó en el dispositivo una copia automática de los datos anteriores.") }
        }
    }
}
