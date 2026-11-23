package com.turnocontrol.data.accounts

import androidx.room.withTransaction
import com.turnocontrol.data.TurnoDatabase
import com.turnocontrol.domain.auth.AccountId
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/*
 * Account-isolated local storage (TurnoControl 0.3.0, phase 4B.2). Not wired into production navigation yet: the app
 * keeps using the guest database exactly as before. Invariants:
 *  - The guest database keeps its historical name ("turnocontrol.db") and recovery directory; it is never renamed,
 *    moved, deleted, linked to an account or merged automatically.
 *  - Each account gets its own database file and recovery directory, named from an opaque key derived from the
 *    validated account id (never the raw id), so no path traversal and no collision with the guest files.
 *  - Sign-out switches to the guest namespace: the account's database stays on disk, closed and unreachable.
 *  - Nothing in this file deletes a database.
 */

sealed interface StorageNamespace {
    /** Short stable tag recorded in recovery journals, so a journal is only ever applied to its own database. */
    val tag: String
    data object Guest : StorageNamespace { override val tag = "guest" }
    data class Account(val key: String) : StorageNamespace {
        init { require(KEY.matches(key)) { "Clave de espacio no válida." } }
        override val tag get() = "account-$key"
    }
    companion object {
        private val KEY = Regex("^[0-9a-f]{32}$")
        /** 128-bit opaque key; a versioned prefix keeps it from ever matching another derivation. */
        fun forAccount(id: AccountId): Account = Account(MessageDigest.getInstance("SHA-256")
            .digest("turnocontrol/account-storage/v1:${id.value}".toByteArray()).joinToString("") { "%02x".format(it) }.take(32))
        fun fromTag(tag: String): StorageNamespace? = when {
            tag == Guest.tag -> Guest
            tag.startsWith("account-") -> tag.removePrefix("account-").takeIf { KEY.matches(it) }?.let(::Account)
            else -> null
        }
    }
}

/**
 * [databaseName] is the short display/legacy name; [databasePath] is what gets passed to Room to actually open the
 * file. For the guest it is the same bare name (Android's default "databases" directory, untouched for compatibility).
 * For an account it is an absolute path inside that account's own directory: a different physical directory from the
 * guest's and from every other account's, never resolved via the shared default "databases" directory. This keeps
 * accounts from ever racing the guest database's connection teardown on the same directory (observed on Robolectric
 * on Windows as a transient SQLITE_CANTOPEN while a just-closed connection's statements were still being finalized
 * asynchronously on a background thread; see AccountLinkingTest).
 */
data class StorageLocation(val namespace: StorageNamespace, val databaseName: String, val databasePath: String, val recoveryDirectory: File)

/** Where each namespace lives. Every path is checked to stay inside the app's private files directory. */
class StorageLayout(private val filesDir: File) {
    companion object {
        const val GUEST_DATABASE = "turnocontrol.db"
        const val GUEST_RECOVERY = "backup-recovery"
    }
    fun locate(namespace: StorageNamespace): StorageLocation = when (namespace) {
        StorageNamespace.Guest -> StorageLocation(namespace, GUEST_DATABASE, GUEST_DATABASE, inside(File(filesDir, GUEST_RECOVERY)))
        is StorageNamespace.Account -> {
            val name = "turnocontrol-acct-${namespace.key}.db"
            val directory = inside(File(File(filesDir, "accounts"), namespace.key))
            StorageLocation(namespace, name, File(directory, name).path, inside(File(directory, GUEST_RECOVERY)))
        }
    }
    private fun inside(file: File): File {
        val root = filesDir.canonicalFile
        val canonical = file.canonicalFile
        require(canonical.toPath().startsWith(root.toPath())) { "Ruta fuera del almacenamiento privado." }
        return canonical
    }
}

/** The actual on-disk database file for [location]: the guest's default-directory file, or an account's own file. */
fun android.content.Context.databaseFile(location: StorageLocation): File =
    if (location.namespace == StorageNamespace.Guest) getDatabasePath(location.databaseName) else File(location.databasePath)

/** Deletes a namespace's database and its WAL/SHM siblings. Tests only; production never deletes a database. */
fun android.content.Context.deleteStorageDatabase(location: StorageLocation) {
    if (location.namespace == StorageNamespace.Guest) { deleteDatabase(location.databaseName); return }
    val file = databaseFile(location)
    listOf(file, File(file.path + "-wal"), File(file.path + "-shm"), File(file.path + "-journal")).forEach { it.delete() }
}

/** A save or switch was refused because storage is being switched. Nothing was written. */
class StorageBusyException : IllegalArgumentException("Se está cambiando de cuenta. No se ha guardado nada; inténtalo de nuevo en unos segundos.")

/**
 * Coordinates data operations with account switching. Saves, restores and synchronization run as shared operations;
 * a switch needs exclusive use and is refused (never queued) while any operation is running.
 */
@Singleton
class OperationGate @Inject constructor() {
    private val lock = Mutex()
    private val running = mutableMapOf<String, Int>()
    private var switching = false

