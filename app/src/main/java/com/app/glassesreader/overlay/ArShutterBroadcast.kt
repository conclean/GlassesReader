package com.app.glassesreader.overlay

/**
 * AR 截图快门悬浮窗倒计时结束后发往 [GlassesReaderApp] 的广播，由 [com.app.glassesreader.MainActivity] 执行拍照与同步。
 */
object ArShutterBroadcast {
    const val ACTION = "com.app.glassesreader.action.AR_SHUTTER_FIRE"
    const val EXTRA_SNAPSHOT_TEXT = "extra_snapshot_text"
}
