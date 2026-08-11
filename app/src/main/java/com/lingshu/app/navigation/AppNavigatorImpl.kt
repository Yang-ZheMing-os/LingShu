package com.lingshu.app.navigation

import androidx.navigation.NavController
import com.lingshu.core.common.navigation.IAppNavigator
import com.lingshu.feature.guide.navigation.GuideSection
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppNavigatorImpl @Inject constructor() : IAppNavigator {

    private var navController: NavController? = null

    fun setNavController(controller: NavController) {
        navController = controller
    }

    override fun navigateToChat() {
        navController?.navigate(GuideSection.Chat.route) {
            launchSingleTop = true
        }
    }

    override fun navigateToControl() {
        navController?.navigate(GuideSection.Control.route) {
            launchSingleTop = true
        }
    }

    override fun navigateToSettings() {
        navController?.navigate(GuideSection.Settings.route) {
            launchSingleTop = true
        }
    }

    override fun navigateToGuide() {
        navController?.navigate(GuideSection.Guide.route) {
            popUpTo(0) { inclusive = true }
        }
    }

    override fun navigateToMain() {
        navController?.navigate(GuideSection.Main.route) {
            popUpTo(0) { inclusive = true }
        }
    }

    override fun navigateBack() {
        navController?.popBackStack()
    }

    fun navigateTo(route: String) {
        navController?.navigate(route)
    }

    fun popUpTo(route: String, inclusive: Boolean = false) {
        navController?.popBackStack(route, inclusive)
    }
}
