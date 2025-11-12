package com.app.glassesreader.accessibility.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.app.glassesreader.accessibility.ScreenTextPublisher

/**
 * ScreenTextService 透過無障礙事件擷取目前螢幕上的文字資訊。
 */
class ScreenTextService : AccessibilityService() {

    private var lastSnapshot: String = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "ScreenTextService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !shouldHandle(event)) {
            return
        }

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
     * 判斷是否需要處理該事件類型。
     */
    private fun shouldHandle(event: AccessibilityEvent): Boolean {
        return when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> true
            else -> false
        }
    }

    /**
     * 遞迴收集節點文字及描述資訊。
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
     * 將收集的文字整理為去重、去空白的段落，若為空則退回事件本身的文字。
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

