package com.lingshu.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.rememberNavController
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import com.lingshu.app.navigation.AppNavigatorImpl
import com.lingshu.core.common.navigation.IAppNavigator
import com.lingshu.core.data.datastore.AppPreferences
import com.lingshu.feature.guide.navigation.GuideNavGraph
import com.lingshu.feature.guide.navigation.GuideSection
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first

@Composable
fun App() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val systemUiController = rememberSystemUiController()
    val darkTheme = isSystemInDarkTheme()

    val appNavigator = remember {
        EntryPointAccessors.fromApplication(
            context,
            AppNavigatorEntryPoint::class.java
        ).appNavigator()
    }

    LaunchedEffect(navController) {
        (appNavigator as? AppNavigatorImpl)?.setNavController(navController)
    }

    LaunchedEffect(darkTheme, systemUiController) {
        systemUiController.setStatusBarColor(
            color = Color.Transparent,
            darkIcons = false
        )
        systemUiController.setNavigationBarColor(
            color = Color.Transparent,
            darkIcons = false
        )
    }

    val appPreferences = remember { AppPreferences(context) }
    var startDestination by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(appPreferences) {
        val isFirstLaunch = appPreferences.isFirstLaunch.first()
        startDestination = if (isFirstLaunch) {
            GuideSection.Guide.route
        } else {
            GuideSection.Main.route
        }
    }

    startDestination?.let { destination ->
        GuideNavGraph(
            navController = navController,
            startDestination = destination
        )
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppNavigatorEntryPoint {
    fun appNavigator(): IAppNavigator
}
