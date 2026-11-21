package com.turnocontrol.ui

import androidx.lifecycle.ViewModelStore
import com.turnocontrol.domain.CompatibilityReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StartupViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }

    @Test fun compatibleDataOpensTheApp() = runTest(dispatcher) {
        val model = StartupViewModel { CompatibilityReport.Compatible }.also { store.put("a", it) }
        assertEquals(StartupState.Checking, model.state.value)
        runCurrent()
        assertEquals(StartupState.Ready, model.state.value)
    }

    @Test fun incompatibleDataAndCheckFailuresBlockWithoutWriting() = runTest(dispatcher) {
        var report: CompatibilityReport = CompatibilityReport.Incompatible(listOf("Formato 3"))
        val blocked = StartupViewModel { report }.also { store.put("b", it) }
        runCurrent()
        assertEquals(StartupState.Blocked(listOf("Formato 3")), blocked.state.value)
        report = CompatibilityReport.Compatible
        blocked.retry(); runCurrent()
        assertEquals(StartupState.Ready, blocked.state.value)
        val failing = StartupViewModel { throw IllegalStateException("Synthetic") }.also { store.put("c", it) }
        runCurrent()
        assertEquals(1, (failing.state.value as StartupState.Blocked).reasons.size)
    }
}
