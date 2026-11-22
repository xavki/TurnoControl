package com.turnocontrol.domain.sync

import java.time.LocalDate

/**
 * Everything one device stores for one account (the future Room side tables plus the domain rows). Immutable: every
 * engine step returns a new value, which models one committed Room transaction. An interruption therefore leaves
 * the last committed value, never a half-applied step.
 */
data class Replica(
    val accountId: String,
    val deviceId: String,
    val records: Map<RecordKey, LocalRecord> = emptyMap(),
    val known: Map<RecordKey, KnownVersion> = emptyMap(),
    val outbox: List<PendingChange> = emptyList(),
    val cursor: Long = 0,
    val conflicts: Map<RecordKey, SyncConflict> = emptyMap(),
    val quarantine: Map<RecordKey, RemoteRecord> = emptyMap(),
    val recovered: List<RecoveredVersion> = emptyList(),
    val nextOperation: Long = 1,
)

enum class EditKind { CREATED, UPDATED, DELETED, NO_OP, BLOCKED_BY_CONFLICT, REJECTED }
data class EditResult(val replica: Replica, val kind: EditKind, val error: String? = null)

enum class SyncStatus { COMPLETED, INTERRUPTED, AUTH_REQUIRED, ACCOUNT_MISMATCH }
data class SyncResult(val replica: Replica, val status: SyncStatus, val error: String? = null) {
    /** Conflicts are never decided by the engine. */
    val requiresUserDecision: Boolean get() = replica.conflicts.isNotEmpty()
}

enum class Resolution { KEEP_LOCAL, KEEP_REMOTE, CANCEL }
enum class ResolutionOutcome { KEPT_LOCAL, KEPT_REMOTE, CANCELLED, ALREADY_IDENTICAL, NO_CONFLICT, REJECTED }
data class ResolutionResult(val replica: Replica, val outcome: ResolutionOutcome, val error: String? = null)

/** Offline-first synchronization rules. Stateless; all state is in [Replica]. */
object SyncEngine {
    const val BATCH_SIZE = 50
    const val PAGE_SIZE = 500

    /** A local edit (null = delete). Validated across records, then queued if the server must learn about it. */
    fun edit(replica: Replica, key: RecordKey, value: LocalRecord?): EditResult {
        if (key in replica.conflicts) return EditResult(replica, EditKind.BLOCKED_BY_CONFLICT, "Resuelve primero el conflicto de este registro.")
        val old = replica.records[key]
        if (old == value) return EditResult(replica, EditKind.NO_OP)
        if (value is LocalRecord.Shared) require(value.payload.entity == key.entity) { "El contenido no corresponde a la entidad." }
        val records = if (value == null) replica.records - key else replica.records + (key to value)
        SyncRules.validate(records)?.let { return EditResult(replica, EditKind.REJECTED, it) }
        val kind = when { value == null -> EditKind.DELETED; old == null -> EditKind.CREATED; else -> EditKind.UPDATED }
        return EditResult(stage(replica.copy(records = records), key, value), kind)
    }

    /**
     * Brings the outbox in line with the desired local content of [key]. A tombstone is queued only for an actual
     * deletion (null); every stored state, sick leave included, is an upsert.
     */
    private fun stage(replica: Replica, key: RecordKey, value: LocalRecord?): Replica {
        val inFlight = replica.outbox.lastOrNull { it.key == key && it.state == ChangeState.IN_FLIGHT }
        val outbox = replica.outbox.filterNot { it.key == key && it.state == ChangeState.PENDING } // Unsent edits coalesce.
        val known = replica.known[key]
        // What the server will hold for this key once the device's in-flight change (if any) is applied.
        val serverPayload = if (inFlight != null) inFlight.payload else known?.takeIf { !it.deleted }?.payload
        val desired = (value as LocalRecord.Shared?)?.payload
        if (desired == serverPayload) return replica.copy(outbox = outbox)
        val change = PendingChange(operationId = "${replica.deviceId}-${replica.nextOperation}", key = key,
            op = if (desired == null) ChangeOp.DELETE else ChangeOp.UPSERT, payload = desired,
            baseVersion = if (inFlight != null) null else known?.version, afterPredecessor = inFlight != null)
        return replica.copy(outbox = outbox + change, nextOperation = replica.nextOperation + 1)
    }

