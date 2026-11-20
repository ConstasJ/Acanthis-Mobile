package io.acanthis.app.feature.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.acanthis.app.core.database.ReadingProgressEntity
import io.acanthis.app.core.network.ApiException
import io.acanthis.app.core.network.ChapterDto
import io.acanthis.app.core.network.NovelDto
import io.acanthis.app.data.LibraryRepository
import io.acanthis.app.data.ReaderRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DisplayChapterItem(
    val itemKey: String,
    val chapter: ChapterDto,
    val isCurrentProgress: Boolean,
    val isCached: Boolean,
)

data class DisplayVolumeSection(
    val volumeKey: String,
    val headerKey: String,
    val title: String,
    val coverUrl: String?,
    val isExpanded: Boolean,
    val chapters: List<DisplayChapterItem>,
)

data class NovelDetailUiState(
    val platform: String,
    val novelId: String,
    val novel: NovelDto? = null,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val isInBookshelf: Boolean = false,
    val isTogglingBookshelf: Boolean = false,
    val progress: ReadingProgressEntity? = null,
    val cachedChapterIds: Set<String> = emptySet(),
    val isReversed: Boolean = false,
    val collapsedVolumeIds: Set<String> = emptySet(),
    val displayVolumes: List<DisplayVolumeSection> = emptyList(),
    val totalChapterCount: Int = 0,
    val cachedChapterCount: Int = 0,
    val firstChapterId: String? = null,
) {
    val hasProgress: Boolean
        get() = !progress?.chapterId.isNullOrBlank()

    val canStartReading: Boolean
        get() = hasProgress || !firstChapterId.isNullOrBlank()

    fun isVolumeExpanded(volumeId: String): Boolean = volumeId !in collapsedVolumeIds
}

private data class RefreshRequest(
    val token: Int = 0,
    val forceRefresh: Boolean = false,
)

