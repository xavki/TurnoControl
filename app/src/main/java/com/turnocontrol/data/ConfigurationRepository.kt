package com.turnocontrol.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.turnocontrol.data.accounts.OperationGate
import com.turnocontrol.domain.*
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import java.math.BigDecimal
import kotlinx.coroutines.flow.map

private val Context.preferences by preferencesDataStore("preferences")
@Singleton
class ConfigurationRepository internal constructor(private val db: TurnoDatabase, private val preferences: DataStore<Preferences>, private val gate: OperationGate = OperationGate()) {
    @Inject constructor(db: TurnoDatabase, @ApplicationContext context: Context, gate: OperationGate) : this(db, context.preferences, gate)
    val profile = db.configuration().observeProfile()
    val rates = db.configuration().observeRates().map { rules -> rules.associate { it.type to it.amountDecimal } }
    suspend fun load(profile: Profile): ConfigurationDraft {
        val contract = db.configuration().contract()
        val rates = db.configuration().rates()
        val rate = rates.firstOrNull { it.type == "HOURLY_BASE" }
        return ConfigurationDraft(profile.displayName, profile.alias, profile.zoneId,
            contract?.type ?: "PARTIAL", contract?.agreedMinutes?.toBigDecimal()?.divide(BigDecimal(60), 6, java.math.RoundingMode.HALF_UP)?.stripTrailingZeros()?.toPlainString() ?: "",
            contract?.unit ?: "MONTH", contract?.validFrom ?: java.time.LocalDate.now().toString(), rate?.amountDecimal ?: "",
            rates.filter { it.type in rateLabels }.associate { it.type to it.amountDecimal })
    }
    suspend fun save(draft: ConfigurationDraft) = gate.operation("settings-save") { saveLocked(draft) }

    private suspend fun saveLocked(draft: ConfigurationDraft) {
        // Authoritative check against what is stored (legacy values longer than the limit are kept when unchanged).
        val stored = db.schedule().profile()
        draft.validate(stored?.displayName, stored?.alias)?.let { throw ConfigurationValidationException(it) }
        // Each managed type keeps its existing id (or its historical default); ids of unknown rows are never reused.
        val existing = db.configuration().rates()
        val taken = existing.map { it.id }.toMutableSet()
        var next = (existing.maxOfOrNull { it.id } ?: 0L).coerceAtLeast(DataFormat.knownRateTypes.size.toLong())
        val rates = (linkedMapOf("HOURLY_BASE" to draft.hourlyRate) + rateLabels.keys.associateWith { draft.extraRates[it].orEmpty() }).entries.mapIndexedNotNull { index, (type, amount) ->
            val id = existing.firstOrNull { it.type == type }?.id ?: (index.toLong() + 1).takeIf { it !in taken } ?: ++next
            taken += id
            amount.takeIf { it.isNotBlank() }?.let { PayRule(id = id, type = type, amountDecimal = decimal(it).toPlainString(), mode = if (type in listOf("NIGHT", "SPECIAL_NIGHT", "EXTRA_PAY")) "PLUS" else "BASE", accumulation = "UNDEFINED", validFrom = draft.from) }
        }
        db.withTransaction {
            // Diff-based capture: rewriting unchanged settings produces no outgoing change.
            val before = if (SyncCapture.isLinked(db)) SyncRecords.configuration(db) else null
            db.configuration().saveConfiguration(Profile(displayName = draft.name.trim(), alias = draft.alias.trim(), zoneId = draft.zone),
                Contract(type = draft.type, agreedMinutes = decimal(draft.hours).multiply(BigDecimal(60)).longValueExact(), unit = draft.unit, validFrom = draft.from),
                rates)
            if (before != null) {
                val after = SyncRecords.configuration(db)
                SyncCapture.record(db, (before.keys + after.keys).filter { before[it] != after[it] }.associateWith { after[it] })
            }
        }
    }
    val darkTheme = preferences.data.map { it[booleanPreferencesKey("dark_theme")] ?: false }
    suspend fun setDarkTheme(enabled: Boolean) { preferences.edit { it[booleanPreferencesKey("dark_theme")] = enabled } }
}
