package com.turnocontrol

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.core.view.WindowCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.turnocontrol.data.ConfigurationRepository
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/** Regressions found on the QA-1 emulator: system bar icons and the status bar inset. */
class SystemBarsThemeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun statusBarIconsFollowTheAppTheme() {
        compose.awaitStartup()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.turnocontrol.verification")
        val db = EntryPointAccessors.fromApplication(context, CalendarTestDatabase::class.java).database()
        val configuration = ConfigurationRepository(db, context)
        val original = runBlocking { configuration.darkTheme.first() }
        fun lightIcons() = compose.runOnUiThread {
            WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView).isAppearanceLightStatusBars
        }
        try {
            listOf(true, false, true).forEach { dark ->
                runBlocking { configuration.setDarkTheme(dark) }
                // Light (dark-coloured) icons only on a light app theme; on a dark theme the icons must be light.
                compose.waitUntil(5_000) { lightIcons() == !dark }
            }
        } finally { runBlocking { configuration.setDarkTheme(original) } }
    }

    /** The top app bar sits right below the status bar: the inset is applied once, not twice. */
    @Test fun topBarIsNotPushedDownByADoubleStatusBarInset() {
        compose.awaitStartup()
        val statusBar = compose.runOnUiThread {
            androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)!!
                .getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top
        }
        val title = compose.onNodeWithText("TurnoControl").fetchSemanticsNode().boundsInRoot.top
        val limit = statusBar + 40 * compose.activity.resources.displayMetrics.density
        check(title < limit) { "Título en $title px; barra de estado $statusBar px; límite $limit px" }
    }
}
