package com.app.glassesreader.ui.screens

import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.app.glassesreader.sdk.BluetoothHelper
import com.app.glassesreader.sdk.CxrConnectionManager
import com.app.glassesreader.sdk.CxrCustomViewManager
import com.app.glassesreader.ui.components.DeviceList
import com.app.glassesreader.ui.theme.GlassesReaderTheme
import com.rokid.cxr.client.utils.ValueUtil

/**
 * 设备扫描和连接页面
 */
class DeviceScanActivity : ComponentActivity() {

    private var isScanning by mutableStateOf(false)
    private var devices by mutableStateOf<List<BluetoothDevice>>(emptyList())
    private var isConnecting by mutableStateOf(false)
    private var connectionStatus by mutableStateOf<String?>(null)
    private var bluetoothHelper: BluetoothHelper? = null
    private val connectionManager = CxrConnectionManager.getInstance()

    private val bluetoothEnableLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (bluetoothHelper?.isBluetoothEnabled() == true) {
                startScan()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
            GlassesReaderTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
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
                    
                    // 延迟一下再检查连接状态，给 SDK 时间完成连接
                    Handler(Looper.getMainLooper()).postDelayed({
                        val verified = connectionManager.isConnected()
                        if (verified) {
                            connectionStatus = "连接成功并已验证！"
                            Log.d(TAG, "Connection verified: ${connectionManager.isConnected()}")
                            CxrCustomViewManager.ensureInitialized()
                            // 延迟返回主页面
                            Handler(Looper.getMainLooper()).postDelayed({
                                setResult(RESULT_OK)
                                finish()
                            }, 2000)
                        } else {
                            connectionStatus = "连接成功但验证失败，请重试"
                            Log.w(TAG, "Connection not verified: ${connectionManager.isConnected()}")
                        }
                    }, 1000)
                }

                override fun onDisconnected() {
                    Log.d(TAG, "Device disconnected")
                    isConnecting = false
                    connectionStatus = "连接断开"
                }

                override fun onFailed(errorCode: ValueUtil.CxrBluetoothErrorCode?) {
                    Log.e(TAG, "Connection failed: $errorCode")
                    isConnecting = false
                    
                    // 根据错误码提供更详细的错误信息
                    val errorMessage = when (errorCode) {
                        ValueUtil.CxrBluetoothErrorCode.BLE_CONNECT_FAILED -> {
                            "连接失败：BLE 连接失败，可能是设备不支持多连接或设备忙"
                        }
                        ValueUtil.CxrBluetoothErrorCode.SOCKET_CONNECT_FAILED -> {
                            "连接失败：Socket 连接失败，可能是设备不支持多连接或网络问题"
                        }
                        ValueUtil.CxrBluetoothErrorCode.PARAM_INVALID -> {
                            "连接失败：参数无效"
                        }
                        ValueUtil.CxrBluetoothErrorCode.UNKNOWN -> {
                            "连接失败：未知错误"
                        }
                        null -> {
                            "连接失败：未知错误"
                        }
                        else -> {
                            "连接失败：$errorCode"
                        }
                    }
                    connectionStatus = errorMessage
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

    companion object {
        private const val TAG = "DeviceScanActivity"
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
            .padding(horizontal = 20.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "扫描智能眼镜",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        // 提示信息
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.small
        ) {
            Text(
                text = "提示：如已连接官方应用，请先断开其蓝牙配对",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }

        if (isConnected) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    text = "智能眼镜：已连接",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        if (connectionStatus != null) {
            Text(
                text = connectionStatus,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
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
                text = "选择要连接的设备：",
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

        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("返回")
        }
    }
}

