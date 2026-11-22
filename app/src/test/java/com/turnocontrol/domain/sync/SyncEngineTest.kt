package com.turnocontrol.domain.sync

import com.turnocontrol.domain.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test

/** Deterministic multi-device scenarios against the in-memory server. Synthetic data only; no network. */
class SyncEngineTest {
    private val today = LocalDate.parse("2026-03-01")
    private val server = FakeSyncServer { today }

    private inner class Device(account: String, id: String) {
        var replica = Replica(account, id)
        var token = server.signIn(account)
        fun put(key: RecordKey, value: LocalRecord?): EditKind = SyncEngine.edit(replica, key, value).also { replica = it.replica }.kind
        fun sync(batch: Int = SyncEngine.BATCH_SIZE, page: Int = SyncEngine.PAGE_SIZE, transport: SyncTransport = server.transport(token)) =
            SyncEngine.sync(replica, transport, batch, page).also { replica = it.replica }
        fun resolve(key: RecordKey, resolution: Resolution) = SyncEngine.resolve(replica, key, resolution, today).also { replica = it.replica }
        fun day(date: String): PlannedDay? = key(date).let { k -> replica.records[k]?.let { SyncCodecs.localDay(k, it).day } }
    }

    private fun key(date: String) = SyncCodecs.dayKey(LocalDate.parse(date))
    private fun day(date: String, text: String, origin: String = "MANUAL") =
        TimeCellParser.parse(text).let { SyncCodecs.encodeDay(DayRecord(PlannedDay(LocalDate.parse(date), it.kind, it.intervals), origin)) }
    private fun kind(date: String, kind: DayKind) = SyncCodecs.encodeDay(DayRecord(PlannedDay(LocalDate.parse(date), kind, emptyList())))
    private fun extraDay(date: String) = SyncCodecs.encodeDay(DayRecord(PlannedDay(LocalDate.parse(date), DayKind.WORK, listOf(
        ProvisionalInterval(LocalTime.of(8, 0), LocalTime.of(14, 0), 0, 0),
        ProvisionalInterval(LocalTime.of(14, 0), LocalTime.of(16, 30), 0, 0, isExtra = true)))))
    private fun rate(type: String, from: String, until: String?, amount: String) =
        SyncCodecs.encodeRate(RateRecord(type, LocalDate.parse(from), until?.let(LocalDate::parse), BigDecimal(amount)))
    private fun rateKey(type: String, from: String) = SyncCodecs.rateKey(type, LocalDate.parse(from))

    private fun assertConverged(vararg devices: Device) {
        devices.forEach { assertTrue("${it.replica.deviceId}: cola pendiente", it.replica.outbox.isEmpty()); assertTrue("${it.replica.deviceId}: conflictos", it.replica.conflicts.isEmpty()) }
        devices.map { d -> d.replica.records.filterValues { it is LocalRecord.Shared } }.zipWithNext().forEach { (a, b) -> assertEquals(a, b) }
    }
    /** A tombstone only ever represents an actual deletion: none must appear in these scenarios. */
    private fun assertNoTombstones(vararg accounts: String) {
        assertTrue(server.received.none { it.op == ChangeOp.DELETE })
        accounts.forEach { a -> assertTrue(server.records(a).values.none { it.deleted }) }
    }

    // Basic synchronization

    @Test fun t01_emptyAccount() {
        val a = Device("ana", "A")
        val result = a.sync()
        assertEquals(SyncStatus.COMPLETED, result.status)
        assertEquals(Replica("ana", "A"), a.replica)
        assertTrue(server.records("ana").isEmpty())
    }

    @Test fun t02_firstUpload() {
        val a = Device("ana", "A")
        assertEquals(EditKind.CREATED, a.put(key("2026-03-02"), day("2026-03-02", "08:00-16:00")))
        a.put(key("2026-03-03"), day("2026-03-03", "LIBRE"))
        assertEquals(2, a.replica.outbox.size)
        assertEquals(SyncStatus.COMPLETED, a.sync().status)
        assertTrue(a.replica.outbox.isEmpty())
        assertEquals(listOf(1L, 1L), server.records("ana").values.map { it.version })
        assertEquals(2L, a.replica.cursor)
        assertEquals(setOf(key("2026-03-02"), key("2026-03-03")), a.replica.known.keys)
    }

    @Test fun t03_firstDownload() {
        val a = Device("ana", "A"); val b = Device("ana", "B")
        a.put(key("2026-03-02"), day("2026-03-02", "08:00-16:00")); a.sync()
        b.sync()
        assertConverged(a, b)
        assertEquals(LocalTime.of(16, 0), b.day("2026-03-02")!!.intervals.single().end)
    }

    @Test fun t04_incrementalSynchronization() {
        val a = Device("ana", "A"); val b = Device("ana", "B")
        a.put(key("2026-03-02"), day("2026-03-02", "08:00-16:00")); a.sync(); b.sync()
        val before = b.replica.cursor
        a.put(key("2026-03-04"), day("2026-03-04", "09:00-13:00")); a.sync()
        assertEquals(1, server.pull(b.token, before, 500).changes.size) // Only what changed after the cursor.
        b.sync()
        assertConverged(a, b)
        assertTrue(b.replica.cursor > before)
    }

    @Test fun t05_repeatedSynchronizationWithoutChanges() {
        val a = Device("ana", "A")
        a.put(key("2026-03-02"), day("2026-03-02", "08:00-16:00")); a.sync()
        val replica = a.replica; val records = server.records("ana"); val requests = server.received.size
        repeat(3) { assertEquals(SyncStatus.COMPLETED, a.sync().status) }
        assertEquals(replica, a.replica)
        assertEquals(records, server.records("ana"))
        assertEquals(requests, server.received.size) // Nothing is pushed when nothing changed.
    }

