package io.acanthis.app.core.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.acanthis.app.core.network.stripTrailingV1Prefix
import io.acanthis.app.core.platform.defaultDeviceLabel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class ServerConfig(
    val apiBaseUrl: String = "",
    val authToken: String = "",
    val deviceLabel: String = defaultDeviceLabel(),
) {
    val isConfigured: Boolean
        get() = apiBaseUrl.isNotBlank()
}

class ServerConfigStore(
    private val dataStore: DataStore<Preferences>,
) {
    private object Keys {
        val API_BASE_URL = stringPreferencesKey("api_base_url")
        val AUTH_TOKEN = stringPreferencesKey("auth_token")
        val DEVICE_LABEL = stringPreferencesKey("device_label")
    }

    val configFlow: Flow<ServerConfig> = dataStore.data.map { prefs ->
        ServerConfig(
            apiBaseUrl = prefs[Keys.API_BASE_URL].orEmpty(),
            authToken = prefs[Keys.AUTH_TOKEN].orEmpty(),
            deviceLabel = prefs[Keys.DEVICE_LABEL]?.takeIf { it.isNotBlank() }
                ?: defaultDeviceLabel(),
        )
    }

    suspend fun save(
        apiBaseUrl: String,
        authToken: String,
        deviceLabel: String,
    ) {
        val cleanedUrl = stripTrailingV1Prefix(apiBaseUrl)
        val cleanedToken = authToken.trim()
        val cleanedDevice = deviceLabel.trim().ifEmpty { defaultDeviceLabel() }
        dataStore.edit { prefs ->
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
}
