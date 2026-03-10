package com.app.glassesreader.accessibility.service

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.app.glassesreader.accessibility.ScreenTextPublisher

/**
 * ScreenTextService 通过无障碍事件获取当前屏幕上的文字信息。
 */
class ScreenTextService : AccessibilityService() {

    private var lastSnapshot: String = ""
    // 上次实际处理事件的时间，用于简单节流，避免高频事件导致卡顿
    private var lastHandledEventTime: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "ScreenTextService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !shouldHandle(event)) {
            return
        }

        // 简单时间节流：在短时间内的高频事件（例如滚动、内容频繁变化）只处理一部分
        // 例如：150ms 内只处理一次，既能保持文字相对及时，又能大幅降低主线程压力
        val now = SystemClock.uptimeMillis()
        if (now - lastHandledEventTime < 150) {
            return
        }
        lastHandledEventTime = now

        val collectedLines = mutableListOf<String>()
        val rootNode = rootInActiveWindow

        if (rootNode != null) {
            try {
                collectText(rootNode, collectedLines)
            } finally {
                rootNode.recycle()
            }
        }

        val snapshot = buildSnapshot(collectedLines, event)
        if (snapshot != lastSnapshot) {
            lastSnapshot = snapshot
            ScreenTextPublisher.updateText(snapshot)
        }
    }

    override fun onInterrupt() {
        ScreenTextPublisher.clear()
        lastSnapshot = ""
    }

    override fun onDestroy() {
        super.onDestroy()
        ScreenTextPublisher.clear()
        lastSnapshot = ""
        Log.i(TAG, "ScreenTextService destroyed")
    }

    /**
     * 判断是否需要处理该事件类型。
     */
    private fun shouldHandle(event: AccessibilityEvent): Boolean {
        return when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> true
            else -> false
        }
    }

    /**
     * 递归收集节点文字及描述信息。
     */
    private fun collectText(node: AccessibilityNodeInfo, container: MutableList<String>) {
        node.text?.toString()?.let { text ->
            val normalized = text.trim()
            if (normalized.isNotEmpty()) {
                container.add(normalized)
            }
        }

        node.contentDescription?.toString()?.let { description ->
            val normalized = description.trim()
            if (normalized.isNotEmpty()) {
                container.add(normalized)
            }
        }

        for (index in 0 until node.childCount) {
            val child = node.getChild(index)
            if (child != null) {
                try {
                    collectText(child, container)
                } finally {
                    child.recycle()
                }
            }
        }
    }

    /**
     * 将收集的文字整理为去重、去空白的段落，若为空则退回事件本身的文字。
     */
    private fun buildSnapshot(
        lines: List<String>,
        event: AccessibilityEvent
    ): String {
        val normalized = lines
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        if (normalized.isNotEmpty()) {
            return normalized.joinToString(separator = "\n")
        }

        val eventText = mutableListOf<String>()
        event.text?.forEach { seq ->
            val text = seq?.toString()?.trim()
            if (!text.isNullOrEmpty()) {
                eventText.add(text)
            }
        }
        event.contentDescription?.toString()?.trim()?.let {
            if (it.isNotEmpty()) {
                eventText.add(it)
            }
        }

        return eventText.distinct().joinToString(separator = "\n")
    }

    companion object {
        private const val TAG = "ScreenTextService"
    }
}

