package com.gameswu.nyadeskpet.plugin

import com.gameswu.nyadeskpet.data.PluginConfigStorage
import com.gameswu.nyadeskpet.plugin.api.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*

/**
 * 插件管理器 — 负责 Plugin / Tool / Panel / Widget / Command 的注册与查询
 *
 * 对齐原项目 AgentPluginManager：
 * - 管理插件生命周期（注册→激活→停用→卸载）
 * - 命令来源追踪（知道命令由哪个插件注册）
 * - 插件配置持久化（对齐原项目 config.json 持久化方式）
 */
class PluginManager(private val configStorage: PluginConfigStorage) {

    private val _plugins = MutableStateFlow<Map<String, Plugin>>(emptyMap())
    val plugins: StateFlow<Map<String, Plugin>> = _plugins.asStateFlow()

    private val _toolProviders = MutableStateFlow<Map<String, ToolProvider>>(emptyMap())
    val toolProviders: StateFlow<Map<String, ToolProvider>> = _toolProviders.asStateFlow()

    private val _panelPlugins = MutableStateFlow<Map<String, PanelPlugin>>(emptyMap())
    val panelPlugins: StateFlow<Map<String, PanelPlugin>> = _panelPlugins.asStateFlow()

    // ---------- 工具级别启用/禁用 ----------
    private val _toolEnabledOverrides = mutableMapOf<String, Boolean>()
    private val _toolEnabledVersion = MutableStateFlow(0) // 用于触发 UI 重组

    // 命令系统 — 追踪来源插件
    private val _commandHandlers = mutableMapOf<String, suspend (String) -> String>()
    private val _commandDescriptions = mutableMapOf<String, String>()
    private val _commandSources = mutableMapOf<String, String>() // commandName → pluginId
    private val _commandEnabled = mutableMapOf<String, Boolean>()

    // 插件配置 — 启动时从 PluginConfigStorage 加载，变更时自动持久化
    private val _pluginConfigs = mutableMapOf<String, MutableMap<String, JsonElement>>()
    private val json = Json { ignoreUnknownKeys = true }

    init {
        // 对齐原项目：启动时从持久化存储加载所有插件配置
        loadPersistedConfigs()
    }

    /** 上下文工厂 — 每个插件获取各自独立的 PluginContext（pluginId 已绑定） */
    private var contextFactory: ((pluginId: String) -> PluginContext)? = null

    fun initialize(contextFactory: (pluginId: String) -> PluginContext) {
        this.contextFactory = contextFactory
    }

    // ---------- 插件生命周期 ----------

    fun registerPlugin(plugin: Plugin) {
        val id = plugin.manifest.id
        _plugins.value = _plugins.value + (id to plugin)

        if (plugin is ToolProvider) {
            _toolProviders.value = _toolProviders.value + (plugin.providerId to plugin)
        }
        if (plugin is PanelPlugin) {
            _panelPlugins.value = _panelPlugins.value + (plugin.panelId to plugin)
        }

        contextFactory?.invoke(id)?.let { ctx -> plugin.onLoad(ctx) }
    }

    fun unregisterPlugin(id: String) {
        val plugin = _plugins.value[id] ?: return
        plugin.onUnload()

        // 移除该插件注册的所有命令
        _commandSources.entries.removeAll { it.value == id }

        _plugins.value = _plugins.value - id

        if (plugin is ToolProvider) {
            _toolProviders.value = _toolProviders.value - plugin.providerId
        }
        if (plugin is PanelPlugin) {
            _panelPlugins.value = _panelPlugins.value - plugin.panelId
        }
    }

    fun setPluginEnabled(id: String, enabled: Boolean) {
        _plugins.value[id]?.enabled = enabled
        // 通知 StateFlow 更新（替换引用）
        _plugins.value = _plugins.value.toMap()
    }

    /** 重载插件 — 停用后重新激活 */
    fun reloadPlugin(id: String) {
        val plugin = _plugins.value[id] ?: return
        plugin.onUnload()
        contextFactory?.invoke(id)?.let { ctx -> plugin.onLoad(ctx) }
    }

    // ---------- 外部 ToolProvider 注册（MCP 等）----------

    /**
     * 注册外部 ToolProvider（非 Plugin 子类，如 McpToolProvider）。
     * 使其工具加入 getAllTools() → getToolSchemas() → LLM 工具列表。
     */
    fun registerToolProvider(provider: ToolProvider) {
        _toolProviders.value = _toolProviders.value + (provider.providerId to provider)
    }

    /**
     * 注销外部 ToolProvider（MCP 服务器断连时调用）。
     */
    fun unregisterToolProvider(providerId: String) {
        _toolProviders.value = _toolProviders.value - providerId
    }

    // ---------- 工具查询 ----------

    /** 工具启用/禁用变更版本号（UI 通过 collectAsState 观察此值来刷新） */
    val toolEnabledVersion: StateFlow<Int> = _toolEnabledVersion.asStateFlow()

    /** 设置单个工具的启用状态 */
    fun setToolEnabled(toolName: String, enabled: Boolean) {
        _toolEnabledOverrides[toolName] = enabled
        _toolEnabledVersion.value++
    }

    /** 查询单个工具是否被启用 */
    fun isToolEnabled(toolName: String): Boolean {
        return _toolEnabledOverrides[toolName] != false
    }

    fun getAllTools(): List<ToolDefinition> {
        return _toolProviders.value.values
            .filter { (it as? Plugin)?.enabled != false }
            .flatMap { it.getTools() }
            .filter { _toolEnabledOverrides[it.name] != false }
    }

