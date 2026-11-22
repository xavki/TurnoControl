package com.turnocontrol.domain.sync

import com.turnocontrol.domain.DataFormat
import com.turnocontrol.domain.DayKind
import com.turnocontrol.domain.PlannedDay
import com.turnocontrol.domain.PlannedScheduleValidator
import com.turnocontrol.domain.ProvisionalInterval
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.util.Locale

/** A payload this version cannot interpret. The record is quarantined or rejected, never coerced into another value. */
class SyncFormatException(message: String) : IllegalArgumentException(message)

/** A calendar day as one logical record: kind, all its intervals (with declared extras) and how it was confirmed. */
data class DayRecord(val day: PlannedDay, val origin: String = "MANUAL")
/** One validity period of a pay rate; identity is (type, validFrom). */
data class RateRecord(val type: String, val validFrom: LocalDate, val validUntil: LocalDate?, val amount: BigDecimal, val currency: String = "EUR")
/** A payslip entered by the user; identity is the pay period. */
data class PayslipRecord(val period: YearMonth, val gross: BigDecimal?, val net: BigDecimal?)

/**
 * Canonical text codecs. Encoding is deterministic, so equal content always gives equal payloads (used for
 * automatic resolution of identical edits). Decoding is strict and validates with the existing domain rules.
 */
object SyncCodecs {
    private val origins = setOf("MANUAL", "OCR_REVIEWED")
    /** Kinds that may leave the device. SICK_LEAVE is local only (owner decision D9); UNKNOWN is never stored. */
    val shareableDayKinds = setOf(DayKind.WORK, DayKind.FREE, DayKind.VACATION)
    private const val LOCAL_ONLY_REASON = "Baja médica: se guarda solo en este dispositivo y no se sincroniza."
    private val intervalPattern = Regex("""(\d{2}):(\d{2})\+([01])-(\d{2}):(\d{2})\+([01]):([NE])""")

    fun dayKey(date: LocalDate) = RecordKey(SyncEntity.SCHEDULED_DAY, date.toString())
    fun rateKey(type: String, validFrom: LocalDate) = RecordKey(SyncEntity.PAY_RULE, "$type@$validFrom")
    fun payslipKey(period: YearMonth) = RecordKey(SyncEntity.PAYSLIP, period.toString())

    // Calendar days. Format: 1|date|kind|origin|HH:MM+o-HH:MM+o:E,... (E = declared extra, N = regular)

    /** Sick leave becomes [LocalRecord.LocalOnly]; its kind never appears in a [SyncPayload]. */
    fun encodeDay(record: DayRecord): LocalRecord {
        val day = record.day
        require(day.kind != DayKind.UNKNOWN) { "Un día sin configurar no se guarda." }
        require(record.origin in origins) { "Origen desconocido «${record.origin}»." }
        PlannedScheduleValidator.validate(listOf(day))?.let { throw IllegalArgumentException(it) }
        val text = dayText(record)
        return if (day.kind in shareableDayKinds) LocalRecord.Shared(SyncPayload(SyncEntity.SCHEDULED_DAY, text))
            else LocalRecord.LocalOnly(SyncEntity.SCHEDULED_DAY, text, LOCAL_ONLY_REASON)
    }

    /** Decodes shared content; a shared payload claiming a non-shareable kind is refused, not reinterpreted. */
    fun decodeDay(key: RecordKey, payload: SyncPayload): DayRecord = parseDay(key, payload.text).also {
        if (it.day.kind !in shareableDayKinds) throw SyncFormatException("Tipo de día no admitido en datos sincronizados: ${it.day.kind}.")
    }

    /** Any local day, shared or local only (validation needs both). */
    fun localDay(key: RecordKey, record: LocalRecord): DayRecord = when (record) {
        is LocalRecord.Shared -> decodeDay(key, record.payload)
        is LocalRecord.LocalOnly -> parseDay(key, record.content)
    }

    private fun dayText(record: DayRecord) = listOf("1", record.day.date.toString(), record.day.kind.name, record.origin,
        record.day.intervals.sortedBy(PlannedScheduleValidator::start).joinToString(",") {
            "%02d:%02d+%d-%02d:%02d+%d:%s".format(Locale.ROOT, it.start.hour, it.start.minute, it.startDayOffset, it.end.hour, it.end.minute, it.endDayOffset, if (it.isExtra) "E" else "N")
        }).joinToString("|")

    private fun parseDay(key: RecordKey, text: String): DayRecord {
        val parts = text.split("|")
        if (parts.size != 5 || parts[0] != "1") throw SyncFormatException("Formato de día no admitido.")
        val date = runCatching { LocalDate.parse(parts[1]) }.getOrElse { throw SyncFormatException("Fecha no válida.") }
        if (key != dayKey(date)) throw SyncFormatException("La clave no coincide con la fecha del día.")
        val kind = DataFormat.dayKind(parts[2]) ?: throw SyncFormatException("Tipo de día desconocido «${parts[2]}».")
        if (parts[3] !in origins) throw SyncFormatException("Origen desconocido «${parts[3]}».")
        val intervals = if (parts[4].isEmpty()) emptyList() else parts[4].split(",").map { raw ->
            // An interval without an end ("open") or with any other shape is refused.
            val m = intervalPattern.matchEntire(raw)?.groupValues ?: throw SyncFormatException("Intervalo incompleto o no válido «$raw».")
            val start = runCatching { LocalTime.of(m[1].toInt(), m[2].toInt()) }.getOrElse { throw SyncFormatException("Hora no válida «$raw».") }
            val end = runCatching { LocalTime.of(m[4].toInt(), m[5].toInt()) }.getOrElse { throw SyncFormatException("Hora no válida «$raw».") }
            ProvisionalInterval(start, end, m[3].toInt(), m[6].toInt(), m[7] == "E")
        }
        val day = PlannedDay(date, kind, intervals)
        PlannedScheduleValidator.validate(listOf(day))?.let { throw SyncFormatException(it) }
        val record = DayRecord(day, parts[3])
        if (dayText(record) != text) throw SyncFormatException("El día no está en forma canónica.")
        return record
    }

