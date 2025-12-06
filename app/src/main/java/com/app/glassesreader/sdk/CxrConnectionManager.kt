package com.app.glassesreader.sdk

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.callbacks.BluetoothStatusCallback
import com.rokid.cxr.client.utils.ValueUtil

/**
 * CXR-M SDK 蓝牙连接管理器
 * 
 * 【作用】
 * 统一管理 Rokid CXR-M SDK 的蓝牙连接流程，封装了完整的连接生命周期管理。
 * 这是应用与 Rokid 眼镜进行 SDK 级别通信的核心组件。
 * 
 * 【主要功能】
 * 1. 初始化蓝牙模块（initBluetooth）- 获取连接信息（UUID、MAC地址）
 * 2. 建立蓝牙连接（connectBluetooth）- 使用获取的信息建立实际连接
 * 3. 管理连接状态和回调
 * 4. 处理连接超时和错误
 * 5. 支持重连机制
 * 6. 清理和断开连接
 * 
 * 【两种连接流程】
 * 
 * 流程 1：手动扫描-连接（首次连接或自动重连失败后的手动连接）
 *   1. 扫描设备 → 用户选择设备
 *   2. 调用 connectDevice() → 检查连接状态
 *   3. 调用 deinitBluetooth() 清理 SDK 状态
 *   4. 延迟 200ms 后调用 initBluetooth() → 等待 onConnectionInfo 回调
 *   5. 在 onConnectionInfo 中获取 socketUuid 和 macAddress
 *   6. 调用 connectBluetooth() → 等待 onConnected 回调
 *   7. 连接成功后保存参数并验证连接状态
 * 
 * 流程 2：自动重连（应用启动时）
 *   1. 应用启动 → 调用 init() 加载持久化参数
 *   2. 调用 autoReconnect() → 检查是否有保存的参数
 *   3. 调用 reconnect() → 清理 SDK 状态（deinitBluetooth）
 *   4. 直接使用保存的参数调用 connectBluetooth()（不需要 initBluetooth）
 *   5. 等待 onConnected 回调
 * 
 * 【共用方法】
 * - connectBluetooth()：两种流程都会使用的核心连接方法
 * - 回调处理、超时处理、错误处理：两种流程共用
 * 
 * 【注意事项】
 * - Rokid 眼镜在同一时间只能与一个应用建立 SDK 连接（独占连接）
 * - 如果设备已被其他应用连接，需要先断开其他应用的连接
 * - 每次重新配对时，socketUuid 和 macAddress 可能会变化
 * - 连接超时设置为 10 秒，如果无响应则认为失败
 */
class CxrConnectionManager private constructor() {

    companion object {
        private const val TAG = "CxrConnectionManager"
        private const val DEFAULT_CONNECTION_TIMEOUT_MS = 10_000L
        
        // SharedPreferences 相关常量
        private const val PREFS_NAME = "cxr_connection_prefs"
        private const val KEY_SOCKET_UUID = "socket_uuid"
        private const val KEY_MAC_ADDRESS = "mac_address"
        
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
    
    // Context 用于访问 SharedPreferences
    private var context: Context? = null

    /**
     * 初始化管理器，设置 Context 并加载持久化的连接参数
     * 
     * 应该在应用启动时调用，以便加载之前保存的连接参数
     * 
     * @param context Application Context
     */
    fun init(context: Context) {
        this.context = context.applicationContext
        loadConnectionInfo()
        Log.d(TAG, "CxrConnectionManager initialized, loaded connection info: UUID=${socketUuid != null}, MAC=${macAddress != null}")
    }
    
    /**
     * 保存连接信息到 SharedPreferences（持久化存储）
     * 
     * 当获取到新的连接参数时，会自动覆盖旧的参数
     */
    private fun saveConnectionInfo() {
        context?.let { ctx ->
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_SOCKET_UUID, socketUuid)
                .putString(KEY_MAC_ADDRESS, macAddress)
                .apply()
            Log.d(TAG, "Connection info saved to SharedPreferences")
        } ?: run {
            Log.w(TAG, "Cannot save connection info: Context is null, please call init() first")
        }
    }
    
