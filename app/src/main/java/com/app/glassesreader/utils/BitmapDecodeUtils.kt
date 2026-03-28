package com.app.glassesreader.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File

/**
 * BitmapFactory 默认忽略 EXIF 方向，叠字按像素坐标排版会与图库「转正后」观感不一致。
 * 解码后按 EXIF 将像素旋转到**视觉正向**，再算居中框。
 */
object BitmapDecodeUtils {

    fun decodeFileApplyExifOrientation(file: File): Bitmap? {
        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        ) ?: return null
        val degrees = readExifRotationDegrees(file)
        if (degrees == 0) return decoded
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val out = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        decoded.recycle()
        return out
    }

    private fun readExifRotationDegrees(file: File): Int {
        return runCatching {
            val exif = ExifInterface(file)
            when (
                exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }.getOrDefault(0)
    }
}
