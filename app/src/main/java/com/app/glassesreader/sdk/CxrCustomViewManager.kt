package com.app.glassesreader.sdk

import android.content.Context
import android.util.Log
import org.json.JSONObject
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.listeners.CustomViewListener
import com.rokid.cxr.client.utils.ValueUtil

/**
 * Rokid 眼镜端自定义页面管理器
 * 
 * 【作用】
 * 统一管理 Rokid 眼镜端自定义页面的打开、更新与关闭。
 * 通过 JSON 描述页面布局，下发到眼镜端显示，实现文本内容的实时显示。
 * 
 * 【主要功能】
 * 1. 打开自定义界面（openCustomView）- 使用 JSON 描述初始化页面
 * 2. 更新界面内容（updateCustomView）- 更新特定控件的属性
 * 3. 关闭界面（closeCustomView）- 关闭眼镜端页面
 * 4. 监听界面状态（CustomViewListener）- 监听页面生命周期事件
 * 5. 文本处理和格式化 - 应用用户设置的文本处理选项
 * 6. 字体大小管理 - 支持动态调整字体大小
 * 
 * 【参考文档】
 * - 自定义页面场景.md 第1节 "打开自定义界面"
 *   - openCustomView(content: String) 方法
 *   - content 为界面初始化 JSON 字符串
 * - 自定义页面场景.md 第2节 "监听界面状态"
 *   - setCustomViewListener() 方法
 *   - CustomViewListener 接口说明
 * - 自定义页面场景.md 第3节 "更新界面"
 *   - updateCustomView(content: String) 方法
 *   - content 为 JSON 数组，指定操作类型和目标控件
 * - 自定义页面场景.md 第4节 "关闭界面"
 *   - closeCustomView() 方法
 * - 自定义页面场景.md 第6节 "初始化布局 JSON"
 *   - 布局支持 LinearLayout 和 RelativeLayout
 *   - 控件支持 TextView 和 ImageView
 * 
 * 【使用流程】
 * 1. 调用 init(context) 初始化管理器
 * 2. 确保蓝牙已连接（通过 CxrConnectionManager）
 * 3. 调用 ensureInitialized() 打开自定义页面
 * 4. 调用 updateText() 更新文本内容
 * 5. 调用 close() 关闭页面
 * 
 * 【注意事项】
 * - 使用自定义页面前，必须确保蓝牙连接已建立
 * - 页面描述使用 JSON 格式
 * - 文本长度限制为 500 字符（超过会截断）
 * - 字体大小范围：12-48 sp
 */
object CxrCustomViewManager {

    private const val TAG = "CxrCustomViewManager"
    private const val TEXT_VIEW_ID = "tv_content"
    private const val DEFAULT_EMPTY_TEXT = "连接已暂停..."
    private const val PREFS_NAME = "glasses_reader_prefs"
    private const val KEY_TEXT_SIZE = "text_size"
    private const val KEY_REMOVE_EMPTY_LINES = "remove_empty_lines"
    private const val KEY_REMOVE_LINE_BREAKS = "remove_line_breaks"
    private const val KEY_REMOVE_FIRST_LINE = "remove_first_line"
    private const val KEY_REMOVE_LAST_LINE = "remove_last_line"
    private const val KEY_REMOVE_FIRST_LINE_COUNT = "remove_first_line_count"
    private const val KEY_REMOVE_LAST_LINE_COUNT = "remove_last_line_count"
    private const val DEFAULT_TEXT_SIZE = 18f // 默认字体大小（sp）
    private const val DEFAULT_LINE_COUNT = 1 // 默认删除行数

    private var textSize: Float = DEFAULT_TEXT_SIZE
    private var removeEmptyLines: Boolean = false
    private var removeLineBreaks: Boolean = false
    private var removeFirstLine: Boolean = false
    private var removeLastLine: Boolean = false
    private var removeFirstLineCount: Int = DEFAULT_LINE_COUNT
    private var removeLastLineCount: Int = DEFAULT_LINE_COUNT
    private var context: Context? = null

    /**
     * 页面状态变化监听器
     * 用于通知外部页面状态的变化（打开/关闭）
     */
    interface ViewStateListener {
        /**
         * 页面状态变化回调
         * @param isActive true 表示页面已打开，false 表示页面已关闭
         */
        fun onViewStateChanged(isActive: Boolean)
    }

    private var viewStateListener: ViewStateListener? = null

    /**
     * 设置页面状态变化监听器
     * @param listener 监听器，传入 null 可取消监听
     */
    fun setViewStateListener(listener: ViewStateListener?) {
        viewStateListener = listener
    }

