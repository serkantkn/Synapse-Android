package com.serkantkn.synapse.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.serkantkn.synapse.presentation.screens.HomeScreen
import com.serkantkn.synapse.presentation.screens.NotificationFilterScreen
import com.serkantkn.synapse.presentation.screens.PairingScreen
import com.serkantkn.synapse.presentation.viewmodel.SynapseViewModel

/**
 * Root navigation graph for the Synapse app.
 */
@Composable
fun SynapseNavGraph() {
    val navController = rememberNavController()
    val viewModel: SynapseViewModel = viewModel()

    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                viewModel = viewModel,
                onNavigateToPairing = {
                    navController.navigate(Screen.Pairing.route)
                },
                onNavigateToNotificationFilter = {
                    navController.navigate(Screen.NotificationFilter.route)
                }
            )
        }
        composable(Screen.Pairing.route) {
            PairingScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.NotificationFilter.route) {
            NotificationFilterScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
