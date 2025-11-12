package com.app.glassesreader.sdk

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.util.concurrent.ConcurrentHashMap

/**
 * 蓝牙设备扫描和发现辅助类。
 * 负责扫描 Rokid 眼镜设备，检查已配对和已连接的设备。
 */
class BluetoothHelper(
    private val context: Context
) {
    companion object {
        private const val TAG = "BluetoothHelper"
        const val ROKID_SERVICE_UUID = "00009100-0000-1000-8000-00805f9b34fb"
    }

    enum class InitStatus {
        NotStart,
        INITING,
        INIT_END
    }

    // 扫描结果
    val scanResultMap: ConcurrentHashMap<String, BluetoothDevice> = ConcurrentHashMap()
    // 已配对设备
    val bondedDeviceMap: ConcurrentHashMap<String, BluetoothDevice> = ConcurrentHashMap()

    private val _initStatus = MutableLiveData<InitStatus>(InitStatus.NotStart)
    val initStatus: LiveData<InitStatus> = _initStatus

    private val _deviceFound = MutableLiveData<Unit>()
    val deviceFound: LiveData<Unit> = _deviceFound

    private val manager: BluetoothManager? by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }

    private val adapter: BluetoothAdapter? by lazy {
        manager?.adapter
    }

    private val scanner by lazy {
        adapter?.bluetoothLeScanner ?: run {
            Log.e(TAG, "Bluetooth is not supported")
            null
        }
    }

    private val scanListener = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            result?.device?.name?.let { name ->
                if (name.contains("Glasses", ignoreCase = true)) {
                    scanResultMap[name] = result.device
                    _deviceFound.postValue(Unit)
                    Log.d(TAG, "Found device: $name, address: ${result.device.address}")
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e(TAG, "Scan failed with error code: $errorCode")
        }
    }

    private val bluetoothStateListener = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(
                    BluetoothAdapter.EXTRA_STATE,
                    BluetoothAdapter.ERROR
                )
                if (state == BluetoothAdapter.STATE_OFF) {
                    _initStatus.postValue(InitStatus.NotStart)
                    Log.d(TAG, "Bluetooth turned off")
                } else if (state == BluetoothAdapter.STATE_ON) {
                    Log.d(TAG, "Bluetooth turned on")
                    startScan()
                }
            }
        }
    }

    /**
     * 检查蓝牙是否已启用
     */
    fun isBluetoothEnabled(): Boolean {
        return adapter?.isEnabled == true
    }

    /**
     * 请求启用蓝牙
     */
    fun requestBluetoothEnable(launcher: ActivityResultLauncher<Intent>) {
        if (adapter == null) {
            Log.e(TAG, "Bluetooth adapter is null")
            return
        }
        if (!adapter!!.isEnabled) {
            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            launcher.launch(intent)
        }
    }

    /**
     * 开始扫描蓝牙设备
     */
    @SuppressLint("MissingPermission")
    fun startScan() {
        if (scanner == null) {
            Log.e(TAG, "Bluetooth scanner is null")
            return
        }

        if (!isBluetoothEnabled()) {
            Log.w(TAG, "Bluetooth is not enabled")
            return
        }

        scanResultMap.clear()
        bondedDeviceMap.clear()

        // 检查已连接的设备
        getConnectedDevices().forEach { device ->
            device.name?.let { name ->
                if (name.contains("Glasses", ignoreCase = true)) {
                    bondedDeviceMap[name] = device
                    _deviceFound.postValue(Unit)
                    Log.d(TAG, "Found connected device: $name")
                }
            }
        }

        // 检查已配对的设备
        adapter?.bondedDevices?.forEach { device ->
            device.name?.let { name ->
                if (name.contains("Glasses", ignoreCase = true) && bondedDeviceMap[name] == null) {
                    bondedDeviceMap[name] = device
                    _deviceFound.postValue(Unit)
                    Log.d(TAG, "Found bonded device: $name")
                }
            }
        }

        // 开始 BLE 扫描
        try {
            _initStatus.postValue(InitStatus.INITING)
            scanner?.startScan(
                listOf(
                    ScanFilter.Builder()
                        .setServiceUuid(ParcelUuid.fromString(ROKID_SERVICE_UUID))
                        .build()
                ),
                ScanSettings.Builder().build(),
                scanListener
            )
            _initStatus.postValue(InitStatus.INIT_END)
            Log.d(TAG, "Bluetooth scan started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start scan: ${e.message}", e)
            _initStatus.postValue(InitStatus.NotStart)
        }
    }

    /**
     * 停止扫描
     */
    @SuppressLint("MissingPermission")
    fun stopScan() {
        try {
            scanner?.stopScan(scanListener)
            Log.d(TAG, "Bluetooth scan stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop scan: ${e.message}", e)
        }
    }

    /**
     * 获取已连接的设备列表
     */
    @SuppressLint("MissingPermission")
    private fun getConnectedDevices(): List<BluetoothDevice> {
        return adapter?.bondedDevices?.filter { device ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Android 12+ 使用反射检查连接状态
                    device::class.java.getMethod("isConnected").invoke(device) as Boolean
                } else {
                    // Android 11 及以下，假设已配对的设备可能已连接
                    true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to check connection status: ${e.message}")
                false
            }
        } ?: emptyList()
    }

    /**
     * 注册蓝牙状态监听器
     */
    fun registerBluetoothStateListener() {
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        context.registerReceiver(bluetoothStateListener, filter)
        Log.d(TAG, "Bluetooth state listener registered")
    }

    /**
     * 注销蓝牙状态监听器
     */
    fun unregisterBluetoothStateListener() {
        try {
            context.unregisterReceiver(bluetoothStateListener)
            Log.d(TAG, "Bluetooth state listener unregistered")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister receiver: ${e.message}")
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        stopScan()
        unregisterBluetoothStateListener()
        scanResultMap.clear()
        bondedDeviceMap.clear()
        _initStatus.postValue(InitStatus.NotStart)
        Log.d(TAG, "BluetoothHelper released")
    }

    /**
     * 检查设备是否已被其他应用连接
     */
    @SuppressLint("MissingPermission")
    fun isDeviceConnected(device: BluetoothDevice): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ 使用反射检查连接状态
                device::class.java.getMethod("isConnected").invoke(device) as Boolean
            } else {
                // Android 11 及以下，检查设备类型和状态
                val bondState = device.bondState
                val isBonded = bondState == BluetoothDevice.BOND_BONDED
                // 对于已配对的设备，尝试检查连接状态
                if (isBonded) {
                    try {
                        // 尝试通过反射检查
                        device::class.java.getMethod("isConnected").invoke(device) as Boolean
                    } catch (e: Exception) {
                        // 如果反射失败，返回false（不确定状态）
                        false
                    }
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check device connection status: ${e.message}")
            false
        }
    }

    /**
     * 获取设备的详细信息（用于调试）
     */
    @SuppressLint("MissingPermission")
    fun getDeviceInfo(device: BluetoothDevice): String {
        return try {
            val bondState = when (device.bondState) {
                BluetoothDevice.BOND_NONE -> "未配对"
                BluetoothDevice.BOND_BONDING -> "配对中"
                BluetoothDevice.BOND_BONDED -> "已配对"
                else -> "未知"
            }
            val isConnected = isDeviceConnected(device)
            val type = when (device.type) {
                BluetoothDevice.DEVICE_TYPE_CLASSIC -> "经典蓝牙"
                BluetoothDevice.DEVICE_TYPE_LE -> "BLE"
                BluetoothDevice.DEVICE_TYPE_DUAL -> "双模"
                else -> "未知"
            }
            "设备: ${device.name ?: "未知"}, 地址: ${device.address}, " +
                "类型: $type, 配对状态: $bondState, 连接状态: ${if (isConnected) "已连接" else "未连接"}"
        } catch (e: Exception) {
            "获取设备信息失败: ${e.message}"
        }
    }

    /**
     * 获取所有发现的设备（扫描到的 + 已配对的）
     */
    fun getAllDevices(): List<BluetoothDevice> {
        val allDevices = mutableSetOf<BluetoothDevice>()
        scanResultMap.values.forEach { allDevices.add(it) }
        bondedDeviceMap.values.forEach { allDevices.add(it) }
        return allDevices.toList()
    }
}
