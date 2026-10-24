package com.turnocontrol.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.turnocontrol.domain.*
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import java.math.BigDecimal
import kotlinx.coroutines.flow.map

private val Context.preferences by preferencesDataStore("preferences")
@Singleton
class ConfigurationRepository internal constructor(private val db: TurnoDatabase, private val preferences: DataStore<Preferences>) {
    @Inject constructor(db: TurnoDatabase, @ApplicationContext context: Context) : this(db, context.preferences)
    val profile = db.configuration().observeProfile()
    suspend fun load(profile: Profile): ConfigurationDraft {
        val contract = db.configuration().contract()
        val rates = db.configuration().rates()
        val rate = rates.firstOrNull { it.type == "HOURLY_BASE" }
        return ConfigurationDraft(profile.displayName, profile.alias, profile.zoneId,
            contract?.type ?: "PARTIAL", contract?.agreedMinutes?.toBigDecimal()?.divide(BigDecimal(60), 6, java.math.RoundingMode.HALF_UP)?.stripTrailingZeros()?.toPlainString() ?: "",
            contract?.unit ?: "MONTH", contract?.validFrom ?: java.time.LocalDate.now().toString(), rate?.amountDecimal ?: "",
            rates.filter { it.type in rateLabels }.associate { it.type to it.amountDecimal })
    }
    suspend fun save(draft: ConfigurationDraft) {
        require(draft.validate() == null)
        val rates = (linkedMapOf("HOURLY_BASE" to draft.hourlyRate) + rateLabels.keys.associateWith { draft.extraRates[it].orEmpty() }).entries.mapIndexedNotNull { index, (type, amount) ->
            amount.takeIf { it.isNotBlank() }?.let { PayRule(id = index.toLong() + 1, type = type, amountDecimal = decimal(it).toPlainString(), mode = if (type in listOf("NIGHT", "SPECIAL_NIGHT", "EXTRA_PAY")) "PLUS" else "BASE", accumulation = "UNDEFINED", validFrom = draft.from) }
        }
        db.configuration().saveConfiguration(Profile(displayName = draft.name.trim(), alias = draft.alias.trim(), zoneId = draft.zone),
            Contract(type = draft.type, agreedMinutes = decimal(draft.hours).multiply(BigDecimal(60)).longValueExact(), unit = draft.unit, validFrom = draft.from),
            rates)
    }
    val darkTheme = preferences.data.map { it[booleanPreferencesKey("dark_theme")] ?: false }
    suspend fun setDarkTheme(enabled: Boolean) { preferences.edit { it[booleanPreferencesKey("dark_theme")] = enabled } }
}