    /** One synchronization pass: push the outbox, then pull until the cursor is current. */
    fun sync(replica: Replica, transport: SyncTransport, batchSize: Int = BATCH_SIZE, pageSize: Int = PAGE_SIZE): SyncResult {
        // A database is bound to one account; its data is never sent to another one.
        val owner = try { transport.accountId } catch (e: SyncUnauthorizedException) { return SyncResult(replica, SyncStatus.AUTH_REQUIRED, e.message) }
        if (owner != replica.accountId) return SyncResult(replica, SyncStatus.ACCOUNT_MISMATCH, "La sesión pertenece a otra cuenta.")
        var current = replica
        return try {
            repeat(replica.outbox.size + 1) {
                val batch = nextBatch(current, batchSize)
                if (batch.isEmpty()) return@repeat
                // Marked as in flight before sending: after a lost response the same operation ids are resent.
                current = current.copy(outbox = current.outbox.map { c -> if (batch.any { it.operationId == c.operationId }) c.copy(state = ChangeState.IN_FLIGHT, attempts = c.attempts + 1) else c })
                val sent = batch.map { b -> current.outbox.first { it.operationId == b.operationId } }
                val results = transport.push(current.deviceId, sent.map { PushRequest(it.operationId, it.key, it.op, it.payload, it.baseVersion) }).associateBy { it.operationId }
                if (sent.any { it.operationId !in results }) throw SyncTransportException("Respuesta incompleta del servidor.")
                current = sent.fold(current) { r, change -> applyResult(r, change, results.getValue(change.operationId)) }
            }
            while (true) {
                val page = transport.pull(current.cursor, pageSize)
                if (page.hasMore && page.nextCursor <= current.cursor) throw SyncTransportException("El servidor no avanzó el cursor.")
                // One page = one transaction: the records and the cursor are committed together.
                current = page.changes.fold(current) { r, remote -> applyRemote(r, remote) }.copy(cursor = maxOf(current.cursor, page.nextCursor))
                if (!page.hasMore) break
            }
            SyncResult(current, SyncStatus.COMPLETED)
        } catch (e: SyncUnauthorizedException) {
            SyncResult(current, SyncStatus.AUTH_REQUIRED, e.message)
        } catch (e: SyncTransportException) {
            SyncResult(current, SyncStatus.INTERRUPTED, e.message)
        }
    }

    /** The first queued change of each key, so one batch never holds two changes for the same record. */
    private fun nextBatch(replica: Replica, size: Int) = replica.outbox.distinctBy { it.key }.filterNot { it.afterPredecessor }.take(size)

    private fun applyResult(replica: Replica, change: PendingChange, result: PushResult): Replica = when (result) {
        is PushResult.Applied -> acknowledge(replica, change, result.version)
        is PushResult.Conflict -> {
            val rest = replica.copy(outbox = replica.outbox.filterNot { it.key == change.key })
            val local = replica.records[change.key]
            if (sameContent(local, result.remote)) adopt(rest, change.key, result.remote)
            else rest.copy(conflicts = rest.conflicts + (change.key to SyncConflict(change.key, ConflictKind.VERSION, local, result.remote)))
        }
        is PushResult.Rejected -> replica.copy(outbox = replica.outbox.filterNot { it.key == change.key },
            conflicts = replica.conflicts + (change.key to SyncConflict(change.key, ConflictKind.REJECTED, replica.records[change.key], null, result.reason)))
    }

    /** The change is on the server with [version]; a follow-up edit of the same key now knows its base. */
    private fun acknowledge(replica: Replica, change: PendingChange, version: Long): Replica {
        val outbox = replica.outbox.filterNot { it.operationId == change.operationId }
        val next = outbox.indexOfFirst { it.key == change.key }
        val resolved = if (next >= 0 && outbox[next].afterPredecessor) outbox.toMutableList().also { it[next] = it[next].copy(baseVersion = version, afterPredecessor = false) } else outbox
        val previous = replica.known[change.key]
        // A replayed acknowledgement of an older version never moves the known version backwards.
        val known = if (previous != null && previous.version >= version) replica.known
            else replica.known + (change.key to KnownVersion(version, change.op == ChangeOp.DELETE, change.payload))
        return replica.copy(outbox = resolved, known = known)
    }

    private fun adopt(replica: Replica, key: RecordKey, remote: RemoteRecord?): Replica =
        replica.copy(known = if (remote == null) replica.known - key else replica.known + (key to KnownVersion(remote.version, remote.deleted, remote.payload)))

    /** Same state on the server: equal content, or no record on either side (absent or deleted). */
    private fun sameContent(local: LocalRecord?, remote: RemoteRecord?): Boolean =
        (local as LocalRecord.Shared?)?.payload == remote?.takeIf { !it.deleted }?.payload

