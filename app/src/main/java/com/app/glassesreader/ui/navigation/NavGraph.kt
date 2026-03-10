package com.app.glassesreader.ui.navigation

/**
 * 导航路由定义
 */
sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Settings : Screen("settings")
}