    suspend fun <T> operation(name: String, block: suspend () -> T): T {
        lock.withLock {
            if (switching) throw StorageBusyException()
            running[name] = (running[name] ?: 0) + 1
        }
        try { return block() } finally { lock.withLock { running[name] = running.getValue(name) - 1; if (running[name] == 0) running.remove(name) } }
    }

    /** Names of operations in progress; empty when a switch may start. */
    suspend fun active(): Set<String> = lock.withLock { running.keys.toSet() }

    /** Exclusive lease for a switch, or null while anything is running. Always [release] it. */
    suspend fun tryExclusive(): SwitchLease? = lock.withLock {
        if (switching || running.isNotEmpty()) null else { switching = true; SwitchLease(this) }
    }
    internal suspend fun release() = lock.withLock { switching = false }

    class SwitchLease internal constructor(private val gate: OperationGate) { suspend fun release() = gate.release() }
}

/** Device-level record of the active namespace: the commit point of a switch. Written atomically. */
class ActiveNamespaceStore(private val directory: File) {
    private val file get() = File(directory, "active")
    fun read(): StorageNamespace = runCatching { StorageNamespace.fromTag(file.readText().trim()) }.getOrNull() ?: StorageNamespace.Guest
    fun write(namespace: StorageNamespace) = atomicWrite(directory, file, namespace.tag)
}

/** Records a switch in progress. If the process dies mid-switch, the next start finds it and keeps the committed namespace. */
class SwitchJournal(private val directory: File) {
    private val file get() = File(directory, "switch.journal")
    fun begin(from: StorageNamespace, to: StorageNamespace, at: Instant) = atomicWrite(directory, file, "${from.tag} ${to.tag} $at")
    fun pending(): Boolean = file.exists()
    fun clear() { file.delete() }
}

private fun atomicWrite(directory: File, file: File, text: String) {
    directory.mkdirs()
    val temporary = File(directory, file.name + ".tmp")
    temporary.writeText(text)
    Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
}

/**
 * Room reopens a closed database on the next query, so closing alone is not a barrier. Every account-scoped database
 * is built with this guard in its open callback: once the session is closed, any reopen through a stale repository,
 * DAO or coroutine fails instead of reading the data again.
 */
class SessionGuard {
    @Volatile var closed = false
        internal set
    fun checkOpen() { if (closed) throw StorageSessionClosedException() }
}

/**
 * A stale reference touched a closed storage session; nothing was read or written. Unchecked, because Room turns
 * checked exceptions from its open callback into coroutine cancellation; screens treat it as a storage error.
 */
class StorageSessionClosedException : IllegalStateException("La sesión de almacenamiento se cerró al cambiar de cuenta; esta referencia ya no es válida.")

/** Builds a namespace database with the shared migrations and its [SessionGuard]. */
object StorageDatabases {
    fun build(context: android.content.Context, location: StorageLocation, guard: SessionGuard, configure: (androidx.room.RoomDatabase.Builder<TurnoDatabase>) -> Unit = {}): TurnoDatabase {
        if (location.namespace is StorageNamespace.Account) File(location.databasePath).parentFile?.mkdirs()
        return androidx.room.Room.databaseBuilder(context, TurnoDatabase::class.java, location.databasePath)
            .addMigrations(*com.turnocontrol.data.ALL_MIGRATIONS)
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) = guard.checkOpen()
            })
            .also(configure).build()
    }
}

/** One open namespace. Once closed, repositories built on [database] fail loudly and never read data again. */
class StorageSession internal constructor(val location: StorageLocation, val database: TurnoDatabase, val generation: Long, private val guard: SessionGuard) {
    val open get() = !guard.closed
    internal fun close() { guard.closed = true; database.close() }
}

sealed interface SwitchOutcome {
    data class Switched(val session: StorageSession) : SwitchOutcome
    /** Refused before anything changed; the current session is still open and untouched. */
    data class Busy(val reason: String) : SwitchOutcome
    /** The target could not be opened safely; the previous namespace was reopened. */
    data class Failed(val reason: String, val session: StorageSession) : SwitchOutcome
}

/**
 * Opens and switches namespaces safely. A switch: exclusive gate lease -> blockers (pending restore, uncertain write)
 * -> journal -> close the current database -> open and verify the target -> commit the active record -> clear journal.
 * Old repositories, DAOs and coroutines hold the closed database and fail loudly instead of touching other data.
 * The production lifecycle (recreating ViewModels, a controlled restart without killing the process) comes with the
 * account UI; this class provides the tested coordination for it.
 */
