import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 自定义尺寸的 IconButton
 * @param size 按钮的宽高 (例如 56.dp)
 * @param containerColor 背景颜色
 * @param contentColor 图标颜色
 * @param shape 按钮形状 (默认圆角或小圆)
 */
@Composable
fun CustomIconButton(
    onClick: () -> Unit,
    size: Dp = 48.dp, // 默认保持标准大小，但可修改
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    shape: Shape = MaterialTheme.shapes.small,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color = if (enabled) containerColor else Color.Gray.copy(alpha = 0.3f))
            .clickable(
                onClick = onClick,
                enabled = enabled,
                role = androidx.compose.ui.semantics.Role.Button,
                interactionSource = interactionSource,
                indication = rememberRipple(
                    bounded = true,
                    radius = size / 2
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}