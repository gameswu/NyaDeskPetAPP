package com.gameswu.nyadeskpet.di

import com.gameswu.nyadeskpet.audio.AudioStreamPlayer
import com.gameswu.nyadeskpet.data.ConversationStorage
import com.gameswu.nyadeskpet.data.LogManager
import com.gameswu.nyadeskpet.data.ModelDataManager
import com.gameswu.nyadeskpet.data.PluginConfigStorage
import com.gameswu.nyadeskpet.data.SettingsStorage
import com.gameswu.nyadeskpet.live2d.Live2DManager
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val androidModule = module {
    single { SettingsStorage(androidContext()) }
    single { ConversationStorage(androidContext()) }
    single { PluginConfigStorage(androidContext()) }
    single { ModelDataManager(androidContext()) }
    single { LogManager(androidContext(), get()) }
    single { Live2DManager(androidContext()) }
    single { AudioStreamPlayer(androidContext()) }
}
