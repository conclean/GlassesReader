package com.app.glassesreader.ui.theme

import androidx.compose.ui.graphics.Color

// 浅色主题颜色
val LightBackground = Color(0xFFEBF6FF)  // 最浅 - 圆角矩形背景色、次要按钮
val LightSecondary = Color(0xFFCEE5FF)   // 中等 - 次要按钮
val LightPrimary = Color(0xFF03629A)      // 最深 - 关键按钮、对勾框、开关按钮背景色
val LightSurfaceVariant = Color(0xFFF3F2F7)  // 灰色 - 主页面和设置模块的圆角矩形背景
val LightButtonBackground = Color(0xFFF3F2F7)  // 浅灰色 - 日间模式下设置按钮和返回按钮的圆形背景

// 深色主题颜色（基于浅色主题调整）
val DarkBackground = Color(0xFF1A3A4D)    // 深色背景
val DarkSecondary = Color(0xFF2A4A5D)     // 深色次要
val DarkPrimary = Color(0xFF4A9FD9)       // 深色主色（稍亮以便在深色背景下可见）
val DarkSurfaceVariant = Color(0xFF2A2A2C)  // 深色主题的 surfaceVariant
val DarkButtonBackground = Color(0xFF3A3A3C)  // 深色主题的按钮背景

// 兼容旧代码的颜色（保留以避免破坏现有代码）
val Blue80 = Color(0xFFB3E5FC)
val BlueGrey80 = Color(0xFF90CAF9)
val Teal80 = Color(0xFF80DEEA)

val Blue40 = Color(0xFF1565C0)
val BlueGrey40 = Color(0xFF1E88E5)
val Teal40 = Color(0xFF26C6DA)