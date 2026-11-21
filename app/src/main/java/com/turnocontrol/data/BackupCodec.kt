package com.turnocontrol.data

import com.turnocontrol.domain.*
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Every stored row plus the DataStore settings. Lists are kept in id order so snapshots compare structurally. */
data class BackupSnapshot(
    val profiles: List<Profile>, val contracts: List<Contract>, val payRules: List<PayRule>,
    val days: List<ScheduledDay>, val intervals: List<ScheduledInterval>, val darkTheme: Boolean,
) {
    fun normalized() = copy(profiles = profiles.sortedBy { it.id }, contracts = contracts.sortedBy { it.id }, payRules = payRules.sortedBy { it.id },
        days = days.sortedBy { it.id }, intervals = intervals.sortedBy { it.id })
    /** Room part only: DataStore is written in a separate step and compared separately. */
    fun roomData() = normalized().copy(darkTheme = false)
}
data class BackupHeader(val formatVersion: Int, val appVersionName: String, val appVersionCode: Long, val schemaVersion: Int, val dataFormat: Int, val createdAt: Instant)
data class BackupFile(val header: BackupHeader, val snapshot: BackupSnapshot)
class BackupException(message: String) : Exception(message)

/**
 * TurnoControl backup format, version 1 (documented in docs/COPIAS_DE_SEGURIDAD.md).
 * The integrity field is a SHA-256 of a canonical (sorted-key) serialisation of `data`: it detects corrupted or
 * truncated files, it is not a signature and does not protect against deliberate edits.
 */
object BackupCodec {
    const val FORMAT = "turnocontrol-backup"
    const val FORMAT_VERSION = 1
    const val MAX_BYTES = 10 * 1024 * 1024
    private val contractTypes = setOf("PARTIAL", "FULL")
    private val contractUnits = setOf("WEEK", "MONTH", "YEAR")
    private val origins = setOf("MANUAL", "OCR_REVIEWED")

    fun encode(file: BackupFile): String {
        val s = file.snapshot.normalized()
        val data = JSONObject()
            .put("profiles", JSONArray(s.profiles.map { JSONObject().put("id", it.id).put("displayName", it.displayName).put("alias", it.alias).put("zoneId", it.zoneId) }))
            .put("contracts", JSONArray(s.contracts.map { JSONObject().put("id", it.id).put("profileId", it.profileId).put("type", it.type).put("agreedMinutes", it.agreedMinutes)
                .put("unit", it.unit).put("validFrom", it.validFrom).put("validUntil", it.validUntil ?: JSONObject.NULL) }))
            .put("payRules", JSONArray(s.payRules.map { JSONObject().put("id", it.id).put("profileId", it.profileId).put("type", it.type).put("amountDecimal", it.amountDecimal)
                .put("mode", it.mode).put("accumulation", it.accumulation).put("validFrom", it.validFrom).put("validUntil", it.validUntil ?: JSONObject.NULL).put("currency", it.currency) }))
            .put("scheduledDays", JSONArray(s.days.map { JSONObject().put("id", it.id).put("profileId", it.profileId).put("date", it.date).put("kind", it.kind)
                .put("zoneId", it.zoneId).put("origin", it.origin).put("revision", it.revision) }))
            .put("scheduledIntervals", JSONArray(s.intervals.map { JSONObject().put("id", it.id).put("dayId", it.dayId).put("position", it.position).put("startMinute", it.startMinute)
                .put("endMinute", it.endMinute).put("startDayOffset", it.startDayOffset).put("endDayOffset", it.endDayOffset).put("isExtra", it.isExtra) }))
            .put("settings", JSONObject().put("darkTheme", s.darkTheme))
        val h = file.header
        return JSONObject()
            .put("format", FORMAT).put("formatVersion", h.formatVersion)
            .put("app", JSONObject().put("versionName", h.appVersionName).put("versionCode", h.appVersionCode))
            .put("schemaVersion", h.schemaVersion).put("dataFormat", h.dataFormat).put("createdAt", h.createdAt.toString())
            .put("integrity", JSONObject().put("algorithm", "SHA-256").put("data", sha256(canonical(data))))
            .put("data", data).toString(2)
    }

