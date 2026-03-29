package com.app.glassesreader.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.util.Log
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.ContextCompat
import com.app.glassesreader.R
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

/**
 * 在同步到本地的眼镜照片上叠字并另存为 JPEG。
 *
 * **显示区域**为逻辑尺寸 480×640（宽×高），按整张照片等比缩放后，**相对整张照片**水平、垂直居中。
 *
 * **文字**在该显示区域**内部**左对齐、自上而下排版（与眼镜 TextView `start|top` 一致）；「靠左」仅指在框内靠左，不是相对整图靠左。超出区域底部不显示。
 *
 * AR 截图导出与 AR 录屏烧录时，在显示区**底部**另绘 [R.drawable.endline]：宽度与显示区一致，高度按资源 viewport 比例随显示区缩放，读屏文字绘制在其上方区域。
 *
 * 字号按 [textSizeSp] 映射到**整图内 contain 后的逻辑 480 宽**（与眼镜端按完整显示区一致），**不**按缩小后的叠字框宽映射；否则叠字框相对 contain 缩小 [OVERLAY_BOX_INSET_FRACTION] 时，字会同比偏小一倍。
 *
 * **尺寸直觉**：先把逻辑 480×640 按整图「contain」放大到能放进画面的最大框（与眼镜同比例的一块）；再对该框的**宽高各乘** [OVERLAY_BOX_INSET_FRACTION] 得到实际叠字框（居中）。叠字仍只画在该内缩框内并 clip，但 sp→像素比例与眼镜「全宽 480」一致。
 *
 * 取 [OVERLAY_BOX_INSET_FRACTION] = 0.5 时，相对上述最大框**线度为一半、面积为 1/4**；若最大框铺满整图（同比例成片），则叠字区约占**整图面积的四分之一**。
 */
object PhotoOverlayComposer {

    private const val LOG_TAG = "PhotoOverlay"

    /** 逻辑显示区：宽×高 = 480×640（先居中于整图，再在框内排字） */
    private const val OVERLAY_LOGIC_W = 480f
    private const val OVERLAY_LOGIC_H = 640f

    /**
     * 相对「整图内最大可放入的 480×640 比例框」的**线度**比例（0~1）。
     * 面积为该比例的平方（0.5 → 面积约 1/4）；字号仍按缩小后的框宽映射。
     */
    private const val OVERLAY_BOX_INSET_FRACTION = 0.5f

    /**
     * 在「按 contain 宽映射 sp」之后的经验微调，用于与镜上观感对齐（略偏大可酌情调低）。
     * 当前为 3/4。
     */
    private const val OVERLAY_TEXT_SIZE_FINE_TUNE = 3f / 4f

    /** 与 [R.drawable.endline] 的 vector viewport 一致，用于在显示区底部按比例算高度 */
    private const val ENDLINE_VIEWPORT_W = 1389f
    private const val ENDLINE_VIEWPORT_H = 94f

    private val textColor = Color.rgb(0, 220, 0)

