package com.turnocontrol.domain.sync

import com.turnocontrol.domain.DayKind
import com.turnocontrol.domain.PlannedDay
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class LinkMergePlannerTest {
    private fun day(date: String, kind: DayKind) = SyncCodecs.dayKey(LocalDate.parse(date)) to SyncCodecs.encodeDay(DayRecord(PlannedDay(LocalDate.parse(date), kind, emptyList())))

    @Test fun quarantinedAccountRecordsAreNeverMergedAndPlansFingerprintTheData() {
        val (key, guestValue) = day("2026-12-01", DayKind.FREE)
        val plan = LinkMergePlanner.plan(mapOf(key to guestValue), emptyMap(), emptyMap(), accountQuarantined = setOf(key))
        assertEquals(LinkKind.UNSUPPORTED, plan.entries.single().kind)
        val ready = LinkMergePlanner.resolve(plan, emptyMap(), emptyMap(), false) as LinkResolution.Ready
        assertTrue(ready.changes.isEmpty()) // Left untouched; the guest copy still exists in the guest data.
        val other = LinkMergePlanner.plan(mapOf(day("2026-12-01", DayKind.VACATION)), emptyMap(), emptyMap(), setOf(key))
        assertNotEquals(plan.id, other.id)
        assertEquals(plan.id, LinkMergePlanner.plan(mapOf(key to guestValue), emptyMap(), emptyMap(), setOf(key)).id)
    }

    @Test fun keepingTheAccountSideOrSkippingNeedsNoAcknowledgement() {
        val (key, guestValue) = day("2026-12-01", DayKind.SICK_LEAVE)
        val (_, accountValue) = day("2026-12-01", DayKind.VACATION)
        val plan = LinkMergePlanner.plan(mapOf(key to guestValue), mapOf(key to accountValue), mapOf(key to KnownVersion(1, false, (accountValue as LocalRecord.Shared).payload)))
        assertEquals(LinkKind.CONFLICT, plan.entries.single().kind)
        val kept = LinkMergePlanner.resolve(plan, mapOf(key to accountValue), mapOf(key to Resolution.KEEP_REMOTE), acknowledgeReplacements = false) as LinkResolution.Ready
        assertTrue(kept.changes.isEmpty())
        assertEquals(listOf(key), kept.skippedFromGuest)
    }
}
