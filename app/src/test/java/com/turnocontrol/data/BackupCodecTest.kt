package com.turnocontrol.data

import com.turnocontrol.domain.DataFormat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/** Synthetic data only. org.json needs Robolectric's real implementation. */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class BackupCodecTest {
    private val snapshot = BackupSnapshot(
        listOf(Profile(1, "Perfil ficticio", "ALFA", "Europe/Madrid")),
        listOf(Contract(1, 1, "FULL", 2400, "WEEK", "2026-01-01")),
        listOf(PayRule(1, 1, "HOURLY_BASE", "10.5", "BASE", "UNDEFINED", "2026-01-01"), PayRule(6, 1, "EXTRA_HOUR", "15", "BASE", "UNDEFINED", "2026-01-01")),
        listOf(ScheduledDay(10, 1, "2026-03-02", "WORK", "Europe/Madrid", "MANUAL", 2), ScheduledDay(11, 1, "2026-03-03", "VACATION", "Europe/Madrid", "MANUAL"),
            ScheduledDay(12, 1, "2026-03-04", "SICK_LEAVE", "Europe/Madrid", "OCR_REVIEWED"), ScheduledDay(13, 1, "2026-03-05", "WORK", "Europe/Madrid", "MANUAL")),
        listOf(ScheduledInterval(100, 10, 0, 480, 720, 0, 0), ScheduledInterval(101, 10, 1, 960, 1200, 0, 0, isExtra = true),
            ScheduledInterval(102, 13, 0, 1320, 120, 0, 1)), darkTheme = true)
    private val header = BackupHeader(1, "0.2.0", 2, DataFormat.ROOM_SCHEMA_VERSION, DataFormat.CURRENT, Instant.parse("2026-11-21T20:00:00Z"))
    private val text get() = BackupCodec.encode(BackupFile(header, snapshot))

    /** Edits the payload and re-stamps the hash, so the validation itself is under test. */
    private fun edited(change: (JSONObject) -> Unit): String {
        val root = JSONObject(text)
        val data = root.getJSONObject("data")
        change(data)
        root.getJSONObject("integrity").put("data", BackupCodec.sha256(BackupCodec.canonical(data)))
        return root.toString()
    }
    private fun rejected(json: String, fragment: String) {
        val error = assertThrows(BackupException::class.java) { BackupCodec.decode(json) }
        assertTrue("«${error.message}» should mention «$fragment»", error.message!!.contains(fragment, ignoreCase = true))
    }

    @Test fun roundTripPreservesEveryRecordIdAndSetting() {
        val decoded = BackupCodec.decode(text)
        assertEquals(snapshot.normalized(), decoded.snapshot)
        assertEquals(header, decoded.header)
    }

    @Test fun reorderedKeysStillValidate() {
        fun reversed(value: Any?): Any? = when (value) {
            is JSONObject -> { val out = JSONObject(); value.keys().asSequence().toList().reversed().forEach { out.put(it, reversed(value.get(it))) }; out }
            is JSONArray -> JSONArray((0 until value.length()).map { reversed(value.get(it)) })
            else -> value
        }
        assertEquals(snapshot.normalized(), BackupCodec.decode(reversed(JSONObject(text)).toString()).snapshot)
    }

    @Test fun corruptedTruncatedAndForeignFilesAreRejected() {
        rejected(text.replace("\"Perfil ficticio\"", "\"Perfil alterado\""), "dañada")
        rejected(text.substring(0, text.length / 2), "incompleto")
        rejected("{\"hello\": 1}", "no es una copia")
        rejected("", "incompleto")
    }

    @Test fun futureAndUnsupportedVersionsAreRejectedSafely() {
        rejected(JSONObject(text).put("formatVersion", 2).toString(), "más nueva")
        rejected(JSONObject(text).put("schemaVersion", 5).toString(), "más nueva")
        rejected(JSONObject(text).put("schemaVersion", 3).toString(), "no admitida")
        rejected(JSONObject(text).put("dataFormat", DataFormat.CURRENT + 1).toString(), "más nueva")
    }

    @Test fun unknownEnumValuesAreRejectedNotCoerced() {
        rejected(edited { it.getJSONArray("scheduledDays").getJSONObject(1).put("kind", "COMPENSATORY_REST") }, "COMPENSATORY_REST")
        rejected(edited { it.getJSONArray("payRules").getJSONObject(1).put("type", "WEEKEND_FUTURE") }, "WEEKEND_FUTURE")
        rejected(edited { it.getJSONArray("scheduledDays").getJSONObject(0).put("origin", "CLOUD") }, "origen")
    }

    @Test fun referentialIntegrityAndDuplicatesAreChecked() {
        rejected(edited { it.getJSONArray("scheduledIntervals").getJSONObject(0).put("dayId", 999) }, "sin su día")
        rejected(edited { it.getJSONArray("scheduledDays").getJSONObject(1).put("id", 10) }, "duplicados")
        rejected(edited { it.getJSONArray("scheduledDays").getJSONObject(1).put("date", "2026-03-02") }, "duplicados")
        rejected(edited { it.put("profiles", JSONArray()) }, "perfil")
        rejected(edited { it.getJSONArray("scheduledIntervals").getJSONObject(1).put("position", 0) }, "posiciones")
    }

    @Test fun datesTimesAndTypesAreValidated() {
        rejected(edited { it.getJSONArray("scheduledDays").getJSONObject(0).put("date", "2026-02-31") }, "fecha")
        rejected(edited { it.getJSONArray("scheduledIntervals").getJSONObject(0).put("startMinute", 1440) }, "fuera de rango")
        rejected(edited { it.getJSONArray("scheduledIntervals").getJSONObject(1).put("startMinute", 600) }, "solapados")
        rejected(edited { it.getJSONArray("scheduledDays").getJSONObject(0).put("revision", "2") }, "revision")
        rejected(edited { it.getJSONArray("payRules").getJSONObject(0).put("amountDecimal", "-1") }, "importe")
        rejected(edited { it.getJSONArray("profiles").getJSONObject(0).remove("zoneId") }, "zoneId")
        rejected(JSONObject(text).also { it.getJSONObject("data").getJSONArray("scheduledIntervals").getJSONObject(0).put("startMinute", 480.5) }.toString(), "no admitido")
        rejected(edited { it.getJSONArray("scheduledDays").getJSONObject(1).put("kind", "WORK") }, "intervalos")
    }
}
