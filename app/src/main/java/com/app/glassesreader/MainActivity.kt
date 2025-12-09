package com.app.glassesreader

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.text.KeyboardOptions
import androidx.core.content.ContextCompat
import com.app.glassesreader.accessibility.service.ScreenTextService
import com.app.glassesreader.sdk.CxrConnectionManager
import com.app.glassesreader.sdk.CxrCustomViewManager
import com.app.glassesreader.service.overlay.TextOverlayService
import com.app.glassesreader.ui.screens.DeviceScanActivity
import com.app.glassesreader.ui.theme.GlassesReaderTheme
import com.app.glassesreader.update.UpdateChecker
import com.app.glassesreader.update.UpdateResult
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.utils.ValueUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val MIN_BRIGHTNESS = 0
private const val MAX_BRIGHTNESS = 15
private const val DEFAULT_BRIGHTNESS = 8

/**
 * MainActivity 用於引導使用者授權並啟動浮窗服務。
 */
class MainActivity : ComponentActivity() {

    private var overlayPermissionGranted by mutableStateOf(false)
    private var accessibilityEnabled by mutableStateOf(false)
    private var notificationGranted by mutableStateOf(true)
    private var sdkPermissionsGranted by mutableStateOf(false)
    private var serviceRunning by mutableStateOf(false)
    private var readerEnabled by mutableStateOf(false)
    private var glassesConnected by mutableStateOf(false)
    private var defaultTab by mutableStateOf(MainTab.SETUP)
    private var userDisabledService by mutableStateOf(false)
    private var overlayUIEnabled by mutableStateOf(false)
    private var showAutoReconnectFailedDialog by mutableStateOf(false)
    private var isDarkTheme by mutableStateOf(false)
    // 标记用户是否正在主动操作，用于防止在用户开启时误触发自动关闭
    private var isUserActivelyEnabling = false
    // 更新检查相关状态
    private var currentVersion by mutableStateOf("")
    private var checkingUpdate by mutableStateOf(false)
    private var updateResult by mutableStateOf<UpdateResult?>(null)
    private var showUpdateDialog by mutableStateOf(false)
    private lateinit var appPrefs: SharedPreferences
    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                KEY_READER_ENABLED -> {
                    val enabled = appPrefs.getBoolean(KEY_READER_ENABLED, false)
                    readerEnabled = enabled
                }
            }
        }
    private lateinit var requiredSdkPermissions: Array<String>
    private val connectionManager = CxrConnectionManager.getInstance()
    private var glassBrightness by mutableStateOf(DEFAULT_BRIGHTNESS)
    private var brightnessSynced = false

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshPermissionStates()
        }

    private val accessibilityLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshPermissionStates()
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationGranted = granted || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        }

    private val sdkPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            sdkPermissionsGranted = areSdkPermissionsGranted()
        }

    private val deviceScanLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // 从扫描页面返回后，刷新连接状态
            checkConnectionStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appPrefs = getSharedPreferences(PREF_APP_SETTINGS, Context.MODE_PRIVATE)
        appPrefs.registerOnSharedPreferenceChangeListener(prefsListener)
        overlayUIEnabled = appPrefs.getBoolean(KEY_OVERLAY_ENABLED, false)
        userDisabledService = appPrefs.getBoolean(KEY_USER_DISABLED_READER, false)
        readerEnabled = appPrefs.getBoolean(KEY_READER_ENABLED, false)
        glassBrightness = appPrefs.getInt(KEY_LAST_BRIGHTNESS, DEFAULT_BRIGHTNESS)
            .coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS)
        // 读取主题设置，默认跟随系统
        val systemDarkMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        isDarkTheme = appPrefs.getBoolean(KEY_DARK_THEME, systemDarkMode)
        
        // 初始化版本号（使用 try-catch 确保即使失败也不会崩溃）
        try {
            currentVersion = UpdateChecker.getCurrentVersion(this)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to get current version", e)
            // 如果获取失败，使用 build.gradle.kts 中的默认版本号
            currentVersion = "1.1.5"
        }
        
        // 初始化连接管理器并尝试自动重连
        connectionManager.init(this)
        
        // 延迟自动重连，等待权限检查完成
        // 注意：重连时不需要 initBluetooth，直接使用 connectBluetooth
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            connectionManager.autoReconnect(object : CxrConnectionManager.ConnectionCallback {
                override fun onConnected() {
                    Log.d(LOG_TAG, "Auto reconnect succeeded")
                    checkConnectionStatus()
                }
                
                override fun onDisconnected() {
                    Log.d(LOG_TAG, "Auto reconnect disconnected")
                    checkConnectionStatus()
                }
                
                override fun onFailed(errorCode: com.rokid.cxr.client.utils.ValueUtil.CxrBluetoothErrorCode?) {
                    Log.d(LOG_TAG, "Auto reconnect failed: $errorCode")
                    // 自动重连失败，显示弹窗提示用户手动连接
                    showAutoReconnectFailedDialog = true
                    checkConnectionStatus()
                }
                
                override fun onConnectionInfo(
                    socketUuid: String?,
                    macAddress: String?,
                    rokidAccount: String?,
                    glassesType: Int
                ) {
                    // 连接信息更新
                }
            })
        }, 1000) // 延迟 1 秒，确保应用初始化完成
        requiredSdkPermissions = createRequiredSdkPermissionArray()
        // 初始化 CxrCustomViewManager
        CxrCustomViewManager.init(this)
        // 设置自定义页面状态监听器，监听外部关闭事件
        CxrCustomViewManager.setViewStateListener(object : CxrCustomViewManager.ViewStateListener {
            override fun onViewStateChanged(isActive: Boolean) {
                Log.d(LOG_TAG, "Custom view state changed: isActive=$isActive, readerEnabled=$readerEnabled, isUserActivelyEnabling=$isUserActivelyEnabling")
                
                // 如果用户正在主动开启读屏，忽略关闭事件（可能是 SDK 的残留状态回调）
                if (!isActive && readerEnabled && glassesConnected && !isUserActivelyEnabling) {
                    // 页面被外部关闭（用户在眼镜端手动关闭），但读屏服务应该开启
                    // 有两种处理方式：
                    // 1. 尝试重新打开页面（如果用户只是误操作）
                    // 2. 关闭读屏服务，保持状态一致（如果用户确实想关闭）
                    // 我们选择方式2：关闭读屏服务，因为用户手动关闭页面通常意味着想要停止读屏
                    Log.d(LOG_TAG, "Custom view closed externally by user, disabling reader to sync state...")
                    setReaderEnabled(false, userInitiated = false)
                } else if (isActive && !readerEnabled && glassesConnected) {
                    // 页面被外部打开（异常情况，通常不会发生）
                    // 如果读屏服务未开启但页面打开了，保持当前状态，不自动开启读屏
                    Log.d(LOG_TAG, "Custom view opened externally but reader is disabled, keeping state")
                } else if (!isActive && isUserActivelyEnabling) {
                    // 用户正在主动开启，但收到关闭事件，这可能是 SDK 的残留状态或初始化过程中的短暂关闭
                    // 忽略此事件，等待页面成功打开并稳定
                    Log.d(LOG_TAG, "Ignoring close event during user active enabling")
                } else if (isActive && isUserActivelyEnabling) {
                    // 页面成功打开，延迟重置标志，给 SDK 足够的初始化时间
                    // 避免在初始化过程中短暂关闭时误触发自动关闭逻辑
                    Log.d(LOG_TAG, "Custom view opened successfully, will reset user active enabling flag after delay")
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        // 再次检查页面是否仍然打开，如果打开则重置标志
                        if (CxrCustomViewManager.isViewActive() && readerEnabled) {
                            isUserActivelyEnabling = false
                            Log.d(LOG_TAG, "Custom view still active after delay, reset user active enabling flag")
                        } else {
                            Log.d(LOG_TAG, "Custom view closed during delay, keep flag for protection")
                        }
                    }, 2000) // 延迟 2 秒，给 SDK 足够的初始化时间
                }
                // 刷新 UI 状态
                // 注意：这里不调用 refreshPermissionStates()，因为它可能会触发 ensureInitialized()
                // 但我们需要更新 UI，所以可以安全地更新状态变量
            }
        })
        refreshPermissionStates()
        
        setContent {
            GlassesReaderTheme(darkTheme = isDarkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        uiState = buildMainUiState(),
                        defaultTab = defaultTab,
                        currentVersion = currentVersion,
                        checkingUpdate = checkingUpdate,
                        onRequestOverlay = ::openOverlaySettings,
                        onRequestAccessibility = ::openAccessibilitySettings,
                        onRequestNotification = ::requestNotificationPermission,
                        onRequestSdkPermissions = ::requestSdkPermissions,
                        onOpenDeviceScan = ::openDeviceScan,
                        onToggleService = ::onToggleReaderRequested,
                        onOverlaySettingChange = ::onOverlaySettingChange,
                        onThemeChange = ::onThemeChange,
                        onShowMessage = ::showToast,
                        onBrightnessChange = ::onBrightnessChange,
                        onCheckUpdate = ::checkForUpdate
                    )
                    
                    // 自动重连失败弹窗
                    if (showAutoReconnectFailedDialog) {
                        AutoReconnectFailedDialog(
                            onDismiss = { showAutoReconnectFailedDialog = false },
                            onNavigateToConnect = {
                                showAutoReconnectFailedDialog = false
                                defaultTab = MainTab.CONNECT
                            }
                        )
                    }
                    
                    // 更新对话框
                    updateResult?.let { result ->
                        if (showUpdateDialog && result is UpdateResult.NewVersionAvailable) {
                            UpdateDialog(
                                updateInfo = result,
                                onDismiss = { showUpdateDialog = false },
                                onOpenGitHub = {
                                    showUpdateDialog = false
                                    openGitHubRelease()
                                },
                                onOpenWebsite = {
                                    showUpdateDialog = false
                                    openWebsiteDownload()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStates()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (this::appPrefs.isInitialized) {
            appPrefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        }
    }

    private fun refreshPermissionStates() {
        overlayPermissionGranted = Settings.canDrawOverlays(this)
        accessibilityEnabled = isAccessibilityServiceEnabled(this, ScreenTextService::class.java)
        notificationGranted = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            true
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }
        sdkPermissionsGranted = areSdkPermissionsGranted()
        serviceRunning = isOverlayServiceRunning()
        readerEnabled = appPrefs.getBoolean(KEY_READER_ENABLED, readerEnabled)
        userDisabledService = appPrefs.getBoolean(KEY_USER_DISABLED_READER, userDisabledService)

        Log.d(LOG_TAG, "Overlay permission granted: $overlayPermissionGranted")
        Log.d(LOG_TAG, "Accessibility service enabled: $accessibilityEnabled")
        Log.d(LOG_TAG, "Notification permission granted: $notificationGranted")
        Log.d(LOG_TAG, "SDK permissions granted: $sdkPermissionsGranted")
        Log.d(LOG_TAG, "Overlay service running: $serviceRunning")

        if (sdkPermissionsGranted) {
            checkConnectionStatus()
            if (glassesConnected && readerEnabled) {
                // 只有当读屏服务开启且眼镜已连接时，才确保自定义页面打开
                // 如果页面被外部关闭，这里会尝试重新打开
                if (!CxrCustomViewManager.isViewActive()) {
                    Log.d(LOG_TAG, "Reader enabled but view closed, ensuring initialized...")
                }
                CxrCustomViewManager.ensureInitialized()
            }
        } else {
            glassesConnected = false
        }

        syncBrightnessWithGlass()

        updateDefaultTab()
        ensureOverlayServiceState()
        maybeAutoControlReader()
        updateReaderAvailability()
    }

    private fun openDeviceScan() {
        if (!sdkPermissionsGranted) {
            Log.w(LOG_TAG, "SDK permissions not granted, cannot open device scan")
            return
        }
        val intent = Intent(this, DeviceScanActivity::class.java)
        deviceScanLauncher.launch(intent)
    }

    private fun checkConnectionStatus() {
        if (sdkPermissionsGranted) {
            glassesConnected = connectionManager.isConnected()
            Log.d(LOG_TAG, "Connection status: $glassesConnected")
        }
    }

    private fun openOverlaySettings() {
        if (overlayPermissionGranted) return
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlayPermissionLauncher.launch(intent)
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        accessibilityLauncher.launch(intent)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (notificationGranted) return
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun requestSdkPermissions() {
        if (sdkPermissionsGranted) return
        sdkPermissionLauncher.launch(requiredSdkPermissions)
    }

    private fun startOverlayService(autoStarted: Boolean = false) {
        if (!overlayPermissionGranted) {
            return
        }
        if (!serviceRunning) {
        TextOverlayService.start(this)
        serviceRunning = true
    }
        if (overlayUIEnabled) {
            TextOverlayService.enableOverlay(this)
        } else {
            TextOverlayService.disableOverlay(this)
        }
        if (readerEnabled) {
            TextOverlayService.enableReader(this)
            if (glassesConnected) {
                CxrCustomViewManager.ensureInitialized()
            }
        } else {
            TextOverlayService.disableReader(this)
        }
        if (!autoStarted) {
            userDisabledService = false
            appPrefs.edit().putBoolean(KEY_USER_DISABLED_READER, userDisabledService).apply()
        }
    }

    private fun stopOverlayService(userInitiated: Boolean = false) {
        if (!serviceRunning) {
            if (userInitiated) {
                userDisabledService = true
                appPrefs.edit().putBoolean(KEY_USER_DISABLED_READER, userDisabledService).apply()
            }
            return
        }
        TextOverlayService.disableReader(this)
        TextOverlayService.stop(this)
        serviceRunning = false
        readerEnabled = false
        appPrefs.edit().putBoolean(KEY_READER_ENABLED, false).apply()
        if (userInitiated) {
            userDisabledService = true
            appPrefs.edit().putBoolean(KEY_USER_DISABLED_READER, userDisabledService).apply()
        }
        CxrCustomViewManager.close()
        updateReaderAvailability()
    }

    private fun isOverlayServiceRunning(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return manager.getRunningServices(Int.MAX_VALUE).any { service ->
            service.service.className == TextOverlayService::class.java.name
        }
    }

    private fun areSdkPermissionsGranted(): Boolean {
        return requiredSdkPermissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun createRequiredSdkPermissionArray(): Array<String> {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        return permissions.toTypedArray()
    }

    private fun updateDefaultTab() {
        defaultTab = when {
            !overlayPermissionGranted || !accessibilityEnabled || !sdkPermissionsGranted -> MainTab.SETUP
            !glassesConnected -> MainTab.CONNECT
            else -> MainTab.DISPLAY
        }
    }

    private fun maybeAutoControlReader() {
        val shouldDisableReader = !(overlayPermissionGranted &&
            accessibilityEnabled &&
            sdkPermissionsGranted &&
            glassesConnected)

        if (shouldDisableReader && readerEnabled) {
            setReaderEnabled(false, userInitiated = false)
        }
    }

    private fun ensureOverlayServiceState() {
        if (!overlayPermissionGranted) {
            if (serviceRunning) {
                stopOverlayService(userInitiated = false)
            }
            return
        }
        startOverlayService(autoStarted = true)
        if (overlayUIEnabled) {
            TextOverlayService.enableOverlay(this)
        } else {
            TextOverlayService.disableOverlay(this)
        }
    }

    private fun setReaderEnabled(enable: Boolean, userInitiated: Boolean) {
        if (enable == readerEnabled) {
            // 即使状态相同，也要检查页面是否真的打开
            // 如果应该开启但页面已关闭，需要重新打开（仅用户主动操作时）
            if (enable && glassesConnected && !CxrCustomViewManager.isViewActive() && userInitiated) {
                Log.d(LOG_TAG, "Reader should be enabled but view is closed, reopening...")
                CxrCustomViewManager.ensureInitialized()
            }
            return
        }
        if (enable) {
            // 标记用户正在主动开启，防止监听器误判
            if (userInitiated) {
                isUserActivelyEnabling = true
                // 延迟重置标志，给页面打开足够的时间（5秒）
                // 这个延迟作为保险机制，实际会在页面成功打开后通过 onViewStateChanged 延迟重置
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (isUserActivelyEnabling) {
                        isUserActivelyEnabling = false
                        Log.d(LOG_TAG, "User active enabling flag reset (timeout)")
                    }
                }, 5000)
            }
            
            startOverlayService(autoStarted = true)
            TextOverlayService.enableReader(this)
            readerEnabled = true
            if (userInitiated) {
                userDisabledService = false
            }
            // 开启读屏时，确保自定义页面已打开
            if (glassesConnected) {
                // 检查页面状态，如果已关闭则重新打开
                if (!CxrCustomViewManager.isViewActive()) {
                    Log.d(LOG_TAG, "Opening custom view when enabling reader...")
                    CxrCustomViewManager.ensureInitialized()
                }
            }
        } else {
            // 关闭时重置标志
            isUserActivelyEnabling = false
            TextOverlayService.disableReader(this)
            readerEnabled = false
            if (userInitiated) {
                userDisabledService = true
            }
        }
        appPrefs.edit()
            .putBoolean(KEY_READER_ENABLED, readerEnabled)
            .putBoolean(KEY_USER_DISABLED_READER, userDisabledService)
            .apply()
        updateReaderAvailability()
    }

    private fun collectMissingReasons(): List<String> {
        val reasons = mutableListOf<String>()
        if (!overlayPermissionGranted) {
            reasons += "请先授权悬浮窗"
        }
        if (!accessibilityEnabled) {
            reasons += "请开启无障碍服务"
        }
        if (!sdkPermissionsGranted) {
            reasons += "请授权蓝牙与定位"
        }
        if (!glassesConnected) {
            reasons += "请连接智能眼镜"
        }
        return reasons
    }

    private fun updateReaderAvailability() {
        val reasons = collectMissingReasons()
        TextOverlayService.updateToggleAvailability(
            this,
            reasons.isEmpty(),
            reasons.takeIf { it.isNotEmpty() }?.joinToString("、")
        )
    }

    private fun syncBrightnessWithGlass() {
        if (!sdkPermissionsGranted) {
            brightnessSynced = false
            return
        }
        if (glassesConnected) {
            if (!brightnessSynced) {
                pushBrightnessToGlass(glassBrightness)
                brightnessSynced = true
            }
        } else {
            brightnessSynced = false
        }
    }

    private fun onBrightnessChange(value: Int) {
        val clamped = value.coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS)
        if (glassBrightness == clamped) {
            return
        }
        glassBrightness = clamped
        appPrefs.edit().putInt(KEY_LAST_BRIGHTNESS, clamped).apply()
        brightnessSynced = false
        pushBrightnessToGlass(clamped)
    }

    private fun pushBrightnessToGlass(value: Int) {
        if (!glassesConnected) {
            Log.d(LOG_TAG, "Glass not connected, skip brightness push")
            return
        }
        try {
            val status = CxrApi.getInstance().setGlassBrightness(value)
            Log.d(LOG_TAG, "setGlassBrightness result: $status")
            brightnessSynced = true
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to set glass brightness: ${e.message}", e)
        }
    }

    private fun buildMainUiState(): MainUiState {
        return MainUiState(
            overlayGranted = overlayPermissionGranted,
            accessibilityGranted = accessibilityEnabled,
            notificationGranted = notificationGranted,
            sdkPermissionsGranted = sdkPermissionsGranted,
            overlayUIEnabled = overlayUIEnabled,
            overlayServiceRunning = serviceRunning,
            readerEnabled = readerEnabled,
            glassesConnected = glassesConnected,
            brightness = glassBrightness,
            customViewRunning = CxrCustomViewManager.isViewActive(),
            toggleReasons = collectMissingReasons(),
            hasSavedConnectionInfo = connectionManager.hasSavedConnectionInfo(),
            isDarkTheme = isDarkTheme
        )
    }

    private fun onToggleReaderRequested() {
        val reasons = collectMissingReasons()
        if (reasons.isNotEmpty()) {
            showToast(reasons.joinToString("、"))
            updateReaderAvailability()
            return
        }
        setReaderEnabled(!readerEnabled, userInitiated = true)
    }

    private fun onOverlaySettingChange(enabled: Boolean) {
        if (overlayUIEnabled == enabled) return
        val previous = overlayUIEnabled
        overlayUIEnabled = enabled
        val reasons = collectMissingReasons()
        if (reasons.isNotEmpty()) {
            overlayUIEnabled = previous
            showToast(reasons.joinToString("、"))
            updateReaderAvailability()
            return
        }
        appPrefs.edit().putBoolean(KEY_OVERLAY_ENABLED, overlayUIEnabled).apply()
        if (overlayPermissionGranted) {
            startOverlayService(autoStarted = true)
            if (overlayUIEnabled) {
                TextOverlayService.enableOverlay(this)
            } else {
                TextOverlayService.disableOverlay(this)
            }
        }
    }

    private fun onThemeChange(isDark: Boolean) {
        isDarkTheme = isDark
        appPrefs.edit().putBoolean(KEY_DARK_THEME, isDarkTheme).apply()
    }

    private fun showToast(message: String) {
        if (message.isBlank()) return
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    
    private fun checkForUpdate() {
        if (checkingUpdate) return
        
        checkingUpdate = true
        CoroutineScope(Dispatchers.IO).launch {
            val result = UpdateChecker.checkForUpdate(this@MainActivity)
            CoroutineScope(Dispatchers.Main).launch {
                checkingUpdate = false
                updateResult = result
                when (result) {
                    is UpdateResult.NewVersionAvailable -> {
                        showUpdateDialog = true
                    }
                    is UpdateResult.UpToDate -> {
                        showToast("已是最新版本 (v${result.currentVersion})")
                    }
                    is UpdateResult.Error -> {
                        showToast(result.message)
                    }
                }
            }
        }
    }
    
    private fun openGitHubRelease() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.getGitHubReleaseUrl()))
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to open GitHub release page", e)
            showToast("无法打开浏览器")
        }
    }
    
    private fun openWebsiteDownload() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.getWebsiteDownloadUrl()))
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to open website download page", e)
            showToast("无法打开浏览器")
        }
    }


    companion object {
        const val LOG_TAG = "MainActivity"
        private const val PREF_APP_SETTINGS = "gr_app_settings"
        private const val KEY_OVERLAY_ENABLED = "overlay_enabled"
        private const val KEY_READER_ENABLED = "reader_enabled"
        private const val KEY_USER_DISABLED_READER = "user_disabled_reader"
        private const val KEY_LAST_BRIGHTNESS = "glass_brightness"
        private const val KEY_DARK_THEME = "dark_theme"
    }
}

