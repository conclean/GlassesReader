package com.app.glassesreader.ui.screens

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import CustomIconButton
import androidx.compose.material.icons.filled.Check
import com.app.glassesreader.ui.theme.DarkButtonBackground
import com.app.glassesreader.ui.theme.LightButtonBackground
import com.app.glassesreader.ui.components.SimplePermissionItem

/**
 * 设置页面组件
 * 包含权限引导、设备连接、应用设置三个部分
 */
@Composable
fun SettingsScreen(
    uiState: com.app.glassesreader.ui.model.MainUiState,
    currentVersion: String,
    checkingUpdate: Boolean,
    onNavigateBack: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onRequestNotification: () -> Unit,
    onRequestSdkPermissions: () -> Unit,
    onOpenDeviceScan: () -> Unit,
    onToggleOverlay: (Boolean) -> Unit,
    onThemeChange: (Boolean) -> Unit,
    onShowMessage: (String) -> Unit,
    onCheckUpdate: () -> Unit,
    /** 标题栏「AR截图」 */
    onArScreenshot: () -> Unit,
    /** 标题栏「AR录屏」 */
    onArScreenRecord: () -> Unit,
    /** 弹窗确认后执行：见 [sdk/doc/控制与监听设备状态.md] notifyGlassReboot */
    onConfirmRebootGlasses: () -> Unit
) {
    var showRebootConfirmDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        // 页面标题和返回按钮；右侧胶囊文字按钮与「设置」同一行对齐；AR 截图先连 Wi‑Fi 时在整行下方显示细进度条
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CustomIconButton(
                    onClick = onNavigateBack,
                    size = 56.dp,
                    containerColor = if (uiState.isDarkTheme) {
                        DarkButtonBackground
                    } else {
                        LightButtonBackground
                    },
                    contentColor = if (uiState.isDarkTheme) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "返回",
                        modifier = Modifier.size(24.dp),
                        tint = if (uiState.isDarkTheme) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
                Text(
                    text = "设置",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp)
                )
                Spacer(modifier = Modifier.weight(1f))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SettingsCapsuleTextButton(
                        text = "AR截图",
                        isDarkTheme = uiState.isDarkTheme,
                        enabled = !uiState.arScreenshotWifiPreparing && !uiState.arVideoSyncInProgress,
                        onClick = onArScreenshot
                    )
                    SettingsCapsuleTextButton(
                        text = "AR录屏",
                        isDarkTheme = uiState.isDarkTheme,
                        enabled = !uiState.arScreenshotWifiPreparing && !uiState.arVideoSyncInProgress,
                        onClick = onArScreenRecord
                    )
                }
            }
            if (uiState.arScreenshotWifiPreparing) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "WiFi连接中",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = " ${uiState.arScreenshotWifiCountdownSec}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        
        // 权限引导部分
        PermissionSetupSection(
            uiState = uiState,
            onRequestOverlay = onRequestOverlay,
            onRequestAccessibility = onRequestAccessibility,
            onRequestNotification = onRequestNotification,
            onRequestSdkPermissions = onRequestSdkPermissions
        )

        // 设备连接部分
        DeviceConnectionSection(
            uiState = uiState,
            onOpenDeviceScan = onOpenDeviceScan,
            onRebootGlassesClick = { showRebootConfirmDialog = true }
        )

        // 应用设置部分
        AppSettingsSection(
            uiState = uiState,
            currentVersion = currentVersion,
            checkingUpdate = checkingUpdate,
            onToggleOverlay = onToggleOverlay,
            onThemeChange = onThemeChange,
            onShowMessage = onShowMessage,
            onCheckUpdate = onCheckUpdate
        )
    }

    if (showRebootConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showRebootConfirmDialog = false },
            title = { Text("确认重启眼镜") },
            text = { Text("是否确认重启眼镜？重启后设备将短暂断开连接。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRebootConfirmDialog = false
                        onConfirmRebootGlasses()
                    }
                ) {
                    Text("确认")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRebootConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun SettingsCapsuleTextButton(
    text: String,
    isDarkTheme: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    val bg = if (isDarkTheme) DarkButtonBackground else LightButtonBackground
    val fg =
        if (isDarkTheme) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Surface(
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        color = bg,
        shadowElevation = 0.dp
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = fg.copy(alpha = if (enabled) 1f else 0.45f),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

/**
 * 权限引导部分
 */
@Composable
private fun PermissionSetupSection(
    uiState: com.app.glassesreader.ui.model.MainUiState,
    onRequestOverlay: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onRequestNotification: () -> Unit,
    onRequestSdkPermissions: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "权限引导",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium
        ) {
            Column(
                // 不额外设置内边距，让每一行和分隔线在圆角矩形内左右、上下都顶满
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                // 第一行：悬浮窗权限
                SimplePermissionItem(
                    title = "悬浮窗权限",
                    isCompleted = uiState.overlayGranted,
                    onClick = onRequestOverlay
                )
                Divider(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f),
                    thickness = 0.5.dp
                )

                // 第二行：无障碍服务
                SimplePermissionItem(
                    title = "无障碍服务",
                    isCompleted = uiState.accessibilityGranted,
                    onClick = onRequestAccessibility
                )

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Divider(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f),
                        thickness = 0.5.dp
                    )

                    SimplePermissionItem(
                        title = "通知权限",
                        isCompleted = uiState.notificationGranted,
                        onClick = onRequestNotification
                    )
                }

                Divider(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f),
                    thickness = 0.5.dp
                )

                // 最后一行：蓝牙与定位权限
                SimplePermissionItem(
                    title = "蓝牙与定位权限",
                    isCompleted = uiState.sdkPermissionsGranted,
                    onClick = onRequestSdkPermissions
                )
            }
        }
    }
}

/**
 * 设备连接部分
 */
@Composable
private fun DeviceConnectionSection(
    uiState: com.app.glassesreader.ui.model.MainUiState,
    onOpenDeviceScan: () -> Unit,
    onRebootGlassesClick: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "设备连接",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                // 顶部说明文案
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "若看到连接状态显示未连接，请确保智能眼镜已在系统蓝牙完成配对，并断开官方应用后重新尝试。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 细线分隔说明和下半部分点击区域
                Divider(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f),
                    thickness = 0.5.dp
                )

                if (uiState.deviceAutoReconnectInProgress) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "正在尝试自动重连…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }

                // 下半部分：复用 SimplePermissionItem，风格与权限引导完全一致
                SimplePermissionItem(
                    title = if (uiState.glassesConnected) "眼镜已连接" else "眼镜未配对本应用",
                    // 已连接视为完成，展示灰字 + 蓝色对勾；未配对视为未完成，展示红字 + 红色箭头
                    isCompleted = uiState.glassesConnected,
                    onClick = onOpenDeviceScan,
                    clickEnabled = !uiState.deviceAutoReconnectInProgress
                )
                Divider(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f),
                    thickness = 0.5.dp
                )
                TextButton(
                    onClick = onRebootGlassesClick,
                    enabled = uiState.glassesConnected,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("重启眼镜")
                }
            }
        }
    }
}

/**
 * 应用设置部分
 */
@Composable
private fun AppSettingsSection(
    uiState: com.app.glassesreader.ui.model.MainUiState,
    currentVersion: String,
    checkingUpdate: Boolean,
    onToggleOverlay: (Boolean) -> Unit,
    onThemeChange: (Boolean) -> Unit,
    onShowMessage: (String) -> Unit,
    onCheckUpdate: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "应用设置",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
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
                        .fillMaxWidth()
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
                            text = "悬浮窗开关",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
