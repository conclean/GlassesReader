package com.app.glassesreader.ui.screens

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.app.glassesreader.sdk.BluetoothHelper
import com.app.glassesreader.sdk.CxrConnectionManager
import com.app.glassesreader.sdk.CxrCustomViewManager
import com.app.glassesreader.ui.components.DeviceList
import CustomIconButton
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.ui.graphics.luminance
import com.app.glassesreader.ui.theme.GlassesReaderTheme
import com.app.glassesreader.ui.theme.DarkButtonBackground
import com.app.glassesreader.ui.theme.LightButtonBackground
import com.rokid.cxr.client.utils.ValueUtil

/**
 * 设备扫描和连接页面
 */
class DeviceScanActivity : ComponentActivity() {

    companion object {
        private const val TAG = "DeviceScanActivity"
        // 连接验证延迟时间（给 SDK 时间完成连接）
        private const val VERIFICATION_DELAY_MS = 1000L
        // 成功提示显示时间
        private const val SUCCESS_DELAY_MS = 2000L
        private const val PREF_APP_SETTINGS = "gr_app_settings"
        private const val KEY_DARK_THEME = "dark_theme"
    }

    private var isScanning by mutableStateOf(false)
    private var devices by mutableStateOf<List<BluetoothDevice>>(emptyList())
    private var isConnecting by mutableStateOf(false)
    private var connectionStatus by mutableStateOf<String?>(null)
    private var bluetoothHelper: BluetoothHelper? = null
    private val connectionManager = CxrConnectionManager.getInstance()
    private lateinit var appPrefs: SharedPreferences
    
    // Handler 用于延迟任务，需要在 onDestroy 中清理
    private val mainHandler = Handler(Looper.getMainLooper())
    private val verificationRunnable = Runnable {
        verifyConnection()
    }
    private val finishRunnable = Runnable {
        setResult(RESULT_OK)
        finish()
    }

    private val bluetoothEnableLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (bluetoothHelper?.isBluetoothEnabled() == true) {
                startScan()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 读取主题设置
        appPrefs = getSharedPreferences(PREF_APP_SETTINGS, Context.MODE_PRIVATE)
        val systemDarkMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val isDarkTheme = appPrefs.getBoolean(KEY_DARK_THEME, systemDarkMode)

        // 注册生命周期监听
        lifecycle.addObserver(object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        checkConnectionStatus()
                    }
                    Lifecycle.Event.ON_PAUSE -> {
                        bluetoothHelper?.stopScan()
                    }
                    Lifecycle.Event.ON_DESTROY -> {
                        // 清理所有延迟任务
                        mainHandler.removeCallbacks(verificationRunnable)
                        mainHandler.removeCallbacks(finishRunnable)
                        bluetoothHelper?.release()
                        bluetoothHelper = null
                    }
                    else -> {}
                }
            }
        })

        initBluetoothHelper()
        checkConnectionStatus()

        setContent {
            GlassesReaderTheme(darkTheme = isDarkTheme) {
                Scaffold { innerPadding ->
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        DeviceScanScreen(
                            isScanning = isScanning,
                            devices = devices,
                            isConnecting = isConnecting,
                            connectionStatus = connectionStatus,
                            isConnected = connectionManager.isConnected(),
                            onStartScan = ::startScan,
                            onStopScan = ::stopScan,
                            onDeviceSelected = ::connectDevice,
                            isDeviceConnected = { device ->
                                bluetoothHelper?.isDeviceConnected(device) ?: false
                            },
                            onBack = { finish() }
                        )
                    }
                }
            }
        }
    }

    private fun initBluetoothHelper() {
        if (bluetoothHelper == null) {
            bluetoothHelper = BluetoothHelper(this).apply {
                registerBluetoothStateListener()
                initStatus.observe(this@DeviceScanActivity) { status ->
                    isScanning = status == BluetoothHelper.InitStatus.INITING
                }
                deviceFound.observe(this@DeviceScanActivity) {
                    val helper = bluetoothHelper
                    devices = helper?.getAllDevices() ?: emptyList()
                }
            }
        }
    }

    private fun startScan() {
        // 检查是否已连接
        val alreadyConnected = connectionManager.isConnected()
        if (alreadyConnected) {
            Log.d(TAG, "Already connected, skip scan")
            connectionStatus = "已连接，无需扫描"
            return
        }

        val helper = bluetoothHelper ?: return

        if (!helper.isBluetoothEnabled()) {
            helper.requestBluetoothEnable(bluetoothEnableLauncher)
            return
        }

        helper.startScan()
        Log.d(TAG, "Bluetooth scan started")
    }

    private fun stopScan() {
        bluetoothHelper?.stopScan()
        isScanning = false
        Log.d(TAG, "Bluetooth scan stopped")
    }

    private fun connectDevice(device: BluetoothDevice) {
        Log.d(TAG, "=== Attempting to connect device ===")
        
        // 检查是否已连接
        val alreadyConnected = connectionManager.isConnected()
        if (alreadyConnected) {
            Log.d(TAG, "Already connected, skip connection attempt")
            connectionStatus = "已连接，无需重复连接"
            return
        }
        
        // 记录设备状态信息（但不阻止连接，因为 BLE 支持多连接）
        val deviceInfo = bluetoothHelper?.getDeviceInfo(device) ?: "无法获取设备信息"
        Log.d(TAG, deviceInfo)
        
        // 注意：BLE 支持多连接，即使设备已被其他应用连接，我们也可以尝试连接
        // 如果连接失败，SDK 会返回相应的错误码
        
        Log.d(TAG, "Proceeding with connection attempt (BLE supports multiple connections)")
        Log.d(TAG, "Connecting to device: ${device.name}, address: ${device.address}")
        
        stopScan()
        isConnecting = true
        connectionStatus = "正在连接..."

        connectionManager.connectDevice(
            this,
            device,
            object : CxrConnectionManager.ConnectionCallback {
                override fun onConnected() {
                    Log.d(TAG, "Device connected successfully")
                    isConnecting = false
                    connectionStatus = "连接成功！正在验证..."
                    
                    // 延迟验证连接状态，给 SDK 时间完成连接
                    mainHandler.postDelayed(verificationRunnable, VERIFICATION_DELAY_MS)
                }

                override fun onDisconnected() {
                    Log.d(TAG, "Device disconnected")
                    isConnecting = false
                    connectionStatus = "连接断开"
                }

                override fun onFailed(errorCode: ValueUtil.CxrBluetoothErrorCode?) {
                    Log.e(TAG, "Connection failed: $errorCode")
                    isConnecting = false
                    // 统一为简洁的连接失败提示，不再区分具体错误码文案
                    connectionStatus = "连接失败，请重试"
                }

                override fun onConnectionInfo(
                    socketUuid: String?,
                    macAddress: String?,
                    rokidAccount: String?,
                    glassesType: Int
                ) {
                    Log.d(
                        TAG,
                        "Connection info - UUID: $socketUuid, MAC: $macAddress, " +
                            "Account: $rokidAccount, Type: $glassesType"
                    )
                }
            }
        )
    }

    private fun checkConnectionStatus() {
        val connected = connectionManager.isConnected()
        connectionStatus = if (connected) "已连接" else null
        Log.d(TAG, "Connection status: $connected")
    }

    /**
     * 验证连接状态并处理后续逻辑
     */
    private fun verifyConnection() {
        val verified = connectionManager.isConnected()
        if (verified) {
            connectionStatus = "连接成功并已验证！"
            Log.d(TAG, "Connection verified successfully")
            CxrCustomViewManager.ensureInitialized()
            // 延迟返回主页面，让用户看到成功提示
            mainHandler.postDelayed(finishRunnable, SUCCESS_DELAY_MS)
        } else {
            connectionStatus = "连接成功但验证失败，请重试"
            Log.w(TAG, "Connection verification failed")
        }
    }

}