    /**
     * 初始化，设置 Context 并加载保存的设置
     */
    fun init(context: Context) {
        this.context = context.applicationContext
        loadSettings()
    }

    /**
     * 设置字体大小
     */
    fun setTextSize(size: Float) {
        textSize = size.coerceIn(12f, 48f) // 限制在 12-48 sp 之间
        saveTextSize()
        // 如果视图已打开，立即更新字体大小
        if (viewReady) {
            updateTextSize()
        }
    }

    /**
     * 获取当前字体大小
     */
    fun getTextSize(): Float = textSize

    /**
     * 设置文本处理选项
     */
    fun setTextProcessingOptions(
        removeEmptyLines: Boolean = this.removeEmptyLines,
        removeLineBreaks: Boolean = this.removeLineBreaks,
        removeFirstLine: Boolean = this.removeFirstLine,
        removeLastLine: Boolean = this.removeLastLine,
        removeFirstLineCount: Int = this.removeFirstLineCount,
        removeLastLineCount: Int = this.removeLastLineCount
    ) {
        this.removeEmptyLines = removeEmptyLines
        this.removeLineBreaks = removeLineBreaks
        this.removeFirstLine = removeFirstLine
        this.removeLastLine = removeLastLine
        this.removeFirstLineCount = removeFirstLineCount.coerceIn(1, 100) // 限制在 1-100 行之间
        this.removeLastLineCount = removeLastLineCount.coerceIn(1, 100)
        saveSettings()
        // 如果视图已打开，重新处理并更新文本
        if (viewReady && latestRawText != DEFAULT_EMPTY_TEXT) {
            val sanitized = sanitizeText(latestRawText)
            val processedText = processText(sanitized)
            sendTextToView(processedText)
        }
    }

    /**
     * 获取文本处理选项
     */
    fun getTextProcessingOptions(): TextProcessingOptions {
        return TextProcessingOptions(
            removeEmptyLines = removeEmptyLines,
            removeLineBreaks = removeLineBreaks,
            removeFirstLine = removeFirstLine,
            removeLastLine = removeLastLine,
            removeFirstLineCount = removeFirstLineCount,
            removeLastLineCount = removeLastLineCount
        )
    }

    data class TextProcessingOptions(
        val removeEmptyLines: Boolean,
        val removeLineBreaks: Boolean,
        val removeFirstLine: Boolean,
        val removeLastLine: Boolean,
        val removeFirstLineCount: Int,
        val removeLastLineCount: Int
    )

    private fun loadSettings() {
        context?.let { ctx ->
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            textSize = prefs.getFloat(KEY_TEXT_SIZE, DEFAULT_TEXT_SIZE)
            removeEmptyLines = prefs.getBoolean(KEY_REMOVE_EMPTY_LINES, false)
            removeLineBreaks = prefs.getBoolean(KEY_REMOVE_LINE_BREAKS, false)
            removeFirstLine = prefs.getBoolean(KEY_REMOVE_FIRST_LINE, false)
            removeLastLine = prefs.getBoolean(KEY_REMOVE_LAST_LINE, false)
            removeFirstLineCount = prefs.getInt(KEY_REMOVE_FIRST_LINE_COUNT, DEFAULT_LINE_COUNT)
            removeLastLineCount = prefs.getInt(KEY_REMOVE_LAST_LINE_COUNT, DEFAULT_LINE_COUNT)
        }
    }

    private fun saveSettings() {
        context?.let { ctx ->
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putFloat(KEY_TEXT_SIZE, textSize)
                .putBoolean(KEY_REMOVE_EMPTY_LINES, removeEmptyLines)
                .putBoolean(KEY_REMOVE_LINE_BREAKS, removeLineBreaks)
                .putBoolean(KEY_REMOVE_FIRST_LINE, removeFirstLine)
                .putBoolean(KEY_REMOVE_LAST_LINE, removeLastLine)
                .putInt(KEY_REMOVE_FIRST_LINE_COUNT, removeFirstLineCount)
                .putInt(KEY_REMOVE_LAST_LINE_COUNT, removeLastLineCount)
                .apply()
        }
    }

    private fun saveTextSize() {
        context?.let { ctx ->
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putFloat(KEY_TEXT_SIZE, textSize).apply()
        }
    }

