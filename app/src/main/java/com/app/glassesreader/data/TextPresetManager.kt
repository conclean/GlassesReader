package com.app.glassesreader.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.app.glassesreader.sdk.CxrCustomViewManager
import com.rokid.cxr.client.extend.CxrApi
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 文字设置预设管理器
 * 负责预设的存储、加载、切换和管理
 */
class TextPresetManager private constructor(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val context: Context = context.applicationContext
    
    companion object {
        private const val TAG = "TextPresetManager"
        private const val PREFS_NAME = "gr_text_presets"
        private const val KEY_PRESETS = "presets_json"
        private const val KEY_CURRENT_PRESET_ID = "current_preset_id"
        private const val MAX_NAME_LENGTH = 10
        
        @Volatile
        private var INSTANCE: TextPresetManager? = null
        
        fun getInstance(context: Context): TextPresetManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TextPresetManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    /**
     * 获取所有预设列表（按使用频率排序，最近使用的在前）
     */
    fun getAllPresets(): List<TextPreset> {
        val presetsJson = prefs.getString(KEY_PRESETS, null) ?: return emptyList()
        return try {
            val jsonArray = JSONArray(presetsJson)
            val presets = mutableListOf<TextPreset>()
            for (i in 0 until jsonArray.length()) {
                val jsonObject = jsonArray.getJSONObject(i)
                presets.add(jsonObjectToPreset(jsonObject))
            }
            // 按最后使用时间排序，最近使用的在前
            presets.sortedByDescending { it.lastUsedTime }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load presets", e)
            emptyList()
        }
    }
    
    /**
     * 获取当前使用的预设ID
     */
    fun getCurrentPresetId(): String? {
        return prefs.getString(KEY_CURRENT_PRESET_ID, null)
    }
    
    /**
     * 获取当前使用的预设
     */
    fun getCurrentPreset(): TextPreset? {
        val currentId = getCurrentPresetId() ?: return null
        return getAllPresets().find { it.id == currentId }
    }
    
    /**
     * 根据ID获取预设
     */
    fun getPresetById(id: String): TextPreset? {
        return getAllPresets().find { it.id == id }
    }
    
    /**
     * 切换到指定预设
     */
    fun switchToPreset(presetId: String): Boolean {
        val preset = getPresetById(presetId) ?: return false
        
        // 更新最后使用时间
        val updatedPreset = preset.copy(lastUsedTime = System.currentTimeMillis())
        updatePreset(updatedPreset)
        
        // 保存当前预设ID
        prefs.edit().putString(KEY_CURRENT_PRESET_ID, presetId).apply()
        
        // 应用预设到系统
        applyPreset(updatedPreset)
        
        Log.d(TAG, "Switched to preset: ${preset.name}")
        return true
    }
    
    /**
     * 创建新预设
     * @param name 预设名称
     * @param brightness 当前亮度值（可选，如果不提供则使用默认值8）
     */
    fun createPreset(name: String, brightness: Int = 8): TextPreset? {
        // 验证名称
        if (name.isBlank() || name.length > MAX_NAME_LENGTH) {
            Log.w(TAG, "Invalid preset name: $name")
            return null
        }
        
        // 检查重名
        if (getAllPresets().any { it.name == name }) {
            Log.w(TAG, "Preset name already exists: $name")
            return null
        }
        
        // 基于当前设置创建新预设
        val currentBrightness = brightness
        val currentTextSize = CxrCustomViewManager.getTextSize()
        val currentOptions = CxrCustomViewManager.getTextProcessingOptions()
        
        val newPreset = TextPreset(
            id = UUID.randomUUID().toString(),
            name = name,
            brightness = currentBrightness,
            textSize = currentTextSize,
            removeEmptyLines = currentOptions.removeEmptyLines,
            removeLineBreaks = currentOptions.removeLineBreaks,
            removeFirstLine = currentOptions.removeFirstLine,
            removeFirstLineCount = currentOptions.removeFirstLineCount,
            removeLastLine = currentOptions.removeLastLine,
            removeLastLineCount = currentOptions.removeLastLineCount
        )
        
        savePreset(newPreset)
        
        // 切换到新预设
        switchToPreset(newPreset.id)
        
        Log.d(TAG, "Created new preset: ${newPreset.name}")
        return newPreset
    }
    
    /**
     * 更新预设（重命名或修改设置）
     */
    fun updatePreset(preset: TextPreset): Boolean {
        // 验证名称
        if (preset.name.isBlank() || preset.name.length > MAX_NAME_LENGTH) {
            Log.w(TAG, "Invalid preset name: ${preset.name}")
            return false
        }
        
        val allPresets = getAllPresets()
        
        // 检查重名（排除自己）
        if (allPresets.any { it.name == preset.name && it.id != preset.id }) {
            Log.w(TAG, "Preset name already exists: ${preset.name}")
            return false
        }
        
        val updatedPresets = allPresets.map { if (it.id == preset.id) preset else it }
        saveAllPresets(updatedPresets)
        
        // 如果是当前预设，应用更新
        if (getCurrentPresetId() == preset.id) {
            applyPreset(preset)
        }
        
        Log.d(TAG, "Updated preset: ${preset.name}")
        return true
    }
    
    /**
     * 删除预设
     */
    fun deletePreset(presetId: String): Boolean {
        val allPresets = getAllPresets()
        
        // 至少保留一个预设
        if (allPresets.size <= 1) {
            Log.w(TAG, "Cannot delete last preset")
            return false
        }
        
        val presetToDelete = allPresets.find { it.id == presetId } ?: return false
        val updatedPresets = allPresets.filter { it.id != presetId }
        saveAllPresets(updatedPresets)
        
        // 如果删除的是当前预设，切换到第一个可用预设
        if (getCurrentPresetId() == presetId) {
            val firstPreset = updatedPresets.firstOrNull()
            if (firstPreset != null) {
                switchToPreset(firstPreset.id)
            }
        }
        
        Log.d(TAG, "Deleted preset: ${presetToDelete.name}")
        return true
    }
    
    /**
     * 保存当前设置到当前预设（实时保存）
     * @param brightness 当前亮度值（需要从外部传入）
     */
    fun saveCurrentSettingsToCurrentPreset(brightness: Int? = null) {
        val currentPresetId = getCurrentPresetId() ?: return
        val currentPreset = getPresetById(currentPresetId) ?: return
        
        val currentBrightness = brightness ?: currentPreset.brightness
        val currentTextSize = CxrCustomViewManager.getTextSize()
        val currentOptions = CxrCustomViewManager.getTextProcessingOptions()
        
        val updatedPreset = currentPreset.copy(
            brightness = currentBrightness,
            textSize = currentTextSize,
            removeEmptyLines = currentOptions.removeEmptyLines,
            removeLineBreaks = currentOptions.removeLineBreaks,
            removeFirstLine = currentOptions.removeFirstLine,
            removeFirstLineCount = currentOptions.removeFirstLineCount,
            removeLastLine = currentOptions.removeLastLine,
            removeLastLineCount = currentOptions.removeLastLineCount
        )
        
        updatePreset(updatedPreset)
    }
    
    /**
     * 应用预设到系统（更新CxrCustomViewManager和亮度）
     */
    private fun applyPreset(preset: TextPreset) {
        // 应用文本处理选项
        CxrCustomViewManager.setTextProcessingOptions(
            removeEmptyLines = preset.removeEmptyLines,
            removeLineBreaks = preset.removeLineBreaks,
            removeFirstLine = preset.removeFirstLine,
            removeLastLine = preset.removeLastLine,
            removeFirstLineCount = preset.removeFirstLineCount,
            removeLastLineCount = preset.removeLastLineCount
        )
        
        // 应用字体大小
        CxrCustomViewManager.setTextSize(preset.textSize)
        
        // 应用亮度（如果眼镜已连接）
        try {
            if (CxrApi.getInstance().isBluetoothConnected()) {
                CxrApi.getInstance().setGlassBrightness(preset.brightness)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set brightness", e)
        }
        
        Log.d(TAG, "Applied preset: ${preset.name}")
    }
    
    /**
     * 初始化：确保至少有一个默认预设
     */
    fun initializeIfNeeded() {
        val presets = getAllPresets()
        if (presets.isEmpty()) {
            // 创建默认预设
            val defaultPreset = TextPreset.createDefault()
            savePreset(defaultPreset)
            prefs.edit().putString(KEY_CURRENT_PRESET_ID, defaultPreset.id).apply()
            Log.d(TAG, "Created default preset")
        } else {
            // 确保有当前预设
            val currentId = getCurrentPresetId()
            if (currentId == null || getPresetById(currentId) == null) {
                // 如果没有当前预设，使用第一个
                val firstPreset = presets.first()
                prefs.edit().putString(KEY_CURRENT_PRESET_ID, firstPreset.id).apply()
                applyPreset(firstPreset)
            } else {
                // 应用当前预设
                val currentPreset = getPresetById(currentId)
                if (currentPreset != null) {
                    applyPreset(currentPreset)
                }
            }
        }
    }
    
    /**
     * 保存单个预设
     */
    private fun savePreset(preset: TextPreset) {
        val allPresets = getAllPresets().toMutableList()
        val existingIndex = allPresets.indexOfFirst { it.id == preset.id }
        if (existingIndex >= 0) {
            allPresets[existingIndex] = preset
        } else {
            allPresets.add(preset)
        }
        saveAllPresets(allPresets)
    }
    
    /**
     * 保存所有预设
     */
    private fun saveAllPresets(presets: List<TextPreset>) {
        try {
            val jsonArray = JSONArray()
            presets.forEach { preset ->
                jsonArray.put(presetToJsonObject(preset))
            }
            prefs.edit().putString(KEY_PRESETS, jsonArray.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save presets", e)
        }
    }
    
    /**
     * 将预设转换为JSON对象
     */
    private fun presetToJsonObject(preset: TextPreset): JSONObject {
        return JSONObject().apply {
            put("id", preset.id)
            put("name", preset.name)
            put("brightness", preset.brightness)
            put("textSize", preset.textSize.toDouble())
            put("removeEmptyLines", preset.removeEmptyLines)
            put("removeLineBreaks", preset.removeLineBreaks)
            put("removeFirstLine", preset.removeFirstLine)
            put("removeFirstLineCount", preset.removeFirstLineCount)
            put("removeLastLine", preset.removeLastLine)
            put("removeLastLineCount", preset.removeLastLineCount)
            put("lastUsedTime", preset.lastUsedTime)
        }
    }
    
    /**
     * 将JSON对象转换为预设
     */
    private fun jsonObjectToPreset(jsonObject: JSONObject): TextPreset {
        return TextPreset(
            id = jsonObject.getString("id"),
            name = jsonObject.getString("name"),
            brightness = jsonObject.getInt("brightness"),
            textSize = jsonObject.getDouble("textSize").toFloat(),
            removeEmptyLines = jsonObject.getBoolean("removeEmptyLines"),
            removeLineBreaks = jsonObject.getBoolean("removeLineBreaks"),
            removeFirstLine = jsonObject.getBoolean("removeFirstLine"),
            removeFirstLineCount = jsonObject.getInt("removeFirstLineCount"),
            removeLastLine = jsonObject.getBoolean("removeLastLine"),
            removeLastLineCount = jsonObject.getInt("removeLastLineCount"),
            lastUsedTime = jsonObject.optLong("lastUsedTime", System.currentTimeMillis())
        )
    }
}
