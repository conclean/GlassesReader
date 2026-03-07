@file:OptIn(ExperimentalFoundationApi::class)

package com.app.glassesreader.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.app.glassesreader.data.TextPreset

/**
 * 预设标签栏组件
 * 显示所有预设标签，支持切换、新建、编辑和删除
 */
@Composable
fun PresetTabRow(
    presets: List<TextPreset>,
    currentPresetId: String?,
    onPresetSelected: (String) -> Unit,
    onPresetLongPress: (TextPreset) -> Unit,
    onCreateNew: () -> Unit
) {
    val selectedIndex = presets.indexOfFirst { it.id == currentPresetId }.coerceAtLeast(0)
    
    ScrollableTabRow(
        selectedTabIndex = if (selectedIndex < presets.size) selectedIndex else 0,
        modifier = Modifier.fillMaxWidth()
    ) {
        // 预设标签
        presets.forEachIndexed { _, preset ->
            Tab(
                selected = preset.id == currentPresetId,
                onClick = { onPresetSelected(preset.id) },
                modifier = Modifier.combinedClickable(
                    onClick = { 
                        // 点击事件由 Tab 的 onClick 处理，这里只处理长按
                    },
                    onLongClick = { onPresetLongPress(preset) }
                ),
                text = {
                    Text(
                        text = preset.name,
                        maxLines = 1
                    )
                }
            )
        }
        
        // 新建按钮（作为最后一个Tab）
        Tab(
            selected = false,
            onClick = onCreateNew,
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("+")
                }
            }
        )
    }
}

/**
 * 新建预设对话框
 */
@Composable
fun CreatePresetDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var presetName by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("新建预设配置")
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = presetName,
                    onValueChange = { newValue ->
                        if (newValue.length <= 10) {
                            presetName = newValue
                            errorMessage = null
                        } else {
                            errorMessage = "名称不能超过10个字符"
                        }
                    },
                    label = { Text("预设名称") },
                    singleLine = true,
                    isError = errorMessage != null,
                    supportingText = errorMessage?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "提示：将基于当前设置创建新预设",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (presetName.isNotBlank()) {
                        onCreate(presetName)
                        onDismiss()
                    } else {
                        errorMessage = "请输入预设名称"
                    }
                },
                enabled = presetName.isNotBlank()
            ) {
                Text("创建")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

/**
 * 编辑预设对话框（长按弹出）
 * 包含重命名和删除功能
 */
@Composable
fun EditPresetDialog(
    preset: TextPreset,
    canDelete: Boolean,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit
) {
    var presetName by remember { mutableStateOf(preset.name) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    
    if (showDeleteConfirm) {
        // 删除确认对话框
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = {
                Text("删除预设")
            },
            text = {
                Text("确定要删除\"${preset.name}\"吗？删除后无法恢复。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDelete()
                        showDeleteConfirm = false
                        onDismiss()
                    }
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("取消")
                }
            }
        )
        return
    }
    
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "编辑预设",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                
                OutlinedTextField(
                    value = presetName,
                    onValueChange = { newValue ->
                        if (newValue.length <= 10) {
                            presetName = newValue
                            errorMessage = null
                        } else {
                            errorMessage = "名称不能超过10个字符"
                        }
                    },
                    label = { Text("预设名称") },
                    singleLine = true,
                    isError = errorMessage != null,
                    supportingText = errorMessage?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (canDelete) {
                        Button(
                            onClick = { showDeleteConfirm = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("删除")
                        }
                    }
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("取消")
                    }
                    Button(
                        onClick = {
                            if (presetName.isNotBlank() && presetName != preset.name) {
                                onRename(presetName)
                            }
                            onDismiss()
                        },
                        enabled = presetName.isNotBlank() && presetName != preset.name,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("确定")
                    }
                }
            }
        }
    }
}
