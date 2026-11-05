package io.github.ev80diag.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import io.github.ev80diag.pipeline.LinkStatus
import io.github.ev80diag.session.SessionType
import io.github.ev80diag.signals.SignalFormat
import io.github.ev80diag.ui.screen.BatteryScreen
import io.github.ev80diag.ui.screen.LiveScreen
import io.github.ev80diag.ui.screen.TilesScreen
import io.github.ev80diag.ui.screen.LiveThresholds
import io.github.ev80diag.ui.screen.LiveUiBuilder
import io.github.ev80diag.ui.screen.DetailsScreen
import io.github.ev80diag.ui.screen.TopBarWarning
import io.github.ev80diag.warnings.Level
import io.github.ev80diag.warnings.WarningRules
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.ev80diag.R
import io.github.ev80diag.service.Recorder
import io.github.ev80diag.ui.components.TopStatusBar
import io.github.ev80diag.ui.components.MarkDialog
import io.github.ev80diag.ui.components.ProfileEventUi
import io.github.ev80diag.ui.screen.VanEditScreen
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import io.github.ev80diag.ui.screen.ChargingScreen
import io.github.ev80diag.export.ExportFormat
import io.github.ev80diag.ui.screen.ConnectScreen
import io.github.ev80diag.ui.screen.ExportDebugScreen
import io.github.ev80diag.ui.screen.shareSession
import io.github.ev80diag.ui.screen.SettingsScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Lets the activity (notification tap, USB attach) ask the nav graph to open a route. */
object NavRequests { val route = MutableStateFlow<String?>(null) }

private data class NavItem(val screen: Screen, val icon: ImageVector, val labelRes: Int)

/** Shown in the bar in portrait (the rest goes into ⋯); in landscape [Screen.Details] joins them. */
private val BAR_PORTRAIT = listOf(
    NavItem(Screen.Live, Icons.Default.Speed, R.string.nav_live),
    NavItem(Screen.Tiles, Icons.Default.GridView, R.string.nav_tiles),
    NavItem(Screen.Battery, Icons.Default.ViewModule, R.string.nav_battery),
    NavItem(Screen.Charging, Icons.Default.BatteryChargingFull, R.string.nav_charging),
)
private val DETAILS = NavItem(Screen.Details, Icons.Default.List, R.string.nav_details)
private val MORE = listOf(
    NavItem(Screen.Connect, Icons.Default.Bluetooth, R.string.nav_connect),
    NavItem(Screen.Export, Icons.Default.SaveAlt, R.string.nav_export),
    NavItem(Screen.Settings, Icons.Default.Settings, R.string.nav_settings),
)

