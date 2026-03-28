package com.app.glassesreader.ui.model

import com.app.glassesreader.data.TextPreset

/**
 * 主界面标签页枚举
 */
enum class MainTab(val title: String) {
    SETUP("权限引导"),
    CONNECT("设备连接"),
    DISPLAY("显示设置"),
    SETTINGS("应用设置")
}

/**
 * 主界面 UI 状态数据类
 */
data class MainUiState(
    val overlayGranted: Boolean,
    val accessibilityGranted: Boolean,
    val notificationGranted: Boolean,
    val sdkPermissionsGranted: Boolean,
    val overlayUIEnabled: Boolean,
    val overlayServiceRunning: Boolean,
    val readerEnabled: Boolean,
    val glassesConnected: Boolean,
    val brightness: Int,
    val customViewRunning: Boolean,
    val toggleReasons: List<String>,
    val hasSavedConnectionInfo: Boolean = false,
    /** 设备连接行触发的蓝牙自动重连进行中（用于展示「正在尝试自动重连」） */
    val deviceAutoReconnectInProgress: Boolean = false,
    /** AR 截图：先建立 Wi‑Fi P2P，再出现快门浮窗（标题栏下展示细进度条） */
    val arScreenshotWifiPreparing: Boolean = false,
    /** AR 截图 Wi‑Fi 等待阶段剩余秒数（60→0，仅数字展示） */
    val arScreenshotWifiCountdownSec: Int = 0,
    val isDarkTheme: Boolean = false,
    val presets: List<TextPreset> = emptyList(),
    val currentPresetId: String? = null,
    val onPresetSelected: (String) -> Unit = {},
    val onPresetLongPress: (TextPreset) -> Unit = {},
    val onCreatePreset: (String) -> Unit = {},
    val onRenamePreset: (String, String) -> Unit = { _, _ -> },
    val onDeletePreset: (String) -> Unit = {}
) {
    val canToggleReader: Boolean
        get() = toggleReasons.isEmpty()
}

