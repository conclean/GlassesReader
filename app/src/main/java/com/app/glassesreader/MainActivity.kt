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
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.app.glassesreader.accessibility.ScreenTextPublisher
import com.app.glassesreader.accessibility.service.ScreenTextService
import com.app.glassesreader.data.TextPreset
import com.app.glassesreader.data.TextPresetManager
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
import com.rokid.cxr.client.extend.callbacks.PhotoPathCallback
import com.rokid.cxr.client.extend.callbacks.SyncStatusCallback
import com.rokid.cxr.client.extend.callbacks.WifiP2PStatusCallback
import com.rokid.cxr.client.utils.ValueUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** AR 截图连 Wi‑Fi：单次失败后重试前间隔（毫秒），直至 60 秒倒计时结束 */
private const val AR_SCREENSHOT_WIFI_RETRY_DELAY_MS = 1000L

private const val MIN_BRIGHTNESS = 0
private const val MAX_BRIGHTNESS = 15
private const val DEFAULT_BRIGHTNESS = 8

/** P2P 刚连上时稍后再拉文件，避免眼镜端尚未写完。 */
private const val POST_WIFI_SYNC_DELAY_MS = 500L

/** 同步失败后换参数或再试的间隔。 */
private const val SYNC_FAILURE_RETRY_DELAY_MS = 900L

/**
 * 与 [sdk/doc/拍照录像录音.md] 白名单「4032×3024」一致：接口第一参数为高、第二参数为宽（与 Kotlin 形参名 width/height 可能不一致，以文档为准）。
 */
private const val PHOTO_SYNC_API_HEIGHT = 4032
private const val PHOTO_SYNC_API_WIDTH = 3024
private const val PHOTO_SYNC_QUALITY = 100

