package com.turnocontrol.data.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.turnocontrol.data.ScheduleRepository
import com.turnocontrol.data.accounts.*
import com.turnocontrol.domain.DayKind
import com.turnocontrol.domain.PlannedDay
import com.turnocontrol.domain.auth.*
import com.turnocontrol.domain.auth.FakeAuthProvider.Companion.ACCOUNT_A
import com.turnocontrol.domain.auth.FakeAuthProvider.Companion.ACCOUNT_B
import com.turnocontrol.ui.AccountUiState
import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 5E.1: AuthCoordinator, the sequencing between AuthManager (provider + session), EncryptedSessionVault
 * (persistence) and StorageSessionHolder (active database). FakeAuthProvider and a JVM AES-GCM double stand in for a
 * real provider and the Android Keystore (both already validated separately: AuthManager's own domain tests, and
 * KeystoreSessionVaultTest on a real emulator).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class AuthCoordinatorTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private var clockNow = Instant.parse("2026-12-01T10:00:00Z")
    private val opened = mutableListOf<com.turnocontrol.data.TurnoDatabase>()

    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun cleanup() {
        Dispatchers.resetMain()
        opened.forEach { runCatching { it.close() } }
        val layout = StorageLayout(File(temporary.root, "files"))
        listOf(StorageNamespace.Guest, StorageNamespace.forAccount(ACCOUNT_A), StorageNamespace.forAccount(ACCOUNT_B))
            .forEach { context.deleteStorageDatabase(layout.locate(it)) }
    }

    /**
     * A fresh coordinator over the same synthetic files: simulates "the process restarted" when called again.
     * [cipher] defaults to a fresh one (a brand-new app install with no Keystore history); pass the same instance
     * across two calls to simulate the Keystore key surviving an ordinary restart on the same device.
     */
    private fun coordinator(provider: AuthProvider = FakeAuthProvider { clockNow }, gate: OperationGate = OperationGate(),
        cipher: SessionCipher = SessionVaultTest.JvmGcmCipher()): AuthCoordinator {
        val layout = StorageLayout(File(temporary.root, "files"))
        val holder = StorageSessionHolder(context, gate, AccountStorageCoordinator(layout, ActiveNamespaceStore(File(temporary.root, "nb")),
            SwitchJournal(File(temporary.root, "nb")), gate, { location, guard -> StorageDatabases.build(context, location, guard) { it.allowMainThreadQueries() }.also { opened += it } }))
        val vault = EncryptedSessionVault(File(temporary.root, "sessions"), cipher)
        return AuthCoordinator(AuthManager(provider, vault, clock = { clockNow }), vault, holder)
    }

    private fun day(date: String, kind: DayKind = DayKind.FREE) = PlannedDay(LocalDate.parse(date), kind, emptyList())

    @Test fun guestIsTheInitialStateWithNoAccountBound() = runTest {
        val c = coordinator()
        c.restoreOnStart()
        assertEquals(AccountUiState.Guest, c.state.value)
    }

    @Test fun successfulSignInSwitchesToTheAccountsOwnStorage() = runTest {
        val c = coordinator()
        c.restoreOnStart()
        val outcome = c.signIn(SignInRequest.Email("ana@example.invalid", "clave-sintetica-a".toCharArray()))
        assertEquals(AuthCoordinatorOutcome.Success, outcome)
        val state = c.state.value as AccountUiState.Authenticated
        assertEquals("a***@example.invalid", state.maskedEmail)
        assertEquals("correo electrónico", state.provider)
        assertEquals(0, state.pendingChanges) // Fresh account database: nothing queued yet.
    }

    @Test fun invalidCredentialsLeaveStorageAtGuest() = runTest {
        val c = coordinator()
        c.restoreOnStart()
        val outcome = c.signIn(SignInRequest.Email("ana@example.invalid", "clave-incorrecta".toCharArray()))
        assertEquals(AuthCoordinatorOutcome.Failed(AuthError.InvalidCredentials), outcome)
        assertEquals(AccountUiState.Guest, c.state.value) // A failed sign-in never changes the active database.
    }

    @Test fun concurrentSignInAttemptsOnlyOneSucceeds() = runTest {
        // Deterministic interleaving, not a real scheduler race: the first attempt's provider call is held open
        // (still inside AuthManager's own lock) until the second attempt has already queued behind that same lock.
        val release = CompletableDeferred<Unit>()
        val fake = FakeAuthProvider { clockNow }
        var firstCallStarted = false
        val provider = object : AuthProvider by fake {
            override suspend fun signIn(request: SignInRequest): AuthResult {
                if (!firstCallStarted) { firstCallStarted = true; release.await() }
                return fake.signIn(request)
            }
        }
        val c = coordinator(provider)
        c.restoreOnStart()
        var firstOutcome: AuthCoordinatorOutcome? = null
        var secondOutcome: AuthCoordinatorOutcome? = null
        val first = launch { firstOutcome = c.signIn(SignInRequest.Email("ana@example.invalid", "clave-sintetica-a".toCharArray())) }
        advanceUntilIdle() // Drive the first attempt until it blocks on release.await(), inside AuthManager's own lock.
        val second = launch { secondOutcome = c.signIn(SignInRequest.Email("bea@example.invalid", "clave-sintetica-b".toCharArray())) }
        advanceUntilIdle() // Second now queues behind that same lock: it cannot run concurrently with the first.
        assertFalse(first.isCompleted); assertFalse(second.isCompleted) // Both genuinely still in flight, not finished.
        release.complete(Unit)
        advanceUntilIdle()
        first.join(); second.join()
        assertEquals(AuthCoordinatorOutcome.Success, firstOutcome)
        assertEquals(AuthCoordinatorOutcome.Failed(AuthError.InvalidState), secondOutcome)
        val state = c.state.value as AccountUiState.Authenticated
        assertEquals("a***@example.invalid", state.maskedEmail) // Whichever acquired the lock first (here: A) wins; B is rejected with InvalidState.
    }

    @Test fun expiredSessionIsDetectedWithoutTouchingStorage() = runTest {
        val c = coordinator()
        c.restoreOnStart()
        c.signIn(SignInRequest.Email("ana@example.invalid", "clave-sintetica-a".toCharArray()))
        clockNow = clockNow.plusSeconds(FakeAuthProvider.SESSION_SECONDS + 1)
        c.refreshValidity()
        val state = c.state.value as AccountUiState.Expired
        assertEquals("a***@example.invalid", state.maskedEmail)
    }

    @Test fun revokedSessionClearsThePersistedSessionButKeepsTheData() = runTest {
        // Built inline (not via the shared coordinator() helper) so the test can reach the AuthManager directly and
        // revoke the exact AuthSession object the provider issued, the same way "sign out everywhere" from another
        // device would: AuthCoordinator deliberately does not expose raw sessions through its public AccountUiState.
        val provider = FakeAuthProvider { clockNow }
        val layout = StorageLayout(File(temporary.root, "files"))
        val gate = OperationGate()
        val holder = StorageSessionHolder(context, gate, AccountStorageCoordinator(layout, ActiveNamespaceStore(File(temporary.root, "nb")),
            SwitchJournal(File(temporary.root, "nb")), gate, { location, guard -> StorageDatabases.build(context, location, guard) { it.allowMainThreadQueries() }.also { opened += it } }))
        val vault = EncryptedSessionVault(File(temporary.root, "sessions"), SessionVaultTest.JvmGcmCipher())
        val manager = AuthManager(provider, vault, clock = { clockNow })
        val c = AuthCoordinator(manager, vault, holder)
        c.restoreOnStart()
        c.signIn(SignInRequest.Email("ana@example.invalid", "clave-sintetica-a".toCharArray()))
        val session = (manager.state as AuthState.Authenticated).session
        assertEquals(ACCOUNT_A, session.identity.accountId) // Sanity on what we are about to revoke.
        provider.revoke(session)
        c.refreshValidity()
        val state = c.state.value as AccountUiState.Expired
        assertEquals("a***@example.invalid", state.maskedEmail)
        assertEquals(SessionLoad.Missing, vault.load(ACCOUNT_A)) // Cleared: a revoked session is never kept.
        assertTrue(context.databaseFile(layout.locate(StorageNamespace.forAccount(ACCOUNT_A))).exists()) // Calendar data untouched.
    }

    @Test fun signOutReturnsToGuestAndPreservesTheAccountsData() = runTest {
        val c = coordinator()
        c.restoreOnStart()
        c.signIn(SignInRequest.Email("ana@example.invalid", "clave-sintetica-a".toCharArray()))
        val layout = StorageLayout(File(temporary.root, "files"))
        val outcome = c.signOut()
        assertTrue(outcome is SwitchOutcome.Switched)
        assertEquals(AccountUiState.Guest, c.state.value)
        assertTrue(context.databaseFile(layout.locate(StorageNamespace.forAccount(ACCOUNT_A))).exists()) // Not deleted.
    }

    @Test fun switchingAccountsNeverExposesTheOtherAccountsData() = runTest {
        val gate = OperationGate()
        val c = coordinator(gate = gate)
        c.restoreOnStart()
        c.signIn(SignInRequest.Email("ana@example.invalid", "clave-sintetica-a".toCharArray()))
        val layout = StorageLayout(File(temporary.root, "files"))
        // Write into A's database through a direct, separate connection (production would use the injected
        // repository; this test only needs to put real data on disk to prove it stays there after the switch).
        StorageDatabases.build(context, layout.locate(StorageNamespace.forAccount(ACCOUNT_A)), SessionGuard()) { it.allowMainThreadQueries() }.let { db ->
            try { ScheduleRepository(db, gate).save(ScheduleRepository(db, gate).snapshot(), listOf(day("2026-12-03", DayKind.VACATION))) } finally { db.close() }
        }
        val outcome = c.switchAccount(SignInRequest.Email("bea@example.invalid", "clave-sintetica-b".toCharArray()))
        assertEquals(AuthCoordinatorOutcome.Success, outcome)
        val state = c.state.value as AccountUiState.Authenticated
        assertEquals("b***@example.invalid", state.maskedEmail)
        assertEquals(0, state.pendingChanges) // B's own, unrelated database: A's write never crossed over.
        // A's data is still on disk, unaffected by being switched away from.
        StorageDatabases.build(context, layout.locate(StorageNamespace.forAccount(ACCOUNT_A)), SessionGuard()) { it.allowMainThreadQueries() }.let { db ->
            try { assertEquals(listOf("2026-12-03"), ScheduleRepository(db).snapshot().map { it.day.date.toString() }) } finally { db.close() }
        }
    }

    @Test fun failedStorageSwitchDuringSignInRollsBackToGuest() = runTest {
        val gate = OperationGate()
        val c = coordinator(gate = gate)
        c.restoreOnStart()
        val release = CompletableDeferred<Unit>()
        val holding = launch { gate.operation("calendar-save") { release.await() } }
        yield()
        val outcome = c.signIn(SignInRequest.Email("ana@example.invalid", "clave-sintetica-a".toCharArray()))
        assertTrue(outcome is AuthCoordinatorOutcome.Busy)
        assertEquals(AccountUiState.Guest, c.state.value) // Rolled back: AuthManager signed back out, storage never left Guest.
        release.complete(Unit); holding.join()
    }

    @Test fun failedStorageSwitchDuringAccountSwitchFallsBackAllTheWayToGuest() = runTest {
        val gate = OperationGate()
        val c = coordinator(gate = gate)
        c.restoreOnStart()
        c.signIn(SignInRequest.Email("ana@example.invalid", "clave-sintetica-a".toCharArray()))
        val release = CompletableDeferred<Unit>()
        val holding = launch { gate.operation("calendar-save") { release.await() } }
        yield()
        val outcome = c.switchAccount(SignInRequest.Email("bea@example.invalid", "clave-sintetica-b".toCharArray()))
        assertTrue(outcome is AuthCoordinatorOutcome.Busy)
        // The same held gate that refused switching to B also refuses the coordinator's own guest-fallback attempt
        // (AuthCoordinator deliberately does not special-case this): storage never moves off A (still open, still
        // A's data, exactly as AccountStorageCoordinator's Busy contract promises), while AuthManager has already
        // moved on to Guest. publish() is built to never trust AuthManager's state alone for exactly this reason
        // (see its own doc comment): it checks the real storage binding and reports "sign in again" for the account
        // actually on screen, never "Sin cuenta" (which would wrongly imply nothing sensitive is being shown) and
        // never B's identity (which storage was never switched to). No cross-account exposure either way.
        assertEquals(AccountUiState.Expired("esta cuenta"), c.state.value)
        release.complete(Unit); holding.join()
        // Once the gate frees up, a plain sign-out correctly returns to Guest and A's data is still intact.
        assertTrue(c.signOut() is SwitchOutcome.Switched)
        assertEquals(AccountUiState.Guest, c.state.value)
        val layout = StorageLayout(File(temporary.root, "files"))
        assertTrue(context.databaseFile(layout.locate(StorageNamespace.forAccount(ACCOUNT_A))).exists())
    }

    @Test fun restoreOnStartRecreatesAuthenticatedStateAfterARestart() = runTest {
        val provider = FakeAuthProvider { clockNow }
        // The same cipher instance across both coordinators simulates the Android Keystore key surviving an
        // ordinary process restart (a fresh JvmGcmCipher() per call, as the other tests use, would instead simulate
        // the key being lost, which restoreOnStartShowsSignInAgain... below already covers on its own terms).
        val cipher = SessionVaultTest.JvmGcmCipher()
        val first = coordinator(provider, cipher = cipher)
        first.restoreOnStart()
        first.signIn(SignInRequest.Email("ana@example.invalid", "clave-sintetica-a".toCharArray()))
        opened.forEach { runCatching { it.close() } } // Process ends.
        val second = coordinator(provider, cipher = cipher)
        second.restoreOnStart()
        val state = second.state.value as AccountUiState.Authenticated
        assertEquals("a***@example.invalid", state.maskedEmail)
    }

    @Test fun restoreOnStartShowsSignInAgainWhenTheSessionIsUnreadableButTheAccountDataIsStillBound() = runTest {
        val layout = StorageLayout(File(temporary.root, "files"))
        val gate = OperationGate()
        val holder = StorageSessionHolder(context, gate, AccountStorageCoordinator(layout, ActiveNamespaceStore(File(temporary.root, "nb")),
            SwitchJournal(File(temporary.root, "nb")), gate, { location, guard -> StorageDatabases.build(context, location, guard) { it.allowMainThreadQueries() }.also { opened += it } }))
        // Bind the account database directly (no session ever saved), simulating a session file lost/corrupted
        // independently of the data (e.g. Keystore key invalidated by a lock-screen change).
        holder.switchTo(StorageNamespace.forAccount(ACCOUNT_A), ACCOUNT_A)
        opened.forEach { runCatching { it.close() } }
        val c = coordinator()
        c.restoreOnStart()
        val state = c.state.value as AccountUiState.Expired
        assertEquals("esta cuenta", state.maskedEmail) // No identity available: a generic label, never an invented email.
    }

    @Test fun pendingSyncOperationsSurviveSignOutAndSignInAgain() = runTest {
        val c = coordinator()
        c.restoreOnStart()
        c.signIn(SignInRequest.Email("ana@example.invalid", "clave-sintetica-a".toCharArray()))
        val layout = StorageLayout(File(temporary.root, "files"))
        val gate = OperationGate()
        // Write directly against the account's database the coordinator just opened, through its own storage holder
        // would require exposing the scope; instead, open the same file again briefly to enqueue a change (separate
        // connection, same file, closed immediately) the way production code would via its own injected repository.
        val db = StorageDatabases.build(context, layout.locate(StorageNamespace.forAccount(ACCOUNT_A)), SessionGuard()) { it.allowMainThreadQueries() }
        try { ScheduleRepository(db, gate).save(ScheduleRepository(db, gate).snapshot(), listOf(day("2026-12-05", DayKind.VACATION))) } finally { db.close() }
        c.signOut()
        assertEquals(AccountUiState.Guest, c.state.value)
        val second = c.signIn(SignInRequest.Email("ana@example.invalid", "clave-sintetica-a".toCharArray()))
        assertEquals(AuthCoordinatorOutcome.Success, second)
        val state = c.state.value as AccountUiState.Authenticated
        assertEquals(1, state.pendingChanges) // The queued change from before sign-out is still there.
    }
}