    // Pay rates. Format: 1|type|validFrom|validUntil or -|amount|currency

    fun encodeRate(rate: RateRecord): LocalRecord.Shared {
        validateRate(rate)
        return LocalRecord.Shared(SyncPayload(SyncEntity.PAY_RULE, listOf("1", rate.type, rate.validFrom.toString(), rate.validUntil?.toString() ?: "-",
            rate.amount.stripTrailingZeros().toPlainString(), rate.currency).joinToString("|")))
    }

    fun decodeRate(key: RecordKey, payload: SyncPayload): RateRecord {
        val parts = payload.text.split("|")
        if (parts.size != 6 || parts[0] != "1") throw SyncFormatException("Formato de tarifa no admitido.")
        val rate = runCatching {
            RateRecord(parts[1], LocalDate.parse(parts[2]), parts[3].takeIf { it != "-" }?.let(LocalDate::parse), BigDecimal(parts[4]), parts[5])
        }.getOrElse { throw SyncFormatException("Tarifa no válida.") }
        runCatching { validateRate(rate) }.onFailure { throw SyncFormatException(it.message ?: "Tarifa no válida.") }
        if (key != rateKey(rate.type, rate.validFrom)) throw SyncFormatException("La clave no coincide con la tarifa.")
        if (encodeRate(rate).payload != payload) throw SyncFormatException("La tarifa no está en forma canónica.")
        return rate
    }

    private fun validateRate(rate: RateRecord) {
        require(rate.type in DataFormat.knownRateTypes) { "Tipo de tarifa desconocido «${rate.type}»." }
        require(rate.amount.signum() >= 0 && rate.amount.stripTrailingZeros().scale() <= 4) { "Importe de tarifa no admitido." }
        require(rate.validUntil == null || !rate.validUntil.isBefore(rate.validFrom)) { "Vigencia de tarifa invertida." }
        require(rate.currency.matches(Regex("[A-Z]{3}"))) { "Moneda no válida." }
    }

    // Payslips. Format: 1|period|gross or -|net or -

    fun encodePayslip(slip: PayslipRecord): LocalRecord.Shared {
        validatePayslip(slip)
        fun amount(v: BigDecimal?) = v?.stripTrailingZeros()?.toPlainString() ?: "-"
        return LocalRecord.Shared(SyncPayload(SyncEntity.PAYSLIP, listOf("1", slip.period.toString(), amount(slip.gross), amount(slip.net)).joinToString("|")))
    }

    fun decodePayslip(key: RecordKey, payload: SyncPayload): PayslipRecord {
        val parts = payload.text.split("|")
        if (parts.size != 4 || parts[0] != "1") throw SyncFormatException("Formato de nómina no admitido.")
        val slip = runCatching { PayslipRecord(YearMonth.parse(parts[1]), parts[2].takeIf { it != "-" }?.let(::BigDecimal), parts[3].takeIf { it != "-" }?.let(::BigDecimal)) }
            .getOrElse { throw SyncFormatException("Nómina no válida.") }
        runCatching { validatePayslip(slip) }.onFailure { throw SyncFormatException(it.message ?: "Nómina no válida.") }
        if (key != payslipKey(slip.period)) throw SyncFormatException("La clave no coincide con el periodo.")
        if (encodePayslip(slip).payload != payload) throw SyncFormatException("La nómina no está en forma canónica.")
        return slip
    }

    private fun validatePayslip(slip: PayslipRecord) {
        require(slip.gross != null || slip.net != null) { "La nómina necesita al menos un importe." }
        require(listOfNotNull(slip.gross, slip.net).all { it.signum() >= 0 && it.stripTrailingZeros().scale() <= 2 }) { "Importe de nómina no admitido." }
    }

    /** Server-side style check of one shared payload (no cross-record rules). Null when acceptable. */
    fun validationError(key: RecordKey, payload: SyncPayload, dataFormat: Int = SYNC_DATA_FORMAT): String? {
        if (dataFormat > SYNC_DATA_FORMAT) return "Formato de datos $dataFormat más nuevo que el admitido ($SYNC_DATA_FORMAT)."
        if (payload.entity != key.entity) return "El contenido no corresponde a la entidad."
        return try {
            when (key.entity) {
                SyncEntity.SCHEDULED_DAY -> decodeDay(key, payload)
                SyncEntity.PAY_RULE -> decodeRate(key, payload)
                SyncEntity.PAYSLIP -> decodePayslip(key, payload)
            }
            null
        } catch (e: SyncFormatException) { e.message }
    }
}
