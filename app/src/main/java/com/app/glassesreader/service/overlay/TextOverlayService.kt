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
import android.view.Gravity
import android.widget.TextView
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
    private var toggleTextView: TextView? = null
    private var contentTextView: TextView? = null
    private var latestContent: String = ""

    override fun onCreate() {
        super.onCreate()
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
        createContentFloat()
        EasyFloat.hide(TEXT_FLOAT_TAG)
        CxrCustomViewManager.ensureInitialized()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                stopSelf()
            }

            ACTION_ENABLE_READER -> {
                updateContentText("")
                handleToggleState(true)
            }

            ACTION_DISABLE_READER -> {
                handleToggleState(false)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        textCollectJob?.cancel()
        scope.cancel()
        EasyFloat.dismiss(TOGGLE_FLOAT_TAG, true)
        EasyFloat.dismiss(TEXT_FLOAT_TAG, true)
        CxrCustomViewManager.close()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handleToggleState(isActive: Boolean) {
        isReadingActive = isActive
        updateToggleUi()
        if (isActive) {
            EasyFloat.show(TEXT_FLOAT_TAG)
            CxrCustomViewManager.ensureInitialized()
            startCollectingText()
        } else {
            EasyFloat.hide(TEXT_FLOAT_TAG)
            stopCollectingText()
            CxrCustomViewManager.updateText("")
        }
        updateNotification(isActive = isActive)
    }

    private fun startCollectingText() {
        if (textCollectJob?.isActive == true) return
        textCollectJob = scope.launch {
            ScreenTextPublisher.state.collectLatest { state ->
                latestContent = state.text
                updateContentText(state.text)
                if (isReadingActive) {
                    CxrCustomViewManager.updateText(state.text)
                }
            }
        }
    }

    private fun stopCollectingText() {
        textCollectJob?.cancel()
        textCollectJob = null
        updateContentText("")
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
                toggleTextView = view.findViewById(R.id.tvToggle)
                view.setOnClickListener { handleToggleState(!isReadingActive) }
                updateToggleUi()
            }
            .show()
    }

    private fun createContentFloat() {
        EasyFloat.with(applicationContext)
            .setTag(TEXT_FLOAT_TAG)
            .setShowPattern(ShowPattern.ALL_TIME)
            .setDragEnable(false)
            .setLayout(R.layout.float_text) { view ->
                contentTextView = view.findViewById(R.id.tvFloatContent)
                updateContentText(latestContent)
            }
            .setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, resources.displayMetrics.heightPixels / 8)
            .show()
    }

    private fun updateToggleUi() {
        val label = if (isReadingActive) "Reading" else "Off"
        toggleTextView?.text = label
    }

    private fun updateContentText(content: String) {
        val displayText = if (content.isBlank()) {
            "等待取样..."
        } else {
            content
        }
        if (contentTextView == null) {
            EasyFloat.getFloatView(TEXT_FLOAT_TAG)?.findViewById<TextView>(R.id.tvFloatContent)?.let {
                contentTextView = it
            }
        }
        contentTextView?.text = displayText
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
        private const val TOGGLE_FLOAT_TAG = "toggle_float"
        private const val TEXT_FLOAT_TAG = "text_float"

        fun start(context: Context) {
            val intent = Intent(context, TextOverlayService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TextOverlayService::class.java)
            context.stopService(intent)
        }

    }
}
