package com.app.glassesreader.utils

import android.media.MediaMetadataRetriever
import java.io.File

object MediaDurationUtils {

    fun getVideoDurationMs(file: File): Long {
        if (!file.isFile) return 0L
        val r = MediaMetadataRetriever()
        return runCatching {
            r.setDataSource(file.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        }.getOrDefault(0L).also {
            runCatching { r.release() }
        }
    }

    /**
     * 解码后画面像素尺寸（与 Transformer 合成用帧一致）；若带 90°/270° rotation 元数据则交换宽高。
     */
    fun getVideoSizePx(file: File): Pair<Int, Int>? {
        if (!file.isFile) return null
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (w <= 0 || h <= 0) null
            else if (rot == 90 || rot == 270) Pair(h, w) else Pair(w, h)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }
}
