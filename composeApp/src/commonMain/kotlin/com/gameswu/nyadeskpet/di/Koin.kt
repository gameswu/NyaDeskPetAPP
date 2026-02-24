package com.gameswu.nyadeskpet.di

import com.gameswu.nyadeskpet.agent.AgentClient
import com.gameswu.nyadeskpet.agent.BuiltinAgentService
import com.gameswu.nyadeskpet.agent.CharacterInfo
import com.gameswu.nyadeskpet.agent.ResponseController
import com.gameswu.nyadeskpet.data.ConversationManager
import com.gameswu.nyadeskpet.data.ModelDataManager
import com.gameswu.nyadeskpet.data.PluginConfigStorage
import com.gameswu.nyadeskpet.data.SettingsRepository
import com.gameswu.nyadeskpet.dialogue.DialogueManager
import com.gameswu.nyadeskpet.live2d.Live2DController
import com.gameswu.nyadeskpet.plugin.PluginManager
import com.gameswu.nyadeskpet.ui.chat.ChatViewModel
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val commonModule = module {
    // 数据层
    singleOf(::SettingsRepository)
    singleOf(::ConversationManager)

    // 插件系统（Tool / Panel / Widget / Command）— 注入 PluginConfigStorage 实现持久化
    single { PluginManager(get<PluginConfigStorage>()) }

    // 领域层与控制器
    singleOf(::ResponseController)
    singleOf(::Live2DController)
    singleOf(::DialogueManager)

    // 内置 Agent 服务（Provider 实例管理 + LLM 调用）
    singleOf(::BuiltinAgentService)

    // AgentClient（依赖 ResponseController, SettingsRepository, BuiltinAgentService）
    singleOf(::AgentClient)

    // UI 层
    viewModelOf(::ChatViewModel)
}

/**
 * 在所有依赖注入就绪后调用此方法，完成联动注入和连接。
 * 在 Application.onCreate 或 App() 中调用。
 */
fun org.koin.core.KoinApplication.setupWiring() {
    val koin = this.koin
    val rc: ResponseController = koin.get()
    rc.audioPlayer = koin.get()
    rc.dialogueManager = koin.get()

    val agentClient: AgentClient = koin.get()
    val settingsRepo: SettingsRepository = koin.get()
    val modelDataManager: ModelDataManager = koin.get()
    val builtinAgentService: BuiltinAgentService = koin.get()

    // 首次启动时将内置模型复制到数据目录
    modelDataManager.ensureBuiltinModels()

    // 初始化插件系统，注册所有内置 Agent 插件
    builtinAgentService.initializePlugins()

    agentClient.onConnectedCallback = {
        val settings = settingsRepo.current
        agentClient.sendCharacterInfo(
            CharacterInfo(
                useCustom = settings.useCustomCharacter,
                name = settings.customName.takeIf { it.isNotBlank() },
                personality = settings.customPersonality.takeIf { it.isNotBlank() }
            )
        )
    }

    // 内置模式下，应用启动时自动连接
    if (settingsRepo.current.backendMode == "builtin") {
        agentClient.connect(settingsRepo.current.wsUrl)
    }
}