package com.app.glassesreader.ui.screens

import CustomIconButton
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.app.glassesreader.ui.theme.DarkButtonBackground
import com.app.glassesreader.ui.theme.LightButtonBackground
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.app.glassesreader.data.TextPreset
import com.app.glassesreader.ui.components.BrightnessControl
import com.app.glassesreader.ui.components.CreatePresetDialog
import com.app.glassesreader.ui.components.EditPresetDialog
import com.app.glassesreader.ui.components.PresetTabRow
import com.app.glassesreader.ui.components.ServiceFab
import com.app.glassesreader.ui.components.StatusBanner
import com.app.glassesreader.ui.components.TextProcessingControls
import com.app.glassesreader.ui.components.TextSizeControl
import com.app.glassesreader.ui.model.MainUiState
import com.app.glassesreader.ui.navigation.Screen

/**
 * 主屏幕组件
 * 使用 Navigation Compose 进行页面导航
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    uiState: MainUiState,
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
    onCheckUpdate: () -> Unit,
    onSettingChanged: () -> Unit = {}
) {
    val navController = rememberNavController()

    Scaffold(
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
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    uiState = uiState,
                    onBrightnessChange = onBrightnessChange,
                    onShowMessage = onShowMessage,
                    presets = uiState.presets,
                    currentPresetId = uiState.currentPresetId,
                    onPresetSelected = uiState.onPresetSelected,
                    onPresetLongPress = uiState.onPresetLongPress,
                    onCreatePreset = uiState.onCreatePreset,
                    onRenamePreset = uiState.onRenamePreset,
                    onDeletePreset = uiState.onDeletePreset,
                    onSettingChanged = onSettingChanged,
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) }
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    uiState = uiState,
                    currentVersion = currentVersion,
                    checkingUpdate = checkingUpdate,
                    onNavigateBack = { navController.popBackStack() },
                    onRequestOverlay = onRequestOverlay,
                    onRequestAccessibility = onRequestAccessibility,
                    onRequestNotification = onRequestNotification,
                    onRequestSdkPermissions = onRequestSdkPermissions,
                    onOpenDeviceScan = onOpenDeviceScan,
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
 * 主页内容
 * 显示设置作为主页，包含状态提示横幅
 */
@Composable
private fun HomeScreen(
    uiState: MainUiState,
    onBrightnessChange: (Int) -> Unit,
    onShowMessage: (String) -> Unit,
    presets: List<TextPreset>,
    currentPresetId: String?,
    onPresetSelected: (String) -> Unit,
    onPresetLongPress: (TextPreset) -> Unit,
    onCreatePreset: (String) -> Unit,
    onRenamePreset: (String, String) -> Unit,
    onDeletePreset: (String) -> Unit,
    onSettingChanged: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingPreset by remember { mutableStateOf<TextPreset?>(null) }
    
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // 页面标题和设置按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "GlassesReader",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            CustomIconButton(
                onClick = onNavigateToSettings,
                size = 56.dp,
                containerColor = if (uiState.isDarkTheme) DarkButtonBackground else LightButtonBackground,
                contentColor = if (uiState.isDarkTheme) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
            )  {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "设置",
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        
        // 状态提示横幅 - 显示未完成的权限或未连接设备
        val hasUncompletedPermissions = !uiState.overlayGranted || 
            !uiState.accessibilityGranted || 
            !uiState.sdkPermissionsGranted ||
            (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU && !uiState.notificationGranted)
        
        if (hasUncompletedPermissions) {
            StatusBanner(
                title = "需要完成权限设置",
                message = "点击右上角设置按钮完成权限授权，以便正常使用应用。",
                onClick = onNavigateToSettings
            )
        } else if (!uiState.glassesConnected) {
            StatusBanner(
                title = "未连接智能眼镜",
                message = "点击右上角设置按钮连接智能眼镜，以便同步文字到眼镜。",
                onClick = onNavigateToSettings
            )
        }

        // 预设标签栏
        PresetTabRow(
            presets = presets,
            currentPresetId = currentPresetId,
            onPresetSelected = { presetId ->
                onPresetSelected(presetId)
                onShowMessage("已切换到：${presets.find { it.id == presetId }?.name ?: ""}")
            },
            onPresetLongPress = { preset ->
                editingPreset = preset
                onPresetLongPress(preset)
            },
            onCreateNew = {
                showCreateDialog = true
            }
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

        TextSizeControl(
            currentPresetId = currentPresetId,
            onSettingChanged = onSettingChanged
        )
        TextProcessingControls(
            currentPresetId = currentPresetId,
            onSettingChanged = onSettingChanged
        )
    }
    
    // 新建预设对话框
    if (showCreateDialog) {
        CreatePresetDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name ->
                onCreatePreset(name)
                showCreateDialog = false
            }
        )
    }
    
    // 编辑预设对话框
    editingPreset?.let { preset ->
        EditPresetDialog(
            preset = preset,
            canDelete = presets.size > 1,
            onDismiss = { editingPreset = null },
            onRename = { newName ->
                onRenamePreset(preset.id, newName)
            },
            onDelete = {
                onDeletePreset(preset.id)
            }
        )
    }
}

