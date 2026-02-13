package com.coderred.andclaw.ui.navigation

sealed class Screen(val route: String) {
    data object Setup : Screen("setup")
    data object Onboarding : Screen("onboarding")
    data object Dashboard : Screen("dashboard")
    data object Settings : Screen("settings")
}
