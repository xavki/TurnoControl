package com.turnocontrol.domain

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class OcrImportReviewTest {
    private val date = LocalDate.parse("2026-03-10")
    private fun day(text: String, on: LocalDate = date) = TimeCellParser.parse(text).let { PlannedDay(on, it.kind, it.intervals) }

    @Test fun classifiesNewUnchangedAndConflictAgainstTheSnapshot() {
        val stored = listOf(day("08:00-16:00"), day("LIBRE", date.plusDays(1)))
        val review = OcrImportReview.classify(listOf(day("08:00-16:00"), day("09:00-13:00", date.plusDays(1)), day("10:00-14:00", date.plusDays(2))), stored)
        assertEquals(listOf(ImportStatus.UNCHANGED, ImportStatus.CONFLICT, ImportStatus.NEW), review.proposals.map { it.status })
        assertEquals(ImportSummary(days = 1, shifts = 1, added = 1, replaced = 0, unchanged = 1, kept = 0, unresolved = 1), review.summary())
        assertTrue(review.blocker()!!.contains("1 día(s)"))
        assertEquals(listOf(date.plusDays(2)), review.writes.map { it.date }) // An unresolved conflict is never written.
    }

    @Test fun reimportingIdenticalDataWritesNothing() {
        val stored = listOf(day("08:00-12:00 / 16:00-20:00"), day("22:00-06:00", date.plusDays(1)))
        val review = OcrImportReview.classify(stored, stored)
        assertTrue(review.writes.isEmpty())
        assertTrue(review.blocker()!!.contains("No hay cambios"))
    }

    @Test fun onlyConflictsCanBeResolvedAndKeepNeverWrites() {
        val review = OcrImportReview.classify(listOf(day("09:00-13:00"), day("10:00-14:00", date.plusDays(1))), listOf(day("LIBRE")))
        val ignored = review.resolve(1, replace = true) // NEW: a decision is not applicable.
        assertNull(ignored.proposals[1].replace)
        val kept = review.resolve(0, replace = false)
        assertEquals(listOf(date.plusDays(1)), kept.writes.map { it.date })
        assertNull(kept.blocker())
        val replaced = review.resolve(0, replace = true)
        assertEquals(ImportSummary(days = 2, shifts = 2, added = 1, replaced = 1, unchanged = 0, kept = 0, unresolved = 0), replaced.summary())
        assertNull(review.proposals[0].replace) // Immutable: the original review is unchanged.
    }

    @Test fun overnightImportOverlappingAStoredNeighbourIsNamed() {
        val review = OcrImportReview.classify(listOf(day("05:00-09:00", date.plusDays(1))), listOf(day("22:00-06:00")))
        val blocker = review.blocker()!!
        assertTrue(blocker.contains("nocturno"))
        assertTrue(blocker.contains(date.plusDays(1).toString()))
    }

    @Test fun splitOvernightAndDeclaredExtraArePreservedUnchanged() {
        val split = day("08:00-12:00 / 16:00-20:00")
        val overnight = day("22:00-06:00", date.plusDays(1))
        val extra = PlannedDay(date.plusDays(3), DayKind.WORK, listOf(ProvisionalInterval(java.time.LocalTime.of(18, 0), java.time.LocalTime.of(20, 0), 0, 0, isExtra = true)))
        val review = OcrImportReview.classify(listOf(split, overnight, extra), emptyList())
        assertNull(review.blocker())
        assertEquals(listOf(split, overnight, extra), review.writes)
        assertEquals(120, review.writes.last().extraMinutes())
    }

    @Test fun replacingAStoredExtraIsExplainedBeforeWriting() {
        val stored = PlannedDay(date, DayKind.WORK, listOf(ProvisionalInterval(java.time.LocalTime.of(8, 0), java.time.LocalTime.of(10, 0), 0, 0, isExtra = true)))
        val review = OcrImportReview.classify(listOf(day("08:00-12:00")), listOf(stored))
        assertTrue(importDifference(review.proposals.single().existing!!, review.proposals.single().planned).contains("extra"))
    }

    @Test fun storedVacationIsAConflictNotSilentlyReplaced() {
        val review = OcrImportReview.classify(listOf(day("08:00-12:00")), listOf(PlannedDay(date, DayKind.VACATION, emptyList())))
        assertEquals(ImportStatus.CONFLICT, review.proposals.single().status)
        assertTrue(importDifference(review.proposals.single().existing!!, review.proposals.single().planned).contains("vacaciones"))
        assertTrue(review.writes.isEmpty())
    }
}