private data class DetailViewControls(
    val isReversed: Boolean = false,
    val collapsedVolumeIds: Set<String> = emptySet(),
    val isTogglingBookshelf: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class NovelDetailViewModel(
    val platform: String,
    val novelId: String,
    private val readerRepository: ReaderRepository,
    private val libraryRepository: LibraryRepository,
) : ViewModel() {

    private val refreshTrigger = MutableStateFlow(RefreshRequest())
    private val controlsState = MutableStateFlow(DetailViewControls())

    private val detailFlow = refreshTrigger.flatMapLatest { request ->
        readerRepository.observeNovelDetail(
            platform = platform,
            novelId = novelId,
            forceRefresh = request.forceRefresh,
        )
    }

    val uiState: StateFlow<NovelDetailUiState> = combine(
        detailFlow,
        readerRepository.observeCachedChapterIds(platform, novelId),
        libraryRepository.observeIsInBookshelf(platform, novelId),
        libraryRepository.observeProgress(platform, novelId),
        controlsState,
    ) { detailState, cachedChapterIds, isInBookshelf, progress, controls ->
        val novel = detailState.novel
        val progressChapterId = progress?.chapterId?.trim()?.takeIf { it.isNotEmpty() }

        val firstChapterId = novel?.volumes?.firstNotNullOfOrNull { volume ->
            volume.chapters.firstOrNull { it.id.isNotBlank() }?.id
        }

        val totalChapterCount = novel?.volumes?.sumOf { it.chapters.size } ?: 0
        val cachedChapterCount = novel?.volumes?.sumOf { volume ->
            volume.chapters.count { it.id in cachedChapterIds }
        } ?: cachedChapterIds.size

        val indexedVolumes = novel?.volumes?.mapIndexed { volumeIndex, volume ->
            val volumeKey = volume.id.ifBlank { "volume_$volumeIndex" }
            val indexedChapters = volume.chapters.mapIndexed { chapterIndex, chapter ->
                DisplayChapterItem(
                    itemKey = "ch_${volumeKey}_${chapter.id}_${volumeIndex}_${chapterIndex}",
                    chapter = chapter,
                    isCurrentProgress = progressChapterId != null && chapter.id == progressChapterId,
                    isCached = chapter.id in cachedChapterIds,
                )
            }
            DisplayVolumeSection(
                volumeKey = volumeKey,
                headerKey = "vol_${volumeKey}_${volumeIndex}",
                title = volume.title,
                coverUrl = volume.coverUrl,
                isExpanded = volumeKey !in controls.collapsedVolumeIds,
                chapters = if (controls.isReversed) indexedChapters.reversed() else indexedChapters,
            )
        }.orEmpty()

        val orderedVolumes = if (controls.isReversed) indexedVolumes.reversed() else indexedVolumes

        NovelDetailUiState(
            platform = platform,
            novelId = novelId,
            novel = novel,
            isRefreshing = detailState.isRefreshing,
            errorMessage = detailState.error?.toFriendlyErrorMessage(),
            isInBookshelf = isInBookshelf,
            isTogglingBookshelf = controls.isTogglingBookshelf,
            progress = progress,
            cachedChapterIds = cachedChapterIds,
            isReversed = controls.isReversed,
            collapsedVolumeIds = controls.collapsedVolumeIds,
            displayVolumes = orderedVolumes,
            totalChapterCount = totalChapterCount,
            cachedChapterCount = cachedChapterCount,
            firstChapterId = firstChapterId,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = NovelDetailUiState(
            platform = platform,
            novelId = novelId,
            isRefreshing = true,
        ),
    )

    fun refresh() {
        refreshTrigger.update { current ->
            RefreshRequest(
                token = current.token + 1,
                forceRefresh = true,
            )
        }
    }

    fun toggleBookshelf() {
        val currentState = uiState.value
        if (currentState.isTogglingBookshelf) {
            return
        }
        val novel = currentState.novel
        val progress = currentState.progress
        if (novel == null && progress == null) {
            return
        }

        val title = novel?.title?.takeIf { it.isNotBlank() }
            ?: progress?.novelTitle?.takeIf { it.isNotBlank() }
            ?: novelId
        val author = novel?.author.orEmpty()
        val coverUrl = novel?.coverUrl?.takeIf { it.isNotBlank() }
            ?: progress?.coverUrl.orEmpty()

        viewModelScope.launch {
            controlsState.update { it.copy(isTogglingBookshelf = true) }
            try {
                libraryRepository.toggleBookshelf(
                    platform = platform,
                    novelId = novelId,
                    title = title,
                    author = author,
                    coverUrl = coverUrl,
                )
            } finally {
                controlsState.update { it.copy(isTogglingBookshelf = false) }
            }
        }
    }

    fun toggleReverseOrder() {
        controlsState.update { current ->
            current.copy(isReversed = !current.isReversed)
        }
    }

    fun toggleVolumeExpanded(volumeId: String) {
        controlsState.update { current ->
            val updated = if (volumeId in current.collapsedVolumeIds) {
                current.collapsedVolumeIds - volumeId
            } else {
                current.collapsedVolumeIds + volumeId
            }
            current.copy(collapsedVolumeIds = updated)
        }
    }

    fun resolveStartChapterId(): String? {
        val current = uiState.value
        val progressChapterId = current.progress?.chapterId?.trim()
        if (!progressChapterId.isNullOrEmpty()) {
            return progressChapterId
        }
        return current.novel?.volumes?.firstNotNullOfOrNull { volume ->
            volume.chapters.firstOrNull { it.id.isNotBlank() }?.id
        }
    }

    private fun Throwable.toFriendlyErrorMessage(): String {
        return when (this) {
            is ApiException -> {
                val retryHint = retryAfterSeconds?.let { "，请于 ${it} 秒后重试" }.orEmpty()
                when (statusCode) {
                    503 -> "源站触发保护或限流（HTTP 503）$retryHint"
                    401 -> "服务端鉴权失败（HTTP 401），请检查设置中的访问令牌"
                    404 -> "未找到该小说（HTTP 404）"
                    else -> "请求失败（HTTP $statusCode）：${message.ifBlank { "未知错误" }}$retryHint"
                }
            }
            else -> message?.takeIf { it.isNotBlank() } ?: "网络连接失败，请检查网络或服务器地址"
        }
    }
}
