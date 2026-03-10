package com.app.glassesreader.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,                    // 关键按钮、开关按钮背景色
    onPrimary = Color.White,                  // 关键按钮上的文字颜色
    primaryContainer = DarkSecondary,         // 次要按钮背景色
    onPrimaryContainer = Color.White,         // 次要按钮上的文字颜色
    secondaryContainer = DarkBackground,      // 圆角矩形背景色
    onSecondaryContainer = Color.White,      // 圆角矩形上的文字颜色
    surfaceVariant = DarkSurfaceVariant,     // 主页面和设置模块的圆角矩形背景
    onSurfaceVariant = Color.White,          // surfaceVariant 上的文字颜色
    secondary = BlueGrey80,
    tertiary = Teal80
)

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,                  // 关键按钮、对勾框、开关按钮背景色 (#03629A)
    onPrimary = Color.White,                 // 关键按钮上的文字颜色
    primaryContainer = LightSecondary,       // 次要按钮背景色 (#CEE5FF)
    onPrimaryContainer = LightPrimary,       // 次要按钮上的文字颜色
    secondaryContainer = LightBackground,   // 圆角矩形背景色 (#EBF6FF)
    onSecondaryContainer = LightPrimary,    // 圆角矩形上的文字颜色
    surfaceVariant = LightSurfaceVariant,   // 主页面和设置模块的圆角矩形背景 (#F9F9FB)
    onSurfaceVariant = Color(0xFF1C1B1F),   // surfaceVariant 上的文字颜色
    secondary = BlueGrey40,
    tertiary = Teal40
)

@Composable
fun GlassesReaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}