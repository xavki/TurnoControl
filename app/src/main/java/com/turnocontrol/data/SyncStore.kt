package com.turnocontrol.data

import androidx.room.withTransaction
import com.turnocontrol.domain.DataFormat
import com.turnocontrol.domain.sync.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A save touched a record with an unresolved synchronization conflict. Nothing was written. */
class SyncConflictPendingException(val key: RecordKey) : IllegalArgumentException(
    "No se puede guardar: ${describe(key)} tiene un conflicto de sincronización pendiente. Tus cambios no se han perdido ni se ha modificado nada; podrás guardarlos cuando se resuelva el conflicto.") {
    private companion object {
        fun describe(key: RecordKey) = when (key.entity) {
            SyncEntity.PROFILE -> "el perfil"
            SyncEntity.CONTRACT -> "el contrato"
            SyncEntity.PAY_RULE -> "la tarifa ${key.key.substringBefore("@")}"
            SyncEntity.SCHEDULED_DAY -> "el día ${key.key}"
            SyncEntity.PAYSLIP -> "la nómina de ${key.key}"
        }
    }
}

/** Domain rows <-> synchronized records. Only allowlisted fields cross this boundary (see SyncCodecs). */
internal object SyncRecords {
    private val plusTypes = setOf("NIGHT", "SPECIAL_NIGHT", "EXTRA_PAY")
    /** Entities this schema can store. Others are kept in quarantine until a schema that stores them. */
    val storedEntities = setOf(SyncEntity.SCHEDULED_DAY, SyncEntity.PAY_RULE, SyncEntity.PROFILE, SyncEntity.CONTRACT)

    fun day(stored: StoredScheduleDay): Pair<RecordKey, LocalRecord> =
        SyncCodecs.dayKey(LocalDate.parse(stored.day.date)) to SyncCodecs.encodeDay(DayRecord(stored.planned(), stored.day.origin))

    /** Rate types unknown to this build are preserved locally but not synchronized (they cannot be validated). */
    fun rate(rule: PayRule): Pair<RecordKey, LocalRecord>? = rule.takeIf { it.type in DataFormat.knownRateTypes }?.let {
        val record = RateRecord(it.type, LocalDate.parse(it.validFrom), it.validUntil?.let(LocalDate::parse), BigDecimal(it.amountDecimal), it.currency)
        SyncCodecs.rateKey(record.type, record.validFrom) to SyncCodecs.encodeRate(record)
    }
    fun profile(profile: Profile) = SyncCodecs.profileKey to SyncCodecs.encodeProfile(ProfileRecord(profile.displayName, profile.alias, profile.zoneId))
    fun contract(c: Contract) = SyncCodecs.contractKey to
        SyncCodecs.encodeContract(ContractRecord(c.type, c.agreedMinutes, c.unit, LocalDate.parse(c.validFrom), c.validUntil?.let(LocalDate::parse)))

    suspend fun configuration(db: TurnoDatabase): Map<RecordKey, LocalRecord> = buildMap {
        db.schedule().profile()?.let { val (k, v) = profile(it); put(k, v) }
        db.configuration().contract()?.let { val (k, v) = contract(it); put(k, v) }
        db.configuration().rates().mapNotNull(::rate).forEach { (k, v) -> check(put(k, v) == null) { "Tarifa duplicada para $k." } }
    }

    suspend fun all(db: TurnoDatabase): Map<RecordKey, LocalRecord> = configuration(db) + db.schedule().all().associate(::day)

