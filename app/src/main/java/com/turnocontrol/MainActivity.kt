package com.turnocontrol

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import com.turnocontrol.data.accounts.AccountSwitchRestart
import com.turnocontrol.ui.*
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val model: ConfigurationViewModel by viewModels()
    private val importModel: ImportViewModel by viewModels()
    private val reviewModel: ScheduleReviewViewModel by viewModels()
    private val calendarModel: CalendarViewModel by viewModels()
    private val summaryModel: SummaryViewModel by viewModels()
    private val startupModel: StartupViewModel by viewModels()
    private val backupModel: BackupViewModel by viewModels()
    private val accountModel: AccountViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val theme by model.theme.collectAsStateWithLifecycle()
            val startup by startupModel.state.collectAsStateWithLifecycle()
            // The in-app theme can differ from the system's: system bar icons must follow the app theme, or a dark app
            // theme on a light system shows dark status-bar icons on a dark background (seen on the QA emulator).
            LaunchedEffect(theme.dark) {
                val style = if (theme.dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            TurnoTheme(theme.dark) {
                // The calendar ViewModels are created lazily, only after the stored data has been accepted.
                when (val gate = startup) {
                    StartupState.Checking -> StartupCheckingScreen()
                    is StartupState.Blocked -> IncompatibleDataScreen(gate.reasons, startupModel::retry)
                    is StartupState.Recovery -> InterruptedRestoreScreen(gate, startupModel::keepCurrentData, startupModel::restoreSafetyCopy, startupModel::acknowledge)
                    StartupState.Ready -> {
                        val backup by backupModel.state.collectAsStateWithLifecycle()
                        val recovered by startupModel.dataReplaced.collectAsStateWithLifecycle()
                        // One-shot events, consumed after handling, so an Activity recreation never repeats the reset.
                        LaunchedEffect(recovered) { if (recovered) { model.reload(); startupModel.consumeDataReplaced() } }
                        // A completed restore replaced every row: reload the configuration draft and drop calendar/OCR drafts.
                        LaunchedEffect(backup.dataReplaced) {
                            if (backup.dataReplaced) { model.reload(); calendarModel.resetAfterRestore(); reviewModel.clear(); importModel.clear(); backupModel.consumeDataReplaced() }
                        }
                        // After a confirmed storage switch the calendar, summary and settings ViewModels still point at the
                        // previous (closed) database: start a fresh task so every screen is rebuilt on the new scope.
                        val account by accountModel.state.collectAsStateWithLifecycle()
                        LaunchedEffect(account.restartRequired) {
                            if (account.restartRequired) { accountModel.consumeRestart(); AccountSwitchRestart.restart(this@MainActivity) }
                        }
                        TurnoApp(model, importModel, reviewModel, calendarModel, summaryModel, backupModel, accountModel)
                    }
                }
            }
        }
    }
}

private val tabs = listOf("Calendario" to TurnoIcons.Calendar, "Resumen" to TurnoIcons.Summary)

@Composable
fun TurnoApp(model: ConfigurationViewModel, importModel: ImportViewModel, reviewModel: ScheduleReviewViewModel, calendarModel: CalendarViewModel, summaryModel: SummaryViewModel, backupModel: BackupViewModel? = null,
    accountModel: AccountViewModel? = null) {
    val state by model.state.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    // No saveState/restoreState: secondary routes (Detalle, Ajustes, OCR) above a tab would be saved and resurface on a later tab switch.
    // Tab state lives in Activity-scoped ViewModels; only the scroll position resets.
    fun tab(title: String) = nav.navigate(title) { popUpTo(nav.graph.startDestinationId); launchSingleTop = true }
    val openSettings: () -> Unit = { nav.navigate("Ajustes") { launchSingleTop = true } }
    val backToCalendar: () -> Unit = { nav.navigate("Calendario") { popUpTo("Calendario") { inclusive = true }; launchSingleTop = true } }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = {
        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
            tabs.forEach { (title, icon) ->
                NavigationBarItem(selected = route == title || (title == "Calendario" && route == "Detalle"),
                    onClick = { tab(title) }, icon = { Icon(icon, contentDescription = null) }, label = { Text(title, style = MaterialTheme.typography.labelMedium) })
            }
        }
    }) { padding ->
        // consumeWindowInsets: each screen's TopAppBar would otherwise add the status bar inset a second time (≈ 50 dp gap
        // under the status bar, seen on the QA emulator); screens without a top bar still get the padding.
        NavHost(navController = nav, startDestination = "Calendario", modifier = Modifier.padding(padding).consumeWindowInsets(padding)) {
            composable("Calendario") { CalendarScreen(calendarModel, summaryModel, openDay = { nav.navigate("Detalle") },
                openSummary = { month -> summaryModel.show(month); tab("Resumen") }, openSettings = openSettings, openImport = { nav.navigate("Importar") }) }
            composable("Resumen") { SummaryScreen(summaryModel, calendarModel, openDay = { date -> calendarModel.select(date); nav.navigate("Detalle") }, openSettings = openSettings) }
            composable("Detalle") {
                val detailState by calendarModel.state.collectAsStateWithLifecycle()
                if (detailState.selected == null) LaunchedEffect(Unit) { backToCalendar() }
                else DayDetailScreen(calendarModel, backToCalendar)
            }
            composable("Ajustes") {
                val calendarState by calendarModel.state.collectAsStateWithLifecycle()
                SettingsScreen(model, state, onImport = { nav.navigate("Importar") }, onBack = { nav.popBackStack() },
                    backup = backupModel, restoreBlockedReason = calendarState.let { calendarModel.restoreBlockedReason() },
                    account = accountModel, onAccount = { nav.navigate("Cuenta") { launchSingleTop = true } })
            }
            // Account options (informative while no provider exists; nothing here switches data).
            accountModel?.let { account -> composable("Cuenta") { AccountScreen(account, onBack = { nav.popBackStack() }) } }
            composable("Importar") { ImportScreen(importModel, reviewModel::clear, { document ->
                reviewModel.prepare(document, state.draft.name, state.draft.alias)
                nav.navigate("Revisar")
            }) { nav.popBackStack() } }
            composable("Revisar") {
                val imported by importModel.state.collectAsStateWithLifecycle()
                ScheduleReviewScreen(reviewModel, { days ->
                    calendarModel.prepareImport(days)
                    nav.navigate("Confirmar")
                }, imported.image?.bitmap) { nav.popBackStack() }
            }
            // Review data is discarded only after a confirmed write; on failure it stays for a retry.
            composable("Confirmar") { ImportConfirmationScreen(calendarModel, { nav.popBackStack() }, backToCalendar,
                saved = { reviewModel.clear(); importModel.clear(); backToCalendar() }) }
        }
    }
}
