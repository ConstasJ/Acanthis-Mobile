package io.acanthis.app.core.platform

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.room.RoomDatabase
import io.acanthis.app.core.database.AppDatabase
import io.ktor.client.engine.HttpClientEngine

expect fun platformName(): String

expect fun defaultDeviceLabel(): String

expect fun currentDeviceKind(): String

expect fun currentTimeMillis(): Long

expect fun platformCacheDirectory(): String

expect fun createHttpClientEngine(): HttpClientEngine

expect fun createAppDatabaseBuilder(): RoomDatabase.Builder<AppDatabase>

expect fun createPreferencesDataStore(): DataStore<Preferences>