private enum class MainTab(val title: String) {
    SETUP("权限引导"),
    CONNECT("设备连接"),
    DISPLAY("显示设置"),
    SETTINGS("应用设置")
}

private data class MainUiState(
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
    val isDarkTheme: Boolean = false
) {
    val canToggleReader: Boolean
        get() = toggleReasons.isEmpty()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
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

@Composable
private fun ServiceFab(
    canToggle: Boolean,
    readerEnabled: Boolean,
    overlayGranted: Boolean,
    onToggle: () -> Unit,
    onShowMessage: (String) -> Unit,
    disabledMessage: String
) {
    val containerColor = when {
        !overlayGranted -> MaterialTheme.colorScheme.surfaceVariant
        readerEnabled -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = when {
        !overlayGranted -> MaterialTheme.colorScheme.onSurfaceVariant
        readerEnabled -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }

    LargeFloatingActionButton(
        onClick = {
            if (canToggle) {
                onToggle()
            } else {
                val message = when {
                    disabledMessage.isNotBlank() -> disabledMessage
                    !overlayGranted -> "悬浮窗权限未授权"
                    else -> "还有前置步骤未完成"
                }
                onShowMessage(message)
            }
        },
        containerColor = containerColor,
        contentColor = contentColor,
        shape = CircleShape
    ) {
        Icon(
            imageVector = when {
            readerEnabled -> Icons.Filled.Pause
            else -> Icons.Filled.PlayArrow
            },
            contentDescription = when {
                readerEnabled -> "暂停读屏服务"
                else -> "启动读屏服务"
            },
            modifier = Modifier.size(36.dp)
        )
    }
}

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
            text = "若看到“未连接”，请确保智能眼镜已在系统蓝牙完成配对，并断开官方应用后重新尝试。",
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

@Composable
private fun StatusListItem(
    title: String,
    isCompleted: Boolean,
    description: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    actionEnabled: Boolean = true
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = if (isCompleted) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = if (isCompleted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCompleted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Medium
            )
            if (!description.isNullOrEmpty()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (actionLabel != null) {
                TextButton(
                    onClick = onAction ?: {},
                    enabled = actionEnabled && onAction != null,
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(text = actionLabel)
                }
            }
        }
    }
}

@Composable
private fun TextSizeControl() {
    var textSize by remember {
        mutableStateOf(CxrCustomViewManager.getTextSize())
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "眼镜文字大小",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "${textSize.toInt()}sp",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
        }
            Slider(
                value = textSize,
                onValueChange = { newSize ->
                    textSize = newSize
                    CxrCustomViewManager.setTextSize(newSize)
                },
                valueRange = 12f..48f,
                steps = 35, // 12-48 共 36 个值，steps = 35
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "12sp - 48sp",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TextProcessingControls() {
    val options = CxrCustomViewManager.getTextProcessingOptions()
    var removeEmptyLines by remember { 
        mutableStateOf(options.removeEmptyLines) 
    }
    var removeLineBreaks by remember { 
        mutableStateOf(options.removeLineBreaks) 
    }
    var removeFirstLine by remember { 
        mutableStateOf(options.removeFirstLine) 
    }
    var removeLastLine by remember { 
        mutableStateOf(options.removeLastLine) 
    }
    var removeFirstLineCount by remember { 
        mutableStateOf(options.removeFirstLineCount.toString()) 
    }
    var removeLastLineCount by remember { 
        mutableStateOf(options.removeLastLineCount.toString()) 
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "文本处理选项",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )

            // 删除空行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "删除空行",
                    style = MaterialTheme.typography.bodyMedium
                )
                Switch(
                    checked = removeEmptyLines,
                    onCheckedChange = {
                        removeEmptyLines = it
                        CxrCustomViewManager.setTextProcessingOptions(removeEmptyLines = it)
                    }
                )
            }

            // 删除换行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "删除换行",
                    style = MaterialTheme.typography.bodyMedium
                )
                Switch(
                    checked = removeLineBreaks,
                    onCheckedChange = {
                        removeLineBreaks = it
                        CxrCustomViewManager.setTextProcessingOptions(removeLineBreaks = it)
                    }
                )
            }

            // 删除第一行
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
        Text(
                        text = "删除前 N 行",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(
                        checked = removeFirstLine,
                        onCheckedChange = {
                            removeFirstLine = it
                            val count = removeFirstLineCount.toIntOrNull() ?: 1
                            CxrCustomViewManager.setTextProcessingOptions(
                                removeFirstLine = it,
                                removeFirstLineCount = count
                            )
                        }
                    )
                }
                if (removeFirstLine) {
                    OutlinedTextField(
                        value = removeFirstLineCount,
                        onValueChange = { newValue ->
                            // 只允许输入数字
                            if (newValue.all { it.isDigit() } || newValue.isEmpty()) {
                                removeFirstLineCount = newValue
                                val count = newValue.toIntOrNull() ?: 1
                                CxrCustomViewManager.setTextProcessingOptions(
                                    removeFirstLine = true,
                                    removeFirstLineCount = count
        )
    }
                        },
                        label = { Text("行数") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number
                        )
                    )
                }
            }

            // 删除最后一行
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "删除后 N 行",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(
                        checked = removeLastLine,
                        onCheckedChange = {
                            removeLastLine = it
                            val count = removeLastLineCount.toIntOrNull() ?: 1
                            CxrCustomViewManager.setTextProcessingOptions(
                                removeLastLine = it,
                                removeLastLineCount = count
                            )
                        }
                    )
                }
                if (removeLastLine) {
                    OutlinedTextField(
                        value = removeLastLineCount,
                        onValueChange = { newValue ->
                            // 只允许输入数字
                            if (newValue.all { it.isDigit() } || newValue.isEmpty()) {
                                removeLastLineCount = newValue
                                val count = newValue.toIntOrNull() ?: 1
                                CxrCustomViewManager.setTextProcessingOptions(
                                    removeLastLine = true,
                                    removeLastLineCount = count
                                )
                            }
                        },
                        label = { Text("行数") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun BrightnessControl(
    brightness: Int,
    enabled: Boolean,
    onBrightnessChange: (Int) -> Unit
) {
    var sliderValue by remember(brightness) { mutableStateOf(brightness.toFloat()) }

    LaunchedEffect(brightness) {
        sliderValue = brightness.toFloat()
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
                    text = "眼镜亮度",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = sliderValue.roundToInt().coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS).toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                onValueChangeFinished = {
                    if (enabled) {
                        onBrightnessChange(
                            sliderValue.roundToInt().coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS)
                        )
                    } else {
                        sliderValue = brightness.toFloat()
                    }
                },
                valueRange = MIN_BRIGHTNESS.toFloat()..MAX_BRIGHTNESS.toFloat(),
                steps = (MAX_BRIGHTNESS - MIN_BRIGHTNESS - 1).coerceAtLeast(0),
                enabled = enabled
            )

            if (!enabled) {
                Text(
                    text = "请连接智能眼镜后再调整亮度",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
        )
            }
        }
    }
}

