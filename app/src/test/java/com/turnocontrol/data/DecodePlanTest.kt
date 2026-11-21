package com.turnocontrol.data

import org.junit.Assert.*
import org.junit.Test

class DecodePlanTest {
    private fun output(w: Int, h: Int) = decodePlan(w, h).let { (w / it.sample * it.scale).toInt() to (h / it.sample * it.scale).toInt() }

    @Test fun portraitAndLandscapeOfTheSameSensorKeepTheSameResolution() {
        // 12 MP camera: previously both were halved to 2000 × 1500, leaving a portrait table 25 % fewer pixels across.
        assertEquals(4000 to 3000, output(4000, 3000))
        assertEquals(3000 to 4000, output(3000, 4000))
        assertEquals(DecodePlan(1, 1f), decodePlan(4000, 3000))
    }

    @Test fun tallScreenshotIsNoLongerHalvedByTheLongEdge() {
        // 1080 × 2340 used to decode at 540 × 1170 because 2340 > 2048. Small sources are now enlarged 2×.
        assertEquals(DecodePlan(1, 2f), decodePlan(1080, 2340))
        assertEquals(DecodePlan(1, 2f), decodePlan(1200, 1600))
    }

    @Test fun largeSourcesStayWithinThePixelBudget() {
        for ((w, h) in listOf(9248 to 6936, 6936 to 9248, 8160 to 6120, 12000 to 9000, 4624 to 3468)) {
            val plan = decodePlan(w, h)
            val (ow, oh) = output(w, h)
            assertTrue("$w×$h -> $ow×$oh", ow.toLong() * oh <= LocalImageOcr.MAX_PIXELS)
            assertTrue("$w×$h keeps at least half the budget", ow.toLong() * oh >= LocalImageOcr.MAX_PIXELS / 2)
            // The decoder never allocates much more than twice the budget before the exact scale.
            assertTrue("$w×$h decode size", (w / plan.sample).toLong() * (h / plan.sample) <= LocalImageOcr.MAX_PIXELS * 2)
            assertTrue(plan.scale <= 1f)
        }
    }

    @Test fun panoramaIsBoundedByTheLongEdge() {
        val (ow, oh) = output(30000, 2000)
        assertTrue(ow <= LocalImageOcr.MAX_EDGE)
        assertTrue(oh > 0)
    }
}
