package com.lingshu.feature.guide.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.material3.Scaffold
import com.lingshu.feature.chat.presentation.ChatScreen
import com.lingshu.feature.health.presentation.HealthScreen
import com.lingshu.feature.guide.navigation.GuideSection

@Composable
fun MainScreen(
    onNavigateToRag: () -> Unit = {},
    onNavigateToCloneVoice: () -> Unit = {},
    onNavigateToMod: () -> Unit = {},
    onNavigateToWakeWord: () -> Unit = {},
    onNavigateToCommunity: () -> Unit = {},
    onNavigateToHealth: () -> Unit = {},
    onNavigateToMemory: () -> Unit = {},
    onNavigateToPersona: () -> Unit = {},
    onNavigateToProactive: () -> Unit = {},
    onNavigateToSceneManager: () -> Unit = {},
    onNavigateToModelSettings: () -> Unit = {}
) {
    val items = listOf(
        BottomNavItem.Chat,
        BottomNavItem.Health,
        BottomNavItem.Settings
    )

    val innerNavController = rememberNavController()

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier.weight(1f)
        ) {
            NavHost(
                navController = innerNavController,
                startDestination = BottomNavItem.Chat.route
            ) {
                composable(BottomNavItem.Chat.route) {
                    ChatScreen()
                }
                composable(BottomNavItem.Health.route) {
                    HealthScreen(onNavigateToCommunity = onNavigateToCommunity)
                }
                composable(BottomNavItem.Settings.route) {
                    SettingsScreen(
                        onNavigateToModelSettings = onNavigateToModelSettings,
                        onNavigateToRag = onNavigateToRag,
                        onNavigateToCloneVoice = onNavigateToCloneVoice,
                        onNavigateToMod = onNavigateToMod,
                        onNavigateToWakeWord = onNavigateToWakeWord,
                        onNavigateToCommunity = onNavigateToCommunity,
                        onNavigateToHealth = onNavigateToHealth,
                        onNavigateToMemory = onNavigateToMemory,
                        onNavigateToPersona = onNavigateToPersona,
                        onNavigateToProactive = onNavigateToProactive,
                        onNavigateToSceneManager = onNavigateToSceneManager
                    )
                }
            }
        }

        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            val navBackStackEntry by innerNavController.currentBackStackEntryAsState()
            val currentDestination = navBackStackEntry?.destination

            items.forEach { item ->
                NavigationBarItem(
                    icon = {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label
                        )
                    },
                    label = {
                        Text(text = item.label)
                    },
                    selected = currentDestination?.hierarchy?.any { it.route == item.route } == true,
                    onClick = {
                        innerNavController.navigate(item.route) {
                            popUpTo(innerNavController.graph.findStartDestination().id) {
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
}

sealed class BottomNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    object Chat : BottomNavItem(
        route = "main_chat",
        label = "\u804A\u5929",
        icon = Icons.Default.ChatBubble
    )

    object Health : BottomNavItem(
        route = "main_health",
        label = "\u5065\u5EB7",
        icon = Icons.Default.Favorite
    )

    object Settings : BottomNavItem(
        route = "main_settings",
        label = "\u8BBE\u7F6E",
        icon = Icons.Default.Settings
    )
}
