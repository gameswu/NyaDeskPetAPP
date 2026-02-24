package com.gameswu.nyadeskpet.data

import com.gameswu.nyadeskpet.agent.provider.ProviderInstanceConfig
import com.gameswu.nyadeskpet.agent.provider.TTSProviderInstanceConfig
import kotlinx.coroutines.flow.*
import kotlinx.serialization.*

@Serializable
data class AppSettings(
    // 模型
    val modelPath: String = "models/live2d/mao_pro_zh/runtime/mao_pro.model3.json",

    // 连接
    val backendMode: String = "builtin",       // "builtin" | "custom"
    val agentPort: Int = 8765,
    val backendUrl: String = "http://localhost:8000",
    val wsUrl: String = "ws://localhost:8000/ws",
    val autoConnect: Boolean = true,

    // 显示
    val volume: Float = 0.8f,
    val locale: String = "zh-CN",
    val theme: String = "system",              // "system" | "light" | "dark"
    val showSubtitle: Boolean = true,

    // 角色
    val useCustomCharacter: Boolean = false,
    val customName: String = "",
    val customPersonality: String = "",

    // Live2D 交互
    val tapConfigs: Map<String, Map<String, TapAreaConfig>> = emptyMap(),

    // ===== LLM Provider 实例（对齐原项目 providers.json）=====
    val llmProviderInstances: List<ProviderInstanceConfig> = emptyList(),
    /** 主 LLM 实例 ID */
    val primaryLlmInstanceId: String = "",
    val llmSystemPrompt: String = "",
    val llmTemperature: Float = 0.7f,
    val llmMaxTokens: Int = 2048,
    val llmMaxHistory: Int = 20,
    val llmStream: Boolean = true,

    // ===== TTS Provider 实例 =====
    val ttsProviderInstances: List<TTSProviderInstanceConfig> = emptyList(),
    val primaryTtsInstanceId: String = "",

    // 麦克风 / ASR
    val micBackgroundMode: Boolean = false,
    val micVolumeThreshold: Int = 30,
    val micAutoSend: Boolean = true,
    val asrModel: String = "sense-voice-small",

    // 日志
    val logEnabled: Boolean = false,
    val logLevels: List<String> = listOf("warn", "error", "critical"),
    val logRetentionDays: Int = 7,

    // 其他
    val updateSource: String = "https://github.com/gameswu/NyaDeskPetAPP",
    val autoLaunch: Boolean = false,
)

@Serializable
data class TapAreaConfig(
    val enabled: Boolean = true,
    val description: String = "",
    val expression: String = "",
    val motion: String = "",
)

expect class SettingsStorage {
    fun load(): AppSettings
    fun save(settings: AppSettings)
}

class SettingsRepository(private val storage: SettingsStorage) {
    private val _settings = MutableStateFlow(storage.load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val current: AppSettings get() = _settings.value

    fun update(transform: (AppSettings) -> AppSettings) {
        val new = transform(_settings.value)
        _settings.value = new
        storage.save(new)
    }

    fun resetToDefaults() {
        val default = AppSettings()
        _settings.value = default
        storage.save(default)
    }
}