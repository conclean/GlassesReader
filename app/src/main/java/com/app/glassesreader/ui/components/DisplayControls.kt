package com.app.glassesreader.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.app.glassesreader.sdk.CxrCustomViewManager
import kotlin.math.roundToInt

/**
 * 文字大小控制组件
 */
@Composable
fun TextSizeControl() {
    var textSize by remember {
        mutableStateOf(CxrCustomViewManager.getTextSize())
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "眼镜文字大小",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "${textSize.toInt()}sp",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = textSize,
                onValueChange = { newSize ->
                    textSize = newSize
                    CxrCustomViewManager.setTextSize(newSize)
                },
                valueRange = 12f..48f,
                steps = 35, // 12-48 共 36 个值，steps = 35
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "12sp - 48sp",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 文本处理控制组件
 */
@Composable
fun TextProcessingControls() {
    val options = CxrCustomViewManager.getTextProcessingOptions()
    var removeEmptyLines by remember { 
        mutableStateOf(options.removeEmptyLines) 
    }
    var removeLineBreaks by remember { 
        mutableStateOf(options.removeLineBreaks) 
    }
    var removeFirstLine by remember { 
        mutableStateOf(options.removeFirstLine) 
    }
    var removeLastLine by remember { 
        mutableStateOf(options.removeLastLine) 
    }
    var removeFirstLineCount by remember { 
        mutableStateOf(options.removeFirstLineCount.toString()) 
    }
    var removeLastLineCount by remember { 
        mutableStateOf(options.removeLastLineCount.toString()) 
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "文本处理选项",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )

            // 删除空行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "删除空行",
                    style = MaterialTheme.typography.bodyMedium
                )
                Switch(
                    checked = removeEmptyLines,
                    onCheckedChange = {
                        removeEmptyLines = it
                        CxrCustomViewManager.setTextProcessingOptions(removeEmptyLines = it)
                    }
                )
            }

            // 删除换行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "删除换行",
                    style = MaterialTheme.typography.bodyMedium
                )
                Switch(
                    checked = removeLineBreaks,
                    onCheckedChange = {
                        removeLineBreaks = it
                        CxrCustomViewManager.setTextProcessingOptions(removeLineBreaks = it)
                    }
                )
            }

            // 删除第一行
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "删除前 N 行",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(
                        checked = removeFirstLine,
                        onCheckedChange = {
                            removeFirstLine = it
                            val count = removeFirstLineCount.toIntOrNull() ?: 1
                            CxrCustomViewManager.setTextProcessingOptions(
                                removeFirstLine = it,
                                removeFirstLineCount = count
                            )
                        }
                    )
                }
                if (removeFirstLine) {
                    OutlinedTextField(
                        value = removeFirstLineCount,
                        onValueChange = { newValue ->
                            // 只允许输入数字
                            if (newValue.all { it.isDigit() } || newValue.isEmpty()) {
                                removeFirstLineCount = newValue
                                val count = newValue.toIntOrNull() ?: 1
                                CxrCustomViewManager.setTextProcessingOptions(
                                    removeFirstLine = true,
                                    removeFirstLineCount = count
                                )
                            }
                        },
                        label = { Text("行数") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number
                        )
                    )
                }
            }

            // 删除最后一行
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "删除后 N 行",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(
                        checked = removeLastLine,
                        onCheckedChange = {
                            removeLastLine = it
                            val count = removeLastLineCount.toIntOrNull() ?: 1
                            CxrCustomViewManager.setTextProcessingOptions(
                                removeLastLine = it,
                                removeLastLineCount = count
                            )
                        }
                    )
                }
                if (removeLastLine) {
                    OutlinedTextField(
                        value = removeLastLineCount,
                        onValueChange = { newValue ->
                            // 只允许输入数字
                            if (newValue.all { it.isDigit() } || newValue.isEmpty()) {
                                removeLastLineCount = newValue
                                val count = newValue.toIntOrNull() ?: 1
                                CxrCustomViewManager.setTextProcessingOptions(
                                    removeLastLine = true,
                                    removeLastLineCount = count
                                )
                            }
                        },
                        label = { Text("行数") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number
                        )
                    )
                }
            }
        }
    }
}

/**
 * 亮度控制组件
 */
@Composable
fun BrightnessControl(
    brightness: Int,
    enabled: Boolean,
    onBrightnessChange: (Int) -> Unit,
    minBrightness: Int = 0,
    maxBrightness: Int = 15
) {
    var sliderValue by remember(brightness) { mutableStateOf(brightness.toFloat()) }

    androidx.compose.runtime.LaunchedEffect(brightness) {
        sliderValue = brightness.toFloat()
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "眼镜亮度",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = sliderValue.roundToInt().coerceIn(minBrightness, maxBrightness).toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                onValueChangeFinished = {
                    if (enabled) {
                        onBrightnessChange(
                            sliderValue.roundToInt().coerceIn(minBrightness, maxBrightness)
                        )
                    } else {
                        sliderValue = brightness.toFloat()
                    }
                },
                valueRange = minBrightness.toFloat()..maxBrightness.toFloat(),
                steps = (maxBrightness - minBrightness - 1).coerceAtLeast(0),
                enabled = enabled
            )

            if (!enabled) {
                Text(
                    text = "请连接智能眼镜后再调整亮度",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