    /**
     * Writes one remote/resolved record into the user tables. Returns false when this schema cannot apply it (entity
     * not stored yet, or a profile deletion, which would cascade over the whole calendar): it is then quarantined.
     */
    suspend fun apply(db: TurnoDatabase, key: RecordKey, value: LocalRecord?): Boolean {
        if (key.entity !in storedEntities) return false
        val payload = (value as LocalRecord.Shared?)?.payload
        val schedule = db.schedule(); val configuration = db.configuration()
        suspend fun ensureProfile(): Profile = schedule.profile() ?: Profile(displayName = "Mi calendario", alias = "", zoneId = ZoneId.systemDefault().id).also { schedule.insertProfile(it) }
        when (key.entity) {
            SyncEntity.PROFILE -> {
                if (payload == null) return false
                val p = SyncCodecs.decodeProfile(key, payload)
                configuration.putProfile(Profile(displayName = p.displayName, alias = p.alias, zoneId = p.zoneId))
            }
            SyncEntity.CONTRACT -> if (payload == null) configuration.deleteContract() else {
                ensureProfile()
                val c = SyncCodecs.decodeContract(key, payload)
                configuration.putContract(Contract(type = c.type, agreedMinutes = c.agreedMinutes, unit = c.unit, validFrom = c.validFrom.toString(), validUntil = c.validUntil?.toString()))
            }
            SyncEntity.PAY_RULE -> {
                val (type, from) = key.key.split("@").let { it[0] to it[1] }
                val rows = configuration.rates()
                val existing = rows.firstOrNull { it.type == type && it.validFrom == from }
                if (payload == null) { existing?.let { configuration.deleteRateById(it.id) } } else {
                    ensureProfile()
                    val r = SyncCodecs.decodeRate(key, payload)
                    // Ids are never reused: a new period gets the next free id above the historical defaults.
                    val id = existing?.id ?: ((rows.maxOfOrNull { it.id } ?: 0L).coerceAtLeast(DataFormat.knownRateTypes.size.toLong()) + 1)
                    configuration.putRate(PayRule(id = id, type = r.type, amountDecimal = r.amount.toPlainString(), mode = if (r.type in plusTypes) "PLUS" else "BASE",
                        accumulation = "UNDEFINED", validFrom = r.validFrom.toString(), validUntil = r.validUntil?.toString(), currency = r.currency))
                }
            }
            SyncEntity.SCHEDULED_DAY -> {
                val existing = schedule.day(key.key)
                if (payload == null) { existing?.let { schedule.deleteDay(it.id) } } else {
                    val record = SyncCodecs.decodeDay(key, payload)
                    val zone = existing?.zoneId ?: ensureProfile().zoneId
                    // Sick leave carries no origin in the payload: an existing local provenance is kept.
                    val origin = if (record.day.kind == com.turnocontrol.domain.DayKind.SICK_LEAVE && existing?.kind == "SICK_LEAVE") existing.origin else record.origin
                    val row = ScheduledDay(id = existing?.id ?: 0, date = key.key, kind = record.day.kind.name, zoneId = zone, origin = origin, revision = (existing?.revision ?: 0) + 1)
                    val id = if (existing == null) schedule.insertDay(row) else { schedule.updateDay(row); row.id }
                    schedule.clearIntervals(id)
                    schedule.insertIntervals(record.day.intervals.mapIndexed { i, it ->
                        ScheduledInterval(dayId = id, position = i, startMinute = it.start.hour * 60 + it.start.minute, endMinute = it.end.hour * 60 + it.end.minute,
                            startDayOffset = it.startDayOffset, endDayOffset = it.endDayOffset, isExtra = it.isExtra)
                    })
                }
            }
            SyncEntity.PAYSLIP -> return false
        }
        return true
    }
}

/**
 * Captures local edits into the outbox. Must be called inside the repository transaction that writes the user data,
 * so an edit and its outgoing change commit (or roll back) together. A database that is not linked to an account
 * records nothing: local-only data stays local, and linking later never enqueues history by itself.
 */
internal object SyncCapture {
    suspend fun isLinked(db: TurnoDatabase) = db.sync().state()?.accountId != null

    suspend fun record(db: TurnoDatabase, changes: Map<RecordKey, LocalRecord?>) {
        if (changes.isEmpty()) return
        val dao = db.sync()
        val state = dao.state()?.takeIf { it.accountId != null } ?: return
        var next = state.nextOperation
        for ((key, value) in changes) {
            if (dao.conflictCount(key.entity.name, key.key) > 0) throw SyncConflictPendingException(key)
            val known = dao.recordState(key.entity.name, key.key)?.let { key to it.known(key) }
            val partial = Replica(state.accountId!!, state.deviceId, known = listOfNotNull(known).toMap(),
                outbox = dao.outboxFor(key.entity.name, key.key).map { it.change() }, nextOperation = next)
            val staged = SyncEngine.stageChange(partial, key, value, OperationIds.Random)
            dao.deletePendingFor(key.entity.name, key.key)
            dao.insertOutbox(staged.outbox.filter { it.state == ChangeState.PENDING }.map { it.row() })
            next = staged.nextOperation
        }
        dao.updateState(state.copy(nextOperation = next))
    }
}

