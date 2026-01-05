package com.app.glassesreader.ui.screens

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.app.glassesreader.ui.components.BrightnessControl
import com.app.glassesreader.ui.components.ServiceFab
import com.app.glassesreader.ui.components.StatusListItem
import com.app.glassesreader.ui.components.TextProcessingControls
import com.app.glassesreader.ui.components.TextSizeControl
import com.app.glassesreader.ui.model.MainTab
import com.app.glassesreader.ui.model.MainUiState

/**
 * 主屏幕组件
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    uiState: MainUiState,
    defaultTab: MainTab,
    currentVersion: String,
    checkingUpdate: Boolean,
    onRequestOverlay: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onRequestNotification: () -> Unit,
    onRequestSdkPermissions: () -> Unit,
    onOpenDeviceScan: () -> Unit,
    onToggleService: () -> Unit,
    onOverlaySettingChange: (Boolean) -> Unit,
    onThemeChange: (Boolean) -> Unit,
    onShowMessage: (String) -> Unit,
    onBrightnessChange: (Int) -> Unit,
    onCheckUpdate: () -> Unit
) {
    val tabs = remember { MainTab.values().toList() }
    var selectedTab by rememberSaveable { mutableStateOf(defaultTab) }

    LaunchedEffect(defaultTab) {
        selectedTab = defaultTab
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(text = "GlassesReader") },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors()
            )
        },
        floatingActionButton = {
            ServiceFab(
                canToggle = uiState.canToggleReader,
                readerEnabled = uiState.readerEnabled,
                overlayGranted = uiState.overlayGranted,
                onToggle = onToggleService,
                onShowMessage = onShowMessage,
                disabledMessage = uiState.toggleReasons.joinToString("、")
            )
        },
        floatingActionButtonPosition = FabPosition.End
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            TabRow(selectedTabIndex = selectedTab.ordinal) {
                tabs.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = { Text(tab.title) }
                    )
                }
            }

            when (selectedTab) {
                MainTab.SETUP -> SetupTabContent(
                    uiState = uiState,
                    modifier = Modifier.fillMaxSize(),
                    onRequestOverlay = onRequestOverlay,
                    onRequestAccessibility = onRequestAccessibility,
                    onRequestNotification = onRequestNotification,
                    onRequestSdkPermissions = onRequestSdkPermissions
                )

                MainTab.CONNECT -> ConnectionTabContent(
                    uiState = uiState,
                    modifier = Modifier.fillMaxSize(),
                    onOpenDeviceScan = onOpenDeviceScan
                )

                MainTab.DISPLAY -> DisplayTabContent(
                    uiState = uiState,
                    modifier = Modifier.fillMaxSize(),
                    onBrightnessChange = onBrightnessChange
                )

                MainTab.SETTINGS -> SettingsTabContent(
                    uiState = uiState,
                    modifier = Modifier.fillMaxSize(),
                    currentVersion = currentVersion,
                    checkingUpdate = checkingUpdate,
                    onToggleOverlay = onOverlaySettingChange,
                    onThemeChange = onThemeChange,
                    onShowMessage = onShowMessage,
                    onCheckUpdate = onCheckUpdate
                )
            }
        }
    }
}

/**
 * 权限引导标签页内容
 */
@Composable
private fun SetupTabContent(
    uiState: MainUiState,
    modifier: Modifier = Modifier,
    onRequestOverlay: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onRequestNotification: () -> Unit,
    onRequestSdkPermissions: () -> Unit
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            text = "准备使用",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "完成以下步骤后，应用会自动启动读屏服务并同步文字到眼镜。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        StatusListItem(
            title = "悬浮窗权限",
            isCompleted = uiState.overlayGranted,
            description = "用于在手机端显示浮窗和读屏开关。",
            actionLabel = "前往授权",
            onAction = if (uiState.overlayGranted) null else onRequestOverlay
        )
        StatusListItem(
            title = "无障碍服务",
            isCompleted = uiState.accessibilityGranted,
            description = "读取屏幕文字并同步到智能眼镜。",
            actionLabel = "打开设置",
            onAction = if (uiState.accessibilityGranted) null else onRequestAccessibility
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            StatusListItem(
                title = "通知权限",
                isCompleted = uiState.notificationGranted,
                description = "用于显示前台服务通知，确保读屏稳定运行。",
                actionLabel = "授权通知",
                onAction = if (uiState.notificationGranted) null else onRequestNotification
            )
        }
        StatusListItem(
            title = "蓝牙与定位权限",
            isCompleted = uiState.sdkPermissionsGranted,
            description = "确保可以扫描并连接智能眼镜。",
            actionLabel = "授权权限",
            onAction = if (uiState.sdkPermissionsGranted) null else onRequestSdkPermissions
        )
    }
}