/** false 时仅同步并导出原图，不做叠字 */
private const val PHOTO_OVERLAY_ENABLED = true

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
    // 预设管理相关状态
    private lateinit var presetManager: TextPresetManager
    private var presets by mutableStateOf<List<TextPreset>>(emptyList())
    private var currentPresetId by mutableStateOf<String?>(null)
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
    private var photoSyncInProgress = false
    private var photoSyncAttemptSeq = 0
    private var deviceAutoReconnectInProgress by mutableStateOf(false)
    private var arScreenshotWifiPreparing by mutableStateOf(false)
    private var arScreenshotWifiCountdownSec by mutableStateOf(0)
    private var showArScreenshotWifiTimeoutDialog by mutableStateOf(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var deviceReconnectTimeoutRunnable: Runnable? = null
    private var arWifiCountdownRunnable: Runnable? = null
    private var arScreenshotWifiRetryRunnable: Runnable? = null
    /** 每次进入「AI 截图连 Wi‑Fi」流程自增，用于丢弃旧会话回调、区分超时后的晚到 [onConnected] */
    private var arScreenshotWifiFlowId: Int = 0

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
        synchronized(MainActivity::class.java) { uiInstance = this }
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
        
        // 初始化预设管理器
        presetManager = TextPresetManager.getInstance(this)
        presetManager.initializeIfNeeded()
        presets = presetManager.getAllPresets()
        currentPresetId = presetManager.getCurrentPresetId()
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
                    // 在 Compose 作用域内调用，确保状态更新时自动重组
                    MainScreen(
                        uiState = buildMainUiState(),
                        currentVersion = currentVersion,
                        checkingUpdate = checkingUpdate,
                        onRequestOverlay = ::openOverlaySettings,
                        onRequestAccessibility = ::openAccessibilitySettings,
                        onRequestNotification = ::requestNotificationPermission,
                        onRequestSdkPermissions = ::requestSdkPermissions,
                        onOpenDeviceScan = ::onDeviceConnectionRowClick,
                        onToggleService = ::onToggleReaderRequested,
                        onOverlaySettingChange = ::onOverlaySettingChange,
                        onThemeChange = ::onThemeChange,
                        onShowMessage = ::showToast,
                        onBrightnessChange = ::onBrightnessChange,
                        onCheckUpdate = ::checkForUpdate,
                        onPhotoSyncVerify = ::runPhotoSyncVerify,
                        onArScreenRecord = { showToast("敬请期待") },
                        onConfirmRebootGlasses = ::requestGlassesReboot,
                        onSettingChanged = {
                            // 实时保存到当前预设
                            if (this::presetManager.isInitialized) {
                                presetManager.saveCurrentSettingsToCurrentPreset(glassBrightness)
                            }
                        }
                    )
                    
                    // 自动重连失败弹窗
                    if (showAutoReconnectFailedDialog) {
                        AutoReconnectFailedDialog(
                            onDismiss = { showAutoReconnectFailedDialog = false },
                            onNavigateToConnect = {
                                showAutoReconnectFailedDialog = false
                                // 导航到设置页面的设备连接部分（由用户手动点击设置按钮）
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
                                    // 设置页面现在通过导航系统访问，用户需要手动点击设置按钮
                                }
                            )
                        }
                    }

                    if (showArScreenshotWifiTimeoutDialog) {
                        AlertDialog(
                            onDismissRequest = { showArScreenshotWifiTimeoutDialog = false },
                            title = { Text("Wi‑Fi 连接超时") },
                            text = {
                                Text(
                                    "一分钟内未能建立 Wi‑Fi 连接。建议重启眼镜后再试，有助于恢复直连与传图。\n\n是否立即重启眼镜？"
                                )
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        showArScreenshotWifiTimeoutDialog = false
                                        requestGlassesReboot()
                                    }
                                ) {
                                    Text("立即重启")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showArScreenshotWifiTimeoutDialog = false }) {
                                    Text("取消")
                                }
                            }
                        )
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
        cancelDeviceReconnectTimeout()
        deviceAutoReconnectInProgress = false
        arScreenshotWifiPreparing = false
        cancelArScreenshotWifiCountdown()
        showArScreenshotWifiTimeoutDialog = false
        synchronized(MainActivity::class.java) {
            if (uiInstance == this) uiInstance = null
        }
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

    /**
     * 设置页「设备连接」行：已连接则直接进入扫描页；未连接时若有历史连接参数则先 [CxrConnectionManager.autoReconnect]，
     * 在区块内展示「正在尝试自动重连」，成功则仅刷新状态，失败则提示并打开扫描页。
     */
    private fun onDeviceConnectionRowClick() {
        if (!sdkPermissionsGranted) {
            Log.w(LOG_TAG, "SDK permissions not granted, cannot open device scan")
            showToast("请先授予蓝牙等相关权限")
            requestSdkPermissions()
            return
        }
        if (glassesConnected) {
            openDeviceScan()
            return
        }
        if (deviceAutoReconnectInProgress) {
            return
        }
        if (!connectionManager.hasSavedConnectionInfo()) {
            showToast("未发现已保存的连接，将打开扫描页")
            openDeviceScan()
            return
        }

        cancelDeviceReconnectTimeout()
        deviceAutoReconnectInProgress = true
        scheduleDeviceReconnectTimeout()

        val callback = object : CxrConnectionManager.ConnectionCallback {
            override fun onConnected() {
                runOnUiThread {
                    cancelDeviceReconnectTimeout()
                    deviceAutoReconnectInProgress = false
                    checkConnectionStatus()
                    showToast("已重新连接")
                }
            }

            override fun onDisconnected() {
                // 重连过程中可能出现短暂断开，不在此结束 loading，等待成功或失败回调
            }

            override fun onFailed(errorCode: ValueUtil.CxrBluetoothErrorCode?) {
                Log.d(LOG_TAG, "Device row auto reconnect failed: $errorCode")
                runOnUiThread {
                    cancelDeviceReconnectTimeout()
                    deviceAutoReconnectInProgress = false
                    checkConnectionStatus()
                    showToast("自动重连失败，将打开扫描页，请手动连接")
                    openDeviceScan()
                }
            }

            override fun onConnectionInfo(
                socketUuid: String?,
                macAddress: String?,
                rokidAccount: String?,
                glassesType: Int
            ) {
            }
        }

        val attempted = connectionManager.autoReconnect(callback)
        if (!attempted && deviceAutoReconnectInProgress) {
            // 未发起异步重连（例如 Context 为空等），且同步路径也未调用 onConnected
            cancelDeviceReconnectTimeout()
            deviceAutoReconnectInProgress = false
            showToast("无法启动自动重连，将打开扫描页")
            openDeviceScan()
        }
    }

    private fun scheduleDeviceReconnectTimeout() {
        cancelDeviceReconnectTimeout()
        deviceReconnectTimeoutRunnable = Runnable {
            if (!deviceAutoReconnectInProgress) return@Runnable
            deviceAutoReconnectInProgress = false
            checkConnectionStatus()
            showToast("自动重连超时，将打开扫描页，请手动连接")
            openDeviceScan()
        }
        mainHandler.postDelayed(deviceReconnectTimeoutRunnable!!, 45_000L)
    }

    private fun cancelDeviceReconnectTimeout() {
        deviceReconnectTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        deviceReconnectTimeoutRunnable = null
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        return permissions.toTypedArray()
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
            reasons += if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                "请授权蓝牙、定位与附近设备"
            } else {
                "请授权蓝牙与定位"
            }
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
        // 实时保存到当前预设
        if (this::presetManager.isInitialized) {
            presetManager.saveCurrentSettingsToCurrentPreset(clamped)
        }
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
            deviceAutoReconnectInProgress = deviceAutoReconnectInProgress,
            arScreenshotWifiPreparing = arScreenshotWifiPreparing,
            arScreenshotWifiCountdownSec = arScreenshotWifiCountdownSec,
            isDarkTheme = isDarkTheme,
            presets = presets,
            currentPresetId = currentPresetId,
            onPresetSelected = ::onPresetSelected,
            onPresetLongPress = { /* 由UI处理 */ },
            onCreatePreset = ::onCreatePreset,
            onRenamePreset = ::onRenamePreset,
            onDeletePreset = ::onDeletePreset
        )
    }
    
    private fun onPresetSelected(presetId: String) {
        if (presetManager.switchToPreset(presetId)) {
            currentPresetId = presetId
            presets = presetManager.getAllPresets()
            // 更新亮度状态
            val preset = presetManager.getPresetById(presetId)
            if (preset != null) {
                glassBrightness = preset.brightness
            }
        }
    }
    
    private fun onCreatePreset(name: String) {
        val newPreset = presetManager.createPreset(name, glassBrightness)
        if (newPreset != null) {
            presets = presetManager.getAllPresets()
            currentPresetId = newPreset.id
            showToast("已创建预设：$name")
        } else {
            showToast("创建失败：名称已存在或无效")
        }
    }
    
    private fun onRenamePreset(presetId: String, newName: String) {
        val preset = presetManager.getPresetById(presetId) ?: return
        val updatedPreset = preset.copy(name = newName)
        if (presetManager.updatePreset(updatedPreset)) {
            presets = presetManager.getAllPresets()
            showToast("已重命名为：$newName")
        } else {
            showToast("重命名失败：名称已存在或无效")
        }
    }
    
    private fun onDeletePreset(presetId: String) {
        if (presetManager.deletePreset(presetId)) {
            presets = presetManager.getAllPresets()
            currentPresetId = presetManager.getCurrentPresetId()
            showToast("已删除预设")
        } else {
            showToast("删除失败：至少保留一个预设")
        }
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

    /**
     * 见 [sdk/doc/控制与监听设备状态.md]：`notifyGlassReboot()` 通知眼镜重启。
     */
    private fun requestGlassesReboot() {
        if (!glassesConnected) {
            showToast("请先连接智能眼镜")
            return
        }
        val status = runCatching { CxrApi.getInstance().notifyGlassReboot() }.getOrNull()
        when (status) {
            ValueUtil.CxrStatus.REQUEST_SUCCEED -> showToast("已发送重启指令")
            ValueUtil.CxrStatus.REQUEST_WAITING -> showToast("眼镜繁忙，请稍后再试")
            ValueUtil.CxrStatus.REQUEST_FAILED -> showToast("重启请求失败")
            null -> showToast("重启请求异常")
            else -> showToast("重启：$status")
        }
    }

    /**
     * 见 [sdk/doc/设备连接.md]：Wi‑Fi 为高耗能模块，拍照同步流程结束后应反初始化。
     * 下次进入同步若未连接会再次 [initWifiP2P]（未连接分支里会先 [deinitWifiP2P] 再 init）。
     * 若实测每次断开后重连不稳定，可再改为延迟 deinit、或仅成功/失败分支调用等策略。
     */
    private fun deinitWifiP2PAfterPhotoSync(attemptId: Int) {
        runCatching {
            CxrApi.getInstance().deinitWifiP2P()
            Log.d(LOG_TAG, "[PhotoSync#$attemptId] deinitWifiP2P (after photo sync flow)")
        }.onFailure { e ->
            Log.w(LOG_TAG, "[PhotoSync#$attemptId] deinitWifiP2P failed: ${e.message}")
        }
    }

    /**
     * 设置页「AR截图」：先建立 Wi‑Fi P2P（设置页显示细进度条），连上后再弹出圆形快门；拍照同步阶段沿用已连上的 Wi‑Fi。
     */
    private fun runPhotoSyncVerify() {
        if (!glassesConnected) {
            showToast("请先连接智能眼镜")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(LOG_TAG, "NEARBY_WIFI_DEVICES not granted, requesting")
            showToast("需要附近设备权限以同步图片")
            sdkPermissionLauncher.launch(arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES))
            return
        }
        if (photoSyncInProgress) {
            showToast("拍照同步进行中")
            return
        }
        if (arScreenshotWifiPreparing) {
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            showToast("需要悬浮窗权限：请先在系统设置中为本应用开启悬浮窗")
            return
        }
        ensureWifiForArScreenshotThenShowShutter()
    }

    /**
     * 与 [startSyncSinglePicture] 中逻辑一致：未连接时先 [deinitWifiP2P] 再 [initWifiP2P]，连上后保持至同步结束。
     * 在 60 秒倒计时结束前，[onFailed] / [REQUEST_FAILED] 会按间隔自动重试，不弹 Toast；仅倒计时结束才提示重启眼镜。
     * 若 60 秒已到并已弹出重启提示，但**同一会话**最后一次 [initWifiP2P] 的 [onConnected] 晚到，则关闭该弹窗并照常进入快门（见 [handleArScreenshotWifiConnected]）。
     */
    private fun ensureWifiForArScreenshotThenShowShutter() {
        val api = CxrApi.getInstance()
        val wifiReady = runCatching { api.isWifiP2PConnected() }.getOrDefault(false)
        if (wifiReady) {
            Log.d(LOG_TAG, "[ArScreenshot] WiFi already connected, show shutter")
            TextOverlayService.showArShutter(this)
            return
        }

        arScreenshotWifiFlowId += 1
        arScreenshotWifiPreparing = true
        startArScreenshotWifiCountdown()
        attemptArScreenshotWifiInit()
    }

    private fun attemptArScreenshotWifiInit() {
        if (!arScreenshotWifiPreparing || isFinishing) return
        val flowId = arScreenshotWifiFlowId
        val api = CxrApi.getInstance()
        runCatching { api.deinitWifiP2P() }
            .onFailure { Log.w(LOG_TAG, "[ArScreenshot] deinitWifiP2P: ${it.message}") }

        val initStatus = api.initWifiP2P(object : WifiP2PStatusCallback {
            override fun onConnected() {
                Log.d(LOG_TAG, "[ArScreenshot] WiFi connected, show shutter")
                mainHandler.post {
                    handleArScreenshotWifiConnected(flowId)
                }
            }

            override fun onDisconnected() {
                Log.d(LOG_TAG, "[ArScreenshot] WiFi disconnected")
            }

            override fun onFailed(errorCode: ValueUtil.CxrWifiErrorCode?) {
                Log.e(LOG_TAG, "[ArScreenshot] WiFi init failed: $errorCode")
                mainHandler.post {
                    scheduleArScreenshotWifiRetryAfterFailure("onFailed:$errorCode", flowId)
                }
            }
        })
        Log.d(LOG_TAG, "[ArScreenshot] initWifiP2P status=$initStatus")
        if (initStatus == ValueUtil.CxrStatus.REQUEST_FAILED) {
            mainHandler.post {
                scheduleArScreenshotWifiRetryAfterFailure("REQUEST_FAILED", flowId)
            }
        }
    }

    /**
     * [onConnected]：正常在倒计时内；若已超时则可能是最后一次尝试的回调晚到，关闭重启提示并仍进入快门。
     */
    private fun handleArScreenshotWifiConnected(flowId: Int) {
        if (flowId != arScreenshotWifiFlowId) {
            Log.d(LOG_TAG, "[ArScreenshot] onConnected ignored (stale flowId=$flowId current=$arScreenshotWifiFlowId)")
            return
        }
        if (arScreenshotWifiPreparing) {
            cancelArScreenshotWifiCountdown()
            arScreenshotWifiPreparing = false
            showArScreenshotWifiTimeoutDialog = false
            if (!isFinishing) {
                TextOverlayService.showArShutter(this)
            }
            return
        }
        if (showArScreenshotWifiTimeoutDialog) {
            Log.d(LOG_TAG, "[ArScreenshot] onConnected after timeout (late callback), dismissing restart dialog")
            showArScreenshotWifiTimeoutDialog = false
            if (!isFinishing) {
                TextOverlayService.showArShutter(this)
            }
            return
        }
        Log.d(LOG_TAG, "[ArScreenshot] onConnected ignored (session already finished, flowId=$flowId)")
    }

    /**
     * 在倒计时未结束前自动重试；已结束或已取消则不调度。
     */
    private fun scheduleArScreenshotWifiRetryAfterFailure(reason: String, flowId: Int) {
        if (flowId != arScreenshotWifiFlowId) return
        if (!arScreenshotWifiPreparing || isFinishing) return
        if (arScreenshotWifiCountdownSec <= 0) return
        Log.w(LOG_TAG, "[ArScreenshot] WiFi attempt failed ($reason), retry in ${AR_SCREENSHOT_WIFI_RETRY_DELAY_MS}ms (countdown=$arScreenshotWifiCountdownSec)")
        arScreenshotWifiRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            arScreenshotWifiRetryRunnable = null
            if (flowId != arScreenshotWifiFlowId) return@Runnable
            if (!arScreenshotWifiPreparing || isFinishing) return@Runnable
            if (arScreenshotWifiCountdownSec <= 0) return@Runnable
            attemptArScreenshotWifiInit()
        }
        arScreenshotWifiRetryRunnable = r
        mainHandler.postDelayed(r, AR_SCREENSHOT_WIFI_RETRY_DELAY_MS)
    }

    private fun startArScreenshotWifiCountdown() {
        cancelArScreenshotWifiCountdown()
        arScreenshotWifiCountdownSec = 60
        val r = object : Runnable {
            override fun run() {
                if (!arScreenshotWifiPreparing) return
                arScreenshotWifiCountdownSec -= 1
                if (arScreenshotWifiCountdownSec <= 0) {
                    onArScreenshotWifiTimeout()
                    return
                }
                mainHandler.postDelayed(this, 1000L)
            }
        }
        arWifiCountdownRunnable = r
        mainHandler.postDelayed(r, 1000L)
    }

    private fun cancelArScreenshotWifiCountdown() {
        arWifiCountdownRunnable?.let { mainHandler.removeCallbacks(it) }
        arWifiCountdownRunnable = null
        arScreenshotWifiRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        arScreenshotWifiRetryRunnable = null
        arScreenshotWifiCountdownSec = 0
    }

    private fun onArScreenshotWifiTimeout() {
        Log.w(LOG_TAG, "[ArScreenshot] WiFi countdown timeout (60s)")
        cancelArScreenshotWifiCountdown()
        arScreenshotWifiPreparing = false
        runCatching {
            CxrApi.getInstance().deinitWifiP2P()
            Log.d(LOG_TAG, "[ArScreenshot] deinitWifiP2P after timeout")
        }.onFailure { e ->
            Log.w(LOG_TAG, "[ArScreenshot] deinitWifiP2P after timeout: ${e.message}")
        }
        if (!isFinishing) {
            showArScreenshotWifiTimeoutDialog = true
        }
    }

    /**
     * 快门倒计时结束时调用：[overlayTextSnapshot] 为当时无障碍采集到的文字，用于叠字（避免同步完成时文字已变）。
     */
    private fun executePhotoSyncAfterShutter(overlayTextSnapshot: String) {
        if (!glassesConnected) {
            showToast("请先连接智能眼镜")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(LOG_TAG, "NEARBY_WIFI_DEVICES not granted, requesting")
            showToast("需要附近设备权限以同步图片")
            sdkPermissionLauncher.launch(arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES))
            return
        }
        if (photoSyncInProgress) {
            showToast("拍照同步进行中")
            return
        }

        photoSyncInProgress = true
        photoSyncAttemptSeq += 1
        val attemptId = photoSyncAttemptSeq
        val quality = PHOTO_SYNC_QUALITY
        val h = PHOTO_SYNC_API_HEIGHT
        val w = PHOTO_SYNC_API_WIDTH
        Log.d(LOG_TAG, "[PhotoSync#$attemptId] open/take ${h}x${w} quality=$quality snapshotLen=${overlayTextSnapshot.length}")

        val callback = object : PhotoPathCallback {
            override fun onPhotoPath(status: ValueUtil.CxrStatus?, path: String?) {
                Log.d(LOG_TAG, "[PhotoSync#$attemptId] onPhotoPath status=$status path=$path")
                if (status != ValueUtil.CxrStatus.RESPONSE_SUCCEED || path.isNullOrBlank()) {
                    photoSyncInProgress = false
                    showToast("拍照路径回调失败")
                    return
                }
                startSyncSinglePicture(attemptId, path, overlayTextSnapshot)
            }
        }

        val openStatus = try {
            CxrApi.getInstance().openGlassCamera(h, w, quality)
        } catch (e: Exception) {
            photoSyncInProgress = false
            Log.e(LOG_TAG, "[PhotoSync#$attemptId] openGlassCamera exception: ${e.message}", e)
            showToast("打开相机异常")
            return
        }
        Log.d(LOG_TAG, "[PhotoSync#$attemptId] openGlassCamera status=$openStatus")
        if (openStatus == ValueUtil.CxrStatus.REQUEST_FAILED) {
            photoSyncInProgress = false
            showToast("打开相机失败")
            return
        }

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val requestStatus = try {
                CxrApi.getInstance().takeGlassPhoto(h, w, quality, callback)
            } catch (e: Exception) {
                photoSyncInProgress = false
                Log.e(LOG_TAG, "[PhotoSync#$attemptId] takeGlassPhoto exception: ${e.message}", e)
                showToast("路径请求异常")
                return@postDelayed
            }

            Log.d(LOG_TAG, "[PhotoSync#$attemptId] request status=$requestStatus")
            if (requestStatus == ValueUtil.CxrStatus.REQUEST_FAILED) {
                photoSyncInProgress = false
                showToast("路径请求失败")
            }
        }, 400)
    }

    private fun startSyncSinglePicture(
        attemptId: Int,
        glassesMediaPath: String,
        overlayTextSnapshot: String = ""
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(LOG_TAG, "[PhotoSync#$attemptId] NEARBY_WIFI_DEVICES not granted before P2P")
            photoSyncInProgress = false
            showToast("需要附近设备权限以同步图片")
            sdkPermissionLauncher.launch(arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES))
            return
        }
        // SDK 会把 savePath 与文件名直接拼接，必须带尾部分隔符，否则得到 …/incomingimage-xxx.jpg
        val incomingDir = File(getExternalFilesDir("photo_sync"), "incoming").apply { mkdirs() }
        val savePath = incomingDir.absolutePath.let { p ->
            if (p.endsWith(File.separator)) p else p + File.separator
        }
        val api = CxrApi.getInstance()
        val baseName = File(glassesMediaPath).name
        val glassesPathTrim = glassesMediaPath.trim()
        val fullGlassesPath =
            if (glassesPathTrim.startsWith("/") && glassesPathTrim != baseName) glassesPathTrim else null
        val mainHandler = Handler(Looper.getMainLooper())
        val syncFinished = AtomicBoolean(false)

        fun finishSync(success: Boolean, message: String? = null) {
            if (!syncFinished.compareAndSet(false, true)) return
            photoSyncInProgress = false
            deinitWifiP2PAfterPhotoSync(attemptId)
            showToast(message ?: if (success) "拍照并同步完成" else "图片同步失败")
        }

        fun runSyncWithArg(syncArg: String, alternateArg: String?) {
            var localSyncedPath: String? = null
            val syncCallback = object : SyncStatusCallback {
                override fun onSyncStart() {
                    Log.d(LOG_TAG, "[PhotoSync#$attemptId] sync start syncArg=$syncArg")
                }

                override fun onSingleFileSynced(name: String?) {
                    localSyncedPath = name
                    Log.d(LOG_TAG, "[PhotoSync#$attemptId] synced file=$name savePath=$savePath")
                }

                override fun onSyncFailed() {
                    Log.e(LOG_TAG, "[PhotoSync#$attemptId] sync failed syncArg=$syncArg")
                    val next = alternateArg
                    if (next != null && next != syncArg) {
                        Log.w(LOG_TAG, "[PhotoSync#$attemptId] retry sync alternate=$next")
                        mainHandler.postDelayed({
                            runSyncWithArg(next, alternateArg = null)
                        }, SYNC_FAILURE_RETRY_DELAY_MS)
                    } else {
                        finishSync(false)
                    }
                }

                override fun onSyncFinished() {
                    Log.d(LOG_TAG, "[PhotoSync#$attemptId] sync finished savePath=$savePath")
                    CoroutineScope(Dispatchers.Main).launch {
                        val localPath = localSyncedPath ?: File(incomingDir, baseName).absolutePath
                        val (resultFile, galleryUri, didComposeOverlay) = withContext(Dispatchers.IO) {
                            val f = File(localPath)
                            if (!f.isFile) {
                                return@withContext Triple(null, null, false)
                            }
                            if (!PHOTO_OVERLAY_ENABLED) {
                                val uri = GalleryMediaStore.insertJpegFromFile(this@MainActivity, f)
                                return@withContext Triple(f, uri, false)
                            }
                            if (!CxrCustomViewManager.shouldOverlayTextOnSyncedPhoto()) {
                                Log.d(
                                    LOG_TAG,
                                    "[PhotoSync#$attemptId] skip text overlay (glasses not showing reader text)"
                                )
                                val uri = GalleryMediaStore.insertJpegFromFile(this@MainActivity, f)
                                return@withContext Triple(f, uri, false)
                            }
                            val rawOverlay = overlayTextSnapshot.ifBlank {
                                ScreenTextPublisher.state.value.text
                            }
                            val displayText = CxrCustomViewManager.computeDisplayTextForOverlay(
                                rawOverlay
                            )
                            val textToDraw =
                                if (CxrCustomViewManager.isPlaceholderDisplayText(displayText)) {
                                    ""
                                } else {
                                    displayText
                                }
                            val overlay = PhotoOverlayComposer.composeAndSaveJpeg(
                                f,
                                File(getExternalFilesDir("photo_sync"), "overlay"),
                                overlayText = textToDraw,
                                textSizeSp = CxrCustomViewManager.getTextSize()
                            )
                            val uri = overlay?.let {
                                GalleryMediaStore.insertJpegFromFile(this@MainActivity, it)
                            }
                            Triple(overlay, uri, true)
                        }
                        if (resultFile != null) {
                            Log.d(LOG_TAG, "[PhotoSync#$attemptId] export source=${resultFile.absolutePath}")
                        } else {
                            Log.w(LOG_TAG, "[PhotoSync#$attemptId] no local file path=$localPath")
                        }
                        if (galleryUri != null) {
                            Log.d(LOG_TAG, "[PhotoSync#$attemptId] gallery uri=$galleryUri")
                        } else if (resultFile != null) {
                            Log.w(LOG_TAG, "[PhotoSync#$attemptId] gallery insert failed")
                        }
                        val msg = if (didComposeOverlay) {
                            when {
                                resultFile != null && galleryUri != null ->
                                    "同步完成，叠字图已加入相册「GlassesReader」"
                                resultFile != null ->
                                    "叠字已保存，写入相册失败（可在应用私有目录查看）"
                                else -> "拍照已同步，叠字未生成"
                            }
                        } else {
                            when {
                                resultFile != null && galleryUri != null ->
                                    "同步完成，原图已加入相册「GlassesReader」"
                                resultFile != null ->
                                    "同步完成，原图已保存，写入相册失败"
                                else -> "拍照已同步，未取得本地文件"
                            }
                        }
                        finishSync(true, msg)
                    }
                }
            }

            val ok = runCatching {
                api.syncSingleFile(savePath, ValueUtil.CxrMediaType.PICTURE, syncArg, syncCallback)
            }.getOrElse { e ->
                Log.e(LOG_TAG, "[PhotoSync#$attemptId] syncSingleFile threw: ${e.message}", e)
                false
            }
            Log.d(LOG_TAG, "[PhotoSync#$attemptId] syncSingleFile accepted=$ok syncArg=$syncArg")
            if (!ok) {
                val next = alternateArg
                if (next != null && next != syncArg) {
                    mainHandler.postDelayed({
                        runSyncWithArg(next, alternateArg = null)
                    }, SYNC_FAILURE_RETRY_DELAY_MS)
                } else {
                    finishSync(false)
                }
            }
        }

        val startSyncAfterReady = {
            // 实机日志：仅传文件名会同步失败，眼镜端绝对路径可成功；优先完整路径，再回退文件名
            if (fullGlassesPath != null) {
                runSyncWithArg(fullGlassesPath, alternateArg = baseName)
            } else {
                runSyncWithArg(baseName, alternateArg = null)
            }
        }

        val wifiReady = runCatching { api.isWifiP2PConnected() }.getOrDefault(false)
        if (wifiReady) {
            Log.d(LOG_TAG, "[PhotoSync#$attemptId] wifi already connected")
            mainHandler.postDelayed(startSyncAfterReady, POST_WIFI_SYNC_DELAY_MS)
            return
        }

        runCatching { api.deinitWifiP2P() }
            .onFailure { Log.w(LOG_TAG, "[PhotoSync#$attemptId] deinitWifiP2P: ${it.message}") }

        val initStatus = api.initWifiP2P(object : WifiP2PStatusCallback {
            override fun onConnected() {
                Log.d(LOG_TAG, "[PhotoSync#$attemptId] wifi connected")
                mainHandler.postDelayed(startSyncAfterReady, POST_WIFI_SYNC_DELAY_MS)
            }

            override fun onDisconnected() {
                Log.d(LOG_TAG, "[PhotoSync#$attemptId] wifi disconnected")
            }

            override fun onFailed(errorCode: ValueUtil.CxrWifiErrorCode?) {
                Log.e(LOG_TAG, "[PhotoSync#$attemptId] wifi init failed error=$errorCode")
                finishSync(false, "Wi-Fi连接失败")
            }
        })
        Log.d(LOG_TAG, "[PhotoSync#$attemptId] initWifiP2P status=$initStatus")
        if (initStatus == ValueUtil.CxrStatus.REQUEST_FAILED) {
            finishSync(false, "Wi-Fi初始化失败")
        }
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
    


    companion object {
        const val LOG_TAG = "MainActivity"

        private var uiInstance: MainActivity? = null

        fun handleArShutterBroadcast(snapshotText: String) {
            val act = synchronized(MainActivity::class.java) { uiInstance }
            act?.runOnUiThread {
                act.executePhotoSyncAfterShutter(snapshotText)
            }
        }

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
