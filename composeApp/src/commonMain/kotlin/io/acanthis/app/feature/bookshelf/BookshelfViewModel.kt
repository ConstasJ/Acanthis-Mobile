package io.acanthis.app.feature.bookshelf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.acanthis.app.core.database.BookshelfEntity
import io.acanthis.app.core.database.ReadingProgressEntity
import io.acanthis.app.core.network.rebaseMediaUrlWithBase
import io.acanthis.app.core.preferences.ServerConfigStore
import io.acanthis.app.data.LibraryRepository
import io.acanthis.app.data.SyncStateSnapshot
import io.acanthis.app.data.SyncStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BookshelfCardItem(
    val platform: String,
    val novelId: String,
    val title: String,
    val author: String,
    val coverUrl: String,
    val progressLabel: String?,
    val progressPercent: Int?,
    val updatedAt: Long,
)

data class ContinueReadingBanner(
    val platform: String,
    val novelId: String,
    val novelTitle: String,
    val coverUrl: String,
    val volumeTitle: String,
    val chapterId: String,
    val chapterTitle: String,
    val progressPercent: Int,
)

data class BookshelfUiState(
    val isConfigured: Boolean = false,
    val apiBaseUrl: String = "",
    val syncState: SyncStateSnapshot = SyncStateSnapshot(),
    val continueReading: ContinueReadingBanner? = null,
    val items: List<BookshelfCardItem> = emptyList(),
) {
    val isRefreshing: Boolean
        get() = syncState.status == SyncStatus.SYNCING
}

class BookshelfViewModel(
    private val libraryRepository: LibraryRepository,
    serverConfigStore: ServerConfigStore,
) : ViewModel() {

    val uiState: StateFlow<BookshelfUiState> = combine(
        libraryRepository.observeActiveBookshelf(),
        libraryRepository.observeAllProgress(),
        libraryRepository.observeContinueReading(),
        libraryRepository.syncState,
        serverConfigStore.configFlow,
    ) { bookshelfItems, progressList, latestProgress, syncSnapshot, serverConfig ->
        val progressByKey = progressList.associateBy { it.platform to it.novelId }
        val baseUrl = serverConfig.apiBaseUrl

        val cards = bookshelfItems.map { item ->
            val progress = progressByKey[item.platform to item.novelId]
            val percent = progress?.let(::computeProgressPercent)
            item.toCardItem(
                apiBaseUrl = baseUrl,
                progress = progress,
                progressPercent = percent,
            )
        }

        val banner = latestProgress?.let { progress ->
            ContinueReadingBanner(
                platform = progress.platform,
                novelId = progress.novelId,
                novelTitle = progress.novelTitle.ifBlank { "未命名小说" },
                coverUrl = rebaseMediaUrlWithBase(progress.coverUrl, baseUrl),
                volumeTitle = progress.volumeTitle,
                chapterId = progress.chapterId,
                chapterTitle = progress.chapterTitle.ifBlank { "继续阅读" },
                progressPercent = computeProgressPercent(progress),
            )
        }

        BookshelfUiState(
            isConfigured = serverConfig.isConfigured,
            apiBaseUrl = baseUrl,
            syncState = syncSnapshot,
            continueReading = banner,
            items = cards,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = BookshelfUiState(),
    )

    fun syncNow() {
        viewModelScope.launch {
            libraryRepository.syncNow()
        }
    }

    fun removeFromBookshelf(platform: String, novelId: String) {
        viewModelScope.launch {
            libraryRepository.removeFromBookshelf(platform, novelId)
        }
    }

    private fun BookshelfEntity.toCardItem(
        apiBaseUrl: String,
        progress: ReadingProgressEntity?,
        progressPercent: Int?,
    ): BookshelfCardItem {
        val label = when {
            progressPercent != null -> "$progressPercent%"
            progress != null && progress.chapterTitle.isNotBlank() -> progress.chapterTitle
            else -> null
        }
        return BookshelfCardItem(
            platform = platform,
            novelId = novelId,
            title = title,
            author = author,
            coverUrl = rebaseMediaUrlWithBase(coverUrl, apiBaseUrl),
            progressLabel = label,
            progressPercent = progressPercent,
            updatedAt = updatedAt,
        )
    }

    private fun computeProgressPercent(progress: ReadingProgressEntity): Int {
        if (progress.totalParagraphs <= 0) {
            return 0
        }
        val current = (progress.paragraphIndex.coerceAtLeast(0) + 1)
            .coerceAtMost(progress.totalParagraphs)
        return ((current * 100) / progress.totalParagraphs).coerceIn(0, 100)
    }
}
