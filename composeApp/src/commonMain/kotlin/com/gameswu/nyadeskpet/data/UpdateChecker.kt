package com.gameswu.nyadeskpet.data

import com.gameswu.nyadeskpet.getAppVersion
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * GitHub Release 版本检查 — 对齐原项目 check-update IPC handler
 *
 * 从 GitHub API 获取最新 release 信息，与当前版本比较。
 * 支持解析用户自定义的 updateSource URL 来获取 owner/repo。
 */
class UpdateChecker {

    private val httpClient = HttpClient { expectSuccess = false }
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class GitHubRelease(
        val tag_name: String = "",
        val html_url: String = "",
        val body: String = "",
        val published_at: String = "",
    )

    data class UpdateResult(
        val hasUpdate: Boolean,
        val currentVersion: String,
        val latestVersion: String,
        val releaseUrl: String,
        val releaseNotes: String,
        val publishedAt: String,
        val error: String? = null,
    )

    /**
     * 检查更新 — 对齐原项目 check-update 逻辑
     *
     * @param updateSource GitHub 仓库 URL，如 "https://github.com/gameswu/NyaDeskPetAPP"
     */
    suspend fun checkForUpdate(updateSource: String): UpdateResult {
        val currentVersion = getAppVersion()

        try {
            // 解析 owner/repo
            val (owner, repo) = parseGitHubUrl(updateSource)
                ?: return UpdateResult(
                    hasUpdate = false,
                    currentVersion = currentVersion,
                    latestVersion = "",
                    releaseUrl = "",
                    releaseNotes = "",
                    publishedAt = "",
                    error = "无法解析 GitHub 仓库 URL: $updateSource"
                )

            val apiUrl = "https://api.github.com/repos/$owner/$repo/releases/latest"

            val response = httpClient.get(apiUrl) {
                header("Accept", "application/vnd.github.v3+json")
                header("User-Agent", "NyaDeskPet-KMP/${currentVersion}")
            }

            if (response.status.value != 200) {
                return UpdateResult(
                    hasUpdate = false,
                    currentVersion = currentVersion,
                    latestVersion = "",
                    releaseUrl = "",
                    releaseNotes = "",
                    publishedAt = "",
                    error = "GitHub API 请求失败: HTTP ${response.status.value}"
                )
            }

            val body = response.bodyAsText()
            val release = json.decodeFromString<GitHubRelease>(body)

            // 清理版本号前缀 v
            val latestVersion = release.tag_name.removePrefix("v").removePrefix("V")

            val hasUpdate = compareVersions(currentVersion, latestVersion) < 0

            return UpdateResult(
                hasUpdate = hasUpdate,
                currentVersion = currentVersion,
                latestVersion = latestVersion,
                releaseUrl = release.html_url,
                releaseNotes = release.body,
                publishedAt = release.published_at,
            )
        } catch (e: Exception) {
            return UpdateResult(
                hasUpdate = false,
                currentVersion = currentVersion,
                latestVersion = "",
                releaseUrl = "",
                releaseNotes = "",
                publishedAt = "",
                error = "检查更新失败: ${e.message}"
            )
        }
    }

    fun close() {
        httpClient.close()
    }

    companion object {
        /**
         * 解析 GitHub URL 获取 owner/repo
         * 支持格式: https://github.com/owner/repo 或 https://github.com/owner/repo.git
         */
        fun parseGitHubUrl(url: String): Pair<String, String>? {
            val cleaned = url.trimEnd('/').removeSuffix(".git")
            val regex = Regex("""github\.com/([^/]+)/([^/]+)""")
            val match = regex.find(cleaned) ?: return null
            return match.groupValues[1] to match.groupValues[2]
        }

        /**
         * 比较版本号: <0 表示 a 更旧, =0 表示相同, >0 表示 a 更新
         * 对齐原项目 compareVersions 逻辑
         */
        fun compareVersions(a: String, b: String): Int {
            val partsA = a.removePrefix("v").removePrefix("V").split(".").map { it.toIntOrNull() ?: 0 }
            val partsB = b.removePrefix("v").removePrefix("V").split(".").map { it.toIntOrNull() ?: 0 }
            val maxLen = maxOf(partsA.size, partsB.size)
            for (i in 0 until maxLen) {
                val pa = partsA.getOrElse(i) { 0 }
                val pb = partsB.getOrElse(i) { 0 }
                if (pa != pb) return pa.compareTo(pb)
            }
            return 0
        }
    }
}
