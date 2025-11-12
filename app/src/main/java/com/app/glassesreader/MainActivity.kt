package com.app.glassesreader

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.app.glassesreader.accessibility.service.ScreenTextService
import com.app.glassesreader.sdk.CxrConnectionManager
import com.app.glassesreader.sdk.CxrCustomViewManager
import com.app.glassesreader.service.overlay.TextOverlayService
import com.app.glassesreader.ui.screens.DeviceScanActivity
import com.app.glassesreader.ui.theme.GlassesReaderTheme

/**
 * MainActivity 用於引導使用者授權並啟動浮窗服務。
 */
class MainActivity : ComponentActivity() {

    private var overlayPermissionGranted by mutableStateOf(false)
    private var accessibilityEnabled by mutableStateOf(false)
    private var notificationGranted by mutableStateOf(true)
    private var sdkPermissionsGranted by mutableStateOf(false)
    private var serviceRunning by mutableStateOf(false)
    private var glassesConnected by mutableStateOf(false)
    private lateinit var requiredSdkPermissions: Array<String>
    private val connectionManager = CxrConnectionManager.getInstance()

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
        requiredSdkPermissions = createRequiredSdkPermissionArray()
        refreshPermissionStates()
        
        setContent {
            GlassesReaderTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        overlayGranted = overlayPermissionGranted,
                        accessibilityGranted = accessibilityEnabled,
                        notificationGranted = notificationGranted,
                        sdkPermissionsGranted = sdkPermissionsGranted,
                        serviceRunning = serviceRunning,
                        glassesConnected = glassesConnected,
                        onRequestOverlay = ::openOverlaySettings,
                        onRequestAccessibility = ::openAccessibilitySettings,
                        onRequestNotification = ::requestNotificationPermission,
                        onRequestSdkPermissions = ::requestSdkPermissions,
                        onStartService = ::startOverlayService,
                        onStopService = ::stopOverlayService,
                        onOpenDeviceScan = ::openDeviceScan
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStates()
        checkConnectionStatus()
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

        Log.d(LOG_TAG, "Overlay permission granted: $overlayPermissionGranted")
        Log.d(LOG_TAG, "Accessibility service enabled: $accessibilityEnabled")
        Log.d(LOG_TAG, "Notification permission granted: $notificationGranted")
        Log.d(LOG_TAG, "SDK permissions granted: $sdkPermissionsGranted")
        Log.d(LOG_TAG, "Overlay service running: $serviceRunning")

        if (sdkPermissionsGranted) {
            checkConnectionStatus()
            if (glassesConnected) {
                CxrCustomViewManager.ensureInitialized()
            }
        }
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

    private fun startOverlayService() {
        if (!overlayPermissionGranted || !accessibilityEnabled || !sdkPermissionsGranted) {
            return
        }
        TextOverlayService.start(this)
        serviceRunning = true
        if (glassesConnected) {
            CxrCustomViewManager.ensureInitialized()
        }
    }

    private fun stopOverlayService() {
        TextOverlayService.stop(this)
        serviceRunning = false
        CxrCustomViewManager.close()
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

    companion object {
        const val LOG_TAG = "MainActivity"
    }
}

@Composable
private fun MainScreen(
    overlayGranted: Boolean,
    accessibilityGranted: Boolean,
    notificationGranted: Boolean,
    sdkPermissionsGranted: Boolean,
    serviceRunning: Boolean,
    glassesConnected: Boolean,
    onRequestOverlay: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onRequestNotification: () -> Unit,
    onRequestSdkPermissions: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onOpenDeviceScan: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "GlassesReader 屏幕读取器",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Text(
            text = "1. 开启悬浮窗权限\n2. 在无障碍设置中启用 GlassesReader 屏幕文字采集\n3. 授权蓝牙和定位权限以连接智能眼镜\n4. 启动服务后即可显示测试浮窗",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        StatusBadge(
            label = "悬浮窗权限",
            enabled = overlayGranted
        )
        StatusBadge(
            label = "无障碍服务",
            enabled = accessibilityGranted
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            StatusBadge(
                label = "通知权限",
                enabled = notificationGranted
            )
        }
        StatusBadge(
            label = "设备通讯权限",
            enabled = sdkPermissionsGranted
        )
        StatusBadge(
            label = "智能眼镜连接",
            enabled = glassesConnected
        )

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = onRequestOverlay,
            enabled = !overlayGranted,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (overlayGranted) "悬浮窗已授权" else "前往授权悬浮窗")
        }

        Button(
            onClick = onRequestAccessibility,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("前往开启无障碍服务")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationGranted) {
            OutlinedButton(
                onClick = onRequestNotification,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("授权通知以维持前台服务")
            }
        }

        OutlinedButton(
            onClick = onRequestSdkPermissions,
            enabled = !sdkPermissionsGranted,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (sdkPermissionsGranted) "设备通讯权限已完成" else "授权蓝牙/定位等通讯权限")
        }

        if (sdkPermissionsGranted) {
            OutlinedButton(
                onClick = onOpenDeviceScan,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (glassesConnected) "重新连接智能眼镜" else "连接智能眼镜")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        val startButtonEnabled = overlayGranted && accessibilityGranted && sdkPermissionsGranted && glassesConnected

        Button(
            onClick = onStartService,
            enabled = startButtonEnabled && !serviceRunning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (serviceRunning) "服务运行中" else "启动服务")
        }

        OutlinedButton(
            onClick = onStopService,
            enabled = serviceRunning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("停止服务")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = if (serviceRunning) {
                "测试浮窗已启动，可在阅读时观察文字更新。"
            } else {
                "启动服务后，浮窗开关预设位于屏幕右侧中部，可拖曳调整位置。"
            },
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatusBadge(
    label: String,
    enabled: Boolean
) {
    val background = if (enabled) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(
        color = background,
        shape = MaterialTheme.shapes.medium
    ) {
        Text(
            text = if (enabled) "$label：已完成" else "$label：待处理",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = contentColor
        )
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