@Composable
fun AppNavGraph(vanName: String) {
    val navController = rememberNavController()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val bar = if (landscape) BAR_PORTRAIT + DETAILS else BAR_PORTRAIT
    val more = if (landscape) MORE else listOf(DETAILS) + MORE
    val state by Recorder.state.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val settings = androidx.compose.runtime.remember { io.github.ev80diag.settings.AppSettings(ctx) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Every switch between screens, by tap or automatic, goes through here. A plain navigate() would stack the
    // target on the current tab, and that tab would then restore it every time it is opened again.
    fun go(route: String) = navController.navigate(route) {
        popUpTo(navController.graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    val requested by NavRequests.route.collectAsState()
    LaunchedEffect(requested) {
        requested?.let { go(it); NavRequests.route.value = null }
    }

    // open Charging when a charge starts (setting)
    val sessionType = state.session?.type
    LaunchedEffect(sessionType) {
        val isCharge = sessionType == SessionType.CHARGE_AC || sessionType == SessionType.CHARGE_DC
        if (isCharge && settings.openChargingOnStart) go(Screen.Charging.route)
    }
    // after Connect: on to Live, or Charging if a charge is running (spec §5.1)
    LaunchedEffect(state.link) {
        if (state.link == LinkStatus.CONNECTED && navController.currentDestination?.route == Screen.Connect.route) {
            val charging = sessionType == SessionType.CHARGE_AC || sessionType == SessionType.CHARGE_DC
            go(if (charging) Screen.Charging.route else Screen.Live.route)
        }
    }
    val startRoute = androidx.compose.runtime.remember {
        if (Recorder.state.value.link == LinkStatus.DISCONNECTED) Screen.Connect.route else Screen.Live.route
    }

    // the state chip turns amber/red for the worst warning (lowest cell, insulation, spread), with icon + text
    val live = LiveUiBuilder.build(state, 0.0, null, LiveThresholds(settings.spreadWarnMv, settings.lowCellMv))
    val insulation = state.decoded["insulation_kohm"]?.takeIf { it < 10_000.0 }
        ?.let { WarningRules.insulation(it, settings.insulationLowKOhm.toDouble()) } ?: Level.OK
    val warning = if (live.stale) Level.OK to null else TopBarWarning.of(live, insulation)
    val gear = state.decoded["gear"]?.let { SignalFormat.gearLetter(it) }

    val vans by io.github.ev80diag.data.store.Ev80Database.get(ctx).profiles().all().collectAsState(emptyList())
    val activeVan by io.github.ev80diag.profile.ActiveProfile.flow(ctx).collectAsState()
    val snackbar = androidx.compose.runtime.remember { SnackbarHostState() }
    ProfileEventUi(state.profileEvent, vans, activeVan?.id, snackbar)
    var marking by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    if (marking) MarkDialog(onDismiss = { marking = false })

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopStatusBar(state, vanName, gear = gear, warning = warning, onVanClick = { go(Screen.Connect.route) },
            onMark = if (state.link == LinkStatus.CONNECTED) ({ marking = true }) else null) },
        bottomBar = {
            val destination = navController.currentBackStackEntryAsState().value?.destination
            fun selected(s: Screen) = destination?.hierarchy?.any { it.route == s.route } == true
            NavigationBar {
                bar.forEach { item ->
                    NavigationBarItem(
                        icon = { Icon(item.icon, contentDescription = stringResource(item.labelRes)) },
                        label = { Text(stringResource(item.labelRes)) },
                        selected = selected(item.screen),
                        onClick = { go(item.screen.route) },
                    )
                }
                var open by androidx.compose.runtime.remember { mutableStateOf(false) }
                NavigationBarItem(
                    icon = {
                        Box {
                            Icon(Icons.Default.MoreHoriz, contentDescription = stringResource(R.string.nav_more))
                            DropdownMenu(open, onDismissRequest = { open = false }) {
                                more.forEach { item ->
                                    DropdownMenuItem(text = { Text(stringResource(item.labelRes)) },
                                        leadingIcon = { Icon(item.icon, contentDescription = null) },
                                        onClick = { open = false; go(item.screen.route) })
                                }
                            }
                        }
                    },
                    label = { Text(stringResource(R.string.nav_more)) },
                    selected = more.any { selected(it.screen) },
                    onClick = { open = true },
                )
            }
        },
    ) { padding ->
        NavHost(navController, startDestination = startRoute, modifier = Modifier.padding(padding)) {
            composable(Screen.Live.route) { LiveScreen() }
            composable(Screen.Tiles.route) { TilesScreen() }
            composable(Screen.Battery.route) { BatteryScreen() }
            composable(Screen.Details.route) { DetailsScreen() }
            composable(Screen.Connect.route) { ConnectScreen(onEditVan = { id -> navController.navigate(Screen.VanEdit.of(id)) }) }
            composable(Screen.VanEdit.route) { entry ->
                VanEditScreen(entry.arguments?.getString("id")?.toLongOrNull() ?: 0L, onDone = { navController.popBackStack() })
            }
            composable(Screen.Charging.route) { ChargingScreen(onShare = { id -> scope.launch { shareSession(ctx, id, ExportFormat.BUNDLE) } }) }
            composable(Screen.Export.route) { ExportDebugScreen() }
            composable(Screen.Settings.route) { SettingsScreen() }
        }
    }
}