    @Test fun t06_identicalRecordsFromTwoDevicesResolveAutomatically() {
        val a = Device("ana", "A"); val b = Device("ana", "B")
        a.put(key("2026-03-02"), day("2026-03-02", "08:00-12:00 / 16:00-20:00", "OCR_REVIEWED"))
        b.put(key("2026-03-02"), day("2026-03-02", "08:00-12:00 / 16:00-20:00", "OCR_REVIEWED"))
        a.sync(); b.sync()
        assertConverged(a, b)
        assertEquals(1L, server.records("ana").getValue(key("2026-03-02")).version)
        assertTrue(server.history("ana").isEmpty())
    }

    // Multi-device behaviour

    @Test fun t07_devicesEditDifferentDays() {
        val a = Device("ana", "A"); val b = Device("ana", "B")
        a.put(key("2026-03-02"), day("2026-03-02", "08:00-16:00"))
        b.put(key("2026-03-05"), day("2026-03-05", "10:00-18:00"))
        a.sync(); b.sync(); a.sync()
        assertConverged(a, b)
        assertEquals(2, a.replica.records.size)
    }

    @Test fun t08_bothDevicesEditTheSameDayOffline() {
        val a = Device("ana", "A"); val b = Device("ana", "B")
        a.put(key("2026-03-02"), day("2026-03-02", "08:00-16:00")); a.sync(); b.sync()
        a.put(key("2026-03-02"), day("2026-03-02", "08:00-17:00"))
        b.put(key("2026-03-02"), day("2026-03-02", "07:00-15:00"))
        a.sync()
        val result = b.sync()
        assertTrue(result.requiresUserDecision)
        val conflict = b.replica.conflicts.getValue(key("2026-03-02"))
        assertEquals(ConflictKind.VERSION, conflict.kind)
        assertEquals(LocalTime.of(7, 0), b.day("2026-03-02")!!.intervals.single().start) // Local content kept.
        assertEquals(day("2026-03-02", "08:00-17:00"), LocalRecord.Shared(server.records("ana").getValue(key("2026-03-02")).payload!!)) // Not overwritten.
    }

    @Test fun t09_deleteWhileAnotherDeviceEditsAndDeletedDaysNeverReappear() {
        val a = Device("ana", "A"); val b = Device("ana", "B"); val c = Device("ana", "C")
        a.put(key("2026-03-02"), day("2026-03-02", "08:00-16:00")); a.sync(); b.sync(); c.sync()
        assertEquals(EditKind.DELETED, a.put(key("2026-03-02"), null))
        b.put(key("2026-03-02"), day("2026-03-02", "09:00-17:00"))
        a.sync()
        assertTrue(server.records("ana").getValue(key("2026-03-02")).deleted)
        assertNull(server.records("ana").getValue(key("2026-03-02")).payload) // Tombstone without content.
        b.sync()
        assertTrue(b.replica.conflicts.getValue(key("2026-03-02")).remote!!.deleted)
        assertEquals(ResolutionOutcome.KEPT_REMOTE, b.resolve(key("2026-03-02"), Resolution.KEEP_REMOTE).outcome)
        // A stale device that never saw the deletion and has no edit does not bring the day back.
        c.sync(); a.sync(); b.sync()
        assertConverged(a, b, c)
        assertNull(c.day("2026-03-02"))
        // A repeated deletion of an already deleted record is a no-op.
        assertEquals(EditKind.NO_OP, c.put(key("2026-03-02"), null))
    }

    @Test fun t10_deviceReconnectsAfterALongAbsence() {
        val a = Device("ana", "A"); val c = Device("ana", "C")
        a.put(key("2026-01-01"), day("2026-01-01", "08:00-16:00")); a.sync(); c.sync()
        c.put(key("2026-04-01"), day("2026-04-01", "10:00-14:00")) // Offline edit on C.
        var date = LocalDate.parse("2026-01-02")
        repeat(42) { // Six weeks of edits on A, synchronized every day.
            a.put(SyncCodecs.dayKey(date), day(date.toString(), if (it % 7 == 6) "LIBRE" else "08:00-16:00")); a.sync()
            date = date.plusDays(1)
        }
        a.put(key("2026-01-01"), null); a.sync()
        assertEquals(SyncStatus.COMPLETED, c.sync(page = 5).status)
        a.sync()
        assertConverged(a, c)
        assertEquals(43, a.replica.records.size)
        assertNull(c.day("2026-01-01"))
    }

    @Test fun t11_devicesSynchronizingInDifferentOrdersConverge() {
        fun scenario(order: String): Map<RecordKey, SyncPayload?> {
            val s = FakeSyncServer { today }
            val devices = listOf("A", "B", "C").associateWith { id -> Replica("ana", id) to s.signIn("ana") }.toMutableMap()
            fun edit(id: String, date: String, text: String) { devices[id] = devices.getValue(id).let { (r, t) -> SyncEngine.edit(r, key(date), day(date, text)).replica to t } }
            edit("A", "2026-03-02", "08:00-16:00"); edit("B", "2026-03-03", "22:00-06:00"); edit("C", "2026-03-05", "08:00-12:00 / 16:00-20:00")
            (order + order).forEach { id -> devices[id.toString()] = devices.getValue(id.toString()).let { (r, t) -> SyncEngine.sync(r, s.transport(t)).replica to t } }
            val finals = devices.values.map { it.first.records }
            finals.zipWithNext().forEach { (x, y) -> assertEquals(x, y) }
            return s.records("ana").mapValues { it.value.payload }
        }
        assertEquals(scenario("ABC"), scenario("CBA"))
        assertEquals(scenario("ABC"), scenario("BCA"))
    }

    // Reliability