class AccountStorageCoordinator(
    private val layout: StorageLayout,
    private val active: ActiveNamespaceStore,
    private val journal: SwitchJournal,
    private val gate: OperationGate,
    private val openDatabase: (StorageLocation, SessionGuard) -> TurnoDatabase,
    /** Extra reasons to refuse a switch (e.g. an uncertain calendar write shown in the UI). */
    private val blockers: suspend () -> String? = { null },
    private val clock: () -> Instant = Instant::now,
) {
    private var generation = 0L
    var current: StorageSession? = null
        private set

    /**
     * Process start without I/O on the database (safe on the main thread): an interrupted switch never committed, so
     * the committed namespace is opened. Its binding is checked asynchronously by [verifyCurrent] in the startup gate.
     */
    fun openCommitted(): StorageSession {
        check(current == null) { "El almacenamiento ya está abierto." }
        if (journal.pending()) journal.clear()
        val location = layout.locate(active.read())
        val guard = SessionGuard()
        return StorageSession(location, openDatabase(location, guard), ++generation, guard).also { current = it }
    }

    /** Startup gate check of the committed session's binding; a reason means the data must not be shown. */
    suspend fun verifyCurrent(): String? {
        val session = checkNotNull(current) { "El almacenamiento no está abierto." }
        return runCatching {
            val state = session.database.withTransaction { session.database.sync().state() }
            when (session.location.namespace) {
                StorageNamespace.Guest -> state?.accountId?.let { "Los datos locales están vinculados a una cuenta y no se muestran como datos sin cuenta." }
                is StorageNamespace.Account -> state?.accountId?.let { id ->
                    if (AccountId.parse(id)?.let { StorageNamespace.forAccount(it) } == session.location.namespace) null
                    else "Los datos de esta cuenta no corresponden a su espacio de almacenamiento."
                }
            }
        }.getOrElse { "No se pudo comprobar a quién pertenecen los datos locales. No se ha cambiado nada." }
    }

    /** Process start with verification (tests and future callers that may wait). */
    suspend fun start(): StorageSession {
        check(current == null) { "El almacenamiento ya está abierto." }
        if (journal.pending()) journal.clear()
        val namespace = active.read()
        // The account id is only known after authentication; the binding stored in the database is checked on switch.
        return openVerified(namespace, null).getOrElse {
            // A broken account database never blocks the app: fall back to the guest data (never deleted or merged).
            active.write(StorageNamespace.Guest)
            openVerified(StorageNamespace.Guest, null).getOrThrow()
        }.also { current = it }
    }

    /** Switches to [target]. For an account, [account] must be the authenticated id the namespace derives from. */
    suspend fun switchTo(target: StorageNamespace, account: AccountId?, beforeCommit: suspend () -> Unit = {}): SwitchOutcome {
        val session = checkNotNull(current) { "El almacenamiento no está abierto." }
        if (target is StorageNamespace.Account) require(account != null && StorageNamespace.forAccount(account) == target) { "La cuenta no corresponde al espacio solicitado." }
        if (target == StorageNamespace.Guest) require(account == null) { "El modo invitado no tiene cuenta." }
        if (target == session.location.namespace) return SwitchOutcome.Switched(session)
        val lease = gate.tryExclusive() ?: return SwitchOutcome.Busy("Espera a que termine el guardado, la restauración o la sincronización en curso.")
        try {
            pendingRestore(session.location)?.let { return SwitchOutcome.Busy(it) }
            blockers()?.let { return SwitchOutcome.Busy(it) }
            journal.begin(session.location.namespace, target, clock())
            session.close()
            current = null
            val opened = openVerified(target, account).getOrElse { error ->
                val previous = openVerified(session.location.namespace, null).getOrThrow()
                current = previous
                journal.clear()
                return SwitchOutcome.Failed(error.message ?: "No se pudo abrir el almacenamiento de la cuenta.", previous)
            }
            current = opened
            beforeCommit() // Test hook: an interruption here leaves the journal and the previous committed namespace.
            active.write(target)
            journal.clear()
            return SwitchOutcome.Switched(opened)
        } finally { lease.release() }
    }

    /** Sign-out: back to the guest data. The account database stays on disk, closed. */
    suspend fun signOut(): SwitchOutcome = switchTo(StorageNamespace.Guest, null)

    private fun pendingRestore(location: StorageLocation): String? =
        if (File(location.recoveryDirectory, "restore.journal").exists()) "Hay una restauración interrumpida pendiente de revisar en estos datos." else null

    /**
     * Opens a namespace and checks its binding: the guest database must never be linked to an account; an account
     * database is bound to its own account on first use (no data is enqueued) and must never belong to another one.
     */
    private suspend fun openVerified(namespace: StorageNamespace, account: AccountId?): Result<StorageSession> {
        val location = layout.locate(namespace)
        val guard = SessionGuard()
        val db = runCatching { openDatabase(location, guard) }.getOrElse { return Result.failure(it) }
        return runCatching {
            db.withTransaction {
                val state = db.sync().state()
                when (namespace) {
                    StorageNamespace.Guest -> check(state?.accountId == null) { "Los datos locales están vinculados a una cuenta; no se abren como invitado." }
                    is StorageNamespace.Account -> {
                        if (state?.accountId != null && account != null) check(state.accountId == account.value) { "Estos datos pertenecen a otra cuenta." }
                        if (state?.accountId == null && account != null) com.turnocontrol.data.SyncStore(db).link(account.value, clock())
                    }
                }
            }
            StorageSession(location, db, ++generation, guard)
        }.onFailure { guard.closed = true; db.close() }
    }
}
