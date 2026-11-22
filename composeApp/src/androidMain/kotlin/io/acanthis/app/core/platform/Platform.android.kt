package io.acanthis.app.core.platform

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.room.RoomDatabase
import io.acanthis.app.core.database.AppDatabase
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import okio.Path.Companion.toPath

object AndroidPlatformContext {
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun requireContext(): Context {
        return checkNotNull(appContext) {
            "AndroidPlatformContext has not been initialized in Application.onCreate()"
        }
    }
}

actual fun platformName(): String = "Android ${Build.VERSION.RELEASE}"

actual fun defaultDeviceLabel(): String {
    return listOf(Build.MANUFACTURER, Build.MODEL)
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .ifBlank { "Android" }
}

actual fun currentDeviceKind(): String = DeviceKind.MOBILE

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun platformCacheDirectory(): String =
    AndroidPlatformContext.requireContext().cacheDir.absolutePath

actual fun createHttpClientEngine(): HttpClientEngine = OkHttp.create()

actual fun createAppDatabaseBuilder(): RoomDatabase.Builder<AppDatabase> {
    val context = AndroidPlatformContext.requireContext()
    val dbFile = context.getDatabasePath("acanthis.db")
    return Room.databaseBuilder<AppDatabase>(
        context = context,
        name = dbFile.absolutePath,
    )
}

actual fun createPreferencesDataStore(): DataStore<Preferences> {
    val context = AndroidPlatformContext.requireContext()
    return PreferenceDataStoreFactory.createWithPath(
        produceFile = {
            context.filesDir.resolve("acanthis_prefs.preferences_pb").absolutePath.toPath()
        },
    )
}
