package com.turnocontrol

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.turnocontrol.data.ScheduleRepository
import com.turnocontrol.ui.BackupViewModel
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Restore dialog usability on the isolated verification package. The backup is an export of the current data,
 * so confirming the restore leaves the calendar exactly as it was.
 */
class BackupRestoreUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val file get() = File(File(context.cacheDir, "ocr-fixtures").apply { mkdirs() }, "ui-backup.json")
    @Before fun waitForStartupGate() = compose.awaitStartup()
    @After fun cleanup() { file.delete() }

    private fun waitText(text: String) = compose.waitUntil(15_000) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    @Test fun wholeConfirmationRowTogglesAndTheSuccessNoticeIsVisibleAtTheTop() {
        val repository = ScheduleRepository(EntryPointAccessors.fromApplication(context, CalendarTestDatabase::class.java).database())
        val before = runBlocking { repository.snapshot() }
        compose.onNodeWithContentDescription("Ajustes").performClick()
        file.writeText("")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.ocr-fixtures", file)
        val backup = compose.runOnUiThread { ViewModelProvider(compose.activity)[BackupViewModel::class.java] }
        compose.runOnUiThread { backup.exportTo(uri) }
        waitText("Copia guardada y comprobada")
        compose.runOnUiThread { backup.pick(uri) }
        waitText("¿Restaurar esta copia?")
        val restore = hasText("Restaurar") and hasClickAction()
        val label = "Entiendo que se sustituirán mis datos actuales"
        // The Ajustes screen behind the dialog has its own toggle (Tema oscuro): match only the confirmation row.
        val confirmation = isToggleable() and hasText(label)
        val checkboxRole = SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Checkbox) and hasAnyAncestor(isDialog())
        compose.onNode(restore).assertIsNotEnabled()
        // Tapping the label (not the box) must toggle the confirmation.
        compose.onNodeWithText("Entiendo que se sustituirán mis datos actuales").performClick()
        compose.onNode(confirmation).assertIsOn()
        compose.onNode(restore).assertIsEnabled()
        compose.onNodeWithText("Entiendo que se sustituirán mis datos actuales").performClick()
        compose.onNode(confirmation).assertIsOff()
        compose.onNode(restore).assertIsNotEnabled()
        // One semantic checkbox for the whole row, so TalkBack reads box and label together.
        compose.onAllNodes(checkboxRole).assertCountEquals(1)
        compose.onNode(checkboxRole).assert(hasText(label))
        compose.onNodeWithText("Entiendo que se sustituirán mis datos actuales").performClick()
        compose.onNode(restore).performClick()
        waitText("Copia restaurada y comprobada")
        // Visible without scrolling, even though the settings form reloaded after the restore.
        compose.onAllNodes(hasText("Copia restaurada y comprobada", substring = true))[0].assertIsDisplayed()
        compose.onNodeWithText("Entendido").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Entendido")).fetchSemanticsNodes().isEmpty() }
        assertEquals("Restoring an export of the current data changes nothing", before, runBlocking { repository.snapshot() })
    }
}
