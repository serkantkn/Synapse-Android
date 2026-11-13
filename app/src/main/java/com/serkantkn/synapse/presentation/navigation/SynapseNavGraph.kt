package com.serkantkn.synapse.presentation.navigation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.serkantkn.synapse.presentation.screens.DeviceSelectionScreen
import com.serkantkn.synapse.presentation.screens.HomeScreen
import com.serkantkn.synapse.presentation.screens.NotificationFilterScreen
import com.serkantkn.synapse.presentation.screens.PairingScreen
import com.serkantkn.synapse.presentation.viewmodel.SynapseViewModel

private const val PUSH_MS = 380

/**
 * Root navigation graph for the Synapse app. Screens push and pop like iOS navigation:
 * the new screen slides in from the right while the old one drifts a third to the left.
 *
 * [demoMode] (debug builds only) fills the UI with fake state for screenshots.
 */
@Composable
fun SynapseNavGraph(demoMode: String? = null) {
    val navController = rememberNavController()
    val viewModel: SynapseViewModel = viewModel()

    LaunchedEffect(demoMode) {
        if (demoMode != null) viewModel.startDemo(demoMode)
    }

    NavHost(
        navController = navController,
        startDestination = Screen.DeviceSelection.route,
        enterTransition = { slideInHorizontally(tween(PUSH_MS, easing = FastOutSlowInEasing)) { it } },
        exitTransition = {
            slideOutHorizontally(tween(PUSH_MS, easing = FastOutSlowInEasing)) { -it / 3 } +
                fadeOut(tween(PUSH_MS), targetAlpha = 0.6f)
        },
        popEnterTransition = {
            slideInHorizontally(tween(PUSH_MS, easing = FastOutSlowInEasing)) { -it / 3 } +
                fadeIn(tween(PUSH_MS), initialAlpha = 0.6f)
        },
        popExitTransition = { slideOutHorizontally(tween(PUSH_MS, easing = FastOutSlowInEasing)) { it } }
    ) {
        // Step 1: Choose target platform (Mac or iOS)
        composable(Screen.DeviceSelection.route) {
            DeviceSelectionScreen(
                onSelectMac = {
                    viewModel.setTargetPlatform("macOS")
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.DeviceSelection.route) { inclusive = true }
                    }
                },
                onSelectiOS = {
                    viewModel.setTargetPlatform("iOS")
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.DeviceSelection.route) { inclusive = true }
                    }
                }
            )
        }

        // Step 2: Home (existing Mac flow, or iOS flow)
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