    /** Parses and fully validates; any problem rejects the whole file. Nothing is written here. */
    fun decode(text: String): BackupFile {
        val root = try { JSONObject(text) } catch (e: JSONException) { throw BackupException("El archivo no es una copia de TurnoControl válida o está incompleto.") }
        if (root.optString("format") != FORMAT) throw BackupException("El archivo no es una copia de seguridad de TurnoControl.")
        val formatVersion = root.int("formatVersion")
        if (formatVersion > FORMAT_VERSION) throw BackupException("La copia se creó con una versión más nueva de TurnoControl (formato $formatVersion). Actualiza la aplicación para restaurarla.")
        if (formatVersion < 1) throw BackupException("Versión de copia no admitida ($formatVersion).")
        val schema = root.int("schemaVersion")
        if (schema > DataFormat.ROOM_SCHEMA_VERSION) throw BackupException("La copia procede de una versión más nueva de la base de datos ($schema). Actualiza la aplicación.")
        if (schema < DataFormat.ROOM_SCHEMA_VERSION) throw BackupException("Versión de base de datos de la copia no admitida ($schema).")
        val dataFormat = root.int("dataFormat")
        if (dataFormat > DataFormat.CURRENT) throw BackupException("La copia contiene datos de una versión más nueva de TurnoControl (formato de datos $dataFormat).")
        val app = root.obj("app")
        val createdAt = runCatching { Instant.parse(root.str("createdAt")) }.getOrElse { throw BackupException("Fecha de creación de la copia no válida.") }
        val integrity = root.obj("integrity")
        if (integrity.str("algorithm") != "SHA-256") throw BackupException("Algoritmo de integridad no admitido.")
        val data = root.obj("data")
        if (sha256(canonical(data)) != integrity.str("data")) throw BackupException("La copia está dañada: la comprobación de integridad no coincide.")
        val snapshot = BackupSnapshot(
            data.arr("profiles").objects().map { Profile(it.long("id"), it.str("displayName"), it.str("alias"), it.str("zoneId")) },
            data.arr("contracts").objects().map { Contract(it.long("id"), it.long("profileId"), it.str("type"), it.long("agreedMinutes"), it.str("unit"), it.str("validFrom"), it.strOrNull("validUntil")) },
            data.arr("payRules").objects().map { PayRule(it.long("id"), it.long("profileId"), it.str("type"), it.str("amountDecimal"), it.str("mode"), it.str("accumulation"), it.str("validFrom"), it.strOrNull("validUntil"), it.str("currency")) },
            data.arr("scheduledDays").objects().map { ScheduledDay(it.long("id"), it.long("profileId"), it.str("date"), it.str("kind"), it.str("zoneId"), it.str("origin"), it.long("revision")) },
            data.arr("scheduledIntervals").objects().map { ScheduledInterval(it.long("id"), it.long("dayId"), it.int("position"), it.int("startMinute"), it.int("endMinute"), it.int("startDayOffset"), it.int("endDayOffset"), it.bool("isExtra")) },
            data.obj("settings").bool("darkTheme"),
        ).normalized()
        validate(snapshot)
        return BackupFile(BackupHeader(formatVersion, app.str("versionName"), app.long("versionCode"), schema, dataFormat, createdAt), snapshot)
    }