/**
 * 檢查無障礙服務是否已啟用。
 */
private fun isAccessibilityServiceEnabled(
    context: Context,
    serviceClass: Class<*>
): Boolean {
    val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        ?: return false
    val enabledServices =
        manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
    val componentName = ComponentName(context, serviceClass)
    val targetFullId = componentName.flattenToString()
    val relativeName = serviceClass.name.removePrefix("${context.packageName}.")
    val targetShortId = "${context.packageName}/.$relativeName"
    val activeIds = enabledServices.map { it.id }
    Log.d(MainActivity.LOG_TAG, "Enabled accessibility IDs: $activeIds")
    val matched = enabledServices.any {
        val id = it.id
        it.resolveInfo.serviceInfo.packageName.isNotEmpty() &&
            (id == targetFullId || id == targetShortId)
    }
    Log.d(MainActivity.LOG_TAG, "Accessibility match for $targetFullId/$targetShortId: $matched")
    return matched
}

/**
 * 自动重连失败弹窗
 * 提示用户前往设备连接页面手动连接设备
 */
@Composable
private fun AutoReconnectFailedDialog(
    onDismiss: () -> Unit,
    onNavigateToConnect: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("连接失败")
        },
        text = {
            Text("自动重连失败，请前往设备连接页面手动连接设备")
        },
        confirmButton = {
            TextButton(onClick = onNavigateToConnect) {
                Text("前往连接")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("稍后")
            }
        }
    )
}

/**
 * 更新对话框
 * 显示新版本信息并提供下载选项
 */
@Composable
private fun UpdateDialog(
    updateInfo: UpdateResult.NewVersionAvailable,
    onDismiss: () -> Unit,
    onOpenGitHub: () -> Unit,
    onOpenWebsite: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "发现新版本",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "当前版本：v${updateInfo.currentVersion}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "最新版本：v${updateInfo.latestVersion}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (updateInfo.releaseNotes.isNotBlank()) {
                        Text(
                            text = "更新内容：",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        Text(
                            text = updateInfo.releaseNotes.take(200) + if (updateInfo.releaseNotes.length > 200) "..." else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "\n提示：建议使用官网下载链接，访问更稳定。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onOpenWebsite,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("官网下载（推荐）")
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(
                            onClick = onOpenGitHub,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("GitHub")
                        }
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("稍后")
                        }
                    }
                }
            }
        }
    }
}