@Composable
private fun DeviceScanScreen(
    isScanning: Boolean,
    devices: List<BluetoothDevice>,
    isConnecting: Boolean,
    connectionStatus: String?,
    isConnected: Boolean,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onDeviceSelected: (BluetoothDevice) -> Unit,
    isDeviceConnected: (BluetoothDevice) -> Boolean,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // 与主页/设置页保持统一的内容内边距（顶部留白由 Scaffold 处理）
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.Start
    ) {
        // 顶部返回按钮 + 标题，风格与设置页统一
        Row(
            modifier = Modifier
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CustomIconButton(
                onClick = onBack,
                size = 56.dp,
                containerColor = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
                    DarkButtonBackground
                } else {
                    LightButtonBackground
                },
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                androidx.compose.material3.Icon(
                    imageVector = androidx.compose.material.icons.Icons.Default.ArrowBack,
                    contentDescription = "返回",
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "设备连接",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
        }

        // 提示信息，使用圆角矩形与浅背景，风格与设置页提示统一
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "蓝牙连接说明",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "如已连接官方应用，请按三次眼镜按钮，完成与系统蓝牙的配对后，再在此页面扫描并选择目标眼镜进行连接。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (isConnected) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    text = "智能眼镜：已连接",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        if (connectionStatus != null) {
            Text(
                text = connectionStatus,
                style = MaterialTheme.typography.bodyMedium,
                color = if (connectionStatus.startsWith("连接失败") || connectionStatus.contains("断开")) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (!isConnected) {
            Button(
                onClick = if (isScanning) onStopScan else onStartScan,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isConnecting
            ) {
                Text(if (isScanning) "停止扫描" else "开始扫描")
            }
        }

        if (devices.isNotEmpty()) {
            Text(
                text = "选择要连接的设备",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.fillMaxWidth()
            )
            DeviceList(
                devices = devices,
                isLoading = isScanning,
                onDeviceSelected = onDeviceSelected,
                isDeviceConnected = isDeviceConnected,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        } else if (isScanning) {
            Text(
                text = "正在扫描设备...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

