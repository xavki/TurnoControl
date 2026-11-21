package com.turnocontrol.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.turnocontrol.domain.ConfigurationDraft
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class ExtraHourRateTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var db: TurnoDatabase
    private val fixture = ConfigurationDraft(
        name = "Perfil ficticio de reinicio", alias = "alias ficticio", zone = "UTC",
        type = "FULL", hours = "37.5", unit = "WEEK", from = "2026-01-01", hourlyRate = "10.1234",
        extraRates = mapOf("EXTRA_PAY" to "2.00", "COMPLEMENTARY" to "11.50", "NIGHT" to "0.50", "SPECIAL_NIGHT" to "1.50")
    )
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TurnoDatabase::class.java).build() }
    @After fun cleanup() { db.close() }
    private fun repository(scope: kotlinx.coroutines.CoroutineScope) = ConfigurationRepository(db, PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(temporary.root, "rates.preferences_pb") }))

    @Test fun extraHourRateIsStoredAsSixthRuleWithoutRenumbering() = runTest {
        val repository = repository(backgroundScope)
        repository.save(fixture.copy(extraRates = fixture.extraRates + ("EXTRA_HOUR" to "15")))
        val rules = db.configuration().rates()
        assertEquals(6, rules.size)
        assertEquals((1L..6L).toList(), rules.map { it.id })
        val extra = rules.single { it.type == "EXTRA_HOUR" }
        assertEquals(6L, extra.id)
        assertEquals("BASE", extra.mode)
        assertEquals("15", extra.amountDecimal)
        assertEquals("15", repository.load(repository.profile.first()!!).extraRates["EXTRA_HOUR"])
        assertEquals("15", repository.rates.first()["EXTRA_HOUR"])
        assertEquals("10.1234", repository.rates.first()["HOURLY_BASE"])
    }

    @Test fun fixtureWithFiveRatesStaysFive() = runTest {
        val repository = repository(backgroundScope)
        repository.save(fixture)
        assertEquals(5, db.configuration().rates().size)
        assertEquals(fixture, repository.load(repository.profile.first()!!))
        assertFalse(repository.rates.first().containsKey("EXTRA_HOUR"))
    }
}