/**
 * 设备连接标签页内容
 */
@Composable
private fun ConnectionTabContent(
    uiState: MainUiState,
    modifier: Modifier = Modifier,
    onOpenDeviceScan: () -> Unit
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            text = "设备连接",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "若看到连接状态显示未连接，请确保智能眼镜已在系统蓝牙完成配对，并断开官方应用后重新尝试。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        StatusListItem(
            title = if (uiState.glassesConnected) "眼镜已连接" else "眼镜未配对本应用",
            isCompleted = uiState.glassesConnected,
            description = if (uiState.glassesConnected) {
                "当前已与眼镜建立连接，可在显示设置中调整样式。"
            } else {
                "点击下方按钮扫描附近设备，选择目标眼镜进行连接。"
            },
            actionLabel = if (uiState.sdkPermissionsGranted) {
                if (uiState.glassesConnected) "重新扫描" else "扫描设备"
            } else null,
            onAction = if (uiState.sdkPermissionsGranted) onOpenDeviceScan else null
        )
    }
}

/**
 * 显示设置标签页内容
 */
@Composable
private fun DisplayTabContent(
    uiState: MainUiState,
    modifier: Modifier = Modifier,
    onBrightnessChange: (Int) -> Unit
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            text = "显示设置",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        if (!uiState.glassesConnected) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    text = "尚未连接智能眼镜，设置将暂存，待连接后自动生效。",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        BrightnessControl(
            brightness = uiState.brightness,
            enabled = uiState.glassesConnected,
            onBrightnessChange = onBrightnessChange
        )

        TextSizeControl()
        TextProcessingControls()
    }
}

/**
 * 应用设置标签页内容
 */
@Composable
private fun SettingsTabContent(
    uiState: MainUiState,
    modifier: Modifier = Modifier,
    currentVersion: String,
    checkingUpdate: Boolean,
    onToggleOverlay: (Boolean) -> Unit,
    onThemeChange: (Boolean) -> Unit,
    onShowMessage: (String) -> Unit,
    onCheckUpdate: () -> Unit
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            text = "应用设置",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val switchEnabled = uiState.overlayGranted && uiState.canToggleReader
                val disabledMessage = uiState.toggleReasons.joinToString("、").ifBlank { "请完成前置步骤" }
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .let {
                            if (!switchEnabled) {
                                it.clickable { onShowMessage(disabledMessage) }
                            } else {
                                it
                            }
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "悬浮窗开关按钮",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "控制手机端浮窗按钮的显示。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = uiState.overlayUIEnabled,
                        onCheckedChange = {
                            if (switchEnabled) {
                                onToggleOverlay(it)
                            } else {
                                onShowMessage(disabledMessage)
                            }
                        },
                        enabled = switchEnabled
                    )
                }
                if (!uiState.overlayGranted) {
                    Text(
                        text = "尚未授权悬浮窗权限，无法显示浮窗。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Text(
                        text = if (uiState.overlayUIEnabled) {
                            "关闭后将隐藏浮窗按钮，读屏状态保持不变。"
                        } else {
                            "开启后显示浮窗按钮，可随时控制识别服务。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                // 主题切换开关
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "夜间模式",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (uiState.isDarkTheme) {
                                "当前为夜间模式（深色主题）"
                            } else {
                                "当前为日间模式（浅色主题）"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = uiState.isDarkTheme,
                        onCheckedChange = onThemeChange
                    )
                }
                
                // 版本信息和检查更新
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "版本信息",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "当前版本：v$currentVersion",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = onCheckUpdate,
                        enabled = !checkingUpdate,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (checkingUpdate) "检查更新中..." else "检查更新",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

