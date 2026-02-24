package com.gameswu.nyadeskpet

import android.app.Application
import com.gameswu.nyadeskpet.di.androidModule
import com.gameswu.nyadeskpet.di.commonModule
import com.gameswu.nyadeskpet.di.setupWiring
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class NyaDeskPetApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger()
            androidContext(this@NyaDeskPetApp)
            modules(commonModule, androidModule)
            setupWiring()
        }
    }
}