internal fun SyncRecordState.known(key: RecordKey) = KnownVersion(serverVersion, deleted, payload?.let { SyncPayload(key.entity, it) })
internal fun SyncOutbox.change(): PendingChange {
    val key = RecordKey(SyncEntity.valueOf(entity), recordKey)
    return PendingChange(operationId, key, ChangeOp.valueOf(op), payload?.let { SyncPayload(key.entity, it) }, baseVersion, afterPredecessor, ChangeState.valueOf(state), attempts)
}
internal fun PendingChange.row() = SyncOutbox(operationId = operationId, entity = key.entity.name, recordKey = key.key, op = op.name, payload = payload?.text,
    baseVersion = baseVersion, afterPredecessor = afterPredecessor, state = state.name, attempts = attempts)

/**
 * Persistent side of the synchronization engine. Each protocol step is its own Room transaction over freshly loaded
 * state, and network calls happen outside any transaction:
 *  1. mark the next batch IN_FLIGHT and commit (a later edit of the same record becomes a follow-up, never a
 *     replacement of an operation the server may already have applied);
 *  2. push;
 *  3. apply the results by operation id to the reloaded state and commit;
 *  4. per page: pull, then apply the page and its cursor to the reloaded state and commit.
 * A crash between steps is safe: in-flight operations are resent with the same ids (idempotent on the server) and
 * pages are pulled again from the committed cursor.
 */
class SyncStore(private val db: TurnoDatabase, private val gate: com.turnocontrol.data.accounts.OperationGate) {
    constructor(db: TurnoDatabase) : this(db, com.turnocontrol.data.accounts.OperationGate())
    companion object {
        /** Reason of a valid record this schema cannot store yet; it is restored as-is once the schema can. */
        const val PARKED = "Este dispositivo todavía no puede guardar este dato."
    }

    private data class Loaded(val replica: Replica, val state: SyncState, val replayable: Set<RecordKey>, val reasons: Map<RecordKey, String>)

    private suspend fun ensureState(): SyncState = db.sync().state() ?: SyncState(deviceId = UUID.randomUUID().toString(), accountId = null,
        cursor = 0, nextOperation = 1, restoreReviewRequired = false, linkedAt = null).also { db.sync().insertState(it) }

    /** Random UUID created once; survives restarts and restores, never exported. */
    suspend fun deviceId(): String = db.withTransaction { ensureState().deviceId }
    suspend fun linkedAccount(): String? = db.sync().state()?.accountId

    /**
     * Binds this database to an account. For the future first-link flow only (no production caller yet). A database
     * bound to one account is never re-bound to another, and binding enqueues no existing record.
     */
    internal suspend fun link(accountId: String, now: Instant) = db.withTransaction {
        val state = ensureState()
        require(state.accountId == null || state.accountId == accountId) { "Esta base de datos pertenece a otra cuenta." }
        if (state.accountId == null) db.sync().updateState(state.copy(accountId = accountId, linkedAt = now.toString()))
    }