    /**
     * 构建基础布局 JSON
     * 
     * 参考文档：自定义页面场景.md 第6节 "初始化布局 JSON"
     * 使用 LinearLayout 作为根布局，包含一个 TextView 用于显示文本
     * 布局支持 LinearLayout 和 RelativeLayout
     * 控件支持 TextView 和 ImageView
     */
    private fun buildBaseLayoutJson(): String {
        val textSizeStr = "${textSize.toInt()}sp"
        return """
        {
          "type": "LinearLayout",
          "props": {
            "layout_width": "match_parent",
            "layout_height": "match_parent",
            "orientation": "vertical",
            "backgroundColor": "#CC000000"
          },
          "children": [
            {
              "type": "TextView",
              "props": {
                "id": "$TEXT_VIEW_ID",
                "layout_width": "match_parent",
                "layout_height": "match_parent",
                "text": "$DEFAULT_EMPTY_TEXT",
                "textSize": "$textSizeStr",
                "textColor": "#FFFFFFFF",
                "gravity": "start|top"
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
    private var latestRawText: String = DEFAULT_EMPTY_TEXT // 保存原始文本，用于重新处理
    @Volatile
    private var pendingText: String? = null

    /**
     * 尝试在蓝牙连接准备就绪时打开自定义页面
     * 
     * 参考文档：自定义页面场景.md 第1节 "打开自定义界面"
     * 使用 openCustomView(content) 方法打开自定义页面
     * 
     * 注意：使用自定义页面前，请确保蓝牙连接已建立
     */
    fun ensureInitialized() {
        runCatching {
            if (!CxrApi.getInstance().isBluetoothConnected) {
                Log.d(TAG, "Bluetooth not connected, skip opening custom view.")
                val wasReady = viewReady
                openRequested = false
                viewReady = false
                // 如果之前是打开状态，现在因为蓝牙断开而关闭，需要通知
                if (wasReady) {
                    viewStateListener?.onViewStateChanged(false)
                }
                return
            }
            registerListenerIfNeeded()
            if (!openRequested) {
                Log.d(TAG, "Opening custom view...")
                openRequested = true
                val layoutJson = buildBaseLayoutJson()
                // 参考文档：自定义页面场景.md 第1节
                // openCustomView(content) 接受 JSON 描述字符串，返回 CxrStatus
                val status = CxrApi.getInstance().openCustomView(layoutJson)
                Log.d(TAG, "openCustomView status: $status")
                if (status == ValueUtil.CxrStatus.REQUEST_FAILED) {
                    openRequested = false
                }
            } else if (viewReady) {
                // 页面已打开且就绪，发送待处理的文本
                deliverPendingTextIfNeeded()
            } else {
                // openRequested 为 true 但 viewReady 为 false
                // 说明页面被外部关闭了（例如用户在眼镜端手动关闭），需要重新打开
                Log.d(TAG, "View was closed externally (openRequested=true but viewReady=false), reopening...")
                openRequested = false // 重置状态，允许重新打开
                // 重新打开页面
                val layoutJson = buildBaseLayoutJson()
                openRequested = true
                val status = CxrApi.getInstance().openCustomView(layoutJson)
                Log.d(TAG, "Reopen custom view status: $status")
                if (status == ValueUtil.CxrStatus.REQUEST_FAILED) {
                    openRequested = false
                }
            }
        }.onFailure { throwable ->
            Log.e(TAG, "ensureInitialized failed: ${throwable.message}", throwable)
        }
    }

    /**
     * 更新展示文字，当页面尚未就绪时会缓存等待。
     */
    fun updateText(rawText: String?) {
        latestRawText = rawText ?: ""
        val sanitized = sanitizeText(rawText)
        val processed = processText(sanitized)
        pendingText = processed
        if (!viewReady) {
            ensureInitialized()
            return
        }
        sendTextToView(processed)
    }

    /**
     * 处理文本：应用用户设置的文本处理选项
     */
    private fun processText(text: String): String {
        if (text == DEFAULT_EMPTY_TEXT) {
            return text
        }

        var result = text

        // 删除前 N 行
        if (removeFirstLine && removeFirstLineCount > 0) {
            val lines = result.lines()
            val count = removeFirstLineCount.coerceAtMost(lines.size)
            if (count > 0 && lines.size > count) {
                result = lines.drop(count).joinToString("\n")
            } else if (count > 0 && lines.size <= count) {
                result = ""
            }
        }

        // 删除后 N 行
        if (removeLastLine && removeLastLineCount > 0) {
            val lines = result.lines()
            val count = removeLastLineCount.coerceAtMost(lines.size)
            if (count > 0 && lines.size > count) {
                result = lines.dropLast(count).joinToString("\n")
            } else if (count > 0 && lines.size <= count) {
                result = ""
            }
        }

        // 删除空行（将多个连续空行合并为一个空行，或删除所有空行）
        if (removeEmptyLines) {
            // 删除所有空行（包括只包含空白字符的行）
            result = result.lines()
                .filter { it.trim().isNotEmpty() }
                .joinToString("\n")
        }

        // 删除换行（将所有换行符替换为空格）
        if (removeLineBreaks) {
            result = result.replace("\n", " ")
            // 合并多个连续空格为一个空格
            result = result.replace(Regex("\\s+"), " ")
        }

        return result.trim()
    }

    /**
     * 关闭眼镜端页面
     * 
     * 参考文档：自定义页面场景.md 第4节 "关闭界面"
     * 使用 closeCustomView() 方法关闭自定义页面
     */
    fun close() {
        runCatching {
            pendingText = null
            latestText = DEFAULT_EMPTY_TEXT
            latestRawText = DEFAULT_EMPTY_TEXT
            openRequested = false
            viewReady = false
            // 参考文档：自定义页面场景.md 第4节
            val status = CxrApi.getInstance().closeCustomView()
            Log.d(TAG, "closeCustomView status: $status")
            // 注意：onClosed() 回调会在 SDK 确认关闭后触发，这里不需要手动通知
        }.onFailure { throwable ->
            Log.e(TAG, "close custom view failed: ${throwable.message}", throwable)
        }
    }

    fun isViewActive(): Boolean = viewReady

    /**
     * 注册自定义页面状态监听器
     * 
     * 参考文档：自定义页面场景.md 第2节 "监听界面状态"
     * 使用 setCustomViewListener() 方法注册监听器，监听页面生命周期事件
     */
    private fun registerListenerIfNeeded() {
        if (listenerRegistered) return
        // 参考文档：自定义页面场景.md 第2节
        // setCustomViewListener() 注册 CustomViewListener 监听器
        CxrApi.getInstance().setCustomViewListener(object : CustomViewListener {
            /**
             * 图标已下发回调
             * 参考文档：自定义页面场景.md 第2节
             */
            override fun onIconsSent() {
                Log.d(TAG, "Custom view icons sent.")
            }

            /**
             * 页面已打开回调
             * 参考文档：自定义页面场景.md 第2节
             */
            override fun onOpened() {
                Log.d(TAG, "Custom view opened.")
                viewReady = true
                viewStateListener?.onViewStateChanged(true)
                deliverPendingTextIfNeeded()
            }

            /**
             * 打开失败回调
             * 参考文档：自定义页面场景.md 第2节
             */
            override fun onOpenFailed(errorCode: Int) {
                Log.e(TAG, "Custom view open failed: $errorCode")
                viewReady = false
                openRequested = false
                viewStateListener?.onViewStateChanged(false)
            }

            /**
             * 页面已更新回调
             * 参考文档：自定义页面场景.md 第2节
             */
            override fun onUpdated() {
                Log.d(TAG, "Custom view updated.")
            }

            /**
             * 页面已关闭回调
             * 参考文档：自定义页面场景.md 第2节
             */
            override fun onClosed() {
                Log.d(TAG, "Custom view closed.")
                viewReady = false
                openRequested = false
                viewStateListener?.onViewStateChanged(false)
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

    /**
     * 发送文本到眼镜端视图
     * 
     * 参考文档：自定义页面场景.md 第3节 "更新界面"
     * 使用 updateCustomView(content) 方法更新已打开界面的特定控件
     * content 为 JSON 数组，指定操作类型、目标控件 ID 以及修改属性
     */
    private fun sendTextToView(text: String) {
        val payload = buildUpdatePayload(text)
        val status = runCatching {
            // 参考文档：自定义页面场景.md 第3节
            // updateCustomView(content) 更新已打开界面的特定控件
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

    private fun updateTextSize() {
        if (!viewReady) return
        val textSizeStr = "${textSize.toInt()}sp"
        val payload = """
            [
              {
                "action": "update",
                "id": "$TEXT_VIEW_ID",
                "props": {
                  "textSize": "$textSizeStr"
                }
              }
            ]
        """.trimIndent()
        runCatching {
            val status = CxrApi.getInstance().updateCustomView(payload)
            Log.d(TAG, "updateTextSize status: $status")
        }.onFailure { throwable ->
            Log.e(TAG, "updateTextSize error: ${throwable.message}", throwable)
        }
    }

    /**
     * 构建更新 JSON 载荷
     * 
     * 参考文档：自定义页面场景.md 第3节 "更新界面" 和第7节 "更新布局 JSON"
     * 更新时仅需传递变更项，使用 JSON 数组格式
     * action: "update" 表示更新操作
     * id: 目标控件 ID
     * props: 要修改的属性
     */
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

