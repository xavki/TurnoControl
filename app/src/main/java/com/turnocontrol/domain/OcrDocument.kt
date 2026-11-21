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

/**
 * Clockwise quarter turn (0, 90, 180 or 270) that makes most recognized text horizontal, from ML Kit's line angles
 * (positive is clockwise). Lines are weighted by their characters. Returns 0 when the text is scarce or the
 * angles disagree: an uncertain orientation is left to the user's manual rotation.
 */
fun OcrDocument.uprightCorrection(minCharacters: Int = 20, agreement: Double = .6): Int {
    val weights = IntArray(4)
    blocks.flatMap { it.children }.forEach { line ->
        val angle = line.angleDegrees ?: return@forEach
        val quarter = Math.floorMod(Math.round(angle / 90f), 4)
        weights[quarter] += line.text.count { !it.isWhitespace() }
    }
    val total = weights.sum()
    if (total < minCharacters) return 0
    val dominant = weights.indices.maxBy { weights[it] }
    if (dominant == 0 || weights[dominant] < total * agreement) return 0
    return (360 - dominant * 90) % 360
}
