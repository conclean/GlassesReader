package com.app.glassesreader.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.app.glassesreader.update.UpdateResult

/**
 * 自动重连失败弹窗
 * 提示用户前往设备连接页面手动连接设备
 */
@Composable
fun AutoReconnectFailedDialog(
    onDismiss: () -> Unit,
    onNavigateToConnect: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("连接失败")
        },
        text = {
            Text("自动重连失败，请前往设备连接页面手动连接设备")
        },
        confirmButton = {
            TextButton(onClick = onNavigateToConnect) {
                Text("前往连接")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("稍后")
            }
        }
    )
}

/**
 * 更新对话框
 * 显示新版本信息并提供下载选项
 */
@Composable
fun UpdateDialog(
    updateInfo: UpdateResult.NewVersionAvailable,
    onDismiss: () -> Unit,
    onOpenGitHub: () -> Unit,
    onOpenWebsite: () -> Unit
) {
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
                    text = "发现新版本",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "当前版本：v${updateInfo.currentVersion}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "最新版本：v${updateInfo.latestVersion}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (updateInfo.releaseNotes.isNotBlank()) {
                        Text(
                            text = "更新内容：",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        Text(
                            text = updateInfo.releaseNotes.take(200) + if (updateInfo.releaseNotes.length > 200) "..." else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "\n提示：建议使用官网下载链接，访问更稳定。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onOpenWebsite,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("官网下载（推荐）")
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(
                            onClick = onOpenGitHub,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("GitHub")
                        }
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("稍后")
                        }
                    }
                }
            }
        }
    }
}

/**
 * 自动更新提醒对话框
 * 用于应用启动时自动检查到新版本后的提醒
 */
@Composable
fun AutoUpdateReminderDialog(
    updateInfo: UpdateResult.NewVersionAvailable,
    onDismiss: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "发现新版本",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "当前版本：v${updateInfo.currentVersion}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "最新版本：v${updateInfo.latestVersion}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "有新版本可用，请前往应用设置页面更新。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = onNavigateToSettings) {
                Text("前往设置")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("稍后提醒")
            }
        }
    )
}

