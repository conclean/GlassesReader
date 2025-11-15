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
        private const val DEFAULT_CONNECTION_TIMEOUT_MS = 10_000L
        
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
     * 根据文档，连接成功后只需检查 SDK 的连接状态即可
     */
    private fun verifyConnection() {
        val sdkConnected = try {
            CxrApi.getInstance().isBluetoothConnected()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check SDK connection: ${e.message}", e)
            false
        }
        
        if (sdkConnected) {
            Log.d(TAG, "✓ Connection verified: SDK reports connected")
        } else {
            Log.w(TAG, "⚠ Connection verification failed: SDK reports not connected")
        }
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
     * 统一记录错误码信息（根据文档中的错误码说明）
     */
    private fun logErrorCode(errorCode: ValueUtil.CxrBluetoothErrorCode?, context: String = "") {
        val prefix = if (context.isNotEmpty()) "$context: " else ""
        Log.e(TAG, "${prefix}Error code: $errorCode")
        when (errorCode) {
            ValueUtil.CxrBluetoothErrorCode.PARAM_INVALID -> {
                Log.e(TAG, "  - PARAM_INVALID: Parameter Invalid")
            }
            ValueUtil.CxrBluetoothErrorCode.BLE_CONNECT_FAILED -> {
                Log.e(TAG, "  - BLE_CONNECT_FAILED: BLE Connect Failed")
            }
            ValueUtil.CxrBluetoothErrorCode.SOCKET_CONNECT_FAILED -> {
                Log.e(TAG, "  - SOCKET_CONNECT_FAILED: Socket Connect Failed")
            }
            ValueUtil.CxrBluetoothErrorCode.UNKNOWN -> {
                Log.e(TAG, "  - UNKNOWN: Unknown")
            }
            null -> {
                Log.e(TAG, "  - NULL: Error code is null")
            }
            else -> {
                Log.e(TAG, "  - Other error: $errorCode")
            }
        }
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

        // 检查 SDK 是否已连接（根据文档使用 isBluetoothConnected() 方法）
        val sdkConnected = try {
            CxrApi.getInstance().isBluetoothConnected()
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
                    // 注意：根据文档，initBluetooth 的 onConnected() 可能是空的
                    // 真正的连接成功应该在 connectBluetooth 的 onConnected() 中处理
                    // 这里不做任何处理，等待 connectBluetooth 的 onConnected() 回调
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
                    logErrorCode(errorCode, "Init failed")
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
            timeoutHandler?.postDelayed(timeoutRunnable!!, DEFAULT_CONNECTION_TIMEOUT_MS)
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
                    Log.e(TAG, "Failed") // 按照文档示例的简单日志
                    cancelTimeout() // 取消超时检测
                    logErrorCode(errorCode, "Connection failed")
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
     * 根据文档，重连可以直接使用 connectBluetooth，不需要重新 initBluetooth
     * 
     * @param context Application Context
     * @param callback 连接状态回调（可选，如果不提供则使用之前保存的回调）
     */
    fun reconnect(context: Context, callback: ConnectionCallback? = null) {
        val uuid = socketUuid
        val mac = macAddress

        if (uuid == null || mac == null) {
            Log.w(TAG, "Cannot reconnect: missing connection info")
            callback?.onFailed(ValueUtil.CxrBluetoothErrorCode.PARAM_INVALID)
            return
        }

        // 如果提供了新的回调，则更新；否则使用之前保存的回调
        if (callback != null) {
            this.connectionCallback = callback
        }
        
        // 检查是否已连接
        if (isConnected()) {
            Log.d(TAG, "Already connected, skip reconnect")
            connectionCallback?.onConnected()
            return
        }

        isConnecting = true
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

