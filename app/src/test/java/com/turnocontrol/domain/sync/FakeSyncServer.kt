package com.turnocontrol.domain.sync

import java.time.LocalDate

class SyncForbiddenException : SyncTransportException("La operación no pertenece a la cuenta de la sesión.")

/**
 * Deterministic in-memory model of the proposed Supabase RPCs (`sync_push` / `sync_pull`). No sockets, no HTTP,
 * no real data. Semantics mirror the SQL proposal:
 *  - the owner comes from the session token the server issued, never from the request;
 *  - per-record versions and a per-account sequence (the cursor) are assigned here only;
 *  - a push batch is one transaction: an injected failure before the end leaves nothing applied;
 *  - operation ids are remembered per account, so a resent operation returns its stored result;
 *  - writes are compare-and-swap on the base version; deletions become tombstones;
 *  - every replaced version goes to a history kept for [RetentionPolicy.days].
 */
class FakeSyncServer(private val today: () -> LocalDate) {
    enum class Failure { BEFORE_APPLY, AFTER_APPLY, PULL }
    data class HistoryEntry(val replaced: RemoteRecord, val replacedOn: LocalDate)

    private class Account {
        var records = linkedMapOf<RecordKey, RemoteRecord>()
        var lastSeq = 0L
        var applied = mutableMapOf<String, PushResult>()
        var history = mutableListOf<HistoryEntry>()
        fun copy() = Account().also { a -> a.records = LinkedHashMap(records); a.lastSeq = lastSeq; a.applied = applied.toMutableMap(); a.history = history.toMutableList() }
    }

    private val accounts = mutableMapOf<String, Account>()
    private val sessions = mutableMapOf<String, String>()
    private val failures = ArrayDeque<Failure>()
    private var issued = 0
    /** Every request body the server ever received, for leakage assertions. */
    val received = mutableListOf<PushRequest>()

    fun signIn(accountId: String): String = "session-${++issued}".also { sessions[it] = accountId }
    fun revoke(token: String) { sessions.remove(token) }
    fun failNext(vararg failure: Failure) { failures.addAll(failure) }

    /** Administrative views for assertions (what the database would hold). */
    fun records(accountId: String): Map<RecordKey, RemoteRecord> = accounts[accountId]?.records.orEmpty().toMap()
    fun history(accountId: String): List<HistoryEntry> = accounts[accountId]?.history.orEmpty().toList()

    fun transport(token: String): SyncTransport = object : SyncTransport {
        override val accountId: String get() = sessions[token] ?: throw SyncUnauthorizedException()
        override fun push(deviceId: String, changes: List<PushRequest>) = this@FakeSyncServer.push(token, deviceId, changes)
        override fun pull(since: Long, limit: Int) = this@FakeSyncServer.pull(token, since, limit)
    }

    /** [claimedOwner] models a forged owner field in a request: it is never trusted, and a mismatch is refused. */
    fun push(token: String, deviceId: String, changes: List<PushRequest>, claimedOwner: String? = null): List<PushResult> {
        val owner = sessions[token] ?: throw SyncUnauthorizedException()
        if (claimedOwner != null && claimedOwner != owner) throw SyncForbiddenException()
        require(deviceId.isNotBlank() && changes.size <= 100)
        if (failures.firstOrNull() == Failure.BEFORE_APPLY) { failures.removeFirst(); throw SyncTransportException("Conexión cortada antes de aplicar.") }
        received += changes
        val account = accounts.getOrPut(owner) { Account() }
        val working = account.copy() // One transaction: committed only at the end.
        val results = changes.map { request -> apply(working, request) }
        accounts[owner] = working
        if (failures.firstOrNull() == Failure.AFTER_APPLY) { failures.removeFirst(); throw SyncTransportException("Respuesta perdida después de aplicar.") }
        return results
    }

    private fun apply(account: Account, request: PushRequest): PushResult {
        account.applied[request.operationId]?.let { return it }
        val invalid = when (request.op) {
            ChangeOp.UPSERT -> if (request.payload == null) "Falta el contenido." else SyncCodecs.validationError(request.key, request.payload, request.dataFormat)
            ChangeOp.DELETE -> if (request.payload != null) "Un borrado no lleva contenido." else null
        }
        val current = account.records[request.key]
        val result = when {
            invalid != null -> PushResult.Rejected(request.operationId, invalid)
            current?.version != request.baseVersion -> PushResult.Conflict(request.operationId, current)
            else -> {
                if (current != null) account.history += HistoryEntry(current, today())
                val seq = ++account.lastSeq
                val version = (current?.version ?: 0) + 1
                account.records[request.key] = RemoteRecord(request.key, version, seq, request.op == ChangeOp.DELETE, request.dataFormat,
                    request.payload, request.operationId)
                PushResult.Applied(request.operationId, version, seq)
            }
        }
        account.applied[request.operationId] = result
        return result
    }

    fun pull(token: String, since: Long, limit: Int): PullPage {
        val owner = sessions[token] ?: throw SyncUnauthorizedException()
        if (failures.firstOrNull() == Failure.PULL) { failures.removeFirst(); throw SyncTransportException("Conexión cortada durante la descarga.") }
        val newer = accounts[owner]?.records?.values.orEmpty().filter { it.seq > since }.sortedBy { it.seq }
        val page = newer.take(limit.coerceIn(1, 1000))
        return PullPage(page, page.lastOrNull()?.seq ?: since, newer.size > page.size)
    }

    /** Test hook: write directly as another, possibly newer, client would (e.g. an unknown data format). */
    fun injectRaw(accountId: String, key: RecordKey, payload: SyncPayload?, dataFormat: Int, deleted: Boolean = false): RemoteRecord {
        val account = accounts.getOrPut(accountId) { Account() }
        val current = account.records[key]
        current?.let { account.history += HistoryEntry(it, today()) }
        return RemoteRecord(key, (current?.version ?: 0) + 1, ++account.lastSeq, deleted, dataFormat, payload, "raw-${account.lastSeq}").also { account.records[key] = it }
    }
}
