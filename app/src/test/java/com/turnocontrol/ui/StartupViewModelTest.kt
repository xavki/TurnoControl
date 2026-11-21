package com.turnocontrol.ui

import androidx.lifecycle.ViewModelStore
import com.turnocontrol.data.RecoveryOutcome
import com.turnocontrol.domain.CompatibilityReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StartupViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }

    @Test fun compatibleDataOpensTheApp() = runTest(dispatcher) {
        val model = StartupViewModel(check = { CompatibilityReport.Compatible }).also { store.put("a", it) }
        assertEquals(StartupState.Checking, model.state.value)
        runCurrent()
        assertEquals(StartupState.Ready, model.state.value)
    }

    @Test fun incompatibleDataAndCheckFailuresBlockWithoutRecoveryOrWrites() = runTest(dispatcher) {
        var report: CompatibilityReport = CompatibilityReport.Incompatible(listOf("Formato 3"))
        var recoveries = 0
        val blocked = StartupViewModel(check = { report }, recover = { recoveries++; RecoveryOutcome.None }).also { store.put("b", it) }
        runCurrent()
        assertEquals(StartupState.Blocked(listOf("Formato 3")), blocked.state.value)
        assertEquals("Recovery reads Room and must not run on rejected data", 0, recoveries)
        report = CompatibilityReport.Compatible
        blocked.retry(); runCurrent()
        assertEquals(StartupState.Ready, blocked.state.value)
        val failing = StartupViewModel(check = { throw IllegalStateException("Synthetic") }).also { store.put("c", it) }
        runCurrent()
        assertEquals(1, (failing.state.value as StartupState.Blocked).reasons.size)
    }

    @Test fun interruptedRestoreOutcomesAreShownAndDecisionsAreExplicit() = runTest(dispatcher) {
        val rolled = StartupViewModel(check = { CompatibilityReport.Compatible }, recover = { RecoveryOutcome.RolledForward }).also { store.put("d", it) }
        runCurrent()
        assertFalse((rolled.state.value as StartupState.Recovery).needsDecision)
        assertTrue("Configuration must reload after recovery", rolled.dataReplaced.value)
        rolled.acknowledge()
        assertEquals(StartupState.Ready, rolled.state.value)
        rolled.consumeDataReplaced()
        assertFalse(rolled.dataReplaced.value)
        val clean = StartupViewModel(check = { CompatibilityReport.Compatible }).also { store.put("f", it) }
        runCurrent()
        assertFalse(clean.dataReplaced.value)

        var outcome: RecoveryOutcome = RecoveryOutcome.NeedsDecision("Sin coincidencia")
        var restored = 0
        val undecided = StartupViewModel(check = { CompatibilityReport.Compatible }, recover = { outcome },
            restoreSafety = { restored++; outcome = RecoveryOutcome.None }).also { store.put("e", it) }
        runCurrent()
        undecided.acknowledge() // Not allowed: a decision is required.
        assertTrue((undecided.state.value as StartupState.Recovery).needsDecision)
        assertEquals(0, restored)
        undecided.restoreSafetyCopy(); runCurrent()
        assertEquals(1, restored)
        assertEquals(StartupState.Ready, undecided.state.value)
        assertTrue(undecided.dataReplaced.value)
    }
}
