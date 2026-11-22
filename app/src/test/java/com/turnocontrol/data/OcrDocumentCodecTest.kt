package com.turnocontrol.data

import com.turnocontrol.domain.OcrBox
import com.turnocontrol.domain.OcrDocument
import com.turnocontrol.domain.OcrPoint
import com.turnocontrol.domain.OcrRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The isolated-OCR result format carries text and geometry exactly, and nothing else. */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class OcrDocumentCodecTest {
    @Test fun roundTripsTheFullRegionTree() {
        val element = OcrRegion("08:00", OcrBox(1, 2, 30, 12), listOf(OcrPoint(1, 2), OcrPoint(30, 2), OcrPoint(30, 12), OcrPoint(1, 12)), "und", 0.93f, -1.5f)
        val line = OcrRegion("LUNES 08:00-16:00", null, emptyList(), "es", null, null, listOf(element))
        val document = OcrDocument("LUNES 08:00-16:00", 640, 480, listOf(OcrRegion("LUNES 08:00-16:00", OcrBox(0, 0, 200, 20), emptyList(), "es", children = listOf(line))))
        val encoded = OcrDocumentCodec.encode(document)
        assertEquals(document, OcrDocumentCodec.decode(encoded))
        assertFalse(encoded.contains("bitmap") || encoded.contains("uri"))
    }
}
