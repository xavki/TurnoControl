package com.turnocontrol.data

import com.turnocontrol.domain.OcrBox
import com.turnocontrol.domain.OcrDocument
import com.turnocontrol.domain.OcrPoint
import com.turnocontrol.domain.OcrRegion
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serializes an OCR result across a process boundary (isolated-OCR proof of concept, phase 5D). Only recognized
 * text and geometry: no image, no file, no account or calendar data. Unused by the production OCR path.
 */
object OcrDocumentCodec {
    fun encode(document: OcrDocument): String = JSONObject()
        .put("text", document.text).put("width", document.width).put("height", document.height)
        .put("blocks", JSONArray(document.blocks.map(::region))).toString()

    fun decode(text: String): OcrDocument {
        val root = JSONObject(text)
        return OcrDocument(root.getString("text"), root.getInt("width"), root.getInt("height"), regions(root.getJSONArray("blocks")))
    }

    private fun region(r: OcrRegion): JSONObject = JSONObject().put("text", r.text).put("language", r.language)
        .put("box", r.box?.let { JSONArray(listOf(it.left, it.top, it.right, it.bottom)) } ?: JSONObject.NULL)
        .put("corners", JSONArray(r.corners.flatMap { listOf(it.x, it.y) }))
        .put("confidence", r.confidence?.toDouble() ?: JSONObject.NULL).put("angle", r.angleDegrees?.toDouble() ?: JSONObject.NULL)
        .put("children", JSONArray(r.children.map(::region)))

    private fun regions(array: JSONArray): List<OcrRegion> = (0 until array.length()).map { i ->
        val o = array.getJSONObject(i)
        val box = o.optJSONArray("box")?.let { OcrBox(it.getInt(0), it.getInt(1), it.getInt(2), it.getInt(3)) }
        val corners = o.getJSONArray("corners").let { c -> (0 until c.length() step 2).map { OcrPoint(c.getInt(it), c.getInt(it + 1)) } }
        OcrRegion(o.getString("text"), box, corners, o.getString("language"),
            if (o.isNull("confidence")) null else o.getDouble("confidence").toFloat(),
            if (o.isNull("angle")) null else o.getDouble("angle").toFloat(), regions(o.getJSONArray("children")))
    }
}
