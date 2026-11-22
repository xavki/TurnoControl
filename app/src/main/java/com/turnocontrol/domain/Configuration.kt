package com.turnocontrol.domain

import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

data class ConfigurationDraft(
    val name: String = "", val alias: String = "", val zone: String = ZoneId.systemDefault().id,
    val type: String = "PARTIAL", val hours: String = "", val unit: String = "MONTH",
    val from: String = LocalDate.now().toString(), val hourlyRate: String = "",
    val extraRates: Map<String, String> = emptyMap()
)

/** A configuration value the user must correct. Its message is Spanish and safe to show as is. */
class ConfigurationValidationException(message: String) : IllegalArgumentException(message)

/**
 * Limits of the profile texts, shared by the settings form, the repository and the synchronization codec.
 * Length is counted in Unicode characters (code points), so accents, ñ, ç, l·l or emoji count as one each.
 */
object ProfileLimits {
    const val MAX_TEXT_LENGTH = 200
    fun length(value: String) = value.codePointCount(0, value.length)
    fun fits(value: String) = length(value) <= MAX_TEXT_LENGTH
}

/**
 * [storedName] / [storedAlias] are the values already saved. A longer value saved by an older version (which had no
 * limit) is kept as is when unchanged, so offline use and other settings never break; only a new or edited value
 * must respect [ProfileLimits]. Nothing is ever truncated.
 */
fun ConfigurationDraft.validate(storedName: String? = null, storedAlias: String? = null): String? {
    if (name.isBlank()) return "Introduce un nombre visible."
    if (!ProfileLimits.fits(name.trim()) && name.trim() != storedName) return "El nombre visible no puede superar ${ProfileLimits.MAX_TEXT_LENGTH} caracteres."
    if (!ProfileLimits.fits(alias.trim()) && alias.trim() != storedAlias) return "El alias no puede superar ${ProfileLimits.MAX_TEXT_LENGTH} caracteres."
    if (runCatching { ZoneId.of(zone) }.isFailure) return "Zona horaria no válida."
    if (runCatching { LocalDate.parse(from) }.isFailure) return "Fecha no válida (AAAA-MM-DD)."
    if (type !in listOf("PARTIAL", "FULL") || unit !in listOf("WEEK", "MONTH", "YEAR")) return "Contrato no válido."
    val minutes = runCatching { decimal(hours).multiply(BigDecimal(60)).longValueExact() }.getOrNull()
    if (minutes == null || minutes <= 0) return "Introduce horas positivas con precisión de minutos."
    for (value in listOf(hourlyRate) + extraRates.values) {
      if (value.isNotBlank()) {
        val rate = runCatching { decimal(value) }.getOrNull()
        if (rate == null || rate.signum() < 0 || rate.scale() > 4) return "Tarifa no válida (máximo 4 decimales)."
      }
    }
    return null
}
fun decimal(value: String): BigDecimal = value.trim().replace(',', '.').toBigDecimal()

val rateLabels = linkedMapOf(
    "EXTRA_PAY" to "Prorrata de pagas EUR/h",
    "COMPLEMENTARY" to "Hora complementaria EUR/h",
    "NIGHT" to "Plus nocturno EUR/h",
    "SPECIAL_NIGHT" to "Plus nocturno especial EUR/h",
    "EXTRA_HOUR" to "Hora extra EUR/h"
)
