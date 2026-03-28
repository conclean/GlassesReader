package com.app.glassesreader.service.overlay

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.TextView
import android.widget.Toast
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.app.glassesreader.MainActivity
import com.app.glassesreader.R
import com.app.glassesreader.accessibility.ScreenTextPublisher
import com.app.glassesreader.overlay.ArRecordBroadcast
import com.app.glassesreader.overlay.ArShutterBroadcast
import com.app.glassesreader.sdk.CxrCustomViewManager
import com.lzf.easyfloat.EasyFloat
import com.lzf.easyfloat.enums.ShowPattern
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val ACTION_SHOW_AR_SHUTTER = "com.app.glassesreader.action.SHOW_AR_SHUTTER"
private const val ACTION_SHOW_AR_RECORD_SHUTTER = "com.app.glassesreader.action.SHOW_AR_RECORD_SHUTTER"
private const val ACTION_SHOW_AR_RECORD_STOP = "com.app.glassesreader.action.SHOW_AR_RECORD_STOP"
private const val ACTION_DISMISS_AR_RECORD_STOP = "com.app.glassesreader.action.DISMISS_AR_RECORD_STOP"
private const val ACTION_NOTIFY_AR_RECORDING_FINISHED =
    "com.app.glassesreader.action.NOTIFY_AR_RECORDING_FINISHED"

private const val LOG_TAG = "TextOverlayService"
private const val SHUTTER_FLOAT_TAG = "ar_shutter_float"
private const val AR_RECORD_STOP_TAG = "ar_record_stop_float"

/** 与 AR 录屏时长（秒）一致：倒计时结束后自动结束录屏 */
private const val AR_RECORD_STOP_COOLDOWN_SEC = 15

private enum class ArShutterMode { SCREENSHOT, RECORD }

/**
 * TextOverlayService 作为前台服务，负责显示浮窗并订阅无障碍文字更新。
 */
