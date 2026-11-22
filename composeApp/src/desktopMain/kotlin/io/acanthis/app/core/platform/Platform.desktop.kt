package io.acanthis.app.core.platform

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.room.RoomDatabase
import io.acanthis.app.core.database.AppDatabase
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import okio.Path.Companion.toPath
import java.io.File

internal fun desktopDataDirectory(): File {
    val home = System.getProperty("user.home")?.takeIf { it.isNotBlank() } ?: "."
    return File(home, ".acanthis-desktop").apply { mkdirs() }
}

actual fun platformName(): String = "Desktop (${System.getProperty("os.name") ?: "JVM"})"

actual fun defaultDeviceLabel(): String {
    return System.getProperty("os.name")?.trim().orEmpty().ifBlank { "Desktop" }
}

actual fun currentDeviceKind(): String = DeviceKind.DESKTOP

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun platformCacheDirectory(): String {
    return desktopDataDirectory().resolve("cache").apply { mkdirs() }.absolutePath
}

actual fun createHttpClientEngine(): HttpClientEngine = OkHttp.create()

actual fun createAppDatabaseBuilder(): RoomDatabase.Builder<AppDatabase> {
    val dbFile = desktopDataDirectory().resolve("acanthis.db")
    return Room.databaseBuilder<AppDatabase>(
        name = dbFile.absolutePath,
    )
}

actual fun createPreferencesDataStore(): DataStore<Preferences> {
    return PreferenceDataStoreFactory.createWithPath(
        produceFile = {
            desktopDataDirectory().resolve("acanthis_prefs.preferences_pb").absolutePath.toPath()
        },
    )
}
