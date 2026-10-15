package com.turnocontrol

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.turnocontrol.data.*
import com.turnocontrol.domain.*
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real Compose captures of synthetic planning only, never system bars or user photos. */
class PortfolioScreenshotsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun syntheticCalendarDetailEditorAndExtras() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.turnocontrol.verification")
        val db = EntryPointAccessors.fromApplication(context, CalendarTestDatabase::class.java).database()
        val repo = ScheduleRepository(db)
        val config = ConfigurationRepository(db, context)
        val previous = runBlocking { repo.snapshot() }
        val dark = runBlocking { config.darkTheme.first() }
        val date = LocalDate.now().withDayOfMonth(10)
        val split = PlannedDay(date, DayKind.WORK, TimeCellParser.parse("08:00-12:00 / 16:00-20:00").intervals.mapIndexed { i, interval -> interval.copy(isExtra = i == 1) })
        val demo = listOf(split, PlannedDay(date.plusDays(1), DayKind.FREE, emptyList()),
            PlannedDay(date.plusDays(2), DayKind.WORK, TimeCellParser.parse("18:00-24:00 / 00:00-01:00").intervals.mapIndexed { i, interval -> interval.copy(isExtra = i == 1) }))
        fun capture(name: String) {
            compose.waitForIdle()
            val directory = File(context.cacheDir, "portfolio").apply { mkdirs() }
            val bitmap = if (name == "shift-editor.png") captureDialog() else compose.onRoot().captureToImage().asAndroidBitmap()
            File(directory, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
        try {
            runBlocking {
                config.setDarkTheme(false)
                repo.save(previous, demo, previous.map { LocalDate.parse(it.day.date) }.filter { it !in demo.map { day -> day.date } }.toSet())
            }
            compose.waitUntil(10000) { runCatching { compose.onNodeWithContentDescription("$date · Trabajo", substring = true).assertIsEnabled(); true }.getOrDefault(false) }
            capture("calendar.png")
            compose.onNodeWithContentDescription("$date · Trabajo", substring = true).performClick()
            compose.waitUntil(10000) { runCatching { compose.onNodeWithText("Marcar libre").assertIsEnabled(); true }.getOrDefault(false) }
            compose.onNodeWithText("Programadas: 8 h 0 min").assertExists()
            capture("day-detail.png")
            compose.onNodeWithText("Editar intervalo 2").performScrollTo().performClick()
            compose.onNodeWithText("Guardar registro").assertExists()
            capture("shift-editor.png")
            compose.onNodeWithText("Cancelar registro").performClick()
            compose.onNodeWithText("Volver al calendario").performScrollTo().performClick()
            compose.onNodeWithText("Horas extras").performClick()
            compose.onNodeWithText("5 h 0 min").assertExists()
            capture("extra-hours.png")
        } finally {
            runBlocking {
                val current = repo.snapshot()
                repo.save(current, previous.map { it.planned() }, current.map { LocalDate.parse(it.day.date) }.filter { it !in previous.map { day -> LocalDate.parse(day.day.date) } }.toSet())
                config.setDarkTheme(dark)
            }
        }
    }

    /** PixelCopy captures the actual dialog window, not coordinates in the Activity behind it. */
    private fun captureDialog(): Bitmap {
        var window: Window? = null
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isRoot())
            .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
            .perform(object : androidx.test.espresso.ViewAction {
                override fun getDescription() = "Find the synthetic shift editor window"
                override fun getConstraints(): org.hamcrest.Matcher<View> = androidx.test.espresso.matcher.ViewMatchers.isRoot()
                override fun perform(controller: androidx.test.espresso.UiController, view: View) {
                    fun find(node: View): Window? {
                        if (node is DialogWindowProvider) return node.window
                        if (node is ViewGroup) for (i in 0 until node.childCount) find(node.getChildAt(i))?.let { return it }
                        return null
                    }
                    window = find(view)
                }
            })
        val target = checkNotNull(window) { "Editor dialog window not found" }
        val bitmap = Bitmap.createBitmap(target.decorView.width, target.decorView.height, Bitmap.Config.ARGB_8888)
        val ready = CountDownLatch(1)
        var result = PixelCopy.ERROR_UNKNOWN
        PixelCopy.request(target, bitmap, { result = it; ready.countDown() }, Handler(Looper.getMainLooper()))
        check(ready.await(10, TimeUnit.SECONDS) && result == PixelCopy.SUCCESS) { "Dialog capture failed: $result" }
        return bitmap
    }
}
