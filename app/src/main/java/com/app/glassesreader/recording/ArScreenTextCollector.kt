package com.app.glassesreader.recording

import com.app.glassesreader.accessibility.ScreenTextPublisher
import com.app.glassesreader.sdk.CxrCustomViewManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 录屏期间采集读屏 [ScreenTextPublisher] 时间线（相对 [anchor] 的毫秒），供 ASS/叠字使用。
 */
class ArScreenTextCollector(
    private val anchor: ArRecordingTimeline.SessionAnchor,
    private val onCollected: (ArScreenTextPoint) -> Unit = {}
) {
    data class ArScreenTextPoint(val tMs: Long, val rawText: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private val points = mutableListOf<ArScreenTextPoint>()

    fun start() {
        job?.cancel()
        points.clear()
        val initial = ScreenTextPublisher.state.value
        val t0 = ArRecordingTimeline.relativeOffsetMs(initial.timestamp, anchor)
            .coerceAtLeast(0L)
        appendPoint(ArScreenTextPoint(t0, initial.text))
        job = scope.launch {
            ScreenTextPublisher.state.collect { st ->
                val t = ArRecordingTimeline.relativeOffsetMs(st.timestamp, anchor)
                appendPoint(ArScreenTextPoint(t.coerceAtLeast(0L), st.text))
            }
        }
    }

    private fun appendPoint(p: ArScreenTextPoint) {
        val last = points.lastOrNull()
        if (last != null && last.tMs == p.tMs && last.rawText == p.rawText) return
        points.add(p)
        onCollected(p)
    }

    fun snapshotPoints(): List<ArScreenTextPoint> = points.toList()

    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        /**
         * 将采样点转为非占位片段 [startMs, endMs) + 展示文案；无读屏时段不产生片段。
         */
        fun buildDisplaySegments(
            points: List<ArScreenTextPoint>,
            endMs: Long
        ): List<Triple<Long, Long, String>> {
            if (points.isEmpty() || endMs <= 0) return emptyList()
            val sorted = points.sortedBy { it.tMs }
            val out = mutableListOf<Triple<Long, Long, String>>()
            for (i in sorted.indices) {
                val start = sorted[i].tMs.coerceIn(0L, endMs)
                val nextStart = sorted.getOrNull(i + 1)?.tMs ?: endMs
                val segEnd = nextStart.coerceIn(start, endMs)
                if (segEnd <= start) continue
                val disp = CxrCustomViewManager.computeDisplayTextForOverlay(sorted[i].rawText)
                if (CxrCustomViewManager.isPlaceholderDisplayText(disp)) continue
                out.add(Triple(start, segEnd, disp))
            }
            return out
        }
    }
}