    private fun applyRemote(replica: Replica, remote: RemoteRecord): Replica {
        val key = remote.key
        val known = replica.known[key]
        if (known != null && remote.version <= known.version) return replica // Echo of an own change or already applied.
        replica.outbox.firstOrNull { it.key == key && it.state == ChangeState.IN_FLIGHT && it.operationId == remote.operationId }?.let {
            return acknowledge(replica, it, remote.version) // The response to this change was lost; this is its result.
        }
        val unreadable = remote.dataFormat > SYNC_DATA_FORMAT || (!remote.deleted && (remote.payload == null || SyncCodecs.validationError(key, remote.payload, remote.dataFormat) != null))
        if (unreadable) return replica.copy(quarantine = replica.quarantine + (key to remote)) // Kept intact for a newer version.
        return applyReadable(replica.copy(quarantine = replica.quarantine - key), remote) // A newer readable version supersedes it.
    }

    private fun applyReadable(replica: Replica, remote: RemoteRecord): Replica {
        val key = remote.key
        replica.conflicts[key]?.let { open -> return replica.copy(conflicts = replica.conflicts + (key to open.copy(remote = remote))) }
        val local = replica.records[key]
        if (replica.outbox.any { it.key == key }) {
            if (!sameContent(local, remote)) {
                return replica.copy(outbox = replica.outbox.filterNot { it.key == key },
                    conflicts = replica.conflicts + (key to SyncConflict(key, ConflictKind.VERSION, local, remote)))
            }
            // Identical content: unsent edits are no longer needed; an in-flight one will come back as a conflict and be adopted.
            return adopt(replica.copy(outbox = replica.outbox.filterNot { it.key == key && it.state == ChangeState.PENDING }), key, remote)
        }
        val records = if (remote.deleted) replica.records - key else replica.records + (key to LocalRecord.Shared(remote.payload!!))
        SyncRules.validate(records)?.let { reason ->
            return replica.copy(conflicts = replica.conflicts + (key to SyncConflict(key, ConflictKind.INVARIANT, local, remote, reason)))
        }
        return adopt(replica.copy(records = records), key, remote)
    }

    /** Applies an explicit user decision. The losing content is kept in [Replica.recovered]. */
    fun resolve(replica: Replica, key: RecordKey, resolution: Resolution, today: LocalDate): ResolutionResult {
        val conflict = replica.conflicts[key] ?: return ResolutionResult(replica, ResolutionOutcome.NO_CONFLICT)
        if (resolution == Resolution.CANCEL) return ResolutionResult(replica, ResolutionOutcome.CANCELLED)
        // A newer version pulled while the conflict was open is always the other side. For a refused change with no
        // such version, it is what the server last confirmed to this device.
        val remote: RemoteRecord? = conflict.remote ?: if (conflict.kind == ConflictKind.REJECTED) replica.known[key]?.let { RemoteRecord(key, it.version, 0, it.deleted, SYNC_DATA_FORMAT, it.payload, "") } else null
        val local = replica.records[key]
        val remoteContent = remote?.takeIf { !it.deleted }?.payload?.let { LocalRecord.Shared(it) }
        if (sameContent(local, remote)) return ResolutionResult(adopt(replica.copy(conflicts = replica.conflicts - key), key, remote), ResolutionOutcome.ALREADY_IDENTICAL)
        return when (resolution) {
            Resolution.KEEP_LOCAL -> {
                if (conflict.kind == ConflictKind.REJECTED) return ResolutionResult(replica, ResolutionOutcome.REJECTED, "El servidor rechazó este contenido: corrígelo o recupera la versión confirmada.")
                SyncRules.validate(replica.records)?.let { return ResolutionResult(replica, ResolutionOutcome.REJECTED, it) }
                val base = adopt(replica.copy(conflicts = replica.conflicts - key,
                    recovered = replica.recovered + RecoveredVersion(key, RecoverySource.REMOTE, remoteContent, today)), key, remote)
                ResolutionResult(stage(base, key, local), ResolutionOutcome.KEPT_LOCAL)
            }
            Resolution.KEEP_REMOTE -> {
                val records = if (remoteContent == null) replica.records - key else replica.records + (key to remoteContent)
                SyncRules.validate(records)?.let { return ResolutionResult(replica, ResolutionOutcome.REJECTED, it) }
                ResolutionResult(adopt(replica.copy(records = records, conflicts = replica.conflicts - key, outbox = replica.outbox.filterNot { it.key == key },
                    recovered = replica.recovered + RecoveredVersion(key, RecoverySource.LOCAL, local, today)), key, remote), ResolutionOutcome.KEPT_REMOTE)
            }
            Resolution.CANCEL -> ResolutionResult(replica, ResolutionOutcome.CANCELLED)
        }
    }
}
