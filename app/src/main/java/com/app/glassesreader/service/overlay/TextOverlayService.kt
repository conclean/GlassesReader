package com.app.glassesreader.service.overlay

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
import android.os.IBinder
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.app.glassesreader.R
import com.app.glassesreader.accessibility.ScreenTextPublisher
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
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        textCollectJob?.cancel()
        scope.cancel()
        EasyFloat.dismiss(TOGGLE_FLOAT_TAG, true)
        CxrCustomViewManager.close()
        prefs.edit().putBoolean(KEY_READER_ENABLED, false).apply()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handleToggleState(isActive: Boolean) {
        isReadingActive = isActive
        updateToggleUi()
        if (isReadingActive) {
            CxrCustomViewManager.ensureInitialized()
            startCollectingText()
        } else {
            stopCollectingText()
            CxrCustomViewManager.updateText("")
        }
        updateNotification(isActive = isActive)
        prefs.edit().putBoolean(KEY_READER_ENABLED, isReadingActive).apply()
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
        CxrCustomViewManager.updateText("")
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
            ContextCompat.startForegroundService(context, intent)
        }

        fun disableReader(context: Context) {
            val intent = Intent(context, TextOverlayService::class.java).apply {
                action = ACTION_DISABLE_READER
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun enableOverlay(context: Context) {
            val intent = Intent(context, TextOverlayService::class.java).apply {
                action = ACTION_ENABLE_OVERLAY
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun disableOverlay(context: Context) {
            val intent = Intent(context, TextOverlayService::class.java).apply {
                action = ACTION_DISABLE_OVERLAY
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun updateToggleAvailability(context: Context, enabled: Boolean, message: String?) {
            val intent = Intent(context, TextOverlayService::class.java).apply {
                action = ACTION_UPDATE_TOGGLE_AVAILABILITY
                putExtra(EXTRA_TOGGLE_ENABLED, enabled)
                putExtra(EXTRA_TOGGLE_MESSAGE, message)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TextOverlayService::class.java)
            context.stopService(intent)
        }

    }
}
