package com.turnocontrol.domain.sync

import java.time.LocalDate

/*
 * Offline synchronization model (TurnoControl 0.3.0, phase 2). Pure Kotlin: no Android, network, Room or account
 * storage. It models the protocol described in docs/ARQUITECTURA_0.3.0_CUENTAS_Y_SINCRONIZACION.md so that the
 * future Room and Supabase layers only have to persist these values and move them over the wire.
 *
 * Invariants:
 *  - Records are identified by natural keys (date, rate type + start date, pay period), never by local row ids.
 *  - Versions are assigned by the server only; device clocks never order anything.
 *  - Every outgoing change has a stable operation id; resending it can never apply it twice.
 *  - Different content is never overwritten silently: it becomes a [SyncConflict] for the user.
 *  - Payloads are an allowlist of calendar fields (date, day state, intervals, origin). Sick leave is synchronized
 *    as a plain day state (revised decision D9): no diagnosis, reason or document field exists anywhere in the model.
 */

/** Data format of synchronized payloads understood by this version. Newer payloads are quarantined, never coerced. */
const val SYNC_DATA_FORMAT = 1

enum class SyncEntity { SCHEDULED_DAY, PAY_RULE, PAYSLIP, PROFILE, CONTRACT }

/** Natural key inside one account: the account itself is never part of what a client sends. */
data class RecordKey(val entity: SyncEntity, val key: String) {
    override fun toString() = "${entity.name}:$key"
}

/** Canonical, deterministic content of a shareable record. Equal text means equal content. */
data class SyncPayload(val entity: SyncEntity, val text: String)

/**
 * What a device holds for a key. Every stored calendar state is synchronized; adding a device-only kind of record
 * would require an explicit owner decision.
 */
sealed interface LocalRecord {
    data class Shared(val payload: SyncPayload) : LocalRecord
}

/** The server's state of a record as last seen by this device. */
data class KnownVersion(val version: Long, val deleted: Boolean, val payload: SyncPayload?)

enum class ChangeOp { UPSERT, DELETE }
enum class ChangeState { PENDING, IN_FLIGHT }

/**
 * Outgoing change. [baseVersion] is the server version the edit was based on (null = the device knows no server
 * record). [afterPredecessor] marks a follow-up edit queued behind an in-flight change: its base is resolved from the
 * predecessor's acknowledged version, never copied from it.
 */
data class PendingChange(
    val operationId: String, val key: RecordKey, val op: ChangeOp, val payload: SyncPayload?,
    val baseVersion: Long?, val afterPredecessor: Boolean = false, val state: ChangeState = ChangeState.PENDING, val attempts: Int = 0,
) {
    init {
        require((op == ChangeOp.DELETE) == (payload == null)) { "Un borrado no lleva contenido y una escritura siempre lo lleva." }
        require(payload == null || payload.entity == key.entity) { "El contenido no corresponde a la entidad." }
    }
}

/** What the server sends for a record. [operationId] is the change that produced this version. */
data class RemoteRecord(
    val key: RecordKey, val version: Long, val seq: Long, val deleted: Boolean, val dataFormat: Int,
    val payload: SyncPayload?, val operationId: String,
)

data class PushRequest(val operationId: String, val key: RecordKey, val op: ChangeOp, val payload: SyncPayload?, val baseVersion: Long?, val dataFormat: Int = SYNC_DATA_FORMAT)

sealed interface PushResult {
    val operationId: String
    data class Applied(override val operationId: String, val version: Long, val seq: Long) : PushResult
    /** [remote] is null when the server has no record although the change expected one. */
    data class Conflict(override val operationId: String, val remote: RemoteRecord?) : PushResult
    data class Rejected(override val operationId: String, val reason: String) : PushResult
}

data class PullPage(val changes: List<RemoteRecord>, val nextCursor: Long, val hasMore: Boolean)

/** Transport-level failures. Nothing about their outcome is assumed: the client resends or reconciles. */
open class SyncTransportException(message: String) : Exception(message)
class SyncUnauthorizedException : SyncTransportException("Sesión no válida o caducada.")

/** Future Supabase RPC boundary. One transport is bound to one authenticated account. */
interface SyncTransport {
    /** Account of the session, as established by the server, never by the client. */
    val accountId: String
    fun push(deviceId: String, changes: List<PushRequest>): List<PushResult>
    fun pull(since: Long, limit: Int): PullPage
}

enum class ConflictKind {
    /** The same record changed on another device since this device's base version. */
    VERSION,
    /** Applying the remote record would break a rule across records (overnight overlap, rate periods). */
    INVARIANT,
    /** The server refused the change. */
    REJECTED,
}

/** An unresolved disagreement. Both sides are kept until the user decides. */
data class SyncConflict(val key: RecordKey, val kind: ConflictKind, val local: LocalRecord?, val remote: RemoteRecord?, val detail: String? = null)

enum class RecoverySource { LOCAL, REMOTE }
/** Losing content of a resolved conflict, kept for recovery during [RetentionPolicy.days]. Never sent anywhere. */
data class RecoveredVersion(val key: RecordKey, val source: RecoverySource, val content: LocalRecord?, val discardedOn: LocalDate)

/** Proposed retention of losing versions and server history (90 days). Only the rule is modelled, not the cleanup. */
data class RetentionPolicy(val days: Long = 90) {
    fun isRecoverable(discardedOn: LocalDate, today: LocalDate) = !today.isAfter(discardedOn.plusDays(days))
}
