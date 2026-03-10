package com.app.glassesreader.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.app.glassesreader.R

/**
 * 服务控制浮动按钮
 */
@Composable
fun ServiceFab(
    canToggle: Boolean,
    readerEnabled: Boolean,
    overlayGranted: Boolean,
    onToggle: () -> Unit,
    onShowMessage: (String) -> Unit,
    disabledMessage: String
) {
    val containerColor = when {
        !overlayGranted -> MaterialTheme.colorScheme.surfaceVariant
        readerEnabled -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = when {
        !overlayGranted -> MaterialTheme.colorScheme.onSurfaceVariant
        readerEnabled -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }

    LargeFloatingActionButton(
        onClick = {
            if (canToggle) {
                onToggle()
            } else {
                val message = when {
                    disabledMessage.isNotBlank() -> disabledMessage
                    !overlayGranted -> "悬浮窗权限未授权"
                    else -> "还有前置步骤未完成"
                }
                onShowMessage(message)
            }
        },
        containerColor = containerColor,
        contentColor = contentColor,
        shape = CircleShape
    ) {
        Icon(
            painter = painterResource(
                id = when {
                    readerEnabled -> R.drawable.ic_pause
                    else -> R.drawable.ic_play_arrow
                }
            ),
            contentDescription = when {
                readerEnabled -> "暂停读屏服务"
                else -> "启动读屏服务"
            },
            tint = contentColor,
            modifier = Modifier.size(36.dp)
        )
    }
}

/**
 * 状态列表项组件
 */
@Composable
fun StatusListItem(
    title: String,
    isCompleted: Boolean,
    description: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    actionEnabled: Boolean = true
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            painter = painterResource(
                id = if (isCompleted) R.drawable.ic_check_circle else R.drawable.ic_error_outline
            ),
            contentDescription = null,
            tint = if (isCompleted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(24.dp)
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCompleted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Medium
            )
            if (!description.isNullOrEmpty()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (actionLabel != null) {
                TextButton(
                    onClick = onAction ?: {},
                    enabled = actionEnabled && onAction != null,
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(text = actionLabel)
                }
            }
        }
    }
}

/**
 * 简化的权限列表项组件
 * 一行文字 + 右侧箭头/对勾
 */
@Composable
fun SimplePermissionItem(
    title: String,
    isCompleted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // 先设置点击，再通过 padding 控制行内文字/图标的内边距和高度
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isCompleted) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                // 未完成时使用错误色，提升警示感
                MaterialTheme.colorScheme.error
            },
            fontWeight = if (isCompleted) FontWeight.Normal else FontWeight.Medium
        )
        if (isCompleted) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "已完成",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        } else {
            Icon(
                imageVector = Icons.Default.ArrowForward,
                contentDescription = "前往设置",
                // 未完成时使用错误色
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
