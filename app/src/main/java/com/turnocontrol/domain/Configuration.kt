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

fun ConfigurationDraft.validate(): String? {
    if (name.isBlank()) return "Introduce un nombre visible."
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
    "SPECIAL_NIGHT" to "Plus nocturno especial EUR/h"
)
