package com.coderred.andclaw.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.coderred.andclaw.ui.screen.dashboard.DashboardScreen
import com.coderred.andclaw.ui.screen.onboarding.OnboardingScreen
import com.coderred.andclaw.ui.screen.settings.SettingsScreen
import com.coderred.andclaw.ui.screen.setup.SetupScreen

@Composable
fun AndClawNavGraph(
    navController: NavHostController,
    isSetupComplete: Boolean,
    isOnboardingComplete: Boolean,
    authCallbackUri: Uri? = null,
) {
    val startDestination = when {
        !isSetupComplete -> Screen.Setup.route
        !isOnboardingComplete -> Screen.Onboarding.route
        else -> Screen.Dashboard.route
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
    ) {
        composable(Screen.Setup.route) {
            SetupScreen(
                onSetupComplete = {
                    navController.navigate(Screen.Onboarding.route) {
                        popUpTo(Screen.Setup.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Onboarding.route) {
            OnboardingScreen(
                authCallbackUri = authCallbackUri,
                onOnboardingComplete = {
                    navController.navigate(Screen.Dashboard.route) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Dashboard.route) {
            DashboardScreen(
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
