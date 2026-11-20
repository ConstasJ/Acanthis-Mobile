package io.acanthis.app.app.di

import io.acanthis.app.core.database.AppDatabase
import io.acanthis.app.core.database.buildAppDatabase
import io.acanthis.app.core.media.CoilCacheController
import io.acanthis.app.core.media.DefaultCoilCacheController
import io.acanthis.app.core.network.AcanthisApi
import io.acanthis.app.core.network.AcanthisApiClient
import io.acanthis.app.core.platform.createAppDatabaseBuilder
import io.acanthis.app.core.platform.createHttpClientEngine
import io.acanthis.app.core.platform.createPreferencesDataStore
import io.acanthis.app.core.preferences.ReaderSettingsStore
import io.acanthis.app.core.preferences.ServerConfigStore
import io.acanthis.app.data.ExploreRepository
import io.acanthis.app.data.LibraryLocalStore
import io.acanthis.app.data.LibraryRepository
import io.acanthis.app.data.ReaderLocalStore
import io.acanthis.app.data.ReaderRepository
import io.acanthis.app.data.RoomLibraryLocalStore
import io.acanthis.app.data.RoomReaderLocalStore
import io.acanthis.app.data.StoreSyncConfigProvider
import io.acanthis.app.data.SyncConfigProvider
import io.acanthis.app.data.SyncCoordinator
import io.acanthis.app.feature.bookshelf.BookshelfViewModel
import io.acanthis.app.feature.explore.ExploreViewModel
import io.acanthis.app.feature.settings.SettingsViewModel
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.bind
import org.koin.dsl.module

val appModule = module {
    single {
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }

    single {
        val json = get<Json>()
        HttpClient(createHttpClientEngine()) {
            install(ContentNegotiation) {
                json(json)
            }
        }
    }

    single { AcanthisApiClient(httpClient = get(), json = get()) } bind AcanthisApi::class

    single { DefaultCoilCacheController(httpClient = get()) } bind CoilCacheController::class

    single<AppDatabase> { buildAppDatabase(createAppDatabaseBuilder()) }
    single { get<AppDatabase>().bookshelfDao() }
    single { get<AppDatabase>().readingProgressDao() }
    single { get<AppDatabase>().dirtySyncDao() }
    single { get<AppDatabase>().novelCacheDao() }
    single { get<AppDatabase>().chapterCacheDao() }

    single { createPreferencesDataStore() }
    single { ServerConfigStore(dataStore = get()) }
    single { ReaderSettingsStore(dataStore = get()) }

    single<LibraryLocalStore> {
        RoomLibraryLocalStore(
            bookshelfDao = get(),
            readingProgressDao = get(),
            dirtySyncDao = get(),
        )
    }

    single<SyncConfigProvider> {
        StoreSyncConfigProvider(serverConfigStore = get())
    }

    single {
        SyncCoordinator(
            api = get(),
            localStore = get(),
            configProvider = get(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }

    single {
        LibraryRepository(
            localStore = get(),
            syncCoordinator = get(),
            configProvider = get(),
        )
    }

    single<ReaderLocalStore> {
        RoomReaderLocalStore(
            novelCacheDao = get(),
            chapterCacheDao = get(),
        )
    }

    single {
        ExploreRepository(
            api = get(),
            serverConfigStore = get(),
        )
    }

    single {
        ReaderRepository(
            api = get(),
            localStore = get(),
            serverConfigStore = get(),
            json = get(),
        )
    }

    viewModelOf(::BookshelfViewModel)
    viewModelOf(::ExploreViewModel)
    viewModelOf(::SettingsViewModel)
}