    @Test fun t12_duplicateOperationDeliveryIsAppliedOnce() {
        val request = PushRequest("X-1", key("2026-03-02"), ChangeOp.UPSERT, (day("2026-03-02", "08:00-16:00") as LocalRecord.Shared).payload, null)
        val token = server.signIn("ana")
        val first = server.push(token, "X", listOf(request))
        val second = server.push(token, "X", listOf(request))
        assertEquals(first, second)
        assertEquals(1L, server.records("ana").getValue(key("2026-03-02")).version)
        assertEquals(1L, server.records("ana").getValue(key("2026-03-02")).seq)
    }

    @Test fun t13_interruptedUploadBeforeAndAfterTheServerApplied() {
        val a = Device("ana", "A")
        a.put(key("2026-03-02"), day("2026-03-02", "08:00-16:00"))
        server.failNext(FakeSyncServer.Failure.BEFORE_APPLY)
        assertEquals(SyncStatus.INTERRUPTED, a.sync().status)
        assertTrue(server.records("ana").isEmpty())
        val sent = a.replica.outbox.single()
        assertEquals(ChangeState.IN_FLIGHT, sent.state)
        a.put(key("2026-03-03"), day("2026-03-03", "LIBRE"))
        server.failNext(FakeSyncServer.Failure.AFTER_APPLY) // Applied, but the response is lost.
        assertEquals(SyncStatus.INTERRUPTED, a.sync().status)
        assertEquals(2, server.records("ana").size)
        assertEquals(sent.operationId, a.replica.outbox.first().operationId) // Same id is resent.
        assertEquals(SyncStatus.COMPLETED, a.sync().status)
        assertTrue(a.replica.outbox.isEmpty() && a.replica.conflicts.isEmpty())
        assertEquals(listOf(1L, 1L), server.records("ana").values.map { it.version })
    }

    @Test fun t14_interruptedDownloadResumesFromTheCommittedCursor() {
        val a = Device("ana", "A"); val b = Device("ana", "B")
        (2..6).forEach { a.put(key("2026-03-0$it"), day("2026-03-0$it", "08:00-16:00")) }
        a.sync()
        var pulls = 0
        val real = server.transport(b.token)
        val flaky = object : SyncTransport by real {
            override fun pull(since: Long, limit: Int): PullPage = if (++pulls == 2) throw SyncTransportException("corte") else real.pull(since, limit)
        }
        assertEquals(SyncStatus.INTERRUPTED, b.sync(page = 2, transport = flaky).status)
        assertEquals(2, b.replica.records.size) // First page committed with its cursor.
        assertEquals(2L, b.replica.cursor)
        assertEquals(SyncStatus.COMPLETED, b.sync(page = 2).status)
        assertConverged(a, b)
        assertEquals(5, b.replica.records.size)
    }

    @Test fun t15_retryAfterRepeatedFailures() {
        val a = Device("ana", "A")
        a.put(key("2026-03-02"), day("2026-03-02", "08:00-16:00"))
        server.failNext(FakeSyncServer.Failure.BEFORE_APPLY, FakeSyncServer.Failure.BEFORE_APPLY)
        repeat(2) { assertEquals(SyncStatus.INTERRUPTED, a.sync().status) }
        assertEquals(2, a.replica.outbox.single().attempts)
        server.failNext(FakeSyncServer.Failure.AFTER_APPLY)
        assertEquals(SyncStatus.INTERRUPTED, a.sync().status)
        assertEquals(SyncStatus.COMPLETED, a.sync().status)
        assertEquals(1L, server.records("ana").getValue(key("2026-03-02")).version)
    }

    @Test fun t16_outOfOrderOperationsNeverOverwriteOrSelfConflict() {
        val a = Device("ana", "A"); val b = Device("ana", "B")
        a.put(key("2026-03-02"), day("2026-03-02", "08:00-16:00"))
        server.failNext(FakeSyncServer.Failure.AFTER_APPLY)
        a.sync() // Version 1 applied; the response is lost, the change stays in flight.
        a.put(key("2026-03-02"), day("2026-03-02", "08:00-18:00")) // Follow-up queued behind the in-flight change.
        val followUp = a.replica.outbox.last()
        assertTrue(followUp.afterPredecessor && followUp.baseVersion == null)
        assertEquals(SyncStatus.COMPLETED, a.sync().status)
        assertTrue(a.replica.conflicts.isEmpty())
        assertEquals(2L, server.records("ana").getValue(key("2026-03-02")).version)
        // A stale operation based on version 1 that arrives late is refused, not applied.
        b.sync()
        val stale = PushRequest("late-1", key("2026-03-02"), ChangeOp.UPSERT, (day("2026-03-02", "06:00-10:00") as LocalRecord.Shared).payload, 1L)
        assertTrue(server.push(b.token, "B", listOf(stale)).single() is PushResult.Conflict)
        assertEquals(day("2026-03-02", "08:00-18:00"), LocalRecord.Shared(server.records("ana").getValue(key("2026-03-02")).payload!!))
    }

    @Test fun t17_cursorRecoveryReplaysWithoutChanges() {
        val a = Device("ana", "A"); val b = Device("ana", "B")
        (2..4).forEach { a.put(key("2026-03-0$it"), day("2026-03-0$it", "08:00-16:00")) }
        a.put(key("2026-03-02"), day("2026-03-02", "09:00-16:00")); a.sync(); b.sync()
        val synced = b.replica
        b.replica = b.replica.copy(cursor = 0) // Lost cursor: everything is delivered again.
        assertEquals(SyncStatus.COMPLETED, b.sync(page = 1).status)
        assertEquals(synced, b.replica)
    }

