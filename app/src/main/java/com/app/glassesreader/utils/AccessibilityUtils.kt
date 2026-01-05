package com.app.glassesreader.utils

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityManager

/**
 * 无障碍服务工具类
 */
object AccessibilityUtils {
    private const val LOG_TAG = "AccessibilityUtils"
    
    /**
     * 检查无障碍服务是否已启用
     * @param context 上下文
     * @param serviceClass 服务类
     * @return true 如果服务已启用
     */
    fun isAccessibilityServiceEnabled(
        context: Context,
        serviceClass: Class<*>
    ): Boolean {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false
        val enabledServices =
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val componentName = ComponentName(context, serviceClass)
        val targetFullId = componentName.flattenToString()
        val relativeName = serviceClass.name.removePrefix("${context.packageName}.")
        val targetShortId = "${context.packageName}/.$relativeName"
        val activeIds = enabledServices.map { it.id }
        Log.d(LOG_TAG, "Enabled accessibility IDs: $activeIds")
        val matched = enabledServices.any {
            val id = it.id
            it.resolveInfo.serviceInfo.packageName.isNotEmpty() &&
                (id == targetFullId || id == targetShortId)
        }
        Log.d(LOG_TAG, "Accessibility match for $targetFullId/$targetShortId: $matched")
        return matched
    }
}