    /**
     * 从 SharedPreferences 加载连接信息
     */
    private fun loadConnectionInfo() {
        context?.let { ctx ->
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            socketUuid = prefs.getString(KEY_SOCKET_UUID, null)
            macAddress = prefs.getString(KEY_MAC_ADDRESS, null)
            if (socketUuid != null && macAddress != null) {
                Log.d(TAG, "Connection info loaded from SharedPreferences: UUID=$socketUuid, MAC=$macAddress")
            } else {
                Log.d(TAG, "No saved connection info found in SharedPreferences")
            }
        } ?: run {
            Log.w(TAG, "Cannot load connection info: Context is null, please call init() first")
        }
    }
    
    /**
     * 清除持久化的连接信息
     */
    private fun clearConnectionInfo() {
        context?.let { ctx ->
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .remove(KEY_SOCKET_UUID)
                .remove(KEY_MAC_ADDRESS)
                .apply()
            Log.d(TAG, "Connection info cleared from SharedPreferences")
        }
    }
    
    /**
     * 检查是否有保存的连接信息（用于快速重连）
     * 
     * @return true 表示有保存的连接参数（socketUuid 和 macAddress），可以进行快速重连
     */
    fun hasSavedConnectionInfo(): Boolean {
        // 如果内存中有，直接返回
        if (socketUuid != null && macAddress != null) {
            return true
        }
        // 如果内存中没有，尝试从 SharedPreferences 加载
        if (context != null) {
            loadConnectionInfo()
            return socketUuid != null && macAddress != null
        }
        return false
    }
    
