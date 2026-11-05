package io.github.ev80diag.ui.navigation

sealed class Screen(val route: String) {
    data object Live : Screen("live")
    data object Tiles : Screen("tiles")
    data object Battery : Screen("battery")
    data object Details : Screen("details")
    data object Connect : Screen("connect")
    data object Charging : Screen("charging")
    data object Export : Screen("export")
    data object Settings : Screen("settings")
    /** Add (id 0) or edit a van, opened from Connect. */
    data object VanEdit : Screen("van/{id}") { fun of(id: Long) = "van/$id" }
}
