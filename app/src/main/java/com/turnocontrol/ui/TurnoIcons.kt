package com.turnocontrol.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Original line icons drawn in code; no icon library is bundled. */
object TurnoIcons {
    private fun icon(name: String, build: PathBuilder.() -> Unit) = ImageVector.Builder(name = "Turno.$name", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathFillType = PathFillType.NonZero, pathBuilder = build)
        .build()

    val Work: ImageVector by lazy { icon("Work") {
        moveTo(4f, 8f); horizontalLineTo(20f); verticalLineTo(19f); horizontalLineTo(4f); close()
        moveTo(9f, 8f); verticalLineTo(5f); horizontalLineTo(15f); verticalLineTo(8f)
        moveTo(4f, 13f); horizontalLineTo(20f)
    } }
    val Free: ImageVector by lazy { icon("Free") {
        moveTo(12f, 4f); arcTo(8f, 8f, 0f, true, true, 12f, 20f); arcTo(8f, 8f, 0f, true, true, 12f, 4f); close()
        moveTo(9f, 13f); curveTo(10f, 15f, 14f, 15f, 15f, 13f)
    } }
    val Vacation: ImageVector by lazy { icon("Vacation") {
        moveTo(12f, 8f); arcTo(4f, 4f, 0f, true, true, 12f, 16f); arcTo(4f, 4f, 0f, true, true, 12f, 8f); close()
        moveTo(12f, 2f); verticalLineTo(4.5f); moveTo(12f, 19.5f); verticalLineTo(22f)
        moveTo(2f, 12f); horizontalLineTo(4.5f); moveTo(19.5f, 12f); horizontalLineTo(22f)
        moveTo(4.9f, 4.9f); lineTo(6.7f, 6.7f); moveTo(17.3f, 17.3f); lineTo(19.1f, 19.1f)
        moveTo(4.9f, 19.1f); lineTo(6.7f, 17.3f); moveTo(17.3f, 6.7f); lineTo(19.1f, 4.9f)
    } }
    val Medical: ImageVector by lazy { icon("Medical") {
        moveTo(9f, 3f); horizontalLineTo(15f); verticalLineTo(9f); horizontalLineTo(21f); verticalLineTo(15f)
        horizontalLineTo(15f); verticalLineTo(21f); horizontalLineTo(9f); verticalLineTo(15f)
        horizontalLineTo(3f); verticalLineTo(9f); horizontalLineTo(9f); close()
    } }
    val Summary: ImageVector by lazy { icon("Summary") {
        moveTo(4f, 20f); horizontalLineTo(20f)
        moveTo(6f, 17f); verticalLineTo(11f); moveTo(12f, 17f); verticalLineTo(5f); moveTo(18f, 17f); verticalLineTo(9f)
    } }
    val Euro: ImageVector by lazy { icon("Euro") {
        moveTo(18f, 6.5f); curveTo(16.5f, 5f, 14.5f, 4f, 12.5f, 4f); curveTo(8f, 4f, 5.5f, 7.5f, 5.5f, 12f)
        curveTo(5.5f, 16.5f, 8f, 20f, 12.5f, 20f); curveTo(14.5f, 20f, 16.5f, 19f, 18f, 17.5f)
        moveTo(3f, 10f); horizontalLineTo(13f); moveTo(3f, 14f); horizontalLineTo(13f)
    } }
    val Calendar: ImageVector by lazy { icon("Calendar") {
        moveTo(4f, 6f); horizontalLineTo(20f); verticalLineTo(20f); horizontalLineTo(4f); close()
        moveTo(4f, 10f); horizontalLineTo(20f); moveTo(8f, 3f); verticalLineTo(7f); moveTo(16f, 3f); verticalLineTo(7f)
    } }
    /** Three-quarter arc travelling clockwise, arrow head at its end. */
    val RotateRight: ImageVector by lazy { icon("RotateRight") {
        moveTo(12f, 20f); arcTo(8f, 8f, 0f, true, true, 20f, 12f)
        moveTo(17.5f, 9.5f); lineTo(20f, 12f); lineTo(22.5f, 9.5f)
    } }
    val RotateLeft: ImageVector by lazy { icon("RotateLeft") {
        moveTo(12f, 20f); arcTo(8f, 8f, 0f, true, false, 4f, 12f)
        moveTo(1.5f, 9.5f); lineTo(4f, 12f); lineTo(6.5f, 9.5f)
    } }
    val Check: ImageVector by lazy { icon("Check") {
        moveTo(5f, 12.5f); lineTo(10f, 17f); lineTo(19f, 7f)
    } }
    val Clock: ImageVector by lazy { icon("Clock") {
        moveTo(12f, 4f); arcTo(8f, 8f, 0f, true, true, 12f, 20f); arcTo(8f, 8f, 0f, true, true, 12f, 4f); close()
        moveTo(12f, 8f); verticalLineTo(12f); lineTo(15f, 14f)
    } }
}
