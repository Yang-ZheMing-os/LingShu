package com.lingshu.feature.guide.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.lingshu.feature.chat.presentation.ChatScreen
import com.lingshu.feature.clonevoice.presentation.CloneVoiceScreen
import com.lingshu.feature.guide.presentation.CommunityPlaceholderScreen
import com.lingshu.feature.guide.presentation.ControlScreen
import com.lingshu.feature.guide.presentation.GuideScreen
import com.lingshu.feature.guide.presentation.MainScreen
import com.lingshu.feature.guide.presentation.PermissionQueueScreen
import com.lingshu.feature.guide.presentation.SettingsScreen
import com.lingshu.feature.guide.presentation.settings.ModelSettingsScreen
import com.lingshu.feature.mod.presentation.ModScreen
import com.lingshu.feature.rag.presentation.RagScreen
import com.lingshu.feature.wakeword.presentation.WakeWordScreen

sealed class GuideSection(val route: String) {
    object Guide : GuideSection("guide")
    object PermissionQueue : GuideSection("permission_queue")
    object Main : GuideSection("main")
    object Chat : GuideSection("chat")
    object Control : GuideSection("control")
    object Settings : GuideSection("settings")
    object SettingsModel : GuideSection("settings_model")
    object Rag : GuideSection("rag")
    object CloneVoice : GuideSection("clone_voice")
    object Mod : GuideSection("mod")
    object WakeWord : GuideSection("wake_word")
    object Community : GuideSection("community")
}

@Composable
fun GuideNavGraph(
    navController: NavHostController,
    startDestination: String = GuideSection.Guide.route
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(GuideSection.Guide.route) {
            GuideScreen(
                onGuideComplete = {
                    navController.navigate(GuideSection.PermissionQueue.route) {
                        popUpTo(GuideSection.Guide.route) { inclusive = true }
                    }
                }
            )
        }

        composable(GuideSection.PermissionQueue.route) {
            PermissionQueueScreen(
                onAllPermissionsHandled = {
                    navController.navigate(GuideSection.Main.route) {
                        popUpTo(GuideSection.PermissionQueue.route) { inclusive = true }
                    }
                }
            )
        }

        composable(GuideSection.Main.route) {
            MainScreen(
                onNavigateToModelSettings = {
                    navController.navigate(GuideSection.SettingsModel.route)
                },
                onNavigateToRag = {
                    navController.navigate(GuideSection.Rag.route)
                },
                onNavigateToCloneVoice = {
                    navController.navigate(GuideSection.CloneVoice.route)
                },
                onNavigateToMod = {
                    navController.navigate(GuideSection.Mod.route)
                },
                onNavigateToWakeWord = {
                    navController.navigate(GuideSection.WakeWord.route)
                },
                onNavigateToCommunity = {
                    navController.navigate(GuideSection.Community.route)
                }
            )
        }

        composable(GuideSection.Chat.route) {
            ChatScreen()
        }

        composable(GuideSection.Control.route) {
            ControlScreen()
        }

        composable(GuideSection.Settings.route) {
            SettingsScreen(
                onNavigateToModelSettings = {
                    navController.navigate(GuideSection.SettingsModel.route)
                },
                onNavigateToRag = {
                    navController.navigate(GuideSection.Rag.route)
                },
                onNavigateToCloneVoice = {
                    navController.navigate(GuideSection.CloneVoice.route)
                },
                onNavigateToMod = {
                    navController.navigate(GuideSection.Mod.route)
                },
                onNavigateToWakeWord = {
                    navController.navigate(GuideSection.WakeWord.route)
                },
                onNavigateToCommunity = {
                    navController.navigate(GuideSection.Community.route)
                }
            )
        }

        composable(GuideSection.SettingsModel.route) {
            ModelSettingsScreen(
                onBackClick = {
                    navController.popBackStack()
                }
            )
        }

        composable(GuideSection.Rag.route) {
            RagScreen()
        }

        composable(GuideSection.CloneVoice.route) {
            CloneVoiceScreen()
        }

        composable(GuideSection.Mod.route) {
            ModScreen()
        }

        composable(GuideSection.WakeWord.route) {
            WakeWordScreen()
        }

        composable(GuideSection.Community.route) {
            CommunityPlaceholderScreen()
        }
    }
}
