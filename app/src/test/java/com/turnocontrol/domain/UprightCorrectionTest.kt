package com.turnocontrol.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class UprightCorrectionTest {
    private fun document(vararg lines: Pair<String, Float?>) = OcrDocument("", 100, 100, listOf(
        OcrRegion("block", null, emptyList(), "es", children = lines.map { (text, angle) -> OcrRegion(text, null, emptyList(), "es", .9f, angle) })))

    @Test fun quarterTurnsAreUndone() {
        val text = "10/03/2032 08:15-12:00 LIBRE"
        assertEquals(0, document(text to 0f, text to 2.5f).uprightCorrection())
        assertEquals(270, document(text to 90f, text to 88f).uprightCorrection())
        assertEquals(90, document(text to -90f, text to 271f).uprightCorrection())
        assertEquals(180, document(text to 180f, text to -179f).uprightCorrection())
    }

    @Test fun scarceOrConflictingEvidenceLeavesTheImageAsIs() {
        assertEquals(0, document("08:00" to 90f).uprightCorrection())
        assertEquals(0, document("TRABAJADOR LUNES MARTES" to 90f, "ALFA BETA GAMMA DELTA" to 0f).uprightCorrection())
        assertEquals(0, document("TRABAJADOR LUNES MARTES MIERCOLES" to null).uprightCorrection())
    }

    @Test fun majorityWeightedByCharactersWins() {
        assertEquals(270, document("TRABAJADOR 10/03/2032 11/03/2032 12/03/2032" to 90f, "X" to 0f).uprightCorrection())
    }
}