    /**
     * @param overlayText 已与眼镜端展示一致的最终字符串（空则只保存原图拷贝，不叠字）
     * @param textSizeSp 当前读屏/眼镜 [sp] 字号（与 [com.app.glassesreader.sdk.CxrCustomViewManager] 一致）
     * @param includeEndline 为 true 时在**显示区域**底部绘制 [R.drawable.endline]（宽同显示区宽，高按 viewport 比例；AR 截图与 [renderOverlayArgbBitmap] 一致）
     */
    fun composeAndSaveJpeg(
        context: Context,
        sourceFile: File,
        outputDir: File,
        overlayText: String,
        textSizeSp: Float,
        lineSpacingMultiplier: Float = 1f,
        lineSpacingExtraPx: Float = 0f,
        includeEndline: Boolean = true
    ): File? {
        if (!sourceFile.isFile) return null
        outputDir.mkdirs()

        val decoded = runCatching {
            BitmapDecodeUtils.decodeFileApplyExifOrientation(sourceFile)
        }.getOrNull() ?: return null

        val w = decoded.width
        val h = decoded.height
        if (w <= 0 || h <= 0) {
            decoded.recycle()
            return null
        }

        val bitmap = decoded.copy(Bitmap.Config.ARGB_8888, true)
        decoded.recycle()

        val canvas = Canvas(bitmap)

        val box = centeredOverlayBox(w, h)
        val boxLeft = box.left
        val boxTop = box.top
        val boxW = box.width()
        val boxH = box.height()
        Log.d(
            LOG_TAG,
            "image=${w}x${h} overlayBox=${boxW.toInt()}x${boxH.toInt()} " +
                "left=${"%.1f".format(boxLeft)} top=${"%.1f".format(boxTop)} " +
                "(已与 EXIF 转正后的像素一致)"
        )

        drawOverlayTextOnCanvas(
            canvas,
            w,
            h,
            overlayText,
            textSizeSp,
            lineSpacingMultiplier,
            lineSpacingExtraPx,
            includeEndline = includeEndline,
            context = if (includeEndline) context else null
        )

        val base = sourceFile.name.substringBeforeLast('.')
        val outFile = File(outputDir, "${base}_overlay.jpg")
        val ok = runCatching {
            FileOutputStream(outFile).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos)
            }
        }.isSuccess
        bitmap.recycle()
        if (!ok) {
            outFile.delete()
            return null
        }
        return outFile
    }

    /**
     * 与 [composeAndSaveJpeg] 相同的叠字框、字号映射与 [StaticLayout] 换行（框内左对齐），画在透明 ARGB 位图上，供 AR 视频烧录。
     *
     * 文案可为空：仅绘底部 [R.drawable.endline]（与 AR 截图水印一致）。
     *
     * @param includeEndline 默认 true，与截图导出同款底栏；需 [context] 以加载矢量资源。
     */
    fun renderOverlayArgbBitmap(
        context: Context,
        imageW: Int,
        imageH: Int,
        overlayText: String,
        textSizeSp: Float,
        lineSpacingMultiplier: Float = 1f,
        lineSpacingExtraPx: Float = 0f,
        includeEndline: Boolean = true
    ): Bitmap? {
        if (imageW <= 0 || imageH <= 0) return null
        val hasText = overlayText.isNotBlank()
        if (!hasText && !(includeEndline)) return null
        val bitmap = Bitmap.createBitmap(imageW, imageH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawOverlayTextOnCanvas(
            canvas,
            imageW,
            imageH,
            overlayText,
            textSizeSp,
            lineSpacingMultiplier,
            lineSpacingExtraPx,
            includeEndline = includeEndline,
            context = context
        )
        return bitmap
    }

    private fun drawOverlayTextOnCanvas(
        canvas: Canvas,
        imageW: Int,
        imageH: Int,
        overlayText: String,
        textSizeSp: Float,
        lineSpacingMultiplier: Float,
        lineSpacingExtraPx: Float,
        includeEndline: Boolean = false,
        context: Context? = null
    ) {
        val wantEndline = includeEndline && context != null
        val hasText = overlayText.isNotBlank()
        if (!hasText && !wantEndline) return

        val box = centeredOverlayBox(imageW, imageH)
        val boxLeft = box.left
        val boxTop = box.top
        val boxW = box.width()
        val boxH = box.height()

        val endLineH = if (wantEndline) {
            min(boxW * (ENDLINE_VIEWPORT_H / ENDLINE_VIEWPORT_W), boxH * 0.98f)
        } else {
            0f
        }
        val textClipBottom = (boxH - endLineH).coerceAtLeast(0f)

        if (hasText) {
            val textSizePx = (
                textSizeSp * (boxW / OVERLAY_LOGIC_W) / OVERLAY_BOX_INSET_FRACTION *
                    OVERLAY_TEXT_SIZE_FINE_TUNE
            ).coerceAtLeast(8f)
            val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = textColor
                typeface = Typeface.DEFAULT
                isSubpixelText = true
                textSize = textSizePx
            }
            val innerWidth = boxW.toInt().coerceAtLeast(1)
            val layout = buildStaticLayout(
                text = overlayText,
                paint = textPaint,
                width = innerWidth,
                lineSpacingMultiplier = lineSpacingMultiplier,
                lineSpacingExtraPx = lineSpacingExtraPx
            )
            canvas.save()
            canvas.translate(boxLeft, boxTop)
            canvas.clipRect(0f, 0f, boxW, textClipBottom)
            layout.draw(canvas)
            canvas.restore()
        }

        if (wantEndline && endLineH > 0f) {
            val ctx = context ?: return
            val d = ContextCompat.getDrawable(ctx, R.drawable.endline)?.mutate() ?: return
            canvas.save()
            canvas.translate(boxLeft, boxTop)
            val top = boxH - endLineH
            d.setBounds(0, top.toInt(), boxW.toInt(), boxH.toInt())
            d.draw(canvas)
            canvas.restore()
        }
    }

    /**
     * 在 [imageW]×[imageH] 像素内放置与逻辑 **480×640** 同比例的显示区，**相对整图**水平垂直居中。
     * 先算整图能容纳的最大比例 maxScale，再乘 [OVERLAY_BOX_INSET_FRACTION]（当前 0.5 → 相对最大框宽、高各一半，面积约 1/4）。
     */
    fun centeredOverlayBox(imageW: Int, imageH: Int): RectF {
        val iw = imageW.toFloat()
        val ih = imageH.toFloat()
        val maxScale = min(iw / OVERLAY_LOGIC_W, ih / OVERLAY_LOGIC_H)
        val scale = maxScale * OVERLAY_BOX_INSET_FRACTION
        val boxW = OVERLAY_LOGIC_W * scale
        val boxH = OVERLAY_LOGIC_H * scale
        val left = (iw - boxW) / 2f
        val top = (ih - boxH) / 2f
        return RectF(left, top, left + boxW, top + boxH)
    }

    private fun buildStaticLayout(
        text: String,
        paint: TextPaint,
        width: Int,
        lineSpacingMultiplier: Float,
        lineSpacingExtraPx: Float
    ): StaticLayout {
        val t = normalizeWhitespaceForLayout(text)
        // ALIGN_NORMAL：在传入的 [width] 内左对齐（即仅在叠字显示框内靠左，非整图靠左）
        val builder = StaticLayout.Builder.obtain(t, 0, t.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(lineSpacingExtraPx, lineSpacingMultiplier)
            .setIncludePad(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setUseLineSpacingFromFallbacks(false)
        }
        return builder.build()
    }

    private fun normalizeWhitespaceForLayout(s: String): String {
        return s.replace("\r\n", "\n").replace("\r", "\n")
    }
}
