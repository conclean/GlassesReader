package com.app.glassesreader.sdk

import android.util.Log
import org.json.JSONObject
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.listeners.CustomViewListener
import com.rokid.cxr.client.utils.ValueUtil

/**
 * 统一管理 Rokid 眼镜端自定义页面的打开、更新与关闭。
 */
object CxrCustomViewManager {

    private const val TAG = "CxrCustomViewManager"
    private const val TEXT_VIEW_ID = "tv_content"
    private const val DEFAULT_EMPTY_TEXT = "等待取样..."

    private val baseLayoutJson: String by lazy {
        """
        {
          "type": "LinearLayout",
          "props": {
            "layout_width": "match_parent",
            "layout_height": "match_parent",
            "orientation": "vertical",
            "gravity": "center_horizontal",
            "paddingTop": "120dp",
            "paddingBottom": "120dp",
            "paddingStart": "48dp",
            "paddingEnd": "48dp",
            "backgroundColor": "#CC000000"
          },
          "children": [
            {
              "type": "TextView",
              "props": {
                "id": "$TEXT_VIEW_ID",
                "layout_width": "match_parent",
                "layout_height": "wrap_content",
                "text": "$DEFAULT_EMPTY_TEXT",
                "textSize": "18sp",
                "textColor": "#FFFFFFFF",
                "gravity": "center"
              }
            }
          ]
        }
        """.trimIndent()
    }

    @Volatile
    private var listenerRegistered = false
    @Volatile
    private var openRequested = false
    @Volatile
    private var viewReady = false
    @Volatile
    private var latestText: String = DEFAULT_EMPTY_TEXT
    @Volatile
    private var pendingText: String? = null

    /**
     * 尝试在蓝牙连接准备就绪时打开自定义页面。
     */
    fun ensureInitialized() {
        runCatching {
            if (!CxrApi.getInstance().isBluetoothConnected) {
                Log.d(TAG, "Bluetooth not connected, skip opening custom view.")
                openRequested = false
                viewReady = false
                return
            }
            registerListenerIfNeeded()
            if (!openRequested) {
                Log.d(TAG, "Opening custom view...")
                openRequested = true
                val status = CxrApi.getInstance().openCustomView(baseLayoutJson)
                Log.d(TAG, "openCustomView status: $status")
                if (status == ValueUtil.CxrStatus.REQUEST_FAILED) {
                    openRequested = false
                }
            } else if (viewReady) {
                deliverPendingTextIfNeeded()
            }
        }.onFailure { throwable ->
            Log.e(TAG, "ensureInitialized failed: ${throwable.message}", throwable)
        }
    }

    /**
     * 更新展示文字，当页面尚未就绪时会缓存等待。
     */
    fun updateText(rawText: String?) {
        val displayText = sanitizeText(rawText)
        pendingText = displayText
        if (!viewReady) {
            ensureInitialized()
            return
        }
        sendTextToView(displayText)
    }

    /**
     * 关闭眼镜端页面。
     */
    fun close() {
        runCatching {
            pendingText = null
            latestText = DEFAULT_EMPTY_TEXT
            openRequested = false
            viewReady = false
            val status = CxrApi.getInstance().closeCustomView()
            Log.d(TAG, "closeCustomView status: $status")
        }.onFailure { throwable ->
            Log.e(TAG, "close custom view failed: ${throwable.message}", throwable)
        }
    }

    private fun registerListenerIfNeeded() {
        if (listenerRegistered) return
        CxrApi.getInstance().setCustomViewListener(object : CustomViewListener {
            override fun onIconsSent() {
                Log.d(TAG, "Custom view icons sent.")
            }

            override fun onOpened() {
                Log.d(TAG, "Custom view opened.")
                viewReady = true
                deliverPendingTextIfNeeded()
            }

            override fun onOpenFailed(errorCode: Int) {
                Log.e(TAG, "Custom view open failed: $errorCode")
                viewReady = false
                openRequested = false
            }

            override fun onUpdated() {
                Log.d(TAG, "Custom view updated.")
            }

            override fun onClosed() {
                Log.d(TAG, "Custom view closed.")
                viewReady = false
                openRequested = false
            }
        })
        listenerRegistered = true
    }

    private fun deliverPendingTextIfNeeded() {
        val text = pendingText ?: return
        if (text != latestText) {
            sendTextToView(text)
        }
    }

    private fun sendTextToView(text: String) {
        val payload = buildUpdatePayload(text)
        val status = runCatching {
            CxrApi.getInstance().updateCustomView(payload)
        }.onFailure { throwable ->
            Log.e(TAG, "updateCustomView error: ${throwable.message}", throwable)
        }.getOrNull()

        if (status == ValueUtil.CxrStatus.REQUEST_FAILED) {
            Log.w(TAG, "updateCustomView failed, will retry when possible.")
        } else {
            latestText = text
            if (status == ValueUtil.CxrStatus.REQUEST_SUCCEED) {
                pendingText = null
            }
            Log.d(TAG, "updateCustomView status: $status")
        }
    }

    private fun sanitizeText(rawText: String?): String {
        val trimmed = rawText?.trim()
        if (trimmed.isNullOrEmpty()) {
            return DEFAULT_EMPTY_TEXT
        }
        return if (trimmed.length <= MAX_LENGTH) trimmed else trimmed.take(MAX_LENGTH) + "..."
    }

    private fun buildUpdatePayload(text: String): String {
        val jsonText = JSONObject.quote(text)
        return """
            [
              {
                "action": "update",
                "id": "$TEXT_VIEW_ID",
                "props": {
                  "text": $jsonText
                }
              }
            ]
        """.trimIndent()
    }

    private val CxrApi.isBluetoothConnected: Boolean
        get() = runCatching { isBluetoothConnected() }
            .onFailure { Log.e(TAG, "Check bluetooth connection failed: ${it.message}", it) }
            .getOrDefault(false)

    private const val MAX_LENGTH = 500
}