    fun validate(s: BackupSnapshot) {
        fun fail(message: String): Nothing = throw BackupException("Copia no válida: $message")
        fun date(value: String, what: String) = runCatching { LocalDate.parse(value) }.getOrElse { fail("fecha incorrecta en $what ($value).") }
        fun zone(value: String) = runCatching { ZoneId.of(value) }.getOrElse { fail("zona horaria desconocida ($value).") }
        fun <T> unique(items: List<T>, what: String) { if (items.distinct().size != items.size) fail("identificadores duplicados en $what.") }
        if (s.profiles.size > 1 || s.profiles.any { it.id != 1L }) fail("solo se admite un perfil con identificador 1.")
        s.profiles.forEach { if (it.displayName.isBlank()) fail("el perfil no tiene nombre."); zone(it.zoneId) }
        val profileIds = s.profiles.map { it.id }.toSet()
        if (s.contracts.size > 1 || s.contracts.any { it.id != 1L }) fail("solo se admite un contrato con identificador 1.")
        s.contracts.forEach {
            if (it.profileId !in profileIds) fail("un contrato no tiene perfil.")
            if (it.type !in contractTypes || it.unit !in contractUnits || it.agreedMinutes <= 0) fail("contrato con valores no admitidos.")
            val from = date(it.validFrom, "el contrato")
            it.validUntil?.let { until -> if (date(until, "el contrato") < from) fail("vigencia de contrato invertida.") }
        }
        unique(s.payRules.map { it.id }, "tarifas"); unique(s.payRules.map { it.type }, "tipos de tarifa")
        s.payRules.forEach {
            if (it.profileId !in profileIds) fail("una tarifa no tiene perfil.")
            if (it.type !in DataFormat.knownRateTypes) fail("tipo de tarifa desconocido «${it.type}»: la copia procede de otra versión.")
            val amount = runCatching { BigDecimal(it.amountDecimal) }.getOrElse { fail("importe de tarifa no numérico.") }
            if (amount.signum() < 0 || amount.scale() > 4) fail("importe de tarifa no admitido.")
            if (it.mode.isBlank() || it.accumulation.isBlank() || it.currency.isBlank()) fail("tarifa incompleta.")
            date(it.validFrom, "una tarifa"); it.validUntil?.let { until -> date(until, "una tarifa") }
        }
        unique(s.days.map { it.id }, "días"); unique(s.days.map { it.date }, "fechas")
        s.days.forEach {
            if (it.id <= 0) fail("identificador de día no válido.")
            if (it.profileId !in profileIds) fail("hay días sin perfil.")
            date(it.date, "un día"); zone(it.zoneId)
            if (DataFormat.dayKind(it.kind) == null) fail("tipo de día desconocido «${it.kind}»: la copia procede de otra versión.")
            if (it.origin !in origins || it.revision < 1) fail("día con origen o revisión no válidos.")
        }
        val dayIds = s.days.map { it.id }.toSet()
        unique(s.intervals.map { it.id }, "intervalos"); unique(s.intervals.map { it.dayId to it.position }, "posiciones de intervalo")
        s.intervals.forEach {
            if (it.id <= 0 || it.dayId !in dayIds) fail("hay intervalos sin su día.")
            if (it.startMinute !in 0..1439 || it.endMinute !in 0..1439 || it.startDayOffset !in 0..1 || it.endDayOffset !in 0..1) fail("intervalo con horas fuera de rango.")
        }
        val planned = s.days.map { day ->
            PlannedDay(LocalDate.parse(day.date), DataFormat.dayKind(day.kind)!!, s.intervals.filter { it.dayId == day.id }.sortedBy { it.position }.map {
                ProvisionalInterval(LocalTime.of(it.startMinute / 60, it.startMinute % 60), LocalTime.of(it.endMinute / 60, it.endMinute % 60), it.startDayOffset, it.endDayOffset, it.isExtra)
            })
        }
        PlannedScheduleValidator.validate(planned)?.let { fail(it) }
    }

    /** Sorted-key JSON so the hash does not depend on how a parser orders keys. */
    internal fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { "${JSONObject.quote(it)}:${canonical(value.get(it))}" }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        is Boolean -> value.toString()
        is Int, is Long -> value.toString()
        else -> throw BackupException("Valor no admitido en la copia.")
    }
    internal fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun missing(key: String): Nothing = throw BackupException("Copia no válida: falta o es incorrecto el campo «$key».")
    private fun JSONObject.str(key: String) = (opt(key) as? String) ?: missing(key)
    private fun JSONObject.strOrNull(key: String): String? = when (val v = opt(key)) { null -> missing(key); JSONObject.NULL -> null; is String -> v; else -> missing(key) }
    private fun JSONObject.long(key: String): Long = when (val v = opt(key)) { is Int -> v.toLong(); is Long -> v; else -> missing(key) }
    private fun JSONObject.int(key: String): Int = long(key).let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else missing(key) }
    private fun JSONObject.bool(key: String) = (opt(key) as? Boolean) ?: missing(key)
    private fun JSONObject.obj(key: String) = (opt(key) as? JSONObject) ?: missing(key)
    private fun JSONObject.arr(key: String) = (opt(key) as? JSONArray) ?: missing(key)
    private fun JSONArray.objects() = (0 until length()).map { (opt(it) as? JSONObject) ?: missing("elemento $it") }
}