    private suspend fun loadInTransaction(): Loaded {
        val dao = db.sync()
        val state = ensureState()
        val quarantine = dao.quarantine()
        val replica = Replica(
            accountId = state.accountId.orEmpty(), deviceId = state.deviceId, records = SyncRecords.all(db),
            known = dao.recordStates().associate { r -> RecordKey(SyncEntity.valueOf(r.entity), r.recordKey).let { it to r.known(it) } },
            outbox = dao.outbox().map { it.change() }, cursor = state.cursor,
            conflicts = dao.conflicts().associate { c ->
                val key = RecordKey(SyncEntity.valueOf(c.entity), c.recordKey)
                key to SyncConflict(key, ConflictKind.valueOf(c.kind), c.localPayload?.let { LocalRecord.Shared(SyncPayload(key.entity, it)) },
                    c.remoteVersion?.let { RemoteRecord(key, it, c.remoteSeq!!, c.remoteDeleted!!, c.remoteDataFormat!!, c.remotePayload?.let { p -> SyncPayload(key.entity, p) }, c.remoteOperationId!!) }, c.detail)
            },
            quarantine = quarantine.associate { q -> RecordKey(SyncEntity.valueOf(q.entity), q.recordKey).let { it to q.remote(it) } },
            recovered = dao.recovered().map { r -> RecordKey(SyncEntity.valueOf(r.entity), r.recordKey).let {
                RecoveredVersion(it, RecoverySource.valueOf(r.source), r.payload?.let { p -> LocalRecord.Shared(SyncPayload(it.entity, p)) }, LocalDate.parse(r.discardedOn)) } },
            nextOperation = state.nextOperation,
        )
        // Re-evaluated once per build: only rows judged by an older payload format or schema.
        val replayable = quarantine.filter { it.evaluatedFormat < SYNC_DATA_FORMAT || it.evaluatedSchema < DataFormat.ROOM_SCHEMA_VERSION }
            .map { RecordKey(SyncEntity.valueOf(it.entity), it.recordKey) }.toSet()
        return Loaded(replica, state, replayable, quarantine.associate { RecordKey(SyncEntity.valueOf(it.entity), it.recordKey) to it.reason })
    }

    suspend fun load(): Replica = db.withTransaction { loadInTransaction().replica }

    /**
     * One local step: load, compute and write inside the same transaction, so no concurrent local edit can be lost.
     * Quarantined rows due for re-evaluation are replayed first.
     */
    private suspend fun <T> step(run: (Replica) -> Pair<Replica, T>): T = withContext(Dispatchers.IO) {
        db.withTransaction {
            val loaded = loadInTransaction()
            val (after, result) = run(replay(loaded))
            // A step that changed nothing writes nothing, except to stamp re-evaluated quarantine rows so they are not retried.
            if (after != loaded.replica || loaded.replayable.isNotEmpty()) write(loaded, after)
            result
        }
    }

    /**
     * Parked records were valid and already acknowledged, so they return straight to the records once their entity is
     * stored; everything else goes through the engine's normal incoming checks again.
     */
    private fun replay(loaded: Loaded): Replica {
        val (parked, held) = loaded.replayable.partition { loaded.reasons[it] == PARKED && it.entity in SyncRecords.storedEntities }
        val restored = parked.fold(loaded.replica) { r, key ->
            val q = r.quarantine.getValue(key)
            r.copy(quarantine = r.quarantine - key, records = if (q.deleted) r.records - key else r.records + (key to LocalRecord.Shared(q.payload!!)))
        }
        return SyncEngine.replayQuarantined(restored, held.toSet())
    }

    private fun SyncQuarantine.remote(key: RecordKey) = RemoteRecord(key, version, seq, deleted, dataFormat, payload?.let { SyncPayload(key.entity, it) }, operationId)

    /** Failure-injection hook for tests: runs inside the write transaction, before anything is written. */
    internal var beforeCommitWrites: suspend () -> Unit = {}