    /**
     * 应用启动时自动重连
     * 
     * 【作用】
     * 在应用启动时，检查是否有持久化的连接参数，如果有则尝试自动重连。
     * 
     * 【流程】
     * 1. 检查是否已连接（如果已连接则跳过）
     * 2. 检查是否有保存的连接参数（如果没有则跳过）
     * 3. 调用 reconnect() 进行自动重连
     * 
     * 【与 reconnect() 的区别】
     * - autoReconnect() 是应用启动时的入口方法，会先检查条件
     * - reconnect() 是实际的重连执行方法
     * 
     * 【参考文档】
     * - 设备连接.md 第6节 "蓝牙重连"
     * 
     * @param callback 连接状态回调（可选）
     * @return true 表示已尝试重连，false 表示没有保存的连接参数或已连接
     */
    fun autoReconnect(callback: ConnectionCallback? = null): Boolean {
        // 检查是否已连接
        if (isConnected()) {
            Log.d(TAG, "Already connected, skip auto reconnect")
            callback?.onConnected()
            return false
        }
        
        // 如果没有 Context，先尝试加载连接信息
        if (context == null) {
            Log.w(TAG, "Context is null, cannot auto reconnect. Please call init() first.")
            return false
        }
        
        // 如果没有保存的连接参数，无法重连
        val uuid = socketUuid
        val mac = macAddress
        if (uuid == null || mac == null) {
            Log.d(TAG, "No saved connection info, skip auto reconnect")
            return false
        }
        
        Log.d(TAG, "Attempting auto reconnect with saved connection info: UUID=$uuid, MAC=$mac")
        
        // 如果提供了新的回调，则更新；否则使用之前保存的回调
        if (callback != null) {
            this.connectionCallback = callback
        }
        
        // 调用 reconnect() 进行重连（不需要 initBluetooth）
        // 注意：重连时不需要 initBluetooth，直接使用 connectBluetooth
        reconnect(null, callback) // 传入 null，使用之前保存的 context
        return true
    }

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
     * 验证连接是否成功
     * 
     * 参考文档：设备连接.md 第4节 "获取蓝牙通信模块连接状态"
     * 使用 isBluetoothConnected() 方法检查 SDK 的连接状态
     * 
     * @return true 表示 SDK 报告已连接，false 表示未连接
     */
    private fun verifyConnection() {
        val sdkConnected = try {
            // 参考文档：设备连接.md 第4节
            // CxrApi.getInstance().isBluetoothConnected() 返回当前蓝牙通信模块的连接状态
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

    // ============================================================================
    // 连接流程 1：手动扫描-连接流程（首次连接或自动重连失败后的手动连接）
    // ============================================================================
    // 
    // 【完整流程】
    //   1. 用户扫描设备 → 选择设备
    //   2. connectDevice(device) → 检查连接状态
    //   3. deinitBluetooth() → 清理 SDK 状态
    //   4. initBluetooth(device) → 获取连接信息（socketUuid, macAddress）
    //   5. connectBluetooth(uuid, mac) → 建立实际连接（共用方法）
    //   6. 保存连接参数到持久化存储
    // 
    // 【特点】
    //   - 需要 BluetoothDevice 对象
    //   - 需要先调用 initBluetooth() 获取连接参数
    //   - 适用于首次连接或参数失效后的重新连接
    // 
    // 【使用场景】
    //   - 用户在设备扫描页面手动选择设备连接
    //   - 自动重连失败后，用户手动重新连接
    // 
    // ============================================================================
    
    /**
     * 手动连接设备（首次连接流程）
     * 
     * 【流程说明】
     * 1. 清理 SDK 状态（deinitBluetooth）
     * 2. 初始化蓝牙模块（initBluetooth）→ 获取 socketUuid 和 macAddress
     * 3. 使用获取的参数建立连接（connectBluetooth）
     * 
     * 【使用场景】
     * - 用户在设备扫描页面手动选择设备连接
     * - 自动重连失败后，用户手动重新连接
     * 
     * 【参考文档】
     * - 设备连接.md 第2节 "初始化蓝牙获取蓝牙信息"
     * - 设备连接.md 第3节 "连接蓝牙模块"
     * 
     * 注意：Rokid 眼镜在同一时间只能与一个应用建立 SDK 连接。
     * 如果官方应用已连接，需要先断开其蓝牙配对，再由本应用发起连接。
     * 
     * @param context Application Context
     * @param device 要连接的蓝牙设备（从扫描结果中获取）
     * @param callback 连接状态回调
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
        // 参考文档：设备连接.md 第4节 "获取蓝牙通信模块连接状态"
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

        // 检查设备是否已连接（系统级连接）
        var deviceIsConnected = false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                deviceIsConnected = device::class.java.getMethod("isConnected").invoke(device) as Boolean
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot check device connection status: ${e.message}")
        }

        // 重要：在调用 initBluetooth() 之前，先清理 SDK 状态
        // 参考文档：设备连接.md 第5节 "反初始化蓝牙"
        // 使用 deinitBluetooth() 清理 SDK 状态，避免重复初始化导致 SDK 无响应
        // 这是解决连接超时问题的关键步骤
        Log.d(TAG, "Cleaning up SDK state before initialization...")
        try {
            CxrApi.getInstance().deinitBluetooth()
            Log.d(TAG, "SDK state cleaned up")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cleanup SDK state (may not be initialized): ${e.message}")
            // 如果 deinit 失败，可能是 SDK 未初始化，继续尝试 init
        }

        // 如果设备已连接，需要更长的延迟时间
        // SDK 在 initBluetooth 时会检测到设备已连接，内部会先断开再重新连接
        // 这个过程需要更多时间，所以增加延迟
        val delayMs = if (deviceIsConnected) {
            Log.d(TAG, "Device is already connected, using longer delay (1000ms) to allow SDK to handle disconnection")
            1000L
        } else {
            200L
        }

        // 使用 Handler 延迟调用 initBluetooth，给 SDK 时间完成清理
        val initHandler = Handler(Looper.getMainLooper())
        initHandler.postDelayed({
            if (!isConnecting) {
                Log.w(TAG, "Connection cancelled before init, skip")
                return@postDelayed
            }
            
            Log.d(TAG, "Initializing device: ${device.name}, address: ${device.address}")
            Log.d(TAG, "Calling CxrApi.getInstance().initBluetooth()...")

            try {
                // 参考文档：设备连接.md 第2节 "初始化蓝牙获取蓝牙信息"
                // initBluetooth(context, device, callback) 方法用于初始化蓝牙模块
                // 该方法会建立 BLE 连接，获取连接信息（socketUuid、macAddress 等）
                CxrApi.getInstance().initBluetooth(
                    context,
                    device,
                    object : BluetoothStatusCallback {
                        /**
                         * 连接信息回调
                         * 
                         * 参考文档：设备连接.md 第2节 "BluetoothStatusCallback接口说明"
                         * onConnectionInfo 在 initBluetooth 成功后回调，提供连接所需的关键信息
                         * 
                         * @param socketUuid 设备 UUID，用于后续 connectBluetooth 调用
                         * @param macAddress 经典蓝牙 MAC 地址，用于后续 connectBluetooth 调用
                         * @param rokidAccount Rokid 账号
                         * @param glassesType 眼镜类型（0-无显示，1-有显示）
                         */
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
                            
                            // 保存连接参数到持久化存储（新参数会自动覆盖旧参数）
                            saveConnectionInfo()

                            callback?.onConnectionInfo(socketUuid, macAddress, rokidAccount, glassesType)

                            // 获取到连接信息后，立即调用共用方法 connectBluetooth() 建立实际连接
                            // 参考文档：设备连接.md 第3节 "连接蓝牙模块"
                            // 注意：这是手动连接流程的最后一步，调用共用方法完成连接
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

                        /**
                         * 连接成功回调（initBluetooth 阶段）
                         * 
                         * 参考文档：设备连接.md 第2节 "BluetoothStatusCallback接口说明"
                         * 注意：initBluetooth 的 onConnected() 可能不会触发或为空
                         * 真正的连接成功应该在 connectBluetooth 的 onConnected() 中处理
                         */
                        override fun onConnected() {
                            Log.d(TAG, "onConnected callback received from initBluetooth! Thread: ${Thread.currentThread().name}")
                            cancelTimeout() // 取消超时检测
                            // 注意：根据文档，initBluetooth 的 onConnected() 可能是空的
                            // 真正的连接成功应该在 connectBluetooth 的 onConnected() 中处理
                            // 这里不做任何处理，等待 connectBluetooth 的 onConnected() 回调
                        }

                        /**
                         * 连接断开回调
                         * 
                         * 参考文档：设备连接.md 第2节 "BluetoothStatusCallback接口说明"
                         * onDisconnected() 在蓝牙连接丢失时回调
                         */
                        override fun onDisconnected() {
                            Log.d(TAG, "onDisconnected callback received! Thread: ${Thread.currentThread().name}")
                            cancelTimeout() // 取消超时检测
                            Log.d(TAG, "Bluetooth disconnected")
                            isConnecting = false
                            socketUuid = null
                            macAddress = null
                            callback?.onDisconnected()
                        }

                        /**
                         * 连接失败回调
                         * 
                         * 参考文档：设备连接.md 第2节 "BluetoothStatusCallback接口说明"
                         * onFailed() 在连接失败时回调，包含错误码：
                         * - PARAM_INVALID: 参数无效
                         * - BLE_CONNECT_FAILED: BLE 连接失败
                         * - SOCKET_CONNECT_FAILED: Socket 连接失败
                         * - UNKNOWN: 未知错误
                         */
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
                        val timeoutSeconds = if (deviceIsConnected) 20 else 10
                        Log.e(TAG, "initBluetooth timeout: No callback received within $timeoutSeconds seconds")
                        Log.e(TAG, "This may indicate:")
                        Log.e(TAG, "  1. Device is already connected by another app (most likely)")
                        Log.e(TAG, "  2. SDK internal error")
                        Log.e(TAG, "  3. Device does not support multiple connections")
                        Log.e(TAG, "  4. Device connection state is inconsistent")
                        if (deviceIsConnected) {
                            Log.e(TAG, "  → Device was already connected when initBluetooth was called")
                            Log.e(TAG, "  → Please try disconnecting the device from system settings first")
                        }
                        isConnecting = false
                        callback?.onFailed(ValueUtil.CxrBluetoothErrorCode.UNKNOWN)
                    }
                }
                // 如果设备已连接，增加超时时间（SDK 需要更多时间处理断开和重连）
                val timeoutMs = if (deviceIsConnected) {
                    Log.d(TAG, "Device is connected, using longer timeout (20s) for initBluetooth")
                    20_000L
                } else {
                    DEFAULT_CONNECTION_TIMEOUT_MS
                }
                timeoutHandler?.postDelayed(timeoutRunnable!!, timeoutMs)
            } catch (e: Exception) {
                Log.e(TAG, "Exception occurred while calling initBluetooth: ${e.message}", e)
                isConnecting = false
                callback?.onFailed(ValueUtil.CxrBluetoothErrorCode.UNKNOWN)
            }
        }, delayMs) // 延迟时间根据设备连接状态动态调整
    }

    // ============================================================================
    // 共用方法：建立蓝牙连接（两种流程都会使用）
    // ============================================================================
    // 
    // 【作用】
    //   这是核心连接方法，被两种连接流程共同使用。
    //   使用 socketUuid 和 macAddress 建立实际的蓝牙连接。
    // 
    // 【被调用位置】
    //   1. 手动连接流程：在 initBluetooth 的 onConnectionInfo 回调中调用
    //   2. 自动重连流程：在 reconnect() 中直接调用
    // 
    // 【参数来源】
    //   - 手动连接：从 initBluetooth 的 onConnectionInfo 回调中获取
    //   - 自动重连：从持久化存储（SharedPreferences）中加载
    // 
    // 【参考文档】
    //   - 设备连接.md 第3节 "连接蓝牙模块"
    // 
    // ============================================================================
    
    /**
     * 建立蓝牙连接（共用方法）
     * 
     * 【作用】
     * 使用 socketUuid 和 macAddress 建立实际的蓝牙连接。
     * 这是两种连接流程的共同步骤，都会被调用。
     * 
     * 【调用场景】
     * 1. 手动连接流程：在 initBluetooth 的 onConnectionInfo 回调中调用
     * 2. 自动重连流程：在 reconnect() 中直接调用
     * 
     * 【参考文档】
     * - 设备连接.md 第3节 "连接蓝牙模块"
     * 
     * @param context Application Context
     * @param socketUuid 设备 UUID（手动连接时从 initBluetooth 获取，自动重连时从持久化存储加载）
     * @param macAddress 设备 MAC 地址（手动连接时从 initBluetooth 获取，自动重连时从持久化存储加载）
     */
    private fun connectBluetooth(
        context: Context,
        socketUuid: String,
        macAddress: String
    ) {
        // 注意：此方法被两种流程调用：
        // 1. 手动连接流程：在 initBluetooth 的 onConnectionInfo 回调中调用（isConnecting 已在 connectDevice 中设置）
        // 2. 自动重连流程：在 reconnect() 中调用（isConnecting 在 reconnect 中设置）
        // 因此这里不检查 isConnecting，由调用方保证状态正确

        this.socketUuid = socketUuid
        this.macAddress = macAddress

        Log.d(TAG, "Connecting with UUID: $socketUuid, MAC: $macAddress")
        Log.d(TAG, "Calling CxrApi.getInstance().connectBluetooth()...")

        // 参考文档：设备连接.md 第3节 "连接蓝牙模块"
        // connectBluetooth(context, socketUuid, macAddress, callback) 方法
        // 这是两种连接流程的共同步骤，建立实际的蓝牙连接
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
                    
                    // 更新并保存连接参数（新参数会自动覆盖旧参数）
                    saveConnectionInfo()
                    
                    connectionCallback?.onConnectionInfo(
                        socketUuid,
                        macAddress,
                        rokidAccount,
                        glassesType
                    )
                }

                /**
                 * 连接成功回调（connectBluetooth 阶段）
                 * 
                 * 参考文档：设备连接.md 第3节 "BluetoothStatusCallback接口说明"
                 * 这是真正的连接成功回调，表示 SDK 蓝牙连接已建立
                 */
                override fun onConnected() {
                    Log.d(TAG, "Connected") // 按照示例代码的简单日志
                    cancelTimeout() // 取消超时检测
                    
                    // 验证连接是否真的成功（真正的连接成功在这里）
                    verifyConnection()
                    
                    // 连接成功后，确保连接参数已保存（可能在 onConnectionInfo 中已保存，这里再次确认）
                    if (socketUuid != null && macAddress != null) {
                        saveConnectionInfo()
                    }
                    
                    isConnecting = false
                    connectionCallback?.onConnected()
                }

                /**
                 * 连接断开回调（connectBluetooth 阶段）
                 * 
                 * 参考文档：设备连接.md 第3节 "BluetoothStatusCallback接口说明"
                 * 在连接丢失时回调
                 */
                override fun onDisconnected() {
                    Log.d(TAG, "Disconnected") // 按照示例代码的简单日志
                    cancelTimeout() // 取消超时检测
                    isConnecting = false
                    this@CxrConnectionManager.socketUuid = null
                    this@CxrConnectionManager.macAddress = null
                    connectionCallback?.onDisconnected()
                }

                /**
                 * 连接失败回调（connectBluetooth 阶段）
                 * 
                 * 参考文档：设备连接.md 第3节 "BluetoothStatusCallback接口说明"
                 * 在连接失败时回调，包含错误码
                 */
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

    // ============================================================================
    // 连接流程 2：自动重连流程（应用启动时）
    // ============================================================================
    // 
    // 【完整流程】
    //   1. 应用启动 → init() → 加载持久化参数（socketUuid, macAddress）
    //   2. autoReconnect() → 检查是否有保存的参数
    //   3. reconnect() → 检查连接状态
    //   4. deinitBluetooth() → 清理 SDK 状态
    //   5. connectBluetooth(uuid, mac) → 直接建立连接（共用方法，不需要 initBluetooth）
    // 
    // 【特点】
    //   - 不需要 BluetoothDevice 对象
    //   - 不需要 initBluetooth()，直接使用已保存的参数
    //   - 连接速度更快（跳过初始化步骤）
    //   - 如果参数失效（设备重新配对），重连会失败
    // 
    // 【使用场景】
    //   - 应用启动时自动尝试重连之前连接的设备
    //   - 连接断开后快速重连（如果参数仍然有效）
    // 
    // ============================================================================
    
    /**
     * 自动重连（使用已保存的连接参数）
     * 
     * 【流程说明】
     * 1. 从持久化存储加载 socketUuid 和 macAddress
     * 2. 清理 SDK 状态（deinitBluetooth）
     * 3. 直接使用保存的参数建立连接（connectBluetooth）
     * 
     * 【与手动连接的区别】
     * - 不需要 BluetoothDevice
     * - 不需要 initBluetooth() 获取连接信息
     * - 直接使用之前保存的参数，更快
     * 
     * 【使用场景】
     * - 应用启动时自动重连
     * - 连接断开后快速重连
     * 
     * 【参考文档】
     * - 设备连接.md 第6节 "蓝牙重连"
     * 
     * 注意：为了确保 SDK 状态干净，在重连前会先调用 deinitBluetooth() 清理状态
     * 
     * @param context Application Context（如果为 null，则使用之前通过 init() 设置的 context）
     * @param callback 连接状态回调（可选，如果不提供则使用之前保存的回调）
     */
    fun reconnect(context: Context? = null, callback: ConnectionCallback? = null) {
        // 使用传入的 context 或之前保存的 context
        val ctx = context ?: this.context
        if (ctx == null) {
            Log.e(TAG, "Cannot reconnect: Context is null, please call init() first or provide context")
            callback?.onFailed(ValueUtil.CxrBluetoothErrorCode.PARAM_INVALID)
            return
        }
        
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

        // 检查是否正在连接中
        if (isConnecting) {
            Log.w(TAG, "Connection already in progress, skip reconnect")
            return
        }

        // 设置连接状态标志，防止在延迟期间被其他操作取消
        isConnecting = true

        // 重要：在重连前先清理 SDK 状态，确保从干净状态开始
        // 参考文档：设备连接.md 第5节 "反初始化蓝牙"
        // 这样可以避免 SDK 状态混乱导致重连失败
        Log.d(TAG, "Cleaning up SDK state before reconnect...")
        try {
            CxrApi.getInstance().deinitBluetooth()
            Log.d(TAG, "SDK state cleaned up for reconnect")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cleanup SDK state before reconnect (may not be initialized): ${e.message}")
            // 如果 deinit 失败，可能是 SDK 未初始化，继续尝试重连
        }

        // 使用 Handler 延迟调用 connectBluetooth，给 SDK 时间完成清理
        val reconnectHandler = Handler(Looper.getMainLooper())
        reconnectHandler.postDelayed({
            // 再次检查连接状态，防止在延迟期间连接状态发生变化
            if (!isConnecting) {
                Log.w(TAG, "Reconnect cancelled before connect, skip")
                return@postDelayed
            }
            
            Log.d(TAG, "Reconnecting with UUID: $uuid, MAC: $mac")
            connectBluetooth(ctx, uuid, mac)
        }, 200) // 延迟 200ms，给 SDK 时间完成清理
    }

    /**
     * 断开连接
     * 
     * 参考文档：设备连接.md 第5节 "反初始化蓝牙"
     * 使用 deinitBluetooth() 方法断开连接并清理 SDK 状态
     */
    fun disconnect() {
        Log.d(TAG, "Disconnecting...")
        cancelTimeout() // 取消超时检测
        try {
            // 参考文档：设备连接.md 第5节
            CxrApi.getInstance().deinitBluetooth()
            socketUuid = null
            macAddress = null
            isConnecting = false
            connectionCallback = null
            
            // 清除持久化的连接信息
            clearConnectionInfo()
            
            Log.d(TAG, "Disconnected")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disconnect: ${e.message}", e)
        }
    }

    /**
     * 检查连接状态
     * 
     * 参考文档：设备连接.md 第4节 "获取蓝牙通信模块连接状态"
     * 使用 isBluetoothConnected() 方法获取当前连接状态
     * 
     * @return true 表示已连接，false 表示未连接
     */
    fun isConnected(): Boolean {
        return try {
            // 参考文档：设备连接.md 第4节
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

