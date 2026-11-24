package com.turnocontrol.ui

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.turnocontrol.TurnoApp
import com.turnocontrol.data.*
import com.turnocontrol.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

/**
 * The real navigation graph (TurnoApp) with real ViewModels over an in-memory database and synthetic data.
 * JVM/Robolectric: it checks routes, back stack and state restoration, not a device or TalkBack.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class AppNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val temporary = TemporaryFolder()
    private val store = ViewModelStore()
    private lateinit var db: TurnoDatabase
    private lateinit var schedule: ScheduleRepository
    private lateinit var configuration: ConfigurationRepository
    // A day of this month with a declared extra, so Resumen lists it and Detalle can be opened from there.
    private val day = YearMonth.now().atDay(10)

    private object NoOcr : ImageOcr {
        override suspend fun load(uri: Uri): ImportedImage = error("not used")
        override suspend fun recognize(image: ImportedImage): OcrDocument = error("not used")
        override suspend fun rotate(image: ImportedImage, degrees: Int): ImportedImage = error("not used")
    }

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TurnoDatabase::class.java).allowMainThreadQueries().build()
        schedule = ScheduleRepository(db)
        configuration = ConfigurationRepository(db, PreferenceDataStoreFactory.create(produceFile = { File(temporary.root, "nav.preferences_pb") }))
        runBlocking { schedule.save(schedule.snapshot(), listOf(PlannedDay(day, DayKind.WORK, TimeCellParser.parse("08:00-12:00").intervals.map { it.copy(isExtra = true) }))) }
    }
    @After fun cleanup() { store.clear(); db.close(); Dispatchers.resetMain() }

    private var counter = 0
    private fun <T : androidx.lifecycle.ViewModel> T.kept() = also { store.put("vm${counter++}", it) }
    private inner class Models {
        val config = ConfigurationViewModel(configuration).kept()
        val import = ImportViewModel(NoOcr).kept()
        val review = ScheduleReviewViewModel(WorkerRowsInterpreter()).kept()
        val calendar = CalendarViewModel(schedule).kept()
        val summary = SummaryViewModel(schedule, configuration).kept()
        val account = AccountViewModel(AccountUiState.Guest, false, null).kept()
    }
    private fun show(models: Models, dark: Boolean = false) = compose.setContent {
        TurnoTheme(dark) { TurnoApp(models.config, models.import, models.review, models.calendar, models.summary, null, models.account) }
    }
    /** Every clickable node of the screen on display: an accessible name and a touch target of at least 48 × 48 dp. */
    private fun assertTargets(screen: String) {
        val nodes = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertTrue(nodes.isNotEmpty())
        // A node cut by a horizontal list's edge (month chips) is measured once scrolled into view, not half hidden.
        val whole = nodes.filter { it.boundsInRoot.width >= it.size.width - 1 && it.boundsInRoot.height >= it.size.height - 1 }
        assertTrue(whole.size >= nodes.size - 3)
        whole.forEach { node ->
            val name = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString()
            assertFalse("$screen: elemento pulsable sin nombre", name.isNullOrBlank())
            val (w, h) = node.touchBoundsInRoot.let { with(compose.density) { it.width.toDp() to it.height.toDp() } }
            assertTrue("$screen: «$name» mide $w × $h", w >= 48.dp && h >= 48.dp)
        }
    }
    private fun waitFor(text: String) = compose.waitUntil(5000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun onCalendar() = waitFor("Hoy")
    private fun onSummary() = waitFor("Extras declaradas del mes")
    private fun onSettings() = waitFor("Perfil del trabajador")
    private fun onDetail() = waitFor("Volver al calendario")
    private fun gear() = compose.onNodeWithContentDescription("Ajustes").performClick()
    private fun tab(name: String) = compose.onAllNodesWithText(name).filterToOne(hasClickAction()).performClick()
    private fun systemBack() { compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle() }

    @Test fun everyRequiredRouteAndBackPath() {
        show(Models())
        onCalendar()
        tab("Resumen"); onSummary()                                   // Calendario → Resumen
        tab("Calendario"); onCalendar()                               // Resumen → Calendario
        gear(); onSettings(); systemBack(); onCalendar()              // Calendario → Ajustes → Atrás del sistema
        gear(); onSettings(); compose.onNodeWithContentDescription("Volver").performClick(); onCalendar() // … → flecha Volver
        tab("Resumen"); onSummary()
        gear(); onSettings(); systemBack(); onSummary()               // Resumen → Ajustes → Atrás
        compose.onNodeWithText("$day · 4 h 0 min").performScrollTo().performClick(); onDetail() // Resumen → Detalle
        systemBack(); onSummary()                                     // Atrás vuelve a Resumen
        tab("Calendario"); onCalendar()
        val cell = hasContentDescription("$day · Trabajo", substring = true)
        compose.waitUntil(5000) { compose.onAllNodes(cell and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(cell).performScrollTo().performClick(); onDetail() // Calendario → Detalle
        compose.onNodeWithText("Volver al calendario").performClick(); onCalendar()
    }

    @Test fun settingsNeverReappearsAfterSwitchingTabs() {
        show(Models())
        onCalendar()
        gear(); onSettings()
        tab("Resumen"); onSummary()
        compose.onAllNodesWithText("Perfil del trabajador").assertCountEquals(0)
        tab("Calendario"); onCalendar()
        compose.onAllNodesWithText("Perfil del trabajador").assertCountEquals(0)
        tab("Resumen"); onSummary()
        compose.onAllNodesWithText("Perfil del trabajador").assertCountEquals(0)
        // System Back from a tab root never surfaces Ajustes either: the graph pops back to Calendario.
        systemBack(); onCalendar()
        compose.onAllNodesWithText("Perfil del trabajador").assertCountEquals(0)
    }

    @Test fun accountScreenAndOcrImportAreReachableAndReturn() {
        show(Models())
        onCalendar()
        compose.onNodeWithText("Importar horario desde una imagen").performScrollTo().performClick()
        waitFor("Importar imagen · OCR")
        systemBack(); onCalendar()
        gear(); onSettings()
        compose.onNodeWithText("Opciones de cuenta").performScrollTo().performClick()
        compose.onNodeWithTag("account-status").assertTextEquals("Sin cuenta")
        compose.onNodeWithTag("account-signin").assertIsNotEnabled()
        compose.onNodeWithTag("account-provider-note").assertExists()
        systemBack(); onSettings()
        compose.onNodeWithText("Importar horario").performScrollTo().performClick()
        waitFor("Importar imagen · OCR")
        systemBack(); onSettings()
    }

    // Tall virtual screens so every node is fully on screen (Robolectric clips bounds to the window).
    @Config(qualifiers = "w360dp-h5000dp") @Test fun mainScreensHaveNamedTargetsOf48dpAt360dp() = walkMainScreens("360 dp")
    @Config(qualifiers = "w320dp-h5000dp") @Test fun mainScreensHaveNamedTargetsOf48dpAt320dp() = walkMainScreens("320 dp")
    private fun walkMainScreens(width: String) {
        show(Models())
        onCalendar(); assertTargets("Calendario $width")
        tab("Resumen"); onSummary(); assertTargets("Resumen $width")
        gear(); onSettings(); assertTargets("Ajustes $width")
        systemBack(); onSummary()
        compose.onNodeWithText("$day · 4 h 0 min").performScrollTo().performClick(); onDetail(); assertTargets("Detalle $width")
    }

    @Test fun aRejectedSaveIsAnnouncedToScreenReaders() {
        val models = Models()
        show(models, dark = true)
        onCalendar()
        val cell = hasContentDescription("$day · Trabajo", substring = true)
        compose.waitUntil(5000) { compose.onAllNodes(cell and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(cell).performScrollTo().performClick(); onDetail()
        compose.waitUntil(5000) { models.calendar.state.value.let { it.editorReady && !it.busy } }
        compose.onNodeWithContentDescription("Añadir registro").performClick()
        compose.onNodeWithText("Guardar registro").performClick() // No times chosen: rejected before any write.
        val message = hasText("Elige la entrada y salida de cada intervalo.")
        compose.waitUntil(5000) { compose.onAllNodes(message).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(message).fetchSemanticsNodes().forEach { node ->
            assertEquals(LiveRegionMode.Polite, node.config.getOrNull(SemanticsProperties.LiveRegion))
        }
    }

    @Test fun routeSurvivesRecreationAndDetailFallsBackWhenTheProcessLostItsSelection() {
        val tester = StateRestorationTester(compose)
        var models by mutableStateOf(Models())
        tester.setContent { TurnoTheme(false) { TurnoApp(models.config, models.import, models.review, models.calendar, models.summary, null, models.account) } }
        onCalendar()
        tab("Resumen"); onSummary()
        tester.emulateSavedInstanceStateRestore()                     // Configuration change: same ViewModels.
        onSummary()
        compose.onNodeWithText("$day · 4 h 0 min").performScrollTo().performClick(); onDetail()
        tester.emulateSavedInstanceStateRestore()
        onDetail()
        models = Models()                                             // Process death: new ViewModels, nothing selected.
        tester.emulateSavedInstanceStateRestore()
        onCalendar()
        compose.onAllNodesWithText("Volver al calendario").assertCountEquals(0)
    }
}