    /** Writes [after] over [loaded]. Must run in the transaction that loaded [loaded]. */
    private suspend fun write(loaded: Loaded, after: Replica) {
        check(after.accountId == loaded.replica.accountId && after.deviceId == loaded.replica.deviceId)
        beforeCommitWrites()
        val dao = db.sync()
        val parked = mutableMapOf<RecordKey, SyncQuarantine>()
        val order = listOf(SyncEntity.PROFILE, SyncEntity.CONTRACT, SyncEntity.PAY_RULE, SyncEntity.SCHEDULED_DAY, SyncEntity.PAYSLIP)
        (loaded.replica.records.keys + after.records.keys).filter { loaded.replica.records[it] != after.records[it] }.sortedBy { order.indexOf(it.entity) }.forEach { key ->
            val value = after.records[key]
            if (!SyncRecords.apply(db, key, value)) {
                val known = after.known[key]
                parked[key] = SyncQuarantine(key.entity.name, key.key, known?.version ?: 0, 0, value == null, SYNC_DATA_FORMAT, (value as LocalRecord.Shared?)?.payload?.text,
                    "", PARKED, SYNC_DATA_FORMAT, DataFormat.ROOM_SCHEMA_VERSION)
            }
        }
        dao.deleteRecordStates(); dao.insertRecordStates(after.known.map { (k, v) -> SyncRecordState(k.entity.name, k.key, v.version, v.deleted, v.payload?.text) })
        dao.deleteOutbox(); dao.insertOutbox(after.outbox.map { it.row() })
        dao.deleteConflicts(); dao.insertConflicts(after.conflicts.values.map { c ->
            SyncConflictRow(c.key.entity.name, c.key.key, c.kind.name, (c.local as LocalRecord.Shared?)?.payload?.text, c.remote?.version, c.remote?.seq, c.remote?.deleted,
                c.remote?.dataFormat, c.remote?.payload?.text, c.remote?.operationId, c.detail)
        })
        dao.deleteQuarantine(); dao.insertQuarantine(after.quarantine.map { (k, r) ->
            SyncQuarantine(k.entity.name, k.key, r.version, r.seq, r.deleted, r.dataFormat, r.payload?.text, r.operationId,
                loaded.reasons[k] ?: "Formato no admitido o datos no válidos.", SYNC_DATA_FORMAT, DataFormat.ROOM_SCHEMA_VERSION)
        } + parked.filterKeys { it !in after.quarantine }.values)
        dao.deleteRecovered(); dao.insertRecovered(after.recovered.map { r ->
            SyncRecovered(entity = r.key.entity.name, recordKey = r.key.key, source = r.source.name, payload = (r.content as LocalRecord.Shared?)?.payload?.text, discardedOn = r.discardedOn.toString())
        })
        dao.updateState(loaded.state.copy(cursor = after.cursor, nextOperation = after.nextOperation))
    }

    /** One synchronization pass against [transport]. Refuses unlinked databases and restores awaiting review. */
    suspend fun synchronize(transport: SyncTransport, batchSize: Int = SyncEngine.BATCH_SIZE, pageSize: Int = SyncEngine.PAGE_SIZE): SyncResult =
        gate.operation("sync") { synchronizeLocked(transport, batchSize, pageSize) }

    private suspend fun synchronizeLocked(transport: SyncTransport, batchSize: Int, pageSize: Int): SyncResult = withContext(Dispatchers.IO) {
        val state = db.withTransaction { ensureState() }
        if (state.accountId == null) return@withContext SyncResult(load(), SyncStatus.NOT_LINKED, "No hay ninguna cuenta vinculada.")
        if (state.restoreReviewRequired) return@withContext SyncResult(load(), SyncStatus.REVIEW_REQUIRED, "Revisa los datos restaurados antes de sincronizar.")
        val owner = try { transport.accountId } catch (e: SyncUnauthorizedException) { return@withContext SyncResult(load(), SyncStatus.AUTH_REQUIRED, e.message) }
        if (owner != state.accountId) return@withContext SyncResult(load(), SyncStatus.ACCOUNT_MISMATCH, "La sesión pertenece a otra cuenta.")
        step { it to Unit } // Local quarantine re-evaluation, if due.
        val status = try {
            while (true) {
                val sent = step { replica -> SyncEngine.prepareBatch(replica, batchSize) }
                if (sent.isEmpty()) break
                val results = transport.push(state.deviceId, SyncEngine.requests(sent))
                step { replica -> SyncEngine.applyPushResults(replica, sent, results) to Unit }
            }
            while (true) {
                val cursor = db.sync().state()!!.cursor
                val page = transport.pull(cursor, pageSize)
                step { replica -> SyncEngine.applyPage(replica, page) to Unit }
                if (!page.hasMore) break
            }
            SyncStatus.COMPLETED
        } catch (e: SyncUnauthorizedException) { SyncStatus.AUTH_REQUIRED } catch (e: SyncTransportException) { SyncStatus.INTERRUPTED }
        SyncResult(load(), status)
    }

    suspend fun resolve(key: RecordKey, resolution: Resolution, today: LocalDate): ResolutionResult = gate.operation("sync") {
        step { replica -> SyncEngine.resolve(replica, key, resolution, today, OperationIds.Random).let { it.replica to it } }
    }

    /** Re-evaluates quarantined records once after an update; still unsupported ones stay quarantined. */
    suspend fun replayQuarantine(): Replica {
        if (db.sync().state()?.accountId == null) return load()
        return step { it to it }.let { load() }
    }
}
