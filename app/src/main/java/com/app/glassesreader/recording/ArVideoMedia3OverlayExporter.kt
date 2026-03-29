package com.app.glassesreader.recording

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.app.glassesreader.utils.MediaDurationUtils
import com.app.glassesreader.utils.PhotoOverlayComposer
import com.google.common.collect.ImmutableList
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "ArVideoMedia3Overlay"

/**
 * 使用 Media3 Transformer 将读屏时间线烧录进 MP4。
 * 叠字布局与 [PhotoOverlayComposer]（AR 截图）一致：居中 480×640 比例内缩框、框内左对齐多行、绿色与 sp 映射相同，**底部 endline 水印**与截图同款。
 * AR 录屏不需要声音：[EditedMediaItem] 去掉音轨。
 */
@UnstableApi
object ArVideoMedia3OverlayExporter {

    /**
     * 按时间切换文案；每段用 [PhotoOverlayComposer.renderOverlayArgbBitmap] 生成与截图一致的整帧透明叠图并缓存，避免每帧分配。
     */
    private class TimelinePhotoStyleBitmapOverlay(
        private val appContext: Context,
        private val segmentsMs: List<Triple<Long, Long, String>>,
        private val videoW: Int,
        private val videoH: Int,
        private val textSizeSp: Float
    ) : BitmapOverlay() {

        private val blankFrame: Bitmap = Bitmap.createBitmap(videoW, videoH, Bitmap.Config.ARGB_8888)
        private val lock = Any()
        private var cachedKey: String? = null
        private var cachedBitmap: Bitmap? = null

        override fun getBitmap(presentationTimeUs: Long): Bitmap {
            val tMs = presentationTimeUs / 1000L
            val seg = segmentsMs.firstOrNull { tMs >= it.first && tMs < it.second }
            if (seg == null) return blankFrame
            val raw = seg.third
            synchronized(lock) {
                val cur = cachedBitmap
                if (raw == cachedKey && cur != null && !cur.isRecycled) return cur
                cur?.takeIf { it !== blankFrame }?.recycle()
                val newBm = PhotoOverlayComposer.renderOverlayArgbBitmap(
                    appContext,
                    videoW,
                    videoH,
                    raw,
                    textSizeSp
                )
                cachedBitmap = newBm
                cachedKey = raw
                return newBm ?: blankFrame
            }
        }

        override fun release() {
            synchronized(lock) {
                cachedBitmap?.takeIf { it !== blankFrame }?.recycle()
                cachedBitmap = null
                cachedKey = null
                if (!blankFrame.isRecycled) blankFrame.recycle()
            }
            super.release()
        }
    }

    /**
     * @param segmentsMs 每条 [Triple] 为 startMs, endMs, 展示文案
     * @param textSizeSp 与 [com.app.glassesreader.sdk.CxrCustomViewManager.getTextSize] 一致
     */
    fun burnInTimeline(
        context: Context,
        inputMp4: File,
        outputMp4: File,
        segmentsMs: List<Triple<Long, Long, String>>,
        textSizeSp: Float
    ): Boolean {
        if (!inputMp4.isFile) {
            Log.w(TAG, "[burnIn] abort: input not a file path=${inputMp4.absolutePath}")
            return false
        }
        if (segmentsMs.isEmpty()) {
            Log.w(TAG, "[burnIn] abort: segments empty")
            return false
        }
        outputMp4.parentFile?.mkdirs()
        if (inputMp4.absolutePath == outputMp4.absolutePath) {
            Log.w(TAG, "[burnIn] abort: input and output same path")
            return false
        }

        val wh = MediaDurationUtils.getVideoSizePx(inputMp4)
        if (wh == null) {
            Log.w(TAG, "[burnIn] abort: could not read video size")
            return false
        }
        val (vw, vh) = wh

        Log.d(
            TAG,
            "[burnIn] start input=${inputMp4.absolutePath} (${inputMp4.length()} bytes) " +
                "out=${outputMp4.absolutePath} video=${vw}x${vh} textSizeSp=$textSizeSp segments=${segmentsMs.size}"
        )
        segmentsMs.take(5).forEachIndexed { i, t ->
            Log.d(TAG, "[burnIn] seg[$i] [${t.first},${t.second}) len=${t.third.length}")
        }

        val appCtx = context.applicationContext
        val overlay = TimelinePhotoStyleBitmapOverlay(appCtx, segmentsMs, vw, vh, textSizeSp)
        val overlayEffect = OverlayEffect(ImmutableList.of(overlay))
        val mediaItem = MediaItem.fromUri(Uri.fromFile(inputMp4))
        val editedMediaItem = EditedMediaItem.Builder(mediaItem)
            .setRemoveAudio(true)
            .setEffects(
                Effects(
                    ImmutableList.of(),
                    ImmutableList.of<Effect>(overlayEffect)
                )
            )
            .build()
        val composition = Composition.Builder(EditedMediaItemSequence(ImmutableList.of(editedMediaItem)))
            .build()

        val latch = CountDownLatch(1)
        val errorRef = AtomicReference<Throwable?>(null)

        val mainHandler = Handler(Looper.getMainLooper())
        Log.d(TAG, "[burnIn] schedule Transformer on main thread (caller=${Thread.currentThread().name})")
        mainHandler.post {
            try {
                val transformer = Transformer.Builder(appCtx).build()
                transformer.addListener(
                    object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, result: ExportResult) {
                            Log.d(TAG, "[burnIn] Transformer.onCompleted exportResult=$result")
                            latch.countDown()
                        }

                        override fun onError(
                            composition: Composition,
                            result: ExportResult,
                            exception: ExportException
                        ) {
                            errorRef.set(exception)
                            Log.e(TAG, "[burnIn] Transformer.onError result=$result", exception)
                            latch.countDown()
                        }
                    }
                )
                Log.d(TAG, "[burnIn] transformer.start(path=${outputMp4.absolutePath})")
                transformer.start(composition, outputMp4.absolutePath)
            } catch (e: Throwable) {
                errorRef.set(e)
                Log.e(TAG, "[burnIn] Transformer build/addListener/start failed", e)
                latch.countDown()
            }
        }

        val finished = latch.await(12, TimeUnit.MINUTES)
        val err = errorRef.get()
        val ok = finished && err == null && outputMp4.isFile && outputMp4.length() > 0L
        if (!ok) {
            Log.w(
                TAG,
                "[burnIn] failed finished=$finished err=${err?.message} " +
                    "outExists=${outputMp4.isFile} outLen=${if (outputMp4.isFile) outputMp4.length() else 0L}"
            )
            runCatching { outputMp4.delete() }
        } else {
            Log.d(TAG, "[burnIn] success out=${outputMp4.absolutePath} (${outputMp4.length()} bytes)")
        }
        return ok
    }
}

