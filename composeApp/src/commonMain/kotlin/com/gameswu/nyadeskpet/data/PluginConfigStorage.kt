package com.gameswu.nyadeskpet.data

import com.gameswu.nyadeskpet.PlatformContext

/**
 * 插件配置持久化存储 — 对齐原项目 config.json 持久化
 *
 * 原项目将每个插件的配置存储在 {userData}/data/agent-plugins/{pluginName}/config.json。
 * KMP 版本使用 SharedPreferences/NSUserDefaults 存储全部插件配置的 JSON 映射。
 */
expect class PluginConfigStorage(context: PlatformContext) {
    /**
     * 加载所有插件配置
     * @return JSON 字符串（Map<pluginId, Map<key, JsonElement>> 的序列化结果），无数据时返回 null
     */
    fun loadAll(): String?

    /**
     * 保存所有插件配置
     * @param data JSON 字符串（Map<pluginId, Map<key, JsonElement>> 的序列化结果）
     */
    fun saveAll(data: String)

    /**
     * 清除所有插件配置
     */
    fun clearAll()
}
