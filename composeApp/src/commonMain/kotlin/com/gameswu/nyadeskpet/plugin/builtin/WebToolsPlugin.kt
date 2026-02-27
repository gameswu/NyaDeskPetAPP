package com.gameswu.nyadeskpet.plugin.builtin

import com.gameswu.nyadeskpet.plugin.*
import com.gameswu.nyadeskpet.plugin.api.ToolDefinition
import com.gameswu.nyadeskpet.plugin.api.ToolProvider
import com.gameswu.nyadeskpet.plugin.api.ToolResult
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

/**
 * 网络工具插件 — 对齐原项目 agent-plugins/web-tools
 *
 * 提供两个 Function Calling 工具：
 * - fetch_url：获取指定 URL 的网页内容（提取正文文本）
 * - search_web：通过搜索引擎查询关键词，返回搜索结果列表
 */
class WebToolsPlugin : Plugin, ToolProvider {

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    }

    override val manifest = PluginManifest(
        id = "builtin.web-tools",
        name = "网络工具",
        version = "1.0.0",
        author = "NyaDeskPet",
        description = "提供网页获取和搜索功能",
        type = PluginType.BACKEND,
        capabilities = listOf(PluginCapability.TOOL),
        autoActivate = true,
    )
    override var enabled: Boolean = true

    override val providerId: String = "builtin.web-tools"
    override val providerName: String = "网络工具"

    // ==================== 配置 Schema ====================

    override val configSchema = PluginConfigSchema(
        fields = listOf(
            ConfigFieldDef(
                key = "searchEngine",
                type = ConfigFieldType.STRING,
                description = "搜索引擎。search_web 工具使用的搜索引擎。",
                default = JsonPrimitive("bing"),
                options = listOf("bing", "google", "duckduckgo"),
            ),
            ConfigFieldDef(
                key = "maxContentLength",
                type = ConfigFieldType.INT,
                description = "fetch_url 返回给 LLM 的最大字符数。过大会消耗大量 token。",
                default = JsonPrimitive(8000),
            ),
            ConfigFieldDef(
                key = "requestTimeout",
                type = ConfigFieldType.INT,
                description = "HTTP 请求超时时间（毫秒）。",
                default = JsonPrimitive(15000),
            ),
            ConfigFieldDef(
                key = "searchResultCount",
                type = ConfigFieldType.INT,
                description = "搜索结果返回条数。",
                default = JsonPrimitive(5),
            ),
        ),
    )

    // ==================== 状态 ====================

    private var ctx: PluginContext? = null
    private val httpClient = HttpClient { expectSuccess = false }

    private var searchEngine: String = "bing"
    private var maxContentLength: Int = 8000
    private var requestTimeout: Long = 15000L
    private var searchResultCount: Int = 5

    // ==================== 生命周期 ====================

    override fun onLoad(context: PluginContext) {
        ctx = context
        loadConfig(context.getConfig())
        context.logInfo("网络工具插件已初始化")
    }

    override fun onUnload() {
        httpClient.close()
        ctx = null
    }

    override fun onConfigChanged(config: Map<String, JsonElement>) {
        loadConfig(config)
    }

    private fun loadConfig(config: Map<String, JsonElement>) {
        config["searchEngine"]?.jsonPrimitive?.contentOrNull?.let { searchEngine = it }
        config["maxContentLength"]?.jsonPrimitive?.intOrNull?.let { maxContentLength = it }
        config["requestTimeout"]?.jsonPrimitive?.intOrNull?.let { requestTimeout = it.toLong() }
        config["searchResultCount"]?.jsonPrimitive?.intOrNull?.let { searchResultCount = it }
    }

    // ==================== 工具 ====================

    override fun getTools(): List<ToolDefinition> {
        return listOf(
            ToolDefinition(
                name = "fetch_url",
                description = "获取指定 URL 的网页内容。会自动提取页面正文文本，去除 HTML 标签、脚本和样式。适合获取文章、文档、API 响应等内容。",
                parameters = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    putJsonObject("properties") {
                        putJsonObject("url") {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("要获取的网页 URL（必须以 http:// 或 https:// 开头）"))
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("url")) }
                },
            ),
            ToolDefinition(
                name = "search_web",
                description = "使用搜索引擎搜索关键词，返回搜索结果列表（标题、链接、摘要）。当需要查找最新信息、回答事实性问题时使用。",
                parameters = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    putJsonObject("properties") {
                        putJsonObject("query") {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("搜索关键词"))
                        }
                        putJsonObject("count") {
                            put("type", JsonPrimitive("number"))
                            put("description", JsonPrimitive("返回结果数量（默认 5，最多 10）"))
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("query")) }
                },
            ),
        )
    }

    override suspend fun executeTool(name: String, arguments: JsonObject): ToolResult {
        return when (name) {
            "fetch_url" -> handleFetchUrl(arguments)
            "search_web" -> handleSearchWeb(arguments)
            else -> ToolResult(success = false, error = "Unknown tool: $name")
        }
    }

    // ==================== fetch_url ====================

    private suspend fun handleFetchUrl(arguments: JsonObject): ToolResult {
        val url = arguments["url"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(success = false, error = "缺少 url 参数")

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolResult(success = false, error = "URL 必须以 http:// 或 https:// 开头")
        }

        return try {
            ctx?.logInfo("fetch_url: $url")
            val response = withTimeout(requestTimeout) {
                httpClient.get(url) {
                    headers {
                        append(HttpHeaders.UserAgent, USER_AGENT)
                        append(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        append(HttpHeaders.AcceptLanguage, "zh-CN,zh;q=0.9,en;q=0.8")
                    }
                }
            }

            if (response.status != HttpStatusCode.OK) {
                return ToolResult(success = false, error = "HTTP ${response.status.value}")
            }

            val html = response.bodyAsText()
            val text = extractText(html)

            val truncated = if (text.length > maxContentLength) {
                text.take(maxContentLength) + "\n\n[内容已截断，共 ${text.length} 字符，仅显示前 $maxContentLength 字符]"
            } else text

            ToolResult(
                success = true,
                result = JsonPrimitive(truncated.ifBlank { "(页面无可提取的文本内容)" }),
            )
        } catch (e: Exception) {
            ToolResult(success = false, error = "获取 URL 失败: ${e.message}")
        }
    }

    // ==================== search_web ====================

    private suspend fun handleSearchWeb(arguments: JsonObject): ToolResult {
        val query = arguments["query"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(success = false, error = "缺少 query 参数")

        val count = (arguments["count"]?.jsonPrimitive?.intOrNull ?: searchResultCount).coerceIn(1, 10)

        return try {
            ctx?.logInfo("search_web: \"$query\" (engine: $searchEngine, count: $count)")
            val results = doSearch(query, count)

            if (results.isEmpty()) {
                return ToolResult(
                    success = true,
                    result = JsonPrimitive("搜索 \"$query\" 未找到结果"),
                )
            }

            val formatted = results.mapIndexed { i, r ->
                "${i + 1}. ${r.title}\n   链接: ${r.url}\n   摘要: ${r.snippet}"
            }.joinToString("\n\n")

            ToolResult(
                success = true,
                result = JsonPrimitive("搜索 \"$query\" 的结果:\n\n$formatted"),
            )
        } catch (e: Exception) {
            ToolResult(success = false, error = "搜索失败: ${e.message}")
        }
    }

    // ==================== 搜索引擎适配 ====================

    private data class SearchResult(val title: String, val url: String, val snippet: String)

    private suspend fun doSearch(query: String, count: Int): List<SearchResult> {
        return when (searchEngine) {
            "bing" -> searchBing(query, count)
            "google" -> searchGoogle(query, count)
            "duckduckgo" -> searchDuckDuckGo(query, count)
            else -> searchBing(query, count)
        }
    }

    private suspend fun searchBing(query: String, count: Int): List<SearchResult> {
        val url = "https://www.bing.com/search?q=${query.encodeURLParameter()}&count=$count"
        val html = fetchHtml(url)
        return parseBingResults(html, count)
    }

    private suspend fun searchGoogle(query: String, count: Int): List<SearchResult> {
        val url = "https://www.google.com/search?q=${query.encodeURLParameter()}&num=$count"
        val html = fetchHtml(url)
        return parseGoogleResults(html, count)
    }

    private suspend fun searchDuckDuckGo(query: String, count: Int): List<SearchResult> {
        val url = "https://html.duckduckgo.com/html/?q=${query.encodeURLParameter()}"
        val html = fetchHtml(url)
        return parseDuckDuckGoResults(html, count)
    }

    private suspend fun fetchHtml(url: String): String {
        val response = withTimeout(requestTimeout) {
            httpClient.get(url) {
                headers {
                    append(HttpHeaders.UserAgent, USER_AGENT)
                    append(HttpHeaders.Accept, "text/html,application/xhtml+xml")
                    append(HttpHeaders.AcceptLanguage, "zh-CN,zh;q=0.9,en;q=0.8")
                }
            }
        }
        return response.bodyAsText()
    }

    // ==================== 搜索结果解析 ====================

    private fun parseBingResults(html: String, count: Int): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        // 匹配 Bing 搜索结果 <li class="b_algo">
        val blockRegex = """<li class="b_algo">([\s\S]*?)</li>""".toRegex(RegexOption.IGNORE_CASE)
        for (match in blockRegex.findAll(html)) {
            if (results.size >= count) break
            val block = match.groupValues[1]
            val linkMatch = """<a[^>]+href="([^"]+)"[^>]*>([\s\S]*?)</a>""".toRegex(RegexOption.IGNORE_CASE).find(block)
            val snippetMatch = """<p[^>]*>([\s\S]*?)</p>""".toRegex(RegexOption.IGNORE_CASE).find(block)
            if (linkMatch != null) {
                results.add(SearchResult(
                    title = stripTags(linkMatch.groupValues[2]).trim(),
                    url = linkMatch.groupValues[1],
                    snippet = snippetMatch?.let { stripTags(it.groupValues[1]).trim() } ?: "",
                ))
            }
        }
        return results
    }

    private fun parseGoogleResults(html: String, count: Int): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        // 尝试匹配 Google 搜索结果
        val fallbackRegex = """<a href="/url\?q=([^&"]+)[^"]*"[^>]*>([\s\S]*?)</a>""".toRegex(RegexOption.IGNORE_CASE)
        for (match in fallbackRegex.findAll(html)) {
            if (results.size >= count) break
            val rawUrl = match.groupValues[1]
            val title = stripTags(match.groupValues[2]).trim()
            if (rawUrl.startsWith("http") && title.isNotBlank() && "google.com" !in rawUrl) {
                results.add(SearchResult(title = title, url = rawUrl, snippet = ""))
            }
        }
        return results
    }

    private fun parseDuckDuckGoResults(html: String, count: Int): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val linkRegex = """<a[^>]+href="([^"]+)"[^>]*class="result__a"[^>]*>([\s\S]*?)</a>""".toRegex(RegexOption.IGNORE_CASE)
        val snippetRegex = """<a[^>]+class="result__snippet"[^>]*>([\s\S]*?)</a>""".toRegex(RegexOption.IGNORE_CASE)
        
        val blockRegex = """<div class="result[^"]*"[^>]*>([\s\S]*?)</div>\s*</div>""".toRegex(RegexOption.IGNORE_CASE)
        for (match in blockRegex.findAll(html)) {
            if (results.size >= count) break
            val block = match.groupValues[1]
            val linkMatch = linkRegex.find(block) ?: continue
            val snippetMatch = snippetRegex.find(block)
            
            var url = linkMatch.groupValues[1]
            // DuckDuckGo 有时用重定向 URL
            val directMatch = """uddg=([^&]+)""".toRegex().find(url)
            if (directMatch != null) {
                url = directMatch.groupValues[1].decodeURLPart()
            }

            results.add(SearchResult(
                title = stripTags(linkMatch.groupValues[2]).trim(),
                url = url,
                snippet = snippetMatch?.let { stripTags(it.groupValues[1]).trim() } ?: "",
            ))
        }
        return results
    }

    // ==================== HTML 处理 ====================

    private fun extractText(html: String): String {
        if (html.isBlank()) return ""

        // 检查是否是 JSON 响应
        val trimmed = html.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                Json.parseToJsonElement(trimmed)
                return trimmed // 直接返回 JSON
            } catch (_: Exception) {}
        }

        var text = html
        // 移除 <script> 和 <style>
        text = text.replace("""<script[\s\S]*?</script>""".toRegex(RegexOption.IGNORE_CASE), "")
        text = text.replace("""<style[\s\S]*?</style>""".toRegex(RegexOption.IGNORE_CASE), "")
        // 移除 HTML 注释
        text = text.replace("""<!--[\s\S]*?-->""".toRegex(), "")
        // 移除 nav、header、footer
        text = text.replace("""<nav[\s\S]*?</nav>""".toRegex(RegexOption.IGNORE_CASE), "")
        text = text.replace("""<header[\s\S]*?</header>""".toRegex(RegexOption.IGNORE_CASE), "")
        text = text.replace("""<footer[\s\S]*?</footer>""".toRegex(RegexOption.IGNORE_CASE), "")
        // 保留段落分隔
        text = text.replace("""</?(p|div|br|h[1-6]|li|tr)[^>]*>""".toRegex(RegexOption.IGNORE_CASE), "\n")
        // 移除所有剩余标签
        text = text.replace("""<[^>]+>""".toRegex(), "")
        // 解码 HTML 实体
        text = decodeEntities(text)
        // 压缩空白
        text = text.replace("""[ \t]+""".toRegex(), " ")
        text = text.replace("""\n\s*\n+""".toRegex(), "\n\n")
        return text.trim()
    }

    private fun stripTags(html: String): String {
        if (html.isBlank()) return ""
        return decodeEntities(html.replace("""<[^>]+>""".toRegex(), ""))
    }

    private fun decodeEntities(text: String): String {
        val entities = mapOf(
            "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"",
            "&#39;" to "'", "&apos;" to "'", "&nbsp;" to " ", "&#x27;" to "'",
            "&#x2F;" to "/", "&mdash;" to "—", "&ndash;" to "–",
            "&hellip;" to "…", "&copy;" to "©", "&reg;" to "®",
        )
        var result = text
        for ((entity, char) in entities) {
            result = result.replace(entity, char)
        }
        // 数字实体
        result = result.replace("""&#(\d+);""".toRegex()) { m ->
            m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value
        }
        result = result.replace("""&#x([0-9a-fA-F]+);""".toRegex()) { m ->
            m.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: m.value
        }
        return result
    }
}
