package com.app.glassesreader.accessibility

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 无障碍文字广播中心，负责在应用内共享最新的屏幕文字。
 */
object ScreenTextPublisher {
    /**
     * 文字状态数据结构，包含内容与时间戳。
     */
    data class ScreenTextState(
        val text: String,
        val timestamp: Long
    )

    private val _state = MutableStateFlow(ScreenTextState(text = "", timestamp = 0L))

    /**
     * 对外暴露不可变的状态流，方便浮窗服务或其他模块观测。
     */
    val state: StateFlow<ScreenTextState> = _state.asStateFlow()

    /**
     * 更新字幕内容，避免重复发送相同信息。
     */
    fun updateText(rawText: String) {
        val normalized = rawText.trim()
        if (normalized == _state.value.text) {
            return
        }
        _state.value = ScreenTextState(
            text = normalized,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * 当服务中断时清除当前数据。
     */
    fun clear() {
        _state.value = ScreenTextState(text = "", timestamp = System.currentTimeMillis())
    }
}

