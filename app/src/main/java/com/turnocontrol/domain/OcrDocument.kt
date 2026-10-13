package com.turnocontrol.domain

data class OcrPoint(val x: Int, val y: Int)
data class OcrBox(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Coordinates are pixels in the oriented, sampled preview, with origin at its top left.
 * Confidence is supplied by ML Kit, never estimated; null means unavailable.
 * Children are lines within blocks and elements within lines, in SDK reading order.
 */
data class OcrRegion(
    val text: String,
    val box: OcrBox?,
    val corners: List<OcrPoint>,
    val language: String,
    val confidence: Float? = null,
    val angleDegrees: Float? = null,
    val children: List<OcrRegion> = emptyList(),
)

data class OcrDocument(val text: String, val width: Int, val height: Int, val blocks: List<OcrRegion>)
