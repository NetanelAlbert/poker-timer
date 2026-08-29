package com.netanelalbert.pokertimer.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

private const val ROUTE_TIMER = "timer"
private const val ROUTE_LEVELS = "levels"
private const val ROUTE_SETTINGS = "settings"

@Composable
fun PokerTimerNavHost(viewModel: PokerTimerViewModel) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = ROUTE_TIMER) {
        composable(ROUTE_TIMER) {
            TimerScreen(
                viewModel = viewModel,
                onOpenLevels = { navController.navigate(ROUTE_LEVELS) },
                onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
            )
        }
        composable(ROUTE_LEVELS) {
            LevelsScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
        composable(ROUTE_SETTINGS) {
            SettingsScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
    }
}
