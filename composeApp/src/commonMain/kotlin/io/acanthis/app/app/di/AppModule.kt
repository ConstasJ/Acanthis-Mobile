package io.acanthis.app.app.di

import io.acanthis.app.core.database.AppDatabase
import io.acanthis.app.core.database.buildAppDatabase
import io.acanthis.app.core.network.AcanthisApiClient
import io.acanthis.app.core.platform.createAppDatabaseBuilder
import io.acanthis.app.core.platform.createHttpClientEngine
import io.acanthis.app.core.platform.createPreferencesDataStore
import io.acanthis.app.core.preferences.ServerConfigStore
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
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

    single { AcanthisApiClient(httpClient = get(), json = get()) }

    single<AppDatabase> { buildAppDatabase(createAppDatabaseBuilder()) }

    single { createPreferencesDataStore() }

    single { ServerConfigStore(dataStore = get()) }
}