class TextOverlayService : Service() {

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var textCollectJob: Job? = null
    private var isReadingActive: Boolean = false
    private var overlayEnabled: Boolean = true
    private var toggleAllowedByState: Boolean = true
    private var toggleDisabledMessage: String? = null
    private var toggleRootView: View? = null
    private var toggleTextView: TextView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var shutterRootView: View? = null
    private var arShutterLabelRow: View? = null
    private var shutterCountdownView: TextView? = null
    private var shutterCountingInProgress = false
    private val shutterCountdownRunnables = mutableListOf<Runnable>()
    private var arShutterMode: ArShutterMode = ArShutterMode.SCREENSHOT
    private var arRecordStopRootView: View? = null
    private var arRecordCooldownRunnable: Runnable? = null
    /**
     * 用户在 AR 录屏中途关闭读屏时暂不调用 [CxrCustomViewManager.close]，置此标记；录屏结束后再关页。
     */
    private var pendingCloseCustomViewAfterArRecording: Boolean = false
    private val prefs by lazy { getSharedPreferences(PREF_APP_SETTINGS, Context.MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        overlayEnabled = prefs.getBoolean(KEY_OVERLAY_ENABLED, false)

        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(isActive = false),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
        startForeground(NOTIFICATION_ID, buildNotification(isActive = false))
        }

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        createToggleFloat()
        if (!overlayEnabled) {
            EasyFloat.hide(TOGGLE_FLOAT_TAG)
            toggleAllowedByState = false
            applyToggleAvailability()
        }
        CxrCustomViewManager.ensureInitialized()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 确保前台通知已启动（防止 startForegroundService 后 onCreate 未及时调用的情况）
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(isActive = isReadingActive),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification(isActive = isReadingActive))
            }
        } catch (e: Exception) {
            // 如果已经启动过，忽略异常
        }
        
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                stopSelf()
            }

            ACTION_ENABLE_READER -> {
                handleToggleState(true)
            }

            ACTION_DISABLE_READER -> {
                handleToggleState(false)
            }

            ACTION_ENABLE_OVERLAY -> {
                overlayEnabled = true
                toggleAllowedByState = true
                EasyFloat.show(TOGGLE_FLOAT_TAG)
                applyToggleAvailability()
            }

            ACTION_DISABLE_OVERLAY -> {
                overlayEnabled = false
                EasyFloat.hide(TOGGLE_FLOAT_TAG)
                toggleAllowedByState = false
                applyToggleAvailability()
            }

            ACTION_UPDATE_TOGGLE_AVAILABILITY -> {
                toggleAllowedByState = intent.getBooleanExtra(EXTRA_TOGGLE_ENABLED, true)
                toggleDisabledMessage = intent.getStringExtra(EXTRA_TOGGLE_MESSAGE)
                applyToggleAvailability()
            }

            ACTION_SHOW_AR_SHUTTER -> {
                cancelShutterCountdown()
                shutterCountingInProgress = false
                arShutterMode = ArShutterMode.SCREENSHOT
                createOrShowArShutterFloat()
            }

            ACTION_SHOW_AR_RECORD_SHUTTER -> {
                cancelShutterCountdown()
                shutterCountingInProgress = false
                arShutterMode = ArShutterMode.RECORD
                createOrShowArShutterFloat()
            }

            ACTION_SHOW_AR_RECORD_STOP -> {
                createOrShowArRecordStopFloat()
            }

            ACTION_DISMISS_AR_RECORD_STOP -> {
                cancelArRecordStopCooldown()
                arRecordStopRootView = null
                runCatching { EasyFloat.dismiss(AR_RECORD_STOP_TAG, true) }
            }

            ACTION_NOTIFY_AR_RECORDING_FINISHED -> {
                applyPendingCloseCustomViewAfterArRecording()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        textCollectJob?.cancel()
        scope.cancel()
        EasyFloat.dismiss(TOGGLE_FLOAT_TAG, true)
        cancelShutterCountdown()
        cancelArRecordStopCooldown()
        arRecordStopRootView = null
        runCatching { EasyFloat.dismiss(SHUTTER_FLOAT_TAG, true) }
        runCatching { EasyFloat.dismiss(AR_RECORD_STOP_TAG, true) }
        CxrCustomViewManager.close()
        prefs.edit().putBoolean(KEY_READER_ENABLED, false).apply()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handleToggleState(isActive: Boolean) {
        isReadingActive = isActive
        updateToggleUi()
        if (isReadingActive) {
            pendingCloseCustomViewAfterArRecording = false
            CxrCustomViewManager.ensureInitialized()
            startCollectingText()
        } else {
            stopCollectingText()
            // 关闭读屏时关闭眼镜自定义页；若正在 AR 录屏则不可 close，否则会打断录像场景。
            if (MainActivity.isArVideoRecordingActive()) {
                pendingCloseCustomViewAfterArRecording = true
                Log.d(LOG_TAG, "defer closeCustomView: AR video recording active")
            } else {
                pendingCloseCustomViewAfterArRecording = false
                CxrCustomViewManager.close()
            }
        }
        updateNotification(isActive = isActive)
        prefs.edit().putBoolean(KEY_READER_ENABLED, isReadingActive).apply()
    }

    private fun applyPendingCloseCustomViewAfterArRecording() {
        if (!pendingCloseCustomViewAfterArRecording) return
        pendingCloseCustomViewAfterArRecording = false
        if (!isReadingActive) {
            Log.d(LOG_TAG, "apply deferred closeCustomView after AR recording")
            CxrCustomViewManager.close()
        }
    }

    private fun startCollectingText() {
        if (textCollectJob?.isActive == true) return
        textCollectJob = scope.launch {
            ScreenTextPublisher.state.collectLatest { state ->
                if (isReadingActive) {
                    CxrCustomViewManager.updateText(state.text)
                }
            }
        }
    }

    private fun stopCollectingText() {
        textCollectJob?.cancel()
        textCollectJob = null
        // 注意：不再调用 updateText("")，因为关闭读屏时会调用 close() 关闭页面
        // 如果只是停止收集文本但读屏仍然开启，页面会保持显示，不需要清空文本
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "GlassesReader overlay",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Overlay reader service"
            enableLights(false)
            enableVibration(false)
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    private fun buildNotification(isActive: Boolean): Notification {
        val statusText = if (isActive) "读取中" else "已暂停"
        val pendingStop = PendingIntentFactory.createStopIntent(this)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GlassesReader")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_overlay_notification)
            .setOngoing(isActive)
            .setColor(Color.parseColor("#2196F3"))
            .addAction(
                R.drawable.ic_overlay_notification,
                "停止",
                pendingStop
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(isActive: Boolean) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(isActive))
    }

    private fun createToggleFloat() {
        EasyFloat.with(applicationContext)
            .setTag(TOGGLE_FLOAT_TAG)
            .setShowPattern(ShowPattern.ALL_TIME)
            .setDragEnable(true)
            .setLayout(R.layout.float_toggle) { view ->
                toggleRootView = view
                toggleTextView = view.findViewById(R.id.tvToggle)
                view.setOnClickListener {
                    if (toggleAllowedByState) {
                        handleToggleState(!isReadingActive)
                    } else {
                        val message = toggleDisabledMessage?.takeIf { it.isNotBlank() }
                            ?: if (!overlayEnabled) "悬浮窗已关闭" else "还有前置步骤未完成"
                        showHint(message)
                    }
                }
                applyToggleAvailability()
                updateToggleUi()
            }
            .show()
    }

    private fun updateToggleUi() {
        val label = if (isReadingActive) "Reading" else "Off"
        toggleTextView?.text = label
    }

    private fun applyToggleAvailability() {
        val enabled = overlayEnabled && toggleAllowedByState
        if (enabled) {
            EasyFloat.show(TOGGLE_FLOAT_TAG)
        } else {
            EasyFloat.hide(TOGGLE_FLOAT_TAG)
        }
        toggleRootView?.isEnabled = enabled
        toggleRootView?.alpha = if (enabled) 1f else 0.5f
        toggleTextView?.alpha = if (enabled) 1f else 0.5f
    }

    private fun showHint(message: String?) {
        if (!message.isNullOrBlank()) {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun cancelShutterCountdown() {
        shutterCountdownRunnables.forEach { mainHandler.removeCallbacks(it) }
        shutterCountdownRunnables.clear()
    }

    /** AR 截图/录屏浮窗距屏幕左上角的边距（约 12dp） */
    private fun arFloatMarginPx(): Int = (12f * resources.displayMetrics.density).toInt()

    /**
     * AR 截图/录屏：点快门后、3-2-1 倒计时前若读屏未开则自动开启，
     * 避免无文字采集或录屏中途再开叠不上字等问题。
     */
    private fun ensureReaderEnabledForArShutterIfNeeded() {
        if (isReadingActive) return
        Log.d(LOG_TAG, "AR shutter: auto-enable reader before countdown (mode=$arShutterMode)")
        handleToggleState(true)
    }

    private fun createOrShowArShutterFloat() {
        if (!Settings.canDrawOverlays(this)) return
        runCatching { EasyFloat.dismiss(SHUTTER_FLOAT_TAG, true) }
        shutterRootView = null
        arShutterLabelRow = null
        shutterCountdownView = null
        val m = arFloatMarginPx()
        EasyFloat.with(applicationContext)
            .setTag(SHUTTER_FLOAT_TAG)
            .setShowPattern(ShowPattern.ALL_TIME)
            .setDragEnable(true)
            .setGravity(Gravity.TOP or Gravity.START, m, m)
            .setLayout(R.layout.float_ar_shutter) { view ->
                shutterRootView = view
                arShutterLabelRow = view.findViewById(R.id.arShutterLabelRow)
                shutterCountdownView = view.findViewById(R.id.tvShutterCountdown)
                // 截图：仅「AR」；录屏：「AR」+ 红色英文句号「.」
                view.findViewById<TextView>(R.id.tvArSuffix).apply {
                    when (arShutterMode) {
                        ArShutterMode.SCREENSHOT -> visibility = View.GONE
                        ArShutterMode.RECORD -> {
                            visibility = View.VISIBLE
                            setTextColor(Color.RED)
                        }
                    }
                }
                arShutterLabelRow?.visibility = View.VISIBLE
                shutterCountdownView?.visibility = View.GONE
                view.findViewById<View>(R.id.shutterCircleRoot).setOnClickListener {
                    if (shutterCountingInProgress) return@setOnClickListener
                    ensureReaderEnabledForArShutterIfNeeded()
                    startShutterCountdown()
                }
            }
            .show()
    }

    private fun startShutterCountdown() {
        cancelShutterCountdown()
        shutterCountingInProgress = true
        arShutterLabelRow?.visibility = View.GONE
        shutterCountdownView?.visibility = View.VISIBLE
        shutterCountdownView?.text = "3"
        val r1 = Runnable { shutterCountdownView?.text = "2" }
        val r2 = Runnable { shutterCountdownView?.text = "1" }
        val r3 = Runnable { completeShutterCountdown() }
        shutterCountdownRunnables.addAll(listOf(r1, r2, r3))
        mainHandler.postDelayed(r1, 1000L)
        mainHandler.postDelayed(r2, 2000L)
        mainHandler.postDelayed(r3, 3000L)
    }

    private fun completeShutterCountdown() {
        cancelShutterCountdown()
        shutterCountingInProgress = false
        shutterCountdownView?.visibility = View.GONE
        val textSnapshot = ScreenTextPublisher.state.value.text
        runCatching { EasyFloat.dismiss(SHUTTER_FLOAT_TAG, true) }
        shutterRootView = null
        arShutterLabelRow = null
        shutterCountdownView = null
        when (arShutterMode) {
            ArShutterMode.SCREENSHOT -> {
                val intent = Intent(ArShutterBroadcast.ACTION).apply {
                    setPackage(packageName)
                    putExtra(ArShutterBroadcast.EXTRA_SNAPSHOT_TEXT, textSnapshot)
                }
                sendBroadcast(intent)
            }
            ArShutterMode.RECORD -> {
                val intent = Intent(ArRecordBroadcast.ACTION_RECORD_START).apply {
                    setPackage(packageName)
                }
                sendBroadcast(intent)
            }
        }
    }

    private fun cancelArRecordStopCooldown() {
        arRecordCooldownRunnable?.let { mainHandler.removeCallbacks(it) }
        arRecordCooldownRunnable = null
    }

    private fun createOrShowArRecordStopFloat() {
        if (!Settings.canDrawOverlays(this)) return
        cancelArRecordStopCooldown()
        runCatching { EasyFloat.dismiss(AR_RECORD_STOP_TAG, true) }
        arRecordStopRootView = null
        val m = arFloatMarginPx()
        EasyFloat.with(applicationContext)
            .setTag(AR_RECORD_STOP_TAG)
            .setShowPattern(ShowPattern.ALL_TIME)
            .setDragEnable(true)
            .setGravity(Gravity.TOP or Gravity.START, m, m)
            .setLayout(R.layout.float_ar_record_stop) { view ->
                val root = view.findViewById<View>(R.id.arRecordStopRoot)
                val secTv = view.findViewById<TextView>(R.id.tvArRecordSecCountdown)
                arRecordStopRootView = root
                root.isClickable = false
                root.isEnabled = false
                startArRecordStopCooldownUi(root, secTv)
            }
            .show()
    }

    /**
     * 数字 N→1（N 与录屏时长一致）各约 1 秒，此期间不可点击；结束后自动结束录屏并关闭浮窗。
     */
    private fun startArRecordStopCooldownUi(root: View, secTv: TextView) {
        cancelArRecordStopCooldown()
        secTv.visibility = View.VISIBLE
        var step = 0
        val r = object : Runnable {
            override fun run() {
                val stopRoot = arRecordStopRootView
                if (stopRoot == null || !stopRoot.isAttachedToWindow) {
                    cancelArRecordStopCooldown()
                    return
                }
                if (step < AR_RECORD_STOP_COOLDOWN_SEC) {
                    secTv.text = (AR_RECORD_STOP_COOLDOWN_SEC - step).toString()
                    step++
                    arRecordCooldownRunnable = this
                    mainHandler.postDelayed(this, 1000L)
                } else {
                    arRecordCooldownRunnable = null
                    val i = Intent(ArRecordBroadcast.ACTION_RECORD_STOP).apply { setPackage(packageName) }
                    sendBroadcast(i)
                }
            }
        }
        arRecordCooldownRunnable = r
        r.run()
    }

    private object PendingIntentFactory {
        fun createStopIntent(context: Context): PendingIntent {
            val stopIntent = Intent(context, TextOverlayService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            return PendingIntent.getService(
                context,
                0,
                stopIntent,
                PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "glassesreader_overlay_channel"
        private const val NOTIFICATION_ID = 2
        private const val ACTION_STOP_SERVICE = "com.app.glassesreader.action.STOP_SERVICE"
        private const val ACTION_ENABLE_READER = "com.app.glassesreader.action.ENABLE_READER"
        private const val ACTION_DISABLE_READER = "com.app.glassesreader.action.DISABLE_READER"
        private const val ACTION_ENABLE_OVERLAY = "com.app.glassesreader.action.ENABLE_OVERLAY"
        private const val ACTION_DISABLE_OVERLAY = "com.app.glassesreader.action.DISABLE_OVERLAY"
        private const val ACTION_UPDATE_TOGGLE_AVAILABILITY =
            "com.app.glassesreader.action.UPDATE_TOGGLE_AVAILABILITY"
        private const val EXTRA_TOGGLE_ENABLED = "extra_toggle_enabled"
        private const val EXTRA_TOGGLE_MESSAGE = "extra_toggle_message"
        private const val PREF_APP_SETTINGS = "gr_app_settings"
        private const val KEY_READER_ENABLED = "reader_enabled"
        private const val KEY_OVERLAY_ENABLED = "overlay_enabled"
        private const val TOGGLE_FLOAT_TAG = "toggle_float"
        fun start(context: Context) {
            val intent = Intent(context, TextOverlayService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun enableReader(context: Context) {
            val intent = Intent(context, TextOverlayService::class.java).apply {
                action = ACTION_ENABLE_READER
            }
            // 如果服务已经在运行，使用 startService 而不是 startForegroundService
            if (isServiceRunning(context)) {
                context.startService(intent)
            } else {
                ContextCompat.startForegroundService(context, intent)
            }
        }

        fun disableReader(context: Context) {
            val intent = Intent(context, TextOverlayService::class.java).apply {
                action = ACTION_DISABLE_READER
            }
            if (isServiceRunning(context)) {
                context.startService(intent)
            } else {
                ContextCompat.startForegroundService(context, intent)
            }
        }

        fun enableOverlay(context: Context) {
            val intent = Intent(context, TextOverlayService::class.java).apply {
                action = ACTION_ENABLE_OVERLAY
            }
            if (isServiceRunning(context)) {
                context.startService(intent)
            } else {
                ContextCompat.startForegroundService(context, intent)
            }
        }

        fun disableOverlay(context: Context) {
            val intent = Intent(context, TextOverlayService::class.java).apply {
                action = ACTION_DISABLE_OVERLAY
            }
            if (isServiceRunning(context)) {
                context.startService(intent)
            } else {
                ContextCompat.startForegroundService(context, intent)
            }
        }

        fun updateToggleAvailability(context: Context, enabled: Boolean, message: String?) {
            val intent = Intent(context, TextOverlayService::class.java).apply {
                action = ACTION_UPDATE_TOGGLE_AVAILABILITY
                putExtra(EXTRA_TOGGLE_ENABLED, enabled)
                putExtra(EXTRA_TOGGLE_MESSAGE, message)
            }
            if (isServiceRunning(context)) {
                context.startService(intent)
            } else {
                ContextCompat.startForegroundService(context, intent)
            }
        }
        
        private fun isServiceRunning(context: Context): Boolean {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                ?: return false
            return activityManager.getRunningServices(Int.MAX_VALUE).any { service ->
                service.service.className == TextOverlayService::class.java.name
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, TextOverlayService::class.java)
            context.stopService(intent)
        }

        /** 显示 AR 截图快门圆钮；倒计时结束后再由 [MainActivity] 发起拍照。 */
        fun showArShutter(context: Context) {
            val intent = Intent(context, TextOverlayService::class.java).apply {
                action = ACTION_SHOW_AR_SHUTTER
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /** AR 录屏：与截图相同的倒计时快门，结束后广播 [ArRecordBroadcast.ACTION_RECORD_START]。 */
        fun showArRecordShutter(context: Context) {
            val intent = Intent(context, TextOverlayService::class.java).apply {
                action = ACTION_SHOW_AR_RECORD_SHUTTER
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /** 录屏进行中：显示与录屏等长的秒数倒计时浮窗，结束后自动停止录屏并收起。 */
        fun showArRecordStop(context: Context) {
            val intent = Intent(context, TextOverlayService::class.java).apply {
                action = ACTION_SHOW_AR_RECORD_STOP
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun dismissArRecordStop(context: Context) {
            val intent = Intent(context, TextOverlayService::class.java).apply {
                action = ACTION_DISMISS_AR_RECORD_STOP
            }
            if (isServiceRunning(context)) {
                context.startService(intent)
            } else {
                ContextCompat.startForegroundService(context, intent)
            }
        }

        /** [MainActivity] 在 AR 录屏结束（关闭视频场景）后调用，用于执行录屏期间被推迟的 [CxrCustomViewManager.close]。 */
        fun notifyArVideoRecordingFinished(context: Context) {
            val intent = Intent(context, TextOverlayService::class.java).apply {
                action = ACTION_NOTIFY_AR_RECORDING_FINISHED
            }
            if (isServiceRunning(context)) {
                context.startService(intent)
            } else {
                ContextCompat.startForegroundService(context, intent)
            }
        }

    }
}
