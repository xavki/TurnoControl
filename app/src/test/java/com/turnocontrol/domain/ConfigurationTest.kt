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
    @Test fun profileTextLimitIsSharedAndCountedInCharacters() {
        assertEquals("Introduce un nombre visible.", valid.copy(name = "   ").validate())
        assertNull(valid.copy(name = "a".repeat(ProfileLimits.MAX_TEXT_LENGTH)).validate())
        assertEquals("El nombre visible no puede superar 200 caracteres.", valid.copy(name = "a".repeat(201)).validate())
        assertEquals("El alias no puede superar 200 caracteres.", valid.copy(alias = "b".repeat(201)).validate())
        // Accented Spanish and Catalan characters and emoji count as one character each.
        assertNull(valid.copy(name = "Àngels Núñez i Colomer, l·l ç ü").validate())
        val emoji = "😀" // One character, two UTF-16 units.
        assertNull(valid.copy(name = emoji.repeat(200)).validate())
        assertNotNull(valid.copy(name = emoji.repeat(201)).validate())
        assertEquals(200, ProfileLimits.length("ñ".repeat(100) + emoji.repeat(100)))
    }
    @Test fun unchangedLegacyNamesLongerThanTheLimitStayValid() {
        val legacy = "Nombre antiguo ".repeat(20).trim() // 299 characters, saved by a version without a limit.
        assertNull(valid.copy(name = legacy).validate(storedName = legacy))
        assertNull(valid.copy(name = "  $legacy  ", hourlyRate = "9").validate(storedName = legacy))
        assertNotNull(valid.copy(name = "$legacy!").validate(storedName = legacy)) // Edited: the limit applies.
        assertNotNull(valid.copy(name = legacy).validate())
    }
}
