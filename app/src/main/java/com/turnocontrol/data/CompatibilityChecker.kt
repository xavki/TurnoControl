package com.turnocontrol.data

import android.database.sqlite.SQLiteException
import com.turnocontrol.domain.CompatibilityReport
import com.turnocontrol.domain.DataFormat
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Startup gate. Reads raw stored strings (never through `planned()`), so unknown values cannot crash it, and it
 * never writes when it rejects the data. Room fails on first access, not at build(), for a newer schema version
 * or a schema hash it does not recognise; both are reported instead of crashing.
 */
@Singleton
class CompatibilityChecker @Inject constructor(private val db: TurnoDatabase) {
    fun check(): CompatibilityReport {
        val sql = try { db.openHelper.writableDatabase } catch (e: IllegalStateException) {
            val message = e.message.orEmpty()
            if ("migration" in message.lowercase() || "verify the data integrity" in message) return CompatibilityReport.Incompatible(listOf(
                "La base de datos fue creada o modificada por otra versión de TurnoControl (esquema no reconocido). No se ha cambiado nada."))
            throw e
        } catch (e: SQLiteException) {
            return CompatibilityReport.Incompatible(listOf("No se puede abrir la base de datos local. No se ha cambiado nada."))
        }
        val applicationId = sql.query("PRAGMA application_id").use { if (it.moveToFirst()) it.getInt(0) else 0 }
        val reasons = mutableListOf<String>()
        val format = when {
            applicationId == 0 -> 1 // Created before the marker existed (0.1.0 or an earlier 0.2.0 build).
            applicationId and 0xFFFF0000.toInt() == DataFormat.APPLICATION_ID_MAGIC -> applicationId and 0xFFFF
            else -> { reasons += "La base de datos no pertenece a TurnoControl."; -1 }
        }
        if (format > DataFormat.CURRENT) reasons += "Los datos proceden de una versión más nueva de TurnoControl (formato $format). Actualiza la aplicación."
        val unknownKinds = sql.query("SELECT DISTINCT kind FROM ScheduledDay").use { cursor ->
            buildList { while (cursor.moveToNext()) cursor.getString(0).takeIf { DataFormat.dayKind(it) == null }?.let(::add) }
        }
        if (unknownKinds.isNotEmpty()) reasons += "Hay tipos de día que esta versión no reconoce: ${unknownKinds.joinToString()}."
        if (reasons.isNotEmpty()) return CompatibilityReport.Incompatible(reasons)
        // Only accepted data is stamped, so a later older build can be detected by future versions.
        if (format < DataFormat.CURRENT) sql.execSQL("PRAGMA application_id = ${DataFormat.applicationId()}")
        return CompatibilityReport.Compatible
    }

    /** Unknown pay-rule types are not an error: they are shown nowhere but always preserved on save. */
    fun unknownRateTypes(): List<String> = db.openHelper.readableDatabase.query("SELECT DISTINCT type FROM PayRule").use { cursor ->
        buildList { while (cursor.moveToNext()) cursor.getString(0).takeIf { it !in DataFormat.knownRateTypes }?.let(::add) }
    }
}