    @Test fun t18_t19_noMissedUpdatesAndNoDuplicates() {
        val a = Device("ana", "A"); val b = Device("ana", "B")
        var date = LocalDate.parse("2026-05-01")
        repeat(20) { i ->
            val device = if (i % 2 == 0) a else b
            device.put(SyncCodecs.dayKey(date), day(date.toString(), "08:00-16:00"))
            if (i % 3 == 0) device.put(SyncCodecs.dayKey(date.minusDays(1)), null)
            device.sync()
            date = date.plusDays(1)
        }
        a.sync(); b.sync(); a.sync()
        assertConverged(a, b)
        val live = server.records("ana").filterValues { !it.deleted }
        assertEquals(live.keys, a.replica.records.keys)
        assertEquals(live.size, live.keys.distinct().size)
        val seqs = server.records("ana").values.map { it.seq }
        assertEquals(seqs.size, seqs.distinct().size)
    }

    @Test fun authenticationExpiryDuringSyncKeepsTheQueue() {
        val a = Device("ana", "A")
        a.put(key("2026-03-02"), day("2026-03-02", "08:00-16:00"))
        val transport = server.transport(a.token)
        val expiring = object : SyncTransport by transport {
            override val accountId = "ana"
            override fun push(deviceId: String, changes: List<PushRequest>): List<PushResult> { server.revoke(a.token); return transport.push(deviceId, changes) }
        }
        val result = a.sync(transport = expiring)
        assertEquals(SyncStatus.AUTH_REQUIRED, result.status)
        assertEquals(1, a.replica.outbox.size)
        a.token = server.signIn("ana")
        assertEquals(SyncStatus.COMPLETED, a.sync().status)
        assertEquals(1, server.records("ana").size)
    }

    @Test fun alreadyRevokedSessionReportsAuthRequiredAndKeepsTheQueue() {
        val a = Device("ana", "A")
        a.put(key("2026-03-02"), day("2026-03-02", "08:00-16:00"))
        val queued = a.replica.outbox
        server.revoke(a.token)
        assertEquals(SyncStatus.AUTH_REQUIRED, a.sync().status)
        assertEquals(queued, a.replica.outbox)
        assertTrue(server.received.isEmpty())
    }

    @Test fun serverThatNeverAdvancesTheCursorInterruptsInsteadOfLooping() {
        val a = Device("ana", "A")
        val real = server.transport(a.token)
        val stuck = object : SyncTransport by real { override fun pull(since: Long, limit: Int) = PullPage(emptyList(), since, hasMore = true) }
        assertEquals(SyncStatus.INTERRUPTED, a.sync(transport = stuck).status)
    }

    @Test fun keepRemoteOnARejectedChangeTakesTheNewestPulledVersion() {
        val a = Device("ana", "A"); val b = Device("ana", "B")
        a.put(key("2026-03-02"), day("2026-03-02", "08:00-16:00")); a.sync(); b.sync()
        b.put(key("2026-03-02"), day("2026-03-02", "09:00-17:00"))
        val real = server.transport(b.token)
        val refusing = object : SyncTransport by real {
            override fun push(deviceId: String, changes: List<PushRequest>) = changes.map { PushResult.Rejected(it.operationId, "Regla nueva del servidor.") }
        }
        b.sync(transport = refusing)
        assertEquals(ConflictKind.REJECTED, b.replica.conflicts.getValue(key("2026-03-02")).kind)
        a.put(key("2026-03-02"), day("2026-03-02", "10:00-18:00")); a.sync() // Version 2 while B's conflict is open.
        b.sync()
        assertEquals(ResolutionOutcome.KEPT_REMOTE, b.resolve(key("2026-03-02"), Resolution.KEEP_REMOTE).outcome)
        assertEquals(LocalTime.of(10, 0), b.day("2026-03-02")!!.intervals.single().start) // Not the older confirmed version.
        assertEquals(2L, b.replica.known.getValue(key("2026-03-02")).version)
        b.sync()
        assertConverged(a, b)
    }

    // Data integrity

