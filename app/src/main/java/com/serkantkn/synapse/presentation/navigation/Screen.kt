package com.serkantkn.synapse.presentation.navigation

/**
 * Navigation route definitions for the Synapse app.
 */
sealed class Screen(val route: String) {
    data object DeviceSelection : Screen("device_selection")
    data object Home : Screen("home")
    data object Pairing : Screen("pairing")
    data object NotificationFilter : Screen("notification_filter")
}
