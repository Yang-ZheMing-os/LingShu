package com.lingshu.agent

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lingshu.agent.feature.chat.ChatScreen
import com.lingshu.agent.feature.community.CommunityScreen
import com.lingshu.agent.feature.control.ControlPanelScreen
import com.lingshu.agent.feature.health.HealthPanelScreen
import com.lingshu.agent.feature.settings.SettingsScreen

sealed class BottomNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    object Chat : BottomNavItem("chat", "对话", Icons.Filled.Chat)
    object Control : BottomNavItem("control", "控制", Icons.Filled.SmartToy)
    object Health : BottomNavItem("health", "健康", Icons.Filled.HealthAndSafety)
    object Community : BottomNavItem("community", "社区", Icons.Filled.Groups)
    object Settings : BottomNavItem("settings", "设置", Icons.Filled.Settings)
}

@Composable
fun Navigation() {
    val navController = rememberNavController()
    val items = listOf(
        BottomNavItem.Chat,
        BottomNavItem.Control,
        BottomNavItem.Health,
        BottomNavItem.Community,
        BottomNavItem.Settings
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                items.forEach { item ->
                    NavigationBarItem(
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                        selected = currentDestination?.hierarchy?.any { it.route == item.route } == true,
                        onClick = {
                            navController.navigate(item.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = BottomNavItem.Chat.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(BottomNavItem.Chat.route) { ChatScreen() }
            composable(BottomNavItem.Control.route) { ControlPanelScreen() }
            composable(BottomNavItem.Health.route) { HealthPanelScreen() }
            composable(BottomNavItem.Community.route) { CommunityScreen() }
            composable(BottomNavItem.Settings.route) { SettingsScreen() }
        }
    }
}
