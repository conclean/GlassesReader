package com.app.glassesreader.data

import java.util.UUID

/**
 * 文字设置预设数据模型
 */
data class TextPreset(
    val id: String,                      // 唯一标识（UUID）
    val name: String,                    // 预设名称（用户自定义，最多10字符）
    val brightness: Int,                 // 亮度 0-15
    val textSize: Float,                 // 字体大小 12-48sp
    val removeEmptyLines: Boolean,      // 删除空行
    val removeLineBreaks: Boolean,       // 删除换行
    val removeFirstLine: Boolean,        // 删除前N行
    val removeFirstLineCount: Int,       // 删除前N行的数量
    val removeLastLine: Boolean,         // 删除后N行
    val removeLastLineCount: Int,        // 删除后N行的数量
    val lastUsedTime: Long = System.currentTimeMillis() // 最后使用时间（用于排序）
) {
    companion object {
        /**
         * 创建默认预设
         */
        fun createDefault(): TextPreset {
            return TextPreset(
                id = UUID.randomUUID().toString(),
                name = "默认配置",
                brightness = 8,
                textSize = 18f,
                removeEmptyLines = false,
                removeLineBreaks = false,
                removeFirstLine = false,
                removeFirstLineCount = 1,
                removeLastLine = false,
                removeLastLineCount = 1
            )
        }
    }
}