    /** 获取所有工具（含来源信息），不过滤禁用工具，供 UI 展示 */
    fun getAllToolsWithSource(): List<Pair<ToolDefinition, String>> {
        return _toolProviders.value.entries
            .filter { (it.value as? Plugin)?.enabled != false }
            .flatMap { (_, provider) ->
                val sourceName = (provider as? Plugin)?.manifest?.name ?: provider.providerName
                provider.getTools().map { it to sourceName }
            }
    }

    suspend fun executeTool(name: String, arguments: kotlinx.serialization.json.JsonObject): ToolResult {
        // 检查工具级别是否禁用
        if (_toolEnabledOverrides[name] == false) {
            return ToolResult(success = false, error = "Tool disabled: $name")
        }
        for (provider in _toolProviders.value.values) {
            // 与 getAllTools() 使用相同的 enabled 过滤逻辑
            if ((provider as? Plugin)?.enabled == false) continue
            val tool = provider.getTools().find { it.name == name }
            if (tool != null) {
                return provider.executeTool(name, arguments)
            }
        }
        return ToolResult(success = false, error = "Tool not found: $name")
    }

    // ---------- 命令系统 ----------

    fun registerCommand(
        name: String,
        description: String = "",
        handler: suspend (String) -> String,
        source: String = "",
    ) {
        _commandHandlers[name] = handler
        _commandDescriptions[name] = description
        _commandSources[name] = source
        _commandEnabled[name] = true
    }

    fun unregisterCommand(name: String) {
        _commandHandlers.remove(name)
        _commandDescriptions.remove(name)
        _commandSources.remove(name)
        _commandEnabled.remove(name)
    }

    fun getCommandHandler(name: String): (suspend (String) -> String)? {
        if (_commandEnabled[name] == false) return null
        return _commandHandlers[name]
    }

    fun setCommandEnabled(name: String, enabled: Boolean) {
        _commandEnabled[name] = enabled
    }

    fun getRegisteredCommands(): Set<String> {
        return _commandHandlers.keys.toSet()
    }

    /** 获取所有命令定义 (name, description, source, enabled) */
    data class CommandInfo(
        val name: String,
        val description: String,
        val source: String,
        val enabled: Boolean,
    )

    fun getCommandDefinitions(): List<CommandInfo> {
        return _commandHandlers.keys.map { name ->
            CommandInfo(
                name = name,
                description = _commandDescriptions[name] ?: "",
                source = _commandSources[name] ?: "",
                enabled = _commandEnabled[name] != false,
            )
        }
    }

    // ---------- 插件配置 ----------

    fun getPluginConfig(pluginId: String): Map<String, JsonElement> {
        return _pluginConfigs[pluginId] ?: emptyMap()
    }

    /**
     * 保存插件配置 — 对齐原项目 savePluginConfig
     * 同步更新内存 + 持久化到存储
     */
    fun savePluginConfig(pluginId: String, config: Map<String, JsonElement>) {
        _pluginConfigs[pluginId] = config.toMutableMap()
        persistConfigs()
        // 通知插件配置变更
        _plugins.value[pluginId]?.onConfigChanged(config)
    }

    /**
     * 清除插件持久化数据 — 对齐原项目 agent:clear-plugin-data
     *
     * 原项目行为：清除数据目录，但保留 config.json。
     * KMP 版本：清除该插件的所有配置并持久化清空状态，通知插件重新初始化。
     */
    fun clearPluginData(pluginId: String) {
        _pluginConfigs.remove(pluginId)
        persistConfigs()
        // 通知插件配置已被清空
        _plugins.value[pluginId]?.onConfigChanged(emptyMap())
    }

    /** 清除所有插件配置（用于设置页"恢复默认"等场景） */
    fun clearAllPluginData() {
        _pluginConfigs.clear()
        configStorage.clearAll()
        // 通知所有插件
        _plugins.value.values.forEach { it.onConfigChanged(emptyMap()) }
    }

    /**
     * 获取指定插件是否有持久化数据
     */
    fun hasPluginData(pluginId: String): Boolean {
        return _pluginConfigs.containsKey(pluginId) && _pluginConfigs[pluginId]?.isNotEmpty() == true
    }

    // ---------- 持久化 helpers ----------

    /**
     * 从持久化存储加载所有插件配置 — 对齐原项目启动时读取 config.json
     */
    private fun loadPersistedConfigs() {
        val raw = configStorage.loadAll() ?: return
        try {
            val root = json.parseToJsonElement(raw).jsonObject
            for ((pluginId, configElement) in root) {
                val configObj = configElement.jsonObject
                _pluginConfigs[pluginId] = configObj.toMutableMap()
            }
        } catch (_: Exception) {
            // JSON 格式损坏，忽略并使用默认空配置
        }
    }

    /**
     * 将所有插件配置持久化到存储 — 对齐原项目 writeFileSync config.json
     */
    private fun persistConfigs() {
        val root = buildJsonObject {
            for ((pluginId, config) in _pluginConfigs) {
                put(pluginId, buildJsonObject {
                    for ((key, value) in config) {
                        put(key, value)
                    }
                })
            }
        }
        configStorage.saveAll(root.toString())
    }

    // ---------- 按类型获取 ----------

    @Suppress("UNCHECKED_CAST")
    fun <T : Plugin> getPlugin(id: String): T? {
        return _plugins.value[id] as? T
    }

    /** 按名称查找插件（用于 getPluginInstance 兼容原项目） */
    fun getPluginByName(name: String): Plugin? {
        return _plugins.value.values.find { it.manifest.name == name || it.manifest.id == name }
    }

    fun getPluginsByCapability(capability: String): List<Plugin> {
        return _plugins.value.values.filter { capability in it.manifest.capabilities }
    }

    fun getAllPlugins(): List<Plugin> = _plugins.value.values.toList()
}