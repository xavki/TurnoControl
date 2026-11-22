package com.turnocontrol.ui

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.turnocontrol.data.*
import com.turnocontrol.data.accounts.*
import com.turnocontrol.domain.*
import com.turnocontrol.domain.auth.AccountId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

/** Account interface with synthetic states (JVM/Robolectric rendering, not a physical device check). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class AccountUiTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()
    private val store = ViewModelStore()
    private val authenticated = AccountUiState.Authenticated("a***@example.invalid", "correo electrónico", pendingChanges = 3)

    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }
    private fun model(state: AccountUiState, action: (suspend () -> SwitchOutcome)?) = AccountViewModel(state, false, action).also { store.put("a${System.nanoTime()}", it) }
    private fun render(state: AccountScreenState, dark: Boolean = false, onBack: () -> Unit = {}) =
        compose.setContent { TurnoTheme(dark) { AccountContent(state, onBack = onBack, onSignOut = {}, onDismiss = {}) } }

    // Presentation (pure)

    @Test fun presentationsAreHonestAboutUnavailableProviders() {
        val guest = AccountUiState.Guest.presentation(providersAvailable = false)
        assertEquals("Sin cuenta", guest.status)
        assertFalse(guest.providersEnabled)
        assertEquals(PROVIDERS_UNAVAILABLE, guest.providerNote)
        assertTrue(guest.showContinueWithoutAccount && !guest.showSignOut)
        val signedIn = authenticated.presentation(false)
        assertTrue(signedIn.showSignOut)
        assertTrue(signedIn.details.contains("Cambios pendientes de sincronizar: 3"))
        assertTrue(signedIn.details.contains("Sincronización: todavía no disponible"))
        assertEquals(AccountProblem.SESSION_EXPIRED.message, AccountUiState.Expired("a***@example.invalid").presentation(false).explanation)
        assertNull(AccountUiState.Guest.presentation(providersAvailable = true).providerNote)
    }

    @Test fun productionViewModelIsAlwaysAGuestWithoutWorkingSignIn() = runTest {
        val production = AccountViewModel().also { store.put("prod", it) }
        assertEquals(AccountScreenState(AccountUiState.Guest, providersAvailable = false), production.state.value)
        production.signOut() // Nothing to sign out of: no effect.
        assertEquals(AccountUiState.Guest, production.state.value.account)
    }

    // Rendering

    @Test fun guestScreenShowsDisabledProvidersAndAWorkingGuestOption() {
        var back = 0
        render(AccountScreenState(), onBack = { back++ })
        compose.onNodeWithTag("account-status").assertTextEquals("Sin cuenta")
        compose.onNodeWithTag("account-google").assertIsNotEnabled().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("account-email").assertIsNotEnabled().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Continuar con Google, todavía no disponible").assertExists()
        compose.onNodeWithContentDescription("Correo electrónico, todavía no disponible").assertExists()
        compose.onNodeWithTag("account-provider-note").assertTextEquals(PROVIDERS_UNAVAILABLE)
        compose.onNodeWithTag("account-signout").assertDoesNotExist()
        compose.onNodeWithTag("account-guest").assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, back)
    }

    @Test fun darkThemeRendersTheSameContent() {
        render(AccountScreenState(), dark = true)
        compose.onNodeWithText("Sin cuenta").assertExists()
        compose.onNodeWithTag("account-google").assertIsNotEnabled()
    }

    @Test fun authenticatedAndExpiredStatesShowSignOutAndMessages() {
        render(AccountScreenState(authenticated, message = AccountProblem.NETWORK.message))
        compose.onNodeWithText("Sesión iniciada como a***@example.invalid").assertExists()
        compose.onNodeWithText("Cambios pendientes de sincronizar: 3").assertExists()
        compose.onNodeWithTag("account-signout").assertIsEnabled().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("account-google").assertDoesNotExist()
        compose.onNodeWithTag("account-message").assertTextEquals(AccountProblem.NETWORK.message)
    }

    @Test fun expiredSessionExplainsThatSigningInAgainIsRequired() {
        render(AccountScreenState(AccountUiState.Expired("a***@example.invalid")))
        compose.onNodeWithText(AccountProblem.SESSION_EXPIRED.message).assertExists()
        compose.onNodeWithTag("account-signout").assertExists()
        compose.onNodeWithTag("account-google").assertIsNotEnabled()
    }

    // Sign-out outcomes (synthetic actions)

    @Test fun refusedOrFailedSignOutChangesNothingAndExplainsWhy() = runTest {
        val busy = model(authenticated) { SwitchOutcome.Busy("Hay un guardado en curso.") }
        busy.signOut()
        val busyState = busy.state.first { !it.busy && it.message != null }
        assertEquals(authenticated, busyState.account)
        assertEquals("${AccountProblem.SWITCH_BLOCKED.message} Hay un guardado en curso.", busyState.message)
        val pending = model(authenticated) { throw StorageBusyException() }
        pending.signOut()
        assertEquals(AccountProblem.PENDING_OPERATION.message, pending.state.first { it.message != null }.message)
        val broken = model(authenticated) { throw java.io.IOException("disco") }
        broken.signOut()
        assertEquals(AccountProblem.STORAGE.message, broken.state.first { it.message != null }.message)
        assertEquals(authenticated, broken.state.value.account)
    }

    @Test fun signOutThroughTheCoordinatorReturnsToGuestDataWithoutDeletingTheAccount() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val layout = StorageLayout(File(temporary.root, "files"))
        val gate = OperationGate()
        val account = AccountId.parse("00000000-0000-4000-8000-00000000000a")!!
        val ns = StorageNamespace.forAccount(account)
        val dbs = mutableListOf<TurnoDatabase>()
        val coordinator = AccountStorageCoordinator(layout, ActiveNamespaceStore(File(temporary.root, "nb")), SwitchJournal(File(temporary.root, "nb")), gate,
            { location, guard -> StorageDatabases.build(context, location, guard) { it.allowMainThreadQueries() }.also { dbs += it } })
        try {
            val guest = coordinator.start()
            ScheduleRepository(guest.database, gate).let { it.save(it.snapshot(), listOf(PlannedDay(LocalDate.parse("2026-12-01"), DayKind.FREE, emptyList()))) }
            val sessionA = (coordinator.switchTo(ns, account) as SwitchOutcome.Switched).session
            ScheduleRepository(sessionA.database, gate).let { it.save(it.snapshot(), listOf(PlannedDay(LocalDate.parse("2026-12-02"), DayKind.VACATION, emptyList()))) }
            val vm = model(authenticated) { coordinator.signOut() }
            vm.signOut()
            val after = vm.state.first { !it.busy && it.message != null }
            assertEquals(AccountUiState.Guest, after.account)
            assertEquals(listOf("2026-12-01"), ScheduleRepository(coordinator.current!!.database).snapshot().map { it.day.date }) // A's calendar not visible.
            assertTrue(context.getDatabasePath(layout.locate(ns).databaseName).exists()) // Not deleted.
        } finally {
            dbs.forEach { runCatching { it.close() } }
            listOf(StorageNamespace.Guest, ns).forEach { context.deleteDatabase(layout.locate(it).databaseName) }
        }
    }

    // Settings integration and unsaved edits

    @Test fun settingsShowsTheAccountCardAndKeepsUnsavedEdits() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TurnoDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repo = ConfigurationRepository(db, PreferenceDataStoreFactory.create(produceFile = { File(temporary.root, "p.preferences_pb") }))
            val configuration = ConfigurationViewModel(repo).also { store.put("config", it) }
            configuration.state.first { !it.loading }
            configuration.update(configuration.state.value.draft.copy(name = "Borrador sin guardar"))
            val account = model(authenticated) { SwitchOutcome.Busy("x") }
            var opened = 0
            compose.setContent { TurnoTheme(false) {
                val state by configuration.state.collectAsState()
                SettingsScreen(configuration, state, onImport = {}, onBack = {}, account = account, onAccount = { opened++ })
            } }
            compose.onNodeWithTag("account-summary").assertExists()
            compose.onNodeWithText("Opciones de cuenta").assertHeightIsAtLeast(48.dp).performClick()
            assertEquals(1, opened)
            account.signOut()
            account.state.first { it.message != null }
            assertEquals("Borrador sin guardar", configuration.state.value.draft.name) // The account flow never touches the settings draft.
            assertNull(db.schedule().profile()) // Nothing was saved behind the user's back.
        } finally { db.close() }
    }
}
