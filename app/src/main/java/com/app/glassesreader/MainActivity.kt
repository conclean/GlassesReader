package com.app.glassesreader

import android.Manifest
import android.app.ActivityManager
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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.app.glassesreader.accessibility.service.ScreenTextService
import com.app.glassesreader.sdk.CxrConnectionManager
import com.app.glassesreader.sdk.CxrCustomViewManager
import com.app.glassesreader.service.overlay.TextOverlayService
import com.app.glassesreader.ui.components.*
import com.app.glassesreader.ui.model.*
import com.app.glassesreader.ui.screens.*
import com.app.glassesreader.ui.theme.GlassesReaderTheme
import com.app.glassesreader.update.UpdateChecker
import com.app.glassesreader.update.UpdateResult
import com.app.glassesreader.utils.*
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.utils.ValueUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val MIN_BRIGHTNESS = 0
private const val MAX_BRIGHTNESS = 15
private const val DEFAULT_BRIGHTNESS = 8

/**
 * MainActivity 用于引导用户授权并启动浮窗服务。
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
    // 自动更新检查相关状态
    private var autoUpdateCheckResult by mutableStateOf<UpdateResult?>(null)
    private var showAutoUpdateReminderDialog by mutableStateOf(false)
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
                    
                    // 自动更新提醒对话框
                    autoUpdateCheckResult?.let { result ->
                        if (showAutoUpdateReminderDialog && result is UpdateResult.NewVersionAvailable) {
                            AutoUpdateReminderDialog(
                                updateInfo = result,
                                onDismiss = { 
                                    showAutoUpdateReminderDialog = false 
                                },
                                onNavigateToSettings = {
                                    showAutoUpdateReminderDialog = false
                                    navigateToSettingsTab()
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
        
        // 自动检查更新（仅在满足条件时执行）
        if (shouldPerformAutoUpdateCheck()) {
            performAutoUpdateCheck()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (this::appPrefs.isInitialized) {
            appPrefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        }
    }

    private fun refreshPermissionStates() {
        overlayPermissionGranted = Settings.canDrawOverlays(this)
        accessibilityEnabled = AccessibilityUtils.isAccessibilityServiceEnabled(this, ScreenTextService::class.java)
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
    
    /**
     * 判断是否应该执行自动更新检查
     * @return true 如果距离上次检查已超过24小时，且当前没有正在手动检查
     */
    private fun shouldPerformAutoUpdateCheck(): Boolean {
        // 1. 如果正在手动检查更新，跳过自动检查
        if (checkingUpdate) {
            Log.d(LOG_TAG, "Manual update check in progress, skip auto check")
            return false
        }
        
        // 2. 获取上次检查的时间戳（如果从未检查过，返回0）
        val lastCheckTime = appPrefs.getLong(KEY_LAST_AUTO_UPDATE_CHECK_TIME, 0)
        
        // 3. 如果从未检查过（lastCheckTime == 0），应该执行首次检查
        if (lastCheckTime == 0L) {
            Log.d(LOG_TAG, "First time auto update check")
            return true
        }
        
        // 4. 计算距离上次检查的时间间隔
        val currentTime = System.currentTimeMillis()
        val timeSinceLastCheck = currentTime - lastCheckTime
        
        // 5. 判断是否超过24小时
        val shouldCheck = timeSinceLastCheck >= AUTO_UPDATE_CHECK_INTERVAL_MS
        
        if (shouldCheck) {
            val hoursSinceLastCheck = timeSinceLastCheck / (60 * 60 * 1000)
            Log.d(LOG_TAG, "Time since last check: $hoursSinceLastCheck hours, performing auto check")
        } else {
            val remainingHours = (AUTO_UPDATE_CHECK_INTERVAL_MS - timeSinceLastCheck) / (60 * 60 * 1000)
            Log.d(LOG_TAG, "Auto update check skipped, remaining: $remainingHours hours")
        }
        
        return shouldCheck
    }
    
    /**
     * 执行自动更新检查
     * 在后台线程执行，检查完成后更新 SharedPreferences 中的时间戳
     */
    private fun performAutoUpdateCheck() {
        Log.d(LOG_TAG, "Starting auto update check")
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 调用更新检查 API
                val result = UpdateChecker.checkForUpdate(this@MainActivity)
                
                // 无论成功与否，都更新检查时间戳（避免频繁重试）
                val currentTime = System.currentTimeMillis()
                appPrefs.edit()
                    .putLong(KEY_LAST_AUTO_UPDATE_CHECK_TIME, currentTime)
                    .apply()
                
                Log.d(LOG_TAG, "Auto update check completed, timestamp updated: $currentTime")
                
                // 在主线程更新 UI
                CoroutineScope(Dispatchers.Main).launch {
                    autoUpdateCheckResult = result
                    when (result) {
                        is UpdateResult.NewVersionAvailable -> {
                            // 有新版本，显示提醒对话框
                            Log.d(LOG_TAG, "New version available: ${result.latestVersion}")
                            showAutoUpdateReminderDialog = true
                        }
                        is UpdateResult.UpToDate -> {
                            // 已是最新版本，静默处理
                            Log.d(LOG_TAG, "App is up to date: ${result.currentVersion}")
                        }
                        is UpdateResult.Error -> {
                            // 检查失败，静默处理（不显示错误提示）
                            Log.w(LOG_TAG, "Auto update check failed: ${result.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                // 异常情况也更新时间戳，避免频繁重试
                val currentTime = System.currentTimeMillis()
                appPrefs.edit()
                    .putLong(KEY_LAST_AUTO_UPDATE_CHECK_TIME, currentTime)
                    .apply()
                
                Log.e(LOG_TAG, "Auto update check error", e)
            }
        }
    }
    
    /**
     * 跳转到设置页面
     */
    private fun navigateToSettingsTab() {
        defaultTab = MainTab.SETTINGS
    }


    companion object {
        const val LOG_TAG = "MainActivity"
        private const val PREF_APP_SETTINGS = "gr_app_settings"
        private const val KEY_OVERLAY_ENABLED = "overlay_enabled"
        private const val KEY_READER_ENABLED = "reader_enabled"
        private const val KEY_USER_DISABLED_READER = "user_disabled_reader"
        private const val KEY_LAST_BRIGHTNESS = "glass_brightness"
        private const val KEY_DARK_THEME = "dark_theme"
        // 自动更新检查相关常量
        private const val KEY_LAST_AUTO_UPDATE_CHECK_TIME = "last_auto_update_check_time"
        private const val AUTO_UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L // 24小时（毫秒）
    }
}
