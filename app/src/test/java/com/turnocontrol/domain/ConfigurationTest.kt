package com.turnocontrol.domain

import org.junit.Assert.*
import org.junit.Test

class ConfigurationTest {
    private val valid = ConfigurationDraft(name = "Perfil ficticio", zone = "Europe/Madrid", hours = "60", from = "2026-01-01")
    @Test fun acceptsExplicitContractWithoutRate() { assertNull(valid.validate()) }
    @Test fun acceptsDecimalComma() { assertNull(valid.copy(hours = "7,5", hourlyRate = "7,17").validate()) }
    @Test fun rejectsInvalidInput() {
        listOf(valid.copy(name = ""), valid.copy(hours = "-1"), valid.copy(hours = "0.001"), valid.copy(zone = "unknown"), valid.copy(from = "2026-02-30"), valid.copy(hourlyRate = "-1"), valid.copy(hourlyRate = "1.12345")).forEach { assertNotNull(it.validate()) }
    }
}
