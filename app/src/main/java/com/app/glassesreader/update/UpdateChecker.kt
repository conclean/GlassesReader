package com.app.glassesreader.update

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * 更新检查器
 * 用于检查 GitHub Release 是否有新版本
 */
object UpdateChecker {
    private const val LOG_TAG = "UpdateChecker"
    private const val GITHUB_API_BASE_URL = "https://api.github.com/"
    private const val GITHUB_OWNER = "conclean"
    private const val GITHUB_REPO = "GlassesReader"
    
    // 官网下载链接
    private const val WEBSITE_DOWNLOAD_URL = "https://glassesreader.conclean.top" 
    
    @Volatile
    private var apiInstance: GitHubReleaseApi? = null
    
    private val api: GitHubReleaseApi
        get() {
            return apiInstance ?: synchronized(this) {
                apiInstance ?: Retrofit.Builder()
                    .baseUrl(GITHUB_API_BASE_URL)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                    .create(GitHubReleaseApi::class.java)
                    .also { apiInstance = it }
            }
        }
    
    /**
     * 获取当前应用版本号
     */
    fun getCurrentVersion(context: Context): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_META_DATA
            )
            packageInfo.versionName ?: "未知"
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to get current version", e)
            "未知"
        }
    }
    
    /**
     * 比较版本号
     * @param currentVersion 当前版本，如 "1.1.3"
     * @param latestVersion 最新版本，如 "v1.1.4" 或 "1.1.4"
     * @return true 如果有新版本
     */
    fun isNewVersionAvailable(currentVersion: String, latestVersion: String): Boolean {
        // 移除版本号前缀 "v"（如果存在）
        val cleanCurrent = currentVersion.trim().removePrefix("v").removePrefix("V")
        val cleanLatest = latestVersion.trim().removePrefix("v").removePrefix("V")
        
        return try {
            compareVersions(cleanCurrent, cleanLatest) < 0
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to compare versions: $cleanCurrent vs $cleanLatest", e)
            false
        }
    }
    
    /**
     * 比较两个版本号
     * @return 负数表示 version1 < version2，0 表示相等，正数表示 version1 > version2
     */
    private fun compareVersions(version1: String, version2: String): Int {
        val parts1 = version1.split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 = version2.split(".").map { it.toIntOrNull() ?: 0 }
        
        val maxLength = maxOf(parts1.size, parts2.size)
        
        for (i in 0 until maxLength) {
            val part1 = parts1.getOrElse(i) { 0 }
            val part2 = parts2.getOrElse(i) { 0 }
            
            when {
                part1 < part2 -> return -1
                part1 > part2 -> return 1
            }
        }
        
        return 0
    }
    
    /**
     * 检查更新
     * @return UpdateResult 更新检查结果
     */
    suspend fun checkForUpdate(context: Context): UpdateResult {
        return try {
            val response = api.getLatestRelease(GITHUB_OWNER, GITHUB_REPO)
            
            if (response.isSuccessful && response.body() != null) {
                val release = response.body()!!
                val currentVersion = getCurrentVersion(context)
                val latestVersion = release.tag_name.removePrefix("v").removePrefix("V")
                
                Log.d(LOG_TAG, "Current version: $currentVersion, Latest version: $latestVersion")
                
                if (isNewVersionAvailable(currentVersion, latestVersion)) {
                    UpdateResult.NewVersionAvailable(
                        currentVersion = currentVersion,
                        latestVersion = latestVersion,
                        releaseName = release.name,
                        releaseNotes = release.body ?: "",
                        releaseUrl = release.html_url,
                        downloadUrl = release.assets.firstOrNull()?.browser_download_url
                    )
                } else {
                    UpdateResult.UpToDate(currentVersion)
                }
            } else {
                Log.e(LOG_TAG, "Failed to fetch release: ${response.code()} ${response.message()}")
                UpdateResult.Error("检查更新失败：${response.code()}")
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error checking for update", e)
            UpdateResult.Error("网络错误：${e.message}")
        }
    }
    
    /**
     * 获取 GitHub Release 页面 URL
     */
    fun getGitHubReleaseUrl(): String {
        return "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
    }
    
    /**
     * 获取官网下载链接（预留）
     */
    fun getWebsiteDownloadUrl(): String {
        return WEBSITE_DOWNLOAD_URL
    }
}

/**
 * 更新检查结果
 */
sealed class UpdateResult {
    /**
     * 有新版本可用
     */
    data class NewVersionAvailable(
        val currentVersion: String,
        val latestVersion: String,
        val releaseName: String,
        val releaseNotes: String,
        val releaseUrl: String,
        val downloadUrl: String?
    ) : UpdateResult()
    
    /**
     * 已是最新版本
     */
    data class UpToDate(val currentVersion: String) : UpdateResult()
    
    /**
     * 检查失败
     */
    data class Error(val message: String) : UpdateResult()
}


