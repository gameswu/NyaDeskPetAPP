package com.gameswu.nyadeskpet.di

import com.gameswu.nyadeskpet.PlatformContext
import com.gameswu.nyadeskpet.audio.AudioStreamPlayer
import com.gameswu.nyadeskpet.data.ConversationStorage
import com.gameswu.nyadeskpet.data.ModelDataManager
import com.gameswu.nyadeskpet.data.PluginConfigStorage
import com.gameswu.nyadeskpet.data.SettingsStorage
import com.gameswu.nyadeskpet.live2d.Live2DManager
import org.koin.dsl.module

val iosModule = module {
    val context = PlatformContext()
    single { SettingsStorage(context) }
    single { ConversationStorage(context) }
    single { PluginConfigStorage(context) }
    single { ModelDataManager(context) }
    single { Live2DManager(context) }
    single { AudioStreamPlayer(context) }
}
