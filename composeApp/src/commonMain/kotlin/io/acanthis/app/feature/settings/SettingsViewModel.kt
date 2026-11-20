package io.acanthis.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.acanthis.app.core.media.CoilCacheController
import io.acanthis.app.core.network.AcanthisApi
import io.acanthis.app.core.network.ApiException
import io.acanthis.app.core.network.NovelSourceDescriptor
import io.acanthis.app.core.preferences.ServerConfig
import io.acanthis.app.core.preferences.ServerConfigStore
import io.acanthis.app.data.CacheStats
import io.acanthis.app.data.LibraryRepository
import io.acanthis.app.data.ReaderRepository
import io.acanthis.app.data.SyncStateSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ConnectionTestState {
    data object Idle : ConnectionTestState
    data object Testing : ConnectionTestState
    data class Success(
        val serverTimestamp: String,
        val sources: List<NovelSourceDescriptor>,
    ) : ConnectionTestState

    data class Failure(val message: String) : ConnectionTestState
}

data class SettingsUiState(
    val serverConfig: ServerConfig = ServerConfig(),
    val syncState: SyncStateSnapshot = SyncStateSnapshot(),
    val pendingDirtyCount: Int = 0,
    val chapterCacheStats: CacheStats = CacheStats(chapterCount = 0, chapterBytes = 0L),
    val imageCacheBytes: Long = 0L,
    val isClearingCache: Boolean = false,
    val connectionTestState: ConnectionTestState = ConnectionTestState.Idle,
    val feedbackMessage: String? = null,
)

class SettingsViewModel(
    private val serverConfigStore: ServerConfigStore,
    private val api: AcanthisApi,
    private val libraryRepository: LibraryRepository,
    private val readerRepository: ReaderRepository,
    private val coilCacheController: CoilCacheController,
) : ViewModel() {

    private val imageCacheBytes = MutableStateFlow(0L)
    private val isClearingCache = MutableStateFlow(false)
    private val connectionTestState = MutableStateFlow<ConnectionTestState>(ConnectionTestState.Idle)
    private val feedbackMessage = MutableStateFlow<String?>(null)

    private val localActionState = combine(
        imageCacheBytes,
        isClearingCache,
        connectionTestState,
        feedbackMessage,
    ) { imageBytes, clearing, testState, feedback ->
        LocalSettingsActions(
            imageCacheBytes = imageBytes,
            isClearingCache = clearing,
            connectionTestState = testState,
            feedbackMessage = feedback,
        )
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        serverConfigStore.configFlow,
        libraryRepository.syncState,
        libraryRepository.observeDirtyCount(),
        readerRepository.observeCacheStats(),
        localActionState,
    ) { serverConfig, syncState, dirtyCount, chapterStats, actions ->
        SettingsUiState(
            serverConfig = serverConfig,
            syncState = syncState,
            pendingDirtyCount = dirtyCount,
            chapterCacheStats = chapterStats,
            imageCacheBytes = actions.imageCacheBytes,
            isClearingCache = actions.isClearingCache,
            connectionTestState = actions.connectionTestState,
            feedbackMessage = actions.feedbackMessage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = SettingsUiState(),
    )

    init {
        refreshImageCacheSize()
    }

    fun saveConfig(
        apiBaseUrl: String,
        authToken: String,
        deviceLabel: String,
    ) {
        viewModelScope.launch {
            serverConfigStore.save(
                apiBaseUrl = apiBaseUrl,
                authToken = authToken,
                deviceLabel = deviceLabel,
            )
            feedbackMessage.value = "配置已保存到本地 DataStore"
        }
    }

    fun testConnection(
        apiBaseUrl: String,
        authToken: String,
        deviceLabel: String,
    ) {
        if (connectionTestState.value is ConnectionTestState.Testing) {
            return
        }
        viewModelScope.launch {
            connectionTestState.value = ConnectionTestState.Testing
            feedbackMessage.value = null
            val targetUrl = apiBaseUrl.trim()
            val targetToken = authToken.trim()
            if (targetUrl.isEmpty()) {
                connectionTestState.value = ConnectionTestState.Failure("请先填写 API 服务器地址")
                return@launch
            }
            connectionTestState.value = try {
                val health = api.checkHealth(targetUrl)
                val sources = api.fetchSources(targetUrl, targetToken.ifBlank { null })
                serverConfigStore.save(
                    apiBaseUrl = targetUrl,
                    authToken = targetToken,
                    deviceLabel = deviceLabel,
                )
                ConnectionTestState.Success(
                    serverTimestamp = health.timestamp,
                    sources = sources,
                )
            } catch (e: ApiException) {
                ConnectionTestState.Failure(
                    "接口错误 HTTP ${e.statusCode} (code=${e.apiCode}): ${e.message}",
                )
            } catch (e: Exception) {
                ConnectionTestState.Failure(
                    e.message ?: "连接失败，请检查地址或网络状态",
                )
            }
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            libraryRepository.syncNow()
        }
    }

    fun refreshImageCacheSize() {
        viewModelScope.launch {
            val sizeBytes = withContext(Dispatchers.IO) {
                coilCacheController.diskCacheSizeBytes()
            }
            imageCacheBytes.value = sizeBytes
        }
    }

    fun clearReadingCachesOnly() {
        if (isClearingCache.value) return
        viewModelScope.launch {
            isClearingCache.value = true
            try {
                readerRepository.clearReadingCaches()
                feedbackMessage.value = "已清理正文与目录缓存"
            } finally {
                isClearingCache.value = false
                refreshImageCacheSize()
            }
        }
    }

    fun clearAllCaches() {
        if (isClearingCache.value) return
        viewModelScope.launch {
            isClearingCache.value = true
            try {
                readerRepository.clearReadingCaches()
                coilCacheController.clearDiskCache()
                feedbackMessage.value = "已清理全部本地缓存（含图片）"
            } finally {
                isClearingCache.value = false
                refreshImageCacheSize()
            }
        }
    }

    fun clearFeedback() {
        feedbackMessage.value = null
    }

    private data class LocalSettingsActions(
        val imageCacheBytes: Long,
        val isClearingCache: Boolean,
        val connectionTestState: ConnectionTestState,
        val feedbackMessage: String?,
    )
}
