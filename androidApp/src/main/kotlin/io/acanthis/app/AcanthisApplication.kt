package io.acanthis.app

import android.app.Application
import io.acanthis.app.app.di.appModule
import io.acanthis.app.core.platform.AndroidPlatformContext
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class AcanthisApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidPlatformContext.init(this)
        startKoin {
            androidContext(this@AcanthisApplication)
            modules(appModule)
        }
    }
}
