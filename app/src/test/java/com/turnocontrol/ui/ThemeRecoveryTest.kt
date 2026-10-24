package com.turnocontrol.ui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.turnocontrol.data.ConfigurationRepository
import com.turnocontrol.data.TurnoDatabase
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class ThemeRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private lateinit var db: TurnoDatabase
    private val key = booleanPreferencesKey("dark_theme")
    private class PreferencesSource : DataStore<Preferences> {
        var failure: Exception? = null
        var current: Preferences = preferencesOf(booleanPreferencesKey("dark_theme") to true)
        var writes = 0
        var collections = 0
        var active = 0
        var maximumActive = 0
        val events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        override val data = flow {
            collections++
            active++
            maximumActive = maxOf(maximumActive, active)
            try {
                failure?.let { throw it }
                emit(current)
                events.collect { failure?.let { throw it }; emit(current) }
            } finally { active-- }
        }
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            writes++
            current = transform(current)
            events.emit(Unit)
            return current
        }
    }
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TurnoDatabase::class.java).build()
    }
    @After fun cleanup() { store.clear(); db.close(); Dispatchers.resetMain() }
    private fun model(source: DataStore<Preferences>) = ConfigurationViewModel(ConfigurationRepository(db, source)).also { store.put("config", it) }

    @Test fun realDataStoreReadsAndWritesThemeNormally() = runTest(dispatcher) {
        val preferences = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { File(temporary.root, "theme.preferences_pb") })
        val repository = ConfigurationRepository(db, preferences)
        assertFalse(repository.darkTheme.first())
        repository.setDarkTheme(true)
        assertTrue(repository.darkTheme.first())
        val model = model(preferences)
        model.theme.first { !it.loading }
        assertTrue(model.theme.value.dark)
        model.setDarkTheme(false)
        model.theme.first { !it.dark }
        assertFalse(repository.darkTheme.first())
        assertNull(model.theme.value.error)
    }

    @Test fun initialIOExceptionExposesRetryWithoutWritingEmptyPreferences() = runTest(dispatcher) {
        val source = PreferencesSource().apply { failure = IOException("Synthetic disk error") }
        val model = model(source)
        runCurrent()
        assertNotNull(model.theme.value.error)
        assertFalse(model.theme.value.loading)
        model.setDarkTheme(false)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, source.collections)
        assertEquals(0, source.writes)
        assertTrue(source.current[key]!!)
        source.failure = null
        model.retryThemeRead()
        model.retryThemeRead()
        runCurrent()
        assertTrue(model.theme.value.dark)
        assertNull(model.theme.value.error)
        assertEquals(2, source.collections)
        assertEquals(1, source.maximumActive)
    }

    @Test fun failureAfterValidThemeKeepsLastValueAndRetryRestoresLiveUpdates() = runTest(dispatcher) {
        val source = PreferencesSource()
        val model = model(source)
        runCurrent()
        assertTrue(model.theme.value.dark)
        source.failure = IOException("Synthetic read failure")
        source.events.emit(Unit)
        runCurrent()
        assertTrue(model.theme.value.dark)
        assertNotNull(model.theme.value.error)
        assertEquals(0, source.writes)
        source.failure = null
        source.current = preferencesOf(key to false)
        model.retryThemeRead()
        runCurrent()
        assertFalse(model.theme.value.dark)
        assertNull(model.theme.value.error)
        model.setDarkTheme(true)
        runCurrent()
        assertTrue(model.theme.value.dark)
        assertEquals(1, source.writes)
    }

    @Test fun clearingViewModelPropagatesCancellationWithoutAReadError() = runTest(dispatcher) {
        val source = PreferencesSource()
        val model = model(source)
        runCurrent()
        store.clear()
        runCurrent()
        assertEquals(0, source.active)
        assertNull(model.theme.value.error)
        assertEquals(0, source.writes)
    }

    @Test fun repositoryDoesNotConvertCancellationOrProgrammingErrorsIntoEmptyPreferences() = runTest(dispatcher) {
        val source = PreferencesSource()
        val repository = ConfigurationRepository(db, source)
        for (failure in listOf(CancellationException("Synthetic cancellation"), IllegalStateException("Synthetic programming error"))) {
            source.failure = failure
            val actual = runCatching { repository.darkTheme.first() }.exceptionOrNull()
            assertSame(failure, actual)
        }
        assertEquals(0, source.writes)
    }

    @Test fun viewModelDoesNotHideProgrammingErrors() {
        val failure = IllegalStateException("Synthetic programming error")
        val thrown = assertThrows(IllegalStateException::class.java) {
            runTest(dispatcher) {
                model(PreferencesSource().apply { this.failure = failure })
                runCurrent()
            }
        }
        assertSame(failure, thrown)
    }

    @Test fun cancelledReadKeepsLastThemeWithoutErrorOrPreferenceWrite() = runTest(dispatcher) {
        val source = PreferencesSource()
        val model = model(source)
        runCurrent()
        source.failure = CancellationException("Synthetic cancelled read")
        source.events.emit(Unit)
        runCurrent()
        assertEquals(0, source.active)
        assertTrue(model.theme.value.dark)
        assertNull(model.theme.value.error)
        assertEquals(0, source.writes)
    }
}
