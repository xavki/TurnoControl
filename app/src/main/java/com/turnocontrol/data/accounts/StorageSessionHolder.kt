package com.turnocontrol.data.accounts

import android.content.Context
import com.turnocontrol.data.BackupRepository
import com.turnocontrol.data.CompatibilityChecker
import com.turnocontrol.data.ConfigurationRepository
import com.turnocontrol.data.ScheduleRepository
import com.turnocontrol.data.SyncStore
import com.turnocontrol.data.TurnoDatabase
import com.turnocontrol.domain.auth.AccountId
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything that holds a storage session (phase 4B.6.1). One instance of each repository per session, so locks and
 * observers keep their identity inside a session, and nothing survives into the next one: after a switch the old
 * scope's repositories reach a closed, guarded database and fail with [StorageSessionClosedException].
 */
class StorageScope internal constructor(val session: StorageSession, context: Context, gate: OperationGate) {
    val database: TurnoDatabase get() = session.database
    val schedule by lazy { ScheduleRepository(session.database, gate) }
    val configuration by lazy { ConfigurationRepository(session.database, context, gate) }
    val backup by lazy { BackupRepository(session.database, configuration, context, gate, session.location) }
    val sync by lazy { SyncStore(session.database, gate) }
    val compatibility by lazy { CompatibilityChecker(session.database) }
}

/**
 * Process-wide owner of the active storage session. Hilt provides the current scope's instances (unscoped), so a
 * ViewModel created after a switch gets the new account's repositories; the previous Activity's ViewModels are
 * discarded by a controlled task restart (see AccountSwitchRestart), never by killing the process.
 * In production the committed namespace is always the guest one: no account can be selected yet.
 */
@Singleton
class StorageSessionHolder internal constructor(
    private val context: Context,
    private val gate: OperationGate,
    private val coordinator: AccountStorageCoordinator,
) {
    @Inject constructor(@ApplicationContext context: Context, gate: OperationGate) : this(context, gate, AccountStorageCoordinator(
        StorageLayout(context.filesDir), ActiveNamespaceStore(File(context.noBackupFilesDir, "accounts")),
        SwitchJournal(File(context.noBackupFilesDir, "accounts")), gate, { location, guard -> StorageDatabases.build(context, location, guard) }))

    private val lock = Any()
    private var scope: StorageScope? = null

    /** The current scope; opens the committed namespace on first use without touching the database file. */
    fun scope(): StorageScope = synchronized(lock) {
        scope?.takeIf { it.session.open } ?: StorageScope(coordinator.current ?: coordinator.openCommitted(), context, gate).also { scope = it }
    }

    /** Startup gate: null when the active data may be shown. */
    suspend fun verifyActive(): String? { scope(); return coordinator.verifyCurrent() }

    /**
     * Switches storage. Not reachable from the production UI until real authentication exists. On success the caller
     * must restart the task immediately (AccountSwitchRestart) so no screen keeps the previous scope.
     */
    suspend fun switchTo(target: StorageNamespace, account: AccountId?, blockers: suspend () -> String? = { null }): SwitchOutcome {
        scope()
        blockers()?.let { return SwitchOutcome.Busy(it) }
        return coordinator.switchTo(target, account).also { outcome ->
            synchronized(lock) {
                scope = when (outcome) {
                    is SwitchOutcome.Switched -> StorageScope(outcome.session, context, gate)
                    is SwitchOutcome.Failed -> StorageScope(outcome.session, context, gate)
                    is SwitchOutcome.Busy -> scope
                }
            }
        }
    }
}

/** Controlled restart after a successful switch: a new task with new ViewModels; the process keeps running. */
object AccountSwitchRestart {
    fun restart(activity: android.app.Activity) {
        val intent = android.content.Intent(activity, activity.javaClass)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        activity.startActivity(intent)
        activity.finish()
    }
}
