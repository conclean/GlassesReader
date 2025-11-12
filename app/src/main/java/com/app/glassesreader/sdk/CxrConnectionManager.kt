package com.app.glassesreader.sdk

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.callbacks.BluetoothStatusCallback
import com.rokid.cxr.client.utils.ValueUtil

/**
 * 统一管理 CXR-M SDK 的蓝牙连接流程。
 * 负责初始化、连接、断开连接和状态管理。
 */
class CxrConnectionManager private constructor() {

    companion object {
        private const val TAG = "CxrConnectionManager"
        @Volatile
        private var INSTANCE: CxrConnectionManager? = null

        fun getInstance(): CxrConnectionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CxrConnectionManager().also { INSTANCE = it }
            }
        }
    }

    @Volatile
    private var isConnecting = false

    @Volatile
    private var socketUuid: String? = null

    @Volatile
    private var macAddress: String? = null

    private var connectionCallback: ConnectionCallback? = null
    
    private var timeoutHandler: Handler? = null
    private var timeoutRunnable: Runnable? = null

    /**
     * 连接状态回调接口
     */
    interface ConnectionCallback {
        fun onConnected()
        fun onDisconnected()
        fun onFailed(errorCode: ValueUtil.CxrBluetoothErrorCode?)
        fun onConnectionInfo(
            socketUuid: String?,
            macAddress: String?,
            rokidAccount: String?,
            glassesType: Int
        )
    }

    /**
     * 验证连接是否真的成功
     */
    private fun verifyConnection() {
        Log.d(TAG, "=== Verifying Connection ===")
        
        // 1. 检查 SDK 连接状态
        val sdkConnected = try {
            val connected = CxrApi.getInstance().isBluetoothConnected()
            Log.d(TAG, "SDK isBluetoothConnected(): $connected")
            connected
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check SDK connection: ${e.message}", e)
            false
        }
        
        // 2. 尝试打开自定义页面来验证连接
        if (sdkConnected) {
            try {
                Log.d(TAG, "Attempting to open custom view to verify connection...")
                val status = CxrApi.getInstance().openCustomView("""{
                    "type": "LinearLayout",
                    "props": {
                        "layout_width": "match_parent",
                        "layout_height": "match_parent",
                        "orientation": "vertical",
                        "gravity": "center",
                        "backgroundColor": "#CC000000"
                    },
                    "children": [{
                        "type": "TextView",
                        "props": {
                            "id": "test_view",
                            "layout_width": "wrap_content",
                            "layout_height": "wrap_content",
                            "text": "连接测试成功！",
                            "textSize": "24sp",
                            "textColor": "#FFFFFFFF"
                        }
                    }]
                }""")
                Log.d(TAG, "openCustomView test result: $status")
                
                if (status == ValueUtil.CxrStatus.REQUEST_SUCCEED) {
                    Log.d(TAG, "✓ Connection verified: Custom view opened successfully!")
                    // 3秒后关闭测试页面
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            CxrApi.getInstance().closeCustomView()
                            Log.d(TAG, "Test custom view closed")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to close test view: ${e.message}")
                        }
                    }, 3000)
                } else {
                    Log.w(TAG, "⚠ Connection may not be fully established: openCustomView returned $status")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to verify connection via custom view: ${e.message}", e)
            }
        } else {
            Log.w(TAG, "⚠ SDK reports not connected, but onConnected was called")
        }
        
        Log.d(TAG, "=== Connection Verification Complete ===")
    }

    /**
     * 取消超时检测
     */
    private fun cancelTimeout() {
        timeoutHandler?.removeCallbacks(timeoutRunnable ?: return)
        timeoutHandler = null
        timeoutRunnable = null
    }

    /**
     * 初始化蓝牙设备并建立连接
     * 
     * 注意：Rokid 眼镜在同一时间只能与一个应用建立 SDK 连接。
     * 如果官方应用已连接，需要先断开其蓝牙配对，再由本应用发起连接。
     */
    fun connectDevice(
        context: Context,
        device: BluetoothDevice,
        callback: ConnectionCallback?
    ) {
        Log.d(TAG, "=== Connection Check Before Connect ===")
        
        if (isConnecting) {
            Log.w(TAG, "Connection already in progress, skip")
            return
        }

        // 检查 SDK 是否已连接
        val sdkConnected = try {
            val connected = CxrApi.getInstance().isBluetoothConnected()
            Log.d(TAG, "SDK connection status check: $connected")
            connected
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check SDK connection status: ${e.message}")
            false
        }

        if (sdkConnected) {
            Log.d(TAG, "SDK already connected, skip connection")
            Log.d(TAG, "=== Connection Check Complete: Already Connected ===")
            callback?.onConnected()
            return
        }
        
        Log.d(TAG, "SDK not connected, proceeding with connection")
        Log.d(TAG, "=== Connection Check Complete: Proceeding ===")

        // 记录设备详细信息
        Log.d(TAG, "=== Device Connection Info ===")
        Log.d(TAG, "Device name: ${device.name}")
        Log.d(TAG, "Device address: ${device.address}")
        Log.d(TAG, "Device type: ${device.type}")
        Log.d(TAG, "Bond state: ${device.bondState}")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val isConnected = device::class.java.getMethod("isConnected").invoke(device) as Boolean
                Log.d(TAG, "Device isConnected: $isConnected")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot check device connection status: ${e.message}")
        }
        Log.d(TAG, "==============================")

        this.connectionCallback = callback
        isConnecting = true

        Log.d(TAG, "Initializing device: ${device.name}, address: ${device.address}")
        Log.d(TAG, "Calling CxrApi.getInstance().initBluetooth()...")

        try {
            CxrApi.getInstance().initBluetooth(
            context,
            device,
            object : BluetoothStatusCallback {
                override fun onConnectionInfo(
                    socketUuid: String?,
                    macAddress: String?,
                    rokidAccount: String?,
                    glassesType: Int
                ) {
                    Log.d(TAG, "onConnectionInfo callback received! Thread: ${Thread.currentThread().name}")
                    cancelTimeout() // 取消超时检测
                    Log.d(
                        TAG,
                        "Connection info received - UUID: $socketUuid, MAC: $macAddress, " +
                            "Account: $rokidAccount, Type: $glassesType"
                    )

                    this@CxrConnectionManager.socketUuid = socketUuid
                    this@CxrConnectionManager.macAddress = macAddress

                    callback?.onConnectionInfo(socketUuid, macAddress, rokidAccount, glassesType)

                    socketUuid?.let { uuid ->
                        macAddress?.let { mac ->
                            Log.d(TAG, "Attempting to connect with UUID: $uuid, MAC: $mac")
                            connectBluetooth(context, uuid, mac)
                        } ?: run {
                            Log.e(TAG, "macAddress is null")
                            isConnecting = false
                            callback?.onFailed(ValueUtil.CxrBluetoothErrorCode.PARAM_INVALID)
                        }
                    } ?: run {
                        Log.e(TAG, "socketUuid is null")
                        isConnecting = false
                        callback?.onFailed(ValueUtil.CxrBluetoothErrorCode.PARAM_INVALID)
                    }
                }

                override fun onConnected() {
                    Log.d(TAG, "onConnected callback received from initBluetooth! Thread: ${Thread.currentThread().name}")
                    cancelTimeout() // 取消超时检测
                    Log.d(TAG, "initBluetooth onConnected - this may indicate initialization complete, but actual connection happens in connectBluetooth")
                    // 注意：根据示例代码，initBluetooth 的 onConnected() 可能是空的
                    // 真正的连接成功应该在 connectBluetooth 的 onConnected() 中处理
                }

                override fun onDisconnected() {
                    Log.d(TAG, "onDisconnected callback received! Thread: ${Thread.currentThread().name}")
                    cancelTimeout() // 取消超时检测
                    Log.d(TAG, "Bluetooth disconnected")
                    isConnecting = false
                    socketUuid = null
                    macAddress = null
                    callback?.onDisconnected()
                }

                override fun onFailed(errorCode: ValueUtil.CxrBluetoothErrorCode?) {
                    Log.e(TAG, "onFailed callback received! Thread: ${Thread.currentThread().name}")
                    cancelTimeout() // 取消超时检测
                    Log.e(TAG, "Init failed with error code: $errorCode")
                    Log.e(TAG, "Error details:")
                    when (errorCode) {
                        ValueUtil.CxrBluetoothErrorCode.PARAM_INVALID -> {
                            Log.e(TAG, "  - PARAM_INVALID: Parameter invalid")
                        }
                        ValueUtil.CxrBluetoothErrorCode.BLE_CONNECT_FAILED -> {
                            Log.e(TAG, "  - BLE_CONNECT_FAILED: BLE connection failed (device may not support multiple connections or is busy)")
                        }
                        ValueUtil.CxrBluetoothErrorCode.SOCKET_CONNECT_FAILED -> {
                            Log.e(TAG, "  - SOCKET_CONNECT_FAILED: Socket connection failed (device may not support multiple connections)")
                        }
                        ValueUtil.CxrBluetoothErrorCode.UNKNOWN -> {
                            Log.e(TAG, "  - UNKNOWN: Unknown error")
                        }
                        null -> {
                            Log.e(TAG, "  - NULL: Error code is null")
                        }
                        else -> {
                            Log.e(TAG, "  - Other error: $errorCode")
                        }
                    }
                    isConnecting = false
                    socketUuid = null
                    macAddress = null
                    callback?.onFailed(errorCode)
                }
            }
            )
            Log.d(TAG, "initBluetooth() call completed, waiting for callbacks...")
            
            // 设置超时检测：如果 10 秒内没有回调，认为失败
            timeoutHandler = Handler(Looper.getMainLooper())
            timeoutRunnable = Runnable {
                if (isConnecting) {
                    Log.e(TAG, "initBluetooth timeout: No callback received within 10 seconds")
                    Log.e(TAG, "This may indicate:")
                    Log.e(TAG, "  1. Device is already connected by another app")
                    Log.e(TAG, "  2. SDK internal error")
                    Log.e(TAG, "  3. Device does not support multiple connections")
                    isConnecting = false
                    callback?.onFailed(ValueUtil.CxrBluetoothErrorCode.UNKNOWN)
                }
            }
            timeoutHandler?.postDelayed(timeoutRunnable!!, 10000)
        } catch (e: Exception) {
            Log.e(TAG, "Exception occurred while calling initBluetooth: ${e.message}", e)
            isConnecting = false
            callback?.onFailed(ValueUtil.CxrBluetoothErrorCode.UNKNOWN)
        }
    }

    /**
     * 使用已保存的连接信息建立连接
     * 这是真正的连接步骤，在 initBluetooth 的 onConnectionInfo 回调中调用
     */
    fun connectBluetooth(
        context: Context,
        socketUuid: String,
        macAddress: String
    ) {
        // 注意：connectBluetooth 在 initBluetooth 的回调中被调用
        // 连接流程是连续的，所以不检查 isConnecting

        this.socketUuid = socketUuid
        this.macAddress = macAddress
        // isConnecting 已经在 connectDevice 中设置为 true，这里不需要重复设置

        Log.d(TAG, "Connecting with UUID: $socketUuid, MAC: $macAddress")
        Log.d(TAG, "Calling CxrApi.getInstance().connectBluetooth()...")

        CxrApi.getInstance().connectBluetooth(
            context,
            socketUuid,
            macAddress,
            object : BluetoothStatusCallback {
                override fun onConnectionInfo(
                    socketUuid: String?,
                    macAddress: String?,
                    rokidAccount: String?,
                    glassesType: Int
                ) {
                    // 根据示例代码，connectBluetooth 的 onConnectionInfo 可以是空的
                    // 但我们可以更新保存的连接信息
                    Log.d(
                        TAG,
                        "connectBluetooth onConnectionInfo - UUID: $socketUuid, MAC: $macAddress, " +
                            "Account: $rokidAccount, Type: $glassesType"
                    )
                    this@CxrConnectionManager.socketUuid = socketUuid
                    this@CxrConnectionManager.macAddress = macAddress
                    connectionCallback?.onConnectionInfo(
                        socketUuid,
                        macAddress,
                        rokidAccount,
                        glassesType
                    )
                }

                override fun onConnected() {
                    Log.d(TAG, "Connected") // 按照示例代码的简单日志
                    cancelTimeout() // 取消超时检测
                    
                    // 验证连接是否真的成功（真正的连接成功在这里）
                    verifyConnection()
                    
                    isConnecting = false
                    connectionCallback?.onConnected()
                }

                override fun onDisconnected() {
                    Log.d(TAG, "Disconnected") // 按照示例代码的简单日志
                    cancelTimeout() // 取消超时检测
                    isConnecting = false
                    this@CxrConnectionManager.socketUuid = null
                    this@CxrConnectionManager.macAddress = null
                    connectionCallback?.onDisconnected()
                }

                override fun onFailed(errorCode: ValueUtil.CxrBluetoothErrorCode?) {
                    Log.e(TAG, "Failed") // 按照示例代码的简单日志
                    cancelTimeout() // 取消超时检测
                    // 添加详细错误信息用于调试
                    Log.e(TAG, "Connection failed with error code: $errorCode")
                    when (errorCode) {
                        ValueUtil.CxrBluetoothErrorCode.PARAM_INVALID -> {
                            Log.e(TAG, "  - PARAM_INVALID: Parameter invalid")
                        }
                        ValueUtil.CxrBluetoothErrorCode.BLE_CONNECT_FAILED -> {
                            Log.e(TAG, "  - BLE_CONNECT_FAILED: BLE connection failed")
                        }
                        ValueUtil.CxrBluetoothErrorCode.SOCKET_CONNECT_FAILED -> {
                            Log.e(TAG, "  - SOCKET_CONNECT_FAILED: Socket connection failed")
                        }
                        ValueUtil.CxrBluetoothErrorCode.UNKNOWN -> {
                            Log.e(TAG, "  - UNKNOWN: Unknown error")
                        }
                        null -> {
                            Log.e(TAG, "  - NULL: Error code is null")
                        }
                        else -> {
                            Log.e(TAG, "  - Other error: $errorCode")
                        }
                    }
                    isConnecting = false
                    this@CxrConnectionManager.socketUuid = null
                    this@CxrConnectionManager.macAddress = null
                    connectionCallback?.onFailed(errorCode)
                }
            }
        )
    }

    /**
     * 重连（使用已保存的连接信息）
     */
    fun reconnect(context: Context) {
        val uuid = socketUuid
        val mac = macAddress

        if (uuid == null || mac == null) {
            Log.w(TAG, "Cannot reconnect: missing connection info")
            return
        }

        connectBluetooth(context, uuid, mac)
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        Log.d(TAG, "Disconnecting...")
        cancelTimeout() // 取消超时检测
        try {
            CxrApi.getInstance().deinitBluetooth()
            socketUuid = null
            macAddress = null
            isConnecting = false
            connectionCallback = null
            Log.d(TAG, "Disconnected")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disconnect: ${e.message}", e)
        }
    }

    /**
     * 检查连接状态
     */
    fun isConnected(): Boolean {
        return try {
            CxrApi.getInstance().isBluetoothConnected()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check connection status: ${e.message}", e)
            false
        }
    }

    /**
     * 获取保存的连接信息
     */
    fun getConnectionInfo(): Pair<String?, String?> {
        return Pair(socketUuid, macAddress)
    }
}

