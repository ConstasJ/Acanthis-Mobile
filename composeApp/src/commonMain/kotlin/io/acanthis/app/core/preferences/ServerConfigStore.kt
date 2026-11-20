package io.acanthis.app.core.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.acanthis.app.core.network.stripTrailingV1Prefix
import io.acanthis.app.core.platform.defaultDeviceLabel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class ServerConfig(
    val apiBaseUrl: String = "",
    val authToken: String = "",
    val deviceLabel: String = defaultDeviceLabel(),
    val lastSyncCursor: Long = 0L,
) {
    val isConfigured: Boolean
        get() = apiBaseUrl.isNotBlank()
}

fun shouldResetSyncCursor(oldUrl: String, newUrl: String): Boolean {
    return stripTrailingV1Prefix(oldUrl) != stripTrailingV1Prefix(newUrl)
}

class ServerConfigStore(
    private val dataStore: DataStore<Preferences>,
) {
    private object Keys {
        val API_BASE_URL = stringPreferencesKey("api_base_url")
        val AUTH_TOKEN = stringPreferencesKey("auth_token")
        val DEVICE_LABEL = stringPreferencesKey("device_label")
        val LAST_SYNC_CURSOR = longPreferencesKey("last_sync_cursor")
    }

    val configFlow: Flow<ServerConfig> = dataStore.data.map { prefs ->
        ServerConfig(
            apiBaseUrl = prefs[Keys.API_BASE_URL].orEmpty(),
            authToken = prefs[Keys.AUTH_TOKEN].orEmpty(),
            deviceLabel = prefs[Keys.DEVICE_LABEL]?.takeIf { it.isNotBlank() }
                ?: defaultDeviceLabel(),
            lastSyncCursor = (prefs[Keys.LAST_SYNC_CURSOR] ?: 0L).coerceAtLeast(0L),
        )
    }

    suspend fun currentConfig(): ServerConfig = configFlow.first()

    suspend fun save(
        apiBaseUrl: String,
        authToken: String,
        deviceLabel: String,
    ) {
        val cleanedUrl = stripTrailingV1Prefix(apiBaseUrl)
        val cleanedToken = authToken.trim()
        val cleanedDevice = deviceLabel.trim().ifEmpty { defaultDeviceLabel() }
        dataStore.edit { prefs ->
            val previousUrl = prefs[Keys.API_BASE_URL].orEmpty()
            if (shouldResetSyncCursor(previousUrl, cleanedUrl)) {
                prefs[Keys.LAST_SYNC_CURSOR] = 0L
            }

            if (cleanedUrl.isEmpty()) {
                prefs.remove(Keys.API_BASE_URL)
            } else {
                prefs[Keys.API_BASE_URL] = cleanedUrl
            }
            if (cleanedToken.isEmpty()) {
                prefs.remove(Keys.AUTH_TOKEN)
            } else {
                prefs[Keys.AUTH_TOKEN] = cleanedToken
            }
            prefs[Keys.DEVICE_LABEL] = cleanedDevice
        }
    }

    suspend fun updateLastSyncCursor(cursor: Long) {
        val sanitized = cursor.coerceAtLeast(0L)
        dataStore.edit { prefs ->
            prefs[Keys.LAST_SYNC_CURSOR] = sanitized
        }
    }
}