    @Test fun t20_t21_t22_overnightSplitAndOvertimeArePreserved() {
        val a = Device("ana", "A"); val b = Device("ana", "B")
        a.put(key("2026-03-02"), day("2026-03-02", "22:00-06:00"))
        a.put(key("2026-03-04"), day("2026-03-04", "08:00-12:00 / 16:00-20:00"))
        a.put(key("2026-03-06"), extraDay("2026-03-06"))
        a.sync(); b.sync()
        assertConverged(a, b)
        assertEquals(1, b.day("2026-03-02")!!.intervals.single().endDayOffset)
        assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(16, 0)), b.day("2026-03-04")!!.intervals.map { it.start })
        assertEquals(150, b.day("2026-03-06")!!.extraMinutes())
        val days = listOf("2026-03-02", "2026-03-04", "2026-03-06").map { b.day(it)!! }
        assertEquals(ScheduleSummary.month(listOf("2026-03-02", "2026-03-04", "2026-03-06").map { a.day(it)!! }, YearMonth.of(2026, 3)), ScheduleSummary.month(days, YearMonth.of(2026, 3)))
    }

    @Test fun t23_t24_crossDayConflictIsDetectedAndValidatedAfterMerging() {
        val a = Device("ana", "A"); val b = Device("ana", "B")
        a.put(key("2026-03-02"), day("2026-03-02", "22:00-06:00")) // Continues into 03-03.
        b.put(key("2026-03-03"), day("2026-03-03", "05:00-13:00")) // Valid on B alone.
        a.sync(); b.sync(); a.sync()
        assertEquals(ConflictKind.INVARIANT, b.replica.conflicts.getValue(key("2026-03-02")).kind)
        assertEquals(ConflictKind.INVARIANT, a.replica.conflicts.getValue(key("2026-03-03")).kind)
        assertNull(SyncRules.validate(a.replica.records)); assertNull(SyncRules.validate(b.replica.records)) // Never applied.
        // Taking the remote overnight shift is refused while the neighbour still overlaps.
        assertEquals(ResolutionOutcome.REJECTED, b.resolve(key("2026-03-02"), Resolution.KEEP_REMOTE).outcome)
        assertEquals(EditKind.UPDATED, b.put(key("2026-03-03"), day("2026-03-03", "07:00-13:00")))
        assertEquals(ResolutionOutcome.KEPT_REMOTE, b.resolve(key("2026-03-02"), Resolution.KEEP_REMOTE).outcome)
        b.sync(); a.sync()
        assertEquals(ResolutionOutcome.KEPT_REMOTE, a.resolve(key("2026-03-03"), Resolution.KEEP_REMOTE).outcome)
        a.sync(); b.sync()
        assertConverged(a, b)
        assertEquals(LocalTime.of(7, 0), a.day("2026-03-03")!!.intervals.single().start)
    }

    @Test fun incomingIncompleteDuplicateOrInvalidOvertimeRecordsAreQuarantined() {
        val a = Device("ana", "A")
        val open = server.injectRaw("ana", key("2026-03-02"), SyncPayload(SyncEntity.SCHEDULED_DAY, "1|2026-03-02|WORK|MANUAL|08:00+0-"), 1)
        val duplicated = server.injectRaw("ana", key("2026-03-03"), SyncPayload(SyncEntity.SCHEDULED_DAY, "1|2026-03-03|WORK|MANUAL|08:00+0-12:00+0:N,08:00+0-12:00+0:N"), 1)
        val freeWithExtra = server.injectRaw("ana", key("2026-03-04"), SyncPayload(SyncEntity.SCHEDULED_DAY, "1|2026-03-04|FREE|MANUAL|18:00+0-20:00+0:E"), 1)
        val newer = server.injectRaw("ana", key("2026-03-05"), SyncPayload(SyncEntity.SCHEDULED_DAY, "2|2026-03-05|ON_CALL|MANUAL|"), 2)
        a.sync()
        assertTrue(a.replica.records.isEmpty())
        assertEquals(mapOf(open.key to open, duplicated.key to duplicated, freeWithExtra.key to freeWithExtra, newer.key to newer), a.replica.quarantine)
        // A later readable version supersedes the quarantined one.
        server.injectRaw("ana", key("2026-03-02"), (day("2026-03-02", "08:00-12:00") as LocalRecord.Shared).payload, 1)
        a.sync()
        assertFalse(key("2026-03-02") in a.replica.quarantine)
        assertNotNull(a.day("2026-03-02"))
        // The same rules refuse such days locally.
        assertThrows(IllegalArgumentException::class.java) {
            SyncCodecs.encodeDay(DayRecord(PlannedDay(LocalDate.parse("2026-03-04"), DayKind.FREE, listOf(ProvisionalInterval(LocalTime.of(18, 0), LocalTime.of(20, 0), 0, 0, true)))))
        }
    }

    // Sick leave (revised D9): a synchronized day state that carries only the date and the state.

    private val sickPayloadText = "1|2026-12-10|SICK_LEAVE|MANUAL|"
    private fun sick(date: String) = kind(date, DayKind.SICK_LEAVE)

    @Test fun t25_singleSickLeaveDayPropagatesWithOnlyDateAndState() {
        val a = Device("ana", "A"); val b = Device("ana", "B")
        assertEquals(EditKind.CREATED, a.put(key("2026-12-10"), sick("2026-12-10")))
        assertEquals(ChangeOp.UPSERT, a.replica.outbox.single().op)
        a.sync(); b.sync()
        assertConverged(a, b)
        assertEquals(DayKind.SICK_LEAVE, b.day("2026-12-10")!!.kind)
        assertEquals(sickPayloadText, server.received.single().payload!!.text) // Nothing beyond date and state.
        assertNoTombstones("ana")
    }

    @Test fun t25_workDayChangedToSickLeaveIsAnUpdateNotADeletion() {
        val a = Device("ana", "A"); val b = Device("ana", "B")
        a.put(key("2026-12-10"), day("2026-12-10", "17:00-23:00")); a.sync(); b.sync()
        assertEquals(EditKind.UPDATED, a.put(key("2026-12-10"), sick("2026-12-10")))
        val change = a.replica.outbox.single()
        assertEquals(ChangeOp.UPSERT, change.op)
        assertEquals(1L, change.baseVersion)
        a.sync(); b.sync()
        assertConverged(a, b)
        assertEquals(PlannedDay(LocalDate.parse("2026-12-10"), DayKind.SICK_LEAVE, emptyList()), b.day("2026-12-10")) // Intervals dropped by the day rules.
        assertEquals(2L, server.records("ana").getValue(key("2026-12-10")).version)
        assertNoTombstones("ana")
    }

    @Test fun t25_vacationChangedToSickLeavePropagates() {
        val a = Device("ana", "A"); val b = Device("ana", "B")
        (14..16).forEach { a.put(key("2026-08-$it"), kind("2026-08-$it", DayKind.VACATION)) }
        a.sync(); b.sync()
        a.put(key("2026-08-15"), sick("2026-08-15")); a.sync(); b.sync()
        assertConverged(a, b)
        assertEquals(listOf(DayKind.VACATION, DayKind.SICK_LEAVE, DayKind.VACATION), (14..16).map { b.day("2026-08-$it")!!.kind })
        val summary = ScheduleSummary.month((14..16).map { b.day("2026-08-$it")!! }, YearMonth.of(2026, 8))
        assertEquals(2 to 1, summary.vacationDays to summary.sickDays)
        assertNoTombstones("ana")
    }

    private fun conflictOn(date: String, onA: LocalRecord?, onB: LocalRecord?, initial: LocalRecord? = day(date, "08:00-16:00")): Pair<Device, Device> {
        // Device ids must be unique per device: operation ids derive from them and the server deduplicates by id.
        val a = Device("ana", "A-$date"); val b = Device("ana", "B-$date")
        if (initial != null) { a.put(key(date), initial); a.sync(); b.sync() }
        a.put(key(date), onA); b.put(key(date), onB)
        a.sync(); b.sync()
        return a to b
    }

    @Test fun t25_workFreeAndVacationAgainstSickLeaveRequireADecision() {
        listOf("2026-12-10" to day("2026-12-10", "17:00-23:00"), "2026-12-11" to kind("2026-12-11", DayKind.FREE),
            "2026-12-12" to kind("2026-12-12", DayKind.VACATION)).forEach { (date, other) ->
            val (a, b) = conflictOn(date, other, sick(date), initial = day(date, "09:00-13:00"))
            val conflict = b.replica.conflicts.getValue(key(date))
            assertEquals(setOf(key(date)), b.replica.conflicts.keys)
            assertEquals(ConflictKind.VERSION, conflict.kind)
            assertEquals(DayKind.SICK_LEAVE, b.day(date)!!.kind) // Local choice kept until the user decides.
            assertEquals(other, LocalRecord.Shared(conflict.remote!!.payload!!)) // The other side is kept for the decision, not overwritten.
            assertTrue(a.replica.conflicts.isEmpty())
        }
    }

    @Test fun t25_identicalSickLeaveOnTwoDevicesResolvesAutomatically() {
        val (a, b) = conflictOn("2026-12-10", sick("2026-12-10"), sick("2026-12-10"))
        assertConverged(a, b)
        assertEquals(2L, server.records("ana").getValue(key("2026-12-10")).version)
    }

    @Test fun t25_sickLeaveAgainstAnActualDeletionIsAConflictNotAMerge() {
        val (a, b) = conflictOn("2026-12-10", null, sick("2026-12-10"))
        assertTrue(server.records("ana").getValue(key("2026-12-10")).deleted) // The deletion was a real one.
        assertTrue(b.replica.conflicts.getValue(key("2026-12-10")).remote!!.deleted)
        assertEquals(ResolutionOutcome.KEPT_LOCAL, b.resolve(key("2026-12-10"), Resolution.KEEP_LOCAL).outcome)
        b.sync(); a.sync()
        assertConverged(a, b)
        assertEquals(DayKind.SICK_LEAVE, a.day("2026-12-10")!!.kind)
        assertFalse(server.records("ana").getValue(key("2026-12-10")).deleted)
    }

    @Test fun t25_sickLeaveEditedIndependentlyOnTwoDevicesAndCancelledResolution() {
        val (a, b) = conflictOn("2026-12-10", day("2026-12-10", "17:00-23:00"), kind("2026-12-10", DayKind.FREE), initial = sick("2026-12-10"))
        val before = b.replica
        assertEquals(ResolutionOutcome.CANCELLED, b.resolve(key("2026-12-10"), Resolution.CANCEL).outcome)
        assertEquals(before, b.replica)
        assertEquals(EditKind.BLOCKED_BY_CONFLICT, b.put(key("2026-12-10"), sick("2026-12-10")))
        assertEquals(ResolutionOutcome.KEPT_REMOTE, b.resolve(key("2026-12-10"), Resolution.KEEP_REMOTE).outcome)
        assertEquals(kind("2026-12-10", DayKind.FREE), b.replica.recovered.single().content)
        b.sync()
        assertConverged(a, b)
    }

    @Test fun t25_multiDaySickLeaveRangeWhileAnotherDeviceIsOffline() {
        val a = Device("ana", "A"); val b = Device("ana", "B")
        (10..14).forEach { a.put(key("2026-12-$it"), day("2026-12-$it", "17:00-23:00")) }
        a.sync(); b.sync()
        (10..14).forEach { a.put(key("2026-12-$it"), sick("2026-12-$it")) } // Range marked on A.
        b.put(key("2026-12-12"), day("2026-12-12", "18:00-23:00")) // B offline edits one day of the range.
        a.sync(); b.sync()
        assertEquals(setOf(key("2026-12-12")), b.replica.conflicts.keys) // Only the day edited on both sides.
        assertEquals(listOf(10, 11, 13, 14).map { DayKind.SICK_LEAVE }, listOf(10, 11, 13, 14).map { b.day("2026-12-$it")!!.kind })
        b.resolve(key("2026-12-12"), Resolution.KEEP_REMOTE); b.sync()
        assertConverged(a, b)
        assertEquals(5, ScheduleSummary.month((10..14).map { b.day("2026-12-$it")!! }, YearMonth.of(2026, 12)).sickDays)
        assertEquals(5, server.records("ana").size) // No duplicate records.
        assertNoTombstones("ana")
    }

    @Test fun t25_interruptedAndDuplicatedSickLeaveOperationsApplyOnce() {
        val a = Device("ana", "A"); val b = Device("ana", "B")
        a.put(key("2026-12-10"), day("2026-12-10", "17:00-23:00")); a.sync(); b.sync()
        a.put(key("2026-12-10"), sick("2026-12-10"))
        server.failNext(FakeSyncServer.Failure.AFTER_APPLY)
        assertEquals(SyncStatus.INTERRUPTED, a.sync().status)
        val sent = a.replica.outbox.single()
        assertEquals(SyncStatus.COMPLETED, a.sync().status) // Same operation id resent.
        val request = PushRequest(sent.operationId, sent.key, sent.op, sent.payload, sent.baseVersion)
        assertTrue(server.push(a.token, "A", listOf(request)).single() is PushResult.Applied) // Duplicate delivery: stored result.
        assertEquals(2L, server.records("ana").getValue(key("2026-12-10")).version)
        b.sync()
        assertConverged(a, b)
        assertEquals(DayKind.SICK_LEAVE, b.day("2026-12-10")!!.kind)
    }

    @Test fun t25_staleDeviceReconnectingSeesTheSickLeave() {
        val a = Device("ana", "A"); val c = Device("ana", "C")
        a.put(key("2026-12-10"), day("2026-12-10", "17:00-23:00")); a.sync(); c.sync()
        a.put(key("2026-12-10"), sick("2026-12-10")); a.sync()
        (11..20).forEach { a.put(key("2026-12-$it"), day("2026-12-$it", "17:00-23:00")); a.sync() }
        c.sync(page = 3)
        assertConverged(a, c)
        assertEquals(DayKind.SICK_LEAVE, c.day("2026-12-10")!!.kind)
    }

    @Test fun t25_medicalOrUnsupportedFieldsCannotEnterAPayload() {
        val a = Device("ana", "A")
        val forged = listOf(
            "1|2026-12-10|SICK_LEAVE|MANUAL||gripe",             // Extra field.
            "1|2026-12-10|SICK_LEAVE|MANUAL|diagnostico=gripe",   // Free text in the intervals field.
            "1|2026-12-10|SICK_LEAVE|MANUAL|08:00+0-12:00+0:N",   // Hours on a sick-leave day.
            "1|2026-12-10|SICK_LEAVE|OCR_REVIEWED|",              // Extra provenance data.
            "1|2026-12-10|SICK_LEAVE|MANUAL| ",
        )
        forged.forEachIndexed { i, text ->
            val payload = SyncPayload(SyncEntity.SCHEDULED_DAY, text)
            assertNotNull(text, SyncCodecs.validationError(key("2026-12-10"), payload))
            assertTrue(text, server.push(a.token, "A", listOf(PushRequest("A-f$i", key("2026-12-10"), ChangeOp.UPSERT, payload, null))).single() is PushResult.Rejected)
        }
        assertTrue(server.records("ana").isEmpty())
        server.injectRaw("ana", key("2026-12-11"), SyncPayload(SyncEntity.SCHEDULED_DAY, "1|2026-12-11|SICK_LEAVE|MANUAL||gripe"), 1)
        a.sync()
        assertNull(a.day("2026-12-11")) // Quarantined, never applied or reinterpreted.
        assertTrue(key("2026-12-11") in a.replica.quarantine)
        // Structural allowlist: the synchronized day model has no field that could hold medical information.
        assertEquals(setOf("day", "origin"), DayRecord::class.java.declaredFields.map { it.name }.filterNot { it.startsWith("$") }.toSet())
        assertEquals(setOf("date", "kind", "intervals"), PlannedDay::class.java.declaredFields.map { it.name }.filterNot { it.startsWith("$") }.toSet())
        assertThrows(IllegalArgumentException::class.java) { SyncCodecs.encodeDay(DayRecord(PlannedDay(LocalDate.parse("2026-12-10"), DayKind.SICK_LEAVE, emptyList()), "OCR_REVIEWED")) }
    }

    @Test fun t25_sickLeaveStaysInsideItsAccount() {
        val ana = Device("ana", "A"); val bob = Device("bob", "B")
        ana.put(key("2026-12-10"), sick("2026-12-10")); ana.sync()
        bob.sync()
        assertTrue(bob.replica.records.isEmpty())
        assertTrue(server.records("bob").isEmpty())
        assertEquals(SyncStatus.ACCOUNT_MISMATCH, ana.sync(transport = server.transport(bob.token)).status)
    }

    @Test fun t26_vacationIsPreserved() {
        val a = Device("ana", "A"); val b = Device("ana", "B")
        (10..14).forEach { a.put(key("2026-08-$it"), kind("2026-08-$it", DayKind.VACATION)) }
        a.sync(); b.sync()
        assertConverged(a, b)
        assertEquals(5, ScheduleSummary.month((10..14).map { b.day("2026-08-$it")!! }, YearMonth.of(2026, 8)).vacationDays)
    }

    @Test fun t27_payRateAndPayslipConflictsRequireADecision() {
        val a = Device("ana", "A"); val b = Device("ana", "B")
        a.put(rateKey("HOURLY_BASE", "2026-01-01"), rate("HOURLY_BASE", "2026-01-01", null, "12.50"))
        b.put(rateKey("HOURLY_BASE", "2026-01-01"), rate("HOURLY_BASE", "2026-01-01", null, "13"))
        a.sync(); b.sync()
        assertEquals(ConflictKind.VERSION, b.replica.conflicts.getValue(rateKey("HOURLY_BASE", "2026-01-01")).kind)
        // Overlapping validity periods under different keys are a cross-record conflict, not a silent merge.
        a.put(rateKey("EXTRA_HOUR", "2026-01-01"), rate("EXTRA_HOUR", "2026-01-01", null, "15"))
        b.put(rateKey("EXTRA_HOUR", "2026-03-01"), rate("EXTRA_HOUR", "2026-03-01", null, "16"))
        a.sync(); b.sync()
        assertEquals(ConflictKind.INVARIANT, b.replica.conflicts.getValue(rateKey("EXTRA_HOUR", "2026-01-01")).kind)
        val slip = SyncCodecs.payslipKey(YearMonth.of(2026, 2))
        a.put(slip, SyncCodecs.encodePayslip(PayslipRecord(YearMonth.of(2026, 2), BigDecimal("1500.00"), null)))
        b.put(slip, SyncCodecs.encodePayslip(PayslipRecord(YearMonth.of(2026, 2), BigDecimal("1550.00"), null)))
        a.sync(); b.sync()
        assertEquals(ConflictKind.VERSION, b.replica.conflicts.getValue(slip).kind)
        assertEquals(BigDecimal("1500"), SyncCodecs.decodePayslip(slip, server.records("ana").getValue(slip).payload!!).gross)
    }

    @Test fun t28_accountIsolation() {
        val ana = Device("ana", "A"); val bob = Device("bob", "B")
        ana.put(key("2026-03-02"), day("2026-03-02", "08:00-16:00")); ana.sync()
        bob.sync()
        assertTrue(bob.replica.records.isEmpty())
        bob.put(key("2026-03-02"), day("2026-03-02", "10:00-12:00")); bob.sync()
        assertTrue(bob.replica.conflicts.isEmpty()) // Same natural key, separate account space.
        assertEquals(1L, server.records("ana").getValue(key("2026-03-02")).version)
        // A forged owner field is refused, and nothing is written in either account.
        val forged = PushRequest("B-x", key("2026-03-09"), ChangeOp.UPSERT, (day("2026-03-09", "LIBRE") as LocalRecord.Shared).payload, null)
        assertThrows(SyncForbiddenException::class.java) { server.push(bob.token, "B", listOf(forged), claimedOwner = "ana") }
        assertFalse(key("2026-03-09") in server.records("ana") || key("2026-03-09") in server.records("bob"))
        // Ana's database never talks to Bob's session.
        ana.put(key("2026-03-03"), day("2026-03-03", "LIBRE"))
        val requests = server.received.size
        assertEquals(SyncStatus.ACCOUNT_MISMATCH, ana.sync(transport = server.transport(bob.token)).status)
        assertEquals(requests, server.received.size)
        assertEquals(1, ana.replica.outbox.size)
        // An unknown or revoked session reads nothing.
        server.revoke(bob.token)
        assertThrows(SyncUnauthorizedException::class.java) { server.pull(bob.token, 0, 10) }
    }

    // Conflict recovery

    private fun conflicted(): Pair<Device, Device> {
        val a = Device("ana", "A"); val b = Device("ana", "B")
        a.put(key("2026-03-02"), day("2026-03-02", "08:00-16:00")); a.sync(); b.sync()
        a.put(key("2026-03-02"), day("2026-03-02", "08:00-17:00")); a.sync()
        b.put(key("2026-03-02"), day("2026-03-02", "07:00-15:00")); b.sync()
        assertTrue(b.replica.conflicts.containsKey(key("2026-03-02")))
        return a to b
    }

    @Test fun t29_keepLocal() {
        val (a, b) = conflicted()
        assertEquals(ResolutionOutcome.KEPT_LOCAL, b.resolve(key("2026-03-02"), Resolution.KEEP_LOCAL).outcome)
        assertEquals(2L, b.replica.outbox.single().baseVersion)
        b.sync(); a.sync()
        assertConverged(a, b)
        assertEquals(LocalTime.of(7, 0), a.day("2026-03-02")!!.intervals.single().start)
        assertEquals(RecoverySource.REMOTE, b.replica.recovered.single().source)
        assertEquals(day("2026-03-02", "08:00-17:00"), b.replica.recovered.single().content)
    }

    @Test fun t30_keepRemote() {
        val (a, b) = conflicted()
        assertEquals(ResolutionOutcome.KEPT_REMOTE, b.resolve(key("2026-03-02"), Resolution.KEEP_REMOTE).outcome)
        assertTrue(b.replica.outbox.isEmpty())
        b.sync()
        assertConverged(a, b)
        assertEquals(LocalTime.of(17, 0), b.day("2026-03-02")!!.intervals.single().end)
        assertEquals(day("2026-03-02", "07:00-15:00"), b.replica.recovered.single().content)
    }

    @Test fun t31_cancelLeavesEverythingAsItWas() {
        val (_, b) = conflicted()
        val before = b.replica
        assertEquals(ResolutionOutcome.CANCELLED, b.resolve(key("2026-03-02"), Resolution.CANCEL).outcome)
        assertEquals(before, b.replica)
        assertEquals(EditKind.BLOCKED_BY_CONFLICT, b.put(key("2026-03-02"), day("2026-03-02", "06:00-14:00")))
        assertEquals(ResolutionOutcome.NO_CONFLICT, b.resolve(key("2026-03-09"), Resolution.KEEP_LOCAL).outcome)
    }

    @Test fun t32_conflictBecomesIdenticalAndResolvesWithoutWriting() {
        val (a, b) = conflicted()
        a.put(key("2026-03-02"), day("2026-03-02", "07:00-15:00")); a.sync() // The other device converges on B's content.
        b.sync()
        assertEquals(ResolutionOutcome.ALREADY_IDENTICAL, b.resolve(key("2026-03-02"), Resolution.KEEP_LOCAL).outcome)
        assertTrue(b.replica.outbox.isEmpty())
        assertEquals(3L, b.replica.known.getValue(key("2026-03-02")).version)
        assertConverged(a, b)
    }

    @Test fun t33_losingVersionsStayRecoverableFor90Days() {
        val (_, b) = conflicted()
        b.resolve(key("2026-03-02"), Resolution.KEEP_LOCAL); b.sync()
        val policy = RetentionPolicy()
        val local = b.replica.recovered.single()
        assertTrue(policy.isRecoverable(local.discardedOn, today.plusDays(90)))
        assertFalse(policy.isRecoverable(local.discardedOn, today.plusDays(91)))
        // The server keeps every replaced version as well (here versions 1 and 2 of the day).
        assertEquals(listOf(1L, 2L), server.history("ana").filter { it.replaced.key == key("2026-03-02") }.map { it.replaced.version })
        assertTrue(server.history("ana").all { policy.isRecoverable(it.replacedOn, today) })
    }
}
