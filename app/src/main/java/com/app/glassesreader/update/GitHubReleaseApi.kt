package com.app.glassesreader.update

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * GitHub Release API 接口
 * 用于获取仓库的最新 Release 信息
 */
interface GitHubReleaseApi {
    /**
     * 获取指定仓库的最新 Release
     * @param owner 仓库所有者（用户名或组织名）
     * @param repo 仓库名称
     * @return Release 信息
     */
    @GET("repos/{owner}/{repo}/releases/latest")
    suspend fun getLatestRelease(
        @Path("owner") owner: String,
        @Path("repo") repo: String
    ): Response<Release>
}

/**
 * GitHub Release 数据模型
 */
data class Release(
    val tag_name: String,           // 版本标签，如 "v1.1.3"
    val name: String,                // Release 名称
    val body: String,                // Release 说明
    val published_at: String,       // 发布时间
    val html_url: String,            // Release 页面 URL
    val assets: List<Asset>         // 附件列表（APK 文件等）
)

/**
 * Release 附件数据模型
 */
data class Asset(
    val name: String,                // 文件名
    val browser_download_url: String // 下载链接
)



