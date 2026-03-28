package com.app.glassesreader.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream

/**
 * 将已有 JPEG 写入系统相册，便于在「图库 / Google 相册」中直接浏览。
 * 相册目录：`Pictures/GlassesReader`（各 ROM 可能显示为「GlassesReader」相册）。
 */
object GalleryMediaStore {

    private val albumRelativePath: String
        get() = "${Environment.DIRECTORY_PICTURES}/GlassesReader"

    private val videoAlbumRelativePath: String
        get() = "${Environment.DIRECTORY_MOVIES}/GlassesReader"

    /**
     * 把本地 JPEG 复制到公共相册并返回 [Uri]，失败返回 null。
     */
    fun insertJpegFromFile(context: Context, jpegFile: File, displayName: String? = null): Uri? {
        if (!jpegFile.isFile) return null
        val name = displayName ?: jpegFile.name
        val resolver = context.contentResolver

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, albumRelativePath)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val uri = resolver.insert(collection, contentValues) ?: return null

        // copyTo 返回 Long，原先误与 true 比较导致永远判定写入失败
        val writeOk = runCatching {
            val stream = resolver.openOutputStream(uri) ?: return@runCatching false
            stream.use { out ->
                FileInputStream(jpegFile).use { it.copyTo(out) }
            }
            true
        }.getOrDefault(false)

        if (!writeOk) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val done = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            resolver.update(uri, done, null, null)
        }

        return uri
    }

    /**
     * 将本地 MP4 复制到系统相册（Movies/GlassesReader），失败返回 null。
     */
    fun insertMp4FromFile(context: Context, mp4File: File, displayName: String? = null): Uri? {
        if (!mp4File.isFile) return null
        val name = displayName ?: mp4File.name
        val resolver = context.contentResolver

        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, videoAlbumRelativePath)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val uri = resolver.insert(collection, contentValues) ?: return null

        val writeOk = runCatching {
            val stream = resolver.openOutputStream(uri) ?: return@runCatching false
            stream.use { out ->
                FileInputStream(mp4File).use { it.copyTo(out) }
            }
            true
        }.getOrDefault(false)

        if (!writeOk) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val done = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            resolver.update(uri, done, null, null)
        }

        return uri
    }
}
