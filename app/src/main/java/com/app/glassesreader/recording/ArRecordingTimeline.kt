package com.app.glassesreader.recording

/**
 * AR 录屏与读屏叠字的时间轴约定（实现录屏/导出时可按实测微调）。
 *
 * **t=0 怎么取（当前约定）**
 * - 在 `CxrApi.controlScene(VIDEO_RECORD, true, …)` **返回** `ValueUtil.CxrStatus.REQUEST_SUCCEED`（Rokid CXR SDK）之后，
 *   **立刻**调用 [captureAnchorAfterRecordRequestSucceed]（内部用 [System.currentTimeMillis]）。
 * - 与 [com.app.glassesreader.accessibility.ScreenTextPublisher] 的 `timestamp` 同一时钟，
 *   叠字相对时间：`eventEpochMs - anchor.startEpochMs`（见 [relativeOffsetMs]）。
 *
 * **为何不用「按下瞬间」或「首帧」**
 * - SDK 未提供首帧/文件起点回调；同步成功是唯一能稳定挂钩的 API 边界。
 * - 若成片与字幕有固定偏差，可在导出阶段加一个常量偏移（毫秒），或用视频轨 PTS 再对齐。
 *
 * **成片长度**
 * - 取 `min(视频时长, 叠字时间线长度)`，按较短者裁尾部（后续在合成模块落实）。
 */
object ArRecordingTimeline {

    /**
     * 一次录屏会话的时间原点（epoch 毫秒，与 [System.currentTimeMillis] 一致）。
     */
    data class SessionAnchor(val startEpochMs: Long)

    /**
     * 仅在 `controlScene(VIDEO_RECORD, true)` 已返回 `REQUEST_SUCCEED` 后调用，用于锁定 t=0。
     */
    fun captureAnchorAfterRecordRequestSucceed(): SessionAnchor =
        SessionAnchor(startEpochMs = System.currentTimeMillis())

    /**
     * 将读屏事件时间戳转为相对录屏起点的毫秒（可为负，若事件略早于锚点取样）。
     */
    fun relativeOffsetMs(eventEpochMs: Long, anchor: SessionAnchor): Long =
        eventEpochMs - anchor.startEpochMs
}
